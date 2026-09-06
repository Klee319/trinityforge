package com.trinityforge.progression;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.function.Consumer;

/**
 * 節目レベルアップの全体アナウンス 1 行を組み立てる (2026-08-16)。
 *
 * <p>Bukkit に触れない純粋な処理として切り出してある（{@code network.ChatFormat} と同じ作り）。
 * 表示の壊れ方は「サーバを起動してレベルを上げてみる」以外に気付く手段が無いので、ここは単体テストで固定する。
 *
 * <p><b>プレイヤー名とスキル表示名を文字列置換で流し込んではいけない。</b>置換すると、名前や
 * 表示名に含まれる {@code <red>} や {@code <click:run_command:...>} が MiniMessage として解釈される。
 * また {@code <yellow>%player%</yellow>} をプレースホルダ位置で割ると {@code </yellow>} だけの断片ができ、
 * MiniMessage は対応の無い閉じタグを<b>そのまま文字として出す</b>。どちらも
 * {@link Placeholder#component} でタグとして解決させれば起きない。
 */
public final class LevelBroadcastFormat {

    public static final String PLACEHOLDER_PLAYER = "%player%";
    public static final String PLACEHOLDER_SKILL = "%skill%";
    public static final String PLACEHOLDER_LEVEL = "%level%";
    /** プレステージ(NG+)段番号。{@code %player%}/{@code %level%} と違い必須ではない。 */
    public static final String PLACEHOLDER_PRESTIGE = "%prestige%";

    // MiniMessage のタグ名は [a-z0-9_-] のみ。既存タグとぶつからないよう接頭辞を付ける
    // （ChatFormat の tf_player 等と同じ流儀）。
    private static final String TAG_PLAYER = "tf_player";
    private static final String TAG_SKILL = "tf_skill";
    private static final String TAG_LEVEL = "tf_level";
    private static final String TAG_PRESTIGE = "tf_prestige";

    static final String FALLBACK_TEMPLATE =
            "<gold><bold>[祝!]</bold></gold> <yellow><tf_player></yellow><gray> が </gray>"
                    + "<aqua><tf_skill></aqua><gray> で </gray><green>Lv<tf_level></green>"
                    + "<gray> に到達しました!</gray>";

    private LevelBroadcastFormat() {
    }

    /**
     * {@code format} を組み立てて 1 行の Component にする。
     *
     * <p>{@code %player%} か {@code %level%} を欠いた書式は「誰が何レベルになったのか分からない行」に
     * なるので、警告のうえ既定の書式へ落とす。{@code %skill%} は任意（スキルを問わず祝う運用があり得る）。
     *
     * @param skillDisplay スキル表示名。既に Component 化されている（{@code skilltree/*.yml} の
     *                     {@code display_name} は MiniMessage 記法を持ちうるため、呼び出し側で解決させる）
     * @param prestige     プレステージ(NG+)段番号。{@code %prestige%} が書式に無ければ単に無視される
     *                     （{@code %skill%} と同じく任意プレースホルダ）
     * @param onWarning    書式が不正だったときの通知先（ログ出力を想定）
     */
    public static Component render(String format, String playerName, Component skillDisplay, int level,
                                   int prestige, Consumer<String> onWarning) {
        TagResolver resolver = TagResolver.resolver(
                Placeholder.component(TAG_PLAYER, Component.text(playerName == null ? "" : playerName)),
                Placeholder.component(TAG_SKILL,
                        skillDisplay == null ? Component.empty() : skillDisplay),
                Placeholder.component(TAG_LEVEL, Component.text(Integer.toString(level))),
                Placeholder.component(TAG_PRESTIGE, Component.text(Integer.toString(prestige))));

        String template = toTemplate(format);
        if (template == null) {
            onWarning.accept("message には " + PLACEHOLDER_PLAYER + " と " + PLACEHOLDER_LEVEL
                    + " が必要です。既定の書式で表示します: " + format);
            return MiniMessage.miniMessage().deserialize(FALLBACK_TEMPLATE, resolver);
        }

        try {
            return MiniMessage.miniMessage().deserialize(template, resolver);
        } catch (RuntimeException ex) {
            onWarning.accept("message の MiniMessage 記法が不正です: " + ex.getMessage());
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
                || !format.contains(PLACEHOLDER_LEVEL)) {
            return null;
        }
        return format
                .replace(PLACEHOLDER_PLAYER, "<" + TAG_PLAYER + ">")
                .replace(PLACEHOLDER_SKILL, "<" + TAG_SKILL + ">")
                .replace(PLACEHOLDER_LEVEL, "<" + TAG_LEVEL + ">")
                .replace(PLACEHOLDER_PRESTIGE, "<" + TAG_PRESTIGE + ">");
    }
}
