package com.trinityforge.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure projection of addon-owned stat values onto vanilla {@code AttributeModifier} descriptors
 * (COMBAT_SYSTEM_SPEC section 5 "vanilla mapping"; ADDON_INTEGRATION_SPEC section 1.2).
 *
 * <p>"The truth of numbers is the PDC, while physical behaviour and display are mirrored onto the
 * Vanilla / ValhallaMMO Attributes" (COMBAT_SYSTEM_SPEC section 1). Only the subset of stats that
 * have a vanilla-attribute analogue (e.g. attack-damage, knockback-resistance, max-health,
 * movement-speed) is mapped here; PDC-only combat stats (penetration%, crit, resistance%, flat
 * defense) are absent from {@link #entries} and so are skipped.
 *
 * <p>This type is deliberately Bukkit-free so the mapping arithmetic is unit-testable. The actual
 * {@code ItemMeta.addAttributeModifier(...)} application is a thin adapter on top of the
 * {@link AttributeModifierSpec} list this produces.
 */
public record AttributeProjection(Map<String, AttributeMapEntry> entries) {

    /** Mirrors {@code org.bukkit.attribute.AttributeModifier.Operation} by name (1:1 adapter map). */
    public enum AttributeOperation {
        ADD_NUMBER,
        ADD_SCALAR,
        MULTIPLY_SCALAR_1
    }

    /** One configured stat-to-attribute rule: which attribute, how to combine, and the scale factor. */
    public record AttributeMapEntry(String attributeKey, AttributeOperation operation, double scale) {
        public AttributeMapEntry {
            Objects.requireNonNull(attributeKey, "attributeKey");
            Objects.requireNonNull(operation, "operation");
            if (!Double.isFinite(scale)) {
                throw new IllegalArgumentException("scale must be finite: " + scale);
            }
        }
    }

    /**
     * A resolved modifier to apply to one attribute (consumed by the Bukkit adapter). {@code statKey}
     * is the source stat: it gives the modifier a stable, content-derived identity so re-applying the
     * same item replaces rather than stacks, independent of list order or vanilla attribute key churn.
     */
    public record AttributeModifierSpec(String statKey, String attributeKey, AttributeOperation operation,
                                        double amount) {
        public AttributeModifierSpec {
            Objects.requireNonNull(statKey, "statKey");
            Objects.requireNonNull(attributeKey, "attributeKey");
            Objects.requireNonNull(operation, "operation");
            if (!Double.isFinite(amount)) {
                throw new IllegalArgumentException("amount must be finite: " + amount);
            }
        }
    }

    public AttributeProjection {
        entries = canonicalize(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * ハードコード化された既定の stat-to-vanilla-Attribute 写像(旧 {@code stats/attribute-map.yml} の
     * デフォルト値をそのまま移植)。運用チューニング対象ではなくなったため config 化を廃止し、ここに固定した
     * ({@code AttributeMappingConfig#projection()} が返す唯一の値)。値の意味は元の yml コメントを踏襲する。
     */
    public static AttributeProjection defaults() {
        Map<String, AttributeMapEntry> entries = new LinkedHashMap<>();
        // 注意: attack-power はバニラ attack_damage には写像しない。TF内部でパイプラインの物理基本ダメージ
        // として使い(CombatListener)、vanilla attack_damage との二重計上を避けるため、ここには載せない。
        entries.put("knockback_resistance",
                new AttributeMapEntry("knockback_resistance", AttributeOperation.ADD_NUMBER, 1.0));
        // armor_defense_rate はアイテム側では「バニラ防具値そのもの」の直結マッピングであり、TF独自の0〜1割合
        // として二重消費されることはない(combat.DefenseStatBridgeがアイテム側のこのステを0扱いにし、代わりに
        // VanillaArmorMappingが実際に付いたバニラAttribute.ARMORの値を読み戻して防御計算に使う)。スキルツリーの
        // perk buffとしての同名キーは別経路([0,1]の直接加算)で、これとは別物(PlayerDefenseResolver参照)。
        // ※ armor_strength(防具強度) は会心軽減率%へ役割変更したため、もはや armor_toughness へは写像しない
        //   (DefenseStatBridge が derived stat map から直接読む)。二重計上を避けるためここには載せない。
        entries.put("armor_defense_rate", new AttributeMapEntry("armor", AttributeOperation.ADD_NUMBER, 1.0));
        entries.put("max_health", new AttributeMapEntry("max_health", AttributeOperation.ADD_NUMBER, 1.0));
        // 加算ステ: プレイヤー基礎(または材質既定)の上に載せる。0 は no-op。
        entries.put("move_speed", new AttributeMapEntry("movement_speed", AttributeOperation.ADD_SCALAR, 1.0));
        // move-speed は item-stats/lore で分数(0.1 = +10%)として書かれる。旧 scale 0.01 は
        // パーセントポイント想定で実効が約1/100になり「効果がない」ように見えていた。
        // 攻撃速度加算: バニラ attack_speed への加算。材質既定(剣のマイナス補正等)は AttributeApplier が
        // 復元したうえでこの値を重ねる。物理CT(item-cooldown)とは別軸。
        entries.put("attack_speed", new AttributeMapEntry("attack_speed", AttributeOperation.ADD_NUMBER, 1.0));
        // attack_speed_bonus(2026-07-25新設): 全ソース(装備/パーク/base-stats等)横断の「割合」ボーナス。
        // MULTIPLY_SCALAR_1 を使う理由(ADD_SCALARではない): Bukkit の Attribute 計算は
        // base → ADD_NUMBER合計 → ADD_SCALAR合計(baseのみに掛かる: base*(1+Σadd_scalar)) →
        // MULTIPLY_SCALAR_1(そこまでの合計に対して個別に(1+x)を掛ける、複数あれば連続で掛かる)の順で
        // 積み上がる。ADD_SCALAR はあくまで「素の基礎値(4.0)」だけに掛かるため、attack-speed(絶対値・
        // ADD_NUMBER)で既に積み増した実効値を無視してしまう。MULTIPLY_SCALAR_1 はその時点までの
        // running total(=絶対値適用後の実効速度)に掛かるので、「素手4.0 + attack-speed-bonus 10% = 4.4」
        // のような仕様通りの挙動になる。この attack_speed_bonus 自体は PerkAttributeApplier がプレイヤー
        // レベルで集約した単一の合成割合(複数ソースを合算した1個の(1+合計%))として適用する
        // (このプロジェクション層は個々のsource値をそのまま渡された場合の写像ルールを定義するのみ)。
        entries.put("attack_speed_bonus",
                new AttributeMapEntry("attack_speed", AttributeOperation.MULTIPLY_SCALAR_1, 1.0));
        // リーチ加算: 既定 3.0 ブロックへの加算。
        entries.put("attack_reach",
                new AttributeMapEntry("entity_interaction_range", AttributeOperation.ADD_NUMBER, 1.0));
        return new AttributeProjection(Map.copyOf(entries));
    }

    /**
     * Re-keys the mapping table to canonical stat keys ({@link StatKeys#canonical}) so a snake_case
     * {@code attribute-map.yml} entry resolves against a kebab-case rolled stat (gap I1). On the rare
     * collision (two source keys folding to the same canonical key) last-write-wins, matching the
     * config author's intent of a single rule per stat.
     */
    private static Map<String, AttributeMapEntry> canonicalize(Map<String, AttributeMapEntry> raw) {
        Map<String, AttributeMapEntry> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, AttributeMapEntry> entry : raw.entrySet()) {
            canonical.put(StatKeys.canonical(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(canonical);
    }

    /**
     * Projects raw stat values onto attribute-modifier specs. Stats with no configured mapping are
     * skipped (they stay PDC-only); a mapped stat whose scaled amount is exactly zero is also skipped
     * to avoid emitting no-op modifiers.
     */
    public List<AttributeModifierSpec> project(Map<String, Double> statValues) {
        Objects.requireNonNull(statValues, "statValues");
        List<AttributeModifierSpec> specs = new ArrayList<>();
        for (Map.Entry<String, Double> stat : statValues.entrySet()) {
            // Look up via the canonical key so a kebab-case rolled stat matches a snake_case mapping
            // entry (gap I1). The original key is kept on the spec below for stable modifier identity.
            AttributeMapEntry entry = entries.get(StatKeys.canonical(stat.getKey()));
            if (entry == null) {
                continue;
            }
            Double value = stat.getValue();
            if (value == null) {
                continue;
            }
            double amount = value * entry.scale();
            if (amount == 0.0 || !Double.isFinite(amount)) {
                // Skip zero-scaled stats (no-op modifier) and non-finite results from bad stat data
                // (NaN/Inf), which would otherwise slip past the exact-zero check.
                continue;
            }
            specs.add(new AttributeModifierSpec(
                    stat.getKey(), entry.attributeKey(), entry.operation(), amount));
        }
        return List.copyOf(specs);
    }
}
