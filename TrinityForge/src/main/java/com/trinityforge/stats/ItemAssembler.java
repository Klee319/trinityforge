package com.trinityforge.stats;

import com.trinityforge.config.domains.AttributeMappingConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.combat.EnchantmentStatBridge;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.AttributeProjection.AttributeModifierSpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Write-side assembly of an addon item: the single place that turns a (rollSeed, quality) pair into
 * a finished {@link ItemMeta}. The drop/craft flows (M3 forks) and the live refresh listener
 * ({@code com.trinityforge.listeners.ItemRefreshListener}) call this so item creation/re-sync has
 * one consistent path (SELECTION_SPEC 5, ADDON_INTEGRATION_SPEC 6).
 *
 * <p>Only rollSeed + quality are persisted to PDC as the derivation inputs — rollSeed is purely the
 * TF-stamp identity marker (ItemRefreshPolicy) and plays no role in stats. Every visible stat is
 * derived live from {@code stats/item-stats.yml} ({@link DerivedItemStats#profileStats}, the SOLE
 * per-item stat source: fixed + per-quality, fully deterministic) and the attribute-mapped subset is
 * mirrored onto the item's vanilla attributes ({@link AttributeProjection} + {@link AttributeApplier},
 * slot-scoped by {@code material}). Nothing is baked: a table edit + {@code /trinityforge reload}
 * re-derives existing items once the refresh listener revisits them, and {@link #assemble} is
 * idempotent/replace-based so calling it again on an already-current item is safe (see
 * {@link TableGeneration} for the generation stamp that lets the listener skip that case).
 *
 * @apiNote The constructor takes {@code (ItemStatsConfig, AttributeMappingConfig, AttributeApplier,
 * LoreConfig, LoreComposer, QualityTiersConfig, TableGeneration, ItemCatalogConfig)}.
 * M3 fork drop/craft callers that build this directly must pass all eight; prefer going through
 * {@code ItemFactory} so item creation stays on one path and future constructor changes do not ripple
 * into forks.
 */
public final class ItemAssembler {

    /**
     * Canonical stat key for the 補助(support)カテゴリの耐久値ステ({@code stats/lore.yml} の
     * {@code durability} エントリと一致)。stats map(fixed/per-quality/random 由来)にこのキーがあれば
     * 旧来の item直下 {@code durability} 上書きより優先される({@link #resolveEffectiveDurability}).
     */
    private static final String DURABILITY_KEY = StatKeys.canonical("durability");

    /**
     * Canonical prefix for a quality-driven tool-enchant stat key (ITEM_ECONOMY_SPEC 5.2b, migrated
     * 2026-07-19 off the separate {@code stats/tool-enchants.yml} config and onto plain item-stats):
     * {@code stats/item-stats.yml} authors {@code tool-enchant-<vanillaEnchantKey>} (e.g.
     * {@code tool-enchant-efficiency}) as a normal fixed/per-quality/random stat, and {@link
     * #applyToolEnchants} floors the resolved value to the vanilla enchant level. The per-quality
     * decimal accumulation IS the threshold mechanic (e.g. per-quality {@code 0.34} -> Efficiency I at
     * quality 3), so no separate threshold config is needed.
     */
    private static final String TOOL_ENCHANT_PREFIX = StatKeys.canonical("tool-enchant-");

    private final ItemStatsConfig itemStats;
    private final AttributeMappingConfig attributeMapping;
    private final AttributeApplier applier;
    private final LoreConfig loreConfig;
    private final LoreComposer loreComposer;
    private final QualityTiersConfig qualityTiers;
    private final TableGeneration tableGeneration;
    private final ItemCatalogConfig itemCatalog;
    private final SkillTreeConfig skillTrees;
    private final CraftingFeaturesConfig craftingFeatures;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ItemAssembler(ItemStatsConfig itemStats,
                         AttributeMappingConfig attributeMapping,
                         AttributeApplier applier,
                         LoreConfig loreConfig,
                         LoreComposer loreComposer,
                         QualityTiersConfig qualityTiers,
                         TableGeneration tableGeneration,
                         ItemCatalogConfig itemCatalog,
                         SkillTreeConfig skillTrees,
                         CraftingFeaturesConfig craftingFeatures) {
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        this.attributeMapping = Objects.requireNonNull(attributeMapping, "attributeMapping");
        this.applier = Objects.requireNonNull(applier, "applier");
        this.loreConfig = Objects.requireNonNull(loreConfig, "loreConfig");
        this.loreComposer = Objects.requireNonNull(loreComposer, "loreComposer");
        this.qualityTiers = Objects.requireNonNull(qualityTiers, "qualityTiers");
        this.tableGeneration = Objects.requireNonNull(tableGeneration, "tableGeneration");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.skillTrees = Objects.requireNonNull(skillTrees, "skillTrees");
        this.craftingFeatures = Objects.requireNonNull(craftingFeatures, "craftingFeatures");
    }

    /**
     * Stamps {@code meta} with its identity and applies its attribute-mapped stats, scoped to the
     * slot inferred from {@code material}.
     *
     * @return the number of attribute modifiers applied (PDC-only stats are not counted)
     */
    public int assemble(ItemMeta meta, Material material, long rollSeed, int quality) {
        Objects.requireNonNull(meta, "meta");
        Objects.requireNonNull(material, "material");
        ItemData data = ItemData.of(meta);
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        // タスクB (2026-07-26 クラフト品質): perQuality/random(乗算レイヤ内含む)を一切持たない
        // プロファイル(fixedのみ、例: 素材/触媒)は品質で値が変動しないので「品質なし」として扱う。
        // バグ修正 (2026-07-26, ユーザー報告): 以前は profile が未定義(null)の場合を判定対象外にし、
        // 既定で品質を「出す」側に倒していた。しかし stats/item-stats.yml にステータス定義が
        // 一切無いカタログアイテム(装飾品・非戦闘アイテム等)にまで★品質ティア名+スコアが
        // 付与されてしまう実害があり、これはクラフト経路 (CraftQualityListener.hasStatsProfile,
        // = itemStats.profileFor(...).isPresent()) の判定基準とも矛盾していた。
        // よってここでも「profile が存在しないアイテムには品質を付けない」に基準を統一する。
        // ここで解決した profile は下の granted/statSources/qualityScore 算出でも使い回す。
        ItemStatProfile profile = itemStats.profileFor(material, cmd)
                .orElseGet(() -> itemStats.fallback().orElse(null));
        boolean qualityApplies = profile != null && profile.qualityApplies();
        int effectiveQuality = qualityApplies ? quality : 0;
        data.setRollSeed(rollSeed);
        data.setQuality(effectiveQuality);
        data.setDataVersion(ItemData.CURRENT_DATA_VERSION);
        data.setTableGeneration(tableGeneration.current());
        // use-level/use-skill の刻印: item-stats が use-skill を欠いているケースがあるため、
        // (a) item-stats 側が完全に設定されていればそれを優先し、(b) そうでなければ
        // catalog template 側の use gate をフォールバックして PDC を揃える。
        // useRequirementFor は level-only 行に UseSkillDefaults でスキルを補完する。
        // 未入力の use-level は 0 扱い。
        itemStats.useRequirementFor(material, cmd)
                .filter(ItemUseRequirement::shouldStamp)
                .ifPresent(req -> data.setUseRequirement(req.skill(), req.levelOrZero()));
        if (data.useSkill().isEmpty()) {
            data.catalogId()
                    .flatMap(itemCatalog::template)
                    .filter(ItemTemplate::hasUseRequirement)
                    .ifPresent(t -> data.setUseRequirement(t.useSkill(), t.useLevelRequirement()));
        }

        // Re-read the clamped quality so derivation matches exactly what was persisted. stats/item-stats.yml
        // is the SOLE per-item stat source (fully deterministic: fixed + per-quality, no roll/material-base
        // layer). Keyed by this item's own material + CustomModelData, so the refresh listener (which has
        // no template, only the item's own PDC/meta) re-derives identically.
        // The crafter's stage-2 (ステータスロール) perk deltas were baked into this item's PDC BEFORE
        // assemble() runs (ItemFactory.stamp), so data.craftRollMods() already reflects them here — apply
        // the same adjusted model as DerivedItemStats.resolve so baked lore matches live combat.
        QualityRollModel effModel = itemStats.rollModel() == null
                ? null : itemStats.rollModel().withCraftMods(data.craftRollMods());
        Map<String, Double> stats = DerivedItemStats.profileStats(
                material, cmd, data.quality(), rollSeed, itemStats, effModel);
        ThreadSlotPolicy.applyCraftBonus(stats, data.ritualThreadSlotBonus());
        ThreadSlotPolicy.applyCategoryCap(stats, material, craftingFeatures.threadSlotMaxByCategory());
        List<AttributeModifierSpec> specs = attributeMapping.projection().project(stats);

        // 耐久値(補助カテゴリのステ)を解決。Damageable を持たないアイテムは対象外(null のまま)。
        // 優先順: stats map の durability(fixed/per-quality/random 由来) > 旧来の item直下 durability 上書き
        // > 未指定(バニラ既定)。lore/実適用の両方でこの一本化した値を使う。
        Integer effectiveDurability = (meta instanceof Damageable)
                ? resolveEffectiveDurability(material, cmd, stats, meta)
                : null;

        // Render the same derived stats into ValhallaMMO-styled lore so the item reads consistently
        // next to ValhallaMMO gear. Driven by stats/lore.yml, so a reload re-styles existing items.
        // Read the layout + table as one snapshot so a concurrent reload can't mismatch them.
        LoreConfig.Snapshot loreSnapshot = loreConfig.snapshot();
        // lore表示用のstats mapは compose に渡す直前にコピーして durability を注入する(属性投影に使った
        // stats本体は汚染しない)。durability は attribute-map.yml に無いため投影対象外で実害は無いが、
        // 念のためコピー側にのみ書く。未指定(override/statどちらも無い)ならバニラ既定の最大耐久を表示する。
        Map<String, Double> loreStats = stats;
        if (meta instanceof Damageable) {
            int displayDurability = effectiveDurability != null ? effectiveDurability : 0;
            loreStats = new LinkedHashMap<>(stats);
            loreStats.put(DURABILITY_KEY, (double) displayDurability);
        }
        java.util.Set<String> granted = DerivedItemStats.resolveGrantedKeys(profile, rollSeed);
        Map<String, StatSource> statSources = StatSourceResolver.resolve(
                profile, data.quality(), rollSeed, effModel, granted);
        int qualityScore = QualityScoreCalculator.score(
                profile, data.quality(), rollSeed, effModel, granted);
        // タスクB優先: 品質が付かないアイテムは(タスクAの「プレビューは品質0でも行を出す」より優先して)
        // 品質ティア名を空にする — LoreComposer.compose の !qualityTierName().isBlank() ゲートを再利用して
        // 品質ティア名+スコアの行そのものを出さない(既存の仕組みの再利用、新規フラグ追加なし)。
        java.util.Optional<QualityTier> tier = qualityApplies
                ? qualityTiers.tierFor(data.quality()) : java.util.Optional.empty();
        String tierName = tier.map(QualityTier::name).orElse("");
        String tierColor = tier.map(QualityTier::color).orElse("");
        String skillDisplay = data.useSkill()
                .map(skill -> skillTrees.all().get(skill) != null
                        ? skillTrees.all().get(skill).displayName() : skill)
                .orElse("");
        int useLevel = data.useLevelRequirement().orElse(0);
        Integer durabilityMax = null;
        Integer durabilityCurrent = null;
        if (meta instanceof Damageable damageable) {
            durabilityMax = effectiveDurability;
            if (durabilityMax != null && durabilityMax > 0) {
                durabilityCurrent = Math.max(0, durabilityMax - damageable.getDamage());
            } else {
                durabilityCurrent = durabilityMax;
            }
        }

        // lore 「デフォルト表示」ON キーは hide-when-zero をバイパス（未解決なら 0 を注入して行を出す）
        java.util.Set<String> forceShow = itemStats.loreDefaultKeysFor(material, cmd);
        if (!forceShow.isEmpty()) {
            if (loreStats == stats) {
                loreStats = new LinkedHashMap<>(stats);
            }
            for (String key : forceShow) {
                loreStats.putIfAbsent(key, 0.0);
            }
        }

        // grant-chances が設定されたキー（高度な色設定の対象）と、乗算モードのレイヤ別倍率
        // (contribution = v-1 で解決されるため lore表示用に +1 して倍率へ戻す)。
        java.util.Set<String> chanceKeys = profile == null
                ? java.util.Set.of() : profile.grantChances().keySet();
        Map<String, Map<String, Double>> loreMultipliers = new LinkedHashMap<>();
        DerivedItemStats.resolveMultipliers(profile, data.quality(), rollSeed, effModel)
                .forEach((layer, contributions) -> {
                    Map<String, Double> values = new LinkedHashMap<>();
                    contributions.forEach((key, contribution) -> values.put(key, contribution + 1.0));
                    loreMultipliers.put(layer, values);
                });

        List<Component> lore = new ArrayList<>();
        data.catalogId()
                .flatMap(itemCatalog::template)
                .map(ItemTemplate::lore)
                .ifPresent(flavorLines -> flavorLines.forEach(line -> lore.add(flavorLore(line))));
        lore.addAll(loreComposer.compose(
                new LoreComposeRequest(loreStats, statSources, tierName, qualityScore,
                        durabilityCurrent, durabilityMax, skillDisplay, useLevel, forceShow,
                        chanceKeys, loreMultipliers, tierColor),
                loreSnapshot.displayTable(), loreSnapshot.layout(), loreSnapshot.bind()));
        appendBindLore(lore, data, loreSnapshot.bind());
        ArsThreadLore.appendTo(meta, lore);
        meta.lore(lore);

        // 個別ステの durability(最大耐久力上書き)を適用。null=上書きなしで setMaxDamage(null) がバニラ既定に
        // リセットするので、reloadで durability を外すと元に戻る(冪等)。Damageable を持たないアイテムは対象外。
        if (meta instanceof Damageable damageable) {
            damageable.setMaxDamage(effectiveDurability);
            meta.setUnbreakable(effectiveDurability == null);
        }

        // Quality-driven, Bedrock-safe tool stats delivered as vanilla enchant levels (ITEM_ECONOMY_SPEC
        // 5.2b): purely derived from the resolved stats map every assemble, so this is inherently
        // idempotent/replace-based (no PDC bonus bookkeeping needed, unlike the old separate config).
        applyToolEnchants(stats, meta, material);
        // アイテムCT: material+CMD 単位の cooldown_group を刻印し、同マテリアル別IDのアイテムとCTゲージを
        // 共有しないようにする (CombatListener/フォークは ItemStack版 set/getCooldown で照会する)。
        applyItemCooldownGroup(stats, meta, material, cmd);
        boolean configWeapon = itemStats.topLevelCategoryFor(material, cmd)
                .filter(EquipmentSlotResolver.CATEGORY_WEAPON::equals)
                .isPresent();
        return applier.apply(meta, material, specs,
                itemStats.offhandStatsApply(material, cmd), configWeapon);
    }

    /** Canonical key for the physical weapon CT stat ({@code item-cooldown}, lore表示名: CT). */
    private static final String ITEM_COOLDOWN_KEY = StatKeys.canonical("item-cooldown");

    /** Namespace of the TF-stamped cooldown groups (only groups we own are ever cleared/overwritten). */
    private static final String COOLDOWN_GROUP_NAMESPACE = "trinityforge";

    /**
     * Stamps (or clears) the vanilla {@code minecraft:use_cooldown} component so the item's CT gauge is
     * scoped per material+CustomModelData instead of the vanilla default (shared per material):
     * {@code Player#setCooldown(ItemStack, int)} / {@code getCooldown(ItemStack)} key the cooldown by the
     * component's {@code cooldown_group} when present. Only items that actually OWN a positive
     * {@code item-cooldown} stat get a group ({@code trinityforge:ct/<material>/<cmd>}), so vanilla
     * item-use cooldown behaviour (ender pearl etc.) is untouched elsewhere. Re-assembly is
     * replace-based: when the stat is removed, a previously TF-stamped group is cleared (never a
     * vanilla/datapack-authored one).
     */
    private static void applyItemCooldownGroup(Map<String, Double> stats, ItemMeta meta,
                                                 Material material, Integer cmd) {
        double seconds = stats.getOrDefault(ITEM_COOLDOWN_KEY, 0.0);
        if (seconds > 0.0 && Double.isFinite(seconds)) {
            String path = "ct/" + material.name().toLowerCase(java.util.Locale.ROOT)
                    + (cmd != null ? "/" + cmd : "");
            org.bukkit.inventory.meta.components.UseCooldownComponent cooldown = meta.getUseCooldown();
            cooldown.setCooldownSeconds((float) seconds);
            cooldown.setCooldownGroup(new NamespacedKey(COOLDOWN_GROUP_NAMESPACE, path));
            meta.setUseCooldown(cooldown);
            return;
        }
        if (meta.hasUseCooldown()) {
            NamespacedKey group = meta.getUseCooldown().getCooldownGroup();
            if (group != null && COOLDOWN_GROUP_NAMESPACE.equals(group.getNamespace())) {
                meta.setUseCooldown(null);
            }
        }
    }

    /**
     * Applies every {@code tool-enchant-<vanillaEnchantKey>} stat in {@code stats} as a vanilla enchant
     * level: the resolved value is floored to an int, and the enchant is set only when that floor is
     * {@code >= 1} AND the enchant is naturally valid for {@code material} ({@link
     * Enchantment#canEnchantItem}) — so e.g. Fortune never lands on a fishing rod. A stat key present but
     * flooring below 1 (or an enchant no longer valid for the material) explicitly clears any previously
     * applied level, keeping re-assembly replace-based rather than additive (never stacks on repeats).
     */
    private static void applyToolEnchants(Map<String, Double> stats, ItemMeta meta, Material material) {
        ItemStack probe = new ItemStack(material);
        java.util.Set<String> granted = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Double> entry : stats.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(TOOL_ENCHANT_PREFIX)) {
                continue;
            }
            String enchantKey = key.substring(TOOL_ENCHANT_PREFIX.length());
            Enchantment enchant = resolveEnchant(enchantKey);
            if (enchant == null) {
                continue;
            }
            int level = (int) Math.floor(entry.getValue());
            if (level >= 1 && enchant.canEnchantItem(probe)) {
                meta.addEnchant(enchant, level, true);
                granted.add(enchantKey);
            } else {
                meta.removeEnchant(enchant);
            }
        }
        // 再組み立ては置換ベース: 前回TFが付与した(PDCに記録済み)が今回のstatsに無いエンチャントは剥がす。
        // テーブルから tool-enchant-* キー自体が消された場合でも古い付与が残らないようにするため。
        // プレイヤーが金床等で付けた無関係のエンチャントには触れない(記録に無いものは対象外)。
        org.bukkit.persistence.PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String previous = pdc.get(TOOL_ENCHANTS_PDC_KEY, org.bukkit.persistence.PersistentDataType.STRING);
        if (previous != null && !previous.isEmpty()) {
            for (String prevKey : previous.split(",")) {
                if (!granted.contains(prevKey)) {
                    Enchantment stale = resolveEnchant(prevKey);
                    if (stale != null) {
                        meta.removeEnchant(stale);
                    }
                }
            }
        }
        if (granted.isEmpty()) {
            pdc.remove(TOOL_ENCHANTS_PDC_KEY);
        } else {
            pdc.set(TOOL_ENCHANTS_PDC_KEY, org.bukkit.persistence.PersistentDataType.STRING,
                    String.join(",", granted));
        }
    }

    /** PDC記録: TFが最後に付与した tool-enchant 由来エンチャントのキー一覧(カンマ区切り)。 */
    private static final NamespacedKey TOOL_ENCHANTS_PDC_KEY =
            new NamespacedKey(COOLDOWN_GROUP_NAMESPACE, "tool-enchants");

    /** Resolves a vanilla {@link Enchantment} from its lower-case minecraft key path (e.g. {@code "efficiency"}). */
    private static Enchantment resolveEnchant(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    /**
     * 実効最大耐久を一本化して解決する。優先順:
     * <ol>
     *   <li>解決済み stats map(fixed/per-quality/random 由来)の {@code durability} — floor して int化し、
     *       最低1にクランプ(0以下や小数でも耐久が消えないように)。</li>
     *   <li>旧来の item直下 {@code durability} 上書き(後方互換。{@link ItemStatProfile#durability}、
     *       profileFor が無ければ fallback から)。</li>
     *   <li>どちらも無ければ {@code null}(={@link Damageable#setMaxDamage} がバニラ既定にリセット)。</li>
     * </ol>
     */
    private Integer resolveEffectiveDurability(Material material, Integer cmd, Map<String, Double> stats,
                                               ItemMeta meta) {
        Integer base = resolveEffectiveDurabilityBase(material, cmd, stats);
        if (base == null || meta == null) {
            return base;
        }
        int adjusted = EnchantmentStatBridge.adjustedDurability(base, enchantDurabilityBonus(meta, material));
        return adjusted;
    }

    private static EnchantmentStatBridge.Bonuses enchantDurabilityBonus(ItemMeta meta, Material material) {
        ItemStack stack = new ItemStack(material);
        stack.setItemMeta(meta);
        return EnchantmentStatBridge.bonuses(stack, null);
    }

    private Integer resolveEffectiveDurabilityBase(Material material, Integer cmd, Map<String, Double> stats) {
        Double statDurability = stats.get(DURABILITY_KEY);
        if (statDurability != null) {
            return Math.max(1, (int) Math.floor(statDurability));
        }
        return itemStats.profileFor(material, cmd)
                .map(ItemStatProfile::durability)
                .orElseGet(() -> {
                    // CMDを渡す: 任意カテゴリの fallback-overrides はアイテムキー(MATERIAL#cmd)単位で
                    // 解決されるため、material のみだと上書きを取り逃がす。
                    Double fromFb = itemStats.fallbackFixedFor(material, cmd).get(DURABILITY_KEY);
                    if (fromFb != null) {
                        return Math.max(1, (int) Math.floor(fromFb));
                    }
                    return null;
                });
    }

    /**
     * 所有者(バインド元)と使用可能レベルのlore行を追記する。テンプレートは {@code stats/lore.yml} の
     * {@code bind:} セクション由来({@link LoreConfig.BindLore})。所有者UUIDは名前解決(オフライン可、未解決なら
     * UUID短縮)。使用可能Lvは 1以上のときだけ表示。
     */
    private void appendBindLore(List<Component> lore, ItemData data, LoreConfig.BindLore bind) {
        if (bind.showOwner()) {
            data.owner().ifPresent(owner -> lore.add(noItalic(miniMessage.deserialize(
                    bind.ownerLine(), Placeholder.unparsed("owner", ownerName(owner))))));
        }
        // 使用可能レベルは LoreComposer が bind.use-requirement-line テンプレート
        // (LoreConfig.BindLore) を使ってスキル表示名付きで出力する。
    }

    /** 所有者UUID→表示名。オフラインでも解決を試み、未解決ならUUIDの先頭8桁にフォールバックする。 */
    private static String ownerName(UUID owner) {
        String name = Bukkit.getOfflinePlayer(owner).getName();
        return name != null && !name.isBlank() ? name : owner.toString().substring(0, 8);
    }

    private Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /**
     * Deserializes one catalog {@code lore:} line as MiniMessage, forcing italic off by default so
     * vanilla's default italic lore styling does not leak in — matches the display-name convention
     * used by {@code ItemFactory.create} and the stat-line convention used by {@link LoreComposer}.
     */
    private Component flavorLore(String line) {
        return miniMessage.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
