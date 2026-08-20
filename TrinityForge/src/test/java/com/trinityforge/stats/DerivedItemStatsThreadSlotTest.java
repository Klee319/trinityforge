package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * derivation の順序(profile overlay → 儀式/クラフト加算 → カテゴリ上限)を固定する。
 *
 * <p>⚠ 上限マップはここでも「機構の入力例」で、出荷 yml の実値ではない
 * ({@code ShippedThreadSlotCapDriftTest} が出荷値との一致を担保する。理由は
 * {@link ThreadSlotPolicyTest} の javadoc)。
 */
class DerivedItemStatsThreadSlotTest {

    /** 防具だけ枠を持つ入力例。出荷値ではない。 */
    private static final Map<String, Integer> ARMOR_ONLY_CAPS = Map.of(
            EquipmentSlotResolver.CATEGORY_ARMOR, 5,
            EquipmentSlotResolver.CATEGORY_WEAPON, 0,
            EquipmentSlotResolver.CATEGORY_TOOL, 0,
            EquipmentSlotResolver.CATEGORY_OTHER, 0);

    /** 出荷値と同じ形(全カテゴリ 5)。 */
    private static final Map<String, Integer> ALL_CATEGORY_CAPS = Map.of(
            EquipmentSlotResolver.CATEGORY_ARMOR, 5,
            EquipmentSlotResolver.CATEGORY_WEAPON, 5,
            EquipmentSlotResolver.CATEGORY_TOOL, 5,
            EquipmentSlotResolver.CATEGORY_OTHER, 5);

    @Test
    void craftBonusAndCapApplyAfterProfileOverlay() {
        String key = StatKeys.canonical("thread-slots");
        Map<String, Double> stats = new LinkedHashMap<>();
        ItemStatProfile profile = new ItemStatProfile(
                Map.of(key, 4.0),
                Map.of(key, 1.0),
                Map.of());
        DerivedItemStats.applyProfile(stats, profile, 2);
        ThreadSlotPolicy.applyCraftBonus(stats, 2);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.DIAMOND_CHESTPLATE, ARMOR_ONLY_CAPS);

        assertEquals(5.0, stats.get(key));
    }

    @Test
    void weaponCapRemovesThreadSlotsAfterCraftBonus() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(StatKeys.canonical("thread-slots"), 2.0);
        ThreadSlotPolicy.applyCraftBonus(stats, 1);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.DIAMOND_SWORD, ARMOR_ONLY_CAPS);
        assertFalse(stats.containsKey(StatKeys.canonical("thread-slots")));
    }

    @Test
    void weaponKeepsRitualBonusUnderShippedShapedCaps() {
        // F2(2026-07-31): 出荷値の形では「スレッド枠拡張の儀式」の +1 が武器にも残る。
        // 儀式が成功して枠だけ増え、それが derivation で消える(= 素材の無駄打ち)状態にはしない。
        String key = StatKeys.canonical("thread-slots");
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put(key, 3.0);
        ThreadSlotPolicy.applyCraftBonus(stats, 1);
        ThreadSlotPolicy.applyCategoryCap(stats, Material.NETHERITE_SWORD, ALL_CATEGORY_CAPS);
        assertEquals(4.0, stats.get(key));

        // 上限を超えた分だけがクランプされる(削除ではない)。
        Map<String, Double> over = new LinkedHashMap<>();
        over.put(key, 5.0);
        ThreadSlotPolicy.applyCraftBonus(over, 3);
        ThreadSlotPolicy.applyCategoryCap(over, Material.NETHERITE_SWORD, ALL_CATEGORY_CAPS);
        assertEquals(5.0, over.get(key));
    }
}
