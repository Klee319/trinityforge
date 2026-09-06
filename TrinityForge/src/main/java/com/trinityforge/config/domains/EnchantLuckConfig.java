package com.trinityforge.config.domains;

import com.trinityforge.config.ConfigDomain;
import com.trinityforge.config.ConfigSchema;
import com.trinityforge.config.SchemaField;

/**
 * Typed accessor for {@code stats/enchant-luck.yml}: the config-driven weighting used by
 * {@code EnchantLuckListener} to bias {@link org.bukkit.event.enchantment.EnchantItemEvent} results
 * toward higher levels (and, for players with an unlocked {@code overenchant:} profile, beyond the
 * vanilla cap) based on the per-player {@code enchant_luck} stat accumulated from
 * {@code skilltree/enchanting.yml} (A / C / A-alpha-1 / A-alpha-2 / A-beta-1 / A-beta-2 nodes).
 *
 * <p>Vanilla's enchant-table draw has no player-side luck parameter (only bookshelf count and
 * per-item enchantability drive it) — this is TrinityForge's own additive re-roll layer, entirely
 * config-driven so the weighting can be tuned without a code change.
 */
public final class EnchantLuckConfig {

    public static final String PATH = "stats/enchant-luck.yml";

    private final ConfigDomain domain;

    public EnchantLuckConfig() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("level-boost-chance-per-luck", SchemaField.Type.DOUBLE, 0.01, 0.0, 1.0))
                .field(SchemaField.number("level-boost-max-steps", SchemaField.Type.INT, 2, 0, 10))
                .field(SchemaField.number("overenchant-bonus-chance-per-luck", SchemaField.Type.DOUBLE, 0.02, 0.0, 1.0))
                .field(SchemaField.number("extra-enchant-chance-per-luck", SchemaField.Type.DOUBLE, 0.005, 0.0, 1.0))
                .field(SchemaField.number("vanilla-parity-luck", SchemaField.Type.DOUBLE, 10.0, 0.0, 200.0))
                .field(SchemaField.number("level-nerf-chance-at-zero", SchemaField.Type.DOUBLE, 0.5, 0.0, 1.0))
                .field(SchemaField.number("level-nerf-max-steps", SchemaField.Type.INT, 2, 0, 10));
        this.domain = new ConfigDomain(PATH, schema);
    }

    public ConfigDomain domain() {
        return domain;
    }

    /** Per-{@code enchant_luck}-point probability of bumping a resolved enchant level by +1 (clamped [0,1] on use). */
    public double levelBoostChancePerLuck() {
        return Math.max(0.0, domain.get().getDouble("level-boost-chance-per-luck"));
    }

    /** Maximum number of +1 level-boost attempts per enchantment per table use. */
    public int levelBoostMaxSteps() {
        return Math.max(0, domain.get().getInt("level-boost-max-steps"));
    }

    /** Per-luck-point probability of boosting past the vanilla cap, for players with the matching overenchant unlocked. */
    public double overenchantBonusChancePerLuck() {
        return Math.max(0.0, domain.get().getDouble("overenchant-bonus-chance-per-luck"));
    }

    /** Per-luck-point probability of granting one extra, otherwise-unrolled compatible enchantment at level 1. */
    public double extraEnchantChancePerLuck() {
        return Math.max(0.0, domain.get().getDouble("extra-enchant-chance-per-luck"));
    }

    /**
     * この値未満の {@code enchant_luck} では格上げせず、バニラ結果を弱める。
     * {@code 0} ならナーフを無効化して従来どおり(運0はバニラのまま、運が正のときだけ格上げ)。
     */
    public double vanillaParityLuck() {
        return Math.max(0.0, domain.get().getDouble("vanilla-parity-luck"));
    }

    /** 運0のときのレベル-1 試行確率。パリティ直前では 0 に近づく。 */
    public double levelNerfChanceAtZero() {
        return Math.max(0.0, domain.get().getDouble("level-nerf-chance-at-zero"));
    }

    /** 1回の抽選でレベルを-1できる最大回数。下限はレベル1(エンチャントは消さない)。 */
    public int levelNerfMaxSteps() {
        return Math.max(0, domain.get().getInt("level-nerf-max-steps"));
    }
}
