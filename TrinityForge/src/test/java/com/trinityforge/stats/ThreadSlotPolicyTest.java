package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link ThreadSlotPolicy} 自体の振る舞い(クランプ / cap&le;0 でのキー削除)を、上限マップを
 * <b>引数として与えて</b>固定する純粋テスト。
 *
 * <p>⚠ ここに書く上限マップは「機構の入力例」でしかなく、出荷 yml の実値ではない。
 * 出荷値との一致は {@code ShippedThreadSlotCapDriftTest} が実際に yml を読んで検証する
 * ── 2026-07-31 まで<b>このファイルの定数が出荷 yml とずれていても誰も気づけなかった</b>
 * (Java 既定 weapon=0 / 出荷 yml weapon=5)ため、責務を分けてある。
 */
class ThreadSlotPolicyTest {

    /**
     * 「防具だけ枠を持つ」という機構入力の例。<b>出荷値ではない</b>
     * (出荷値は全カテゴリ 5 = {@code CraftingFeaturesConfig.DEFAULT_THREAD_SLOT_CAP})。
     */
    private static final Map<String, Integer> ARMOR_ONLY_CAPS = Map.of(
            EquipmentSlotResolver.CATEGORY_ARMOR, 5,
            EquipmentSlotResolver.CATEGORY_WEAPON, 0,
            EquipmentSlotResolver.CATEGORY_TOOL, 0,
            EquipmentSlotResolver.CATEGORY_OTHER, 0);

    /** 出荷値と同じ形(全カテゴリ 5)。F2 で武器・触媒も枠を持つようになった後の実状。 */
    private static final Map<String, Integer> ALL_CATEGORY_CAPS = Map.of(
            EquipmentSlotResolver.CATEGORY_ARMOR, 5,
            EquipmentSlotResolver.CATEGORY_WEAPON, 5,
            EquipmentSlotResolver.CATEGORY_TOOL, 5,
            EquipmentSlotResolver.CATEGORY_OTHER, 5);

    @Test
    void applyCraftBonusAddsToThreadSlots() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("thread-slots"), 2.0);
        ThreadSlotPolicy.applyCraftBonus(stats, 1);
        assertEquals(3.0, stats.get(StatKeys.canonical("thread-slots")));
    }

    @Test
    void armorCapClampsThreadSlots() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("thread-slots"), 7.0);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.DIAMOND_CHESTPLATE, ARMOR_ONLY_CAPS);
        assertEquals(5.0, stats.get(StatKeys.canonical("thread-slots")));
    }

    @Test
    void weaponCapRemovesThreadSlots() {
        // cap<=0 は「0 を残す」ではなく「キーごと削除」。この削除セマンティクスが
        // weapon=0 の間 drift を見えなくしていた本体なので、期待値として残す。
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("thread-slots"), 2.0);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.DIAMOND_SWORD, ARMOR_ONLY_CAPS);
        assertFalse(stats.containsKey(StatKeys.canonical("thread-slots")));
    }

    @Test
    void axeUsesTheLargerOfItsTwoCategoryCaps() {
        // 斧は weapon + tool の二重分類(EquipmentSlotResolver#statCategories)。capFor は max を採る。
        Map<String, Integer> mixed = Map.of(
                EquipmentSlotResolver.CATEGORY_WEAPON, 2,
                EquipmentSlotResolver.CATEGORY_TOOL, 5);
        assertEquals(5, ThreadSlotPolicy.capFor(Material.NETHERITE_AXE, mixed));

        // 片方が 0 でももう片方が正なら枠は残る(斧が weapon=0 で消えない理由)。
        Map<String, Integer> toolOnly = Map.of(
                EquipmentSlotResolver.CATEGORY_WEAPON, 0,
                EquipmentSlotResolver.CATEGORY_TOOL, 3);
        assertEquals(3, ThreadSlotPolicy.capFor(Material.NETHERITE_AXE, toolOnly));
    }

    @Test
    void weaponAndCatalystKeepThreadSlotsWhenTheirCategoryCapIsPositive() {
        // F2(2026-07-31): 出荷値では武器・触媒(other)も枠を持つ。
        for (Material material : new Material[] {
                Material.NETHERITE_SWORD, Material.BLAZE_ROD, Material.NETHERITE_PICKAXE}) {
            Map<String, Double> stats = new LinkedHashMap<>();
            stats.put(StatKeys.canonical("thread-slots"), 3.0);
            ThreadSlotPolicy.applyCategoryCap(stats, material, ALL_CATEGORY_CAPS);
            assertEquals(3.0, stats.get(StatKeys.canonical("thread-slots")),
                    material + " の thread-slots が消えている");
        }
    }
}
