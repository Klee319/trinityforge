package com.trinityforge.active;

import org.bukkit.entity.Player;

import java.util.Set;

/**
 * One activated (player-triggered, not passive) ability registered onto the shared framework
 * (2026-07-25 gather-rework-active-framework §3 component 1). fork {@code SpellCaster}/{@code ManaManager}
 * are the design reference (concept, not code, porting — TF has no mana/resource layer yet, §3 component
 * 6/§6 Q4).
 *
 * <p>Implementations are expected to be stateless/config-backed (the framework — {@link CooldownManager} —
 * owns all per-player mutable state), so a single instance is registered once at plugin enable and reused
 * for every player.
 *
 * <p>Deviation from the 2026-07-25 design doc §3 component 1 interface sketch: this adds
 * {@link #targetSkills()} and {@link #cooldownMillis(int)}. The design doc's Q2 (正式トリガー =
 * {@code /tf active}+GUI) was overridden by the orchestrating brief in favor of "sneak + 右クリック with
 * the matching {@code use-skill} item" as the ONLY player-facing trigger (GUI dropped entirely,
 * {@code /tf active <id>} kept debug-only) — that trigger rule requires matching the held item's
 * {@code use-skill} tag against the skill-tree(s) the active belongs to, hence {@link #targetSkills()}.
 * {@link #cooldownMillis(int)} makes concrete the design doc's "CT/コストは宣言的メタで外部管理" comment: the
 * {@link ActivationDispatcher} needs a cooldown length before it can consult {@link CooldownManager}, and
 * that length is tier-dependent (haste-active-mining's tiers table), so it cannot live purely inside
 * {@link CooldownManager} itself.
 *
 * <h2>Multi-skill activation is a first-class, expected shape — not an edge case</h2>
 * <p>2026-07-25 regression fix: a single gate ({@code gateEffectId()}) can legitimately be placed on
 * MULTIPLE skill trees (e.g. {@code haste-active-mining} is gated by both {@code mining.yml} A-1 AND
 * {@code digging.yml} A-1 — a shared "採掘速度上昇" ability meant to fire off either a pickaxe or a
 * shovel). {@link #targetSkills()} therefore returns a {@link Set}, not a single id, and
 * {@link #id()}/{@link CooldownManager} stay keyed by the ONE skill id regardless of which tree's item
 * triggered it — a player who activates via a pickaxe and immediately tries again via a shovel must be
 * refused (single shared cooldown per active, never per-trigger-skill). Any new {@link ActiveSkill}
 * implementation, and any future migration of another gathering feature onto this framework, MUST treat
 * "one active reachable from several {@code use-skill} tags" as the default assumption, not a special
 * case to opt into — {@link ActiveSkillRegistry#forTargetSkill(String)} and
 * {@link ActivationDispatcher} are both written against that assumption. Do NOT "solve" a multi-tree gate
 * by registering the same ability twice under two ids: that reintroduces a per-registration cooldown and
 * lets a player double-dip (fire once per held-item type instead of once per ability).
 */
public interface ActiveSkill {

    /** Stable identifier used as the {@link CooldownManager} key and {@code /tf active} debug target. */
    String id();

    /**
     * The {@code feature:<id>} (bare, no prefix) this skill's unlock/tier is gated on, queried via
     * {@code DedicatedEffectsConfig#valueMax(player, gateEffectId())}.
     */
    String gateEffectId();

    /**
     * The skill-tree {@code skill:} id(s) (e.g. {@code {"MINING", "DIGGING"}}) this active can be
     * triggered from. The {@link ActivationDispatcher} trigger rule requires the held main-hand item's
     * {@code use-skill} PDC tag ({@code ItemData#useSkill}, matched case-insensitively, never inferred
     * from Material — see {@code PerkBuffResolver#matchesMainHandSkill}) to be a member of this set
     * before an activation attempt is even considered. Never empty. See the class doc for why this is a
     * set and why that must not be worked around via duplicate registration.
     */
    Set<String> targetSkills();

    /** The cooldown (ms) for a player who resolved to {@code tier} (see {@link ActiveContext#tier()}). */
    long cooldownMillis(int tier);

    /**
     * Applies the skill's effect. Only ever called by {@link ActivationDispatcher} after the gate (tier
     * resolved) and cooldown checks already passed — implementations do not need to re-check either.
     */
    ActivationResult activate(Player player, ActiveContext ctx);
}
