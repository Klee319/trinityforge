package com.trinityforge.stats;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code loot_luck} (2026-07-23 stat-gate-overhaul §2 移行B5: 装備+perk合算、旧
 * {@code power_luckbonus_add} perk専用キーから移行).
 */
class PlayerLootLuckSourceTest {

    @Test
    void unavailableResolverFailsOpenToZero() {
        PlayerLootLuckSource source = new PlayerLootLuckSource(Logger.getLogger("test"), null);
        assertFalse(source.available());
        assertEquals("LUCK_BONUS", source.statKey());
        assertEquals(0.0, source.totalLuck(null));
    }

    @Test
    void vanillaLuckPotionGrantsPlusOnePerLevel() {
        // amplifier 0 = 幸運Lv1 = +1.0, amplifier 1 = Lv2 = +2.0
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK))
                .thenReturn(new PotionEffect(PotionEffectType.LUCK, 600, 1));
        assertEquals(2.0, PlayerLootLuckSource.vanillaLuckEffectLevel(player));
    }

    @Test
    void noLuckPotionContributesZero() {
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK)).thenReturn(null);
        assertEquals(0.0, PlayerLootLuckSource.vanillaLuckEffectLevel(player));
    }

    @Test
    void potionLuckCountsEvenWhenPerkResolverIsUnavailable() {
        PlayerLootLuckSource source = new PlayerLootLuckSource(Logger.getLogger("test"), null);
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK))
                .thenReturn(new PotionEffect(PotionEffectType.LUCK, 600, 0));
        assertEquals(1.0, source.totalLuck(player));
    }

    @Test
    void newLootLuckStatKeyDrivesTotalLuck() {
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK)).thenReturn(null);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(new PlayerCombatAggregate(
                Map.of("loot_luck", 2.0), Map.of(), Map.of(), Map.of(), Map.of()));
        PlayerLootLuckSource source = new PlayerLootLuckSource(Logger.getLogger("test"), aggregator);

        assertEquals(2.0, source.totalLuck(player));
    }

    @Test
    void oldNativeStatKeyNoLongerHasAnyEffect() {
        // Regression guard: the retired perk-only power_luckbonus_add key must not leak in as loot_luck.
        Player player = mock(Player.class);
        when(player.getPotionEffect(PotionEffectType.LUCK)).thenReturn(null);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(new PlayerCombatAggregate(
                Map.of("power_luckbonus_add", 5.0), Map.of(), Map.of(), Map.of(), Map.of()));
        PlayerLootLuckSource source = new PlayerLootLuckSource(Logger.getLogger("test"), aggregator);

        assertEquals(0.0, source.totalLuck(player));
    }
}
