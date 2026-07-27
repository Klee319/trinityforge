package com.trinityforge.config.domains;

import com.trinityforge.config.ConfigDomain;
import com.trinityforge.config.ConfigSchema;
import com.trinityforge.config.SchemaField;
import com.trinityforge.hate.HateSettings;

/**
 * Typed accessor for {@code hate/rates.yml}: the leak-control knobs for the aggro/threat
 * subsystem (gap C5). These govern the <em>mechanism</em> (caps, decay, TTL, sweep cadence),
 * not hate balance; the actual threat-rate design (tank/beastmaster coefficients, R2) is a
 * separate decision and is intentionally not encoded here beyond the neutral
 * {@code threat.per-damage = 1.0}.
 *
 * <p>Defaults are deliberately conservative so enabling the subsystem changes no balance:
 * decay off, generous caps, and a TTL that only reclaims long-abandoned entries.
 */
public final class HateConfig {

    public static final String PATH = "hate/rates.yml";

    private final ConfigDomain domain;

    public HateConfig() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("limits.max-tracked-mobs", SchemaField.Type.INT, 5000, 1, 1_000_000))
                .field(SchemaField.number("limits.max-attackers-per-mob", SchemaField.Type.INT, 64, 1, 10_000))
                .field(SchemaField.of("decay.enabled", SchemaField.Type.BOOLEAN, false))
                .field(SchemaField.number("decay.per-second", SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                .field(SchemaField.number("eviction.entry-ttl-seconds", SchemaField.Type.INT, 600, 0, 86_400))
                .field(SchemaField.number("sweep.interval-ticks", SchemaField.Type.INT, 1200, 20, 72_000))
                .field(SchemaField.number("threat.per-damage", SchemaField.Type.DOUBLE, 1.0, 0.0, 1000.0));
        this.domain = new ConfigDomain(PATH, schema);
    }

    public ConfigDomain domain() {
        return domain;
    }

    public int maxTrackedMobs() {
        return domain.get().getInt("limits.max-tracked-mobs");
    }

    public int maxAttackersPerMob() {
        return domain.get().getInt("limits.max-attackers-per-mob");
    }

    public boolean decayEnabled() {
        return domain.get().getBoolean("decay.enabled");
    }

    public double decayPerSecond() {
        return domain.get().getDouble("decay.per-second");
    }

    public int entryTtlSeconds() {
        return domain.get().getInt("eviction.entry-ttl-seconds");
    }

    public int sweepIntervalTicks() {
        return domain.get().getInt("sweep.interval-ticks");
    }

    public double threatPerDamage() {
        return domain.get().getDouble("threat.per-damage");
    }

    /** Project the live config into an immutable snapshot for {@link com.trinityforge.hate.HateTable}. */
    public HateSettings toSettings() {
        return new HateSettings(
                maxTrackedMobs(),
                maxAttackersPerMob(),
                decayEnabled(),
                decayPerSecond(),
                entryTtlSeconds() * 1000L,
                threatPerDamage());
    }
}
