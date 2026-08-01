package com.trinityforge.mobs;

import java.util.Objects;

/**
 * Per-stat level scaling coefficients for a mob type (or untagged defaults).
 * Effective value = {@code base + coefficient * effectiveLevel}. Rate fields are clamped to
 * {@code [0, 1]} after scaling.
 *
 * <p>{@code maxHealth}/{@code maxHealthGrowth}/{@code maxHealthGrowthInterval} together form the
 * per-level part of a {@link ConversionPolicy.Ramp} for max-health (see
 * {@code MobStatScaling#scaleMaxHealth}), and {@code AttackCoeffs#attackPower}/{@code
 * attackPowerGrowth}/{@code attackPowerGrowthInterval} do the same for the attacker-side
 * attack-power (see {@code MobStatScaling#scaleAttack}): {@code effective = (base + coeff * level)
 * * growth^(level / growthInterval)}. {@code growth == 1.0} (the default) makes the geometric term
 * vanish, so every pre-existing config keeps its exact linear behaviour. Growth is intentionally
 * scoped to max-health and attack-power only for now; other stats stay purely linear, but follow
 * the same {@code <field>}/{@code <field>Growth}/{@code <field>GrowthInterval} shape so a future
 * stat can opt in the same way.
 */
public record MobLevelCoefficients(
        double maxHealth,
        double armorStrength,
        DefenseCoeffs physical,
        DefenseCoeffs magical,
        AttackCoeffs attack,
        double maxHealthGrowth,
        double maxHealthGrowthInterval) {

    /**
     * <b>入れ子レコードの {@code ZERO} を参照してはいけない</b>(2026-08-01 修正)。
     *
     * <p>{@code DefenseCoeffs}/{@code AttackCoeffs} のコンストラクタは外側クラスの静的メソッド
     * {@link #finiteOrZero} を呼ぶ。静的メソッドの呼び出しは外側クラスの初期化を強制するので、
     * <b>入れ子側が先に初期化された場合</b>(例: どこかが {@code DefenseCoeffs.ZERO} を先に触る)、
     * 順序はこうなる:
     * {@code DefenseCoeffs.<clinit>} → {@code new DefenseCoeffs(..)} → {@code finiteOrZero}
     * → {@code MobLevelCoefficients.<clinit>} → ここ。
     * このとき {@code DefenseCoeffs.ZERO} は<b>まだ代入されておらず null</b> なので、
     * 下のコンパクトコンストラクタの {@code requireNonNull(physical)} が
     * {@code ExceptionInInitializerError} を投げ、以後この JVM では
     * {@code NoClassDefFoundError: Could not initialize class MobLevelCoefficients} が出続ける
     * (=モブのレベルスケーリングが丸ごと死ぬ)。どちらの順序で初期化されるかは
     * 「最初にどのクラスに触ったか」だけで決まるため、実サーバでも再現しうる。
     *
     * <p>そこで定数を参照せず<b>その場で生成する</b>。同一スレッドの再帰的な初期化は JVM が許すので、
     * 入れ子側が初期化中でもインスタンス生成は成功し、null が入らない。
     */
    public static final MobLevelCoefficients ZERO = new MobLevelCoefficients(
            0.0, 0.0,
            new DefenseCoeffs(0.0, 0.0, 0.0, 0.0),
            new DefenseCoeffs(0.0, 0.0, 0.0, 0.0),
            new AttackCoeffs(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));

    public MobLevelCoefficients {
        Objects.requireNonNull(physical, "physical");
        Objects.requireNonNull(magical, "magical");
        Objects.requireNonNull(attack, "attack");
        maxHealth = finiteOrZero(maxHealth);
        armorStrength = finiteOrZero(armorStrength);
        if (!Double.isFinite(maxHealthGrowth) || maxHealthGrowth < 0.0) {
            maxHealthGrowth = 1.0;
        }
        if (!(maxHealthGrowthInterval > 0.0) || !Double.isFinite(maxHealthGrowthInterval)) {
            maxHealthGrowthInterval = 1.0;
        }
    }

    /** Back-compat: attack coeffs default to zero; max-health growth defaults to 1.0 (linear). */
    public MobLevelCoefficients(double maxHealth, double armorStrength,
                                DefenseCoeffs physical, DefenseCoeffs magical) {
        this(maxHealth, armorStrength, physical, magical, AttackCoeffs.ZERO, 1.0, 1.0);
    }

    /** Back-compat: max-health growth defaults to 1.0 (linear, pre-growth behaviour). */
    public MobLevelCoefficients(double maxHealth, double armorStrength,
                                DefenseCoeffs physical, DefenseCoeffs magical,
                                AttackCoeffs attack) {
        this(maxHealth, armorStrength, physical, magical, attack, 1.0, 1.0);
    }

    public record DefenseCoeffs(
            double defenseRate,
            double resistance,
            double damageReduction,
            double flatDefense) {

        public static final DefenseCoeffs ZERO = new DefenseCoeffs(0.0, 0.0, 0.0, 0.0);

        public DefenseCoeffs {
            defenseRate = finiteOrZero(defenseRate);
            resistance = finiteOrZero(resistance);
            damageReduction = finiteOrZero(damageReduction);
            flatDefense = finiteOrZero(flatDefense);
        }
    }

    public record AttackCoeffs(
            double attackPower,
            double flatBonusDamage,
            double percentBonusDamage,
            double penetration,
            double critChance,
            double critDamage,
            double damageModifier,
            double fixedDamage,
            double attackPowerGrowth,
            double attackPowerGrowthInterval) {

        public static final AttackCoeffs ZERO = new AttackCoeffs(0, 0, 0, 0, 0, 0, 0, 0, 1.0, 1.0);

        public AttackCoeffs {
            attackPower = finiteOrZero(attackPower);
            flatBonusDamage = finiteOrZero(flatBonusDamage);
            percentBonusDamage = finiteOrZero(percentBonusDamage);
            penetration = finiteOrZero(penetration);
            critChance = finiteOrZero(critChance);
            critDamage = finiteOrZero(critDamage);
            damageModifier = finiteOrZero(damageModifier);
            fixedDamage = finiteOrZero(fixedDamage);
            if (!Double.isFinite(attackPowerGrowth) || attackPowerGrowth < 0.0) {
                attackPowerGrowth = 1.0;
            }
            if (!(attackPowerGrowthInterval > 0.0) || !Double.isFinite(attackPowerGrowthInterval)) {
                attackPowerGrowthInterval = 1.0;
            }
        }

        /** Back-compat: attack-power growth defaults to 1.0 (linear, pre-growth behaviour). */
        public AttackCoeffs(double attackPower, double flatBonusDamage, double percentBonusDamage,
                            double penetration, double critChance, double critDamage,
                            double damageModifier, double fixedDamage) {
            this(attackPower, flatBonusDamage, percentBonusDamage, penetration, critChance,
                    critDamage, damageModifier, fixedDamage, 1.0, 1.0);
        }
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
