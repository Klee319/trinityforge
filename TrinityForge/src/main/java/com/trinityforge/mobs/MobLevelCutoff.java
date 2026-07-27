package com.trinityforge.mobs;

/**
 * 「レベル差による足きり」設定(2026-07-27 「このレベル差がある敵は少なくなる…また、アイテムが
 * 入手できなくなるレベル差の閾値設定も欲しい」要望): {@code combat/mob-overrides.yml} の
 * {@code level-cutoff:} ブロック1つぶんを表す、Bukkit非依存の純粋ロジック。
 *
 * <p>{@code diff = プレイヤーの戦闘レベル - モブのレベル} を基準に2つの独立した足きりを判定する。
 * <ul>
 *   <li><b>over-level</b>(自分が格上): {@code diff >= overLevelThreshold} で発動。発動すると
 *       経験値に {@link #expMultiplier}、TF追加ドロップの確率に {@link #dropChanceMultiplier} が
 *       乗算される。どちらのレートも {@code -1} なら「完全に入手不可(経験値0 / 追加ドロップ無し)」を
 *       表す特別値。</li>
 *   <li><b>under-level</b>(自分が格下): {@code -diff >= underLevelItemThreshold}(= モブが自分より
 *       指定値以上高レベル)で発動。発動するとTF追加ドロップが一切付かなくなる({@link #blocksItems}）。
 *       経験値には影響しない。</li>
 * </ul>
 *
 * <p>フィールドはすべて未設定(null)を許容し、未設定 = その足きりは無効。{@code threshold}系が
 * {@code null} または負値の場合も同様に無効(「未設定/負値 = 無効」という仕様上の明文規則)。
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
 * @param overLevelDropRate     over-level発動時にTF追加ドロップの確率へ掛ける倍率 [0,1]、または
 *                              {@code -1}(追加ドロップを一切付けない)。{@code null} なら発動していても
 *                              ドロップ確率には触れない(倍率1.0扱い)。
 * @param underLevelItemThreshold under-level発動の閾値({@code mobLevel - playerLevel >= この値}で発動)。
 *                                {@code null}または負値なら無効。
 */
public record MobLevelCutoff(Integer overLevelThreshold, Double overLevelExpRate, Double overLevelDropRate,
                              Integer underLevelItemThreshold) {

    /** 全フィールド未設定 = 常に無効。 */
    public static final MobLevelCutoff NONE = new MobLevelCutoff(null, null, null, null);

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

    /** under-levelが発動中か({@code mobLevel - playerLevel} が閾値以上、= モブが格上)。 */
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
     * 設定されているなら {@code clamp01(overLevelDropRate)}。それ以外(未発動、またはレート未設定)は
     * {@code 1.0}(無変化)。
     */
    public double dropChanceMultiplier(int playerLevel, int mobLevel) {
        if (blocksItems(playerLevel, mobLevel)) {
            return 0.0;
        }
        if (isOverLevelActive(playerLevel, mobLevel) && overLevelDropRate != null) {
            return clamp01(overLevelDropRate);
        }
        return 1.0;
    }

    /**
     * 経験値に掛ける倍率。over-levelが発動していなければ、またはレート未設定なら {@code 1.0}(無変化)。
     * 発動していて{@code overLevelExpRate == -1}なら {@code 0.0}(経験値0)。それ以外は
     * {@code clamp01(overLevelExpRate)}。under-levelは経験値に一切影響しない(仕様どおり)。
     */
    public double expMultiplier(int playerLevel, int mobLevel) {
        if (!isOverLevelActive(playerLevel, mobLevel) || overLevelExpRate == null) {
            return 1.0;
        }
        if (isMinusOne(overLevelExpRate)) {
            return 0.0;
        }
        return clamp01(overLevelExpRate);
    }

    private static boolean isMinusOne(Double value) {
        return value != null && value == -1.0;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
