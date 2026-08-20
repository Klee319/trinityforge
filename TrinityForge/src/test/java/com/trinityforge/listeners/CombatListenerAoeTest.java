package com.trinityforge.listeners;

import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    // --- launchDistanceBlocks (2026-08-04, distance-damage-bonus 悪用防止) ---
    //
    // 発射地点↔着弾地点の距離を測る純粋関数。射手の「着弾時点の現在地」を絶対に使わないことが
    // このバグ修正の核心なので、World が食い違う/null な異常系は必ず0(ボーナス無し)へ落ちることを固定する。

    @Test
    void launchDistanceBlocksMeasuresLaunchToImpactDistance() {
        World world = mock(World.class);
        Location launch = new Location(world, 0.0, 64.0, 0.0);
        Location impact = new Location(world, 16.0, 64.0, 0.0);
        assertEquals(16.0, CombatListener.launchDistanceBlocks(launch, impact), 1e-9);
    }

    @Test
    void launchDistanceBlocksIgnoresWhereTheShooterCurrentlyIs() {
        // launch と impact が同一地点なら、このメソッドの呼び出し側(CombatListener本体)が渡す
        // impact 引数は victim の位置であって shooter の現在地ではない ── shooter がどれだけ離れて
        // いても、このメソッド自体はその情報を一切受け取らない(=引数に無い値は結果に影響し得ない)。
        World world = mock(World.class);
        Location launch = new Location(world, 100.0, 64.0, 100.0);
        Location impact = new Location(world, 100.0, 64.0, 100.0);
        assertEquals(0.0, CombatListener.launchDistanceBlocks(launch, impact), 1e-9);
    }

    @Test
    void launchDistanceBlocksFallsBackToZeroAcrossDifferentWorlds() {
        World worldA = mock(World.class);
        World worldB = mock(World.class);
        Location launch = new Location(worldA, 0.0, 64.0, 0.0);
        Location impact = new Location(worldB, 100.0, 64.0, 0.0);
        assertEquals(0.0, CombatListener.launchDistanceBlocks(launch, impact), 1e-9,
                "Location#distance throws across different worlds; this must fall back to 0, not throw");
    }

    @Test
    void launchDistanceBlocksFallsBackToZeroWithNullArguments() {
        World world = mock(World.class);
        Location loc = new Location(world, 0.0, 64.0, 0.0);
        assertEquals(0.0, CombatListener.launchDistanceBlocks(null, loc), 1e-9);
        assertEquals(0.0, CombatListener.launchDistanceBlocks(loc, null), 1e-9);
        assertEquals(0.0, CombatListener.launchDistanceBlocks(null, null), 1e-9);
    }

    @Test
    void launchDistanceBlocksFallsBackToZeroWithNullWorld() {
        World world = mock(World.class);
        Location noWorld = new Location(null, 0.0, 64.0, 0.0);
        Location withWorld = new Location(world, 100.0, 64.0, 0.0);
        assertEquals(0.0, CombatListener.launchDistanceBlocks(noWorld, withWorld), 1e-9);
        assertEquals(0.0, CombatListener.launchDistanceBlocks(withWorld, noWorld), 1e-9);
    }
}
