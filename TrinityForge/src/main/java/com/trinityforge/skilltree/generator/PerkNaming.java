package com.trinityforge.skilltree.generator;

import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic id/key derivation between a TF canonical node id and the ValhallaMMO perk-id space
 * (SKILL_TREE design section 2). Two skill-id forms are needed because ValhallaMMO's real progression
 * files use both:
 *
 * <ul>
 *   <li><b>compact</b> — {@code LIGHT_WEAPONS} → {@code lightweapons}. Used for perk ids
 *       ({@code lightweapons_perk_a}) and perk-reward keys ({@code lightweapons_..._add}).</li>
 *   <li><b>snake</b> — {@code LIGHT_WEAPONS} → {@code light_weapons}. Used for
 *       {@code reset_skill_light_weapons}.</li>
 * </ul>
 *
 * <p>Node ids are normalized by lower-casing and replacing every run of non-alphanumeric characters
 * (notably the {@code -} in {@code A-alpha-1} / {@code C-1-1}) with a single {@code _}. The mapping is
 * stable and, for the well-formed TF id space, one-to-one; callers verify uniqueness and fail explicitly
 * on a collision. Reverse mapping is intentionally not provided.
 */
public final class PerkNaming {

    private PerkNaming() {
    }

    /** {@code LIGHT_WEAPONS} → {@code light_weapons} (lower-cased, underscores kept). */
    public static String snake(String skill) {
        return Objects.requireNonNull(skill, "skill").toLowerCase(Locale.ROOT);
    }

    /** {@code LIGHT_WEAPONS} → {@code lightweapons} (lower-cased, underscores stripped). */
    public static String compact(String skill) {
        return snake(skill).replace("_", "");
    }

    /** Normalizes a TF node id into the ValhallaMMO perk-id suffix charset ({@code a-z0-9_}). */
    public static String normalizeNodeId(String nodeId) {
        String lowered = Objects.requireNonNull(nodeId, "nodeId").toLowerCase(Locale.ROOT);
        return lowered.replaceAll("[^a-z0-9]+", "_");
    }

    /** The full ValhallaMMO perk id for a node, e.g. {@code lightweapons_perk_a_alpha_1}. */
    public static String perkId(String skill, String nodeId) {
        return compact(skill) + "_perk_" + normalizeNodeId(nodeId);
    }

    /** The synthetic lv0 root perk id, e.g. {@code lightweapons_perk_root}. */
    public static String rootPerkId(String skill) {
        return compact(skill) + "_perk_root";
    }

    /** The New-Game+ (prestige) perk id for a tier, e.g. {@code lightweapons_perk_ng1}. */
    public static String prestigePerkId(String skill, int tier) {
        return compact(skill) + "_perk_ng" + tier;
    }

    /** The {@code reset_skill_<snake>} perk-reward key that a prestige perk uses to restart the skill. */
    public static String resetSkillKey(String skill) {
        return "reset_skill_" + snake(skill);
    }

    /** Lang key holding a perk's display name. */
    public static String nameLangKey(String perkId) {
        return perkId + "_name";
    }

    /** Lang key holding a perk's description. */
    public static String descriptionLangKey(String perkId) {
        return perkId + "_description";
    }

    /** Wraps a lang key in the {@code <lang.KEY>} placeholder ValhallaMMO resolves at display time. */
    public static String langRef(String langKey) {
        return "<lang." + langKey + ">";
    }
}
