package com.trinityforge.mobs;

import com.trinityforge.combat.DefenseStats;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Pure conversion of one EliteMobs custom-boss section into a {@link MobProfile} under a
 * {@link ConversionPolicy}. No Bukkit registry access (levels/fields are read as strings/doubles,
 * never resolved to EntityType/Material enums) so it is fully unit-testable headlessly.
 *
 * <p>EliteMobs files keep their fields at the document root, so the whole loaded YAML is the
 * {@code boss} section. Only the fields this server can use are read; everything else (powers,
 * drops, messages, scripts) is intentionally ignored.
 */
public final class EliteMobsMobMapping {

    private EliteMobsMobMapping() {
    }

    /** Converts {@code boss} (id = file name without extension) into a profile. */
    public static MobProfile convert(String id, ConfigurationSection boss, ConversionPolicy policy) {
        int level = resolveLevel(boss, policy);
        double armorStrength = policy.armorStrength().at(level);
        DefenseStats physical = policy.physical().at(level, armorStrength);
        DefenseStats magical = policy.magical().at(level, armorStrength);
        String theme = blankToNull(policy.defaultTheme());
        // Clamp to >= 0 so a negative ramp endpoint degrades to "unconfigured HP" instead of
        // failing MobProfile's validation and aborting the whole import.
        double maxHealth = Math.max(0.0, policy.maxHealth().at(level));
        boolean dynamic = isDynamicLevel(boss, policy);
        return new MobProfile(id, level, theme, physical, magical, policy.attack().at(level), maxHealth,
                dynamic);
    }

    /**
     * True when {@code boss} carries an EliteMobs {@code level: dynamic} (or other non-numeric level
     * keyword) under an {@code ELITEMOBS} level source, meaning the baked {@link #resolveLevel} value
     * is just {@code defaultLevel} and must be re-derived at spawn time from the dungeon's actual
     * runtime level. {@code FIXED} sources and numeric/absent levels are never dynamic.
     */
    static boolean isDynamicLevel(ConfigurationSection boss, ConversionPolicy policy) {
        if (policy.levelSource() == ConversionPolicy.LevelSource.FIXED) {
            return false;
        }
        String raw = boss.getString("level");
        if (raw == null) {
            return false;
        }
        try {
            Integer.parseInt(raw.trim());
            return false;
        } catch (NumberFormatException notNumeric) {
            return true;
        }
    }

    /**
     * Rebuilds a mob profile at an explicit runtime {@code level} under {@code policy}, for
     * {@code dynamic} mobs whose baked level (defaultLevel) does not track the dungeon's actual
     * runtime level. Uses the same synthesis formulas as {@link #convert}. The result is always
     * stamped {@code dynamic = true}.
     */
    public static MobProfile rebuildAt(String id, String theme, ConversionPolicy policy, int level) {
        int lvl = Math.max(0, level);
        double armorStrength = policy.armorStrength().at(lvl);
        DefenseStats physical = policy.physical().at(lvl, armorStrength);
        DefenseStats magical = policy.magical().at(lvl, armorStrength);
        String resolvedTheme = blankToNull(theme);
        double maxHealth = Math.max(0.0, policy.maxHealth().at(lvl));
        return new MobProfile(id, lvl, resolvedTheme, physical, magical, policy.attack().at(lvl),
                maxHealth, true);
    }

    /**
     * Resolves the converted mob's level. With {@code FIXED} the policy level wins; otherwise the
     * EliteMobs {@code level} is parsed as an integer, falling back to the policy default when it
     * is absent or non-numeric (EliteMobs allows "dynamic" and similar keywords).
     */
    static int resolveLevel(ConfigurationSection boss, ConversionPolicy policy) {
        if (policy.levelSource() == ConversionPolicy.LevelSource.FIXED) {
            return Math.max(0, policy.fixedLevel());
        }
        String raw = boss.getString("level");
        if (raw != null) {
            try {
                return Math.max(0, Integer.parseInt(raw.trim()));
            } catch (NumberFormatException notNumeric) {
                // EliteMobs "dynamic"/"miniboss"/etc — fall back to the configured default.
            }
        }
        return Math.max(0, policy.defaultLevel());
    }

    /** The EliteMobs display name, for informational output (may be empty). */
    static String sourceName(ConfigurationSection boss) {
        return orEmpty(boss.getString("name"));
    }

    /** The EliteMobs entity type string, for informational output (may be empty). */
    static String entityType(ConfigurationSection boss) {
        return orEmpty(boss.getString("entityType"));
    }

    private static String orEmpty(String raw) {
        return raw == null ? "" : raw;
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }
}
