package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 yml だけを読み、<b>「作れるのに素材が手に入らない」レシピが存在しないこと</b>を掃きで確認する
 * (2026-08-03 追加)。
 *
 * <h2>なぜ必要か(実際に起きた事故)</h2>
 * {@code items/catalog.yml} の儀式3本 —— 暗殺者の弓({@code hero_bow}) / 暗殺者のクロスボウ
 * ({@code hero_crossbow}) / 黒淵の杖({@code abyss_cane}) —— が {@code custom:warden_tendril x2} を
 * 要求していたのに、この素材は <b>ガチャ表にもどのモブのドロップ表にも1件も載っていなかった</b>。
 * 姉妹素材の {@code dragon_scale} / {@code elder_guardian_spike} / {@code wither_skull_fragment} は
 * {@code gacha.yml} に載っているため、抜けているのは1件だけ。レシピ帳にも図鑑にも普通に並び、
 * ログにも警告にも一切出ないので、<b>プレイヤーが「一生作れない」と報告するまで誰も気づけない</b>。
 *
 * <h2>何を入手経路として数えるか</h2>
 * TF が実装として持っている配布経路だけを数える。
 * <ul>
 *   <li>{@code gacha.yml} の {@code pools.*.entries[].item}(規約により {@code custom:} を付けない)</li>
 *   <li>{@code combat/mob-overrides.yml} の {@code overrides.*.mobs.*.drops[].item}
 *       —— EliteMobs 個体({@code MOB_PROFILE_ID} 刻印あり)のドロップ</li>
 *   <li>{@code combat/mob-level-table.yml} の {@code tiers[].add-drops[].material}
 *       —— バニラ/フィールドモブのドロップ(EntityType で絞れる唯一の経路)</li>
 *   <li>{@code combat/mob-types.yml} の {@code mob-types.*.drops[].material}</li>
 * </ul>
 *
 * <h2>対象を「討伐素材」に限る理由(許可リストではない)</h2>
 * 儀式の素材には {@code blaze_rod_3x} のような ArsPaper {@code materials.yml} 側の圧縮素材も
 * 含まれる。それらは Ars 側にレシピを持つが、フォークのソースは {@code .gitignore} 除外で
 * クローンにも CI にも存在しないため、このテストからは検証しようがない(見えないものを
 * 「経路なし」と判定すると常時赤になる)。そこで判定対象は
 * <b>{@code progression/collection.yml} の図鑑カテゴリ「討伐素材」({@code categories.items.mob_parts})に
 * 載っている素材</b>に絞る —— これは「TF 側が入手経路を用意すると宣言した素材」の一覧そのもので、
 * 手書きの許可リストではなく出荷設定から導出される。将来この一覧に素材を足して経路を付け忘れれば
 * このテストが落ちる。
 */
class ShippedRitualMaterialObtainabilityTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";
    private static final String COLLECTION = "src/main/resources/progression/collection.yml";
    private static final String GACHA = "src/main/resources/gacha.yml";
    private static final String MOB_OVERRIDES = "src/main/resources/combat/mob-overrides.yml";
    private static final String MOB_LEVEL_TABLE = "src/main/resources/combat/mob-level-table.yml";
    private static final String MOB_TYPES = "src/main/resources/combat/mob-types.yml";

    private static final String CUSTOM_PREFIX = "custom:";

    /**
     * {@code MobOverridesConfig} が使うのと同じパス区切り。既定の {@code '.'} のままだと、
     * {@code boss.yml} のような '.' を含むモブidキーが読み込み時点でネスト分解され、
     * その配下の {@code drops:} が見えなくなる(＝経路を見落として偽の赤を出す)。
     */
    private static final char MOB_ID_SAFE_PATH_SEPARATOR = (char) 1;

    /** 討伐素材カテゴリの下限件数。カテゴリごと消えてテストが空回りするのを防ぐ。 */
    private static final int MIN_EXPECTED_MOB_PARTS = 17;

    /** 儀式が要求する討伐素材の下限件数。儀式節が消えてテストが空回りするのを防ぐ。 */
    private static final int MIN_EXPECTED_RITUAL_MOB_PARTS = 4;

    // ------------------------------------------------------------------------------------------
    // テスト本体
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("出荷の討伐素材は全件が実在の入手経路(モブドロップ/ガチャ)を持つ — 経路ゼロの素材が1件でもあれば落ちる")
    void everyShippedMobPartHasAnObtainmentPath() throws Exception {
        Map<String, Set<String>> paths = obtainmentPaths();
        List<String> mobParts = mobPartCollectionEntries();

        List<String> missing = new ArrayList<>();
        for (String entry : mobParts) {
            if (!paths.containsKey(normalize(entry))) {
                missing.add(entry);
            }
        }

        assertTrue(missing.isEmpty(),
                "図鑑の「討伐素材」に載っているのに、TF 側の入手経路が1つも無い素材がある。"
                        + "レシピ帳にも図鑑にも普通に並ぶのにプレイヤーは永久に入手できず、"
                        + "ログにも警告にも一切出ない(実際に warden_tendril がこの状態で、"
                        + "儀式3本 hero_bow / hero_crossbow / abyss_cane が作成不能だった)。"
                        + "経路は combat/mob-level-table.yml の add-drops(バニラモブ) か "
                        + "combat/mob-overrides.yml の drops(EliteMobs個体) か gacha.yml に足すこと。"
                        + " 経路ゼロの素材: " + missing);
    }

    @Test
    @DisplayName("儀式レシピが要求する討伐素材に入手経路がある — warden_tendril のドロップを消すと儀式3本が作成不能に戻って落ちる")
    void everyRitualMobPartMaterialIsObtainable() throws Exception {
        Set<String> ritualMaterials = ritualCustomMaterials();
        assertTrue(ritualMaterials.contains("warden_tendril"),
                "出荷カタログの儀式が custom:warden_tendril を要求しなくなっている。"
                        + "レシピ側を書き換えて「作れない素材」を回避したのなら、このテストの前提"
                        + "(儀式が討伐素材を要求する)ごと見直すこと。要求している儀式素材: "
                        + ritualMaterials);

        Set<String> mobParts = new TreeSet<>();
        for (String entry : mobPartCollectionEntries()) {
            mobParts.add(normalize(entry));
        }
        Map<String, Set<String>> paths = obtainmentPaths();

        List<String> checked = new ArrayList<>();
        List<String> unobtainable = new ArrayList<>();
        for (String material : ritualMaterials) {
            if (!mobParts.contains(material)) {
                // ArsPaper materials.yml 側の圧縮素材など。fork のソースは .gitignore 除外で
                // 読めないため、ここでは判定対象にしない(class javadoc 参照)。
                continue;
            }
            checked.add(material);
            if (!paths.containsKey(material)) {
                unobtainable.add(material);
            }
        }

        assertTrue(checked.size() >= MIN_EXPECTED_RITUAL_MOB_PARTS,
                "儀式が要求する討伐素材が " + checked.size() + " 件しか集まっていない(期待: "
                        + MIN_EXPECTED_RITUAL_MOB_PARTS + " 件以上)。catalog.yml の儀式節か "
                        + "collection.yml の討伐素材カテゴリが消えていないか確認すること: " + checked);
        assertTrue(unobtainable.isEmpty(),
                "儀式レシピが要求しているのに入手経路が1つも無い討伐素材がある。"
                        + "その儀式は【永久に完成できない】: " + unobtainable);
    }

    @Test
    @DisplayName("warden_tendril が踏破ボス3種に chance 0.5 / 1〜2個で載っている — 率・個数・ボスを変えると落ちる")
    void wardenTendrilIsPinnedOnTheThreeClearBosses() throws Exception {
        YamlConfiguration overridesYaml = load(MOB_OVERRIDES, MOB_ID_SAFE_PATH_SEPARATOR);
        ConfigurationSection overrides = overridesYaml.getConfigurationSection("overrides");
        assertNotNull(overrides, MOB_OVERRIDES + " に overrides: 節が無い");

        Map<String, String> found = new TreeMap<>();
        for (String scopeKey : overrides.getKeys(false)) {
            ConfigurationSection scope = overrides.getConfigurationSection(scopeKey);
            if (scope == null) {
                continue;
            }
            ConfigurationSection mobs = scope.getConfigurationSection("mobs");
            if (mobs == null) {
                continue;
            }
            for (String mobId : mobs.getKeys(false)) {
                ConfigurationSection mob = mobs.getConfigurationSection(mobId);
                if (mob == null) {
                    continue;
                }
                for (Map<?, ?> drop : mob.getMapList("drops")) {
                    if (!"warden_tendril".equals(stripCustomPrefix(text(drop.get("item"))))) {
                        continue;
                    }
                    found.put(mobId, "chance=" + number(drop.get("chance"))
                            + " min=" + number(drop.get("min"))
                            + " max=" + number(drop.get("max")));
                }
            }
        }

        Map<String, String> expected = new TreeMap<>();
        expected.put("em_id_the_deep_mines_boss_the_pursuer_p3", "chance=0.5 min=1.0 max=2.0");
        expected.put("em_id_the_city_royal_guard_p3", "chance=0.5 min=1.0 max=2.0");
        expected.put("dark_cathedral_tier_75_boss_phase_3", "chance=0.5 min=1.0 max=2.0");

        assertEquals(expected, found,
                "custom:warden_tendril の配布先/率/個数が変わっている。儀式1本あたり x2、3本で計6個"
                        + "必要という前提で chance 0.5・1〜2個(1討伐あたり平均0.75個)にしてあるので、"
                        + "率や個数を下げるなら「儀式3本を作るのに何回の踏破が要るか」を再計算すること。"
                        + "この3種以外へ移す場合も、必ず踏破ボス(周回コストが高い枠)に置くこと。");
    }

    // ------------------------------------------------------------------------------------------
    // 出荷 yml の読み取り
    // ------------------------------------------------------------------------------------------

    private static YamlConfiguration load(String relativePath) throws Exception {
        return load(relativePath, '.');
    }

    private static YamlConfiguration load(String relativePath, char pathSeparator) throws Exception {
        Path path = Path.of(relativePath);
        assertTrue(Files.isRegularFile(path), "出荷 yml が見つからない: " + path.toAbsolutePath());
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.options().pathSeparator(pathSeparator);
        cfg.loadFromString(Files.readString(path));
        return cfg;
    }

    /** 図鑑カテゴリ「討伐素材」のエントリ一覧(＝TF が入手経路を用意すると宣言した素材)。 */
    private static List<String> mobPartCollectionEntries() throws Exception {
        YamlConfiguration collection = load(COLLECTION);
        ConfigurationSection mobParts = collection.getConfigurationSection("categories.items.mob_parts");
        assertNotNull(mobParts,
                COLLECTION + " の categories.items.mob_parts が無い。図鑑の討伐素材カテゴリは"
                        + "「入手経路を用意すると宣言した素材」の一覧として、このテストの判定対象を決めている");
        List<String> entries = mobParts.getStringList("entries");
        assertTrue(entries.size() >= MIN_EXPECTED_MOB_PARTS,
                "討伐素材カテゴリが " + entries.size() + " 件しかない(期待: " + MIN_EXPECTED_MOB_PARTS
                        + " 件以上)。カテゴリごと消えるとこのテストが素通りするので下限で縛っている");
        return entries;
    }

    /** 出荷カタログの<b>儀式</b>レシピが要求する {@code custom:} 素材ID(小文字・接頭辞なし)。 */
    private static Set<String> ritualCustomMaterials() throws Exception {
        YamlConfiguration catalog = load(CATALOG);
        ConfigurationSection items = catalog.getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");

        Set<String> materials = new TreeSet<>();
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item == null) {
                continue;
            }
            ConfigurationSection single = item.getConfigurationSection("recipe");
            if (single != null) {
                collectRitual(single.getString("method"), single.getStringList("pedestal-items"),
                        single.getString("core-item"), materials);
            }
            for (Map<?, ?> recipe : item.getMapList("recipes")) {
                collectRitual(text(recipe.get("method")), strings(recipe.get("pedestal-items")),
                        text(recipe.get("core-item")), materials);
            }
        }
        return materials;
    }

    private static void collectRitual(String method, List<String> pedestalItems, String coreItem,
                                       Set<String> out) {
        if (method == null || !"ritual".equalsIgnoreCase(method.trim())) {
            return;
        }
        for (String token : pedestalItems) {
            addCustomId(out, token);
        }
        addCustomId(out, coreItem);
    }

    /** {@code "custom:warden_tendril x2"} 形式のトークンからIDだけを取り出す。 */
    private static void addCustomId(Set<String> out, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String head = token.trim().split("\\s+")[0];
        if (head.toLowerCase(Locale.ROOT).startsWith(CUSTOM_PREFIX)) {
            out.add(normalize(head.substring(CUSTOM_PREFIX.length())));
        }
    }

    // ------------------------------------------------------------------------------------------
    // 入手経路の収集
    // ------------------------------------------------------------------------------------------

    /** 素材ID -> その素材を配っている出荷設定の場所(人が読める説明)の集合。 */
    private static Map<String, Set<String>> obtainmentPaths() throws Exception {
        Map<String, Set<String>> paths = new TreeMap<>();

        // (1) ガチャ。entries[].item は規約により custom: を付けない。
        YamlConfiguration gacha = load(GACHA);
        ConfigurationSection pools = gacha.getConfigurationSection("pools");
        assertNotNull(pools, GACHA + " に pools: 節が無い");
        for (String poolKey : pools.getKeys(false)) {
            ConfigurationSection pool = pools.getConfigurationSection(poolKey);
            if (pool == null) {
                continue;
            }
            for (Map<?, ?> entry : pool.getMapList("entries")) {
                addPath(paths, text(entry.get("item")), "gacha.yml pools." + poolKey);
            }
        }

        // (2) EliteMobs 個体のドロップ(MOB_PROFILE_ID 刻印がある個体にだけ効く)。
        YamlConfiguration overridesYaml = load(MOB_OVERRIDES, MOB_ID_SAFE_PATH_SEPARATOR);
        ConfigurationSection overrides = overridesYaml.getConfigurationSection("overrides");
        assertNotNull(overrides, MOB_OVERRIDES + " に overrides: 節が無い");
        for (String scopeKey : overrides.getKeys(false)) {
            ConfigurationSection scope = overrides.getConfigurationSection(scopeKey);
            if (scope == null) {
                continue;
            }
            ConfigurationSection mobs = scope.getConfigurationSection("mobs");
            if (mobs == null) {
                continue;
            }
            for (String mobId : mobs.getKeys(false)) {
                ConfigurationSection mob = mobs.getConfigurationSection(mobId);
                if (mob == null) {
                    continue;
                }
                for (Map<?, ?> drop : mob.getMapList("drops")) {
                    addPath(paths, text(drop.get("item")),
                            "mob-overrides.yml " + scopeKey + "/" + mobId);
                }
            }
        }

        // (3) バニラ/フィールドモブのレベル帯ドロップ。
        YamlConfiguration levelTable = load(MOB_LEVEL_TABLE);
        for (Map<?, ?> tier : levelTable.getMapList("tiers")) {
            for (Map<?, ?> drop : maps(tier.get("add-drops"))) {
                addPath(paths, text(drop.get("material")),
                        "mob-level-table.yml min-level=" + text(tier.get("min-level")));
            }
        }

        // (4) EntityType 単位のドロップ。
        YamlConfiguration mobTypes = load(MOB_TYPES);
        ConfigurationSection types = mobTypes.getConfigurationSection("mob-types");
        if (types != null) {
            for (String typeKey : types.getKeys(false)) {
                ConfigurationSection type = types.getConfigurationSection(typeKey);
                if (type == null) {
                    continue;
                }
                for (Map<?, ?> drop : type.getMapList("drops")) {
                    addPath(paths, text(drop.get("material")), "mob-types.yml " + typeKey);
                }
            }
        }

        return paths;
    }

    private static void addPath(Map<String, Set<String>> paths, String rawId, String source) {
        if (rawId == null || rawId.isBlank()) {
            return;
        }
        paths.computeIfAbsent(stripCustomPrefix(rawId), key -> new TreeSet<>()).add(source);
    }

    // ------------------------------------------------------------------------------------------
    // 小道具
    // ------------------------------------------------------------------------------------------

    private static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripCustomPrefix(String raw) {
        String normalized = normalize(raw);
        if (normalized == null) {
            return null;
        }
        return normalized.startsWith(CUSTOM_PREFIX)
                ? normalized.substring(CUSTOM_PREFIX.length()) : normalized;
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    /** 数値を型に依存せず比較できる形へ。整数/小数のどちらで書かれても同じ文字列になる。 */
    private static String number(Object raw) {
        return raw instanceof Number n ? String.valueOf(n.doubleValue()) : String.valueOf(raw);
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element != null) {
                    out.add(String.valueOf(element));
                }
            }
        }
        return out;
    }

    private static List<Map<?, ?>> maps(Object raw) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> map) {
                    out.add(map);
                }
            }
        }
        return out;
    }
}
