package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RandomRollPool} の抽選ロジック（旧 ArsPaper {@code ThreadRollConfig} からの移設、2026-08-02）を
 * 固定する。ここで守る不変条件は移設元と同一:
 * <ul>
 *   <li>合計ロール数（主1 + サブ）は必ず2種以上（sub-count に0本の重みを置かないことに依存する運用契約）。</li>
 *   <li>quality が高いほど min/max の幅が広がる（下限はわずかに下がり、上限はより大きく上がる）。</li>
 *   <li>ロール値は authored された min/max の小数桁数の刻みに量子化される（2026-08-02 タスク#43）。</li>
 * </ul>
 */
class RandomRollPoolTest {

    private static RandomRollPool simplePool() {
        return new RandomRollPool(
                List.of(new RandomRollPool.Rarity("common", 100, 1.0, "並", "GRAY")),
                List.of(new RandomRollPool.StatDef("attack-power", 100, 200, 600, false)),
                List.of(
                        new RandomRollPool.StatDef("crit-chance", 50, 0.02, 0.05, true),
                        new RandomRollPool.StatDef("penetration", 50, 0.02, 0.05, true)),
                Map.of(1, 100),
                true, 0.05, 0.15);
    }

    @Test
    void rollProducesMainStatAndAtLeastOneSub() {
        RandomRollPool pool = simplePool();
        RandomRollPool.RolledStats rolled = pool.roll(new Random(1), 0).orElseThrow();
        assertEquals(1, rolled.mainStat().size());
        assertTrue(rolled.subStats().size() >= 1, "sub-count が1本固定のプールなのにサブが0本になった");
        assertTrue(rolled.allStats().size() >= 2, "主+サブの合計は2種以上でなければならない");
    }

    @Test
    void emptyRaritiesOrMainStatsYieldsEmptyOptional() {
        RandomRollPool noRarity = new RandomRollPool(List.of(),
                List.of(new RandomRollPool.StatDef("attack-power", 1, 1, 2, false)),
                List.of(), Map.of(), true, 0.05, 0.15);
        assertTrue(noRarity.roll(new Random(1), 0).isEmpty());

        RandomRollPool noMain = new RandomRollPool(
                List.of(new RandomRollPool.Rarity("common", 1, 1.0, "並", "GRAY")),
                List.of(), List.of(), Map.of(), true, 0.05, 0.15);
        assertTrue(noMain.roll(new Random(1), 0).isEmpty());
    }

    @Test
    void subDoesNotDuplicateMainKey() {
        // サブ候補が主ステと同じキーしか無いプールでは、サブが0本になる(重複しない)。
        RandomRollPool pool = new RandomRollPool(
                List.of(new RandomRollPool.Rarity("common", 1, 1.0, "並", "GRAY")),
                List.of(new RandomRollPool.StatDef("attack-power", 1, 200, 600, false)),
                List.of(new RandomRollPool.StatDef("attack-power", 1, 60, 240, false)),
                Map.of(1, 1), true, 0.05, 0.15);
        RandomRollPool.RolledStats rolled = pool.roll(new Random(1), 0).orElseThrow();
        assertTrue(rolled.subStats().isEmpty());
    }

    @Test
    void rarityMultiplierAppliesOnlyToMainStat() {
        RandomRollPool pool = new RandomRollPool(
                List.of(new RandomRollPool.Rarity("legend", 1, 2.0, "神", "GOLD")),
                List.of(new RandomRollPool.StatDef("attack-power", 1, 100, 100, false)),
                List.of(new RandomRollPool.StatDef("penetration", 1, 10, 10, false)),
                Map.of(1, 1), false, 0.0, 0.0);
        RandomRollPool.RolledStats rolled = pool.roll(new Random(1), 0).orElseThrow();
        assertEquals(200.0, rolled.mainStat().get("attack-power"), 1e-9, "主ステにはレア度倍率が掛かる");
        assertEquals(10.0, rolled.subStats().get("penetration"), 1e-9, "サブステには倍率が掛からない");
    }

    @Test
    void qualitySpreadWidensObservedRangeAtHighQuality() {
        RandomRollPool pool = simplePool();
        double minAtZero = Double.MAX_VALUE;
        double maxAtZero = -Double.MAX_VALUE;
        double minAtMax = Double.MAX_VALUE;
        double maxAtMax = -Double.MAX_VALUE;
        Random r0 = new Random(42);
        Random r100 = new Random(42);
        for (int i = 0; i < 500; i++) {
            double v0 = pool.roll(r0, 0).orElseThrow().mainStat().get("attack-power");
            double v100 = pool.roll(r100, 100).orElseThrow().mainStat().get("attack-power");
            minAtZero = Math.min(minAtZero, v0);
            maxAtZero = Math.max(maxAtZero, v0);
            minAtMax = Math.min(minAtMax, v100);
            maxAtMax = Math.max(maxAtMax, v100);
        }
        assertTrue(minAtMax <= minAtZero, "quality=100の下限がquality=0の下限より下がっていない: "
                + minAtMax + " vs " + minAtZero);
        assertTrue(maxAtMax >= maxAtZero, "quality=100の上限がquality=0の上限より上がっていない: "
                + maxAtMax + " vs " + maxAtZero);
        // quality<=0 は元のmin/maxのまま(仕様どおり厳密境界)。
        assertTrue(minAtZero >= 200.0 - 1e-9 && maxAtZero <= 600.0 + 1e-9);
    }

    @Test
    void lowHighMultiplierAreOneAtQualityZero() {
        assertEquals(1.0, RandomRollPool.lowMultiplierFor(0, 0.05), 1e-12);
        assertEquals(1.0, RandomRollPool.highMultiplierFor(0, 0.15), 1e-12);
        assertEquals(1.0, RandomRollPool.lowMultiplierFor(-10, 0.05), 1e-12, "負のqualityも0扱い");
    }

    @Test
    void lowHighMultiplierAtMaxQuality() {
        assertEquals(0.95, RandomRollPool.lowMultiplierFor(100, 0.05), 1e-12);
        assertEquals(1.15, RandomRollPool.highMultiplierFor(100, 0.15), 1e-12);
        // 100を超えるqualityは100にクランプされる(同じ結果)。
        assertEquals(RandomRollPool.lowMultiplierFor(100, 0.05),
                RandomRollPool.lowMultiplierFor(250, 0.05), 1e-12);
    }

    @Test
    void decimalPlacesReadsAuthoredPrecision() {
        assertEquals(0, RandomRollPool.decimalPlaces(200.0), "整数は0桁");
        assertEquals(1, RandomRollPool.decimalPlaces(0.5), "0.5は1桁");
        assertEquals(3, RandomRollPool.decimalPlaces(0.015), "0.015は3桁");
        assertEquals(2, RandomRollPool.decimalPlaces(0.02), "0.02は2桁(末尾0は無視)");
    }

    @Test
    void decimalsOfStatDefTakesTheFinerOfMinAndMax() {
        RandomRollPool.StatDef def = new RandomRollPool.StatDef("k", 1, 0.5, 2.0, false);
        assertEquals(1, RandomRollPool.decimalsOf(def), "min=0.5(1桁)/max=2.0(0桁)のうち細かい方=1桁");

        RandomRollPool.StatDef intDef = new RandomRollPool.StatDef("k", 1, 1, 10, false);
        assertEquals(0, RandomRollPool.decimalsOf(intDef), "min/max とも整数なら整数刻み(0桁)");
    }

    @Test
    void quantizeSnapsToDecimalGrid() {
        // min=0.5, max=2.0 -> 1桁刻み(0.1単位)なので、結果は必ず0.1の倍数になる。
        for (double raw = 0.5; raw <= 2.0; raw += 0.0137) {
            double snapped = RandomRollPool.quantize(raw, 0.5, 2.0, 1);
            double scaled = snapped * 10.0;
            assertEquals(Math.round(scaled), scaled, 1e-6,
                    "0.1刻みのはずが刻みから外れた値: " + snapped);
        }
    }

    @Test
    void quantizeSnapsToIntegerGridWhenBothBoundsAreWhole() {
        // min=1, max=10 -> 0桁刻み(整数)なので、結果は必ず整数になる。
        for (double raw = 1.0; raw <= 10.0; raw += 0.37) {
            double snapped = RandomRollPool.quantize(raw, 1.0, 10.0, 0);
            assertEquals(Math.rint(snapped), snapped, 1e-9,
                    "整数刻みのはずが小数が残った: " + snapped);
        }
    }

    @Test
    void rollValueRespectsDecimalGridEndToEnd() {
        // min: 0.5 / max: 2.0 の主ステを持つプールで、実際に roll() した値が0.1刻みから外れないことを
        // 統計的に確認する(タスク#43: 「小数点の該当桁単位でランダム化」)。
        RandomRollPool pool = new RandomRollPool(
                List.of(new RandomRollPool.Rarity("common", 1, 1.0, "並", "GRAY")),
                List.of(new RandomRollPool.StatDef("flat-bonus-damage", 1, 0.5, 2.0, false)),
                List.of(), Map.of(), false, 0.0, 0.0);
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            double v = pool.roll(random, 0).orElseThrow().mainStat().get("flat-bonus-damage");
            double scaled = v * 10.0;
            assertEquals(Math.round(scaled), scaled, 1e-6, "0.1刻みから外れたロール値: " + v);
        }
    }

    @Test
    void encodeDecodeCompatibleFormat() {
        RandomRollPool.RolledStats rolled = new RandomRollPool.RolledStats("epic",
                Map.of("crit-damage", 0.0725), Map.of("attack-power", 180.0, "dodge-chance", 0.0091));
        String encoded = rolled.encode();
        // ArsPaper側 ThreadRoll.decode がそのまま読める "<rarity>|<main>|<sub>" 形式であること。
        String[] parts = encoded.split("\\|", -1);
        assertEquals(3, parts.length);
        assertEquals("epic", parts[0]);
        assertTrue(parts[1].contains("crit-damage=0.0725"));
        assertTrue(parts[2].contains("attack-power=180.0"));
    }

    @Test
    void percentKeysCollectsFromBothMainAndSub() {
        RandomRollPool pool = new RandomRollPool(
                List.of(new RandomRollPool.Rarity("common", 1, 1.0, "並", "GRAY")),
                List.of(new RandomRollPool.StatDef("crit-chance", 1, 0.02, 0.05, true)),
                List.of(new RandomRollPool.StatDef("attack-power", 1, 60, 240, false)),
                Map.of(), true, 0.05, 0.15);
        assertEquals(java.util.Set.of("crit-chance"), pool.percentKeys());
    }

    @Test
    void subCountDistributionGuaranteesAtLeastTwoTotalOverManyRolls() {
        RandomRollPool pool = simplePool();
        Random random = new Random(99);
        int minTotal = Integer.MAX_VALUE;
        for (int i = 0; i < 1000; i++) {
            Optional<RandomRollPool.RolledStats> rolled = pool.roll(random, 0);
            int total = rolled.orElseThrow().allStats().size();
            minTotal = Math.min(minTotal, total);
        }
        assertTrue(minTotal >= 2, "1000回ロールして合計種類数が2種未満になった: " + minTotal);
    }
}
