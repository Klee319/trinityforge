package com.trinityforge.progression;

import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code role-change.allow-change: false}（＝「初回の無料就職以外はアイテム消費でのみ変更可」、
 * 2026-08-05 の W-28）の契約テスト。
 *
 * <p>旧 {@code allow-command: false} は「一切変更不可」で、初回の就職すらできなかった。
 * ここで固定したいのは次の4点。
 * <ul>
 *   <li>まだ就いていない枠への初回就職は、券なしで通る</li>
 *   <li>就職済みの枠の付け替えは拒否され、理由が「転職の証」を指す</li>
 *   <li>券モード（証を手に持って右クリック）なら通り、券を1個消費する</li>
 *   <li><b>解除は塞がる</b>— 枠を空にできると「初回無料」を無限に再利用できて券が要らなくなる</li>
 * </ul>
 */
class RoleChangeTicketOnlyModeTest {

    private ServerMock server;
    private PlayerMock player;
    private RoleBuffsConfig config;
    private RoleChangeService service;
    private RoleSelectGui gui;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();

        CombatRoleSpec tank = new CombatRoleSpec("tank", "守衛", Map.of(), Map.of(), 1.0, "SHIELD", List.of());
        CombatRoleSpec mage = new CombatRoleSpec("mage", "魔術師", Map.of(), Map.of(), 1.0, "BLAZE_ROD", List.of());
        SupportRoleSpec miner = new SupportRoleSpec("miner", "鉱夫", "MINING", 1.15, null, "IRON_PICKAXE", List.of());

        config = mock(RoleBuffsConfig.class);
        // 不許可モード。待ち時間は 0 にしておく — 拒否理由が「券が必要」であることを、
        // クールダウンと取り違えずに確認するため。
        when(config.allowRoleChange()).thenReturn(false);
        when(config.combatRoles()).thenReturn(Map.of("tank", tank, "mage", mage));
        when(config.supportRoles()).thenReturn(Map.of("miner", miner));
        when(config.roleChangeCooldownMillis()).thenReturn(0L);
        when(config.firstChoiceFree()).thenReturn(true);
        when(config.nearbyEnemyRadius()).thenReturn(0.0);
        when(config.combatRole("tank")).thenReturn(tank);
        when(config.combatRole("mage")).thenReturn(mage);
        when(config.supportRole("miner")).thenReturn(miner);

        service = new RoleChangeService(config, mock(RoleBuffListener.class));

        LoreConfig loreConfig = mock(LoreConfig.class);
        when(loreConfig.displayTable()).thenReturn(Map.of());
        plugin = MockBukkit.createMockPlugin();
        gui = new RoleSelectGui(plugin, service, new RoleDescriptions(loreConfig));
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static ItemStack ticketStack() {
        ItemStack stack = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(RoleSelectGui.TICKET_CATALOG_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    private InventoryClickEvent clickEvent(int slot) {
        return new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
    }

    /** GUI が置いたボタンのスロットを PDC キーで引く(スロット定数は private なので位置に依存しない)。 */
    private int buttonSlot(String pdcKey, String roleId) {
        Inventory top = player.getOpenInventory().getTopInventory();
        NamespacedKey key = new NamespacedKey(plugin, pdcKey);
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            if (roleId.equals(item.getItemMeta().getPersistentDataContainer()
                    .get(key, PersistentDataType.STRING))) {
                return i;
            }
        }
        throw new AssertionError("button not found: " + pdcKey + "=" + roleId);
    }

    private int clearSlot() {
        Inventory top = player.getOpenInventory().getTopInventory();
        NamespacedKey key = new NamespacedKey(plugin, "role_gui_clear");
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            if (item != null && item.hasItemMeta()
                    && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                return i;
            }
        }
        throw new AssertionError("clear button not found");
    }

    @Test
    @DisplayName("不許可モードでも、まだ就いていない枠への初回就職は券なしで通る")
    void firstChoiceIntoAnEmptySlotIsFree() {
        assertTrue(service.denyReasonForCombat(player).isEmpty(), "初回の就職は塞がない");
        assertTrue(service.denyReasonForSupport(player).isEmpty());

        gui.open(player);
        gui.onClick(clickEvent(buttonSlot("role_gui_combat", "tank")));

        assertEquals("tank", PlayerData.of(player).rolePrimary().orElseThrow(),
                "旧 allow-command: false は初回の就職まで塞いでいた");
    }

    @Test
    @DisplayName("不許可モードでは、就職済みの枠の付け替えは券を要求して拒否する")
    void changingAnOccupiedSlotRequiresTheTicket() {
        service.setCombat(player, "tank");

        String reason = service.denyReasonForCombat(player).orElseThrow();
        assertTrue(reason.contains("転職の証"), "拒否理由がどうすれば変更できるかを指していること: " + reason);

        gui.open(player);
        gui.onClick(clickEvent(buttonSlot("role_gui_combat", "mage")));

        assertEquals("tank", PlayerData.of(player).rolePrimary().orElseThrow(), "券なしでは変わらない");
    }

    @Test
    @DisplayName("不許可モードでも、券モードなら付け替えられて券を1個消費する")
    void ticketModeStillChangesAndConsumesOne() {
        service.setCombat(player, "tank");
        player.getInventory().setItemInMainHand(ticketStack());

        gui.open(player, true);
        gui.onClick(clickEvent(buttonSlot("role_gui_combat", "mage")));

        assertEquals("mage", PlayerData.of(player).rolePrimary().orElseThrow());
        // getItemInMainHand() は空スロットでも AIR の ItemStack を返す(Bukkit の契約)ので型で見る。
        ItemStack after = player.getInventory().getItemInMainHand();
        assertTrue(after == null || after.getType() == Material.AIR, "確定したので券は1個消費される");
    }

    @Test
    @DisplayName("不許可モードでは解除も塞ぐ(空にできると初回無料を無限に再利用できる)")
    void clearIsBlockedSoTheFreeFirstChoiceCannotBeRecycled() {
        service.setCombat(player, "tank");
        assertTrue(service.changeDisabledReason(player).isPresent());

        gui.open(player);
        gui.onClick(clickEvent(clearSlot()));

        assertEquals("tank", PlayerData.of(player).rolePrimary().orElseThrow(),
                "解除が通ると枠が空になり、初回無料で好きな職に就き直せてしまう");
    }

    @Test
    @DisplayName("不許可モード + first-choice-free: false なら初回の就職も券が要る")
    void firstChoiceAlsoNeedsTheTicketWhenItIsNotFree() {
        when(config.firstChoiceFree()).thenReturn(false);

        assertFalse(service.denyReasonForCombat(player).isEmpty(),
                "運営が券を配って始める運用。config の組み合わせとして成立させておく");
    }
}
