package com.trinityforge.ranking;

/**
 * ランキング表示に使う、バックエンド間で共通の集計値。
 *
 * <p>ここに入るのは「プレイヤー本人がオンラインのサーバでしか読めない」値だけ。
 * スキルレベルは {@code player_progression.db} に入っており、その DB ファイルは
 * ディレクトリジャンクションで全バックエンドに共有されているので、写す必要がない。
 *
 * <p>4 項目とも<b>減らない量</b>である点が重要。{@link RankingMirrorStore} は
 * 書き込みを単調増加に制限しており、その正当性はこの性質に依存している。
 */
public record RankingStats(int collectionItems, int collectionMobs, int glyphsUnlocked, int mobKills) {

    public static final RankingStats EMPTY = new RankingStats(0, 0, 0, 0);

    public RankingStats {
        // 負値は「読めなかった」の表現として渡ってくることがあるため 0 に潰す。
        // 負値のまま MAX を取ると既存値を守れてしまい、逆にバグを隠す。
        collectionItems = Math.max(0, collectionItems);
        collectionMobs = Math.max(0, collectionMobs);
        glyphsUnlocked = Math.max(0, glyphsUnlocked);
        mobKills = Math.max(0, mobKills);
    }

    /** 図鑑の登録総数（アイテム＋モブ）。図鑑 GUI の「登録数」と同じ数え方。 */
    public int collectionEntries() {
        return collectionItems + collectionMobs;
    }

    /** 全項目が 0 なら true。まだ一度も写されていないプレイヤーの判別に使う。 */
    public boolean isEmpty() {
        return collectionItems == 0 && collectionMobs == 0 && glyphsUnlocked == 0 && mobKills == 0;
    }
}
