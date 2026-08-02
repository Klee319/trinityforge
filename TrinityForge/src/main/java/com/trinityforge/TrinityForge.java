package com.trinityforge;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.trinityforge.active.ActivationDispatcher;
import com.trinityforge.active.ActiveSkillCooldownKeys;
import com.trinityforge.active.ActiveSkillRegistry;
import com.trinityforge.active.CooldownManager;
import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.combat.BleedService;
import com.trinityforge.combat.PlayerDefenseResolver;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.combat.WeaponAttackStatResolver;
import com.trinityforge.command.ActiveCommand;
import com.trinityforge.command.BindCommand;
import com.trinityforge.command.CollectionCommand;
import com.trinityforge.command.DungeonCommand;
import com.trinityforge.command.GiveItemCommand;
import com.trinityforge.command.ImportMobsCommand;
import com.trinityforge.command.InspectCommand;
import com.trinityforge.command.StampCommand;
import com.trinityforge.command.StatsCommand;
import com.trinityforge.command.RoleCommand;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.hate.HateListener;
import com.trinityforge.hate.HateService;
import com.trinityforge.listeners.GrindstonePreserveListener;
import com.trinityforge.listeners.BlacksmithBanListener;
import com.trinityforge.listeners.BrewUnlockListener;
import com.trinityforge.listeners.DisassemblyListener;
import com.trinityforge.listeners.DotDamageListener;
import com.trinityforge.listeners.OverEnchantListener;
import com.trinityforge.listeners.PotionMergeListener;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.listeners.VillagerTradeListener;
import com.trinityforge.listeners.WeaponCoatingListener;
import com.trinityforge.listeners.WoodRepairListener;
import com.trinityforge.listeners.CatalogCraftGateListener;
import com.trinityforge.listeners.CollectionListener;
import com.trinityforge.listeners.AnimalDamageListener;
import com.trinityforge.listeners.BeekeepingListener;
import com.trinityforge.listeners.CombatListener;
import com.trinityforge.listeners.CraftQualityListener;
import com.trinityforge.listeners.ItemDamageClampListener;
import com.trinityforge.listeners.CatalogAnvilListener;
import com.trinityforge.listeners.CatalogSmithingListener;
import com.trinityforge.listeners.CatalogVanillaOperationGuardListener;
import com.trinityforge.listeners.FarmingHarvestListener;
import com.trinityforge.mobs.DungeonGateService;
import com.trinityforge.listeners.DiggingGimmickListener;
import com.trinityforge.listeners.DungeonGateListener;
import com.trinityforge.listeners.FishingGimmickListener;
import com.trinityforge.listeners.EliteMobsCommandGateListener;
import com.trinityforge.listeners.FishingQualityListener;
import com.trinityforge.listeners.FoodGimmickListener;
import com.trinityforge.listeners.GachaListener;
import com.trinityforge.listeners.ItemRefreshListener;
import com.trinityforge.listeners.MagicResistanceFoldListener;
import com.trinityforge.listeners.MiningFortuneListener;
import com.trinityforge.listeners.MiningGimmickListener;
import com.trinityforge.listeners.NativeSkillExperienceListener;
import com.trinityforge.listeners.PlacedBlockTracker;
import com.trinityforge.listeners.ProgressionPreloadListener;
import com.trinityforge.listeners.TreeFellingListener;
import com.trinityforge.listeners.MobLevelTableListener;
import com.trinityforge.listeners.MobOverrideDropListener;
import com.trinityforge.listeners.MobOverrideExpListener;
import com.trinityforge.listeners.MobTypeDropListener;
import com.trinityforge.listeners.MobTransformListener;
import com.trinityforge.listeners.MobTypeSpawnListener;
import com.trinityforge.listeners.PerkMirrorListener;
import com.trinityforge.listeners.OwnerBindListener;
import com.trinityforge.listeners.UseRequirementListener;
import com.trinityforge.listeners.PickupQualityListener;
import com.trinityforge.listeners.VeinMiningListener;
import com.trinityforge.listeners.XpBottleListener;
import com.trinityforge.mining.HasteActiveSkill;
import com.trinityforge.mob.DamagePopupDisplay;
import com.trinityforge.mob.FocusHpDisplay;
import com.trinityforge.mob.MobDisplayNames;
import com.trinityforge.listeners.ArmorUseGateListener;
import com.trinityforge.progression.CollectionService;
import com.trinityforge.progression.NativeProgressionAdminService;
import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.NativeSkillLevelSource;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.progression.UseRequirementService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.infrastructure.CachedProgressionRepository;
import com.trinityforge.progression.infrastructure.ExecutorProgressionRepository;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.progression.repository.ProgressionRepository;
import com.trinityforge.skilltree.runtime.NativeAttributeBridge;
import com.trinityforge.skilltree.runtime.NativeCombatPerkListener;
import com.trinityforge.skilltree.runtime.NativeSurvivalPerkListener;
import com.trinityforge.skilltree.runtime.PerkAttributeApplier;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.PerkMirrorService;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;
import com.trinityforge.skilltree.runtime.NativeSkillPerkStatSource;
import com.trinityforge.skilltree.runtime.NativePerkService;
import com.trinityforge.skilltree.runtime.NativeSkillTreeMenu;
import com.trinityforge.stats.AttributeApplier;
import com.trinityforge.stats.CatalogRitualBridge;
import com.trinityforge.stats.CatalogRecipeRegistrar;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.LoreComposer;
import com.trinityforge.stats.PlayerLootLuckSource;
import com.trinityforge.stats.PlayerMobDropBonusSource;
import com.trinityforge.stats.TableGeneration;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.io.File;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * TrinityForge: MMO server core integrating EliteMobs and ArsPaper with TF-native progression.
 * M0 scope: config-driven foundation (schema-validated load + hot reload + domain-split files).
 * The symmetric damage pipeline, stats, hate, and selection systems build on this in M1+.
 */
public final class TrinityForge extends JavaPlugin {

    private static volatile TrinityForge instance;
    private ConfigManager configManager;
    private DungeonWorldRegistry dungeonWorldRegistry;
    /** 2026-07-30: 被弾/死亡の装備耐久ペナルティ。EliteMobsフォークが死亡側を直接呼ぶ。 */
    private com.trinityforge.durability.EquipmentDurabilityService equipmentDurabilityService;
    private final com.trinityforge.progression.LocationExpDiminishing locationExpDiminishing =
            new com.trinityforge.progression.LocationExpDiminishing();
    private SymmetricCombatService combatService;
    private WeaponAttackStatResolver weaponAttackStatResolver;
    private PlayerStatAggregator playerStatAggregator;
    private com.trinityforge.stats.CrossPluginItemResolver crossPluginItemResolver;
    private SkillLevelSource skillLevelSource;
    private NativeProgressionService progressionService;
    /**
     * 日次EXP逓減の状態保持器(2026-07-31)。プレイヤー×スキルごとに指数移動窓を持つだけなので
     * DB を増やさない。退出時に {@code forget} して有界に保つ。
     */
    private final com.trinityforge.progression.DailyExpDiminishing dailyExpDiminishing =
            new com.trinityforge.progression.DailyExpDiminishing();
    private NativeProgressionAdminService progressionAdminService;
    private NativeExperienceDispatcher experienceDispatcher;
    private ProgressionRepository progressionRepository;
    private NativeSkillCatalog progressionCatalog;
    private NativePerkService nativePerkService;
    private NativeSkillTreeMenu nativeSkillTreeMenu;
    private CraftQualityService craftQualityService;
    private SkillPerkStatSource skillPerkStatSource;
    private PerkMirrorService perkMirrorService;
    private PerkAttributeApplier perkAttributeApplier;
    private com.trinityforge.gathering.GatheringEfficiencyEnchantApplier gatheringEfficiencyApplier;
    /**
     * 一括伐採/一括破壊/範囲収穫の連鎖分へ採取EXPを渡す口(2026-07-28)。
     * {@code NativeSkillExperienceListener} の生成箇所と、それを使うギミックリスナーの登録箇所が
     * 数百行離れているためフィールドで受け渡す。
     */
    private com.trinityforge.gathering.ChainBreakExpGrant chainBreakExpGrant;
    private HateService hateService;
    private DungeonGateService dungeonGateService;
    private BleedService bleedService;
    private FocusHpDisplay focusHpDisplay;
    private DamagePopupDisplay damagePopupDisplay;
    /**
     * packetevents リスナーの登録解除フック(任意依存)。packetevents 不在環境でもこのフィールドの
     * 型解決が走らないよう、あえて packetevents の型ではなく {@link Runnable} で保持する。
     */
    private Runnable damageIndicatorUninstaller;
    private ItemFactory itemFactory;
    private CatalogRecipeRegistrar catalogRecipeRegistrar;
    /** レシピ帳へのプラグインレシピ解禁 (2026-07-31 D7)。{@link #onEnable} 完了までは null。 */
    private com.trinityforge.listeners.RecipeDiscoveryListener recipeDiscoveryListener;
    /** brew-unlocks の醸造 customMixes 登録 (2026-07-31 D10)。{@link #onEnable} 完了までは null。 */
    private com.trinityforge.stats.BrewPotionMixRegistrar brewPotionMixRegistrar;
    private com.trinityforge.stats.VanillaRecipeRemover vanillaRecipeRemover;
    private com.trinityforge.stats.VanillaItemRemover vanillaItemRemover;
    private com.trinityforge.listeners.VanillaItemRemovalListener vanillaItemRemovalListener;
    private GiveItemCommand giveItemCommand;
    private BindCommand bindCommand;
    private StampCommand stampCommand;
    private ImportMobsCommand importMobsCommand;
    private DungeonCommand dungeonCommand;
    private com.trinityforge.command.InstanceCommand instanceCommand;
    private StatsCommand statsCommand;
    private com.trinityforge.stats.status.StatusGui statusGui;
    private RoleCommand roleCommand;
    private RoleBuffListener roleBuffListener;
    private CollectionCommand collectionCommand;
    private com.trinityforge.command.RecipesCommand recipesCommand;
    private com.trinityforge.command.GlyphsCommand glyphsCommand;
    private CollectionService collectionService;
    private com.trinityforge.progression.CollectionGui collectionGui;
    private com.trinityforge.progression.SpecialRewardService specialRewardService;
    private com.trinityforge.listeners.SpecialRewardPruneListener specialRewardPruneListener;
    private com.trinityforge.progression.TitleDisplayService titleDisplayService;
    private com.trinityforge.progression.ParticleEffectService particleEffectService;
    private com.trinityforge.command.SettingsCommand settingsCommand;
    private com.trinityforge.command.SpecialRewardCommand specialRewardCommand;
    private com.trinityforge.afk.AfkService afkService;
    private com.trinityforge.progression.AchievementService achievementService;
    private com.trinityforge.progression.achievement.AchievementGui achievementGui;
    private ActiveSkillRegistry activeSkillRegistry;
    private CooldownManager activeCooldownManager;
    private FeedbackLayer activeFeedbackLayer;
    private ActiveCommand activeCommand;
    private org.bukkit.scheduler.BukkitTask achievementPollTask;
    /** 敵の特殊攻撃(2026-07-31)。config が無効なら start() が何も開始しない。 */
    private com.trinityforge.combat.MobAbilityTask mobAbilityTask;
    private UseRequirementService useRequirementService;
    private PlayerLootLuckSource lootLuckSource;
    private com.trinityforge.stats.PlayerMobDropBonusSource mobDropBonusSource;
    // Bumped on every successful reload so ItemRefreshListener knows which already-assembled items
    // are stale (SELECTION_SPEC 5); see TableGeneration.
    private TableGeneration tableGeneration;
    private ItemRefreshListener itemRefreshListener;
    private com.trinityforge.economy.EconomyBridge economyBridge;

    @Override
    public void onEnable() {
        // Vault soft-dependency (T1): resolved once at startup, before anything else — a currency-dependent
        // feature (fish-sell-toggle) must be able to ask economyBridge.available() from the very first
        // tick. softdepend in paper-plugin.yml guarantees Vault (if present) is already enabled by now.
        this.economyBridge = com.trinityforge.economy.EconomyBridge.resolve(this);

        this.configManager = new ConfigManager(this);

        int domainsWithIssues = configManager.loadAll();
        if (domainsWithIssues > 0) {
            getLogger().warning("Config loaded with issues in " + domainsWithIssues
                    + " domain(s); defaults applied where invalid.");
        }

        this.dungeonWorldRegistry = new DungeonWorldRegistry();

        getDataFolder().mkdirs();
        try {
            File database = new File(getDataFolder(), "player_progression.db");
            this.progressionRepository = new CachedProgressionRepository(
                    new ExecutorProgressionRepository(
                            new SqliteProgressionRepository(
                                    "jdbc:sqlite:" + database.getAbsolutePath())),
                    () -> configManager.combatLevel().cacheTtlMillis());
            this.progressionCatalog = NativeSkillCatalog.loadDataFolder(
                    getDataFolder(), getClassLoader());
            this.skillLevelSource = new NativeSkillLevelSource(progressionRepository);
            this.skillPerkStatSource = new NativeSkillPerkStatSource(progressionRepository);
            // Shared across both services so gameplay EXP grants/perk unlocks and admin level
            // edits serialize on the same per-player lock instead of racing on a stale
            // read-modify-write of the point ledger.
            com.trinityforge.progression.PlayerLockRegistry progressionLocks =
                    new com.trinityforge.progression.PlayerLockRegistry();
            this.progressionService = new NativeProgressionService(
                    progressionRepository, progressionCatalog,
                    // power_allskillexpmultiplier_add → skill_exp_bonus (2026-07-23 stat-gate-overhaul
                    // §2 移行B12): 装備+perk合算。呼び出し頻度が高いため専用キャッシュは追加せず、既存の
                    // 遅延フィールド参照パターン(この時点ではaggregatorはまだ構築されていない)をそのまま踏襲。
                    id -> {
                        PlayerStatAggregator live = this.playerStatAggregator;
                        if (live == null) {
                            return 0.0;
                        }
                        Player online = getServer().getPlayer(id);
                        return online == null ? 0.0
                                : live.aggregate(online).totalOf(
                                        com.trinityforge.stats.StatKeys.canonical("skill_exp_bonus"));
                    },
                    progressionLocks,
                    // タスク3(2026-07-26 EXP調整): レベル逓減カーブ。既定はgathering/combatとも
                    // level-diminishing.*=false なので SkillExpDiminishingCurve は常に1.0を返す
                    // (=現行挙動を1ミリも変えない)。
                    new com.trinityforge.progression.SkillExpDiminishingCurve(configManager.skillExp()),
                    // 2026-07-31 ユーザー確定「既存の24時間EXPによって取得量が軽減されていく設定」。
                    // その機構は実在しなかった(あったのは spot-diminishing=同一地点だけ)ので新設。
                    // 設定は Supplier で毎回引く: reload で skill-exp.yml を読み直しても反映される。
                    this.dailyExpDiminishing,
                    () -> configManager.skillExp().dailyDiminishing(),
                    // スキル別EXP倍率(2026-08-02 柱5-3)。キーは <スキルID>_exp_bonus。
                    // use-skill は装備要件であって分類マーカーではない(採取ツールにも付いている)ので、
                    // 「伐採EXP+15%」の類はここのステでしか表現してはいけない。
                    (id, skillId) -> {
                        PlayerStatAggregator live = this.playerStatAggregator;
                        if (live == null || skillId == null || skillId.isBlank()) {
                            return 0.0;
                        }
                        Player online = getServer().getPlayer(id);
                        return online == null ? 0.0
                                : live.aggregate(online).totalOf(
                                        com.trinityforge.stats.StatKeys.canonical(
                                                skillId + "_exp_bonus"));
                    });
            this.progressionAdminService = new NativeProgressionAdminService(
                    progressionRepository, progressionCatalog,
                    () -> configManager.skillTrees().all().values(),
                    progressionLocks);
        } catch (SQLException ex) {
            getLogger().log(Level.SEVERE, "Native progression database could not be opened", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.experienceDispatcher = new NativeExperienceDispatcher(this, progressionService);
        // EXP獲得ボスバー/アクションバー表示 + レベルアップ通知 (S5/S6)。スキル表示名はスキルツリー定義から解決。
        com.trinityforge.progression.SkillExpFeedbackService skillExpFeedbackService =
                new com.trinityforge.progression.SkillExpFeedbackService(
                this, configManager.skillExp(), progressionCatalog,
                skillId -> {
                    for (com.trinityforge.skilltree.SkillTree t : configManager.skillTrees().all().values()) {
                        if (t != null && skillId != null && skillId.equalsIgnoreCase(t.skill())) {
                            return t.displayName();
                        }
                    }
                    return skillId;
                });
        this.experienceDispatcher.setFeedback(skillExpFeedbackService);
        // B3(2026-07-25 バグ報告): ログアウト時に当該プレイヤーのスキル別ボスバー/タイマーを確実に
        // 破棄するため PlayerQuitEvent を購読する(以前は Listener 未実装で未登録だった)。
        getServer().getPluginManager().registerEvents(skillExpFeedbackService, this);
        // Persistent per-chunk record of player-placed blocks (place+break gathering-XP farm guard).
        PlacedBlockTracker placedBlockTracker = new PlacedBlockTracker(this);
        getServer().getPluginManager().registerEvents(placedBlockTracker, this);
        // NativeSkillExperienceListener の登録は aggregator/dedicatedEffects を要する
        // 破壊時バニラEXP(S9)配線のため aggregator 生成後(下方)へ移動した。gimmick系(Digging/VeinMining/
        // TreeFelling)より前に登録される点は変わらないので、placed-mark の消去順序は不変。
        getServer().getPluginManager().registerEvents(
                new ProgressionPreloadListener(this, progressionRepository, dailyExpDiminishing), this);
        this.nativePerkService = new NativePerkService(progressionService,
                () -> configManager.skillTrees().all().values());
        // スキルノードロック(2026-07-27): プレステージ時に維持する perk をプレイヤーPDCから供給する。
        // オフラインプレイヤーは PDC を読めない=ロック無し扱いだが、プレステージは常に本人が
        // GUI から実行するため実運用で問題にならない。
        this.nativePerkService.setLockedPerkSupplier(playerId -> {
            org.bukkit.entity.Player online = getServer().getPlayer(playerId);
            return online == null
                    ? java.util.Set.of()
                    : java.util.Set.copyOf(com.trinityforge.pdc.PlayerData.of(online).lockedPerks());
        });
        // Public ItemStack -> AttackStats derivation, reusing the same item-category config +
        // stats/item-stats.yml (the SOLE per-item stat source) + attack-stat-keys mapping the
        // CombatListener runs for a melee weapon. Exposed via weaponAttackStats() so the ArsPaper fork can
        // feed a catalyst's real crit/penetration/... into SymmetricCombatService.magicalFinalDamage
        // instead of AttackStats.plain(0) (COMBAT_SYSTEM_SPEC 3.1).
        this.weaponAttackStatResolver = new WeaponAttackStatResolver(
                configManager.itemStats(),
                configManager.combatDamage(), configManager.combatDamage().attackStatKeys(),
                configManager.craftingFeatures());

        // Every combat, craft and use-gate level read now comes from the native SQLite repository.
        // TF is the source and applier of both perk ownership and configured buffs.
        PerkBuffResolver perkBuffResolver = new PerkBuffResolver(skillPerkStatSource,
                () -> configManager.skillTrees().all().values(),
                () -> configManager.lore().layout().multiplierLayers());
        NativeAttributeBridge nativeAttributeBridge = new NativeAttributeBridge(perkBuffResolver);
        // アチーブメント/図鑑報酬の永続ステータスバフ(rewards.permanent-buffs)は都度再計算方式
        // (Java-only achievement/collection reward extension): 達成/解放集合はPDCに記録済みなので、
        // このリゾルバは呼び出しごとにconfig+PDCを読むだけで良く、reload/達成状態変化が即反映される。
        com.trinityforge.progression.PermanentBuffResolver permanentBuffResolver =
                new com.trinityforge.progression.PermanentBuffResolver(
                        configManager.achievements(), configManager.collection());
        // #3 全ステ合算 (LD-13拡張): 攻撃側(CombatListener)と防御側(PlayerDefenseResolver)の両方が
        // 同じ集計者を通して防具4部位 + メインハンド(または発射武器) + (設定により)オフハンド + パーク +
        // アドオンを合算する。二重実装を避けるため単一のインスタンスを両方へ注入する。
        // 2026-07-25: PerkAttributeApplier の attack-speed/attack-speed-bonus 計算にも必要なため、
        // perkAttributeApplier の構築より先にここで作る(旧順序=perkAttributeApplier→aggregatorを反転)。
        // 装備使用ゲートの共有評価器は、防具の集計時にも要件未達部位を除外するためaggregatorより先に作る。
        this.useRequirementService = new UseRequirementService(configManager.useRequirements(),
                configManager.itemStats(), skillLevelSource);
        RoleBuffResolver roleBuffResolver = new RoleBuffResolver(configManager.roleBuffs());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                configManager.itemStats(),
                configManager.combatDamage(), perkBuffResolver, roleBuffResolver, nativeAttributeBridge,
                permanentBuffResolver, configManager.baseStats(), configManager.statCaps(),
                useRequirementService);
        this.playerStatAggregator = aggregator;
        this.perkAttributeApplier = new PerkAttributeApplier(
                this, perkBuffResolver, nativeAttributeBridge, permanentBuffResolver,
                configManager.baseStats(), aggregator, configManager.combatDamage(),
                configManager.itemStats());
        getServer().getPluginManager().registerEvents(perkAttributeApplier, this);
        // 装備フィンガープリント周期照合(安全網、既定10tick)を開始する。
        this.perkAttributeApplier.startPeriodicReconciliation();
        // 採集効率エンチャント連動方式(2026-07-25、mining-efficiency/mining-speed-bonus属性ベースの取り下げ
        // 再設計): メインハンドの農業/採掘/伐採/切削道具へ実行時に効率強化エンチャントとして反映する。
        this.gatheringEfficiencyApplier = new com.trinityforge.gathering.GatheringEfficiencyEnchantApplier(
                this, aggregator, configManager.gatheringEfficiency(), configManager.statCaps());
        getServer().getPluginManager().registerEvents(gatheringEfficiencyApplier, this);
        // 破壊時バニラEXP(S9)を有効化するため aggregator/dedicatedEffects を渡す 7引数版で登録する。
        // ここは placedBlockTracker(上方) 生成後かつ gimmick系リスナー登録より前なので順序不変。
        // 2026-07-28: 一括伐採/一括破壊/範囲収穫の連鎖分EXPを付与させるため、ローカルに保持して
        // 下の3リスナーへ ChainBreakExpGrant として渡す(それらは登録順の都合でここより後に作られる)。
        NativeSkillExperienceListener nativeSkillExperienceListener = new NativeSkillExperienceListener(
                this, experienceDispatcher, progressionCatalog,
                placedBlockTracker, roleBuffResolver,
                configManager.dedicatedEffects(), aggregator,
                configManager.mobLevelTable());
        this.chainBreakExpGrant = nativeSkillExperienceListener::grantChainBreak;
        getServer().getPluginManager().registerEvents(nativeSkillExperienceListener, this);
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.ArsMagicExperienceListener(
                        this, configManager.skillExp(), progressionCatalog, placedBlockTracker,
                        configManager.mobLevelTable()), this);
        // かまど/エンチャント/ポーションは実行者(=スキル取得者)限定ステ反映(2026-07-25)。
        // エンチャント運(良エンチャント出現率格上げ) + オーバーエンチャント解放者の出現率追加ボーナス。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.EnchantLuckListener(aggregator, configManager.enchantLuck(),
                        configManager.dedicatedEffects(), configManager.craftingFeatures()), this);
        // エンチャント費用軽減(enchant_cost_reduction): エンチャントテーブルのレベルコストと
        // 金床の修理コストの両方を軽減する(2026-07-26 新設)。同リスナーに本棚パワーconfig化
        // (crafting-features.yml enchant-bookshelf-power、既定値ではバニラ挙動不変)も相乗り。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.EnchantCostReductionListener(aggregator,
                        configManager.enchantBookshelf()), this);
        // ポーション品質(時間/強度換算) + 醸造速度。所有者解決は NativeSkillExperienceListener が
        // rememberBrewer/markAutomatedBrew で刻むPDCを BrewOwnership 経由で共有読み取りする。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.PotionQualityListener(this, aggregator,
                        configManager.alchemyQuality(), progressionCatalog), this);
        // 材料節約率(ingredient_save_chance)をバニラ醸造台へ配線する(2026-07-26)。所有者解決は上と同じ
        // BrewOwnership経由・HIGH優先度。自動(ホッパー)醸造は複製防止のため完全にスキップする
        // (品質/速度と違いauto_mult減衰すら適用しない、詳細はBrewIngredientSaveListenerのjavadoc参照)。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.BrewIngredientSaveListener(this, aggregator), this);
        // S9: 採取追加ドロップ/食事バフ/繁殖バフ/植えた作物の成長ボーナス consumer 群。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.GatheringExtraDropListener(aggregator, placedBlockTracker), this);
        // 2026-07-25 farming.yml A-alpha-2(ゴミ食のみ強化/非ゴミ食は通常に戻す)対応の4引数版で登録する。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.FoodBonusListener(this, aggregator,
                        configManager.dedicatedEffects(), configManager.foodGimmick()), this);
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.BreedingBonusListener(aggregator), this);
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.PlantedCropGrowthListener(this, aggregator), this);
        // 数値・解放フラグはすべて buffs 経由で集計する。
        getServer().getPluginManager().registerEvents(new NativeSurvivalPerkListener(aggregator), this);
        getServer().getPluginManager().registerEvents(
                new NativeCombatPerkListener(aggregator), this);
        new com.trinityforge.integration.ars.ArsNativeBridge(perkBuffResolver,
                permanentBuffResolver, roleBuffResolver, configManager.baseStats()).registerService(this);
        // Held-perk PDC mirror (UNLOCK 2.1): the ArsPaper integration gates glyph use / recipe / ritual on
        // PlayerData.of(player).heldPerks(), but nothing writes that PDC until here — so without this the
        // whole unlock system is inert. The service mirrors the TF-native unlocked-perk set into the PDC
        // on join, after menu changes, and on a light periodic backfill, writing only on change.
        this.perkMirrorService = new PerkMirrorService(this, skillPerkStatSource,
                PerkMirrorService.DEFAULT_SYNC_INTERVAL_TICKS);
        getServer().getPluginManager().registerEvents(new PerkMirrorListener(perkMirrorService), this);
        perkMirrorService.start();
        this.nativeSkillTreeMenu = new NativeSkillTreeMenu(
                this, progressionService, nativePerkService, player -> {
                    perkAttributeApplier.apply(player);
                    gatheringEfficiencyApplier.reconcileFull(player);
                    perkMirrorService.sync(player);
                    // ノード取得/ツリーリセットの直後に recipe:<id> ゲートの解放状態が変わるので、
                    // レシピ帳もその場で追随させる(次のログインまで待たせない)。
                    // フィールド参照なのは、レシピ登録器がこの行より後で組まれるため
                    // (ラムダは呼ばれた時点の値を読む)。
                    if (recipeDiscoveryListener != null) {
                        recipeDiscoveryListener.reconcile(player);
                    }
                });
        getServer().getPluginManager().registerEvents(nativeSkillTreeMenu, this);
        // Player-defender item side (LD-8 γ, LD-13): sums the TF-only defense (typed 耐性 + common
        // 守備力/被ダメ軽減/回避) derived from the victim's equipped armor, combined by the service
        // with the vanilla armor/toughness mirror AND the skill-tree perk addend. Shares the item
        // category config and the perk resolver with the attacker path.
        PlayerDefenseResolver playerDefenseResolver =
                new PlayerDefenseResolver(configManager.combatDamage().defenseStatKeys(), aggregator);
        this.combatService = new SymmetricCombatService(configManager.combatDamage(),
                configManager.combatLevel(), configManager.mobTypes(), skillLevelSource,
                playerDefenseResolver);
        // Bleed DoT runtime (Q3 = (c)): a bounded, self-evicting active-bleed set + one repeating
        // main-thread task, routing each tick through the pipeline (LD-9). Started/stopped with the
        // plugin so a disable/reload leaks nothing (mirrors HateService).
        this.bleedService = new BleedService(this, combatService, configManager.combatDamage());
        bleedService.start();
        // 毒・ウィザーDoTを出血と同じ「被ダメージ軽減のみ考慮」に揃える (バニラのイベント自体は残す)。
        getServer().getPluginManager().registerEvents(new DotDamageListener(combatService), this);
        // 課題2: ArsPaperフォークの魔法ダメージ(cause=MAGIC)はTF対称パイプラインが既にポーション
        // RESISTANCE(Lv×10%)をTF耐性%へ織り込み済みのため、バニラのRESISTANCE modifier(Lv×20%)を
        // 別途0化して二重軽減を防ぐ。DotDamageListenerとは対象cause/責務が異なる別リスナー
        // (DoTはBASEダメージ自体をbleedFinalDamageFlatで再計算するが、魔法は既に確定したBASEの
        // 後始末のみ)なので分離した(MagicResistanceFoldListenerのjavadoc参照)。
        getServer().getPluginManager().registerEvents(new MagicResistanceFoldListener(), this);
        // 日光炎上ダメージを最大HP割合へ置換 (2026-07-28)。バニラの1.0固定ではTFのモブHP(Lv0で400)に
        // 対して無意味で「朝になっても敵が炎上で死なない」ため。combat/damage.yml の sunlight-burn。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.SunlightBurnListener(configManager.combatDamage()), this);
        // 装備耐久ペナルティ(2026-07-30): EliteMobsのインスタンスダンジョンは致死ダメージをキャンセルして
        // 「ダウン」へ移すため PlayerDeathEvent が発火せず、死亡ペナルティもキャンセルされた一撃分の
        // 防具耐久消費も両方失われていた。被弾側はここで、死亡側はフォークが
        // applyDeathDurabilityPenalty() を呼ぶことで補う。設定は combat/damage.yml の durability。
        this.equipmentDurabilityService = new com.trinityforge.durability.EquipmentDurabilityService(
                configManager.combatDamage()::durabilityPenalty,
                player -> dungeonWorldRegistry.isDungeonWorld(player.getWorld().getUID()));
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.EquipmentDurabilityListener(equipmentDurabilityService), this);
        // The listener bridges the attacker's mainhand weapon's stats/item-stats.yml overlay into
        // AttackStats via the configured attack-stat-keys mapping (COMBAT 3.1), and folds only the
        // ARMOR/RESISTANCE vanilla modifiers into the symmetric pipeline so shield blocking and
        // absorption keep working (COMBAT_SYSTEM_SPEC 5).
        getServer().getPluginManager().registerEvents(
                new CombatListener(this, combatService,
                        configManager.itemStats(), configManager.combatDamage(),
                        skillLevelSource, bleedService, perkBuffResolver, aggregator,
                        configManager.useRequirements(), configManager.skillExp(),
                        configManager.craftingFeatures(), roleBuffResolver,
                        configManager.mobLevelTable(), progressionCatalog), this);

        // Aggro/threat tracking (gap C5). The service owns a bounded, self-evicting HateTable and
        // a periodic sweep; the listener feeds threat and evicts on death/removal/quit/unload so
        // the table can never leak. All knobs live in hate/rates.yml with balance-neutral defaults.
        this.hateService = new HateService(this, configManager.hate());
        getServer().getPluginManager().registerEvents(new HateListener(hateService, roleBuffResolver), this);
        hateService.start();

        // Write-side item assembly shared by the give command, (M3) fork drop/craft flows, and the
        // refresh listener below (SELECTION_SPEC 5: a table edit must reach items already in play).
        this.tableGeneration = new TableGeneration();
        // config で機構ごと殺されたばらつきステ(craft-quality.yml の scale=0)を lore から落とす。
        // 未配線だと「設定で無効にしたのに説明文だけ生きている」表示と実装の食い違いになる。
        // Supplier で渡すのは /trinityforge reload で config が差し替わるため。
        LoreComposer loreComposer = new LoreComposer();
        loreComposer.useInertStatKeys(configManager.craftQuality()::inertSpreadStatKeys);
        ItemAssembler itemAssembler = new ItemAssembler(
                configManager.itemStats(),
                configManager.attributeMapping(),
                new AttributeApplier(this),
                configManager.lore(),
                loreComposer,
                configManager.qualityTiers(),
                tableGeneration,
                configManager.itemCatalog(),
                configManager.skillTrees(),
                configManager.craftingFeatures());
        this.itemFactory = new ItemFactory(itemAssembler, configManager.itemStats(), configManager.craftingFeatures());
        // Single id->ItemStack resolution seam (TF catalog -> ArsPaper registry -> vanilla Material) used
        // by every drop-table listener (mining/woodcutting/digging/fishing, 2026-07-23 stat-gate-overhaul §4).
        this.crossPluginItemResolver =
                new com.trinityforge.stats.CrossPluginItemResolver(configManager.itemCatalog(), itemFactory);

        // Dungeon entry gate (D2, Q4): single SoT dungeon/gates.yml (world + content-package aliases).
        // 2026-07-27 カスタムアイテム鍵対応でcrossPluginItemResolverに依存するようになったため、この
        // 生成をcrossPluginItemResolver構築後(上)へ移動した(以前はhateService直後にあった)。
        this.dungeonGateService = new DungeonGateService(
                configManager.dungeonGates(), combatService, crossPluginItemResolver);
        getServer().getPluginManager().registerEvents(
                new DungeonGateListener(dungeonGateService), this);
        // 2026-07-27 鍵アイテムGUI入場対応: 鍵アイテム右クリック→潜入確認GUI→確定で転送。
        // GUIとリスナーはdungeonGateService構築後(上)へ置くこと(依存順序)。
        com.trinityforge.mobs.DungeonEntryGui dungeonEntryGui = new com.trinityforge.mobs.DungeonEntryGui(
                this, dungeonGateService, combatService);
        getServer().getPluginManager().registerEvents(dungeonEntryGui, this);
        // 2026-08-03: ここにあった腕振り経由の「虚空右クリック」フォールバック(VoidRightClickBridge)は
        // 撤去した。当初の診断「vanilla の使用挙動が無いアイテムは虚空右クリックで
        // PlayerInteractEvent 自体が発火しない」は誤りで、真因は購読側の ignoreCancelled = true
        // だった(RIGHT_CLICK_AIR はブロックが null なので生成時点で常に isCancelled() == true)。
        // 詳細は DungeonKeyItemListener#onInteract / GachaListener#onInteract の javadoc。
        com.trinityforge.listeners.DungeonKeyItemListener dungeonKeyItemListener =
                new com.trinityforge.listeners.DungeonKeyItemListener(
                        configManager.dungeonGates(), dungeonGateService.keyMatcher(), dungeonEntryGui);
        getServer().getPluginManager().registerEvents(dungeonKeyItemListener, this);
        // /tf dungeon <id> のクイック入場(2026-07-27 admin)は、鍵GUI経由の通常入場とまったく同じ
        // 転送ロジックを共有する(DungeonTeleporter)。違いは「成功後に鍵を消費するか」だけ。
        com.trinityforge.mobs.DungeonTeleporter dungeonTeleporter =
                new com.trinityforge.mobs.DungeonTeleporter(dungeonGateService);

        // Catalog-authored crafting recipes (items/catalog.yml `recipe:`/`recipes:`): registers the
        // Bukkit recipes each catalog entry declares. Re-run on every /trinityforge reload (below)
        // AND from ArsPaper's enable hook (refreshCatalogRecipes) so Ars-built results converge.
        this.catalogRecipeRegistrar = new CatalogRecipeRegistrar(this, configManager.itemCatalog(), itemFactory,
                () -> configManager.craftingFeatures().addedRecipes());
        catalogRecipeRegistrar.registerAll();
        CatalogRitualBridge.registerAll(this, configManager.itemCatalog());
        // D7: 登録しただけではレシピ帳に出ない(Bukkit.addRecipe は discover を配らない)。
        // ログイン時・ノード解放時・reload 後に TF/Ars のレシピを解禁するリスナー。
        this.recipeDiscoveryListener = new com.trinityforge.listeners.RecipeDiscoveryListener(
                this, configManager.dedicatedEffects(), configManager.craftingFeatures(),
                catalogRecipeRegistrar::allRegisteredKeys);
        getServer().getPluginManager().registerEvents(recipeDiscoveryListener, this);
        // D10 (K-13): brew-unlocks を Paper の醸造 customMixes へ登録する。これが無いと
        // CMD 付きの討伐素材は上段スロットに置けず、THICK ベースは醸造自体が始まらない。
        // 要求レベル(brew:<id> を置いているノードの最小レベル)は、同じ (base, ingredient) を
        // 複数グループが宣言したときに「上位段を勝たせる」ために渡す(レビュー指摘#2)。
        this.brewPotionMixRegistrar = new com.trinityforge.stats.BrewPotionMixRegistrar(
                this, () -> configManager.craftingFeatures().brewUnlocks(),
                () -> com.trinityforge.stats.BrewPotionMixRegistrar.requirementLevels(
                        configManager.skillTrees().all().values()),
                com.trinityforge.stats.BrewPotionMixRegistrar.serverSink());
        brewPotionMixRegistrar.registerAll();
        // /minecraft:reload は PotionBrewing を作り直すので customMixes が全消滅する。
        // レシピ帳の解禁も同時に張り直す(レシピ側も再送されるため)。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.ServerResourcesReloadListener(this::reapplyAfterResourcesReload),
                this);
        // crafting-features.yml removed-vanilla-recipes: バニラ/データパックレシピの無効化
        // (editor から編集可能。reload でリストから外れたレシピは復元される)。
        this.vanillaRecipeRemover = new com.trinityforge.stats.VanillaRecipeRemover(getLogger());
        vanillaRecipeRemover.apply(configManager.craftingFeatures().removedVanillaRecipes());
        // crafting-features.yml removed-vanilla-items: バニラアイテム自体の排除
        // (入手経路の遮断 + 既存所持の掃除。editor から編集可能)。
        this.vanillaItemRemover = new com.trinityforge.stats.VanillaItemRemover(configManager.itemCatalog());
        vanillaItemRemover.updateTargets(configManager.craftingFeatures().removedVanillaItems(), getLogger());
        this.vanillaItemRemovalListener =
                new com.trinityforge.listeners.VanillaItemRemovalListener(this, vanillaItemRemover);
        getServer().getPluginManager().registerEvents(vanillaItemRemovalListener, this);
        // Per-slot identity check for catalog recipes with custom: ingredients (and correction of
        // cross-recipe mismatches like compressed-stone chains sharing a material at Bukkit level).
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.CatalogWorkbenchListener(
                        catalogRecipeRegistrar, configManager.itemCatalog()), this);
        this.giveItemCommand = new GiveItemCommand(this, itemFactory, configManager.itemCatalog(),
                configManager.quality());
        this.bindCommand = new BindCommand(itemFactory);
        this.importMobsCommand = new ImportMobsCommand(this, configManager.mobImport(),
                configManager.mobProfiles(), configManager.dungeonThemes());
        this.dungeonCommand = new DungeonCommand(configManager.dungeonThemes(),
                configManager.dungeonGates(), dungeonTeleporter);
        this.instanceCommand = new com.trinityforge.command.InstanceCommand();
        // Read-only debug command: shows the running player's aggregate combat level, skill levels,
        // and derived weapon/armor stats (rollSeed + quality), exactly as the pipeline reads them.
        this.statsCommand = new StatsCommand(combatService,
                aggregator,
                configManager.lore(),
                skillLevelSource);
        // /tf status: 同じ数値をGUIで見るための画面 (2026-07-29)。合算は combined()、整形は
        // StatValueRenderer と、チャット版 (/tf stats) と同じ経路を通す。
        this.statusGui = new com.trinityforge.stats.status.StatusGui(this, combatService, aggregator,
                configManager.lore(), skillLevelSource, nativePerkService);
        getServer().getPluginManager().registerEvents(statusGui, this);
        this.roleBuffListener = new RoleBuffListener(configManager.roleBuffs());
        // 2026-07-28: /tf role はロールとバフの内訳をチャットへ、/tf role set はアイテム表示の
        // 選択GUIを開く。コマンド版とGUI版が同じゲート(RoleChangeService)を通るよう分離してある。
        com.trinityforge.progression.RoleChangeService roleChangeService =
                new com.trinityforge.progression.RoleChangeService(configManager.roleBuffs(), roleBuffListener);
        com.trinityforge.progression.RoleDescriptions roleDescriptions =
                new com.trinityforge.progression.RoleDescriptions(configManager.lore());
        com.trinityforge.progression.RoleSelectGui roleSelectGui =
                new com.trinityforge.progression.RoleSelectGui(this, roleChangeService, roleDescriptions);
        getServer().getPluginManager().registerEvents(roleSelectGui, this);
        this.roleCommand = new RoleCommand(roleChangeService, roleDescriptions, roleSelectGui);

        // コレクション図鑑 (M7): カタログアイテム入手/プレイヤー討伐を図鑑へ記録し、
        // 登録数しきい値の報酬ティア(称号/コスメ/QoL、progression/collection.yml)を段階解放する。
        this.collectionService = new CollectionService(configManager.collection(), getLogger(),
                crossPluginItemResolver, experienceDispatcher, perkAttributeApplier);
        getServer().getPluginManager().registerEvents(
                new CollectionListener(configManager.collection(), collectionService,
                        configManager.itemCatalog(), configManager.achievements(), this), this);
        // itemCatalog は「種別順(use-skill)/使用可能レベル順」の並べ替えキーを引くために渡す
        // (2026-07-27。未指定でも名前順・絞り込み・検索は動く)。
        this.collectionGui = new com.trinityforge.progression.CollectionGui(this, configManager.collection(),
                collectionService, crossPluginItemResolver, configManager.itemCatalog());
        this.collectionCommand = new CollectionCommand(configManager.collection(), collectionService, collectionGui);
        this.recipesCommand = new com.trinityforge.command.RecipesCommand();
        // 2026-07-28: グリフ解放素材の閲覧GUI(読み取り専用)。解放自体は従来どおり筆記台。
        this.glyphsCommand = new com.trinityforge.command.GlyphsCommand();
        getServer().getPluginManager().registerEvents(collectionGui, this);

        // 特殊報酬(称号/パーティクル/パーティクルシード, 2026-07-23-stat-gate-overhaul §6.1):
        // スキルツリー reward:<id> 保有 or 達成/図鑑ティア直接付与のいずれかで解放される。
        this.specialRewardService = new com.trinityforge.progression.SpecialRewardService(
                configManager.specialRewards(), configManager.dedicatedEffects());
        this.titleDisplayService = new com.trinityforge.progression.TitleDisplayService(this,
                player -> specialRewardService.equippedTitleDisplay(player).orElse(null),
                () -> configManager.specialRewards().titleSeparator());
        getServer().getPluginManager().registerEvents(titleDisplayService, this);
        this.particleEffectService = new com.trinityforge.progression.ParticleEffectService(
                this, configManager.specialRewards());
        getServer().getPluginManager().registerEvents(particleEffectService, this);
        com.trinityforge.progression.SettingsGui settingsGui = new com.trinityforge.progression.SettingsGui(
                this, configManager.specialRewards(), specialRewardService);
        settingsGui.setOnTitleChanged(titleDisplayService::refresh);
        settingsGui.setOnParticleChanged(particleEffectService::invalidate);
        getServer().getPluginManager().registerEvents(settingsGui, this);
        this.settingsCommand = new com.trinityforge.command.SettingsCommand(settingsGui);
        // 特殊報酬の運営付与/剥奪 (2026-07-27)。アチーブ/図鑑ティアと同じ「直接付与」枠を触る。
        this.specialRewardCommand = new com.trinityforge.command.SpecialRewardCommand(
                configManager.specialRewards(), specialRewardService, perkAttributeApplier);
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.ParticleSeedListener(configManager.specialRewards()), this);
        // special-rewards.yml から削除された報酬IDをプレイヤーPDCの保持分からも掃除する(2026-07-28)。
        // オフラインPDCは触れないため参加時が唯一の掃除機会 + /trinityforge reload 後のオンライン全員一括。
        this.specialRewardPruneListener = new com.trinityforge.listeners.SpecialRewardPruneListener(
                this, new com.trinityforge.progression.SpecialRewardPruner(configManager.specialRewards()));
        getServer().getPluginManager().registerEvents(specialRewardPruneListener, this);

        // アチーブメント (2026-07-23-stat-gate-overhaul §6.2): バニラ実績連動 + 統計しきい値ポーリング。
        this.achievementService = new com.trinityforge.progression.AchievementService(
                configManager.achievements(), getLogger(), crossPluginItemResolver, experienceDispatcher,
                perkAttributeApplier, collectionService);
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.AchievementListener(achievementService), this);
        // /achievement の進捗GUI(2026-07-29)。スキルツリーGUIと同じコネクタ/移動ボタンを再利用する。
        this.achievementGui = new com.trinityforge.progression.achievement.AchievementGui(
                this, configManager.achievements(), crossPluginItemResolver, collectionService);
        getServer().getPluginManager().registerEvents(achievementGui, this);
        // バニラ進捗(advancement)解除のサーバ側抑止(achievements.yml vanilla-advancements, 2026-07-28)。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.VanillaAdvancementBlockListener(configManager.achievements()),
                this);

        // Re-syncs an item's lore/attributes against the live tables on hotbar switch, armor change,
        // and join, so a reload's effect is not stuck at "only new items see it" (item 1).
        this.itemRefreshListener = new ItemRefreshListener(itemAssembler, tableGeneration);
        getServer().getPluginManager().registerEvents(itemRefreshListener, this);

        // Ownership use-gate: SOULBOUND/OWNER_BOUND with an owner deny use by non-owners (trade OK).
        getServer().getPluginManager().registerEvents(new OwnerBindListener(), this);
        // Catalog identity must never fall through to consuming/transforming vanilla Material
        // behaviour (placement, fuel/cooking, default workstations). Dedicated TF recipes remain
        // whitelisted by their own declared recipe specs.
        getServer().getPluginManager().registerEvents(
                new CatalogVanillaOperationGuardListener(
                        configManager.itemCatalog(), configManager.craftingFeatures()), this);
        // 鍛冶村人取引: perk-gated custom trades (economy/villager-trades.yml).
        getServer().getPluginManager().registerEvents(
                new VillagerTradeListener(configManager.dedicatedEffects(), configManager.villagerTrades(),
                        configManager.itemCatalog(), itemFactory), this);
        // バニラ鍛冶村人(防具/道具/武器)との取引を全面禁止。上のVillagerTradeListenerが提供する
        // perk連動の専用取引に一本化するための対の実装。CLN-25: 実装済みだが未登録だったため有効化。
        getServer().getPluginManager().registerEvents(new BlacksmithBanListener(), this);
        getServer().getPluginManager().registerEvents(
                new CatalogCraftGateListener(configManager.dedicatedEffects()), this);
        // PRG-02: recipe:<id> ゲートがTFカタログ品/実在バニラレシピ/既知のネザライトアップグレードの
        // いずれにも解決できない(綴り間違い等)場合は起動時に警告する(「静かに壊れるより騒がしく落ちる」方針)。
        //
        // 【最初のtickまで遅延させる理由 (2026-07-28)】ArsPaper は TF に depend しているので TF より後に
        // enable する。ここ(TFのonEnable内)で走らせると、ArsPaper が materials.yml/items.yml から登録する
        // 作業台レシピ(tf_core_* / compressed_* / source_gem_block 等)がまだ Bukkit のレシピ一覧に無く、
        // 実在するのに「解決できない」と誤警告していた。runTask は全プラグインの enable 完了後・最初の
        // tick で走るため、その時点なら Bukkit.recipeIterator() に他プラグインのレシピも載っている。
        getServer().getScheduler().runTask(this, () -> CatalogCraftGateListener.verifyRecipeGateIds(
                configManager.dedicatedEffects(), configManager.itemCatalog(), getLogger()));
        getServer().getPluginManager().registerEvents(
                new WeaponCoatingListener(configManager.dedicatedEffects(),
                        configManager.craftingFeatures(), configManager.itemStats(),
                        configManager.combatDamage().weaponBaseFormula(), playerStatAggregator()), this);
        getServer().getPluginManager().registerEvents(
                new WoodRepairListener(configManager.dedicatedEffects(), configManager.craftingFeatures()), this);
        getServer().getPluginManager().registerEvents(
                new DisassemblyListener(configManager.dedicatedEffects(), configManager.craftingFeatures(),
                        configManager.itemCatalog(), itemFactory, aggregator), this);
        getServer().getPluginManager().registerEvents(
                new PotionMergeListener(configManager.dedicatedEffects(), configManager.craftingFeatures()), this);
        // ゲート判定は「実際に登録された customMix」だけを見る (2026-07-31 D10 レビュー指摘#1/#3)。
        // フィールド参照のラムダなのは、登録器がこの行より後で組まれるため(ラムダは呼ばれた時点の値を読む)。
        getServer().getPluginManager().registerEvents(
                new BrewUnlockListener(configManager.dedicatedEffects(),
                        () -> brewPotionMixRegistrar == null
                                ? java.util.List.<com.trinityforge.stats.BrewPotionMixRegistrar.MixPlan>of()
                                : brewPotionMixRegistrar.livePlans(),
                        com.trinityforge.listeners.BrewStandOwners.blockPdc(this), this), this);
        getServer().getPluginManager().registerEvents(
                new OverEnchantListener(configManager.dedicatedEffects(), configManager.craftingFeatures()), this);
        getServer().getPluginManager().registerEvents(roleBuffListener, this);
        // TF装備の砥石: エンチャ除去のみ許可し、砥石が剥がす見た目/派生ステを元のロールで復元する(U4)。
        getServer().getPluginManager().registerEvents(
                new GrindstonePreserveListener(configManager.itemCatalog(), itemFactory), this);
        // Tool use-level gate (block break) — mirrors CombatListener weapon gate.
        getServer().getPluginManager().registerEvents(
                new UseRequirementListener(skillLevelSource, configManager.useRequirements(),
                        configManager.itemStats()), this);
        // 防具の装備ゲート: 上で生成した共有評価器を使い、要件未達の防具は装備直後に引き剥がして
        // 返却する(PlayerArmorChangeEvent はキャンセル不可)。同じ評価器をaggregatorにも渡しているため、
        // 次tickの剥離前でも要件未達防具のステータスは集計されない。
        getServer().getPluginManager().registerEvents(
                new ArmorUseGateListener(this, useRequirementService), this);

        // Craft/fishing quality stamp (ITEM_ECONOMY_SPEC 5.2c/5.2d): a crafted piece of equipment gets a
        // quality from the crafter's production skill; a caught piece gets a random quality by treasure
        // luck. Runtime-verified items (shift-click, ARS_SMITHING existence) are noted in each listener.
        this.craftQualityService = new CraftQualityService(
                skillLevelSource, configManager.craftQuality(), configManager.quality(),
                aggregator, configManager.itemStats());
        PlayerLootLuckSource lootLuck = new PlayerLootLuckSource(getLogger(), aggregator);
        this.lootLuckSource = lootLuck;
        // mobドロップ品質のmode+1は幸運ではなく専用stat(mob_drop_quality、装備+perk合算)が担う
        // (幸運spec=「ドロップとクラフト以外」)。EliteMobsフォークからは mobDropBonus() で読む。
        PlayerMobDropBonusSource mobDropBonus = new PlayerMobDropBonusSource(getLogger(), aggregator);
        this.mobDropBonusSource = mobDropBonus;
        getServer().getPluginManager().registerEvents(
                new CraftQualityListener(this, itemFactory, craftQualityService, configManager.craftQuality(),
                        configManager.skillExp(), configManager.itemCatalog(), configManager.itemStats()),
                this);
        // Clamp item damage after other HIGHEST-priority durability handlers have run.
        getServer().getPluginManager().registerEvents(new ItemDamageClampListener(), this);
        getServer().getPluginManager().registerEvents(
                new CatalogSmithingListener(configManager.itemCatalog(), itemFactory), this);
        getServer().getPluginManager().registerEvents(
                new CatalogAnvilListener(this, configManager.itemCatalog(), itemFactory), this);
        // 釣果が宝/ゴミどちらのグループから引かれたか(FishingGimmickListenerの置換フロー)を
        // FishingQualityListenerのトレジャー複製防止ロジックへ伝える、釣果エンティティ限定のPDCフラグ。
        org.bukkit.NamespacedKey fishingTreasureKey = new org.bukkit.NamespacedKey(this, "fishing_table_treasure");
        getServer().getPluginManager().registerEvents(
                new FishingQualityListener(itemFactory,
                        configManager.quality(),
                        configManager.fishingGimmick(), skillLevelSource, configManager.itemCatalog(), lootLuck,
                        playerStatAggregator, configManager.itemStats(), fishingTreasureKey), this);
        PickupQualityListener pickupQualityListener = new PickupQualityListener(
                this, itemFactory, configManager.itemStats(),
                configManager.qualityTiers(), configManager.quality(),
                configManager.itemCatalog(), lootLuck);
        getServer().getPluginManager().registerEvents(pickupQualityListener, this);
        this.stampCommand = new StampCommand(pickupQualityListener);

        // 採掘の連続処理 (mining-gimmick.yml fortune.*): ツールの mining-fortune ステ + MINING スキルLvに
        // 応じた期待値ぶん、対象鉱石/作物ブロックのドロップを追加スポーンする。
        getServer().getPluginManager().registerEvents(
                new MiningFortuneListener(configManager.miningGimmick(), skillLevelSource,
                        playerStatAggregator, placedBlockTracker), this);

        // Gacha ticket right-click (gacha.yml): right-clicking a ticket item (matched by its
        // items/catalog.yml id, not display name) draws one weighted prize and consumes the ticket.
        GachaListener gachaListener = new GachaListener(this, configManager.gacha(), configManager.itemCatalog(),
                itemFactory, configManager.quality(), aggregator);
        getServer().getPluginManager().registerEvents(gachaListener, this);

        // 汎用アクティブスキル基盤 (2026-07-25 gather-rework-active-framework §3 W1): haste-active-mining を
        // 基盤上の最初のActiveSkillとして再実装(旧HasteActiveMiningListenerの私製CT Mapを置換)。
        // 正式トリガーは「スニーク+右クリック かつ メインハンドのuse-skillが一致」の一本のみ
        // (設計書§6 Q2の/tf active+GUIトリガーは不採用 — オーケストレータ指示で上書き)。FeedbackLayerは
        // §3 component 5設計どおりW3の採取系フィードバック(B-1)とも共有する。
        this.activeSkillRegistry = new ActiveSkillRegistry();
        this.activeSkillRegistry.register(new HasteActiveSkill(configManager.miningGimmick()));
        // 2026-07-25 CT設計一本化 §2: 登録した全ActiveSkillに対応するCT短縮ステータスキー
        // (StatVocabulary.ATTACK_KEYS の "<id>-cooldown-reduction") が存在するか起動時に検査する。
        // 新しいActiveSkillを追加して対応するキーの登録を忘れると、ここでIllegalStateExceptionが飛んで
        // 起動が止まる(「静かに壊れるより騒がしく落ちる」方針、VanillaAttributeDefaultsと同じ流儀)。
        ActiveSkillCooldownKeys.verifyRegistered(this.activeSkillRegistry);
        this.activeCooldownManager = new CooldownManager();
        this.activeFeedbackLayer = new FeedbackLayer();
        this.activeCommand = new ActiveCommand(activeSkillRegistry, configManager.dedicatedEffects(),
                activeCooldownManager, activeFeedbackLayer, aggregator);
        getServer().getPluginManager().registerEvents(
                new ActivationDispatcher(activeSkillRegistry, configManager.dedicatedEffects(),
                        activeCooldownManager, activeFeedbackLayer, aggregator), this);
        // 採掘スキルツリーのflag系dedicated-effect(各 skilltree/*.yml ノードの dedicated-effects: フィールド)consumer群
        // (stats/mining-gimmick.yml でチューニング): 鉱脈破壊/怪しいブロック復活/スポナーST回収 +
        // mining drop-table(旧ガチャ券1-3/古代のがれき個別consumerを置換、2026-07-23 §4)。
        getServer().getPluginManager().registerEvents(
                new VeinMiningListener(configManager.dedicatedEffects(), configManager.miningGimmick(),
                        crossPluginItemResolver, placedBlockTracker, activeFeedbackLayer,
                        chainBreakExpGrant), this);
        getServer().getPluginManager().registerEvents(
                new MiningGimmickListener(this, configManager.dedicatedEffects(), aggregator,
                        configManager.miningGimmick(), placedBlockTracker), this);

        // 伐採スキルツリーのflag系dedicated-effect(各 skilltree/*.yml ノードの dedicated-effects: フィールド)consumer群
        // (stats/woodcutting-gimmick.yml でチューニング): tree-fell(旧小木/大木一括伐採を統合) +
        // woodcutting drop-table(旧リンゴ/金リンゴ/クリスタルリンゴ個別consumerを置換、2026-07-23 §4)。
        // 2026-07-25 PRG-07: 一括伐採CTを私製Mapから汎用CooldownManager(activeCooldownManager、
        // ActivationDispatcherと共有)へ統合し、tree-fell-cooldown-reductionステータスを読むようにした。
        TreeFellingListener treeFellingListener =
                new TreeFellingListener(configManager.dedicatedEffects(), configManager.woodcuttingGimmick(),
                        crossPluginItemResolver, placedBlockTracker, activeFeedbackLayer,
                        activeCooldownManager, aggregator, chainBreakExpGrant);
        // 段階破壊のスケジューリングに使う Plugin を明示注入する。未注入だと getProvidingPlugin
        // 一本足になり、失敗時は WARNING を出して同tick破壊へ縮退する(N1/N2 の配線)。
        treeFellingListener.setPlugin(this);
        getServer().getPluginManager().registerEvents(treeFellingListener, this);
        // 通常アクティブと半アクティブを同じ0.5秒タスクで表示し、同tickでの二重上書きを避ける。
        // 一括伐採の適格条件/CT計算は発動リスナー自身へ委譲し、表示との仕様ずれを防ぐ。
        new com.trinityforge.active.ActiveCooldownDisplay(this, activeSkillRegistry,
                configManager.dedicatedEffects(), activeCooldownManager, activeFeedbackLayer,
                aggregator, List.of(treeFellingListener)).start();

        // 掘削(シャベル適正ブロック破壊)ギミック: digging drop-table(2026-07-23 §4、新設リスナー)。
        // 対象判定は NativeSkillExperienceListener.grantGathering と同じ digging_break 分類ロジックを流用する。
        getServer().getPluginManager().registerEvents(
                new DiggingGimmickListener(configManager.dedicatedEffects(), configManager.diggingGimmick(),
                        progressionCatalog, placedBlockTracker, crossPluginItemResolver), this);

        // 農業(作物収穫)ギミック: farming drop-table(2026-08-01 新設)。掘削と同形だが、作物は必ず
        // プレイヤーが植える = 種の設置が PlacedBlockTracker に必ずマークを付けるため、
        // 「置く→壊す」ガードだけはリスナー側で別扱いしている(素の isPlaced を掛けると全収穫が除外される)。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.FarmingGimmickListener(
                        configManager.dedicatedEffects(), configManager.farmingGimmick(),
                        progressionCatalog, placedBlockTracker, crossPluginItemResolver), this);

        // 2026-07-25 切削C-1/C-2: シャベル耐久累計→バニラ/職業EXPボーナス。バニラ分は本リスナー自身が
        // PlayerExpChangeEventで直接適用し、職業分は experienceDispatcher の任意resolverへ配線する
        // (NativeExperienceDispatcher#grant自体はイベントではないため、この間接注入が必要)。
        com.trinityforge.listeners.DiggingDurabilityExpListener diggingDurabilityExpListener =
                new com.trinityforge.listeners.DiggingDurabilityExpListener(
                        configManager.dedicatedEffects(), configManager.diggingGimmick());
        getServer().getPluginManager().registerEvents(diggingDurabilityExpListener, this);
        experienceDispatcher.setJobExpMultiplierResolver((playerId, skillId) -> {
            if (!"DIGGING".equalsIgnoreCase(skillId)) return 0.0;
            org.bukkit.entity.Player p = org.bukkit.Bukkit.getPlayer(playerId);
            return p == null ? 0.0 : diggingDurabilityExpListener.jobExpBonusFraction(p);
        });

        // 2026-07-25 かまど精錬速度(smithing.yml A-1〜3, feature:furnace-smelt-speed)/精錬ボーナス
        // (B-1〜3, feature:furnace-smelt-bonus): 所有者=精錬物を入れた本人のみ(FurnaceSmeltListener
        // 参照)。ホッパー自動投入は smithing-gimmick.yml auto-mode-multiplier で減衰。
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.listeners.FurnaceSmeltListener(
                        this, configManager.dedicatedEffects(), configManager.smithingGimmick()), this);

        // 農業/畜産スキルツリーのflag/percent系dedicated-effect(各 skilltree/*.yml ノードの dedicated-effects: フィールド)consumer群
        // (stats/farming-gimmick.yml でチューニング): 植え直しと収穫同時(auto-replant)・範囲収穫
        // (area-harvest)・動物への与ダメ倍率(animal-damage-4x)・ハチ非敵対+養蜂幸運(bee-no-aggro/
        // hive-harvest-fortune)。
        getServer().getPluginManager().registerEvents(
                new FarmingHarvestListener(this, configManager.dedicatedEffects(),
                        configManager.farmingGimmick(), activeFeedbackLayer, chainBreakExpGrant), this);
        getServer().getPluginManager().registerEvents(
                new AnimalDamageListener(configManager.dedicatedEffects(), configManager.farmingGimmick()), this);
        getServer().getPluginManager().registerEvents(
                new BeekeepingListener(configManager.dedicatedEffects(), configManager.farmingGimmick(),
                        aggregator), this);

        // 農業ツリーA-α/β系「食事」ギミックのflag/percent系dedicated-effect(各 skilltree/*.yml ノードの dedicated-effects: フィールド)
        // consumer群(stats/food-gimmick.yml でチューニング): ゴミ食免疫(junkfood-immunity)・ゴミ食/非ゴミ食の
        // 満腹度回復反転(junkfood-inversion)・食事非消費確率(no-food-consume-chance)・完全食満腹バフ
        // (satiety-buff)・カスタム食料(custom-foods: 圧縮食料等の満腹度/隠し満腹度をREPLACE方式で上書き、
        // 解放はskilltree側のrecipe:ゲートで管理、flagゲート無しで常時有効)。
        getServer().getPluginManager().registerEvents(
                new FoodGimmickListener(this, configManager.dedicatedEffects(), configManager.foodGimmick(),
                        aggregator), this);

        // 釣果の宝/ゴミ置換 (2026-07-23 stat-gate-overhaul §2.3/§4): fishing.groups(treasure/junk) drop-table
        // + fishing_luck由来の宝率シフト。テーブル未設定時は旧junk/treasureマテリアルリストの
        // junk-to-scrapフォールバックへ自動的に切替わる(FishingGimmickConfig#dropTablesEmpty参照)。
        // fish-sell-toggle保持時は宝抽選をゴミ抽選へ丸ごと差し替える(2026-07-25経済連携、クラスjavadoc参照)。
        // FishingQualityListener(NORMAL)より先に走らせる必要があるため EventPriority.LOW で登録。
        FishingGimmickListener fishingGimmickListener = new FishingGimmickListener(
                configManager.dedicatedEffects(), configManager.fishingGimmick(),
                crossPluginItemResolver, playerStatAggregator, skillLevelSource, fishingTreasureKey);
        // ENCHANTED_BOOK の釣果に中身のエンチャントを抽選するとき、サーバから消してあるエンチャント
        // (removed-vanilla-items、既定は修繕)を候補から外すために参照する(2026-07-30)。
        fishingGimmickListener.setVanillaItemRemover(vanillaItemRemover);
        getServer().getPluginManager().registerEvents(fishingGimmickListener, this);

        // fish-sell-toggle (2026-07-25 経済連携): 釣った魚を釣った瞬間に自動でVault通貨へ換金する。
        // Vault不在時はeconomyBridge.available()==falseで静かに無効化(通常どおりアイテムとして入手)。
        // 宝/ゴミ置換(FishingGimmickListener, LOW)・品質刻印(FishingQualityListener, NORMAL)の後、
        // 最終確定したMaterialに対して判定する必要があるため EventPriority.MONITOR で登録。
        com.trinityforge.listeners.FishSellListener fishSellListener =
                new com.trinityforge.listeners.FishSellListener(configManager.dedicatedEffects(),
                        configManager.fishingGimmick(), aggregator, economyBridge);
        getServer().getPluginManager().registerEvents(fishSellListener, this);

        // enchanting.yml B-3のxp-bottle-store-unlock(flag): 経験値瓶への経験値の格納/取出(MVP)。
        getServer().getPluginManager().registerEvents(
                new XpBottleListener(configManager.dedicatedEffects(), configManager.fishingGimmick()), this);

        // Vanilla mob-type system (combat/mob-types.yml): EntityType-keyed level/defense/coordinate
        // scaling + extra drops, independent of the EliteMobs-keyed mob-profiles.yml system. The
        // spawn listener stamps the PDC profile; the drop listener rolls the extra drop table.
        getServer().getPluginManager().registerEvents(
                new MobTypeSpawnListener(this, configManager.mobTypes()), this);
        // 変身(ゾンビ→ドラウンド等)で PDC と MAX_HEALTH が完全に消えるのを埋める。
        // MobTypeSpawnListener より先(EntityTransformEvent は CreatureSpawnEvent の前)に走るので、
        // 引き継いだダンジョンテーマ等を同リスナーが見られる。
        getServer().getPluginManager().registerEvents(new MobTransformListener(), this);
        MobTypeDropListener mobTypeDropListener =
                new MobTypeDropListener(configManager.mobTypes(), configManager.craftQuality(),
                        configManager.quality(), itemFactory, mobDropBonusSource,
                        configManager.itemStats(), crossPluginItemResolver);
        getServer().getPluginManager().registerEvents(mobTypeDropListener, this);

        // レベル帯テーブル(combat/mob-level-table.yml): ドロップ削除/追加/バニラEXP上書き、
        // 任意でダンジョンインスタンスワールド限定。field/dungeon両方のレベル刻印モブに適用。
        // HIGH優先度で MobTypeDropListener(NORMAL) の後に走らせる(§report参照)。
        MobLevelTableListener mobLevelTableListener = new MobLevelTableListener(
                configManager.mobLevelTable(), dungeonWorldRegistry, crossPluginItemResolver);
        getServer().getPluginManager().registerEvents(mobLevelTableListener, this);

        // ダンジョン(ワールド)×モブid単位のドロップオーバーライド(combat/mob-overrides.yml、
        // 2026-07-26新設)。MONITOR優先度でMobLevelTableListener(HIGH)より後に走らせ、EliteMobs自身の
        // LootTables#onDeathによるgetDrops()クリアの影響も受けない(MobOverrideDropListener Javadoc参照)。
        MobOverrideDropListener mobOverrideDropListener =
                // 2026-07-27: レベル差による足きり判定に戦闘レベル(SymmetricCombatService)が要る。
                new MobOverrideDropListener(configManager.mobOverrides(), crossPluginItemResolver,
                        combatService);
        getServer().getPluginManager().registerEvents(mobOverrideDropListener, this);

        // 同じ combat/mob-overrides.yml の「モブごとのレベル依存EXP式」(2026-07-26)。同じMONITOR優先度で
        // MobLevelTableListener(HIGH)のレベル帯 vanilla-exp より後 = より具体的な指定が勝つ。
        getServer().getPluginManager().registerEvents(
                // 2026-07-27: 足きりの exp-rate 判定にも戦闘レベルが要る。
                new MobOverrideExpListener(configManager.mobOverrides(), combatService), this);

        // AFK(離席)対策 (2026-07-27, afk.yml): 判定は AfkActivityListener が集める「人間にしか出せない
        // 入力」だけで行う。報酬停止はここで4経路へ述語を挿す —
        //   ①スキルEXP: NativeExperienceDispatcher(ゲームプレイEXPの唯一の合流点)
        //   ②バニラEXP: AfkSuppressionListener(PlayerExpChangeEvent、オーブ回収も含む)
        //   ③TF追加ドロップ: mob-types / mob-level-table / mob-overrides の add-drops
        //   ④釣り自動換金: FishSellListener
        // 抑止の可否は都度 config を読む(reload で即反映され、再配線が要らない)。
        this.afkService = new com.trinityforge.afk.AfkService(this, configManager.afk());
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.afk.AfkActivityListener(afkService, configManager.afk()), this);
        getServer().getPluginManager().registerEvents(
                new com.trinityforge.afk.AfkSuppressionListener(afkService, configManager.afk()), this);
        experienceDispatcher.setGrantSuppressor(playerId ->
                configManager.afk().suppressSkillExp() && afkService.isSuppressed(playerId));
        mobTypeDropListener.setDropGate(killer ->
                configManager.afk().suppressMobDrops() && afkService.isAfk(killer));
        mobLevelTableListener.setDropGate(killer ->
                configManager.afk().suppressMobDrops() && afkService.isAfk(killer));
        mobOverrideDropListener.setDropGate(killer ->
                configManager.afk().suppressMobDrops() && afkService.isAfk(killer));
        fishSellListener.setSellGate(player ->
                configManager.afk().suppressFishingSell() && afkService.isAfk(player));
        afkService.start();

        // TT/放置対策(同一地点の逓減)。自身はEXPを配らず、上記2リスナーと戦闘/防具EXP経路から
        // 呼ばれる縮小係数の供給元。Listenerとして登録するのはログアウト時の履歴破棄のためだけ。
        getServer().getPluginManager().registerEvents(locationExpDiminishing, this);

        // Cosmetic focus-target HP display (TextDisplay above the targeted mob). Purely visual;
        // no combat/config coupling beyond reading MobData for the level tag.
        // 2026-07-26: TF側の表示に出すモブ名は combat/mob-overrides.yml の display-name を最優先にする
        // (出荷ymlに396体ぶんの日本語名が入っている)。解決は MobDisplayNames に一元化。
        MobDisplayNames mobDisplayNames = new MobDisplayNames(configManager.mobOverrides());
        this.focusHpDisplay = new FocusHpDisplay(this, mobDisplayNames);
        getServer().getPluginManager().registerEvents(focusHpDisplay, this);
        if (configManager.display().focusHpEnabled()) {
            focusHpDisplay.start();
        }

        // TF実ダメージのポップアップ表示(頭上に短命TextDisplayで数値のみ表示)。cosmetic、config駆動。
        this.damagePopupDisplay = new DamagePopupDisplay(this, configManager.display());
        getServer().getPluginManager().registerEvents(damagePopupDisplay, this);
        damagePopupDisplay.start();

        installDamageIndicatorLimiter();

        // 称号頭上表示(パッセンジャーTextDisplay) + パーティクル演出の周期タスク開始。
        titleDisplayService.start();
        particleEffectService.start();
        // アチーブメント統計ポーリング(1分毎、§6.2): JUMP/WALK_ONE_CM/PLAY_ONE_MINUTE等バニラStatistic。
        this.achievementPollTask = getServer().getScheduler().runTaskTimer(this,
                achievementService::pollStatistics, 20L * 60, 20L * 60);

        // 敵の特殊攻撃(2026-07-31): combat/mob-abilities.yml のテンプレートを
        // combat/mob-overrides.yml の abilities: に従って撃つ。プレイヤー周囲だけを走査する。
        this.mobAbilityTask = new com.trinityforge.combat.MobAbilityTask(this,
                configManager.mobAbilities(), configManager.mobOverrides(),
                new com.trinityforge.combat.MobAbilityExecutor(this, combatService),
                new com.trinityforge.combat.MobAbilityCooldowns(),
                new java.util.Random());
        mobAbilityTask.start();

        // Block player-facing /em /ag while TF owns progression (ops can bypass).
        getServer().getPluginManager().registerEvents(new EliteMobsCommandGateListener(), this);

        registerCommands();
        // Publish the singleton only after every field is initialized and registration is complete,
        // so anything reaching getInstance() during enable never observes a half-built plugin.
        instance = this;
        getLogger().info("TrinityForge enabled (Paper 1.21.11).");
    }

    /**
     * バニラの被弾パーティクル({@code damage_indicator})の個数上限を有効化する(任意依存)。
     *
     * <p>packetevents が無い環境では {@link com.trinityforge.combat.DamageIndicatorParticleLimiter}
     * を<b>参照してはならない</b>(packetevents の型を直接持つのでクラスロードが
     * {@link NoClassDefFoundError} になる)。そのためプラグイン存在チェックを先に行い、
     * それでも失敗した場合は {@link Throwable} ごと握って警告1行に留める(起動は絶対に止めない)。
     */
    private void installDamageIndicatorLimiter() {
        if (getServer().getPluginManager()
                .getPlugin(com.trinityforge.combat.DamageIndicatorParticleLimiter.PACKETEVENTS_PLUGIN) == null) {
            getLogger().info("[display] packetevents が未導入のため damage_indicator パーティクル上限は"
                    + "無効です(バニラそのままの個数で表示されます)。");
            return;
        }
        try {
            this.damageIndicatorUninstaller =
                    com.trinityforge.combat.DamageIndicatorParticleLimiter.install(this, configManager.display());
        } catch (Throwable ex) { // NoClassDefFoundError も含めて握る — 表示だけの機能で起動を止めない
            getLogger().warning("[display] damage_indicator パーティクル上限を有効化できませんでした: " + ex);
        }
    }

    @Override
    public void onDisable() {
        // Stop the sweep task and drop all tracked threat so a disable/hot-reload leaks nothing.
        if (hateService != null) {
            hateService.shutdown();
        }
        // Stop the bleed tick task and drop all active bleeds (same leak-safety as hate).
        if (bleedService != null) {
            bleedService.shutdown();
        }
        // Stop the held-perk mirror backfill task (leak-safety, mirrors hate/bleed).
        if (perkMirrorService != null) {
            perkMirrorService.shutdown();
        }
        // 危険な点3(離脱経路)の安全網: プラグイン無効化時に全オンラインプレイヤーの採集効率エンチャント
        // 付与分を剥がす(付与中に無効化されると焼き付いたまま残るため)。
        if (gatheringEfficiencyApplier != null) {
            gatheringEfficiencyApplier.stripAllOnline();
        }
        // Cancel the AFK check task (leak-safety, mirrors hate/bleed).
        if (afkService != null) {
            afkService.stop();
        }
        // Cancel the focus-HP tick task and despawn every tracked TextDisplay (leak-safety).
        if (focusHpDisplay != null) {
            focusHpDisplay.shutdown();
        }
        // packetevents は TF の無効化でリスナーを自動的に外さないので明示的に外す(多重登録防止)。
        if (damageIndicatorUninstaller != null) {
            try {
                damageIndicatorUninstaller.run();
            } catch (Throwable ex) {
                getLogger().warning("[display] damage_indicator パーティクル上限の登録解除に失敗しました: " + ex);
            }
            damageIndicatorUninstaller = null;
        }
        // DamagePopupDisplay has no shutdown(): its displays are one-shot and so short-lived (default
        // 15 ticks = 0.75s) that forced cleanup on disable is unnecessary — each already schedules its
        // own removal, and any left behind by a crash are swept on the next enable.
        // Cancel the title-display/particle tick tasks and despawn every tracked TextDisplay (leak-safety).
        if (titleDisplayService != null) {
            titleDisplayService.shutdown();
        }
        if (particleEffectService != null) {
            particleEffectService.shutdown();
        }
        if (achievementPollTask != null) {
            achievementPollTask.cancel();
            achievementPollTask = null;
        }
        if (mobAbilityTask != null) {
            mobAbilityTask.stop();
            mobAbilityTask = null;
        }
        try {
            if (experienceDispatcher != null) {
                experienceDispatcher.close();
                experienceDispatcher = null;
            }
        } finally {
            if (progressionRepository != null) {
                progressionRepository.close();
                progressionRepository = null;
                progressionService = null;
            }
        }
        // Clear the static reference so a hot-reload (PlugMan etc.) does not retain the dead plugin
        // instance and leak its classloader, and so getInstance() never returns a disabled plugin.
        instance = null;
        getLogger().info("TrinityForge disabled.");
    }

    /** Admin gate: OP or explicit {@code trinityforge.admin} (LuckPerms can hide default:op from Brigadier). */
    private static boolean isTfAdmin(CommandSourceStack src) {
        var sender = src.getSender();
        return sender.isOp() || sender.hasPermission("trinityforge.admin");
    }

    /**
     * Rebuilds online-player projections whose source configs may have changed during reload.
     * Kept as one operation so a newly added projection cannot silently diverge from the reload path.
     */
    void reapplyOnlinePlayerProjectionsAfterReload() {
        if (perkAttributeApplier != null) {
            perkAttributeApplier.applyAllOnline();
        }
        if (gatheringEfficiencyApplier != null) {
            gatheringEfficiencyApplier.applyAllOnline();
        }
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();
            commands.register(
                    Commands.literal("trinityforge")
                            .requires(src -> isTfAdmin(src)
                                    || src.getSender().hasPermission("trinityforge.use"))
                            .executes(ctx -> {
                                ctx.getSource().getSender().sendMessage(Component.text(
                                        "用法: /tf <reload|skills|achievement|start|stop|progression|give|bind|stamp|import|dungeon|stats|status|role|collection|recipes|glyphs|settings|reward|inspect>",
                                        NamedTextColor.YELLOW));
                                ctx.getSource().getSender().sendMessage(Component.text(
                                        "※ reload/progression/give/bind/stamp/import/dungeon/reward は OP または trinityforge.admin が必要です。",
                                        NamedTextColor.GRAY));
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.literal("reload")
                                    .requires(TrinityForge::isTfAdmin)
                                    .executes(ctx -> {
                                        int issues = configManager.loadAll();
                                        if (progressionCatalog != null
                                                && !progressionCatalog.reload(getDataFolder(), getClassLoader())) {
                                            issues++;
                                            getLogger().warning("NativeSkillCatalog reload failed; previous curve snapshot retained");
                                        } else if (progressionCatalog != null && progressionRepository != null) {
                                            try {
                                                int rewritten = new com.trinityforge.progression.ProgressionCurveReconciler(
                                                        progressionRepository, progressionCatalog).recalculateAll();
                                                if (rewritten > 0) {
                                                    getLogger().info("[progression] curve/cap reconciler rewrote "
                                                            + rewritten + " skill row(s)");
                                                }
                                            } catch (RuntimeException ex) {
                                                issues++;
                                                getLogger().log(java.util.logging.Level.WARNING,
                                                        "[progression] curve reconciler failed; levels may lag new curves",
                                                        ex);
                                            }
                                        }
                                        // Every already-assembled item becomes stale as of this reload;
                                        // ItemRefreshListener re-syncs them lazily as they come back into
                                        // play (item 1).
                                        if (tableGeneration != null) {
                                            tableGeneration.bump();
                                        }
                                        if (itemRefreshListener != null) {
                                            itemRefreshListener.refreshAllOnlinePlayers();
                                        }
                                        // Re-apply hate caps/decay/TTL/sweep cadence from the freshly
                                        // loaded config (the table caches its settings snapshot).
                                        if (hateService != null) {
                                            hateService.applyConfig();
                                        }
                                        // Reloaded stats/perks may change runtime projections already
                                        // present on online players; update both attributes and the
                                        // gathering-efficiency enchant mirror immediately.
                                        reapplyOnlinePlayerProjectionsAfterReload();
                                        // A reload may have added/edited/removed a catalog `recipe:`;
                                        // re-derive the whole registered set (fail-soft per entry).
                                        if (catalogRecipeRegistrar != null) {
                                            catalogRecipeRegistrar.registerAll();
                                            CatalogRitualBridge.registerAll(TrinityForge.this, configManager.itemCatalog());
                                        }
                                        // brew-unlocks の増減を醸造 customMixes へ反映する (D10)。
                                        if (brewPotionMixRegistrar != null) {
                                            brewPotionMixRegistrar.registerAll();
                                        }
                                        // レシピの増減 / recipe: ゲートの配置替えをレシピ帳へ反映する (D7)。
                                        if (recipeDiscoveryListener != null) {
                                            recipeDiscoveryListener.reconcileAllOnline();
                                        }
                                        // removed-vanilla-recipes の増減を反映 (外れた分は復元)。
                                        if (vanillaRecipeRemover != null) {
                                            vanillaRecipeRemover.apply(
                                                    configManager.craftingFeatures().removedVanillaRecipes());
                                        }
                                        // removed-vanilla-items の増減を反映し、オンライン全員から即掃除。
                                        if (vanillaItemRemover != null) {
                                            vanillaItemRemover.updateTargets(
                                                    configManager.craftingFeatures().removedVanillaItems(),
                                                    getLogger());
                                            if (vanillaItemRemovalListener != null) {
                                                vanillaItemRemovalListener.sweepAllOnline();
                                            }
                                        }
                                        // AFK判定タイマーの間隔/有効フラグが変わりうるので張り直す
                                        // (start() は冪等: 既存タスクを止めてから再スケジュールする)。
                                        if (afkService != null) {
                                            afkService.start();
                                        }
                                        // special-rewards.yml から報酬IDが削除されていたら、オンライン
                                        // 全員のPDC保持分を即座に掃除する(安全弁はプルーナー自身が持つ)。
                                        if (specialRewardPruneListener != null) {
                                            specialRewardPruneListener.pruneAllOnline();
                                        }
                                        var sender = ctx.getSource().getSender();
                                        if (issues == 0) {
                                            sender.sendMessage(Component.text(
                                                    "TrinityForge config reloaded.", NamedTextColor.GREEN));
                                        } else {
                                            sender.sendMessage(Component.text(
                                                    "Config reloaded with issues in " + issues
                                                            + " domain(s); see console.", NamedTextColor.YELLOW));
                                        }
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("skills")
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getSender() instanceof Player player)) {
                                            ctx.getSource().getSender().sendMessage(Component.text(
                                                    "プレイヤーのみ実行できます。", NamedTextColor.RED));
                                            return 0;
                                        }
                                        nativeSkillTreeMenu.open(player);
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("progression")
                                    .requires(TrinityForge::isTfAdmin)
                                    .then(Commands.literal("level")
                                            .then(Commands.argument("player", StringArgumentType.word())
                                                    .suggests((ctx, builder) -> {
                                                        getServer().getOnlinePlayers().stream()
                                                                .map(Player::getName)
                                                                .filter(name -> name.toLowerCase(java.util.Locale.ROOT)
                                                                        .startsWith(builder.getRemainingLowerCase()))
                                                                .forEach(builder::suggest);
                                                        return builder.buildFuture();
                                                    })
                                                    .then(Commands.argument("skill", StringArgumentType.word())
                                                            .suggests((ctx, builder) -> {
                                                                progressionCatalog.entries().keySet().stream()
                                                                        .filter(skill -> skill.toLowerCase(
                                                                                        java.util.Locale.ROOT)
                                                                                .startsWith(
                                                                                        builder.getRemainingLowerCase()))
                                                                        .forEach(builder::suggest);
                                                                return builder.buildFuture();
                                                            })
                                                            .then(Commands.argument(
                                                                            "operation",
                                                                            StringArgumentType.word())
                                                                    .suggests((ctx, builder) -> {
                                                                        for (String operation : List.of(
                                                                                "set", "add", "subtract")) {
                                                                            if (operation.startsWith(
                                                                                    builder.getRemainingLowerCase())) {
                                                                                builder.suggest(operation);
                                                                            }
                                                                        }
                                                                        return builder.buildFuture();
                                                                    })
                                                                    .then(Commands.argument(
                                                                                    "amount",
                                                                                    IntegerArgumentType.integer(0))
                                                                            .executes(ctx ->
                                                                                    executeProgressionLevel(
                                                                                            ctx.getSource(),
                                                                                            StringArgumentType.getString(
                                                                                                    ctx, "player"),
                                                                                            StringArgumentType.getString(
                                                                                                    ctx, "skill"),
                                                                                            StringArgumentType.getString(
                                                                                                    ctx, "operation"),
                                                                                            IntegerArgumentType.getInteger(
                                                                                                    ctx, "amount"),
                                                                                            null))
                                                                            .then(Commands.argument(
                                                                                            "prestige",
                                                                                            IntegerArgumentType.integer(0))
                                                                                    .executes(ctx ->
                                                                                            executeProgressionLevel(
                                                                                                    ctx.getSource(),
                                                                                                    StringArgumentType.getString(
                                                                                                            ctx, "player"),
                                                                                                    StringArgumentType.getString(
                                                                                                            ctx, "skill"),
                                                                                                    StringArgumentType.getString(
                                                                                                            ctx, "operation"),
                                                                                                    IntegerArgumentType.getInteger(
                                                                                                            ctx, "amount"),
                                                                                                    IntegerArgumentType.getInteger(
                                                                                                            ctx, "prestige")))))))))
                                    .then(Commands.literal("exp")
                                            .then(Commands.argument("player", StringArgumentType.word())
                                                    .then(Commands.argument("skill", StringArgumentType.word())
                                                            .then(Commands.argument("amount",
                                                                            DoubleArgumentType.doubleArg())
                                                                    .executes(ctx -> {
                                                                        String playerName = StringArgumentType.getString(
                                                                                ctx, "player");
                                                                        Player target = getServer().getPlayerExact(playerName);
                                                                        if (target == null) {
                                                                            ctx.getSource().getSender().sendMessage(Component.text(
                                                                                    "オンラインプレイヤーが見つかりません: "
                                                                                            + playerName,
                                                                                    NamedTextColor.RED));
                                                                            return 0;
                                                                        }
                                                                        try {
                                                                            var result = progressionService.grantExp(
                                                                                    target.getUniqueId(),
                                                                                    StringArgumentType.getString(ctx, "skill"),
                                                                                    DoubleArgumentType.getDouble(ctx, "amount"));
                                                                            if (result.after() == null) {
                                                                                ctx.getSource().getSender().sendMessage(Component.text(
                                                                                        "EXP変化はありません。", NamedTextColor.YELLOW));
                                                                                return Command.SINGLE_SUCCESS;
                                                                            }
                                                                            ctx.getSource().getSender().sendMessage(Component.text(
                                                                                    result.skillId() + " Lv "
                                                                                            + result.after().level(),
                                                                                    NamedTextColor.GREEN));
                                                                            return Command.SINGLE_SUCCESS;
                                                                        } catch (IllegalArgumentException ex) {
                                                                            ctx.getSource().getSender().sendMessage(Component.text(
                                                                                    ex.getMessage(), NamedTextColor.RED));
                                                                            return 0;
                                                                        }
                                                                    })))))
                                    .then(Commands.literal("reset")
                                            .then(Commands.argument("player", StringArgumentType.word())
                                                    .executes(ctx -> {
                                                        Player target = getServer().getPlayerExact(
                                                                StringArgumentType.getString(ctx, "player"));
                                                        if (target == null) return 0;
                                                        progressionRepository.resetPlayer(target.getUniqueId());
                                                        perkAttributeApplier.apply(target);
                                                        perkMirrorService.sync(target);
                                                        ctx.getSource().getSender().sendMessage(Component.text(
                                                                "進行データを初期化しました: " + target.getName(),
                                                                NamedTextColor.GREEN));
                                                        return Command.SINGLE_SUCCESS;
                                                    })))
                                    .then(Commands.literal("save")
                                            .executes(ctx -> {
                                                progressionRepository.flush();
                                                ctx.getSource().getSender().sendMessage(Component.text(
                                                        "進行DBをcheckpointしました。", NamedTextColor.GREEN));
                                                return Command.SINGLE_SUCCESS;
                                            }))
                                    .then(Commands.literal("diagnose")
                                            .then(Commands.argument("player", StringArgumentType.word())
                                                    .executes(ctx -> {
                                                        Player target = getServer().getPlayerExact(
                                                                StringArgumentType.getString(ctx, "player"));
                                                        if (target == null) return 0;
                                                        var snapshot = progressionService.snapshot(target.getUniqueId());
                                                        ctx.getSource().getSender().sendMessage(Component.text(
                                                                snapshot.toString(), NamedTextColor.GRAY));
                                                        return Command.SINGLE_SUCCESS;
                                                    }))))
                            .then(giveItemCommand.node()
                                    .requires(TrinityForge::isTfAdmin))
                            .then(bindCommand.node()
                                    .requires(TrinityForge::isTfAdmin))
                            .then(stampCommand.node()
                                    .requires(TrinityForge::isTfAdmin))
                            .then(importMobsCommand.node()
                                    .requires(TrinityForge::isTfAdmin))
                            .then(dungeonCommand.node()
                                    .requires(TrinityForge::isTfAdmin))
                            // プレイヤー向け(requiresなし = trinityforge.use で誰でも)。
                            // EliteMobs の /em start・/em quit に相当する正式なTF側入口。
                            .then(instanceCommand.startNode())
                            .then(instanceCommand.stopNode())
                            .then(instanceCommand.quitNode())
                            .then(statsCommand.node())
                            // /tf status: ステータス確認GUI (2026-07-29)。/tf stats と同じ値。
                            .then(Commands.literal("status")
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getSender() instanceof Player player)) {
                                            ctx.getSource().getSender().sendMessage(Component.text(
                                                    "プレイヤーのみ実行できます。", NamedTextColor.RED));
                                            return 0;
                                        }
                                        statusGui.open(player);
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            // /tf achievement: 単独の /achievement と同じGUI(2026-07-30)。
                            .then(achievementNode())
                            .then(roleCommand.node())
                            .then(collectionCommand.node())
                            .then(recipesCommand.node())
                            .then(glyphsCommand.node())
                            .then(settingsCommand.node())
                            .then(specialRewardCommand.node()
                                    .requires(TrinityForge::isTfAdmin))
                            .then(new InspectCommand().node())
                            // DEBUG専用(2026-07-25 §6 Q2): /tf 用法テキスト・タブ補完のプレイヤー導線には
                            // 出さない。正式トリガーはスニーク+右クリック(ActivationDispatcher)。
                            .then(activeCommand.node().requires(TrinityForge::isTfAdmin))
                            .build(),
                    "TrinityForge admin command",
                    List.of("tf"));
            commands.register(
                    Commands.literal("skills")
                            .requires(src -> src.getSender().hasPermission("trinityforge.use"))
                            .executes(ctx -> {
                                if (!(ctx.getSource().getSender() instanceof Player player)) {
                                    ctx.getSource().getSender().sendMessage(Component.text(
                                            "プレイヤーのみ実行できます。", NamedTextColor.RED));
                                    return 0;
                                }
                                nativeSkillTreeMenu.open(player);
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.argument("skill", StringArgumentType.word())
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getSender() instanceof Player player)) {
                                            ctx.getSource().getSender().sendMessage(Component.text(
                                                    "プレイヤーのみ実行できます。", NamedTextColor.RED));
                                            return 0;
                                        }
                                        nativeSkillTreeMenu.open(
                                                player, StringArgumentType.getString(ctx, "skill"));
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .build(),
                    "Open the TrinityForge skill tree",
                    List.of("s"));
            // アチーブメント進捗GUI(2026-07-29)。スキルツリーと同じ操作感にそろえてある。
            // 2026-07-30: /tf のサブコマンドにも同じノードを生やした(/skills と /tf skills が
            // 併存しているのと同じ形)。ノード定義は achievementNode() の1本だけで、
            // 単独コマンドと /tf achievement のどちらからも同じものを組み立てる。
            commands.register(
                    achievementNode()
                            .requires(src -> src.getSender().hasPermission("trinityforge.use"))
                            .build(),
                    "Open the TrinityForge achievement progress GUI",
                    List.of("achievements", "ach"));
        });
    }

    /**
     * {@code achievement} サブツリー。単独コマンド {@code /achievement} と {@code /tf achievement} の
     * 両方から使う(Brigadier のノードは1つのツリーにしか繋げないため、builder を都度組み立てる)。
     */
    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> achievementNode() {
        return Commands.literal("achievement")
                .executes(ctx -> {
                    if (!(ctx.getSource().getSender() instanceof Player player)) {
                        ctx.getSource().getSender().sendMessage(Component.text(
                                "プレイヤーのみ実行できます。", NamedTextColor.RED));
                        return 0;
                    }
                    achievementGui.open(player);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("id", StringArgumentType.word())
                        .executes(ctx -> {
                            if (!(ctx.getSource().getSender() instanceof Player player)) {
                                ctx.getSource().getSender().sendMessage(Component.text(
                                        "プレイヤーのみ実行できます。", NamedTextColor.RED));
                                return 0;
                            }
                            achievementGui.open(player, StringArgumentType.getString(ctx, "id"));
                            return Command.SINGLE_SUCCESS;
                        }));
    }

    private int executeProgressionLevel(
            CommandSourceStack source, String playerName, String skill,
            String rawOperation, int amount, Integer prestige) {
        Player target = getServer().getPlayerExact(playerName);
        if (target == null) {
            source.getSender().sendMessage(Component.text(
                    "オンラインプレイヤーが見つかりません: " + playerName, NamedTextColor.RED));
            return 0;
        }
        NativeProgressionAdminService.EditMode mode;
        try {
            mode = NativeProgressionAdminService.EditMode.valueOf(
                    rawOperation.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            source.getSender().sendMessage(Component.text(
                    "操作は set / add / subtract のいずれかです。", NamedTextColor.RED));
            return 0;
        }
        var result = progressionAdminService.edit(
                target.getUniqueId(), skill, mode, amount, prestige);
        if (result.status() != NativeProgressionAdminService.EditStatus.APPLIED) {
            String message = switch (result.status()) {
                case UNKNOWN_SKILL -> "不明なスキルです: " + result.skillId();
                case INVALID_AMOUNT -> "値は0以上で指定してください。";
                case LEVEL_OUT_OF_RANGE -> "変更後レベルが許容範囲外です。";
                case PRESTIGE_DISABLED -> "このスキルではプレステージを設定できません。";
                case PRESTIGE_OUT_OF_RANGE -> "プレステージ回数が許容範囲外です。";
                case POINT_LEDGER_CONFLICT ->
                        "変更後POWERでは取得済みノードの消費ポイントを維持できません。";
                case STORAGE_FAILURE -> "進行DBの更新に失敗しました。コンソールを確認してください。";
                case APPLIED -> throw new IllegalStateException("unreachable");
            };
            source.getSender().sendMessage(Component.text(message, NamedTextColor.RED));
            return 0;
        }
        perkAttributeApplier.apply(target);
        perkMirrorService.sync(target);
        source.getSender().sendMessage(Component.text(
                target.getName() + " の " + result.skillId() + " を Lv"
                        + result.after().level() + " に更新しました"
                        + (prestige == null ? "。" : "（Prestige " + prestige + "）。"),
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    public static TrinityForge getInstance() {
        return instance;
    }

    public ConfigManager config() {
        return configManager;
    }

    public NativeProgressionService progressionService() {
        return progressionService;
    }

    public NativeExperienceDispatcher experienceDispatcher() {
        return experienceDispatcher;
    }

    /**
     * The shared damage service. The Ars integration (M2) calls
     * {@link SymmetricCombatService#magicalFinalDamage} on this to route spell damage through the
     * symmetric pipeline. Null until {@link #onEnable} has run.
     */
    public SymmetricCombatService combatService() {
        return combatService;
    }

    /**
     * 課題1(魔法出血)の公開API。ArsPaperフォークの {@code TrinityForgeBridge} が魔法ヒット
     * (最終ダメージ確定後)のたびに呼ぶ想定で、{@code bleed-chance}/{@code bleed-damage} の
     * ロール判定と {@link BleedService#apply} 呼び出しの責務はここ(TF側)へ一本化する
     * ——フォーク側に確率ロジックを持ち込ませないため。
     *
     * <p>{@code attackerStats} は近接の {@code agg.item()} と対称に、魔法ダメージ計算そのものが
     * 読んだのと同じ攻撃集約(装備4部位 + (設定により)オフハンド + パーク + アドオン + 触媒自身の
     * 解決済みステ。メインハンド武器は除外——触媒がその代わりを務めるため)であること。
     * {@link #bleedService} が未初期化({@link #onEnable} 完了前)なら no-op。
     *
     * @param attackerStats 魔法ダメージが読んだのと同じ攻撃集約(canonical key -&gt; 値)
     * @param victim        出血を負わせる対象
     * @param attackerId    出血の帰属先(詠唱者)UUID
     * @param finalDamage   実際に適用された(または適用予定の)最終魔法ダメージ。0以下(回復含む)は no-op
     */
    public void applyMagicBleed(Map<String, Double> attackerStats, LivingEntity victim,
                                 UUID attackerId, double finalDamage) {
        if (bleedService != null) {
            bleedService.maybeApplyFromAggregate(attackerStats, victim, attackerId, finalDamage);
        }
    }

    /**
     * Shared dungeon entry gate evaluation ({@code dungeon/gates.yml}). EliteMobs instanced-dungeon
     * joins and TF cross-world teleports both route through this service.
     */
    public DungeonGateService dungeonGateService() {
        return dungeonGateService;
    }

    /**
     * Registry of instanced dungeon world UUIDs, populated by the EliteMobs fork (softdepend on
     * TrinityForge). The fork reads this via {@code TrinityForge.getInstance().dungeonWorldRegistry()}.
     */
    public DungeonWorldRegistry dungeonWorldRegistry() {
        return dungeonWorldRegistry;
    }

    /**
     * 死亡時の装備耐久ペナルティ({@code combat/damage.yml durability.on-death})を適用する。
     *
     * <p>EliteMobs のインスタンスダンジョンは致死ダメージをキャンセルして「ダウン」状態へ移すため
     * {@code PlayerDeathEvent} が発火せず、TF の死亡ペナルティが一切走らなかった(EliteMobs 自前の
     * {@code AlternativeDurabilityLoss} は EliteMobs 製アイテムしか対象にしない)。フォークの
     * {@code InstancePlayerManager#playerDeath} からこれを直接呼ぶことで穴を埋める。
     *
     * <p>{@code onEnable} 前や設定で無効化されている場合は何もしない。適用条件(クリエイティブ除外・
     * {@code dungeon-only})の判定はサービス側が持つので、呼び出し側は無条件に呼んでよい。
     */
    public void applyDeathDurabilityPenalty(org.bukkit.entity.Player player) {
        if (equipmentDurabilityService != null && player != null) {
            equipmentDurabilityService.applyOnDeath(player);
        }
    }

    /**
     * TT/放置対策の「同一地点の逓減」トラッカー (2026-07-26)。武器EXP・防具EXP・バニラEXPの3経路が
     * 同じインスタンスを共有する — 経路ごとに別勘定にすると、経路を混ぜるだけで逓減を回避できるため。
     */
    public com.trinityforge.progression.LocationExpDiminishing locationExpDiminishing() {
        return locationExpDiminishing;
    }

    /**
     * The shared player stat aggregator (#3 全ステ合算, {@link PlayerStatAggregator}). Exposed so the
     * ArsPaper fork can build a caster's "other equipment excluding mainhand" attack stats for Change 1
     * (P10 magic aggregation, ARSPAPER_FORK_SPEC) via {@link PlayerStatAggregator#aggregateExcludingMainhand}
     * without duplicating the armor/offhand/perk/addon accumulation logic. Null until {@link #onEnable}
     * has run.
     */
    public PlayerStatAggregator playerStatAggregator() {
        return playerStatAggregator;
    }

    /**
     * The player's総合stat total (装備 + skill-tree perk合算, mainhand context) for {@code key} — the
     * public read seam forks use instead of the retired perk-only native reward path (2026-07-23
     * stat-gate-overhaul §2 移行B14: fork consumer系 {@code lapis_cost_reduction}/
     * {@code source_cost_reduction}/{@code material_refund_chance}/{@code ingredient_save_chance}
     * 等はこのAPI経由で読む想定。fork側の切替は別ウェーブ)。{@code 0.0} when
     * the aggregator has not been built yet (before {@link #onEnable} completes) or {@code player} is
     * {@code null}.
     */
    public double statTotal(Player player, String key) {
        if (player == null || playerStatAggregator == null) {
            return 0.0;
        }
        return playerStatAggregator.aggregate(player).totalOf(com.trinityforge.stats.StatKeys.canonical(key));
    }

    /**
     * 装備アイテム由来を除いた、プレイヤー単位の寄与(パーク general / 役職バフ / 永続バフ /
     * base-stats)だけの合計 — {@link #statTotal} の兄弟API(2026-07-26 マナ系ステ穴埋め)。
     * フォークのように「装備は自前で集計済み」の消費者(ArsPaper の {@code ArmorManaListener}/
     * {@code SpellCaster} 等)が、TF の装備集計と二重計上せずにパーク分だけを上乗せするための
     * 読み取り口。詳細は {@link PlayerStatAggregator#nonItemStatTotal} を参照(addon非含有・
     * multipliers非適用)。アグリゲータ未構築({@link #onEnable} 完了前)または {@code player} が
     * {@code null} のときは {@code 0.0}。
     */
    public double nonItemStatTotal(Player player, String key) {
        if (player == null || playerStatAggregator == null) {
            return 0.0;
        }
        return playerStatAggregator.nonItemStatTotal(player, com.trinityforge.stats.StatKeys.canonical(key));
    }

    /**
     * Glyph-id-keyed damage multiplier the ArsPaper fork's spell-effect classes should multiply their
     * final damage by (2026-07-25 ars_magic.yml B-3「害悪強化」). Backed by the generic
     * {@code glyph_damage_multiplier_bonus} stat (fraction, e.g. {@code 0.3}=+30%) — harmに決め打ちしない:
     * which glyph ids receive the bonus is config-driven ({@code stats/glyph-damage-boost.yml
     * boosted-glyphs}, {@link com.trinityforge.config.domains.GlyphDamageBoostConfig}), not hardcoded
     * here. {@code caster} must be the
     * player who cast the spell (this codebase's cross-cutting rule: only the executor's own stats ever
     * apply — never a bystander's, never a later picker-up's). Returns {@code 1.0} (no-op multiplier)
     * for a {@code null} caster/glyphId, before {@link #onEnable} completes, or when {@code glyphId} is
     * not in the boosted list.
     *
     * <p>設計注記: フォークは {@code glyphConfig} 経由ではなく、このAPIを直接呼ぶ想定
     * (グリフID→倍率の解決はTF側のconfigが真源であるため、フォーク側に重複した対象リストを持たせない)。
     * 公開APIを追加したため、フォーク連携には {@code TrinityForge/libs/TrinityForge.jar}(フォークが
     * compileOnlyで参照するAPI jar)の再生成が必要。
     */
    public double glyphDamageMultiplier(Player caster, String glyphId) {
        if (caster == null || glyphId == null || playerStatAggregator == null || configManager == null) {
            return 1.0;
        }
        if (!configManager.glyphDamageBoost().isBoosted(glyphId)) {
            return 1.0;
        }
        double bonus = statTotal(caster, "glyph_damage_multiplier_bonus");
        return 1.0 + Math.max(0.0, bonus);
    }

    /**
     * Derives {@link com.trinityforge.combat.AttackStats} from a catalyst/weapon {@link
     * org.bukkit.inventory.ItemStack}. The Ars integration (M2) calls {@code forItem(catalyst)} to
     * obtain the real attacker stats to pass to {@link SymmetricCombatService#magicalFinalDamage},
     * replacing the {@code AttackStats.plain(0)} placeholder. Null until {@link #onEnable} has run.
     */
    public WeaponAttackStatResolver weaponAttackStats() {
        return weaponAttackStatResolver;
    }

    /**
     * The skill-driven craft-quality roller. Exposed for the ArsPaper fork's ritual-craft path, which
     * hands over a finished item outside a {@code CraftItemEvent} (so {@code CraftQualityListener} does
     * not see it) and needs to stamp the same skill-driven quality as a normal craft. Null until
     * {@link #onEnable} has run.
     */
    public CraftQualityService craftQualityService() {
        return craftQualityService;
    }

    /**
     * The write-side item builder. The give command and (M3) fork drop/craft flows use it to
     * create catalog items so every item goes through one assembly path. Null until
     * {@link #onEnable} has run.
     */
    public ItemFactory itemFactory() {
        return itemFactory;
    }

    /**
     * Re-registers every catalog workbench recipe. Called by the ArsPaper fork from its enable hook
     * (alongside {@code repushCatalogRituals}) so recipe RESULTS whose catalog id is an Ars custom
     * item are rebuilt as real Ars items (with their functional {@code arspaper:*} PDC) — at
     * TrinityForge's own enable time Ars is not yet up and those results fall back to TF identity
     * builds. Idempotent; safe no-op before {@link #onEnable} completes.
     */
    public void refreshCatalogRecipes() {
        if (catalogRecipeRegistrar != null) {
            catalogRecipeRegistrar.registerAll();
        }
        // ArsPaper の enable でレシピが増えるので、レシピ帳の解禁も張り直す (D7)。
        // この時点でオンラインのプレイヤーは通常いないが、reload 経路と同じ扱いにしておく。
        if (recipeDiscoveryListener != null) {
            recipeDiscoveryListener.reconcileAllOnline();
        }
    }

    /**
     * {@code /minecraft:reload}(データパック再読込)後の張り直し (2026-07-31 D10)。
     * {@code PotionBrewing#reload} は customMixes を引き継がないため、醸造 mix は必ず再登録が要る。
     */
    private void reapplyAfterResourcesReload() {
        if (brewPotionMixRegistrar != null) {
            brewPotionMixRegistrar.registerAll();
        }
        if (recipeDiscoveryListener != null) {
            recipeDiscoveryListener.reconcileAllOnline();
        }
    }

    /**
     * The catalog workbench recipe registrar. Exposed for the ArsPaper fork's recipe browser, which
     * lists {@code trinityforge:catalog_*} recipes and needs the parsed spec (notably {@code custom:}
     * ingredient tokens that a Bukkit MaterialChoice cannot express). Null until {@link #onEnable}.
     */
    public com.trinityforge.stats.CatalogRecipeRegistrar catalogRecipeRegistrar() {
        return catalogRecipeRegistrar;
    }

    /**
     * The aggro/threat service (gap C5). Owns the bounded, self-evicting hate table that the
     * tank/beastmaster wall behaviour (R2) will read. Null until {@link #onEnable} has run.
     */
    public HateService hateService() {
        return hateService;
    }

    /**
     * 装備使用ゲートの共有評価 ({@code progression/use-requirements.yml})。ArsPaper フォークが
     * 触媒/魔導書の詠唱ゲート(マナ消費前)に使う。Null until {@link #onEnable} has run.
     */
    public UseRequirementService useRequirementGate() {
        return useRequirementService;
    }

    /**
     * TFネイティブ運ステ({@code power_luckbonus_add})→品質mode加算の読み出し口。釣り/拾得に加え、
     * EliteMobs フォークのドロップ品質(幸運1につきmode+1、ITEM_ECONOMY 5.2d)が使う。
     * Null until {@link #onEnable} has run.
     */
    public PlayerLootLuckSource lootLuck() {
        return lootLuckSource;
    }

    /** mobドロップ品質ボーナス(power_mobdropbonus_add)。EliteMobsフォークのドロップ品質が読む。 */
    public com.trinityforge.stats.PlayerMobDropBonusSource mobDropBonus() {
        return mobDropBonusSource;
    }
}
