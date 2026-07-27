package com.trinityforge.combat;

/**
 * B2 (2026-07-25 バグ報告 / 2026-07-25 レビュー修正): バニラの「攻撃クールダウン中に攻撃すると威力が落ちる」
 * 補正を、TFの独自ダメージパイプラインへ再導入する純粋関数。バニラの式 {@code 0.2 + t^2 * 0.8} (t =
 * チャージ進捗, 0.0〜1.0) を一般化し、下限倍率と指数をconfig化したもの(既定値はバニラ相当)。
 *
 * <p><b>レビュー修正(2026-07-25, HIGH指摘1): {@code Player#getAttackCooldown()} に依存しない。</b>
 * 一次情報確認の結果(セッションレポート参照): PaperMC/Paper PR #13856 (2026-05-03マージ, 課題
 * #13838「spear jab attacks」修正)が {@code resetAttackStrengthTicker()} の呼び出し位置を
 * {@code EntityDamageByEntityEvent} 発火の"前"へ動かした。Paper開発者自身が issue #13884 で
 * 「(この新順序が)正しい({@code The cooldown is computed before the damage is now, which is proper})」
 * と明言し、プラグインには {@code PrePlayerAttackEntityEvent} の使用を推奨している——つまり
 * {@code EntityDamageByEntityEvent} 内で {@code getAttackCooldown()} を読む設計はPaper側で今後も
 * 保証されない。加えて #11552 (persist open) の持ち替えexploitもBukkit値依存の根本原因。よって
 * TF自身が「最後に攻撃した tick」を {@link MeleeChargeTracker} で記録し、経過tickと実効攻撃速度
 * ({@code Attribute.ATTACK_SPEED})からチャージ進捗を自前計算する——バニラのリセット順序にもExploitにも
 * 一切依存しない決定的な値になる。
 *
 * <p>適用箇所の判断(レポート参照): TFの8段階ダメージパイプライン({@link ComponentDamageCalculator})
 * 内の特定ステップにではなく、{@code CombatListener} が算出した「最終物理ダメージ合計」
 * (fixed-damageを含む、全防御貫通後の値)へ後乗算する。fixed-damageは「防御を貫通する」仕様であって
 * 「攻撃者自身のチャージ状態を無視する」仕様ではないため、fixed-damage武器だけ連打が最適になる抜け道を
 * 塞ぐには、このステートに掛かった後段の合計へ乗算するのが唯一の一貫した選択肢になる。近接プレイヤー
 * 攻撃(素手/剣/斧等)にのみ適用し、弓・クロスボウ・トライデントの遠隔攻撃や魔法ダメージ、モブの攻撃には
 * 一切適用しない(呼び出し側=CombatListenerのゲートで保証する)。
 *
 * <p><b>レビュー修正(HIGH指摘2): 二重減衰の回避。</b> {@code attack-power} 未定義アイテムでの攻撃は
 * イベントの生ダメージ({@code event.getDamage()})が既にバニラ側でチャージ減衰済みのため、TF側の
 * 減衰をもう一度掛けると二重になる。呼び出し側({@code CombatListener}) は既存の {@code tfBaseReplaces}
 * (= 攻撃集約が {@code attack-power} を持つか)フラグでこの乗算をゲートする——{@code baseDamage} が
 * バニラ値由来か TF算出値かを判定するのに、この関数自身が既に使っている唯一の正しい基準だから
 * (Sweeping Edge再導入・Sharpness等の二重計上防止と全く同じ判定を再利用。2026-07-25: かつて存在した
 * 専用ガード {@code zeroEnchantModifierIfPresent} は現行Paperの {@code DamageModifier} enumに
 * {@code ENCHANTMENTS} が無く恒久的にno-opだったため削除済み)。
 */
public final class MeleeChargeMultiplier {

    private MeleeChargeMultiplier() {
    }

    /** バニラ既定の実効攻撃速度(Attribute.ATTACK_SPEED の素の既定値)。取得不能時のフォールバック。 */
    static final double DEFAULT_ATTACK_SPEED = 4.0;

    /**
     * @param enabled       トグルOFFなら常に1.0(補正なし)。elapsedTicks/attackSpeed を一切参照しない。
     * @param elapsedTicks  前回攻撃からの経過tick数({@link MeleeChargeTracker} 由来)。負値は0へクランプ。
     *                      {@code Integer.MAX_VALUE}(=このセッションで初回攻撃、または記録なし)はフル
     *                      チャージ(t=1.0)として扱う。
     * @param attackSpeed   {@code Attribute.ATTACK_SPEED} の実効値(攻撃/秒)。非有限/0以下はバニラ既定
     *                      {@value #DEFAULT_ATTACK_SPEED} にフォールバックする。
     * @param minMultiplier t=0時点の下限倍率。[0,1]へクランプ。非有限はバニラ既定0.2にフォールバック
     * @param exponent      tに掛かる指数。0以下/非有限はバニラ既定2.0にフォールバック
     * @return [minMultiplier, 1.0] の範囲の乗数
     */
    public static double compute(boolean enabled, int elapsedTicks, double attackSpeed,
                                 double minMultiplier, double exponent) {
        if (!enabled) {
            return 1.0;
        }
        double t = chargeFraction(elapsedTicks, attackSpeed);
        double min = Double.isFinite(minMultiplier) ? Math.max(0.0, Math.min(1.0, minMultiplier)) : 0.2;
        double exp = Double.isFinite(exponent) && exponent > 0.0 ? exponent : 2.0;
        return min + Math.pow(t, exp) * (1.0 - min);
    }

    /**
     * 経過tickと実効攻撃速度から、バニラの {@code attackStrengthTicker / getCurrentItemAttackStrengthDelay()}
     * と同じ形の進捗率 [0,1] を算出する純粋関数(Bukkit非依存、単体テスト可能)。
     * {@code ticksForFullCharge = 20.0 / attackSpeed}("秒間攻撃速度"をtickへ換算)。
     */
    static double chargeFraction(int elapsedTicks, double attackSpeed) {
        double speed = Double.isFinite(attackSpeed) && attackSpeed > 0.0 ? attackSpeed : DEFAULT_ATTACK_SPEED;
        double ticksForFullCharge = 20.0 / speed;
        if (!Double.isFinite(ticksForFullCharge) || ticksForFullCharge <= 0.0) {
            return 1.0;
        }
        if (elapsedTicks == Integer.MAX_VALUE) {
            return 1.0;
        }
        double clampedElapsed = Math.max(0, elapsedTicks);
        return Math.max(0.0, Math.min(1.0, clampedElapsed / ticksForFullCharge));
    }
}
