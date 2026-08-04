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

    public ProgressionCurveReconciler(ProgressionRepository repository, NativeSkillCatalog catalog) {
        this(repository, catalog, () -> 1);
    }

    /**
     * スキルポイント付与間隔つきの構築子（2026-08-04）。{@link NativeProgressionService} と
     * <b>同じ供給元</b>を渡すこと。ここだけ旧式のままだと、ログインごとに
     * 「付与された点が元に戻される」挙動になる。
     */
    public ProgressionCurveReconciler(ProgressionRepository repository, NativeSkillCatalog catalog,
                                      java.util.function.IntSupplier levelsPerSkillPoint) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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

    public int recalculatePlayer(UUID playerId) {
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
