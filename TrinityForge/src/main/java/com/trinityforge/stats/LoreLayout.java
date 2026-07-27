package com.trinityforge.stats;

import java.util.List;
import java.util.Objects;

/**
 * Global lore presentation shared by every stat line (loaded from {@code stats/lore.yml}
 * {@code layout:}). The line template is a MiniMessage string with three placeholders resolved per
 * stat: {@code <icon>}, {@code <value>}, {@code <name>}. The score line template renders the
 * quality header line with {@code <tier>} (ティア色付きの【ティア名】) / {@code <tier-name>} /
 * {@code <score>}. Header/footer are MiniMessage lines wrapped around the stat block. Value colors
 * follow {@link LoreColorRules} (固定値ステ/ロールステ別 + grant-chances別の高度設定); the legacy
 * {@code positiveColor}/{@code negativeColor} pair is kept only for backwards compatibility as the
 * seed for default color rules. Immutable.
 *
 * @param header            MiniMessage lines emitted above the stat block
 * @param footer            MiniMessage lines emitted below the stat block
 * @param lineTemplate      MiniMessage template with {@code <icon>}/{@code <value>}/{@code <name>}
 * @param scoreLineTemplate MiniMessage template for the quality score line with
 *                          {@code <tier>}/{@code <tier-name>}/{@code <score>}; blank falls back to
 *                          {@link #DEFAULT_SCORE_LINE_TEMPLATE}
 * @param positiveColor     legacy: MiniMessage color for non-negative values
 * @param negativeColor     legacy: MiniMessage color for negative values
 * @param colors            per-source/per-sign value color rules
 * @param multiplierLayers  乗算レイヤ定義 (表示順 = セレクトメニュー順)。id はitem-stats側の layer キー
 */
public record LoreLayout(List<String> header,
                         List<String> footer,
                         String lineTemplate,
                         String scoreLineTemplate,
                         String positiveColor,
                         String negativeColor,
                         LoreColorRules colors,
                         List<MultiplierLayer> multiplierLayers) {

    /** 従来ハードコードされていた品質スコア行と同一の表示になる既定テンプレート。 */
    public static final String DEFAULT_SCORE_LINE_TEMPLATE =
            "<tier><gray>:</gray><white>Score→<score></white>";

    /**
     * 乗算レイヤの表示定義。{@code statKey} is the one canonical stat this layer may multiply.
     * Blank is accepted only for backward compatibility with legacy configuration.
     */
    public record MultiplierLayer(String id, String name, String statKey) {
        public MultiplierLayer {
            Objects.requireNonNull(id, "id");
            name = name == null || name.isBlank() ? id : name;
            statKey = statKey == null ? "" : StatKeys.canonical(statKey);
        }

        /** Backward-compatible legacy layer without a per-stat restriction. */
        public MultiplierLayer(String id, String name) {
            this(id, name, "");
        }
    }

    public LoreLayout {
        header = List.copyOf(Objects.requireNonNull(header, "header"));
        footer = List.copyOf(Objects.requireNonNull(footer, "footer"));
        Objects.requireNonNull(lineTemplate, "lineTemplate");
        // 空文字は「未設定」とみなして既定テンプレートへフォールバックする (editorがキーを
        // 空で保存しても品質行が消えない/例外にならないようにする)。
        scoreLineTemplate = scoreLineTemplate == null || scoreLineTemplate.isBlank()
                ? DEFAULT_SCORE_LINE_TEMPLATE : scoreLineTemplate;
        Objects.requireNonNull(positiveColor, "positiveColor");
        Objects.requireNonNull(negativeColor, "negativeColor");
        colors = colors == null ? LoreColorRules.defaults() : colors;
        multiplierLayers = multiplierLayers == null ? List.of() : List.copyOf(multiplierLayers);
        if (lineTemplate.isBlank()) {
            throw new IllegalArgumentException("lineTemplate must not be blank");
        }
    }

    /** Back-compat: pre-score-line-template constructor (default score line template). */
    public LoreLayout(List<String> header, List<String> footer, String lineTemplate,
                      String positiveColor, String negativeColor,
                      LoreColorRules colors, List<MultiplierLayer> multiplierLayers) {
        this(header, footer, lineTemplate, DEFAULT_SCORE_LINE_TEMPLATE,
                positiveColor, negativeColor, colors, multiplierLayers);
    }

    /** Back-compat: pre-colors constructor (default color rules, no multiplier layers). */
    public LoreLayout(List<String> header, List<String> footer, String lineTemplate,
                      String positiveColor, String negativeColor) {
        this(header, footer, lineTemplate, positiveColor, negativeColor,
                LoreColorRules.defaults(), List.of());
    }

    /** Neutral default: 「ステータス名：数値」 gray stat lines. */
    public static LoreLayout defaults() {
        return new LoreLayout(List.of(), List.of(),
                "<gray><icon><name>：<value></gray>", "green", "red");
    }
}
