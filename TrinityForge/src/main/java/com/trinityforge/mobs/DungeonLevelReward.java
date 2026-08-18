package com.trinityforge.mobs;

/**
 * 「ダンジョンの挑戦レベルに応じた報酬の上乗せ」設定(2026-08-18 W-80): {@code combat/damage.yml} の
 * {@code dungeon-level-reward:} ブロック1つぶんを表す、Bukkit非依存の純粋ロジック。
 *
 * <p><b>解こうとしている問題。</b> EliteMobs のダイナミックダンジョンは入場時に挑戦レベルを自分で選ぶ。
 * 選んだレベルはインスタンス内のモブ全員のレベルになるので<b>敵の強さには効いている</b>が、
 * 報酬(TF追加ドロップの確率・撃破EXP)には<b>ほとんど効いていなかった</b> ──
 * TF追加ドロップの {@code chance} は {@code combat/mob-overrides.yml} の固定値でレベル項が無く、
 * 撃破EXPもモブレベルに対して 1.008 乗の緩い傾斜しか持たない。
 * つまり「選べる中で一番低いレベルを選んで最速で回す」のが常に最適で、高いレベルを選ぶ理由が
 * どこにも無かった(2026-08-18 ユーザー報告 ②③)。
 *
 * <p><b>なぜ「プレイヤーとのレベル差」で書かないか(2026-08-18 差し戻しの理由)。</b>
 * 最初の実装は「自分より高レベルのモブを倒したらレベル差ぶん報酬を増やす」というグローバルな条件で
 * 書いたため、<b>ダンジョンと無関係なオーバーワールドの高レベルモブにも効いてしまい</b>、
 * {@code level-cutoff.under-level}(低レベルのままハメ殺し/デスルーラーで高レベルのモブを狩る行為の抑制、
 * W-73)と真正面から衝突した。ここでは判定に使うのを<b>倒したモブのレベルそのもの</b>
 * (= ダンジョンで選んだレベル)に限り、適用先も<b>ダンジョンワールドで倒したモブだけ</b>に絞る。
 * プレイヤーのレベルは一切見ないので、強い人が低レベルのダンジョンを回しても増えない。
 *
 * <p><b>足きりとの関係。</b> {@link MobLevelCutoff} の足きりを掛けた<b>あと</b>に乗せる。足きりが
 * 「完全に入手不可」を返した場合は 0 に掛かるので、上乗せがあっても結果は 0 のまま
 * (＝連れて行かれた低レベルが上乗せで抜け穴を作ることはない)。
 *
 * <p>フィールドはすべて未設定(null)を許容し、未設定は 0 として扱う(= 上乗せなし)。
 *
 * @param enabled           この上乗せ自体を有効にするか。{@code null} は無効扱い。
 * @param baseLevel         上乗せが効き始めるモブレベル。これ以下のモブは完全に等倍
 *                          (低レベル帯の初挑戦を巻き込まないための足切り)。{@code null} は 0。
 * @param dropBonusPerLevel {@code baseLevel} を1超えるごとにTF追加ドロップ確率へ足す割合
 *                          ({@code 0.02} なら1レベルにつき +2%)。{@code null}/0 でドロップ側は無効。
 * @param dropBonusCap      ドロップ側の上乗せの頭打ち({@code 1.5} なら最大 +150% = 2.5倍)。
 *                          {@code null}/0 でドロップ側は無効。
 * @param expBonusPerLevel  同じく撃破EXPへ足す割合。{@code null}/0 でEXP側は無効。
 * @param expBonusCap       EXP側の上乗せの頭打ち。{@code null}/0 でEXP側は無効。
 */
public record DungeonLevelReward(Boolean enabled,
                                 Integer baseLevel,
                                 Double dropBonusPerLevel,
                                 Double dropBonusCap,
                                 Double expBonusPerLevel,
                                 Double expBonusCap) {

    /** 何も上乗せしない設定(未設定のconfigやテスト用)。 */
    public static final DungeonLevelReward NONE =
            new DungeonLevelReward(false, 0, 0.0, 0.0, 0.0, 0.0);

    /** この設定が実質的に何もしないか(無効、または両側の per-level / cap が 0)。 */
    public boolean isNone() {
        return !isEnabled() || (dropMultiplierAt(Integer.MAX_VALUE) == 1.0 && expMultiplierAt(Integer.MAX_VALUE) == 1.0);
    }

    private boolean isEnabled() {
        return enabled != null && enabled;
    }

    /**
     * {@code baseLevel} を超えた量。{@code baseLevel} 以下なら 0。
     *
     * <p>{@code Integer.MAX_VALUE} を渡しても溢れないよう long で引いてから clamp する。
     */
    private long excessOver(int mobLevel) {
        long base = baseLevel == null ? 0L : baseLevel.longValue();
        return Math.max(0L, (long) mobLevel - base);
    }

    private static double orZero(Double value) {
        return value == null ? 0.0 : value;
    }

    /**
     * TF追加ドロップの確率へ掛ける倍率。1.0 以上(下げることはない)。
     *
     * <p>呼び出し側は<b>ダンジョンワールドで倒したモブにだけ</b>掛けること。
     */
    public double dropMultiplierAt(int mobLevel) {
        return multiplier(mobLevel, orZero(dropBonusPerLevel), orZero(dropBonusCap));
    }

    /** 撃破EXPへ掛ける倍率。1.0 以上(下げることはない)。 */
    public double expMultiplierAt(int mobLevel) {
        return multiplier(mobLevel, orZero(expBonusPerLevel), orZero(expBonusCap));
    }

    private double multiplier(int mobLevel, double perLevel, double cap) {
        if (!isEnabled() || perLevel <= 0.0 || cap <= 0.0) {
            return 1.0;
        }
        long excess = excessOver(mobLevel);
        if (excess <= 0L) {
            return 1.0;
        }
        return 1.0 + Math.min(cap, perLevel * excess);
    }
}
