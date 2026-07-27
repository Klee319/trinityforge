package com.trinityforge.progression;

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
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SpecialRewardPruner}: special-rewards.yml から消えたIDを、プレイヤーのPDC保持分
 * (直接付与リスト/装備欄)からも取り除く動作の検証(2026-07-28)。
 */
class SpecialRewardPrunerTest {

    private static final Logger LOG = Logger.getLogger("SpecialRewardPrunerTest");

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

    private static SpecialRewardsConfig configWith(File dir, String extraTopLevelYaml) throws IOException {
        String yaml = """
                %s
                titles:
                  dragon-slayer:
                    display: "<red>test</red>"
                particles:
                  crit-aura:
                    particle: CRIT
                particle-seeds: {}
                """.formatted(extraTopLevelYaml == null ? "" : extraTopLevelYaml);
        File file = new File(dir, SpecialRewardsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        SpecialRewardsConfig config = new SpecialRewardsConfig();
        config.load(fakePlugin(dir));
        return config;
    }

    // ---- orphanedIds: pure helper -------------------------------------------------------------

    @Test
    void orphanedIds_keepsOnlyIdsUnknownToConfig() {
        List<String> orphaned = SpecialRewardPruner.orphanedIds(
                List.of("dragon-slayer", "old-reward", "crit-aura"),
                id -> id.equals("dragon-slayer") || id.equals("crit-aura"));
        assertEquals(List.of("old-reward"), orphaned);
    }

    @Test
    void orphanedIds_emptyWhenAllKnown() {
        List<String> orphaned = SpecialRewardPruner.orphanedIds(List.of("a", "b"), id -> true);
        assertTrue(orphaned.isEmpty());
    }

    // ---- prune(Player): only undefined ids are removed, defined ids stay ----------------------

    @Test
    void prune_removesOnlyUndefinedGrantedIds(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, null);
        SpecialRewardPruner pruner = new SpecialRewardPruner(config);
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);
        data.grantSpecialReward("dragon-slayer"); // 定義済み
        data.grantSpecialReward("old-reward");     // 未定義(configから消えた想定)

        SpecialRewardPruner.PruneResult result = pruner.prune(player);

        assertEquals(List.of("old-reward"), result.revokedGrants());
        assertTrue(result.clearedEquipped().isEmpty());
        assertEquals(List.of("dragon-slayer"), data.unlockedSpecialRewards());
    }

    @Test
    void prune_leavesEverythingWhenNothingOrphaned(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, null);
        SpecialRewardPruner pruner = new SpecialRewardPruner(config);
        Player player = server.addPlayer();
        PlayerData.of(player).grantSpecialReward("dragon-slayer");

        SpecialRewardPruner.PruneResult result = pruner.prune(player);

        assertTrue(result.isEmpty());
        assertEquals(List.of("dragon-slayer"), PlayerData.of(player).unlockedSpecialRewards());
    }

    // ---- equipped-only orphan: reward:<id> perk-held cosmetic never enters the grant list -----

    @Test
    void prune_clearsEquippedTitleThatIsNoLongerKnown(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, null);
        SpecialRewardPruner pruner = new SpecialRewardPruner(config);
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);
        // スキルツリー reward:<id> perk 経由で装備した想定: 直接付与リストには入らない。
        data.setEquippedTitle("removed-title");

        SpecialRewardPruner.PruneResult result = pruner.prune(player);

        assertEquals(List.of("removed-title"), result.clearedEquipped());
        assertTrue(data.equippedTitle().isEmpty());
    }

    @Test
    void prune_clearsEquippedParticleThatIsNoLongerKnown(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, null);
        SpecialRewardPruner pruner = new SpecialRewardPruner(config);
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);
        data.setEquippedParticle("removed-particle");

        SpecialRewardPruner.PruneResult result = pruner.prune(player);

        assertEquals(List.of("removed-particle"), result.clearedEquipped());
        assertTrue(data.equippedParticle().isEmpty());
    }

    @Test
    void prune_leavesKnownEquippedTitleAlone(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, null);
        SpecialRewardPruner pruner = new SpecialRewardPruner(config);
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);
        data.grantSpecialReward("dragon-slayer");
        data.setEquippedTitle("dragon-slayer");

        SpecialRewardPruner.PruneResult result = pruner.prune(player);

        assertTrue(result.isEmpty());
        assertEquals("dragon-slayer", data.equippedTitle().orElseThrow());
    }

    // ---- safety valves: nothing is pruned when disabled, or when the last load failed ---------

    @Test
    void prune_doesNothingWhenPruneOrphanedGrantsIsFalse(@TempDir File dir) throws Exception {
        SpecialRewardsConfig config = configWith(dir, "prune-orphaned-grants: false");
        SpecialRewardPruner pruner = new SpecialRewardPruner(config);
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);
        data.grantSpecialReward("old-reward");
        data.setEquippedTitle("removed-title");

        SpecialRewardPruner.PruneResult result = pruner.prune(player);

        assertTrue(result.isEmpty());
        assertEquals(List.of("old-reward"), data.unlockedSpecialRewards());
        assertEquals("removed-title", data.equippedTitle().orElseThrow());
    }

    @Test
    void prune_doesNothingWhenLastLoadFailed(@TempDir File dir) throws Exception {
        // 破損エントリで load() が false を返した状態(=lastLoadOk()==false)を再現する。
        File file = new File(dir, SpecialRewardsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                titles:
                  broken: {}
                particles: {}
                particle-seeds: {}
                """);
        SpecialRewardsConfig config = new SpecialRewardsConfig();
        config.load(fakePlugin(dir));
        assertFalse(config.lastLoadOk(), "precondition: load() must have failed");
        assertTrue(config.pruneOrphanedGrants(), "precondition: the yml-level toggle stays true/default");

        SpecialRewardPruner pruner = new SpecialRewardPruner(config);
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);
        data.grantSpecialReward("old-reward");

        SpecialRewardPruner.PruneResult result = pruner.prune(player);

        assertTrue(result.isEmpty());
        assertEquals(List.of("old-reward"), data.unlockedSpecialRewards());
    }
}
