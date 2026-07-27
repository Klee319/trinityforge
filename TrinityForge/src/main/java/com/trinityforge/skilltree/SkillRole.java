package com.trinityforge.skilltree;

import java.util.Locale;
import java.util.Optional;

/**
 * The layout/semantics role of a skill-tree node (SKILL_TREE design section A).
 *
 * <ul>
 *   <li>{@link #MAIN} — the A..E pillar nodes (required-level 10/30/50/70/90).</li>
 *   <li>{@link #INTERMEDIATE} — nodes between two pillars (required-level 20/40/60/80).</li>
 *   <li>{@link #BRANCH} — {@code X-n-m} nodes growing off a pillar.</li>
 *   <li>{@link #GREEK} — {@code X-alpha/beta/gamma-1} nodes; same {@code group} is mutually exclusive.</li>
 * </ul>
 */
public enum SkillRole {
    MAIN,
    INTERMEDIATE,
    BRANCH,
    GREEK;

    /**
     * Parses a config role token (case-insensitive) to a {@link SkillRole}, or {@link Optional#empty()}
     * when the token is null/blank/unknown so the loader can skip the node with a warning instead of
     * throwing.
     */
    public static Optional<SkillRole> fromConfig(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String token = raw.trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(token));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
