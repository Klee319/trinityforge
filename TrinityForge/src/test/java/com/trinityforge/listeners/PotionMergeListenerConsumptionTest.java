package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GTH-03 exploit fix: {@link PotionMergeListener#onBrewingClick} must fully consume both source
 * potions (not just leave a live-reference amount mutation that a caller could fail to observe) —
 * explicitly re-assigning the result slot and cursor via {@code setCurrentItem}/{@code setItemOnCursor},
 * nulling out at amount 0 rather than leaving a "ghost" zero-amount {@link ItemStack} behind. Mirrors
 * the established consumption pattern in {@code WoodRepairListener#onInventoryClick}.
 *
 * <p><b>2026-08-25 書き直し(W-115)</b>: 旧版は {@code InventoryAction.PLACE_ALL} をスタブしていたが、
 * ポーションはスタック上限1なので「カーソルにもスロットにも既にポーションがある」状態でこの
 * アクションは実クライアントからは絶対に来ない({@code PLACE_*} は対象スロットが空/同一種未満枠が
 * 前提)。この組み合わせで実際に飛ぶのは {@link InventoryAction#SWAP_WITH_CURSOR}
 * ({@code BrewInsertion#insertedStack} が同じ判定を採用済み)。また統合可能なのは
 * 「同一効果のカスタムポーション同士」だけ(ユーザー決定)なので、両方 {@code SPEED} に揃えた。
 */
class PotionMergeListenerConsumptionTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private CraftingFeaturesConfig features;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        // 2026-07-26 tier-expand: potion-merge は SCALE化(feature:<id> valueMax でゲート)。
        // 既存の単一解放ノードに value: が無ければ tier1 が自動補完される想定を再現する。
        when(dedicatedEffects.valueMax(org.mockito.ArgumentMatchers.any(), eq("potion-merge")))
                .thenReturn(OptionalDouble.of(1.0));
        features = new CraftingFeaturesConfig(); // real: defaults max-effects=5, max-duration-seconds=960
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PotionMergeListener listener() {
        return new PotionMergeListener(dedicatedEffects, features);
    }

    private static ItemStack potionWith(PotionEffectType type, int amplifier, int durationTicks) {
        ItemStack stack = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) stack.getItemMeta();
        meta.addCustomEffect(new PotionEffect(type, durationTicks, amplifier), true);
        stack.setItemMeta(meta);
        return stack;
    }

    private InventoryClickEvent brewingClickEvent(ItemStack slotItem, ItemStack cursorItem) {
        BrewerInventory inventory = mock(BrewerInventory.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getAction()).thenReturn(InventoryAction.SWAP_WITH_CURSOR);
        when(event.getCursor()).thenReturn(cursorItem);
        when(event.getCurrentItem()).thenReturn(slotItem);
        return event;
    }

    @Test
    void mergingTwoStackOfOnePotionsFullyConsumesBothSources() {
        ItemStack slot = potionWith(PotionEffectType.SPEED, 0, 200);
        ItemStack cursor = potionWith(PotionEffectType.SPEED, 1, 200);
        InventoryClickEvent event = brewingClickEvent(slot, cursor);

        listener().onBrewingClick(event);

        // Both sources stack at 1, so a full consume must null out BOTH the result slot and the cursor
        // (not leave a zero-amount "ghost" stack, and not leave the original stack untouched).
        verify(event).setCurrentItem(null);
        ItemStack remainingCursor = player.getItemOnCursor();
        assertTrue(remainingCursor == null || remainingCursor.getType().isAir(),
                "cursor must be cleared, not left as a live 1-amount potion");
    }

    @Test
    void mergingDoesNotDoubleGrantWhenSourceStackHasMoreThanOne() {
        ItemStack slot = potionWith(PotionEffectType.SPEED, 0, 200);
        slot.setAmount(2);
        ItemStack cursor = potionWith(PotionEffectType.SPEED, 1, 200);
        InventoryClickEvent event = brewingClickEvent(slot, cursor);

        listener().onBrewingClick(event);

        // Slot had 2: after consuming exactly one, exactly 1 must remain (never the original 2 = dupe).
        org.mockito.ArgumentCaptor<ItemStack> captor = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
        verify(event).setCurrentItem(captor.capture());
        assertEquals(1, captor.getValue().getAmount());
    }

    @Test
    void mergeAlwaysCancelsTheEventSoVanillaNeverAlsoMovesTheStack() {
        ItemStack slot = potionWith(PotionEffectType.SPEED, 0, 200);
        ItemStack cursor = potionWith(PotionEffectType.SPEED, 1, 200);
        InventoryClickEvent event = brewingClickEvent(slot, cursor);

        listener().onBrewingClick(event);

        verify(event).setCancelled(true);
    }
}
