package com.trinityforge.stats.status;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.progression.RoleChangeService;
import com.trinityforge.progression.RoleDescriptions;
import com.trinityforge.progression.RoleSelectGui;
import com.trinityforge.progression.SkillLevelSource;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /tf status} に統合したロールの確認・変更(2026-08-05, W-28)。
 *
 * <p>{@code /tf menu} と {@code /tf role set} を削除した先がここなので、
 * <b>「ロールを確認する画面」と「変更を始める導線」がこの画面から失われていないこと</b>を固定する。
 * 表示している可否は {@link RoleChangeService} をそのまま読んでいること(独自ルールを持たないこと)も
 * ここで見る — 食い違うと「表示は変更できると言うのに押したら拒否される」になる。
 */
class StatusGuiRolePanelTest {

    private ServerMock server;
    private PlayerMock player;
    private RoleBuffsConfig config;
    private StatusGui statusGui;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();

        CombatRoleSpec tank = new CombatRoleSpec("tank", "守衛", Map.of(), Map.of(), 1.0, "SHIELD", List.of());
        SupportRoleSpec miner = new SupportRoleSpec("miner", "鉱夫", "MINING", 1.15, null, "IRON_PICKAXE", List.of());

        config = mock(RoleBuffsConfig.class);
        when(config.allowRoleChange()).thenReturn(true);
        when(config.combatRoles()).thenReturn(Map.of("tank", tank));
        when(config.supportRoles()).thenReturn(Map.of("miner", miner));
        when(config.roleChangeCooldownMillis()).thenReturn(0L);
        when(config.firstChoiceFree()).thenReturn(true);
        when(config.nearbyEnemyRadius()).thenReturn(0.0);
        when(config.combatRole("tank")).thenReturn(tank);
        when(config.supportRole("miner")).thenReturn(miner);

        RoleChangeService roleChangeService = new RoleChangeService(config, mock(RoleBuffListener.class));
        LoreConfig loreConfig = mock(LoreConfig.class);
        when(loreConfig.displayTable()).thenReturn(Map.of());
        RoleSelectGui roleSelectGui = new RoleSelectGui(plugin, roleChangeService,
                new RoleDescriptions(loreConfig));

        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(1);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any())).thenReturn(new PlayerCombatAggregate(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null));
        SkillLevelSource skillLevelSource = mock(SkillLevelSource.class);
        when(skillLevelSource.levelsOf(any())).thenReturn(Map.of());

        statusGui = new StatusGui(plugin, combatService, aggregator, loreConfig, skillLevelSource,
                null, roleChangeService, roleSelectGui);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 概要画面から、ロールのアイコン(表示名に「職業」を含む)を探して返す。 */
    private int roleIconSlot() {
        Inventory top = player.getOpenInventory().getTopInventory();
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            if (item == null || !item.hasItemMeta() || item.getItemMeta().displayName() == null) {
                continue;
            }
            String name = PlainTextComponentSerializer.plainText()
                    .serialize(item.getItemMeta().displayName());
            if (name.contains("職業")) {
                return i;
            }
        }
        throw new AssertionError("ロールのアイコンが概要画面に無い");
    }

    private List<String> loreAt(int slot) {
        List<String> lines = new ArrayList<>();
        var lore = player.getOpenInventory().getTopInventory().getItem(slot).getItemMeta().lore();
        if (lore != null) {
            lore.forEach(line -> lines.add(PlainTextComponentSerializer.plainText().serialize(line)));
        }
        return lines;
    }

    @Test
    @DisplayName("概要画面に現在のロールと変更可否が出る")
    void overviewShowsTheCurrentRolesAndWhetherTheyCanChange() {
        statusGui.open(player);
        List<String> lore = loreAt(roleIconSlot());

        assertTrue(lore.stream().anyMatch(l -> l.contains("戦闘職") && l.contains("(未選択)")),
                "未就職なら未選択と出す: " + lore);
        assertTrue(lore.stream().anyMatch(l -> l.contains("変更できます")),
                "待ち時間なしなら変更できると出す: " + lore);
    }

    @Test
    @DisplayName("ロールを持っていれば職業名が出て、待ち時間中はその理由がそのまま出る")
    void overviewShowsTheRoleLabelAndTheServiceDenyReason() {
        when(config.roleChangeCooldownMillis()).thenReturn(120L * 60_000L);
        // first-choice-free だと初回の就職では待ち時間を刻まない(空欄を埋めただけでは待たせない設計)。
        // 待ち時間の表示を見たいので、ここでは初回から刻む設定にする。
        when(config.firstChoiceFree()).thenReturn(false);
        RoleChangeService service = new RoleChangeService(config, mock(RoleBuffListener.class));
        service.setCombat(player, "tank");

        statusGui.open(player);
        List<String> lore = loreAt(roleIconSlot());

        assertTrue(lore.stream().anyMatch(l -> l.contains("守衛")), "職業名(label)を出す: " + lore);
        assertTrue(lore.stream().anyMatch(l -> l.contains("戦闘職の変更はあと")),
                "拒否理由は RoleChangeService の文言をそのまま出す(独自に組み立てない): " + lore);
    }

    @Test
    @DisplayName("ロールのアイコンをクリックするとロール選択GUIが開く")
    void clickingTheRoleIconOpensTheRoleSelectGui() {
        statusGui.open(player);
        int slot = roleIconSlot();

        statusGui.onClick(new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        // openInventory は次tickへ逃がしてある(クリック処理中に開くとカーソルがずれる)。
        server.getScheduler().performOneTick();

        assertEquals("ロール選択", PlainTextComponentSerializer.plainText()
                .serialize(player.getOpenInventory().title()));
    }
}
