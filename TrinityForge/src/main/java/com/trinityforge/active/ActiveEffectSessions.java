package com.trinityforge.active;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「いま効果が乗っている {@link ActiveSkill}」のプレイヤー単位の台帳
 * (2026-08-18 ユーザー確定要件「該当のツールから持ち替えると効果が強制終了する」)。
 *
 * <p>{@link ActiveSkill} の実装は状態を持たない規約({@link ActiveSkill} クラスjavadoc)なので、
 * 「誰にいつまで何が乗っているか」は {@link CooldownManager} と同じく基盤側が持つ。
 * 開くのは {@link ActivationDispatcher}(発動成功時)、読むのは {@link ToolBoundEffectListener}。
 *
 * <p><b>持続時間を持たせている理由</b>: 持ち替えを検知するイベント
 * ({@code PlayerItemHeldEvent} 等)は効果が切れた後も飛び続けるので、期限を持たないと
 * 「もう切れている効果」に対して {@link ActiveSkill#cancelEffect} を呼び続けることになる。
 * cancelEffect は「自分が付けた効果か」を確認してから剥がす実装が前提だが、
 * 期限切れを台帳側で落としておけば無駄な照合そのものが起きない。
 */
public final class ActiveEffectSessions {

    /**
     * 1件のセッション。
     *
     * @param skillId      {@link ActiveSkill#id()}
     * @param tier         発動時に解決した段階({@link ActiveSkill#cancelEffect} が効果量の照合に使う)
     * @param endsAtMillis 効果が自然に切れる時刻(epoch millis)
     */
    public record Session(String skillId, int tier, long endsAtMillis) {
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /** 発動成功時に開く(同じプレイヤーの前のセッションは上書きする)。 */
    public void open(UUID playerId, String skillId, int tier, long endsAtMillis) {
        if (playerId == null || skillId == null) {
            return;
        }
        sessions.put(playerId, new Session(skillId, tier, endsAtMillis));
    }

    /**
     * まだ効果が続いているセッション。期限切れのものは<b>その場で捨てて</b>空を返すので、
     * Mapがサーバ稼働時間ぶん伸びることはない。
     */
    public Optional<Session> active(UUID playerId, long nowMillis) {
        if (playerId == null) {
            return Optional.empty();
        }
        Session session = sessions.get(playerId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.endsAtMillis() <= nowMillis) {
            sessions.remove(playerId, session);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    /** 強制終了・退出・死亡時に閉じる。 */
    public void close(UUID playerId) {
        if (playerId != null) {
            sessions.remove(playerId);
        }
    }
}
