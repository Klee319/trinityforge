package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit-tests {@link DerivedItemStats#applyProfile}, the pure overlay step of the item derivation. The
 * full {@link DerivedItemStats#resolve} needs a live Bukkit {@code ItemStack}/{@code ItemMeta} (no
 * MockBukkit on the headless classpath), so the overwrite semantics — fixed always OVERWRITES, per-quality
 * ADDS {@code step * qualityLevel}, an empty/null profile is a no-op — are asserted directly on the
 * extracted pure method (documented on {@code applyProfile}).
 */
class DerivedItemStatsProfileTest {

    private static Map<String, Double> commonLayer() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("attack_power", 2.0);
        map.put("crit_chance", 0.10);
        return map;
    }

    @Test
    void nullProfileLeavesMapUnchanged() {
        Map<String, Double> map = commonLayer();
        DerivedItemStats.applyProfile(map, null, 3);
        assertEquals(Map.of("attack_power", 2.0, "crit_chance", 0.10), map);
    }

    @Test
    void emptyProfileLeavesMapUnchanged() {
        Map<String, Double> map = commonLayer();
        DerivedItemStats.applyProfile(map, new ItemStatProfile(Map.of(), Map.of(), Map.of()), 3);
        assertEquals(Map.of("attack_power", 2.0, "crit_chance", 0.10), map);
    }

    @Test
    void fixedOverwritesCommonValue() {
        Map<String, Double> map = commonLayer();
        ItemStatProfile profile = new ItemStatProfile(Map.of("attack_power", 7.0), Map.of(), Map.of());

        DerivedItemStats.applyProfile(map, profile, 0);

        assertEquals(7.0, map.get("attack_power"), 0.0, "fixed replaces (not sums) the common value");
        assertEquals(0.10, map.get("crit_chance"), 0.0, "a stat not in the profile keeps the common value");
    }

    @Test
    void undefinedPerQualityLeavesMapUnchangedAtAnyQualityLevel() {
        // A profile authored before per-quality existed (empty perQuality map) must derive identically
        // to the pre-I8 behaviour, regardless of the item's quality level (回帰なし).
        Map<String, Double> map = commonLayer();
        ItemStatProfile profile = new ItemStatProfile(Map.of("attack_power", 5.0), Map.of(), Map.of());

        DerivedItemStats.applyProfile(map, profile, 7);

        assertEquals(5.0, map.get("attack_power"), 0.0);
        assertEquals(0.10, map.get("crit_chance"), 0.0);
    }

    @Test
    void qualityZeroAddsNoPerQualityIncrement() {
        Map<String, Double> map = commonLayer();
        ItemStatProfile profile = new ItemStatProfile(Map.of(), Map.of("attack_power", 0.5), Map.of());

        DerivedItemStats.applyProfile(map, profile, 0);

        // step * 0 == 0.0, summed onto the existing common-layer value: no observable change.
        assertEquals(2.0, map.get("attack_power"), 0.0);
    }

    @Test
    void perQualityAddsStepTimesQualityLevelOnTopOfCommonValue() {
        Map<String, Double> map = commonLayer();
        ItemStatProfile profile = new ItemStatProfile(Map.of(), Map.of("attack_power", 0.5), Map.of());

        DerivedItemStats.applyProfile(map, profile, 4);

        // common attack_power (2.0) + step(0.5) * qualityLevel(4) = 4.0.
        assertEquals(4.0, map.get("attack_power"), 0.0);
    }

    @Test
    void fixedAndPerQualityCombineAdditively() {
        Map<String, Double> map = commonLayer();
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack_power", 7.0), Map.of("attack_power", 0.5), Map.of());

        DerivedItemStats.applyProfile(map, profile, 3);

        // fixed(7.0) overwrites the common layer first, then per-quality adds step(0.5) * quality(3).
        assertEquals(8.5, map.get("attack_power"), 0.0);
    }

    // --- random roll layer (段2, applyRandom) ---

    private static final QualityRollModel ROLL_MODEL = new QualityRollModel(9, 0.5, 0.5, 0.0);

    @Test
    void applyRandomAddsTheRolledValueOnTopOfTheExistingValue() {
        Map<String, Double> map = commonLayer(); // attack_power starts at 2.0
        ItemStatProfile profile = new ItemStatProfile(
                Map.of(), Map.of(), Map.of("attack-power", new StatRange(0.0, 10.0)));
        long seed = 123456789L;
        int quality = 5;

        DerivedItemStats.applyRandom(map, profile, quality, seed, ROLL_MODEL);

        // Same pipeline the method uses: canonical key -> RollHash standard-normal -> quality reach -> valueAt,
        // ADDED on top of the existing common-layer 2.0 (undefined base would count as 0).
        double expectedRoll = new StatRange(0.0, 10.0).valueAt(
                ROLL_MODEL.reach(quality, RollHash.standardNormal(seed, StatKeys.canonical("attack-power"))));
        assertEquals(2.0 + expectedRoll, map.get("attack_power"), 1e-9);
    }

    @Test
    void higherQualityRollsAtLeastAsHighForTheSameItem() {
        // reach = clamp(qNorm + Z·σ) is monotonic in qNorm for a fixed draw, so the SAME item (same seed)
        // rolls at least as high at a higher quality — the mode shifts toward max.
        ItemStatProfile profile = new ItemStatProfile(
                Map.of(), Map.of(), Map.of("attack-power", new StatRange(0.0, 10.0)));
        Map<String, Double> lowQ = new LinkedHashMap<>();
        Map<String, Double> highQ = new LinkedHashMap<>();
        long seed = 555L;

        DerivedItemStats.applyRandom(lowQ, profile, 0, seed, ROLL_MODEL);
        DerivedItemStats.applyRandom(highQ, profile, 9, seed, ROLL_MODEL);

        org.junit.jupiter.api.Assertions.assertTrue(highQ.get("attack_power") >= lowQ.get("attack_power"),
                "same item must roll at least as high at higher quality; low=" + lowQ.get("attack_power")
                        + " high=" + highQ.get("attack_power"));
    }

    @Test
    void applyRandomIsDeterministicForTheSameSeed() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of(), Map.of(), Map.of("crit-chance", new StatRange(0.0, 0.5)));
        Map<String, Double> a = new LinkedHashMap<>();
        Map<String, Double> b = new LinkedHashMap<>();

        DerivedItemStats.applyRandom(a, profile, 7, 42L, ROLL_MODEL);
        DerivedItemStats.applyRandom(b, profile, 7, 42L, ROLL_MODEL);

        assertEquals(a.get("crit_chance"), b.get("crit_chance"), 0.0);
    }

    @Test
    void applyRandomWithoutARollModelIsInert() {
        // No roll model wired (null) -> the random layer is a no-op; the base value is untouched.
        Map<String, Double> map = commonLayer();
        ItemStatProfile profile = new ItemStatProfile(
                Map.of(), Map.of(), Map.of("attack-power", new StatRange(0.0, 10.0)));

        DerivedItemStats.applyRandom(map, profile, 5, 1L, null);

        assertEquals(2.0, map.get("attack_power"), 0.0);
    }

    @Test
    void applyRandomWithNoRandomStatsLeavesMapUnchanged() {
        Map<String, Double> map = commonLayer();
        ItemStatProfile profile = new ItemStatProfile(Map.of("attack_power", 5.0), Map.of(), Map.of());

        DerivedItemStats.applyRandom(map, profile, 5, 1L, ROLL_MODEL);

        assertEquals(Map.of("attack_power", 2.0, "crit_chance", 0.10), map);
    }

    // --- CraftRollMods threading (段2 鍛冶ロールパーク): a crafted item's baked-in perk deltas widen the
    // roll model applyRandom is given, so the same seed/quality rolls at least as high with the mods. ---

    @Test
    void applyRandomWithCraftModsAdjustedModelRollsAtLeastAsHighAsWithNoneForTheSameSeedAndQuality() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of(), Map.of(), Map.of("attack-power", new StatRange(0.0, 10.0)));
        long seed = 987654321L;
        int quality = 5;
        Map<String, Double> baseline = new LinkedHashMap<>();
        Map<String, Double> boosted = new LinkedHashMap<>();

        QualityRollModel boostedModel = ROLL_MODEL.withCraftMods(new CraftRollMods(0.5, 0.0, 0.0));

        DerivedItemStats.applyRandom(baseline, profile, quality, seed, ROLL_MODEL);
        DerivedItemStats.applyRandom(boosted, profile, quality, seed, boostedModel);

        org.junit.jupiter.api.Assertions.assertTrue(
                boosted.get("attack_power") >= baseline.get("attack_power"),
                "a positive rollUpBonus must widen the up side, so the same seed/quality rolls at least as "
                        + "high; baseline=" + baseline.get("attack_power") + " boosted=" + boosted.get("attack_power"));
    }

    @Test
    void withCraftModsOfNoneReDerivesTheIdenticalStatsAsTheUnadjustedModel() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of(), Map.of(), Map.of("attack-power", new StatRange(0.0, 10.0)));
        long seed = 111222333L;
        int quality = 6;
        Map<String, Double> unadjusted = new LinkedHashMap<>();
        Map<String, Double> withNoneMods = new LinkedHashMap<>();

        DerivedItemStats.applyRandom(unadjusted, profile, quality, seed, ROLL_MODEL);
        DerivedItemStats.applyRandom(withNoneMods, profile, quality, seed, ROLL_MODEL.withCraftMods(CraftRollMods.NONE));

        assertEquals(unadjusted.get("attack_power"), withNoneMods.get("attack_power"), 0.0);
    }
}
