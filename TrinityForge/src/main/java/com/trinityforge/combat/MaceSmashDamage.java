package com.trinityforge.combat;

/**
 * バニラのメイス「スマッシュ攻撃」(落下攻撃)によるダメージ加算を再現する Bukkit 非依存の純粋関数群。
 *
 * <p>TF は {@code item-stats.yml} で {@code MACE} に {@code attack-power} を定義しているため、
 * {@code CombatListener} の {@code tfBaseReplaces} 契約により {@code vanillaBaseDamage}(=バニラが
 * スマッシュ加算/Densityエンチャント加算まで含めて計算済みの値)を丸ごと捨ててTFの値へ置換してしまう
 * — 2026-07-25 に修正した Sweeping Edge 消失バグと全く同じ根本原因。ここではバニラのスマッシュ判定と
 * 加算式だけを切り出し、{@code CombatListener} 側で MACE かつスマッシュ条件成立時にだけ
 * {@code baseDamage} へ明示的に足し戻す(バニラのスマッシュ非対象の通常メイス攻撃では何も加算しない
 * ので、通常攻撃の挙動は変わらない)。
 *
 * <p>一次情報(2026-07-25 minecraft.wiki 確認, "Mace" / "Density" 記事):
 * <ul>
 *   <li>スマッシュ発動条件: 落下距離が 1.5 ブロック以上、かつ非接地、かつ非滑空(エリトラ飛行中でない)、
 *       かつスロウフォーリング未付与。攻撃クールダウン(チャージ度合い)には無関係に発動する。</li>
 *   <li>スマッシュ加算(階層式, HP): 最初の3ブロック分は+4/ブロック、次の5ブロック分(4〜8ブロック目)は
 *       +2/ブロック、9ブロック目以降は+1/ブロック。理論上上限なし。</li>
 *   <li>Density(重撃)エンチャント: レベルごとに +0.5 ダメージ/落下ブロック を追加する。バニラでも
 *       スマッシュ階層式とは別の独立した加算項として単純に和算されるため、二重計上ではない。</li>
 * </ul>
 */
public final class MaceSmashDamage {

    /** スマッシュ攻撃発動に必要な最小落下距離(ブロック)。 */
    private static final double SMASH_MIN_FALL_DISTANCE = 1.5;

    /** 落下距離3ブロックまでの階層(1ブロックあたりの加算)。 */
    private static final double TIER1_BLOCKS = 3.0;
    private static final double TIER1_PER_BLOCK = 4.0;
    /** 落下距離4〜8ブロック目の階層。 */
    private static final double TIER2_BLOCKS = 5.0;
    private static final double TIER2_PER_BLOCK = 2.0;
    /** 9ブロック目以降は上限なしでこの単価。 */
    private static final double TIER3_PER_BLOCK = 1.0;

    /** Density: レベルごとの追加ダメージ/落下ブロック。 */
    private static final double DENSITY_PER_LEVEL_PER_BLOCK = 0.5;

    private MaceSmashDamage() {
    }

    /**
     * バニラのスマッシュ攻撃発動条件を満たすか(一次情報: minecraft.wiki "Mace")。落下距離 &gt;= 1.5、
     * 非接地、非滑空、スロウフォーリング未付与のすべてを満たす必要がある。
     */
    public static boolean isSmashAttack(double fallDistance, boolean onGround, boolean gliding,
                                         boolean slowFalling) {
        return fallDistance >= SMASH_MIN_FALL_DISTANCE && !onGround && !gliding && !slowFalling;
    }

    /**
     * バニラのスマッシュ階層式によるダメージ加算(一次情報: minecraft.wiki "Mace")。スマッシュ非発動
     * ({@code fallDistance < 1.5})なら {@code 0}。落下距離は連続値として扱う(バニラのY座標差そのもの)。
     */
    public static double smashTierBonus(double fallDistance) {
        if (!Double.isFinite(fallDistance) || fallDistance < SMASH_MIN_FALL_DISTANCE) {
            return 0.0;
        }
        double remaining = fallDistance;
        double bonus = 0.0;

        double tier1 = Math.min(remaining, TIER1_BLOCKS);
        bonus += tier1 * TIER1_PER_BLOCK;
        remaining -= tier1;
        if (remaining <= 0.0) {
            return bonus;
        }

        double tier2 = Math.min(remaining, TIER2_BLOCKS);
        bonus += tier2 * TIER2_PER_BLOCK;
        remaining -= tier2;
        if (remaining <= 0.0) {
            return bonus;
        }

        bonus += remaining * TIER3_PER_BLOCK;
        return bonus;
    }

    /**
     * Density(重撃)エンチャントによる追加ダメージ(一次情報: minecraft.wiki "Density"): レベル × 0.5 ×
     * 落下ブロック数。スマッシュ非発動またはレベル0以下なら {@code 0}。
     */
    public static double densityBonus(double fallDistance, int level) {
        if (level <= 0 || !Double.isFinite(fallDistance) || fallDistance < SMASH_MIN_FALL_DISTANCE) {
            return 0.0;
        }
        return level * DENSITY_PER_LEVEL_PER_BLOCK * fallDistance;
    }
}
