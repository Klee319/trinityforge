package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.fishing.XpBottlePolicy;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link XpBottleListener#handleWithdraw}: {@code xp-bottle-store.return-rate}(§取り出し時の目減り)が
 * 実際に付与される経験値量に反映されること。格納({@code handleStore})側は据え置きの既存動作。
 */
class XpBottleListenerTest {

    private static final String EFFECT = "xp-bottle-store-unlock";

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private FishingGimmickConfig gimmickConfig;
    private XpBottleListener listener;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(FishingGimmickConfig.class);
        listener = new XpBottleListener(dedicatedEffects, gimmickConfig);
        player = server.addPlayer();
        // 2026-07-26 tier-expand: xp-bottle-store-unlock は SCALE化(feature:<id> valueMax でゲート)。
        // 既存の単一解放ノードに value: が無ければ tier1 が自動補完される想定を再現する。
        when(dedicatedEffects.valueMax(any(), eq(EFFECT))).thenReturn(OptionalDouble.of(1.0));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack filledBottle(int storedAmount) {
        ItemStack bottle = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = bottle.getItemMeta();
        meta.getPersistentDataContainer().set(PdcKeys.ITEM_XP_BOTTLE_AMOUNT, PersistentDataType.INTEGER, storedAmount);
        bottle.setItemMeta(meta);
        return bottle;
    }

    private PlayerInteractEvent withdrawEvent(ItemStack bottle) {
        player.getInventory().setItemInMainHand(bottle);
        player.setSneaking(false);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        return event;
    }

    private int totalPlayerExp() {
        return XpBottlePolicy.totalExperience(player.getLevel(), player.getExp());
    }

    @Test
    void withdrawalAppliesTheConfiguredReturnRate() {
        when(gimmickConfig.xpBottleReturnRate(1)).thenReturn(0.9);
        PlayerInteractEvent event = withdrawEvent(filledBottle(100));

        listener.onInteract(event);

        assertEquals(90, totalPlayerExp(), "floor(100 * 0.9) = 90 must be the amount actually returned");
        verify(event, times(1)).setCancelled(true);
    }

    @Test
    void withdrawalWithReturnRate1PreservesExistingBehavior() {
        when(gimmickConfig.xpBottleReturnRate(1)).thenReturn(1.0);
        PlayerInteractEvent event = withdrawEvent(filledBottle(100));

        listener.onInteract(event);

        assertEquals(100, totalPlayerExp(), "return-rate 1.0 must return the full stored amount");
    }

    @Test
    void withdrawalFlooresAFractionalReturnAmount() {
        when(gimmickConfig.xpBottleReturnRate(1)).thenReturn(0.33);
        PlayerInteractEvent event = withdrawEvent(filledBottle(10));

        listener.onInteract(event);

        // floor(10 * 0.33) = floor(3.3) = 3
        assertEquals(3, totalPlayerExp());
    }
}
