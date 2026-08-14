package com.trinityforge.config.domains;

import com.trinityforge.stats.FallbackCategoryProfile;
import com.trinityforge.stats.EquipmentSlotResolver;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.ItemUseRequirement;
import com.trinityforge.stats.PercentStatNormalize;
import com.trinityforge.stats.QualityRollModel;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatRange;
import com.trinityforge.stats.UseSkillDefaults;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code stats/item-stats.yml}: the SOLE per-item stat source (fixed + per-quality) keyed by
 * Material name (e.g. {@code DIAMOND_SWORD}) or {@code Material#CustomModelData} (e.g. {@code
 * BLAZE_ROD#100012}). {@link com.trinityforge.stats.DerivedItemStats} applies the matched
 * {@link ItemStatProfile}: {@code fixed} OVERWRITES (via {@code put}), while {@code per-quality} is an
 * additive {@code step * qualityLevel} bonus applied on top (absent means no scaling, quality 0 is a
 * no-op). Fully deterministic: no roll/material-base layer exists anymore, so a given item + quality
 * always derives to the exact same stats.
 *
 * <p>Lookup precedence: a {@code Material#cmd} entry wins over the plain {@code Material} entry
 * ({@link #profileFor}). Nothing is baked: editing here + {@code /trinityforge reload} re-derives
 * existing items live.
 *
 * <p>A YAML syntax error aborts the reload and keeps the previous snapshot (returns {@code false}); a
 * malformed individual entry (unknown material, bad value) is skipped with a logged warning while the
 * rest load. Stat keys are folded through {@link StatKeys#canonical} so they line up with the lore
 * tables. The bundled default ships empty ({@code items: {}}) so the overlay is inert until an
 * operator adds an item.
 */
public final class ItemStatsConfig {

    public static final String PATH = "stats/item-stats.yml";
    private static final String ROOT = "items";
    private static final String FALLBACK_ROOT = "fallback";
    private static final String FALLBACK_OVERRIDES_ROOT = "fallback-overrides";
    private static final String EDITOR_ROOT = "_editor";

    // normalized item key ("MATERIAL" or "MATERIAL#cmd") -> overlay profile.
    private volatile Map<String, ItemStatProfile> profiles = Map.of();
    // use-level / use-skill keyed like profiles (item-stats.yml siblings of fixed/per-quality).
    private volatile Map<String, ItemUseRequirement> useRequirements = Map.of();
    // 品質基準値 (quality-mode-offset): クラフト品質の mode を項目単位でずらすオフセット。
    // +1=mode+1 / -1=mode-1 / 未指定=クラフトユーザの品質ポイント通り。key は profiles と同じ正規形。
    private volatile Map<String, Integer> qualityModeOffsets = Map.of();
    // フォールバック: カテゴリ別 (weapon/armor/tool/other/...) の fixed + lore-default。
    // 旧形式 (fallback.fixed 直下) は legacyFallbackProfile に残し、全カテゴリの下敷きとして使う。
    private volatile ItemStatProfile legacyFallbackProfile = new ItemStatProfile(Map.of(), Map.of(), Map.of());
    private volatile Map<String, FallbackCategoryProfile> fallbackByCategory = Map.of();
    // 任意カテゴリ(editorのネストカテゴリ)ごとのフォールバック上書き: categoryId -> profile。
    // 上書きが存在するカテゴリ所属アイテムは既定(未設定)フォールバックの代わりにこれを使う。
    private volatile Map<String, FallbackCategoryProfile> fallbackOverrides = Map.of();
    // normalized item key -> editor任意カテゴリid (_editor.categories 由来)。
    private volatile Map<String, String> editorCategoryByItem = Map.of();
    // normalized item key -> editor最上位タブ名 (weapon/armor/tool/...)。Materialだけでは分類できない
    // BOOKベース武器等を、属性適用時にも正しい装備スロットへ限定するために使う。
    private volatile Map<String, String> editorTopLevelCategoryByItem = Map.of();
    // Supplies the quality-dependent roll distribution for the `random` layer; null = no roll applied.
    // Wired once by ConfigManager so item-stats derivation can reach quality.yml's roll model without
    // threading it through every DerivedItemStats caller (mirrors QualityConfig#useEffectiveMaxOverride).
    private volatile Supplier<QualityRollModel> rollModelSupplier = () -> null;

    // フォーク動的登録(触媒等、TF configに書かれていないMATERIAL#cmdをフォーク側が実行時に補完する用途)。
    // normalized item key ("MATERIAL" or "MATERIAL#cmd") -> (namespace, profile)。config読込(load())は
    // このマップを一切触らない(reloadで動的登録が消えない契約)。読み取りは戦闘/表示スレッド、書き込みは
    // コマンド/フォークreloadスレッドから起こり得るため ConcurrentHashMap で保護する。
    private final Map<String, DynamicEntry> dynamicProfiles = new ConcurrentHashMap<>();

    /**
     * One namespaced dynamic registration: {@code namespace} identifies the owning fork/subsystem so
     * {@link #clearDynamic} can remove an entire group at once without touching another fork's entries.
     */
    private record DynamicEntry(String namespace, ItemStatProfile profile) {
    }

    /**
     * The individual overlay for {@code material} (+ optional {@code customModelData}), or empty when
     * none is configured.
     *
     * <p>Lookup precedence (design decision, ties config-authored and fork dynamic-registered entries
     * together at read time):
     * <ol>
     *   <li>a non-empty config {@code MATERIAL#cmd} exact entry (when {@code customModelData} is present);</li>
     *   <li>a dynamically registered entry ({@link #registerDynamic}) — preferring its own
     *       {@code MATERIAL#cmd} entry over its plain {@code MATERIAL} entry, mirroring config's own
     *       cmd-over-plain preference;</li>
     *   <li>a config plain {@code MATERIAL} entry.</li>
     * </ol>
     * Rationale: dynamic registration exists to COMPLEMENT config for a {@code MATERIAL#cmd} the
     * operator has not (yet) authored in {@code item-stats.yml}; an explicit config entry — cmd-specific
     * OR plain — always wins over a same-key dynamic one, so an operator can always override a fork's
     * runtime default by simply adding a config entry. {@link #fallback()} is unrelated to dynamic
     * registration and is applied by the caller only when this method returns empty.
     */
    public Optional<ItemStatProfile> profileFor(Material material, Integer customModelData) {
        if (material == null) {
            return Optional.empty();
        }
        String base = material.name();
        String cmdKey = customModelData != null ? base + "#" + customModelData : null;

        if (cmdKey != null) {
            ItemStatProfile specific = profiles.get(cmdKey);
            if (specific != null && !specific.isEmpty()) {
                return Optional.of(specific);
            }
        }

        Optional<ItemStatProfile> dynamic = dynamicProfileFor(base, cmdKey);
        if (dynamic.isPresent()) {
            return dynamic;
        }

        ItemStatProfile plain = profiles.get(base);
        return plain == null || plain.isEmpty() ? Optional.empty() : Optional.of(plain);
    }

    /**
     * Use-level/skill authored on item-stats for {@code material}+CMD. Prefers {@code MATERIAL#cmd}
     * over plain {@code MATERIAL}. When a row has a level (or skill) but omits {@code use-skill},
     * fills skill via {@link com.trinityforge.stats.UseSkillDefaults}. Empty when unset (caller may
     * fall back to catalog template). Missing level is treated as {@code 0}.
     */
    public Optional<ItemUseRequirement> useRequirementFor(Material material, Integer customModelData) {
        if (material == null) {
            return Optional.empty();
        }
        ItemUseRequirement raw = lookupUseRequirement(material.name(), customModelData);
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.hasSkill()) {
            return Optional.of(raw);
        }
        if (raw.hasRole()) {
            // use-skill が無くても use-role だけで要件になる。ここで empty を返すと
            // ロール条件が黙って消える(装備制限が一切かからない)。
            return Optional.of(UseSkillDefaults.infer(material, customModelData)
                    .map(skill -> new ItemUseRequirement(raw.levelOrZero(), skill, raw.role()))
                    .orElse(raw));
        }
        return UseSkillDefaults.infer(material, customModelData)
                .map(skill -> new ItemUseRequirement(raw.levelOrZero(), skill));
    }

    private ItemUseRequirement lookupUseRequirement(String base, Integer customModelData) {
        if (customModelData != null) {
            ItemUseRequirement specific = useRequirements.get(base + "#" + customModelData);
            if (specific != null) {
                return specific;
            }
        }
        return useRequirements.get(base);
    }

    /**
     * 品質基準値 (quality-mode-offset): このアイテムをクラフトしたときの品質 mode に加算する
     * オフセット。{@code MATERIAL#cmd} エントリが素の {@code MATERIAL} エントリより優先。
     * 未指定は 0 (クラフトユーザの品質ポイント通り)。
     */
    public int qualityModeOffsetFor(Material material, Integer customModelData) {
        Objects.requireNonNull(material, "material");
        String base = material.name();
        if (customModelData != null) {
            Integer specific = qualityModeOffsets.get(base + "#" + customModelData);
            if (specific != null) {
                return specific;
            }
        }
        Integer plain = qualityModeOffsets.get(base);
        return plain == null ? 0 : plain;
    }

    private Optional<ItemStatProfile> dynamicProfileFor(String base, String cmdKey) {
        if (cmdKey != null) {
            DynamicEntry specific = dynamicProfiles.get(cmdKey);
            if (specific != null && !specific.profile().isEmpty()) {
                return Optional.of(specific.profile());
            }
        }
        DynamicEntry plain = dynamicProfiles.get(base);
        return plain != null && !plain.profile().isEmpty()
                ? Optional.of(plain.profile())
                : Optional.empty();
    }

    /**
     * True when {@code material} (+ optional {@code customModelData}) resolves (config or dynamic, per
     * {@link #profileFor}) to a profile with {@code offhandApplies = true}; {@code false} for an
     * unresolved item or one whose profile leaves it at the default {@code false}. Used by a fork's
     * offhand stat-aggregation to decide whether to add that slot's stats into the combined total.
     */
    public boolean offhandStatsApply(Material material, Integer customModelData) {
        return profileFor(material, customModelData).map(ItemStatProfile::offhandApplies).orElse(false);
    }

    /**
     * Config editorの最上位カテゴリ ({@code weapon/armor/tool/...})。CMD完全一致を優先し、
     * 無ければ素のMaterial分類へフォールバックする。
     */
    public Optional<String> topLevelCategoryFor(Material material, Integer customModelData) {
        if (material == null) {
            return Optional.empty();
        }
        String base = material.name();
        if (customModelData != null) {
            String exact = editorTopLevelCategoryByItem.get(base + "#" + customModelData);
            if (exact != null && !exact.isBlank()) {
                return Optional.of(exact);
            }
        }
        return Optional.ofNullable(editorTopLevelCategoryByItem.get(base));
    }

    /**
     * Registers (or replaces) a dynamic overlay for {@code material} (+ optional {@code
     * customModelData}), attributed to {@code namespace} so a later {@link #clearDynamic(String)} can
     * remove just this group. Intended for a fork that authors its own item stats (e.g. a catalyst
     * defined in the fork's own config) and wants them to flow through the SAME resolution/lore/combat
     * pipeline as a config-authored item, without duplicating that logic fork-side. No-op (never
     * throws) when {@code material} or {@code profile} is null, so a fork bug here cannot break TF's
     * own item-stats resolution.
     */
    public void registerDynamic(String namespace, Material material, Integer customModelData,
                                ItemStatProfile profile) {
        if (namespace == null || material == null || profile == null) {
            return;
        }
        String key = customModelData != null ? material.name() + "#" + customModelData : material.name();
        dynamicProfiles.put(key, new DynamicEntry(namespace, profile));
    }

    /**
     * Convenience overload building the {@link ItemStatProfile} from raw maps, so a fork does not need
     * to depend on the profile's constructor shape directly.
     */
    public void registerDynamic(String namespace, Material material, Integer customModelData,
                                Map<String, Double> fixed, Map<String, Double> perQuality,
                                Map<String, StatRange> random, Integer durability, boolean offhandApplies) {
        registerDynamic(namespace, material, customModelData,
                new ItemStatProfile(fixed, perQuality, random, durability, offhandApplies));
    }

    /**
     * Removes a single dynamic entry for {@code material} (+ optional {@code customModelData}), if any.
     * No-op when nothing was registered under that key or when {@code material} is null.
     */
    public void unregisterDynamic(Material material, Integer customModelData) {
        if (material == null) {
            return;
        }
        String key = customModelData != null ? material.name() + "#" + customModelData : material.name();
        dynamicProfiles.remove(key);
    }

    /**
     * Removes every dynamic entry registered under {@code namespace} (and none belonging to another
     * namespace). Intended for a fork's own {@code reload}: clear its whole group, then re-register from
     * its freshly reloaded config, so a removed/renamed dynamic item does not linger. No-op when {@code
     * namespace} is null.
     */
    public void clearDynamic(String namespace) {
        if (namespace == null) {
            return;
        }
        dynamicProfiles.values().removeIf(entry -> entry.namespace().equals(namespace));
    }

    /**
     * Legacy whole-profile fallback (old {@code fallback.fixed} shape). Prefer
     * {@link #fallbackFixedFor(Material)} for resolution. Still used for offhandApplies default.
     */
    public Optional<ItemStatProfile> fallback() {
        ItemStatProfile fb = legacyFallbackProfile;
        return fb.isEmpty() ? Optional.empty() : Optional.of(fb);
    }

    /**
     * Category-scoped fallback fixed values for {@code material}: legacy global fixed first, then each
     * matching equipment category's {@code fallback.<cat>.fixed} (later categories overwrite).
     */
    public Map<String, Double> fallbackFixedFor(Material material) {
        return fallbackFixedFor(material, null);
    }

    /**
     * Same as {@link #fallbackFixedFor(Material)} with CustomModelData awareness: when the item's
     * editor任意カテゴリ ({@code _editor.categories}) has a {@code fallback-overrides.<catId>} entry,
     * that override REPLACES the default (未設定) fallback entirely. Otherwise the default resolution
     * (legacy global + equipment categories) applies.
     */
    public Map<String, Double> fallbackFixedFor(Material material, Integer customModelData) {
        FallbackCategoryProfile override = overrideFor(material, customModelData);
        if (override != null) {
            return Map.copyOf(override.fixed());
        }
        Map<String, Double> out = new LinkedHashMap<>();
        if (!legacyFallbackProfile.isEmpty()) {
            out.putAll(legacyFallbackProfile.fixed());
        }
        if (material == null) {
            return Map.copyOf(out);
        }
        for (String cat : EquipmentSlotResolver.statCategories(material)) {
            FallbackCategoryProfile profile = fallbackByCategory.get(cat);
            if (profile != null) {
                out.putAll(profile.fixed());
            }
        }
        return Map.copyOf(out);
    }

    /** Lore 「デフォルト表示」ON のキー集合 (canonical) for this material's categories. */
    public Set<String> loreDefaultKeysFor(Material material) {
        return loreDefaultKeysFor(material, null);
    }

    /** CMD対応版 {@link #loreDefaultKeysFor(Material)}: 任意カテゴリ上書きがあればそれだけを使う。 */
    public Set<String> loreDefaultKeysFor(Material material, Integer customModelData) {
        if (material == null) {
            return Set.of();
        }
        FallbackCategoryProfile override = overrideFor(material, customModelData);
        if (override != null) {
            return Set.copyOf(override.loreDefaultKeys());
        }
        Set<String> out = new LinkedHashSet<>();
        for (String cat : EquipmentSlotResolver.statCategories(material)) {
            FallbackCategoryProfile profile = fallbackByCategory.get(cat);
            if (profile != null) {
                out.addAll(profile.loreDefaultKeys());
            }
        }
        return Set.copyOf(out);
    }

    /** The fallback override for this item's editor任意カテゴリ, or null when none applies. */
    private FallbackCategoryProfile overrideFor(Material material, Integer customModelData) {
        if (material == null || fallbackOverrides.isEmpty() || editorCategoryByItem.isEmpty()) {
            return null;
        }
        String catId = null;
        if (customModelData != null) {
            catId = editorCategoryByItem.get(material.name() + "#" + customModelData);
        }
        if (catId == null) {
            catId = editorCategoryByItem.get(material.name());
        }
        return catId == null ? null : fallbackOverrides.get(catId);
    }

    /** Test/editor helper: category fallback map (unmodifiable). */
    public Map<String, FallbackCategoryProfile> fallbackByCategory() {
        return fallbackByCategory;
    }

    /** Test/editor helper: 任意カテゴリ別フォールバック上書き (categoryId -> profile, unmodifiable). */
    public Map<String, FallbackCategoryProfile> fallbackOverrides() {
        return fallbackOverrides;
    }

    /**
     * Wires the source of the quality-dependent roll distribution (from {@code quality.yml} via
     * {@code QualityConfig#rollModel}) used by the {@code random} layer. Called once by {@code ConfigManager}.
     * Until wired (or when the supplier returns {@code null}) the {@code random} layer is inert.
     */
    public void useRollModel(Supplier<QualityRollModel> supplier) {
        this.rollModelSupplier = supplier == null ? () -> null : supplier;
    }

    /** The current quality roll model for the {@code random} layer, or {@code null} when none is wired. */
    public QualityRollModel rollModel() {
        return rollModelSupplier.get();
    }

    /** Loads (or reloads) the overlay table. Returns true when every entry parsed cleanly. */
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }

        // loadConfiguration(File) は構文エラーを握り潰して空configを返すため自前でload()する。
        // 構文エラー時は直前に成功ロード済みのprofiles(初回失敗時はMap.of())を維持しfalseを返す。
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML構文エラーのため読み込みを中止しました。"
                    + "直前の設定値を維持します: " + ex.getMessage(), ex);
            return false;
        }

        Map<String, ItemStatProfile> parsed = new LinkedHashMap<>();
        Map<String, ItemUseRequirement> parsedUse = new LinkedHashMap<>();
        Map<String, Integer> parsedOffsets = new LinkedHashMap<>();
        int skipped = 0;
        ConfigurationSection root = yaml.getConfigurationSection(ROOT);
        if (root != null) {
            for (String rawKey : root.getKeys(false)) {
                ConfigurationSection entry = root.getConfigurationSection(rawKey);
                if (entry == null) {
                    log.warning("[" + PATH + "] item '" + rawKey + "' is not a section; skipped");
                    skipped++;
                    continue;
                }
                String normalizedKey = normalizeKey(rawKey, log);
                if (normalizedKey == null) {
                    skipped++;
                    continue;
                }
                try {
                    parsed.put(normalizedKey, new ItemStatProfile(
                            parseFixed(entry), parsePerQuality(entry), parseRandom(entry),
                            parseDurability(entry),
                            entry.getBoolean("offhand-stats-apply", false),
                            parseRandomizeGrants(entry),
                            parseGrantChances(entry),
                            parseMultipliers(entry),  // may throw -> skip item
                            // 装着専用(スレッド)。true のアイテムは装備/手持ちスロットから
                            // ステを一切寄与しない。詳細は ItemStatProfile#socketedOnly の javadoc。
                            entry.getBoolean("socketed-only-stats", false)));
                    ItemUseRequirement useReq = parseUseRequirement(entry);
                    // Keep skill-only OR level-authored rows (level-only needs UseSkillDefaults later).
                    if (useReq.hasSkill() || useReq.hasRole()
                            || entry.contains("use-level-requirement")) {
                        parsedUse.put(normalizedKey, useReq);
                    }
                    Integer offset = parseQualityModeOffset(entry);
                    if (offset != null) {
                        parsedOffsets.put(normalizedKey, offset);
                    }
                } catch (IllegalArgumentException ex) {
                    log.log(Level.WARNING, "[" + PATH + "] item '" + rawKey + "' invalid: "
                            + ex.getMessage() + "; skipped");
                    skipped++;
                }
            }
        }
        this.profiles = Collections.unmodifiableMap(parsed);
        this.useRequirements = Collections.unmodifiableMap(parsedUse);
        this.qualityModeOffsets = Collections.unmodifiableMap(parsedOffsets);

        // フォールバック: 旧形式 (fixed/per-quality/random 直下) + 新形式 (weapon/armor/... カテゴリ別)。
        ItemStatProfile legacyFallback = new ItemStatProfile(Map.of(), Map.of(), Map.of());
        Map<String, FallbackCategoryProfile> byCategory = new LinkedHashMap<>();
        ConfigurationSection fallbackSection = yaml.getConfigurationSection(FALLBACK_ROOT);
        if (fallbackSection != null) {
            try {
                if (fallbackSection.isConfigurationSection("fixed")
                        || fallbackSection.isConfigurationSection("per-quality")
                        || fallbackSection.isConfigurationSection("random")
                        || fallbackSection.contains("durability")) {
                    legacyFallback = new ItemStatProfile(parseFixed(fallbackSection),
                            parsePerQuality(fallbackSection), parseRandom(fallbackSection),
                            parseDurability(fallbackSection),
                            fallbackSection.getBoolean("offhand-stats-apply", false),
                            parseRandomizeGrants(fallbackSection),
                            parseGrantChances(fallbackSection));
                }
                for (String catKey : fallbackSection.getKeys(false)) {
                    if (isLegacyFallbackKey(catKey)) {
                        continue;
                    }
                    ConfigurationSection catSec = fallbackSection.getConfigurationSection(catKey);
                    if (catSec == null) {
                        continue;
                    }
                    String cat = catKey.trim().toLowerCase(Locale.ROOT);
                    byCategory.put(cat, parseFallbackCategory(catSec));
                }
            } catch (IllegalArgumentException ex) {
                log.log(Level.WARNING, "[" + PATH + "] fallback セクションが不正です: "
                        + ex.getMessage() + "; フォールバックを無効化します");
                skipped++;
            }
        }
        this.legacyFallbackProfile = legacyFallback;
        this.fallbackByCategory = Collections.unmodifiableMap(byCategory);

        // 任意カテゴリ別フォールバック上書き (fallback-overrides.<categoryId>) + アイテム→任意カテゴリ対応
        // (_editor.categories) の読み込み。editorが書くメタをランタイムでも解決に使う。
        Map<String, FallbackCategoryProfile> overrides = new LinkedHashMap<>();
        ConfigurationSection overridesSection = yaml.getConfigurationSection(FALLBACK_OVERRIDES_ROOT);
        if (overridesSection != null) {
            for (String catId : overridesSection.getKeys(false)) {
                ConfigurationSection catSec = overridesSection.getConfigurationSection(catId);
                if (catSec == null) {
                    continue;
                }
                try {
                    overrides.put(catId.trim(), parseFallbackCategory(catSec));
                } catch (IllegalArgumentException ex) {
                    log.log(Level.WARNING, "[" + PATH + "] fallback-overrides." + catId + " が不正です: "
                            + ex.getMessage() + "; このカテゴリの上書きをスキップします");
                    skipped++;
                }
            }
        }
        this.fallbackOverrides = Collections.unmodifiableMap(overrides);
        this.editorCategoryByItem = Collections.unmodifiableMap(parseEditorCategories(yaml));
        this.editorTopLevelCategoryByItem = Collections.unmodifiableMap(parseEditorTopLevelCategories(yaml));

        if (skipped > 0) {
            log.warning("[" + PATH + "] loaded " + parsed.size() + " item(s), " + skipped + " skipped");
            return false;
        }
        log.info("[" + PATH + "] loaded " + parsed.size() + " item(s) OK");
        return true;
    }

    /**
     * Reads {@code _editor.categories.<tabKey>} (a list of {@code {id, label, itemIds}} maps written by
     * the config editor) into a flat {@code normalized item key -> categoryId} map for runtime
     * fallback-override resolution. Malformed entries are silently ignored (editor metadata is advisory).
     */
    private static Map<String, String> parseEditorCategories(YamlConfiguration yaml) {
        Map<String, String> byItem = new LinkedHashMap<>();
        ConfigurationSection editor = yaml.getConfigurationSection(EDITOR_ROOT);
        if (editor == null) {
            return byItem;
        }
        ConfigurationSection categories = editor.getConfigurationSection("categories");
        if (categories == null) {
            return byItem;
        }
        for (String tabKey : categories.getKeys(false)) {
            for (Object raw : categories.getList(tabKey, java.util.List.of())) {
                if (!(raw instanceof Map<?, ?> cat)) {
                    continue;
                }
                Object id = cat.get("id");
                Object itemIds = cat.get("itemIds");
                if (id == null || !(itemIds instanceof java.util.List<?> ids)) {
                    continue;
                }
                for (Object itemId : ids) {
                    if (itemId != null) {
                        byItem.putIfAbsent(String.valueOf(itemId).trim(), String.valueOf(id).trim());
                    }
                }
            }
        }
        return byItem;
    }

    /** Reads the same category lists as {@link #parseEditorCategories}, retaining their top-level tab. */
    private static Map<String, String> parseEditorTopLevelCategories(YamlConfiguration yaml) {
        Map<String, String> byItem = new LinkedHashMap<>();
        ConfigurationSection editor = yaml.getConfigurationSection(EDITOR_ROOT);
        if (editor == null) {
            return byItem;
        }
        ConfigurationSection categories = editor.getConfigurationSection("categories");
        if (categories == null) {
            return byItem;
        }
        for (String tabKey : categories.getKeys(false)) {
            for (Object raw : categories.getList(tabKey, java.util.List.of())) {
                if (!(raw instanceof Map<?, ?> cat)) {
                    continue;
                }
                Object itemIds = cat.get("itemIds");
                if (!(itemIds instanceof java.util.List<?> ids)) {
                    continue;
                }
                for (Object itemId : ids) {
                    if (itemId != null) {
                        byItem.putIfAbsent(String.valueOf(itemId).trim(), tabKey.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return byItem;
    }

    /**
     * Reads the {@code multipliers} section: layer id -&gt; {@code {fixed, per-quality, random}} where
     * each stat value is a MULTIPLIER (1.2 = x1.2), NOT a percent — so {@link PercentStatNormalize}
     * is intentionally not applied here (x2 must stay 2.0, never 0.02). Malformed values throw
     * {@link IllegalArgumentException} so the whole item is skipped (matching fixed/per-quality).
     */
    private static Map<String, ItemStatProfile.MultiplierSpec> parseMultipliers(ConfigurationSection entry) {
        Map<String, ItemStatProfile.MultiplierSpec> out = new LinkedHashMap<>();
        ConfigurationSection root = entry.getConfigurationSection("multipliers");
        if (root == null) {
            return out;
        }
        for (String layer : root.getKeys(false)) {
            // "__unset__" 等のエディタ内部プレースホルダは無視する (保存前バリデーションで弾くが防御的に)。
            if (layer.startsWith("__")) {
                continue;
            }
            ConfigurationSection layerSec = root.getConfigurationSection(layer);
            if (layerSec == null) {
                throw new IllegalArgumentException("multipliers." + layer + " must be a section");
            }
            Map<String, Double> fixed = parsePlainNumberMap(layerSec, "fixed", "multipliers." + layer);
            Map<String, Double> perQuality = parsePlainNumberMap(layerSec, "per-quality", "multipliers." + layer);
            Map<String, StatRange> random = new LinkedHashMap<>();
            ConfigurationSection randomSec = layerSec.getConfigurationSection("random");
            if (randomSec != null) {
                for (String statKey : randomSec.getKeys(false)) {
                    ConfigurationSection range = randomSec.getConfigurationSection(statKey);
                    if (range == null || !(range.isDouble("min") || range.isInt("min"))
                            || !(range.isDouble("max") || range.isInt("max"))) {
                        throw new IllegalArgumentException(
                                "multipliers." + layer + ".random." + statKey + " needs numeric min/max");
                    }
                    double min = range.getDouble("min");
                    double max = range.getDouble("max");
                    if (min > max) {
                        throw new IllegalArgumentException(
                                "multipliers." + layer + ".random." + statKey + " has min > max");
                    }
                    random.put(StatKeys.canonical(statKey), new StatRange(min, max));
                }
            }
            ItemStatProfile.MultiplierSpec spec = new ItemStatProfile.MultiplierSpec(fixed, perQuality, random);
            if (!spec.isEmpty()) {
                out.put(layer.trim(), spec);
            }
        }
        return out;
    }

    /** Numeric map reader without percent coercion (multiplier values are stored verbatim). */
    private static Map<String, Double> parsePlainNumberMap(ConfigurationSection parent, String key,
                                                           String context) {
        Map<String, Double> out = new LinkedHashMap<>();
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            return out;
        }
        for (String statKey : section.getKeys(false)) {
            if (!section.isDouble(statKey) && !section.isInt(statKey)) {
                throw new IllegalArgumentException(context + "." + key + ".'" + statKey + "' is not numeric");
            }
            double value = section.getDouble(statKey);
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(context + "." + key + ".'" + statKey + "' is not finite");
            }
            out.put(StatKeys.canonical(statKey), value);
        }
        return out;
    }

    /**
     * 任意の {@code quality-mode-offset} (品質基準値) を読む。未指定なら {@code null}。
     * 整数のみ許容 (負値可)。不正値は entry ごと skip される例外にする。
     */
    private static Integer parseQualityModeOffset(ConfigurationSection entry) {
        if (!entry.contains("quality-mode-offset", true)) {
            return null;
        }
        if (!entry.isInt("quality-mode-offset")) {
            throw new IllegalArgumentException("quality-mode-offset は整数で指定してください");
        }
        return entry.getInt("quality-mode-offset");
    }

    private static boolean isLegacyFallbackKey(String key) {
        return switch (key) {
            case "fixed", "per-quality", "random", "durability", "offhand-stats-apply",
                 "advanced", "use-skill", "use-level-requirement", "use-role" -> true;
            default -> false;
        };
    }

    private static FallbackCategoryProfile parseFallbackCategory(ConfigurationSection catSec) {
        Map<String, Double> fixed = parseFixed(catSec);
        Set<String> loreDefaults = new LinkedHashSet<>();
        ConfigurationSection loreSec = catSec.getConfigurationSection("lore-default");
        if (loreSec != null) {
            for (String statKey : loreSec.getKeys(false)) {
                if (loreSec.getBoolean(statKey, false)) {
                    loreDefaults.add(StatKeys.canonical(statKey));
                }
            }
        }
        // Also accept list form: lore-default: [attack-power, crit-chance]
        if (catSec.isList("lore-default")) {
            for (String raw : catSec.getStringList("lore-default")) {
                if (raw != null && !raw.isBlank()) {
                    loreDefaults.add(StatKeys.canonical(raw));
                }
            }
        }
        return new FallbackCategoryProfile(fixed, loreDefaults);
    }

    /**
     * Normalizes a config key to {@code MATERIAL} or {@code MATERIAL#cmd} (material upper-cased,
     * validated against the {@link Material} registry). Returns {@code null} (caller skips) when the
     * material is unknown or the CustomModelData part is not an integer, so a typo does not silently
     * install a dead entry.
     */
    private static String normalizeKey(String rawKey, Logger log) {
        String trimmed = rawKey.trim();
        int hash = trimmed.indexOf('#');
        String materialPart = (hash >= 0 ? trimmed.substring(0, hash) : trimmed).trim();
        String cmdPart = hash >= 0 ? trimmed.substring(hash + 1).trim() : null;

        Material material = Material.getMaterial(materialPart.toUpperCase(Locale.ROOT));
        if (material == null) {
            log.warning("[" + PATH + "] item '" + rawKey + "' has unknown material '" + materialPart
                    + "'; skipped");
            return null;
        }
        if (cmdPart == null) {
            return material.name();
        }
        try {
            int cmd = Integer.parseInt(cmdPart);
            return material.name() + "#" + cmd;
        } catch (NumberFormatException ex) {
            log.warning("[" + PATH + "] item '" + rawKey + "' has a non-integer CustomModelData '"
                    + cmdPart + "'; skipped");
            return null;
        }
    }

    /**
     * Reads the {@code fixed} section: canonical stat key -&gt; exact value. Absent means none. Each
     * value MUST be numeric and finite: a typo like {@code attack-power: foo} would otherwise be read
     * by {@code getDouble} as 0.0 and silently install a dead value. A non-numeric or non-finite value
     * throws {@link IllegalArgumentException} so the whole item is skipped with a logged warning, never
     * a 0.0 stomp.
     */
    private static Map<String, Double> parseFixed(ConfigurationSection entry) {
        Map<String, Double> fixed = new LinkedHashMap<>();
        ConfigurationSection section = entry.getConfigurationSection("fixed");
        if (section != null) {
            for (String statKey : section.getKeys(false)) {
                if (!section.isDouble(statKey) && !section.isInt(statKey)) {
                    throw new IllegalArgumentException(
                            "fixed stat '" + statKey + "' is not numeric");
                }
                double value = section.getDouble(statKey);
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "fixed stat '" + statKey + "' is not finite");
                }
                fixed.put(StatKeys.canonical(statKey),
                        PercentStatNormalize.coerce(statKey, value));
            }
        }
        return fixed;
    }

    /**
     * Reads the {@code per-quality} section: canonical stat key -&gt; per-quality-level increment (I8,
     * back-compat quality scaling). Absent means none (empty map, no behaviour change for items that
     * predate this field). Each value MUST be numeric and finite, exactly like {@link #parseFixed}: a
     * typo like {@code attack-power: foo} would otherwise be read by {@code getDouble} as 0.0 and
     * silently install a dead (zero-increment) entry instead of surfacing the mistake. A non-numeric or
     * non-finite value throws {@link IllegalArgumentException} so the whole item is skipped with a
     * logged warning, matching the {@code fixed} malformed-skip contract.
     */
    private static Map<String, Double> parsePerQuality(ConfigurationSection entry) {
        Map<String, Double> perQuality = new LinkedHashMap<>();
        ConfigurationSection section = entry.getConfigurationSection("per-quality");
        if (section != null) {
            for (String statKey : section.getKeys(false)) {
                if (!section.isDouble(statKey) && !section.isInt(statKey)) {
                    throw new IllegalArgumentException(
                            "per-quality stat '" + statKey + "' is not numeric");
                }
                double value = section.getDouble(statKey);
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "per-quality stat '" + statKey + "' is not finite");
                }
                perQuality.put(StatKeys.canonical(statKey),
                        PercentStatNormalize.coerce(statKey, value));
            }
        }
        return perQuality;
    }

    /**
     * Reads the optional {@code durability} field: 最大耐久力(最大ダメージ)の上書き値。未指定なら {@code null}
     * (上書きなし)。整数でない/正でない値は {@link IllegalArgumentException} を投げてアイテム全体をskipさせる
     * ({@code fixed}/{@code per-quality} と同じ「不正なら黙って0埋めせずskip」契約)。
     */
    private static Integer parseDurability(ConfigurationSection entry) {
        if (!entry.contains("durability", true)) {
            return null;
        }
        if (!entry.isInt("durability")) {
            throw new IllegalArgumentException("durability は整数で指定してください");
        }
        int durability = entry.getInt("durability");
        if (durability <= 0) {
            throw new IllegalArgumentException("durability は正の整数で指定してください: " + durability);
        }
        return durability;
    }

    /**
     * Reads the {@code random} section: canonical stat key -&gt; {@link StatRange} ({@code {min, max}}).
     * Only stats listed here are rolled (ITEM_ECONOMY_SPEC 5.1 roll layer, 段2): {@code
     * DerivedItemStats} adds {@code min + reach * (max - min)} on top of fixed/per-quality, with the reach
     * seeded deterministically by the item's rollSeed. Absent means none. A stat that is not a
     * {@code {min, max}} section, has a non-numeric/non-finite bound, or has {@code min > max}, throws
     * {@link IllegalArgumentException} so the whole item is skipped with a logged warning — matching the
     * {@code fixed}/{@code per-quality} malformed-skip contract (never a silent dead roll).
     *
     * <p>The quantization step (旧 random-roll-pools の「刻み幅」仕様。StatRange 側へ移設) is derived
     * from the number of decimal places AUTHORED on {@code min}/{@code max} in the yml (the larger of
     * the two): {@code min: 0.5, max: 2.0} → 0.1刻み、両方とも整数（{@code min: 200, max: 600}）なら
     * 1刻み。{@code getDouble} は {@code 2} と {@code 2.0} を区別しないため、桁数は生オブジェクト
     * ({@code section.get(key)}) の {@code toString()} から数える。指数表記（{@code 1.0E-4} 等）が来た
     * ときは量子化を諦めて {@code step = 0}（＝連続値のまま、壊れるより無効化）にする。
     */
    private static Map<String, StatRange> parseRandom(ConfigurationSection entry) {
        Map<String, StatRange> random = new LinkedHashMap<>();
        ConfigurationSection section = entry.getConfigurationSection("random");
        if (section != null) {
            for (String statKey : section.getKeys(false)) {
                ConfigurationSection range = section.getConfigurationSection(statKey);
                if (range == null) {
                    throw new IllegalArgumentException(
                            "random stat '" + statKey + "' must be a {min, max} section");
                }
                boolean minNumeric = range.isDouble("min") || range.isInt("min");
                boolean maxNumeric = range.isDouble("max") || range.isInt("max");
                if (!minNumeric || !maxNumeric) {
                    throw new IllegalArgumentException(
                            "random stat '" + statKey + "' needs numeric min and max");
                }
                double min = PercentStatNormalize.coerce(statKey, range.getDouble("min"));
                double max = PercentStatNormalize.coerce(statKey, range.getDouble("max"));
                // Malformed range skips the whole item (see javadoc); never silently swap bounds.
                if (min > max) {
                    throw new IllegalArgumentException(
                            "random stat '" + statKey + "' has min (" + min + ") > max (" + max + ")");
                }
                double step = quantizationStep(range);
                random.put(StatKeys.canonical(statKey), new StatRange(min, max, step));
            }
        }
        return random;
    }

    /**
     * The authored quantization step for a {@code {min, max}} random-range section: {@code 10^-d} where
     * {@code d} is the larger of the two bounds' authored decimal places, or {@code 0} (no quantization)
     * when either bound was authored in exponential notation. See {@link #parseRandom} javadoc for the
     * full rule.
     */
    private static double quantizationStep(ConfigurationSection range) {
        int minDecimals = authoredDecimalPlaces(range.get("min"));
        int maxDecimals = authoredDecimalPlaces(range.get("max"));
        if (minDecimals < 0 || maxDecimals < 0) {
            return 0d;
        }
        return Math.pow(10, -Math.max(minDecimals, maxDecimals));
    }

    /**
     * The number of decimal places in {@code raw}'s own {@code toString()} (trailing zeros stripped), so
     * {@code 2} and {@code 2.0} are told apart even though {@code ConfigurationSection#getDouble} would
     * collapse both to {@code 2.0}. Returns {@code -1} when the value was authored in exponential
     * notation ({@code e}/{@code E} present) — the caller treats that as "give up quantizing".
     */
    private static int authoredDecimalPlaces(Object raw) {
        if (raw == null) {
            return 0;
        }
        String text = raw.toString();
        if (text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
            return -1;
        }
        int dot = text.indexOf('.');
        if (dot < 0) {
            return 0;
        }
        int end = text.length();
        while (end > dot + 1 && text.charAt(end - 1) == '0') {
            end--;
        }
        return end == dot + 1 ? 0 : end - dot - 1;
    }

    private static boolean parseRandomizeGrants(ConfigurationSection entry) {
        ConfigurationSection advanced = entry.getConfigurationSection("advanced");
        if (advanced == null) {
            return false;
        }
        return advanced.getBoolean("randomize-grants", false);
    }

    /**
     * Reads {@code advanced.grant-chances} as canonical stat key -&gt; probability in {@code [0, 1]}.
     * Absent section yields an empty map (all chances default to 1.0 at resolve time).
     */
    private static Map<String, Double> parseGrantChances(ConfigurationSection entry) {
        Map<String, Double> chances = new LinkedHashMap<>();
        ConfigurationSection advanced = entry.getConfigurationSection("advanced");
        if (advanced == null) {
            return chances;
        }
        ConfigurationSection section = advanced.getConfigurationSection("grant-chances");
        if (section == null) {
            return chances;
        }
        for (String statKey : section.getKeys(false)) {
            if (!(section.isDouble(statKey) || section.isInt(statKey))) {
                throw new IllegalArgumentException(
                        "advanced.grant-chances.'" + statKey + "' must be a number in [0, 1]");
            }
            double value = section.getDouble(statKey);
            if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
                throw new IllegalArgumentException(
                        "advanced.grant-chances.'" + statKey + "' must be in [0, 1], got " + value);
            }
            chances.put(StatKeys.canonical(statKey), value);
        }
        return chances;
    }

    private static ItemUseRequirement parseUseRequirement(ConfigurationSection entry) {
        // Missing / non-int use-level → 0 (explicit authoring of 0 is also fine).
        int level = 0;
        if (entry.contains("use-level-requirement") && entry.isInt("use-level-requirement")) {
            level = Math.max(0, entry.getInt("use-level-requirement"));
        }
        String skill = entry.getString("use-skill");
        if (skill != null && skill.isBlank()) {
            skill = null;
        }
        return new ItemUseRequirement(level, skill, entry.getString("use-role"));
    }
}
