package com.trinityforge.progression.event;

import java.util.UUID;

/**
 * レベル上昇の受け口。{@code NativeProgressionService} は純粋なドメイン層で Bukkit に依存しないため、
 * 「レベルが上がった」という事実だけをここへ流し、Bukkit イベントへの変換
 * （メインスレッドへの寄せ・1 レベルずつの分解）は {@link BukkitSkillLevelUpDispatcher} が担う。
 *
 * <p>この分離が無いと、進行サービスの単体テストが Bukkit サーバ無しで動かなくなる。
 */
@FunctionalInterface
public interface SkillLevelUpSink {

    /** 何もしない受け口（未配線時の既定）。 */
    SkillLevelUpSink NOOP = (playerId, skillId, oldLevel, newLevel) -> { };

    /**
     * 1 回の EXP 付与で確定したレベル上昇を、<b>まとめて 1 回</b>通知する。
     * 到達レベルごとの分解は受け手側の責務。
     *
     * @param skillId 大文字へ正規化済みのスキル ID
     */
    void onSkillLevelUp(UUID playerId, String skillId, int oldLevel, int newLevel);
}
