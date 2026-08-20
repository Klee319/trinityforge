package com.trinityforge.config.domains;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.domains.MobOverridesConfig.ParseResult;
import com.trinityforge.mobs.MobProfile;
import com.trinityforge.mobs.MobStatOverride;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code combat/mob-overrides.yml} の<b>倍率キー</b>({@code stats.max-health-multiplier} /
 * {@code stats.attack-power-multiplier}、2026-08-14 「ダンジョンの難易度を敵の強さの差で表現する」要望)。
 *
 * <p>絶対値上書きだけではダイナミックダンジョン(プレイヤーが入場時にレベルを選ぶ 265 体)と両立しない
 * ため新設したキーなので、ここで固定するのは「<b>元の値に対する相対倍率として効くこと</b>」と
 * 「<b>書かなければ 1 ミリも挙動が変わらないこと</b>」の 2 点が中心。値の検証(0/負値/非数値)を
 * 警告付きで捨てることも固定する —— このリポジトリの事故は「無言で効かない/無言で別のことが起きる」が
 * 大半なので、捨てたことがログに出ない実装は回帰とみなす。
 */
class MobOverridesMultiplierTest {

    private static final Logger LOG = Logger.getLogger("MobOverridesMultiplierTest");

    // --- helpers -------------------------------------------------------------------------------

    /** HP 100 / attack-power 10 / Lv10 の基底プロファイル(= ランプとレベルが決めた「本来の強さ」)。 */
    private static MobProfile baseProfile() {
        DefenseStats physical = new DefenseStats(0.1, 0.1, 0.1, 5.0, 0.2);
        DefenseStats magical = new DefenseStats(0.1, 0.1, 0.1, 5.0, 0.2);
        return new MobProfile("goblin_chief", 10, null, physical, magical, AttackStats.plain(10.0), 100.0, false);
    }

    private static ParseResult parse(String yaml, Logger log) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().pathSeparator(MobOverridesConfig.MOB_ID_SAFE_PATH_SEPARATOR);
        cfg.loadFromString(yaml);
        return MobOverridesConfig.parse(cfg, log);
    }

    private static ParseResult parse(String yaml) throws Exception {
        return parse(yaml, LOG);
    }

    /** Full {@code load()} pipeline: scope 直下の {@code stats:} も含めて配線された config を返す。 */
    private static MobOverridesConfig loadedConfig(File dataFolder, String yaml) throws Exception {
        File file = new File(dataFolder, MobOverridesConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        MobOverridesConfig config = new MobOverridesConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    private static org.bukkit.plugin.Plugin fakePlugin(File dataFolder) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> LOG;
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class}, handler);
    }

    /** 警告ログを実際に採取するロガー(匿名ロガーなのでテスト間でハンドラが積み上がらない)。 */
    private static Logger capturingLogger(List<String> sink) {
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                sink.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return log;
    }

    private static boolean anyContains(List<String> messages, String... fragments) {
        for (String message : messages) {
            boolean all = true;
            for (String fragment : fragments) {
                all &= message != null && message.contains(fragment);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    /** その scope の mob 単位エントリの stats。 */
    private static MobStatOverride statsOf(ParseResult r, String scope, String mobId) {
        return r.scopes().get(scope).get(mobId).stats();
    }

    // --- 倍率が「元の値に対する相対倍率」として効く ------------------------------------------------

    @Test
    void multiplierAloneScalesTheValueTheChosenLevelDecided() throws Exception {
        // 難易度の本来の使い方: 絶対値を一切書かず、レベルが決めた値の N 倍にする。
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health-multiplier: 3.0
                          attack-power-multiplier: 2.5
                """);
        assertEquals(0, r.skipped());

        MobProfile result = statsOf(r, "default", "goblin_chief").applyTo(baseProfile());
        assertEquals(300.0, result.maxHealth(), 1.0e-9, "100 * 3.0");
        assertEquals(25.0, result.attack().defaultDamage(), 1.0e-9, "10 * 2.5");
        // 倍率が巻き込んではいけないもの(会心率などを一緒に伸ばすと別軸で破綻する)。
        assertEquals(10, result.level());
        assertEquals(0.2, result.armorStrength());
        assertEquals(baseProfile().attack().critChance(), result.attack().critChance());
        assertEquals(baseProfile().physical(), result.physical());
    }

    @Test
    void absoluteValueIsAppliedFirstThenTheMultiplier() throws Exception {
        // 併記した場合の順序仕様: 絶対値で置き換えてから倍率を掛ける(1000 * 3 = 3000)。
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 1000
                          max-health-multiplier: 3.0
                          attack-power-multiplier: 2.0
                          attack:
                            attack-power: 50
                """);
        assertEquals(0, r.skipped());

        MobProfile result = statsOf(r, "default", "goblin_chief").applyTo(baseProfile());
        assertEquals(3000.0, result.maxHealth(), 1.0e-9, "絶対値1000を先に適用し、その結果に3.0を掛ける");
        assertEquals(100.0, result.attack().defaultDamage(), 1.0e-9, "絶対値50に2.0を掛ける");
    }

    @Test
    void scopeLevelMultiplierAppliesToEveryMobIncludingOnesWithNoEntry(@TempDir File dir) throws Exception {
        // ダンジョン全体の難易度は scope 直下に 1 行書くだけで全モブに乗る必要がある
        // (396体に1体ずつ書くのは非現実的、というのが scope 直下 stats: の存在意義)。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  em_hard_dungeon:
                    stats:
                      max-health-multiplier: 4.0
                      attack-power-multiplier: 1.5
                    mobs: {}
                """);
        MobProfile result = config.resolve("em_hard_dungeon_12", "a_mob_with_no_entry", baseProfile());
        assertEquals(400.0, result.maxHealth(), 1.0e-9);
        assertEquals(15.0, result.attack().defaultDamage(), 1.0e-9);

        // 別ダンジョンには一切乗らない。
        MobProfile elsewhere = config.resolve("em_other_dungeon_1", "a_mob_with_no_entry", baseProfile());
        assertEquals(100.0, elsewhere.maxHealth(), 1.0e-9);
        assertEquals(10.0, elsewhere.attack().defaultDamage(), 1.0e-9);
    }

    @Test
    void multipliersCompoundAcrossCascadeLayers(@TempDir File dir) throws Exception {
        // 倍率だけは「後勝ち」ではなく掛け合わさる(resolve が層ごとに applyTo を呼ぶため)。
        // = default に書いた倍率は全ダンジョンに乗る。難易度差だけを付けたいなら default に書かない。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    stats:
                      max-health-multiplier: 1.5
                    mobs: {}
                  em_hard_dungeon:
                    stats:
                      max-health-multiplier: 2.0
                    mobs:
                      goblin_chief:
                        stats:
                          max-health-multiplier: 2.0
                """);
        assertEquals(600.0, config.resolve("em_hard_dungeon_3", "goblin_chief", baseProfile()).maxHealth(),
                1.0e-9, "100 * 1.5(default) * 2.0(ダンジョン) * 2.0(モブ個別)");
        assertEquals(300.0, config.resolve("em_hard_dungeon_3", "other_mob", baseProfile()).maxHealth(),
                1.0e-9, "モブ個別が無ければ 100 * 1.5 * 2.0");
        assertEquals(150.0, config.resolve("unrelated_world", "other_mob", baseProfile()).maxHealth(),
                1.0e-9, "default の倍率は全ワールドに乗る");
    }

    @Test
    void absoluteValueInAHigherLayerDiscardsLowerLayerMultipliers(@TempDir File dir) throws Exception {
        // 【次フェーズ向けの落とし穴】ダンジョン全体に倍率を掛けても、そのモブが上位レイヤーで
        // 絶対値 max-health を書いていると、倍率が作った値ごと置き換わって無かったことになる
        // (絶対値上書きの定義どおりの挙動であって、バグではない)。
        // ただし出荷 yml でこれに当たるのは 411 体中ごく一部(2026-08-14 実測 23 体。件数は編集で
        // 動くので数え直すこと)。残りの 388 体は絶対値 HP を持たないのでダンジョン単位の倍率は
        // そのまま効く ——「per-mob を全部触らないと倍率が効かない」という話ではない
        // (旧 javadoc の「396 体が per-mob の絶対値を持つ」は誤り)。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  em_hard_dungeon:
                    stats:
                      max-health-multiplier: 4.0
                      attack-power-multiplier: 4.0
                    mobs:
                      absolute_mob:
                        stats:
                          max-health: 1000
                      multiplier_mob:
                        stats:
                          max-health-multiplier: 2.0
                """);
        assertEquals(1000.0, config.resolve("em_hard_dungeon_1", "absolute_mob", baseProfile()).maxHealth(),
                1.0e-9, "絶対値が scope 直下の 4.0 倍(=400)を捨てて 1000 に置き換える");
        assertEquals(40.0,
                config.resolve("em_hard_dungeon_1", "absolute_mob", baseProfile()).attack().defaultDamage(),
                1.0e-9, "絶対値を書いていない攻撃側は 10 * 4.0 のまま生きる");
        assertEquals(800.0, config.resolve("em_hard_dungeon_1", "multiplier_mob", baseProfile()).maxHealth(),
                1.0e-9, "倍率で書けば 100 * 4.0 * 2.0 と積み上がる");
    }

    // --- 書かなければ完全な no-op --------------------------------------------------------------

    @Test
    void absentMultipliersAreAnExactNoOp() throws Exception {
        // 「既存 config の挙動を 1 ミリも変えない」の固定。max-health だけ書いた従来の記述で、
        // 攻撃側は【同一インスタンス】のまま(= 倍率コードが素通りしていること)を要求する。
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 500
                """);
        assertEquals(0, r.skipped());

        MobProfile base = baseProfile();
        MobProfile result = statsOf(r, "default", "goblin_chief").applyTo(base);
        assertEquals(500.0, result.maxHealth(), 1.0e-9, "倍率が無いので絶対値がそのまま");
        assertSame(base.attack(), result.attack(), "attack は再構築すらされない");
        assertSame(base.physical(), result.physical());
        assertSame(base.magical(), result.magical());
    }

    @Test
    void statsBlockWithNoKeysStillReturnsTheBaseInstance() throws Exception {
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats: {}
                """);
        assertEquals(0, r.skipped());
        MobProfile base = baseProfile();
        assertSame(base, statsOf(r, "default", "goblin_chief").applyTo(base),
                "空の stats: は基底プロファイルをそのまま返す(EMPTY 判定に倍率フィールドが入っていること)");
    }

    @Test
    void multiplierCannotResurrectAnUnconfiguredValue() throws Exception {
        // 既知の限界を明文化: max-health 0 / attack 未設定は「TF が値を持たない = EliteMobs 側に任せる」
        // という意味なので、0 に何を掛けても 0 のまま。倍率だけでは強くできないモブが存在する。
        DefenseStats def = new DefenseStats(0.0, 0.0, 0.0, 0.0, 0.0);
        MobProfile unconfigured = new MobProfile("em_mob", 10, null, def, def, AttackStats.plain(0.0), 0.0, false);

        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      em_mob:
                        stats:
                          max-health-multiplier: 10.0
                          attack-power-multiplier: 10.0
                """);
        MobProfile result = statsOf(r, "default", "em_mob").applyTo(unconfigured);
        assertFalse(result.hasMaxHealth(), "0 * 10 = 0。TF 側 HP は依然として未設定のまま");
        assertFalse(result.hasAttack(), "0 * 10 = 0。TF 側の攻撃側も未設定のまま");
    }

    // --- 不正値は警告して無視 ------------------------------------------------------------------

    @Test
    void zeroAndNegativeMultipliersAreWarnedAndIgnored() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger log = capturingLogger(warnings);
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health-multiplier: 0
                          attack-power-multiplier: -2.0
                          max-health: 777
                """, log);

        assertEquals(2, r.skipped(), "0 と負値の 2 件が skipped に計上される");
        assertTrue(anyContains(warnings, "stats.max-health-multiplier", "must be > 0"),
                "0 が無視されたことがログに出ること: " + warnings);
        assertTrue(anyContains(warnings, "stats.attack-power-multiplier", "must be > 0"),
                "負値が無視されたことがログに出ること: " + warnings);

        MobProfile base = baseProfile();
        MobProfile result = statsOf(r, "default", "goblin_chief").applyTo(base);
        assertEquals(777.0, result.maxHealth(), 1.0e-9, "同じブロックの正しい項目は生きる");
        assertEquals(10.0, result.attack().defaultDamage(), 1.0e-9, "不正な倍率は掛からない");
    }

    @Test
    void nonNumericAndNaNMultipliersAreWarnedAndIgnored() throws Exception {
        List<String> warnings = new ArrayList<>();
        Logger log = capturingLogger(warnings);
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health-multiplier: "とても強い"
                          attack-power-multiplier: .nan
                          armor-strength: 0.9
                """, log);

        assertEquals(2, r.skipped());
        assertTrue(anyContains(warnings, "stats.max-health-multiplier", "must be numeric"),
                "文字列が無視されたことがログに出ること: " + warnings);
        assertTrue(anyContains(warnings, "stats.attack-power-multiplier", "must be numeric"),
                "NaN が無視されたことがログに出ること: " + warnings);

        MobProfile result = statsOf(r, "default", "goblin_chief").applyTo(baseProfile());
        assertEquals(100.0, result.maxHealth(), 1.0e-9);
        assertEquals(10.0, result.attack().defaultDamage(), 1.0e-9);
        assertEquals(0.9, result.armorStrength(), 1.0e-9, "同じブロックの正しい項目は生きる");
    }

    @Test
    void quotedNumericStringMultiplierIsWarnedAndIgnored() throws Exception {
        // 2026-08-14 方針変更: 倍率キーはクォートされた数値文字列を受け付けない。
        // 理由は「掛け算として不正だから」ではなく【config-editor と受理範囲を揃えるため】——
        // editor の schema.js は isNumber = typeof value === "number" で文字列を弾くので、
        // Java だけが受け付けると「手書きで "2.5" と書いた yml を editor で開くとファイルごと
        // 保存できない」という非対称が生まれる。狭い側へ寄せても既存設定は壊れない —— 2026-08-14
        // 実測で出荷 mob-overrides.yml の倍率キー 40 件は全件が素の数値(クォート文字列 0 件)。
        List<String> warnings = new ArrayList<>();
        Logger log = capturingLogger(warnings);
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health-multiplier: "2.5"
                          armor-strength: 0.9
                """, log);

        assertEquals(1, r.skipped(), "文字列の倍率は skipped に計上される");
        assertTrue(anyContains(warnings, "stats.max-health-multiplier", "must be numeric and unquoted"),
                "文字列が無視されたことがログに出ること: " + warnings);

        MobProfile result = statsOf(r, "default", "goblin_chief").applyTo(baseProfile());
        assertEquals(100.0, result.maxHealth(), 1.0e-9, "倍率は掛からない");
        assertEquals(0.9, result.armorStrength(), 1.0e-9, "同じブロックの正しい項目は生きる");
    }

    @Test
    void quotedNumericStringIsStillAcceptedForNonMultiplierKeys() throws Exception {
        // 上の絞り込みが【倍率キーだけ】に閉じていることの固定。max-health など既存の数値キーまで
        // 巻き込むと、手書き yml が黙って無視される方向の回帰になる。
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: "1000"
                """);
        assertEquals(0, r.skipped());
        assertEquals(1000.0, statsOf(r, "default", "goblin_chief").applyTo(baseProfile()).maxHealth(), 1.0e-9);
    }

    @Test
    void multiplierMisplacedInsideAttackSectionIsWarnedNotSilentlyDropped() throws Exception {
        // attack-power は attack: の中、attack-power-multiplier は stats: 直下 —— 混同しやすい。
        // Bukkit は未知キーを黙って捨てるので、警告が無いと「書いたのに何も起きない」が成立する。
        List<String> warnings = new ArrayList<>();
        Logger log = capturingLogger(warnings);
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          attack:
                            attack-power-multiplier: 2.0
                            crit-chance: 0.4
                """, log);

        assertEquals(1, r.skipped());
        assertTrue(anyContains(warnings, "stats.attack.attack-power-multiplier",
                        "write it as stats.attack-power-multiplier"),
                "階層の書き間違いが警告されること: " + warnings);

        MobProfile result = statsOf(r, "default", "goblin_chief").applyTo(baseProfile());
        assertEquals(10.0, result.attack().defaultDamage(), 1.0e-9, "誤配置の倍率は効かない");
        assertEquals(0.4, result.attack().critChance(), 1.0e-9, "同じブロックの正しい項目は生きる");
    }

    @Test
    void bothMultipliersMisplacedInsideAttackSectionAreWarnedIndividually() throws Exception {
        // 難易度は「HP 何倍・攻撃力何倍」の対で書かれるので、対ごと attack: の中へ入れる書き間違いが
        // 現実的。対の片方しか警告しないと、警告に従って attack-power-multiplier だけ直したのに
        // max-health-multiplier は attack: の中に残ったまま無言で不発、という直り方をする。
        List<String> warnings = new ArrayList<>();
        Logger log = capturingLogger(warnings);
        ParseResult r = parse("""
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        stats:
                          attack:
                            max-health-multiplier: 3.0
                            attack-power-multiplier: 2.0
                            crit-chance: 0.4
                """, log);

        assertEquals(2, r.skipped(), "誤配置 2 件がそれぞれ skipped に計上される");
        assertTrue(anyContains(warnings, "stats.attack.max-health-multiplier",
                        "write it as stats.max-health-multiplier"),
                "max-health-multiplier の誤配置も警告されること: " + warnings);
        assertTrue(anyContains(warnings, "stats.attack.attack-power-multiplier",
                        "write it as stats.attack-power-multiplier"),
                "attack-power-multiplier の誤配置も警告されること: " + warnings);

        MobProfile result = statsOf(r, "default", "goblin_chief").applyTo(baseProfile());
        assertEquals(100.0, result.maxHealth(), 1.0e-9, "誤配置の HP 倍率は効かない");
        assertEquals(10.0, result.attack().defaultDamage(), 1.0e-9, "誤配置の攻撃倍率は効かない");
        assertEquals(0.4, result.attack().critChance(), 1.0e-9, "同じブロックの正しい項目は生きる");
    }

    // --- yml ラウンドトリップ -------------------------------------------------------------------

    @Test
    void multipliersSurviveAYamlRoundTrip(@TempDir File dir) throws Exception {
        // config-editor は「読む → 書く → 読み直す」を必ず通る。ここで倍率が落ちると
        // 「エディタで開いて保存しただけで難易度が消える」という無言の劣化になる。
        String yaml = """
                overrides:
                  em_hard_dungeon:
                    stats:
                      max-health-multiplier: 4.0
                      attack-power-multiplier: 1.5
                    mobs:
                      goblin_chief:
                        stats:
                          max-health: 1000
                          max-health-multiplier: 2.0
                          attack-power-multiplier: 3.0
                """;
        MobOverridesConfig first = loadedConfig(dir, yaml);
        MobProfile expected = first.resolve("em_hard_dungeon_1", "goblin_chief", baseProfile());
        // HP: モブ個別が絶対値 1000 を書いているので、scope 直下の 4.0 倍が作った 400 は【捨てられる】。
        // 残るのは 1000 * 2.0(モブ個別の倍率)だけ。詳しくは
        // absoluteValueInAHigherLayerDiscardsLowerLayerMultipliers を参照。
        assertEquals(2000.0, expected.maxHealth(), 1.0e-9, "1000(絶対値) * 2.0");
        // attack: 絶対値が無いので両方の層の倍率が乗る。
        assertEquals(45.0, expected.attack().defaultDamage(), 1.0e-9, "10 * 1.5 * 3.0");

        // 読み込んだものを YAML として書き戻し、それをもう一度読ませる。
        YamlConfiguration written = new YamlConfiguration();
        written.options().pathSeparator(MobOverridesConfig.MOB_ID_SAFE_PATH_SEPARATOR);
        written.loadFromString(yaml);
        String rewritten = written.saveToString();
        assertTrue(rewritten.contains("max-health-multiplier"), "書き出した yml に倍率キーが残ること");
        assertTrue(rewritten.contains("attack-power-multiplier"), "書き出した yml に倍率キーが残ること");

        File roundTripDir = new File(dir, "round-trip");
        MobOverridesConfig second = loadedConfig(roundTripDir, rewritten);
        MobProfile actual = second.resolve("em_hard_dungeon_1", "goblin_chief", baseProfile());
        assertEquals(expected.maxHealth(), actual.maxHealth(), 1.0e-9);
        assertEquals(expected.attack().defaultDamage(), actual.attack().defaultDamage(), 1.0e-9);
        assertEquals(expected, actual, "ラウンドトリップ後もプロファイル全体が一致すること");
    }
}
