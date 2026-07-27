package com.trinityforge.gacha;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Pure weighted random selection over a {@link GachaPool} (concern: one config-editable prize table,
 * one draw per ticket use). Deliberately Bukkit-free and takes an injected {@link Random} so the
 * distribution is unit-testable with a seeded generator; the listener supplies
 * {@link java.util.concurrent.ThreadLocalRandom#current()} at runtime.
 */
public final class GachaDraw {

    private GachaDraw() {
    }

    /**
     * Draws one {@link GachaEntry} from {@code pool}, weighted by {@link GachaEntry#weight()}.
     *
     * @throws IllegalStateException if the pool has no entries or no positive total weight (both
     *                                are prevented for a config-loaded pool by {@code GachaConfig},
     *                                but a hand-built pool in a test could still hit this)
     */
    public static GachaEntry draw(GachaPool pool, Random rng) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(rng, "rng");
        List<GachaEntry> entries = pool.entries();
        if (entries.isEmpty()) {
            throw new IllegalStateException("pool '" + pool.id() + "' has no entries to draw from");
        }

        int totalWeight = 0;
        for (GachaEntry entry : entries) {
            totalWeight += entry.weight();
        }
        if (totalWeight <= 0) {
            throw new IllegalStateException("pool '" + pool.id() + "' has no positive weight to draw from");
        }

        int roll = rng.nextInt(totalWeight);
        int cumulative = 0;
        for (GachaEntry entry : entries) {
            cumulative += entry.weight();
            if (roll < cumulative) {
                return entry;
            }
        }
        // Unreachable: cumulative reaches totalWeight on the last entry and roll < totalWeight always.
        throw new IllegalStateException("pool '" + pool.id() + "' weighted draw fell through (bug)");
    }

    /**
     * "最高レア"の近似定義（{@link GachaRateUp}の rare-slot 近似と同じ規約）: プール内で最小
     * weightに並ぶエントリ全て。config に明示的なrarityフィールドが無いため、既存のrate-up実装が
     * 採用している「最小weight = 最も出にくい = 実質最高レア」という近似をpity天井にも踏襲する。
     *
     * @throws IllegalStateException pool.entries() が空の場合
     */
    public static List<GachaEntry> rarestEntries(GachaPool pool) {
        Objects.requireNonNull(pool, "pool");
        List<GachaEntry> entries = pool.entries();
        if (entries.isEmpty()) {
            throw new IllegalStateException("pool '" + pool.id() + "' has no entries to draw from");
        }
        int minWeight = Integer.MAX_VALUE;
        for (GachaEntry entry : entries) {
            minWeight = Math.min(minWeight, entry.weight());
        }
        int min = minWeight;
        return entries.stream().filter(entry -> entry.weight() == min).toList();
    }

    /** {@code true} if {@code entry} is one of {@link #rarestEntries(GachaPool)} for {@code pool}. */
    public static boolean isRarestEntry(GachaPool pool, GachaEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return rarestEntries(pool).contains(entry);
    }

    /**
     * 最高レア枠(タイ含む)の中から均等抽選で1件確定させる（天井発火時に使う）。タイの各エントリは
     * 定義上同じweightなので、それらの間では均等抽選が正しい重み付き抽選と一致する。
     */
    public static GachaEntry drawRarest(GachaPool pool, Random rng) {
        Objects.requireNonNull(rng, "rng");
        List<GachaEntry> rarest = rarestEntries(pool);
        return rarest.get(rng.nextInt(rarest.size()));
    }

    /**
     * 天井(pity)を考慮した抽選結果。
     *
     * @param entry           確定した景品
     * @param updatedPityCount 呼び出し元がプレイヤーPDCへ保存すべき次回カウンタ値
     * @param pityTriggered   このドローが天井到達による強制確定だったか（UI/ログ用）
     */
    public record PityDraw(GachaEntry entry, int updatedPityCount, boolean pityTriggered) {
    }

    /**
     * 天井(pity)ロジック本体（Bukkit非依存の純ロジック。PDC読み書きは呼び出し元の責務）。
     *
     * <p>{@code pool.pityThreshold() <= 0} なら天井は無効で、通常の{@link #draw}と同じ挙動になり
     * カウンタは常に0を返す（呼び出し元がPDCに書いても実害はないが、天井なしプールでは無視してよい）。
     *
     * <p>天井が有効な場合: {@code currentPityCount + 1 >= pityThreshold} なら今回のドローを
     * {@link #drawRarest}で最高レア確定にする。それ以外は通常の重み付き抽選。
     * ドロー結果が{@link #isRarestEntry}であれば（天井発火/自然的中を問わず）カウンタを0にリセットし、
     * そうでなければ+1する。
     *
     * @param currentPityCount 直前までの連続非最高レア回数（0以上。負値は0として扱う）
     */
    public static PityDraw drawWithPity(GachaPool pool, Random rng, int currentPityCount) {
        return drawWithPity(pool, pool, rng, currentPityCount);
    }

    /**
     * rate-up併用向けの天井抽選: 通常抽選は{@code drawPool}（rate-upブースト済みでよい）で行い、
     * 「最高レア」の定義・天井発火時の確定抽選・カウンタのリセット判定は{@code rarityPool}
     * （configの元プール）に固定する。rate-upは最小weight枠のweightをブーストするため、ブースト後の
     * プールでは「最小weight=最高レア」の近似が壊れる（元の最レア枠が最小でなくなり、別の枠が
     * 最高レア扱いになる）— レア判定を元プールに固定することでこれを防ぐ。
     *
     * <p>ブースト後エントリはweightが異なる別インスタンスになるため、メンバーシップ判定は
     * itemId基準で行う（1プール内でitemIdは景品を一意に識別する）。
     */
    public static PityDraw drawWithPity(GachaPool drawPool, GachaPool rarityPool, Random rng, int currentPityCount) {
        Objects.requireNonNull(drawPool, "drawPool");
        Objects.requireNonNull(rarityPool, "rarityPool");
        Objects.requireNonNull(rng, "rng");
        int safeCurrent = Math.max(0, currentPityCount);
        int threshold = rarityPool.pityThreshold();
        boolean pityTriggered = threshold > 0 && safeCurrent + 1 >= threshold;

        GachaEntry entry = pityTriggered ? drawRarest(rarityPool, rng) : draw(drawPool, rng);
        boolean rarestHit = rarestEntries(rarityPool).stream()
                .anyMatch(rarest -> rarest.itemId().equals(entry.itemId()));
        int updatedCount = rarestHit ? 0 : safeCurrent + 1;
        return new PityDraw(entry, updatedCount, pityTriggered);
    }
}
