package com.trinityforge.progression;

import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.core.XpTransitionService;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;

import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Re-derives skill levels from stored total EXP after curve/cap catalog changes.
 * Preserves total EXP and prestige; updates maxAllowedLevel to the new catalog cap.
 */
public final class ProgressionCurveReconciler {

    private static final Logger LOG = Logger.getLogger(ProgressionCurveReconciler.class.getName());

    private final ProgressionRepository repository;
    private final NativeSkillCatalog catalog;
    /**
     * 何POWERレベルごとにスキルポイント1点を与えるか
     * （{@code stats/skill-exp.yml: power.levels-per-skill-point}）。既定は {@code () -> 1}。
     */
    private final java.util.function.IntSupplier levelsPerSkillPoint;
    /**
     * W-314 タスク2: プレイヤー単位の読み→書きを直列化するロック。{@link #recalculatePlayer}
     * は「読む(load)→計算する→書く(saveSkillProgress/savePointBalance)」という
     * read-modify-write を<b>ロックを取らずに絶対値で上書き</b>していたため、reload と同時刻に
     * 経験値付与({@code NativeExperienceDispatcher} → {@code NativeProgressionService#grantExp})
     * が同じプレイヤーへ走ると、どちらか片方の書き込みが後勝ちで消える(ロストアップデート)。
     *
     * <p>{@link NativeProgressionService#playerLocks()} と<b>同じインスタンス</b>を渡すことで、
     * 「reload中の再計算」と「プレイ中のEXP付与/perk解放/prestige」がプレイヤー単位で
     * 互いに排他される。{@code NativePerkService#prestigeUnderLock} /
     * {@code SkillTreePerkPruner#resetUnderLock} と同じ作法(共有registryを外から注入)。
     */
    private final PlayerLockRegistry locks;

    public ProgressionCurveReconciler(ProgressionRepository repository, NativeSkillCatalog catalog) {
        this(repository, catalog, new PlayerLockRegistry(), () -> 1);
    }

    /**
     * スキルポイント付与間隔つきの構築子（2026-08-04）。{@link NativeProgressionService} と
     * <b>同じ供給元</b>を渡すこと。ここだけ旧式のままだと、ログインごとに
     * 「付与された点が元に戻される」挙動になる。
     *
     * <p>⚠️ このコンストラクタは他サービスと共有しない専用の {@link PlayerLockRegistry} を
     * 内部で新規に作る。同時実行中の {@code NativeProgressionService}/{@code NativePerkService}
     * とロックを共有したい場合(＝サーバ本体での通常運用)は
     * {@link #ProgressionCurveReconciler(ProgressionRepository, NativeSkillCatalog,
     * PlayerLockRegistry, java.util.function.IntSupplier)} で共有インスタンスを明示的に渡すこと。
     */
    public ProgressionCurveReconciler(ProgressionRepository repository, NativeSkillCatalog catalog,
                                      java.util.function.IntSupplier levelsPerSkillPoint) {
        this(repository, catalog, new PlayerLockRegistry(), levelsPerSkillPoint);
    }

    /**
     * 共有 {@link PlayerLockRegistry} を明示的に渡す完全構築子(W-314)。
     * 必ず {@link NativeProgressionService#playerLocks()} と<b>同じインスタンス</b>を渡すこと —
     * 別インスタンスだとロックが効かず、このクラスを作った意味が無くなる。
     */
    public ProgressionCurveReconciler(ProgressionRepository repository, NativeSkillCatalog catalog,
                                      PlayerLockRegistry locks,
                                      java.util.function.IntSupplier levelsPerSkillPoint) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.levelsPerSkillPoint = Objects.requireNonNull(levelsPerSkillPoint, "levelsPerSkillPoint");
    }

    /** @return number of skill rows rewritten */
    public int recalculateAll() {
        int updated = 0;
        for (UUID playerId : repository.listPlayerIds()) {
            try {
                updated += recalculatePlayer(playerId);
            } catch (RuntimeException ex) {
                // A single player's storage failure must not abort reconciliation for everyone.
                LOG.log(Level.WARNING, "[progression] curve recalc failed for " + playerId, ex);
            }
        }
        return updated;
    }

    /**
     * 1プレイヤー分の読み→書きを {@link #locks} で直列化する(W-314 タスク2)。
     * ロックの粒度は「このプレイヤー1人ぶんの再計算」— {@link #recalculateAll} は
     * このメソッドをプレイヤーごとに呼ぶだけなので、reload 全体を1本の巨大ロックで
     * 直列化することはない(他プレイヤーの通常プレイを止めない)。
     */
    public int recalculatePlayer(UUID playerId) {
        return locks.withLock(playerId, () -> recalculatePlayerUnderLock(playerId));
    }

    private int recalculatePlayerUnderLock(UUID playerId) {
        LoadResult<PlayerProgression> load = repository.load(playerId);
        if (!load.isFound()) {
            if (load.isFailed()) {
                LOG.log(Level.WARNING, "[progression] curve recalc skipped for " + playerId, load.error());
            }
            return 0;
        }
        PlayerProgression player = load.orElseThrow();
        int updated = 0;
        for (var entry : player.skills().entrySet()) {
            String skillId = entry.getKey();
            SkillCatalogEntry cat = catalog.get(skillId);
            if (cat == null) continue;
            SkillProgress old = entry.getValue();
            SkillProgress seed = new SkillProgress(
                    0, 0.0, 0.0, old.prestige(), cat.maxLevel());
            SkillProgress next = new XpTransitionService(cat.curve()).apply(seed, old.totalExp());
            if (next.level() != old.level()
                    || next.residualExp() != old.residualExp()
                    || next.maxAllowedLevel() != old.maxAllowedLevel()
                    || next.totalExp() != old.totalExp()) {
                repository.saveSkillProgress(playerId, skillId, next);
                updated++;
            }
        }
        // Keep POWER point balance coherent with POWER level after cap/curve shifts.
        SkillCatalogEntry power = catalog.get("POWER");
        if (power != null) {
            LoadResult<PlayerProgression> again = repository.load(playerId);
            if (again.isFound()) {
                PlayerProgression p = again.orElseThrow();
                SkillProgress powerProg = p.skills().get("POWER");
                // 既存被害者の復旧経路(2026-08-04): 修正前の POWER プレステージは level/EXP を
                // 0 にリセットしていた(prestige だけが増える)。ここで「プレステージ済み
                // (prestige > 0) な POWER」を毎回、他スキルの現在レベルから同じ加算式で再導出し、
                // 現在値より高ければ引き上げる。下げることは絶対にしない ―
                // 導出値以上(修正後に正しく積み上がった通常状態、または管理者が意図的に編集した
                // 状態)を巻き戻さないための一方向ガード。
                if (powerProg != null && powerProg.prestige() > 0) {
                    SkillProgress derived = NativeProgressionService.derivePowerProgress(
                            catalog, p, powerProg.prestige(), powerProg.maxAllowedLevel());
                    if (derived.totalExp() > powerProg.totalExp()) {
                        repository.saveSkillProgress(playerId, "POWER", derived);
                        updated++;
                        LOG.log(Level.INFO, "[progression] healed stuck POWER prestige for "
                                + playerId + ": level " + powerProg.level() + " -> " + derived.level());
                        p = p.withSkill("POWER", derived);
                        powerProg = derived;
                    }
                }
                if (powerProg != null) {
                    long earned = PlayerProgression.earnedPoints(
                            powerProg.level(), levelsPerSkillPoint.getAsInt());
                    long available = Math.max(0L, earned - p.spentPoints());
                    if (p.spentPoints() > earned) {
                        // Ledger is incoherent (spent > earned) — a cap/curve change lowered POWER
                        // below what the player already spent. Surface it instead of silently
                        // clamping to 0 (which would swallow future POWER points without notice).
                        LOG.log(Level.WARNING, "[progression] point ledger deficit after curve recalc for "
                                + playerId + ": spent=" + p.spentPoints() + " > earned=" + earned
                                + " (POWER level " + powerProg.level() + "); available clamped to 0."
                                + " Owned perks retained; run an admin level edit to reconcile if intended.");
                    }
                    if (available != p.availablePoints()) {
                        repository.savePointBalance(playerId, available, p.spentPoints());
                    }
                }
            }
        }
        return updated;
    }
}
