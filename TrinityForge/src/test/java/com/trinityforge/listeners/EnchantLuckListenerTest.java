package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.EnchantLuckConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EnchantLuckListener}: enchant_luck stat による {@link EnchantItemEvent} 結果の格上げ補正。
 */
class EnchantLuckListenerTest {

    private ServerMock server;
    private PlayerMock player;
    private PlayerStatAggregator aggregator;
    private EnchantLuckConfig config;
    private DedicatedEffectsConfig dedicatedEffects;
    private CraftingFeaturesConfig craftingFeatures;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        aggregator = mock(PlayerStatAggregator.class);
        config = mock(EnchantLuckConfig.class);
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        craftingFeatures = mock(CraftingFeaturesConfig.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private EnchantItemEvent newEvent(Map<Enchantment, Integer> toAdd) {
        InventoryView view = player.openInventory(
                server.createInventory(player, org.bukkit.event.inventory.InventoryType.ENCHANTING));
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        return new EnchantItemEvent(player, view, block, item, 30, toAdd,
                Enchantment.SHARPNESS, 2, 0);
    }

    private void stubAggregateLuck(double luck) {
        PlayerCombatAggregate totals = mock(PlayerCombatAggregate.class);
        when(totals.totalOf("enchant_luck")).thenReturn(luck);
        when(aggregator.aggregate(player)).thenReturn(totals);
    }

    @Test
    void zeroLuckLeavesResultUntouched() {
        stubAggregateLuck(0.0);
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 1);
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertEquals(1, event.getEnchantsToAdd().get(Enchantment.SHARPNESS));
    }

    @Test
    void luckAlwaysBoostsLevelWhenChanceIsCertain() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(1.0); // 100% per luck point -> deterministic
        when(config.levelBoostMaxSteps()).thenReturn(2);
        when(config.overenchantBonusChancePerLuck()).thenReturn(0.0);
        when(config.extraEnchantChancePerLuck()).thenReturn(0.0);
        // Sharpness vanilla max is 5; start at 3 so two guaranteed +1 steps land within the vanilla cap.
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 3);
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertEquals(5, event.getEnchantsToAdd().get(Enchantment.SHARPNESS),
                "guaranteed boost should reach the vanilla cap (5) in 2 steps");
    }

    @Test
    void levelBoostStopsAtVanillaCapWithoutOverenchantUnlock() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(1.0);
        when(config.levelBoostMaxSteps()).thenReturn(5);
        when(config.overenchantBonusChancePerLuck()).thenReturn(1.0); // would always boost IF unlocked
        when(config.extraEnchantChancePerLuck()).thenReturn(0.0);
        when(craftingFeatures.overEnchantMaxLevel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Enchantment.SHARPNESS)))
                .thenReturn(0); // not unlocked -> overEnchantMaxLevel <= vanillaMax
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 5); // already at vanilla cap
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertEquals(5, event.getEnchantsToAdd().get(Enchantment.SHARPNESS),
                "without overenchant unlock, must not exceed vanilla max");
    }

    @Test
    void overenchantUnlockAllowsBoostPastVanillaCap() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(1.0);
        when(config.levelBoostMaxSteps()).thenReturn(1);
        when(config.overenchantBonusChancePerLuck()).thenReturn(1.0); // certain when unlocked
        when(config.extraEnchantChancePerLuck()).thenReturn(0.0);
        when(craftingFeatures.overEnchantMaxLevel(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(Enchantment.SHARPNESS)))
                .thenReturn(7); // unlocked profile raises the absolute cap above vanilla (5)
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 5); // already at vanilla cap
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertEquals(6, event.getEnchantsToAdd().get(Enchantment.SHARPNESS),
                "overenchant-unlocked player should boost past vanilla cap");
    }

    @Test
    void extraEnchantChanceAddsOneCompatibleEnchantWhenCertain() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(0.0);
        when(config.levelBoostMaxSteps()).thenReturn(0);
        when(config.overenchantBonusChancePerLuck()).thenReturn(0.0);
        when(config.extraEnchantChancePerLuck()).thenReturn(1.0); // certain extra grant
        Map<Enchantment, Integer> toAdd = new HashMap<>();
        toAdd.put(Enchantment.SHARPNESS, 1);
        EnchantLuckListener listener = new EnchantLuckListener(
                aggregator, config, dedicatedEffects, craftingFeatures, new Random(1));

        EnchantItemEvent event = newEvent(toAdd);
        listener.onEnchant(event);

        assertTrue(event.getEnchantsToAdd().size() > 1, "an extra compatible enchant should have been added");
        assertFalse(event.getEnchantsToAdd().containsKey(null));
    }

    // ---------------------------------------------------------------------
    // U17: 追加抽選にエンチャントテーブル外(treasure 系)が混ざっていたバグの回帰テスト
    //
    // 注意: MockBukkit 4.110.0 は Registry#hasTag / #getTagValues を未実装
    // (UnimplementedOperationException = TestAbortedException のサブクラス)なので、
    // enchantingTablePool() はここでは isTreasure() フォールバック経路を通る。
    // タグ経路そのものは母集団を直接渡す seam(pickCompatibleEnchant の第1引数)で縛る。
    // ---------------------------------------------------------------------

    // ⚠️ 比較は必ず NamespacedKey 文字列で行う。Enchantment を静的フィールドに保持すると、
    // その静的初期化は MockBukkit.mock() より前に走るため、Registry.ENCHANTMENT が
    // テスト中に返すインスタンスとは**別オブジェクト**になる(equals/hashCode は同一性ベース)。
    // その状態で assertFalse(pool.contains(定数)) と書くと、除外が効いていなくても常に通る
    // = 何も検証しない緑になる。実際にこの罠を踏んだ(id 824900551 vs 1250848393)。

    /** バニラで treasure 扱い = エンチャントテーブルでは出ないもの。 */
    private static final List<String> TREASURE_KEYS = List.of(
            "minecraft:mending",
            "minecraft:frost_walker",
            "minecraft:soul_speed",
            "minecraft:swift_sneak",
            "minecraft:binding_curse",
            "minecraft:vanishing_curse",
            "minecraft:wind_burst");

    private static Set<String> poolKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Enchantment e : EnchantLuckListener.enchantingTablePool()) {
            keys.add(e.getKey().toString());
        }
        return keys;
    }

    @Test
    void enchantingTablePoolExcludesTreasureEnchants() {
        Set<String> pool = poolKeys();

        assertFalse(pool.isEmpty(), "母集団が空だと以降のアサーションが無意味になる");
        for (String treasure : TREASURE_KEYS) {
            assertFalse(pool.contains(treasure),
                    () -> treasure + " はエンチャントテーブルでは出ないので母集団に入ってはいけない");
        }
    }

    @Test
    void enchantingTablePoolStillContainsOrdinaryEnchants() {
        Set<String> pool = poolKeys();

        // 修正がやり過ぎて普通のエンチャントまで消していないことを縛る。
        // (このアサーションが無いと、上の除外テストが「常に通る」壊れ方をしても気づけない)
        assertTrue(pool.contains("minecraft:efficiency"), "効率強化はテーブルで出る");
        assertTrue(pool.contains("minecraft:sharpness"), "ダメージ増加はテーブルで出る");
        assertTrue(pool.contains("minecraft:unbreaking"), "耐久力はテーブルで出る");
        assertTrue(pool.contains("minecraft:protection"), "ダメージ軽減はテーブルで出る");
        assertTrue(pool.contains("minecraft:looting"), "ドロップ増加はテーブルで出る");
    }

    /**
     * 反バキューム性の確認: 修繕はダイヤの剣に「付けられる」ので、旧実装(全走査 + canEnchantItem のみ)
     * では実際に候補へ入っていた。除外は canEnchantItem の副作用ではなく母集団の絞り込みが効いている。
     */
    @Test
    void treasureEnchantWouldHaveBeenApplicableUnderOldFilters() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);

        assertTrue(Enchantment.MENDING.canEnchantItem(sword),
                "修繕は剣に付与可能 = 旧実装の canEnchantItem フィルタでは弾けていなかった");
    }

    @Test
    void extraEnchantNeverGrantsTreasureEnchantAcrossManySeeds() {
        stubAggregateLuck(1.0);
        when(config.levelBoostChancePerLuck()).thenReturn(0.0);
        when(config.levelBoostMaxSteps()).thenReturn(0);
        when(config.overenchantBonusChancePerLuck()).thenReturn(0.0);
        when(config.extraEnchantChancePerLuck()).thenReturn(1.0); // 追加抽選を必ず走らせる

        List<String> granted = new ArrayList<>();
        for (int seed = 0; seed < 300; seed++) {
            Map<Enchantment, Integer> toAdd = new HashMap<>();
            toAdd.put(Enchantment.SHARPNESS, 1);
            EnchantLuckListener listener = new EnchantLuckListener(
                    aggregator, config, dedicatedEffects, craftingFeatures, new Random(seed));

            EnchantItemEvent event = newEvent(toAdd);
            listener.onEnchant(event);

            for (Enchantment added : event.getEnchantsToAdd().keySet()) {
                String key = added.getKey().toString();
                if (!"minecraft:sharpness".equals(key)) {
                    granted.add(key);
                }
            }
        }

        assertFalse(granted.isEmpty(), "1件も追加されていないとテストが素通りになる");
        for (String added : granted) {
            assertFalse(TREASURE_KEYS.contains(added),
                    () -> added + " がエンチャントテーブル経由で付与された(U17 の再発)");
        }
    }

    // --- 母集団を直接渡す seam: 既存フィルタ(canEnchantItem / conflictsWith / 重複)の回帰 ---

    @Test
    void pickCompatibleEnchantReturnsCandidateFromGivenPool() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);

        Enchantment picked = EnchantLuckListener.pickCompatibleEnchant(
                List.of(Enchantment.LOOTING), sword, new HashMap<>(), new Random(1));

        assertSame(Enchantment.LOOTING, picked);
    }

    @Test
    void pickCompatibleEnchantOnlyDrawsFromThePool() {
        // 母集団に修繕が無ければ、剣に付けられても選ばれない(タグ経路の縛り)。
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);

        for (int seed = 0; seed < 50; seed++) {
            Enchantment picked = EnchantLuckListener.pickCompatibleEnchant(
                    List.of(Enchantment.LOOTING), sword, new HashMap<>(), new Random(seed));
            assertSame(Enchantment.LOOTING, picked, "母集団外のエンチャントが選ばれてはいけない");
        }
    }

    @Test
    void pickCompatibleEnchantSkipsAlreadyPresentEnchant() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        Map<Enchantment, Integer> already = new HashMap<>();
        already.put(Enchantment.SHARPNESS, 1);

        Enchantment picked = EnchantLuckListener.pickCompatibleEnchant(
                List.of(Enchantment.SHARPNESS), sword, already, new Random(1));

        assertNull(picked, "既に付いているエンチャントを重複して選んではいけない");
    }

    @Test
    void pickCompatibleEnchantSkipsInapplicableEnchant() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);

        Enchantment picked = EnchantLuckListener.pickCompatibleEnchant(
                List.of(Enchantment.PROTECTION), sword, new HashMap<>(), new Random(1));

        assertNull(picked, "剣に付けられない防具エンチャントは canEnchantItem で弾かれるべき");
    }

    @Test
    void pickCompatibleEnchantSkipsConflictingEnchant() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        Map<Enchantment, Integer> already = new HashMap<>();
        already.put(Enchantment.SHARPNESS, 1);

        Enchantment picked = EnchantLuckListener.pickCompatibleEnchant(
                List.of(Enchantment.SMITE), sword, already, new Random(1));

        assertNull(picked, "ダメージ増加と排他のアンデッド特効は conflictsWith で弾かれるべき");
    }

    @Test
    void pickCompatibleEnchantReturnsNullForNullItem() {
        Enchantment picked = EnchantLuckListener.pickCompatibleEnchant(
                List.of(Enchantment.LOOTING), null, new HashMap<>(), new Random(1));

        assertNull(picked);
    }
}
