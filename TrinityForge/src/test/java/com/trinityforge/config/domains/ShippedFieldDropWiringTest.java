package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * 出荷 {@code combat/mob-level-table.yml} の <b>フィールドドロップ配線</b>(2026-08-14)を、
 * 「無言で効かなくなる」4つの壊れ方から守る。
 *
 * <h2>守る対象(いずれも例外もログも出さずに no-op になる)</h2>
 * <ol>
 *   <li><b>帯の書き漏らし</b> —— 帯(tiers)の解決は floor lookup で<b>1つしか選ばれず</b>、上の帯から
 *       下の帯へ継承されない({@code MobLevelBandTable#resolve})。{@code min-level: 0} の帯にだけ
 *       書くと Lv0〜9 のモブでしか抽選されない。ここでは「各素材は<b>全帯</b>か<b>ちょうど1帯</b>か
 *       のどちらか」+「全帯に載る素材は6帯で内容が同一」を固定する。1帯だけに置くのは
 *       ガチャ券【II〜IV】(券の番号を Lv45/65/85 の帯に対応させたもの)だけで、
 *       「5/6帯にしか無い」ような中間状態は複製し忘れ/消し忘れとして落とす。</li>
 *   <li><b>帯の mobs: から漏れる</b> —— 帯そのものにも {@code mobs:} があり、そこに載っていない
 *       EntityType のキルは帯ごと適用対象外になる({@code MobLevelTableListener} の
 *       {@code rule.appliesTo})。add-drops 側だけに書いた EntityType は永久に発火しない。</li>
 *   <li><b>ユーザー指示との食い違い</b> —— 討伐素材とスレッドは {@code where: field}(フィールド限定)、
 *       ガチャ券【I】は {@code where: dungeon}(ダンジョン限定)が指示。where を書き忘れると
 *       既定の {@code any} になり、討伐素材がダンジョンモブからも出る。</li>
 *   <li><b>実在しないカタログID</b> —— {@code custom:} の解決は討伐時にしか行われず、失敗しても
 *       WARNING 1行で抽選が捨てられるだけ。プレイヤーからは「落ちない」としか見えない。</li>
 * </ol>
 *
 * <h2>許可リストにしない工夫</h2>
 * 「検査すべき素材」の一覧を手書きせず、<b>{@code progression/collection.yml} の図鑑カテゴリ
 * 「討伐素材」({@code categories.items.mob_parts})から導出</b>する —— これは
 * 「TF 側が入手経路を用意すると宣言した素材」そのもの。素材を足して配線し忘れれば落ちる。
 * カタログIDの実在確認も、出荷設定3本({@code items/catalog.yml} / {@code collection.yml} /
 * {@code gacha.yml})から作った集合と突き合わせる(ArsPaper の {@code materials.yml} は
 * {@code .gitignore} 除外でクローンにも CI にも存在しないため、そこは見に行かない)。
 */
class ShippedFieldDropWiringTest {

    private static final String MOB_LEVEL_TABLE = "src/main/resources/combat/mob-level-table.yml";
    private static final String COLLECTION = "src/main/resources/progression/collection.yml";
    private static final String CATALOG = "src/main/resources/items/catalog.yml";
    private static final String GACHA = "src/main/resources/gacha.yml";

    private static final String CUSTOM_PREFIX = "custom:";

    /** 討伐素材カテゴリの下限件数(カテゴリごと消えてテストが空回りするのを防ぐ)。 */
    private static final int MIN_EXPECTED_MOB_PARTS = 17;

    /** スレッドの下限件数(同上)。 */
    private static final int MIN_EXPECTED_THREADS = 5;

    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("add-drops の各素材は『全帯』か『ちょうど1帯』のどちらか — 5/6帯だけに残る中途半端な状態は削除事故")
    void everyMaterialIsEitherInAllBandsOrInExactlyOneBand() throws Exception {
        List<Map<?, ?>> tiers = tiers();
        assertTrue(tiers.size() >= 2,
                "帯が " + tiers.size() + " 個しかない。このテストは帯をまたいだ出現数を見るので"
                        + "帯が1つだと素通りする");

        Map<String, Set<String>> bandsByMaterial = bandsByMaterial();
        assertTrue(!bandsByMaterial.isEmpty(), "add-drops が全帯で空。配線ごと消えている");

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : bandsByMaterial.entrySet()) {
            int count = entry.getValue().size();
            if (count != 1 && count != tiers.size()) {
                problems.add(entry.getKey() + ": " + count + "/" + tiers.size() + " 帯 " + entry.getValue());
            }
        }
        assertTrue(problems.isEmpty(),
                "add-drops に『一部の帯にだけ残っている』素材がある。帯の解決は floor lookup で"
                        + "【1つの帯しか選ばれず】上の帯から下の帯へ継承されないので、書かれていない帯の"
                        + "レベルのモブからは1個も落ちない(例外もログも出ない)。"
                        + "許される形は2つだけ: (a) 帯を問わない供給源なので【全帯】に置く"
                        + "(討伐素材・スレッド・素の券・ガチャ券【I】)、"
                        + "(b) 券の番号を帯に対応させているので【ちょうど1帯】に置く"
                        + "(ガチャ券【II〜IV】= Lv45/65/85)。"
                        + "その中間は『複製し忘れ』か『消し忘れ』のどちらかしかない: " + problems);
    }

    @Test
    @DisplayName("全帯に載る add-drops は6帯すべてで内容が同一 — 1帯でも中身が違うとその帯のレベルでは差分が落ちない")
    void everyLevelBandCarriesTheSameAddDrops() throws Exception {
        List<Map<?, ?>> tiers = tiers();
        assertTrue(tiers.size() >= 2,
                "帯が " + tiers.size() + " 個しかない。このテストは『複数の帯が同じ add-drops を持つ』"
                        + "ことを見るので、帯が1つだと素通りする");

        Set<String> allBandMaterials = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : bandsByMaterial().entrySet()) {
            if (entry.getValue().size() == tiers.size()) {
                allBandMaterials.add(entry.getKey());
            }
        }
        assertTrue(!allBandMaterials.isEmpty(),
                "全帯に載っている素材が1件も無い。討伐素材・スレッド・素の券の配線ごと消えている");

        Map<String, String> perBand = new TreeMap<>();
        for (Map<?, ?> tier : tiers) {
            perBand.put(bandKey(tier), canonicalAddDrops(tier, allBandMaterials));
        }
        Set<String> distinct = new TreeSet<>(perBand.values());
        assertEquals(1, distinct.size(),
                "帯によって add-drops の内容が違う。帯の解決は floor lookup で【1つの帯しか選ばれず】、"
                        + "上の帯から下の帯へ継承されない —— つまり内容の薄い帯のレベルのモブからは"
                        + "その差分が1個も落ちない(例外もログも出ない)。"
                        + "add-drops を編集したら必ず全帯へ同じ内容を反映すること。"
                        + "(chance-by-level を持たないエントリの chance だけは、帯ごとの階段として"
                        + "意図的に変えてよいので比較から外してある —— そちらは"
                        + "perBandStepChancesNeverDecreaseWithLevel が別に見る。"
                        + "また、意図して1帯だけに置くエントリ(ガチャ券【II〜IV】)は"
                        + "everyMaterialIsEitherInAllBandsOrInExactlyOneBand の担当なので"
                        + "この比較からは外してある。)"
                        + "帯ごとの内容: " + perBand);
        assertTrue(!distinct.iterator().next().isEmpty(),
                "全帯の add-drops が空。討伐素材・スレッド・ガチャ券の配線ごと消えている");
    }

    @Test
    @DisplayName("帯ごとに chance を変えているエントリは、帯が上がるほど確率が下がらない — 逆転すると『強い敵ほど出ない』になる")
    void perBandStepChancesNeverDecreaseWithLevel() throws Exception {
        // material -> (帯の min-level -> chance)。chance-by-level を持つエントリは
        // レベル補間側で右上がりが保証されている(everyChanceCurveIncreasesWithLevel)ので対象外。
        Map<String, TreeMap<Double, Double>> steps = new TreeMap<>();
        for (Map<?, ?> tier : tiers()) {
            double minLevel = doubleOf(tier.get("min-level"));
            for (Map<?, ?> drop : maps(tier.get("add-drops"))) {
                if (drop.get("chance-by-level") != null) {
                    continue;
                }
                String material = text(drop.get("material"));
                if (material == null) {
                    continue;
                }
                steps.computeIfAbsent(material, key -> new TreeMap<>())
                        .put(minLevel, doubleOf(drop.get("chance")));
            }
        }
        assertTrue(!steps.isEmpty(),
                "chance-by-level を持たない add-drops エントリが1件も無い。"
                        + "この検査は『帯ごとの階段』を見るものなので、0件だと素通りする");

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Double, Double>> entry : steps.entrySet()) {
            double previous = Double.NEGATIVE_INFINITY;
            for (Map.Entry<Double, Double> band : entry.getValue().entrySet()) {
                if (band.getValue() < previous) {
                    problems.add(entry.getKey() + ": min-level " + band.getKey()
                            + " の chance が " + band.getValue() + " で、下の帯(" + previous + ")より低い");
                }
                previous = band.getValue();
            }
        }
        assertTrue(problems.isEmpty(),
                "帯ごとに chance を変えているエントリで、帯が上がるほど確率が下がっている。"
                        + "『強い(=レベルの高い)モブを狩るほど出る』という報酬設計と逆になっている: "
                        + problems);
    }

    @Test
    @DisplayName("add-drops の対象モブは、その帯自身の mobs: にも載っている — 載っていない EntityType は帯ごと弾かれて永久に発火しない")
    void everyAddDropTargetIsAlsoListedOnItsBand() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map<?, ?> tier : tiers()) {
            Set<String> bandMobs = new TreeSet<>(strings(tier.get("mobs")));
            if (bandMobs.isEmpty()) {
                // 帯に mobs: が無ければ全モブが対象なので、この検査は不要。
                continue;
            }
            for (Map<?, ?> drop : maps(tier.get("add-drops"))) {
                for (String mob : strings(drop.get("mobs"))) {
                    if (!bandMobs.contains(mob)) {
                        problems.add(bandKey(tier) + " の " + text(drop.get("material")) + " が " + mob
                                + " を狙っているが、帯の mobs: に " + mob + " が無い");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(),
                "add-drops の mobs: に、その帯の mobs: へ載っていない EntityType がある。"
                        + "帯そのものの mobs: に無いモブのキルは【帯ごと適用対象外】になるので"
                        + "(MobLevelTableListener の rule.appliesTo)、そのドロップは永久に発火しない。"
                        + "帯の mobs: にも足すこと: " + problems);
    }

    @Test
    @DisplayName("図鑑の討伐素材は全件が where: field のフィールドドロップを持つ — ダンジョン限定にすると指示と逆になる")
    void everyMobPartIsWiredAsAFieldDrop() throws Exception {
        List<String> mobParts = mobPartCollectionEntries();
        Map<String, Set<String>> scopesById = dropScopesById();

        List<String> problems = new ArrayList<>();
        for (String id : mobParts) {
            Set<String> scopes = scopesById.get(normalize(id));
            if (scopes == null) {
                problems.add(id + ": mob-level-table.yml の add-drops に1件も無い");
            } else if (!scopes.equals(Set.of("field"))) {
                problems.add(id + ": where が " + scopes + "(期待: [field])");
            }
        }
        assertTrue(problems.isEmpty(),
                "討伐素材の配線がユーザー指示「フィールドの該当モブの固有ドロップへ。ダンジョンモブには"
                        + "適用しない」と食い違っている。where を省略すると既定の any になり、"
                        + "ダンジョン内の見た目替えモブ(EliteMobs 個体にも戦闘レベルは刻まれる)からも"
                        + "落ちてしまう点に注意: " + problems);
    }

    @Test
    @DisplayName("スレッド5種は where: field / 1個固定 — min<max にするとドロップ増加ステが確率側に乗らなくなる")
    void threadsAreFieldOnlyAndSingleFixed() throws Exception {
        Map<String, Map<?, ?>> threads = new TreeMap<>();
        for (Map<?, ?> drop : allAddDrops()) {
            String id = customId(text(drop.get("material")));
            if (id != null && id.startsWith("thread_")) {
                threads.put(id, drop);
            }
        }
        assertTrue(threads.size() >= MIN_EXPECTED_THREADS,
                "スレッドのフィールドドロップが " + threads.size() + " 種しかない(期待: "
                        + MIN_EXPECTED_THREADS + " 種以上)。配線ごと消えるとこのテストが素通りするので"
                        + "下限で縛っている: " + threads.keySet());

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<?, ?>> entry : threads.entrySet()) {
            Map<?, ?> drop = entry.getValue();
            if (!"field".equals(text(drop.get("where")))) {
                problems.add(entry.getKey() + ": where=" + text(drop.get("where")) + "(期待: field)");
            }
            if (!"1.0".equals(number(drop.get("min"))) || !"1.0".equals(number(drop.get("max")))) {
                problems.add(entry.getKey() + ": min/max=" + number(drop.get("min")) + "/"
                        + number(drop.get("max")) + "(期待: 1/1)");
            }
        }
        assertTrue(problems.isEmpty(),
                "スレッドはレアドロップ枠なので、min:1 max:1(個数1個固定)であることが仕様の一部。"
                        + "【1個固定のエントリだけドロップ増加ステが抽選"
                        + "\"確率\"側に乗る】(MobDropRoller#isSingleFixed → boostedChance)ので、"
                        + "min<max にすると同じステが\"個数への加算\"に化け、レア遭遇率を上げる狙いが"
                        + "無言で消える: " + problems);
    }

    @Test
    @DisplayName("ガチャ券【I】はダンジョン限定 — where: field に変わるとフィールド周回で無限に稼げる")
    void gachaTicketOneIsDungeonOnly() throws Exception {
        List<Map<?, ?>> tickets = new ArrayList<>();
        for (Map<?, ?> drop : allAddDrops()) {
            if ("gacha_ticket_1".equals(customId(text(drop.get("material"))))) {
                tickets.add(drop);
            }
        }
        assertTrue(!tickets.isEmpty(),
                "gacha_ticket_1 のドロップが mob-level-table.yml の add-drops に1件も無い。"
                        + "ユーザー指示「ガチャ券Ⅰはダンジョン内のモブに限定。レベルが高いほど"
                        + "ドロップしやすい」の唯一の実装箇所");

        for (Map<?, ?> drop : tickets) {
            assertEquals("dungeon", text(drop.get("where")),
                    "gacha_ticket_1 の where がダンジョン限定でない。field/any にするとフィールドの"
                            + "雑魚周回で無限にガチャ券が出る");
            assertEquals("1.0", number(drop.get("min")), "gacha_ticket_1 は1個固定(レアドロップ枠)");
            assertEquals("1.0", number(drop.get("max")), "gacha_ticket_1 は1個固定(レアドロップ枠)");
            assertNotNull(drop.get("chance-by-level"),
                    "gacha_ticket_1 に chance-by-level が無い。「レベルが高いほどドロップしやすい」"
                            + "というユーザー指示が実装されていない");
        }
    }

    @Test
    @DisplayName("add-drops の chance-by-level は必ず右上がり — 上端 <= 下端だと「レベルが高いほど落ちやすい」が逆転/横ばいになる")
    void everyChanceCurveIncreasesWithLevel() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map<?, ?> drop : allAddDrops()) {
            Object raw = drop.get("chance-by-level");
            if (!(raw instanceof Map<?, ?> curve)) {
                continue;
            }
            double fromChance = doubleOf(curve.get("from-chance"));
            double toChance = doubleOf(curve.get("to-chance"));
            double fromLevel = doubleOf(curve.get("from-level"));
            double toLevel = doubleOf(curve.get("to-level"));
            String label = text(drop.get("material"));
            if (!(toLevel > fromLevel)) {
                problems.add(label + ": to-level(" + toLevel + ") <= from-level(" + fromLevel + ")");
            }
            if (!(toChance > fromChance)) {
                problems.add(label + ": to-chance(" + toChance + ") <= from-chance(" + fromChance + ")");
            }
        }
        assertTrue(problems.isEmpty(),
                "chance-by-level が右上がりになっていないエントリがある。to-level <= from-level は"
                        + "Java 側(MobLevelTableConfig#parseChanceCurve)がカーブごと捨てて素の chance に"
                        + "戻すので、【設定したのに何も変わらない】という形で壊れる: " + problems);
    }

    @Test
    @DisplayName("add-drops の custom: 参照は全件が出荷設定に実在する — 実在しないIDは討伐時に警告1行で捨てられるだけ")
    void everyCustomReferenceExistsInShippedConfig() throws Exception {
        Set<String> known = knownCustomIds();
        assertTrue(known.size() >= 100,
                "出荷設定から集めた既知IDが " + known.size() + " 件しかない。集計側が壊れていると"
                        + "この検査が丸ごと無効化されるので下限で縛っている");

        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Map<?, ?> drop : allAddDrops()) {
            String id = customId(text(drop.get("material")));
            if (id == null) {
                continue;
            }
            checked++;
            if (!known.contains(id)) {
                missing.add(id);
            }
        }
        assertTrue(checked > 0, "add-drops に custom: 参照が1件も無い(配線ごと消えている)");
        assertTrue(missing.isEmpty(),
                "mob-level-table.yml の add-drops が、出荷設定のどこにも定義が無い custom: IDを"
                        + "参照している。解決は討伐時にしか行われず、失敗しても WARNING 1行で"
                        + "その抽選が捨てられるだけなので、プレイヤーからは『落ちない』としか見えない。"
                        + "IDのタイプミス(例: `hoglin_tusk` は 2026-08-16 に全参照を削除した"
                        + "実在しない旧ID。正しくは `hoglin_fang`)を疑うこと: " + missing);
    }

    // ------------------------------------------------------------------------------------------
    // 出荷 yml の読み取り
    // ------------------------------------------------------------------------------------------

    private static YamlConfiguration load(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        assertTrue(Files.isRegularFile(path), "出荷 yml が見つからない: " + path.toAbsolutePath());
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(path));
        return cfg;
    }

    private static List<Map<?, ?>> tiers() throws Exception {
        List<Map<?, ?>> tiers = load(MOB_LEVEL_TABLE).getMapList("tiers");
        assertTrue(!tiers.isEmpty(), MOB_LEVEL_TABLE + " に tiers: が無い");
        return tiers;
    }

    private static List<Map<?, ?>> allAddDrops() throws Exception {
        List<Map<?, ?>> out = new ArrayList<>();
        for (Map<?, ?> tier : tiers()) {
            out.addAll(maps(tier.get("add-drops")));
        }
        return out;
    }

    /** 素材ID -> その素材に付いている {@code where:} の集合(未指定は {@code any})。 */
    private static Map<String, Set<String>> dropScopesById() throws Exception {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Map<?, ?> drop : allAddDrops()) {
            String id = customId(text(drop.get("material")));
            if (id == null) {
                continue;
            }
            String where = drop.get("where") == null ? "any" : normalize(text(drop.get("where")));
            out.computeIfAbsent(id, key -> new TreeSet<>()).add(where);
        }
        return out;
    }

    /** 図鑑カテゴリ「討伐素材」のエントリ一覧(＝TF が入手経路を用意すると宣言した素材)。 */
    private static List<String> mobPartCollectionEntries() throws Exception {
        ConfigurationSection mobParts = load(COLLECTION)
                .getConfigurationSection("categories.items.mob_parts");
        assertNotNull(mobParts, COLLECTION + " の categories.items.mob_parts が無い");
        List<String> entries = mobParts.getStringList("entries");
        assertTrue(entries.size() >= MIN_EXPECTED_MOB_PARTS,
                "討伐素材カテゴリが " + entries.size() + " 件しかない(期待: " + MIN_EXPECTED_MOB_PARTS
                        + " 件以上)。カテゴリごと消えるとこのテストが素通りするので下限で縛っている");
        return entries;
    }

    /**
     * 出荷設定に定義/登録されている {@code custom:} IDの集合。手書きの許可リストではなく、
     * {@code items/catalog.yml} の items キー・{@code progression/collection.yml} の全エントリ・
     * {@code gacha.yml} の tickets キーから毎回導出する。
     *
     * <p>ArsPaper の {@code materials.yml} は {@code .gitignore} 除外でクローンにも CI にも
     * 存在しないため見に行かない。討伐素材17種はそこで定義されているが、図鑑
     * ({@code collection.yml})に全件載っているので、この集合で拾える。
     */
    private static Set<String> knownCustomIds() throws Exception {
        Set<String> ids = new TreeSet<>();

        ConfigurationSection items = load(CATALOG).getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        for (String id : items.getKeys(false)) {
            ids.add(normalize(id));
        }

        ConfigurationSection categories = load(COLLECTION).getConfigurationSection("categories");
        assertNotNull(categories, COLLECTION + " に categories: 節が無い");
        collectCollectionEntries(categories, ids);

        ConfigurationSection tickets = load(GACHA).getConfigurationSection("tickets");
        assertNotNull(tickets, GACHA + " に tickets: 節が無い");
        for (String id : tickets.getKeys(false)) {
            ids.add(normalize(id));
        }

        return ids;
    }

    /** {@code categories.<group>.<category>.entries[]} を階層を問わず全部拾う。 */
    private static void collectCollectionEntries(ConfigurationSection section, Set<String> out) {
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) {
                continue;
            }
            for (String entry : child.getStringList("entries")) {
                if (entry != null && !entry.isBlank()) {
                    out.add(normalize(entry));
                }
            }
            collectCollectionEntries(child, out);
        }
    }

    // ------------------------------------------------------------------------------------------
    // 小道具
    // ------------------------------------------------------------------------------------------

    private static String bandKey(Map<?, ?> tier) {
        return "min-level=" + text(tier.get("min-level"));
    }

    /** 素材(material の文字列そのまま) -> その素材が載っている帯キーの集合。 */
    private static Map<String, Set<String>> bandsByMaterial() throws Exception {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Map<?, ?> tier : tiers()) {
            String band = bandKey(tier);
            for (Map<?, ?> drop : maps(tier.get("add-drops"))) {
                String material = normalize(text(drop.get("material")));
                if (material != null) {
                    out.computeIfAbsent(material, key -> new TreeSet<>()).add(band);
                }
            }
        }
        return out;
    }

    /**
     * 帯の {@code add-drops} を、キー順・型ゆれ(1 と 1.0)に依存しない比較用の文字列へ潰す。
     * 「同じ内容か」だけを見たいので、順序はエントリの並び順を保つ(並び替えも差分として検出する)。
     *
     * <p>比較対象は {@code allBandMaterials}(=全帯に載っている素材)だけ。意図して1帯だけに置く
     * エントリ(ガチャ券【II〜IV】。券の番号を Lv45/65/85 の帯に対応させている)は、そもそも
     * 帯ごとに違うのが仕様なのでここでは見ない —— 「全帯かちょうど1帯か」は
     * {@code everyMaterialIsEitherInAllBandsOrInExactlyOneBand} が固定する。
     *
     * <p>{@code chance-by-level} を<b>持たない</b>エントリの {@code chance} だけは比較から外す。
     * レベル補間を使わず【帯ごとの階段】で確率を上げるエントリ(素の券 {@code gacha_ticket_0} が
     * この形。2026-07-31 の実績値をそのまま維持するため)が意図的に帯ごとに違う値を持つため。
     * 階段側の妥当性は {@code perBandStepChancesNeverDecreaseWithLevel} が別に検査する。
     * 【材料・where・min/max・mobs・baby・chance-by-level は全帯一致でなければならない】という
     * 本来の保護はそのまま効く。
     */
    private static String canonicalAddDrops(Map<?, ?> tier, Set<String> allBandMaterials) {
        StringBuilder sb = new StringBuilder();
        for (Map<?, ?> drop : maps(tier.get("add-drops"))) {
            if (!allBandMaterials.contains(normalize(text(drop.get("material"))))) {
                continue;
            }
            Map<Object, Object> comparable = new LinkedHashMap<>(drop);
            if (comparable.get("chance-by-level") == null) {
                comparable.remove("chance");
            }
            sb.append(canonicalMap(comparable)).append('\n');
        }
        return sb.toString();
    }

    private static String canonicalMap(Map<?, ?> map) {
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            String rendered;
            if (value instanceof Map<?, ?> nested) {
                rendered = canonicalMap(nested);
            } else if (value instanceof List<?> list) {
                List<String> parts = new ArrayList<>();
                for (Object element : list) {
                    parts.add(String.valueOf(element));
                }
                rendered = parts.toString();
            } else {
                rendered = number(value);
            }
            sorted.put(String.valueOf(entry.getKey()), rendered);
        }
        return new LinkedHashMap<>(sorted).toString();
    }

    private static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code "custom:warden_tendril"} -> {@code "warden_tendril"}。custom: でなければ {@code null}。 */
    private static String customId(String raw) {
        String normalized = normalize(raw);
        if (normalized == null || !normalized.startsWith(CUSTOM_PREFIX)) {
            return null;
        }
        return normalized.substring(CUSTOM_PREFIX.length());
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    /** 数値を型に依存せず比較できる形へ。整数/小数のどちらで書かれても同じ文字列になる。 */
    private static String number(Object raw) {
        return raw instanceof Number n ? String.valueOf(n.doubleValue()) : String.valueOf(raw);
    }

    private static double doubleOf(Object raw) {
        return raw instanceof Number n ? n.doubleValue() : Double.NaN;
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
