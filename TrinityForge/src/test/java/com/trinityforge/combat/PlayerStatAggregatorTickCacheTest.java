package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * CMB-30 (2026-07-25): {@link PlayerStatAggregator#aggregate} previously re-ran the full item/perk/
 * role/permanent/base-stats resolution on every call, even though real hot paths call it 3-6x for the
 * same player within one server tick (CombatListener x2, NativeCombatPerkListener, PerkAttributeApplier
 * polling). This test locks down the per-tick memo cache added to fix that: identical results within a
 * tick, only one real resolution per distinct (player, item, offhand-flag) key, a fresh resolution once
 * the tick advances, and no cross-player leakage.
 */
class PlayerStatAggregatorTickCacheTest {

    /**
     * profileFor 呼び出し回数は「実解決が走ったか / キャッシュから返ったか」の代理指標。1 回の実解決あたり
     * 非 air アイテム 1 個につき 3 回呼ばれる（装着専用スロットゲート / resolve / resolveMultipliers）。
     * この定数はゲートの実装都合であって、テストが守りたいのは「実解決の回数」の方。
     */
    private static final int RESOLVE_PROFILE_LOOKUPS = 3;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void writeItemStats(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 10.0 }
                """);
    }

    /**
     * @return the aggregator under test, plus the spy wrapping the REAL loaded {@link ItemStatsConfig}
     *         so {@code profileFor} invocations (one per {@code DerivedItemStats.resolve} call) can be
     *         counted as a proxy for "the full resolution actually ran" vs. "served from cache".
     */
    private ItemStatsConfig spiedItemStats;

    private PlayerStatAggregator aggregator(File dir) throws IOException {
        writeItemStats(dir);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, "");
        spiedItemStats = spy(cm.itemStats());
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        return new PlayerStatAggregator(spiedItemStats, damage, perks, new RoleBuffResolver(cm.roleBuffs()));
    }

    @Test
    void sameTickReturnsIdenticalResultAndResolvesOnlyOnce(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir);
        Player player = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        player.getInventory().setItemInMainHand(sword);

        PlayerCombatAggregate first = aggregator.aggregate(player, sword);
        PlayerCombatAggregate second = aggregator.aggregate(player, sword);

        assertSame(first, second, "same tick + same key must return the cached instance, not a fresh one");
        assertEquals(10.0, first.mainhand().get("attack_power"), 1e-9);
        // profileFor is called 3x per real resolution of the mainhand sword: once from
        // PlayerStatAggregator.socketedOnly (the "装着専用(スレッド)" slot gate added 2026-08-14, which
        // must read the item's profile to decide whether the slot contributes at all), once from
        // DerivedItemStats.resolve, once from DerivedItemStats.resolveMultipliers. The sword is the
        // only non-air item this player has, so exactly 3 total calls proves the second aggregate()
        // was served entirely from cache instead of re-resolving.
        verify(spiedItemStats, times(RESOLVE_PROFILE_LOOKUPS)).profileFor(any(), any());
    }

    @Test
    void tickAdvanceProducesFreshResolution(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir);
        Player player = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        player.getInventory().setItemInMainHand(sword);

        PlayerCombatAggregate first = aggregator.aggregate(player, sword);
        server.getScheduler().performTicks(1);
        PlayerCombatAggregate second = aggregator.aggregate(player, sword);

        assertNotSame(first, second, "advancing the tick must invalidate the memo and force a fresh resolve");
        assertEquals(first.mainhand(), second.mainhand(), "the resolved VALUE is unchanged, only re-computed");
        // RESOLVE_PROFILE_LOOKUPS calls per real resolution (see
        // sameTickReturnsIdenticalResultAndResolvesOnlyOnce) x 2 real resolutions (before and after
        // the tick advance).
        verify(spiedItemStats, times(2 * RESOLVE_PROFILE_LOOKUPS)).profileFor(any(), any());
    }

    @Test
    void differentPlayersDoNotShareACacheEntry(@TempDir File dir) throws IOException {
        PlayerStatAggregator aggregator = aggregator(dir);
        Player playerA = server.addPlayer();
        Player playerB = server.addPlayer();
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        playerA.getInventory().setItemInMainHand(sword);
        playerB.getInventory().setItemInMainHand(sword);

        PlayerCombatAggregate resultA = aggregator.aggregate(playerA, sword);
        PlayerCombatAggregate resultB = aggregator.aggregate(playerB, sword);

        assertNotSame(resultA, resultB, "distinct players must not share a cache entry even with an equal item");
        // Two distinct players -> two real resolutions in the same tick (xRESOLVE_PROFILE_LOOKUPS
        // profileFor calls each, see sameTickReturnsIdenticalResultAndResolvesOnlyOnce), even though
        // the item is equal.
        verify(spiedItemStats, times(2 * RESOLVE_PROFILE_LOOKUPS)).profileFor(any(), any());
    }
}
