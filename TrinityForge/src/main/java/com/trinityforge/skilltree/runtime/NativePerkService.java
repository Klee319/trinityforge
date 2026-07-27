package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Validates and commits native perk unlock and prestige transactions. */
public final class NativePerkService {

    private static final Logger LOG = Logger.getLogger(NativePerkService.class.getName());

    private final NativeProgressionService progression;
    private final ProgressionRepository repository;
    private final Supplier<Collection<SkillTree>> trees;

    public NativePerkService(NativeProgressionService progression,
                             Supplier<Collection<SkillTree>> trees) {
        this.progression = Objects.requireNonNull(progression, "progression");
        this.repository = progression.repository();
        this.trees = Objects.requireNonNull(trees, "trees");
    }

    public UnlockResult unlock(UUID playerId, String rawSkillId, String nodeId) {
        SkillTree tree = tree(rawSkillId);
        if (tree == null) return UnlockResult.UNKNOWN_SKILL;
        SkillNode node = tree.nodes().get(nodeId);
        if (node == null) return UnlockResult.UNKNOWN_NODE;
        var snapshot = progression.snapshot(playerId);
        int level = snapshot.skillOrDefault(tree.skill(), 100).level();
        LoadResult<Set<String>> ownedLoad = repository.loadPerkIds(playerId);
        if (ownedLoad.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load owned perks for " + playerId,
                    ownedLoad.error());
            return UnlockResult.INSUFFICIENT_POINTS;
        }
        Set<String> owned = ownedLoad.orElseThrow();
        UnlockResult validation = validateUnlock(
                tree, node, level, snapshot.availablePoints(), owned);
        if (validation != UnlockResult.ELIGIBLE) return validation;
        String perkId = PerkNaming.perkId(tree.skill(), node.id());
        return progression.unlockPerk(playerId, perkId, Math.max(0, node.cost()))
                ? UnlockResult.UNLOCKED : UnlockResult.INSUFFICIENT_POINTS;
    }

    static UnlockResult validateUnlock(SkillTree tree, SkillNode node, int level,
                                       long availablePoints, Set<String> owned) {
        String perkId = PerkNaming.perkId(tree.skill(), node.id());
        if (owned.contains(perkId)) return UnlockResult.ALREADY_UNLOCKED;
        if (level < node.level()) return UnlockResult.LEVEL_TOO_LOW;
        if (!node.prerequisiteParents().isEmpty()
                && node.prerequisiteParents().stream()
                        .map(parent -> PerkNaming.perkId(tree.skill(), parent))
                        .noneMatch(owned::contains)) {
            return UnlockResult.MISSING_PARENT;
        }
        if (node.group() != null) {
            boolean conflict = tree.nodes().values().stream()
                    .filter(other -> node.group().equals(other.group()))
                    .filter(other -> Objects.equals(node.parent(), other.parent()))
                    .filter(other -> !node.id().equals(other.id()))
                    .map(other -> PerkNaming.perkId(tree.skill(), other.id()))
                    .anyMatch(owned::contains);
            if (conflict) return UnlockResult.EXCLUSIVE_CONFLICT;
        }
        if (availablePoints < Math.max(0, node.cost())) {
            return UnlockResult.INSUFFICIENT_POINTS;
        }
        return UnlockResult.ELIGIBLE;
    }

    public PrestigeResult prestige(UUID playerId, String rawSkillId) {
        SkillTree tree = tree(rawSkillId);
        if (tree == null) return PrestigeResult.UNKNOWN_SKILL;
        Prestige config = tree.prestige();
        if (config == null || !config.enabled()) return PrestigeResult.DISABLED;
        // The owned-perk/stored-cost reads and the repository.prestige() write must be one atomic
        // span: a concurrent unlockPerk landing between the read and the wipe would be deleted
        // without refund. Serialize on the same per-player lock as grantExp/unlockPerk/admin edit.
        return progression.playerLocks().withLock(playerId, () -> prestigeUnderLock(playerId, tree, config));
    }

    private PrestigeResult prestigeUnderLock(UUID playerId, SkillTree tree, Prestige config) {
        SkillProgress current = progression.progress(playerId, tree.skill()).orElse(null);
        if (current == null || current.level() < config.atLevel()) {
            return PrestigeResult.LEVEL_TOO_LOW;
        }
        int tier = current.prestige() + 1;
        if (tier > config.maxTimes()) return PrestigeResult.MAXED;

        LoadResult<Set<String>> ownedLoad = repository.loadPerkIds(playerId);
        if (ownedLoad.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load perks for prestige " + playerId,
                    ownedLoad.error());
            return PrestigeResult.FAILED;
        }
        Set<String> owned = ownedLoad.orElseThrow();
        LoadResult<Map<String, Long>> costsLoad = repository.loadPerkCosts(playerId);
        if (costsLoad.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load perk costs for prestige " + playerId,
                    costsLoad.error());
            return PrestigeResult.FAILED;
        }
        Map<String, Long> storedCosts = costsLoad.orElseThrow();
        // Refund stored purchase_cost when present (authoritative, incl. 0 for free unlocks); fall
        // back to current YAML cost only for rows with no stored cost (pre-migration). Treating a
        // stored 0 as "unknown" and refunding YAML cost would mint phantom points on prestige.
        long refund = 0L;
        for (var node : tree.nodes().values()) {
            String perkId = PerkNaming.perkId(tree.skill(), node.id());
            if (!owned.contains(perkId)) continue;
            Long stored = storedCosts.get(perkId);
            refund += stored != null ? Math.max(0L, stored) : Math.max(0L, node.cost());
        }
        SkillProgress reset = new SkillProgress(
                0, 0.0, 0.0, tier, current.maxAllowedLevel());
        String prefix = PerkNaming.compact(tree.skill()) + "_perk_";
        return repository.prestige(playerId, tree.skill(), prefix,
                PerkNaming.prestigePerkId(tree.skill(), tier), reset, refund)
                ? PrestigeResult.PRESTIGED : PrestigeResult.FAILED;
    }

    public SkillTree tree(String rawSkillId) {
        if (rawSkillId == null) return null;
        String skillId = rawSkillId.trim().toUpperCase(Locale.ROOT);
        return trees.get().stream().filter(tree -> skillId.equals(tree.skill())).findFirst().orElse(null);
    }

    public enum UnlockResult {
        ELIGIBLE, UNLOCKED, UNKNOWN_SKILL, UNKNOWN_NODE, LEVEL_TOO_LOW, MISSING_PARENT,
        EXCLUSIVE_CONFLICT, ALREADY_UNLOCKED, INSUFFICIENT_POINTS
    }

    public enum PrestigeResult {
        PRESTIGED, UNKNOWN_SKILL, DISABLED, LEVEL_TOO_LOW, MAXED, FAILED
    }
}
