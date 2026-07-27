package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates the {@link DefenderProfile} dodge clamp (B2): additive armor 回避 rolls can exceed 1.0,
 * which would otherwise dodge every hit (permanent invincibility) under {@link DodgeResolver#RANDOM}.
 */
class DefenderProfileTest {

    @Test
    void dodgeAboveOneIsClampedToOne() {
        DefenderProfile profile = new DefenderProfile(DefenseStats.NONE, 1.2);
        assertEquals(1.0, profile.dodgeChance());
    }

    @Test
    void negativeDodgeIsPreserved() {
        DefenderProfile profile = new DefenderProfile(DefenseStats.NONE, -0.3);
        assertEquals(-0.3, profile.dodgeChance());
    }

    @Test
    void nanDodgeCollapsesToZero() {
        DefenderProfile profile = new DefenderProfile(DefenseStats.NONE, Double.NaN);
        assertEquals(0.0, profile.dodgeChance());
    }

    @Test
    void inRangeDodgeIsPreserved() {
        DefenderProfile profile = new DefenderProfile(DefenseStats.NONE, 0.25);
        assertEquals(0.25, profile.dodgeChance());
    }
}
