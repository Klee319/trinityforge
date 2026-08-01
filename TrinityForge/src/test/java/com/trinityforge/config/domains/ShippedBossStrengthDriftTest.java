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
 *   <li><b>倍率は yml に書けない。</b> {@code MobStatOverride} は絶対値しか受け取らないので、
 *       プランが倍率で書いた値は「共通ランプ({@code combat/mob-import.yml})を Lv50 で評価した実値 ×倍率」
 *       として展開してある。ここではその割り戻しが計画どおりの倍率に戻ることを固定する
 *       ── ランプ側の base/growth を触ると倍率の意味が変わるので、そのときここが落ちる。</li>
 * </ul>
 */
class ShippedBossStrengthDriftTest {

    // === 柱2 の出典となる共通ランプ(combat/mob-import.yml)と束縛者の contentLevel ===

    /** 束縛者は {@code contentLevel: 50} 固定(level-sync が無い)。柱2 の倍率はこのレベルで評価する。 */
    private static final int BINDER_CONTENT_LEVEL = 50;

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

    /** 柱2-1 の割り当て表: ワールド名 -&gt; その踏破ボス(印を落とすモブ)id と ability の列。 */
    private static final Map<String, Map.Entry<String, List<String>>> DUNGEON_BOSS_ABILITIES =
            new LinkedHashMap<>();

    static {
        // 物理寄り(物理 defense-rate .418) — shockwave + bull_rush
        List<String> phys = List.of("shockwave", "bull_rush");
        putBoss("em_id_the_mines", "the_mines_soulweaver_daine_p3", phys);
        putBoss("em_id_the_deep_mines", "em_id_the_deep_mines_boss_the_pursuer_p3", phys);
        putBoss("em_id_the_quarry", "em_id_the_quarry_royal_wizard_five_unlocker_p3", phys);
        putBoss("em_id_the_city", "em_id_the_city_royal_guard_p3", phys);
        putBoss("em_knight_castle", "the_castle_charlemagne_p3", phys);
        putBoss("em_steamworks_lair", "the_steamworks_clk_wrx702_p3", phys);
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
        putBoss("em_id_the_climb", "the_climb_undead_beastmaster_p3", even);
        putBoss("em_id_the_palace", "the_palace_old_stone_king_p3", even);
        putBoss("em_sewer_maze", "sewer_tier_70_boss", even);
        // 低難度 — frost_field 1つだけ
        List<String> easy = List.of("frost_field");
        putBoss("em_id_enchantment_challenge_10", "enchantment_boss_tricky_bones", easy);
        putBoss("em_north_pole", "northpole_santa_claus", easy);
        putBoss("em_id_the_nether_wastes", "em_id_the_nether_wastes_miniboss_5_shroud_p2", easy);
        // エンチャント試練1〜9(2026-08-02 積み残しの解消)。低難度なので技は1つずつ。
        // 課題ごとに要求ビルドが入れ替わるダンジョン群なので、技もボスの性格に合わせて散らしてある。
        putBoss("em_id_enchantment_challenge_1", "enchantment_boss_dark_flame", List.of("ember_spray"));
        putBoss("em_id_enchantment_challenge_2", "enchantment_boss_energized_bunny", List.of("shadow_step"));
        putBoss("em_id_enchantment_challenge_3", "enchantment_boss_jealous_block", List.of("shockwave"));
        putBoss("em_id_enchantment_challenge_4", "enchantment_boss_leet_summoner", List.of("call_the_swarm"));
        putBoss("em_id_enchantment_challenge_5", "enchantment_boss_loveable_impaler", List.of("piercing_beam"));
        putBoss("em_id_enchantment_challenge_6", "enchantment_boss_ravegarer", List.of("bull_rush"));
        putBoss("em_id_enchantment_challenge_7", "enchantment_boss_rock_solid_cold", List.of("frost_field"));
        putBoss("em_id_enchantment_challenge_8", "enchantment_boss_the_firebunger", List.of("ember_spray"));
        putBoss("em_id_enchantment_challenge_9", "enchantment_boss_the_glass_master", List.of("arrow_fan"));
    }

    private static void putBoss(String world, String mobId, List<String> abilities) {
        DUNGEON_BOSS_ABILITIES.put(world, Map.entry(mobId, abilities));
    }

    /**
     * abilities を持つモブの総数。9(default のバニラモブ) + 7(束縛者、2026-07-31)
     * + 18(柱2-1、2026-08-01) + 9(エンチャント試練1〜9、2026-08-02) = 43。
     * 増減したらこの定数と一緒に「なぜ増えたか」を書くこと。
     */
    private static final int EXPECTED_ABILITY_CARRIER_COUNT = 43;

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

    @Test
    @DisplayName("束縛者の4段階＋ミニボス3種が、共通ランプLv50実値×計画倍率の絶対値で書かれている")
    void binderTierMatchesThePlannedMultipliers(@TempDir File tempDir) throws IOException {
        MobOverridesConfig config = loadShippedOverrides(tempDir);
        double rampHp = rampAt(RAMP_HP_BASE, RAMP_HP_GROWTH, BINDER_CONTENT_LEVEL);
        double rampAttack = rampAt(RAMP_ATTACK_BASE, RAMP_ATTACK_GROWTH, BINDER_CONTENT_LEVEL);

        BINDER_TIER.forEach((mobId, multipliers) -> {
            MobProfile resolved = config.resolve(BINDER_WORLD, mobId, neutralBase(mobId));
            double expectedHp = Math.rint(rampHp * multipliers[0]);
            double expectedAttack = Math.rint(rampAttack * multipliers[1] * 100.0) / 100.0;

            assertEquals(expectedHp, resolved.maxHealth(), 1.0,
                    mobId + " の max-health が " + resolved.maxHealth() + "。柱2 は共通ランプ Lv"
                            + BINDER_CONTENT_LEVEL + " 実値 " + String.format("%.2f", rampHp)
                            + " × " + multipliers[0] + " = " + expectedHp + " を指定している。"
                            + "倍率キーは MobStatOverride に無いので、ここは絶対値でしか書けない。");
            assertEquals(expectedAttack, resolved.attack().defaultDamage(), 0.01,
                    mobId + " の attack.attack-power が " + resolved.attack().defaultDamage()
                            + "。柱2 は共通ランプ Lv" + BINDER_CONTENT_LEVEL + " 実値 "
                            + String.format("%.2f", rampAttack) + " × " + multipliers[1]
                            + " = " + expectedAttack + " を指定している。");
        });
    }

    @Test
    @DisplayName("束縛者の増援は HP も攻撃も無干渉のまま(未設定 = 0 ではなく『下位層をそのまま使う』)")
    void binderReinforcementsAreLeftUntouched(@TempDir File tempDir) throws IOException {
        MobOverridesConfig config = loadShippedOverrides(tempDir);
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        ConfigurationSection mobs = mobsSection(yaml, BINDER_WORLD);

        List<String> reinforcements = mobs.getKeys(false).stream()
                .filter(id -> id.contains("reinforcement"))
                .sorted()
                .toList();
        assertEquals(11, reinforcements.size(),
                "束縛者の増援は 11 体のはず(プラン本文の『増援×10』は実データと1体ずれている)。"
                        + "実際に見つかったのは " + reinforcements);

        // 素の profile に 999/9.99 を敷き、オーバーライド後もその値が残る = 何も上書きしていない。
        for (String mobId : reinforcements) {
            MobProfile base = new MobProfile(mobId, 1, null, DefenseStats.NONE, DefenseStats.NONE,
                    AttackStats.plain(9.99), 999.0, false);
            MobProfile resolved = config.resolve(BINDER_WORLD, mobId, base);
            assertEquals(999.0, resolved.maxHealth(), 1.0e-9,
                    mobId + " に max-health が書かれている。増援は『数で圧をかける役』なので"
                            + "個体を強くしない、というのが柱2 の明示的な指定。");
            assertEquals(9.99, resolved.attack().defaultDamage(), 1.0e-9,
                    mobId + " に attack-power が書かれている(同上)。");
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

    // === 柱2-1: 他ダンジョンのボスへ配った abilities ===

    @Test
    @DisplayName("18ダンジョンの踏破ボスに、属性配分どおりの abilities が付いている")
    void everyDungeonBossCarriesItsPlannedAbilities(@TempDir File tempDir) throws IOException {
        MobOverridesConfig config = loadShippedOverrides(tempDir);
        DUNGEON_BOSS_ABILITIES.forEach((world, boss) -> {
            List<String> actual = config.abilitiesFor(world, boss.getKey());
            assertEquals(boss.getValue(), actual,
                    world + " の踏破ボス " + boss.getKey() + " の abilities が想定と違う。"
                            + "柱2-1 は属性配分(物理寄り/魔法寄り/均等/低難度)で割り当てを決めている。");
        });
    }

    @Test
    @DisplayName("abilities は踏破ボスにだけ。同じダンジョンのミニボス・雑魚には付けない")
    void onlyTheClearBossOfEachDungeonCarriesAbilities() throws IOException {
        YamlConfiguration yaml = loadShippedYaml(MobOverridesConfig.PATH);
        DUNGEON_BOSS_ABILITIES.forEach((world, boss) -> {
            ConfigurationSection mobs = mobsSection(yaml, world);
            Set<String> carriers = new TreeSet<>();
            for (String mobId : mobs.getKeys(false)) {
                ConfigurationSection mob = mobs.getConfigurationSection(mobId);
                if (mob != null && !mob.getStringList("abilities").isEmpty()) {
                    carriers.add(mobId);
                }
            }
            assertEquals(Set.of(boss.getKey()), carriers,
                    world + " で abilities を持つモブが踏破ボス1体になっていない。"
                            + "『ボスだけが技を撃つ』という手触りを守るため、ミニボス・雑魚・増援には付けない"
                            + "(束縛者だけは 2026-07-31 に決めた例外で、ミニボス3種も技を持つ)。");
        });
    }

    @Test
    @DisplayName("abilities を持つモブの総数が 34(default 9 + 束縛者 7 + 踏破ボス 18)")
    void abilityCarrierCountIsPinned() throws IOException {
        assertEquals(EXPECTED_ABILITY_CARRIER_COUNT, allAbilityUsages().size(),
                "abilities を持つモブの数が変わった。内訳は default のバニラモブ9 + 束縛者7 + "
                        + "柱2-1 の踏破ボス18。増減させたときはこの定数と理由を一緒に更新すること。"
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
