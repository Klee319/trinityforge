package com.trinityforge.progression;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * ワイルドカード照合({@code *} = 任意長、{@code ?} = 任意1文字)。大文字小文字は無視する。
 *
 * <p>図鑑GUI / レシピ一覧GUI の名前検索で使う。ワイルドカードを1つも含まないパターンは
 * 「部分一致」として扱う — プレイヤーが砥石に {@code 剣} と打ったときに {@code *剣*} を
 * 期待するのは自明なので、そこで空振りさせない。明示的に {@code *} / {@code ?} を書いた
 * ときだけ厳密なグロブ(完全一致)になる。
 *
 * <p>コンパイル済み {@link Pattern} は小さな上限付きキャッシュに載せる。検索語はプレイヤーの
 * 自由入力なので、無制限にキャッシュすると悪意ある入力でヒープを膨らませられる。
 */
public final class GlobMatcher {

    private static final int MAX_CACHE = 256;
    private static final int MAX_PATTERN_LENGTH = 128;
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private GlobMatcher() {
    }

    /**
     * {@code value} が {@code pattern} に一致するか。パターンが null/空なら常に {@code true}
     * (絞り込み無し)。
     */
    public static boolean matches(String pattern, String value) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        Pattern compiled = compile(pattern);
        if (compiled == null) {
            return true; // 壊れたパターンで全件消すより、絞り込み無しに倒す。
        }
        return compiled.matcher(value == null ? "" : value).matches();
    }

    private static Pattern compile(String rawPattern) {
        String trimmed = rawPattern.trim();
        if (trimmed.length() > MAX_PATTERN_LENGTH) {
            trimmed = trimmed.substring(0, MAX_PATTERN_LENGTH);
        }
        String key = trimmed.toLowerCase(Locale.ROOT);
        Pattern cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Pattern built = build(key);
        if (built != null && CACHE.size() < MAX_CACHE) {
            CACHE.put(key, built);
        }
        return built;
    }

    private static Pattern build(String glob) {
        boolean hasWildcard = glob.indexOf('*') >= 0 || glob.indexOf('?') >= 0;
        String effective = hasWildcard ? glob : "*" + glob + "*";
        StringBuilder regex = new StringBuilder(effective.length() * 2);
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < effective.length(); i++) {
            char c = effective.charAt(i);
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        try {
            return Pattern.compile(regex.toString(),
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        } catch (PatternSyntaxException ex) {
            return null;
        }
    }
}
