package com.trinityforge.progression;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
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
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * アチーブメント達成判定/報酬付与 (2026-07-23-stat-gate-overhaul §6.2): statistic型は閾値到達で1回だけ
 * 達成扱いになり、advancement型はキー一致でのみ達成すること。
 *
 * <p>2026-08-04 手動解放方式への変更: {@code pollStatistics}/{@code onAdvancementDone} は条件成立
 * (achievedIds)を記録するだけで<b>報酬は付与しない</b>。報酬は {@link AchievementService#claim} を
 * 呼んで初めて付与される。
 */
class AchievementServiceTest {

    private static final Logger LOG = Logger.getLogger("AchievementServiceTest");

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

    @Test
    void statisticAchievementGrantsOnceThresholdReached(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  jump-king:
                    display-name: "ジャンプ王"
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 5
                    rewards:
                      commands: ["say %player% did it"]
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        service.pollStatistics();
        assertFalse(PlayerData.of(player).achievedIds().contains("jump-king"), "未到達では達成しない");

        for (int i = 0; i < 5; i++) {
            player.incrementStatistic(Statistic.JUMP);
        }
        service.pollStatistics();
        assertTrue(PlayerData.of(player).achievedIds().contains("jump-king"));

        // 再ポーリングしても再達成/再付与しない(冪等)。
        List<String> before = PlayerData.of(player).achievedIds();
        service.pollStatistics();
        assertEquals(before, PlayerData.of(player).achievedIds());
    }

    @Test
    void advancementAchievementOnlyGrantsOnMatchingKey(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  diamond:
                    display-name: "採掘者"
                    trigger:
                      type: advancement
                      advancement: "minecraft:story/mine_diamond"
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        service.onAdvancementDone(player, "minecraft:story/mine_iron");
        assertFalse(PlayerData.of(player).achievedIds().contains("diamond"));

        service.onAdvancementDone(player, "minecraft:story/mine_diamond");
        assertTrue(PlayerData.of(player).achievedIds().contains("diamond"));
    }

    @Test
    void pollingAloneDoesNotGrantRewards(@TempDir File dir) throws Exception {
        // 2026-08-04 手動解放方式: 条件成立(pollStatistics)だけでは報酬は一切付かない。
        AchievementsConfig config = configOf(dir, """
                achievements:
                  full-reward:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                    rewards:
                      vanilla-exp: 100
                      job-exp:
                        - skill: MINING
                          amount: 500.0
                      items:
                        - id: diamond
                          amount: 3
                """);
        CrossPluginItemResolver itemResolver = mock(CrossPluginItemResolver.class);
        when(itemResolver.create("diamond")).thenReturn(Optional.of(new ItemStack(Material.DIAMOND)));
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        AchievementService service = new AchievementService(config, LOG, itemResolver, dispatcher);
        Player player = server.addPlayer();
        int expBefore = player.getTotalExperience();

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();

        assertTrue(PlayerData.of(player).achievedIds().contains("full-reward"), "条件成立=達成扱い");
        assertFalse(PlayerData.of(player).claimedAchievementIds().contains("full-reward"),
                "解放(claim)していないので未解放のまま");
        assertEquals(expBefore, player.getTotalExperience(), "解放前はvanilla-expが付与されない");
        verify(dispatcher, org.mockito.Mockito.never()).grant(any(), any(), anyDouble());
        assertTrue(player.getInventory().isEmpty(), "解放前はアイテムも付与されない");
    }

    @Test
    void claimGrantsItemsVanillaExpAndJobExpOnlyAfterExplicitClaim(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  full-reward:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                    rewards:
                      vanilla-exp: 100
                      job-exp:
                        - skill: MINING
                          amount: 500.0
                      items:
                        - id: diamond
                          amount: 3
                """);
        CrossPluginItemResolver itemResolver = mock(CrossPluginItemResolver.class);
        when(itemResolver.create("diamond")).thenReturn(Optional.of(new ItemStack(Material.DIAMOND)));
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        AchievementService service = new AchievementService(config, LOG, itemResolver, dispatcher);
        Player player = server.addPlayer();
        int expBefore = player.getTotalExperience();

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();

        AchievementService.ClaimResult result = service.claim(player, "full-reward");

        assertEquals(AchievementService.ClaimResult.CLAIMED, result);
        assertTrue(PlayerData.of(player).claimedAchievementIds().contains("full-reward"));
        assertTrue(player.getTotalExperience() > expBefore, "解放後にvanilla-expがgiveExpされること");
        verify(dispatcher).grant(player.getUniqueId(), "MINING", 500.0);
        ItemStack found = null;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.DIAMOND) {
                found = stack;
                break;
            }
        }
        assertTrue(found != null && found.getAmount() == 3, "amount指定通りに付与されること");
    }

    @Test
    void claimingTwiceGrantsRewardOnlyOnce(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  full-reward:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                    rewards:
                      vanilla-exp: 100
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();
        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();

        AchievementService.ClaimResult first = service.claim(player, "full-reward");
        int expAfterFirstClaim = player.getTotalExperience();
        AchievementService.ClaimResult second = service.claim(player, "full-reward");

        assertEquals(AchievementService.ClaimResult.CLAIMED, first);
        assertEquals(AchievementService.ClaimResult.ALREADY_CLAIMED, second);
        assertEquals(expAfterFirstClaim, player.getTotalExperience(), "2回目の解放でEXPが再付与されないこと");
    }

    @Test
    void claimBeforeAchievingIsRejected(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, """
                achievements:
                  full-reward:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 5
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        AchievementService.ClaimResult result = service.claim(player, "full-reward");

        assertEquals(AchievementService.ClaimResult.NOT_ACHIEVED, result);
        assertFalse(PlayerData.of(player).claimedAchievementIds().contains("full-reward"));
    }

    @Test
    void claimOfUnknownIdIsRejected(@TempDir File dir) throws Exception {
        AchievementsConfig config = configOf(dir, "achievements: {}");
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();

        assertEquals(AchievementService.ClaimResult.UNKNOWN_ACHIEVEMENT,
                service.claim(player, "does-not-exist"));
    }

    @Test
    void skipsItemAndJobExpRewardsWhenDependenciesNotInjected(@TempDir File dir) throws Exception {
        // 2引数コンストラクタ(既存テスト互換): itemResolver/experienceDispatcherがnullでも
        // 例外にならず、単に該当報酬をスキップすること(解放後の付与についても同様)。
        AchievementsConfig config = configOf(dir, """
                achievements:
                  full-reward:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                    rewards:
                      items:
                        - id: diamond
                      job-exp:
                        - skill: MINING
                          amount: 10
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();
        player.incrementStatistic(Statistic.JUMP);

        service.pollStatistics();
        service.claim(player, "full-reward");

        assertTrue(PlayerData.of(player).claimedAchievementIds().contains("full-reward"));
        assertTrue(player.getInventory().isEmpty(), "itemResolver未注入ならアイテムは付与されない");
    }

    @Test
    void claimCallsPerkAttributeApplierApplyWhenInjected(@TempDir File dir) throws Exception {
        // 修正A: ATTRIBUTE系永続バフ(move_speed等)は次回join/防具変更まで反映されないため、
        // 解放成功後に perkAttributeApplier.apply(player) が即座に呼ばれること。
        AchievementsConfig config = configOf(dir, """
                achievements:
                  attr-reward:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                    rewards:
                      permanent-buffs:
                        move-speed: 0.02
                """);
        PerkAttributeApplier applier = mock(PerkAttributeApplier.class);
        AchievementService service = new AchievementService(config, LOG, null, null, applier);
        Player player = server.addPlayer();

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();
        org.mockito.Mockito.verifyNoInteractions(applier); // 達成しただけでは呼ばれないこと

        service.claim(player, "attr-reward");

        assertTrue(PlayerData.of(player).claimedAchievementIds().contains("attr-reward"));
        verify(applier).apply(player);
    }

    @Test
    void claimDoesNotThrowWhenPerkAttributeApplierNotInjected(@TempDir File dir) throws Exception {
        // 既存の4引数コンストラクタ(perkAttributeApplier=null)は例外にならず単にスキップすること。
        AchievementsConfig config = configOf(dir, """
                achievements:
                  attr-reward:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                    rewards:
                      permanent-buffs:
                        move-speed: 0.02
                """);
        AchievementService service = new AchievementService(config, LOG, null, null);
        Player player = server.addPlayer();

        player.incrementStatistic(Statistic.JUMP);
        service.pollStatistics();
        service.claim(player, "attr-reward");

        assertTrue(PlayerData.of(player).claimedAchievementIds().contains("attr-reward"));
    }

    @Test
    void migrationCopiesPreExistingAchievementsToClaimedWithoutRegrantingRewards(@TempDir File dir) throws Exception {
        // 2026-08-04 移行: 手動解放方式の導入前に達成済みだったプレイヤーは既に報酬を受け取っている
        // ため、claim()を経由しない移行コピーでは報酬(vanilla-exp等)が再付与されてはいけない。
        AchievementsConfig config = configOf(dir, """
                achievements:
                  legacy-done:
                    trigger:
                      type: statistic
                      statistic: JUMP
                      threshold: 1
                    rewards:
                      vanilla-exp: 100
                """);
        AchievementService service = new AchievementService(config, LOG);
        Player player = server.addPlayer();
        // 「導入前に達成済みだった」を模擬: PDCへ直接 achievedIds のみ書き込む(claimedは未設定)。
        PlayerData.of(player).markAchieved("legacy-done");
        int expBefore = player.getTotalExperience();

        // claimedIds() を呼ぶだけで移行が走る(GUI表示経路と同じ)。
        List<String> claimed = service.claimedIds(player);

        assertTrue(claimed.contains("legacy-done"), "移行でachieved→claimedへコピーされること");
        assertEquals(expBefore, player.getTotalExperience(), "移行コピーでは報酬が再付与されないこと");

        // 移行後にclaim()しても、既にclaimed済みなのでALREADY_CLAIMEDになり、やはり再付与されない。
        AchievementService.ClaimResult result = service.claim(player, "legacy-done");
        assertEquals(AchievementService.ClaimResult.ALREADY_CLAIMED, result);
        assertEquals(expBefore, player.getTotalExperience());
    }
}
