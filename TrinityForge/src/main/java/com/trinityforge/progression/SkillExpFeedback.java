package com.trinityforge.progression;

import java.util.UUID;

/**
 * スキルEXP付与時のプレイヤー向けフィードバック(ボスバー/アクションバー表示・レベルアップ通知)の受け口。
 *
 * <p>{@link NativeExperienceDispatcher#drain()} は<em>非同期</em>スレッドで走るため、実装側は
 * Bukkit API に触れる前に必ずメインスレッドへ再ディスパッチすること。付与自体を絶対に妨げないよう、
 * dispatcher 側はこの呼び出しを try/catch で保護する。
 */
@FunctionalInterface
public interface SkillExpFeedback {

    /**
     * @param playerId 対象プレイヤー
     * @param skillId  付与されたスキルid
     * @param amount   この1秒バッチで合算された獲得EXP量
     * @param result   付与結果(before/after/levelsChanged を含む)
     */
    void onExpGranted(UUID playerId, String skillId, double amount,
                      NativeProgressionService.GrantResult result);
}
