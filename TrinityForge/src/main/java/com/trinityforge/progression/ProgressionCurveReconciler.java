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

    public ProgressionCurveReconciler(ProgressionRepository repository, NativeSkillCatalog catalog) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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
                if (powerProg != null) {
                    long earned = PlayerProgression.STARTING_SKILL_POINTS + powerProg.level();
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
