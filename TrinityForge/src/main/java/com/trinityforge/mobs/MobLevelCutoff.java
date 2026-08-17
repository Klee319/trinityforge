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
 *       指定値以上高レベル)で発動。<b>2026-08-18 に over-level と完全対称にした</b> ── 発動すると
 *       経験値に {@link #underLevelExpRate}、TF追加ドロップの確率に {@link #underLevelDropRate} が
 *       乗算され、超過ぶんは {@link #underLevelExpDecayPerLevel}/{@link #underLevelDropDecayPerLevel}
 *       で減衰する({@link #underLevelRateFloor} が下限)。{@code -1} が「完全に入手不可」を表すのも同じ。</li>
 * </ul>
 *
 * <p><b>2026-08-18 なぜ under-level 側を対称にしたか(ユーザー指示)。</b> 以前の under-level は閾値1本だけで、
 * 効果は「TF追加ドロップを一切付けない」の全か無かのみ、<b>経験値には一切影響しなかった</b>
 * ({@link #expMultiplier} が {@link #isOverLevelActive} でしか分岐していなかった)。つまり
 * <b>低レベルのプレイヤーがハメ殺しやデスルーラーで高レベルのモブを倒すと、バニラの経験値オーブも
 * TFの戦闘スキルEXPも満額入っていた</b>。撃破EXPはモブのレベルに応じて伸びるので、抑制したい行為に
 * 対してここが一番大きな抜け穴だった。抑制の主眼はこちら側だという判断なので、強い側の道具
 * (レート・線形逓減・下限)を両方の向きに用意した。
 * <p><b>パーティでの同行は区別しない</b>(2026-08-18 ユーザー決定)。レベル差だけで判定するので、
 * 高レベルの人にダンジョンへ連れて行ってもらった低レベルも同じだけ削られる。区別する術が無いのに
 * 「同行なら免除」を入れると、低レベルを連れて行くだけで抑制を回避できてしまうため。
 *
 * <p><b>両方が同時に発動する場合</b>(閾値が両方 0 かつ プレイヤーとモブが同レベルのときだけ起こる):
 * 経験値・ドロップとも<b>厳しいほう(小さいほう)を採用する</b>。{@code -1}(完全遮断)はどちら側の指定でも
 * 最優先される。
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
 *                                {@code null}または負値なら無効。<b>キー名は {@code item-threshold} のまま
 *                                据え置いている</b>が、2026-08-18 以降はアイテムだけでなく経験値の足きりも
 *                                この1本の閾値で判定する(配備済み config の値が別キーへ移って無言で
 *                                既定値に化けるのを避けるため、リネームしない)。
 * @param overLevelExpDecayPerLevel  over-level超過1レベルごとに {@code overLevelExpRate} から引く量。
 *                                   {@code null}/{@code 0.0} なら減衰なし(既定、完全後方互換)。
 * @param overLevelDropDecayPerLevel over-level超過1レベルごとに {@code overLevelDropRate} から引く量。
 *                                   {@code null}/{@code 0.0} なら減衰なし(既定、完全後方互換)。
 * @param overLevelRateFloor         減衰後のレートがここより下がらないようにする下限 [0,1]。
 *                                   {@code null} なら {@code 0.0} 扱い(既定、完全後方互換)。
 * @param underLevelExpRate      under-level発動時に経験値へ掛ける倍率 [0,1]、または {@code -1}(経験値0)。
 *                               {@code null} なら発動していても経験値には触れない(倍率1.0扱い)。
 * @param underLevelDropRate     under-level発動時にTF追加ドロップの確率へ掛ける倍率 [0,1]、または
 *                               {@code -1}(追加ドロップを一切付けない)。<b>後方互換のため既定は {@code -1}</b>
 *                               ── 2026-08-18 以前の under-level は「発動＝TF追加ドロップを一切付けない」
 *                               だったので、既定値を {@code -1} にしておくと旧挙動と完全に一致する。
 * @param underLevelExpDecayPerLevel  under-level超過1レベルごとに {@code underLevelExpRate} から引く量。
 * @param underLevelDropDecayPerLevel under-level超過1レベルごとに {@code underLevelDropRate} から引く量。
 * @param underLevelRateFloor         under-level側の減衰の下限 [0,1]。{@code null} なら {@code 0.0} 扱い。
 */
public record MobLevelCutoff(Integer overLevelThreshold, Double overLevelExpRate, Double overLevelDropRate,
                              Integer underLevelItemThreshold, Double overLevelExpDecayPerLevel,
                              Double overLevelDropDecayPerLevel, Double overLevelRateFloor,
                              Double underLevelExpRate, Double underLevelDropRate,
                              Double underLevelExpDecayPerLevel, Double underLevelDropDecayPerLevel,
                              Double underLevelRateFloor) {

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

    /**
     * 2026-08-18(W-72)以前からの7引数コンストラクタ(既存呼び出し元・既存テストとの後方互換)。
     * under-level側は「経験値には触れず(null)、発動したらTF追加ドロップを一切付けない(-1)」で
     * 初期化する ── これが対称化する前の under-level の挙動そのもの。
     */
    public MobLevelCutoff(Integer overLevelThreshold, Double overLevelExpRate, Double overLevelDropRate,
                           Integer underLevelItemThreshold, Double overLevelExpDecayPerLevel,
                           Double overLevelDropDecayPerLevel, Double overLevelRateFloor) {
        this(overLevelThreshold, overLevelExpRate, overLevelDropRate, underLevelItemThreshold,
                overLevelExpDecayPerLevel, overLevelDropDecayPerLevel, overLevelRateFloor,
                null, -1.0, 0.0, 0.0, 0.0);
    }

    /**
     * {@code true} なら全フィールドが未設定(このインスタンスは実質{@link #NONE}と同じ)。
     * <p>under-level側のレート/減衰は判定に含めない ── それらは
     * {@link #underLevelItemThreshold} が有効なときにしか効かないので、閾値が未設定なら
     * どんな値が入っていても挙動は「無効」で変わらないため。
     */
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
     * TF追加ドロップを一切付けないべきか。どちらかの向きが発動していて、その向きの
     * {@code drop-rate} が {@code -1} のとき {@code true}。
     * <p>under-level側の {@code drop-rate} は既定が {@code -1} なので、設定を書き換えていない
     * 環境では「under-level発動＝追加ドロップ無し」という2026-08-18以前の挙動のまま。
     * バニラ本来のドロップは対象外(呼び出し側が別途保証する、クラスjavadoc参照)。
     */
    public boolean blocksItems(int playerLevel, int mobLevel) {
        if (isUnderLevelActive(playerLevel, mobLevel) && isMinusOne(underLevelDropRate)) {
            return true;
        }
        return isOverLevelActive(playerLevel, mobLevel) && isMinusOne(overLevelDropRate);
    }

    /**
     * TF追加ドロップの各エントリの{@code chance}に掛ける倍率。{@link #blocksItems}が{@code true}なら
     * 常に {@code 0.0}。発動中の向きについて、超過レベルぶん減衰させた値
     * (それぞれの {@code rate-floor} でクランプ)を求め、<b>両方発動しているときは小さいほう</b>を返す。
     * どちらも未発動、またはレート未設定なら {@code 1.0}(無変化)。
     */
    public double dropChanceMultiplier(int playerLevel, int mobLevel) {
        if (blocksItems(playerLevel, mobLevel)) {
            return 0.0;
        }
        double rate = 1.0;
        if (isOverLevelActive(playerLevel, mobLevel) && overLevelDropRate != null) {
            rate = Math.min(rate, decayedRate(overLevelDropRate, overLevelDropDecayPerLevel,
                    excessOverLevels(playerLevel, mobLevel), overLevelRateFloor));
        }
        if (isUnderLevelActive(playerLevel, mobLevel) && underLevelDropRate != null) {
            rate = Math.min(rate, decayedRate(underLevelDropRate, underLevelDropDecayPerLevel,
                    excessUnderLevels(playerLevel, mobLevel), underLevelRateFloor));
        }
        return rate;
    }

    /**
     * 経験値に掛ける倍率。どちらの向きも発動していない、またはレート未設定なら {@code 1.0}(無変化)。
     * 発動中の向きのレートが {@code -1} なら(減衰計算を経由せず)常に {@code 0.0}(経験値0)。
     * それ以外は、超過レベルぶん減衰させた値をそれぞれ求め、<b>両方発動しているときは小さいほう</b>を返す。
     * <p>この倍率はバニラの経験値オーブとTFの戦闘スキルEXPの両方に掛かる
     * ({@code KillRewardAdjuster#expMultiplier} 経由)。
     */
    public double expMultiplier(int playerLevel, int mobLevel) {
        boolean overActive = isOverLevelActive(playerLevel, mobLevel);
        boolean underActive = isUnderLevelActive(playerLevel, mobLevel);
        if ((overActive && isMinusOne(overLevelExpRate)) || (underActive && isMinusOne(underLevelExpRate))) {
            return 0.0;
        }
        double rate = 1.0;
        if (overActive && overLevelExpRate != null) {
            rate = Math.min(rate, decayedRate(overLevelExpRate, overLevelExpDecayPerLevel,
                    excessOverLevels(playerLevel, mobLevel), overLevelRateFloor));
        }
        if (underActive && underLevelExpRate != null) {
            rate = Math.min(rate, decayedRate(underLevelExpRate, underLevelExpDecayPerLevel,
                    excessUnderLevels(playerLevel, mobLevel), underLevelRateFloor));
        }
        return rate;
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
     * under-levelの閾値をどれだけ超過しているか({@code (mobLevel - playerLevel) - underLevelItemThreshold}、
     * 負にはならない)。{@link #isUnderLevelActive} が真の場合にのみ呼ばれる前提。
     */
    private int excessUnderLevels(int playerLevel, int mobLevel) {
        int diff = mobLevel - playerLevel;
        return Math.max(0, diff - underLevelItemThreshold);
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
