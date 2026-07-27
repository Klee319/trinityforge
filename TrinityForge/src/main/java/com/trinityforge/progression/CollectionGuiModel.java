package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure (Bukkit-free) grouping/paging logic for the {@code /tf collection} GUI
 * (2026-07-23-stat-gate-overhaul §6.3): which category tabs exist, which entries fall under a tab,
 * and how entries split across pages. The Inventory-building/click-handling glue is
 * {@code CollectionGui}; kept separate so this shape is unit-testable without a running server.
 */
public final class CollectionGuiModel {

    public enum Domain { ITEM, MOB }

    /** The synthetic tab id every domain gets for entries not covered by any configured category. */
    public static final String OTHER_TAB_ID = "_other";

    public record Tab(Domain domain, String id, String displayName, int order, List<String> entries) {
    }

    /** One row in a tab's entry list: {@code entryId} is the full {@code item:x}/{@code mob:x} id. */
    public record GuiEntry(String entryId, boolean discovered, long epochMillis, int qualityPt) {
    }

    private CollectionGuiModel() {
    }

    /**
     * Builds the tab list: configured categories (order asc) for each domain, plus a trailing
     * "その他" tab per domain containing every entry the player has discovered that isn't listed in
     * any configured category for that domain. A domain contributes no tabs at all when it has
     * neither configured categories nor any uncategorized discovered entries.
     */
    public static List<Tab> buildTabs(CollectionConfig config, List<CollectionRecord> owned) {
        List<Tab> tabs = new ArrayList<>();
        tabs.addAll(domainTabs(Domain.ITEM, config.itemCategories(), owned, "item:"));
        tabs.addAll(domainTabs(Domain.MOB, config.mobCategories(), owned, "mob:"));
        return List.copyOf(tabs);
    }

    private static List<Tab> domainTabs(Domain domain, List<CollectionConfig.Category> categories,
                                         List<CollectionRecord> owned, String prefix) {
        List<Tab> tabs = new ArrayList<>();
        Set<String> categorized = new LinkedHashSet<>();
        for (CollectionConfig.Category category : categories) {
            List<String> entries = category.entries().stream().map(bare -> prefix + bare).toList();
            categorized.addAll(entries);
            tabs.add(new Tab(domain, category.id(), category.displayName(), category.order(), entries));
        }
        List<String> uncategorized = new ArrayList<>();
        for (CollectionRecord record : owned) {
            if (record.id().startsWith(prefix) && !categorized.contains(record.id())) {
                uncategorized.add(record.id());
            }
        }
        if (!categories.isEmpty() || !uncategorized.isEmpty()) {
            tabs.add(new Tab(domain, OTHER_TAB_ID, "その他", Integer.MAX_VALUE, List.copyOf(uncategorized)));
        }
        return tabs;
    }

    /** Entries for {@code tab}, in declared order, each annotated with the player's discovery state. */
    public static List<GuiEntry> entriesFor(Tab tab, List<CollectionRecord> owned) {
        Map<String, CollectionRecord> ownedById = new LinkedHashMap<>();
        for (CollectionRecord record : owned) {
            ownedById.put(record.id(), record);
        }
        List<GuiEntry> result = new ArrayList<>();
        for (String entryId : tab.entries()) {
            CollectionRecord record = ownedById.get(entryId);
            if (record != null) {
                result.add(new GuiEntry(entryId, true, record.epochMillis(), record.maxQualityPt()));
            } else {
                result.add(new GuiEntry(entryId, false, 0L, 0));
            }
        }
        return result;
    }

    /**
     * 並べ替え軸 (2026-07-27)。{@code DEFAULT} は config の宣言順(=カテゴリの {@code entries} 順)で、
     * 従来の唯一の並びと完全に一致する。
     *
     * <p>{@code KIND}/{@code LEVEL} は「ステータス(items/catalog.yml)に設定された使用スキル種別」と
     * 「使用可能レベル」を見る。どちらも持たないエントリ(素の Material のカタログ品・モブ)は
     * それぞれ「素材(Material)名」「レベル0」として扱い、同値の中では名前順で決着させる
     * (完全な決定性を持たせ、ページ送りで並びが揺れないようにするため)。
     */
    public enum SortMode {
        DEFAULT("既定(カテゴリ順)"),
        NAME("名前順"),
        KIND("種別順(スキル→素材)"),
        LEVEL("使用可能レベル順");

        private final String label;

        SortMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public SortMode next() {
            SortMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /** 解放状態の絞り込み (2026-07-27)。図鑑では「解放済み=発見済み」。 */
    public enum FilterMode {
        ALL("すべて"),
        DISCOVERED("発見済みのみ"),
        UNDISCOVERED("未発見のみ");

        private final String label;

        FilterMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public FilterMode next() {
            FilterMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /**
     * 1エントリの並べ替えキー。
     *
     * @param name  表示名のプレーンテキスト(小文字化済みを想定)
     * @param kind  種別ラベル。items/catalog.yml の {@code use-skill}、無ければ Material 名
     * @param level 使用可能レベル({@code use-level-requirement}、無指定/非対象は 0)
     */
    public record EntrySortKey(String name, String kind, int level) {
        public EntrySortKey {
            name = name == null ? "" : name;
            kind = kind == null ? "" : kind;
        }
    }

    /** ワイルドカード検索と並べ替え・絞り込みをまとめて適用する。 */
    public static List<GuiEntry> arrange(List<GuiEntry> entries, SortMode sort, FilterMode filter,
                                          String namePattern, Function<String, EntrySortKey> keys) {
        Function<String, EntrySortKey> keyOf = keys == null
                ? id -> new EntrySortKey(id, "", 0)
                : keys;
        List<GuiEntry> working = new ArrayList<>();
        for (GuiEntry entry : entries) {
            if (filter == FilterMode.DISCOVERED && !entry.discovered()) continue;
            if (filter == FilterMode.UNDISCOVERED && entry.discovered()) continue;
            // 未発見エントリは名前を伏せている(？？？)ため、検索で当てられると図鑑の意味が壊れる。
            // 検索の対象は発見済みエントリだけに限る。
            if (!matchesSearch(entry, namePattern, keyOf)) continue;
            working.add(entry);
        }
        if (sort == null || sort == SortMode.DEFAULT) {
            return List.copyOf(working);
        }
        Comparator<GuiEntry> comparator = switch (sort) {
            case NAME -> Comparator.comparing(e -> keyOf.apply(e.entryId()).name());
            case KIND -> Comparator.<GuiEntry, String>comparing(e -> keyOf.apply(e.entryId()).kind())
                    .thenComparing(e -> keyOf.apply(e.entryId()).name());
            case LEVEL -> Comparator.<GuiEntry>comparingInt(e -> keyOf.apply(e.entryId()).level())
                    .thenComparing(e -> keyOf.apply(e.entryId()).name());
            case DEFAULT -> null;
        };
        if (comparator != null) {
            working.sort(comparator.thenComparing(GuiEntry::entryId));
        }
        return List.copyOf(working);
    }

    private static boolean matchesSearch(GuiEntry entry, String namePattern,
                                          Function<String, EntrySortKey> keyOf) {
        if (namePattern == null || namePattern.isBlank()) {
            return true;
        }
        if (!entry.discovered()) {
            return false;
        }
        return GlobMatcher.matches(namePattern, keyOf.apply(entry.entryId()).name());
    }

    /** Splits {@code entries} into pages of at most {@code pageSize} (a non-positive size yields one page). */
    public static List<List<GuiEntry>> paginate(List<GuiEntry> entries, int pageSize) {
        List<List<GuiEntry>> pages = new ArrayList<>();
        if (pageSize <= 0) {
            pages.add(List.copyOf(entries));
            return pages;
        }
        for (int i = 0; i < entries.size(); i += pageSize) {
            pages.add(List.copyOf(entries.subList(i, Math.min(i + pageSize, entries.size()))));
        }
        if (pages.isEmpty()) {
            pages.add(List.of());
        }
        return pages;
    }
}
