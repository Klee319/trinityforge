package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ItemStatProfile#qualityApplies()} (タスクB, 2026-07-26): 品質で値が変動する層(per-quality /
 * random、乗算レイヤ内のものも含む)を1つでも持つかどうかの判定。fixedのみのプロファイル(素材・触媒など)は
 * false になり、{@link ItemAssembler} が品質を0固定・品質lore行を出さないようにする根拠になる。
 */
class ItemStatProfileTest {

    private static ItemStatProfile fixedOnly() {
        return new ItemStatProfile(Map.of("attack-damage", 5.0), Map.of(), Map.of());
    }

    @Test
    void fixedOnlyDoesNotHaveQualityApplies() {
        assertFalse(fixedOnly().qualityApplies());
    }

    @Test
    void completelyEmptyDoesNotHaveQualityApplies() {
        ItemStatProfile empty = new ItemStatProfile(Map.of(), Map.of(), Map.of());
        assertFalse(empty.qualityApplies());
    }

    @Test
    void perQualityMakesQualityApply() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of("attack-damage", 0.5), Map.of());
        assertTrue(profile.qualityApplies());
    }

    @Test
    void randomMakesQualityApply() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of("attack-damage", new StatRange(1.0, 3.0)));
        assertTrue(profile.qualityApplies());
    }

    @Test
    void multiplierLayerPerQualityOnlyMakesQualityApply() {
        // 加算層は fixed のみでも、乗算レイヤ側に perQuality があれば品質は適用される。
        ItemStatProfile.MultiplierSpec multiplierSpec = new ItemStatProfile.MultiplierSpec(
                Map.of(), Map.of("attack-damage", 0.02), Map.of());
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of(), null, false, false, Map.of(),
                Map.of("layer1", multiplierSpec));
        assertTrue(profile.qualityApplies());
    }

    @Test
    void multiplierLayerRandomOnlyMakesQualityApply() {
        ItemStatProfile.MultiplierSpec multiplierSpec = new ItemStatProfile.MultiplierSpec(
                Map.of(), Map.of(), Map.of("attack-damage", new StatRange(1.0, 1.2)));
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of(), null, false, false, Map.of(),
                Map.of("layer1", multiplierSpec));
        assertTrue(profile.qualityApplies());
    }

    @Test
    void multiplierLayerFixedOnlyDoesNotMakeQualityApply() {
        // 乗算レイヤも fixed のみなら、加算層と合わせて全体が品質非依存のまま。
        ItemStatProfile.MultiplierSpec multiplierSpec = new ItemStatProfile.MultiplierSpec(
                Map.of("attack-damage", 1.1), Map.of(), Map.of());
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of(), null, false, false, Map.of(),
                Map.of("layer1", multiplierSpec));
        assertFalse(profile.qualityApplies());
        assertFalse(profile.randomApplies());
    }

    @Test
    void fixedOnlyDoesNotHaveRandomApplies() {
        assertFalse(fixedOnly().randomApplies());
    }

    @Test
    void perQualityOnlyDoesNotHaveRandomApplies() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of("attack-damage", 0.5), Map.of());
        assertTrue(profile.qualityApplies());
        assertFalse(profile.randomApplies());
    }

    @Test
    void randomLayerMakesRandomApply() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of("attack-damage", new StatRange(1.0, 3.0)));
        assertTrue(profile.randomApplies());
    }

    @Test
    void randomizeGrantsMakesRandomApplyWithoutRandomLayer() {
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of(), null, false, true, Map.of());
        assertTrue(profile.randomApplies());
    }

    @Test
    void multiplierLayerRandomMakesRandomApply() {
        ItemStatProfile.MultiplierSpec multiplierSpec = new ItemStatProfile.MultiplierSpec(
                Map.of(), Map.of(), Map.of("attack-damage", new StatRange(1.0, 1.2)));
        ItemStatProfile profile = new ItemStatProfile(
                Map.of("attack-damage", 5.0), Map.of(), Map.of(), null, false, false, Map.of(),
                Map.of("layer1", multiplierSpec));
        assertTrue(profile.randomApplies());
    }
}
