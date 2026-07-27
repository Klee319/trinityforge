package com.trinityforge.progression.core;

import java.util.Objects;

/**
 * Pure service that applies a signed EXP delta to a {@link SkillProgress} snapshot and
 * returns the updated snapshot. All computation is performed on {@code totalExp} as the
 * single source of truth; level and residualExp are derived by walking the {@link XpCurve}.
 *
 * <p>Invariants enforced on every transition:
 * <ul>
 *   <li><b>0-floor:</b> {@code totalExp} never falls below 0 (absolute minimum; levels cannot go
 *       negative)</li>
 *   <li><b>max cap:</b> {@code level} never exceeds {@link SkillProgress#maxAllowedLevel()};
 *       {@code totalExp} may grow beyond the cap threshold (the residual accumulates above the
 *       cap level) so that negative EXP can later drop the player back below the cap</li>
 *   <li><b>multi-level crossing:</b> both gains and losses that span multiple level boundaries
 *       are handled correctly in a single call</li>
 * </ul>
 *
 * <p>This class is Bukkit-independent and side-effect free, making it fully unit-testable
 * without a server or database.
 */
public final class XpTransitionService {

    private final XpCurve curve;

    public XpTransitionService(XpCurve curve) {
        this.curve = Objects.requireNonNull(curve, "curve");
    }

    /**
     * Applies {@code deltaExp} (positive or negative) to {@code current} and returns the
     * updated {@link SkillProgress}. Returns the same reference when {@code deltaExp == 0}.
     *
     * @param current  the current skill snapshot
     * @param deltaExp EXP to add (positive) or subtract (negative)
     * @return updated snapshot; same reference if nothing changed
     */
    public SkillProgress apply(SkillProgress current, double deltaExp) {
        Objects.requireNonNull(current, "current");
        if (!Double.isFinite(deltaExp)) {
            throw new IllegalArgumentException("deltaExp must be finite");
        }
        if (deltaExp == 0.0) return current;

        double newTotal = current.totalExp() + deltaExp;
        if (newTotal < 0) {
            newTotal = 0.0;
        }

        int maxLevel = current.maxAllowedLevel();
        int level = 0;
        double remaining = newTotal;

        while (level < maxLevel) {
            double cost = curve.expRequiredAt(level);
            if (remaining < cost) break;
            remaining -= cost;
            level++;
        }

        // At level == maxLevel, remaining is the residual above the cap threshold.
        double residual = remaining;

        if (level == current.level()
                && residual == current.residualExp()
                && newTotal == current.totalExp()) {
            return current;
        }
        return new SkillProgress(level, residual, newTotal, current.prestige(), maxLevel);
    }

    /**
     * Cumulative EXP required to reach the given {@code level} from level 0, i.e.
     * {@code sum(expRequiredAt(0), …, expRequiredAt(level - 1))}.
     * {@code cumulativeExpForLevel(0) == 0} by definition.
     *
     * @param level target level, {@code >= 0}
     */
    public double cumulativeExpForLevel(int level) {
        if (level <= 0) return 0.0;
        double total = 0.0;
        for (int i = 0; i < level; i++) {
            total += curve.expRequiredAt(i);
        }
        return total;
    }
}
