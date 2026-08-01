package com.trinityforge.progression;

import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.core.XpTransitionService;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Authoritative TrinityForge progression use case. Mutations are synchronously committed before
 * returning so a subsequent restart cannot observe an older level.
 */
public final class NativeProgressionService {

    private static final Logger LOG = Logger.getLogger(NativeProgressionService.class.getName());

    private static final String POWER = "POWER";
    private static final double DEFAULT_POWER_EXP_PER_SKILL_LEVEL = 100.0;
    // PRG-09 exploit fix (2026-07-25, ユーザー決定): プレステージ(スキルLv0リセット+SP返金)自体は周回できる
    // 仕様のまま残し、その代わり「同じスキルを何周プレステージしたか」に応じて、そのスキルのレベルアップが
    // 生む POWER EXP を等比減衰させる。0.5 = 1周ごとに半減(tier0=100%, tier1=50%, tier2=25%, ...)。
    // 幾何級数は必ず収束するため「無限に周回してもPOWER総量は発散しない」を満たす — 1スキルあたりの
    // 上限は 10,000 / decayRate (tier0を含む全周回の合計)。既定0.5なら1スキル20,000、15スキル合計
    // 300,000(現行の正規プレイ最大供給150,000のちょうど2倍)に収束する。0.0を設定すると減衰なし
    // (旧来どおりプレステージ周回でPOWERが無限に増える挙動)に戻る後方互換スイッチ。
    private static final double DEFAULT_PRESTIGE_POWER_DECAY_RATE = 0.5;
    private static final long STARTING_SKILL_POINTS = PlayerProgression.STARTING_SKILL_POINTS;

    private final ProgressionRepository repository;
    private final NativeSkillCatalog catalog;
    private final java.util.function.ToDoubleFunction<UUID> allSkillExpMultiplier;
    /**
     * スキル別のEXP倍率（2026-08-02 柱5-3）。{@link #allSkillExpMultiplier} が全スキル一律なのに対し、
     * こちらは<b>付与先スキルごと</b>に引く（{@code woodcutting_exp_bonus} など）。
     *
     * <p><b>なぜ {@code use-skill} で代用しないか</b>: {@code use-skill} は分類マーカーではなく
     * 「装備要件」であり、採取ツールにも付いている。伐採EXP+15% を {@code use-skill: WOODCUTTING} で
     * 表現すると<b>斧で殴っただけで伐採EXPが入る</b>。倍率は必ずこのステで表現すること。
     */
    private final PerSkillExpBonus perSkillExpMultiplier;
    private final PlayerLockRegistry locks;
    private final ExpDiminishingCurve diminishingCurve;
    /**
     * 直近24時間の稼ぎ総量に応じた逓減（2026-07-31）。レベル逓減({@link #diminishingCurve})とは
     * 別の軸で、こちらは<b>プレイヤーごと</b>に効く。既定は {@code null}（無効）なので、
     * 既存の呼び出し側は挙動が変わらない。
     */
    private final DailyExpDiminishing dailyDiminishing;
    /** {@link #dailyDiminishing} が使う設定の供給元。reload で差し替わるので毎回引き直す。 */
    private final java.util.function.Supplier<DailyExpDiminishing.Settings> dailySettings;

    public NativeProgressionService(ProgressionRepository repository, NativeSkillCatalog catalog) {
        this(repository, catalog, id -> 0.0);
    }

    public NativeProgressionService(ProgressionRepository repository, NativeSkillCatalog catalog,
                                    java.util.function.ToDoubleFunction<UUID> allSkillExpMultiplier) {
        this(repository, catalog, allSkillExpMultiplier, new PlayerLockRegistry());
    }

    /**
     * Pass the same {@link PlayerLockRegistry} instance used by {@link NativeProgressionAdminService}
     * so admin edits and gameplay EXP grants serialize against each other for the same player instead
     * of racing on a stale read-modify-write. Uses {@link ExpDiminishingCurve#NONE} (タスク3逓減なし).
     */
    public NativeProgressionService(ProgressionRepository repository, NativeSkillCatalog catalog,
                                    java.util.function.ToDoubleFunction<UUID> allSkillExpMultiplier,
                                    PlayerLockRegistry locks) {
        this(repository, catalog, allSkillExpMultiplier, locks, ExpDiminishingCurve.NONE);
    }

    /**
     * Full constructor, adding the 2026-07-26 EXP調整タスク3 level-diminishing hook. Called once per
     * gameplay grant with the skill's level immediately before this grant is applied; see
     * {@link ExpDiminishingCurve} for the contract.
     */
    public NativeProgressionService(ProgressionRepository repository, NativeSkillCatalog catalog,
                                    java.util.function.ToDoubleFunction<UUID> allSkillExpMultiplier,
                                    PlayerLockRegistry locks, ExpDiminishingCurve diminishingCurve) {
        this(repository, catalog, allSkillExpMultiplier, locks, diminishingCurve, null, null);
    }

    /**
     * 日次逓減つきの構築子（2026-07-31）。{@code dailyDiminishing} / {@code dailySettings} の
     * どちらかが {@code null} なら日次逓減は完全に無効で、上の構築子と同一挙動になる。
     * 設定は Supplier 経由で毎回引く ── {@code /trinityforge reload} で
     * {@code stats/skill-exp.yml} を読み直したとき、焼き込んだ値だと反映されないため。
     */
    public NativeProgressionService(ProgressionRepository repository, NativeSkillCatalog catalog,
                                    java.util.function.ToDoubleFunction<UUID> allSkillExpMultiplier,
                                    PlayerLockRegistry locks, ExpDiminishingCurve diminishingCurve,
                                    DailyExpDiminishing dailyDiminishing,
                                    java.util.function.Supplier<DailyExpDiminishing.Settings> dailySettings) {
        this(repository, catalog, allSkillExpMultiplier, locks, diminishingCurve,
                dailyDiminishing, dailySettings, (playerId, skillId) -> 0.0);
    }

    /**
     * スキル別EXP倍率つきの構築子（2026-08-02 柱5-3）。{@code perSkillExpMultiplier} は
     * <b>正規化済みのスキルID</b>（{@code woodcutting} など）を受け取り、そのスキルにだけ効く
     * 加算倍率を返す。全スキル一律ぶんの {@code allSkillExpMultiplier} とは<b>加算</b>で合成される。
     */
    public NativeProgressionService(ProgressionRepository repository, NativeSkillCatalog catalog,
                                    java.util.function.ToDoubleFunction<UUID> allSkillExpMultiplier,
                                    PlayerLockRegistry locks, ExpDiminishingCurve diminishingCurve,
                                    DailyExpDiminishing dailyDiminishing,
                                    java.util.function.Supplier<DailyExpDiminishing.Settings> dailySettings,
                                    PerSkillExpBonus perSkillExpMultiplier) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.allSkillExpMultiplier = Objects.requireNonNull(allSkillExpMultiplier, "allSkillExpMultiplier");
        this.perSkillExpMultiplier = Objects.requireNonNull(perSkillExpMultiplier, "perSkillExpMultiplier");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.diminishingCurve = Objects.requireNonNull(diminishingCurve, "diminishingCurve");
        this.dailyDiminishing = dailyDiminishing;
        this.dailySettings = dailySettings;
    }

    /** 日次逓減の状態保持器（退出時に {@code forget} を呼ぶリスナ用）。無効なら {@code null}。 */
    public DailyExpDiminishing dailyDiminishing() {
        return dailyDiminishing;
    }

    /** Exposes the shared per-player lock so sibling services can serialize against it. */
    public PlayerLockRegistry playerLocks() {
        return locks;
    }

    public PlayerProgression snapshot(UUID playerId) {
        LoadResult<PlayerProgression> result = repository.load(playerId);
        if (result.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load snapshot for " + playerId, result.error());
            return PlayerProgression.empty(playerId);
        }
        return result.orElseGet(() -> PlayerProgression.empty(playerId)
                .withPoints(STARTING_SKILL_POINTS, 0L));
    }

    public GrantResult grantExp(UUID playerId, String rawSkillId, double amount) {
        return locks.withLock(playerId,
                () -> grantExpUnderRepositoryLock(playerId, rawSkillId, amount));
    }

    private GrantResult grantExpUnderRepositoryLock(
            UUID playerId, String rawSkillId, double amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (!Double.isFinite(amount) || amount == 0.0) {
            return GrantResult.unchanged(rawSkillId);
        }
        String skillId = normalizeSkillId(rawSkillId);
        // 全スキル一律ぶんとスキル別ぶんは【加算】で合成してから1回だけ掛ける。
        // 別々に掛けると (1+a)(1+b) となり、表記どおりの合計にならない。
        double multAdd = allSkillExpMultiplier.applyAsDouble(playerId);
        if (!Double.isFinite(multAdd)) {
            multAdd = 0.0;
        }
        double perSkill = perSkillExpMultiplier.bonusFor(playerId, skillId);
        if (Double.isFinite(perSkill)) {
            multAdd += perSkill;
        }
        if (multAdd != 0.0) {
            amount = amount * (1.0 + Math.max(-0.9, multAdd));
        }
        if (!Double.isFinite(amount) || amount == 0.0) {
            return GrantResult.unchanged(rawSkillId);
        }
        SkillCatalogEntry entry = requireSkill(skillId);
        LoadResult<PlayerProgression> persisted = repository.load(playerId);
        if (persisted.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load player for grantExp " + playerId,
                    persisted.error());
            return GrantResult.unchanged(rawSkillId);
        }
        PlayerProgression player = persisted.orElseGet(() ->
                PlayerProgression.empty(playerId).withPoints(STARTING_SKILL_POINTS, 0L));
        SkillProgress before = player.skillOrDefault(skillId, entry.maxLevel());
        // タスク3(2026-07-26): レベル逓減カーブ。既定実装(ExpDiminishingCurve.NONE)は常に1.0を返すため
        // ここは無条件で通しても現行挙動に影響しない。「このEXP付与が適用される前」のレベルを渡す。
        double diminishing = diminishingCurve.multiplierFor(skillId, before.level());
        if (Double.isFinite(diminishing) && diminishing != 1.0) {
            amount = amount * Math.max(0.0, diminishing);
            if (!Double.isFinite(amount) || amount == 0.0) {
                return GrantResult.unchanged(rawSkillId);
            }
        }
        // 日次逓減(2026-07-31): 直近24時間にそのスキルで稼いだ総量で薄める。レベル逓減とは軸が違うので
        // 乗算で合成する。consume は「読み取りと蓄積の加算」が一体なので1回の付与につき1回だけ呼ぶ。
        // 逓減前の amount(レベル逓減適用後)を蓄積へ入れる ── 逓減後の値を入れると、薄まるほど
        // 蓄積が増えなくなって自分で自分を打ち消す(いくら稼いでも threshold に届かない)。
        if (dailyDiminishing != null && dailySettings != null) {
            double daily = dailyDiminishing.consume(dailySettings.get(), playerId, skillId, amount);
            if (Double.isFinite(daily) && daily != 1.0) {
                amount = amount * Math.max(0.0, daily);
                if (!Double.isFinite(amount) || amount == 0.0) {
                    return GrantResult.unchanged(rawSkillId);
                }
            }
        }
        SkillProgress after = new XpTransitionService(entry.curve()).apply(before, amount);

        int levelsChanged = after.level() - before.level();
        SkillProgress powerAfter = null;
        int powerLevelsChanged = 0;
        int resultingPowerLevel;
        if (!POWER.equals(skillId)) {
            SkillCatalogEntry powerEntry = requireSkill(POWER);
            SkillProgress powerBefore = player.skillOrDefault(POWER, powerEntry.maxLevel());
            double powerPerLevel = powerEntry.rate("power.exp_per_skill_level",
                    DEFAULT_POWER_EXP_PER_SKILL_LEVEL);
            double decayRate = powerEntry.rate("power.prestige_decay_rate",
                    DEFAULT_PRESTIGE_POWER_DECAY_RATE);
            // before.prestige() = このスキル自身の現在のプレステージ回数(0 = 未プレステージ、通常の初回
            // 到達は常に等倍)。NativePerkService#prestigeUnderLock が SkillProgress.prestige をインクリメント
            // して永続化するので、追加のDBスキーマ変更は不要。
            double decayMultiplier = prestigePowerDecayMultiplier(before.prestige(), decayRate);
            powerAfter = new XpTransitionService(powerEntry.curve()).apply(
                    powerBefore, powerPerLevel * levelsChanged * decayMultiplier);
            powerLevelsChanged = powerAfter.level() - powerBefore.level();
            resultingPowerLevel = powerAfter.level();
        } else {
            resultingPowerLevel = after.level();
        }
        long availablePoints = Math.max(0L,
                STARTING_SKILL_POINTS + resultingPowerLevel - player.spentPoints());
        repository.saveProgressionTransition(playerId, skillId, after, powerAfter,
                availablePoints, player.spentPoints());
        return new GrantResult(skillId, before, after, levelsChanged, powerLevelsChanged);
    }

    public boolean unlockPerk(UUID playerId, String perkId, int pointCost) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(perkId, "perkId");
        return locks.withLock(playerId, () -> {
            LoadResult<PlayerProgression> current = repository.load(playerId);
            if (current.isFailed()) {
                LOG.log(Level.WARNING, "[progression] Failed to load player for unlockPerk " + playerId,
                        current.error());
                return false;
            }
            if (current.isMissing()) {
                repository.savePointBalance(playerId, STARTING_SKILL_POINTS, 0L);
            }
            return repository.unlockPerk(playerId, perkId, Math.max(0, pointCost));
        });
    }

    public Optional<SkillProgress> progress(UUID playerId, String rawSkillId) {
        String skillId = normalizeSkillId(rawSkillId);
        LoadResult<PlayerProgression> result = repository.load(playerId);
        if (result.isFailed()) {
            return Optional.empty();
        }
        if (result.isMissing()) {
            return Optional.empty();
        }
        return Optional.ofNullable(result.orElseThrow().skills().get(skillId));
    }

    public ProgressionRepository repository() {
        return repository;
    }

    private SkillCatalogEntry requireSkill(String skillId) {
        SkillCatalogEntry entry = catalog.get(skillId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown skill: " + skillId);
        }
        return entry;
    }

    private static String normalizeSkillId(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId is blank");
        }
        return skillId.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * PRG-09: the POWER-EXP multiplier for a level-up happening on a skill currently at
     * {@code prestigeTier} (its own {@link SkillProgress#prestige()}). Geometric decay —
     * {@code (1 - decayRate)^prestigeTier} — so tier 0 (never prestiged, i.e. every player's normal
     * first climb) is always exactly {@code 1.0} regardless of {@code decayRate}, and each further
     * prestige cycle of the SAME skill is worth strictly less. {@code decayRate <= 0} (including the
     * config's back-compat "0% = OFF" switch) or a completed-cycle count of 0 both yield {@code 1.0} —
     * i.e. no decay applied, matching the pre-fix behavior exactly. {@code decayRate} is clamped to
     * {@code [0, 1]} so a misconfigured value (e.g. {@code > 1}, which would go negative/oscillate
     * under {@code Math.pow}) can never produce a negative or NaN multiplier.
     */
    static double prestigePowerDecayMultiplier(int prestigeTier, double decayRate) {
        if (prestigeTier <= 0 || !Double.isFinite(decayRate) || decayRate <= 0.0) {
            return 1.0;
        }
        double clampedRetain = 1.0 - Math.min(1.0, decayRate);
        return Math.pow(clampedRetain, prestigeTier);
    }

    public record GrantResult(String skillId, SkillProgress before, SkillProgress after,
                              int levelsChanged, int powerLevelsChanged) {
        static GrantResult unchanged(String skillId) {
            return new GrantResult(skillId, null, null, 0, 0);
        }
    }
}
