package com.trinityforge.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * サーバ間チャットの表示行 ({@link ChatFormat}) の回帰テスト (2026-08-15)。
 *
 * <p>固定しているのは「起動して喋ってみるまで気付けない壊れ方」だけ:
 * プレイヤーが打った MiniMessage 記法が解釈されること、書式設定のミスで発言そのものが消えること。
 */
class ChatFormatTest {

    private static final String DEFAULT_FORMAT =
            "<gray>【</gray><aqua>%server%</aqua><gray>】</gray>"
                    + "<white>%player%</white><gray>:</gray> <white>%message%</white>";

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static void failOnWarning(String warning) {
        throw new AssertionError("正しい書式なのに警告が出た: " + warning);
    }

    @Test
    @DisplayName("既定の書式はサーバ名・発言者・本文をこの順で並べる")
    void render_defaultFormat_ordersServerPlayerMessage() {
        Component rendered = ChatFormat.render(
                DEFAULT_FORMAT, "資源", "Klee319", Component.text("hello"), ChatFormatTest::failOnWarning);

        assertEquals("【資源】Klee319: hello", plain(rendered));
    }

    /**
     * <b>これが一番大事な回帰。</b>本文をプレースホルダ置換で流し込むと、プレイヤーが打った
     * {@code <red>} が色指定として解釈されるだけでなく、{@code <click:run_command:...>} を書けば
     * 「押すとコマンドが走る行」を他人に配れてしまう。本文は Component として差し込むこと。
     */
    @Test
    @DisplayName("発言者が打った MiniMessage 記法は解釈せずそのまま出す")
    void render_playerTypedMiniMessage_isNotInterpreted() {
        Component rendered = ChatFormat.render(
                DEFAULT_FORMAT, "メイン", "Klee319",
                Component.text("<red>あかい</red> <click:run_command:'/op me'>押して</click>"),
                ChatFormatTest::failOnWarning);

        String text = plain(rendered);
        assertTrue(text.contains("<red>あかい</red>"), "記法が消えている: " + text);
        assertTrue(text.contains("<click:run_command:'/op me'>押して</click>"), text);
        // 解釈されていたらタグは平文から消え、代わりにクリックイベントが付く。
        assertNull(rendered.clickEvent(), "本文の記法からクリックイベントが作られた");
    }

    @Test
    @DisplayName("発言者名も解釈しない（Bedrock のドット付き MCID もそのまま）")
    void render_playerName_isNotInterpreted() {
        Component rendered = ChatFormat.render(
                DEFAULT_FORMAT, "開発", "<bold>.Klee3192821", Component.text("hi"), warning -> { });

        assertEquals("【開発】<bold>.Klee3192821: hi", plain(rendered));
    }

    @Test
    @DisplayName("プレースホルダが欠けた書式でも発言は必ず届く")
    void render_missingPlaceholder_stillDeliversMessage() {
        List<String> warnings = new ArrayList<>();
        Component rendered = ChatFormat.render(
                "【%server%】だけ書いてある", "資源", "Klee319", Component.text("hello"), warnings::add);

        assertTrue(plain(rendered).contains("hello"), plain(rendered));
        assertTrue(plain(rendered).contains("Klee319"), plain(rendered));
        assertEquals(1, warnings.size(), "書式ミスを黙って飲み込んでいる");
    }

    @Test
    @DisplayName("プレースホルダの順序は自由（%message% が先でもよい）")
    void render_reversedPlaceholders_isAccepted() {
        Component rendered = ChatFormat.render(
                "%message% -- %player%", "資源", "Klee319", Component.text("hello"),
                ChatFormatTest::failOnWarning);

        assertEquals("hello -- Klee319", plain(rendered));
    }

    /**
     * 書式を文字列として切り貼りすると {@code <white>%player%</white>} が
     * {@code ...<white>} と {@code </white>...} に割れ、MiniMessage が対応の無い閉じタグを
     * <b>そのまま文字として出す</b>（{@code Klee319</white>: hello}）。
     * タグ解決に任せている限りこうならない、を固定する。
     */
    @Test
    @DisplayName("プレースホルダを挟むタグが分断されて閉じタグが素で出ない")
    void render_tagsWrappingPlaceholder_doNotLeakAsText() {
        Component rendered = ChatFormat.render(
                "<white>%player%</white><gray>:</gray> <white>%message%</white>",
                "資源", "Klee319", Component.text("hello"), ChatFormatTest::failOnWarning);

        assertEquals("Klee319: hello", plain(rendered));
    }

    @Test
    @DisplayName("飾りの MiniMessage 記法が壊れていても例外にしない")
    void render_brokenDecorationTag_doesNotThrow() {
        List<String> warnings = new ArrayList<>();
        Component rendered = ChatFormat.render(
                "<notatag:>%player%: %message%", "資源", "Klee319", Component.text("hello"), warnings::add);

        assertTrue(plain(rendered).contains("hello"), plain(rendered));
    }

    @Test
    @DisplayName("書式が null でも発言は必ず届く")
    void render_nullFormat_stillDeliversMessage() {
        List<String> warnings = new ArrayList<>();
        Component rendered = ChatFormat.render(
                null, "資源", "Klee319", Component.text("hello"), warnings::add);

        assertTrue(plain(rendered).contains("hello"), plain(rendered));
        assertEquals(1, warnings.size());
    }

    @Test
    @DisplayName("本文は自分の書式を保ったまま差し込まれる（飾りの色に上書きされない）")
    void render_messageKeepsItsOwnStyle() {
        Component message = Component.text("hello", NamedTextColor.GREEN);
        Component rendered = ChatFormat.render(
                "<red>[%server%] %player%: %message%</red>", "資源", "Klee319", message,
                ChatFormatTest::failOnWarning);

        assertTrue(plain(rendered).startsWith("[資源] Klee319: hello"), plain(rendered));
        assertEquals(NamedTextColor.GREEN, colorOf(rendered, "hello"),
                "飾りの色が本文を上書きした");
    }

    /**
     * 出荷 {@code network.yml} の {@code chat.format} をそのまま通して固定する (2026-08-20 W-177)。
     *
     * <p>この yml は設定エディタに画面が無いので、直すときは必ずここが唯一の書き換え先になる。
     * そして {@link ChatFormat#render} は書式が壊れていても<b>既定の書式へ黙って落ちる</b>ので、
     * 実サーバでは「設定したのに前の見た目のまま」= ロールバックしたようにしか見えない。
     * 出荷値を実際に render して、既定へ落ちていないことを確かめる。
     */
    @Test
    @DisplayName("出荷 network.yml の chat.format は既定へ落ちずにそのまま描画される")
    void shippedNetworkFormat_rendersWithoutFallingBack() throws Exception {
        java.nio.file.Path file = java.nio.file.Path.of("src/main/resources/network.yml");
        assertTrue(java.nio.file.Files.isRegularFile(file),
                "出荷 yml が見つからない: " + file.toAbsolutePath());
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.loadFromString(java.nio.file.Files.readString(file));
        String format = cfg.getString("chat.format");
        assertNotNull(format, "network.yml に chat.format が無い");

        List<String> warnings = new ArrayList<>();
        Component rendered = ChatFormat.render(
                format, "資源", "Klee319", Component.text("hello"), warnings::add);

        assertTrue(warnings.isEmpty(), "出荷書式で警告が出た: " + warnings);
        assertEquals("【資源】Klee319: hello", plain(rendered),
                "出荷書式の並びが変わった(プレースホルダの欠落やタイポなら既定へ落ちている)");
    }

    /** 木を辿って、指定の文字列を持つ最初のノードの色を返す。 */
    private static TextColor colorOf(Component component, String content) {
        if (component instanceof TextComponent text && content.equals(text.content())) {
            return component.color();
        }
        for (Component child : component.children()) {
            TextColor found = colorOf(child, content);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
