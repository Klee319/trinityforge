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

    /**
     * 解体の戻り先候補1件 (2026-07-27)。{@code weight} は同一ルール内の相対重みで、
     * 1ルールにつき<b>1件だけ</b>当たる(全部が出るのではない)。
     *
     * @param item       戻すアイテム({@code Material} 名 または {@code custom:<カタログID>})
     * @param weight     抽選の相対重み(0以下は候補から外れる)
     * @param multiplier この候補が当たったときの戻り量倍率
     */
    public record DisassemblyOutput(String item, double weight, double multiplier) {}

    /**
     * A single return conversion for a disassembly target series.
     *
     * @param input      戻り量の基準にする「レシピ上の材料」({@code Material} / {@code list:<id>} /
     *                   {@code custom:<id>})。{@code baseAmount} を指定した場合は参照されない。
     * @param outputs    戻り先候補。1件なら従来どおり確定、複数なら {@code weight} で1件を抽選する。
     * @param multiplier ルール既定の戻り量倍率(候補側が {@code multiplier} を持たない場合に使う)。
     * @param baseAmount レシピを引かずに材料数を直接与える (2026-07-27)。{@code null} なら従来どおり
     *                   レシピから数える。<b>クラフトレシピを持たないアイテム</b>(釣りのゴミ等)は
     *                   レシピ由来の材料数が必ず 0 になり戻りが発生しないので、この指定が唯一の手段。
     */
    public record DisassemblyRule(String input, List<DisassemblyOutput> outputs, double multiplier,
                                  Double baseAmount) {

        public DisassemblyRule {
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }

        /** 旧2値コンストラクタ相当(単一 output・レシピ由来の材料数)。既存の呼び出し/テスト用。 */
        public DisassemblyRule(String input, String output, double multiplier) {
            this(input, List.of(new DisassemblyOutput(output, 1.0, multiplier)), multiplier, null);
        }

        /** 単一候補時代の互換アクセサ。候補が無ければ {@code null}。 */
        public String output() {
            return outputs.isEmpty() ? null : outputs.get(0).item();
        }

        /** {@code baseAmount} が指定されているか(＝レシピを引かない)。 */
        public boolean hasBaseAmount() {
            return baseAmount != null;
        }

        /**
         * {@code roll} (0.0以上1.0未満) で候補を1件選ぶ。候補が1件ならその候補、
         * 空なら {@code null}。重みの合計が0以下(全候補が無効重み)の場合も {@code null}
         * — 呼び出し側は「戻りなし」として扱い、素材を消費してはいけない。
         */
        public DisassemblyOutput pick(double roll) {
            if (outputs.isEmpty()) {
                return null;
            }
            if (outputs.size() == 1) {
                return outputs.get(0).weight() > 0.0 ? outputs.get(0) : null;
            }
            double total = 0.0;
            for (DisassemblyOutput candidate : outputs) {
                if (candidate.weight() > 0.0) {
                    total += candidate.weight();
                }
            }
            if (total <= 0.0) {
                return null;
            }
            double cursor = Math.max(0.0, Math.min(roll, 1.0)) * total;
            for (DisassemblyOutput candidate : outputs) {
                if (candidate.weight() <= 0.0) {
                    continue;
                }
                cursor -= candidate.weight();
                if (cursor <= 0.0) {
                    return candidate;
                }
            }
            // 浮動小数の丸めで cursor が僅かに残った場合の保険(最後の有効候補)。
            for (int i = outputs.size() - 1; i >= 0; i--) {
                if (outputs.get(i).weight() > 0.0) {
                    return outputs.get(i);
                }
            }
            return null;
        }
    }

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
    /**
     * {@code disassembly.tiers.<level>.percent} (2026-07-28 数値のギミックyml集約)。キーは解体レベル
     * ({@code dismantle-unlock} の value、{@code FeatureEffectParam.LEVEL})の<b>完全一致のみ</b> —
     * digging/smithingのtierテーブルと違い「以下で最大」フォールバックはしない({@link #disassemblyPercentFor}
     * のjavadoc参照)。未定義キーは {@link #disassemblyPercentPerLevel} × level の線形式へ後方互換フォールバック。
     */
    private volatile Map<Integer, Integer> disassemblyPercentTiers = Map.of();
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
    /**
     * {@code thread-slots.max-by-category} の既定値。
     *
     * <p>この初期値が実際に使われるのは <b>{@code thread-slots} セクションが無い/読めない config
     * だけ</b>である(セクションがあれば {@link #loadThreadSlots} が seed 後に上書きする)。
     * 出荷 yml は 4 キーすべてを明示しているので、稼働サーバではこの値は効かない。
     * 詳しい経緯は {@link #DEFAULT_THREAD_SLOT_CAP} の javadoc。
     */
    private volatile Map<String, Integer> threadSlotMaxByCategory =
            Collections.unmodifiableMap(defaultThreadSlotCaps());
    /** Vanilla/datapack recipe keys to unregister (e.g. {@code minecraft:iron_sword}). */
    private volatile List<String> removedVanillaRecipes = List.of();
    /**
     * Raw {@code removed-vanilla-items} entries ({@code Material} or {@code Material:enchant_id}).
     * Parsing into matchers is delegated to {@link com.trinityforge.stats.VanillaItemRemover}.
     */
    private volatile List<String> removedVanillaItems = List.of();
    /** {@code added-recipes}: extra Bukkit recipes whose result is a plain vanilla Material. */
    private volatile List<AddedRecipe> addedRecipes = List.of();
    /** {@code recipe-book.reveal-plugin-recipes} — 既定 true。 */
    private volatile boolean recipeBookRevealPluginRecipes = true;
    /** {@code recipe-book.hide-locked-recipes} — 既定 true。 */
    private volatile boolean recipeBookHideLockedRecipes = true;

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

    /**
     * 解体レベル {@code level} に対する戻り総%を解決する。{@code disassembly.tiers} に {@code level} と
     * <b>完全一致</b>する行があればその {@code percent} を、無ければ {@link #disassemblyPercentPerLevel()}
     * {@code × level}(従来の線形式)を返す。digging/smithingのtierテーブルと違い「level以下で最大の行へ
     * フォールバック」はしない設計判断 — dismantle-unlock は既に線形式という連続的な既定を持つため、
     * floorフォールバックを重ねると「未定義レベルの戻り率が近傍のtierへ勝手に引き寄せられる」曖昧さが
     * 増えるだけで得るものが無い(tiersが完全に未設定なら数値は1ビットも変わらない — 既存テストで担保)。
     */
    public int disassemblyPercentFor(int level) {
        Integer exact = disassemblyPercentTiers.get(level);
        if (exact != null) {
            return exact;
        }
        return disassemblyPercentPerLevel * level;
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

    /**
     * {@code recipe-book.reveal-plugin-recipes}(既定 {@code true}): ログイン時に TF / ArsPaper の
     * 登録レシピをプレイヤーのレシピ帳へ解禁するか。
     *
     * <p><b>false へ戻しても既に解禁されたレシピは消えない</b>(レシピ帳の解禁状態は playerdata に
     * 永続するため)。false は「今後新しく解禁しない」という意味であり、隠し直しはしない
     * ({@code RecipeDiscoveryListener} の javadoc 参照)。
     */
    public boolean recipeBookRevealPluginRecipes() {
        return recipeBookRevealPluginRecipes;
    }

    /**
     * {@code recipe-book.hide-locked-recipes}(既定 {@code true}): {@code recipe:<id>} ゲートが
     * 未解放のレシピをレシピ帳から隠すか({@code undiscoverRecipes})。
     *
     * <p>false にすると「レシピ帳に出るのにクラフトすると結果枠が空になる」状態
     * ({@code CatalogCraftGateListener} が結果を消す)になるので、既定の true を推奨する。
     */
    public boolean recipeBookHideLockedRecipes() {
        return recipeBookHideLockedRecipes;
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
        loadDisassembly(yaml, log);
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
        loadRecipeBook(yaml);

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

    private void loadDisassembly(YamlConfiguration yaml, Logger log) {
        ConfigurationSection dis = yaml.getConfigurationSection("disassembly");
        if (dis == null) {
            this.disassemblyPercentTiers = Map.of();
            return;
        }
        this.disassemblyPercentPerLevel = Math.max(0, dis.getInt("percent-per-level", 25));
        this.disassemblyPercentTiers = parseDisassemblyPercentTiers(dis.getConfigurationSection("tiers"), log);

        Map<String, List<DisassemblyRule>> items = new LinkedHashMap<>();
        ConfigurationSection itemSec = dis.getConfigurationSection("items");
        if (itemSec != null) {
            for (String target : itemSec.getKeys(false)) {
                List<Map<?, ?>> rawRules = itemSec.getMapList(target);
                List<DisassemblyRule> rules = new ArrayList<>();
                for (Map<?, ?> raw : rawRules) {
                    DisassemblyRule rule = parseDisassemblyRule(raw);
                    if (rule != null) {
                        rules.add(rule);
                    }
                }
                if (!rules.isEmpty()) items.put(target, List.copyOf(rules));
            }
        }
        this.disassemblyItems = Collections.unmodifiableMap(items);
    }

    /**
     * {@code disassembly.tiers: {<level>: {percent: N}}} (2026-07-28)。Absent/empty section yields an
     * empty map(＝完全後方互換、線形式のみ使われる)。level キーが正の整数でない、または {@code percent}
     * が欠落/負値の行は警告を出して skip する(他のtierテーブルparseと同じ fail-soft 方針)。
     */
    private static Map<Integer, Integer> parseDisassemblyPercentTiers(ConfigurationSection section, Logger log) {
        if (section == null) {
            return Map.of();
        }
        Map<Integer, Integer> rows = new LinkedHashMap<>();
        for (String levelKey : section.getKeys(false)) {
            int level;
            try {
                level = Integer.parseInt(levelKey.trim());
                if (level <= 0) {
                    log.warning("[" + PATH + "] 'disassembly.tiers." + levelKey + "' key must be a positive integer; skipped");
                    continue;
                }
            } catch (NumberFormatException ex) {
                log.warning("[" + PATH + "] 'disassembly.tiers." + levelKey + "' key is not an integer; skipped");
                continue;
            }
            ConfigurationSection row = section.getConfigurationSection(levelKey);
            int percent = row == null ? -1 : row.getInt("percent", -1);
            if (percent < 0) {
                log.warning("[" + PATH + "] 'disassembly.tiers." + levelKey + ".percent' must be >= 0; row skipped");
                continue;
            }
            rows.put(level, percent);
        }
        return rows.isEmpty() ? Map.of() : Collections.unmodifiableMap(rows);
    }

    /**
     * 解体ルール1件のパース (2026-07-27 拡張)。壊れた行は {@code null} を返して黙って捨てる
     * (この節の従来からの fail-soft 方針を維持)。
     *
     * <p>受け付ける形:
     * <pre>
     *   - input: IRON_INGOT          # 従来形。レシピからこの材料の個数を数える
     *     output: custom:iron_scrap
     *     multiplier: 2
     *
     *   - base-amount: 1             # レシピを引かない(レシピの無いアイテム用)
     *     outputs:                   # 重み付きで1件だけ当たる
     *       - item: custom:plank_scrap
     *         weight: 3
     *       - item: custom:iron_ingot_scrap
     *         weight: 1
     *         multiplier: 0.5        # 省略時はルールの multiplier
     * </pre>
     * {@code input} と {@code base-amount} の両方が無い行は、戻り量を決める術が無いので捨てる。
     */
    private static DisassemblyRule parseDisassemblyRule(Map<?, ?> raw) {
        double multiplier = raw.get("multiplier") instanceof Number n ? n.doubleValue() : 1.0;
        if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
            return null;
        }

        Double baseAmount = null;
        if (raw.get("base-amount") instanceof Number n) {
            double value = n.doubleValue();
            if (Double.isFinite(value) && value > 0.0) {
                baseAmount = value;
            }
        }

        String input = raw.get("input") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (input == null && baseAmount == null) {
            return null; // 戻り量の基準が無い。
        }

        List<DisassemblyOutput> outputs = new ArrayList<>();
        Object outputsRaw = raw.get("outputs");
        if (outputsRaw instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> entry)) {
                    continue;
                }
                if (!(entry.get("item") instanceof String item) || item.isBlank()) {
                    continue;
                }
                double weight = entry.get("weight") instanceof Number w ? w.doubleValue() : 1.0;
                if (!Double.isFinite(weight) || weight <= 0.0) {
                    continue;
                }
                double entryMultiplier = entry.get("multiplier") instanceof Number m
                        ? m.doubleValue() : multiplier;
                if (!Double.isFinite(entryMultiplier) || entryMultiplier <= 0.0) {
                    entryMultiplier = multiplier;
                }
                outputs.add(new DisassemblyOutput(item.trim(), weight, entryMultiplier));
            }
        }
        // 単一 output は従来形。outputs と併記された場合は outputs を正とし、単一側は候補として足す。
        if (raw.get("output") instanceof String output && !output.isBlank()) {
            String trimmed = output.trim();
            boolean alreadyListed = outputs.stream().anyMatch(o -> o.item().equals(trimmed));
            if (!alreadyListed) {
                outputs.add(new DisassemblyOutput(trimmed, 1.0, multiplier));
            }
        }
        if (outputs.isEmpty()) {
            return null;
        }
        return new DisassemblyRule(input, List.copyOf(outputs), multiplier, baseAmount);
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

    /**
     * {@code recipe-book}(2026-07-31 D7 新設)。セクションを丸ごと省略しても既定 true のままなので、
     * 既存の yml をそのまま読んでも挙動は「解禁する / 未解放は隠す」になる。
     */
    private void loadRecipeBook(YamlConfiguration yaml) {
        ConfigurationSection section = yaml.getConfigurationSection("recipe-book");
        if (section == null) {
            this.recipeBookRevealPluginRecipes = true;
            this.recipeBookHideLockedRecipes = true;
            return;
        }
        this.recipeBookRevealPluginRecipes = section.getBoolean("reveal-plugin-recipes", true);
        this.recipeBookHideLockedRecipes = section.getBoolean("hide-locked-recipes", true);
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

    /**
     * {@code thread-slots} セクションを持つ config で、{@code max-by-category} に
     * <b>書かれていないカテゴリ</b>へ敷く既定の枠上限。
     *
     * <h2>「武器・触媒のスレッド枠が機能しない」(2026-07-31 F2)の正しい因果</h2>
     * <ol>
     *   <li>出荷 yml の cap を 0→5 にしたのは本バッチ前段(Wave 0 / commit {@code 7dca432})の
     *       変更で、これにより {@code ItemAssembler} が lore へ「スレッド枠 N枠」を焼くようになった。</li>
     *   <li>しかし ArsPaper フォーク側の装着 GUI の入口が<b>防具限定</b>で、スレッドのステ収集も
     *       {@code getArmorContents()} 限定だったため、非防具では枠が<b>飾り</b>だった。
     *       <b>これが真因</b>(フォークの commit {@code 331b0c2} で {@code /ars thread} と
     *       メイン/オフハンド収集を入れて解消)。</li>
     *   <li>この定数を 0 から 5 へ揃えたのは<b>無害な防御的整合</b>であって、症状の原因ではない。
     *       {@link #loadThreadSlots} は {@code thread-slots} セクションがあれば既定値を seed した上で
     *       {@code max-by-category} で上書きするので、4 キーが揃っている出荷 yml では実行時の cap は
     *       変更前も後も 5 ——<b>Java 側のフィールド既定値 0 は稼働サーバで一度も効いていない</b>。</li>
     * </ol>
     * ⚠ commit {@code 4c60833} の message には「Java 既定値の drift が症状の原因」という
     * 誤った因果が残っているが、正はこの javadoc の 1〜3。
     *
     * <p>なお {@link com.trinityforge.stats.ThreadSlotPolicy#applyCategoryCap} は
     * <b>cap&le;0 のとき {@code thread-slots} をマップから削除する</b>設計なので、
     * 0 のカテゴリでは lore にも枠が出ない(=スレッド機構ごと無効)。カテゴリ別に違う値を
     * 置くのは正当な調整であり、{@code ShippedThreadSlotCapDriftTest} が禁じるのは
     * 「0 以下」と「Java が知らないカテゴリキー」だけである。
     */
    static final int DEFAULT_THREAD_SLOT_CAP = 5;

    /**
     * Java が知っているスレッド枠カテゴリのキー集合。
     *
     * <p>config に<b>綴りの違うキー</b>({@code weapons} など)を書いても
     * {@link #loadThreadSlots} は素通しでマップへ入れるだけで、
     * どの材質も解決されないので<b>無言で何も起きない</b>(本来直したかった側は既定値のまま)。
     * これが実際に検出したい drift なので、{@code ShippedThreadSlotCapDriftTest} が
     * 出荷 yml のキーをここへ突き合わせる。
     */
    static java.util.Set<String> knownThreadSlotCategories() {
        return defaultThreadSlotCaps().keySet();
    }

    /** {@link #DEFAULT_THREAD_SLOT_CAP} を全カテゴリへ敷いた既定マップ。 */
    private static Map<String, Integer> defaultThreadSlotCaps() {
        Map<String, Integer> caps = new LinkedHashMap<>();
        caps.put(EquipmentSlotResolver.CATEGORY_ARMOR, DEFAULT_THREAD_SLOT_CAP);
        caps.put(EquipmentSlotResolver.CATEGORY_WEAPON, DEFAULT_THREAD_SLOT_CAP);
        caps.put(EquipmentSlotResolver.CATEGORY_TOOL, DEFAULT_THREAD_SLOT_CAP);
        caps.put(EquipmentSlotResolver.CATEGORY_OTHER, DEFAULT_THREAD_SLOT_CAP);
        return caps;
    }

    private void loadThreadSlots(YamlConfiguration yaml) {
        ConfigurationSection threadSlots = yaml.getConfigurationSection("thread-slots");
        if (threadSlots == null) {
            return;
        }
        Map<String, Integer> caps = defaultThreadSlotCaps();
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
