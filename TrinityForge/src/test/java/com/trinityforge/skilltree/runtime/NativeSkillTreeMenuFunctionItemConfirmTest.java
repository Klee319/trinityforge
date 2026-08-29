package com.trinityforge.skilltree.runtime;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 楔／再構築の書はメインハンド所持＋ノードクリックで確認GUIを開く（W-276）。
 * プレステージと同じく、押したマスに「はい」を置かない。
 */
class NativeSkillTreeMenuFunctionItemConfirmTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private SqliteProgressionRepository repository;
    private NativeSkillTreeMenu menu;
    private NamespacedKey actionKey;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("TrinityForge");
        player = server.addPlayer();

        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        repository = new SqliteProgressionRepository("jdbc:sqlite::memory:");
        NativeProgressionService progression = new NativeProgressionService(repository, catalog);
        NativePerkService perks = new NativePerkService(progression, () -> List.of(tree()));
        progression.grantExp(player.getUniqueId(), SkillId.MINING, 1.0);
        repository.savePointBalance(player.getUniqueId(), 10L, 0L);
        repository.unlockPerk(player.getUniqueId(), "mining_perk_a", 2L);
        menu = new NativeSkillTreeMenu(plugin, progression, perks, ignored -> { });
        actionKey = new NamespacedKey(plugin, "skill_menu_action");
    }

    @AfterEach
    void tearDown() throws Exception {
        repository.close();
        MockBukkit.unmock();
    }

    private static SkillTree tree() {
        SkillNode root = new SkillNode("A", "A", 0, SkillRole.MAIN, null, null, "STONE", 0,
                "", Map.of(), Map.of(), List.of(), List.of(), List.of());
        Prestige prestige = new Prestige(true, 0, "採掘王の栄光", "", Map.of(), Map.of(), 1);
        return new SkillTree(SkillId.MINING, "採掘", "STONE", "2,10", prestige, Map.of("A", root));
    }

    @Test
    @DisplayName("楔を持ってノードをクリックしてもロックは走らず、確認が出る")
    void lockItemOpensConfirmInsteadOfLocking() {
        giveFunctionItem(SkillTreeItems.NODE_LOCK, Material.AMETHYST_SHARD);
        menu.open(player);
        int slot = nodeSlot();
        player.nextMessage();
        clickSlot(slot);
        server.getScheduler().performOneTick();

        assertFalse(PlayerData.of(player).lockedPerks().contains("mining_perk_a"),
                "1クリック目でロックが確定してはいけない");
        assertTrue(drainMessages().contains("確認画面"),
                "確認を開いたことがチャットに出ること");
        assertTrue(slotWithAction("function-confirm") >= 0, "確認画面の「はい」が無い");
        assertTrue(NativeSkillTreeMenu.prestigeYesSlot(slot) != slot);
        clickSlot(slot);
        server.getScheduler().performOneTick();
        assertFalse(PlayerData.of(player).lockedPerks().contains("mining_perk_a"),
                "押したマスの2打目で確定してはいけない");
    }

    @Test
    @DisplayName("確認画面の「はい」でロックし、楔を1個消費する")
    void confirmingLockConsumesTheWedge() {
        giveFunctionItem(SkillTreeItems.NODE_LOCK, Material.AMETHYST_SHARD);
        menu.open(player);
        clickSlot(nodeSlot());
        server.getScheduler().performOneTick();
        clickSlot(slotWithAction("function-confirm"));
        server.getScheduler().performOneTick();

        assertTrue(PlayerData.of(player).lockedPerks().contains("mining_perk_a"));
        ItemStack held = player.getInventory().getItemInMainHand();
        assertTrue(held == null || held.getType().isAir() || held.getAmount() == 0,
                "ロック確定で楔を消費すること");
    }

    @Test
    @DisplayName("再構築の書を持ってノードをクリックしてもリセットは走らず、確認が出る")
    void resetItemOpensConfirmInsteadOfResetting() {
        giveFunctionItem(SkillTreeItems.TREE_RESET, Material.ECHO_SHARD);
        menu.open(player);
        int slot = nodeSlot();
        player.nextMessage();
        clickSlot(slot);
        server.getScheduler().performOneTick();

        assertTrue(repository.loadPerkIds(player.getUniqueId()).orElseThrow()
                .contains("mining_perk_a"), "1クリック目でリセットが確定してはいけない");
        assertTrue(drainMessages().contains("確認画面"));
        assertTrue(slotWithAction("function-confirm") >= 0);
        clickSlot(slot);
        server.getScheduler().performOneTick();
        assertTrue(repository.loadPerkIds(player.getUniqueId()).orElseThrow()
                .contains("mining_perk_a"), "押したマスの2打目で確定してはいけない");
    }

    @Test
    @DisplayName("確認画面の「はい」でツリーをリセットする")
    void confirmingResetClearsTheTree() {
        giveFunctionItem(SkillTreeItems.TREE_RESET, Material.ECHO_SHARD);
        menu.open(player);
        clickSlot(nodeSlot());
        server.getScheduler().performOneTick();
        clickSlot(slotWithAction("function-confirm"));
        server.getScheduler().performOneTick();

        assertFalse(repository.loadPerkIds(player.getUniqueId()).orElseThrow()
                .contains("mining_perk_a"));
    }

    private void giveFunctionItem(String catalogId, Material material) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> ItemData.of(meta).setCatalogId(catalogId));
        player.getInventory().setItemInMainHand(item);
    }

    /**
     * 幹の起点に開くので、ノードが見えるまで北へ送る（プレステージ確認テストと同じ）。
     */
    private int nodeSlot() {
        for (int step = 0; step < 40; step++) {
            int found = findAction("node");
            if (found >= 0) {
                return found;
            }
            int northSlot = findAction("move-n");
            if (northSlot < 0) {
                break;
            }
            clickSlot(northSlot);
            server.getScheduler().performOneTick();
        }
        int found = findAction("node");
        if (found >= 0) {
            return found;
        }
        return fail("ノードが描画されていない（北へスクロールしても現れない）");
    }

    private int slotWithAction(String wanted) {
        int found = findAction(wanted);
        if (found >= 0) {
            return found;
        }
        return fail("アクション " + wanted + " のマスが描画されていない");
    }

    private int findAction(String wanted) {
        Inventory top = player.getOpenInventory().getTopInventory();
        for (int slot = 0; slot < top.getSize(); slot++) {
            ItemStack item = top.getItem(slot);
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            String action = item.getItemMeta().getPersistentDataContainer()
                    .get(actionKey, PersistentDataType.STRING);
            if (wanted.equals(action)) {
                return slot;
            }
        }
        return -1;
    }

    private void clickSlot(int slot) {
        menu.onClick(new InventoryClickEvent(
                player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    private String drainMessages() {
        StringBuilder all = new StringBuilder();
        String next;
        while ((next = player.nextMessage()) != null) {
            all.append(next).append('\n');
        }
        return PlainTextComponentSerializer.plainText()
                .serialize(Component.text(all.toString()));
    }
}
