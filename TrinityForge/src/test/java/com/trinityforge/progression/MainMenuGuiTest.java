package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.progression.achievement.AchievementGui;
import com.trinityforge.skilltree.runtime.NativeSkillTreeMenu;
import com.trinityforge.stats.status.StatusGui;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MainMenuGui}: 権限/機能フラグに応じた項目の可否表示と、クリックが対応する既存GUIの
 * {@code open(Player)}を呼ぶことの回帰(2026-08-04新設)。
 *
 * <p>{@code SettingsGuiGatherTogglesTest} と同じ作法(MockBukkit + {@code gui.onClick(event)} を
 * 直接呼ぶ)で検証する。各既存GUIはコンストラクタを呼ばずにMockitoでモック化するため、
 * このテストは「MainMenuGuiが正しいGUIへ委譲するか」だけを見る(各GUI自体のレンダリングは
 * それぞれのテストの責務)。
 */
class MainMenuGuiTest {

    private ServerMock server;
    private PlayerMock player;
    private RoleSelectGui roleSelectGui;
    private RoleChangeService roleChangeService;
    private StatusGui statusGui;
    private NativeSkillTreeMenu skillTreeMenu;
    private AchievementGui achievementGui;
    private CollectionGui collectionGui;
    private CollectionService collectionService;
    private CollectionConfig collectionConfig;
    private SettingsGui settingsGui;
    private MainMenuGui gui;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        roleSelectGui = mock(RoleSelectGui.class);
        roleChangeService = mock(RoleChangeService.class);
        when(roleChangeService.commandDisabledReason(any())).thenReturn(Optional.empty());
        statusGui = mock(StatusGui.class);
        skillTreeMenu = mock(NativeSkillTreeMenu.class);
        achievementGui = mock(AchievementGui.class);
        collectionGui = mock(CollectionGui.class);
        collectionService = mock(CollectionService.class);
        collectionConfig = mock(CollectionConfig.class);
        when(collectionConfig.enabled()).thenReturn(true);
        settingsGui = mock(SettingsGui.class);
        gui = new MainMenuGui(MockBukkit.createMockPlugin(), roleSelectGui, roleChangeService, statusGui,
                skillTreeMenu, achievementGui, collectionGui, collectionService, collectionConfig,
                settingsGui);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void openRendersAllSixItemsAsAvailableByDefault() {
        gui.open(player);
        Inventory open = player.getOpenInventory().getTopInventory();

        for (MainMenuLayout.Item item : MainMenuLayout.items()) {
            ItemStack stack = open.getItem(item.slot());
            assertNotNull(stack, "slot " + item.slot() + " must have an icon");
            assertNotEquals(Material.BARRIER, stack.getType(),
                    item.id() + " should render as available by default");
        }
    }

    @Test
    void roleDisabledByConfigRendersBarrierAndBlocksTheClick() {
        when(roleChangeService.commandDisabledReason(any()))
                .thenReturn(Optional.of("コマンドによるロール変更は無効です。"));
        gui.open(player);
        int roleSlot = MainMenuLayout.itemForId(MainMenuLayout.ROLE).orElseThrow().slot();
        Inventory open = player.getOpenInventory().getTopInventory();
        assertEquals(Material.BARRIER, open.getItem(roleSlot).getType());

        clickSlot(roleSlot);

        verify(roleSelectGui, never()).open(any());
    }

    @Test
    void collectionDisabledByConfigRendersBarrierAndBlocksTheClick() {
        when(collectionConfig.enabled()).thenReturn(false);
        gui.open(player);
        int slot = MainMenuLayout.itemForId(MainMenuLayout.COLLECTION).orElseThrow().slot();
        Inventory open = player.getOpenInventory().getTopInventory();
        assertEquals(Material.BARRIER, open.getItem(slot).getType());

        clickSlot(slot);

        verify(collectionGui, never()).open(any());
        verify(collectionService, never()).grantPendingTiers(any());
    }

    @Test
    void clickingEachAvailableSlotOpensTheCorrespondingExistingGui() {
        gui.open(player);

        clickSlot(MainMenuLayout.itemForId(MainMenuLayout.ROLE).orElseThrow().slot());
        verify(roleSelectGui).open(player);

        clickSlot(MainMenuLayout.itemForId(MainMenuLayout.STATUS).orElseThrow().slot());
        verify(statusGui).open(player);

        clickSlot(MainMenuLayout.itemForId(MainMenuLayout.SKILLS).orElseThrow().slot());
        verify(skillTreeMenu).open(player);

        clickSlot(MainMenuLayout.itemForId(MainMenuLayout.ACHIEVEMENT).orElseThrow().slot());
        verify(achievementGui).open(player);

        clickSlot(MainMenuLayout.itemForId(MainMenuLayout.COLLECTION).orElseThrow().slot());
        verify(collectionService).grantPendingTiers(player);
        verify(collectionGui).open(player);

        clickSlot(MainMenuLayout.itemForId(MainMenuLayout.SETTINGS).orElseThrow().slot());
        verify(settingsGui).open(player);
    }

    private void clickSlot(int slot) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        gui.onClick(event);
    }
}
