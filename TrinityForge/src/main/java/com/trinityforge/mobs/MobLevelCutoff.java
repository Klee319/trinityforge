package com.trinityforge.mobs;

/**
 * 「レベル差による足きり」設定(2026-07-27 「このレベル差がある敵は少なくなる…また、アイテムが
 * 入手できなくなるレベル差の閾値設定も欲しい」要望): {@code combat/mob-overrides.yml} の
 * {@code level-cutoff:} ブロック1つぶんを表す、Bukkit非依存の純粋ロジック。
 *
 * <p>{@code diff = プレイヤーの戦闘レベル - モブのレベル} を基準に2つの独立した足きりを判定する。
 * <b>over/under が指すのはモブではなくプレイヤーの側</b>で、{@code over-level} は
 * 「プレイヤーのほうが高レベル」＝<b>低レベル狩り</b>のときに効く。日本語で「格上狩り」と呼ぶと
 * 主語が反転して読めるため、その言い方はしない(2026-08-18 ユーザー指摘)。
 * <ul>
 *   <li><b>over-level</b>(プレイヤーが高レベル＝低レベル狩り): {@code diff >= overLevelThreshold} で発動。発動すると
 *       経験値に {@link #expMultiplier}、TF追加ドロップの確率に {@link #dropChanceMultiplier} が
 *       乗算される。どちらのレートも {@code -1} なら「完全に入手不可(経験値0 / 追加ドロップ無し)」を
 *       表す特別値。</li>
 *   <li><b>under-level</b>(プレイヤーが低レベル): {@code -diff >= underLevelItemThreshold}(= モブが自分より
 *       指定値以上高レベル)で発動。発動するとTF追加ドロップが一切付かなくなる({@link #blocksItems}）。
 *       経験値には影響しない。</li>
 * </ul>
 *
 * <p>フィールドはすべて未設定(null)を許容し、未設定 = その足きりは無効。{@code threshold}系が
 * {@code null} または負値の場合も同様に無効(「未設定/負値 = 無効」という仕様上の明文規則)。
 *
 * <p><b>2026-08-18(W-60) 線形傾斜の追加</b>: 以前は over-level 発動直後に固定レート
 * ({@code overLevelExpRate}/{@code overLevelDropRate}) へ単純ジャンプするステップ関数で、
 * 超過量({@code diff - overLevelThreshold})の大小を一切見なかった(閾値5レベル超も50レベル超も
 * 同じ倍率)。{@link #overLevelExpDecayPerLevel}/{@link #overLevelDropDecayPerLevel} は
 * 「超過1レベルごとに基準レートから引く量」、{@link #overLevelRateFloor} は減衰の下限を表す。
 * 既定はどちらも {@code 0.0} で、この場合は超過が何レベルあっても減衰量が常に0になるため、
 * 従来の「閾値到達で固定レートへジャンプ」という挙動と完全に一致する(後方互換)。
 *
 * <p><b>{@code -1}(完全遮断)との整合</b>: {@code overLevelExpRate}/{@code overLevelDropRate} が
 * {@code -1} のときは、減衰計算に入る前に無条件で0(exp)/ブロック(drop)を返す。つまり
 * 「rateを-1にして完全に締め出す」運用と「decayで徐々に絞る」運用は排他で、-1は常に最優先される。
 * 減衰で徐々に0へ絞りたい場合は rate に通常値(例: {@code 1.0})を設定し、decay/floor で調整すること。
 *
 * <p>超過量({@code excess = diff - threshold}、{@code diff} は over-level なら
 * {@code playerLevel - mobLevel})が0(閾値ちょうど)のときは減衰を一切適用しない
 * ({@code overLevelExpRate}/{@code overLevelDropRate} がそのまま基準値になる)。
 *
 * <p><b>バニラ本来のドロップには一切関与しない</b>: このクラスが止めるのは
 * {@code combat/mob-overrides.yml drops:} 由来のTF追加ドロップだけで、{@code EntityDeathEvent} の
 * 素のドロップ(バニラ)には呼び出し側(リスナー)も一切触らない。理由: バニラドロップまで止めると
 * モブトラップが完全に死んで「足きり」の域を超えるため(2026-07-27 設計判断)。
 *
 * @param overLevelThreshold    over-level発動の閾値({@code diff >= この値}で発動)。{@code null}または
 *                              負値なら無効。
 * @param overLevelExpRate      over-level発動時に経験値へ掛ける倍率 [0,1]、または {@code -1}(経験値0)。
 *                              {@code null} なら発動していても経験値には触れない(倍率1.0扱い)。
 *                              超過(閾値超え)がある場合は {@link #overLevelExpDecayPerLevel} ぶん減衰した
 *                              値が実際に使われる({@code -1} のときは減衰を経由せず常に0)。
 * @param overLevelDropRate     over-level発動時にTF追加ドロップの確率へ掛ける倍率 [0,1]、または
 *                              {@code -1}(追加ドロップを一切付けない)。{@code null} なら発動していても
 *                              ドロップ確率には触れない(倍率1.0扱い)。exp側と同様、超過があれば
 *                              {@link #overLevelDropDecayPerLevel} ぶん減衰する({@code -1} は減衰を経由しない)。
 * @param underLevelItemThreshold under-level発動の閾値({@code mobLevel - playerLevel >= この値}で発動)。
 *                                {@code null}または負値なら無効。
 * @param overLevelExpDecayPerLevel  over-level超過1レベルごとに {@code overLevelExpRate} から引く量。
 *                                   {@code null}/{@code 0.0} なら減衰なし(既定、完全後方互換)。
 * @param overLevelDropDecayPerLevel over-level超過1レベルごとに {@code overLevelDropRate} から引く量。
 *                                   {@code null}/{@code 0.0} なら減衰なし(既定、完全後方互換)。
 * @param overLevelRateFloor         減衰後のレートがここより下がらないようにする下限 [0,1]。
 *                                   {@code null} なら {@code 0.0} 扱い(既定、完全後方互換)。
 */
public record MobLevelCutoff(Integer overLevelThreshold, Double overLevelExpRate, Double overLevelDropRate,
                              Integer underLevelItemThreshold, Double overLevelExpDecayPerLevel,
                              Double overLevelDropDecayPerLevel, Double overLevelRateFloor) {

    /** 全フィールド未設定 = 常に無効。 */
    public static final MobLevelCutoff NONE = new MobLevelCutoff(null, null, null, null);

    /**
     * 2026-08-18(W-60)以前からの4引数コンストラクタ(既存呼び出し元との後方互換)。
     * 減衰系3フィールドは {@code 0.0} で初期化する ── 減衰量が常に0になるため、
     * 従来の「閾値到達で固定レートへジャンプする」挙動と完全に一致する。
     */
    public MobLevelCutoff(Integer overLevelThreshold, Double overLevelExpRate, Double overLevelDropRate,
                           Integer underLevelItemThreshold) {
        this(overLevelThreshold, overLevelExpRate, overLevelDropRate, underLevelItemThreshold,
                0.0, 0.0, 0.0);
    }

    /** {@code true} なら全フィールドが未設定(このインスタンスは実質{@link #NONE}と同じ)。 */
    public boolean isNone() {
        return overLevelThreshold == null && overLevelExpRate == null && overLevelDropRate == null
                && underLevelItemThreshold == null;
    }

    /** over-levelが発動中か({@code diff = playerLevel - mobLevel} が閾値以上)。 */
    public boolean isOverLevelActive(int playerLevel, int mobLevel) {
        return overLevelThreshold != null && overLevelThreshold >= 0
                && (playerLevel - mobLevel) >= overLevelThreshold;
    }

    /** under-levelが発動中か({@code mobLevel - playerLevel} が閾値以上、= モブのほうが高レベル)。 */
    public boolean isUnderLevelActive(int playerLevel, int mobLevel) {
        return underLevelItemThreshold != null && underLevelItemThreshold >= 0
                && (mobLevel - playerLevel) >= underLevelItemThreshold;
    }

    /**
     * TF追加ドロップを一切付けないべきか。under-levelが発動しているか、または
     * over-levelが発動していて {@code overLevelDropRate == -1} のとき {@code true}。
     * バニラ本来のドロップは対象外(呼び出し側が別途保証する、クラスjavadoc参照)。
     */
    public boolean blocksItems(int playerLevel, int mobLevel) {
        if (isUnderLevelActive(playerLevel, mobLevel)) {
            return true;
        }
        return isOverLevelActive(playerLevel, mobLevel) && isMinusOne(overLevelDropRate);
    }

    /**
     * TF追加ドロップの各エントリの{@code chance}に掛ける倍率。{@link #blocksItems}が{@code true}なら
     * 常に {@code 0.0}。over-levelが発動していて{@code overLevelDropRate}が {@code -1} 以外の値で
     * 設定されているなら、超過レベルぶん {@link #overLevelDropDecayPerLevel} で減衰させた値
     * ({@link #overLevelRateFloor} でクランプ)。それ以外(未発動、またはレート未設定)は
     * {@code 1.0}(無変化)。
     */
    public double dropChanceMultiplier(int playerLevel, int mobLevel) {
        if (blocksItems(playerLevel, mobLevel)) {
            return 0.0;
        }
        if (isOverLevelActive(playerLevel, mobLevel) && overLevelDropRate != null) {
            return decayedRate(overLevelDropRate, overLevelDropDecayPerLevel,
                    excessOverLevels(playerLevel, mobLevel), overLevelRateFloor);
        }
        return 1.0;
    }

    /**
     * 経験値に掛ける倍率。over-levelが発動していなければ、またはレート未設定なら {@code 1.0}(無変化)。
     * 発動していて{@code overLevelExpRate == -1}なら(減衰計算を経由せず)常に {@code 0.0}(経験値0)。
     * それ以外は、超過レベルぶん {@link #overLevelExpDecayPerLevel} で減衰させた値
     * ({@link #overLevelRateFloor} でクランプ)。under-levelは経験値に一切影響しない(仕様どおり)。
     */
    public double expMultiplier(int playerLevel, int mobLevel) {
        if (!isOverLevelActive(playerLevel, mobLevel) || overLevelExpRate == null) {
            return 1.0;
        }
        if (isMinusOne(overLevelExpRate)) {
            return 0.0;
        }
        return decayedRate(overLevelExpRate, overLevelExpDecayPerLevel, excessOverLevels(playerLevel, mobLevel),
                overLevelRateFloor);
    }

    /**
     * over-levelの閾値をどれだけ超過しているか({@code diff - overLevelThreshold}、負にはならない)。
     * {@link #isOverLevelActive} が真の場合にのみ呼ばれる前提なので {@code overLevelThreshold} は
     * 非null・非負であることが保証されている。
     */
    private int excessOverLevels(int playerLevel, int mobLevel) {
        int diff = playerLevel - mobLevel;
        return Math.max(0, diff - overLevelThreshold);
    }

    /**
     * 「基準レート − 超過レベル×減衰量」を{@code [floor, 1.0]}へクランプする。
     * {@code decayPerLevel}/{@code floor} が {@code null} ならそれぞれ {@code 0.0} 扱い
     * (＝閾値ちょうど・decay未設定のときは基準レートをそのまま返す、既存の {@code clamp01} と同じ挙動)。
     */
    private static double decayedRate(double baseRate, Double decayPerLevel, int excessLevels, Double floor) {
        double decay = orZero(decayPerLevel) * excessLevels;
        double lowerBound = orZero(floor);
        return Math.max(lowerBound, Math.min(1.0, baseRate - decay));
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private static boolean isMinusOne(Double value) {
        return value != null && value == -1.0;
    }
}
