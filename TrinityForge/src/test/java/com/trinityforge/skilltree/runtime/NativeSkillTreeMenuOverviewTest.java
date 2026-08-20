package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NativeSkillTreeMenu}: 一覧モード(2026-08-04新設)の回帰。
 *
 * <p>{@code NativePerkServicePowerPrestigeTest} と同じ流儀(実物の {@link NativeSkillCatalog}/
 * {@link SqliteProgressionRepository}(in-memory)/{@link NativeProgressionService}/
 * {@link NativePerkService} を組み、ツリー供給元だけを固定リストへ差し替える)で構築する
 * — モックGUIではなく実サービスを使うことで、{@code select-skill} の既存経路を通した
 * 「そのツリーの位置へ実際に移動する」までを検証できる。
 *
 * <p>モード判定は{@link Session}が非公開のため、通常モードだけが描画する移動矢印(スロット4)の
 * 有無で行う(一覧モードは移動矢印もツリー内容も描画しない)。
 */
class NativeSkillTreeMenuOverviewTest {

    private static final int NAV_SLOT_PROBE = 4; // "move-n" (通常モードのみ描画される)

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private SqliteProgressionRepository repository;
    private NativeSkillTreeMenu menu;
    private NamespacedKey actionKey;
    private NamespacedKey valueKey;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("TrinityForge");
        player = server.addPlayer();

        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        repository = new SqliteProgressionRepository("jdbc:sqlite::memory:");
        NativeProgressionService progression = new NativeProgressionService(repository, catalog);
        NativePerkService perks = new NativePerkService(progression,
                () -> List.of(tree(SkillId.MINING, "採掘"), tree(SkillId.WOODCUTTING, "伐採")));
        menu = new NativeSkillTreeMenu(plugin, progression, perks, ignored -> { });
        actionKey = new NamespacedKey(plugin, "skill_menu_action");
        valueKey = new NamespacedKey(plugin, "skill_menu_value");
    }

    @AfterEach
    void tearDown() throws Exception {
        repository.close();
        MockBukkit.unmock();
    }

    private static SkillTree tree(String skillId, String displayName) {
        SkillNode root = new SkillNode("A", "A", 0, SkillRole.MAIN, null, null, "STONE", 0,
                "", Map.of(), Map.of(), List.of(), List.of(), List.of());
        return new SkillTree(skillId, displayName, "STONE", "2,10", null, Map.of("A", root));
    }

    @Test
    void openStartsInDetailModeWithNavigationArrowsAndToggleButton() {
        menu.open(player);
        Inventory top = player.getOpenInventory().getTopInventory();

        assertNotNull(top.getItem(NAV_SLOT_PROBE), "detail mode must render the movement arrows");
        ItemStack toggle = top.getItem(SkillTreeOverviewLayout.TOGGLE_SLOT);
        assertNotNull(toggle);
        assertEquals("toggle-view", action(toggle));
        assertTrue(plain(toggle).contains("一覧表示"), "detail mode's toggle button must offer to switch to overview");
    }

    @Test
    void toggleSwitchesToOverviewListingEveryConfiguredTreeThenBackToDetailOnIconClick() {
        menu.open(player);
        Inventory top = player.getOpenInventory().getTopInventory();
        assertNotNull(top.getItem(NAV_SLOT_PROBE), "precondition: starts in detail mode");

        clickSlot(SkillTreeOverviewLayout.TOGGLE_SLOT);
        server.getScheduler().performOneTick();

        top = player.getOpenInventory().getTopInventory();
        assertNull(top.getItem(NAV_SLOT_PROBE), "overview mode must not render the detail-mode viewport/arrows");
        ItemStack toggleInOverview = top.getItem(SkillTreeOverviewLayout.TOGGLE_SLOT);
        assertTrue(plain(toggleInOverview).contains("通常表示"),
                "overview mode's toggle button must offer to switch back to detail");

        Map<String, Integer> slots = SkillTreeOverviewLayout.assign(List.of(SkillId.MINING, SkillId.WOODCUTTING));
        int miningSlot = slots.get(SkillId.MINING);
        int woodcuttingSlot = slots.get(SkillId.WOODCUTTING);

        ItemStack miningIcon = top.getItem(miningSlot);
        assertNotNull(miningIcon, "every configured tree must appear in the overview grid");
        assertEquals("select-skill", action(miningIcon));
        assertEquals(SkillId.MINING, value(miningIcon));
        assertTrue(plain(miningIcon).contains("解放済みノード"),
                "lore must expose unlocked-node count to help pick a destination");

        assertNotNull(top.getItem(woodcuttingSlot), "the second configured tree must also appear");
        assertEquals(SkillId.WOODCUTTING, value(top.getItem(woodcuttingSlot)));

        // アイコンをクリックするとそのツリーの位置へ移動して通常モードへ戻る(往復の確認)。
        clickSlot(miningSlot);
        server.getScheduler().performOneTick();

        Inventory afterReturn = player.getOpenInventory().getTopInventory();
        assertNotNull(afterReturn.getItem(NAV_SLOT_PROBE), "picking a tree must return to detail mode");
        assertTrue(plain(afterReturn.getItem(SkillTreeOverviewLayout.TOGGLE_SLOT)).contains("一覧表示"),
                "round trip: back in detail mode, the toggle button offers overview again");
    }

    @Test
    void everyConfiguredTreeIsAssignedAUniqueSlotWithNoOverlap() {
        Map<String, Integer> slots = SkillTreeOverviewLayout.assign(List.of(SkillId.MINING, SkillId.WOODCUTTING));
        assertEquals(2, slots.size());
        assertFalse(slots.get(SkillId.MINING).equals(slots.get(SkillId.WOODCUTTING)));
    }

    private void clickSlot(int slot) {
        InventoryClickEvent event = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        menu.onClick(event);
    }

    private String action(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private String value(ItemStack stack) {
        return stack.getItemMeta().getPersistentDataContainer().get(valueKey, PersistentDataType.STRING);
    }

    private static String plain(ItemStack stack) {
        StringBuilder text = new StringBuilder();
        var name = stack.getItemMeta().displayName();
        if (name != null) {
            text.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(name));
        }
        for (var line : stack.getItemMeta().lore() == null ? List.<net.kyori.adventure.text.Component>of()
                : stack.getItemMeta().lore()) {
            text.append(' ').append(
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(line));
        }
        return text.toString();
    }
}
