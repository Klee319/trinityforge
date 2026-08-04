package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.progression.achievement.AchievementGui;
import com.trinityforge.skilltree.runtime.NativeSkillTreeMenu;
import com.trinityforge.stats.status.StatusGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code /tf menu} — 統合メニュー(2026-08-04新設)。既存の各GUIへの入口をアイコンで並べる。
 *
 * <p>並べる項目: ロールセット / ステータス / スキルツリー / 実績 / 図鑑 / 設定
 * (スロット割当・項目定義は {@link MainMenuLayout} を単一の出所とする)。
 *
 * <p><b>設計方針</b>: 各項目のクリックは対応する既存GUIの {@code open(Player)} を呼ぶだけで、
 * 各GUI自体の実装には一切手を入れない(ユーザー要件)。利用可否も各機能が既に持っている判定
 * (ロールの{@code allow-command}フラグ・図鑑の{@code enabled}フラグ)をそのまま読むだけで、
 * ここで独自の権限体系・二重の権限管理は作らない。管理者専用の機能(reload/give等)はそもそも
 * この一覧に含めない。
 *
 * <p>統合版(Bedrock/Geyser)対策: アイコンは全て素のバニラ{@link Material}で、
 * {@code item_model}のような追加のリソースパック登録を要する仕組みは使わない
 * (新しいCMD/登録が無い状態でも統合版で破綻しないようにするため)。
 */
public final class MainMenuGui implements Listener {

    private static final int SIZE = 54;

    private final Plugin plugin;
    private final RoleSelectGui roleSelectGui;
    private final RoleChangeService roleChangeService;
    private final StatusGui statusGui;
    private final NativeSkillTreeMenu skillTreeMenu;
    private final AchievementGui achievementGui;
    private final CollectionGui collectionGui;
    private final CollectionService collectionService;
    private final CollectionConfig collectionConfig;
    private final SettingsGui settingsGui;
    private final NamespacedKey itemIdKey;

    public MainMenuGui(Plugin plugin,
                       RoleSelectGui roleSelectGui,
                       RoleChangeService roleChangeService,
                       StatusGui statusGui,
                       NativeSkillTreeMenu skillTreeMenu,
                       AchievementGui achievementGui,
                       CollectionGui collectionGui,
                       CollectionService collectionService,
                       CollectionConfig collectionConfig,
                       SettingsGui settingsGui) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.roleSelectGui = Objects.requireNonNull(roleSelectGui, "roleSelectGui");
        this.roleChangeService = Objects.requireNonNull(roleChangeService, "roleChangeService");
        this.statusGui = Objects.requireNonNull(statusGui, "statusGui");
        this.skillTreeMenu = Objects.requireNonNull(skillTreeMenu, "skillTreeMenu");
        this.achievementGui = Objects.requireNonNull(achievementGui, "achievementGui");
        this.collectionGui = Objects.requireNonNull(collectionGui, "collectionGui");
        this.collectionService = Objects.requireNonNull(collectionService, "collectionService");
        this.collectionConfig = Objects.requireNonNull(collectionConfig, "collectionConfig");
        this.settingsGui = Objects.requireNonNull(settingsGui, "settingsGui");
        this.itemIdKey = new NamespacedKey(plugin, "main_menu_item");
    }

    public void open(Player player) {
        Session session = new Session();
        Inventory inventory = Bukkit.createInventory(session, SIZE,
                Component.text("TrinityForge メニュー"));
        session.inventory = inventory;
        Map<String, Boolean> availability = availability(player);
        Map<String, String> reasons = reasons(player);
        for (MainMenuLayout.Item item : MainMenuLayout.items()) {
            boolean available = availability.getOrDefault(item.id(), true);
            inventory.setItem(item.slot(), icon(item, available, reasons.get(item.id())));
        }
        player.openInventory(inventory);
    }

    /**
     * 各項目の利用可否。ここでは各機能が既に持つフラグを読むだけで、独自の権限判定は作らない
     * (ロール: {@code RoleChangeService#commandDisabledReason} = {@code role-buffs.yml} の
     * {@code allow-command} / 図鑑: {@code CollectionConfig#enabled}の{@code enabled})。
     * 管理者専用のOP/{@code trinityforge.admin}判定はこの一覧に無い(そもそも含めていないため)。
     */
    private Map<String, Boolean> availability(Player player) {
        Map<String, Boolean> result = new HashMap<>();
        result.put(MainMenuLayout.ROLE, roleChangeService.commandDisabledReason(player).isEmpty());
        result.put(MainMenuLayout.COLLECTION, collectionConfig.enabled());
        return result;
    }

    /** 利用不可の理由(クリック時にそのままチャットへ出す文言)。 */
    private Map<String, String> reasons(Player player) {
        Map<String, String> result = new HashMap<>();
        roleChangeService.commandDisabledReason(player)
                .ifPresent(reason -> result.put(MainMenuLayout.ROLE, reason));
        if (!collectionConfig.enabled()) {
            result.put(MainMenuLayout.COLLECTION, "コレクション図鑑は無効化されています。");
        }
        return result;
    }

    private ItemStack icon(MainMenuLayout.Item item, boolean available, String reason) {
        Material material = available ? iconMaterial(item.id()) : Material.BARRIER;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(item.displayName(),
                        available ? NamedTextColor.WHITE : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : item.description()) {
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (!available) {
            lore.add(Component.text(reason != null ? reason : "現在は利用できません。", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, item.id());
        stack.setItemMeta(meta);
        return stack;
    }

    /** 素のバニラMaterialのみ(統合版で追加のリソースパック登録を要求しない)。 */
    private static Material iconMaterial(String id) {
        return switch (id) {
            case MainMenuLayout.ROLE -> Material.LEATHER_CHESTPLATE;
            case MainMenuLayout.STATUS -> Material.PLAYER_HEAD;
            case MainMenuLayout.SKILLS -> Material.NETHER_STAR;
            case MainMenuLayout.ACHIEVEMENT -> Material.BOOK;
            case MainMenuLayout.COLLECTION -> Material.WRITTEN_BOOK;
            case MainMenuLayout.SETTINGS -> Material.COMPARATOR;
            default -> Material.PAPER;
        };
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        String id = clicked.getItemMeta().getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        Map<String, Boolean> availability = availability(player);
        MainMenuLayout.Resolution resolution = MainMenuLayout.resolveClick(event.getRawSlot(), availability);
        if (!resolution.matched()) {
            return;
        }
        if (!resolution.available()) {
            Map<String, String> reasons = reasons(player);
            player.sendMessage(Component.text(
                    reasons.getOrDefault(id, "現在は利用できません。"), NamedTextColor.RED));
            return;
        }
        dispatch(player, id);
    }

    /** 対応する既存GUIの{@code open(Player)}を呼ぶだけ(各GUI自体の実装には手を入れない)。 */
    private void dispatch(Player player, String id) {
        switch (id) {
            case MainMenuLayout.ROLE -> roleSelectGui.open(player);
            case MainMenuLayout.STATUS -> statusGui.open(player);
            case MainMenuLayout.SKILLS -> skillTreeMenu.open(player);
            case MainMenuLayout.ACHIEVEMENT -> achievementGui.open(player);
            case MainMenuLayout.COLLECTION -> {
                // /tf collection (CollectionCommand#show) と同じ追い付き解放を先に走らせる。
                collectionService.grantPendingTiers(player);
                collectionGui.open(player);
            }
            case MainMenuLayout.SETTINGS -> settingsGui.open(player);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    private static final class Session implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
