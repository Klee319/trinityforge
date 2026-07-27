package com.trinityforge.stats;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Shared parser for the "drop-tables" schema (2026-07-23 stat-gate-overhaul §4): a category has a
 * display name, an optional trigger-chance-percent (block-break gimmicks only — a fishing group's
 * categories have no per-category trigger; the group itself is chosen by the group-ratio roll), and a
 * list of weighted entries (item id string + weight + amount). {@link ConfigurationSection} is used only
 * as a generic nested-map reader (no {@code Plugin}/{@code YamlConfiguration} coupling), so this one
 * parser is shared by every gimmick config (mining/woodcutting/digging/fishing) instead of duplicating
 * the walk logic four times. Fail-safe: any malformed category/entry is warned about and skipped, never
 * thrown — matches the raw-YAML-loader style every other {@code stats/*.yml} domain already uses.
 */
public final class DropTableConfig {

    private DropTableConfig() {
    }

    /** One weighted prize: {@code item} resolves via {@code CrossPluginItemResolver} (catalog/Ars/Material). */
    public record Entry(String item, int weight, int amount) {
    }

    /**
     * One drop-table category: display name + (block gimmicks only) trigger roll + weighted entries.
     *
     * @param scrapExempt 2026-07-23 verifier指摘⑥: {@code true} のとき、このカテゴリから抽選された結果は
     *                    {@code junk-to-scrap} の一律スクラップ差し替え対象から除外される（例: fishing-gimmick.yml
     *                    の {@code ocean_thread} — junk-to-scrap保持者でも本来の釣果を入手可能にするため）。
     *                    既定値 {@code false}（従来通りスクラップ差し替え対象）。
     */
    public record Category(String id, String displayName, double triggerChancePercent, List<Entry> entries,
                            boolean scrapExempt) {
    }

    /**
     * Parses a {@code drop-tables.categories} (or a fishing group's {@code categories}) section into an
     * id-ordered, immutable map. {@code requireTrigger} controls whether {@code trigger-chance-percent} is
     * read (block gimmicks) or left at {@code 0} (fishing groups). A category with zero valid entries after
     * filtering is dropped entirely (warn + skip) rather than kept empty, since an empty category could
     * never contribute a draw anyway.
     */
    public static Map<String, Category> parseCategories(ConfigurationSection categoriesSection,
                                                          boolean requireTrigger, String pathLabel, Logger log) {
        Map<String, Category> parsed = new LinkedHashMap<>();
        if (categoriesSection == null) {
            return Map.of();
        }
        for (String catId : categoriesSection.getKeys(false)) {
            if (catId == null || catId.isBlank()) {
                continue;
            }
            ConfigurationSection catSection = categoriesSection.getConfigurationSection(catId);
            if (catSection == null) {
                log.warning("[" + pathLabel + "] category '" + catId + "' is not a mapping; skipped");
                continue;
            }
            String displayName = catSection.getString("display-name", catId);
            double trigger = requireTrigger
                    ? clampPercent(catSection.getDouble("trigger-chance-percent", 0.0), pathLabel, catId, log)
                    : 0.0;
            List<Entry> entries = parseEntries(catSection.getMapList("entries"), pathLabel, catId, log);
            if (entries.isEmpty()) {
                log.warning("[" + pathLabel + "] category '" + catId + "' has no valid entries; skipped");
                continue;
            }
            boolean scrapExempt = catSection.getBoolean("scrap-exempt", false);
            parsed.put(catId, new Category(catId, displayName, trigger, entries, scrapExempt));
        }
        return Map.copyOf(parsed);
    }

    private static List<Entry> parseEntries(List<?> rawEntries, String pathLabel, String catId, Logger log) {
        List<Entry> entries = new ArrayList<>();
        if (rawEntries == null) {
            return entries;
        }
        for (Object raw : rawEntries) {
            if (!(raw instanceof Map<?, ?> map)) {
                log.warning("[" + pathLabel + "] '" + catId + "' has a non-mapping entry; skipped");
                continue;
            }
            Object itemRaw = map.get("item");
            String item = itemRaw == null ? null : String.valueOf(itemRaw).trim();
            if (item == null || item.isBlank()) {
                log.warning("[" + pathLabel + "] '" + catId + "' has an entry with a blank 'item'; skipped");
                continue;
            }
            Integer weight = toPositiveInt(map.get("weight"));
            if (weight == null || weight < 1) {
                log.warning("[" + pathLabel + "] '" + catId + "' entry '" + item + "' has weight < 1; skipped");
                continue;
            }
            Integer amount = toPositiveInt(map.get("amount"));
            if (amount == null) {
                amount = 1;
            } else if (amount < 1) {
                log.warning("[" + pathLabel + "] '" + catId + "' entry '" + item + "' has amount < 1; skipped");
                continue;
            }
            entries.add(new Entry(item, weight, amount));
        }
        return entries;
    }

    private static Integer toPositiveInt(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double clampPercent(double raw, String pathLabel, String catId, Logger log) {
        if (!Double.isFinite(raw)) {
            log.warning("[" + pathLabel + "] '" + catId + "' trigger-chance-percent is not finite; using 0");
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(raw, 100.0));
        if (clamped != raw) {
            log.warning("[" + pathLabel + "] '" + catId + "' trigger-chance-percent clamped to " + clamped);
        }
        return clamped;
    }
}
