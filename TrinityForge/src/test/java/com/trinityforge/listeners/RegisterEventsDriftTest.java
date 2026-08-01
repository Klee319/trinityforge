package com.trinityforge.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「config も yml も editor UI も揃っているのに {@code registerEvents} だけ無くて無言で死ぬ」を
 * 機械的に検出するドリフトテスト(2026-08-01 レビューHIGH指摘2)。
 *
 * <p><b>なぜ要るか</b>: このコードベースは同じ形の事故を繰り返している(直近では
 * {@code FarmingGimmickListener} 新設時に配線漏れが起きかけた)。テストが3000件超あっても、
 * 「そもそも登録されていないListener」を検出するテストが1本も無かったため、config側だけ
 * 完璧に作っても実サーバでは何も起きないまま3324件緑という事故を許してしまう。
 *
 * <p><b>実装方針</b>: リフレクションや Bukkit 起動(サーバ実体・PluginManager)は使わず、
 * {@code TrinityForge.java} と {@code listeners/} 配下の {@code .java} を<b>テキストとして</b>読む
 * (このリポジトリの {@code ShippedStatCapsDriftTest} / {@code RetiredStatKeyDriftTest} /
 * {@code SpellBreakMarkerDriftTest} と同じ流儀)。
 *
 * <h2>この検出方式の限界(意図的に把握して割り切っている)</h2>
 * 「{@code TrinityForge.java} 本文にクラス名が単語境界付きで1度も現れない」ことだけを見る。
 * つまり <b>本当に一度も参照されていない Listener</b> は確実に捕まえるが、
 * 「import はされている/他クラスの依存として渡されているだけで、その Listener 自身は
 * {@code registerEvents} されていない」という中間状態までは判別しない(それには
 * {@code registerEvents(...)} の引数を1つずつ型解決する本格的なパーサが要り、このリポジトリの
 * 他ドリフトテストの水準を超える)。2026-08-01 時点でこのリポジトリの
 * {@code com.trinityforge.listeners} 配下の全 Listener 実装は実際に
 * {@code new ClassName(...)} または対応するフィールド経由で {@code registerEvents} まで
 * 到達していることを1件ずつ確認済み(このテストを書く過程での手動監査)。
 *
 * <p>このテストが落ちたら、対象クラスを {@code TrinityForge.java} の {@code onEnable} 系メソッド内で
 * {@code getServer().getPluginManager().registerEvents(...)} に実際に足すこと
 * (単に import するだけ・他クラスのコンストラクタに渡すだけでは緑にならない)。
 */
class RegisterEventsDriftTest {

    private static final Path LISTENERS_DIR =
            Path.of("src/main/java/com/trinityforge/listeners");
    private static final Path TRINITY_FORGE_JAVA =
            Path.of("src/main/java/com/trinityforge/TrinityForge.java");

    /**
     * public トップレベルclass宣言を検出し、その直後({@code {} まで)に {@code implements} と
     * 単語境界付きの {@code Listener} が両方現れるものだけを対象にする
     * ({@code org.bukkit.event.Listener} を暗黙importで実装している場合のみ該当。
     * このリポジトリ内に完全修飾 {@code implements org.bukkit.event.Listener} で書いたクラスは無い
     * ことを確認済み)。
     */
    private static final Pattern CLASS_DECL =
            Pattern.compile("public\\s+(?:abstract\\s+)?(?:final\\s+)?class\\s+(\\w+)");
    private static final Pattern LISTENER_WORD = Pattern.compile("\\bListener\\b");

    /**
     * 意図的に {@code TrinityForge.java} へ配線しない Listener 実装の許可リスト。
     * <b>1件追加するたびに、なぜ配線しなくてよいかを日本語コメントで書くこと。</b>
     * 判断が付かない場合はここに足さず、配線するか調査を報告すること。
     *
     * <p>2026-08-01 時点: 空。{@code com.trinityforge.listeners} 配下の Listener 実装は
     * 全件 {@code TrinityForge.java} から実際に {@code registerEvents} されている
     * (手動監査で71クラス全件を確認済み)。将来ここに何か追加する場合、そのクラスが
     * 本当に「意図して登録しない」のか「単に配線を忘れている」のかを先に切り分けること
     * (このテストの目的そのものが後者の検出なので、安易にここへ逃がすと本末転倒になる)。
     */
    private static final Set<String> ALLOWED_UNREGISTERED = Set.of();

    /** {@code file} 内の public トップレベル class が Listener 実装なら、そのクラス名を返す。 */
    private static Optional<String> listenerClassNameIn(String source) {
        Matcher classMatcher = CLASS_DECL.matcher(source);
        while (classMatcher.find()) {
            int braceIdx = source.indexOf('{', classMatcher.end());
            if (braceIdx < 0) {
                continue;
            }
            String header = source.substring(classMatcher.end(), braceIdx);
            if (header.contains("implements") && LISTENER_WORD.matcher(header).find()) {
                return Optional.of(classMatcher.group(1));
            }
        }
        return Optional.empty();
    }

    private static List<String> listAllListenerClassNames() throws IOException {
        assertTrue(Files.isDirectory(LISTENERS_DIR),
                "listeners ディレクトリが見つからない(作業ディレクトリがTrinityForge/でない可能性): "
                        + LISTENERS_DIR.toAbsolutePath());
        List<String> names = new ArrayList<>();
        try (var files = Files.list(LISTENERS_DIR)) {
            for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                listenerClassNameIn(source).ifPresent(names::add);
            }
        }
        return names;
    }

    @Test
    @DisplayName("com.trinityforge.listeners の全Listener実装は TrinityForge.java から参照されている"
            + "(参照ゼロ=registerEvents漏れの疑い)")
    void everyListenerImplementationIsReferencedFromTrinityForgeJava() throws IOException {
        List<String> listenerClasses = listAllListenerClassNames();
        assertTrue(listenerClasses.size() >= 50,
                "listeners配下のListener実装が" + listenerClasses.size() + "件しか検出できなかった。"
                        + "ディレクトリ走査か正規表現が壊れている可能性がある(空振り防止アンカー)。");

        assertTrue(Files.exists(TRINITY_FORGE_JAVA),
                "TrinityForge.java が見つからない: " + TRINITY_FORGE_JAVA.toAbsolutePath());
        String trinityForgeSource = Files.readString(TRINITY_FORGE_JAVA, StandardCharsets.UTF_8);

        List<String> violations = new ArrayList<>();
        for (String className : listenerClasses) {
            if (ALLOWED_UNREGISTERED.contains(className)) {
                continue;
            }
            boolean referenced = Pattern.compile("\\b" + Pattern.quote(className) + "\\b")
                    .matcher(trinityForgeSource).find();
            if (!referenced) {
                violations.add(className);
            }
        }

        assertTrue(violations.isEmpty(),
                "以下のListener実装がTrinityForge.java本文のどこにも現れない"
                        + "(import/コンストラクタ呼び出し/フィールド宣言のいずれも無い):\n  "
                        + String.join("\n  ", violations)
                        + "\nTrinityForge.java の該当 onEnable 系メソッド内で"
                        + " getServer().getPluginManager().registerEvents(...) に実際に足すこと。"
                        + "意図的に配線しないなら RegisterEventsDriftTest.ALLOWED_UNREGISTERED へ"
                        + "理由コメント付きで追加すること。");
    }

    /** 許可リストの腐敗防止: 存在しないクラス名を許可リストに残しても検出できないため、実在を固定する。 */
    @Test
    @DisplayName("ALLOWED_UNREGISTERED のエントリは実在するListener実装だけである(死んだ許可は残さない)")
    void allowListEntriesReferToRealListenerClasses() throws IOException {
        Set<String> actual = new TreeSet<>(listAllListenerClassNames());
        for (String allowed : ALLOWED_UNREGISTERED) {
            assertFalse(!actual.contains(allowed),
                    "ALLOWED_UNREGISTERED の '" + allowed + "' は listeners 配下に実在しない"
                            + "(リネーム/削除されたのに許可リストだけ残っている)。除去すること。");
        }
    }
}
