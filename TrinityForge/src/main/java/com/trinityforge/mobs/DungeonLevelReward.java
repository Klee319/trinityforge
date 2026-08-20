package com.trinityforge.mobs;

/**
 * 「ダンジョンの挑戦レベルに応じた報酬の増減」設定(2026-08-18 W-80): {@code combat/damage.yml} の
 * {@code dungeon-level-reward:} ブロック1つぶんを表す、Bukkit非依存の純粋ロジック。
 *
 * <p><b>解こうとしている問題。</b> EliteMobs のダイナミックダンジョンは入場時に挑戦レベルと難易度を自分で選ぶ。
 * 選んだレベルはインスタンス内のモブ全員のレベルになるので<b>敵の強さには効いている</b>が、
 * 報酬(TF追加ドロップの確率・撃破EXP)には<b>ほとんど効いていなかった</b> ──
 * TF追加ドロップの {@code chance} は {@code combat/mob-overrides.yml} の固定値でレベル項が無く、
 * 撃破EXPもモブレベルに対して 1.008 乗の緩い傾斜しか持たない。
 * つまり「選べる中で一番低いレベルを選んで最速で回す」のが常に最適で、高いレベルを選ぶ理由が
 * どこにも無かった(2026-08-18 ユーザー報告 ②③)。
 *
 * <p><b>なぜ「上乗せ」ではなく「増減」なのか(2026-08-18 ユーザー指示 3.)。</b>
 * 最初は {@code baseLevel} 超えぶんを一方的に上乗せする片側の曲線({@code 1.0} 以上にしかならない)だったが、
 * それだと<b>頭打ちの倍率を大きく取らないと差が出ない</b>。低いレベルを選んだときに規定値より<b>減らす</b>ように
 * すれば、同じ「選ぶ動機」を作りつつ頭打ちの倍率を下げられる。よってこの曲線は
 * {@link #pivotLevel} で等倍、それより下は {@code 1.0} 未満、上は {@code 1.0} 超になる。
 *
 * <p><b>なぜ {@link #step} レベル刻みの階段関数なのか(同 3.)。</b> EMの難易度 normal/hard/mythic は
 * 相対 levelSync が {@code +5 / +0 / -5} で定義されている。フォーク側でこの符号を反転した値を
 * <b>モブレベルの補正</b>として使う(normal はモブが5レベル低く、mythic は5レベル高い)ので、
 * ここを {@code 5} 刻みにすると<b>難易度1段 = 報酬1段</b>で対応する。連続関数にすると
 * 難易度の差が端数になって「どれを選んでも似たようなもの」になってしまう。
 *
 * <p><b>なぜ「プレイヤーとのレベル差」で書かないか(2026-08-18 差し戻しの理由)。</b>
 * 最初の実装は「自分より高レベルのモブを倒したらレベル差ぶん報酬を増やす」というグローバルな条件で
 * 書いたため、<b>ダンジョンと無関係なオーバーワールドの高レベルモブにも効いてしまい</b>、
 * {@code level-cutoff.under-level}(低レベルのままハメ殺し/デスルーラーで高レベルのモブを狩る行為の抑制、
 * W-73)と真正面から衝突した。ここでは判定に使うのを<b>倒したモブのレベルそのもの</b>
 * (= ダンジョンで選んだレベル ± 難易度補正)に限り、適用先も<b>ダンジョンワールドで倒したモブだけ</b>に絞る。
 * プレイヤーのレベルは一切見ないので、強い人が低レベルのダンジョンを回しても増えない。
 *
 * <p><b>足きりとの関係。</b> {@link MobLevelCutoff} の足きりを掛けた<b>あと</b>に乗せる。足きりが
 * 「完全に入手不可」を返した場合は 0 に掛かるので、増減があっても結果は 0 のまま
 * (＝連れて行かれた低レベルが増加で抜け穴を作ることはない)。
 *
 * <p>フィールドはすべて未設定(null)を許容し、未設定は 0 として扱う(= 増減なし)。
 *
 * @param enabled          この増減自体を有効にするか。{@code null} は無効扱い。
 * @param pivotLevel       倍率がちょうど {@code 1.0}(規定値)になるモブレベル。これより低いダンジョンは
 *                         規定値より少なく、高いダンジョンは多くなる。{@code null} は 0。
 * @param step             何レベルごとに1段変えるか。{@code null}/0以下なら {@code 5} 扱い。
 *                         EMの難易度が相対 levelSync {@code ±5} なので、5 にしておくと難易度1段＝報酬1段になる。
 * @param dropBonusPerStep 1段ごとにTF追加ドロップ確率へ足す割合({@code 0.08} なら1段につき ±8%)。
 *                         {@code null}/0以下でドロップ側は増減しない。
 * @param dropBonusCap     ドロップ側の<b>増加</b>の頭打ち({@code 0.5} なら最大 +50% = 1.5倍)。
 * @param dropPenaltyCap   ドロップ側の<b>減少</b>の頭打ち({@code 0.3} なら最小 -30% = 0.7倍)。
 * @param expBonusPerStep  同じく撃破EXPの1段あたりの割合。{@code null}/0以下でEXP側は増減しない。
 * @param expBonusCap      EXP側の増加の頭打ち。
 * @param expPenaltyCap    EXP側の減少の頭打ち。
 */
public record DungeonLevelReward(Boolean enabled,
                                 Integer pivotLevel,
                                 Integer step,
                                 Double dropBonusPerStep,
                                 Double dropBonusCap,
                                 Double dropPenaltyCap,
                                 Double expBonusPerStep,
                                 Double expBonusCap,
                                 Double expPenaltyCap) {

    /** 何も増減しない設定(未設定のconfigやテスト用)。 */
    public static final DungeonLevelReward NONE =
            new DungeonLevelReward(false, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    /** {@link #step} が未設定/0以下のときに使う刻み幅。EMの難易度の相対 levelSync ±5 に合わせてある。 */
    private static final int DEFAULT_STEP = 5;

    /**
     * 減少側の最終防波堤。{@code penalty-cap} に 1.0 以上を書かれても報酬が 0 や負にならないようにする。
     * schema 側でも {@code [0, 0.9]} へ制限しているが、テストや直呼びの経路まで守るのはここ。
     */
    private static final double MIN_MULTIPLIER = 0.1;

    /** この設定が実質的に何もしないか(無効、または全レベル帯で倍率が 1.0)。 */
    public boolean isNone() {
        if (!isEnabled()) {
            return true;
        }
        return dropMultiplierAt(Integer.MIN_VALUE) == 1.0 && dropMultiplierAt(Integer.MAX_VALUE) == 1.0
                && expMultiplierAt(Integer.MIN_VALUE) == 1.0 && expMultiplierAt(Integer.MAX_VALUE) == 1.0;
    }

    private boolean isEnabled() {
        return enabled != null && enabled;
    }

    /**
     * {@code pivotLevel} から何段離れているか(下なら負)。
     *
     * <p>{@code Integer.MIN_VALUE}/{@code MAX_VALUE} を渡しても溢れないよう long で引いてから割る。
     * 端数は {@link Math#floorDiv} で切り下げるので、pivot ちょうどから {@code step-1} レベル上までが同じ0段になる。
     */
    private long stepsFrom(int mobLevel) {
        long pivot = pivotLevel == null ? 0L : pivotLevel.longValue();
        return Math.floorDiv((long) mobLevel - pivot, effectiveStep());
    }

    private long effectiveStep() {
        return (step == null || step <= 0) ? DEFAULT_STEP : step.longValue();
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /**
     * TF追加ドロップの確率へ掛ける倍率。{@code pivotLevel} 未満なら {@code 1.0} 未満になりうる。
     *
     * <p>呼び出し側は<b>ダンジョンワールドで倒したモブにだけ</b>掛けること。
     */
    public double dropMultiplierAt(int mobLevel) {
        return multiplier(mobLevel, orZero(dropBonusPerStep), orZero(dropBonusCap), orZero(dropPenaltyCap));
    }

    /** 撃破EXPへ掛ける倍率。{@code pivotLevel} 未満なら {@code 1.0} 未満になりうる。 */
    public double expMultiplierAt(int mobLevel) {
        return multiplier(mobLevel, orZero(expBonusPerStep), orZero(expBonusCap), orZero(expPenaltyCap));
    }

    private double multiplier(int mobLevel, double perStep, double bonusCap, double penaltyCap) {
        if (!isEnabled() || perStep <= 0.0 || (bonusCap <= 0.0 && penaltyCap <= 0.0)) {
            return 1.0;
        }
        long steps = stepsFrom(mobLevel);
        if (steps == 0L) {
            return 1.0;
        }
        double raw = perStep * steps;
        double adjusted = steps > 0L ? Math.min(bonusCap, raw) : Math.max(-penaltyCap, raw);
        return Math.max(MIN_MULTIPLIER, 1.0 + adjusted);
    }
}
