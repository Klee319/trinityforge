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
 * <h2>2. attack-power の高レベル区間 — 要件#63の残り</h2>
 * HP 側には {@code max-health-high-level-*} が入っているのに攻撃力側にはキーごと無く、
 * フィールドモブが「硬いだけで痛くない」方向へ片寄っていた。全エントリに
 * {@code attack-power-high-level-from: 45 / -per-level: 0.25} が入っていることを縛る
 * (ダンジョン側 {@code combat/mob-import.yml} と同値)。
 * 1エントリでも欠けると、そのモブだけ高レベル帯で無害になるが実プレイでは気づけない。
 */
class ShippedMobTypesLevelBandTest {

    private static final Logger LOG = Logger.getLogger("ShippedMobTypesLevelBandTest");
    private static final double DELTA = 1.0e-9;

    /** 出荷値。ダンジョン側 combat/mob-import.yml の attack-power と同じ。 */
    private static final double EXPECTED_ATTACK_HIGH_LEVEL_FROM = 45.0;
    private static final double EXPECTED_ATTACK_HIGH_LEVEL_PER_LEVEL = 0.25;

    /**
     * 出荷値。{@code dimensions.<ENV>.base-level} は<b>意図的に空(=0)</b>。
     * 理由は {@code shippedDimensionBaseLevelsAreDeliberatelyEmpty} の Javadoc を読むこと。
     */
    private static final int EXPECTED_DIMENSION_BASE_LEVEL = 0;

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
     * 出荷 {@code dimensions:} は<b>意図的に空</b>である、という状態を固定する。
     *
     * <p>空である限り {@code MobTypeSpawnListener#applyDimensionLevelBonus} は
     * {@code if (bonus == 0) return;} で必ず早期returnし、直後の
     * {@code reapplyDimensionBonusMaxHealth}(次元下駄をHPへ反映する既存修正)へ制御が到達しない。
     * <b>これは既知の状態であって、このテストはそれを追認するものではない</b> ——
     * 「埋めるとどうなるか」を検算した結果が割に合わなかったので空に戻した、という決定を固定する。
     *
     * <p><b>検算結果(2026-08-03)</b>: 下駄は各モブの {@code level} に加算され、
     * {@code max-health-high-level-from}(=45)をまたぐと per-level(モブごとに 554〜1293)が
     * 毎レベル加算される指数区間に入る。{@code THE_END: 45} は
     * 「エンドの全モブを発火点ちょうどに置く」設定になり、ENDERMAN(level:0 / 560HP /
     * growth 1.048 / per-level 1293.02)は本島 ≒Lv47 で 615HP → <b>7,653HP(約12倍)</b>、
     * 外周1,000ブロック ≒Lv65 で <b>約26倍</b>。さらに MOB_LEVEL は EXP 帯と
     * {@code drops[].quality} も駆動するので報酬側も同時に跳ねる。
     * ネザーは各モブが既に level 25〜40 を持っているため +20 は二重計上になる。
     *
     * <p>埋めると決めたときは、このテストの期待値を変えるだけでなく
     * <b>上の倍率を実測し直してから</b>にすること。
     */
    @Test
    @DisplayName("出荷 mob-types.yml: 次元の基準レベルは意図的に空のまま"
            + "(埋めるとエンドのモブHPが約12〜26倍になるので、決定なしに値を入れさせない)")
    void shippedDimensionBaseLevelsAreDeliberatelyEmpty(@TempDir Path dir) throws Exception {
        MobTypesConfig config = loadShipped(dir);

        assertEquals(EXPECTED_DIMENSION_BASE_LEVEL, config.dimensionBaseLevel(World.Environment.NETHER),
                "dimensions.NETHER.base-level に値が入っている。ネザーのモブは既に level 25〜40 を"
                        + "持っているので下駄は二重計上になる。入れる判断をしたなら Javadoc の検算をやり直すこと");
        assertEquals(EXPECTED_DIMENSION_BASE_LEVEL, config.dimensionBaseLevel(World.Environment.THE_END),
                "dimensions.THE_END.base-level に値が入っている。45 を入れるとエンドの全モブが"
                        + "max-health-high-level-from(45)の指数区間に乗り、ENDERMAN で約12倍(外周で約26倍)になる。"
                        + "入れるなら 20〜30 から刻み、EXP帯とドロップ品質への波及も併せて検算すること");
        assertEquals(0, config.dimensionBaseLevel(World.Environment.NORMAL),
                "オーバーワールドに下駄が入っている。mob-types の各 level とバランス表の前提が崩れる");
    }

    @Test
    @DisplayName("出荷 mob-types.yml: ネザー/エンドの coordinate-coefficient は意図的に未設定のまま"
            + "(設定するとモブ側の係数を置き換えて全モブを平らにするため)")
    void shippedDimensionsDoNotOverrideCoordinateCoefficient(@TempDir Path dir) throws Exception {
        MobTypesConfig config = loadShipped(dir);

        assertTrue(config.dimensionCoordinateCoefficient(World.Environment.NETHER).isEmpty(),
                "ネザーの coordinate-coefficient が設定されている。この上書きはモブ側の係数を掛けるのではなく"
                        + "置き換えるため、意図せず全モブの距離カーブを平らにする。入れるなら yml の"
                        + "コメントにある通り 0.04〜0.08 から刻んで検証すること");
        assertTrue(config.dimensionCoordinateCoefficient(World.Environment.THE_END).isEmpty(),
                "エンドの coordinate-coefficient が設定されている。同上");
    }

    // ------------------------------------------------------------------------------------------
    // 2. attack-power の高レベル区間
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("出荷 mob-types.yml: level-coefficients.attack を持つ全エントリに"
            + " attack-power-high-level-from: 45 / -per-level: 0.25 が入っている")
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
                "attack-power の高レベル区間が入っていないエントリがある。そのモブだけ Lv45 以降で"
                        + "攻撃力が伸びず「硬いだけで痛くない」に戻る。該当: " + missing);
        assertTrue(wrongValue.isEmpty(),
                "attack-power の高レベル区間がダンジョン側(combat/mob-import.yml の from 45 / "
                        + "per-level 0.25)と揃っていない。フィールドとダンジョンで被弾の伸びがずれる。該当: "
                        + wrongValue);
    }

    @Test
    @DisplayName("出荷 mob-types.yml: 攻撃力の高レベル区間がロード後に実値として引ける"
            + "(Lv44 は 0 / Lv60 は +3.75 / Lv100 は +13.75)")
    void shippedAttackPowerHighLevelPhaseIsLoadedAndComputesTheExpectedBonus(@TempDir Path dir)
            throws Exception {
        MobTypesConfig config = loadShipped(dir);

        MobTypesConfig.AttackPowerHighLevelPhase phase = config.attackPowerHighLevel(EntityType.ZOMBIE);
        assertEquals(EXPECTED_ATTACK_HIGH_LEVEL_FROM, phase.from(), DELTA,
                "ZOMBIE の attack-power-high-level-from が出荷値でない");
        assertEquals(EXPECTED_ATTACK_HIGH_LEVEL_PER_LEVEL, phase.perLevel(), DELTA,
                "ZOMBIE の attack-power-high-level-per-level が出荷値でない");

        assertEquals(0.0, phase.bonusAt(44), DELTA, "Lv45 未満は 1 ミリも変わらないこと");
        assertEquals(0.0, phase.bonusAt(45), DELTA, "開始レベルちょうどでは 0(境界で不連続にならない)");
        assertEquals(3.75, phase.bonusAt(60), DELTA, "Lv60 は 0.25 × 15 = +3.75");
        assertEquals(8.75, phase.bonusAt(80), DELTA, "Lv80 は 0.25 × 35 = +8.75");
        assertEquals(13.75, phase.bonusAt(100), DELTA, "Lv100 は 0.25 × 55 = +13.75");
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
