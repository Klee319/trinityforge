package com.trinityforge.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 設定ファイルに書かれた「表示名 / 説明文」を描画するための共通ヘルパー(2026-07-29)。
 *
 * <p>アイテムカタログの {@code display-name} / {@code lore} は以前から MiniMessage で書けたのに、
 * アチーブメントの表示名やロールの {@code label} / {@code description} は素のテキストとして
 * 描画されていた。同じ「表示名と説明」でも画面ごとに書ける記法が違うと、コンフィグエディタ側で
 * 同じ入力UI(色付きリッチテキスト欄)を出せない。ここへ集約して記法を揃える。</p>
 */
public final class MiniText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private MiniText() {
    }

    /**
     * MiniMessage として描画する。記法が壊れていても例外は投げず、その行を素の文字として出す
     * (GUI 全体が落ちるより、1行だけ生テキストで出るほうが原因を追える)。
     *
     * @param fallback 色指定を持たないテキストへ与える色。呼び出し側が状態色(達成済み=緑 等)を
     *                 持っているときに渡す。{@code colorIfAbsent} なので、設定側で色を書いた
     *                 テキストはその色を保つ。{@code null} なら色を触らない。
     */
    public static Component render(String raw, NamedTextColor fallback) {
        if (raw == null) {
            return Component.empty();
        }
        Component component;
        try {
            component = MINI.deserialize(raw);
        } catch (RuntimeException ex) {
            component = Component.text(raw);
        }
        if (fallback != null) {
            component = component.colorIfAbsent(fallback);
        }
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 文字列連結・チャット1行などで使うための、タグを落とした素のテキスト。
     * MiniMessage タグをそのまま連結すると {@code <gold>} が見えてしまうのを防ぐ。
     */
    public static String plain(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(MINI.deserialize(raw));
        } catch (RuntimeException ex) {
            return raw;
        }
    }
}
