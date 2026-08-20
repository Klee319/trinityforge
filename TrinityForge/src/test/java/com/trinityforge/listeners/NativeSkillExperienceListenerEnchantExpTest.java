package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.StatKeys;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NativeSkillExperienceListener#onEnchant} が<b>プレイヤー単位のEXP倍率を一切掛けない</b>ことを固定する
 * (2026-08-14 実サーバ報告「EXP増加(エンチャント)の説明がスキルEXP増加になっているが、それは別にある」)。
 *
 * <h2>なぜこの向きの検査に変わったか</h2>
 * かつてここには専用ステ {@code enchant_exp_gain_bonus} があり、このリスナーが
 * {@code amount × (1 + bonus)} と先に掛けていた。しかし {@link SkillId#ENCHANTING} への EXP 付与点は
 * このメソッドの1箇所しかないため、職業EXP増加の共通機構 {@code enchanting_exp_bonus}
 * ({@code <スキルID>_exp_bonus}) と<b>同じ量に別経路で掛かる重複</b>でしかなかった。
 * lore にも「EXP増加(エンチャント)」と「職業EXP増加(エンチャント)」が並んで区別できない状態だった。
 *
 * <p>統合にあたり {@link StatKeys} へ旧キー→新キーのエイリアスを入れたので、
 * <b>ここで旧定数を読み直すと {@code enchanting_exp_bonus} が listener と
 * {@code NativeProgressionService#grant} の二重で掛かる</b>。この二重適用こそが最も起きやすい
 * 巻き戻し方なので、「倍率が報告されていても付与量は素のまま」を明示的に落とす形で固定する。
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

    /** 統合先/旧キーのどちらを読んでも倍率が返る集計器。二重適用が起きればここが効いて量がずれる。 */
    private void stubExpBonus(PlayerStatAggregator aggregator, Player player, double bonus) {
        PlayerCombatAggregate totals = mock(PlayerCombatAggregate.class);
        when(totals.totalOf(StatKeys.canonical("enchanting_exp_bonus"))).thenReturn(bonus);
        when(totals.totalOf(StatKeys.canonical("enchant_exp_gain_bonus"))).thenReturn(bonus);
        when(aggregator.aggregate(player)).thenReturn(totals);
    }

    @Test
    @DisplayName("設定表どおりの素の量を付与する(button 0 → 1レベル × 0.5 で下限1.0)")
    void grantsTheBaselineAmount() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        Player enchanter = enchanter();
        stubExpBonus(aggregator, enchanter, 0.0);
        NativeSkillExperienceListener listener = newListener(dispatcher, aggregator);

        listener.onEnchant(enchantEvent(enchanter, 0));

        verify(dispatcher).grant(enchanter.getUniqueId(), SkillId.ENCHANTING, 1.0);
    }

    @Test
    @DisplayName("職業EXP増加(エンチャント)を持っていても、このリスナーは倍率を掛けない(二重適用の防止)")
    void doesNotPreMultiplyBySkillExpBonus() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        Player enchanter = enchanter();
        stubExpBonus(aggregator, enchanter, 0.1); // +10% を報告するが、掛けるのは下流の役目
        NativeSkillExperienceListener listener = newListener(dispatcher, aggregator);

        listener.onEnchant(enchantEvent(enchanter, 2)); // button 2 -> 3レベル × 0.5 = 1.5

        // 1.65(=1.5×1.1) になったら、旧 enchant_exp_gain_bonus の乗算が復活して
        // NativeProgressionService#grant の適用と二重に掛かっている。
        verify(dispatcher).grant(eq(enchanter.getUniqueId()), eq(SkillId.ENCHANTING),
                org.mockito.ArgumentMatchers.doubleThat(amount -> Math.abs(amount - 1.5) < 1e-9));
    }

    @Test
    @DisplayName("付与量が負になることはない")
    void neverGrantsNegativeAmount() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        Player enchanter = enchanter();
        stubExpBonus(aggregator, enchanter, -1.5);
        NativeSkillExperienceListener listener = newListener(dispatcher, aggregator);

        listener.onEnchant(enchantEvent(enchanter, 0));

        verify(dispatcher, never()).grant(eq(enchanter.getUniqueId()), eq(SkillId.ENCHANTING),
                org.mockito.ArgumentMatchers.doubleThat(amount -> amount < 0.0));
        verify(dispatcher).grant(eq(enchanter.getUniqueId()), eq(SkillId.ENCHANTING), anyDouble());
    }

    @Test
    @DisplayName("旧キー enchant_exp_gain_bonus は enchanting_exp_bonus へ読み替わる(手編集ymlの無言死を防ぐ)")
    void legacyKeyResolvesToTheUnifiedKey() {
        assertEquals(StatKeys.canonical("enchanting_exp_bonus"),
                StatKeys.canonical("enchant_exp_gain_bonus"),
                "旧キーが新キーへ読み替わらないと、手編集ymlに残った enchant-exp-gain-bonus が"
                        + "警告もエラーも出さずに効かなくなる");
        assertEquals(StatKeys.canonical("enchanting_exp_bonus"),
                StatKeys.canonical("enchant-exp-gain-bonus"),
                "kebab-case 綴りでも同じ読み替えが効くこと");
    }
}
