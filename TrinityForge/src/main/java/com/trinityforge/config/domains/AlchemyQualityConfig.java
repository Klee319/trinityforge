package com.trinityforge.config.domains;

import com.trinityforge.config.ConfigDomain;
import com.trinityforge.config.ConfigSchema;
import com.trinityforge.config.SchemaField;

/**
 * Typed accessor for {@code stats/alchemy-quality.yml}: converts the per-player
 * {@code potion_quality_bonus} stat (accumulated from {@code skilltree/alchemy.yml} "品質+N" nodes)
 * into the brewed potion's effect duration. Consumed by
 * {@code com.trinityforge.listeners.PotionQualityListener}.
 *
 * <p>強度(amplifier)は品質では動かさない。持続時間だけ、品質 0.1pt あたり
 * {@link #durationPercentPerTenthPoint()} %（既定 1%）伸びる。0 未満なら同じ割合で短くなる。
 */
public final class AlchemyQualityConfig {

    public static final String PATH = "stats/alchemy-quality.yml";

    private final ConfigDomain domain;

    public AlchemyQualityConfig() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("duration-percent-per-tenth-point", SchemaField.Type.DOUBLE, 1.0, 0.0, 100.0));
        this.domain = new ConfigDomain(PATH, schema);
    }

    public ConfigDomain domain() {
        return domain;
    }

    /**
     * 品質 0.1 ポイントあたり、持続時間が何%伸びるか。既定 1.0（= 1.0pt で +10%）。
     * 負の品質ポイントでは同じ係数で短くなる。
     */
    public double durationPercentPerTenthPoint() {
        return Math.max(0.0, domain.get().getDouble("duration-percent-per-tenth-point"));
    }

    /**
     * {@code 1 + qualityPoints * (percentPerTenth / 10)}。0.1pt・1% なら 1.01 倍。
     */
    public static double durationMultiplier(double qualityPoints, double percentPerTenth) {
        return 1.0 + qualityPoints * (percentPerTenth / 10.0);
    }
}
