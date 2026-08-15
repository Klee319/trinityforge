package com.trinityforge.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.function.Consumer;

/**
 * サーバ間チャットの表示行を組み立てる (2026-08-15)。
 *
 * <p>Bukkit に触れない純粋な処理として切り出してある。表示の壊れ方は
 * 「サーバを起動して喋ってみる」以外に気付く手段が無いので、ここだけは単体テストで固定する。
 */
public final class ChatFormat {

    public static final String PLACEHOLDER_SERVER = "%server%";
    public static final String PLACEHOLDER_PLAYER = "%player%";
    public static final String PLACEHOLDER_MESSAGE = "%message%";

    // MiniMessage のタグ名は [a-z0-9_-] のみ。既存タグとぶつからないよう接頭辞を付ける。
    private static final String TAG_SERVER = "tf_server";
    private static final String TAG_PLAYER = "tf_player";
    private static final String TAG_MESSAGE = "tf_message";

    private static final String FALLBACK_TEMPLATE =
            "<gray>【</gray><tf_server><gray>】</gray><tf_player><gray>:</gray> <tf_message>";

    private ChatFormat() {
    }

    /**
     * {@code format} を組み立てて 1 行の Component にする。
     *
     * <p><b>本文と発言者名は文字列置換で流し込まない。</b>置換すると、プレイヤーが打った
     * {@code <red>} や {@code <click:run_command:...>} が MiniMessage として解釈されてしまう。
     * 色を勝手に使われるだけでなく、押すとコマンドが走る行を他人に配れてしまう。
     * 代わりに {@link Placeholder#component} で「解釈済みの Component」として差し込む。
     *
     * <p><b>書式を文字列として切り貼りしてはいけない。</b>{@code <white>%player%</white>} を
     * プレースホルダ位置で割ると {@code </white>} だけの断片ができ、MiniMessage は
     * 対応の無い閉じタグを<b>そのまま文字として出す</b>（{@code Klee319</white>: hello} になる）。
     * タグの解決に任せれば書式は最後まで 1 本のまま解析される。
     *
     * <p>{@code %player%} か {@code %message%} を欠いた書式でも、発言そのものは必ず届ける。
     * 表示の設定ミスで会話が消えるほうが害が大きい。プレースホルダの順序は自由。
     *
     * @param onWarning 書式が不正だったときの通知先（ログ出力を想定）
     */
    public static Component render(String format, String serverDisplay, String playerName,
                                   Component message, Consumer<String> onWarning) {
        TagResolver resolver = TagResolver.resolver(
                Placeholder.component(TAG_SERVER, Component.text(serverDisplay)),
                Placeholder.component(TAG_PLAYER, Component.text(playerName)),
                Placeholder.component(TAG_MESSAGE, message));

        String template = toTemplate(format);
        if (template == null) {
            onWarning.accept("chat.format には " + PLACEHOLDER_PLAYER + " と " + PLACEHOLDER_MESSAGE
                    + " が必要です。既定の書式で表示します: " + format);
            return MiniMessage.miniMessage().deserialize(FALLBACK_TEMPLATE, resolver);
        }

        try {
            return MiniMessage.miniMessage().deserialize(template, resolver);
        } catch (RuntimeException ex) {
            onWarning.accept("chat.format の MiniMessage 記法が不正です: " + ex.getMessage());
            return MiniMessage.miniMessage().deserialize(FALLBACK_TEMPLATE, resolver);
        }
    }

    /**
     * {@code %...%} 形式のプレースホルダを MiniMessage のタグへ直す。
     * 必須のものが欠けていれば null（＝既定の書式へ落とす）。
     */
    private static String toTemplate(String format) {
        if (format == null
                || !format.contains(PLACEHOLDER_PLAYER)
                || !format.contains(PLACEHOLDER_MESSAGE)) {
            return null;
        }
        return format
                .replace(PLACEHOLDER_SERVER, "<" + TAG_SERVER + ">")
                .replace(PLACEHOLDER_PLAYER, "<" + TAG_PLAYER + ">")
                .replace(PLACEHOLDER_MESSAGE, "<" + TAG_MESSAGE + ">");
    }
}
