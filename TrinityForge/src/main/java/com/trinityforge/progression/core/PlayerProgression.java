package com.trinityforge.progression.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a player's full progression state: per-skill levels/EXP and
 * point balances. Perk ownership is modelled separately as {@link PerkOwnership} to keep
 * read-side queries lightweight.
 *
 * <p>All mutating operations (e.g. {@link #withSkill}, {@link #withPoints}) return new
 * instances; the original is never modified. This makes the record safe to hand off to
 * async threads without defensive copies.
 */
public final class PlayerProgression {
    /** Points every player starts with, before any POWER-level earnings. Single source of truth. */
    public static final long STARTING_SKILL_POINTS = 3L;

    private final UUID playerId;
    private final Map<String, SkillProgress> skills;
    private final long availablePoints;
    private final long spentPoints;

    private PlayerProgression(UUID playerId, Map<String, SkillProgress> skills,
                              long availablePoints, long spentPoints) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.skills = Map.copyOf(skills);
        this.availablePoints = availablePoints;
        this.spentPoints = spentPoints;
    }

    /** A progression snapshot with no skills, no EXP, and zero point balances. */
    public static PlayerProgression empty(UUID playerId) {
        return new PlayerProgression(playerId, Map.of(), 0L, 0L);
    }

    public UUID playerId() { return playerId; }

    /** Immutable view of all per-skill snapshots, keyed by uppercase skill ID. */
    public Map<String, SkillProgress> skills() { return skills; }

    /** Points available to spend on perk unlocks. */
    public long availablePoints() { return availablePoints; }

    /** Cumulative points already spent across all perk unlocks. */
    public long spentPoints() { return spentPoints; }

    /**
     * Returns the {@link SkillProgress} for a skill, or a fresh
     * {@link SkillProgress#start(int)} if the skill has not been saved yet.
     */
    public SkillProgress skillOrDefault(String skillId, int maxAllowedLevel) {
        return skills.getOrDefault(skillId, SkillProgress.start(maxAllowedLevel));
    }

    /** Returns a new snapshot with the given skill replaced/added. */
    public PlayerProgression withSkill(String skillId, SkillProgress progress) {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(progress, "progress");
        Map<String, SkillProgress> next = new HashMap<>(skills);
        next.put(skillId, progress);
        return new PlayerProgression(playerId, next, availablePoints, spentPoints);
    }

    /** Returns a new snapshot with updated point balances. Both values must be {@code >= 0}. */
    public PlayerProgression withPoints(long availablePoints, long spentPoints) {
        if (availablePoints < 0) {
            throw new IllegalArgumentException("availablePoints < 0: " + availablePoints);
        }
        if (spentPoints < 0) {
            throw new IllegalArgumentException("spentPoints < 0: " + spentPoints);
        }
        return new PlayerProgression(playerId, skills, availablePoints, spentPoints);
    }

    @Override
    public String toString() {
        return "PlayerProgression{player=" + playerId
                + ", skills=" + skills.keySet()
                + ", available=" + availablePoints
                + ", spent=" + spentPoints + '}';
    }
}
