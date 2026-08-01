package com.trinityforge.active;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.skilltree.DedicatedEffectEntry;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
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

import java.util.LinkedHashMap;
import java.util.List;
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
    /** 直近の {@link ActiveContext#tier()}(ツリー別ゲート解決の検証用)。未発動なら {@code -1}。 */
    private AtomicInteger lastTier;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        registry = new ActiveSkillRegistry();
        activateCalls = new AtomicInteger();
        lastTier = new AtomicInteger(-1);
        registry.register(new ActiveSkill() {
            public String id() { return SKILL_ID; }
            public String gateEffectId() { return SKILL_ID; }
            public Set<String> targetSkills() { return Set.of(TARGET_SKILL, OTHER_TARGET_SKILL); }
            public long cooldownMillis(int tier) { return 1000L; }
            public ActivationResult activate(Player p, ActiveContext ctx) {
                activateCalls.incrementAndGet();
                lastTier.set(ctx.tier());
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
     * Stubs the gate lookup the dispatcher actually performs — the <b>tree-scoped</b> three-argument
     * {@code valueMax(player, effectId, useSkill)} (2026-08-01 実サーバ報告の修正) — for every skill
     * scope. Tests that care about the scoping use a real {@link DedicatedEffectsConfig} instead
     * (see {@code onlyTheHeldToolsOwnTree...} below).
     */
    private void stubUnlocked(OptionalDouble tier) {
        when(dedicatedEffects.valueMax(any(), eq(SKILL_ID), any())).thenReturn(tier);
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
        stubUnlocked(OptionalDouble.of(1.0));
        PlayerInteractEvent event = interactEvent(taggedItem(TARGET_SKILL), true);

        dispatcher.onInteract(event);

        assertEquals(1, activateCalls.get());
        org.mockito.Mockito.verify(event).setCancelled(true);
    }

    @Test
    void notSneakingNeverActivatesOrCancels() {
        stubUnlocked(OptionalDouble.of(1.0));
        PlayerInteractEvent event = interactEvent(taggedItem(TARGET_SKILL), false);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void itemNotTaggedNeverActivatesOrCancels() {
        stubUnlocked(OptionalDouble.of(1.0));
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.DIAMOND_PICKAXE), true);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void itemTaggedForDifferentSkillNeverActivatesOrCancels() {
        stubUnlocked(OptionalDouble.of(1.0));
        PlayerInteractEvent event = interactEvent(taggedItem("WOODCUTTING"), true);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void notUnlockedNeverActivatesOrCancels() {
        stubUnlocked(OptionalDouble.empty());
        PlayerInteractEvent event = interactEvent(taggedItem(TARGET_SKILL), true);

        dispatcher.onInteract(event);

        assertEquals(0, activateCalls.get());
        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void onCooldownRefusesButNeverCancels() {
        stubUnlocked(OptionalDouble.of(1.0));
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
        stubUnlocked(OptionalDouble.of(1.0));

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
        stubUnlocked(OptionalDouble.of(1.0));
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
        stubUnlocked(OptionalDouble.of(1.0));
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
        stubUnlocked(OptionalDouble.of(1.0));
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
        stubUnlocked(OptionalDouble.of(1.0));
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

    // --- 2026-08-01 実サーバ報告「シャベルを手に持っていても採掘速度上昇のバフが発動できる」 ---

    /**
     * 実配置の再現: {@code feature:haste-active-mining} は {@code mining.yml} A-1(value 1)/A-3(value 5) と
     * {@code digging.yml} A-1(value 1) の<b>両方</b>が置く。ゲートを「どのツリーで解放したか」を無視して
     * 引くと、採掘ツリーしか取っていないプレイヤーがシャベルでも発動できてしまう。
     */
    private ActivationDispatcher dispatcherWithRealGate(List<String> heldPerks) {
        DedicatedEffectsConfig realGate = new DedicatedEffectsConfig();
        realGate.reindex(List.of(
                treeWithHasteNodes("MINING", Map.of("A-1", 1.0, "A-3", 5.0)),
                treeWithHasteNodes("DIGGING", Map.of("A-1", 1.0))));
        PlayerData.of(player).setHeldPerks(heldPerks);
        return new ActivationDispatcher(registry, realGate, cooldowns, new FeedbackLayer(), aggregator);
    }

    private static SkillTree treeWithHasteNodes(String skill, Map<String, Double> tierByNodeId) {
        Map<String, SkillNode> nodes = new LinkedHashMap<>();
        tierByNodeId.forEach((nodeId, tier) -> nodes.put(nodeId, new SkillNode(
                nodeId, nodeId, 10, SkillRole.MAIN, null, null, "STONE", 1, "desc",
                Map.of(), Map.of(), List.of(), List.of(),
                List.of(new DedicatedEffectEntry("feature:" + SKILL_ID, tier)))));
        return new SkillTree(skill, skill, null, "2,10", null, nodes);
    }

    @Test
    void miningTreeUnlockAloneMustNotLetAShovelFireTheSharedActive() {
        // 採掘ツリーの A-1 だけを解放したプレイヤー。ツルハシでは撃てるが、シャベル(切削ツリー未解放)
        // では撃てないのが正しい。旧実装はゲートをツリー横断で引いていたので撃ててしまった。
        ActivationDispatcher realDispatcher = dispatcherWithRealGate(List.of("mining_perk_a_1"));

        realDispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(1, activateCalls.get(), "採掘ツリーを解放したツルハシでは発動できること");
        cooldowns.clear(player.getUniqueId());

        PlayerInteractEvent shovelEvent = interactEvent(taggedItem(OTHER_TARGET_SKILL), true);
        realDispatcher.onInteract(shovelEvent);

        assertEquals(1, activateCalls.get(),
                "切削ツリーのノードを解放していないのでシャベルでは発動できないこと");
        org.mockito.Mockito.verify(shovelEvent, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void diggingTreeUnlockAloneMustNotLetAPickaxeFireTheSharedActive() {
        ActivationDispatcher realDispatcher = dispatcherWithRealGate(List.of("digging_perk_a_1"));

        realDispatcher.onInteract(interactEvent(taggedItem(OTHER_TARGET_SKILL), true));
        assertEquals(1, activateCalls.get(), "切削ツリーを解放したシャベルでは発動できること");
        cooldowns.clear(player.getUniqueId());

        PlayerInteractEvent pickaxeEvent = interactEvent(taggedItem(TARGET_SKILL), true);
        realDispatcher.onInteract(pickaxeEvent);

        assertEquals(1, activateCalls.get(),
                "採掘ツリーのノードを解放していないのでツルハシでは発動できないこと");
        org.mockito.Mockito.verify(pickaxeEvent, org.mockito.Mockito.never()).setCancelled(true);
    }

    @Test
    void tierComesFromTheHeldToolsOwnTreeNotTheHighestAcrossTrees() {
        // 採掘 A-3(tier 5)と切削 A-1(tier 1)の両方を解放。シャベルで撃ったときの tier は
        // 切削ツリーの 1 でなければならない(横断の最大値 5 を拾ってはいけない)。
        ActivationDispatcher realDispatcher =
                dispatcherWithRealGate(List.of("mining_perk_a_3", "digging_perk_a_1"));

        realDispatcher.onInteract(interactEvent(taggedItem(OTHER_TARGET_SKILL), true));
        assertEquals(1, activateCalls.get());
        assertEquals(1, lastTier.get(), "シャベルの tier は切削ツリーの配置だけで決まること");

        cooldowns.clear(player.getUniqueId());
        realDispatcher.onInteract(interactEvent(taggedItem(TARGET_SKILL), true));
        assertEquals(2, activateCalls.get());
        assertEquals(5, lastTier.get(), "ツルハシの tier は採掘ツリーの最大値であること");
    }

    @Test
    void anotherActiveSkillsCooldownReductionKeyHasNoEffectOnThisSkillsCooldown() throws InterruptedException {
        // 2026-07-25 CT設計一本化 §2 regression: the whole point of splitting the old global
        // skill-cooldown-reduction into per-ActiveSkill keys is that a stat leaking through for a
        // DIFFERENT active skill's key (e.g. some future "other-active-skill-cooldown-reduction") must
        // never shorten THIS skill's CT — only ActiveSkillCooldownKeys.forSkill(SKILL_ID) may.
        stubUnlocked(OptionalDouble.of(1.0));
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
