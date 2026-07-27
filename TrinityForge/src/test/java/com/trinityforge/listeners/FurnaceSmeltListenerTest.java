package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.SmithingGimmickConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FurnaceSmeltListener}: 所有者=精錬物(スロット0)を入れた本人のみ判定(横断制約:近くに居るだけの
 * 他人には乗らない)、連続精錬中は所有者を保持し続ける(2個目以降にもバフが乗る)、ホッパー自動投入は
 * autoへ上書きされ auto-mode-multiplier で減衰することを検証する。
 */
class FurnaceSmeltListenerTest {

    private static final String EFFECT_SPEED = "furnace-smelt-speed";
    private static final String EFFECT_BONUS = "furnace-smelt-bonus";

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private SmithingGimmickConfig gimmickConfig;
    private FurnaceSmeltListener listener;
    private PlayerMock owner;
    private PlayerMock bystander;
    private Block block;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(SmithingGimmickConfig.class);
        when(gimmickConfig.autoModeMultiplier()).thenReturn(0.25);
        listener = new FurnaceSmeltListener(MockBukkit.createMockPlugin(), dedicatedEffects, gimmickConfig);
        owner = server.addPlayer();
        bystander = server.addPlayer();
        block = owner.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.FURNACE);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Furnace furnace() {
        return (Furnace) block.getState();
    }

    private InventoryClickEvent manualInsertEvent(Furnace furnace, PlayerMock player, ItemStack item, int rawSlot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(top.getHolder()).thenReturn(furnace);
        when(view.getTopInventory()).thenReturn(top);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(rawSlot);
        when(event.getCurrentItem()).thenReturn(item);
        when(event.getAction()).thenReturn(InventoryAction.PLACE_ALL);
        return event;
    }

    private InventoryClickEvent manualInsertEvent(PlayerMock player, ItemStack item, int rawSlot) {
        return manualInsertEvent(furnace(), player, item, rawSlot);
    }

    private InventoryMoveItemEvent hopperInsertEvent(FurnaceInventory destination) {
        Inventory source = mock(Inventory.class);
        return new InventoryMoveItemEvent(source, new ItemStack(Material.IRON_ORE), destination, false);
    }

    @Test
    void manualInsertStampsInserterAsOwnerAndAppliesSpeedBonus() {
        listener.onInventoryClick(manualInsertEvent(owner, new ItemStack(Material.IRON_ORE), 0));

        // 2026-07-28: valueMax は tier番号(3)を返し、gimmickConfig.smeltSpeedPercent(tier) が実際の30%へ解決する。
        when(dedicatedEffects.valueMax(eq(owner), eq(EFFECT_SPEED))).thenReturn(OptionalDouble.of(3.0));
        when(gimmickConfig.smeltSpeedPercent(3)).thenReturn(30.0);
        FurnaceStartSmeltEvent event = new FurnaceStartSmeltEvent(block, new ItemStack(Material.IRON_ORE), null, 200);
        listener.onStartSmelt(event);

        assertEquals(140, event.getTotalCookTime(), "owner's 30% speed bonus must reduce 200 -> 140 ticks");
    }

    @Test
    void bystanderNearFurnaceNeverGetsCredit() {
        // owner inserts; a bystander is simply online/nearby but never clicked the furnace.
        listener.onInventoryClick(manualInsertEvent(owner, new ItemStack(Material.IRON_ORE), 0));
        when(dedicatedEffects.valueMax(eq(bystander), eq(EFFECT_SPEED))).thenReturn(OptionalDouble.of(30.0));
        when(dedicatedEffects.valueMax(eq(owner), eq(EFFECT_SPEED))).thenReturn(OptionalDouble.empty());

        FurnaceStartSmeltEvent event = new FurnaceStartSmeltEvent(block, new ItemStack(Material.IRON_ORE), null, 200);
        listener.onStartSmelt(event);

        assertEquals(200, event.getTotalCookTime(),
                "bystander's stat must never apply even though they hold the perk; only the recorded owner's does");
    }

    @Test
    void clickOnNonSmeltingSlotDoesNotStampOwner() {
        // slot 1 = fuel slot, not the smelting-input slot -> must not count as "inserted the smelting item".
        listener.onInventoryClick(manualInsertEvent(owner, new ItemStack(Material.COAL), 1));
        when(dedicatedEffects.valueMax(any(), eq(EFFECT_SPEED))).thenReturn(OptionalDouble.of(30.0));

        FurnaceStartSmeltEvent event = new FurnaceStartSmeltEvent(block, new ItemStack(Material.IRON_ORE), null, 200);
        listener.onStartSmelt(event);

        assertEquals(200, event.getTotalCookTime(), "fuel-slot insert must not attribute ownership");
    }

    @Test
    void extraDropBonusRollsWithOwnersStat() {
        listener.onInventoryClick(manualInsertEvent(owner, new ItemStack(Material.IRON_ORE), 0));
        // 2026-07-28: tier番号を返し、smeltBonusPercent(tier)側で>100%(保証抽選)を解決する。
        when(dedicatedEffects.valueMax(eq(owner), eq(EFFECT_BONUS))).thenReturn(OptionalDouble.of(3.0));
        when(gimmickConfig.smeltBonusPercent(anyInt())).thenReturn(1000.0); // >100% => guaranteed extra

        int before = block.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        FurnaceSmeltEvent event = new FurnaceSmeltEvent(block, new ItemStack(Material.IRON_ORE), new ItemStack(Material.IRON_INGOT));
        listener.onSmelt(event);
        int after = block.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        assertEquals(true, after > before, "a guaranteed (>100%) bonus chance must drop at least one extra ingot");
    }

    // NOTE: MockBukkit's FurnaceInventoryMock does not round-trip setSmelting() contents back through
    // block.getState() in this MockBukkit version, so the "still smelting / now idle" decision is
    // exercised directly against FurnaceSmeltListener#clearIfIdle(Furnace) (package-private, exposed
    // exactly for this) with a Mockito-mocked Furnace/FurnaceInventory/PersistentDataContainer instead
    // of round-tripping through a real block. isInputExhausted() itself is covered standalone below too.

    @Test
    void isInputExhaustedTreatsNullOrEmptyAsExhausted() {
        assertEquals(true, FurnaceSmeltListener.isInputExhausted(null));
        assertEquals(true, FurnaceSmeltListener.isInputExhausted(new ItemStack(Material.AIR)));
        ItemStack zeroAmount = mock(ItemStack.class);
        when(zeroAmount.getType()).thenReturn(Material.IRON_ORE);
        when(zeroAmount.getAmount()).thenReturn(0);
        assertEquals(true, FurnaceSmeltListener.isInputExhausted(zeroAmount));
    }

    @Test
    void isInputExhaustedFalseWhenStackRemains() {
        assertEquals(false, FurnaceSmeltListener.isInputExhausted(new ItemStack(Material.IRON_ORE, 2)));
    }

    @Test
    void clearIfIdleDoesNotClearOwnerWhenInputStillRemains() {
        Furnace mockFurnace = mock(Furnace.class);
        FurnaceInventory inv = mock(FurnaceInventory.class);
        org.bukkit.persistence.PersistentDataContainer pdc =
                mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(mockFurnace.getInventory()).thenReturn(inv);
        when(mockFurnace.getPersistentDataContainer()).thenReturn(pdc);
        when(inv.getSmelting()).thenReturn(new ItemStack(Material.IRON_ORE, 2)); // 2 left: still smelting.

        listener.clearIfIdle(mockFurnace);

        org.mockito.Mockito.verify(pdc, org.mockito.Mockito.never()).remove(any());
        org.mockito.Mockito.verify(mockFurnace, org.mockito.Mockito.never()).update();
    }

    @Test
    void clearIfIdleClearsOwnerWhenInputIsEmpty() {
        Furnace mockFurnace = mock(Furnace.class);
        FurnaceInventory inv = mock(FurnaceInventory.class);
        org.bukkit.persistence.PersistentDataContainer pdc =
                mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(mockFurnace.getInventory()).thenReturn(inv);
        when(mockFurnace.getPersistentDataContainer()).thenReturn(pdc);
        when(inv.getSmelting()).thenReturn(null); // nothing left: processing complete.

        listener.clearIfIdle(mockFurnace);

        org.mockito.Mockito.verify(pdc, org.mockito.Mockito.times(2)).remove(any());
        org.mockito.Mockito.verify(mockFurnace, org.mockito.Mockito.times(1)).update();
    }

    @Test
    void hopperInsertMarksAutoAndDecaysSpeedBonus() {
        listener.onInventoryClick(manualInsertEvent(owner, new ItemStack(Material.IRON_ORE), 0));
        FurnaceInventory inv = furnace().getInventory();
        listener.onInventoryMoveItem(hopperInsertEvent(inv));

        when(dedicatedEffects.valueMax(eq(owner), eq(EFFECT_SPEED))).thenReturn(OptionalDouble.of(4.0));
        when(gimmickConfig.smeltSpeedPercent(anyInt())).thenReturn(40.0);
        FurnaceStartSmeltEvent event = new FurnaceStartSmeltEvent(block, new ItemStack(Material.IRON_ORE), null, 200);
        listener.onStartSmelt(event);

        // 40% * 0.25 auto-multiplier = 10% effective reduction -> 200 * 0.9 = 180.
        assertEquals(180, event.getTotalCookTime(), "hopper-fed (auto) mode must decay the bonus by autoModeMultiplier");
    }
}
