package com.trinityforge.progression;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.pdc.PlayerData;
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
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 永続ステータスバフ(rewards.permanent-buffs)の都度再計算リゾルバ: 達成済み/解放済みの
 * エントリのみが合算対象になり、未達成/未解放は寄与しないこと・reload後の再合算が
 * その場で反映されること(状態を焼き込まない)を検証する。
 */
class PermanentBuffResolverTest {

    private static final Logger LOG = Logger.getLogger("PermanentBuffResolverTest");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
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

    private static AchievementsConfig achievementsOf(File dir, String yaml) throws IOException {
        File file = new File(dir, AchievementsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        AchievementsConfig config = new AchievementsConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static CollectionConfig collectionOf(File dir, String yaml) throws IOException {
        File file = new File(dir, CollectionConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CollectionConfig config = new CollectionConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    private static CollectionConfig emptyCollection(File dir) throws IOException {
        return collectionOf(dir, "enabled: true");
    }

    private static AchievementsConfig emptyAchievements(File dir) throws IOException {
        return achievementsOf(dir, "achievements: {}");
    }

    @Test
    void onlyClaimedAchievementBuffsAreIncluded(@TempDir File dir) throws Exception {
        // 2026-08-04 手動解放方式: permanent-buffsは報酬なので、条件成立(achieved)だけでは
        // 寄与せず、解放済み(claimed)になって初めて合算対象になる。
        AchievementsConfig achievements = achievementsOf(dir, """
                achievements:
                  jump-king:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      permanent-buffs:
                        attack-power: 5
                        move-speed: 0.02
                  unearned:
                    trigger: { type: statistic, statistic: JUMP, threshold: 999 }
                    rewards:
                      permanent-buffs:
                        attack-power: 100
                """);
        CollectionConfig collection = emptyCollection(dir);
        PermanentBuffResolver resolver = new PermanentBuffResolver(achievements, collection);
        Player player = server.addPlayer();

        assertTrue(resolver.buffsFor(player).isEmpty(), "未達成時は空");

        PlayerData.of(player).markAchieved("jump-king");
        assertTrue(resolver.buffsFor(player).isEmpty(), "達成しただけ(未解放)では寄与しない");

        PlayerData.of(player).markAchievementClaimed("jump-king");
        Map<String, Double> buffs = resolver.buffsFor(player);
        assertEquals(5.0, buffs.get("attack_power"));
        assertEquals(0.02, buffs.get("move_speed"));
        assertEquals(2, buffs.size(), "未解放のunearnedは寄与しない");
    }

    @Test
    void onlyClaimedCollectionTierBuffsAreIncluded(@TempDir File dir) throws Exception {
        AchievementsConfig achievements = emptyAchievements(dir);
        CollectionConfig collection = collectionOf(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 1
                    permanent-buffs:
                      flat-defense: 3
                  gold:
                    threshold: 999
                    permanent-buffs:
                      flat-defense: 30
                """);
        PermanentBuffResolver resolver = new PermanentBuffResolver(achievements, collection);
        Player player = server.addPlayer();

        assertTrue(resolver.buffsFor(player).isEmpty());

        PlayerData.of(player).setClaimedCollectionTiers(List.of("bronze"));
        Map<String, Double> buffs = resolver.buffsFor(player);
        assertEquals(3.0, buffs.get("flat_defense"));
        assertEquals(1, buffs.size(), "未解放のgoldは寄与しない");
    }

    @Test
    void achievementAndCollectionBuffsMergeByCanonicalKey(@TempDir File dir) throws Exception {
        AchievementsConfig achievements = achievementsOf(dir, """
                achievements:
                  a:
                    trigger: { type: statistic, statistic: JUMP, threshold: 1 }
                    rewards:
                      permanent-buffs:
                        attack-power: 5
                """);
        CollectionConfig collection = collectionOf(dir, """
                enabled: true
                reward-tiers:
                  bronze:
                    threshold: 1
                    permanent-buffs:
                      attack_power: 2.5
                """);
        PermanentBuffResolver resolver = new PermanentBuffResolver(achievements, collection);
        Player player = server.addPlayer();
        PlayerData.of(player).markAchieved("a");
        PlayerData.of(player).markAchievementClaimed("a");
        PlayerData.of(player).setClaimedCollectionTiers(List.of("bronze"));

        Map<String, Double> buffs = resolver.buffsFor(player);
        assertEquals(7.5, buffs.get("attack_power"), "kebab/snake双方が同一canonicalキーへ加算合成される");
    }

    @Test
    void nullPlayerYieldsEmptyMap(@TempDir File dir) throws Exception {
        PermanentBuffResolver resolver = new PermanentBuffResolver(emptyAchievements(dir), emptyCollection(dir));
        assertTrue(resolver.buffsFor(null).isEmpty());
    }
}
