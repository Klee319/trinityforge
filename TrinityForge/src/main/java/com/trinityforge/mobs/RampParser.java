package com.trinityforge.mobs;

import com.trinityforge.mobs.ConversionPolicy.AttackRamp;
import com.trinityforge.mobs.ConversionPolicy.DefenseRamp;
import com.trinityforge.mobs.ConversionPolicy.Ramp;
import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * Shared parsing of {@code base + per-level} ramps from config, used by both the global conversion
 * policy ({@code MobImportConfig}) and dungeon themes ({@code DungeonThemeConfig}) so the ramp YAML
 * shape is defined in exactly one place. Missing sections degrade to a zero ramp (fail-soft).
 */
public final class RampParser {

    private RampParser() {
    }

    /**
     * A {@code { base, per-level, growth, growth-interval, high-level-from, high-level-per-level }}
     * ramp; null section yields a zero (linear) ramp. {@code growth}/{@code growth-interval} default
     * to {@code 1.0} when absent, which makes {@link Ramp}'s geometric term vanish (= the historical
     * linear ramp, full back-compat). {@code high-level-from}/{@code high-level-per-level}
     * (2026-08-03, 45+難易度修正) default to "never triggers" ({@code Double.POSITIVE_INFINITY}) /
     * {@code 0.0} when absent — full back-compat for every config written before this pair existed.
     */
    public static Ramp ramp(ConfigurationSection section) {
        if (section == null) {
            return new Ramp(0.0, 0.0);
        }
        return new Ramp(section.getDouble("base", 0.0), section.getDouble("per-level", 0.0),
                section.getDouble("growth", 1.0), section.getDouble("growth-interval", 1.0),
                section.getDouble("high-level-from", Double.POSITIVE_INFINITY),
                section.getDouble("high-level-per-level", 0.0));
    }

    /**
     * Same as {@link #ramp(ConfigurationSection)}, but resolves {@code key} under {@code parent}
     * itself so a genuinely absent key (normal, silent) can be told apart from two authoring
     * mistakes, both logged as a warning under {@code contextPath} and both still falling back to a
     * zero ramp (fail-soft behavior is unchanged; TRINITY_SPEC 6's override-priority semantics are
     * still undecided, so this only adds visibility):
     * <ul>
     *   <li>the key exists but is a scalar, e.g. {@code resistance: 0.5} instead of
     *       {@code resistance: { base: 0.5, per-level: 0.0 }}</li>
     *   <li>the key resolves to a section but that section has neither {@code base} nor
     *       {@code per-level}</li>
     * </ul>
     */
    public static Ramp ramp(ConfigurationSection parent, String key, Logger log, String contextPath) {
        if (parent == null || !parent.contains(key)) {
            return new Ramp(0.0, 0.0);
        }
        if (!parent.isConfigurationSection(key)) {
            log.warning("[" + contextPath + "] '" + key + "' is a scalar value; expected a section "
                    + "with 'base'/'per-level'. Using a zero ramp.");
            return new Ramp(0.0, 0.0);
        }
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section != null && !section.contains("base") && !section.contains("per-level")) {
            log.warning("[" + contextPath + "] '" + key + "' section has neither 'base' nor "
                    + "'per-level'. Using a zero ramp.");
        }
        return ramp(section);
    }

    /** The four defender ramps ({@code defense-rate/resistance/damage-reduction/flat-defense}). */
    public static DefenseRamp defenseRamp(ConfigurationSection section) {
        return new DefenseRamp(
                ramp(child(section, "defense-rate")),
                ramp(child(section, "resistance")),
                ramp(child(section, "damage-reduction")),
                ramp(child(section, "flat-defense")));
    }

    /**
     * Same as {@link #defenseRamp(ConfigurationSection)}, but resolves {@code key} under
     * {@code parent} so a scalar mistake at the {@code physical:}/{@code magical:} level (e.g.
     * {@code physical: 0.5}) is also warned about, and each of the four child ramps is parsed via
     * {@link #ramp(ConfigurationSection, String, Logger, String)} so per-field mistakes are warned
     * too.
     */
    public static DefenseRamp defenseRamp(ConfigurationSection parent, String key, Logger log,
                                          String contextPath) {
        if (parent != null && parent.contains(key) && !parent.isConfigurationSection(key)) {
            log.warning("[" + contextPath + "] '" + key + "' is a scalar value; expected a section. "
                    + "Using zero ramps for all its fields.");
            return defenseRamp((ConfigurationSection) null);
        }
        ConfigurationSection section = parent == null ? null : parent.getConfigurationSection(key);
        String childPath = contextPath + "." + key;
        return new DefenseRamp(
                ramp(section, "defense-rate", log, childPath),
                ramp(section, "resistance", log, childPath),
                ramp(section, "damage-reduction", log, childPath),
                ramp(section, "flat-defense", log, childPath));
    }

    /**
     * The attacker-side ramps ({@code attack-power/flat-bonus-damage/percent-bonus-damage/
     * crit-chance/crit-damage/penetration/damage-modifier/fixed-damage/magic-ratio}), with the same
     * scalar-mistake warnings as {@link #defenseRamp(ConfigurationSection, String, Logger, String)}.
     * An absent {@code key} yields {@link AttackRamp#ZERO} (= unconfigured attack, fail-soft). A
     * present section with no {@code magic-ratio} child silently defaults to a zero ramp (0.0 at every
     * level = 完全物理) via {@link #ramp(ConfigurationSection, String, Logger, String)}'s normal
     * absent-key handling — no warning, matching every other optional attack field.
     */
    public static AttackRamp attackRamp(ConfigurationSection parent, String key, Logger log,
                                        String contextPath) {
        if (parent != null && parent.contains(key) && !parent.isConfigurationSection(key)) {
            log.warning("[" + contextPath + "] '" + key + "' is a scalar value; expected a section. "
                    + "Using zero ramps for all its fields.");
            return AttackRamp.ZERO;
        }
        ConfigurationSection section = parent == null ? null : parent.getConfigurationSection(key);
        if (section == null) {
            return AttackRamp.ZERO;
        }
        String childPath = contextPath + "." + key;
        return new AttackRamp(
                ramp(section, "attack-power", log, childPath),
                ramp(section, "flat-bonus-damage", log, childPath),
                ramp(section, "percent-bonus-damage", log, childPath),
                ramp(section, "crit-chance", log, childPath),
                ramp(section, "crit-damage", log, childPath),
                ramp(section, "penetration", log, childPath),
                damageModifierRamp(section, log, childPath),
                ramp(section, "fixed-damage", log, childPath),
                ramp(section, "magic-ratio", log, childPath));
    }

    private static Ramp damageModifierRamp(ConfigurationSection section, Logger log, String contextPath) {
        if (section == null || !section.contains("damage-modifier")) {
            return new Ramp(1.0, 0.0);
        }
        if (!section.isConfigurationSection("damage-modifier")) {
            log.warning("[" + contextPath + "] 'damage-modifier' is a scalar value; expected a section "
                    + "with 'base'/'per-level'. Using the neutral endpoint 1.0.");
            return new Ramp(1.0, 0.0);
        }
        ConfigurationSection modifier = section.getConfigurationSection("damage-modifier");
        if (modifier != null && !modifier.contains("base") && !modifier.contains("per-level")) {
            log.warning("[" + contextPath + "] 'damage-modifier' section has neither 'base' nor "
                    + "'per-level'. Using the neutral endpoint 1.0.");
            return new Ramp(1.0, 0.0);
        }
        return ramp(modifier);
    }

    private static ConfigurationSection child(ConfigurationSection parent, String path) {
        return parent == null ? null : parent.getConfigurationSection(path);
    }
}
