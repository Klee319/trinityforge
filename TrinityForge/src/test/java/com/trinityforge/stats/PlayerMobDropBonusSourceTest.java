package com.trinityforge.stats;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code mob_drop_quality} (2026-07-23 stat-gate-overhaul §2 移行B6: 装備+perk合算、旧
 * {@code power_mobdropbonus_add} perk専用キーから移行).
 */
class PlayerMobDropBonusSourceTest {

    @Test
    void unavailableAggregatorFailsOpenToZero() {
        PlayerMobDropBonusSource source = new PlayerMobDropBonusSource(Logger.getLogger("test"), null);
        assertFalse(source.available());
        assertEquals("MOB_DROP_BONUS", source.statKey());
        assertEquals(0.0, source.totalBonus(null));
        assertEquals(0, source.qualityModeBonus(null, 0.99));
    }

    private static PlayerStatAggregator aggregatorReturning(Player player, double mobDropQuality) {
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(new PlayerCombatAggregate(
                Map.of("mob_drop_quality", mobDropQuality), Map.of(), Map.of(), Map.of(), Map.of()));
        return aggregator;
    }

    @Test
    void wholePointsGuaranteeModeBonusAndFractionIsStochastic() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        PlayerStatAggregator aggregator = aggregatorReturning(player, 1.5);
        PlayerMobDropBonusSource source = new PlayerMobDropBonusSource(Logger.getLogger("test"), aggregator);

        assertEquals(1.5, source.totalBonus(player));
        // 幸運と同じEVモデル: 整数部は確定、端数0.5はuniformロールで+1(0.4<0.5)か+0(0.6>=0.5)
        assertEquals(2, source.qualityModeBonus(player, 0.4));
        assertEquals(1, source.qualityModeBonus(player, 0.6));
    }

    @Test
    void negativeRewardClampsToZero() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        PlayerStatAggregator aggregator = aggregatorReturning(player, -3.0);
        PlayerMobDropBonusSource source = new PlayerMobDropBonusSource(Logger.getLogger("test"), aggregator);

        assertEquals(0.0, source.totalBonus(player));
    }

    @Test
    void oldNativeStatKeyNoLongerHasAnyEffect() {
        // Regression guard: the retired perk-only power_mobdropbonus_add key must not leak in as
        // mob_drop_quality via any implicit alias — only the new canonical key is read.
        Player player = mock(Player.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(new PlayerCombatAggregate(
                Map.of("power_mobdropbonus_add", 5.0), Map.of(), Map.of(), Map.of(), Map.of()));
        PlayerMobDropBonusSource source = new PlayerMobDropBonusSource(Logger.getLogger("test"), aggregator);

        assertEquals(0.0, source.totalBonus(player));
    }

    // ------------------------------------------------------------------
    // W-253 (2026-08-25): 幸運のポーションもモブドロップ品質へ乗る。
    // ⚠ この4本は「幸運はモブドロップを意図的に対象外」という旧仕様へ戻すと落ちる。
    // ------------------------------------------------------------------

    @Test
    void vanillaLuckPotionAddsToMobDropQuality() {
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK))
                .thenReturn(new PotionEffect(PotionEffectType.LUCK, 600, 1)); // amplifier1 = 幸運II
        PlayerStatAggregator aggregator = aggregatorReturning(player, 1.0);
        PlayerMobDropBonusSource source =
                new PlayerMobDropBonusSource(Logger.getLogger("test"), aggregator, () -> 1.0);

        // 装備ステ 1.0 + 幸運II(=2レベル) x レート1.0 = 3.0
        assertEquals(3.0, source.totalBonus(player));
        assertEquals(3, source.qualityModeBonus(player, 0.99));
    }

    @Test
    void luckContributesEvenWithoutAggregator() {
        // 装備ステが読めない構成でも幸運ぶんは効く(合算器の有無で幸運が消えない)。
        PlayerMobDropBonusSource source =
                new PlayerMobDropBonusSource(Logger.getLogger("test"), null, () -> 1.0);
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK))
                .thenReturn(new PotionEffect(PotionEffectType.LUCK, 600, 0));
        assertEquals(1.0, source.totalBonus(player));
    }

    @Test
    void luckRateZeroTurnsTheFeatureOff() {
        // stats/quality.yml の luck-potion-quality-per-level: 0 でこの機能だけ切れる。
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK))
                .thenReturn(new PotionEffect(PotionEffectType.LUCK, 600, 4));
        PlayerMobDropBonusSource source =
                new PlayerMobDropBonusSource(Logger.getLogger("test"), null, () -> 0.0);
        assertEquals(0.0, source.totalBonus(player));
    }

    @Test
    void missingRateSupplierFallsBackToOnePerLevel() {
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK))
                .thenReturn(new PotionEffect(PotionEffectType.LUCK, 600, 2)); // 幸運III
        PlayerMobDropBonusSource source =
                new PlayerMobDropBonusSource(Logger.getLogger("test"), null);
        assertEquals(3.0, source.totalBonus(player));
    }
}
