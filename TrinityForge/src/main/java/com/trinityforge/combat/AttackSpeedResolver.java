package com.trinityforge.combat;

/**
 * Pure arithmetic behind {@code PerkAttributeApplier}'s player-level {@code Attribute.ATTACK_SPEED}
 * computation (2026-07-25仕様: {@code attack-speed}(絶対値・メインハンド専用・ADD_NUMBER) と
 * {@code attack-speed-bonus}(割合・全ソース横断・MULTIPLY_SCALAR_1) の分離)。Bukkit非依存、単体テスト
 * 可能(旧sentinel解決の {@code resolveNoCooldownAttackSpeedAmount} パターンを踏襲)。
 *
 * <p><b>Haste保持のための設計制約</b>: このクラスは常に「TF自身が把握している値」だけから計算し、
 * Bukkitのライブ属性値({@code AttributeInstance#getValue()})を一切読まない。ライブ値を読んで
 * 「目標値との差分」を逆算する設計だと、Hasteのような他システムが付けたmodifierの寄与を巻き込んで
 * 誤って相殺(=Haste無効化)してしまう。呼び出し側({@code PerkAttributeApplier})も同じ制約を守ること。
 */
public final class AttackSpeedResolver {

    /** バニラの {@code Attribute.ATTACK_SPEED} の素の基礎値。 */
    public static final double VANILLA_BASE = 4.0;

    /** 著者値が0以下(2-a: authoring-error)のときのフォールバック先。 */
    public static final double AUTHORING_FALLBACK = 4.0;

    /** {@code combat/damage.yml: attack-speed.min-effective} の既定値。 */
    public static final double DEFAULT_LOWER_BOUND = 0.1;

    private AttackSpeedResolver() {
    }

    /** {@code attack-speed}(絶対値)の解決結果。 */
    public record AbsoluteResult(double addNumberAmount, boolean authoringWarning) {
    }

    /**
     * メインハンドの {@code attack-speed} を、プレイヤー単位で足すべき {@code ADD_NUMBER} の量へ変換する。
     *
     * @param authoredMainhand    {@code null} = メインハンドで未定義。この場合TFは一切干渉しない方針
     *                             (2026-07-25決定b)なので、{@code itemOwnContribution}
     *                             (アイテム自身のバニラ材質既定分。二重計上を避けるため、バニラが暗黙
     *                             適用済みなら呼び出し側は0を渡す)をそのまま返す。
     * @param itemOwnContribution 上記参照。authoredMainhandがnullでないときは無視される(絶対値が
     *                             材質既定を完全に上書きするため)。
     * @param authoringFallback   0以下の著者値(2-a: authoring-error)のフォールバック先。通常
     *                             {@link #AUTHORING_FALLBACK}。
     */
    public static AbsoluteResult resolveAbsolute(Double authoredMainhand, double itemOwnContribution,
                                                 double authoringFallback) {
        if (authoredMainhand == null) {
            return new AbsoluteResult(itemOwnContribution, false);
        }
        double authored = authoredMainhand;
        boolean warning = false;
        if (!Double.isFinite(authored) || authored <= 0.0) {
            warning = true;
            authored = authoringFallback;
        }
        return new AbsoluteResult(authored - VANILLA_BASE, warning);
    }

    /** メインハンド未定義時に、絶対値計算で使う「実効速度(クランプ前)」を求める。 */
    public static double effectiveBeforeBonus(Double authoredMainhand, double itemOwnContribution,
                                              double authoringFallback) {
        if (authoredMainhand == null) {
            return VANILLA_BASE + itemOwnContribution;
        }
        double authored = authoredMainhand;
        if (!Double.isFinite(authored) || authored <= 0.0) {
            authored = authoringFallback;
        }
        return authored;
    }

    /**
     * {@code attack-speed-bonus} の合成割合(全ソース合算、0.10=+10%)を {@code MULTIPLY_SCALAR_1} の
     * amount(= x, {@code total *= (1+x)})へ変換する。{@code effectiveBeforeBonus}(このボーナス適用前の
     * 実効速度)を使い、「最終実効速度が {@code lowerBound} を割らない」よう x をその場でクランプする
     * (2-b: computed-result≤0はexploitableなため4.0へは戻さず下限へクランプする、という決定を実現する)。
     *
     * @param bonusFraction      全ソース合算後の割合(負値=デバフも許容)
     * @param effectiveBeforeBonus attack-speed(絶対値)適用後、またはメインハンド未定義時のバニラ実効速度
     * @param lowerBound          {@code combat/damage.yml: attack-speed.min-effective}(既定0.1)
     */
    public static double resolveBonusMultiplyAmount(double bonusFraction, double effectiveBeforeBonus,
                                                     double lowerBound) {
        double floor = Double.isFinite(lowerBound) && lowerBound > 0.0 ? lowerBound : DEFAULT_LOWER_BOUND;
        double fraction = Double.isFinite(bonusFraction) ? bonusFraction : 0.0;
        double factor = 1.0 + fraction;
        if (effectiveBeforeBonus > 0.0) {
            double minFactor = floor / effectiveBeforeBonus;
            if (factor < minFactor) {
                factor = minFactor;
            }
        }
        return factor - 1.0;
    }
}
