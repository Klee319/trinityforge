package com.trinityforge.progression;

import com.trinityforge.config.domains.CollectionConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
