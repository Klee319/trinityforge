package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * {@code /tf collection} インベントリGUI (2026-07-23-stat-gate-overhaul §6.3):
 * カテゴリタブ(行0) + エントリ表示(行1-4、36枠) + ページ送り(行5)。
 * ページング/グルーピングの純ロジックは {@link CollectionGuiModel}(ユニットテスト済み)。
 */
public final class CollectionGui implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_SIZE = 36;
    private static final int NAV_ROW_START = 45;
    private static final int PREV_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final CollectionConfig config;
    private final CollectionService service;
    private final CrossPluginItemResolver itemResolver;
    private final NamespacedKey tabIndexKey;
    private final NamespacedKey navKey;

    public CollectionGui(Plugin plugin, CollectionConfig config, CollectionService service,
                          CrossPluginItemResolver itemResolver) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.tabIndexKey = new NamespacedKey(plugin, "collection_gui_tab");
        this.navKey = new NamespacedKey(plugin, "collection_gui_nav");
    }

    public void open(Player player) {
        List<CollectionGuiModel.Tab> tabs = CollectionGuiModel.buildTabs(config, service.records(player));
        if (tabs.isEmpty()) {
            player.sendMessage(Component.text("図鑑カテゴリが未設定です。", NamedTextColor.GRAY));
            return;
        }
        open(player, tabs, 0, 0);
    }

    private void open(Player player, List<CollectionGuiModel.Tab> tabs, int tabIndex, int page) {
        int clampedTab = Math.max(0, Math.min(tabIndex, tabs.size() - 1));
        CollectionGuiModel.Tab tab = tabs.get(clampedTab);
        List<CollectionRecord> owned = service.records(player);
        List<CollectionGuiModel.GuiEntry> entries = CollectionGuiModel.entriesFor(tab, owned);
        List<List<CollectionGuiModel.GuiEntry>> pages = CollectionGuiModel.paginate(entries, CONTENT_SIZE);
        int clampedPage = Math.max(0, Math.min(page, pages.size() - 1));

        Session session = new Session(tabs, clampedTab, clampedPage);
        Inventory inventory = Bukkit.createInventory(session, SIZE,
                Component.text("図鑑: " + tab.displayName() + " (" + (clampedPage + 1) + "/" + pages.size() + ")"));
        session.inventory = inventory;

        for (int i = 0; i < tabs.size() && i < 9; i++) {
            inventory.setItem(i, tabButton(tabs.get(i), i, i == clampedTab));
        }
        List<CollectionGuiModel.GuiEntry> pageEntries = pages.get(clampedPage);
        for (int i = 0; i < pageEntries.size(); i++) {
            inventory.setItem(CONTENT_START + i, entryIcon(pageEntries.get(i)));
        }
        if (clampedPage > 0) {
            inventory.setItem(PREV_SLOT, navButton("« 前のページ", -1));
        }
        if (clampedPage < pages.size() - 1) {
            inventory.setItem(NEXT_SLOT, navButton("次のページ »", 1));
        }
        player.openInventory(inventory);
    }

    private ItemStack tabButton(CollectionGuiModel.Tab tab, int index, boolean selected) {
        ItemStack stack = new ItemStack(selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(tab.displayName(), selected ? NamedTextColor.GREEN : NamedTextColor.WHITE));
        meta.getPersistentDataContainer().set(tabIndexKey, PersistentDataType.INTEGER, index);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack navButton(String label, int delta) {
        ItemStack stack = new ItemStack(delta < 0 ? Material.ARROW : Material.SPECTRAL_ARROW);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA));
        meta.getPersistentDataContainer().set(navKey, PersistentDataType.INTEGER, delta);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack entryIcon(CollectionGuiModel.GuiEntry entry) {
        ItemStack icon = buildBaseIcon(entry);
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        if (!entry.discovered()) {
            meta.displayName(Component.text("？？？", NamedTextColor.DARK_GRAY));
            meta.lore(List.of(Component.text("未発見", NamedTextColor.GRAY)));
        } else {
            meta.displayName(Component.text(CollectionService.displayOf(entry.entryId()), NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("初記録: " + formatEpoch(entry.epochMillis()), NamedTextColor.GRAY),
                    Component.text("最高品質pt: " + entry.qualityPt(), NamedTextColor.GRAY)));
        }
        icon.setItemMeta(meta);
        return icon;
    }

    /**
     * 2026-07-23 verifier指摘⑨: {@code epochMillis == 0}(旧形式互換のプレースホルダ値)は日時として
     * 1970-01-01 を表示せず「不明」と表示する。
     */
    private static String formatEpoch(long epochMillis) {
        if (epochMillis <= 0L) {
            return "不明";
        }
        return DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private ItemStack buildBaseIcon(CollectionGuiModel.GuiEntry entry) {
        if (!entry.discovered()) {
            return new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        }
        String id = entry.entryId();
        if (id.startsWith("item:")) {
            String catalogId = id.substring("item:".length());
            return itemResolver.create(catalogId).orElseGet(() -> new ItemStack(Material.PAPER));
        }
        if (id.startsWith("mob:")) {
            String entityType = id.substring("mob:".length());
            Material egg = Material.matchMaterial(entityType + "_SPAWN_EGG");
            return new ItemStack(egg != null ? egg : Material.ZOMBIE_HEAD);
        }
        return new ItemStack(Material.PAPER);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        Integer tabTarget = meta.getPersistentDataContainer().get(tabIndexKey, PersistentDataType.INTEGER);
        if (tabTarget != null) {
            open(player, session.tabs, tabTarget, 0);
            return;
        }
        Integer navDelta = meta.getPersistentDataContainer().get(navKey, PersistentDataType.INTEGER);
        if (navDelta != null) {
            open(player, session.tabs, session.tabIndex, session.page + navDelta);
        }
    }

    /**
     * 2026-07-23 verifier指摘⑨: ドラッグでアイテムがGUIへ置けて消失するのを防ぐ(クリックと同様、常に
     * キャンセル)。
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    private static final class Session implements InventoryHolder {
        private final List<CollectionGuiModel.Tab> tabs;
        private final int tabIndex;
        private final int page;
        private Inventory inventory;

        private Session(List<CollectionGuiModel.Tab> tabs, int tabIndex, int page) {
            this.tabs = tabs;
            this.tabIndex = tabIndex;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
