package com.trinityforge.config.domains;

import com.trinityforge.config.LoadableConfig;
import com.trinityforge.skilltree.effects.TierTable;
import com.trinityforge.stats.EquipmentSlotResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loader for {@code progression/crafting-features.yml}: tuning for skill-tree flag consumers
 * (coating, disassembly, potion merge, over-enchant, gated catalog recipes, wood repair, brew).
 */
public final class CraftingFeaturesConfig implements LoadableConfig {

    public static final String PATH = "progression/crafting-features.yml";

    public record CoatingMaterial(double bonusDamage, int maxStacks) {}

    public record WoodRepairMaterial(int durability, boolean quickRepair) {}

    /** A single return conversion for a disassembly target series. */
    public record DisassemblyRule(String input, String output, double multiplier) {}

    public record BrewPotionSpec(String base, String ingredient, PotionEffectType type, int durationTicks, int amplifier) {}

    /**
     * A {@code brew-unlocks.<groupId>} entry. The gate id is always {@code brew:<groupId>} (2026-07-23
     * 動的ID方式改修 §3) — no {@code effect} field to read anymore; the map key from
     * {@link #brewUnlocks()} is the group id.
     */
    public record BrewUnlockGroup(List<BrewPotionSpec> potions) {}

    /**
     * A single {@code added-recipes} entry: a Bukkit workbench/inventory recipe whose result is a
     * plain vanilla {@link Material} (no catalog identity involved). Parsed by
     * {@link #loadAddedRecipes(YamlConfiguration, Logger)}, registered by
     * {@code CatalogRecipeRegistrar#registerAll()} after all catalog recipes.
     */
    public record AddedRecipe(Material result, int amount, com.trinityforge.stats.RecipeSpec spec) {}

    private volatile int coatingBaseMaxStacks = 3;
    private volatile Map<String, CoatingMaterial> coatingMaterials = Map.of();
    /** Legacy fallback when PDC flat damage is absent (old items stamped with stacks only). */
    private volatile double coatingLegacyBonusPerStack = 2.0;
    private volatile Map<String, WoodRepairMaterial> woodRepairMaterials = Map.of();
    private volatile int disassemblyPercentPerLevel = 25;
    /** Target-id/material wildcard → conversions. No fallback is intentionally provided. */
    private volatile Map<String, List<DisassemblyRule>> disassemblyItems = Map.of();
    private volatile int potionMergeMaxEffects = 5;
    private volatile int potionMergeMaxDurationSeconds = 960;
    /** {@code potion-merge.tiers.<tier>.{max-effects,max-duration-seconds}} (2026-07-26 tier-expand)。 */
    private volatile TierTable<PotionMergeTierValues> potionMergeTiers = TierTable.empty();

    /** One {@code potion-merge.tiers.<tier>} row. */
    public record PotionMergeTierValues(int maxEffects, int maxDurationSeconds) {}
    private volatile Map<String, BrewUnlockGroup> brewUnlocks = Map.of();
    /** dedicated-effect id → enchant → absolute max level */
    private volatile Map<String, Map<Enchantment, Integer>> overEnchantProfiles = Map.of();
    private volatile Map<String, Integer> threadSlotMaxByCategory = Map.of(
            EquipmentSlotResolver.CATEGORY_ARMOR, 5,
            EquipmentSlotResolver.CATEGORY_WEAPON, 0,
            EquipmentSlotResolver.CATEGORY_TOOL, 0,
            EquipmentSlotResolver.CATEGORY_OTHER, 0);
    /** Vanilla/datapack recipe keys to unregister (e.g. {@code minecraft:iron_sword}). */
    private volatile List<String> removedVanillaRecipes = List.of();
    /**
     * Raw {@code removed-vanilla-items} entries ({@code Material} or {@code Material:enchant_id}).
     * Parsing into matchers is delegated to {@link com.trinityforge.stats.VanillaItemRemover}.
     */
    private volatile List<String> removedVanillaItems = List.of();
    /** {@code added-recipes}: extra Bukkit recipes whose result is a plain vanilla Material. */
    private volatile List<AddedRecipe> addedRecipes = List.of();

    public int coatingBaseMaxStacks() {
        return coatingBaseMaxStacks;
    }

    public Map<String, CoatingMaterial> coatingMaterials() {
        return coatingMaterials;
    }

    public CoatingMaterial coatingMaterial(String catalogId) {
        if (catalogId == null || catalogId.isBlank()) {
            return null;
        }
        return coatingMaterials.get(catalogId);
    }

    public double coatingLegacyBonusPerStack() {
        return coatingLegacyBonusPerStack;
    }

    public Map<String, WoodRepairMaterial> woodRepairMaterials() {
        return woodRepairMaterials;
    }

    public WoodRepairMaterial woodRepairMaterial(String catalogId) {
        if (catalogId == null || catalogId.isBlank()) {
            return null;
        }
        return woodRepairMaterials.get(catalogId);
    }

    public int disassemblyPercentPerLevel() {
        return disassemblyPercentPerLevel;
    }

    public Map<String, List<DisassemblyRule>> disassemblyItems() {
        return disassemblyItems;
    }

    /** Exact (case-insensitive) or trailing-asterisk wildcard lookup. */
    public List<DisassemblyRule> disassemblyRulesFor(String target) {
        if (target == null || target.isBlank()) return List.of();
        String normalized = target.toLowerCase(Locale.ROOT);
        List<DisassemblyRule> bestWildcard = List.of();
        int bestPrefixLength = -1;
        for (Map.Entry<String, List<DisassemblyRule>> entry : disassemblyItems.entrySet()) {
            String pattern = entry.getKey().toLowerCase(Locale.ROOT);
            if (!pattern.endsWith("*") && normalized.equals(pattern)) return entry.getValue();
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (normalized.startsWith(prefix) && prefix.length() > bestPrefixLength) {
                    bestWildcard = entry.getValue();
                    bestPrefixLength = prefix.length();
                }
            }
        }
        return bestWildcard;
    }

    public int potionMergeMaxEffects() {
        return potionMergeMaxEffects;
    }

    public int potionMergeMaxDurationSeconds() {
        return potionMergeMaxDurationSeconds;
    }

    /**
     * {@code max-effects} を {@code tier}(プレイヤーの解放済み最高tier、{@code DedicatedEffectsConfig#valueMax}
     * の結果)で解決する。{@code potion-merge.tiers} が未定義、または {@code tier} 未満の行しか無い場合は
     * {@link #potionMergeMaxEffects()}(グローバルscalar)へ完全後方互換フォールバックする。
     */
    public int potionMergeMaxEffects(int tier) {
        return potionMergeTiers.resolve(tier).map(PotionMergeTierValues::maxEffects).orElse(potionMergeMaxEffects);
    }

    /** {@link #potionMergeMaxEffects(int)}と同じ floor+フォールバック則で解決する最大持続秒。 */
    public int potionMergeMaxDurationSeconds(int tier) {
        return potionMergeTiers.resolve(tier).map(PotionMergeTierValues::maxDurationSeconds)
                .orElse(potionMergeMaxDurationSeconds);
    }

    public Map<String, BrewUnlockGroup> brewUnlocks() {
        return brewUnlocks;
    }

    public Map<String, Map<Enchantment, Integer>> overEnchantProfiles() {
        return overEnchantProfiles;
    }

    /**
     * Absolute max enchant level for {@code ench} given the player's active over-enchant profiles.
     * Returns 0 when no profile covers the enchantment.
     */
    public int overEnchantMaxLevel(java.util.function.Predicate<String> profileActive, Enchantment ench) {
        if (profileActive == null || ench == null) {
            return 0;
        }
        int best = 0;
        for (Map.Entry<String, Map<Enchantment, Integer>> e : overEnchantProfiles.entrySet()) {
            if (!profileActive.test(e.getKey())) {
                continue;
            }
            Integer cap = e.getValue().get(ench);
            if (cap != null) {
                best = Math.max(best, cap);
            }
        }
        return best;
    }

    /** Category → max thread slots after item-stats + crafter bonus (0 = no slots). */
    public Map<String, Integer> threadSlotMaxByCategory() {
        return threadSlotMaxByCategory;
    }

    /**
     * Vanilla/datapack recipe keys the server should unregister ({@code removed-vanilla-recipes}).
     * Bare names are treated as {@code minecraft:} keys.
     */
    public List<String> removedVanillaRecipes() {
        return removedVanillaRecipes;
    }

    /**
     * {@code removed-vanilla-items} raw entries ({@code Material} or {@code Material:enchant_id}) —
     * バニラアイテムをゲームから排除する対象 (入手経路の遮断 + 既存所持の掃除)。
     */
    public List<String> removedVanillaItems() {
        return removedVanillaItems;
    }

    /**
     * {@code added-recipes}: extra Bukkit workbench/inventory recipes whose result is a plain
     * vanilla {@link Material} (registered by {@code CatalogRecipeRegistrar} after catalog recipes).
     */
    public List<AddedRecipe> addedRecipes() {
        return addedRecipes;
    }

    // --- Backward-compat accessors used by older call sites during migration ---

    /** @deprecated use {@link #coatingMaterial(String)} */
    @Deprecated
    public double coatingBonusPerStack() {
        return coatingLegacyBonusPerStack;
    }

    /** @deprecated use {@link #coatingMaterials()} */
    @Deprecated
    public String coatingGemCatalogId() {
        return coatingMaterials.isEmpty() ? "source_gem" : coatingMaterials.keySet().iterator().next();
    }

    /** @deprecated use {@link #woodRepairMaterial(String)} */
    @Deprecated
    public int woodRepairDurabilityPerWood() {
        if (woodRepairMaterials.isEmpty()) {
            return 200;
        }
        return woodRepairMaterials.values().iterator().next().durability();
    }

    /** @deprecated use {@link #woodRepairMaterials()} */
    @Deprecated
    public String compressedWoodCatalogId() {
        return woodRepairMaterials.isEmpty()
                ? "compressed_wood_1x"
                : woodRepairMaterials.keySet().iterator().next();
    }


    /** @deprecated use {@link #overEnchantProfiles()} */
    @Deprecated
    public Map<String, Integer> overEnchantTiers() {
        return Map.of();
    }

    /** @deprecated use {@link #overEnchantMaxLevel} */
    @Deprecated
    public int overEnchantFortuneBonus() {
        return 0;
    }

    /** @deprecated use {@link #overEnchantProfiles()} */
    @Deprecated
    public java.util.Set<Enchantment> overEnchantEnchants() {
        return java.util.Set.of();
    }

    @Override
    public boolean load(Plugin plugin) {
        Logger log = plugin.getLogger();
        File file = new File(plugin.getDataFolder(), PATH);
        if (!file.exists()) {
            plugin.saveResource(PATH, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException | IOException ex) {
            log.log(Level.SEVERE, "[" + PATH + "] YAML error: " + ex.getMessage(), ex);
            return false;
        }

        loadCoating(yaml);
        loadWoodRepair(yaml);
        loadDisassembly(yaml);
        loadPotionMerge(yaml, log);
        loadBrewUnlocks(yaml, log);
        loadOverEnchant(yaml, log);
        loadThreadSlots(yaml);

        List<String> removed = new ArrayList<>();
        for (String raw : yaml.getStringList("removed-vanilla-recipes")) {
            if (raw != null && !raw.isBlank()) {
                removed.add(raw.trim());
            }
        }
        this.removedVanillaRecipes = List.copyOf(removed);

        List<String> removedItems = new ArrayList<>();
        for (String raw : yaml.getStringList("removed-vanilla-items")) {
            if (raw != null && !raw.isBlank()) {
                removedItems.add(raw.trim());
            }
        }
        this.removedVanillaItems = List.copyOf(removedItems);

        loadAddedRecipes(yaml, log);

        log.info("[" + PATH + "] loaded OK");
        return true;
    }

    private void loadCoating(YamlConfiguration yaml) {
        ConfigurationSection coating = yaml.getConfigurationSection("coating");
        if (coating == null) {
            return;
        }
        this.coatingBaseMaxStacks = Math.max(1, coating.getInt("base-max-stacks", 3));
        this.coatingLegacyBonusPerStack = Math.max(0.0, coating.getDouble("bonus-damage-per-stack", 2.0));

        Map<String, CoatingMaterial> mats = new LinkedHashMap<>();
        ConfigurationSection matSec = coating.getConfigurationSection("materials");
        if (matSec != null) {
            for (String id : matSec.getKeys(false)) {
                ConfigurationSection entry = matSec.getConfigurationSection(id);
                if (entry == null) {
                    continue;
                }
                double dmg = Math.max(0.0, entry.getDouble("bonus-damage", coatingLegacyBonusPerStack));
                int max = Math.max(1, entry.getInt("max-stacks", coatingBaseMaxStacks));
                mats.put(id, new CoatingMaterial(dmg, max));
            }
        }
        // Legacy single gem-catalog-id
        if (mats.isEmpty()) {
            String legacyId = coating.getString("gem-catalog-id", "source_gem");
            if (legacyId != null && !legacyId.isBlank()) {
                mats.put(legacyId, new CoatingMaterial(coatingLegacyBonusPerStack, coatingBaseMaxStacks));
            }
        } else if (!mats.isEmpty()) {
            this.coatingLegacyBonusPerStack = mats.values().iterator().next().bonusDamage();
        }
        this.coatingMaterials = Collections.unmodifiableMap(mats);
    }

    private void loadWoodRepair(YamlConfiguration yaml) {
        ConfigurationSection wood = yaml.getConfigurationSection("wood-repair");
        if (wood == null) {
            return;
        }
        Map<String, WoodRepairMaterial> mats = new LinkedHashMap<>();
        ConfigurationSection matSec = wood.getConfigurationSection("materials");
        if (matSec != null) {
            for (String id : matSec.getKeys(false)) {
                ConfigurationSection entry = matSec.getConfigurationSection(id);
                if (entry == null) {
                    // allow materials: { id: 200 } shorthand
                    int dur = Math.max(1, matSec.getInt(id, 0));
                    if (dur > 0) {
                        mats.put(id, new WoodRepairMaterial(dur, false));
                    }
                    continue;
                }
                mats.put(id, new WoodRepairMaterial(
                        Math.max(1, entry.getInt("durability", 200)),
                        entry.getBoolean("quick-repair", false)));
            }
        }
        if (mats.isEmpty()) {
            String legacyId = wood.getString("compressed-wood-catalog-id", "compressed_wood_1x");
            int dur = Math.max(1, wood.getInt("durability-per-compressed-wood", 200));
            if (legacyId != null && !legacyId.isBlank()) {
                mats.put(legacyId, new WoodRepairMaterial(dur, false));
            }
        }
        this.woodRepairMaterials = Collections.unmodifiableMap(mats);
    }

    private void loadDisassembly(YamlConfiguration yaml) {
        ConfigurationSection dis = yaml.getConfigurationSection("disassembly");
        if (dis == null) {
            return;
        }
        this.disassemblyPercentPerLevel = Math.max(0, dis.getInt("percent-per-level", 25));

        Map<String, List<DisassemblyRule>> items = new LinkedHashMap<>();
        ConfigurationSection itemSec = dis.getConfigurationSection("items");
        if (itemSec != null) {
            for (String target : itemSec.getKeys(false)) {
                List<Map<?, ?>> rawRules = itemSec.getMapList(target);
                List<DisassemblyRule> rules = new ArrayList<>();
                for (Map<?, ?> raw : rawRules) {
                    Object inputRaw = raw.get("input");
                    Object outputRaw = raw.get("output");
                    if (!(inputRaw instanceof String input) || input.isBlank()
                            || !(outputRaw instanceof String output) || output.isBlank()) {
                        continue;
                    }
                    double multiplier = raw.get("multiplier") instanceof Number n ? n.doubleValue() : 1.0;
                    if (Double.isFinite(multiplier) && multiplier > 0.0) {
                        rules.add(new DisassemblyRule(input.trim(), output.trim(), multiplier));
                    }
                }
                if (!rules.isEmpty()) items.put(target, List.copyOf(rules));
            }
        }
        this.disassemblyItems = Collections.unmodifiableMap(items);
    }

    private void loadPotionMerge(YamlConfiguration yaml, Logger log) {
        ConfigurationSection merge = yaml.getConfigurationSection("potion-merge");
        if (merge != null) {
            this.potionMergeMaxEffects = Math.max(1, merge.getInt("max-effects", 5));
            this.potionMergeMaxDurationSeconds = Math.max(1, merge.getInt("max-duration-seconds", 960));
        }
        this.potionMergeTiers = parsePotionMergeTiers(
                merge == null ? null : merge.getConfigurationSection("tiers"), log);
    }

    /**
     * {@code potion-merge.tiers: {<tier>: {max-effects: N, max-duration-seconds: N}}} (2026-07-26
     * tier-expand)。Absent/empty section yields {@link TierTable#empty()} (省略時は完全後方互換)。
     * {@code tier} はスキルツリーのノード value から決まる(feature:potion-merge は
     * {@code FeatureEffectParam.SCALE} — value省略時はtier1が自動補完される)。
     */
    private static TierTable<PotionMergeTierValues> parsePotionMergeTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return TierTable.empty();
        }
        Map<Integer, PotionMergeTierValues> rows = new LinkedHashMap<>();
        for (String tierKey : section.getKeys(false)) {
            int tier;
            try {
                tier = Integer.parseInt(tierKey.trim());
                if (tier <= 0) {
                    log.warning("[" + PATH + "] 'potion-merge.tiers." + tierKey + "' key must be a positive integer; skipped");
                    continue;
                }
            } catch (NumberFormatException ex) {
                log.warning("[" + PATH + "] 'potion-merge.tiers." + tierKey + "' key is not an integer; skipped");
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(tierKey);
            if (row == null) {
                log.warning("[" + PATH + "] 'potion-merge.tiers." + tierKey + "' is not a map; row skipped");
                continue;
            }
            int maxEffects = row.getInt("max-effects", 0);
            int maxDurationSeconds = row.getInt("max-duration-seconds", 0);
            if (maxEffects <= 0 || maxDurationSeconds <= 0) {
                log.warning("[" + PATH + "] 'potion-merge.tiers." + tierKey
                        + "' must have positive max-effects and max-duration-seconds; row skipped");
                continue;
            }
            rows.put(tier, new PotionMergeTierValues(maxEffects, maxDurationSeconds));
        }
        return TierTable.of(rows);
    }

    private void loadBrewUnlocks(YamlConfiguration yaml, Logger log) {
        Map<String, BrewUnlockGroup> groups = new LinkedHashMap<>();
        ConfigurationSection root = yaml.getConfigurationSection("brew-unlocks");
        if (root == null) {
            this.brewUnlocks = Map.of();
            return;
        }
        for (String gid : root.getKeys(false)) {
            ConfigurationSection group = root.getConfigurationSection(gid);
            if (group == null) {
                continue;
            }
            List<BrewPotionSpec> potions = new ArrayList<>();
            List<Map<?, ?>> rawList = group.getMapList("potions");
            for (Map<?, ?> raw : rawList) {
                Object baseObj = raw.get("base");
                Object ingObj = raw.get("ingredient");
                Object resultObj = raw.get("result");
                if (!(resultObj instanceof Map<?, ?> result)) {
                    continue;
                }
                String base = baseObj == null ? "AWKWARD" : String.valueOf(baseObj);
                String ingredient = ingObj == null ? "" : String.valueOf(ingObj);
                String typeName = result.get("type") == null ? "" : String.valueOf(result.get("type"));
                PotionEffectType type = resolvePotionEffectType(typeName);
                if (type == null) {
                    log.warning("[" + PATH + "] brew potion unknown type '" + typeName + "' in " + gid);
                    continue;
                }
                int duration = Math.max(1, toInt(result.get("duration"), 3600));
                int amplifier = Math.max(0, toInt(result.get("amplifier"), 0));
                potions.add(new BrewPotionSpec(base, ingredient, type, duration, amplifier));
            }
            groups.put(gid, new BrewUnlockGroup(List.copyOf(potions)));
        }
        this.brewUnlocks = Collections.unmodifiableMap(groups);
    }

    private void loadOverEnchant(YamlConfiguration yaml, Logger log) {
        ConfigurationSection oe = yaml.getConfigurationSection("over-enchant");
        if (oe == null) {
            this.overEnchantProfiles = Map.of();
            return;
        }

        Map<String, Map<Enchantment, Integer>> profiles = new LinkedHashMap<>();

        // New shape: over-enchant.<effectId>.enchants.<ENCHANT>: maxLevel
        boolean sawNew = false;
        for (String key : oe.getKeys(false)) {
            if ("tiers".equals(key) || "fortune-cap-bonus".equals(key) || "enchants".equals(key)
                    || "profiles".equals(key)) {
                continue;
            }
            ConfigurationSection profile = oe.getConfigurationSection(key);
            if (profile == null) {
                continue;
            }
            ConfigurationSection enchSec = profile.getConfigurationSection("enchants");
            if (enchSec == null) {
                continue;
            }
            sawNew = true;
            Map<Enchantment, Integer> caps = parseEnchantCaps(enchSec, log);
            if (!caps.isEmpty()) {
                profiles.put(key, Collections.unmodifiableMap(caps));
            }
        }

        // Optional profiles: wrapper
        ConfigurationSection profilesSec = oe.getConfigurationSection("profiles");
        if (profilesSec != null) {
            for (String key : profilesSec.getKeys(false)) {
                ConfigurationSection profile = profilesSec.getConfigurationSection(key);
                if (profile == null) {
                    continue;
                }
                ConfigurationSection enchSec = profile.getConfigurationSection("enchants");
                if (enchSec == null) {
                    continue;
                }
                sawNew = true;
                Map<Enchantment, Integer> caps = parseEnchantCaps(enchSec, log);
                if (!caps.isEmpty()) {
                    profiles.put(key, Collections.unmodifiableMap(caps));
                }
            }
        }

        // Legacy: tiers + shared enchants list → absolute max = vanillaMax + tier (+ fortune bonus)
        if (!sawNew) {
            ConfigurationSection tierSec = oe.getConfigurationSection("tiers");
            List<String> enchNames = oe.getStringList("enchants");
            int fortuneBonus = Math.max(0, oe.getInt("fortune-cap-bonus", 1));
            if (tierSec != null && !enchNames.isEmpty()) {
                for (String tid : tierSec.getKeys(false)) {
                    int bonus = Math.max(0, tierSec.getInt(tid, 0));
                    Map<Enchantment, Integer> caps = new LinkedHashMap<>();
                    for (String raw : enchNames) {
                        Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT)));
                        if (ench == null) {
                            continue;
                        }
                        int add = ench.equals(Enchantment.FORTUNE) ? fortuneBonus : bonus;
                        caps.put(ench, ench.getMaxLevel() + add);
                    }
                    if (!caps.isEmpty()) {
                        profiles.put(tid, Collections.unmodifiableMap(caps));
                    }
                }
            }
        }

        this.overEnchantProfiles = Collections.unmodifiableMap(profiles);
    }

    private Map<Enchantment, Integer> parseEnchantCaps(ConfigurationSection enchSec, Logger log) {
        Map<Enchantment, Integer> caps = new LinkedHashMap<>();
        for (String raw : enchSec.getKeys(false)) {
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(raw.toLowerCase(Locale.ROOT)));
            if (ench == null) {
                log.warning("[" + PATH + "] unknown enchant '" + raw + "' in over-enchant");
                continue;
            }
            int max = Math.max(1, enchSec.getInt(raw, ench.getMaxLevel()));
            caps.put(ench, max);
        }
        return caps;
    }

    private void loadThreadSlots(YamlConfiguration yaml) {
        ConfigurationSection threadSlots = yaml.getConfigurationSection("thread-slots");
        if (threadSlots == null) {
            return;
        }
        Map<String, Integer> caps = new LinkedHashMap<>();
        caps.put(EquipmentSlotResolver.CATEGORY_ARMOR, 5);
        caps.put(EquipmentSlotResolver.CATEGORY_WEAPON, 0);
        caps.put(EquipmentSlotResolver.CATEGORY_TOOL, 0);
        caps.put(EquipmentSlotResolver.CATEGORY_OTHER, 0);
        ConfigurationSection byCat = threadSlots.getConfigurationSection("max-by-category");
        if (byCat != null) {
            for (String key : byCat.getKeys(false)) {
                caps.put(key, Math.max(0, byCat.getInt(key, caps.getOrDefault(key, 0))));
            }
        }
        this.threadSlotMaxByCategory = Collections.unmodifiableMap(caps);
    }

    /**
     * Parses {@code added-recipes}: a list of extra Bukkit workbench/inventory recipes whose result
     * is a plain vanilla {@link Material} (no catalog identity). Reuses
     * {@link com.trinityforge.stats.RecipeSpec} / {@link com.trinityforge.stats.RecipeIngredient} —
     * the same ingredient token semantics (Material / {@code custom:<id>} / {@code list:<id>}) as
     * catalog recipes. Each entry is parsed independently; a malformed entry is logged and skipped
     * without aborting the rest of the list (fail-soft, same convention as disassembly/brew parsing).
     */
    private void loadAddedRecipes(YamlConfiguration yaml, Logger log) {
        List<Map<?, ?>> rawList = yaml.getMapList("added-recipes");
        List<AddedRecipe> parsed = new ArrayList<>();
        for (Map<?, ?> raw : rawList) {
            try {
                Object resultObj = raw.get("result");
                if (resultObj == null) {
                    log.warning("[" + PATH + "] added-recipes entry missing 'result'; skipped");
                    continue;
                }
                Material result = Material.matchMaterial(String.valueOf(resultObj));
                if (result == null) {
                    log.warning("[" + PATH + "] added-recipes entry has unknown result material '"
                            + resultObj + "'; skipped");
                    continue;
                }
                int amount = Math.max(1, toInt(raw.get("amount"), 1));
                com.trinityforge.stats.RecipeSpec.Method method =
                        "inventory".equalsIgnoreCase(String.valueOf(raw.get("method")))
                                ? com.trinityforge.stats.RecipeSpec.Method.INVENTORY
                                : com.trinityforge.stats.RecipeSpec.Method.WORKBENCH;
                boolean shapeless = "shapeless".equalsIgnoreCase(String.valueOf(raw.get("type")));

                com.trinityforge.stats.RecipeSpec spec;
                if (shapeless) {
                    Object ingredientsObj = raw.get("ingredients");
                    if (!(ingredientsObj instanceof List<?> ingredientList) || ingredientList.isEmpty()) {
                        log.warning("[" + PATH + "] added-recipes entry for '" + result
                                + "' is shapeless but has no 'ingredients' list; skipped");
                        continue;
                    }
                    List<com.trinityforge.stats.RecipeIngredient> ingredients = new ArrayList<>();
                    for (Object token : ingredientList) {
                        ingredients.add(com.trinityforge.stats.RecipeIngredient.parse(String.valueOf(token)));
                    }
                    spec = com.trinityforge.stats.RecipeSpec.shapeless(method, ingredients, amount);
                } else {
                    Object shapeObj = raw.get("shape");
                    if (!(shapeObj instanceof List<?> shapeList) || shapeList.isEmpty()) {
                        log.warning("[" + PATH + "] added-recipes entry for '" + result
                                + "' is shaped but has no 'shape' list; skipped");
                        continue;
                    }
                    List<String> shape = new ArrayList<>();
                    for (Object row : shapeList) {
                        shape.add(String.valueOf(row));
                    }
                    Object ingredientsObj = raw.get("ingredients");
                    if (!(ingredientsObj instanceof Map<?, ?> ingredientsMap)) {
                        log.warning("[" + PATH + "] added-recipes entry for '" + result
                                + "' is shaped but has no 'ingredients' map; skipped");
                        continue;
                    }
                    Map<Character, com.trinityforge.stats.RecipeIngredient> ingredients = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : ingredientsMap.entrySet()) {
                        String symbolStr = String.valueOf(entry.getKey());
                        if (symbolStr.isEmpty()) {
                            continue;
                        }
                        ingredients.put(symbolStr.charAt(0),
                                com.trinityforge.stats.RecipeIngredient.parse(String.valueOf(entry.getValue())));
                    }
                    spec = com.trinityforge.stats.RecipeSpec.shaped(method, shape, ingredients, amount);
                }
                parsed.add(new AddedRecipe(result, amount, spec));
            } catch (RuntimeException ex) {
                log.warning("[" + PATH + "] failed to parse added-recipes entry '" + raw + "': "
                        + ex.getMessage() + "; skipped");
            }
        }
        this.addedRecipes = List.copyOf(parsed);
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * Resolves potion effect type names including legacy aliases ({@code FAST_DIGGING} → haste).
     * 実装は共有ユーティリティ {@link com.trinityforge.config.PotionEffectTypes#resolve(String)} に委譲。
     */
    public static PotionEffectType resolvePotionEffectType(String typeName) {
        return com.trinityforge.config.PotionEffectTypes.resolve(typeName);
    }
}
