package com.trinityforge.progression;

/**
 * 2026-07-26 EXP調整タスク3: スキルレベルが上がるほど1回あたりのEXP取得量を減らす、任意の逓減カーブ。
 *
 * <p>{@link NativeProgressionService} が各EXP付与の直前に「そのスキルの現在レベル」を渡して
 * 倍率を尋ね、返ってきた値を付与量へ掛ける。既定実装は常に{@code 1.0}を返す {@link #NONE} で、
 * これを使う限り実サーバの挙動は1ミリも変わらない(既定オプトインOFF)。
 */
@FunctionalInterface
public interface ExpDiminishingCurve {

    /**
     * @param skillId      付与対象スキルID(例: {@code "MINING"})
     * @param currentLevel このEXP付与が適用される「前」のスキルレベル(0以下なら通常呼ばれない想定)
     * @return EXP付与量に掛ける倍率。1.0=変化なし。実装は[0,1]へクランプすることを推奨するが、
     *         このインターフェース自体は範囲を強制しない。
     */
    double multiplierFor(String skillId, int currentLevel);

    /** 逓減なし(常に1.0)。テスト/後方互換用の既定実装。 */
    ExpDiminishingCurve NONE = (skillId, currentLevel) -> 1.0;
}
