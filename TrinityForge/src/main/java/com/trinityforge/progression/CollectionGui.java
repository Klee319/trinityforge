package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemTemplate;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /tf collection} インベントリGUI (2026-07-23-stat-gate-overhaul §6.3):
 * カテゴリタブ(行0) + エントリ表示(行1-4、36枠) + 操作行(行5)。
 * ページング/グルーピング/並べ替え/絞り込みの純ロジックは {@link CollectionGuiModel}(ユニットテスト済み)。
 *
 * <p>2026-07-27 追加:
 * <ul>
 *   <li><b>表示名</b> — 内部ID直出しをやめ、アイテムは display-name、モブは翻訳可能コンポーネントで出す
 *       ({@link CollectionEntryNames})。</li>
 *   <li><b>並べ替え</b> — 名前順 / 種別順(use-skill、無ければ Material) / 使用可能レベル順を1ボタンで循環。</li>
 *   <li><b>絞り込み</b> — すべて / 発見済み / 未発見 を別ボタンで循環。</li>
 *   <li><b>名前検索</b> — ワイルドカード({@code *} / {@code ?})。入力はチャット経由
 *       (砥石/金床UIはBedrockクライアントでの挙動差が読めないため、確実に動くチャット入力にしてある)。
 *       未発見エントリは名前を伏せている都合上、検索の対象にしない。</li>
 *   <li><b>ロックアイコン</b> — 未発見は {@code gui.locked-icon}(既定 BARRIER)で表示する。</li>
 * </ul>
 */
public final class CollectionGui implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int CONTENT_START = 9;
    private static final int CONTENT_SIZE = 36;
    private static final int PREV_SLOT = 45;
    private static final int SORT_SLOT = 47;
    private static final int FILTER_SLOT = 48;
    private static final int SEARCH_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    /** 検索解除に使う入力(「-」だけ)。 */
    private static final String SEARCH_CLEAR_TOKEN = "-";

    private final Plugin plugin;
    private final CollectionConfig config;
    private final CollectionService service;
    private final CrossPluginItemResolver itemResolver;
    private final ItemCatalogConfig catalog;
    private final CollectionEntryNames names;
    private final NamespacedKey tabIndexKey;
    private final NamespacedKey navKey;
    private final NamespacedKey actionKey;
    /** 検索語のチャット入力待ちプレイヤー -> 待ち状態(復帰先のタブ/ページ)。 */
    private final Map<UUID, PendingSearch> pendingSearches = new ConcurrentHashMap<>();

    public CollectionGui(Plugin plugin, CollectionConfig config, CollectionService service,
                          CrossPluginItemResolver itemResolver) {
        this(plugin, config, service, itemResolver, null);
    }

    /**
     * @param catalog {@code items/catalog.yml}(null 可)。種別順/レベル順の並べ替えキーを引くためだけに
     *                使う。null のときは種別=Material 不明・レベル=0 として名前順に縮退する。
     */
    public CollectionGui(Plugin plugin, CollectionConfig config, CollectionService service,
                          CrossPluginItemResolver itemResolver, ItemCatalogConfig catalog) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.itemResolver = Objects.requireNonNull(itemResolver, "itemResolver");
        this.catalog = catalog;
        this.names = new CollectionEntryNames(itemResolver);
        this.tabIndexKey = new NamespacedKey(plugin, "collection_gui_tab");
        this.navKey = new NamespacedKey(plugin, "collection_gui_nav");
        this.actionKey = new NamespacedKey(plugin, "collection_gui_action");
    }

    public void open(Player player) {
        List<CollectionGuiModel.Tab> tabs = CollectionGuiModel.buildTabs(config, service.records(player));
        if (tabs.isEmpty()) {
            player.sendMessage(Component.text("図鑑カテゴリが未設定です。", NamedTextColor.GRAY));
            return;
        }
        open(player, new ViewState(tabs, 0, 0, CollectionGuiModel.SortMode.DEFAULT,
                CollectionGuiModel.FilterMode.ALL, ""));
    }

    private void open(Player player, ViewState requested) {
        List<CollectionGuiModel.Tab> tabs = requested.tabs();
        int clampedTab = Math.max(0, Math.min(requested.tabIndex(), tabs.size() - 1));
        CollectionGuiModel.Tab tab = tabs.get(clampedTab);
        List<CollectionRecord> owned = service.records(player);
        List<CollectionGuiModel.GuiEntry> entries = CollectionGuiModel.entriesFor(tab, owned);
        Map<String, CollectionGuiModel.EntrySortKey> keyCache = new HashMap<>();
        List<CollectionGuiModel.GuiEntry> arranged = CollectionGuiModel.arrange(
                entries, requested.sort(), requested.filter(), requested.search(),
                id -> keyCache.computeIfAbsent(id, this::sortKeyOf));
        List<List<CollectionGuiModel.GuiEntry>> pages = CollectionGuiModel.paginate(arranged, CONTENT_SIZE);
        int clampedPage = Math.max(0, Math.min(requested.page(), pages.size() - 1));

        ViewState state = new ViewState(tabs, clampedTab, clampedPage, requested.sort(),
                requested.filter(), requested.search());
        Session session = new Session(state);
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
        inventory.setItem(SORT_SLOT, sortButton(state));
        inventory.setItem(FILTER_SLOT, filterButton(state, arranged.size(), entries.size()));
        inventory.setItem(SEARCH_SLOT, searchButton(state));
        player.openInventory(inventory);
    }

    private ItemStack tabButton(CollectionGuiModel.Tab tab, int index, boolean selected) {
        ItemStack stack = new ItemStack(selected ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(tab.displayName(), selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(tabIndexKey, PersistentDataType.INTEGER, index);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack navButton(String label, int delta) {
        ItemStack stack = new ItemStack(delta < 0 ? Material.ARROW : Material.SPECTRAL_ARROW);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(navKey, PersistentDataType.INTEGER, delta);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack sortButton(ViewState state) {
        ItemStack stack = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("並べ替え: " + state.sort().label(), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("クリックで次の並び順へ", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        for (CollectionGuiModel.SortMode mode : CollectionGuiModel.SortMode.values()) {
            lore.add(Component.text((mode == state.sort() ? "▶ " : "  ") + mode.label(),
                    mode == state.sort() ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, Action.SORT.name());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack filterButton(ViewState state, int shown, int total) {
        ItemStack stack = new ItemStack(Material.HOPPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("絞り込み: " + state.filter().label(), NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("クリックで すべて / 発見済み / 未発見 を切替", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("表示 " + shown + " / 全 " + total + " 件", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, Action.FILTER.name());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack searchButton(ViewState state) {
        boolean active = !state.search().isBlank();
        ItemStack stack = new ItemStack(active ? Material.WRITABLE_BOOK : Material.SPYGLASS);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(active ? "検索中: " + state.search() : "名前で検索",
                        active ? NamedTextColor.GREEN : NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("クリックするとチャット入力に切り替わります", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("ワイルドカード: * = 任意の文字列 / ? = 任意の1文字", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("「" + SEARCH_CLEAR_TOKEN + "」だけ送ると検索を解除します",
                                NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("※ 未発見エントリは検索対象外です", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, Action.SEARCH.name());
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
            meta.displayName(Component.text("？？？", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("未発見", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
        } else {
            meta.displayName(names.displayFrom(entry.entryId(), icon)
                    .colorIfAbsent(NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("初記録: " + formatEpoch(entry.epochMillis()), NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("最高品質pt: " + entry.qualityPt(), NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
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
            return new ItemStack(lockedIconMaterial());
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

    /** 未発見エントリの錠前アイコン。config の値が Material として解決できなければ既定へ落とす。 */
    private Material lockedIconMaterial() {
        Material resolved = Material.matchMaterial(config.lockedIcon());
        if (resolved == null || resolved.isAir()) {
            resolved = Material.matchMaterial(CollectionConfig.DEFAULT_LOCKED_ICON);
        }
        return resolved == null ? Material.GRAY_STAINED_GLASS_PANE : resolved;
    }

    /**
     * 並べ替え/検索用のキー。ItemStack を組み立てずに済ませる(全エントリ分を毎クリック構築すると
     * 数百件のカタログで体感できるほど重くなるため)。
     */
    private CollectionGuiModel.EntrySortKey sortKeyOf(String entryId) {
        if (entryId.startsWith("mob:")) {
            String entityType = entryId.substring("mob:".length());
            String override = config.mobDisplayNames().get(entityType);
            String name = override != null ? override : entityType;
            return new CollectionGuiModel.EntrySortKey(name.toLowerCase(Locale.ROOT), "zz_mob", 0);
        }
        if (entryId.startsWith("item:")) {
            String catalogId = entryId.substring("item:".length());
            String override = config.itemDisplayNames().get(catalogId);
            ItemTemplate template = catalog == null ? null : catalog.template(catalogId).orElse(null);
            String name = override != null ? override
                    : (template != null && template.displayName() != null
                            ? plain(template.displayName()) : catalogId);
            String kind = template == null ? ""
                    : (template.useSkill() != null && !template.useSkill().isBlank()
                            ? template.useSkill() : template.material().name());
            int level = template == null ? 0 : template.useLevelRequirement();
            return new CollectionGuiModel.EntrySortKey(name.toLowerCase(Locale.ROOT),
                    kind.toLowerCase(Locale.ROOT), level);
        }
        return new CollectionGuiModel.EntrySortKey(entryId.toLowerCase(Locale.ROOT), "", 0);
    }

    /** MiniMessage の display-name をプレーンテキストへ。壊れた記法はそのまま素の文字列として扱う。 */
    private static String plain(String miniMessage) {
        try {
            return PlainTextComponentSerializer.plainText()
                    .serialize(MiniMessage.miniMessage().deserialize(miniMessage));
        } catch (RuntimeException ignored) {
            return miniMessage;
        }
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
        ViewState state = session.state;
        Integer tabTarget = meta.getPersistentDataContainer().get(tabIndexKey, PersistentDataType.INTEGER);
        if (tabTarget != null) {
            open(player, state.withTab(tabTarget));
            return;
        }
        Integer navDelta = meta.getPersistentDataContainer().get(navKey, PersistentDataType.INTEGER);
        if (navDelta != null) {
            open(player, state.withPage(state.page() + navDelta));
            return;
        }
        String rawAction = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        Action action = Action.parse(rawAction);
        if (action == null) {
            return;
        }
        switch (action) {
            case SORT -> open(player, state.withSort(state.sort().next()));
            case FILTER -> open(player, state.withFilter(state.filter().next()));
            case SEARCH -> beginSearch(player, state);
        }
    }

    /**
     * 検索語のチャット入力を開始する。GUIを閉じてから受け付け、入力(またはキャンセル)後に
     * 同じタブ/並び順で開き直す。
     */
    private void beginSearch(Player player, ViewState state) {
        pendingSearches.put(player.getUniqueId(), new PendingSearch(state));
        player.closeInventory();
        player.sendMessage(Component.text("図鑑の検索語をチャットに入力してください。", NamedTextColor.AQUA));
        player.sendMessage(Component.text("　ワイルドカード: * = 任意の文字列 / ? = 任意の1文字",
                NamedTextColor.GRAY));
        player.sendMessage(Component.text("　「" + SEARCH_CLEAR_TOKEN + "」で検索解除。何も入力せず離脱すると"
                + "そのままです。", NamedTextColor.GRAY));
    }

    /**
     * 検索語入力待ちのプレイヤーの発言を横取りする。{@link EventPriority#LOWEST} で受けてキャンセル
     * するので、検索語がチャット欄へ流れることはない。GUIの再表示はメインスレッドへ戻してから行う
     * (チャットイベントは非同期のため、ここで直接 openInventory してはいけない)。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSearchChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        PendingSearch pending = pendingSearches.remove(player.getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        String search = SEARCH_CLEAR_TOKEN.equals(input) ? "" : input;
        ViewState next = pending.state().withSearch(search).withPage(0);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (search.isBlank()) {
                player.sendMessage(Component.text("図鑑の検索を解除しました。", NamedTextColor.GRAY));
            } else {
                player.sendMessage(Component.text("図鑑を「" + search + "」で絞り込みました。",
                        NamedTextColor.AQUA));
            }
            open(player, next);
        });
    }

    /** 入力待ちのままログアウトした場合に待ち状態を残さない(次回ログインの発言を食べてしまうため)。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onQuit(PlayerQuitEvent event) {
        pendingSearches.remove(event.getPlayer().getUniqueId());
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

    private enum Action {
        SORT, FILTER, SEARCH;

        static Action parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return valueOf(raw);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    /** 1回の表示に必要な状態一式(タブ/ページ/並び順/絞り込み/検索語)。 */
    private record ViewState(List<CollectionGuiModel.Tab> tabs, int tabIndex, int page,
                             CollectionGuiModel.SortMode sort, CollectionGuiModel.FilterMode filter,
                             String search) {
        ViewState {
            search = search == null ? "" : search;
        }

        ViewState withTab(int newTab) {
            return new ViewState(tabs, newTab, 0, sort, filter, search);
        }

        ViewState withPage(int newPage) {
            return new ViewState(tabs, tabIndex, newPage, sort, filter, search);
        }

        ViewState withSort(CollectionGuiModel.SortMode newSort) {
            return new ViewState(tabs, tabIndex, 0, newSort, filter, search);
        }

        ViewState withFilter(CollectionGuiModel.FilterMode newFilter) {
            return new ViewState(tabs, tabIndex, 0, sort, newFilter, search);
        }

        ViewState withSearch(String newSearch) {
            return new ViewState(tabs, tabIndex, page, sort, filter, newSearch);
        }
    }

    private record PendingSearch(ViewState state) {
    }

    private static final class Session implements InventoryHolder {
        private final ViewState state;
        private Inventory inventory;

        private Session(ViewState state) {
            this.state = state;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
