package com.trinityforge.config.domains;

import com.trinityforge.combat.DamageType;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.ConfigDomain;
import com.trinityforge.config.ConfigSchema;
import com.trinityforge.config.SchemaField;

/**
 * Default defender stat profile applied to mobs that carry no addon profile (plain vanilla mobs),
 * so the symmetric pipeline treats every mob consistently (COMBAT_SYSTEM_SPEC 6). EliteMobs / dungeon
 * mobs supply their own profile via PDC; this is only the fallback baseline.
 *
 * <p>Defaults are the vanilla baseline (zero mitigation); raise them in {@code combat/mob-defaults.yml}
 * to make untagged mobs tougher without code changes. Rates are fractions in [0,1].
 */
public final class MobDefaultsConfig {

    public static final String PATH = "combat/mob-defaults.yml";
    private static final String ARMOR_STRENGTH = "armor-strength";

    private final ConfigDomain domain;

    public MobDefaultsConfig() {
        ConfigSchema schema = new ConfigSchema()
                .field(SchemaField.number("physical.defense-rate", SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                .field(SchemaField.number("physical.resistance", SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                .field(SchemaField.number("physical.damage-reduction", SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                .field(SchemaField.number("physical.flat-defense", SchemaField.Type.DOUBLE, 0.0, 0.0, 100000.0))
                .field(SchemaField.number("magical.defense-rate", SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                .field(SchemaField.number("magical.resistance", SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                .field(SchemaField.number("magical.damage-reduction", SchemaField.Type.DOUBLE, 0.0, 0.0, 1.0))
                .field(SchemaField.number("magical.flat-defense", SchemaField.Type.DOUBLE, 0.0, 0.0, 100000.0))
                .field(SchemaField.number(ARMOR_STRENGTH, SchemaField.Type.DOUBLE, 0.0, 0.0, 100000.0));
        this.domain = new ConfigDomain(PATH, schema);
    }

    public ConfigDomain domain() {
        return domain;
    }

    /** The default defender inputs for one component; 防具強度 is shared across both types. */
    public DefenseStats defaultDefense(DamageType type) {
        String prefix = switch (type) {
            case PHYSICAL -> "physical";
            case MAGICAL -> "magical";
            case TYPELESS -> null;
        };
        if (prefix == null) return DefenseStats.NONE;
        var config = domain.get();
        return new DefenseStats(
                config.getDouble(prefix + ".defense-rate"),
                config.getDouble(prefix + ".resistance"),
                config.getDouble(prefix + ".damage-reduction"),
                config.getDouble(prefix + ".flat-defense"),
                config.getDouble(ARMOR_STRENGTH));
    }
}
