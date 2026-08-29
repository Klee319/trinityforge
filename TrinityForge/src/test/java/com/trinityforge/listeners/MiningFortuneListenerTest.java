package com.trinityforge.listeners;

import com.trinityforge.stats.PercentStatNormalize;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiningFortuneListenerTest {

    @Test
    void expectedExtraIsFortuneRatePlusLevelTerm() {
        assertEquals(0.0, MiningFortuneListener.expectedExtraRate(0, 0, 1.0), 0.0);
        assertEquals(1.0, MiningFortuneListener.expectedExtraRate(1, 0, 1.0), 1e-9);
        assertEquals(3.0, MiningFortuneListener.expectedExtraRate(1, 2, 1.0), 1e-9);
    }

    /**
     * 2026-07-28 回帰: skilltree の「ドロップ増加+15%」ノードは {@code mining-fortune: 15} と
     * パーセントポイントで書かれている。{@link PercentStatNormalize} で 0.15 へ矯正されたうえで
     * 期待値がちょうど +0.15 個/ブロックになること(=宣言どおり)を固定する。矯正が抜けると
     * 15 のまま渡り、1ブロックあたり15個の追加ドロップになる(実サーバーで約6倍のドロップとして
     * 表面化した)。
     */
    @Test
    void skilltreePercentPointBuffYieldsAdvertisedRate() {
        double coerced = PercentStatNormalize.coerce("mining-fortune", 15.0);
        assertEquals(0.15, coerced, 1e-9);
        assertEquals(0.15, MiningFortuneListener.expectedExtraRate(coerced, 0, 0.003), 1e-9);
    }

    /** レベル項は出荷 yml の 0.003 で Lv100 = +30% 相当(× 0.30 撤去前と同じ増加量)。 */
    @Test
    void levelTermKeepsPreviousBalance() {
        assertEquals(0.30, MiningFortuneListener.expectedExtraRate(0.0, 100, 0.003), 1e-9);
    }

    @Test
    void oneWayCrushOresAreSilkPlaceCrushTargets() {
        assertTrue(MiningFortuneListener.isOneWayCrushOre(Material.IRON_ORE));
        assertTrue(MiningFortuneListener.isOneWayCrushOre(Material.DEEPSLATE_GOLD_ORE));
        assertTrue(MiningFortuneListener.isOneWayCrushOre(Material.COPPER_ORE));
        assertTrue(MiningFortuneListener.isOneWayCrushOre(Material.DIAMOND_ORE));
        assertTrue(MiningFortuneListener.isOneWayCrushOre(Material.NETHER_QUARTZ_ORE));
        assertFalse(MiningFortuneListener.isOneWayCrushOre(Material.GLOWSTONE));
        assertFalse(MiningFortuneListener.isOneWayCrushOre(Material.SEA_LANTERN));
        assertFalse(MiningFortuneListener.isOneWayCrushOre(Material.MELON));
        assertFalse(MiningFortuneListener.isOneWayCrushOre(Material.WHEAT));
        assertFalse(MiningFortuneListener.isOneWayCrushOre(Material.ANCIENT_DEBRIS),
                "残骸はドロップがブロック自身なので循環");
    }
}
