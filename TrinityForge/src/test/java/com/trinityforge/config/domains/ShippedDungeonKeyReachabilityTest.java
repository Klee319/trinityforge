package com.trinityforge.config.domains;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
 * {@code gacha.yml} の各プール /
 * <b>ArsPaper フォークの構造物戦利品({@code fork-handoff/arspaper/fork/src/main/resources/loot-tables.yml})</b>。
 *
 * <p><b>構造物戦利品を数える理由と、その扱い</b>(2026-08-16 再仕様化):
 * 探索系の鍵({@code key_bridge} / {@code key_city} / {@code key_steamworks} / {@code key_climb} /
 * {@code key_palace} / {@code key_sewer_maze} / {@code key_knight_castle} / {@code key_dark_cathedral} など)は
 * レシピもギミックドロップも持たず、フォーク側の構造物チェスト抽選からだけ出る。
 * ここを数えないとこの種の鍵が永久に「入手経路ゼロ」判定になり、テストが恒常的に赤くなる。
 * ただしフォークのソースは {@code .gitignore} 除外なので<b>クリーンなクローンには存在しない</b>。
 * そこで
 * <ul>
 *   <li>ファイルがある → 構造物戦利品も経路として数える(＝経路が消えたら落ちる)</li>
 *   <li>ファイルが無い → TF 側の経路だけでは説明できない鍵が残った時点で
 *       {@link org.junit.jupiter.api.Assumptions#abort} して<b>スキップ</b>にする
 *       (フォーク不在を回帰と誤認しない)</li>
 * </ul>
 * とする。
 *
 * <p><b>EM ボスの確定ドロップ({@code combat/mob-overrides.yml})は意図的に数えない</b>
 * (2026-08-14 追記)。以前ここには「EM ボスは印({@code dungeon_seal_*})専用で鍵は配っていない」と
 * 書いてあったが、ダンジョン難易度再設計で {@code key_enchant_trial_2}〜{@code key_enchant_trial_10} を
 * 直前のエンチャント試練の踏破ボスへ確定ドロップとして載せたので、その前提はもう成り立たない。
 * それでも数えないのは、この経路が<b>条件付き</b>だからである
 * (前のダンジョンを踏破していないと出ない＝鍵を1つ落とすと連鎖が丸ごと止まる)。
 * ここで数えてしまうと「レシピを消しても踏破ドロップがあるから緑」になり、
 * <b>連鎖の途中で詰んでいる状態を検出できなくなる</b>。よって鍵は踏破ドロップとは別に
 * {@code recipe:} 等の無条件な経路を必ず1つ持つこと、をこのテストで固定し続ける。
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
     * ArsPaper フォークの構造物戦利品表。テストの作業ディレクトリは {@code TrinityForge/} なので
     * 1つ上へ登る。<b>フォークのソースは {@code .gitignore} 除外</b>なので、この相対パスは
     * クリーンなクローン／新しい worktree では存在しない(＝存在しないことは回帰ではない)。
     */
    private static final String ARS_STRUCTURE_LOOT =
            "../fork-handoff/arspaper/fork/src/main/resources/loot-tables.yml";

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
    @DisplayName("すべての key_* が入手経路(レシピ / ドロップ表 / 構造物戦利品)を持つ")
    void everyDungeonKeyIsObtainable() {
        Set<String> keys = keyItemIds();
        assertFalse(keys.isEmpty(), "catalog.yml から key_* を1件も読めていない"
                + "(構造が変わったならこのテストも直すこと)");

        Set<String> craftable = keysWithRecipe();
        Set<String> dropped = droppedItemIds();

        List<String> unreachableInTf = new ArrayList<>();
        for (String key : keys) {
            if (!craftable.contains(key) && !dropped.contains(key)) {
                unreachableInTf.add(key);
            }
        }
        if (unreachableInTf.isEmpty()) {
            return;
        }

        // ここから先は「TF 側だけでは説明できない鍵」の話。構造物戦利品(フォーク側)を見に行く。
        File lootFile = new File(ARS_STRUCTURE_LOOT);
        Assumptions.assumeTrue(lootFile.isFile(),
                "ArsPaper フォークの " + ARS_STRUCTURE_LOOT + " が無いので、構造物戦利品からしか"
                        + "出ない鍵の入手経路を検証できない(フォークのソースは .gitignore 除外なので"
                        + "クリーンなクローンでは常にこの状態＝回帰ではない)。未検証の鍵: "
                        + unreachableInTf);

        Set<String> fromStructures = structureLootItemIds(lootFile);
        assertFalse(fromStructures.isEmpty(),
                "構造物戦利品 " + lootFile.getAbsolutePath() + " から custom: アイテムを1件も"
                        + "読めていない。抽出側が壊れているとこの検査が丸ごと無効化されるので、"
                        + "まず抽出できていることを確かめる");

        List<String> unreachable = unreachableInTf.stream()
                .filter(key -> !fromStructures.contains(key))
                .toList();
        assertTrue(unreachable.isEmpty(),
                "入手経路がゼロの鍵がある(レシピも無く、TF のどのドロップ表にも、"
                        + "ArsPaper の構造物戦利品(" + ARS_STRUCTURE_LOOT + ")にも載っていない)。"
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

    /**
     * 2026-08-25 に現仕様へ書き直した。
     *
     * <p>以前は「key_binder は {@code list:dungeon_seals} を5個要求する」を固定していたが、
     * 印を集める形はやめて固有素材5種の shaped レシピになった（ユーザー確定「意図した変更」）。
     * 旧テストは実装が意図どおり変わった側を落とし続けていたので、主張を差し替える。
     *
     * <p>ここで固定するのは「鍵が合成でしか手に入らない以上、レシピが存在し、
     * 素材が作業台に並ぶ数に収まっていること」だけ。素材の顔ぶれはバランス調整で動くので縛らない。
     */
    @Test
    @DisplayName("key_binder は作業台で作れる(印は要求しない)")
    void binderKeyIsCraftableWithoutDungeonSeals() {
        ConfigurationSection binder = catalogItems().getConfigurationSection("key_binder");
        assertNotNull(binder, "key_binder が定義されていない");
        ConfigurationSection recipe = binder.getConfigurationSection("recipe");
        assertNotNull(recipe, "key_binder にレシピが無い(合成でしか入手できない鍵なので必須)");

        ConfigurationSection ingredientMap = recipe.getConfigurationSection("ingredients");
        assertNotNull(ingredientMap,
                "key_binder のレシピに ingredients が無い(shaped なので記号→素材の対応表が要る)");
        Set<String> symbols = ingredientMap.getKeys(false);
        assertFalse(symbols.isEmpty(), "key_binder の素材が空");
        assertTrue(symbols.size() <= 9,
                "作業台に並べきれる素材種は9種まで(超えると登録が落ちる): " + symbols.size());

        List<String> sealIngredients = symbols.stream()
                .map(symbol -> String.valueOf(ingredientMap.get(symbol)))
                .filter(value -> value.contains("dungeon_seal"))
                .toList();
        assertTrue(sealIngredients.isEmpty(),
                "印を要求しない形へ変えたはずの key_binder が印を要求している: " + sealIngredients);
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

    /**
     * ArsPaper の構造物戦利品表で配られている <b>{@code custom:} アイテムのID</b>(接頭辞を外したもの)。
     *
     * <p>形は TF のドロップ表と同じ {@code entries: [{item: custom:<id>, chance: N}, ...]} なので、
     * プールの入れ子の深さに依存せず {@code entries} を再帰的に拾う。
     */
    private static Set<String> structureLootItemIds(File lootFile) {
        Set<String> out = new LinkedHashSet<>();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(lootFile);
        for (Map.Entry<String, Object> entry : yaml.getValues(true).entrySet()) {
            if (!entry.getKey().endsWith("entries") || !(entry.getValue() instanceof List<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> map)) {
                    continue;
                }
                Object item = map.get("item");
                if (item == null) {
                    continue;
                }
                String token = String.valueOf(item).trim();
                if (token.regionMatches(true, 0, "custom:", 0, "custom:".length())) {
                    out.add(token.substring("custom:".length()).trim());
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
