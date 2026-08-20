package com.trinityforge.config.domains;

import com.trinityforge.config.ConfigDomain;
import com.trinityforge.config.ConfigSchema;
import com.trinityforge.config.SchemaField;
import com.trinityforge.config.TypedConfig;
import com.trinityforge.stats.QualityRollModel;

import java.util.function.IntSupplier;

/**
 * Typed accessor for {@code stats/quality.yml} (SELECTION_SPEC 3): the global quality model —
 * the effective max quality and the up/down spread (σ) of the quality bell shared by crafts and drops.
 *
 * <p>The number of quality steps is config-variable (Q = 任意段階, ITEM_ECONOMY_SPEC 5): the effective
 * maximum quality is the {@code stats/quality-tiers.yml} tier count minus one when tiers are configured
 * (set via {@link #useEffectiveMaxOverride}), otherwise the numeric {@code max-quality} here.
 * {@code ItemData.MAX_QUALITY} is only a PDC storage sanity ceiling.
 *
 * <p>{@code spread-up}/{@code spread-down} are the σ of the quality draw's upper/lower halves
 * (split-normal, {@link com.trinityforge.stats.CraftQualityPolicy#resolveQualityNormal}), letting the
 * upward and downward spread be tuned independently (上振れ/下振れ). Both crafts and mob drops read them;
 * a craft additionally widens the up side by the crafter's upswing perk.
 */
public final class QualityConfig {

    public static final String PATH = "stats/quality.yml";

    private final ConfigDomain domain;
    // Supplies the tier-count-derived effective max quality (-1 = no tiers -> use numeric max-quality).
    private volatile IntSupplier effectiveMaxOverride = () -> -1;

    public QualityConfig() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("max-quality", SchemaField.Type.INT, 9, 1, 100))
                .field(SchemaField.number("spread-up", SchemaField.Type.DOUBLE, 1.5, 0.0, 100.0))
                .field(SchemaField.number("spread-down", SchemaField.Type.DOUBLE, 1.5, 0.0, 100.0))
                .field(SchemaField.number("roll-spread-up", SchemaField.Type.DOUBLE, 0.15, 0.0, 100.0))
                .field(SchemaField.number("roll-spread-down", SchemaField.Type.DOUBLE, 0.15, 0.0, 100.0))
                .field(SchemaField.number("roll-center-inset", SchemaField.Type.DOUBLE, 0.0, 0.0, 0.49))
                .field(SchemaField.number("loot-base-quality", SchemaField.Type.INT, 0, -100, 100))
                .field(SchemaField.number("fishing-base-quality", SchemaField.Type.INT, 0, -100, 100))
                .field(SchemaField.number("give-default-quality", SchemaField.Type.INT, 3, 0, 100))
                .field(SchemaField.number("luck-potion-quality-per-level",
                        SchemaField.Type.DOUBLE, 1.0, 0.0, 100.0));
        this.domain = new ConfigDomain(PATH, schema);
    }

    public ConfigDomain domain() {
        return domain;
    }

    /**
     * Wires the quality-tiers count as the authoritative step count: when the supplier returns a value
     * &gt;= 0 (tiers configured), it overrides the numeric {@code max-quality}. Called once by
     * {@code ConfigManager} so the tier list length drives the number of quality steps.
     */
    public void useEffectiveMaxOverride(IntSupplier override) {
        this.effectiveMaxOverride = override == null ? () -> -1 : override;
    }

    /** The effective highest quality level: tier-count-derived when tiers exist, else numeric max-quality. */
    public int maxQuality() {
        int override = effectiveMaxOverride.getAsInt();
        return override >= 0 ? override : domain.get().getInt("max-quality");
    }

    /**
     * σ of the quality bell's UPPER half (上振れ幅), from {@code spread-up}. Shared by crafts and mob
     * drops; a craft additionally widens this by the crafter's upswing perk. 0 pins the up side at the mode.
     */
    public double spreadUp() {
        return Math.max(0.0, domain.get().getDouble("spread-up"));
    }

    /** σ of the quality bell's LOWER half (下振れ幅), from {@code spread-down}. 0 pins the down side at the mode. */
    public double spreadDown() {
        return Math.max(0.0, domain.get().getDouble("spread-down"));
    }

    /**
     * The quality-dependent roll distribution ({@link QualityRollModel}, 段2) for the per-item {@code random}
     * stat layer: the split-normal roll spreads {@code roll-spread-up}/{@code roll-spread-down} plus the
     * effective max quality. Distinct from the quality-LEVEL split-normal ({@link #spreadUp}/{@link
     * #spreadDown}); this shapes how a stat rolls within its {@code {min, max}} range once the quality level
     * is fixed (mode = quality fraction, up/down spread tuned independently). Wired into
     * {@code ItemStatsConfig#useRollModel} so item-stats derivation can reach it.
     */
    public QualityRollModel rollModel() {
        TypedConfig snapshot = domain.get();
        return new QualityRollModel(
                maxQuality(),
                Math.max(0.0, snapshot.getDouble("roll-spread-up")),
                Math.max(0.0, snapshot.getDouble("roll-spread-down")),
                Math.max(0.0, Math.min(0.49, snapshot.getDouble("roll-center-inset"))));
    }

    /** Default quality for {@code /trinityforge give <item>} when no quality argument is given. */
    public int giveDefaultQuality() {
        return Math.min(domain.get().getInt("give-default-quality"), maxQuality());
    }

    /** Base mode for pickup/loot equipment before luck and per-item offsets. */
    public int lootBaseQuality() { return domain.get().getInt("loot-base-quality"); }

    /** Base mode for fished equipment before fishing level, luck and per-item offsets. */
    public int fishingBaseQuality() { return domain.get().getInt("fishing-base-quality"); }

    /**
     * 幸運のポーション効果 1 レベルあたり、<b>作業台・儀式・醸造</b>の品質ポイントへ加算する量
     * (2026-08-20 ユーザー要望「醸造・作業台・儀式の各品質ptも幸運のポーションレベルに応じて上がるように」)。
     * 0 にするとこの機能だけを切れる。既定 1.0 ＝ 幸運 I で品質 +1。
     *
     * <p>釣り・拾得は別経路で、装備/パーク由来の {@code loot_luck} へ
     * {@link com.trinityforge.stats.PlayerLootLuckSource} が<b>常に 1 レベル = +1.0</b> で合算する
     * (あちらは {@code loot_luck} と同じ単位なので、このつまみでは動かない)。
     */
    public double luckPotionQualityPerLevel() {
        return Math.max(0.0, domain.get().getDouble("luck-potion-quality-per-level"));
    }
}
