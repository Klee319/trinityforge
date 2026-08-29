package com.trinityforge.stats;

import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * バニラ 1.21.11 の発酵したクモの目 mix 表。品質で倒したポーションの反転救済がここを見る。
 */
class VanillaPotionInvertTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void swiftnessBecomesSlowness() {
        assertEquals(PotionType.SLOWNESS, VanillaPotionInvert.invert(PotionType.SWIFTNESS));
        assertEquals(PotionType.LONG_SLOWNESS, VanillaPotionInvert.invert(PotionType.LONG_SWIFTNESS));
        assertEquals(PotionType.STRONG_SLOWNESS, VanillaPotionInvert.invert(PotionType.STRONG_SWIFTNESS));
    }

    @Test
    void longPoisonBecomesHarmingNotLongHarming() {
        assertEquals(PotionType.HARMING, VanillaPotionInvert.invert(PotionType.LONG_POISON),
                "バニラは長い毒→通常の攻撃(長い攻撃では無い)");
    }

    @Test
    void fireResistanceHasNoVanillaInvert() {
        assertNull(VanillaPotionInvert.invert(PotionType.FIRE_RESISTANCE),
                "反転 mix が無い種類を救済すると弱化へ化けて効果が消える");
    }

    @Test
    void extendedFlagLooksUpLongVariantBeforeInvert() {
        assertEquals(PotionType.LONG_SLOWNESS,
                VanillaPotionInvert.invert(PotionType.SWIFTNESS, "EXTENDED"));
    }

    @Test
    void qualityDurationDeltaMovesToInvertedType() {
        List<PotionEffect> current = List.of(new PotionEffect(PotionEffectType.SPEED, 3620, 0));
        List<PotionEffect> inverted = VanillaPotionInvert.invertedEffects(
                PotionType.SWIFTNESS, PotionType.SLOWNESS, current);
        assertEquals(1, inverted.size());
        assertEquals(PotionEffectType.SLOWNESS, inverted.get(0).getType());
        assertEquals(1820, inverted.get(0).getDuration());
    }
}
