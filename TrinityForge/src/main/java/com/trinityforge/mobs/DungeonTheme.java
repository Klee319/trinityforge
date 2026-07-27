package com.trinityforge.mobs;

import com.trinityforge.mobs.ConversionPolicy.DefenseRamp;
import com.trinityforge.mobs.ConversionPolicy.Ramp;

import java.util.Objects;

/**
 * A named dungeon attribute theme (DUNGEON_SPEC: each dungeon has an attribute theme, e.g.
 * physical-heavy / magic-heavy). Immutable. A theme is a defense-bias preset applied when importing
 * a new dungeon's mobs: it overrides only the synthesized defender ramps and tags every converted
 * mob with its name ({@code MOB_DUNGEON_THEME}); the level settings stay with the global import
 * policy ({@code combat/mob-import.yml}).
 *
 * @param name          theme id (matches the YAML section name and the dungeon-theme tag)
 * @param physical      physical-component defender ramps
 * @param magical       magical-component defender ramps
 * @param armorStrength shared 防具強度 ramp
 */
public record DungeonTheme(String name,
                           DefenseRamp physical,
                           DefenseRamp magical,
                           Ramp armorStrength) {

    public DungeonTheme {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(magical, "magical");
        Objects.requireNonNull(armorStrength, "armorStrength");
    }

    /**
     * Combines this theme's defender ramps + name tag with {@code base}'s level settings, yielding
     * the {@link ConversionPolicy} the importer should use for this dungeon's mobs. The attacker
     * ramps AND the max-health ramp stay with the global policy (a theme is a defense-bias preset,
     * not an offense/HP one) — forwarding {@code base.maxHealth()} is required, otherwise a themed
     * import would silently reset every mob's TrinityForge-driven HP back to 0 (= unconfigured).
     * {@code base.variance()} is forwarded too so a themed policy keeps the same per-individual
     * variance as the base policy (a theme never resets it to zero).
     */
    public ConversionPolicy toPolicy(ConversionPolicy base) {
        Objects.requireNonNull(base, "base");
        return new ConversionPolicy(base.levelSource(), base.fixedLevel(), base.defaultLevel(),
                name, physical, magical, armorStrength, base.attack(), base.maxHealth(), base.variance());
    }
}
