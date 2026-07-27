package com.trinityforge.skilltree.runtime;

import java.util.List;
import java.util.Set;

/**
 * Pure reconciliation between the live ValhallaMMO unlocked-perk set (read by {@link SkillPerkStatSource})
 * and the delimiter-joined held-perk list persisted in a player's PDC ({@code com.trinityforge.pdc.PlayerData},
 * "the unlock truth" UNLOCK 2.1). The PDC mirror is what the ArsPaper integration reads at cast/craft/ritual
 * time ({@code PlayerData.of(player).heldPerks()}); Valhalla is main-thread reflection and the addon must not
 * pay it on every hot-path gate check, so TrinityForge keeps a cheap PDC copy fresh (SKILL_TREE design 3.1).
 *
 * <p>This class holds only the offline-verifiable decisions — canonicalization and change detection — so the
 * Bukkit glue ({@link PerkMirrorService}) stays a thin wrapper. The canonical form is sorted and de-duplicated
 * so a stored list and a freshly-read set compare stably with {@link List#equals}, letting the service skip the
 * PDC write (and its serialization) whenever nothing changed.
 */
public final class PerkMirror {

    private PerkMirror() {
    }

    /**
     * The deterministic list form of an unlocked-perk set for PDC storage: blanks dropped, de-duplicated, and
     * sorted so equal sets always produce an equal list (enabling {@link #needsWrite} to be a plain equality
     * check). Never {@code null}.
     */
    public static List<String> canonical(Set<String> unlockedPerkIds) {
        return unlockedPerkIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Whether the currently-stored held-perk list is out of date versus the freshly-canonicalized unlocked
     * set, i.e. the PDC needs rewriting. Because both sides are canonical (sorted, de-duplicated) this is a
     * direct list equality: a steady-state player produces {@code false} every tick and pays no PDC write.
     */
    public static boolean needsWrite(List<String> storedHeldPerks, List<String> canonicalUnlocked) {
        return !storedHeldPerks.equals(canonicalUnlocked);
    }
}
