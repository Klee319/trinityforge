package com.trinityforge.stats;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Computes a 0–100 roll-performance score as the simple average of every actually granted random
 * stat's normalized position within its authored {@code min..max} range.
 *
 * <p>{@code fixed} と {@code per-quality} は採点せず、各randomステを
 * {@code (actual - min) / (max - min)} で個別に0〜1へ正規化してから同じ重みで平均する。
 * そのため値の単位やレンジ幅が異なるステ同士でも、一方だけがスコアを支配しない。
 */
public final class QualityScoreCalculator {

    private QualityScoreCalculator() {
    }

    /**
     * @param profile   item's authored stat profile
     * @param quality   item's quality level used by the roll distribution
     * @param rollSeed  deterministic seed stamped on the item
     * @param rollModel effective roll model including baked crafting modifiers
     * @param granted   actually granted canonical stat keys, or {@code null} when grant gating is off
     */
    public static int score(ItemStatProfile profile, int quality, long rollSeed,
                            QualityRollModel rollModel, Set<String> granted) {
        if (profile == null || rollModel == null || profile.random().isEmpty()) {
            return 0;
        }

        Map<String, Double> actualRolls = new LinkedHashMap<>();
        profile.random().forEach((key, range) -> {
            if (granted == null || granted.contains(key)) {
                double z = RollHash.standardNormal(rollSeed, key);
                double reach = rollModel.reach(quality, z);
                actualRolls.put(key, range.valueAt(reach));
            }
        });
        return scoreFromActualRolls(actualRolls, profile.random(), granted);
    }

    static int scoreFromActualRolls(Map<String, Double> actualRolls,
                                    Map<String, StatRange> ranges,
                                    Set<String> granted) {
        Objects.requireNonNull(actualRolls, "actualRolls");
        Objects.requireNonNull(ranges, "ranges");

        Map<String, Double> canonicalActuals = new LinkedHashMap<>();
        actualRolls.forEach((key, value) -> canonicalActuals.put(StatKeys.canonical(key), value));
        Set<String> canonicalGranted = null;
        if (granted != null) {
            canonicalGranted = new LinkedHashSet<>();
            for (String key : granted) {
                canonicalGranted.add(StatKeys.canonical(key));
            }
        }

        double normalizedSum = 0.0;
        int rollCount = 0;
        for (Map.Entry<String, StatRange> entry : ranges.entrySet()) {
            String key = StatKeys.canonical(entry.getKey());
            if (canonicalGranted != null && !canonicalGranted.contains(key)) {
                continue;
            }
            StatRange range = entry.getValue();
            Double actual = canonicalActuals.get(key);
            if (range == null || actual == null || !Double.isFinite(actual)) {
                continue;
            }
            double width = range.max() - range.min();
            if (!Double.isFinite(width) || width <= 0.0) {
                continue;
            }
            double normalized = (actual - range.min()) / width;
            normalizedSum += Math.max(0.0, Math.min(1.0, normalized));
            rollCount++;
        }
        return rollCount == 0 ? 0 : (int) Math.round((normalizedSum / rollCount) * 100.0);
    }
}
