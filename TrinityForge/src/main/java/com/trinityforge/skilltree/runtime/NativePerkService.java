package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillTree;
import com.trinityforge.skilltree.generator.PerkNaming;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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

    /**
     * スキルノードロック (2026-07-27): プレステージ時に解放を維持する perk ID を返す供給元。
     * 未配線(null)ならロック無し = 従来どおり全ノードが剥がれる。
     */
    private volatile Function<UUID, Set<String>> lockedPerkSupplier;

    /**
     * ロック済み perk の供給元を差し込む(既定コンストラクタを壊さないための任意注入)。
     * 実運用ではプレイヤーPDC({@code PlayerData#lockedPerks()})を読む関数を渡す。
     */
    public void setLockedPerkSupplier(Function<UUID, Set<String>> supplier) {
        this.lockedPerkSupplier = supplier;
    }

    /** ロック済み perk。供給元の例外でプレステージを落とさない(失敗したらロック無し扱い)。 */
    private Set<String> lockedPerks(UUID playerId) {
        Function<UUID, Set<String>> supplier = this.lockedPerkSupplier;
        if (supplier == null) return Set.of();
        try {
            Set<String> locked = supplier.apply(playerId);
            return locked == null ? Set.of() : locked;
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "[progression] Failed to read locked perks for " + playerId, ex);
            return Set.of();
        }
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
        // スキルノードロック(2026-07-27): ロックされ、かつ現在所持しているノードだけを「維持対象」とする。
        // ロックだけあって未所持の perk は無視する(保護指定は所持状態と独立に付けられるため)。
        Set<String> locked = lockedPerks(playerId);
        List<String> retained = new ArrayList<>();
        // Refund stored purchase_cost when present (authoritative, incl. 0 for free unlocks); fall
        // back to current YAML cost only for rows with no stored cost (pre-migration). Treating a
        // stored 0 as "unknown" and refunding YAML cost would mint phantom points on prestige.
        long refund = 0L;
        for (var node : tree.nodes().values()) {
            String perkId = PerkNaming.perkId(tree.skill(), node.id());
            if (!owned.contains(perkId)) continue;
            if (locked.contains(perkId)) {
                // 維持するノードは返却しない — 返却したうえで無償再付与すると、
                // 支払っていないポイントが増える(ロックが実質的なSP無限増殖になる)。
                retained.add(perkId);
                continue;
            }
            Long stored = storedCosts.get(perkId);
            refund += stored != null ? Math.max(0L, stored) : Math.max(0L, node.cost());
        }
        SkillProgress reset = new SkillProgress(
                0, 0.0, 0.0, tier, current.maxAllowedLevel());
        String prefix = PerkNaming.compact(tree.skill()) + "_perk_";
        if (!repository.prestige(playerId, tree.skill(), prefix,
                PerkNaming.prestigePerkId(tree.skill(), tier), reset, refund)) {
            return PrestigeResult.FAILED;
        }
        // 維持対象を無償(cost 0)で再付与する。既に同じ per-player ロック内なので、
        // 他スレッドの unlockPerk が割り込んで二重計上することはない。
        for (String perkId : retained) {
            Long stored = storedCosts.get(perkId);
            // 支払い済みコストも一緒に復元する(次のプレステージで正しく返却されるように)。
            if (!repository.unlockPerk(playerId, perkId, 0L)) {
                LOG.log(Level.WARNING, "[progression] Failed to retain locked perk " + perkId
                        + " for " + playerId + " (prestige already committed)");
                continue;
            }
            if (stored != null && stored > 0L) {
                LOG.log(Level.FINE, () -> "[progression] Retained locked perk " + perkId
                        + " (original cost " + stored + " kept as spent)");
            }
        }
        return PrestigeResult.PRESTIGED;
    }

    /**
     * スキルツリーリセット (2026-07-27): レベル・プレステージ段は維持したまま、そのスキルの
     * 通常ノードperkを全て剥がしてSPを返却する（いわゆる振り直し）。
     *
     * <p>プレステージとの違いは「レベルを0に戻さない」「プレステージ段を上げない」の2点。
     * ノードロックはプレステージ時の保護であって振り直しの保護ではないため、
     * ここではロック済みノードも剥がして返却する（そうしないとロックしたSPが二度と戻らない）。
     */
    public ResetResult resetTree(UUID playerId, String rawSkillId) {
        SkillTree tree = tree(rawSkillId);
        if (tree == null) return ResetResult.UNKNOWN_SKILL;
        return progression.playerLocks().withLock(playerId, () -> resetUnderLock(playerId, tree));
    }

    private ResetResult resetUnderLock(UUID playerId, SkillTree tree) {
        var snapshot = progression.snapshot(playerId);
        SkillProgress current = progression.progress(playerId, tree.skill()).orElse(null);
        if (current == null) return ResetResult.NOTHING_TO_RESET;

        LoadResult<Set<String>> ownedLoad = repository.loadPerkIds(playerId);
        if (ownedLoad.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load perks for reset " + playerId,
                    ownedLoad.error());
            return ResetResult.FAILED;
        }
        Set<String> owned = ownedLoad.orElseThrow();
        LoadResult<Map<String, Long>> costsLoad = repository.loadPerkCosts(playerId);
        if (costsLoad.isFailed()) {
            LOG.log(Level.WARNING, "[progression] Failed to load perk costs for reset " + playerId,
                    costsLoad.error());
            return ResetResult.FAILED;
        }
        Map<String, Long> storedCosts = costsLoad.orElseThrow();

        List<String> strip = new ArrayList<>();
        long refund = 0L;
        for (var node : tree.nodes().values()) {
            String perkId = PerkNaming.perkId(tree.skill(), node.id());
            if (!owned.contains(perkId)) continue;
            strip.add(perkId);
            Long stored = storedCosts.get(perkId);
            refund += stored != null ? Math.max(0L, stored) : Math.max(0L, node.cost());
        }
        if (strip.isEmpty()) return ResetResult.NOTHING_TO_RESET;

        try {
            // prestigePerkPrefix = null → プレステージperkには一切触れない(段はそのまま)。
            // skillProgress は current をそのまま渡す = レベル/EXPは書き換えない。
            repository.saveAdminProgressionEdit(
                    playerId, tree.skill(), current, null,
                    snapshot.availablePoints() + refund,
                    Math.max(0L, snapshot.spentPoints() - refund),
                    null, 0, strip);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "[progression] Failed to reset skill tree " + tree.skill()
                    + " for " + playerId, ex);
            return ResetResult.FAILED;
        }
        return ResetResult.RESET;
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

    /** {@link #resetTree(UUID, String)} の結果。 */
    public enum ResetResult {
        RESET, UNKNOWN_SKILL, NOTHING_TO_RESET, FAILED
    }
}
