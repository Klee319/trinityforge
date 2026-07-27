package com.trinityforge.active;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.StatKeys;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ActivationDispatcher}: the sneak+右クリック+use-skill一致 trigger contract (2026-07-25
 * gather-rework-active-framework §6 Q2, orchestrating-brief override) — including the
 * "refuse != cancel" rule (CT/gate/item-mismatch refusals never cancel the event; only an actual
 * activation attempt — success or skill-internal failure — does).
 */
class ActivationDispatcherTest {

    private static final String SKILL_ID = "haste-active-mining";
    private static final String TARGET_SKILL = "MINING";
    private static final String OTHER_TARGET_SKILL = "DIGGING";
    private static final String SKILL_COOLDOWN_REDUCTION_KEY = ActiveSkillCooldownKeys.forSkill(SKILL_ID);
    private static final String ITEM_COOLDOWN_REDUCTION_KEY = StatKeys.canonical("cooldown-reduction");
    private static final String OTHER_SKILL_COOLDOWN_REDUCTION_KEY =
            ActiveSkillCooldownKeys.forSkill("other-active-skill");

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private ActiveSkillRegistry registry;
    private CooldownManager cooldowns;
    private PlayerStatAggregator aggregator;
    private ActivationDispatcher dispatcher;
    private PlayerMock player;
    private AtomicInteger activateCalls;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        registry = new ActiveSkillRegistry();
        activateCalls = new AtomicInteger();
        registry.register(new ActiveSkill() {
            public String id() { return SKILL_ID; }
            public String gateEffectId() { return SKILL_ID; }
            public Set<String> targetSkills() { return Set.of(TARGET_SKILL, OTHER_TARGET_SKILL); }
            public long cooldownMillis(int tier) { return 1000L; }
            public ActivationResult activate(Player p, ActiveContext ctx) {
                activateCalls.incrementAndGet();
                return ActivationResult.success("発動！");
            }
        });
        cooldowns = new CooldownManager();
        aggregator = mock(PlayerStatAggregator.class);
        stubSkillCooldownReduction(0.0); // no reduction by default: base 1000ms CT unless a test overrides it.
        dispatcher = new ActivationDispatcher(registry, dedicatedEffects, cooldowns, new FeedbackLayer(), aggregator);
        player = server.addPlayer();
    }

    /**
     * Stubs {@code aggregator.aggregate(any()).totalOf(haste-active-mining-cooldown-reduction)} (this
     * test's registered skill's per-skill key, via {@link ActiveSkillCooldownKeys#forSkill(String)}) to
     * return {@code value}.
     */
    private void stubSkillCooldownReduction(double value) {
        when(aggregator.aggregate(any())).thenReturn(new PlayerCombatAggregate(
                Map.of(SKILL_COOLDOWN_REDUCTION_KEY, value), Map.of(), Map.of(), Map.of(), Map.of()));
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

    private PlayerInteractEvent interactEvent(ItemStack mainHand, boolean sneaking) {
        player.getInventory().setItemInMainHand(mainHand);
        player.setSneaking(sneaking);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        return event;
    }

    @Test
    void unlockedAndOffCooldownActivatesAndCancelsEvent() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        PlayerInteractEvent event = interactEvent(taggedItem(TARGET_SKILL), true);

        dispatcher.onInteract(event);

        assertEquals(1, activateCalls.get());
        org.mockito.Mockito.verify(event).setCancelled(true);
    }

    @Test
    void notSneakingNeverActivatesOrCancels() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        PlayerInteractEvent event = interactEvent(taggedItem(TARGET_SKILL), false);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void itemNotTaggedNeverActivatesOrCancels() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.DIAMOND_PICKAXE), true);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void itemTaggedForDifferentSkillNeverActivatesOrCancels() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        PlayerInteractEvent event = interactEvent(taggedItem("WOODCUTTING"), true);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void notUnlockedNeverActivatesOrCancels() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.empty());
        PlayerInteractEvent event = interactEvent(taggedItem(TARGET_SKILL), true);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void onCooldownRefusesButNeverCancels() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        // First activation consumes the cooldown.
        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(1, activateCalls.get());

        // Second attempt immediately after: on cooldown, must refuse without cancelling.
        PlayerInteractEvent second = interactEvent(taggedItem(TARGET_SKILL), true);
        dispatcher.onInteract(second);

        assertEquals(1, activateCalls.get(), "must not activate again while on cooldown");
        org.mockito.Mockito.verify(second, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void cooldownIsSharedAcrossTargetSkillsNotPerTriggeringItem() {
        // 2026-07-25 regression coverage: haste-active-mining is reachable from both a pickaxe
        // (use-skill=MINING) and a shovel (use-skill=DIGGING) via ActiveSkill#targetSkills(). The
        // cooldown is keyed by ActiveSkill#id() alone, so activating via one tool must block an
        // immediate re-activation via the other tool — never two independent CT tracks.
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));

        // Activate via a pickaxe tagged for MINING: succeeds, consumes the shared cooldown.
        PlayerInteractEvent pickaxeEvent = interactEvent(taggedItem(TARGET_SKILL), true);
        dispatcher.onInteract(pickaxeEvent);
        assertEquals(1, activateCalls.get());
        org.mockito.Mockito.verify(pickaxeEvent).setCancelled(true);

        // Immediately try again via a shovel tagged for DIGGING: same active id, still on cooldown,
        // must be refused without a second activation and without cancelling the event.
        PlayerInteractEvent shovelEvent = interactEvent(taggedItem(OTHER_TARGET_SKILL), true);
        dispatcher.onInteract(shovelEvent);

        assertEquals(1, activateCalls.get(), "shovel attempt must not re-activate while pickaxe-triggered cooldown is active");
        org.mockito.Mockito.verify(shovelEvent, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void offHandInteractIsIgnored() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        player.getInventory().setItemInMainHand(taggedItem(TARGET_SKILL));
        player.setSneaking(true);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
    }

    @Test
    void quitClearsCooldownSoNextSessionActivatesImmediately() {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(1, activateCalls.get());

        cooldowns.clear(player.getUniqueId());

        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(2, activateCalls.get());
    }

    // --- 2026-07-25 CT設計一本化 §2: the skill's own per-skill key shortens the active-skill CT ---

    @Test
    void skillCooldownReductionShortensTheActiveSkillCooldown() throws InterruptedException {
        // 90% reduction -> base 1000ms CT becomes 100ms (CooldownManager.applyReduction clamp).
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        stubSkillCooldownReduction(0.9);
        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(1, activateCalls.get());

        // 250ms of real time later (comfortably clears the shortened 100ms CT but would still be well
        // within the un-shortened 1000ms one): proves the reduction was actually applied, not ignored.
        Thread.sleep(250);
        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(2, activateCalls.get(), "the shortened cooldown must already have elapsed");
    }

    @Test
    void itemCooldownReductionKeyHasNoEffectOnActiveSkillCooldown() throws InterruptedException {
        // 2026-07-25 CT短縮ステータス分離 §1-B regression: cooldown-reduction (アイテムCT短縮) must never shorten
        // an active skill's CT — only skill-cooldown-reduction may. Stub the OLD key at 90% (would shrink the
        // 1000ms base to 100ms if it leaked through) while leaving skill-cooldown-reduction unset (0).
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        when(aggregator.aggregate(any())).thenReturn(new PlayerCombatAggregate(
                Map.of(ITEM_COOLDOWN_REDUCTION_KEY, 0.9), Map.of(), Map.of(), Map.of(), Map.of()));

        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(1, activateCalls.get());

        // 250ms later: past the (leaked) 100ms shortened CT, but still well within the correct 1000ms CT.
        // If cooldown-reduction had leaked into the active-skill path, this second attempt would succeed.
        Thread.sleep(250);
        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(1, activateCalls.get(),
                "cooldown-reduction (item CT key) must not shorten the active skill's own CT");
    }

    @Test
    void anotherActiveSkillsCooldownReductionKeyHasNoEffectOnThisSkillsCooldown() throws InterruptedException {
        // 2026-07-25 CT設計一本化 §2 regression: the whole point of splitting the old global
        // skill-cooldown-reduction into per-ActiveSkill keys is that a stat leaking through for a
        // DIFFERENT active skill's key (e.g. some future "other-active-skill-cooldown-reduction") must
        // never shorten THIS skill's CT — only ActiveSkillCooldownKeys.forSkill(SKILL_ID) may.
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID))).thenReturn(OptionalDouble.of(1.0));
        when(aggregator.aggregate(any())).thenReturn(new PlayerCombatAggregate(
                Map.of(OTHER_SKILL_COOLDOWN_REDUCTION_KEY, 0.9), Map.of(), Map.of(), Map.of(), Map.of()));

        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(1, activateCalls.get());

        // 250ms later: past the (leaked) 100ms shortened CT, but still well within the correct 1000ms CT.
        Thread.sleep(250);
        dispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(1, activateCalls.get(),
                "another ActiveSkill's own cooldown-reduction key must not shorten this skill's CT");
    }
}
