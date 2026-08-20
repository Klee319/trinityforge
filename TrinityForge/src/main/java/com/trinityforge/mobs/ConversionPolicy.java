package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;

import java.util.Objects;

/**
 * Config-driven rules for turning an EliteMobs mob into a {@link MobProfile} (loaded from
 * {@code combat/mob-import.yml}). EliteMobs files carry no defense/attack fields, so the defender
 * profile AND the attacker profile are <em>synthesized</em>: each value is
 * {@code base + perLevel * level}, clamped. This keeps the conversion fully tunable in config
 * (no hardcoded balance) and gives every distributed mob a sensible, level-scaled starting profile
 * the admin can then refine.
 *
 * @param levelSource   whether to take the mob's level from the EliteMobs file or a fixed value
 * @param fixedLevel    level used when {@code levelSource == FIXED}
 * @param defaultLevel  level used when the EliteMobs level is missing/non-numeric (e.g. "dynamic")
 * @param defaultTheme  dungeon theme applied to every converted mob (may be blank)
 * @param physical      physical-component ramps
 * @param magical       magical-component ramps
 * @param armorStrength shared 防具強度 ramp
 * @param attack        attacker-side ramps (mob→player symmetric pipeline routing)
 * @param maxHealth     max-health ramp; the synthesized {@code base + perLevel * level} becomes the
 *                      profile's TrinityForge-driven HP (0 = unconfigured, mob keeps EliteMobs HP)
 * @param variance      per-individual variance (厳選幅) applied to HP/attack at runtime-scale time
 *                       only (never at bake time); see {@code ConfigManager#resolveRuntimeProfile(String, int, long)}
 */
public record ConversionPolicy(LevelSource levelSource,
                               int fixedLevel,
                               int defaultLevel,
                               String defaultTheme,
                               DefenseRamp physical,
                               DefenseRamp magical,
                               Ramp armorStrength,
                               AttackRamp attack,
                               Ramp maxHealth,
                               Variance variance) {

    public ConversionPolicy {
        Objects.requireNonNull(levelSource, "levelSource");
        Objects.requireNonNull(defaultTheme, "defaultTheme");
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(magical, "magical");
        Objects.requireNonNull(armorStrength, "armorStrength");
        Objects.requireNonNull(attack, "attack");
        Objects.requireNonNull(maxHealth, "maxHealth");
        Objects.requireNonNull(variance, "variance");
    }

    /** Back-compat: attack + max-health ramps default to zero (attack unconfigured, HP untouched). */
    public ConversionPolicy(LevelSource levelSource, int fixedLevel, int defaultLevel,
                            String defaultTheme, DefenseRamp physical, DefenseRamp magical,
                            Ramp armorStrength) {
        this(levelSource, fixedLevel, defaultLevel, defaultTheme, physical, magical, armorStrength,
                AttackRamp.ZERO, new Ramp(0.0, 0.0), Variance.ZERO);
    }

    /** Back-compat: max-health ramp defaults to zero (= unconfigured HP, mob keeps EliteMobs HP). */
    public ConversionPolicy(LevelSource levelSource, int fixedLevel, int defaultLevel,
                            String defaultTheme, DefenseRamp physical, DefenseRamp magical,
                            Ramp armorStrength, AttackRamp attack) {
        this(levelSource, fixedLevel, defaultLevel, defaultTheme, physical, magical, armorStrength,
                attack, new Ramp(0.0, 0.0), Variance.ZERO);
    }

    /** Back-compat: the pre-variance 9-arg canonical shape; variance defaults to zero. */
    public ConversionPolicy(LevelSource levelSource, int fixedLevel, int defaultLevel,
                            String defaultTheme, DefenseRamp physical, DefenseRamp magical,
                            Ramp armorStrength, AttackRamp attack, Ramp maxHealth) {
        this(levelSource, fixedLevel, defaultLevel, defaultTheme, physical, magical, armorStrength,
                attack, maxHealth, Variance.ZERO);
    }

    /** {@code hpVariance()} shorthand; see {@link Variance#hp()}. */
    public double hpVariance() {
        return variance.hp();
    }

    /** {@code attackVariance()} shorthand; see {@link Variance#attack()}. */
    public double attackVariance() {
        return variance.attack();
    }

    /** Where a converted mob's level comes from. */
    public enum LevelSource {
        ELITEMOBS,
        FIXED
    }

    /**
     * A per-level ramp. The linear part {@code base + perLevel * level} is multiplied by a geometric
     * growth term {@code growth^(level / growthInterval)} so the ramp can track the game's roughly
     * <em>exponential</em> weapon/armor tier curve (wood→infinity), which a purely linear ramp cannot.
     *
     * <p>{@code growth == 1.0} makes the geometric term vanish, reducing the ramp to the historical
     * linear {@code base + perLevel * level} — so every pre-existing config (and every {@code new
     * Ramp(base, perLevel)} call site) keeps its exact behaviour.
     *
     * @param base          value at level 0 (before growth)
     * @param perLevel      linear per-level slope (before growth)
     * @param growth        geometric factor applied per {@code growthInterval} levels (1.0 = linear)
     * @param growthInterval level span for one {@code growth} multiplication (must be &gt; 0)
     * @param highLevelFrom level at/above which {@code highLevelPerLevel} starts adding on top of the
     *                      base curve (2026-08-03, 45+難易度修正). {@code Double.POSITIVE_INFINITY}
     *                      (the default via every back-compat constructor) never triggers — full
     *                      back-compat for every existing config/call site.
     * @param highLevelPerLevel additive slope applied for the levels at/above {@code highLevelFrom}:
     *                      {@code + highLevelPerLevel * (level - highLevelFrom)}. Deliberately ADDITIVE
     *                      (not a second geometric growth) so it works even when the base curve is
     *                      itself zero at the breakpoint (e.g. {@code combat/mob-import.yml}'s
     *                      {@code flat-defense}/{@code defense-rate}, which are 0 at every level today
     *                      — a multiplicative "grow harder past 45" term would stay 0×anything=0 there).
     *                      At exactly {@code level == highLevelFrom} this contributes 0 (continuity with
     *                      the pre-breakpoint curve); the extra term only becomes visible strictly above
     *                      the threshold.
     */
    public record Ramp(double base, double perLevel, double growth, double growthInterval,
                       double highLevelFrom, double highLevelPerLevel) {

        public Ramp {
            if (!(growthInterval > 0.0) || !Double.isFinite(growthInterval)) {
                growthInterval = 1.0;
            }
            if (!Double.isFinite(growth) || growth < 0.0) {
                growth = 1.0;
            }
            if (Double.isNaN(highLevelFrom)) {
                highLevelFrom = Double.POSITIVE_INFINITY;
            }
            if (!Double.isFinite(highLevelPerLevel)) {
                highLevelPerLevel = 0.0;
            }
        }

        /** Back-compat: a purely linear ramp ({@code growth = 1.0}), no high-level breakpoint. */
        public Ramp(double base, double perLevel) {
            this(base, perLevel, 1.0, 1.0, Double.POSITIVE_INFINITY, 0.0);
        }

        /** Back-compat: a growth-capable ramp with no high-level breakpoint. */
        public Ramp(double base, double perLevel, double growth, double growthInterval) {
            this(base, perLevel, growth, growthInterval, Double.POSITIVE_INFINITY, 0.0);
        }

        public double at(int level) {
            int lvl = Math.max(0, level);
            double linear = base + perLevel * lvl;
            double value = growth == 1.0 ? linear : linear * Math.pow(growth, lvl / growthInterval);
            if (highLevelPerLevel != 0.0 && lvl >= highLevelFrom) {
                value += highLevelPerLevel * (lvl - highLevelFrom);
            }
            return value;
        }
    }

    /** The four defender ramps for one damage component. */
    public record DefenseRamp(Ramp defenseRate, Ramp resistance, Ramp damageReduction, Ramp flatDefense) {

        public DefenseRamp {
            Objects.requireNonNull(defenseRate, "defenseRate");
            Objects.requireNonNull(resistance, "resistance");
            Objects.requireNonNull(damageReduction, "damageReduction");
            Objects.requireNonNull(flatDefense, "flatDefense");
        }

        /** Builds raw defender inputs at {@code level}; the shared combat choke applies configured bounds. */
        public DefenseStats at(int level, double armorStrength) {
            return new DefenseStats(
                    defenseRate.at(level),
                    resistance.at(level),
                    damageReduction.at(level),
                    flatDefense.at(level),
                    armorStrength);
        }
    }

    /**
     * The attacker-side ramps for the mob's stamped {@link AttackStats} (same field order as
     * {@code combat/mob-types.yml attack:}, plus {@code magicRatio} — 2026-08-03: the synthesis ramp
     * used to have no notion of magic-ratio at all, so every EliteMobs mob resolved through this
     * policy (i.e. {@code unknown-mobs.synthesize: true} and {@code level: dynamic} dungeon mobs — the
     * vast majority of dungeon content) was structurally 100% physical regardless of what
     * {@code combat/mob-import.yml} said, unlike {@code combat/mob-types.yml} field mobs where ~40%
     * of tagged entries carry a nonzero magic-ratio). All-zero ramps synthesize an unconfigured attack
     * ({@link AttackStats#plain}(0)) so the mob keeps its vanilla/EliteMobs damage untouched.
     */
    public record AttackRamp(Ramp attackPower, Ramp flatBonusDamage, Ramp percentBonusDamage,
                             Ramp critChance, Ramp critDamage, Ramp penetration,
                             Ramp damageModifier, Ramp fixedDamage, Ramp magicRatio) {

        public static final AttackRamp ZERO;

        static {
            Ramp zero = new Ramp(0.0, 0.0);
            ZERO = new AttackRamp(zero, zero, zero, zero, zero, zero, new Ramp(1.0, 0.0), zero, zero);
        }

        public AttackRamp {
            Objects.requireNonNull(attackPower, "attackPower");
            Objects.requireNonNull(flatBonusDamage, "flatBonusDamage");
            Objects.requireNonNull(percentBonusDamage, "percentBonusDamage");
            Objects.requireNonNull(critChance, "critChance");
            Objects.requireNonNull(critDamage, "critDamage");
            Objects.requireNonNull(penetration, "penetration");
            Objects.requireNonNull(damageModifier, "damageModifier");
            Objects.requireNonNull(fixedDamage, "fixedDamage");
            Objects.requireNonNull(magicRatio, "magicRatio");
        }

        /**
         * Back-compat 8-ramp constructor (pre-2026-08-03 shape): {@code magicRatio} defaults to a zero
         * ramp (0.0 at every level = 完全物理、従来どおり), matching {@link AttackStats}'s own 8-arg
         * back-compat constructor.
         */
        public AttackRamp(Ramp attackPower, Ramp flatBonusDamage, Ramp percentBonusDamage,
                          Ramp critChance, Ramp critDamage, Ramp penetration,
                          Ramp damageModifier, Ramp fixedDamage) {
            this(attackPower, flatBonusDamage, percentBonusDamage, critChance, critDamage, penetration,
                    damageModifier, fixedDamage, new Ramp(0.0, 0.0));
        }

        /**
         * Builds the attacker stats at {@code level}; zero and negative ramp outputs are retained.
         */
        public AttackStats at(int level) {
            return new AttackStats(
                    attackPower.at(level),
                    flatBonusDamage.at(level),
                    percentBonusDamage.at(level),
                    critChance.at(level),
                    critDamage.at(level),
                    penetration.at(level),
                    damageModifier.at(level),
                    fixedDamage.at(level),
                    magicRatio.at(level));
        }
    }

    /**
     * Per-individual variance (厳選幅) applied at runtime-scale time only (see
     * {@code ConfigManager#resolveRuntimeProfile} overload), never at bake time. Negative authored
     * values clamp to 0 (no variance, not "flipped").
     */
    public record Variance(double hp, double attack) {
        public Variance {
            if (!Double.isFinite(hp) || hp < 0.0) hp = 0.0;
            if (!Double.isFinite(attack) || attack < 0.0) attack = 0.0;
        }

        public static final Variance ZERO = new Variance(0.0, 0.0);
    }
}
