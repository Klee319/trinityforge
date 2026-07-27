package com.trinityforge.config;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.AttributeMappingConfig;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.config.domains.CombatLevelConfig;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.DiggingGimmickConfig;
import com.trinityforge.config.domains.DisplayConfig;
import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.config.domains.DungeonThemeConfig;
import com.trinityforge.config.domains.AlchemyQualityConfig;
import com.trinityforge.config.domains.EnchantBookshelfConfig;
import com.trinityforge.config.domains.EnchantLuckConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.config.domains.FishingGimmickConfig;
import com.trinityforge.config.domains.FoodGimmickConfig;
import com.trinityforge.config.domains.AfkConfig;
import com.trinityforge.config.domains.GachaConfig;
import com.trinityforge.config.domains.GatheringEfficiencyConfig;
import com.trinityforge.config.domains.GlyphDamageBoostConfig;
import com.trinityforge.config.domains.HateConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ExternalItemsConfig;
import com.trinityforge.config.domains.MaterialListsConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.LoreConfig;
import com.trinityforge.config.domains.MobImportConfig;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.config.domains.MobProfileConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.config.domains.WoodcuttingGimmickConfig;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.config.domains.SkillTreeConfig;
import com.trinityforge.config.domains.SmithingGimmickConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.BaseStatsConfig;
import com.trinityforge.config.domains.StatCapsConfig;
import com.trinityforge.config.domains.UseRequirementsConfig;
import com.trinityforge.config.domains.VillagerTradesConfig;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Owns every config domain and drives load/reload across all of them.
 * New domains (magic/, dungeon/, ...) register here as they are implemented;
 * the symmetric pipeline and other systems read their typed accessors off this manager.
 *
 * <p>Flat-schema domains ({@link ConfigDomain}) and open-ended table loaders (stat-roll,
 * combat-level) both implement {@link LoadableConfig}, so a single {@link #reloadables} list
 * drives every config uniformly: adding a new config is one {@code register(...)} call.
 */
public final class ConfigManager {

    private final Plugin plugin;
    private final List<LoadableConfig> reloadables = new ArrayList<>();

    private final CombatDamageConfig combatDamage = new CombatDamageConfig();
    private final BaseStatsConfig baseStats = new BaseStatsConfig();
    // 総合ステ上限(2026-07-26新設): PlayerCombatAggregate#totalOf の最終合算値へ上限をかける任意設定。
    // baseStats のすぐ後に register する(依存はないが、関連する設定として並びを揃える)。
    private final StatCapsConfig statCaps = new StatCapsConfig();
    private final QualityConfig quality = new QualityConfig();
    private final QualityTiersConfig qualityTiers = new QualityTiersConfig();
    private final ItemStatsConfig itemStats = new ItemStatsConfig();
    private final CraftQualityConfig craftQuality = new CraftQualityConfig();
    private final SkillExpConfig skillExp = new SkillExpConfig();
    private final CombatLevelConfig combatLevel = new CombatLevelConfig();
    // Cosmetic combat-display toggles (combat/display.yml): focus-HP overlay + damage popup.
    private final DisplayConfig display = new DisplayConfig();
    private final AttributeMappingConfig attributeMapping = new AttributeMappingConfig();
    private final LoreConfig lore = new LoreConfig();
    // 素材互換リスト (items/material-lists.yml): list:<id> ingredient の解決先。
    // itemCatalog より先に register して、同一パスのレシピ登録が読み込み済みリストを見られるようにする。
    private final MaterialListsConfig materialLists = new MaterialListsConfig();
    private final ExternalItemsConfig externalItems = new ExternalItemsConfig();
    private final ItemCatalogConfig itemCatalog = new ItemCatalogConfig();
    private final MobImportConfig mobImport = new MobImportConfig();
    private final MobProfileConfig mobProfiles = new MobProfileConfig();
    // Vanilla EntityType-keyed level/defense/coordinate-scaling/drops (combat/mob-types.yml),
    // entirely independent of the EliteMobs-keyed mobProfiles above.
    private final MobTypesConfig mobTypes = new MobTypesConfig();
    // レベル帯ドロップ削除/追加/バニラEXP/ダンジョン限定テーブル(combat/mob-level-table.yml)。
    // mob-types(フィールド)/mob-profiles(ダンジョン)の両方に、レベル値のみでまたがって適用する。
    private final MobLevelTableConfig mobLevelTable = new MobLevelTableConfig();
    // ダンジョン(ワールド)×モブid単位の強さ/ドロップオーバーライド(combat/mob-overrides.yml,
    // 2026-07-26新設)。mob-profiles.ymlは直接編集せず、この層をその上に重ねる。
    private final MobOverridesConfig mobOverrides = new MobOverridesConfig();
    private final DungeonThemeConfig dungeonThemes = new DungeonThemeConfig();
    private final DungeonGateConfig dungeonGates = new DungeonGateConfig();
    private final HateConfig hate = new HateConfig();
    // Dedicated-effects gate index (2026-07-23 動的ID方式改修): reindex()d from skillTrees after every
    // loadAll pass (see loadAll() below) — no catalog file to load, gate/target derivation is purely from
    // each node's dedicated-effect id prefix (glyph:/recipe:/ritual:/drop:/brew:/trade:/feature:/
    // overenchant:/reward:/ars-tier).
    private final DedicatedEffectsConfig dedicatedEffects = new DedicatedEffectsConfig();
    private final SkillTreeConfig skillTrees = new SkillTreeConfig();
    private final GachaConfig gacha = new GachaConfig();
    // AFK(離席)判定と、その間の報酬停止/自動キック(afk.yml, 2026-07-27)。
    private final AfkConfig afk = new AfkConfig();
    // 採掘ギミックflag系consumer(vein-mining/haste-active-mining) + mining drop-table + fortune連続処理
    // (旧 gathering.yml mining.* 統合、2026-07-23 stat-gate-overhaul §D)のチューニング。
    private final MiningGimmickConfig miningGimmick = new MiningGimmickConfig();
    // 伐採ギミックflag系consumer(tree-fell, 2026-07-25 統合済み) + woodcutting drop-tableのチューニング。
    private final WoodcuttingGimmickConfig woodcuttingGimmick = new WoodcuttingGimmickConfig();
    // 掘削ギミックdrop-table(2026-07-23 stat-gate-overhaul §4、新設DiggingGimmickListener向け)のチューニング。
    private final DiggingGimmickConfig diggingGimmick = new DiggingGimmickConfig();
    // 農業/畜産ギミックflag系consumer(area-harvest/animal-damage-4x/bee-no-aggro)のチューニング。
    private final FarmingGimmickConfig farmingGimmick = new FarmingGimmickConfig();
    // 採集効率エンチャント連動方式(2026-07-25、属性ベースを取り下げ再設計)の効率強化レベル上限(既定5)。
    private final GatheringEfficiencyConfig gatheringEfficiency = new GatheringEfficiencyConfig();
    // 農業ツリーA-α/β系「食事」ギミックflag系consumer(junkfood-immunity/junkfood-inversion/
    // satiety-buff)のチューニング。
    private final FoodGimmickConfig foodGimmick = new FoodGimmickConfig();
    // 釣りツリーB-alpha/B-beta系ギミックflag系consumer(junk-to-scrap/fish-sell-toggle)
    // + enchanting.yml B-3のxp-bottle-store-unlock + fishing group-ratio/drop-table + fishing-luck/-bonus
    // 連続処理(旧 gathering.yml fishing.* 統合、2026-07-23 stat-gate-overhaul §2.3/§4/§D)のチューニング。
    private final FishingGimmickConfig fishingGimmick = new FishingGimmickConfig();
    private final UseRequirementsConfig useRequirements = new UseRequirementsConfig();
    // コレクション図鑑 (M7): 記録ソース切替 + 図鑑登録数しきい値の報酬ティア。
    private final CollectionConfig collection = new CollectionConfig();
    // 特殊報酬レジストリ(称号/パーティクル/パーティクルシード, 2026-07-23-stat-gate-overhaul §6.1)。
    private final SpecialRewardsConfig specialRewards = new SpecialRewardsConfig();
    // アチーブメント(2026-07-23-stat-gate-overhaul §6.2)。
    private final AchievementsConfig achievements = new AchievementsConfig();
    private final CraftingFeaturesConfig craftingFeatures = new CraftingFeaturesConfig();
    private final VillagerTradesConfig villagerTrades = new VillagerTradesConfig();
    private final RoleBuffsConfig roleBuffs = new RoleBuffsConfig();
    // エンチャント運(enchant_luck stat)の重み付け(2026-07-25、かまど/エンチャント/ポーション
    // 実行者限定ステ反映)。
    private final EnchantLuckConfig enchantLuck = new EnchantLuckConfig();
    // エンチャントテーブルの本棚パワーconfig化(2026-07-26新設、crafting-features.yml
    // enchant-bookshelf-power サブツリー、over-enchant と同階層)。EnchantCostReductionListener に
    // 相乗りして適用する(専用リスナーは新設しない)。
    private final EnchantBookshelfConfig enchantBookshelf = new EnchantBookshelfConfig();
    // ポーション品質(potion_quality_bonus stat)の時間/強度換算(同上)。
    private final AlchemyQualityConfig alchemyQuality = new AlchemyQualityConfig();
    // かまど精錬速度/精錬ボーナス(smithing.yml A-1〜3/B-1〜3, feature:furnace-smelt-speed/-bonus)の
    // ホッパー自動投入減衰係数(2026-07-25)。
    private final SmithingGimmickConfig smithingGimmick = new SmithingGimmickConfig();
    // 害悪グリフ強化(ars_magic.yml B-3): glyph_damage_multiplier_bonus statの適用対象グリフID一覧
    // (harmに決め打ちしない汎用設計、2026-07-25)。
    private final GlyphDamageBoostConfig glyphDamageBoost = new GlyphDamageBoostConfig();

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        // The quality-tiers list length drives the number of quality steps (Q = 任意段階): the tier
        // count overrides the numeric max-quality when tiers are configured.
        quality.useEffectiveMaxOverride(qualityTiers::effectiveMaxQuality);
        // item-stats' random roll layer (段2) reads its quality-dependent distribution live from
        // quality.yml, so an operator can retune ceiling-at-min-quality/bias-gain via reload.
        itemStats.useRollModel(quality::rollModel);
        register(combatDamage.domain());
        register(baseStats);
        register(statCaps);
        register(quality.domain());
        register(qualityTiers::load);
        register(itemStats::load);
        register(craftQuality::load);
        register(skillExp::load);
        register(combatLevel::load);
        register(display::load);
        register(useRequirements);
        register(attributeMapping);
        // mob-defaults.yml は MobTypesConfig が defaults 欠落時のフォールバックとして直読する。
        // ConfigDomain 登録は廃止（権威は combat/mob-types.yml の defaults:）。
        register(lore);
        register(externalItems);
        register(materialLists);
        register(itemCatalog);
        register(mobImport);
        register(mobProfiles);
        register(mobTypes);
        register(mobLevelTable);
        register(mobOverrides);
        register(dungeonThemes);
        register(dungeonGates);
        register(hate.domain());
        // Dedicated-effects gate index (2026-07-23 動的ID方式改修): no catalog file to load anymore (see
        // DedicatedEffectsConfig#load), registered here only to preserve the pre-existing ordering
        // documentation relative to skillTrees below.
        register(dedicatedEffects);
        // Skill-tree canonical config (SKILL_TREE design). Load/validate/immutable-model only;
        // runtime buff application (PerkBuffResolver) is wired in a later phase.
        register(skillTrees);
        // Gacha ticket prize tables (gacha.yml): config-driven, so tickets/pools/prizes are all
        // editable without a code change or restart (/trinityforge reload picks up edits live).
        register(gacha);
        // AFK対策(afk.yml): 判定タイマーの再スケジュールは TrinityForge#reload 側が行う。
        register(afk);
        register(miningGimmick::load);
        register(woodcuttingGimmick::load);
        register(diggingGimmick::load);
        register(farmingGimmick::load);
        register(gatheringEfficiency::load);
        register(foodGimmick::load);
        register(fishingGimmick::load);
        register(craftingFeatures);
        register(villagerTrades);
        register(roleBuffs);
        register(collection);
        register(specialRewards);
        register(achievements);
        register(enchantLuck.domain());
        register(enchantBookshelf.domain());
        register(alchemyQuality.domain());
        register(smithingGimmick::load);
        register(glyphDamageBoost::load);
        // TODO(M2+): register magic/, pets/ configs here.
    }

    private void register(LoadableConfig reloadable) {
        reloadables.add(reloadable);
    }

    /** Loads (or reloads) every config. Returns the number that reported issues. */
    public int loadAll() {
        int withIssues = 0;
        for (LoadableConfig reloadable : reloadables) {
            try {
                if (!reloadable.load(plugin)) {
                    withIssues++;
                }
            } catch (RuntimeException ex) {
                // One domain throwing (e.g. a missing bundled resource on saveResource, or a schema
                // resolve failure) must not abort the rest of the load or crash onEnable/reload on the
                // main thread. Keep that domain on its in-memory defaults and continue.
                plugin.getLogger().log(Level.SEVERE,
                        "Unhandled exception loading a config domain; defaults remain in effect.", ex);
                withIssues++;
            }
        }
        // Derived gate/flag index (ランタイム配線層 要件④): recomputed last, once every tree is loaded, so
        // it always reflects the just-loaded trees + the just-loaded dedicated-effects catalog together.
        dedicatedEffects.reindex(skillTrees.all().values());
        return withIssues;
    }

    public CombatDamageConfig combatDamage() {
        return combatDamage;
    }

    public QualityConfig quality() {
        return quality;
    }

    public QualityTiersConfig qualityTiers() {
        return qualityTiers;
    }

    public ItemStatsConfig itemStats() {
        return itemStats;
    }

    public CraftQualityConfig craftQuality() {
        return craftQuality;
    }

    public SkillExpConfig skillExp() {
        return skillExp;
    }

    public CombatLevelConfig combatLevel() {
        return combatLevel;
    }

    public DisplayConfig display() {
        return display;
    }

    public UseRequirementsConfig useRequirements() {
        return useRequirements;
    }

    public AttributeMappingConfig attributeMapping() {
        return attributeMapping;
    }

    public LoreConfig lore() {
        return lore;
    }

    public ItemCatalogConfig itemCatalog() {
        return itemCatalog;
    }

    public MobImportConfig mobImport() {
        return mobImport;
    }

    public MobProfileConfig mobProfiles() {
        return mobProfiles;
    }

    public MobTypesConfig mobTypes() {
        return mobTypes;
    }

    public MobLevelTableConfig mobLevelTable() {
        return mobLevelTable;
    }

    public MobOverridesConfig mobOverrides() {
        return mobOverrides;
    }

    public DungeonThemeConfig dungeonThemes() {
        return dungeonThemes;
    }

    public DungeonGateConfig dungeonGates() {
        return dungeonGates;
    }

    public HateConfig hate() {
        return hate;
    }

    public SkillTreeConfig skillTrees() {
        return skillTrees;
    }

    public DedicatedEffectsConfig dedicatedEffects() {
        return dedicatedEffects;
    }

    public GachaConfig gacha() {
        return gacha;
    }

    public AfkConfig afk() {
        return afk;
    }

    public MiningGimmickConfig miningGimmick() {
        return miningGimmick;
    }

    public WoodcuttingGimmickConfig woodcuttingGimmick() {
        return woodcuttingGimmick;
    }

    public DiggingGimmickConfig diggingGimmick() {
        return diggingGimmick;
    }

    public GatheringEfficiencyConfig gatheringEfficiency() {
        return gatheringEfficiency;
    }

    public FarmingGimmickConfig farmingGimmick() {
        return farmingGimmick;
    }

    public FoodGimmickConfig foodGimmick() {
        return foodGimmick;
    }

    public SmithingGimmickConfig smithingGimmick() {
        return smithingGimmick;
    }

    public GlyphDamageBoostConfig glyphDamageBoost() {
        return glyphDamageBoost;
    }

    public FishingGimmickConfig fishingGimmick() {
        return fishingGimmick;
    }

    public CraftingFeaturesConfig craftingFeatures() {
        return craftingFeatures;
    }

    public VillagerTradesConfig villagerTrades() {
        return villagerTrades;
    }

    public RoleBuffsConfig roleBuffs() {
        return roleBuffs;
    }

    public EnchantLuckConfig enchantLuck() {
        return enchantLuck;
    }

    public EnchantBookshelfConfig enchantBookshelf() {
        return enchantBookshelf;
    }

    public AlchemyQualityConfig alchemyQuality() {
        return alchemyQuality;
    }

    public BaseStatsConfig baseStats() {
        return baseStats;
    }

    public StatCapsConfig statCaps() {
        return statCaps;
    }

    public CollectionConfig collection() {
        return collection;
    }

    public SpecialRewardsConfig specialRewards() {
        return specialRewards;
    }

    public AchievementsConfig achievements() {
        return achievements;
    }

    /**
     * Resolves the runtime-effective profile for {@code profileId} at {@code runtimeLevel}.
     * Fixed (non-{@code dynamic}) profiles are returned as baked; a {@code dynamic} profile (e.g. an
     * EliteMobs {@code level: dynamic} mob) is instead rebuilt at the dungeon's actual runtime level
     * (dynamic dungeon level selection), including its theme's policy overrides if any. Called by the
     * EliteMobs fork at spawn/HP-resolution time.
     *
     * <p>A mob with no {@code combat/mob-profiles.yml} entry is synthesized from the global import
     * policy at {@code runtimeLevel} (see {@link #synthesizeUnknownProfile}) unless
     * {@code combat/mob-import.yml unknown-mobs.synthesize} is off, in which case this returns empty as
     * it always used to.
     */
    public java.util.Optional<com.trinityforge.mobs.MobProfile> resolveRuntimeProfile(String profileId,
            int runtimeLevel) {
        return resolveRuntimeProfileBase(normalizeMobId(profileId), runtimeLevel);
    }

    /**
     * Strips a trailing {@code .yml}/{@code .yaml} so every caller lands on the id
     * {@code /trinityforge importmobs} keys {@code combat/mob-profiles.yml} on (and that an operator
     * writes in {@code combat/mob-overrides.yml}).
     *
     * <p>EliteMobs hands out ids via MagmaCore's {@code CustomConfigFields#getFilename()}, which always
     * carries the extension. Normalizing here (rather than only in the fork) keeps profile lookup,
     * variance seeding and the override overlay agreeing on one spelling, and matters more since an
     * unknown id is now synthesized instead of returning empty — a {@code "boss.yml"} lookup that used
     * to fall through to a retry with {@code "boss"} would otherwise silently synthesize a fresh
     * profile and shadow the imported one.
     *
     * <p>Any remaining {@code .} becomes {@code _}, matching {@code EliteMobsImporter#sanitizeId} (a
     * dot is a path separator in a YAML config key, so the importer cannot store it verbatim).
     *
     * <p>2026-07-26 M1 レビュー指摘: この正規化ロジックは {@link com.trinityforge.mobs.MobIdNormalizer}
     * に切り出し、{@code MobOverridesConfig}(operator-authored {@code mob-overrides.yml} のモブidキー)
     * からも同じ実装を再利用する — 以前は {@code mob-overrides.yml} 側だけ未正規化のままで、
     * {@code "boss.yml"} スタンプと {@code "boss"} キーが噛み合わず上書きが無言で不発になっていた。
     */
    private static String normalizeMobId(String profileId) {
        return com.trinityforge.mobs.MobIdNormalizer.normalize(profileId);
    }

    /**
     * Same as {@link #resolveRuntimeProfile(String, int)} but additionally applies a deterministic
     * per-individual variance (厳選幅) to HP and attack-power only, derived from {@code rollSeed}
     * (see {@code RollHash}). Defense/other attack stats are never varied. Called by the EliteMobs
     * fork with the mob's stamped MOB_ROLL_SEED.
     */
    public java.util.Optional<com.trinityforge.mobs.MobProfile> resolveRuntimeProfile(String profileId,
            int runtimeLevel, long rollSeed) {
        String id = normalizeMobId(profileId);
        java.util.Optional<com.trinityforge.mobs.MobProfile> base = resolveRuntimeProfileBase(id, runtimeLevel);
        return base.map(p -> applyVariance(p, id, rollSeed));
    }

    /**
     * Same as {@link #resolveRuntimeProfile(String, int, long)} but additionally overlays
     * {@code combat/mob-overrides.yml} ({@link MobOverridesConfig#resolve}, 2026-07-26 ダンジョン×モブ
     * 単位オーバーライド新設) keyed on the spawning entity's world name. Overrides are applied AFTER the
     * per-individual variance so an explicit operator override always wins over the automatic
     * 厳選幅 roll. {@code worldName} may be {@code null} (treated as "no world-specific
     * scope" — only the {@code default} scope, if any, applies). Back-compat: when
     * {@code combat/mob-overrides.yml} is absent/empty this returns byte-for-byte the same profile as
     * {@link #resolveRuntimeProfile(String, int, long)}.
     */
    public java.util.Optional<com.trinityforge.mobs.MobProfile> resolveRuntimeProfile(String profileId,
            int runtimeLevel, long rollSeed, String worldName) {
        String id = normalizeMobId(profileId);
        java.util.Optional<com.trinityforge.mobs.MobProfile> varied =
                resolveRuntimeProfile(id, runtimeLevel, rollSeed);
        return varied.map(p -> mobOverrides.resolve(worldName, id, p));
    }

    private java.util.Optional<com.trinityforge.mobs.MobProfile> resolveRuntimeProfileBase(String profileId,
            int runtimeLevel) {
        java.util.Optional<com.trinityforge.mobs.MobProfile> baked = mobProfiles().profile(profileId);
        if (baked.isEmpty()) {
            return synthesizeUnknownProfile(profileId, runtimeLevel);
        }
        com.trinityforge.mobs.MobProfile p = baked.get();
        if (!p.dynamic()) {
            return baked; // 固定モブは焼き値そのまま
        }
        com.trinityforge.mobs.ConversionPolicy base = mobImport().policy();
        com.trinityforge.mobs.ConversionPolicy policy = (p.dungeonTheme() == null)
                ? base
                : dungeonThemes().theme(p.dungeonTheme()).map(t -> t.toPolicy(base)).orElse(base);
        return java.util.Optional.of(com.trinityforge.mobs.EliteMobsMobMapping.rebuildAt(profileId,
                p.dungeonTheme(), policy, runtimeLevel));
    }

    /**
     * Derives a profile for an EliteMobs mob that has no {@code combat/mob-profiles.yml} entry —
     * typically a content pack downloaded after the last {@code /trinityforge importmobs} run (2026-07-26
     * 「無料DL枠ダンジョンの敵のドロップ/ステータスを設定できない」修正).
     *
     * <p>The converter never reads a boss file's own numbers: every value comes from
     * {@code combat/mob-import.yml}'s ramps evaluated at the mob's level. So synthesizing here with the
     * mob's actual runtime level yields exactly what an import would have baked for a
     * {@code level: dynamic} mob — while also giving the fork something to stamp, without which
     * {@code combat/mob-overrides.yml} can never target the mob (no {@code MOB_PROFILE_ID} ⇒ the drop
     * override listener skips it, and no {@code MOB_LEVEL} ⇒ no strength override either).
     *
     * <p>The global (untied-to-theme) policy is used: an un-imported mob has no recorded dungeon theme.
     * Returns empty when {@code unknown-mobs.synthesize} is off, restoring the previous behaviour.
     */
    private java.util.Optional<com.trinityforge.mobs.MobProfile> synthesizeUnknownProfile(String profileId,
            int runtimeLevel) {
        if (!mobImport().synthesizeUnknown()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(com.trinityforge.mobs.EliteMobsMobMapping.rebuildAt(profileId, null,
                mobImport().policy(), runtimeLevel));
    }

    /** Resolves the {@link com.trinityforge.mobs.ConversionPolicy} in effect for {@code profileId}. */
    private com.trinityforge.mobs.ConversionPolicy policyFor(com.trinityforge.mobs.MobProfile p) {
        com.trinityforge.mobs.ConversionPolicy base = mobImport().policy();
        return (p.dungeonTheme() == null)
                ? base
                : dungeonThemes().theme(p.dungeonTheme()).map(t -> t.toPolicy(base)).orElse(base);
    }

    /**
     * Applies the deterministic HP/attack variance factor from {@code policy}'s {@link
     * com.trinityforge.mobs.ConversionPolicy#hpVariance()}/{@link
     * com.trinityforge.mobs.ConversionPolicy#attackVariance()} to {@code profile}, keyed on {@code
     * rollSeed}. Only HP (when configured) and attack default-damage (when configured) are varied;
     * defense and other attack fields are untouched.
     */
    private com.trinityforge.mobs.MobProfile applyVariance(com.trinityforge.mobs.MobProfile profile,
            String profileId, long rollSeed) {
        com.trinityforge.mobs.ConversionPolicy policy = policyFor(profile);
        com.trinityforge.mobs.MobProfile result = profile;
        if (profile.hasMaxHealth() && policy.hpVariance() != 0.0) {
            double factor = varianceFactor(policy.hpVariance(), rollSeed, "mob_hp_var");
            result = result.withMaxHealth(result.maxHealth() * factor);
        }
        if (profile.hasAttack() && policy.attackVariance() != 0.0) {
            double factor = varianceFactor(policy.attackVariance(), rollSeed, "mob_atk_var");
            result = result.withAttack(result.attack().withDefaultDamage(result.attack().defaultDamage() * factor));
        }
        return result;
    }

    private double varianceFactor(double variance, long rollSeed, String statKey) {
        double unit = com.trinityforge.stats.RollHash.unitInterval(rollSeed, statKey);
        double factor = 1.0 + variance * (2.0 * unit - 1.0);
        return Math.max(0.0, factor);
    }
}
