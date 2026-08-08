package com.trinityforge.config.domains;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 yml のダンジョンの鍵({@code key_*})が<b>入手経路を必ず1つ以上持つ</b>ことを固定する
 * (2026-08-01, 追加コンテンツ 柱1)。
 *
 * <p><b>なぜ必要か</b>: 鍵はカタログに書いただけでは<b>どこからも出ない</b>。
 * {@code items/catalog.yml} の定義・{@code recipe:}・各ギミックの {@code drop-tables} は
 * 互いに独立した3つの層で、どれか1つを書き忘れても<b>警告もエラーも出ない</b>。
 * 結果として「レシピ帳にも載らず、どのドロップ表にも無い、永久に入手不能な鍵」が静かに生まれ、
 * しかもその鍵を {@code dungeon/gates.yml} の {@code key-item} に指定すると
 * <b>そのダンジョンが誰も入れない部屋になる</b>。
 * 「集めてみるまで気づけない」形の不具合なので、机上で落とす。
 *
 * <p>入手経路として数えるのは
 * {@code items/catalog.yml} の {@code recipe:} / 採掘・掘削・釣りギミックの {@code drop-tables} /
 * {@code gacha.yml} の各プール。EM ボスの確定ドロップ({@code combat/mob-overrides.yml})は
 * 印({@code dungeon_seal_*})専用で鍵は配っていないため対象外。
 *
 * <p>印そのものと圧縮素材は ArsPaper 側 {@code materials.yml} 定義で、そのソースは
 * {@code .gitignore} 除外のためワークツリーに存在しない。よって
 * <b>「実在するか」ではなく「TF 側の3つの層で辻褄が合っているか」だけを検査する</b>。
 */
class ShippedDungeonKeyReachabilityTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";
    private static final String MATERIAL_LISTS = "src/main/resources/items/material-lists.yml";
    private static final String COLLECTION = "src/main/resources/progression/collection.yml";

    /** 鍵が湧きうるドロップ表。ここに無いファイルへ鍵を置いても入手経路として数えない。 */
    private static final List<String> DROP_SOURCES = List.of(
            "src/main/resources/stats/mining-gimmick.yml",
            "src/main/resources/stats/digging-gimmick.yml",
            "src/main/resources/stats/fishing-gimmick.yml",
            "src/main/resources/gacha.yml");

    /**
     * 意図的に未定義のまま残す鍵ID(現在は0件)。
     *
     * <p>※2026-08-08訂正: 以前は {@code key_hallosseum}/{@code key_north_pole} が
     * 「ルートチェスト機構が無く入手経路を用意できない」として未定義のままここに列挙されていたが、
     * この2種にも workbench レシピを追加したため定義済みになった。今後また入手経路を用意できない
     * 鍵が出た場合はここへ追加すること。
     */
    private static final Set<String> INTENTIONALLY_UNDEFINED = Set.of();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("すべての key_* が入手経路(レシピ or ドロップ表)を持つ")
    void everyDungeonKeyIsObtainable() {
        Set<String> keys = keyItemIds();
        assertFalse(keys.isEmpty(), "catalog.yml から key_* を1件も読めていない"
                + "(構造が変わったならこのテストも直すこと)");

        Set<String> craftable = keysWithRecipe();
        Set<String> dropped = droppedItemIds();

        List<String> unreachable = new ArrayList<>();
        for (String key : keys) {
            if (!craftable.contains(key) && !dropped.contains(key)) {
                unreachable.add(key);
            }
        }
        assertTrue(unreachable.isEmpty(),
                "入手経路がゼロの鍵がある(レシピも無くどのドロップ表にも載っていない)。"
                        + "この鍵を gates.yml の key-item に指定すると誰も入れないダンジョンになる: "
                        + unreachable);
    }

    @Test
    @DisplayName("key_* は TRIAL_KEY ベースで CMD が 5501-5531 に収まり重複しない")
    void dungeonKeysShareBaseMaterialAndHaveUniqueModelData() {
        ConfigurationSection items = catalogItems();
        Map<Integer, String> seen = new LinkedHashMap<>();
        for (String key : keyItemIds()) {
            ConfigurationSection entry = items.getConfigurationSection(key);
            assertNotNull(entry, key + " のセクションが読めない");

            String materialName = entry.getString("material");
            assertEquals("TRIAL_KEY", materialName,
                    key + " のベース Material が TRIAL_KEY ではない"
                            + "(鍵は stats/item-stats.yml に個別ステを持たない前提で TRIAL_KEY に揃えている)");
            assertNotNull(Material.matchMaterial(String.valueOf(materialName)),
                    key + " の material が解決できない");

            int cmd = entry.getInt("custom-model-data", -1);
            assertTrue(cmd >= 5501 && cmd <= 5531,
                    key + " の custom-model-data " + cmd + " が鍵用に確保した 5501-5531 の外にある");
            String previous = seen.put(cmd, key);
            assertTrue(previous == null,
                    "custom-model-data " + cmd + " が " + previous + " と " + key + " で重複している"
                            + "(同じ見た目・同じ識別になり、片方が他方として扱われる)");
        }
    }

    @Test
    @DisplayName("鍵の CMD がカタログの他アイテムと衝突しない")
    void dungeonKeyModelDataDoesNotCollideWithOtherCatalogItems() {
        ConfigurationSection items = catalogItems();
        Set<String> keys = keyItemIds();
        List<String> collisions = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            if (keys.contains(id)) {
                continue;
            }
            ConfigurationSection entry = items.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }
            int cmd = entry.getInt("custom-model-data", -1);
            if (cmd >= 5501 && cmd <= 5531) {
                collisions.add(id + "(CMD " + cmd + ")");
            }
        }
        assertTrue(collisions.isEmpty(),
                "鍵用に確保した CMD 5501-5531 を鍵以外のアイテムが使っている: " + collisions);
    }

    @Test
    @DisplayName("互換リスト dungeon_seals は collection.yml の印28種と完全に一致する")
    void dungeonSealsListMatchesCollectionEntries() {
        Set<String> listMembers = new TreeSet<>();
        ConfigurationSection seals = materialLists().getConfigurationSection("dungeon_seals");
        assertNotNull(seals, "material-lists.yml に dungeon_seals が無い"
                + "(key_binder のレシピが list 未定義で丸ごと無効化される)");
        for (String raw : seals.getStringList("materials")) {
            String token = String.valueOf(raw).trim();
            assertTrue(token.startsWith("custom:"),
                    "dungeon_seals のメンバー '" + token + "' が custom: 参照ではない"
                            + "(印は ArsPaper 定義なのでバニラ Material としては解決できない)");
            listMembers.add(token.substring("custom:".length()));
        }

        Set<String> collectionSeals = new TreeSet<>(collectionSealIds());
        assertFalse(collectionSeals.isEmpty(), "collection.yml から印を1件も読めていない");
        assertEquals(collectionSeals, listMembers,
                "dungeon_seals と collection.yml の印一覧が食い違っている。"
                        + "片方にしか無い印は『集めても key_binder に使えない』"
                        + "または『存在しない印を要求する』のどちらかになる");
    }

    @Test
    @DisplayName("key_binder は印を5個要求する(任意5種で到達できる)")
    void binderKeyConsumesFiveSeals() {
        ConfigurationSection binder = catalogItems().getConfigurationSection("key_binder");
        assertNotNull(binder, "key_binder が定義されていない");
        ConfigurationSection recipe = binder.getConfigurationSection("recipe");
        assertNotNull(recipe, "key_binder にレシピが無い(合成でしか入手できない鍵なので必須)");

        List<String> ingredients = recipe.getStringList("ingredients");
        long seals = ingredients.stream().filter("list:dungeon_seals"::equals).count();
        assertEquals(5, seals,
                "key_binder が要求する印の数が5個ではない。"
                        + "全種すべてを要求すると『名目上の第1目標が実際は最後に解ける』構造に戻る");
        assertTrue(ingredients.size() <= 9,
                "shapeless レシピの素材が9個を超えている(作業台に並べきれず登録が落ちる): "
                        + ingredients.size());
    }

    @Test
    @DisplayName("カタログが参照する list:<id> は全部 material-lists.yml に実在する")
    void everyReferencedMaterialListIsDefined() {
        Set<String> defined = materialLists().getKeys(false);
        Set<String> referenced = new TreeSet<>();
        collectListTokens(YamlConfiguration.loadConfiguration(new File(CATALOG)).getValues(true).values(), referenced);

        assertFalse(referenced.isEmpty(), "catalog.yml から list: 参照を1件も読めていない");
        List<String> missing = referenced.stream().filter(id -> !defined.contains(id)).toList();
        assertTrue(missing.isEmpty(),
                "未定義の互換リストを参照しているレシピがある。"
                        + "該当レシピだけが起動時に警告付きで無効化され、アイテムは作れなくなる: " + missing);
    }

    @Test
    @DisplayName("入手経路を用意できなかった2種はカタログに生やさない")
    void keysWithoutAnyRouteAreNotDefined() {
        Set<String> keys = keyItemIds();
        List<String> defined = INTENTIONALLY_UNDEFINED.stream().filter(keys::contains).toList();
        assertTrue(defined.isEmpty(),
                defined + " が定義されている。ルートチェストへの独自アイテム注入機構は存在しない"
                        + "(LootGenerateEvent は VanillaItemRemovalListener の削除用途にしか使っていない)ので、"
                        + "入手経路を先に用意すること。用意したらこのテストの除外リストから外す");
    }

    // ---- helpers ----

    private static ConfigurationSection catalogItems() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(CATALOG));
        ConfigurationSection items = yaml.getConfigurationSection("items");
        assertNotNull(items, "items セクションが読めない: " + new File(CATALOG).getAbsolutePath());
        return items;
    }

    private static ConfigurationSection materialLists() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(MATERIAL_LISTS));
        ConfigurationSection lists = yaml.getConfigurationSection("lists");
        assertNotNull(lists, "lists セクションが読めない: " + new File(MATERIAL_LISTS).getAbsolutePath());
        return lists;
    }

    /** カタログ上の {@code key_*} アイテムID。 */
    private static Set<String> keyItemIds() {
        Set<String> out = new LinkedHashSet<>();
        for (String id : catalogItems().getKeys(false)) {
            if (id.startsWith("key_")) {
                out.add(id);
            }
        }
        return out;
    }

    private static Set<String> keysWithRecipe() {
        Set<String> out = new LinkedHashSet<>();
        ConfigurationSection items = catalogItems();
        for (String id : keyItemIds()) {
            ConfigurationSection entry = items.getConfigurationSection(id);
            if (entry != null && entry.getConfigurationSection("recipe") != null) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * ドロップ表側で配られているアイテムID。どのファイルも
     * {@code entries: [{item: <id>, weight: N, amount: N}, ...]} という同じ形をしているので、
     * ネストの深さに依存せず {@code entries} を再帰的に拾う。
     */
    private static Set<String> droppedItemIds() {
        Set<String> out = new LinkedHashSet<>();
        for (String path : DROP_SOURCES) {
            File file = new File(path);
            assertTrue(file.isFile(), "ドロップ表が見つからない: " + file.getAbsolutePath());
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (Map.Entry<String, Object> entry : yaml.getValues(true).entrySet()) {
                if (!entry.getKey().endsWith("entries") || !(entry.getValue() instanceof List<?> rows)) {
                    continue;
                }
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map) {
                        Object item = map.get("item");
                        if (item != null) {
                            out.add(String.valueOf(item).trim());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** {@code collection.yml} に載っている印のエントリID。 */
    private static List<String> collectionSealIds() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(COLLECTION));
        List<String> out = new ArrayList<>();
        for (Object value : yaml.getValues(true).values()) {
            if (!(value instanceof List<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                String token = String.valueOf(row).trim();
                if (token.startsWith("dungeon_seal_")) {
                    out.add(token);
                }
            }
        }
        return out;
    }

    /** 任意の yml 値ツリーから {@code list:<id>} トークンの id を集める。 */
    private static void collectListTokens(Iterable<?> values, Set<String> out) {
        for (Object value : values) {
            if (value instanceof String token) {
                String trimmed = token.trim();
                if (trimmed.regionMatches(true, 0, "list:", 0, "list:".length())) {
                    out.add(trimmed.substring("list:".length()).trim());
                }
            } else if (value instanceof Iterable<?> nested) {
                collectListTokens(nested, out);
            } else if (value instanceof Map<?, ?> map) {
                collectListTokens(map.values(), out);
            }
        }
    }
}
