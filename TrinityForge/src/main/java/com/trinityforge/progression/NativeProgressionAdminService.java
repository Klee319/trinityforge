package com.trinityforge.progression;

import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.core.XpTransitionService;
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
import java.util.function.Supplier;

/** Atomic administrator edits for native skill levels and prestige counters. */
public final class NativeProgressionAdminService {

    private static final String POWER = "POWER";
    private static final double DEFAULT_POWER_EXP_PER_SKILL_LEVEL = 100.0;
    private static final long STARTING_SKILL_POINTS = PlayerProgression.STARTING_SKILL_POINTS;

    private final ProgressionRepository repository;
    private final NativeSkillCatalog catalog;
    private final Supplier<Collection<SkillTree>> trees;
    private final PlayerLockRegistry locks;
    /**
     * 何POWERレベルごとにスキルポイント1点を与えるか
     * （{@code stats/skill-exp.yml: power.levels-per-skill-point}）。既定は {@code () -> 1}。
     * reload を反映するため Supplier 経由で毎回引く。
     */
    private final java.util.function.IntSupplier levelsPerSkillPoint;

    public NativeProgressionAdminService(
            ProgressionRepository repository,
            NativeSkillCatalog catalog,
            Supplier<Collection<SkillTree>> trees) {
        this(repository, catalog, trees, new PlayerLockRegistry());
    }

    /**
     * Full constructor. Pass the same {@link PlayerLockRegistry} instance used by
     * {@link NativeProgressionService} so admin edits serialize against gameplay EXP
     * grants/perk unlocks for the same player instead of racing on a stale read-modify-write.
     */
    public NativeProgressionAdminService(
            ProgressionRepository repository,
            NativeSkillCatalog catalog,
            Supplier<Collection<SkillTree>> trees,
            PlayerLockRegistry locks) {
        this(repository, catalog, trees, locks, () -> 1);
    }

    /**
     * スキルポイント付与間隔つきの構築子（2026-08-04）。{@code levelsPerSkillPoint} を
     * {@link NativeProgressionService} と<b>同じ供給元</b>から渡すこと。片方だけ設定を見ると、
     * 管理コマンドの再計算がレベルアップ時の付与と食い違う。
     */
    public NativeProgressionAdminService(
            ProgressionRepository repository,
            NativeSkillCatalog catalog,
            Supplier<Collection<SkillTree>> trees,
            PlayerLockRegistry locks,
            java.util.function.IntSupplier levelsPerSkillPoint) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.trees = Objects.requireNonNull(trees, "trees");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.levelsPerSkillPoint = Objects.requireNonNull(levelsPerSkillPoint, "levelsPerSkillPoint");
    }

    public EditResult edit(
            UUID playerId, String rawSkillId, EditMode mode, int amount, Integer prestigeCount) {
        return locks.withLock(playerId, () ->
                editUnderRepositoryLock(playerId, rawSkillId, mode, amount, prestigeCount));
    }

    private EditResult editUnderRepositoryLock(
            UUID playerId, String rawSkillId, EditMode mode, int amount, Integer prestigeCount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(mode, "mode");
        if (amount < 0) {
            return EditResult.rejected(EditStatus.INVALID_AMOUNT, normalize(rawSkillId));
        }
        String skillId = normalize(rawSkillId);
        SkillCatalogEntry entry = catalog.get(skillId);
        if (entry == null) {
            return EditResult.rejected(EditStatus.UNKNOWN_SKILL, skillId);
        }

        Prestige prestige = null;
        if (prestigeCount != null) {
            SkillTree tree = tree(skillId);
            prestige = tree == null ? null : tree.prestige();
            if (POWER.equals(skillId) || prestige == null || !prestige.enabled()) {
                return EditResult.rejected(EditStatus.PRESTIGE_DISABLED, skillId);
            }
            if (prestigeCount < 0 || prestigeCount > prestige.maxTimes()) {
                return EditResult.rejected(EditStatus.PRESTIGE_OUT_OF_RANGE, skillId);
            }
        }

        PlayerProgression player;
        LoadResult<PlayerProgression> loadResult = repository.load(playerId);
        if (loadResult.isFailed()) {
            return new EditResult(
                    EditStatus.STORAGE_FAILURE, skillId, null, null, null, loadResult.error().getMessage());
        }
        player = loadResult.orElseGet(() ->
                PlayerProgression.empty(playerId).withPoints(STARTING_SKILL_POINTS, 0L));
        SkillProgress before = player.skillOrDefault(skillId, entry.maxLevel());
        long targetLevel = switch (mode) {
            case SET -> amount;
            case ADD -> (long) before.level() + amount;
            case SUBTRACT -> (long) before.level() - amount;
        };
        if (targetLevel < 0 || targetLevel > entry.maxLevel()) {
            return EditResult.rejected(EditStatus.LEVEL_OUT_OF_RANGE, skillId);
        }

        XpTransitionService transitions = new XpTransitionService(entry.curve());
        double totalExp = transitions.cumulativeExpForLevel((int) targetLevel);
        int resultingPrestige = prestigeCount == null ? before.prestige() : prestigeCount;
        SkillProgress after = new SkillProgress(
                (int) targetLevel, 0.0, totalExp, resultingPrestige, entry.maxLevel());

        int levelDelta = after.level() - before.level();
        SkillProgress powerAfter = null;
        int resultingPowerLevel;
        if (POWER.equals(skillId)) {
            resultingPowerLevel = after.level();
        } else {
            SkillCatalogEntry powerEntry = catalog.get(POWER);
            if (powerEntry == null) {
                return EditResult.rejected(EditStatus.UNKNOWN_SKILL, POWER);
            }
            SkillProgress powerBefore = player.skillOrDefault(POWER, powerEntry.maxLevel());
            double powerPerLevel = powerEntry.rate("power.exp_per_skill_level",
                    DEFAULT_POWER_EXP_PER_SKILL_LEVEL);
            powerAfter = new XpTransitionService(powerEntry.curve()).apply(
                    powerBefore, powerPerLevel * levelDelta);
            resultingPowerLevel = powerAfter.level();
        }

        List<String> stripPerkIds = List.of();
        long refund = 0L;
        if (after.level() < before.level()) {
            SkillTree skillTree = tree(skillId);
            if (skillTree != null) {
                LoadResult<Set<String>> ownedLoad = repository.loadPerkIds(playerId);
                if (ownedLoad.isFailed()) {
                    return new EditResult(
                            EditStatus.STORAGE_FAILURE, skillId, before, null, null,
                            ownedLoad.error().getMessage());
                }
                Set<String> owned = ownedLoad.orElseThrow();
                LoadResult<Map<String, Long>> costLoad = repository.loadPerkCosts(playerId);
                if (costLoad.isFailed()) {
                    return new EditResult(
                            EditStatus.STORAGE_FAILURE, skillId, before, null, null,
                            costLoad.error().getMessage());
                }
                Map<String, Long> storedCosts = costLoad.orElseThrow();
                List<String> toStrip = new ArrayList<>();
                for (SkillNode node : skillTree.nodes().values()) {
                    String perkId = PerkNaming.perkId(skillId, node.id());
                    if (owned.contains(perkId) && node.level() > after.level()) {
                        toStrip.add(perkId);
                        // Refund the actual stored purchase cost (authoritative, incl. 0 for free
                        // unlocks); fall back to current YAML cost only for rows with no stored cost
                        // (pre-migration). Using YAML cost for a node bought at 0 mints phantom points.
                        Long stored = storedCosts.get(perkId);
                        refund += stored != null ? Math.max(0L, stored) : Math.max(0, node.cost());
                    }
                }
                stripPerkIds = List.copyOf(toStrip);
            }
        }

        long spentAfter = Math.max(0L, player.spentPoints() - refund);
        long earnedPoints =
                PlayerProgression.earnedPoints(resultingPowerLevel, levelsPerSkillPoint.getAsInt());
        if (spentAfter > earnedPoints) {
            return EditResult.rejected(EditStatus.POINT_LEDGER_CONFLICT, skillId);
        }
        long availablePoints = earnedPoints - spentAfter;
        String prestigePrefix = prestigeCount == null
                ? null : PerkNaming.compact(skillId) + "_perk_ng";
        try {
            repository.saveAdminProgressionEdit(
                    playerId, skillId, after, powerAfter,
                    availablePoints, spentAfter, prestigePrefix, resultingPrestige,
                    stripPerkIds);
        } catch (IllegalStateException ex) {
            return new EditResult(
                    EditStatus.STORAGE_FAILURE, skillId, before, null, powerAfter, ex.getMessage());
        }
        return new EditResult(EditStatus.APPLIED, skillId, before, after, powerAfter, null);
    }

    private SkillTree tree(String skillId) {
        Collection<SkillTree> available = trees.get();
        if (available == null) return null;
        return available.stream()
                .filter(tree -> skillId.equals(normalize(tree.skill())))
                .findFirst()
                .orElse(null);
    }

    private static String normalize(String skillId) {
        return skillId == null ? "" : skillId.trim().toUpperCase(Locale.ROOT);
    }

    public enum EditMode {
        SET, ADD, SUBTRACT
    }

    public enum EditStatus {
        APPLIED,
        UNKNOWN_SKILL,
        INVALID_AMOUNT,
        LEVEL_OUT_OF_RANGE,
        PRESTIGE_DISABLED,
        PRESTIGE_OUT_OF_RANGE,
        POINT_LEDGER_CONFLICT,
        STORAGE_FAILURE
    }

    public record EditResult(
            EditStatus status,
            String skillId,
            SkillProgress before,
            SkillProgress after,
            SkillProgress powerAfter,
            String detail) {

        private static EditResult rejected(EditStatus status, String skillId) {
            return new EditResult(status, skillId, null, null, null, null);
        }
    }
}
