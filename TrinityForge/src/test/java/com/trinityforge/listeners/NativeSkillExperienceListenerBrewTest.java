package com.trinityforge.listeners;

import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NativeSkillExperienceListener#onBrew} after the {@link BrewOwnership} extraction: same
 * behaviour as before the refactor (manual/automated multiplier, no grant without a recorded owner,
 * and the owner PDC is always cleared afterward win-or-lose).
 */
class NativeSkillExperienceListenerBrewTest {

    private static final SkillCatalogEntry ALCHEMY_ENTRY = new SkillCatalogEntry(
            "ALCHEMY", 100, "1", level -> 1L,
            Map.of("brew_ingredient.REDSTONE", 100.0),
            Map.of("alchemy.brew", 25.0, "alchemy.manual_mult", 2.0, "alchemy.auto_mult", 0.25));

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private BrewingStand stand;
    private BrewOwnership ownership;
    private NativeExperienceDispatcher dispatcher;
    private NativeSkillExperienceListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.BREWING_STAND);
        stand = (BrewingStand) block.getState();
        ownership = new BrewOwnership(plugin);

        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.ALCHEMY)).thenReturn(ALCHEMY_ENTRY);
        PlacedBlockTracker tracker = mock(PlacedBlockTracker.class);
        dispatcher = mock(NativeExperienceDispatcher.class);
        listener = new NativeSkillExperienceListener(plugin, dispatcher, catalog, tracker);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private BrewEvent brewEvent() {
        BrewerInventory inv = stand.getInventory();
        List<ItemStack> results = new ArrayList<>();
        return new BrewEvent(stand.getBlock(), inv, results, 20);
    }

    /** 醸造台の現在の状態を取り直す(消去は次tickなので、古いスナップショットで見ない)。 */
    private BrewingStand currentStand() {
        return (BrewingStand) stand.getBlock().getState();
    }

    private void markOwned(String mode) {
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, player.getUniqueId().toString());
        stand.getPersistentDataContainer().set(ownership.brewModeKey(), PersistentDataType.STRING, mode);
        stand.update();
    }

    @Test
    void manualOwnerGrantsWithManualMultiplier() {
        markOwned(BrewOwnership.MODE_MANUAL);

        listener.onBrew(brewEvent());

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ALCHEMY, 50.0); // 25 * 2.0
    }

    @Test
    void automatedOwnerGrantsWithAutoMultiplier() {
        markOwned(BrewOwnership.MODE_AUTO);

        listener.onBrew(brewEvent());

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ALCHEMY, 6.25); // 25 * 0.25
    }

    @Test
    void noOwnerGrantsNothing() {
        listener.onBrew(brewEvent());

        verify(dispatcher, never()).grant(any(), any(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void ownerPdcIsAlwaysClearedAfterward() {
        markOwned(BrewOwnership.MODE_MANUAL);

        listener.onBrew(brewEvent());
        server.getScheduler().performOneTick();

        org.junit.jupiter.api.Assertions.assertTrue(ownership.ownerOf(currentStand()).isEmpty());
        org.junit.jupiter.api.Assertions.assertFalse(ownership.isAutomated(currentStand()));
    }

    /**
     * <b>W-112 / W-124 の回帰ガード</b>: 所有者PDCの消去を {@link BrewEvent} の<b>最中</b>に
     * やってはいけない。消去は {@code stand.update()} を伴い、
     * {@code CraftBlockEntityState#update()} はスナップショットのNBTを丸ごと実体へ load するので、
     * イベント後に走る Paper の {@code doBrew}（{@code items.set(...)} と
     * {@code ingredient.shrink(1)}）が<b>醸造台から切り離された孤児</b>へ書くことになり、
     * 素材が減らず瓶も変換されない（＝醸造が一切完成しない）。
     *
     * <p>MockBukkit の {@code update()} は実体へ書き戻さないため、この巻き戻し自体は再現できない。
     * そこで「イベント中に消去していないこと」＝<b>消去が次tickへ回っていること</b>を固定する。
     * この判定を落とすには消去をイベント中へ戻すしかないので、真因の再発を確実に捕まえられる。
     */
    @Test
    void ownerPdcIsNotClearedDuringTheBrewEventItself() {
        markOwned(BrewOwnership.MODE_MANUAL);

        listener.onBrew(brewEvent());

        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Optional.of(player.getUniqueId()), ownership.ownerOf(currentStand()),
                "BrewEvent の最中にブロック実体へ書き戻すと doBrew の書き込み先が孤児になる");

        server.getScheduler().performOneTick();

        org.junit.jupiter.api.Assertions.assertTrue(ownership.ownerOf(currentStand()).isEmpty(),
                "次tickでは消えていること(手動レートが後続の醸造へ持ち越されないため)");
    }

    /** 醸造台が壊されていても次tickの消去が落ちない。 */
    @Test
    void deferredClearSurvivesTheStandBeingBroken() {
        markOwned(BrewOwnership.MODE_MANUAL);

        listener.onBrew(brewEvent());
        stand.getBlock().setType(Material.AIR);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> server.getScheduler().performOneTick());
    }

    @Test
    void configuredIngredientStageOverridesFlatFallback() {
        markOwned(BrewOwnership.MODE_MANUAL);
        BrewerInventory contents = mock(BrewerInventory.class);
        ItemStack ingredient = mock(ItemStack.class);
        when(ingredient.getType()).thenReturn(Material.REDSTONE);
        when(contents.getIngredient()).thenReturn(ingredient);
        BrewEvent event = mock(BrewEvent.class);
        when(event.getBlock()).thenReturn(stand.getBlock());
        when(event.getContents()).thenReturn(contents);
        when(event.getResults()).thenReturn(List.of());

        listener.onBrew(event);

        verify(dispatcher).grant(player.getUniqueId(), SkillId.ALCHEMY, 200.0); // 100 * manual 2.0
        server.getScheduler().performOneTick();
        org.junit.jupiter.api.Assertions.assertTrue(ownership.ownerOf(currentStand()).isEmpty());
    }

    @Test
    void placingCursorIntoEmptyBrewingSlotRemembersBrewer() {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(top.getHolder()).thenReturn(stand);
        when(top.getSize()).thenReturn(5);
        when(view.getTopInventory()).thenReturn(top);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(3);
        when(event.getCurrentItem()).thenReturn(null);
        when(event.getCursor()).thenReturn(new ItemStack(Material.REDSTONE));
        when(event.getAction()).thenReturn(InventoryAction.PLACE_ALL);

        listener.rememberBrewer(event);

        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.Optional.of(player.getUniqueId()), ownership.ownerOf(stand));
        org.junit.jupiter.api.Assertions.assertFalse(ownership.isAutomated(stand));
    }
}
