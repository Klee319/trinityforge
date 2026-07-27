package com.github.klee319.dpschecker.dummy;

/**
 * Editable TrinityForge defender fields exposed in the TF defense settings GUI.
 * Percent-type stats are stored as fractions (0.20 = 20%).
 *
 * <p>Vocabulary follows COMBAT_SYSTEM_SPEC LD-13 (2026-07-14): only 耐性% is typed
 * (physical / magical are separate); 防御率%・被ダメージ軽減%・守備力(flat)・防具強度(会心軽減率%)・回避
 * are type-independent and apply to both damage components. {@link #ARMOR_STRENGTH} maps to
 * {@code DefenseStats.armorStrength()} = 会心軽減率 [0,1] applied at pipeline step 2
 * ({@code base *= 1 + critDamage*(1-r)}, 会心の増加分のみ軽減) — it is NOT a step-6 flat subtraction.
 */
public enum TfDefenseStat {
    DEFENSE_RATE("防御率", true),
    PHYS_RESISTANCE("物理耐性", true),
    MAGIC_RESISTANCE("魔法耐性", true),
    DAMAGE_REDUCTION("被ダメ軽減", true),
    FLAT_DEFENSE("守備力", false),
    ARMOR_STRENGTH("防具強度", false),
    DODGE_CHANCE("回避率", true);

    private final String displayName;
    private final boolean percent;

    TfDefenseStat(String displayName, boolean percent) {
        this.displayName = displayName;
        this.percent = percent;
    }

    public String displayName() {
        return displayName;
    }

    public boolean percent() {
        return percent;
    }

    public double step() {
        return percent ? 0.01 : 1.0;
    }

    public double min() {
        return 0.0;
    }

    public double max() {
        return percent ? 1.0 : 1000.0;
    }
}
