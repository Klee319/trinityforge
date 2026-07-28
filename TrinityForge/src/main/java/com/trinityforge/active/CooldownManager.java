package com.trinityforge.active;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared per-(player, skillId) cooldown tracker (2026-07-25 gather-rework-active-framework §3 component 3)
 * replacing every active skill's private {@code Map<UUID, Long>} (the pre-framework pattern
 * {@code HasteActiveMiningListener} used). Bukkit-free and unit-testable with an injected clock, same
 * "pure helper + thin Bukkit listener" split as {@code MiningGimmickPolicy}.
 *
 * <p>Not persisted (session-only, same as the pre-framework map it replaces — design doc §3 component 3
 * "永続はまずセッション"): a server restart clears every cooldown.
 */
public final class CooldownManager {

    private final Map<UUID, Map<String, Long>> lastUseMillisByPlayer = new ConcurrentHashMap<>();

    /**
     * Atomically checks-and-consumes: if {@code cooldownMillis} have elapsed since the player's last use
     * of {@code skillId} (or they never used it), records {@code nowMillis} as the new last-use and
     * returns {@code true}; otherwise leaves the recorded last-use untouched and returns {@code false}.
     */
    public boolean tryConsume(UUID playerId, String skillId, long cooldownMillis, long nowMillis) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(skillId, "skillId");
        Map<String, Long> perSkill = lastUseMillisByPlayer.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());
        boolean[] consumed = {false};
        perSkill.compute(skillId, (id, lastUse) -> {
            if (lastUse != null && (nowMillis - lastUse) < cooldownMillis) {
                return lastUse; // still on cooldown: leave untouched, do not consume.
            }
            consumed[0] = true;
            return nowMillis;
        });
        return consumed[0];
    }

    /** Milliseconds remaining before {@code skillId} is off cooldown for this player. Never negative. */
    public long remainingMillis(UUID playerId, String skillId, long cooldownMillis, long nowMillis) {
        Map<String, Long> perSkill = lastUseMillisByPlayer.get(playerId);
        if (perSkill == null) {
            return 0L;
        }
        Long lastUse = perSkill.get(skillId);
        if (lastUse == null) {
            return 0L;
        }
        long elapsed = nowMillis - lastUse;
        return Math.max(0L, cooldownMillis - elapsed);
    }

    /**
     * そのプレイヤーが {@code skillId} を一度でも使ったか(=CTが走っている可能性があるか)。
     * 常時表示({@link ActiveCooldownDisplay})が、未使用のスキルに対して重いステータス集計を
     * 走らせないための軽量な事前判定。
     */
    public boolean hasRecord(UUID playerId, String skillId) {
        Map<String, Long> perSkill = lastUseMillisByPlayer.get(playerId);
        return perSkill != null && perSkill.containsKey(skillId);
    }

    /** Drops every cooldown entry for {@code playerId} (call on {@code PlayerQuitEvent}, unbounded-map guard). */
    public void clear(UUID playerId) {
        lastUseMillisByPlayer.remove(playerId);
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
