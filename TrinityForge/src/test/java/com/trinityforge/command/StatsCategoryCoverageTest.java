package com.trinityforge.command;

import com.trinityforge.stats.StatVocabulary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ドリフト検知: {@link StatsCategory} の {@code *_KEYS} 集合は {@link StatVocabulary} とは独立して
 * 手動管理されており自動同期しない(StatsCategory の javadoc がそう警告している)。
 *
 * <p>同期が切れると新しいステが {@code /tf stats} のカテゴリ表示で {@code OTHER} バケツに落ちる。
 * 表示だけの問題なので実プレイでもテストでも気付かれず放置されやすく、実際 2026-07-24 のレビューで
 * 未分類キーが積み上がっているのが見つかった(2026-07-26 に24件を回収)。以後は増えた瞬間に落ちるよう、
 * ここで機械的に突き合わせる。
 *
 * <p>新しいステキーを足したら {@link StatVocabulary} と {@code stats/lore.yml} に加えて
 * {@link StatsCategory} にも分類を書くこと。「まだ分類を決めていない」を許すための例外リストは
 * 意図的に用意していない — 例外リストは必ず腐るため。
 * （{@link StatsCategory#ITEM_ONLY_KEYS} は逆方向テスト専用の恒久差分であって、この意味での
 * 「分類保留の例外リスト」ではない。詳細は {@link #noCategoryKeyIsMissingFromVocabulary()}。）
 */
class StatsCategoryCoverageTest {

    @Test
    void everyRegisteredStatKeyBelongsToExactlyOneCategory() {
        List<String> unclassified = new ArrayList<>();
        List<String> multiClassified = new ArrayList<>();
        for (String key : StatVocabulary.allKeys()) {
            List<StatsCategory> hits = new ArrayList<>();
            for (StatsCategory category : EnumSet.complementOf(
                    EnumSet.of(StatsCategory.ALL, StatsCategory.OTHER))) {
                if (category.includes(key)) {
                    hits.add(category);
                }
            }
            if (hits.isEmpty()) {
                unclassified.add(key);
            } else if (hits.size() > 1) {
                multiClassified.add(key + " -> " + hits);
            }
        }
        assertEquals(List.of(), unclassified,
                "StatVocabulary にあるのに StatsCategory のどのカテゴリにも入っていないキー"
                        + "(/tf stats で OTHER に落ちる)");
        assertEquals(List.of(), multiClassified,
                "複数カテゴリに重複登録されているキー(どちらで出るかが実装順に依存する)");
    }

    /**
     * 逆方向のドリフト検知。上の2テストは「{@code StatVocabulary} → {@code StatsCategory}」しか見ないので、
     * <b>ボキャブラリから削除されたキーが {@code StatsCategory} に取り残される</b>ケースを一切捕まえられない。
     * 実際 2026-07-26 のレビューで、stat-scope 境界引き直しで除外済みの {@code attack_speed} が
     * {@code ATTACK_KEYS} に死んだまま残っているのが見つかった(永久に一致しない要素なので実害は無いが、
     * 「同期せよ」と警告している当のクラスが同期していない状態だった)。
     *
     * <p>例外は {@link StatsCategory#ITEM_ONLY_KEYS} だけ。これは「腐る例外リスト」ではなく、
     * ボキャブラリ側が item専用キーを構造的に持たない(GENERAL_KEYS の javadoc 参照)ことに由来する
     * 恒久的な差分で、置き場所を1箇所に固定してある。
     */
    @Test
    void noCategoryKeyIsMissingFromVocabulary() {
        List<String> orphans = new ArrayList<>();
        for (StatsCategory category : EnumSet.complementOf(
                EnumSet.of(StatsCategory.ALL, StatsCategory.OTHER))) {
            for (String key : category.keys()) {
                if (StatVocabulary.allKeys().contains(key)
                        || StatsCategory.ITEM_ONLY_KEYS.contains(key)) {
                    continue;
                }
                orphans.add(key + " (" + category + ")");
            }
        }
        assertEquals(List.of(), orphans,
                "StatsCategory にあるのに StatVocabulary にも ITEM_ONLY_KEYS にも無いキー"
                        + "(ボキャブラリから削除された取り残し。分類側からも消すこと)");
    }

    @Test
    void otherBucketIsEmptyForRegisteredKeys() {
        // 上のテストの裏返し。OTHER は「未登録キー用の受け皿」であって、登録済みステの置き場ではない。
        for (String key : StatVocabulary.allKeys()) {
            assertTrue(!StatsCategory.OTHER.includes(key),
                    key + " が OTHER に落ちている(StatsCategory に分類を追加すること)");
        }
    }
}
