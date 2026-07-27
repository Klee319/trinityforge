package com.trinityforge.progression;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.SpecialRewardsConfig;
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
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特殊報酬の保有/装備判定 (2026-07-23-stat-gate-overhaul §6.1): 未保有IDの装備は拒否され、
 * 直接付与(達成/図鑑ティア経由を模した {@link PlayerData#grantSpecialReward}) を受けたIDのみ装備できる。
 */
class SpecialRewardServiceTest {

    private static final Logger LOG = Logger.getLogger("SpecialRewardServiceTest");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static SpecialRewardsConfig configWith(File dir, String titleId, String particleId) throws IOException {
        String yaml = """
                titles:
                  %s:
                    display: "<red>test</red>"
                particles:
                  %s:
                    particle: FLAME
                """.formatted(titleId, particleId);
        File file = new File(dir, SpecialRewardsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        SpecialRewardsConfig config = new SpecialRewardsConfig();
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
    void equipRejectsUnlockedButUndefinedOrUnownedId(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, "dragon-slayer", "crit-aura");
        SpecialRewardService service = new SpecialRewardService(config, new DedicatedEffectsConfig());
        Player player = server.addPlayer();

        assertFalse(service.equipTitle(player, "dragon-slayer"), "未保有なので拒否");
        assertFalse(service.equipTitle(player, "not-defined"), "未定義IDも拒否");
        assertTrue(PlayerData.of(player).equippedTitle().isEmpty());
    }

    @Test
    void equipSucceedsAfterDirectGrant(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, "dragon-slayer", "crit-aura");
        SpecialRewardService service = new SpecialRewardService(config, new DedicatedEffectsConfig());
        Player player = server.addPlayer();

        PlayerData.of(player).grantSpecialReward("dragon-slayer");
        assertTrue(service.isUnlocked(player, "dragon-slayer"));
        assertTrue(service.equipTitle(player, "dragon-slayer"));
        assertEquals("dragon-slayer", PlayerData.of(player).equippedTitle().orElseThrow());
        assertEquals("<red>test</red>", service.equippedTitleDisplay(player).orElseThrow());

        // nullで解除できる。
        assertTrue(service.equipTitle(player, null));
        assertTrue(PlayerData.of(player).equippedTitle().isEmpty());
    }

    @Test
    void equipParticleFollowsSameUnlockRule(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, "dragon-slayer", "crit-aura");
        SpecialRewardService service = new SpecialRewardService(config, new DedicatedEffectsConfig());
        Player player = server.addPlayer();

        assertFalse(service.equipParticle(player, "crit-aura"));
        PlayerData.of(player).grantSpecialReward("crit-aura");
        assertTrue(service.equipParticle(player, "crit-aura"));
        assertTrue(service.equippedParticleEffect(player).isPresent());
    }

    @Test
    void setBasedIsUnlockedOverloadNeedsNoPlayer(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, "dragon-slayer", "crit-aura");
        SpecialRewardService service = new SpecialRewardService(config, new DedicatedEffectsConfig());

        assertFalse(service.isUnlocked(Set.of(), Set.of(), "dragon-slayer"));
        assertTrue(service.isUnlocked(Set.of(), Set.of("dragon-slayer"), "dragon-slayer"));
    }
}
