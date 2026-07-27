package com.trinityforge.active;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code id -> ActiveSkill} registry (2026-07-25 gather-rework-active-framework §3 component 2).
 * Registration happens once at plugin enable (see {@code TrinityForge#onEnable}); lookups happen per
 * activation attempt from {@link ActivationDispatcher} and per {@code /tf active} debug-command
 * invocation.
 *
 * <p>Not thread-safe by design (same as every other TF config/registry class — Bukkit's main thread owns
 * all of this).
 */
public final class ActiveSkillRegistry {

    private final Map<String, ActiveSkill> byId = new LinkedHashMap<>();

    /** Registers {@code skill}, keyed by {@link ActiveSkill#id()}. A duplicate id overwrites (last wins). */
    public void register(ActiveSkill skill) {
        Objects.requireNonNull(skill, "skill");
        byId.put(skill.id(), skill);
    }

    public Optional<ActiveSkill> get(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    /** Every registered skill, in registration order. Immutable snapshot. */
    public Collection<ActiveSkill> all() {
        return List.copyOf(byId.values());
    }

    /**
     * Every registered skill whose {@link ActiveSkill#targetSkills()} contains {@code skill}
     * (case-insensitive), in registration order — the {@link ActivationDispatcher} trigger-matching
     * candidate list for a held item tagged with that {@code use-skill}. Empty when {@code skill} is
     * {@code null}/blank or no active belongs to it.
     *
     * <p>One {@link ActiveSkill} may be reachable from several {@code skill}s (see {@link ActiveSkill}
     * class doc) — that is a single candidate appearing in multiple calls to this method, sharing exactly
     * one {@link CooldownManager} entry keyed by {@link ActiveSkill#id()}, never a per-{@code skill}
     * cooldown.
     */
    public List<ActiveSkill> forTargetSkill(String skill) {
        if (skill == null || skill.isBlank()) {
            return List.of();
        }
        List<ActiveSkill> matches = new ArrayList<>();
        for (ActiveSkill candidate : byId.values()) {
            if (containsIgnoreCase(candidate.targetSkills(), skill)) {
                matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }

    private static boolean containsIgnoreCase(Set<String> targetSkills, String skill) {
        for (String candidate : targetSkills) {
            if (skill.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }
}
