package com.trinityforge.combat;

/**
 * Pure conversion of vanilla armor attributes into physical {@link DefenseStats}
 * (COMBAT_SYSTEM_SPEC section 5: "armor -> 防御率%（使い回し）" / "armor toughness -> 防具強度（会心軽減率%）").
 *
 * <p>This is only ever the fallback for victims that carry no addon PDC profile: a PDC profile
 * always wins over this mapping (see {@code SymmetricCombatService} victim resolution). Without
 * it, a plain armored victim with no PDC profile would resolve to the zero-mitigation config
 * default and lose all defense.
 *
 * <p>防具強度(会心軽減率%) here is the vanilla-toughness FALLBACK only: {@code armorStrengthPerPoint}
 * defaults to {@code 0} (バニラ防具の toughness は会心軽減に寄与しない — 会心軽減は主に item/防具/mob の
 * authored armor-strength ステから {@link DefenseStatBridge} が直接得る)。運営が後日 config で調整可。
 *
 * <p>Resistance%, damage-reduction%, and typed flat-defense have no vanilla-armor analogue
 * (COMBAT_SYSTEM_SPEC 3.3: those are addon-only, PDC-profile stats), so they stay at 0 here.
 */
public final class VanillaArmorMapping {

    private VanillaArmorMapping() {
    }

    /**
     * @param armorAttribute          the victim's vanilla {@code armor} attribute value
     * @param armorToughnessAttribute the victim's vanilla {@code armor_toughness} attribute value
     * @param defenseRatePerPoint     防御率% granted per point of armor (damage.yml, tunable)
     * @param defenseRateMax          upper clamp for the resulting 防御率% (damage.yml, tunable)
     * @param armorStrengthPerPoint   防具強度(会心軽減率%) granted per point of armor toughness (damage.yml,
     *                                tunable; 既定 0 = バニラ toughness は会心軽減に寄与しないフォールバック)
     */
    public static DefenseStats toDefense(double armorAttribute, double armorToughnessAttribute,
                                         double defenseRatePerPoint, double defenseRateMax,
                                         double armorStrengthPerPoint) {
        double armor = nonNegativeFinite(armorAttribute);
        double toughness = nonNegativeFinite(armorToughnessAttribute);
        double rate = Math.min(nonNegativeFinite(defenseRateMax), armor * nonNegativeFinite(defenseRatePerPoint));
        double armorStrength = toughness * nonNegativeFinite(armorStrengthPerPoint);
        return new DefenseStats(rate, 0.0, 0.0, 0.0, armorStrength);
    }

    private static double nonNegativeFinite(double value) {
        return Double.isFinite(value) && value > 0 ? value : 0.0;
    }
}
