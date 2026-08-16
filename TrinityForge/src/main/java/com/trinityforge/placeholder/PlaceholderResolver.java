package com.trinityforge.placeholder;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.ranking.RankingStats;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code %trinityforge_<params>%} の解決本体。
 *
 * <p><b>PlaceholderAPI に依存しない</b>のが要点。PlaceholderAPI は {@code compileOnly} なので
 * テスト実行時のクラスパスに存在せず、{@code PlaceholderExpansion} を継承したクラスは
 * ユニットテストから触れない。書式解釈という一番壊れやすい部分をここへ出しておくことで、
 * PlaceholderAPI もサーバも無しにテストできる。
 * {@link TrinityForgePlaceholders} は薄いアダプタに徹する。
 *
 * <p>データ源は {@link Supplier} で受け取る。未知の書式に対して SQLite を叩かないための遅延評価。
 */
public final class PlaceholderResolver {

    private static final String SKILL_PREFIX = "skill_";

    /** {@code skill_total_level} の「合計」を表す擬似スキル ID。実在の 16 種とは衝突しない。 */
    static final String TOTAL_SKILL_ID = "TOTAL";

    private PlaceholderResolver() {}

    /**
     * @param params {@code %trinityforge_} と {@code %} の間の文字列
     * @param progression 進行データの取得元（スキル系でのみ評価される）
     * @param ranking ランキング集計値の取得元（図鑑／グリフ／討伐数でのみ評価される）
     * @return 置換後の文字列。<b>書式が未知のときだけ {@code null}</b>
     *         （PlaceholderAPI の作法どおり、その場合は置換せず素通しさせる）。
     *         データが無いプレイヤーには {@code null} ではなく {@code "0"} を返す —
     *         ここで {@code null} を返すと順位表からその行ごと消えてしまう。
     */
    public static String resolve(String params,
                                 Supplier<PlayerProgression> progression,
                                 Supplier<RankingStats> ranking) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        String key = params.toLowerCase(Locale.ROOT);
        if (key.startsWith(SKILL_PREFIX)) {
            return skillValue(key.substring(SKILL_PREFIX.length()), progression);
        }
        return switch (key) {
            case "glyphs_unlocked" -> String.valueOf(stats(ranking).glyphsUnlocked());
            case "collection_entries" -> String.valueOf(stats(ranking).collectionEntries());
            case "collection_items" -> String.valueOf(stats(ranking).collectionItems());
            case "collection_mobs" -> String.valueOf(stats(ranking).collectionMobs());
            case "kills_total" -> String.valueOf(stats(ranking).mobKills());
            default -> null;
        };
    }

    private static RankingStats stats(Supplier<RankingStats> ranking) {
        if (ranking == null) {
            return RankingStats.EMPTY;
        }
        RankingStats value = ranking.get();
        return value == null ? RankingStats.EMPTY : value;
    }

    /**
     * {@code <id>_<field>} を解決する。スキル ID 自体が {@code heavy_armor} のように {@code _} を
     * 含むため、<b>末尾の {@code _} より後ろ</b>を項目名として切り出す
     * （先頭から切ると {@code heavy} という存在しないスキルを探しに行ってしまう）。
     */
    private static String skillValue(String rest, Supplier<PlayerProgression> progression) {
        int split = rest.lastIndexOf('_');
        if (split <= 0 || split == rest.length() - 1) {
            return null;
        }
        String skillId = rest.substring(0, split).toUpperCase(Locale.ROOT);
        String field = rest.substring(split + 1);
        boolean isTotal = TOTAL_SKILL_ID.equals(skillId);
        if (!isTotal && !SkillId.ALL.contains(skillId)) {
            return null;
        }
        // 合計は level のみ。prestige/totalexp の合計は意味が薄く誤読を招くので出さない。
        if (isTotal && !"level".equals(field)) {
            return null;
        }
        if (!isTotal && !"level".equals(field) && !"prestige".equals(field)
                && !"totalexp".equals(field)) {
            return null;
        }

        PlayerProgression snapshot = progression == null ? null : progression.get();
        if (snapshot == null) {
            // 進行サービスが未初期化。書式は正しいので 0 を返し、順位表から行を落とさない。
            return "0";
        }
        if (isTotal) {
            int total = 0;
            for (String id : SkillId.ALL) {
                SkillProgress each = snapshot.skills().get(id);
                if (each != null) {
                    total += each.level();
                }
            }
            return String.valueOf(total);
        }

        // 未取得スキルは行そのものが無い。ランキングでは 0 として並ばせたい。
        SkillProgress progress = snapshot.skills().get(skillId);
        return switch (field) {
            case "level" -> String.valueOf(progress == null ? 0 : progress.level());
            case "prestige" -> String.valueOf(progress == null ? 0 : progress.prestige());
            // 累計 EXP は double。指数表記になるとランキング側の数値ソートが壊れるので整数へ丸める。
            case "totalexp" -> String.valueOf(progress == null ? 0L : Math.round(progress.totalExp()));
            default -> null;
        };
    }
}
