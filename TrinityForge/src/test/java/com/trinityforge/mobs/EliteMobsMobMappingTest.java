package com.trinityforge.mobs;

import com.trinityforge.mobs.ConversionPolicy.DefenseRamp;
import com.trinityforge.mobs.ConversionPolicy.LevelSource;
import com.trinityforge.mobs.ConversionPolicy.Ramp;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EliteMobsMobMappingTest {

    private static final double DELTA = 1.0e-9;
    private static final Ramp ZERO = new Ramp(0.0, 0.0);
    private static final DefenseRamp ZERO_DEFENSE = new DefenseRamp(ZERO, ZERO, ZERO, ZERO);

    private static YamlConfiguration boss(String level) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("entityType", "ZOMBIE");
        y.set("name", "Boss");
        if (level != null) {
            y.set("level", level);
        }
        return y;
    }

    private static ConversionPolicy policy(LevelSource source, int fixed, int def,
                                           DefenseRamp physical, Ramp armor) {
        return new ConversionPolicy(source, fixed, def, "", physical, ZERO_DEFENSE, armor);
    }

    @Test
    void usesEliteMobsLevelAndSynthesizesDefenseFromRamps() {
        DefenseRamp physical = new DefenseRamp(new Ramp(0.1, 0.01), ZERO, ZERO, ZERO);
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, physical, new Ramp(0.0, 0.5));

        MobProfile profile = EliteMobsMobMapping.convert("debt_collector", boss("10"), policy);

        assertEquals(10, profile.level());
        assertEquals(0.2, profile.physical().defenseRate(), DELTA);   // 0.1 + 0.01 * 10
        assertEquals(5.0, profile.armorStrength(), DELTA);            // 0.0 + 0.5 * 10
        assertEquals("debt_collector", profile.id());
    }

    @Test
    void nonNumericLevelFallsBackToDefault() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        assertEquals(5, EliteMobsMobMapping.convert("x", boss("dynamic"), policy).level());
    }

    @Test
    void missingLevelFallsBackToDefault() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        assertEquals(5, EliteMobsMobMapping.convert("x", boss(null), policy).level());
    }

    @Test
    void fixedSourceIgnoresEliteMobsLevel() {
        ConversionPolicy policy = policy(LevelSource.FIXED, 7, 5, ZERO_DEFENSE, ZERO);
        assertEquals(7, EliteMobsMobMapping.convert("x", boss("10"), policy).level());
    }

    @Test
    void defenseRateRemainsRawUntilCombatClamp() {
        DefenseRamp physical = new DefenseRamp(new Ramp(0.5, 0.1), ZERO, ZERO, ZERO);
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, physical, ZERO);

        MobProfile profile = EliteMobsMobMapping.convert("x", boss("10"), policy);

        assertEquals(1.5, profile.physical().defenseRate(), DELTA);
    }

    @Test
    void zeroAttackRampSynthesizesUnconfiguredAttack() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        MobProfile profile = EliteMobsMobMapping.convert("x", boss("10"), policy);
        assertEquals(false, profile.hasAttack());
    }

    @Test
    void maxHealthSynthesizedFromRamp() {
        ConversionPolicy policy = new ConversionPolicy(LevelSource.ELITEMOBS, 99, 5, "",
                ZERO_DEFENSE, ZERO_DEFENSE, ZERO, ConversionPolicy.AttackRamp.ZERO,
                new Ramp(20.0, 5.0)); // max-health: 20 + 5*10 = 70

        MobProfile profile = EliteMobsMobMapping.convert("x", boss("10"), policy);

        assertEquals(70.0, profile.maxHealth(), DELTA);
        assertEquals(true, profile.hasMaxHealth());
    }

    @Test
    void zeroMaxHealthRampLeavesHpUnconfigured() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        MobProfile profile = EliteMobsMobMapping.convert("x", boss("10"), policy);
        assertEquals(0.0, profile.maxHealth(), DELTA);
        assertEquals(false, profile.hasMaxHealth());
    }

    @Test
    void negativeMaxHealthRampClampsToUnconfigured() {
        // A negative ramp endpoint must degrade to "unconfigured HP" (0), never fail MobProfile validation.
        ConversionPolicy policy = new ConversionPolicy(LevelSource.ELITEMOBS, 99, 5, "",
                ZERO_DEFENSE, ZERO_DEFENSE, ZERO, ConversionPolicy.AttackRamp.ZERO,
                new Ramp(-100.0, 0.0));

        MobProfile profile = EliteMobsMobMapping.convert("x", boss("10"), policy);

        assertEquals(0.0, profile.maxHealth(), DELTA);
        assertEquals(false, profile.hasMaxHealth());
    }

    @Test
    void attackRampSynthesizesLevelScaledAttackStats() {
        ConversionPolicy.AttackRamp attack = new ConversionPolicy.AttackRamp(
                new Ramp(2.0, 0.5),  // attack-power: 2 + 0.5*10 = 7
                ZERO, ZERO,
                new Ramp(0.05, 0.01), // crit-chance: 0.05 + 0.01*10 = 0.15
                ZERO, ZERO, ZERO, ZERO);
        ConversionPolicy policy = new ConversionPolicy(LevelSource.ELITEMOBS, 99, 5, "",
                ZERO_DEFENSE, ZERO_DEFENSE, ZERO, attack);

        MobProfile profile = EliteMobsMobMapping.convert("x", boss("10"), policy);

        assertEquals(true, profile.hasAttack());
        assertEquals(7.0, profile.attack().defaultDamage(), DELTA);
        assertEquals(0.15, profile.attack().critChance(), DELTA);
    }

    @Test
    void dynamicLevelKeywordIsDetectedAsDynamic() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        assertEquals(true, EliteMobsMobMapping.isDynamicLevel(boss("dynamic"), policy));
        MobProfile profile = EliteMobsMobMapping.convert("x", boss("dynamic"), policy);
        assertEquals(true, profile.dynamic());
    }

    @Test
    void numericLevelIsNotDynamic() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        assertEquals(false, EliteMobsMobMapping.isDynamicLevel(boss("20"), policy));
        MobProfile profile = EliteMobsMobMapping.convert("x", boss("20"), policy);
        assertEquals(false, profile.dynamic());
    }

    @Test
    void missingLevelIsNotDynamic() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        assertEquals(false, EliteMobsMobMapping.isDynamicLevel(boss(null), policy));
    }

    @Test
    void fixedSourceIsNeverDynamic() {
        ConversionPolicy policy = policy(LevelSource.FIXED, 7, 5, ZERO_DEFENSE, ZERO);
        assertEquals(false, EliteMobsMobMapping.isDynamicLevel(boss("dynamic"), policy));
    }

    @Test
    void rebuildAtProducesLevelScaledDynamicProfile() {
        DefenseRamp physical = new DefenseRamp(new Ramp(0.1, 0.01), ZERO, ZERO, ZERO);
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, physical, new Ramp(0.0, 0.5));

        MobProfile atLevel3 = EliteMobsMobMapping.rebuildAt("x", "", policy, 3);
        MobProfile atLevel30 = EliteMobsMobMapping.rebuildAt("x", "", policy, 30);

        assertEquals(3, atLevel3.level());
        assertEquals(30, atLevel30.level());
        assertEquals(true, atLevel3.dynamic());
        assertEquals(true, atLevel30.dynamic());
        assertEquals(0.13, atLevel3.physical().defenseRate(), DELTA);   // 0.1 + 0.01*3
        assertEquals(0.4, atLevel30.physical().defenseRate(), DELTA);   // 0.1 + 0.01*30
        assertEquals(1.5, atLevel3.armorStrength(), DELTA);             // 0.5*3
        assertEquals(15.0, atLevel30.armorStrength(), DELTA);           // 0.5*30
    }

    @Test
    void rebuildAtClampsNegativeLevelToZero() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        MobProfile profile = EliteMobsMobMapping.rebuildAt("x", "", policy, -5);
        assertEquals(0, profile.level());
    }

    @Test
    void rebuildAtBlanksEmptyThemeToNull() {
        ConversionPolicy policy = policy(LevelSource.ELITEMOBS, 99, 5, ZERO_DEFENSE, ZERO);
        MobProfile profile = EliteMobsMobMapping.rebuildAt("x", "  ", policy, 1);
        assertEquals(null, profile.dungeonTheme());
    }
}
