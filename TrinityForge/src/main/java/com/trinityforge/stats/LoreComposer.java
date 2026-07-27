package com.trinityforge.stats;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds categorized item stat lore with quality score header and stat-source coloring.
 * Display order within each category follows {@link StatDisplaySpec#order()}.
 *
 * <p>Category sections are separated by a {@code ====} line whose length adapts to the widest lore
 * line in the tooltip (CJK-aware width heuristic), so the rule visually spans the item popup.
 *
 * <p>乗算モードのステ ({@link LoreComposeRequest#multipliers()}) は同カテゴリ内で加算行の直後に
 * {@code x1.2} 形式 (符号=x, 単位なし) で描画され、レイヤ順は {@code stats/lore.yml multiplier-layers}
 * の並び順に従う。
 */
public final class LoreComposer {

    private static final int SEPARATOR_MIN_UNITS = 8;
    private static final int SEPARATOR_MAX_UNITS = 60;

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    /** Marker inserted where a separator belongs; replaced by the width-adapted rule in a post-pass. */
    private static final Component SEPARATOR_MARKER = Component.text("\u0000TF_SEPARATOR\u0000");

    /** Back-compat overload: use-requirement lore line falls back to {@link LoreConfig.BindLore#defaults()}. */
    public List<Component> compose(LoreComposeRequest request,
                                   Map<String, StatDisplaySpec> displayTable,
                                   LoreLayout layout) {
        return compose(request, displayTable, layout, com.trinityforge.config.domains.LoreConfig.BindLore.defaults());
    }

    public List<Component> compose(LoreComposeRequest request,
                                   Map<String, StatDisplaySpec> displayTable,
                                   LoreLayout layout,
                                   com.trinityforge.config.domains.LoreConfig.BindLore bind) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(displayTable, "displayTable");
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(bind, "bind");

        Map<String, StatDisplaySpec> canonicalTable = canonicalizeTable(displayTable);
        Map<String, Double> displayStats = new LinkedHashMap<>(canonicalizeStats(request.stats()));
        Map<String, StatSource> displaySources = new LinkedHashMap<>(canonicalizeSources(request.statSources()));
        Set<String> forceShow = canonicalizeKeys(request.forceShowKeys());
        Set<String> chanceKeys = canonicalizeKeys(request.chanceKeys());
        Map<String, Map<String, Double>> multipliers = canonicalizeMultipliers(request.multipliers());

        List<Component> lore = new ArrayList<>();
        layout.header().forEach(line -> lore.add(deserialize(line)));

        if (!request.qualityTierName().isBlank()) {
            // 品質行は stats/lore.yml layout.score-line-template で描画する。プレースホルダ:
            //   <tier>      = ティア色付きの【ティア名】(quality-tiers.yml の name/color)。
            //                 color タグは gradient も許容するため閉じずに置く (placeholder内で完結)。
            //   <tier-name> = 色なしのティア名
            //   <score>     = 品質スコア値
            String color = request.qualityTierColor();
            String open = color.isBlank() ? "<gray>" : "<" + color + ">";
            lore.add(noItalic(miniMessage.deserialize(layout.scoreLineTemplate(),
                    Placeholder.parsed("tier", open + "【" + request.qualityTierName() + "】"),
                    Placeholder.unparsed("tier-name", request.qualityTierName()),
                    Placeholder.unparsed("score", String.valueOf(request.qualityScore())))));
        }

        for (StatCategory category : new StatCategory[]{StatCategory.ATTACK, StatCategory.DEFENSE,
                StatCategory.CRAFT, StatCategory.GATHERING, StatCategory.UTILITY,
                StatCategory.ARS, StatCategory.OTHER}) {
            appendSection(lore, displayStats, canonicalTable, displaySources, forceShow, chanceKeys,
                    multipliers, layout, category);
        }

        // 使用可能レベル行: テンプレートは stats/lore.yml の bind.use-requirement-line
        // (LoreConfig.BindLore) 由来。プレースホルダ: <level>=必要Lv, <skill>=スキル表示名。
        boolean hasUseGate = bind.showUseRequirement() && !request.useSkillDisplayName().isBlank();
        if (hasUseGate) {
            lore.add(SEPARATOR_MARKER);
            lore.add(noItalic(miniMessage.deserialize(bind.useRequirementLine(),
                    Placeholder.unparsed("level", String.valueOf(request.useLevelRequirement())),
                    Placeholder.unparsed("skill", request.useSkillDisplayName()))));
        }

        layout.footer().forEach(line -> lore.add(deserialize(line)));
        return resolveSeparators(lore);
    }

    public List<Component> compose(Map<String, Double> stats,
                                   Map<String, StatDisplaySpec> displayTable,
                                   LoreLayout layout) {
        return compose(new LoreComposeRequest(stats, Map.of(), "", 0, null, null, "", 0),
                displayTable, layout);
    }

    private void appendSection(List<Component> lore, Map<String, Double> stats,
                               Map<String, StatDisplaySpec> table, Map<String, StatSource> sources,
                               Set<String> forceShow, Set<String> chanceKeys,
                               Map<String, Map<String, Double>> multipliers,
                               LoreLayout layout, StatCategory category) {
        List<StatDisplaySpec> specs = specsInCategory(stats, table, category, forceShow, multipliers);
        List<Component> lines = new ArrayList<>();
        for (StatDisplaySpec spec : specs) {
            String key = StatKeys.canonical(spec.statKey());
            Double value = stats.get(key);
            if (value != null && additiveVisible(spec, value, forceShow.contains(key))) {
                StatSource source = sources.getOrDefault(key, StatSource.FIXED);
                lines.add(line(spec, value, layout, source, chanceKeys.contains(key)));
            }
            appendMultiplierLines(lines, spec, key, multipliers, layout);
        }
        if (lines.isEmpty()) {
            return;
        }
        lore.add(SEPARATOR_MARKER);
        lore.addAll(lines);
    }

    private static boolean additiveVisible(StatDisplaySpec spec, double value, boolean force) {
        return force || !spec.hideWhenZero() || !spec.format().roundsToZero(value, spec.decimals());
    }

    /**
     * Specs of {@code category} that have either an additive value or a multiplier entry, ordered by
     * {@code order} then key — the multiplier-only case still needs its stat line(s) rendered.
     */
    private static List<StatDisplaySpec> specsInCategory(Map<String, Double> stats,
                                                         Map<String, StatDisplaySpec> table,
                                                         StatCategory category,
                                                         Set<String> forceShow,
                                                         Map<String, Map<String, Double>> multipliers) {
        Set<String> multiplied = new LinkedHashSet<>();
        multipliers.values().forEach(m -> multiplied.addAll(m.keySet()));
        List<StatDisplaySpec> visible = new ArrayList<>();
        for (StatDisplaySpec spec : table.values()) {
            if (spec.category() != category) {
                continue;
            }
            String key = StatKeys.canonical(spec.statKey());
            if (stats.get(key) == null && !multiplied.contains(key)) {
                continue;
            }
            visible.add(spec);
        }
        visible.sort(Comparator.comparingInt(StatDisplaySpec::order).thenComparing(StatDisplaySpec::statKey));
        return visible;
    }

    /**
     * 乗算モード行: {@code stats/lore.yml multiplier-layers} の並び順に、このステのレイヤ別倍率を
     * {@code x1.2} (符号=x・単位なし・小数2桁) で描画。レイヤ名があれば行末に添える。
     * lore.ymlに未登録のレイヤ(editor未同期など)は定義済みレイヤの後ろに出す。
     */
    private void appendMultiplierLines(List<Component> lines, StatDisplaySpec spec, String key,
                                       Map<String, Map<String, Double>> multipliers, LoreLayout layout) {
        if (multipliers.isEmpty()) {
            return;
        }
        List<String> orderedLayers = new ArrayList<>();
        Map<String, String> layerNames = new LinkedHashMap<>();
        for (LoreLayout.MultiplierLayer layer : layout.multiplierLayers()) {
            orderedLayers.add(layer.id());
            layerNames.put(layer.id(), layer.name());
        }
        for (String layerId : multipliers.keySet()) {
            if (!orderedLayers.contains(layerId)) {
                orderedLayers.add(layerId);
            }
        }
        for (String layerId : orderedLayers) {
            Map<String, Double> layerValues = multipliers.get(layerId);
            Double multiplier = layerValues == null ? null : layerValues.get(key);
            if (multiplier == null) {
                continue;
            }
            String color = multiplier < 1.0
                    ? layout.colors().fixedNegative()
                    : layout.colors().fixedPositive();
            String text = "x" + trimTrailingZeros(String.format(java.util.Locale.ROOT, "%.2f", multiplier));
            String layerName = layerNames.getOrDefault(layerId, "");
            String coloredValue = "<color:" + color + ">" + text + "</color>"
                    + (layerName.isBlank() ? "" : " <dark_gray>[" + layerName + "]</dark_gray>");
            lines.add(noItalic(miniMessage.deserialize(layout.lineTemplate(),
                    Placeholder.unparsed("icon", spec.icon()),
                    Placeholder.parsed("value", coloredValue),
                    Placeholder.unparsed("name", spec.displayName()))));
        }
    }

    private static String trimTrailingZeros(String text) {
        if (!text.contains(".")) {
            return text;
        }
        String trimmed = text.replaceAll("0+$", "");
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private Component line(StatDisplaySpec spec, double value, LoreLayout layout, StatSource source,
                           boolean hasChance) {
        String text = spec.renderValue(value);
        String valueColor = layout.colors().colorFor(value, source == StatSource.RANDOM, hasChance);
        String coloredValue = "<color:" + valueColor + ">" + text + "</color>";
        return noItalic(miniMessage.deserialize(layout.lineTemplate(),
                Placeholder.unparsed("icon", spec.icon()),
                Placeholder.parsed("value", coloredValue),
                Placeholder.unparsed("name", spec.displayName())));
    }

    /**
     * Replaces separator markers with a {@code ====} rule sized to the widest lore line so the rule
     * spans the item tooltip WITHOUT widening it. Width heuristic: vanilla-font pixel widths
     * (ASCII ≒ 6px, 細い記号は実幅, CJK/全角 ≒ 9px); one {@code =} = 6px. 端数は切り捨てて
     * セパレータが最長行より長くならないようにする (旧実装は CJK=2units='=2本' 換算で
     * 実幅より約33%長くなり、ツールチップ横幅がセパレータで不要に伸びていた)。
     */
    private List<Component> resolveSeparators(List<Component> lore) {
        int maxPixels = 0;
        for (Component line : lore) {
            if (line == SEPARATOR_MARKER) {
                continue;
            }
            maxPixels = Math.max(maxPixels, pixelWidth(plain.serialize(line)));
        }
        int units = Math.max(SEPARATOR_MIN_UNITS,
                Math.min(SEPARATOR_MAX_UNITS, maxPixels / EQUALS_PIXEL_WIDTH));
        Component separator = deserialize("<dark_gray>" + "=".repeat(units) + "</dark_gray>");
        List<Component> resolved = new ArrayList<>(lore.size());
        for (Component line : lore) {
            resolved.add(line == SEPARATOR_MARKER ? separator : line);
        }
        return List.copyOf(resolved);
    }

    /** '=' のバニラフォント幅 (5px + 字間1px)。 */
    private static final int EQUALS_PIXEL_WIDTH = 6;

    private static int pixelWidth(String text) {
        int px = 0;
        for (int i = 0; i < text.length(); i++) {
            px += charPixelWidth(text.charAt(i));
        }
        return px;
    }

    /**
     * バニラ(default)フォントのグリフ幅の近似 (グリフ幅 + 字間1px)。CJK/全角は 8+1=9px。
     * ASCII は大半が 5+1=6px で、頻出の細い字だけ実幅に合わせる。
     */
    private static int charPixelWidth(char c) {
        if (c > 0xFF) {
            return 9;
        }
        return switch (c) {
            case 'i', '!', ',', '.', ':', ';', '\'', '|' -> 2;
            case 'l' -> 3;
            case ' ', 't', 'I', '[', ']' -> 4;
            case 'f', 'k', '(', ')', '{', '}', '<', '>' -> 5;
            default -> 6;
        };
    }

    private static Map<String, Double> canonicalizeStats(Map<String, Double> raw) {
        Map<String, Double> canonical = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((key, value) -> canonical.put(StatKeys.canonical(key), value));
        }
        return canonical;
    }

    private static Map<String, StatDisplaySpec> canonicalizeTable(Map<String, StatDisplaySpec> raw) {
        Map<String, StatDisplaySpec> canonical = new LinkedHashMap<>();
        raw.forEach((key, spec) -> canonical.put(StatKeys.canonical(key), spec));
        return canonical;
    }

    private static Map<String, StatSource> canonicalizeSources(Map<String, StatSource> raw) {
        Map<String, StatSource> canonical = new LinkedHashMap<>();
        raw.forEach((key, source) -> canonical.put(StatKeys.canonical(key), source));
        return canonical;
    }

    private static Map<String, Map<String, Double>> canonicalizeMultipliers(
            Map<String, Map<String, Double>> raw) {
        Map<String, Map<String, Double>> canonical = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((layer, values) -> {
                Map<String, Double> canonicalValues = new LinkedHashMap<>();
                values.forEach((key, v) -> canonicalValues.put(StatKeys.canonical(key), v));
                canonical.put(layer, canonicalValues);
            });
        }
        return canonical;
    }

    private static Set<String> canonicalizeKeys(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return raw.stream().map(StatKeys::canonical).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Component deserialize(String line) {
        return noItalic(miniMessage.deserialize(line));
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
