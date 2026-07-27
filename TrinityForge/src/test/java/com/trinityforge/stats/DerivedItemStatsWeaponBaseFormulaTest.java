package com.trinityforge.stats;

import com.trinityforge.config.domains.WeaponBaseFormula;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit-tests {@link DerivedItemStats#applyWeaponBaseFormula}, the pure use-level-&gt;attack-power step. The
 * full {@link DerivedItemStats#resolve} needs a live Bukkit {@code ItemStack} (no MockBukkit on the
 * headless classpath), so the application conditions — enabled, weapon-category, positive use-level, and
 * the base being ADDED on top of any directly-authored attack-power (式 + 明示) — are asserted directly on
 * the extracted pure method, mirroring {@link DerivedItemStatsProfileTest}. Canonical key {@code attack_power}.
 */
class DerivedItemStatsWeaponBaseFormulaTest {

    private static final Set<String> WEAPON = Set.of("weapon");
    private static final Set<String> ARMOR = Set.of("armor");
    private static final WeaponBaseFormula ENABLED = new WeaponBaseFormula(true, 2.0, 1000.0);

    private static Map<String, Double> mutableMap() {
        return new LinkedHashMap<>();
    }

    @Test
    void appliesFormulaForWeaponWithPositiveUseLevel() {
        Map<String, Double> map = mutableMap();
        // 1 + 20^2 / 1000 = 1 + 400/1000 = 1.4
        DerivedItemStats.applyWeaponBaseFormula(map, ENABLED, WEAPON, 20);
        assertEquals(1.4, map.get("attack_power"), 1e-9);
    }

    @Test
    void zeroUseLevelIsNotApplied() {
        Map<String, Double> map = mutableMap();
        DerivedItemStats.applyWeaponBaseFormula(map, ENABLED, WEAPON, 0);
        assertFalse(map.containsKey("attack_power"), "useLevel<=0 must not gain a formula attack-power");
    }

    @Test
    void negativeUseLevelIsNotApplied() {
        Map<String, Double> map = mutableMap();
        DerivedItemStats.applyWeaponBaseFormula(map, ENABLED, WEAPON, -5);
        assertFalse(map.containsKey("attack_power"));
    }

    @Test
    void explicitAttackPowerIsSummedWithTheFormulaBase() {
        Map<String, Double> map = mutableMap();
        map.put("attack_power", 7.0); // item-stats fixed/per-quality/random already set it
        DerivedItemStats.applyWeaponBaseFormula(map, ENABLED, WEAPON, 20);
        // 直接指定 7.0 + 式 (1 + 20^2/1000 = 1.4) = 8.4 (両方加算)。
        assertEquals(8.4, map.get("attack_power"), 1e-9, "direct attack-power and the formula base are summed");
    }

    @Test
    void zeroExplicitAttackPowerAddsOnlyTheFormula() {
        Map<String, Double> map = mutableMap();
        map.put("attack_power", 0.0); // 0 base + formula = formula alone
        DerivedItemStats.applyWeaponBaseFormula(map, ENABLED, WEAPON, 20);
        assertEquals(1.4, map.get("attack_power"), 1e-9);
    }

    @Test
    void armorCategoryIsNeverApplied() {
        Map<String, Double> map = mutableMap();
        DerivedItemStats.applyWeaponBaseFormula(map, ENABLED, ARMOR, 20);
        assertFalse(map.containsKey("attack_power"), "防具に幻の attack-power を付けない");
    }

    @Test
    void nonWeaponCatalystIsNeverApplied() {
        Map<String, Double> map = mutableMap();
        DerivedItemStats.applyWeaponBaseFormula(map, ENABLED, Set.of("catalyst"), 20);
        assertFalse(map.containsKey("attack_power"));
    }

    @Test
    void disabledFormulaIsNoOp() {
        Map<String, Double> map = mutableMap();
        DerivedItemStats.applyWeaponBaseFormula(map, new WeaponBaseFormula(false, 2.0, 1000.0), WEAPON, 20);
        assertFalse(map.containsKey("attack_power"), "enabled=false leaves the map untouched (回帰なし)");
    }

    @Test
    void weaponCategoryAmongOthersStillApplies() {
        Map<String, Double> map = mutableMap();
        // An axe is weapon+tool; the weapon membership is enough.
        DerivedItemStats.applyWeaponBaseFormula(map, ENABLED, Set.of("weapon", "tool"), 10);
        // 1 + 10^2 / 1000 = 1.1
        assertEquals(1.1, map.get("attack_power"), 1e-9);
    }
}
