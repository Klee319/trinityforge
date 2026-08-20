package com.trinityforge.config.domains;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.mobs.MobIdNormalizer;
import com.trinityforge.mobs.MobProfile;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>出荷 {@code combat/mob-overrides.yml} のボス強度と特殊攻撃を実データで固定する</b> drift 検出テスト
 * (2026-08-01 追加コンテンツ詳細プラン 柱2 / 柱2-1、K-22(1))。
 *
 * <h2>なぜ要るか — このファイルの間違いは全部「無言」で出る</h2>
 * <ul>
 *   <li><b>ability 名の綴り間違いは何のエラーも出さない。</b>
 *       {@link MobOverridesConfig#parse} は「{@code mob-abilities.yml} のロード順に依存させたくない」
 *       という理由で実在チェックをせず、未定義IDは<b>発動時に黙って読み飛ばす</b>。
 *       つまり存在しない技名を書くと「設定したのにボスが何も撃たない」という症状にしかならない。
 *       ここで出荷 {@code mob-abilities.yml} のテンプレートIDと突き合わせて発明を検出する。</li>
 *   <li><b>モブIDの綴り違いも無言。</b> オーバーライドが当たらないだけで、エラーにはならない。</li>
 *   <li><b>ボスと雑魚が同じ強さでも何も起きない。</b> ダンジョンの難易度はスコープ直下の1本の倍率で
 *       付けるので、<b>個体側に係数を書き忘れると踏破ボスが自分の配下の雑魚と1ダメージ差なく同じになる</b>。
 *       2026-08-20 まで 121 体すべてがその状態だった(実プレイで「エンドコンテンツのボスが
 *       フィールドのエンドラと同じ」として報告された)。誰も例外を投げないので、ここで固定する。</li>
 *   <li><b>倍率と絶対値は別物。</b> {@code MobStatOverride} は 2026-08-14 から倍率キーも受け取る。
 *       <b>絶対値はレベル追従を殺す</b>ので、レベルが動くコンテンツでは必ず倍率で書く。
 *       ── ランプ側の base/growth を触ると倍率の意味が変わるので、そのときここが落ちる。</li>
 * </ul>
 */
class ShippedBossStrengthDriftTest {

    // === 柱2 の出典となる共通ランプ(combat/mob-import.yml)と束縛者の contentLevel ===

    /**
     * 束縛者の実レベル。<b>配備先 {@code custombosses/the_binder_of_worlds/*.yml} の {@code level: 100}</b>
     * が一次情報(2026-08-20 / W-179 に実データで確認)。
     *
     * <p>2026-08-01〜2026-08-19 はここが 50 だった。yml のコメントが「contentLevel: 50 固定」と
     * 書いていたのをそのまま信じたもので、<b>EM の実ファイルとは食い違っていた</b>。その結果、
     * 束縛者の絶対値だけが半分のレベルのランプで書かれ、実HP がフィールドのエンドラと同じ 1.46M、
     * 攻撃力は自分の配下の雑魚より弱い、という逆転が起きていた。
     */
    private static final int BINDER_CONTENT_LEVEL = 100;

    private static final String MOB_IMPORT = "src/main/resources/combat/mob-import.yml";

    // ランプ定数は【出荷 mob-import.yml から読む】。ここに直書きすると、ランプの base/growth を
    // 変えても yml 側の絶対値と乖離したまま全件緑で通ってしまい、このテストの主目的
    // (「倍率の意味が変わったことを検出する」)が果たせない。
    private static final double RAMP_HP_BASE = rampValue("max-health", "base");
    private static final double RAMP_HP_GROWTH = rampValue("max-health", "growth");
    private static final double RAMP_ATTACK_BASE = rampValue("attack.attack-power", "base");
    private static final double RAMP_ATTACK_GROWTH = rampValue("attack.attack-power", "growth");

    /**
     * 出荷 {@code combat/mob-import.yml} から共通ランプの1値を読む。
     * キーが消えたら 0 を返さず即座に落とす —— 0 で続けると「倍率が合っている」という
     * 意味のない緑になるため。
     */
    private static double rampValue(String path, String key) {
        File file = new File(MOB_IMPORT);
        if (!file.isFile()) {
            throw new AssertionError("出荷 mob-import.yml が見つからない: " + file.getAbsolutePath());
        }
        ConfigurationSection section =
                YamlConfiguration.loadConfiguration(file).getConfigurationSection(path);
        if (section == null || !section.isSet(key)) {
            throw new AssertionError("mob-import.yml に " + path + "." + key + " が無い。"
                    + "柱2 の倍率はこのランプを Lv" + BINDER_CONTENT_LEVEL
                    + " で評価した値を基準にしているので、キーが消えると倍率の意味が失われる");
        }
        return section.getDouble(key);
    }

    private static final String BINDER_WORLD = "em_id_binder_of_worlds";

    /**
     * ボスの居ないスコープ。<b>これは「検査を免除するリスト」なので、増やすときは必ず理由を書く。</b>
     * <ul>
     *   <li>{@code default} — ダンジョンではなく「どの EM ダンジョンにも属さないモブ」の受け皿。</li>
     *   <li>{@code em_adventurers_guild} — 戦闘のない拠点(NPC と計測用の的だけ)。</li>
     * </ul>
     */
    private static final Set<String> BOSSLESS_SCOPES = Set.of("default", "em_adventurers_guild");

    /** エンチャント試練の本数(難易度1〜10)。 */
    private static final int TRIAL_COUNT = 10;

    /**
     * エンチャント試練の攻撃力を決めるときの基準レベル。<b>モブ側のレベルではなく、挑むプレイヤーの
     * 到達レベル</b>を指す —— EM 側の個体にレベル指定が無いので TF の共通ランプは Lv1 でしか
     * 評価されず、倍率が効かない。だから「Lv100 のプレイヤーが受けるべきダメージ」を直接書く。
     */
    private static final int TRIAL_PLAYER_LEVEL = 100;

    /** 最終試練の踏破ボス。 */
    private static final String TRIAL_FINAL_BOSS = "enchantment_boss_tricky_bones";

    /**
     * 難易度 n(1〜10)の係数。1.0 から 1.4 までを等間隔に割る。
     * 幅を 0.4 に抑えてあるのは、この10本が<b>同じ Lv100 装備で順に登る梯子</b>だから ——
     * 倍率差を大きくすると下位が作業になり上位が壁になる。
     */
    private static double trialDifficultyFactor(int n) {
        return 1.0 + ((n - 1) / (double) (TRIAL_COUNT - 1)) * 0.4;
    }

    /** 柱2 の段階表: モブid -&gt; {HP倍率, 攻撃倍率}。増援は載せない(「変更しない」が仕様)。 */
    private static final Map<String, double[]> BINDER_TIER = new LinkedHashMap<>();

    static {
        BINDER_TIER.put("em_id_binder_of_worlds_phase_1", new double[] {2.5, 1.3});
        BINDER_TIER.put("em_id_binder_of_worlds_phase_2", new double[] {3.0, 1.4});
        BINDER_TIER.put("em_id_binder_of_worlds_phase_3", new double[] {3.5, 1.5});
        BINDER_TIER.put("em_id_binder_of_worlds_phase_4", new double[] {6.0, 1.8});
        BINDER_TIER.put("em_id_binder_of_worlds_phase_1_melee_miniboss", new double[] {1.8, 1.2});
        BINDER_TIER.put("em_id_binder_of_worlds_phase_1_ranged_miniboss", new double[] {1.8, 1.2});
        BINDER_TIER.put("em_id_binder_of_worlds_phase_1_status_miniboss", new double[] {1.8, 1.2});
    }

    /**
     * 束縛者の踏破ボス。2026-08-20(W-179)からは段階表の倍率(×6.0)だけで決まる —— 絶対値は持たない。
     *
     * <p>実HP = 共通ランプ Lv100({@value #BINDER_CONTENT_LEVEL} で評価)× 6.0 × EliteMobs の
     * {@code healthMultiplier: 120} = 約 1.13 億。難易度 10 の基準であるエンチャント試練10
     * (実HP 69,360,000)を上回り、最終ボスが最難関になる。
     */
    private static final String BINDER_FINAL_PHASE = "em_id_binder_of_worlds_phase_4";

    /**
     * 柱2-1 の割り当て表: ワールド名 -&gt; その踏破ボス(そのダンジョンで<b>最後に戦う</b>モブ)id と ability の列。
     *
     * <h3>「踏破ボス」の確定手順(2026-08-14 に実データで取り直した)</h3>
     * 配備先 {@code plugins/EliteMobs/} の実ファイルだけを根拠にする。手順は2段:
     * <ol>
     *   <li>{@code content_packages/<pack>.yml} の {@code dungeonObjectives} で
     *       <b>そのダンジョンのボス系列の入口</b>を特定する。</li>
     *   <li>その入口ファイルの {@code phases:} を辿り、<b>いちばん深い段階</b>を取る。
     *       {@code phases:} は「HP割合 → 次のファイル」の列で、入口の p1 にだけ書かれている。</li>
     * </ol>
     *
     * <p><b>ここを間違えると症状が出ない</b>: 途中段階のIDに ability を付けても
     * {@link MobOverridesConfig#parse} は何も言わないし、その段階は数秒で通過するので
     * 「たまに撃ってきた気がする」程度にしかならない。<b>一番長く戦う最終段階が無技になる</b>。
     *
     * <h3>2026-08-14 に訂正した4件(すべて「途中フェーズを指していた」)</h3>
     * <ul>
     *   <li>{@code em_knight_castle}: p3 → <b>p4</b>。
     *       {@code the_castle_charlemagne_p1.yml} の phases が p2:0.80 / p3:0.50 / p4:0.30。
     *       p4 だけが {@code dropsEliteMobsLoot: true} + {@code uniqueLootList} を持つ。</li>
     *   <li>{@code em_steamworks_lair}: p3 → <b>p7</b>。
     *       {@code the_steamworks_clk_wrx702_p1.yml} の phases が p2〜p7 の6段。</li>
     *   <li>{@code em_id_the_climb}: p3 → <b>p4</b>。
     *       {@code the_climb_undead_beastmaster.yml} の phases が p2:.99999 / p3:.75 / p4:.50。</li>
     *   <li>{@code em_id_the_quarry}: {@code em_id_the_quarry_royal_wizard_five_unlocker_p3}
     *       → <b>{@code LiftStateFinishDungeon}</b>。
     *       <b>名前は状態機械のように見えるが実体はモブID</b>
     *       ({@code custombosses/em_id_the_quarry/LiftStateFinishDungeon.yml} は
     *       {@code bossType: BOSS} / {@code name: $bossLevel &dLift Master} /
     *       {@code healthMultiplier: 8.5} を持つ本物のボス定義)。
     *       {@code the_quarry_dungeon.yml} の {@code dungeonObjectives} の最後がこれで、
     *       {@code customquests/story_dungeons_quest_7_down_below.yml} もこれを討伐目標にしている。
     *       元の指定 {@code ..._royal_wizard_five_unlocker_p3} は「術式守護のトーテム」
     *       ({@code $normalLevel} 表記・{@code isRegionalBoss: false}・HP倍率3.5)で、
     *       Royal Spellcaster の檻を割るための<b>仕掛け</b>であって踏破ボスではない。</li>
     * </ul>
     */
    private static final Map<String, Map.Entry<String, List<String>>> DUNGEON_BOSS_ABILITIES =
            new LinkedHashMap<>();

    static {
        // 物理寄り(物理 defense-rate .418) — shockwave + bull_rush
        List<String> phys = List.of("shockwave", "bull_rush");
        putBoss("em_id_the_mines", "the_mines_soulweaver_daine_p3", phys);
        putBoss("em_id_the_deep_mines", "em_id_the_deep_mines_boss_the_pursuer_p3", phys);
        putBoss("em_id_the_quarry", "LiftStateFinishDungeon", phys);
        putBoss("em_id_the_city", "em_id_the_city_royal_guard_p3", phys);
        putBoss("em_knight_castle", "the_castle_charlemagne_p4", phys);
        putBoss("em_steamworks_lair", "the_steamworks_clk_wrx702_p7", phys);
        putBoss("em_fireworks", "fireworks_level_50_boss_phase_3", phys);
        // 魔法寄り(魔法 defense-rate .418) — piercing_beam + withering_aura
        List<String> magic = List.of("piercing_beam", "withering_aura");
        putBoss("em_id_the_cave", "the_cave_boiler_p3", magic);
        putBoss("em_id_the_nether_bell", "em_id_the_nether_bell_boss_void_bell_p3", magic);
        putBoss("em_the_dark_cathedral", "dark_cathedral_tier_75_boss_phase_3", magic);
        putBoss("em_hallosseum", "halloween_event_boss_p2", magic);
        // 均等 — call_the_horde + crippling_stomp
        List<String> even = List.of("call_the_horde", "crippling_stomp");
        putBoss("em_id_the_bridge", "the_bridge_ancient_guardian_p3", even);
        putBoss("em_id_the_climb", "the_climb_undead_beastmaster_p4", even);
        putBoss("em_id_the_palace", "the_palace_old_stone_king_p3", even);
        putBoss("em_sewer_maze", "sewer_tier_70_boss", even);
        // 低難度 — frost_field 1つだけ
        List<String> easy = List.of("frost_field");
        putBoss("em_north_pole", "northpole_santa_claus", easy);
        putBoss("em_id_the_nether_wastes", "em_id_the_nether_wastes_miniboss_5_shroud_p2", easy);
        // エンチャント試練1〜10。技の数を【試練の番号とともに増やす梯子】にしてある(2026-08-16、K指示):
        // 1〜3 = 1種 / 4〜6 = 2種 / 7〜10 = 3種。単調性そのものは
        // ShippedMobAbilityAssignmentTest#enchantmentTrialAbilityCountIsMonotonic が別途固定する。
        // 課題ごとに要求ビルドが入れ替わるダンジョン群なので、技もボスの性格に合わせて散らしてある。
        putBoss("em_id_enchantment_challenge_1", "enchantment_boss_dark_flame", List.of("ember_spray"));
        putBoss("em_id_enchantment_challenge_2", "enchantment_boss_energized_bunny", List.of("shadow_step"));
        putBoss("em_id_enchantment_challenge_3", "enchantment_boss_jealous_block", List.of("shockwave"));
        putBoss("em_id_enchantment_challenge_4", "enchantment_boss_leet_summoner",
                List.of("call_the_swarm", "quake_spikes"));
        putBoss("em_id_enchantment_challenge_5", "enchantment_boss_loveable_impaler",
                List.of("piercing_beam", "abyssal_grip"));
        putBoss("em_id_enchantment_challenge_6", "enchantment_boss_ravegarer",
                List.of("bull_rush", "gale_smash"));
        putBoss("em_id_enchantment_challenge_7", "enchantment_boss_rock_solid_cold",
                List.of("frost_field", "crippling_stomp", "quake_spikes"));
        putBoss("em_id_enchantment_challenge_8", "enchantment_boss_the_firebunger",
                List.of("ember_spray", "meteor_mark", "gale_smash"));
        putBoss("em_id_enchantment_challenge_9", "enchantment_boss_the_glass_master",
                List.of("arrow_fan", "abyssal_grip", "piercing_beam"));
        putBoss("em_id_enchantment_challenge_10", "enchantment_boss_tricky_bones",
                List.of("frost_field", "shadow_step", "meteor_mark"));
    }

    private static void putBoss(String world, String mobId, List<String> abilities) {
        DUNGEON_BOSS_ABILITIES.put(world, Map.entry(mobId, abilities));
    }

    /**
     * abilities を持つモブの総数。9(default のバニラモブ) + 7(束縛者、2026-07-31)
     * + 18(柱2-1、2026-08-01) + 9(エンチャント試練1〜9、2026-08-02) + 6(実装1: attack.magic-ratio を
     * 実証する新規派生カスタムボス6体。caster_zombie/warlock_husk/frost_wraith_skeleton/
     * abyssal_drowned/cursed_wanderer/shadow_spider。2026-08-02、既存モブは書き換えていない) = 49。
     * 増減したらこの定数と一緒に「なぜ増えたか」を書くこと。
     *
     * <p><b>2026-08-14 に出荷 yml を数え直して 49 のまま据え置いた</b>。
     * {@link #DUNGEON_BOSS_ABILITIES} の4件の訂正は「abilities を途中フェーズから最終フェーズへ
     * <b>移す</b>」であって足す作業ではないので、担い手の総数は変わらない
     * (default 15 + 束縛者 7 + 踏破ボス 18 + エンチャント試練 9 = 49。
     * default の 15 は「バニラ9 + 派生カスタムボス6」の合算)。
     * <b>ここを 53 のような数へ増やしたら、それは移動ではなく二重掲載になっている</b>
     * ── 途中フェーズ側の {@code abilities:} を消し忘れたということなので、
     * {@link #noIntermediatePhaseOfAClearBossCarriesAbilities} も同時に落ちる。
     *
     * <p><b>2026-08-16 に 49 → 99 へ引き上げた（K指示で設計方針が変わったため）</b>。
     * 「技を撃つのは各ダンジョンの踏破ボス1体だけ」という 2026-08-01 の縛りは撤回し、
     * <b>ミニボス／節目のボスにも1種ずつ配る</b>方針になった（雑魚には依然として付けない ──
     * 同時湧きの頭数ぶん AoE が重なり波の被ダメージが設計不能になるため）。内訳は
     * 既存 49 ＋ 追加 50 = 99。追加 50 の内訳:
     * the_city ミニボス3 / the_climb ボス第2段階3 / the_deep_mines ミニボス4 / the_mines ミニボス3 /
     * the_nether_wastes ミニボス4 / the_nether_bell ビームボス9 / sewer 各層ボス12 /
     * wood_league 節目(5波ごとのミニボス・10波ごとのボス)10 / the_castle ser_prancelot_p2 1 /
     * dark_cathedral black_philip 1。
     */
    private static final int EXPECTED_ABILITY_CARRIER_COUNT = 99;

    // === 読み込みヘルパ(出荷リソースの bytes をそのまま使う。写しを手書きしない) ===

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedBossStrengthDriftTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static InputStream resource(String path) {
        return ShippedBossStrengthDriftTest.class.getClassLoader()
                .getResourceAsStream(path.replace('\\', '/'));
    }

    private static MobOverridesConfig loadShippedOverrides(File tempDir) throws IOException {
        File file = new File(tempDir, MobOverridesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (InputStream in = resource(MobOverridesConfig.PATH)) {
            assertNotNull(in, "出荷リソースが見つからない: " + MobOverridesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        MobOverridesConfig config = new MobOverridesConfig();
        assertTrue(config.load(fakePlugin(tempDir)),
                "出荷 mob-overrides.yml のロードが false を返した(= 1件以上が skip された)。"
                        + "skip されたエントリは実機でも無言で効かない。");
        return config;
    }

    private static YamlConfiguration loadShippedYaml(String path) throws IOException {
        try (InputStream in = resource(path)) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** オーバーライドの「上」に敷く素の profile。全項目ゼロなので、解決結果 = 書かれた値そのもの。 */
    private static MobProfile neutralBase(String mobId) {
        return new MobProfile(mobId, 1, null, DefenseStats.NONE, DefenseStats.NONE,
                AttackStats.plain(0.0), 0.0, false);
    }

    private static double rampAt(double base, double growth, int level) {
        return base * Math.pow(growth, level);
    }

    /** 出荷 yml の生ツリーから「ワールド -&gt; モブid -&gt; そのモブのセクション」を素直に辿る。 */
    private static ConfigurationSection mobsSection(YamlConfiguration yaml, String world) {
        ConfigurationSection overrides = yaml.getConfigurationSection("overrides");
        assertNotNull(overrides, "出荷 mob-overrides.yml に overrides セクションが無い");
        ConfigurationSection worldSection = overrides.getConfigurationSection(world);
        assertNotNull(worldSection, "出荷 mob-overrides.yml に overrides." + world + " が無い");
        ConfigurationSection mobs = worldSection.getConfigurationSection("mobs");
        assertNotNull(mobs, "出荷 mob-overrides.yml の " + world + " に mobs セクションが無い");
        return mobs;
    }

    // === 柱2: 束縛者の4段階 ===

    /**
     * 束縛者スコープ直下の {@code attack-power-multiplier} を<b>出荷 yml から読む</b>。
     * ここを定数で直書きすると、yml 側の倍率だけ動かしても絶対値7体との整合が検査されず、
     * 「増援だけ強くなってボスが置いていかれる」状態を緑のまま通してしまう。
     */
    private static double binderScopeAttackMultiplier() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection stats =
                yaml.getConfigurationSection("overrides." + BINDER_WORLD + ".stats");
        assertNotNull(stats, "出荷 mob-overrides.yml の " + BINDER_WORLD + " に scope 直下の stats が無い");
        assertTrue(stats.isSet("attack-power-multiplier"),
                BINDER_WORLD + " の scope 直下から attack-power-multiplier が消えている。"
                        + "この倍率は【絶対値を持たない増援11体】の被ダメージが床値(min-component-damage)に"
                        + "張り付くのを防ぐ唯一の手段なので、消すと雑魚が無害になる。");
        return stats.getDouble("attack-power-multiplier");
    }

    /**
     * 段階表({@link #BINDER_TIER})の対象が、出荷 yml で実際に絶対値を持つ個体と<b>一致している</b>
     * ことを先に固定する。許可リストだけを見る検査は<b>リスト自体が現実とずれた瞬間に検査ごと無効化</b>
     * されるため(8体目に絶対値を足しても誰も気づかない)、ここで両側から突き合わせる。
     */
    @Test
    @DisplayName("束縛者は per-mob の絶対値を1体も持たない(2026-08-20 / W-179 で倍率へ移した)")
    void binderCarriesNoPerMobAbsoluteValues() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection mobs = mobsSection(yaml, BINDER_WORLD);

        Set<String> absoluteCarriers = new TreeSet<>();
        for (String mobId : mobs.getKeys(false)) {
            ConfigurationSection stats = mobs.getConfigurationSection(mobId + ".stats");
            if (stats == null) {
                continue;
            }
            if (stats.isSet("max-health") || stats.isSet("attack.attack-power")) {
                absoluteCarriers.add(mobId);
            }
        }
        assertEquals(new TreeSet<String>(), absoluteCarriers,
                "束縛者に per-mob の絶対値が復活している: " + absoluteCarriers + "。"
                        + "絶対値はレベル追従を殺すうえ、【scope 直下の attack-power-multiplier が"
                        + "丸ごと捨てられる】側へ移る(MobStatOverride#mergeAttack は置換であって乗算ではない)。"
                        + "実際 2026-08-19 まで、EM 側が level: 100 なのに絶対値が Lv50 のランプで"
                        + "書かれており、最終ボスの攻撃力が自分の配下の雑魚より弱いという逆転が起きていた。"
                        + "強さは必ず倍率(max-health-multiplier / attack-power-multiplier)で書くこと。");
    }

    /**
     * 段階表({@link #BINDER_TIER})の対象が、出荷 yml で実際に倍率を持つ個体と<b>一致している</b>
     * ことを固定する。許可リストだけを見る検査は<b>リスト自体が現実とずれた瞬間に検査ごと無効化</b>
     * されるため(8体目に倍率を足しても誰も気づかない)、ここで両側から突き合わせる。
     */
    @Test
    @DisplayName("束縛者で段階倍率を持つのは段階表の7体ちょうど。増援は1体も持たない")
    void binderMultiplierCarriersAreExactlyTheTierTable() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection mobs = mobsSection(yaml, BINDER_WORLD);

        Set<String> carriers = new TreeSet<>();
        for (String mobId : mobs.getKeys(false)) {
            ConfigurationSection stats = mobs.getConfigurationSection(mobId + ".stats");
            if (stats == null) {
                continue;
            }
            if (stats.isSet("max-health-multiplier") || stats.isSet("attack-power-multiplier")) {
                carriers.add(mobId);
            }
        }
        assertEquals(new TreeSet<>(BINDER_TIER.keySet()), carriers,
                "束縛者で per-mob の倍率を持つ個体の集合が段階表と食い違っている。"
                        + "増援(reinforcement)は『数で圧をかける役』なので個体を強くしない、というのが"
                        + "柱2 の明示的な指定。段階表に足すか、倍率をやめるかのどちらかにすること。");
    }

    @Test
    @DisplayName("束縛者の4段階＋ミニボス3種の倍率が、共通ランプLv100に段階表どおり掛かる")
    void binderTierMatchesThePlannedMultipliers(@TempDir File tempDir) throws IOException {
        MobOverridesConfig config = loadShippedOverrides(tempDir);
        double rampHp = rampAt(RAMP_HP_BASE, RAMP_HP_GROWTH, BINDER_CONTENT_LEVEL);
        double rampAttack = rampAt(RAMP_ATTACK_BASE, RAMP_ATTACK_GROWTH, BINDER_CONTENT_LEVEL);
        double scopeMultiplier = binderScopeAttackMultiplier();

        BINDER_TIER.forEach((mobId, multipliers) -> {
            // 本番と同じ形の素プロファイル: 共通ランプを束縛者の実レベルで評価した値。
            // 倍率は「元の値の何倍か」なので、0 を敷くと何を掛けても 0 になり検査が意味を失う。
            MobProfile base = new MobProfile(mobId, BINDER_CONTENT_LEVEL, null,
                    DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(rampAttack), rampHp, false);
            MobProfile resolved = config.resolve(BINDER_WORLD, mobId, base);

            // 倍率は層ごとに掛け算になる(MobOverridesConfig#resolve が scope → mob の順に applyTo)。
            //   HP     = ランプ × 段階表の倍率                (scope 直下に max-health-multiplier は無い)
            //   攻撃   = ランプ × scope の難易度倍率 × 段階表の倍率
            double expectedHp = rampHp * multipliers[0];
            double expectedAttack = rampAttack * scopeMultiplier * multipliers[1];

            assertEquals(expectedHp, resolved.maxHealth(), expectedHp * 1.0e-9,
                    mobId + " の max-health が " + resolved.maxHealth() + "。期待は 共通ランプ Lv"
                            + BINDER_CONTENT_LEVEL + " 実値 " + String.format("%.2f", rampHp)
                            + " × 段階倍率 " + multipliers[0] + " = " + expectedHp + "。"
                            + "絶対値へ戻すとレベル追従が死ぬ(2026-08-19 まで Lv50 基準のまま"
                            + "取り残されていた)。");
            assertEquals(expectedAttack, resolved.attack().defaultDamage(), expectedAttack * 1.0e-9,
                    mobId + " の attack-power が " + resolved.attack().defaultDamage()
                            + "。期待は 共通ランプ Lv" + BINDER_CONTENT_LEVEL + " 実値 "
                            + String.format("%.2f", rampAttack) + " × scope 倍率 " + scopeMultiplier
                            + " × 段階倍率 " + multipliers[1] + " = " + expectedAttack + "。"
                            + "ここに絶対値を書き足すと scope 倍率が丸ごと捨てられ、"
                            + "ボスだけが難易度補正の外へ落ちる。");
        });
    }

    @Test
    @DisplayName("束縛者の増援は HP 無干渉のまま。攻撃だけは scope 倍率がそのまま効く(絶対値を持たないので)")
    void binderReinforcementsKeepTheirHpButTakeTheScopeAttackMultiplier(@TempDir File tempDir)
            throws IOException {
        MobOverridesConfig config = loadShippedOverrides(tempDir);
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection mobs = mobsSection(yaml, BINDER_WORLD);
        double scopeMultiplier = binderScopeAttackMultiplier();

        List<String> reinforcements = mobs.getKeys(false).stream()
                .filter(id -> id.contains("reinforcement"))
                .sorted()
                .toList();
        assertEquals(11, reinforcements.size(),
                "束縛者の増援は 11 体のはず(プラン本文の『増援×10』は実データと1体ずれている)。"
                        + "実際に見つかったのは " + reinforcements);

        // 素の profile に 999/9.99 を敷く。HP は誰も書いていないのでそのまま残り、
        // 攻撃だけが scope 直下の倍率で押し上げられる —— これが「増援は個体を強くしないが、
        // 被ダメージが床値に張り付くのは直す」という 2026-08-14 の設計そのもの。
        double expectedAttack = 9.99 * scopeMultiplier;
        for (String mobId : reinforcements) {
            MobProfile base = new MobProfile(mobId, 1, null, DefenseStats.NONE, DefenseStats.NONE,
                    AttackStats.plain(9.99), 999.0, false);
            MobProfile resolved = config.resolve(BINDER_WORLD, mobId, base);
            assertEquals(999.0, resolved.maxHealth(), 1.0e-9,
                    mobId + " の max-health が動いている。増援は『数で圧をかける役』なので"
                            + "個体を硬くしない、というのが柱2 の明示的な指定"
                            + "(scope 直下にも max-health-multiplier は置いていない)。");
            assertEquals(expectedAttack, resolved.attack().defaultDamage(), 1.0e-9,
                    mobId + " の attack-power が " + resolved.attack().defaultDamage()
                            + "。増援は per-mob の絶対値を持たないので、scope 直下の "
                            + "attack-power-multiplier " + scopeMultiplier + " が素通しで効き "
                            + expectedAttack + " になるのが正。ここに絶対値を書き足すと"
                            + "倍率が捨てられて逆に弱くなる。");
        }
    }

    @Test
    @DisplayName("柱2 は HP/攻撃だけ。束縛者の vanilla-exp は共通 growth 1.008 のまま")
    void binderExpRampIsNotDraggedAlongWithHp() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection mobs = mobsSection(yaml, BINDER_WORLD);
        for (String mobId : mobs.getKeys(false)) {
            ConfigurationSection exp = mobs.getConfigurationSection(mobId + ".vanilla-exp");
            if (exp == null) {
                continue;
            }
            assertEquals(1.008, exp.getDouble("growth"), 1.0e-9,
                    mobId + " の vanilla-exp.growth が 1.008 から動いている。EXP を HP と同じ伸び"
                            + "(1.072)にすると『所要時間は変わらないのに報酬だけ1000倍』という"
                            + "過去に踏んだ破綻を再現する。EXP は共通 1.008 + 役割係数のまま据え置くこと。");
        }
    }

    // === W-179: ダンジョンのボスが雑魚と同じ強さに戻らないようにする ===

    /**
     * ボス係数を<b>1体も持たないダンジョン</b>を検出する。
     *
     * <p>ダンジョンの難易度は scope 直下の倍率1本で付いており、それは<b>そのダンジョンの全個体に
     * 等しく掛かる</b>。したがって個体側に係数が無いと、踏破ボスの攻撃力は自分の配下の雑魚と
     * <b>完全に同値</b>になる。2026-08-20 まで 121 体すべてがそうで、実プレイでは
     * 「エンドコンテンツのボスがオーバーワールドのエンドラと同じ」として報告された。
     *
     * <p>この検査を「係数を持つ個体の許可リスト」で書くと、<b>リストが現実とずれた瞬間に検査ごと
     * 無効化</b>される。そこで<b>ダンジョン側から</b>「必ず1体は雑魚より強い個体が居ること」を要求する。
     * 実際 W-179 の1回目の適用では、EM ファイルに {@code bossType:} キーが無い虚無の鐘だけが
     * <b>ダンジョン丸ごと素通り</b>していた(分類が {@code name:} の {@code $bossLevel}
     * プレースホルダ側にあった)。この形の検査でしか捕まらない。
     */
    @Test
    @DisplayName("どのダンジョンにも「雑魚より強い個体」が最低1体は居る(ボス=雑魚の再発防止)")
    void everyDungeonHasAtLeastOneMobStrongerThanItsTrash() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection overrides = yaml.getConfigurationSection("overrides");
        assertNotNull(overrides, "出荷 mob-overrides.yml に overrides セクションが無い");

        List<String> flat = new ArrayList<>();
        for (String world : overrides.getKeys(false)) {
            if (BOSSLESS_SCOPES.contains(world)) {
                continue;
            }
            ConfigurationSection mobs = overrides.getConfigurationSection(world + ".mobs");
            if (mobs == null || mobs.getKeys(false).isEmpty()) {
                continue;
            }
            double strongest = 0.0;
            for (String mobId : mobs.getKeys(false)) {
                ConfigurationSection stats = mobs.getConfigurationSection(mobId + ".stats");
                if (stats == null) {
                    continue;
                }
                strongest = Math.max(strongest, stats.getDouble("attack-power-multiplier", 0.0));
            }
            if (strongest <= 1.0) {
                flat.add(world + "(" + mobs.getKeys(false).size() + "体)");
            }
        }
        assertEquals(List.of(), flat,
                "ボス係数を1体も持たないダンジョンがある: " + flat + "。"
                        + "scope 直下の倍率は全個体に等しく掛かるので、この状態では踏破ボスの攻撃力が"
                        + "自分の配下の雑魚と1ダメージ差なく同じになる —— しかも例外は出ない。"
                        + "EM 側の分類は bossType: キーだけでなく name: の $bossLevel / $minibossLevel /"
                        + "$eventBossLevel プレースホルダにも入っているので、片方だけ見て配ると"
                        + "ダンジョン単位で丸ごと漏れる。"
                        + "ボスの居ないスコープを意図的に増やしたときは BOSSLESS_SCOPES に理由付きで足すこと。");
    }

    /**
     * エンチャント試練10本の攻撃力が、難易度1〜10 の等間隔な梯子に載っていることを固定する。
     *
     * <p>この試練群だけ scope 直下が<b>絶対値</b>なのは、EM 側の個体にレベル指定が無く共通ランプが
     * 動かないため(倍率をいくら掛けても効かない)。その代わり<b>他ダンジョンの一斉調整から取り残される</b>：
     * 2026-08-19 の攻撃力圧縮(W-175)は倍率行しか触らなかったので、ここだけ旧値
     * (同レベルのフィールドモブの 2.92〜6.16 倍 = 厳選装備でも実質1発)のまま残っていた。
     *
     * <p>期待値はランプから計算する。直書きすると、ランプを動かしたときにこの10本だけ
     * 置き去りになったことを検出できない —— それが 2026-08-19 に起きたことそのもの。
     */
    @Test
    @DisplayName("エンチャント試練10本の攻撃力が、共通ランプLv100の難易度梯子(×1.0〜×1.4)に載っている")
    void enchantmentTrialAttackLadderStaysOnTheCommonRamp() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        double ramp = rampAt(RAMP_ATTACK_BASE, RAMP_ATTACK_GROWTH, TRIAL_PLAYER_LEVEL);

        List<String> problems = new ArrayList<>();
        for (int n = 1; n <= TRIAL_COUNT; n++) {
            String world = "em_id_enchantment_challenge_" + n;
            ConfigurationSection stats = yaml.getConfigurationSection("overrides." + world + ".stats");
            if (stats == null || !stats.isSet("attack.attack-power")) {
                problems.add(world + ": scope 直下の attack.attack-power が無い");
                continue;
            }
            double expected = Math.rint(ramp * trialDifficultyFactor(n) * 100.0) / 100.0;
            double actual = stats.getDouble("attack.attack-power");
            if (Math.abs(actual - expected) > 0.01) {
                problems.add(world + ": " + actual + " (期待 " + expected + " = 共通ランプ Lv"
                        + TRIAL_PLAYER_LEVEL + " " + String.format("%.2f", ramp) + " × 難易度係数 "
                        + String.format("%.4f", trialDifficultyFactor(n)) + ")");
            }
        }
        assertEquals(List.of(), problems,
                "エンチャント試練の攻撃力が梯子から外れている: " + problems + "。"
                        + "この10本は絶対値なので【他ダンジョンの一斉調整に付いてこない】。"
                        + "ランプや他ダンジョンの攻撃力を動かしたら、ここも同じ尺度へ揃え直すこと。");
    }

    /**
     * 「scope 直下の<b>絶対値</b>の上に、個体の<b>倍率</b>が乗る」ことを実装で確かめる。
     *
     * <p>絶対値と倍率が同じ解決チェーンに並ぶのはこの試練群だけで、しかも
     * {@code MobStatOverride#mergeAttack} は<b>絶対値で置換する</b>(乗算ではない)。
     * 「絶対値を書いた時点で倍率は捨てられる」という直感が正しければ、試練のボスは
     * 自分の配下の召喚体と同じ攻撃力になる。<b>実際にはそうならない</b>
     * (scope と mob は別の層として順に適用されるため)ことを固定しておかないと、
     * ここを絶対値へ「統一」する改修が入ったときに無言でボスが弱体化する。
     */
    @Test
    @DisplayName("試練のボスは scope の絶対攻撃力の上に自分の係数が乗る(絶対値が倍率を殺さない)")
    void trialBossMultiplierRidesOnTopOfTheScopeAbsolute(@TempDir File tempDir) throws IOException {
        MobOverridesConfig config = loadShippedOverrides(tempDir);
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        String world = "em_id_enchantment_challenge_" + TRIAL_COUNT;

        double scopeAbsolute = yaml.getDouble("overrides." + world + ".stats.attack.attack-power");
        assertTrue(scopeAbsolute > 0.0, world + " の scope 直下に絶対 attack-power が無い");
        double bossMultiplier = yaml.getDouble(
                "overrides." + world + ".mobs." + TRIAL_FINAL_BOSS + ".stats.attack-power-multiplier");
        assertTrue(bossMultiplier > 1.0,
                TRIAL_FINAL_BOSS + " にボス係数が無い。最終試練の踏破ボスが自分の召喚体と同じ攻撃力になる。");

        MobProfile boss = config.resolve(world, TRIAL_FINAL_BOSS, neutralBase(TRIAL_FINAL_BOSS));
        assertEquals(scopeAbsolute * bossMultiplier, boss.attack().defaultDamage(), 0.01,
                TRIAL_FINAL_BOSS + " の解決後の攻撃力が " + boss.attack().defaultDamage()
                        + "。期待は scope 絶対値 " + scopeAbsolute + " × ボス係数 " + bossMultiplier
                        + " = " + (scopeAbsolute * bossMultiplier) + "。"
                        + "これが scope 絶対値と同値になったら、絶対値が倍率を握り潰す実装へ変わったということ。"
                        + "そのときは試練群のボス係数を絶対値へ畳み込み直さないとボスが雑魚と同値になる。");
    }

    // === 柱2-1: 他ダンジョンのボスへ配った abilities ===

    /**
     * <b>1件目で止めずに全ダンジョンぶんを集めてから落とす</b>(2026-08-14)。
     * ループの中で assert すると、最初に食い違ったダンジョンの分しか見えず、
     * <b>直しては再実行して次の1件を知る</b>という往復になる
     * (実際 4 ダンジョンが同時にずれていたのに 1 件しか表示されなかった)。
     * 設定の drift は「まとめて直す」のが自然なので、まとめて見せる。
     */
    @Test
    @DisplayName("18ダンジョンの踏破ボスに、属性配分どおりの abilities が付いている")
    void everyDungeonBossCarriesItsPlannedAbilities(@TempDir File tempDir) throws IOException {
        MobOverridesConfig config = loadShippedOverrides(tempDir);
        List<String> mismatches = new ArrayList<>();
        DUNGEON_BOSS_ABILITIES.forEach((world, boss) -> {
            List<String> actual = config.abilitiesFor(world, boss.getKey());
            if (!boss.getValue().equals(actual)) {
                mismatches.add("overrides." + world + ".mobs." + boss.getKey()
                        + ": 期待 abilities: " + boss.getValue() + " / 実測 " + actual);
            }
        });
        assertTrue(mismatches.isEmpty(),
                "踏破ボスの abilities が想定と違うダンジョンがある(" + mismatches.size() + "件): " + mismatches
                        + " ── 柱2-1 は属性配分(物理寄り/魔法寄り/均等/低難度)で割り当てを決めている。"
                        + "実測が [] なら combat/mob-overrides.yml の当該モブに abilities を足し、"
                        + "同じダンジョンの途中フェーズに付いている abilities を消すこと"
                        + "(移動であって追加ではない。詳細は DUNGEON_BOSS_ABILITIES の javadoc)。");
    }

    /**
     * <b>踏破ボスと同じ系列の「途中フェーズ」に技を書いていないこと</b>を固定する。
     * 1件目で止めない理由は {@link #everyDungeonBossCarriesItsPlannedAbilities} と同じ。
     *
     * <p>※2026-08-01〜2026-08-16 は「そのダンジョンで技を持つのは踏破ボス<b>1体だけ</b>」という
     * より強い契約だったが、K の指示でミニボス／節目のボスにも配る方針へ変わったため、
     * <b>この契約は撤回して「途中フェーズ禁止」だけを残した</b>
     * （{@link #EXPECTED_ABILITY_CARRIER_COUNT} の javadoc 参照）。
     * 途中フェーズ禁止のほうは方針が変わっても正しい ── phases は HP 割合で次段へ移るので
     * 中間段は数秒で通過し、<b>一番長く戦う最終段が無技になる</b>という症状は変わらない。
     */
    @Test
    @DisplayName("踏破ボスと同じ系列の途中フェーズには abilities を付けない(最終段が無技になる)")
    void noIntermediatePhaseOfAClearBossCarriesAbilities() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        List<String> mismatches = new ArrayList<>();
        DUNGEON_BOSS_ABILITIES.forEach((world, boss) -> {
            ConfigurationSection mobs = mobsSection(yaml, world);
            String series = phaseSeriesOf(boss.getKey());
            Set<String> strays = new TreeSet<>();
            for (String mobId : mobs.getKeys(false)) {
                if (mobId.equals(boss.getKey()) || !mobId.startsWith(series)) {
                    continue;
                }
                ConfigurationSection mob = mobs.getConfigurationSection(mobId);
                if (mob != null && !mob.getStringList("abilities").isEmpty()) {
                    strays.add(mobId);
                }
            }
            if (!strays.isEmpty()) {
                mismatches.add(world + ": 踏破ボス " + boss.getKey()
                        + " と同じ系列の別段に技が付いている " + strays);
            }
        });
        assertTrue(mismatches.isEmpty(),
                "踏破ボスの途中フェーズに abilities があるダンジョンがある("
                        + mismatches.size() + "件): " + mismatches
                        + " ── phases は HP 割合で次段へ移るので途中段は数秒で通過する。"
                        + "技は必ず最終段へ『移す』こと(両方に書くと二重掲載になり "
                        + "abilityCarrierCountIsPinned も落ちる)。");
    }

    /**
     * {@code the_castle_charlemagne_p4} → {@code the_castle_charlemagne} のように、
     * 末尾のフェーズ表記（{@code _p4} / {@code _phase_3}）を落として系列名にする。
     * フェーズ表記が無いボス（{@code sewer_tier_70_boss} 等）はそのままが系列名。
     */
    private static String phaseSeriesOf(String bossId) {
        return bossId.replaceAll("_(p|phase_)\\d+$", "");
    }

    @Test
    @DisplayName("abilities を持つモブの総数が 99(既存49 + 2026-08-16 のミニボス/節目ボス50)")
    void abilityCarrierCountIsPinned() throws IOException {
        assertEquals(EXPECTED_ABILITY_CARRIER_COUNT, allAbilityUsages().size(),
                "abilities を持つモブの数が変わった。内訳は【既存49】(default のバニラモブ9 + "
                        + "束縛者7 + 柱2-1 の踏破ボス18 + エンチャント試練1〜9の9 + 派生カスタムボス6)"
                        + " + 【2026-08-16 追加の50】(各ダンジョンのミニボス・節目のボス)。"
                        + "増減させたときはこの定数と理由を一緒に更新すること。"
                        + "実際の内訳: " + allAbilityUsages().keySet());
    }

    @Test
    @DisplayName("使われている ability 名がすべて出荷 mob-abilities.yml のテンプレートに実在する")
    void everyAbilityIdExistsInTheShippedTemplates() throws IOException {
        YamlConfiguration abilitiesYaml = loadShippedYaml(MobAbilitiesConfig.PATH);
        Map<String, com.trinityforge.combat.MobAbility> templates = MobAbilitiesConfig
                .parse(abilitiesYaml.getConfigurationSection("abilities"),
                        Logger.getLogger("ShippedBossStrengthDriftTest"))
                .abilities();
        assertFalse(templates.isEmpty(), "出荷 mob-abilities.yml からテンプレートを1つも読めていない");

        Map<String, List<String>> usages = allAbilityUsages();
        Set<String> unknown = new LinkedHashSet<>();
        usages.forEach((owner, ids) -> ids.forEach(id -> {
            if (!templates.containsKey(id)) {
                unknown.add(owner + " -> " + id);
            }
        }));
        assertTrue(unknown.isEmpty(),
                "mob-overrides.yml に mob-abilities.yml へ存在しない ability 名がある: " + unknown
                        + " / 実在するテンプレート: " + new TreeSet<>(templates.keySet())
                        + " ── MobOverridesConfig#parse は実在チェックをしないので、"
                        + "存在しない名前を書いても警告すら出ず『ボスが何も撃たない』としか見えない。");
    }

    // === モブIDの綴り(無言のロックアウト対策) ===

    @Test
    @DisplayName("モブIDは MobIdNormalizer で正規化済みの綴り(.yml を付けない / '.' を含まない)")
    void everyMobIdIsWrittenInItsNormalizedForm() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection overrides = yaml.getConfigurationSection("overrides");
        assertNotNull(overrides, "出荷 mob-overrides.yml に overrides セクションが無い");

        List<String> offenders = new ArrayList<>();
        for (String world : overrides.getKeys(false)) {
            ConfigurationSection mobs = overrides.getConfigurationSection(world + ".mobs");
            if (mobs == null) {
                continue;
            }
            for (String mobId : mobs.getKeys(false)) {
                if (!mobId.equals(MobIdNormalizer.normalize(mobId))) {
                    offenders.add(world + "." + mobId);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "正規化されていないモブIDがある: " + offenders + " ── EliteMobs の "
                        + "CustomConfigFields#getFilename() は必ず .yml 付きを返すが、照合は "
                        + "MobIdNormalizer.normalize() を通した後で行われる(= 拡張子は剥がされ、"
                        + "残った '.' は '_' になる)。このファイルには【拡張子なし】の綴りを書くのが正しく、"
                        + "'.yml' を付けて書くと normalize 後の綴りとずれてオーバーライドが無言で当たらなくなる。");
    }

    /** モブ(「ワールド.モブid」)-&gt; そのモブが持つ ability IDの列。abilities を持つモブだけを載せる。 */
    private static Map<String, List<String>> allAbilityUsages() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection overrides = yaml.getConfigurationSection("overrides");
        assertNotNull(overrides, "出荷 mob-overrides.yml に overrides セクションが無い");

        Map<String, List<String>> usages = new LinkedHashMap<>();
        for (String world : overrides.getKeys(false)) {
            ConfigurationSection mobs = overrides.getConfigurationSection(world + ".mobs");
            if (mobs == null) {
                continue;
            }
            for (String mobId : mobs.getKeys(false)) {
                ConfigurationSection mob = mobs.getConfigurationSection(mobId);
                if (mob == null) {
                    continue;
                }
                List<String> ids = mob.getStringList("abilities");
                if (!ids.isEmpty()) {
                    usages.put(world + "." + mobId, ids);
                }
            }
        }
        return usages;
    }
}
