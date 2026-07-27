package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;

import java.util.Objects;

/**
 * One mob's converted stat profile (concern: bulk-convert distributed EliteMobs mobs to this
 * server's stats). Immutable. Maps onto the mob PDC the EliteMobs fork stamps at spawn
 * ({@code MobData}): a level, an optional dungeon theme, per-component defender inputs, and an
 * optional attacker-side profile (mob→player symmetric pipeline routing).
 *
 * <p>Physical and magical {@link DefenseStats} share one 防具強度 (armorStrength) value by
 * construction, matching how {@code MobData} reads a single shared armor-strength key.
 *
 * @param id           the mob id (EliteMobs file name without extension)
 * @param level        combat level stamped to {@code MOB_LEVEL}
 * @param dungeonTheme attribute theme, or {@code null} when unthemed
 * @param physical     defender inputs vs physical damage
 * @param magical      defender inputs vs magical damage
 * @param attack       attacker-side stats (neutral = unconfigured; see {@link #hasAttack()})
 * @param maxHealth    the mob's TrinityForge-driven max health; {@code 0} = unconfigured, so the
 *                     EliteMobs fork keeps its own (level-scaled) HP untouched (see
 *                     {@link #hasMaxHealth()})
 * @param dynamic      {@code true} when this is an EliteMobs {@code level: dynamic} (or similar
 *                     non-numeric level keyword) mob that must be re-derived at spawn time from the
 *                     dungeon's actual runtime level, rather than the baked {@link #level()} (which
 *                     for a dynamic mob is just {@code defaultLevel}). {@code false} (the default via
 *                     every back-compat constructor) keeps the baked profile as-is.
 */
public record MobProfile(String id,
                         int level,
                         String dungeonTheme,
                         DefenseStats physical,
                         DefenseStats magical,
                         AttackStats attack,
                         double maxHealth,
                         boolean dynamic) {

    public MobProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(magical, "magical");
        Objects.requireNonNull(attack, "attack");
        if (level < 0) {
            throw new IllegalArgumentException("level must be >= 0: " + level);
        }
        if (maxHealth < 0) {
            throw new IllegalArgumentException("maxHealth must be >= 0: " + maxHealth);
        }
        // Compare with a tolerance rather than exact bit equality: both values originate from the same
        // armor-strength input but may arrive via independent Math.max/parse paths, so an exact != is
        // fragile to harmless floating-point divergence.
        if (Math.abs(physical.armorStrength() - magical.armorStrength()) > 1.0e-9) {
            throw new IllegalArgumentException("armorStrength must match across components: "
                    + physical.armorStrength() + " vs " + magical.armorStrength());
        }
    }

    /** Back-compat: a full profile with a TrinityForge-driven HP and no dynamic flag ({@code false}). */
    public MobProfile(String id, int level, String dungeonTheme,
                      DefenseStats physical, DefenseStats magical, AttackStats attack, double maxHealth) {
        this(id, level, dungeonTheme, physical, magical, attack, maxHealth, false);
    }

    /** Back-compat: a full profile with no TrinityForge-driven HP (mob keeps its EliteMobs HP). */
    public MobProfile(String id, int level, String dungeonTheme,
                      DefenseStats physical, DefenseStats magical, AttackStats attack) {
        this(id, level, dungeonTheme, physical, magical, attack, 0.0, false);
    }

    /** Back-compat: a defense-only profile with an unconfigured attack and no TF-driven HP. */
    public MobProfile(String id, int level, String dungeonTheme,
                      DefenseStats physical, DefenseStats magical) {
        this(id, level, dungeonTheme, physical, magical, AttackStats.plain(0), 0.0, false);
    }

    /** The shared 防具強度 value (identical on both components). */
    public double armorStrength() {
        return physical.armorStrength();
    }

    /** Returns a copy with only {@link #maxHealth()} replaced (e.g. runtime variance scaling). */
    public MobProfile withMaxHealth(double newMaxHealth) {
        return new MobProfile(id, level, dungeonTheme, physical, magical, attack, newMaxHealth, dynamic);
    }

    /** Returns a copy with only {@link #attack()} replaced (e.g. runtime variance scaling). */
    public MobProfile withAttack(AttackStats newAttack) {
        return new MobProfile(id, level, dungeonTheme, physical, magical, newAttack, maxHealth, dynamic);
    }

    /**
     * True when this profile carries a TrinityForge-driven max health ({@code maxHealth > 0}). The
     * EliteMobs fork only overrides an entity's HP with {@link #maxHealth()} in that case; an
     * unconfigured (0) profile leaves the mob's own EliteMobs level-scaled HP untouched.
     */
    public boolean hasMaxHealth() {
        return maxHealth > 0;
    }

    /**
     * True when this profile carries a configured attacker side (any non-zero field). The spawn
     * stamper only writes the {@code MOB_ATTACK_*} PDC keys in that case, so an unconfigured mob
     * keeps its vanilla/EliteMobs damage untouched (mirrors {@code MobTypeSpawnListener}'s
     * configured-attack gate).
     */
    public boolean hasAttack() {
        return attack.defaultDamage() != 0 || attack.flatBonusDamage() != 0
                || attack.percentBonusDamage() != 0 || attack.critChance() != 0
                || attack.critDamage() != 0 || attack.penetration() != 0
                || attack.damageModifier() != 1 || attack.fixedDamage() != 0;
    }
}
