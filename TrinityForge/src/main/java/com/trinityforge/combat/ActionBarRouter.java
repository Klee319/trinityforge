package com.trinityforge.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * プレイヤーごとのアクションバーを一元的に調停する。
 *
 * <p><b>なぜ調停役が要るか</b>: 本体には {@code sendActionBar} の呼び出しが 40 箇所を超える。
 * とくに戦闘スキルの EXP 獲得表示（{@link com.trinityforge.progression.SkillExpFeedbackService}）は
 * 設定次第で獲得のたびに発火し、ダンジョン戦闘中は常時出続ける。ここへ敵の技の「予告」
 * （0.25 秒ごとに更新される残り時間バー）を同じ場所へ重ねると、呼び出し元がバラバラなままでは
 * どちらが出るかが呼び出し順の運任せになり、両方とも読めなくなる。優先度を 1 箇所に集約し、
 * 「予告が走っている間は他を捨てる」「捨てたものは溜めて後から出さない」を保証するのがこのクラスの役目。
 *
 * <p>スレッドはメインスレッド前提（同期不要）。
 */
public final class ActionBarRouter {

    /** 優先度。上（先に書いたもの）ほど強い。 */
    public enum Priority {
        TELEGRAPH_LETHAL,
        TELEGRAPH,
        NOTICE,
        WARNING,
        SKILL_EXP,
        AFK
    }

    /** 期限切れ予告の保険掃除用の猶予（ミリ秒）。術者消滅などで {@code telegraphEnd} が来ない事故に備える。 */
    private static final long TELEGRAPH_EXPIRY_GRACE_MILLIS = 1000L;

    /** {@code notice} が下位表示を止める窓（ミリ秒）。 */
    private static final long NOTICE_WINDOW_MILLIS = 800L;

    private static final int BAR_SEGMENTS = 5;
    private static final char BAR_FILLED = '▮'; // ▮
    private static final char BAR_EMPTY = '▯';  // ▯

    private final LongSupplier nowMillis;
    private final BiConsumer<Player, Component> sink;
    private final Map<UUID, PlayerState> states = new HashMap<>();

    public ActionBarRouter() {
        this(System::currentTimeMillis, Player::sendActionBar);
    }

    public ActionBarRouter(LongSupplier nowMillis, BiConsumer<Player, Component> sink) {
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * 予告を登録／更新し、その場でこの viewer の表示を再計算して 1 回送る。
     *
     * @param key            予告の識別子（術者UUID+技ID など、呼び手が一意に決める）
     * @param lethal         致命予告か
     * @param resolveAtMillis 着弾予定時刻（{@link #nowMillis} と同じ時刻基準）
     * @param line           表示行（{@link #telegraphLine} で組み立てたものを渡す想定）
     */
    public void telegraphUpdate(Player viewer, String key, boolean lethal, long resolveAtMillis, Component line) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(line, "line");
        PlayerState state = states.computeIfAbsent(viewer.getUniqueId(), id -> new PlayerState());
        state.telegraphs.put(key, new Telegraph(lethal, resolveAtMillis, line));
        recomputeAndSend(viewer, state);
    }

    /**
     * 予告の登録を外す。その viewer の予告がこれで 0 本になったら {@link Component#empty()} を
     * 1 回だけ sink へ送って表示を消す（2026-09-04 追加指示8）。<b>消さないとクライアントに
     * 最終フレームが2〜3秒残る</b> —— 予告が終わっても次の下位表示（EXP等）が来るまで
     * アクションバーが「動かなくなった予告」のまま固まって見える。
     */
    public void telegraphEnd(Player viewer, String key) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(key, "key");
        PlayerState state = states.get(viewer.getUniqueId());
        if (state == null) {
            return;
        }
        boolean removed = state.telegraphs.remove(key) != null;
        if (removed && state.telegraphs.isEmpty()) {
            sink.accept(viewer, Component.empty());
        }
    }

    /**
     * 「詠唱中断」「詠唱不発」「体勢を崩した」などの通知。
     * 予告が走っていれば捨てる（予告のほうが強い）。それ以外は送信し、以後 800ms は下位（WARNING 以下）を通さない。
     */
    public void notice(Player viewer, Component text) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(text, "text");
        UUID id = viewer.getUniqueId();
        if (hasActiveTelegraph(id)) {
            return;
        }
        PlayerState state = states.computeIfAbsent(id, k -> new PlayerState());
        state.noticeUntil = nowMillis.getAsLong() + NOTICE_WINDOW_MILLIS;
        sink.accept(viewer, text);
    }

    /**
     * 単発表示。予告が走っている、または NOTICE の 800ms 窓の中なら捨てて {@code false}
     * （溜めて後から出さない）。{@code priority} が TELEGRAPH 系なら
     * {@link IllegalArgumentException}（予告は {@link #telegraphUpdate} を使う）。
     */
    public boolean send(Player viewer, Priority priority, Component text) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(text, "text");
        if (priority == Priority.TELEGRAPH_LETHAL || priority == Priority.TELEGRAPH) {
            throw new IllegalArgumentException("予告は telegraphUpdate を使う: " + priority);
        }
        UUID id = viewer.getUniqueId();
        if (hasActiveTelegraph(id)) {
            return false;
        }
        PlayerState state = states.get(id);
        if (state != null && priority.ordinal() > Priority.NOTICE.ordinal()
                && nowMillis.getAsLong() < state.noticeUntil) {
            return false;
        }
        sink.accept(viewer, text);
        return true;
    }

    /** ログアウト掃除用。そのプレイヤーの予告・通知窓の状態を破棄する。 */
    public void forget(UUID player) {
        states.remove(player);
    }

    private boolean hasActiveTelegraph(UUID id) {
        PlayerState state = states.get(id);
        if (state == null) {
            return false;
        }
        purgeExpired(state, nowMillis.getAsLong());
        return !state.telegraphs.isEmpty();
    }

    private void purgeExpired(PlayerState state, long now) {
        state.telegraphs.entrySet()
                .removeIf(e -> now > e.getValue().resolveAtMillis() + TELEGRAPH_EXPIRY_GRACE_MILLIS);
    }

    private void recomputeAndSend(Player viewer, PlayerState state) {
        long now = nowMillis.getAsLong();
        purgeExpired(state, now);
        if (state.telegraphs.isEmpty()) {
            return;
        }
        Telegraph winner = pickWinner(state);
        int others = state.telegraphs.size() - 1;
        Component line = winner.line();
        if (others > 0) {
            line = line.append(Component.text(" +" + others));
        }
        sink.accept(viewer, line);
    }

    /** 致命があれば致命の中から、無ければ全体から、着弾が近い方を選ぶ。 */
    private Telegraph pickWinner(PlayerState state) {
        boolean anyLethal = state.telegraphs.values().stream().anyMatch(Telegraph::lethal);
        Telegraph best = null;
        for (Telegraph t : state.telegraphs.values()) {
            if (anyLethal && !t.lethal()) {
                continue;
            }
            if (best == null || t.resolveAtMillis() < best.resolveAtMillis()) {
                best = t;
            }
        }
        return best;
    }

    /**
     * 予告行の整形（static・純関数）。{@code displayNameMiniMessage} を MiniMessage として解釈し、
     * 空なら {@code fallbackId} を素の文字で使う。その色（解釈結果の {@code component.color()}、
     * 無ければ白）でバーを続ける。バーは5段固定（{@code ▮}×filled + {@code ▯}×(5-filled)）。
     * {@code filled = clamp(ceil(remaining/total*5), 1, 5)}（残りがあるうちは最低1）。
     * {@code totalMillis<=0} なら5段全部埋める。
     */
    public static Component telegraphLine(String displayNameMiniMessage, String fallbackId,
                                          long remainingMillis, long totalMillis) {
        Component nameComponent = (displayNameMiniMessage == null || displayNameMiniMessage.isBlank())
                ? Component.text(fallbackId)
                : MiniMessage.miniMessage().deserialize(displayNameMiniMessage);
        TextColor color = resolveColor(nameComponent);
        if (color == null) {
            color = NamedTextColor.WHITE;
        }
        int filled = filledSegments(remainingMillis, totalMillis);
        StringBuilder bar = new StringBuilder(BAR_SEGMENTS);
        for (int i = 0; i < BAR_SEGMENTS; i++) {
            bar.append(i < filled ? BAR_FILLED : BAR_EMPTY);
        }
        return nameComponent.append(Component.text(" " + bar, color));
    }

    /**
     * 予告行に行動語（機構2026-09-04追加指示5）を前置した版。
     * {@code [横へ] 貫通光 ▮▮▮▯▯ 1.3s} の形。既存の4引数版はテスト互換のため残す。
     */
    public static Component telegraphLine(String responseWord, String displayNameMiniMessage, String fallbackId,
                                          long remainingMillis, long totalMillis) {
        Component inner = telegraphLine(displayNameMiniMessage, fallbackId, remainingMillis, totalMillis);
        double seconds = Math.max(0L, remainingMillis) / 1000.0;
        String secondsText = String.format(java.util.Locale.ROOT, "%.1fs", seconds);
        return Component.text("[" + responseWord + "] ").append(inner).append(Component.text(" " + secondsText));
    }

    /**
     * {@code component.color()} が null のとき、子要素を深さ優先で辿って最初に見つかった色を使う
     * （2026-09-04 修正）。{@code <light_purple><bold>崩落の詠唱</bold></light_purple>} のように
     * root が空で子が色を持つ場合に白へ落ちないようにするため。
     */
    private static TextColor resolveColor(Component component) {
        TextColor own = component.color();
        if (own != null) {
            return own;
        }
        for (Component child : component.children()) {
            TextColor found = resolveColor(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static int filledSegments(long remainingMillis, long totalMillis) {
        if (totalMillis <= 0) {
            return BAR_SEGMENTS;
        }
        double ratio = (double) remainingMillis / (double) totalMillis;
        int filled = (int) Math.ceil(ratio * BAR_SEGMENTS);
        return Math.max(1, Math.min(BAR_SEGMENTS, filled));
    }

    /** 1プレイヤー分の状態: キー付きの予告一覧と、直近 notice の失効時刻。 */
    private static final class PlayerState {
        private final Map<String, Telegraph> telegraphs = new LinkedHashMap<>();
        private long noticeUntil = Long.MIN_VALUE;
    }

    private record Telegraph(boolean lethal, long resolveAtMillis, Component line) {
    }
}
