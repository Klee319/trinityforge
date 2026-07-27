package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-07-27 再発防止 (4回連続再発): {@link PercentStatNormalize#RATE_KEYS} に登録されたキーは
 * {@code PlayerStatAggregator}/{@code PlayerCombatAggregate#totalOf} を経由した時点で既に
 * 「20 → 0.2」のフラクションへ矯正されている。にもかかわらず消費側がさらに {@code /100} する
 * 「二重縮小」バグが {@code BeekeepingListener} → {@code MiningGimmickListener} →
 * {@code FoodGimmickListener} → {@code GachaRateUp} と繰り返し見つかったため、同じ書き方を
 * 機械的に検出する静的テストを置く。
 *
 * <p><b>検出方針(2段)</b>: 完璧な検知は狙わない(過剰実装を避ける)。
 * <ol>
 *   <li>Tier 1 — 同一ファイル内: {@code xxx = ....totalOf(RATE_KEY)} で受けた変数を、その直後
 *       数行以内で {@code / 100} または {@code * 0.01} している典型パターン
 *       (Beekeeping/Mining/Foodの実際のバグ形)。</li>
 *   <li>Tier 2 — 別関数への委譲: RATE_KEY由来の値を他メソッド呼び出しの引数として渡し、その
 *       呼び出し先メソッド(コードベース全体から名前で検索)の本体がいずれかの引数を
 *       {@code / 100} している典型パターン({@code GachaRateUp.applyRateUp} の実際のバグ形 —
 *       引数名が {@code percentBonus} のように見えても、渡された値は既にフラクションだった)。</li>
 * </ol>
 *
 * <p>Tier 2 は「呼び出し先メソッド名」だけで突き合わせており、引数の位置までは検証しない
 * (簡易ヒューリスティック)。誤検知(false positive)が出た場合は {@link #ALLOWLIST} に
 * {@code "ファイル名#変数名"} 形式で追加し、なぜ二重縮小ではないかを必ずコメントで書くこと。
 */
class RateKeyDoubleShrinkGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path PERCENT_STAT_NORMALIZE = Path.of(
            "src/main/java/com/trinityforge/stats/PercentStatNormalize.java");

    /**
     * 誤検知の許可リスト。{@code "<ファイル名(拡張子込み)>#<変数名>"} 形式。
     * 例: {@code "SomeListener.java#chanceFraction"}。
     * 現時点では空 — 2026-07-27の全数調査で二重縮小は0件だったため、正規のケースは無い。
     */
    private static final Set<String> ALLOWLIST = Set.of();

    private static final Pattern RATE_KEY_LITERAL = Pattern.compile(
            "StatKeys\\.canonical\\(\\s*\"([^\"]+)\"\\s*\\)");

    private static final Pattern QUOTED_LITERAL = Pattern.compile("\"([^\"]+)\"");

    // "private static final String KEY = StatKeys.canonical("some-key");" 形の定数宣言。
    private static final Pattern CONST_DECL = Pattern.compile(
            "String\\s+(\\w+)\\s*=\\s*StatKeys\\.canonical\\(\\s*\"([^\"]+)\"\\s*\\)");

    // "varName = ....totalOf(ARG);" — 代入先の変数名と totalOf() の引数を1行から拾う。
    private static final Pattern TOTAL_OF_ASSIGN = Pattern.compile(
            "\\b(\\w+)\\s*=\\s*[^;]*\\.totalOf\\(([^)]*)\\)");

    // メソッド定義: "<修飾子/戻り値>* <名前>(<引数>) {" (constructor/if/for/whileは2語以上の
    // "戻り値 名前" が無いため原則マッチしない)。
    private static final Pattern METHOD_DEF = Pattern.compile(
            "(?:public|private|protected|static|final|synchronized|abstract|\\s)+"
                    + "[\\w<>\\[\\],.?]+\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

    @Test
    void rateKeyConsumersDoNotDivideByOneHundredAgain() throws IOException {
        Set<String> rateKeys = extractRateKeys();
        assertFalse(rateKeys.isEmpty(),
                "RATE_KEYS を読み取れなかった(正規表現がPercentStatNormalize.javaの構造とズレていないか確認): "
                        + PERCENT_STAT_NORMALIZE);

        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(MAIN_JAVA)) {
            javaFiles = walk.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }

        Map<Path, String> sources = new LinkedHashMap<>();
        for (Path file : javaFiles) {
            sources.put(file, Files.readString(file));
        }

        // ファイル横断: "定数名 -> canonicalキー" (StatKeys.canonical("gacha-rate-bonus") 形の宣言)。
        Map<String, String> constToKey = new LinkedHashMap<>();
        for (String src : sources.values()) {
            Matcher m = CONST_DECL.matcher(src);
            while (m.find()) {
                constToKey.put(m.group(1), canonical(m.group(2)));
            }
        }

        // ファイル横断: "メソッド名 -> /100または*0.01している引数を持つか" (Tier 2用)。
        Set<String> shrinkingMethodNames = collectShrinkingMethodNames(sources);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            Path file = entry.getKey();
            if (file.equals(PERCENT_STAT_NORMALIZE)) continue; // coerce() 自身が唯一の正規の /100 箇所
            String src = entry.getValue();
            String[] lines = src.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher assign = TOTAL_OF_ASSIGN.matcher(lines[i]);
                if (!assign.find()) continue;
                String varName = assign.group(1);
                String arg = assign.group(2).trim();
                String key = resolveKey(arg, constToKey);
                if (key == null || !rateKeys.contains(key)) continue;
                if (ALLOWLIST.contains(file.getFileName() + "#" + varName)) continue;

                // Tier 1: 直後数行以内で同じ変数を /100 または *0.01 していないか。
                Pattern reDivide = Pattern.compile(
                        "\\b" + Pattern.quote(varName) + "\\b\\s*(?:/\\s*100(?:\\.0)?\\b|\\*\\s*0\\.01\\b)");
                int end = Math.min(lines.length, i + 6);
                for (int j = i; j < end; j++) {
                    if (reDivide.matcher(lines[j]).find()) {
                        violations.add(file + ":" + (j + 1) + " [Tier1] 変数 '" + varName + "' はRATE_KEY '"
                                + key + "' 由来(totalOf経由で既にフラクション)なのに、さらに/100している疑いがある");
                    }
                }

                // Tier 2: この変数がどこかのメソッド呼び出しの引数として渡され、その呼び出し先メソッドの
                // 本体側に /100 する引数がある(=呼び出し先が「未矯正のpercent値」を期待している)疑い。
                Pattern reCallArg = Pattern.compile(
                        "\\b(\\w+)\\s*\\([^()]*\\b" + Pattern.quote(varName) + "\\b[^()]*\\)");
                for (int j = i; j < end; j++) {
                    Matcher call = reCallArg.matcher(lines[j]);
                    while (call.find()) {
                        String calledMethod = call.group(1);
                        if (calledMethod.equals("totalOf") || calledMethod.equals("canonical")) continue;
                        if (shrinkingMethodNames.contains(calledMethod)) {
                            violations.add(file + ":" + (j + 1) + " [Tier2] 変数 '" + varName + "' はRATE_KEY '"
                                    + key + "' 由来(既にフラクション)なのに、メソッド '" + calledMethod
                                    + "' に渡している。'" + calledMethod + "' の本体は引数を/100している箇所があり、"
                                    + "未矯正のpercent値を期待している可能性がある(GachaRateUp.applyRateUpと同型のバグ)");
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "RATE_KEYS由来の値の二重縮小(設定20が実効0.2%になるバグ)の疑いを検出した。"
                        + "false positiveならALLOWLISTへ理由付きで追加すること:\n" + String.join("\n", violations));
    }

    /** メソッド定義を全ファイルから拾い、本体が自分の引数のどれかを /100 か *0.01 しているメソッド名の集合を返す。 */
    private static Set<String> collectShrinkingMethodNames(Map<Path, String> sources) {
        Set<String> result = new LinkedHashSet<>();
        for (String src : sources.values()) {
            Matcher m = METHOD_DEF.matcher(src);
            while (m.find()) {
                String methodName = m.group(1);
                List<String> params = paramNames(m.group(2));
                if (params.isEmpty()) continue;
                int braceIndex = m.end() - 1; // マッチの最後の文字が '{'
                String body = extractBody(src, braceIndex);
                if (body == null) continue;
                for (String param : params) {
                    Pattern divides = Pattern.compile(
                            "\\b" + Pattern.quote(param) + "\\b\\s*(?:/\\s*100(?:\\.0)?\\b|\\*\\s*0\\.01\\b)");
                    if (divides.matcher(body).find()) {
                        result.add(methodName);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /** "double foo, int bar" のような引数リストから末尾の識別子(引数名)だけを抽出する(簡易・型のカンマ入りジェネリクスは非対応)。 */
    private static List<String> paramNames(String paramList) {
        List<String> names = new ArrayList<>();
        if (paramList == null || paramList.isBlank()) return names;
        for (String part : paramList.split(",")) {
            String trimmed = part.trim().replace("...", " ").replaceAll("[\\[\\]]", "");
            if (trimmed.isEmpty()) continue;
            String[] tokens = trimmed.split("\\s+");
            String last = tokens[tokens.length - 1];
            if (last.matches("\\w+")) {
                names.add(last);
            }
        }
        return names;
    }

    /**
     * {@code openBraceIndex} (src.charAt(openBraceIndex) == '{') から対応する閉じ波括弧までを
     * 波括弧の深さカウントで抽出する。文字列/文字リテラルと行/ブロックコメント内の
     * '{'/'}' は無視する(誤検知防止)。対応する閉じ括弧が見つからない場合は {@code null}。
     */
    private static String extractBody(String src, int openBraceIndex) {
        int depth = 0;
        int i = openBraceIndex;
        int len = src.length();
        while (i < len) {
            char c = src.charAt(i);
            if (c == '"') {
                i = skipString(src, i, '"');
                continue;
            }
            if (c == '\'') {
                i = skipString(src, i, '\'');
                continue;
            }
            if (c == '/' && i + 1 < len && src.charAt(i + 1) == '/') {
                int nl = src.indexOf('\n', i);
                i = (nl < 0) ? len : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < len && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = (end < 0) ? len : end + 2;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(openBraceIndex, i + 1);
                }
            }
            i++;
        }
        return null;
    }

    private static int skipString(String src, int start, char quote) {
        int i = start + 1;
        int len = src.length();
        while (i < len) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static String resolveKey(String arg, Map<String, String> constToKey) {
        Matcher literal = RATE_KEY_LITERAL.matcher(arg);
        if (literal.find()) {
            return canonical(literal.group(1));
        }
        Matcher rawLiteral = QUOTED_LITERAL.matcher(arg);
        if (rawLiteral.find()) {
            return canonical(rawLiteral.group(1));
        }
        return constToKey.get(arg.trim());
    }

    private static String canonical(String key) {
        return key.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static Set<String> extractRateKeys() throws IOException {
        String src = Files.readString(PERCENT_STAT_NORMALIZE);
        int start = src.indexOf("RATE_KEYS = Set.of(");
        assertTrue(start >= 0, "RATE_KEYS宣言の開始位置を見つけられなかった");
        int end = src.indexOf(");", start);
        assertTrue(end >= 0, "RATE_KEYS宣言の終端 ');' を見つけられなかった");
        String block = src.substring(start, end);
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = RATE_KEY_LITERAL.matcher(block);
        while (m.find()) {
            keys.add(canonical(m.group(1)));
        }
        return keys;
    }
}
