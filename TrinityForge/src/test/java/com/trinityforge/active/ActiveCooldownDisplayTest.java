package com.trinityforge.active;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ActiveCooldownDisplay}: 2026-07-28 ユーザー要望「アクティブスキルのクールタイム中に
 * 残り何秒かの表示が欲しい」。表示条件は発動条件と同じ絞り込み(対象 use-skill を持っていて、
 * かつ解放済み)であること、CTが走っていなければ何も出さないことを固定する。
 */
class ActiveCooldownDisplayTest {

    private static final String SKILL_ID = "haste-active-mining";
    private static final String TARGET_SKILL = "MINING";
    private static final String DISPLAY_NAME = "採掘速度上昇";
    private static final String REDUCTION_KEY = ActiveSkillCooldownKeys.forSkill(SKILL_ID);

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private ActiveSkillRegistry registry;
    private CooldownManager cooldowns;
    private FeedbackLayer feedback;
    private PlayerStatAggregator aggregator;
    private ActiveCooldownDisplay display;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        registry = new ActiveSkillRegistry();
        registry.register(new ActiveSkill() {
            public String id() { return SKILL_ID; }
            public String displayName() { return DISPLAY_NAME; }
            public String gateEffectId() { return SKILL_ID; }
            public Set<String> targetSkills() { return Set.of(TARGET_SKILL); }
            public long cooldownMillis(int tier) { return 10_000L; }
            public ActivationResult activate(Player p, ActiveContext ctx) {
                return ActivationResult.success("発動！");
            }
        });
        cooldowns = new CooldownManager();
        feedback = mock(FeedbackLayer.class);
        aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any())).thenReturn(new PlayerCombatAggregate(
                Map.of(REDUCTION_KEY, 0.0), Map.of(), Map.of(), Map.of(), Map.of()));
        display = new ActiveCooldownDisplay(MockBukkit.createMockPlugin(), registry, dedicatedEffects,
                cooldowns, feedback, aggregator);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack taggedItem(String useSkill) {
        ItemStack stack = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setUseRequirement(useSkill, 0);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void showsRemainingWhileTheCooldownIsRunning() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        player.getInventory().setItemInMainHand(taggedItem(TARGET_SKILL));
        cooldowns.tryConsume(player.getUniqueId(), SKILL_ID, 10_000L, System.currentTimeMillis());

        display.run();

        verify(feedback).cooldownTicking(eq(player), eq(DISPLAY_NAME), anyLong());
    }

    @Test
    void staysSilentWhenTheSkillWasNeverUsed() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        player.getInventory().setItemInMainHand(taggedItem(TARGET_SKILL));

        display.run();

        verify(feedback, never()).cooldownTicking(any(), anyString(), anyLong());
    }

    @Test
    void staysSilentWhileHoldingAnItemForAnotherSkill() {
        // 関係ない道具を持っている間までアクションバーを占有すると、他のフィードバックを潰す。
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        player.getInventory().setItemInMainHand(taggedItem("HEAVY_WEAPONS"));
        cooldowns.tryConsume(player.getUniqueId(), SKILL_ID, 10_000L, System.currentTimeMillis());

        display.run();

        verify(feedback, never()).cooldownTicking(any(), anyString(), anyLong());
    }

    @Test
    void staysSilentWhenTheSkillIsNotUnlocked() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.empty());
        player.getInventory().setItemInMainHand(taggedItem(TARGET_SKILL));
        cooldowns.tryConsume(player.getUniqueId(), SKILL_ID, 10_000L, System.currentTimeMillis());

        display.run();

        verify(feedback, never()).cooldownTicking(any(), anyString(), anyLong());
    }

    @Test
    void showsSemiActiveCooldownWhileItsActivationToolIsHeld() {
        SemiActiveCooldown semiActive = mock(SemiActiveCooldown.class);
        when(semiActive.id()).thenReturn("tree-fell");
        when(semiActive.displayName()).thenReturn("一括伐採");
        when(semiActive.isEligible(player)).thenReturn(true);
        when(semiActive.cooldownMillis(player)).thenReturn(10_000L);
        display = new ActiveCooldownDisplay(MockBukkit.createMockPlugin(), registry, dedicatedEffects,
                cooldowns, feedback, aggregator, List.of(semiActive));
        cooldowns.tryConsume(player.getUniqueId(), "tree-fell", 10_000L, System.currentTimeMillis());

        display.run();

        verify(feedback).cooldownTicking(eq(player), eq("一括伐採"), anyLong());
    }

    @Test
    void staysSilentForSemiActiveCooldownWhileItsActivationToolIsNotHeld() {
        SemiActiveCooldown semiActive = mock(SemiActiveCooldown.class);
        when(semiActive.id()).thenReturn("tree-fell");
        when(semiActive.isEligible(player)).thenReturn(false);
        display = new ActiveCooldownDisplay(MockBukkit.createMockPlugin(), registry, dedicatedEffects,
                cooldowns, feedback, aggregator, List.of(semiActive));
        cooldowns.tryConsume(player.getUniqueId(), "tree-fell", 10_000L, System.currentTimeMillis());

        display.run();

        verify(feedback, never()).cooldownTicking(any(), anyString(), anyLong());
        verify(semiActive, never()).cooldownMillis(any());
    }
}
