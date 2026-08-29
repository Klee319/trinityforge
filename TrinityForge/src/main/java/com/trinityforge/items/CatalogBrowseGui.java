package com.trinityforge.items;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.listeners.PickupQualityListener;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code /tf catalog} — カタログのアイテムをタブ + ページで並べて配る画面（2026-08-05）。
 *
 * <h2>なぜ画面を作るのか</h2>
 * 要望は「クリエイティブのインベントリタブにカスタムアイテムを適したカテゴリで出したい」だった。
 * <b>Java 版ではサーバプラグインからそれはできない。</b> クリエイティブ画面はクライアントが
 * 自分のアイテムレジストリから組み立てるもので、TF のカスタム品は「custom_model_data 付きの
 * バニラアイテム」でしかないため、クライアントは別アイテムとして知りようがない
 * （統合版は Geyser がカスタムアイテムを本物として登録するので、あちらでは既にタブに出ている）。
 * そこで同じ用途を Java 版・統合版のどちらでも満たせる唯一の形として、サーバ側の画面にした。
 *
 * <h2>分類の出どころ</h2>
 * タブと小分類は <b>{@code items/catalog.yml} の {@code _editor:} をそのまま読む</b>
 * （{@link ItemCatalogConfig#taxonomy()}）。設定エディタで並べ替えた結果がそのまま画面に出る。
 * 分類用の設定を新設していないのは、同じ意味のデータを二重管理すると必ずズレるため。
 *
 * <p><b>分類から漏れたアイテムを落とさない。</b> 小分類にも {@code itemTabs} にも載っていない
 * アイテムは「未分類」タブへ回収する。許可リスト方式で並べると、リストが古くなった分だけ
 * 画面から黙って消える（それが「一覧なのに全部載っていない」という一番たちの悪い壊れ方になる）。
 */
public final class CatalogBrowseGui implements Listener {

    /** 画面の大きさ。1行目=タブ、2〜5行目=アイテム、6行目=ページ送り。 */
    private static final int SIZE = 54;
    private static final int TAB_ROW_START = 0;
    private static final int ITEM_SLOT_START = 9;
    private static final int ITEMS_PER_PAGE = 36;
    private static final int PREV_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final int MAX_TABS = 9;

    /** 権限を持たない人向けの門。要望が「クリエイティブタブ」だったのでゲームモードでも開ける。 */
    public static final String PERMISSION = "trinityforge.catalog";

    /** タブIDの表示名。エディタ側は英語IDしか持たないので、ここで日本語に当てる。 */
    private static final Map<String, String> TAB_LABELS = Map.of(
            "weapon", "武器",
            "armor", "防具",
            "tool", "道具",
            "catalyst", "触媒",
            "spellbook", "魔導書",
            "thread", "スレッド",
            "key", "鍵",
            "material", "素材",
            "other", "その他");

    /** タブIDのアイコン。 */
    private static final Map<String, Material> TAB_ICONS = Map.of(
            "weapon", Material.IRON_SWORD,
            "armor", Material.IRON_CHESTPLATE,
            "tool", Material.IRON_PICKAXE,
            "catalyst", Material.AMETHYST_SHARD,
            "spellbook", Material.ENCHANTED_BOOK,
            "thread", Material.STRING,
            "key", Material.TRIPWIRE_HOOK,
            "material", Material.GOLD_NUGGET,
            "other", Material.BUNDLE);

    /** 分類から漏れたアイテムの受け皿。{@code _editor} 側には存在しない合成タブ。 */
    private static final String UNSORTED_TAB = "_unsorted";
    private static final String UNSORTED_LABEL = "未分類";

    private final ItemCatalogConfig itemCatalog;
    private final ItemFactory itemFactory;
    /** カタログに無いカスタム品（Ars の素材・装置以外など）。未分類タブへ足す。 */
    private final Supplier<List<String>> extraIds;
    private final Function<String, Optional<ItemStack>> extraCreate;
    private final NamespacedKey tabKey;
    private final NamespacedKey pageKey;
    private final NamespacedKey itemIdKey;

    public CatalogBrowseGui(Plugin plugin, ItemCatalogConfig itemCatalog, ItemFactory itemFactory) {
        this(plugin, itemCatalog, itemFactory, List::of, id -> Optional.empty());
    }

    public CatalogBrowseGui(Plugin plugin, ItemCatalogConfig itemCatalog, ItemFactory itemFactory,
                            Supplier<List<String>> extraIds,
                            Function<String, Optional<ItemStack>> extraCreate) {
        Objects.requireNonNull(plugin, "plugin");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.extraIds = extraIds == null ? List::of : extraIds;
        this.extraCreate = extraCreate == null ? id -> Optional.empty() : extraCreate;
        this.tabKey = new NamespacedKey(plugin, "catalog_gui_tab");
        this.pageKey = new NamespacedKey(plugin, "catalog_gui_page");
        this.itemIdKey = new NamespacedKey(plugin, "catalog_gui_item");
    }

    /** クリエイティブか権限持ちだけが開ける。 */
    public static boolean mayOpen(Player player) {
        return player != null
                && (player.getGameMode() == GameMode.CREATIVE || player.hasPermission(PERMISSION));
    }

    public void open(Player player) {
        open(player, null, 0);
    }

    public void open(Player player, String tab, int page) {
        Map<String, List<Entry>> index = index();
        List<String> tabs = tabs(index);
        if (tabs.isEmpty()) {
            player.sendMessage(Component.text("カタログにアイテムがありません。", NamedTextColor.RED));
            return;
        }
        String selected = tabs.contains(tab) ? tab : tabs.get(0);
        List<Entry> entries = index.getOrDefault(selected, List.of());
        int pageCount = Math.max(1, (entries.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
        int clamped = Math.max(0, Math.min(page, pageCount - 1));

        Session session = new Session();
        Inventory inventory = Bukkit.createInventory(session, SIZE,
                Component.text("カタログ: " + labelOf(selected)
                        + " (" + (clamped + 1) + "/" + pageCount + ")", NamedTextColor.DARK_AQUA));
        session.inventory = inventory;

        for (int i = 0; i < tabs.size() && i < MAX_TABS; i++) {
            String id = tabs.get(i);
            inventory.setItem(TAB_ROW_START + i,
                    tabButton(id, id.equals(selected), index.getOrDefault(id, List.of()).size()));
        }

        int from = clamped * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE && from + i < entries.size(); i++) {
            inventory.setItem(ITEM_SLOT_START + i, itemButton(entries.get(from + i)));
        }

        if (clamped > 0) {
            inventory.setItem(PREV_SLOT, navButton("前のページ", Material.ARROW, selected, clamped - 1));
        }
        if (clamped < pageCount - 1) {
            inventory.setItem(NEXT_SLOT, navButton("次のページ", Material.SPECTRAL_ARROW, selected, clamped + 1));
        }
        inventory.setItem(INFO_SLOT, infoButton(entries.size()));

        player.openInventory(inventory);
    }

    // ---- 並べ方 ---------------------------------------------------------------------------

    /** タブの並び順。エディタの見出し順に合わせてある。 */
    private static final List<String> TAB_ORDER =
            List.of("weapon", "armor", "tool", "catalyst", "spellbook", "thread", "key", "material", "other");

    /**
     * どのアイテムをどのタブのどこに出すかを<b>一度に全部決める</b>。
     *
     * <p>タブごとに独立して集めると<b>同じアイテムが複数のタブに出る</b>。出荷データでも
     * 「小分類には載っているが {@code itemTabs} には無い」アイテムが 100 件以上あり、
     * 小分類のタブと「未分類」の両方に現れていた（2026-08-05 のテストで検出。表示 415 件に対し
     * 実体は 296 件だった）。先に決まった場所が勝つ一本の割り当てにして、
     * 重複と取りこぼしを同時に消す。
     *
     * <p>優先順:
     * <ol>
     *   <li>{@code _editor.categories} の小分類（タブはエディタの見出し順、中は列挙順）</li>
     *   <li>小分類に無いが {@code _editor.itemTabs} でタブが決まっているもの → そのタブの「その他」</li>
     *   <li>どちらにも無い残り全部 → 「未分類」タブ</li>
     * </ol>
     */
    Map<String, List<Entry>> index() {
        Map<String, ItemTemplate> all = itemCatalog.all();
        ItemCatalogConfig.CatalogTaxonomy tax = itemCatalog.taxonomy();
        Map<String, List<Entry>> byTab = new LinkedHashMap<>();
        Set<String> placed = new LinkedHashSet<>();

        for (String tab : TAB_ORDER) {
            for (ItemCatalogConfig.CatalogTaxonomy.Category category
                    : tax.categories().getOrDefault(tab, List.of())) {
                for (String id : category.itemIds()) {
                    ItemTemplate template = all.get(id);
                    if (template != null && placed.add(id)) {
                        byTab.computeIfAbsent(tab, k -> new ArrayList<>())
                                .add(new Entry(id, template, category.label()));
                    }
                }
            }
        }
        for (Map.Entry<String, String> e : tax.tabOf().entrySet()) {
            String tab = e.getValue();
            ItemTemplate template = all.get(e.getKey());
            if (template == null || !TAB_ORDER.contains(tab) || !placed.add(e.getKey())) {
                continue;
            }
            byTab.computeIfAbsent(tab, k -> new ArrayList<>())
                    .add(new Entry(e.getKey(), template, "その他"));
        }
        for (Map.Entry<String, ItemTemplate> e : all.entrySet()) {
            if (placed.add(e.getKey())) {
                byTab.computeIfAbsent(UNSORTED_TAB, k -> new ArrayList<>())
                        .add(new Entry(e.getKey(), e.getValue(), UNSORTED_LABEL));
            }
        }
        for (String extraId : extraIds()) {
            if (extraId == null || extraId.isBlank() || itemCatalog.isDraft(extraId)) {
                continue;
            }
            if (!placed.add(extraId)) {
                continue;
            }
            String tab = tabForExtraId(extraId);
            byTab.computeIfAbsent(tab, k -> new ArrayList<>())
                    .add(new Entry(extraId, null, extraCategoryLabel(tab)));
        }
        foldUnsortedIfTabRowIsFull(byTab);
        return byTab;
    }

    /**
     * カタログに無いカスタム品のタブ。{@code thread_*} はスレッド、それ以外(Ars の素材など)は素材。
     * 未分類の末尾タブへ落とすと、タブ行が9枠しか無い画面では見つからない。
     */
    static String tabForExtraId(String id) {
        if (id != null && id.toLowerCase(Locale.ROOT).startsWith("thread_")) {
            return "thread";
        }
        return "material";
    }

    private static String extraCategoryLabel(String tab) {
        return "thread".equals(tab) ? "スレッド" : "素材";
    }

    /**
     * 名前付きタブが既に {@link #MAX_TABS} 枠埋まっているとき、未分類を 10 番目に出すと
     * タブ行から切れて画面に出ない。その場合だけ「その他」へ吸収する。
     */
    private static void foldUnsortedIfTabRowIsFull(Map<String, List<Entry>> byTab) {
        List<Entry> unsorted = byTab.get(UNSORTED_TAB);
        if (unsorted == null || unsorted.isEmpty()) {
            return;
        }
        int named = 0;
        for (String tab : TAB_ORDER) {
            if (!byTab.getOrDefault(tab, List.of()).isEmpty()) {
                named++;
            }
        }
        if (named >= MAX_TABS) {
            byTab.remove(UNSORTED_TAB);
            byTab.computeIfAbsent("other", k -> new ArrayList<>()).addAll(unsorted);
        }
    }

    private List<String> extraIds() {
        try {
            List<String> ids = extraIds.get();
            return ids == null ? List.of() : ids;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** 表示するタブ。中身が1件も無いタブは出さない。「未分類」は中身があるときだけ末尾に付く。 */
    List<String> tabs() {
        return tabs(index());
    }

    private static List<String> tabs(Map<String, List<Entry>> index) {
        List<String> out = new ArrayList<>();
        for (String tab : TAB_ORDER) {
            if (!index.getOrDefault(tab, List.of()).isEmpty()) {
                out.add(tab);
            }
        }
        if (!index.getOrDefault(UNSORTED_TAB, List.of()).isEmpty()) {
            out.add(UNSORTED_TAB);
        }
        return out;
    }

    /** そのタブに出すアイテム。 */
    List<Entry> entriesFor(String tab) {
        return index().getOrDefault(tab, List.of());
    }

    private static String labelOf(String tab) {
        if (UNSORTED_TAB.equals(tab)) {
            return UNSORTED_LABEL;
        }
        return TAB_LABELS.getOrDefault(tab, tab);
    }

    // ---- ボタン ---------------------------------------------------------------------------

    private ItemStack tabButton(String tab, boolean selected, int count) {
        Material icon = UNSORTED_TAB.equals(tab)
                ? Material.BARRIER : TAB_ICONS.getOrDefault(tab, Material.PAPER);
        ItemStack stack = new ItemStack(selected ? Material.LIME_STAINED_GLASS_PANE : icon);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text((selected ? "▶ " : "") + labelOf(tab),
                        selected ? NamedTextColor.GREEN : NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text(count + " 種", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(tabKey, PersistentDataType.STRING, tab);
        stack.setItemMeta(meta);
        return stack;
    }

    /**
     * アイテム1件分。<b>見た目は実物そのもの</b>を出す(品質0でロールした現物)ので、
     * 名前もモデルも配られる物と一致する。
     */
    private ItemStack itemButton(Entry entry) {
        ItemStack stack = createPreview(entry);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) {
            lore.addAll(meta.lore());
        }
        lore.add(Component.empty());
        lore.add(Component.text("分類: " + entry.categoryLabel(), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("ID: " + entry.id(), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("クリックで入手 / Shift+クリックで16個", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, entry.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack navButton(String label, Material icon, String tab, int page) {
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(label, NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(tabKey, PersistentDataType.STRING, tab);
        meta.getPersistentDataContainer().set(pageKey, PersistentDataType.INTEGER, page);
        stack.setItemMeta(meta);
        return stack;
    }

    private static ItemStack infoButton(int total) {
        ItemStack stack = new ItemStack(Material.BOOK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("このタブに " + total + " 種", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    // ---- 操作 -----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Session)) {
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
        // 開いたあとにゲームモードを落とされた/権限を抜かれた場合に配り続けない。
        if (!mayOpen(player)) {
            player.closeInventory();
            player.sendMessage(Component.text("カタログを開く権限がありません。", NamedTextColor.RED));
            return;
        }
        var pdc = clicked.getItemMeta().getPersistentDataContainer();

        Integer page = pdc.get(pageKey, PersistentDataType.INTEGER);
        String tab = pdc.get(tabKey, PersistentDataType.STRING);
        if (page != null && tab != null) {
            open(player, tab, page);
            return;
        }
        if (tab != null) {
            open(player, tab, 0);
            return;
        }
        String itemId = pdc.get(itemIdKey, PersistentDataType.STRING);
        if (itemId != null) {
            give(player, itemId, event.isShiftClick() ? 16 : 1);
            // 画面はそのまま残す(連続で取り出せるように)。
        }
    }

    /** ドラッグでボタンを持ち出せてしまわないように塞ぐ(他のTF GUIと同じ扱い)。 */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Session) {
            event.setCancelled(true);
        }
    }

    private ItemStack createPreview(Entry entry) {
        if (prefersExternal(entry)) {
            Optional<ItemStack> extra = extraCreate.apply(entry.id());
            if (extra.isPresent()) {
                return extra.get();
            }
        }
        if (entry.template() != null) {
            ItemStack stack = itemFactory.create(entry.template(), 0L, 0);
            CatalogIdentity.ensure(stack, itemCatalog);
            return stack;
        }
        return extraCreate.apply(entry.id()).orElseGet(() -> unnamedPreview(entry.id()));
    }

    /**
     * カタログ外、または {@code external-source:} 宣言あり。スレッドは TF の STRING では
     * 防具に挿せないので、Ars の実体を先に取る。
     */
    private static boolean prefersExternal(Entry entry) {
        return entry.template() == null || entry.template().hasExternalSource();
    }

    /** テストからプレビュー経路を叩く。 */
    ItemStack previewOf(String itemId) {
        for (List<Entry> entries : index().values()) {
            for (Entry entry : entries) {
                if (entry.id().equals(itemId)) {
                    return createPreview(entry);
                }
            }
        }
        return unnamedPreview(itemId);
    }

    private static ItemStack unnamedPreview(String id) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(id, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
        return stack;
    }

    private void give(Player player, String itemId, int amount) {
        ItemTemplate template = itemCatalog.all().get(itemId);
        ItemStack stack;
        boolean externalFirst = template == null || template.hasExternalSource();
        if (externalFirst) {
            Optional<ItemStack> extra = extraCreate.apply(itemId);
            if (extra.isPresent()) {
                stack = extra.get().clone();
            } else if (template != null) {
                stack = itemFactory.create(template, ThreadLocalRandom.current().nextLong(), 0);
            } else {
                player.sendMessage(Component.text("そのアイテムは作れません: " + itemId,
                        NamedTextColor.RED));
                return;
            }
        } else {
            // 個体差(rollSeed)は配るたびに引き直す。同じ物を並べたいときのために品質は0固定。
            stack = itemFactory.create(template, ThreadLocalRandom.current().nextLong(), 0);
        }
        stampGiveIdentity(stack, template, player);
        stack.setAmount(Math.max(1, Math.min(amount, stack.getMaxStackSize())));

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    /**
     * Ars 実体を先に取る経路は {@link ItemFactory#create} を通らないので、catalog の
     * {@code bind-type} と SOULBOUND の所有者をここで焼く。プレビューには付けない。
     */
    void stampGiveIdentity(ItemStack stack, ItemTemplate template, Player player) {
        CatalogIdentity.ensure(stack, itemCatalog);
        if (template != null) {
            stack.editMeta(meta -> {
                ItemData data = ItemData.of(meta);
                data.setCatalogId(template.id());
                data.setBindType(template.bindType());
                if (template.bindType().autoStampsOwner() && player != null) {
                    data.setOwner(player.getUniqueId());
                }
            });
        }
        if (stack.hasItemMeta() && PickupQualityListener.hasArsThreadMarker(stack.getItemMeta())) {
            PickupQualityListener.defaultArsThreadLoreRefresh(stack);
        }
        itemFactory.appendOwnerLoreIfMissing(stack);
    }

    /** GUI 内の並び1件分。{@code template} が null ならカタログ外のカスタム品。 */
    record Entry(String id, ItemTemplate template, String categoryLabel) {
    }

    /**
     * この画面であることの目印。<b>タブ/ページを持たせていない</b> —— 状態はボタン自身の PDC が
     * 運ぶので、Session に二重で置くと再描画のたびにどちらが正か曖昧になる。
     */
    private static final class Session implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
