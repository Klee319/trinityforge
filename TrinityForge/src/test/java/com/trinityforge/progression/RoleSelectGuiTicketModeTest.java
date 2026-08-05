package com.trinityforge.progression;

import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ロール付け直し券({@code role_reselect_ticket})の券モード({@link RoleSelectGui#open(
 * org.bukkit.entity.Player, boolean)})の契約テスト。
 * <ul>
 *   <li>クールダウン中でもGUIが開くこと</li>
 *   <li>実際にロールが確定したときにだけ券が1個消費されること
 *       (GUIを開いただけ・同じロールを選び直しただけでは減らない)</li>
 *   <li>確定した場合は通常のクールダウンが始まること(要件で明示的に確定した設計判断)</li>
 *   <li>GUIを開いた後に券が無くなっていたら通常のゲート(クールダウン)へフォールバックすること</li>
 * </ul>
 */
class RoleSelectGuiTicketModeTest {

    private static final long COOLDOWN_MILLIS = 120L * 60_000L;

    private ServerMock server;
    private PlayerMock player;
    private RoleChangeService roleChangeService;
    private RoleSelectGui gui;
    private AtomicLong now;
    private org.bukkit.plugin.Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        now = new AtomicLong(1_000_000L);

        CombatRoleSpec tank = new CombatRoleSpec("tank", "守衛", Map.of(), Map.of(), 1.0, "SHIELD", List.of());
        CombatRoleSpec mage = new CombatRoleSpec("mage", "魔術師", Map.of(), Map.of(), 1.0, "BLAZE_ROD", List.of());
        SupportRoleSpec miner = new SupportRoleSpec("miner", "鉱夫", "MINING", 1.15, null, "IRON_PICKAXE", List.of());

        RoleBuffsConfig config = mock(RoleBuffsConfig.class);
        when(config.allowRoleChange()).thenReturn(true);
        when(config.combatRoles()).thenReturn(Map.of("tank", tank, "mage", mage));
        when(config.supportRoles()).thenReturn(Map.of("miner", miner));
        when(config.roleChangeCooldownMillis()).thenReturn(COOLDOWN_MILLIS);
        when(config.firstChoiceFree()).thenReturn(false);
        when(config.nearbyEnemyRadius()).thenReturn(0.0);

        RoleBuffListener buffListener = mock(RoleBuffListener.class);
        roleChangeService = new RoleChangeService(config, buffListener, now::get);

        LoreConfig loreConfig = mock(LoreConfig.class);
        when(loreConfig.displayTable()).thenReturn(Map.of());
        RoleDescriptions descriptions = new RoleDescriptions(loreConfig);

        plugin = MockBukkit.createMockPlugin();
        gui = new RoleSelectGui(plugin, roleChangeService, descriptions);
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

    /** メインハンドに券を1個持たせる。 */
    private void giveTicket() {
        player.getInventory().setItemInMainHand(ticketStack());
    }

    private int combatButtonSlot(Inventory top, String roleId) {
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            String id = item.getItemMeta().getPersistentDataContainer()
                    .get(combatKeyReflection(), PersistentDataType.STRING);
            if (roleId.equals(id)) {
                return i;
            }
        }
        throw new AssertionError("role button not found: " + roleId);
    }

    // RoleSelectGui#combatKey はprivateなので、テストからは同じ命名規則のNamespacedKeyを
    // 自前で再構築する(GUI側が使うのと同じ plugin + "role_gui_combat")。
    private org.bukkit.NamespacedKey combatKeyReflection() {
        return new org.bukkit.NamespacedKey(plugin, "role_gui_combat");
    }

    @Test
    @DisplayName("クールダウン中でも券モードならGUIが開く")
    void ticketModeOpensEvenDuringCooldown() {
        roleChangeService.setCombat(player, "tank");
        assertTrue(roleChangeService.denyReasonForCombat(player).isPresent(), "前提: 通常はクールダウン中");

        gui.open(player, true);

        assertTrue(player.getOpenInventory().getTopInventory().getSize() > 0, "券モードはゲートで開閉自体を塞がない");
    }

    @Test
    @DisplayName("同じロールを選び直しただけでは券が減らない")
    void reselectingSameRoleDoesNotConsumeTicket() {
        roleChangeService.setCombat(player, "tank");
        giveTicket();
        gui.open(player, true);
        Inventory top = player.getOpenInventory().getTopInventory();
        int slot = combatButtonSlot(top, "tank");

        gui.onClick(clickEvent(slot));

        assertEquals(1, player.getInventory().getItemInMainHand().getAmount(),
                "同じロールの選び直しは変化が無いので券を消費してはいけない");
    }

    @Test
    @DisplayName("券モードは、実際にロールが確定したときだけクールダウンを無視して1個消費する")
    void ticketBypassesCooldownAndConsumesExactlyOneOnActualChange() {
        // 事前にクールダウンを刻んでおく(通常なら変更不可の状態)。
        roleChangeService.setCombat(player, "tank");
        assertTrue(roleChangeService.denyReasonForCombat(player).isPresent());
        giveTicket();

        gui.open(player, true);
        Inventory top = player.getOpenInventory().getTopInventory();
        int slot = combatButtonSlot(top, "mage");

        gui.onClick(clickEvent(slot));

        assertEquals("mage", PlayerData.of(player).rolePrimary().orElseThrow(),
                "クールダウン中でも券モードなら変更できるはず");
        // getItemInMainHand() は空スロットでも null ではなく AIR の ItemStack(既定amount=1)を返す
        // (Bukkit APIの契約)。よって「消費された」判定は amount==0 ではなく型で見る
        // (EquipmentTicketGuiTest と同じ書き方)。
        ItemStack mainHandAfter = player.getInventory().getItemInMainHand();
        assertTrue(mainHandAfter == null || mainHandAfter.getType() == Material.AIR,
                "実際に確定したので券は1個消費されて空になるはず");
        // 券で変更した場合も通常のクールダウンは開始する(連打による交戦中スイッチの成立を防ぐ)。
        assertTrue(roleChangeService.denyReasonForCombat(player).isPresent(),
                "券で変更しても通常のクールダウンは始まるはず");
    }

    @Test
    @DisplayName("GUIを開いた後に券が無くなっていたら通常のクールダウンへフォールバックする")
    void fallsBackToNormalGateWhenTicketDisappearsBeforeConfirm() {
        roleChangeService.setCombat(player, "tank");
        giveTicket();
        gui.open(player, true);
        Inventory top = player.getOpenInventory().getTopInventory();
        int slot = combatButtonSlot(top, "mage");

        // GUIを開いた後に券をどこかへやってしまったことを模す。
        player.getInventory().setItemInMainHand(null);

        gui.onClick(clickEvent(slot));

        assertEquals("tank", PlayerData.of(player).rolePrimary().orElseThrow(),
                "券が無くなっているので通常のクールダウンに従って拒否されるはず");
    }
}
