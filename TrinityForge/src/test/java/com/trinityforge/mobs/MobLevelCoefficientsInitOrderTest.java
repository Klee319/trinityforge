package com.trinityforge.mobs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MobLevelCoefficients} の静的初期化が<b>どちらの順序でも</b>成立することを固定する
 * (2026-08-01)。
 *
 * <p><b>何が壊れていたか</b>: 入れ子レコード {@code DefenseCoeffs}/{@code AttackCoeffs} の
 * コンストラクタは外側クラスの静的メソッド {@code finiteOrZero} を呼ぶ。静的メソッド呼び出しは
 * 外側クラスの初期化を強制するため、入れ子側が先に初期化されると
 * {@code DefenseCoeffs.<clinit>} → {@code new DefenseCoeffs(..)} → {@code finiteOrZero}
 * → {@code MobLevelCoefficients.<clinit>} → {@code ZERO = new MobLevelCoefficients(.., DefenseCoeffs.ZERO, ..)}
 * となり、{@code DefenseCoeffs.ZERO} がまだ null のまま {@code requireNonNull(physical)} に渡って
 * {@code ExceptionInInitializerError} になっていた。以後その JVM では
 * {@code NoClassDefFoundError: Could not initialize class MobLevelCoefficients} が出続ける
 * = モブのレベルスケーリングが丸ごと死ぬ。
 *
 * <p><b>なぜ気づきにくいか</b>: 発火するのは「最初に触ったクラスが入れ子側だったとき」だけ。
 * 通常はどこかが先に外側 {@code MobLevelCoefficients} を触るので何も起きない。
 * 実際、無関係なテストクラスを1本追加してテストの実行順が変わっただけで、
 * 戦闘・モブ系の 207 件が一斉に落ちた(2026-08-01)。実サーバでもクラスの初回参照順が変われば起きる。
 *
 * <p><b>なぜ専用のクラスローダを使うか</b>: クラスの初期化は JVM ごとに1回きりなので、
 * 通常のテストでは「どちらが先か」を選べない(他のテストが既に初期化済みかどうかに依存する)。
 * プラットフォームクラスローダを親にした {@link URLClassLoader} で読み直すと、この2クラスだけを
 * 未初期化の状態から狙った順序で初期化できる。
 */
class MobLevelCoefficientsInitOrderTest {

    private static final String OUTER = "com.trinityforge.mobs.MobLevelCoefficients";
    private static final String DEFENSE = OUTER + "$DefenseCoeffs";
    private static final String ATTACK = OUTER + "$AttackCoeffs";

    @Test
    @DisplayName("DefenseCoeffs を先に初期化しても外側の静的初期化が壊れない")
    void initializingDefenseCoeffsFirstDoesNotBreakOuterClinit() throws Exception {
        try (URLClassLoader loader = freshLoader()) {
            Class<?> defense = assertDoesNotThrow(() -> Class.forName(DEFENSE, true, loader),
                    "DefenseCoeffs を先に初期化すると外側の <clinit> が ExceptionInInitializerError になる"
                            + "(以後 NoClassDefFoundError が出続け、モブのレベルスケーリングが死ぬ)");
            assertNotNull(defense.getField("ZERO").get(null), "DefenseCoeffs.ZERO が null");
            assertNotNull(Class.forName(OUTER, true, loader).getField("ZERO").get(null),
                    "MobLevelCoefficients.ZERO が null");
        }
    }

    @Test
    @DisplayName("AttackCoeffs を先に初期化しても外側の静的初期化が壊れない")
    void initializingAttackCoeffsFirstDoesNotBreakOuterClinit() throws Exception {
        try (URLClassLoader loader = freshLoader()) {
            Class<?> attack = assertDoesNotThrow(() -> Class.forName(ATTACK, true, loader),
                    "AttackCoeffs を先に初期化すると外側の <clinit> が ExceptionInInitializerError になる");
            assertNotNull(attack.getField("ZERO").get(null), "AttackCoeffs.ZERO が null");
            assertNotNull(Class.forName(OUTER, true, loader).getField("ZERO").get(null),
                    "MobLevelCoefficients.ZERO が null");
        }
    }

    @Test
    @DisplayName("外側を先に初期化する従来の順序でも当然壊れない")
    void initializingOuterFirstStillWorks() throws Exception {
        try (URLClassLoader loader = freshLoader()) {
            Class<?> outer = Class.forName(OUTER, true, loader);
            assertNotNull(outer.getField("ZERO").get(null), "MobLevelCoefficients.ZERO が null");
            assertNotNull(Class.forName(DEFENSE, true, loader).getField("ZERO").get(null),
                    "DefenseCoeffs.ZERO が null");
        }
    }

    /**
     * {@code MobLevelCoefficients} を未初期化から読み直すためのクラスローダ。親をプラットフォーム
     * クラスローダにして、アプリのクラスパス(＝初期化済みかもしれない方)へ委譲させない。
     * このクラス自身の依存は {@code java.util.Objects} だけなので、プラットフォーム側で足りる。
     */
    private static URLClassLoader freshLoader() {
        URL classes = MobLevelCoefficients.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(classes, "クラスの配置場所を特定できない");
        return new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader());
    }
}
