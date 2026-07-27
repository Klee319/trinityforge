package com.trinityforge.stats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure gate + weighted-draw logic for the 4-profession drop-table mechanism (2026-07-23
 * stat-gate-overhaul §4). Bukkit-free: takes plain {@code Set<String>} held perks and the
 * {@code Map<String,Set<String>>} gate index already exposed by
 * {@code DedicatedEffectsConfig#dropGatePerks()}, so every rule here is unit-testable with hand-built
 * maps instead of a live server.
 *
 * <p>Gate convention (design doc §3, "自動反転規則"): a category/item id with NO entry in
 * {@code dropGatePerks} is open to everyone; once referenced by at least one node it becomes locked to
 * only the perk-holders listed. Category gate key: {@code "<prof>:<categoryId>"}. Item gate key:
 * {@code "<prof>:item:<itemId>"}. Both gates apply — a locked category hides every entry in it regardless
 * of item gates; an open category can still individually lock specific entries by item id. A locked entry
 * is simply excluded from the weight pool (自然再配分 onto the remaining open entries), never treated as
 * "still present but worth 0" (which would change nothing) nor as an error.
 */
public final class DropTablePolicy {

    private DropTablePolicy() {
    }

    /** True with probability {@code triggerChancePercent}% (0-100 scale). {@code roll01} is a draw in [0,1). */
    public static boolean triggerRoll(double triggerChancePercent, double roll01) {
        if (!Double.isFinite(triggerChancePercent) || triggerChancePercent <= 0.0) {
            return false;
        }
        double clamped = Math.min(triggerChancePercent, 100.0);
        return roll01 < (clamped / 100.0);
    }

    /** Whether category {@code categoryId} of profession {@code prof} is open to {@code heldPerks}. */
    public static boolean categoryOpen(String prof, String categoryId, Set<String> heldPerks,
                                        Map<String, Set<String>> dropGatePerks) {
        return isOpen(prof + ":" + categoryId, heldPerks, dropGatePerks);
    }

    /** Whether item {@code itemId} of profession {@code prof} is open to {@code heldPerks} (item-level gate). */
    public static boolean itemOpen(String prof, String itemId, Set<String> heldPerks,
                                    Map<String, Set<String>> dropGatePerks) {
        return isOpen(prof + ":item:" + itemId, heldPerks, dropGatePerks);
    }

    private static boolean isOpen(String key, Set<String> heldPerks, Map<String, Set<String>> dropGatePerks) {
        if (dropGatePerks == null) {
            return true;
        }
        Set<String> gatingPerks = dropGatePerks.get(key);
        if (gatingPerks == null || gatingPerks.isEmpty()) {
            return true; // unreferenced = open (design doc §3 automatic-inversion rule)
        }
        if (heldPerks == null || heldPerks.isEmpty()) {
            return false;
        }
        for (String perk : gatingPerks) {
            if (heldPerks.contains(perk)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Weighted draw over {@code entries}, restricted to those whose item-gate is open. Empty when no entry
     * is open or the open pool has no positive total weight.
     *
     * @param roll01 uniform draw in [0,1)
     */
    public static Optional<DropTableConfig.Entry> drawOpenEntry(String prof, List<DropTableConfig.Entry> entries,
                                                                  Set<String> heldPerks,
                                                                  Map<String, Set<String>> dropGatePerks,
                                                                  double roll01) {
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        List<DropTableConfig.Entry> open = new ArrayList<>();
        int totalWeight = 0;
        for (DropTableConfig.Entry entry : entries) {
            if (itemOpen(prof, entry.item(), heldPerks, dropGatePerks)) {
                open.add(entry);
                totalWeight += Math.max(0, entry.weight());
            }
        }
        if (open.isEmpty() || totalWeight <= 0) {
            return Optional.empty();
        }
        double safeRoll = Double.isFinite(roll01) ? Math.max(0.0, Math.min(roll01, Math.nextDown(1.0))) : 0.0;
        int roll = (int) Math.floor(safeRoll * totalWeight);
        int cumulative = 0;
        for (DropTableConfig.Entry entry : open) {
            cumulative += Math.max(0, entry.weight());
            if (roll < cumulative) {
                return Optional.of(entry);
            }
        }
        // Unreachable in theory (cumulative reaches totalWeight and roll < totalWeight always), kept as a
        // float-rounding fallback rather than throwing out of a block-break/fish-catch handler.
        return Optional.of(open.get(open.size() - 1));
    }

    /**
     * Evaluates one block-gimmick category end to end: category gate -&gt; trigger roll -&gt; weighted draw.
     * Used by mining/woodcutting/digging, where each category has its own independent trigger chance.
     */
    public static Optional<DropTableConfig.Entry> evaluateCategory(String prof, DropTableConfig.Category category,
                                                                     Set<String> heldPerks,
                                                                     Map<String, Set<String>> dropGatePerks,
                                                                     double triggerRoll01, double drawRoll01) {
        if (category == null) {
            return Optional.empty();
        }
        if (!categoryOpen(prof, category.id(), heldPerks, dropGatePerks)) {
            return Optional.empty();
        }
        if (!triggerRoll(category.triggerChancePercent(), triggerRoll01)) {
            return Optional.empty();
        }
        return drawOpenEntry(prof, category.entries(), heldPerks, dropGatePerks, drawRoll01);
    }

    /**
     * Combines every open category of a fishing group into a single weighted pool and draws once (a
     * fishing group's categories have no individual trigger — the group itself was already chosen by
     * {@link #rollTreasureGroup}).
     */
    public static Optional<DropTableConfig.Entry> drawAcrossCategories(
            String prof, Map<String, DropTableConfig.Category> categories, Set<String> heldPerks,
            Map<String, Set<String>> dropGatePerks, double roll01) {
        return drawAcrossCategoriesWithExemption(prof, categories, heldPerks, dropGatePerks, roll01)
                .map(DrawnEntry::entry);
    }

    /**
     * One drawn entry plus whether the category it came from is {@code scrap-exempt} (2026-07-23 verifier
     * 指摘⑥): {@code true} means this draw must be excluded from the {@code junk-to-scrap} blanket
     * replacement (e.g. {@code ocean_thread}), regardless of which group (treasure/junk) it was drawn from.
     */
    public record DrawnEntry(DropTableConfig.Entry entry, boolean scrapExempt) {
    }

    /**
     * Same pool-and-draw as {@link #drawAcrossCategories}, but also reports whether the category the drawn
     * entry came from is marked {@code scrap-exempt} — callers that apply a blanket junk-to-scrap swap
     * (e.g. {@code FishingGimmickListener}) must skip that swap for an exempt draw so categories like
     * {@code ocean_thread} stay obtainable even for junk-to-scrap holders.
     */
    public static Optional<DrawnEntry> drawAcrossCategoriesWithExemption(
            String prof, Map<String, DropTableConfig.Category> categories, Set<String> heldPerks,
            Map<String, Set<String>> dropGatePerks, double roll01) {
        if (categories == null || categories.isEmpty()) {
            return Optional.empty();
        }
        List<DropTableConfig.Entry> pooled = new ArrayList<>();
        // Entry objects are unique per config-parsed occurrence (never deduplicated across categories), so
        // identity-keying here correctly recovers which category a pooled entry came from after the draw.
        Map<DropTableConfig.Entry, Boolean> exemptionByEntry = new java.util.IdentityHashMap<>();
        for (Map.Entry<String, DropTableConfig.Category> catEntry : categories.entrySet()) {
            if (!categoryOpen(prof, catEntry.getKey(), heldPerks, dropGatePerks)) {
                continue;
            }
            for (DropTableConfig.Entry entry : catEntry.getValue().entries()) {
                pooled.add(entry);
                exemptionByEntry.put(entry, catEntry.getValue().scrapExempt());
            }
        }
        return drawOpenEntry(prof, pooled, heldPerks, dropGatePerks, roll01)
                .map(entry -> new DrawnEntry(entry, exemptionByEntry.getOrDefault(entry, false)));
    }

    /**
     * Fishing 宝率シフト (§2.3): base treasure-percent shifted by {@code (1 + luckTotal)}, clamped to
     * {@code [0.05, 95.0]} so a huge positive luck can never make junk impossible and a deeply negative
     * luck (e.g. a debuff) can never make treasure exactly impossible either (design doc note: "-9999%でも
     * 厳密に0にはならない").
     *
     * <p>2026-07-23 verifier指摘⑤: このクランプは luck シフト適用後の結果にのみ適用する。{@code luckTotal == 0}
     * のときは管理者設定の {@code treasure-percent} をそのまま返す（{@code 0}/{@code 100} も許容）— luckシフトが
     * 無いのに base 自体を [0.05, 95] へ丸めてしまうと、管理者が意図した「常に0%（宝なし）」「常に100%（宝確定）」
     * 設定が実現不可能になっていた。
     */
    public static double treasurePercent(double baseTreasurePercent, double luckTotal) {
        double base = Double.isFinite(baseTreasurePercent) ? baseTreasurePercent : 0.0;
        double luck = Double.isFinite(luckTotal) ? luckTotal : 0.0;
        if (luck == 0.0) {
            return base;
        }
        double shifted = base * (1.0 + luck);
        if (!Double.isFinite(shifted)) {
            shifted = base >= 0 ? 95.0 : 0.05;
        }
        return Math.max(0.05, Math.min(shifted, 95.0));
    }

    /**
     * Three-way fishing outcome (2026-07-23 仕様確定: 三択モデル化): 宝% / ゴミ% / 残り=通常の魚
     * (バニラキャッチ維持)。宝%は {@link #treasurePercent} と同じluckシフト(luck==0は素通し、luck!=0のみ
     * [0.05,95]クランプ)。ゴミ%は基準値(luckでシフトしない)を、宝%がシフトした分だけ比例縮小/拡大する:
     * {@code junk' = junkBase × (100 − treasureShifted) / (100 − treasureBase)}（{@code treasureBase>=100}
     * のときは宝以外の枠が元々存在しないため {@code junk'=0}）。
     */
    public static FishOutcome rollFishOutcome(double treasureBasePercent, double junkBasePercent,
                                               double luckTotal, double roll01) {
        double treasureBase = Double.isFinite(treasureBasePercent)
                ? Math.max(0.0, Math.min(treasureBasePercent, 100.0)) : 0.0;
        double treasureShifted = treasurePercent(treasureBase, luckTotal);
        double junkShifted = junkPercentAfterTreasureShift(junkBasePercent, treasureBase, treasureShifted);

        double safeRoll = Double.isFinite(roll01) ? Math.max(0.0, Math.min(roll01, Math.nextDown(1.0))) : 0.0;
        double rollPercent = safeRoll * 100.0;
        if (rollPercent < treasureShifted) {
            return FishOutcome.TREASURE;
        }
        if (rollPercent < treasureShifted + junkShifted) {
            return FishOutcome.JUNK;
        }
        return FishOutcome.NORMAL_FISH;
    }

    /** {@code drop:fishing}三択のロール結果: 宝テーブル抽選 / ゴミテーブル抽選 / 通常の魚(バニラキャッチ維持)。 */
    public enum FishOutcome {
        TREASURE, JUNK, NORMAL_FISH
    }

    private static double junkPercentAfterTreasureShift(double junkBasePercent, double treasureBase,
                                                          double treasureShifted) {
        double junkBase = Double.isFinite(junkBasePercent) ? Math.max(0.0, Math.min(junkBasePercent, 100.0)) : 0.0;
        if (treasureBase >= 100.0) {
            return 0.0; // 宝以外の枠が元々存在しない設定 -> ゴミ%は常に0。
        }
        double ratio = (100.0 - treasureShifted) / (100.0 - treasureBase);
        double shifted = junkBase * ratio;
        if (!Double.isFinite(shifted) || shifted < 0.0) {
            return 0.0;
        }
        // 宝%+ゴミ%が100%を超えないよう安全にクランプ(残り=通常の魚枠が負にならないためのガード)。
        return Math.min(shifted, 100.0 - treasureShifted);
    }

    /** True (treasure group) / false (junk group), weighted by {@code treasurePercent}% (0-100 scale). */
    public static boolean rollTreasureGroup(double treasurePercent, double roll01) {
        double clamped = Math.max(0.0, Math.min(Double.isFinite(treasurePercent) ? treasurePercent : 0.0, 100.0));
        double safeRoll = Double.isFinite(roll01) ? roll01 : 1.0;
        return safeRoll < (clamped / 100.0);
    }
}
