package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import com.trinityforge.stats.QualityRollModel;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装備 PDC のスレッド欄からステをライブ合算すること。フォークの addon 書き込みが空でも
 * {@code /tf status} と同じ {@link PlayerCombatAggregate#combined()} に会心・詠唱効率が乗る。
 */
class SocketedThreadStatsTest {

    private static final NamespacedKey THREAD_SLOTS = new NamespacedKey("arspaper", "thread_slots");
    private static final String CRIT = StatKeys.canonical("crit-chance");
    private static final String MANA_COST = StatKeys.canonical("mana-cost-reduction-percent");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        SocketedThreadStats.replaceCatalogIndexForTest(null);
    }

    @AfterEach
    void tearDown() {
        SocketedThreadStats.replaceCatalogIndexForTest(null);
        MockBukkit.unmock();
    }

    @Test
    void parseStringListReadsQuotedIds() {
        assertEquals(List.of("backpack", "assassin"),
                SocketedThreadStats.parseStringList("[\"backpack\",\"assassin\"]"));
        assertEquals(List.of(), SocketedThreadStats.parseStringList("[]"));
        assertEquals(List.of(), SocketedThreadStats.parseStringList(null));
    }

    @Test
    void parseRollReadsQualityAndSeed() {
        assertEquals(12L, SocketedThreadStats.parseRoll("12:3")[0]);
        assertEquals(3L, SocketedThreadStats.parseRoll("12:3")[1]);
        assertEquals(7L, SocketedThreadStats.parseRoll("7")[0]);
        assertEquals(0L, SocketedThreadStats.parseRoll("nope")[0]);
    }

    @Test
    @DisplayName("暗殺者スレッドを挿した胸当ては addon が空でも会心率が combined に乗る")
    void socketedCritThreadAppearsInCombinedWhenAddonIsEmpty(@TempDir File dir) {
        PlayerStatAggregator aggregator = aggregator(dir);
        Player player = server.addPlayer();
        player.getInventory().setChestplate(socketedChest("[\"assassin\"]"));

        double crit = aggregator.aggregate(player).combined().getOrDefault(CRIT, 0.0);
        assertTrue(crit > 0.0, "live socket collect must contribute crit-chance, got " + crit);
    }

    @Test
    @DisplayName("詠唱効率スレッドは mana-cost-reduction-percent が combined に乗る")
    void socketedSpellCostThreadAppearsInCombined(@TempDir File dir) {
        PlayerStatAggregator aggregator = aggregator(dir);
        Player player = server.addPlayer();
        player.getInventory().setChestplate(socketedChest("[\"spell_cost_down\"]"));

        double mana = aggregator.aggregate(player).combined().getOrDefault(MANA_COST, 0.0);
        assertTrue(mana > 0.0, "live socket collect must contribute mana-cost-reduction-percent, got " + mana);
    }

    @Test
    @DisplayName("フォークが addon に書いた値はライブ合算で上書きしない(セット効果を残す)")
    void addonValueWinsOverLiveSocket(@TempDir File dir) {
        PlayerStatAggregator aggregator = aggregator(dir);
        Player player = server.addPlayer();
        player.getInventory().setChestplate(socketedChest("[\"assassin\"]"));
        player.getPersistentDataContainer().set(
                PdcKeys.PLAYER_ADDON_COMBAT_STATS,
                PersistentDataType.STRING,
                AddonCombatStats.encode(Map.of(CRIT, 0.99)));

        assertEquals(0.99, aggregator.aggregate(player).combined().getOrDefault(CRIT, 0.0), 1e-9);
    }

    private static PlayerStatAggregator aggregator(File dir) {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        cm.itemStats().useRollModel(() -> new QualityRollModel(15, 0.22, 0.22, 0.1));
        CombatDamageConfig damage = new CombatDamageConfig();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, List::of);
        return new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
    }

    private static ItemStack socketedChest(String slotsJson) {
        ItemStack chest = new ItemStack(Material.IRON_CHESTPLATE);
        chest.editMeta(meta -> meta.getPersistentDataContainer()
                .set(THREAD_SLOTS, PersistentDataType.STRING, slotsJson));
        return chest;
    }
}
