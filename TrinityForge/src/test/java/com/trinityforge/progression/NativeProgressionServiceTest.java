package com.trinityforge.progression;

import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.core.XpTransitionService;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeProgressionServiceTest {

    @Test
    void fractionalExpAndPowerPointProgressionArePersisted() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService service = new NativeProgressionService(repository, catalog);
            UUID player = UUID.randomUUID();

            service.grantExp(player, SkillId.ARS_MAGIC, 0.1);
            assertEquals(0.1, service.progress(player, SkillId.ARS_MAGIC)
                    .orElseThrow().totalExp(), 0.000001);

            double eightMiningLevels = new XpTransitionService(
                    catalog.get(SkillId.MINING).curve()).cumulativeExpForLevel(8);
            service.grantExp(player, SkillId.MINING, eightMiningLevels);

            // 2026-07-25 PRG-08: exp_gain 100 -> 240 (SP供給不足の是正)。8 MINING levels now grant
            // 8*240=1920 POWER exp; cumulative cost to POWER level2 is 800+818=1618 <= 1920 < 1618+836,
            // so POWER lands on level 2 (was level 1 under the old exp_gain=100).
            assertEquals(8, service.progress(player, SkillId.MINING).orElseThrow().level());
            assertEquals(2, service.progress(player, SkillId.POWER).orElseThrow().level());
            assertEquals(5L, service.snapshot(player).availablePoints());

            service.grantExp(player, SkillId.MINING, -eightMiningLevels);
            assertEquals(0, service.progress(player, SkillId.POWER).orElseThrow().level());
            assertEquals(3L, service.snapshot(player).availablePoints());
            service.grantExp(player, SkillId.MINING, eightMiningLevels);
            assertEquals(2, service.progress(player, SkillId.POWER).orElseThrow().level());
            assertEquals(5L, service.snapshot(player).availablePoints(),
                    "level down/up must not inflate POWER points");

            UUID fresh = UUID.randomUUID();
            assertTrue(service.unlockPerk(fresh, "test_perk", 1));
            assertEquals(2L, service.snapshot(fresh).availablePoints());
        }
    }

    // --- 2026-07-26 EXP調整タスク3: レベル逓減カーブの配線検証 -----------------------------------

    /**
     * 「既定config では現行挙動と一致する」ことの直接検証その2: {@link ExpDiminishingCurve#NONE}
     * (4引数以下のコンストラクタが暗黙に使う既定実装)を明示的に渡しても、渡さない場合と同じ
     * totalExpになる — つまり5引数コンストラクタの追加が既存呼び出し経路に一切影響しないことを示す。
     */
    @Test
    void explicitNoneCurveMatchesImplicitDefaultConstructorExactly() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository implicitRepo =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:");
             SqliteProgressionRepository explicitRepo =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService implicitDefault = new NativeProgressionService(implicitRepo, catalog);
            NativeProgressionService explicitNone = new NativeProgressionService(
                    explicitRepo, catalog, id -> 0.0, new PlayerLockRegistry(), ExpDiminishingCurve.NONE);

            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            implicitDefault.grantExp(a, SkillId.MINING, 500.0);
            explicitNone.grantExp(b, SkillId.MINING, 500.0);

            assertEquals(
                    implicitDefault.progress(a, SkillId.MINING).orElseThrow().totalExp(),
                    explicitNone.progress(b, SkillId.MINING).orElseThrow().totalExp(),
                    0.0);
        }
    }

    /** A non-trivial diminishing curve must actually reduce the amount applied to the skill's total EXP. */
    @Test
    void diminishingCurveReducesAppliedExpWhenEnabled() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            // Every grant on MINING is halved regardless of level; every other skill is untouched.
            ExpDiminishingCurve halveMining = (skillId, level) ->
                    SkillId.MINING.equals(skillId) ? 0.5 : 1.0;
            NativeProgressionService service = new NativeProgressionService(
                    repository, catalog, id -> 0.0, new PlayerLockRegistry(), halveMining);

            UUID player = UUID.randomUUID();
            service.grantExp(player, SkillId.MINING, 100.0);
            service.grantExp(player, SkillId.ARS_MAGIC, 100.0);

            assertEquals(50.0, service.progress(player, SkillId.MINING).orElseThrow().totalExp(), 1e-9);
            assertEquals(100.0, service.progress(player, SkillId.ARS_MAGIC).orElseThrow().totalExp(), 1e-9,
                    "a skill the curve does not target must be granted at full amount");
        }
    }

    // --- PRG-09: プレステージ周回によるPOWER EXP無限増殖の修正 ---------------------------------

    /** Pure algebra, no catalog/repository needed: tier 0 (never prestiged) is always full-rate. */
    @Test
    void prestigeDecayMultiplierIsFullRateAtTierZeroRegardlessOfDecayRate() {
        assertEquals(1.0, NativeProgressionService.prestigePowerDecayMultiplier(0, 0.5), 0.0);
        assertEquals(1.0, NativeProgressionService.prestigePowerDecayMultiplier(0, 1.0), 0.0);
        assertEquals(1.0, NativeProgressionService.prestigePowerDecayMultiplier(0, 0.0), 0.0);
    }

    /** decayRate=0 (config's back-compat switch) must reproduce the pre-fix behavior exactly: no decay ever. */
    @Test
    void zeroDecayRateNeverReducesGrantAtAnyPrestigeTier() {
        assertEquals(1.0, NativeProgressionService.prestigePowerDecayMultiplier(1, 0.0), 0.0);
        assertEquals(1.0, NativeProgressionService.prestigePowerDecayMultiplier(50, 0.0), 0.0);
    }

    /** Each further prestige cycle of the SAME skill is worth strictly less (geometric: 100%, 50%, 25%, ...). */
    @Test
    void prestigeDecayMultiplierHalvesEachTierAtDefaultRate() {
        assertEquals(1.0, NativeProgressionService.prestigePowerDecayMultiplier(0, 0.5), 1e-9);
        assertEquals(0.5, NativeProgressionService.prestigePowerDecayMultiplier(1, 0.5), 1e-9);
        assertEquals(0.25, NativeProgressionService.prestigePowerDecayMultiplier(2, 0.5), 1e-9);
        assertEquals(0.125, NativeProgressionService.prestigePowerDecayMultiplier(3, 0.5), 1e-9);
    }

    /**
     * Infinite-grind convergence: summing the multiplier across an ever-growing number of prestige
     * cycles must approach — and never exceed — {@code 1 / decayRate} (the geometric series limit).
     * This is the "SP総量が発散しない" guarantee: even 1000 simulated cycles at decayRate=0.5 stays
     * under the 2.0 limit, and gets arbitrarily close to it.
     */
    @Test
    void infiniteGrindingConvergesToOneOverDecayRateAndNeverExceedsIt() {
        double decayRate = 0.5;
        double sum = 0.0;
        for (int tier = 0; tier < 1000; tier++) {
            sum += NativeProgressionService.prestigePowerDecayMultiplier(tier, decayRate);
            assertTrue(sum <= 1.0 / decayRate + 1e-9,
                    "cumulative multiplier sum must never exceed the geometric series limit (tier=" + tier + ")");
        }
        assertEquals(2.0, sum, 1e-6, "1000 cycles at decayRate=0.5 must have converged to (essentially) 1/0.5=2.0");
    }

    @Test
    void grantExpAppliesLessPowerExpTheMoreTimesASkillHasBeenPrestiged() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService service = new NativeProgressionService(repository, catalog);
            double oneMiningLevel = new XpTransitionService(catalog.get(SkillId.MINING).curve())
                    .cumulativeExpForLevel(1);
            int miningMaxLevel = catalog.get(SkillId.MINING).maxLevel();

            // Tier 0 (never prestiged): baseline POWER gain.
            UUID neverPrestiged = UUID.randomUUID();
            service.grantExp(neverPrestiged, SkillId.MINING, oneMiningLevel);
            double tier0PowerExp = service.progress(neverPrestiged, SkillId.POWER).orElseThrow().totalExp();
            assertTrue(tier0PowerExp > 0.0, "a fresh (never-prestiged) level-up must still grant POWER exp");

            // Tier 1 (simulates "prestiged this skill once"): repository.saveSkillProgress mirrors what
            // NativePerkService#prestigeUnderLock persists (SkillProgress.prestige incremented, level reset
            // to 0) without needing a full SkillTree/Prestige config wired up just for this test.
            UUID prestigedOnce = UUID.randomUUID();
            repository.saveSkillProgress(prestigedOnce, SkillId.MINING,
                    new SkillProgress(0, 0.0, 0.0, 1, miningMaxLevel));
            service.grantExp(prestigedOnce, SkillId.MINING, oneMiningLevel);
            double tier1PowerExp = service.progress(prestigedOnce, SkillId.POWER).orElseThrow().totalExp();

            UUID prestigedTwice = UUID.randomUUID();
            repository.saveSkillProgress(prestigedTwice, SkillId.MINING,
                    new SkillProgress(0, 0.0, 0.0, 2, miningMaxLevel));
            service.grantExp(prestigedTwice, SkillId.MINING, oneMiningLevel);
            double tier2PowerExp = service.progress(prestigedTwice, SkillId.POWER).orElseThrow().totalExp();

            assertTrue(tier1PowerExp < tier0PowerExp,
                    "1 prior prestige of the SAME skill must grant less POWER exp than never having prestiged");
            assertTrue(tier2PowerExp < tier1PowerExp,
                    "2 prior prestiges must grant even less than 1 (monotonically decreasing)");
            assertEquals(tier0PowerExp * 0.5, tier1PowerExp, 1e-6);
            assertEquals(tier0PowerExp * 0.25, tier2PowerExp, 1e-6);
        }
    }
}
