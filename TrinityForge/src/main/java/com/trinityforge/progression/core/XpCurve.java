package com.trinityforge.progression.core;

/**
 * EXP cost function for a single skill level. {@link #expRequiredAt(int)} returns the amount of
 * EXP a player must earn while at {@code level} to advance to {@code level + 1}, mirroring the
 * semantics of ValhallaMMO's {@code exp_level_curve} formula field.
 *
 * <p>Implementations must guarantee a return value {@code >= 1} so that a level can always be
 * gained with finite EXP and {@link XpTransitionService} never loops infinitely.
 */
@FunctionalInterface
public interface XpCurve {

    /**
     * EXP required to advance from {@code level} to {@code level + 1}.
     *
     * @param level current level, {@code >= 0}
     * @return EXP threshold, always {@code >= 1}
     */
    long expRequiredAt(int level);
}
