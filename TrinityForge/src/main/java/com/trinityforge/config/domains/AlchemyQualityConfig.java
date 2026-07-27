package com.trinityforge.config.domains;

import com.trinityforge.config.ConfigDomain;
import com.trinityforge.config.ConfigSchema;
import com.trinityforge.config.SchemaField;

/**
 * Typed accessor for {@code stats/alchemy-quality.yml}: converts the per-player
 * {@code potion_quality_bonus} stat (accumulated from {@code skilltree/alchemy.yml} "品質+N" nodes)
 * into the brewed potion's effect duration and amplifier. Consumed by
 * {@code com.trinityforge.listeners.PotionQualityListener}.
 *
 * <p>Amplifier is floor-cast to an integer after scaling (design decision confirmed by the user:
 * "強度は切り捨てで整数にキャストすることで2品質ごとに+1とかもできるかな" — setting
 * {@link #amplifierPerQuality()} to {@code 0.5} yields "+1 amplifier every 2 quality points").
 */
public final class AlchemyQualityConfig {

    public static final String PATH = "stats/alchemy-quality.yml";

    private final ConfigDomain domain;

    public AlchemyQualityConfig() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("duration-ticks-per-quality", SchemaField.Type.DOUBLE, 20.0, 0.0, 100000.0))
                .field(SchemaField.number("amplifier-per-quality", SchemaField.Type.DOUBLE, 0.5, 0.0, 100.0))
                .field(SchemaField.number("lingering-splash-duration-ticks-per-quality", SchemaField.Type.DOUBLE, 10.0, 0.0, 100000.0));
        this.domain = new ConfigDomain(PATH, schema);
    }

    public ConfigDomain domain() {
        return domain;
    }

    /** Effect-duration (ticks) added per {@code potion_quality_bonus} point. */
    public double durationTicksPerQuality() {
        return Math.max(0.0, domain.get().getDouble("duration-ticks-per-quality"));
    }

    /** Amplifier added per {@code potion_quality_bonus} point, before the caller floor-casts the total. */
    public double amplifierPerQuality() {
        return Math.max(0.0, domain.get().getDouble("amplifier-per-quality"));
    }

    /** Extra duration (ticks) per quality point, added on top of {@link #durationTicksPerQuality()} for SPLASH/LINGERING bottles only. */
    public double lingeringSplashDurationTicksPerQuality() {
        return Math.max(0.0, domain.get().getDouble("lingering-splash-duration-ticks-per-quality"));
    }
}
