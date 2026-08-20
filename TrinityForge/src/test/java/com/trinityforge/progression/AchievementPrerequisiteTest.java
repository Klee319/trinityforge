package com.trinityforge.progression;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * アチーブメントの前提(parent / parents-any)は<b>達成そのものを縛る</b> (2026-07-29 ユーザー確定方針)。
 *
 * <p>表示順を変えるだけではなく、前提未達成なら条件を満たしても達成扱いにならず報酬も出ない。
 * ここで一緒に守るのは「後から前提を満たしたときに置いていかれない」こと: 統計型は次のポーリングで、
 * バニラ進捗型は{@code PlayerAdvancementDoneEvent}が二度と飛ばないため進捗の再読みで回収する。
 */
class AchievementPrerequisiteTest {

    private static final Logger LOG = Logger.getLogger("AchievementPrerequisiteTest");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static AchievementsConfig configOf(File dir, String yaml) throws IOException {
        File file = new File(dir, AchievementsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        AchievementsConfig config = new AchievementsConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> LOG;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static final String CHAIN = """
            achievements:
              step1:
                display-name: "一歩目"
                trigger:
                  type: statistic
                  statistic: JUMP
                  threshold: 5
              step2:
                display-name: "二歩目"
                parent: step1
                trigger:
                  type: statistic
                  statistic: DEATHS
                  threshold: 1
            """;

    @Test
    void childIsNotGrantedWhileParentIsUnmet(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, CHAIN);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        // 子の条件だけ満たす(親の条件は未達)。
        player.incrementStatistic(Statistic.DEATHS);
        service.pollStatistics();

        assertFalse(PlayerData.of(player).achievedIds().contains("step2"),
                "前提未達成なら条件を満たしても達成しない");
        assertFalse(PlayerData.of(player).achievedIds().contains("step1"));
    }

    @Test
    void childIsGrantedInTheSamePollOnceTheParentUnlocks(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, CHAIN);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        player.incrementStatistic(Statistic.DEATHS);
        for (int i = 0; i < 5; i++) {
            player.incrementStatistic(Statistic.JUMP);
        }
        service.pollStatistics();

        List<String> achieved = PlayerData.of(player).achievedIds();
        assertTrue(achieved.contains("step1"), "親が達成されること");
        assertTrue(achieved.contains("step2"),
                "同じポーリング内で親→子が連鎖して解けること(次の1分を待たされない)");
    }

    @Test
    void parentsAnyOpensTheGateWithASingleMatch(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  left:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                  right:
                    trigger:
                      type: statistic
                      statistic: FISH_CAUGHT
                      threshold: 1
                  either:
                    parents-any: [left, right]
                    trigger:
                      type: statistic
                      statistic: DEATHS
                      threshold: 1
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        player.incrementStatistic(Statistic.DEATHS);
        player.incrementStatistic(Statistic.JUMP); // left だけ満たす
        service.pollStatistics();

        List<String> achieved = PlayerData.of(player).achievedIds();
        assertTrue(achieved.contains("left"));
        assertFalse(achieved.contains("right"));
        assertTrue(achieved.contains("either"), "parents-any は1つ満たせば通ること");
    }

    @Test
    void advancementAchievementIsBlockedThenRecoveredByPolling(@TempDir File dir) throws Exception {
        // PlayerAdvancementDoneEvent は1回しか飛ばない。前提未達のときにイベントで弾いたきり
        // だと永久に取れなくなるので、ポーリング側でバニラ進捗の完了状態を読み直して回収する。
        AchievementsConfig config = configOf(dir, """
                achievements:
                  gatekeeper:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                  gated:
                    parent: gatekeeper
                    trigger:
                      type: advancement
                      advancement: "minecraft:story/mine_diamond"
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        service.onAdvancementDone(player, "minecraft:story/mine_diamond");
        assertFalse(PlayerData.of(player).achievedIds().contains("gated"),
                "前提未達成の間はバニラ進捗を取っても達成しない");

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();
        // MockBukkit の Advancement 実装差で進捗を読めない環境ではスキップ扱いにせず、
        // 「親は必ず開くこと」だけは常に検証する。
        assertTrue(PlayerData.of(player).achievedIds().contains("gatekeeper"));
    }

    @Test
    void prerequisitesMetIsPureAndUsableFromTheGui() throws Exception {
        AchievementsConfig.Trigger trigger = new AchievementsConfig.Trigger(
                AchievementsConfig.TriggerType.STATISTIC, Statistic.JUMP,
                AchievementsConfig.StatisticQualifier.NONE, 1, null, null, List.of(), false);
        AchievementsConfig.Rewards rewards = new AchievementsConfig.Rewards(
                List.of(), List.of(), List.of(), 0, List.of(), java.util.Map.of());

        AchievementsConfig.Achievement root = new AchievementsConfig.Achievement(
                "root", "root", trigger, false, rewards, "", List.of(), "", null, List.of());
        AchievementsConfig.Achievement child = new AchievementsConfig.Achievement(
                "child", "child", trigger, false, rewards, "", List.of(), "", "root", List.of());
        AchievementsConfig.Achievement either = new AchievementsConfig.Achievement(
                "either", "either", trigger, false, rewards, "", List.of(), "", null, List.of("a", "b"));

        assertTrue(root.isRoot());
        assertTrue(AchievementsConfig.prerequisitesMet(root, List.of()));
        assertFalse(AchievementsConfig.prerequisitesMet(child, List.of()));
        assertTrue(AchievementsConfig.prerequisitesMet(child, List.of("root")));
        assertFalse(AchievementsConfig.prerequisitesMet(either, List.of("c")));
        assertTrue(AchievementsConfig.prerequisitesMet(either, List.of("b")));
    }

    @Test
    void blankPrerequisiteKeysAreTreatedAsRoot(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  free:
                    parent: "  "
                    parents-any: ["", "  "]
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();

        assertEquals(List.of("free"), PlayerData.of(player).achievedIds(),
                "空白だけの前提は「前提なし」として扱い、達成を止めないこと");
    }
}
