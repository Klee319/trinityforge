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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.OptionalDouble;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PotionMergeListener#onBrewingClick} の発火条件そのものを検証する(W-115)。
 *
 * <h2>真因</h2>
 * ポーションはスタック上限1なので、「カーソルにもクリック先のスロットにも既にポーションが
 * 入っている」状態は、Bukkitでは常に {@link InventoryAction#SWAP_WITH_CURSOR} でしか起こらない
 * ({@code PLACE_*} はスロットが空、または同一種で上限未満のときにしか成立しない —
 * バニラのポーションは重ならないので後者も起こらない)。旧実装は {@code PLACE_*} だけを許可条件に
 * 入れており、この2条件(「PLACE_*」と「両方ポーションが埋まっている」)が同時に成立することが
 * 無かったため、このリスナーは一度も発火しなかった。
 *
 * <p>このテストは、真因どおり {@code SWAP_WITH_CURSOR} なら発火し、旧条件が想定していた
 * {@code PLACE_ALL}(スロットが空という実クライアントの前提)では素材が無いので不発になることを
 * 両方固定する。
 */
class PotionMergeListenerFireConditionTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private CraftingFeaturesConfig features;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.valueMax(org.mockito.ArgumentMatchers.any(), eq("potion-merge")))
                .thenReturn(OptionalDouble.of(1.0));
        features = new CraftingFeaturesConfig();
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

    private InventoryClickEvent event(InventoryAction action, ItemStack slotItem, ItemStack cursorItem) {
        BrewerInventory inventory = mock(BrewerInventory.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getAction()).thenReturn(action);
        when(event.getCursor()).thenReturn(cursorItem);
        when(event.getCurrentItem()).thenReturn(slotItem);
        return event;
    }

    @Test
    @DisplayName("実クライアントが生成する組(両方ポーション入り = SWAP_WITH_CURSOR)なら発火する")
    void firesOnSwapWithCursorWhenBothSlotsAlreadyHoldAPotion() {
        ItemStack slot = potionWith(PotionEffectType.SPEED, 0, 200);
        ItemStack cursor = potionWith(PotionEffectType.SPEED, 1, 200);
        InventoryClickEvent event = event(InventoryAction.SWAP_WITH_CURSOR, slot, cursor);

        listener().onBrewingClick(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("旧実装が前提にしていたPLACE_ALLは、スロットが空という実クライアントの前提と噛み合わず不発")
    void placeAllWithEmptySlotNeverMerges() {
        // PLACE_ALL が実際に飛ぶのは対象スロットが空のときなので、currentItem は null になる。
        ItemStack cursor = potionWith(PotionEffectType.SPEED, 0, 200);
        InventoryClickEvent event = event(InventoryAction.PLACE_ALL, null, cursor);

        listener().onBrewingClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    @DisplayName("効果の種類が異なる同士は統合不可(ユーザー決定: 同一効果のカスタムポーション同士だけ)")
    void differentEffectTypesDoNotMerge() {
        ItemStack slot = potionWith(PotionEffectType.SPEED, 0, 200);
        ItemStack cursor = potionWith(PotionEffectType.STRENGTH, 0, 200);
        InventoryClickEvent event = event(InventoryAction.SWAP_WITH_CURSOR, slot, cursor);

        listener().onBrewingClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    @DisplayName("効果を持たないポーション同士は統合不可(ユーザー決定)")
    void potionsWithoutEffectsDoNotMerge() {
        // slot/cursorをequals()で早期returnさせないため、材質を分ける(素材の中身は両方effectなし)。
        ItemStack slot = new ItemStack(Material.POTION);
        ItemStack cursor = new ItemStack(Material.SPLASH_POTION);
        InventoryClickEvent event = event(InventoryAction.SWAP_WITH_CURSOR, slot, cursor);

        listener().onBrewingClick(event);

        verify(event, never()).setCancelled(true);
    }
}
