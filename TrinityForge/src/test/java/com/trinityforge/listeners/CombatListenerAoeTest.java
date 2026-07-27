package com.trinityforge.listeners;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link CombatListener#aoeEligible}: the AoE (#2) target filter — self/primary
 * exclusion, player exclusion when {@code hit-players} is off, and the spherical radius boundary.
 */
class CombatListenerAoeTest {

    private static final double R2 = 25.0; // radius 5 → radiusSq 25

    @Test
    void mobInsideRadiusIsEligible() {
        assertTrue(CombatListener.aoeEligible(false, false, false, 16.0, R2, false));
    }

    @Test
    void mobExactlyOnRadiusIsEligible() {
        // distanceSq == radiusSq is inside (<=).
        assertTrue(CombatListener.aoeEligible(false, false, false, 25.0, R2, false));
    }

    @Test
    void mobOutsideRadiusIsExcluded() {
        assertFalse(CombatListener.aoeEligible(false, false, false, 25.01, R2, false));
    }

    @Test
    void attackerIsAlwaysExcluded() {
        assertFalse(CombatListener.aoeEligible(true, false, false, 1.0, R2, true));
    }

    @Test
    void primaryVictimIsAlwaysExcluded() {
        assertFalse(CombatListener.aoeEligible(false, true, false, 1.0, R2, true));
    }

    @Test
    void playerExcludedWhenHitPlayersOff() {
        assertFalse(CombatListener.aoeEligible(false, false, true, 1.0, R2, false));
    }

    @Test
    void playerIncludedWhenHitPlayersOn() {
        assertTrue(CombatListener.aoeEligible(false, false, true, 1.0, R2, true));
    }

    @Test
    void airbornePowerAttackScalesFinalTfDamage() {
        assertEquals(120.0, CombatListener.powerAttackDamage(100.0, 0.2, true), 1e-9);
    }

    @Test
    void groundedOrNegativePowerAttackDoesNotScaleDamage() {
        assertEquals(100.0, CombatListener.powerAttackDamage(100.0, 0.2, false), 1e-9);
        assertEquals(100.0, CombatListener.powerAttackDamage(100.0, -0.2, true), 1e-9);
    }

    // --- distance-damage-bonus (弓術) ---
    //
    // 2026-07-26: この計算は NativeCombatPerkListener が event.setDamage(getDamage() * 係数) で
    // 掛けていたが、CombatListener と同じ EventPriority.HIGH かつ登録順が先(TrinityForge.java 378行
    // → 423行)のため、attack-power を持つアイテム(BOW=69 / CROSSBOW=270.5)では
    // CombatListener の setDamage(BASE, total) に**毎回上書きされて消えていた**。
    // パイプライン内(total 算出後)へ移設したので、ここで純粋関数として固定する。

    @Test
    void distanceDamageScalesWithSixteenBlockUnits() {
        // 16ブロックで bonus がちょうど1倍分乗る。
        assertEquals(120.0, CombatListener.distanceDamage(100.0, 0.2, 16.0), 1e-9);
        // 8ブロックなら半分。
        assertEquals(110.0, CombatListener.distanceDamage(100.0, 0.2, 8.0), 1e-9);
    }

    @Test
    void distanceDamageCapsAtSixtyFourBlocks() {
        assertEquals(180.0, CombatListener.distanceDamage(100.0, 0.2, 64.0), 1e-9);
        // 64を超えても頭打ち(遠距離で青天井にしない)。
        assertEquals(180.0, CombatListener.distanceDamage(100.0, 0.2, 500.0), 1e-9);
    }

    @Test
    void distanceDamageIsNoOpWithoutBonusOrDistance() {
        assertEquals(100.0, CombatListener.distanceDamage(100.0, 0.0, 64.0), 1e-9);
        assertEquals(100.0, CombatListener.distanceDamage(100.0, -0.2, 64.0), 1e-9);
        assertEquals(100.0, CombatListener.distanceDamage(100.0, 0.2, 0.0), 1e-9);
        assertEquals(100.0, CombatListener.distanceDamage(100.0, Double.NaN, 16.0), 1e-9);
        assertEquals(100.0, CombatListener.distanceDamage(100.0, 0.2, Double.NaN), 1e-9);
    }
}
