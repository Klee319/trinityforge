package com.trinityforge.stats;

import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W-85 (実サーバ報告 2026-08-18)「儀式で作成者より先に他のプレイヤーが拾うと所有権が最初に拾った
 * プレイヤーになる」の回帰ガード。
 *
 * <p>旧実装は成果物に「品質未決定」マーカーだけを刻んで置き、品質と SOULBOUND 所有者を
 * {@code PickupQualityListener} が<b>最初に拾ったプレイヤー</b>のステータスで決めていた。
 * ここで固定するのは「儀式完了時点で実行者基準に確定し、以後どのプレイヤーが拾っても
 * 二度と決め直されない」という契約。
 *
 * <p>{@link ItemAssembler} は本物を組まずモックし、PDC への rollSeed/quality 書き込みだけを
 * 再現する({@code ItemFactoryTest} と同じ方針 ── 本物の {@code AttributeApplier} は MockBukkit 未実装の
 * {@code Material#getDefaultAttributeModifiers} を踏むうえ、ここで検証したいのは組み立て内容ではなく
 * 「誰のステで・いつ確定するか」だけ)。
 */
class RitualCraftFinalizerTest {

    private static final long FIXED_SEED = 20260818L;
    private static final int PERFORMER_QUALITY = 42;
    private static final int PICKER_QUALITY = 7;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** rollSeed と quality だけを PDC へ書く最小 assembler(本物の lore/属性組み立ては対象外)。 */
    private static ItemFactory factoryWritingRollAndQuality() {
        ItemAssembler assembler = mock(ItemAssembler.class);
        when(assembler.assemble(any(), any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            ItemMeta meta = invocation.getArgument(0, ItemMeta.class);
            long seed = invocation.getArgument(2, Long.class);
            int quality = invocation.getArgument(3, Integer.class);
            ItemData data = ItemData.of(meta);
            data.setRollSeed(seed);
            data.setQuality(quality);
            return quality;
        });
        return new ItemFactory(assembler);
    }

    private static CraftQualityService qualityServiceReturning(Player performer, int quality) {
        CraftQualityService service = mock(CraftQualityService.class);
        when(service.rollArsSmithingQuality(any(Player.class), any(ItemStack.class)))
                .thenReturn(PICKER_QUALITY);
        when(service.rollArsSmithingQuality(org.mockito.ArgumentMatchers.eq(performer),
                any(ItemStack.class))).thenReturn(quality);
        when(service.craftRollMods(any())).thenReturn(CraftRollMods.NONE);
        return service;
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    /** 旧実装が置いていた状態(マーカーだけが立った未刻印の成果物)。 */
    private static ItemStack pendingResult(Material material) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> ItemData.of(meta).markPendingCraftQuality());
        return stack;
    }

    private static ItemStack pendingResult(Material material, BindType bindType) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            ItemData data = ItemData.of(meta);
            data.markPendingCraftQuality();
            data.setBindType(bindType);
        });
        return stack;
    }

    @Test
    @DisplayName("品質は儀式の実行者のステータスで確定する")
    void qualityIsResolvedFromThePerformer() {
        Player performer = playerWithId(UUID.randomUUID());
        ItemStack result = pendingResult(Material.DIAMOND_SWORD);

        boolean stamped = new RitualCraftFinalizer(
                qualityServiceReturning(performer, PERFORMER_QUALITY), factoryWritingRollAndQuality())
                .finalizeForPerformer(result, performer, FIXED_SEED);

        assertTrue(stamped);
        ItemData data = ItemData.of(result.getItemMeta());
        assertEquals(PERFORMER_QUALITY, data.quality(), "実行者のロール結果が刻まれていること");
    }

    @Test
    @DisplayName("確定後は拾い主が誰でも決め直されない(未決定マーカーが残らない)")
    void pickerCanNeverReRollAfterFinalize() {
        Player performer = playerWithId(UUID.randomUUID());
        ItemStack result = pendingResult(Material.DIAMOND_SWORD);
        assertTrue(ItemData.of(result.getItemMeta()).pendingCraftQuality(),
                "前提: 旧実装は未決定マーカーだけを立てて置いていた");

        new RitualCraftFinalizer(
                qualityServiceReturning(performer, PERFORMER_QUALITY), factoryWritingRollAndQuality())
                .finalizeForPerformer(result, performer, FIXED_SEED);

        ItemData data = ItemData.of(result.getItemMeta());
        // PickupQualityListener#stampIfEligible の再ロール条件は
        // 「pendingCraftQuality() が true」か「rollSeed が未刻印」。どちらも成立させない。
        assertFalse(data.pendingCraftQuality(), "未決定マーカーが残っていると拾い主のステで上書きされる");
        assertTrue(data.hasRollSeed(), "rollSeed が無いと拾い主側の刻印経路に落ちる");
        assertEquals(FIXED_SEED, data.rollSeed().orElseThrow());
    }

    @Test
    @DisplayName("SOULBOUND の所有者は実行者で確定する(先に拾った人ではない)")
    void soulboundOwnerIsThePerformer() {
        UUID performerId = UUID.randomUUID();
        Player performer = playerWithId(performerId);
        ItemStack result = pendingResult(Material.DIAMOND_SWORD, BindType.SOULBOUND);

        new RitualCraftFinalizer(
                qualityServiceReturning(performer, PERFORMER_QUALITY), factoryWritingRollAndQuality())
                .finalizeForPerformer(result, performer, FIXED_SEED);

        ItemData data = ItemData.of(result.getItemMeta());
        assertEquals(performerId, data.owner().orElseThrow());
        assertEquals(PERFORMER_QUALITY, data.quality(), "所有者刻印の再組み立てで品質が変わらないこと");
        assertEquals(FIXED_SEED, data.rollSeed().orElseThrow(), "rollSeed も据え置かれること");
    }

    @Test
    @DisplayName("TRADEABLE には所有者を刻まない")
    void tradeableGetsNoOwner() {
        Player performer = playerWithId(UUID.randomUUID());
        ItemStack result = pendingResult(Material.DIAMOND_SWORD, BindType.TRADEABLE);

        new RitualCraftFinalizer(
                qualityServiceReturning(performer, PERFORMER_QUALITY), factoryWritingRollAndQuality())
                .finalizeForPerformer(result, performer, FIXED_SEED);

        assertTrue(ItemData.of(result.getItemMeta()).owner().isEmpty());
    }

    @Test
    @DisplayName("OWNER_BOUND はコマンド専用なので自動では刻まない")
    void ownerBoundIsNotAutoStamped() {
        Player performer = playerWithId(UUID.randomUUID());
        ItemStack result = pendingResult(Material.DIAMOND_SWORD, BindType.OWNER_BOUND);

        new RitualCraftFinalizer(
                qualityServiceReturning(performer, PERFORMER_QUALITY), factoryWritingRollAndQuality())
                .finalizeForPerformer(result, performer, FIXED_SEED);

        assertTrue(ItemData.of(result.getItemMeta()).owner().isEmpty());
    }

    @Test
    @DisplayName("既に所有者がいる成果物は上書きしない")
    void existingOwnerIsPreserved() {
        UUID existing = UUID.randomUUID();
        Player performer = playerWithId(UUID.randomUUID());
        ItemStack result = pendingResult(Material.DIAMOND_SWORD, BindType.SOULBOUND);
        result.editMeta(meta -> ItemData.of(meta).setOwner(existing));

        new RitualCraftFinalizer(
                qualityServiceReturning(performer, PERFORMER_QUALITY), factoryWritingRollAndQuality())
                .finalizeForPerformer(result, performer, FIXED_SEED);

        assertEquals(existing, ItemData.of(result.getItemMeta()).owner().orElseThrow());
    }

    @Test
    @DisplayName("空スタック / 実行者不明では何もしない")
    void airAndNullAreNoOps() {
        Player performer = playerWithId(UUID.randomUUID());
        RitualCraftFinalizer finalizer = new RitualCraftFinalizer(
                qualityServiceReturning(performer, PERFORMER_QUALITY), factoryWritingRollAndQuality());

        assertFalse(finalizer.finalizeForPerformer(null, performer, FIXED_SEED));
        assertFalse(finalizer.finalizeForPerformer(new ItemStack(Material.AIR), performer, FIXED_SEED));
        assertFalse(finalizer.finalizeForPerformer(
                new ItemStack(Material.DIAMOND_SWORD), null, FIXED_SEED));
    }
}
