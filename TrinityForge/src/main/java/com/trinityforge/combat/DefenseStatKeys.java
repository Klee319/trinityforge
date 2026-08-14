package com.trinityforge.combat;

/**
 * Fixed stat-key names that tell the armor-PDC bridge which entry of a piece's derived stat map feeds
 * which defender field (SKILL_TREE_SPEC 6, LD-8/LD-13). The symmetric counterpart of
 * {@link AttackStatKeys} — see its javadoc for why these names are fixed rather than config-driven
 * (2026-07-25, CMB-31): the same 4 places (
 * {@code PercentStatNormalize}, {@code StatVocabulary}, {@code StatCategoryInference}, the config
 * editor) plus {@code stats/roll.yml} would need to be updated together with this class if a name
 * ever changed.
 *
 * <p>The TF-only defender stats live here: typed 耐性% (physical / magical), typed 守備力(flat), the
 * common 防御率% / 被ダメージ軽減% / 回避, and 防具強度(会心軽減率%). 防具強度 is now read directly here
 * (LD-13 revision): it is a crit-reduction rate applied at step 2, no longer mirrored onto vanilla
 * armor_toughness, so the {@code armor_strength → armor_toughness} projection is removed and there is no
 * double-count.
 *
 * <p><b>2026-08-15: 防御率% もここへ入った。</b> それまでの防御率はアイテム側だけ別扱いで、
 * {@code armor-defense-rate}(バニラ防具値の点数)を {@code Attribute.ARMOR} へ写像し、
 * {@link VanillaArmorMapping} が読み戻すという迂回路を通っていた。「防具値」は直感的でないという
 * ユーザー判断で撤去し、アイテムも {@code defense-rate}([0,1] の軽減率)を直に持つようになったので、
 * 他の防御ステと同じくこのブリッジが素直に読む。TFスタンプ品のバニラ防具バーは常に空
 * ({@code AttributeApplier} が {@code Attribute.ARMOR} の材質既定を復元しない)ため、
 * {@link VanillaArmorMapping} 経由の寄与は 0 になり二重計上しない。
 */
public record DefenseStatKeys(
        String physResistance,
        String magicResistance,
        String physFlatDefense,
        String magicFlatDefense,
        String defenseRate,
        String damageReduction,
        String dodgeChance,
        String armorStrength
) {
    public static final String PHYS_RESISTANCE = "phys-resistance";
    public static final String MAGIC_RESISTANCE = "magic-resistance";
    public static final String PHYS_FLAT_DEFENSE = "phys-flat-defense";
    public static final String MAGIC_FLAT_DEFENSE = "magic-flat-defense";
    public static final String DEFENSE_RATE = "defense-rate";
    public static final String DAMAGE_REDUCTION = "damage-reduction";
    public static final String DODGE_CHANCE = "dodge-chance";
    public static final String ARMOR_STRENGTH = "armor-strength";

    /** The single fixed instance every caller resolves; see the class javadoc for why this is fixed. */
    public static final DefenseStatKeys DEFAULT = new DefenseStatKeys(
            PHYS_RESISTANCE, MAGIC_RESISTANCE, PHYS_FLAT_DEFENSE, MAGIC_FLAT_DEFENSE,
            DEFENSE_RATE, DAMAGE_REDUCTION, DODGE_CHANCE, ARMOR_STRENGTH);
}
