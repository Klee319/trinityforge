package com.trinityforge.combat;

import com.trinityforge.stats.StatKeys;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pure mapping from a defender's derived stats (stat key -&gt; value, produced by {@code
 * stats/item-stats.yml} via {@code DerivedItemStats} and summed across equipped armor) to a
 * {@link DefenseStats} for one damage component, using the fixed key names in {@link DefenseStatKeys}
 * (2026-07-25 CMB-31 — no longer config-driven, see {@link DefenseStatKeys} javadoc for why). The
 * symmetric counterpart of {@link AttackStatBridge}.
 *
 * <p>耐性% and 守備力(flat) are typed (LD-13): a PHYSICAL component reads {@code physResistance}/
 * {@code physFlatDefense}, a MAGICAL component reads {@code magicResistance}/{@code magicFlatDefense}
 * (legacy {@code flat-defense} is honoured as a fallback). 被ダメージ軽減% and 防具強度(会心軽減率%)
 * are type-independent, so the same value feeds both components. 防具強度 is now read directly here
 * (its {@code armor_strength → armor_toughness} projection was removed), so it is no longer sourced from
 * the vanilla mirror. Only 防御率% is left at 0 here: it still comes from the vanilla armor mirror
 * ({@link VanillaArmorMapping}) and the service combines the two ({@link DefenseStats#combine}).
 *
 * <p>Keys are folded through {@link StatKeys#canonical} on both sides so kebab-case
 * ({@code stats/roll.yml}) and snake_case spellings resolve identically (gap I1). A stat with no
 * matching key contributes 0.
 */
public final class DefenseStatBridge {

    private DefenseStatBridge() {
    }

    public static DefenseStats bridge(Map<String, Double> derivedStats, DefenseStatKeys keys, DamageType type) {
        Objects.requireNonNull(derivedStats, "derivedStats");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(type, "type");
        Map<String, Double> canonical = canonicalize(derivedStats);
        double resistance = switch (type) {
            case PHYSICAL -> valueFor(canonical, keys.physResistance());
            case MAGICAL -> valueFor(canonical, keys.magicResistance());
            case TYPELESS -> 0.0;
        };
        if (type == DamageType.TYPELESS) return DefenseStats.NONE;
        // “守備力(flat-defense)”は型別に分離される（物理/魔法）。後方互換として legacy: flat-defense をフォールバック。
        String physFlatKey = keys.physFlatDefense();
        String magicFlatKey = keys.magicFlatDefense();
        String typedFlatKey = type == DamageType.PHYSICAL ? physFlatKey : magicFlatKey;
        // 旧仕様: flat-defense を型別キーが未指定/未存在なら利用する。
        // canonicalize() 済みなので存在チェックは key 統一形（kebab/snake対策）に寄せる。
        String legacyFlat = StatKeys.canonical("flat-defense");
        boolean typedKeyPresent = typedFlatKey != null && canonical.containsKey(StatKeys.canonical(typedFlatKey));
        double flatDefense = typedKeyPresent
                ? canonical.getOrDefault(StatKeys.canonical(typedFlatKey), 0.0)
                : canonical.getOrDefault(legacyFlat, 0.0);

        return new DefenseStats(
                0.0,                                       // 防御率%: vanilla armor mirror, not here (LD-13)
                resistance,                                // 耐性%: typed (LD-13)
                valueFor(canonical, keys.damageReduction()), // 被ダメージ軽減%: common
                flatDefense,                               // 守備力(flat): typed+fallback (LD-13)
                valueFor(canonical, keys.armorStrength())); // 防具強度(会心軽減率%): 直接読取 (vanilla mirror 経由をやめた)
    }

    /** Whole-attack dodge chance (回避, type-independent). Read once per attack alongside the stats. */
    public static double dodgeChance(Map<String, Double> derivedStats, DefenseStatKeys keys) {
        Objects.requireNonNull(derivedStats, "derivedStats");
        Objects.requireNonNull(keys, "keys");
        return valueFor(canonicalize(derivedStats), keys.dodgeChance());
    }

    private static Map<String, Double> canonicalize(Map<String, Double> raw) {
        Map<String, Double> canonical = new HashMap<>();
        raw.forEach((key, value) -> canonical.put(StatKeys.canonical(key), value));
        return canonical;
    }

    private static double valueFor(Map<String, Double> canonical, String configuredKey) {
        return canonical.getOrDefault(StatKeys.canonical(configuredKey), 0.0);
    }
}
