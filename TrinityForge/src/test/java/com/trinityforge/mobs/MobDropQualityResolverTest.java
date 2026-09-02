package com.trinityforge.mobs;

import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

/**
 * {@link MobDropQualityResolver}: 討伐ドロップの品質がモブレベルに追随することの回帰テスト
 * (2026-08-19 / W-130)。実バグは「3本のドロップリスナーが品質0固定の1引数版 resolver を
 * 呼んでいた」ことで、その結果スレッド等の品質付きカスタム品が<b>常に劣悪</b>で落ちていた。
 * ここでは式そのもの、リスナー側の配線は {@code MobLevelTableListenerTest} が見る。
 */
class MobDropQualityResolverTest {

    private static final int MAX_QUALITY = 7;

    private static CraftQualityConfig craftQuality(boolean enabled) {
        CraftQualityConfig config = mock(CraftQualityConfig.class);
        when(config.dropEnabled()).thenReturn(enabled);
        when(config.dropStrengthPerQuality()).thenReturn(10);
        when(config.dropBaseQuality()).thenReturn(0);
        return config;
    }

    private static QualityConfig quality() {
        QualityConfig config = mock(QualityConfig.class);
        when(config.maxQuality()).thenReturn(MAX_QUALITY);
        when(config.spreadUp()).thenReturn(0.5);
        when(config.spreadDown()).thenReturn(0.5);
        return config;
    }

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static double meanQuality(MobDropQualityResolver resolver, int mobLevel, int samples) {
        SplittableRandom random = new SplittableRandom(20260819L);
        long total = 0;
        for (int i = 0; i < samples; i++) {
            total += resolver.resolve(mobLevel, 0, null, null, random);
        }
        return (double) total / samples;
    }

    @Test
    void higherMobLevelRaisesTheDropQuality() {
        MobDropQualityResolver resolver =
                new MobDropQualityResolver(craftQuality(true), quality(), null, null);

        double low = meanQuality(resolver, 0, 500);
        double high = meanQuality(resolver, 200, 500);

        assertTrue(high > low + 2.0,
                "レベル200のモブはレベル0より明確に高い品質を落とすこと (low=" + low + " high=" + high + ")");
        assertTrue(high > 6.0, "中心値が最大品質を超える帯では最高品質付近に張り付くこと: " + high);
    }

    @Test
    void lowLevelMobIsNotPinnedToZero() {
        // レベル0でも「必ず0」ではない(中心値0・上振れσ0.5の切り捨てられた正規分布)。
        // ここが常に0だったのが実バグの見え方(＝劣悪しか落ちない)。
        MobDropQualityResolver resolver =
                new MobDropQualityResolver(craftQuality(true), quality(), null, null);
        SplittableRandom random = new SplittableRandom(1L);

        boolean sawNonZero = false;
        for (int i = 0; i < 500 && !sawNonZero; i++) {
            sawNonZero = resolver.resolve(30, 0, null, null, random) > 0;
        }

        assertTrue(sawNonZero, "レベル30(=中心値3)なら0以外も出ること");
    }

    @Test
    void bonusModeShiftsTheCenterUpward() {
        MobDropQualityResolver resolver =
                new MobDropQualityResolver(craftQuality(true), quality(), null, null);
        SplittableRandom random = new SplittableRandom(7L);

        long plain = 0;
        long boosted = 0;
        for (int i = 0; i < 500; i++) {
            plain += resolver.resolve(0, 0, null, null, random);
            boosted += resolver.resolve(0, 3, null, null, random);
        }

        assertTrue(boosted > plain + 500,
                "mob_drop_quality ぶんの底上げが中心値へ乗ること (plain=" + plain + " boosted=" + boosted + ")");
    }

    @Test
    void disabledDropQualityFallsBackToUniformDraw() {
        MobDropQualityResolver resolver =
                new MobDropQualityResolver(craftQuality(false), quality(), null, null);
        SplittableRandom random = new SplittableRandom(3L);

        boolean sawZero = false;
        boolean sawMax = false;
        for (int i = 0; i < 500; i++) {
            int rolled = resolver.resolve(200, 0, null, null, random);
            assertTrue(rolled >= 0 && rolled <= MAX_QUALITY, "品質は [0, max] に収まること: " + rolled);
            sawZero |= rolled == 0;
            sawMax |= rolled == MAX_QUALITY;
        }

        assertTrue(sawZero && sawMax, "drop.enabled: false なら 0〜最大の一様乱数になること");
    }

    @Test
    void bonusModeIsZeroWithoutAKillerOrSource() {
        MobDropQualityResolver resolver =
                new MobDropQualityResolver(craftQuality(true), quality(), null, null);

        assertEquals(0, resolver.bonusMode(null, new SplittableRandom(0)));
    }

    @Test
    void plainEquipmentDropIsStampedAtDeathUsingMobLevel() {
        CraftQualityConfig craft = mock(CraftQualityConfig.class);
        when(craft.dropEnabled()).thenReturn(true);
        when(craft.dropStrengthPerQuality()).thenReturn(10);
        when(craft.dropBaseQuality()).thenReturn(0);
        when(craft.skillLevelsPerQuality()).thenReturn(10);
        when(craft.baseQuality()).thenReturn(0);
        QualityConfig quality = mock(QualityConfig.class);
        when(quality.maxQuality()).thenReturn(MAX_QUALITY);
        when(quality.spreadUp()).thenReturn(0.0);
        when(quality.spreadDown()).thenReturn(0.0);
        ItemFactory factory = mock(ItemFactory.class);
        when(factory.qualityVaries(any(ItemStack.class))).thenReturn(true);

        MobDropQualityResolver resolver = new MobDropQualityResolver(craft, quality, null, null);
        resolver.setItemFactory(factory);
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);

        resolver.stampPlainDrop(stack, 30, 0, new SplittableRandom(42L));

        // modeFromLevel(30, 10, 0) = 3. The random draw is zero-spread, so this is deterministic.
        verify(factory).stamp(eq(stack), anyLong(), eq(3));
    }

    @Test
    void barePlainEquipmentDropReachesTheRealFactoryStampPath() {
        ItemStatsConfig itemStats = mock(ItemStatsConfig.class);
        ItemStatProfile profile = new ItemStatProfile(
                java.util.Map.of("attack-damage", 5.0),
                java.util.Map.of("attack-damage", 0.5), java.util.Map.of());
        when(itemStats.profileFor(Material.DIAMOND_SWORD, null)).thenReturn(java.util.Optional.of(profile));
        ItemAssembler assembler = mock(ItemAssembler.class);
        ItemFactory factory = new ItemFactory(assembler, itemStats);
        MobDropQualityResolver resolver = new MobDropQualityResolver(
                craftQuality(true), quality(), itemStats, null);
        resolver.setItemFactory(factory);

        ItemStack bare = new ItemStack(Material.DIAMOND_SWORD);
        resolver.stampPlainDrop(bare, 30, 0, new SplittableRandom(42L));

        verify(assembler).assemble(any(), eq(Material.DIAMOND_SWORD), anyLong(), eq(3));
    }
}
