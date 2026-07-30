package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.repository.LoadResult;

import java.util.Set;
import java.util.UUID;

/**
 * Reads the set of ValhallaMMO perk ids a player currently has unlocked (SKILL_TREE design section 3.1).
 * The ids are the compact ValhallaMMO perk ids ({@code lightweapons_perk_a}, {@code lightweapons_perk_ng1})
 * produced by {@link com.trinityforge.skilltree.generator.PerkNaming}, so {@link PerkBuffResolver} can
 * reverse-map a canonical TF node/prestige id to a perk id and test membership.
 *
 * <p>The runtime implementation reads ValhallaMMO's {@code PowerProfile} via reflection (soft dependency,
 * mirroring {@link com.trinityforge.progression.ValhallaSkillLevelSource}); any failure degrades to an
 * empty set so a combat event is never dropped. TrinityForge is the single applier of the TF {@code buffs}
 * (LD-9): Valhalla only owns the unlock state read here, and the {@code buffs} are deliberately excluded
 * from the generated {@code perk_rewards} (P2), so there is no double application.
 */
public interface SkillPerkStatSource {

    /**
     * The compact ValhallaMMO perk ids the player has effectively unlocked (permanent-unlocks folded in,
     * fake-unlocked and permanently-locked perks excluded — the same effective set ValhallaMMO itself uses
     * for perk-reward stat calculation). Never {@code null}; empty when the source is unavailable, the
     * player is offline, or a read fails.
     */
    Set<String> unlockedPerkIds(UUID playerId);

    /**
     * Loads unlocked perk IDs while preserving whether the source read failed.
     *
     * <p>Existing non-persistent sources can keep implementing {@link #unlockedPerkIds(UUID)};
     * their reads are treated as successful. Persistent sources should override this method so
     * callers that maintain a last-known-good mirror do not confuse a storage failure with a
     * successful empty result.
     */
    default LoadResult<Set<String>> loadUnlockedPerkIds(UUID playerId) {
        return LoadResult.found(unlockedPerkIds(playerId));
    }

    /** No-op source: used when ValhallaMMO is absent so perk buffs contribute nothing (full backward compat). */
    SkillPerkStatSource EMPTY = playerId -> Set.of();
}
