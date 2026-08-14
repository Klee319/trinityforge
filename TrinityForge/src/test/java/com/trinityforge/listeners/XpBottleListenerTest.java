package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.fishing.XpBottlePolicy;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link XpBottleListener}: 格納/取出の起点と {@code xp-bottle-store.return-rate} の反映。
 *
 * <p><b>2026-08-05 仕様変更</b>(ユーザー指示「ガラス瓶を右クリックで貯蔵される仕様にしたい」):
 * 格納の起点は「経験値瓶の sneak+右クリック」から「<b>ガラス瓶の通常右クリック</b>」へ変わった。
 * 旧仕様に戻すと {@link #glassBottleRightClickStoresExperience} が落ちる。
 *
 * <p>ガラス瓶はバニラの用途を持つ道具なので、水汲み・ブロック操作を奪わないことも固定する
 * (この網を外すと水が汲めなくなる/チェストが開かなくなる)。視線上の水源探索は
 * <b>MockBukkit が {@code rayTraceBlocks} 未実装でテストが SKIPPED に化ける</b>ため
 * {@code waterTargetLookupForTest} で差し替える。どのブロックを「汲める」と見るかの判定は
 * {@link #isWaterFillTargetCoversCauldronAndWaterloggedBlocks} で本物を検証する。
 */
class XpBottleListenerTest {

    private static final String EFFECT = "xp-bottle-store-unlock";

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private CraftingFeaturesConfig gimmickConfig;
    private XpBottleListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(CraftingFeaturesConfig.class);
        listener = new XpBottleListener(dedicatedEffects, gimmickConfig);
        // 既定は「視線上に水が無い」。水汲みの検証だけ個別に差し替える。
        listener.waterTargetLookupForTest(p -> null);
        player = server.addPlayer();
        // 2026-07-26 tier-expand: xp-bottle-store-unlock は SCALE化(feature:<id> valueMax でゲート)。
        // 既存の単一解放ノードに value: が無ければ tier1 が自動補完される想定を再現する。
        when(dedicatedEffects.valueMax(any(), eq(EFFECT))).thenReturn(OptionalDouble.of(1.0));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- 格納: ガラス瓶の通常右クリック --------------------------------------------------------

    @Test
    @DisplayName("ガラス瓶の通常右クリック(sneakなし)で経験値が格納される")
    void glassBottleRightClickStoresExperience() {
        when(gimmickConfig.xpBottleStoreAmount(1)).thenReturn(100);
        player.giveExp(500);
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.GLASS_BOTTLE), null);

        listener.onInteract(event);

        assertEquals(400, totalPlayerExp(), "格納した100だけプレイヤーの経験値から引かれる");
        ItemStack inHand = player.getInventory().getItemInMainHand();
        assertEquals(Material.EXPERIENCE_BOTTLE, inHand.getType(), "ガラス瓶が充填済み経験値瓶になる");
        assertEquals(100, storedAmountOf(inHand));
        verify(event, times(1)).setCancelled(true);
    }

    @Test
    @DisplayName("素のバニラ経験値瓶(PDCなし)は投擲のまま — 旧 sneak+右クリック格納は無くなった")
    void plainExperienceBottleIsLeftToVanilla() {
        when(gimmickConfig.xpBottleStoreAmount(1)).thenReturn(100);
        player.giveExp(500);
        player.setSneaking(true);
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.EXPERIENCE_BOTTLE), null);

        listener.onInteract(event);

        assertEquals(500, totalPlayerExp(), "経験値瓶では格納されない");
        assertNull(storedAmountOf(player.getInventory().getItemInMainHand()));
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    @DisplayName("経験値を1も持っていなければ何もしない(ガラス瓶を消費しない)")
    void storingNothingIsANoOp() {
        when(gimmickConfig.xpBottleStoreAmount(1)).thenReturn(100);
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.GLASS_BOTTLE), null);

        listener.onInteract(event);

        assertEquals(Material.GLASS_BOTTLE, player.getInventory().getItemInMainHand().getType());
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    @DisplayName("効果を解放していなければガラス瓶に触らない")
    void withoutTheUnlockNothingHappens() {
        when(dedicatedEffects.valueMax(any(), eq(EFFECT))).thenReturn(OptionalDouble.empty());
        when(gimmickConfig.xpBottleStoreAmount(1)).thenReturn(100);
        player.giveExp(500);
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.GLASS_BOTTLE), null);

        listener.onInteract(event);

        assertEquals(500, totalPlayerExp());
        assertEquals(Material.GLASS_BOTTLE, player.getInventory().getItemInMainHand().getType());
    }

    // ---- 格納がバニラの用途を奪わないこと ------------------------------------------------------

    @Test
    @DisplayName("視線上に水源があれば格納しない(水汲みを奪わない)")
    void waterInSightIsLeftToVanillaBottleFill() {
        when(gimmickConfig.xpBottleStoreAmount(1)).thenReturn(100);
        player.giveExp(500);
        Block water = mock(Block.class);
        when(water.getType()).thenReturn(Material.WATER);
        listener.waterTargetLookupForTest(p -> water);
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.GLASS_BOTTLE), null);

        listener.onInteract(event);

        assertEquals(500, totalPlayerExp(), "水汲みのクリックで経験値が吸われてはいけない");
        assertEquals(Material.GLASS_BOTTLE, player.getInventory().getItemInMainHand().getType());
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    @DisplayName("右クリックで開くブロック(チェスト)に向けたクリックでは格納しない")
    void interactableBlockIsLeftToVanilla() {
        when(gimmickConfig.xpBottleStoreAmount(1)).thenReturn(100);
        player.giveExp(500);
        Block chest = mock(Block.class);
        when(chest.getType()).thenReturn(Material.CHEST);
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.GLASS_BOTTLE), chest);

        listener.onInteract(event);

        assertEquals(500, totalPlayerExp(), "チェストを開くクリックで経験値が吸われてはいけない");
        verify(event, never()).setCancelled(anyBoolean());
    }

    @Test
    @DisplayName("何も起きないブロック(石)に向けたクリックなら格納する")
    void plainBlockClickStillStores() {
        when(gimmickConfig.xpBottleStoreAmount(1)).thenReturn(100);
        player.giveExp(500);
        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        PlayerInteractEvent event = interactEvent(new ItemStack(Material.GLASS_BOTTLE), stone);

        listener.onInteract(event);

        assertEquals(400, totalPlayerExp());
        assertEquals(Material.EXPERIENCE_BOTTLE, player.getInventory().getItemInMainHand().getType());
    }

    @Test
    @DisplayName("汲める対象は水源・水入り大釜・水没ブロック")
    void isWaterFillTargetCoversCauldronAndWaterloggedBlocks() {
        assertFalse(XpBottleListener.isWaterFillTarget(null));

        Block cauldron = mock(Block.class);
        when(cauldron.getType()).thenReturn(Material.WATER_CAULDRON);
        assertTrue(XpBottleListener.isWaterFillTarget(cauldron));

        Block emptyCauldron = mock(Block.class);
        when(emptyCauldron.getType()).thenReturn(Material.CAULDRON);
        assertFalse(XpBottleListener.isWaterFillTarget(emptyCauldron), "空の大釜からは汲めない");

        Waterlogged waterlogged = mock(Waterlogged.class);
        when(waterlogged.isWaterlogged()).thenReturn(true);
        Block stairs = mock(Block.class);
        when(stairs.getType()).thenReturn(Material.OAK_STAIRS);
        when(stairs.getBlockData()).thenReturn(waterlogged);
        assertTrue(XpBottleListener.isWaterFillTarget(stairs));

        Waterlogged dry = mock(Waterlogged.class);
        when(dry.isWaterlogged()).thenReturn(false);
        Block dryStairs = mock(Block.class);
        when(dryStairs.getType()).thenReturn(Material.OAK_STAIRS);
        when(dryStairs.getBlockData()).thenReturn(dry);
        assertFalse(XpBottleListener.isWaterFillTarget(dryStairs));
    }

    // ---- 取出 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("取り出すと return-rate ぶんだけ返り、手にはガラス瓶が戻る")
    void withdrawalAppliesTheConfiguredReturnRateAndReturnsAGlassBottle() {
        when(gimmickConfig.xpBottleReturnRate(1)).thenReturn(0.9);
        PlayerInteractEvent event = interactEvent(filledBottle(100), null);

        listener.onInteract(event);

        assertEquals(90, totalPlayerExp(), "floor(100 * 0.9) = 90 must be the amount actually returned");
        // 経験値瓶を返すと「ガラス瓶→経験値瓶」の無償変換路になるのでガラス瓶で返す。
        assertEquals(Material.GLASS_BOTTLE, player.getInventory().getItemInMainHand().getType());
        verify(event, times(1)).setCancelled(true);
    }

    @Test
    void withdrawalWithReturnRate1PreservesExistingBehavior() {
        when(gimmickConfig.xpBottleReturnRate(1)).thenReturn(1.0);
        PlayerInteractEvent event = interactEvent(filledBottle(100), null);

        listener.onInteract(event);

        assertEquals(100, totalPlayerExp(), "return-rate 1.0 must return the full stored amount");
    }

    @Test
    void withdrawalFlooresAFractionalReturnAmount() {
        when(gimmickConfig.xpBottleReturnRate(1)).thenReturn(0.33);
        PlayerInteractEvent event = interactEvent(filledBottle(10), null);

        listener.onInteract(event);

        // floor(10 * 0.33) = floor(3.3) = 3
        assertEquals(3, totalPlayerExp());
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private ItemStack filledBottle(int storedAmount) {
        ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = bottle.getItemMeta();
        meta.getPersistentDataContainer().set(PdcKeys.ITEM_XP_BOTTLE_AMOUNT, PersistentDataType.INTEGER, storedAmount);
        bottle.setItemMeta(meta);
        return bottle;
    }

    private static Integer storedAmountOf(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(PdcKeys.ITEM_XP_BOTTLE_AMOUNT, PersistentDataType.INTEGER);
    }

    /** {@code clicked} が null なら {@code RIGHT_CLICK_AIR}、それ以外は {@code RIGHT_CLICK_BLOCK}。 */
    private PlayerInteractEvent interactEvent(ItemStack held, Block clicked) {
        player.getInventory().setItemInMainHand(held);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(
                clicked == null ? Action.RIGHT_CLICK_AIR : Action.RIGHT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(clicked);
        return event;
    }

    private int totalPlayerExp() {
        return XpBottlePolicy.totalExperience(player.getLevel(), player.getExp());
    }
}
