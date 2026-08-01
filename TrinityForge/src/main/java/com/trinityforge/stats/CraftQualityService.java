package com.trinityforge.stats;

import com.trinityforge.integration.ars.ArsProgressionBridge;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.progression.SkillLevelSource;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Skill-driven craft-quality roll (ITEM_ECONOMY_SPEC 5.2c/5.2d), extracted so it can be reused BOTH by
 * {@code CraftQualityListener} (the vanilla/ValhallaMMO/ArsPaper {@code CraftItemEvent} path) and by
 * external callers that craft an item OUTSIDE a {@code CraftItemEvent} — notably the ArsPaper fork's
 * ritual crafting, which hands over a finished item via {@code dropItemNaturally} and so never fires
 * {@code CraftItemEvent}. Exposed through {@code TrinityForge#craftQualityService()}.
 *
 * <p>The roll: the crafter's highest relevant production-skill level sets a quality {@code mode}
 * ({@link CraftQualityPolicy#modeFromLevel}); the {@code craftQualityBonus} perk-reward stat shifts it
 * ({@link CraftQualityPolicy#resolveQuality}); the final quality is a split-normal (bell) draw around the
 * mode with the up/down σ from {@code quality.yml spread-up}/{@code spread-down}
 * ({@link CraftQualityPolicy#resolveQualityNormal}) — the same model as mob drops, except a craft widens
 * the UP side by the crafter's {@code craftUpswingBonus} perk (上振れパーク). Bounded to
 * {@code [0, quality.maxQuality()]}.
 *
 * <p><b>2026-08-01 作業台/儀式のばらつき分離</b>: {@code craft_upswing_bonus}(品質の上振れ増加) と
 * {@code craft_downswing_reduction}(品質の下振れ抑制)は経路共通のステのままだが、
 * 「どちらの経路にどれだけ効かせるか」は {@code stats/craft-quality.yml} の
 * {@code workbench.*} / {@code ritual.*}({@link CraftQualityConfig.SpreadTuning})で別々に設定できる。
 * 経路の選択は {@link CraftPath} で明示する。出荷既定値は恒等(分離前と同じ挙動)。
 *
 * <p>Purely the quality roll — it
 * grants NO EXP (the {@code CraftItemEvent} listener keeps its own ARS_SMITHING EXP grant), so a caller
 * can roll quality without side effects. Bukkit reads → main-thread only.
 */
public final class CraftQualityService {

    /**
     * 品質を焼き付ける「経路」。{@code stats/craft-quality.yml} のどの節
     * ({@code workbench.*} / {@code ritual.*})でばらつき補正を引くかを決める
     * (2026-08-01 分離)。品質mode加算ステ({@code workbench_quality_bonus} /
     * {@code ritual_quality_bonus}、S7分割)の選択もこの経路に従う。
     */
    public enum CraftPath {
        /** 作業台(CraftItemEvent)経由のクラフト。 */
        WORKBENCH,
        /** ArsPaper フォークの儀式(リチュアル)クラフト。CraftItemEvent は発火しない。 */
        RITUAL
    }

    private final SkillLevelSource skillLevelSource;
    private final CraftQualityConfig config;
    private final QualityConfig quality;
    // 装備+perk合算(2026-07-23 stat-gate-overhaul §2 移行B4)。null可(未配線時は全ボーナス0)。
    private final PlayerStatAggregator aggregator;
    // 品質基準値 (item-stats quality-mode-offset) の参照元。null可 (テスト/未配線時はオフセット0)。
    private final ItemStatsConfig itemStats;

    public CraftQualityService(SkillLevelSource skillLevelSource, CraftQualityConfig config,
                               QualityConfig quality) {
        this(skillLevelSource, config, quality, null, null);
    }

    public CraftQualityService(SkillLevelSource skillLevelSource, CraftQualityConfig config,
                               QualityConfig quality, PlayerStatAggregator aggregator) {
        this(skillLevelSource, config, quality, aggregator, null);
    }

    public CraftQualityService(SkillLevelSource skillLevelSource, CraftQualityConfig config,
                               QualityConfig quality, PlayerStatAggregator aggregator,
                               ItemStatsConfig itemStats) {
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.config = Objects.requireNonNull(config, "config");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.aggregator = aggregator;
        this.itemStats = itemStats;
    }

    /**
     * Rolls a craft quality for {@code crafter} using the highest of the given {@code candidateSkills}
     * (the caller resolves which skills apply — e.g. the item's categories → skills, or a fixed skill).
     * An empty candidate set (or all-unknown skills) means level 0, so the roll sits at the base mode.
     */
    public int rollQuality(Player crafter, Set<String> candidateSkills) {
        return rollQuality(crafter, candidateSkills, 0);
    }

    /**
     * {@link #rollQuality(Player, Set)} に品質基準値 (item-stats {@code quality-mode-offset}) を
     * 加えた版。ロール分布の mode を {@code modeOffset} だけずらす (+1=mode+1 / -1=mode-1 /
     * 0=クラフトユーザの品質ポイント通り)。
     */
    public int rollQuality(Player crafter, Set<String> candidateSkills, int modeOffset) {
        return rollQualityOn(CraftPath.WORKBENCH, crafter, candidateSkills, modeOffset);
    }

    /**
     * 経路を明示するロール本体(2026-08-01 分離)。品質mode加算ステ(作業台=
     * {@code workbench_quality_bonus} / 儀式={@code ritual_quality_bonus}、S7分割)と、
     * ばらつき補正({@code craft-quality.yml} の {@code workbench.*} / {@code ritual.*})の
     * 両方を経路から引く。
     */
    private int rollQualityOn(CraftPath path, Player crafter, Set<String> candidateSkills,
                              int modeOffset) {
        Objects.requireNonNull(crafter, "crafter");
        int mode = qualityModeWithBonus(crafter, candidateSkills, modeOffset, qualityBonusFor(path, crafter));
        CraftQualityConfig.SpreadTuning tuning = spreadTuningFor(path);
        double spreadUp = tuning.effectiveSpreadUp(quality.spreadUp(), craftUpswingBonus(crafter));
        double spreadDown = tuning.effectiveSpreadDown(quality.spreadDown(), craftDownswingBonus(crafter));
        return CraftQualityPolicy.resolveQualityNormal(
                mode, ThreadLocalRandom.current().nextGaussian(), spreadUp, spreadDown, quality.maxQuality());
    }

    /**
     * Deterministic craft-quality mode (skill level + perk bonus, no Gaussian). Used for workbench
     * preview so PrepareItemCraft does not flicker a new random quality every matrix update.
     */
    public int qualityMode(Player crafter, Set<String> candidateSkills) {
        return qualityMode(crafter, candidateSkills, 0);
    }

    /** {@link #qualityMode(Player, Set)} + 品質基準値オフセット (結果は [0, maxQuality] へクランプ)。 */
    public int qualityMode(Player crafter, Set<String> candidateSkills, int modeOffset) {
        return qualityModeWithBonus(crafter, candidateSkills, modeOffset, workbenchQualityBonus(crafter));
    }

    /**
     * The GUARANTEED-MINIMUM quality for a workbench preview (task: "プレビューは常に「最低でもこれは出る」を
     * 示すべき"): same mode/spreadDown計算(craftDownswingBonus 込み)as {@link #rollQualityWithBonus} for the
     * workbench path, run through {@link CraftQualityPolicy#minimumQuality}. Unlike {@link #qualityMode},
     * this reflects the TRUE floor the actual (Gaussian) craft roll can reach — never a mode/peak value
     * that the roll could still undercut.
     */
    public int minimumQuality(Player crafter, Set<String> candidateSkills) {
        return minimumQuality(crafter, candidateSkills, 0);
    }

    /** {@link #minimumQuality(Player, Set)} + 品質基準値オフセット。 */
    public int minimumQuality(Player crafter, Set<String> candidateSkills, int modeOffset) {
        Objects.requireNonNull(crafter, "crafter");
        int mode = qualityModeWithBonus(crafter, candidateSkills, modeOffset, workbenchQualityBonus(crafter));
        // プレビューは作業台専用なので、ばらつき補正も workbench.* を引く(儀式のプレビューは存在しない)。
        double spreadDown = spreadTuningFor(CraftPath.WORKBENCH)
                .effectiveSpreadDown(quality.spreadDown(), craftDownswingBonus(crafter));
        return CraftQualityPolicy.minimumQuality(mode, spreadDown, quality.maxQuality());
    }

    /** {@link #qualityMode(Player, Set, int)} の品質mode加算ボーナス明示版(S7 作業台/儀式分割)。 */
    private int qualityModeWithBonus(Player crafter, Set<String> candidateSkills, int modeOffset,
                                     int qualityBonus) {
        Objects.requireNonNull(crafter, "crafter");
        Map<String, Integer> levels = skillLevelSource.levelsOf(crafter.getUniqueId());
        int bestLevel = 0;
        for (String skill : candidateSkills) {
            bestLevel = Math.max(bestLevel, levels.getOrDefault(skill, 0));
        }
        int mode = CraftQualityPolicy.modeFromLevel(bestLevel, config.skillLevelsPerQuality(), config.baseQuality());
        return CraftQualityPolicy.resolveQuality(mode, qualityBonus + modeOffset, quality.maxQuality());
    }

    /**
     * Convenience for the ArsPaper fork: rolls a craft quality driven by the {@code ARS_SMITHING} skill
     * (the production skill for Ars gear). Used by the ritual-craft path, which fires no
     * {@code CraftItemEvent} and so is not covered by {@code CraftQualityListener}.
     */
    public int rollArsSmithingQuality(Player crafter) {
        return rollQualityOn(CraftPath.RITUAL, crafter, Set.of(ArsProgressionBridge.ARS_SMITHING), 0);
    }

    /**
     * {@link #rollArsSmithingQuality(Player)} に成果物アイテムの品質基準値
     * (item-stats {@code quality-mode-offset}、material#CMD優先) を適用した版。
     * ArsPaper forkの儀式クラフト経路が成果物 ItemStack ごと渡すために使う。
     * itemStats未配線 / result null なら従来通りオフセット0。
     */
    public int rollArsSmithingQuality(Player crafter, ItemStack result) {
        return rollQualityOn(CraftPath.RITUAL, crafter, Set.of(ArsProgressionBridge.ARS_SMITHING),
                qualityModeOffsetFor(result));
    }

    /** 成果物の品質基準値を引く (itemStats未配線・meta無しは0)。 */
    private int qualityModeOffsetFor(ItemStack result) {
        if (itemStats == null || result == null) {
            return 0;
        }
        Integer cmd = result.hasItemMeta() ? DerivedItemStats.customModelDataOf(result.getItemMeta()) : null;
        return itemStats.qualityModeOffsetFor(result.getType(), cmd);
    }

    /**
     * 作業台クラフト品質の mode 加算(S7 分割)。{@code workbench_quality_bonus}(作業台品質)を加算する。
     */
    private int workbenchQualityBonus(Player player) {
        return readIntStat(player, "workbench_quality_bonus");
    }

    /** 儀式クラフト品質の mode 加算(S7 分割)。儀式は新 {@code ritual_quality_bonus} のみ。 */
    private int ritualQualityBonus(Player player) {
        return readIntStat(player, "ritual_quality_bonus");
    }

    /** 経路ごとの品質mode加算ステ(S7 分割)。 */
    private int qualityBonusFor(CraftPath path, Player player) {
        return path == CraftPath.RITUAL ? ritualQualityBonus(player) : workbenchQualityBonus(player);
    }

    /**
     * 経路ごとのばらつき補正(2026-08-01 分離)。{@code craft-quality.yml} の
     * {@code workbench.*} / {@code ritual.*} を引く。config 未配線のテスト等では
     * {@link CraftQualityConfig.SpreadTuning#IDENTITY}(=分離前と同じ挙動)へ落ちる。
     */
    private CraftQualityConfig.SpreadTuning spreadTuningFor(CraftPath path) {
        CraftQualityConfig.SpreadTuning tuning =
                path == CraftPath.RITUAL ? config.ritualSpread() : config.workbenchSpread();
        return tuning == null ? CraftQualityConfig.SpreadTuning.IDENTITY : tuning;
    }

    // キー名は CraftQualityConfig の定数を参照する — lore から落とす判定
    // (CraftQualityConfig#inertSpreadStatKeys)と読み出し側でキーがズレると、
    // 「効いているのに消える」/「効かないのに出る」が無言で起きるため。
    private double craftUpswingBonus(Player player) {
        return readDoubleStat(player, CraftQualityConfig.UPSWING_STAT_KEY);
    }

    private double craftDownswingBonus(Player player) {
        return readDoubleStat(player, CraftQualityConfig.DOWNSWING_STAT_KEY);
    }

    public CraftRollMods craftRollMods(Player player) {
        return new CraftRollMods(
                pctFraction(readDoubleStat(player, "craft_roll_up_bonus")),
                pctFraction(readDoubleStat(player, "craft_roll_down_reduction")),
                pctFraction(readDoubleStat(player, "craft_roll_inset")));
    }

    private int readIntStat(Player player, String key) {
        return (int) Math.round(readDoubleStat(player, key));
    }

    private double readDoubleStat(Player player, String key) {
        if (aggregator == null || player == null) return 0.0;
        return aggregator.aggregate(player).totalOf(StatKeys.canonical(key));
    }

    // 段2パーク値は percent 記法(整数%)。σ/inset は reach-fraction[0,1] 単位なので /100 して足し込む。
    // 非有限値(NaN/Inf)は0に落とし、PDC焼込み後に derive で例外を投げる毒入りアイテムを防ぐ。
    private static double pctFraction(double raw) {
        return (Double.isFinite(raw) && raw > 0.0) ? raw / 100.0 : 0.0;
    }
}
