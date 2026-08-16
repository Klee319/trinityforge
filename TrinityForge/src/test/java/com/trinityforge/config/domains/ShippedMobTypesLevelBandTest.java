package com.trinityforge.config.domains;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code combat/mob-types.yml} の「レベル帯」に関する2つの値を固定する(2026-08-03)。
 *
 * <h2>1. dimensions(次元の基準レベル) — 要件#23</h2>
 * ここが {@code dimensions: {}} に戻ると、
 * {@code MobTypeSpawnListener#applyDimensionLevelBonus} の {@code if (bonus == 0) return;} で
 * 必ず早期returnするため、<b>その直後の {@code reapplyDimensionBonusMaxHealth}
 * (「次元の下駄が攻撃力だけ上げてHPを上げていない」という指摘への修正)へ制御が一度も到達しない</b>。
 * つまり空に戻すと「下駄が乗らない」だけでなく「下駄をHPへ反映する修正そのものが死ぬ」。
 * 空でも yml のロード自体は成功してしまい、警告も出ないので、ここで値そのものを縛る。
 *
 * <h2>2. attack-power の高レベル区間 — 要件#63 → 2026-08-10 火力再較正で撤去</h2>
 * 要件#63(2026-08-03)では HP 側に {@code max-health-high-level-*} が入っているのに攻撃力側に
 * キーごと無く、フィールドモブが「硬いだけで痛くない」方向へ片寄っていたため、いったん
 * {@code attack-power-high-level-per-level: 0.25}(ダンジョン側 combat/mob-import.yml と同値)を
 * 全エントリへ入れて揃えた。
 *
 * <p><b>2026-08-10 の火力/防御再較正でこの上乗せは 0 へ撤去された</b>(base の
 * {@code attack-power-growth} を 1.033→1.02 へ寝かせつつ素の attack-power を ×1.4545 底上げ、かつ
 * {@code max-health-high-level-per-level} を ×2.5 する形で HP 側の高レベル加速だけを残す設計へ変更)。
 * {@code attack-power-high-level-from: 45} キー自体は残っているが per-level が 0 なので常時無干渉。
 * <b>2026-08-12 の追補</b>で {@code attack-power-growth} はさらに 1.02→1.0148 へ寝かせ
 * (Lv100 のプレイヤー最大HPを 166.7→100 に収めるため)、あわせてダンジョン側
 * {@code combat/mob-import.yml} の {@code attack-power} も同じ勾配
 * ({@code base: 10.2 / growth: 1.0148 / high-level-per-level: 0.0}) へ揃えた。
 * 揃えないと Lv100 でダンジョンモブだけ攻撃力が約4.3倍になり事実上の即死になる。
 * 1エントリでも 0.25 のような非0値が残っていると、そのモブだけ高レベル帯の攻撃力が
 * 再較正前の伸びに戻ってしまうので、全エントリが 0 で統一されていることを縛る。
 */
class ShippedMobTypesLevelBandTest {

    private static final Logger LOG = Logger.getLogger("ShippedMobTypesLevelBandTest");
    private static final double DELTA = 1.0e-9;

    /** 出荷値。閾値そのものは 2026-08-10 でも変えていない(per-level が 0 なので無干渉)。 */
    private static final double EXPECTED_ATTACK_HIGH_LEVEL_FROM = 45.0;
    /** 出荷値。2026-08-10 の火力再較正で撤去され、フィールドモブの高レベル加算は 0 に統一された。 */
    private static final double EXPECTED_ATTACK_HIGH_LEVEL_PER_LEVEL = 0.0;

    /**
     * 出荷値(2026-08-16 ユーザー決定)。{@code dimensions.<ENV>.base-level} を空から実値へ移した。
     * 理由と検算は {@code shippedDimensionBaseLevelsMatchTheDecidedValues} の Javadoc を読むこと。
     */
    private static final int EXPECTED_NETHER_BASE_LEVEL = 50;
    private static final int EXPECTED_THE_END_BASE_LEVEL = 80;
    private static final double EXPECTED_NETHER_COORDINATE_COEFFICIENT = 0.01;
    private static final double EXPECTED_THE_END_COORDINATE_COEFFICIENT = 0.004;

    @BeforeEach
    void setUp() {
        // EntityType の解決に Bukkit の初期化が要るため(既存の ResourceServerMobSimulationTest と同じ流儀)。
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------------------------------
    // 1. dimensions(次元の基準レベル)
    // ------------------------------------------------------------------------------------------

    /**
     * 出荷 {@code dimensions:} の実値を固定する。
     *
     * <p><b>2026-08-16 にユーザーが「入れる」と決定した</b>ので、このテストは
     * 「空であること」ではなく<b>決めた値そのもの</b>を固定する側へ作り替えた。
     * 空でなくなった時点で {@code MobTypeSpawnListener#applyDimensionLevelBonus} の
     * {@code if (bonus == 0) return;} を抜け、{@code reapplyDimensionBonusMaxHealth}
     * (次元下駄をHPへ反映する)まで制御が届くようになる。
     *
     * <p><b>この値が何を意味するかは 2026-08-03 の検算のまま変わっていない</b>ので、
     * 動かすときは必ず読むこと: 下駄は各モブの {@code level} に加算され、
     * {@code max-health-high-level-from}(=45)をまたぐと per-level(モブごとに 554〜1293)が
     * 毎レベル加算される指数区間に入る。ENDERMAN(level:0 / 560HP / growth 1.048 /
     * per-level 1293.02)は Lv47 で 615HP → <b>7,653HP(約12倍)</b>、Lv65 で<b>約26倍</b>。
     * {@code THE_END: 80} はこの指数区間の<b>かなり奥</b>に全モブを置く設定で、
     * さらに MOB_LEVEL は EXP 帯と {@code drops[].quality} も駆動するので報酬側も同時に跳ねる。
     * ネザーの各モブは既に level 25〜40 を持っているため {@code NETHER: 50} は
     * その上への加算(実効 75〜90)になる。
     *
     * <p>{@code coordinate-coefficient} の上書きは<b>モブ側の係数に掛けるのではなく置き換える</b>
     * ので、そのディメンションのバニラモブ全体の距離カーブが一律になる(EliteMobs モブには効かない)。
     * ネザーの 0.01 は 1,000 ブロックで +10、エンドの 0.004 は 1,000 ブロックで +4。
     *
     * <p>オーバーワールドだけは 0 のままであることを引き続き固定する ——
     * ここに下駄が入ると mob-types の各 {@code level} とバランス表の前提がまとめて崩れる。
     */
    @Test
    @DisplayName("出荷 mob-types.yml: 次元の基準レベルは 2026-08-16 に決めた実値(ネザー50/エンド80)で、"
            + "オーバーワールドだけは 0 のまま")
    void shippedDimensionBaseLevelsMatchTheDecidedValues(@TempDir Path dir) throws Exception {
        MobTypesConfig config = loadShipped(dir);

        assertEquals(EXPECTED_NETHER_BASE_LEVEL, config.dimensionBaseLevel(World.Environment.NETHER),
                "dimensions.NETHER.base-level が決定値と違う。ネザーのモブは既に level 25〜40 を"
                        + "持っているので、ここの値はその上への加算(実効 75〜90)になる。"
                        + "動かすなら Javadoc の検算をやり直すこと");
        assertEquals(EXPECTED_THE_END_BASE_LEVEL, config.dimensionBaseLevel(World.Environment.THE_END),
                "dimensions.THE_END.base-level が決定値と違う。max-health-high-level-from(45)の"
                        + "指数区間の奥に全モブを置く設定なので、EXP帯とドロップ品質への波及も併せて検算すること");
        assertEquals(0, config.dimensionBaseLevel(World.Environment.NORMAL),
                "オーバーワールドに下駄が入っている。mob-types の各 level とバランス表の前提が崩れる");
    }

    @Test
    @DisplayName("出荷 mob-types.yml: ネザー/エンドの coordinate-coefficient は 2026-08-16 に決めた実値"
            + "(この上書きはモブ側の係数を置き換えるので、そのディメンションの距離カーブが一律になる)")
    void shippedDimensionCoordinateCoefficientsMatchTheDecidedValues(@TempDir Path dir) throws Exception {
        MobTypesConfig config = loadShipped(dir);

        assertEquals(EXPECTED_NETHER_COORDINATE_COEFFICIENT,
                config.dimensionCoordinateCoefficient(World.Environment.NETHER).orElseThrow(
                        () -> new AssertionError("ネザーの coordinate-coefficient が未設定に戻っている")),
                DELTA,
                "ネザーの coordinate-coefficient が決定値と違う。モブ側の係数を掛けるのではなく置き換えるので、"
                        + "動かすとネザーのバニラモブ全体の距離カーブが一律に変わる");
        assertEquals(EXPECTED_THE_END_COORDINATE_COEFFICIENT,
                config.dimensionCoordinateCoefficient(World.Environment.THE_END).orElseThrow(
                        () -> new AssertionError("エンドの coordinate-coefficient が未設定に戻っている")),
                DELTA,
                "エンドの coordinate-coefficient が決定値と違う。同上");
        assertTrue(config.dimensionCoordinateCoefficient(World.Environment.NORMAL).isEmpty(),
                "オーバーワールドに coordinate-coefficient の上書きが入っている。"
                        + "mob-types の各エントリが持つ係数が丸ごと無効になる");
    }

    // ------------------------------------------------------------------------------------------
    // 2. attack-power の高レベル区間
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("出荷 mob-types.yml: level-coefficients.attack を持つ全エントリで"
            + " attack-power-high-level-from: 45 / -per-level: 0(2026-08-10 再較正で撤去済み)")
    void everyShippedAttackCoefficientBlockCarriesTheHighLevelPhase() throws Exception {
        ConfigurationSection types = shippedYaml().getConfigurationSection("mob-types");
        assertNotNull(types, "出荷 mob-types.yml に mob-types: 節が無い");

        List<String> missing = new ArrayList<>();
        List<String> wrongValue = new ArrayList<>();
        int checked = 0;
        for (String id : types.getKeys(false)) {
            ConfigurationSection attackCoeffs =
                    types.getConfigurationSection(id + ".level-coefficients.attack");
            if (attackCoeffs == null) {
                continue; // C群(完全受動モブ)は level-coefficients 自体を持たない = 対象外。
            }
            checked++;
            if (!attackCoeffs.isSet("attack-power-high-level-from")
                    || !attackCoeffs.isSet("attack-power-high-level-per-level")) {
                missing.add(id);
                continue;
            }
            double from = attackCoeffs.getDouble("attack-power-high-level-from");
            double perLevel = attackCoeffs.getDouble("attack-power-high-level-per-level");
            if (Math.abs(from - EXPECTED_ATTACK_HIGH_LEVEL_FROM) > DELTA
                    || Math.abs(perLevel - EXPECTED_ATTACK_HIGH_LEVEL_PER_LEVEL) > DELTA) {
                wrongValue.add(id + "={from=" + from + ", per-level=" + perLevel + "}");
            }
        }

        assertEquals(50, checked,
                "level-coefficients.attack を持つエントリ数が 50 でない(敵対41種＋反撃してくる中立9種)。"
                        + "エントリを足す/消すときは高レベル区間も一緒に入れること");
        assertTrue(missing.isEmpty(),
                "attack-power-high-level-from/-per-level のキー自体が無いエントリがある"
                        + "(0埋めの明示ではなく丸ごと未設定 = 将来 per-level を書き足すときの土台が無い)。該当: "
                        + missing);
        assertTrue(wrongValue.isEmpty(),
                "attack-power の高レベル区間が 2026-08-10 の再較正値(from=45 / per-level=0)から"
                        + "ずれている。0 でない値が残っていると、そのモブだけ再較正前の高レベル加算が"
                        + "生き残ってしまう。該当: " + wrongValue);
    }

    @Test
    @DisplayName("出荷 mob-types.yml: 攻撃力の高レベル区間がロード後に実値として引ける"
            + "(per-level=0 なので Lv44/45/60/80/100 のどこでも加算は常に0)")
    void shippedAttackPowerHighLevelPhaseIsLoadedAndComputesTheExpectedBonus(@TempDir Path dir)
            throws Exception {
        MobTypesConfig config = loadShipped(dir);

        MobTypesConfig.AttackPowerHighLevelPhase phase = config.attackPowerHighLevel(EntityType.ZOMBIE);
        assertEquals(EXPECTED_ATTACK_HIGH_LEVEL_FROM, phase.from(), DELTA,
                "ZOMBIE の attack-power-high-level-from が出荷値でない");
        assertEquals(EXPECTED_ATTACK_HIGH_LEVEL_PER_LEVEL, phase.perLevel(), DELTA,
                "ZOMBIE の attack-power-high-level-per-level が出荷値でない"
                        + "(2026-08-10 再較正で 0.25 → 0 に撤去されたはず)");

        // per-level=0 なので、from の前後どのレベルでも bonusAt は同じ式 per-level×(level-from) の
        // 結果として常に 0 になる(式自体は要件#63 時代のまま。値がゼロになっただけで機構は生きている)。
        for (int level : new int[] {0, 44, 45, 46, 60, 80, 100}) {
            double expected = EXPECTED_ATTACK_HIGH_LEVEL_PER_LEVEL
                    * Math.max(0, level - EXPECTED_ATTACK_HIGH_LEVEL_FROM);
            assertEquals(expected, phase.bonusAt(level), DELTA,
                    "Lv" + level + " の上乗せが 0 でない(per-level=0 のはずなのに加算が生きている)");
        }
    }

    // ------------------------------------------------------------------------------------------
    // 出荷 yml のロード
    // ------------------------------------------------------------------------------------------

    private static MobTypesConfig loadShipped(Path dir) throws IOException {
        Path target = dir.resolve(MobTypesConfig.PATH);
        Files.createDirectories(target.getParent());
        Files.writeString(target, shippedText(), StandardCharsets.UTF_8);
        MobTypesConfig config = new MobTypesConfig();
        assertTrue(config.load(fakePlugin(dir)),
                "出荷 " + MobTypesConfig.PATH + " のロードが警告付きで完了した(スキップされた定義がある)");
        return config;
    }

    private static YamlConfiguration shippedYaml() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(shippedText());
        return yaml;
    }

    /** 出荷 yml はテストクラスパス上の {@code combat/mob-types.yml}(= 真源の resources/ の中身)。 */
    private static String shippedText() throws IOException {
        try (InputStream in = ShippedMobTypesLevelBandTest.class.getClassLoader()
                .getResourceAsStream(MobTypesConfig.PATH)) {
            assertNotNull(in, "出荷 " + MobTypesConfig.PATH + " がテストクラスパスに無い");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * {@code getDataFolder}/{@code getLogger}/{@code saveResource} だけを返す {@link Plugin} 代役。
     * 既存の {@code MobLevelTableListenerTest} / {@code ops} スイートと同じ Proxy パターン。
     */
    private static Plugin fakePlugin(Path dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder.toFile();
            case "getLogger" -> LOG;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin[ShippedMobTypesLevelBandTest]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, handler);
    }
}
