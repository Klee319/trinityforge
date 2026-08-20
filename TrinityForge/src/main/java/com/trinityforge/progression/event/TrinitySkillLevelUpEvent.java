package com.trinityforge.progression.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Objects;

/**
 * TrinityForge のスキルレベルが上がったときに発火する通知イベント。
 * 外部プラグイン（節目レベルの全体アナウンス等）向けの公開 API。
 *
 * <p><b>1 レベルにつき 1 回発火する。</b> 1 回の EXP 付与で複数レベル上がった場合は、
 * 到達したレベルごとに（Lv9→Lv12 なら 9→10 / 10→11 / 11→12 の 3 回）発火する。
 * まとめて 1 回にすると「Lv10 の節目」を判定する側が範囲の跨ぎを自前で書く羽目になるため。
 * したがって {@code newLevel == oldLevel + 1} が常に成り立つ。
 *
 * <p><b>キャンセルできない。</b> 発火時点でレベルは既に永続化済みなので、
 * 止めても DB は戻らない。通知専用として設計している。
 *
 * <p><b>常にメインスレッドで発火する。</b> TF の EXP 付与は非同期スレッド
 * （{@code NativeExperienceDispatcher} の非同期タスク）から走るが、
 * 発火側がメインスレッドへ寄せてから呼ぶ（Bukkit の同期イベントを非同期文脈で
 * 発火すると {@code IllegalStateException} になる）。その分だけ実際のレベル上昇より
 * 最大 1 tick 遅れて届く。
 *
 * <p><b>{@code POWER} も来る。</b> POWER は各スキルのレベルアップから派生して伸びる
 * メタスキルだが、保存上は同じスキル表に載っているのでこのイベントでも通知される。
 * 通常スキルだけを扱いたい場合は {@code "POWER".equals(skillId())} で除外すること。
 */
public class TrinitySkillLevelUpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String skillId;
    private final int oldLevel;
    private final int newLevel;

    /**
     * @param player   レベルが上がったプレイヤー（オンラインであることが保証されている）
     * @param skillId  スキル ID。保存時と同じ<b>大文字</b>表記（{@code MINING} / {@code POWER} など）
     * @param oldLevel 上昇前のレベル
     * @param newLevel 上昇後のレベル（常に {@code oldLevel + 1}）
     */
    public TrinitySkillLevelUpEvent(Player player, String skillId, int oldLevel, int newLevel) {
        this.player = Objects.requireNonNull(player, "player");
        this.skillId = Objects.requireNonNull(skillId, "skillId");
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public Player getPlayer() {
        return player;
    }

    /** スキル ID（大文字）。 */
    public String getSkillId() {
        return skillId;
    }

    public int getOldLevel() {
        return oldLevel;
    }

    public int getNewLevel() {
        return newLevel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
