package com.trinityforge.skilltree.runtime;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-08-24 ユーザー指示「レアなバニラドロップにもドロップ増加ステを効かせる(落ちなかった分を再抽選)」。
 *
 * <p>個数加算({@code onDeathDrops})は「既に落ちたスタックを増やす」仕組みなので、
 * ウィザースケルトンの頭のように<b>抽選に外れると 0 個で現れない</b>ドロップには
 * 構造的に効かない。loot table を引き直して<b>落ちなかった種類だけ</b>を足すのが
 * {@code onDeathRerollAbsentDrops}。
 */
class NativeSurvivalPerkRareDropRerollTest {

    private static final String MOB_DROP_BONUS = StatKeys.canonical("mob_drop_bonus");

    private ServerMock server;
    private WorldMock world;
    private NativeSurvivalPerkListener listener;
    private Player killer;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        killer = server.addPlayer("killer");

        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        // +100% = MobDropRoller.extraCount が確定で1を返す(乱数に依存しない)。
        PlayerCombatAggregate totals = new PlayerCombatAggregate(
                Map.of(MOB_DROP_BONUS, 1.0), Map.of(), Map.of(), Map.of(), Map.of());
        when(aggregator.aggregate(any(Player.class))).thenReturn(totals);
        listener = new NativeSurvivalPerkListener(aggregator);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static org.bukkit.damage.DamageSource genericSource() {
        return org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
    }

    /** 常に同じ中身を返す loot table。実 loot table は MockBukkit に無いので差し替える。 */
    private static LootTable fixedTable(ItemStack... contents) {
        return new LootTable() {
            @Override
            public Collection<ItemStack> populateLoot(Random random, LootContext context) {
                List<ItemStack> loot = new ArrayList<>();
                for (ItemStack stack : contents) {
                    loot.add(stack.clone());
                }
                return loot;
            }

            @Override
            public void fillInventory(Inventory inventory, Random random, LootContext context) {
                throw new UnsupportedOperationException("使わない");
            }

            @Override
            public NamespacedKey getKey() {
                return NamespacedKey.minecraft("test_table");
            }
        };
    }

    /**
     * PDC だけは実物が要る({@code MobData.of} が読む)ので MockBukkit のゾンビから借りる。
     * それ以外(loot table / killer / 座標)は Mockito で与える。
     */
    private Zombie lootableMob(LootTable table) {
        Zombie pdcSource = world.spawn(world.getSpawnLocation(), Zombie.class);
        Zombie entity = mock(Zombie.class);
        when(entity.getKiller()).thenReturn(killer);
        when(entity.getLocation()).thenReturn(world.getSpawnLocation());
        when(entity.getPersistentDataContainer()).thenReturn(pdcSource.getPersistentDataContainer());
        when(entity.hasLootTable()).thenReturn(table != null);
        when(entity.getLootTable()).thenReturn(table);
        return entity;
    }

    @Test
    void rerollAddsOnlyTheTypesThatDidNotDrop() {
        Zombie entity = lootableMob(fixedTable(
                new ItemStack(Material.ROTTEN_FLESH, 1),
                new ItemStack(Material.WITHER_SKELETON_SKULL, 1)));
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.ROTTEN_FLESH, 2)));

        listener.onDeathRerollAbsentDrops(new EntityDeathEvent(entity, genericSource(), drops));

        assertEquals(2, drops.size(), "落ちなかった種類(頭)だけが1つ足されること");
        assertEquals(Material.ROTTEN_FLESH, drops.get(0).getType());
        assertEquals(2, drops.get(0).getAmount(), "既に落ちた種類は個数加算側の担当なのでここでは触らない");
        assertEquals(Material.WITHER_SKELETON_SKULL, drops.get(1).getType());
    }

    @Test
    void rerollAddsNothingWhenEveryTypeAlreadyDropped() {
        Zombie entity = lootableMob(fixedTable(new ItemStack(Material.ROTTEN_FLESH, 1)));
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.ROTTEN_FLESH, 2)));

        listener.onDeathRerollAbsentDrops(new EntityDeathEvent(entity, genericSource(), drops));

        assertEquals(1, drops.size(), "同じ種類を二重に足さないこと");
        assertEquals(2, drops.get(0).getAmount());
    }

    @Test
    void dropBonusOfZeroNeverRerolls() {
        PlayerStatAggregator noBonus = mock(PlayerStatAggregator.class);
        when(noBonus.aggregate(any(Player.class))).thenReturn(new PlayerCombatAggregate(
                Map.of(MOB_DROP_BONUS, 0.0), Map.of(), Map.of(), Map.of(), Map.of()));
        NativeSurvivalPerkListener plain = new NativeSurvivalPerkListener(noBonus);
        Zombie entity = lootableMob(fixedTable(new ItemStack(Material.WITHER_SKELETON_SKULL, 1)));
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.ROTTEN_FLESH, 2)));

        plain.onDeathRerollAbsentDrops(new EntityDeathEvent(entity, genericSource(), drops));

        assertEquals(1, drops.size(), "ドロップ増加ステが無いプレイヤーはバニラのままであること");
    }

    /**
     * EliteMobs 由来のモブは対象外。取り込んだモブの戦利品の可否は EliteMobs 側が決めているので、
     * TF が loot table を引き直すとその判断を無効化してしまう。
     */
    @Test
    void eliteMobsOwnedEntityIsNeverRerolled() {
        Zombie entity = lootableMob(fixedTable(new ItemStack(Material.WITHER_SKELETON_SKULL, 1)));
        entity.getPersistentDataContainer()
                .set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, "elite_zombie");
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.ROTTEN_FLESH, 2)));

        listener.onDeathRerollAbsentDrops(new EntityDeathEvent(entity, genericSource(), drops));

        assertEquals(1, drops.size(), "EliteMobs のモブでは引き直さないこと");
    }

    @Test
    void mobWithoutKillerIsNeverRerolled() {
        Zombie entity = lootableMob(fixedTable(new ItemStack(Material.WITHER_SKELETON_SKULL, 1)));
        when(entity.getKiller()).thenReturn(null);
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.ROTTEN_FLESH, 2)));

        listener.onDeathRerollAbsentDrops(new EntityDeathEvent(entity, genericSource(), drops));

        assertEquals(1, drops.size(), "自然死(キル者なし)では引き直さないこと");
    }

    /**
     * loot table を引けない実装で例外が外へ出ないこと。ここで投げると
     * {@code EntityDeathEvent} の残りのハンドラが丸ごと落ちる(EXP付与などまで消える)。
     */
    @Test
    void unsupportedLootTableDoesNotBreakTheDeathEvent() {
        Zombie pdcSource = world.spawn(world.getSpawnLocation(), Zombie.class);
        Zombie entity = mock(Zombie.class);
        when(entity.getKiller()).thenReturn(killer);
        when(entity.getLocation()).thenReturn(world.getSpawnLocation());
        when(entity.getPersistentDataContainer()).thenReturn(pdcSource.getPersistentDataContainer());
        when(entity.hasLootTable()).thenThrow(new UnsupportedOperationException("未実装"));
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.ROTTEN_FLESH, 2)));

        listener.onDeathRerollAbsentDrops(new EntityDeathEvent(entity, genericSource(), drops));

        assertEquals(1, drops.size());
    }

    // --- 判定そのもの(純関数) ---

    @Test
    void absentTypesFiltersPresentTypesAndDeduplicatesWithinTheReroll() {
        List<ItemStack> existing = List.of(new ItemStack(Material.ROTTEN_FLESH, 2));
        List<ItemStack> rerolled = List.of(
                new ItemStack(Material.ROTTEN_FLESH, 1),
                new ItemStack(Material.WITHER_SKELETON_SKULL, 1),
                new ItemStack(Material.WITHER_SKELETON_SKULL, 1),
                new ItemStack(Material.IRON_INGOT, 1));

        List<ItemStack> added = NativeSurvivalPerkListener.absentTypes(existing, rerolled);

        assertEquals(2, added.size(), "既出は除き、引き直しの中の重複も潰すこと");
        assertEquals(Material.WITHER_SKELETON_SKULL, added.get(0).getType());
        assertEquals(Material.IRON_INGOT, added.get(1).getType());
    }

    @Test
    void absentTypesSkipsEmptyStacks() {
        List<ItemStack> added = NativeSurvivalPerkListener.absentTypes(
                List.of(), List.of(new ItemStack(Material.AIR, 1)));

        assertTrue(added.isEmpty(), "空気は足さないこと");
    }
}
