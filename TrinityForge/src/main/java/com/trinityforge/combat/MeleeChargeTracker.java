package com.trinityforge.combat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B2 レビュー修正(2026-07-25, HIGH指摘1): {@code Player#getAttackCooldown()} ではなく、TF自身が
 * 「最後に近接攻撃した server tick」をプレイヤーごとに記録する軽量トラッカー。
 * 経過tickは呼び出し側が {@code Bukkit.getCurrentTick()} との差分から求め、{@link MeleeChargeMultiplier}
 * の純粋関数へ渡す。Bukkit-free(UUIDとintのみ)なので単体テスト可能。
 *
 * <p>Combat イベントは常にBukkitメインスレッドで同期実行されるため外部同期は不要——{@link
 * ConcurrentHashMap} は{@link AttackerTargetCooldown 同種の既存パターン}に倣った防御的な選択であり、
 * スレッド間の正しさのためではない。
 *
 * <p>リーク防止: プレイヤーがログアウトしたら {@link #forget(UUID)} で記録を破棄すること
 * ({@code CombatListener} の {@code PlayerQuitEvent} ハンドラから呼ばれる;
 * {@code SkillExpFeedbackService#onQuit} と同じ前例に倣う)。
 */
public final class MeleeChargeTracker {

    /** 記録なし(このセッションで初回攻撃 or ログイン直後)を表すセンチネル。フルチャージ扱い。 */
    static final int NEVER_ATTACKED = Integer.MAX_VALUE;

    private final Map<UUID, Integer> lastAttackTick = new ConcurrentHashMap<>();

    /**
     * {@code currentTick} 時点での、{@code playerId} の前回近接攻撃からの経過tick数。記録が無ければ
     * {@link #NEVER_ATTACKED}(フルチャージ扱い)。tickカウンタが記録済みの値より後退していた場合
     * (サーバ再起動等の異常系)も安全側に倒してフルチャージ扱いにする。
     */
    public int elapsedTicksSince(UUID playerId, int currentTick) {
        Integer last = lastAttackTick.get(playerId);
        if (last == null) {
            return NEVER_ATTACKED;
        }
        int elapsed = currentTick - last;
        return elapsed < 0 ? NEVER_ATTACKED : elapsed;
    }

    /** {@code playerId} が {@code currentTick} に近接攻撃したことを記録する(次回の経過tick計算の起点)。 */
    public void recordAttack(UUID playerId, int currentTick) {
        lastAttackTick.put(playerId, currentTick);
    }

    /** ログアウト時にプレイヤーの記録を破棄する(メモリリーク防止)。 */
    public void forget(UUID playerId) {
        lastAttackTick.remove(playerId);
    }

    /** テスト/診断用フック: 現在追跡中のプレイヤー数。 */
    public int trackedPlayerCount() {
        return lastAttackTick.size();
    }
}
