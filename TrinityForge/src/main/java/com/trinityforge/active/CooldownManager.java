package com.trinityforge.active;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared per-(player, key) cooldown tracker (2026-07-25 gather-rework-active-framework §3 component 3)
 * replacing every active skill's private {@code Map<UUID, Long>} (the pre-framework pattern
 * {@code HasteActiveMiningListener} used). Bukkit-free and unit-testable with an injected clock, same
 * "pure helper + thin Bukkit listener" split as {@code MiningGimmickPolicy}.
 *
 * <p>Not persisted (session-only, same as the pre-framework map it replaces — design doc §3 component 3
 * "永続はまずセッション"): a server restart clears every cooldown.
 *
 * <p><b>2026-08-18 (W-59) — the tracking key is a "cooldown key", not necessarily {@link ActiveSkill#id()}
 * itself.</b> Callers may pass {@link ActiveSkill#cooldownGroup()} (default = {@link ActiveSkill#id()}) so
 * that two independently-configured {@link ActiveSkill}s (e.g. {@code haste-active-mining} and
 * {@code haste-active-digging}) can share one CT bucket without sharing an id. To make that sharing safe
 * even when the two skills' own {@code cooldownMillis(tier)} differ, the length used to decide "still on
 * cooldown?" is <b>the length that was actually in effect when the bucket was last consumed</b>, not the
 * length the current caller happens to pass — see {@link #tryConsume}. Without this, whichever skill in the
 * group has the shorter cooldown would silently take over the shared bucket's cadence the first time it
 * fires after the longer one (the group would degrade to the shortest member's cooldown instead of each
 * firing enforcing its own length against the group).
 */
public final class CooldownManager {

    /** One bucket's last-consumed timestamp and the cooldown length that was in effect for that use. */
    private record CooldownRecord(long lastUseMillis, long lockMillis) {
    }

    private final Map<UUID, Map<String, CooldownRecord>> recordsByPlayer = new ConcurrentHashMap<>();

    /**
     * Atomically checks-and-consumes: if the lock length recorded at the bucket's last use (or, when never
     * used, immediately) has elapsed, records {@code nowMillis}/{@code cooldownMillis} as the bucket's new
     * lock and returns {@code true}; otherwise leaves the recorded lock untouched and returns {@code false}.
     *
     * <p>Note {@code cooldownMillis} only takes effect on a <em>successful</em> consume — a refused attempt
     * is still governed by whatever length locked the bucket last time (this is what makes a shared
     * {@code cooldownGroup()} robust to its members having different lengths; see the class doc).
     */
    public boolean tryConsume(UUID playerId, String key, long cooldownMillis, long nowMillis) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(key, "key");
        Map<String, CooldownRecord> perKey =
                recordsByPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        boolean[] consumed = {false};
        perKey.compute(key, (id, previous) -> {
            if (previous != null && (nowMillis - previous.lastUseMillis()) < previous.lockMillis()) {
                return previous; // still locked at the length that locked it: leave untouched, do not consume.
            }
            consumed[0] = true;
            return new CooldownRecord(nowMillis, Math.max(0L, cooldownMillis));
        });
        return consumed[0];
    }

    /**
     * Milliseconds remaining before {@code key} is off cooldown for this player. Never negative. Uses the
     * length recorded at the bucket's last use (see {@link #tryConsume}), not {@code cooldownMillis} — the
     * parameter is kept only so callers that show a "would-be" remaining time before ever consuming still
     * compile against the same shape; once a bucket has a record its own locked length always wins.
     */
    public long remainingMillis(UUID playerId, String key, long cooldownMillis, long nowMillis) {
        Map<String, CooldownRecord> perKey = recordsByPlayer.get(playerId);
        if (perKey == null) {
            return 0L;
        }
        CooldownRecord record = perKey.get(key);
        if (record == null) {
            return 0L;
        }
        long elapsed = nowMillis - record.lastUseMillis();
        return Math.max(0L, record.lockMillis() - elapsed);
    }

    /**
     * そのプレイヤーが {@code key} を一度でも使ったか(=CTが走っている可能性があるか)。
     * 常時表示({@link ActiveCooldownDisplay})が、未使用のスキルに対して重いステータス集計を
     * 走らせないための軽量な事前判定。
     */
    public boolean hasRecord(UUID playerId, String key) {
        Map<String, CooldownRecord> perKey = recordsByPlayer.get(playerId);
        return perKey != null && perKey.containsKey(key);
    }

    /** Drops every cooldown entry for {@code playerId} (call on {@code PlayerQuitEvent}, unbounded-map guard). */
    public void clear(UUID playerId) {
        recordsByPlayer.remove(playerId);
    }

    /**
     * Applies an ActiveSkill per-skill CT短縮キー(例: {@code haste-active-mining-cooldown-reduction}、
     * {@link ActiveSkillCooldownKeys#forSkill(String)} 参照)-style fraction to {@code baseMillis}, using
     * the exact same clamp shape {@code CombatListener.startItemCooldown} uses for アイテムCT's
     * {@code cooldown-reduction} (2026-07-25 CT短縮ステータス分離 §1-B / CT設計一本化 §2): the reduction is
     * capped at {@code 0.9} (never below 10% of the
     * original length) before being turned into a multiplier, and — unlike
     * {@code CombatListener.startItemCooldown}, which additionally gates on {@code reduction > 0.0} and so
     * silently ignores negative values — this method applies the multiplier unconditionally, so a negative
     * {@code reductionFraction} legitimately INCREASES the cooldown (ユーザー要望: 負値でCT増加).
     * {@link Double#isFinite} is required for the fraction to have any effect at all (NaN/±Infinity, e.g. from
     * a misconfigured multiplier chain, leave {@code baseMillis} untouched rather than corrupting the CT).
     */
    public static long applyReduction(long baseMillis, double reductionFraction) {
        if (baseMillis <= 0L || !Double.isFinite(reductionFraction)) {
            return Math.max(0L, baseMillis);
        }
        double multiplier = Math.max(0.05, 1.0 - Math.min(0.9, reductionFraction));
        return Math.max(0L, Math.round(baseMillis * multiplier));
    }
}
