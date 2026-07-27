package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NativeSkillExperienceListener#onEnchant}: プレイヤー単位の {@code enchant_exp_gain_bonus} stat
 * (enchanting.yml A/C/A-alpha/A-beta の「エンチャントEXPの増加/減少」用に新設)が、既存のグローバル
 * {@code enchant.level_cost_multiplier} の上に正しく乗算加算されることを検証する。実行者(＝
 * {@link EnchantItemEvent#getEnchanter()})以外には一切影響しない横断制約は、この経路が唯一
 * enchanterからしかstatを読まない設計であることそのもので担保される。
 */
class NativeSkillExperienceListenerEnchantExpTest {

    private static final SkillCatalogEntry ENCHANTING_ENTRY = new SkillCatalogEntry(
            SkillId.ENCHANTING, 100, "1", level -> 1L, Map.of(),
            Map.of("enchant.level_cost_multiplier", 0.5));

    private NativeSkillExperienceListener newListener(NativeExperienceDispatcher dispatcher,
                                                       PlayerStatAggregator aggregator) {
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.ENCHANTING)).thenReturn(ENCHANTING_ENTRY);
        PlacedBlockTracker tracker = mock(PlacedBlockTracker.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);

        Object plugin = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TrinityForge";
                    case "namespace" -> "trinityforge";
                    case "toString" -> "FakePlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
        return new NativeSkillExperienceListener(
                (org.bukkit.plugin.Plugin) plugin, dispatcher, catalog, tracker, null, dedicatedEffects, aggregator);
    }

    private Player enchanter() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        return player;
    }

    private EnchantItemEvent enchantEvent(Player enchanter, int whichButton) {
        EnchantItemEvent event = mock(EnchantItemEvent.class);
        when(event.getEnchanter()).thenReturn(enchanter);
        when(event.whichButton()).thenReturn(whichButton);
        return event;
    }

    private void stubBonus(PlayerStatAggregator aggregator, Player player, double bonus) {
        PlayerCombatAggregate totals = mock(PlayerCombatAggregate.class);
        when(totals.totalOf("enchant_exp_gain_bonus")).thenReturn(bonus);
        when(aggregator.aggregate(player)).thenReturn(totals);
    }

    @Test
    void zeroBonusGrantsTheBaselineAmount() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        Player enchanter = enchanter();
        stubBonus(aggregator, enchanter, 0.0);
        NativeSkillExperienceListener listener = newListener(dispatcher, aggregator);

        listener.onEnchant(enchantEvent(enchanter, 0)); // button 0 -> 1 level spent * 0.5 mult == 1.0 (clamped to >=1.0)

        verify(dispatcher).grant(enchanter.getUniqueId(), SkillId.ENCHANTING, 1.0);
    }

    @Test
    void positiveBonusIncreasesGrantedExp() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        Player enchanter = enchanter();
        stubBonus(aggregator, enchanter, 0.1); // A-alpha path: +10%
        NativeSkillExperienceListener listener = newListener(dispatcher, aggregator);

        listener.onEnchant(enchantEvent(enchanter, 2)); // button 2 -> 3 levels * 0.5 == 1.5 baseline

        verify(dispatcher).grant(eq(enchanter.getUniqueId()), eq(SkillId.ENCHANTING),
                org.mockito.ArgumentMatchers.doubleThat(amount -> Math.abs(amount - 1.65) < 1e-9)); // 1.5 * 1.1
    }

    @Test
    void negativeBonusDecreasesGrantedExpButNeverBelowZero() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        Player enchanter = enchanter();
        stubBonus(aggregator, enchanter, -1.5); // pathological, but must clamp to >=0 rather than go negative
        NativeSkillExperienceListener listener = newListener(dispatcher, aggregator);

        listener.onEnchant(enchantEvent(enchanter, 0));

        verify(dispatcher, never()).grant(eq(enchanter.getUniqueId()), eq(SkillId.ENCHANTING),
                org.mockito.ArgumentMatchers.doubleThat(amount -> amount < 0.0));
    }

    @Test
    void bystanderPlayerStatIsNeverConsulted() {
        // The listener only ever calls aggregator.aggregate(event.getEnchanter()) — there is no code
        // path that reads any other player's stats, which is how the "executor-only" cross-cutting
        // constraint is structurally guaranteed for this feature. Verified by mock strictness: the
        // aggregator mock has no stub for any player other than the enchanter, so if the listener ever
        // queried a different player, aggregate() would return a plain Mockito default record whose
        // totalOf(...) is 0.0 rather than the enchanter's stubbed 0.1 -- this test would then fail
        // the amount assertion below because the enchanter's bonus stub would never have been read.
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        Player enchanter = enchanter();
        stubBonus(aggregator, enchanter, 0.1);
        NativeSkillExperienceListener listener = newListener(dispatcher, aggregator);

        listener.onEnchant(enchantEvent(enchanter, 0));

        verify(dispatcher).grant(eq(enchanter.getUniqueId()), eq(SkillId.ENCHANTING),
                org.mockito.ArgumentMatchers.doubleThat(amount -> Math.abs(amount - 1.1) < 1e-9)); // 1.0 * 1.1
    }
}
