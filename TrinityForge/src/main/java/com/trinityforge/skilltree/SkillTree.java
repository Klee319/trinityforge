package com.trinityforge.skilltree;

import java.util.Map;
import java.util.Optional;

/**
 * One immutable skill tree loaded from {@code skilltree/<skill>.yml} (SKILL_TREE design section A):
 * the single source of truth for a Valhalla skill's node graph, TF-owned buffs and prestige rewards.
 * One YAML file maps to exactly one {@link SkillTree}.
 *
 * <p>{@code nodes} is defensively copied and unmodifiable; {@code prestige} may be {@code null}
 * (prestige absent = tree has no breakthrough block). Greek-route exclusivity is not a field here: it
 * is enforced structurally by {@link SkillNode#group()} (same group ⇒ reciprocal Valhalla
 * {@code perks_locked_add}), so unlocking one greek letter locks its siblings and their downstream.
 */
public record SkillTree(
        String skill,
        String displayName,
        String icon,
        String startingCoords,
        Prestige prestige,
        Map<String, SkillNode> nodes) {

    public SkillTree {
        nodes = nodes == null ? Map.of() : Map.copyOf(nodes);
    }

    /** The node with {@code id}, or empty when this tree has no such node. */
    public Optional<SkillNode> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }
}
