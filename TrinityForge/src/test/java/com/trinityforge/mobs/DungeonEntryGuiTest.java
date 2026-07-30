package com.trinityforge.mobs;

import com.trinityforge.combat.SymmetricCombatService;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DungeonEntryGuiTest {

    private ServerMock server;
    private DungeonEntryGui gui;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        gui = new DungeonEntryGui(
                MockBukkit.createMockPlugin(),
                mock(DungeonGateService.class),
                mock(SymmetricCombatService.class));
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void nextPageMakesSixteenthCandidateReachable() {
        List<DungeonGate> candidates = candidates(16);

        gui.openSelection(player, candidates);

        assertFalse(hasDisplayName(player.getOpenInventory().getTopInventory(), "dungeon_16"));
        InventoryClickEvent nextPageClick = click(8);

        gui.onClick(nextPageClick);

        assertTrue(nextPageClick.isCancelled());
        assertTrue(hasDisplayName(player.getOpenInventory().getTopInventory(), "dungeon_16"),
                "16件目もページ送り後に選択肢として表示されること");
    }

    @Test
    void navigationCanMoveAcrossAllPagesAndBack() {
        gui.openSelection(player, candidates(31));

        gui.onClick(click(8));
        assertTrue(hasDisplayName(player.getOpenInventory().getTopInventory(), "dungeon_16"));

        gui.onClick(click(8));
        assertTrue(hasDisplayName(player.getOpenInventory().getTopInventory(), "dungeon_31"));

        gui.onClick(click(0));
        assertTrue(hasDisplayName(player.getOpenInventory().getTopInventory(), "dungeon_16"));
    }

    @Test
    void candidateOnLaterPageCanBeSelected() {
        gui.openSelection(player, candidates(16));
        gui.onClick(click(8));

        gui.onClick(click(10));

        Inventory confirmInventory = player.getOpenInventory().getTopInventory();
        assertTrue(hasDisplayName(confirmInventory, "dungeon_16"),
                "2ページ目の候補を選択して潜入確認画面へ進めること");
    }

    @Test
    void candidatesSharingWorldAreSelectedByTheirOwnIndex() {
        DungeonGate first = new DungeonGate("shared_world", 10, null, 1);
        DungeonGate second = new DungeonGate("shared_world", 99, null, 1);
        gui.openSelection(player, List.of(first, second));

        gui.onClick(click(11));

        ItemStack info = player.getOpenInventory().getTopInventory().getItem(13);
        assertTrue(hasLoreLine(info, "必要戦闘Lv: 99 / 現在の戦闘Lv: 0"),
                "同じworld名でもクリックした2件目のゲートを確認画面へ渡すこと");
    }

    private InventoryClickEvent click(int slot) {
        return new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    private static List<DungeonGate> candidates(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> new DungeonGate("dungeon_" + index, 0, null, 1))
                .toList();
    }

    private static boolean hasDisplayName(Inventory inventory, String expected) {
        for (ItemStack item : inventory.getContents()) {
            if (item == null || !item.hasItemMeta() || item.getItemMeta().displayName() == null) {
                continue;
            }
            String actual = PlainTextComponentSerializer.plainText()
                    .serialize(item.getItemMeta().displayName());
            if (expected.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLoreLine(ItemStack item, String expected) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().lore() == null) {
            return false;
        }
        return item.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(expected::equals);
    }
}
