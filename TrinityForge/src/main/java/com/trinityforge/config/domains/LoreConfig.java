package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.stats.LoreColorRules;
import com.trinityforge.stats.LoreLayout;
import com.trinityforge.stats.LoreValueFormat;
import com.trinityforge.stats.StatAppliesTo;
import com.trinityforge.stats.StatBound;
import com.trinityforge.stats.StatCategory;
import com.trinityforge.stats.StatCategoryInference;
import com.trinityforge.stats.StatDisplaySpec;
import com.trinityforge.stats.StatLimits;
import com.trinityforge.stats.StatSourceScope;
import com.trinityforge.stats.StatStacking;
import com.trinityforge.stats.StatTrigger;
import com.trinityforge.stats.StatTriggerWhen;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/lore.yml}: the per-stat display table + shared layout that turn rolled
 * stats into ValhallaMMO-styled item lore (lore-consistency concern). The {@code stats:} section is
 * open-ended (arbitrary stat keys) so a new stat becomes visible by config alone, no code change.
 * Malformed entries are skipped with a warning; the rest still load. The layout + table are held in
 * one {@link Snapshot} swapped atomically so {@code /trinityforge reload} can never expose a
 * mismatched layout/table pair and re-styles existing items live.
 */
public final class LoreConfig implements LoadableConfig {

    public static final String PATH = "stats/lore.yml";
    private static final String LAYOUT = "layout";
    private static final String STATS = "stats";
    private static final String BIND = "bind";
    private static final String MULTIPLIER_LAYERS = "multiplier-layers";

    private volatile Snapshot snapshot = new Snapshot(LoreLayout.defaults(), Map.of(), BindLore.defaults());

    /** The layout + display table as one atomically-swapped unit; read this once per assembly. */
    public Snapshot snapshot() {
        return snapshot;
    }

    public Map<String, StatDisplaySpec> displayTable() {
        return snapshot.displayTable();
    }

    public LoreLayout layout() {
        return snapshot.layout();
    }

    public String resourcePath() {
        return PATH;
    }

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }

        // loadConfiguration(File) は構文エラーを握り潰して空configを返すため自前でload()する。
        // 構文エラー時は直前に成功ロード済みのsnapshot(初回失敗時はデフォルトlayout+空table)を
        // 維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        LoreLayout parsedLayout = parseLayout(yaml.getConfigurationSection(LAYOUT),
                parseMultiplierLayers(yaml.getList(MULTIPLIER_LAYERS)), log);
        BindLore parsedBind = parseBind(yaml.getConfigurationSection(BIND));
        Map<String, StatDisplaySpec> parsed = new LinkedHashMap<>();
        int skipped = 0;
        ConfigurationSection stats = yaml.getConfigurationSection(STATS);
        if (stats != null) {
            for (String key : stats.getKeys(false)) {
                ConfigurationSection entry = stats.getConfigurationSection(key);
                if (entry == null) {
                    log.warning("[" + PATH + "] stat '" + key + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                try {
                    parsed.put(key, new StatDisplaySpec(
                            key,
                            entry.getString("name", key),
                            entry.getString("icon", ""),
                            parseFormat(entry.getString("format")),
                            entry.getInt("decimals", 1),
                            entry.getInt("order", 100),
                            entry.getBoolean("show-sign", true),
                            entry.getBoolean("hide-when-zero", true),
                            entry.getString("unit", ""),
                            entry.contains("category")
                                    ? StatCategory.parse(entry.getString("category"))
                                    : StatCategoryInference.infer(key),
                            parseTrigger(entry.getConfigurationSection("trigger")),
                            parseLimits(entry.getConfigurationSection("limits")),
                            // 内部値→表示値の換算係数。単位(unit)と内部値の桁が違うステだけが
                            // 1.0 以外を持つ(2026-07-31: ノックバック2キー)。0以下/非有限は
                            // StatDisplaySpec が IllegalArgumentException を投げ、この行が skipped になる。
                            entry.getDouble("display-scale", StatDisplaySpec.DEFAULT_DISPLAY_SCALE)));
                } catch (IllegalArgumentException ex) {
                    log.warning("[" + PATH + "] stat '" + key + "' invalid (" + ex.getMessage() + "); skipped");
                    skipped++;
                }
            }
        }

        this.snapshot = new Snapshot(parsedLayout, Map.copyOf(parsed), parsedBind);

        if (skipped > 0) {
            log.warning("[" + PATH + "] loaded " + parsed.size() + " stat display(s), " + skipped + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + parsed.size() + " stat display(s) OK");
        return true;
    }

    // Package-private static (not private) so the color/format/layout parsing is unit-testable
    // without a live Plugin, matching the parse-helper convention used by the other config loaders.
    static LoreLayout parseLayout(ConfigurationSection section, Logger log) {
        return parseLayout(section, java.util.List.of(), log);
    }

    static LoreLayout parseLayout(ConfigurationSection section,
                                  java.util.List<LoreLayout.MultiplierLayer> layers, Logger log) {
        LoreLayout def = LoreLayout.defaults();
        if (section == null) {
            return new LoreLayout(def.header(), def.footer(), def.lineTemplate(),
                    def.positiveColor(), def.negativeColor(), def.colors(), layers);
        }
        String positive = validColor(section.getString("positive-color", def.positiveColor()),
                def.positiveColor(), "positive-color", log);
        String negative = validColor(section.getString("negative-color", def.negativeColor()),
                def.negativeColor(), "negative-color", log);
        try {
            return new LoreLayout(
                    section.getStringList("header"),
                    section.getStringList("footer"),
                    section.getString("line-template", def.lineTemplate()),
                    section.getString("score-line-template", def.scoreLineTemplate()),
                    positive,
                    negative,
                    parseColorRules(section.getConfigurationSection("colors"), negative, log),
                    layers);
        } catch (IllegalArgumentException ex) {
            log.warning("[" + PATH + "] layout invalid (" + ex.getMessage() + "); defaults applied");
            // 乗算レイヤはlayoutセクション外(multiplier-layers)由来なので、layoutが壊れていても捨てない
            // (捨てるとレイヤ名がID表示に化ける)。色はデフォルトへフォールバック。
            return new LoreLayout(def.header(), def.footer(), def.lineTemplate(),
                    def.positiveColor(), def.negativeColor(), def.colors(), layers);
        }
    }

    /**
     * Parses {@code layout.colors.{fixed,roll}.{positive,negative,chance-positive,chance-negative}}.
     * Absent section → defaults matching the historical hardcode (fixed=white / roll=green /
     * negative=layout.negative-color). Chance colors are optional「高度なオプション」; blank means
     * "fall back to the non-chance color".
     */
    static LoreColorRules parseColorRules(ConfigurationSection colors, String legacyNegative, Logger log) {
        LoreColorRules def = new LoreColorRules("white", legacyNegative, "green", legacyNegative,
                "", "", "", "");
        if (colors == null) {
            return def;
        }
        ConfigurationSection fixed = colors.getConfigurationSection("fixed");
        ConfigurationSection roll = colors.getConfigurationSection("roll");
        return new LoreColorRules(
                colorOr(fixed, "positive", def.fixedPositive(), log),
                colorOr(fixed, "negative", def.fixedNegative(), log),
                colorOr(roll, "positive", def.rollPositive(), log),
                colorOr(roll, "negative", def.rollNegative(), log),
                chanceColorOr(fixed, "chance-positive", log),
                chanceColorOr(fixed, "chance-negative", log),
                chanceColorOr(roll, "chance-positive", log),
                chanceColorOr(roll, "chance-negative", log));
    }

    private static String colorOr(ConfigurationSection section, String key, String fallback, Logger log) {
        String raw = section == null ? null : section.getString(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return validColor(raw, fallback, "colors." + key, log);
    }

    /** Chance colors: absent/blank = disabled (empty string), invalid = warn + disabled. */
    private static String chanceColorOr(ConfigurationSection section, String key, Logger log) {
        String raw = section == null ? null : section.getString(key);
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return validColor(raw, "", "colors." + key, log);
    }

    /**
     * Parses the top-level {@code multiplier-layers:} list ({@code [{id, name}, ...]}, order =
     * lore/セレクトメニューの表示順). Malformed entries are ignored — editor-authored metadata.
     */
    static java.util.List<LoreLayout.MultiplierLayer> parseMultiplierLayers(java.util.List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<LoreLayout.MultiplierLayer> layers = new java.util.ArrayList<>();
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Object id = map.get("id");
            if (id == null || String.valueOf(id).isBlank()) {
                continue;
            }
            Object name = map.get("name");
            Object stat = map.get("stat");
            layers.add(new LoreLayout.MultiplierLayer(String.valueOf(id).trim(),
                    name == null ? "" : String.valueOf(name),
                    stat == null ? "" : String.valueOf(stat)));
        }
        return java.util.List.copyOf(layers);
    }

    /** Accepts a MiniMessage named color or {@code #rrggbb}; otherwise warns and uses the default. */
    static String validColor(String raw, String fallback, String field, Logger log) {
        if (raw != null && (NamedTextColor.NAMES.value(raw.toLowerCase(Locale.ROOT)) != null
                || TextColor.fromHexString(raw) != null)) {
            return raw;
        }
        log.warning("[" + PATH + "] " + field + " '" + raw + "' is not a valid color; using '" + fallback + "'");
        return fallback;
    }

    static LoreValueFormat parseFormat(String raw) {
        if (raw == null || raw.isBlank()) {
            return LoreValueFormat.FLAT;
        }
        return LoreValueFormat.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Parses the optional {@code stats.<key>.trigger:} block (段階1 宣言). Absent section → {@code null}
     * (未宣言、許可リストで管理する — 必須にはしない)。An unknown enum member throws
     * {@link IllegalArgumentException}, which the caller (the per-stat try/catch in {@link #load}) turns
     * into a skip + warning, matching the same fail-soft-per-entry convention as the rest of this loader.
     */
    static StatTrigger parseTrigger(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        StatTriggerWhen when = StatTriggerWhen.parse(section.getString("when", ""));
        StatSourceScope sources = StatSourceScope.parse(section.getString("sources", "ALL"));
        List<String> appliesRaw = section.getStringList("applies-to");
        if (appliesRaw.isEmpty()) {
            throw new IllegalArgumentException("trigger.applies-to must not be empty");
        }
        Set<StatAppliesTo> appliesTo = new LinkedHashSet<>();
        for (String raw : appliesRaw) {
            appliesTo.add(StatAppliesTo.parse(raw));
        }
        return new StatTrigger(when, sources, appliesTo);
    }

    /**
     * Parses the optional {@code stats.<key>.limits:} block (段階1 宣言). Absent section → {@code null}.
     * Every bound field is itself optional; only {@code stacking} is validated against a closed
     * vocabulary here (numeric fields are validated by their type, the {@code -ref} pointers are
     * validated by {@link com.trinityforge.stats.CapRefResolver} at the constraint-test layer, not here —
     * this loader has no plugin data-folder root to resolve a relative yml path against at parse time).
     *
     * <p>General rule for every bound field {@code X}: {@code X-ref} may accompany {@code X}, but
     * {@code X-ref} alone (no {@code X}) is rejected — there would be nothing to compare it against.
     */
    static StatLimits parseLimits(ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        return new StatLimits(
                parseBound(section, "cap"),
                parseBound(section, "floor"),
                parseBound(section, "min-pieces"),
                parseBound(section, "max-distance"),
                parseBound(section, "max-duration-ticks"),
                section.contains("stacking") ? StatStacking.parse(section.getString("stacking")) : null);
    }

    /**
     * Parses one {@code limits.<field>} / {@code limits.<field>-ref} pair into a {@link StatBound}.
     * {@code <field>-ref} without {@code <field>} throws — a ref with nothing to compare against is a
     * declaration bug, not a legal "ref-only" shorthand.
     */
    private static StatBound parseBound(ConfigurationSection section, String field) {
        String refField = field + "-ref";
        boolean hasValue = section.contains(field);
        String ref = section.contains(refField) ? section.getString(refField) : null;
        if (!hasValue) {
            if (ref != null) {
                throw new IllegalArgumentException(
                        "limits." + refField + " declared without limits." + field + " to compare it against");
            }
            return null;
        }
        return new StatBound(section.getDouble(field), ref);
    }

    /**
     * Parses the {@code bind:} section (所有者/使用可能Lvのlore行). Absent → built-in defaults, so items
     * predating this field still show bind info. Missing sub-keys fall back per-field.
     */
    static BindLore parseBind(ConfigurationSection section) {
        BindLore def = BindLore.defaults();
        if (section == null) {
            return def;
        }
        return new BindLore(
                section.getBoolean("show-owner", def.showOwner()),
                section.getString("owner-line", def.ownerLine()),
                section.getBoolean("show-use-requirement", def.showUseRequirement()),
                section.getString("use-requirement-line", def.useRequirementLine()));
    }

    /**
     * Bind情報(所有者/使用可能レベル)のlore行テンプレート。MiniMessageのプレースホルダ:
     * {@code <owner>}=所有者名, {@code <skill>}=スキル名, {@code <level>}=必要Lv。
     */
    public record BindLore(boolean showOwner, String ownerLine,
                           boolean showUseRequirement, String useRequirementLine) {
        public static BindLore defaults() {
            return new BindLore(
                    true, "<gray>所有者: <white><owner></white></gray>",
                    // 現行 LoreComposer のハードコード文言と一致させ、admin未設定時の描画結果を変えない。
                    true, "<gray>使用可能レベル: <white><level></white> <gray><skill></gray></gray>");
        }
    }

    /** Atomically-swapped bundle so a reload never exposes a mismatched layout/table/bind. */
    public record Snapshot(LoreLayout layout, Map<String, StatDisplaySpec> displayTable, BindLore bind) {
    }
}
