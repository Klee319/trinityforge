package com.trinityforge.mobs;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** {@link MobStatOverride}: field-level merge semantics (only non-null fields overwrite the base). */
class MobStatOverrideTest {

    private static MobProfile baseProfile() {
        DefenseStats physical = new DefenseStats(0.1, 0.2, 0.3, 4.0, 0.5);
        DefenseStats magical = new DefenseStats(0.1, 0.2, 0.3, 4.0, 0.5);
        AttackStats attack = new AttackStats(10, 1, 0.1, 0.2, 0.3, 0.4, 1.0, 5);
        return new MobProfile("goblin_chief", 10, "dark", physical, magical, attack, 100.0, false);
    }

    @Test
    void emptyOverrideReturnsBaseUnchanged() {
        MobProfile base = baseProfile();
        assertSame(base, MobStatOverride.EMPTY.applyTo(base));
    }

    @Test
    void nullBaseReturnsNull() {
        assertEquals(null, MobStatOverride.EMPTY.applyTo(null));
    }

    @Test
    void topLevelFieldsOverwriteIndependently() {
        MobStatOverride override = new MobStatOverride(50, 999.0, null, null, null, null);
        MobProfile result = override.applyTo(baseProfile());
        assertEquals(50, result.level());
        assertEquals(999.0, result.maxHealth());
        // armorStrength untouched
        assertEquals(0.5, result.armorStrength());
        // id/dungeonTheme/dynamic preserved
        assertEquals("goblin_chief", result.id());
        assertEquals("dark", result.dungeonTheme());
    }

    @Test
    void physicalSubFieldsMergeIndependentlyOfMagical() {
        MobStatOverride.DefenseFieldOverride physicalOverride =
                new MobStatOverride.DefenseFieldOverride(0.9, null, null, null);
        MobStatOverride override = new MobStatOverride(null, null, null, physicalOverride, null, null);
        MobProfile base = baseProfile();
        MobProfile result = override.applyTo(base);

        assertEquals(0.9, result.physical().defenseRate());
        assertEquals(base.physical().resistance(), result.physical().resistance());
        assertEquals(base.physical().damageReduction(), result.physical().damageReduction());
        assertEquals(base.physical().flatDefense(), result.physical().flatDefense());
        assertEquals(base.magical(), result.magical());
    }

    @Test
    void attackSubFieldsMergeIndependently() {
        MobStatOverride.AttackFieldOverride attackOverride =
                new MobStatOverride.AttackFieldOverride(null, null, null, 0.99, null, null, null, null);
        MobStatOverride override = new MobStatOverride(null, null, null, null, null, attackOverride);
        MobProfile base = baseProfile();
        MobProfile result = override.applyTo(base);

        assertEquals(0.99, result.attack().critChance());
        assertEquals(base.attack().defaultDamage(), result.attack().defaultDamage());
        assertEquals(base.attack().flatBonusDamage(), result.attack().flatBonusDamage());
        assertEquals(base.attack().fixedDamage(), result.attack().fixedDamage());
    }

    @Test
    void armorStrengthOverrideAppliesToBothComponentsKeepingInvariant() {
        MobStatOverride override = new MobStatOverride(null, null, 0.75, null, null, null);
        MobProfile result = override.applyTo(baseProfile());
        assertEquals(0.75, result.physical().armorStrength());
        assertEquals(0.75, result.magical().armorStrength());
        // MobProfile's own compact constructor would throw if these ever diverged.
        assertEquals(result.physical().armorStrength(), result.magical().armorStrength());
    }
}
