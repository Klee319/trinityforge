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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * スレッド（{@code thread_*}）の<b>入手経路</b>を、出荷 yml だけを読んで機械的に突き合わせる。
 *
 * <h2>なぜ必要か</h2>
 * 進捗 {@code thread_all}（{@code progression/achievements.yml}）が「45種すべてのスレッドを集める」を
 * 達成条件にしている。つまり<b>1種でも入手経路が無いと、その進捗と配下の進捗ツリーが
 * 全プレイヤーにとって永久に未達成になる</b>。しかも yml 側は何も言わないので、
 * 「集まらない」という体感が数か月積み上がるまで誰も気づけない。
 *
 * <p>2026-08-14 の配線作業で、{@code combat/mob-level-table.yml} のスレッドドロップが8種から5種へ
 * 置き換わり、同時に全種が特定 {@code EntityType} 指定になった（ユーザー指示）。EntityType 依存は
 * 「そのモブが湧かない/届かないプレイヤーは永久に集められない」という形で経路を細らせるため、
 * 経路の総覧は人力ではなく毎回計算する。
 *
 * <h2>数える経路（5系統）</h2>
 * <ol>
 *   <li>{@code combat/mob-level-table.yml} の {@code add-drops}（レベル帯ドロップ）</li>
 *   <li>{@code combat/mob-overrides.yml} の {@code drops}（EM 踏破ボスドロップ）</li>
 *   <li>{@code gacha.yml} の {@code pools.*.entries}（ガチャ景品）</li>
 *   <li>{@code items/catalog.yml} の {@code recipe:} / {@code recipes:}（クラフト・儀式）</li>
 *   <li>{@code progression/achievements.yml} の {@code rewards.items}（進捗報酬）</li>
 * </ol>
 * ArsPaper の {@code loot-tables.yml}（構造物チェスト）も実運用では経路になるが、フォークは
 * {@code .gitignore} 除外でクローンにも CI にも存在しないため、ここからは見に行かない。
 * <b>したがってここで「経路ゼロ」と出た種が、必ずしも実機で入手不能とは限らない。</b>
 *
 * <h2>許可リストにしない工夫</h2>
 * 「検査すべきスレッド」を手書きせず、{@code items/catalog.yml} の {@code items:} から
 * {@code thread_} で始まるキーを全抽出した集合と、上の5系統から計算した「経路を持つ集合」を
 * <b>両方向で</b>突き合わせる。さらに {@code thread_all} 実績の {@code targets} とも突き合わせる。
 * 抽出側が壊れて全部 0 件になると検査ごと無効化されるので、各系統に下限件数を置いている。
 */
class ShippedThreadAcquisitionRouteTest {

    private static final String CATALOG = "src/main/resources/items/catalog.yml";
    private static final String MOB_LEVEL_TABLE = "src/main/resources/combat/mob-level-table.yml";
    private static final String MOB_OVERRIDES = "src/main/resources/combat/mob-overrides.yml";
    private static final String GACHA = "src/main/resources/gacha.yml";
    private static final String ACHIEVEMENTS = "src/main/resources/progression/achievements.yml";

    private static final String THREAD_PREFIX = "thread_";
    private static final String CUSTOM_PREFIX = "custom:";

    /** カタログのスレッド定義の下限件数（items: 節ごと消えて検査が空回りするのを防ぐ）。 */
    private static final int MIN_EXPECTED_CATALOG_THREADS = 45;

    /** 図鑑実績が集めさせるスレッドの下限件数（実績側が消えて検査が空回りするのを防ぐ）。 */
    private static final int MIN_EXPECTED_COLLECTION_TARGETS = 45;

    /**
     * 「実績が集めさせるスレッドのうち、TF 側5系統に経路が1本も無い種」の<b>現時点の件数</b>。
     *
     * <p>2026-08-14 実測で 6 種（{@code thread_angler} / {@code thread_appraiser} /
     * {@code thread_artisan} / {@code thread_perfumer} / {@code thread_ritualist} /
     * {@code thread_scholar}）。うち {@code thread_angler} は ArsPaper の
     * {@code loot-tables.yml}（構造物チェスト）に経路があるが、フォークは {@code .gitignore} 除外で
     * CI からは見えないためここでは経路ゼロとして数える。
     *
     * <p><b>これは「検査を免除する id の一覧」ではなく件数の天井</b>。経路を1本でも減らすと
     * 件数が増えて落ちる。経路を足したらこの値を下げること（下げ忘れても落ちないが、
     * 天井が緩むだけなので気づいたら必ず詰める）。どの種が経路ゼロかは失敗メッセージが毎回列挙する。
     */
    private static final int MAX_UNROUTED_COLLECTION_TARGETS = 6;

    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("入手経路が指しているスレッドIDは全件がカタログに実在する — 綴り違いは discard されて無言で経路ゼロになる")
    void everyRouteReferencesAThreadDefinedInTheCatalog() throws Exception {
        Set<String> defined = catalogThreadIds();
        Map<String, Set<String>> routes = routesByThreadId();

        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : routes.entrySet()) {
            if (!defined.contains(entry.getKey())) {
                unknown.add(entry.getKey() + " (" + entry.getValue() + ")");
            }
        }
        assertTrue(unknown.isEmpty(),
                "items/catalog.yml に定義が無いスレッドIDを、ドロップ/ガチャ/レシピ/進捗報酬が"
                        + "参照している。custom: の解決は実行時にしか行われず、失敗しても WARNING 1行で"
                        + "その抽選が捨てられるだけなので、プレイヤーからは『落ちない』としか見えない。"
                        + "綴り違いを疑うこと: " + unknown);
    }

    @Test
    @DisplayName("thread_all 実績の targets は全件がカタログに実在する — 実在しないIDを条件にすると進捗が永久に未達成になる")
    void everyCollectionTargetIsDefinedInTheCatalog() throws Exception {
        Set<String> defined = catalogThreadIds();
        Set<String> targets = collectionTargets();

        List<String> missing = new ArrayList<>();
        for (String target : targets) {
            if (!defined.contains(target)) {
                missing.add(target);
            }
        }
        assertTrue(missing.isEmpty(),
                "進捗の collection.targets が、items/catalog.yml に存在しないスレッドIDを"
                        + "要求している。存在しない以上どうやっても図鑑に登録されず、その進捗は"
                        + "全プレイヤーにとって永久に未達成になる: " + missing);
    }

    @Test
    @DisplayName("5系統の経路抽出がどれも実際に経路を見つけている — 抽出が壊れると『全部経路あり』に化けて検査ごと無効化される")
    void everyRouteSourceActuallyYieldsRoutes() throws Exception {
        Map<String, Integer> counts = new TreeMap<>();
        counts.put("mob-level-table:add-drops", threadIdsInLevelTable().size());
        counts.put("mob-overrides:drops", threadIdsInMobOverrides().size());
        counts.put("gacha:pools", threadIdsInGachaPools().size());
        counts.put("catalog:recipe", threadIdsWithCatalogRecipe().size());

        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == 0) {
                dead.add(entry.getKey());
            }
        }
        assertTrue(dead.isEmpty(),
                "経路の抽出系統が0件を返している。yml の構造が変わって抽出側だけが黙って壊れると、"
                        + "『経路ゼロの種は無い』という誤った結論になり、この検査が丸ごと無効化される。"
                        + "抽出コードを yml の実構造に合わせ直すこと: " + dead + " / 全系統の件数: " + counts);

        // 進捗報酬(rewards.items)は現在スレッドを1件も配っていないので0件を許す。
        // ただし「抽出が動いていること」自体は、券などスレッド以外も含む総件数で確かめる。
        assertTrue(achievementRewardItemIds().size() >= 10,
                "progression/achievements.yml の rewards.items が "
                        + achievementRewardItemIds().size() + " 件しか読めていない。"
                        + "報酬の構造が変わって抽出が壊れている可能性がある");
    }

    @Test
    @DisplayName("実績が集めさせるスレッドのうち経路ゼロの種は現状より増えていない — 経路を1本消すと即座に落ちる")
    void unroutedCollectionTargetsDoNotGrow() throws Exception {
        Set<String> defined = catalogThreadIds();
        Set<String> targets = collectionTargets();
        Map<String, Set<String>> routes = routesByThreadId();

        Set<String> unrouted = new TreeSet<>();
        for (String target : targets) {
            if (!routes.containsKey(target)) {
                unrouted.add(target);
            }
        }

        // カタログにあるが実績の対象外のスレッド（＝集めなくてよい枠）は参考情報として出す。
        Set<String> notCollected = new TreeSet<>(defined);
        notCollected.removeAll(targets);

        assertTrue(unrouted.size() <= MAX_UNROUTED_COLLECTION_TARGETS,
                "入手経路が1本も無いスレッドが " + unrouted.size() + " 種ある(現時点の天井: "
                        + MAX_UNROUTED_COLLECTION_TARGETS + " 種)。進捗 thread_all は"
                        + "『すべてのスレッドを集める』が条件なので、1種でも経路が無いと"
                        + "その進捗と配下のツリーが全プレイヤーにとって永久に未達成になる。"
                        + "経路を消したなら戻すこと: " + unrouted
                        + " / 実績の対象外(集めなくてよい)スレッド: " + notCollected);
    }

    // ------------------------------------------------------------------------------------------
    // 経路の集計
    // ------------------------------------------------------------------------------------------

    /** スレッドID -> そのIDに経路を与えている系統名の集合。 */
    private static Map<String, Set<String>> routesByThreadId() throws Exception {
        Map<String, Set<String>> out = new TreeMap<>();
        add(out, threadIdsInLevelTable(), "mob-level-table:add-drops");
        add(out, threadIdsInMobOverrides(), "mob-overrides:drops");
        add(out, threadIdsInGachaPools(), "gacha:pools");
        add(out, threadIdsWithCatalogRecipe(), "catalog:recipe");
        Set<String> rewards = new TreeSet<>();
        for (String id : achievementRewardItemIds()) {
            if (id.startsWith(THREAD_PREFIX)) {
                rewards.add(id);
            }
        }
        add(out, rewards, "achievements:rewards");
        return out;
    }

    private static void add(Map<String, Set<String>> out, Set<String> ids, String source) {
        for (String id : ids) {
            out.computeIfAbsent(id, key -> new TreeSet<>()).add(source);
        }
    }

    private static Set<String> threadIdsInLevelTable() throws Exception {
        Set<String> ids = new TreeSet<>();
        for (Map<?, ?> tier : load(MOB_LEVEL_TABLE).getMapList("tiers")) {
            for (Map<?, ?> drop : maps(tier.get("add-drops"))) {
                addThread(ids, customId(text(drop.get("material"))));
            }
        }
        return ids;
    }

    /** {@code mob-overrides.yml} は入れ子が深いので、{@code drops} という名前の節を全部拾う。 */
    private static Set<String> threadIdsInMobOverrides() throws Exception {
        Set<String> ids = new TreeSet<>();
        YamlConfiguration cfg = load(MOB_OVERRIDES);
        for (String key : cfg.getKeys(true)) {
            if (!key.equals("drops") && !key.endsWith(".drops")) {
                continue;
            }
            for (Map<?, ?> drop : cfg.getMapList(key)) {
                addThread(ids, customId(text(drop.get("item"))));
            }
        }
        return ids;
    }

    private static Set<String> threadIdsInGachaPools() throws Exception {
        Set<String> ids = new TreeSet<>();
        YamlConfiguration cfg = load(GACHA);
        ConfigurationSection pools = cfg.getConfigurationSection("pools");
        assertNotNull(pools, GACHA + " に pools: 節が無い");
        for (String pool : pools.getKeys(false)) {
            for (Map<?, ?> entry : cfg.getMapList("pools." + pool + ".entries")) {
                String raw = normalize(text(entry.get("item")));
                addThread(ids, raw != null && raw.startsWith(CUSTOM_PREFIX)
                        ? raw.substring(CUSTOM_PREFIX.length()) : raw);
            }
        }
        return ids;
    }

    private static Set<String> threadIdsWithCatalogRecipe() throws Exception {
        Set<String> ids = new TreeSet<>();
        ConfigurationSection items = load(CATALOG).getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item == null) {
                continue;
            }
            if (item.isSet("recipe") || item.isSet("recipes")) {
                addThread(ids, normalize(id));
            }
        }
        return ids;
    }

    /** 進捗報酬 {@code rewards.items[].id} の全ID（{@code custom:} は剥がす）。 */
    private static Set<String> achievementRewardItemIds() throws Exception {
        Set<String> ids = new TreeSet<>();
        YamlConfiguration cfg = load(ACHIEVEMENTS);
        for (String key : cfg.getKeys(true)) {
            if (!key.endsWith("rewards.items")) {
                continue;
            }
            for (Map<?, ?> item : cfg.getMapList(key)) {
                String raw = normalize(text(item.get("id")));
                if (raw == null) {
                    continue;
                }
                ids.add(raw.startsWith(CUSTOM_PREFIX) ? raw.substring(CUSTOM_PREFIX.length()) : raw);
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------
    // 集合の導出
    // ------------------------------------------------------------------------------------------

    private static Set<String> catalogThreadIds() throws Exception {
        ConfigurationSection items = load(CATALOG).getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        Set<String> ids = new TreeSet<>();
        for (String id : items.getKeys(false)) {
            addThread(ids, normalize(id));
        }
        assertTrue(ids.size() >= MIN_EXPECTED_CATALOG_THREADS,
                "カタログのスレッド定義が " + ids.size() + " 件しか読めていない(期待: "
                        + MIN_EXPECTED_CATALOG_THREADS + " 件以上)。items: 節ごと消えると"
                        + "この検査が素通りするので下限で縛っている");
        return ids;
    }

    /**
     * 進捗の {@code trigger.collection.targets} に現れるスレッドIDの和集合
     * （＝TF が「集めろ」と要求しているスレッド）。実績IDを直接書かずに導出する。
     */
    private static Set<String> collectionTargets() throws Exception {
        Set<String> ids = new TreeSet<>();
        YamlConfiguration cfg = load(ACHIEVEMENTS);
        for (String key : cfg.getKeys(true)) {
            if (!key.endsWith("collection.targets")) {
                continue;
            }
            for (String target : cfg.getStringList(key)) {
                String raw = normalize(target);
                if (raw == null) {
                    continue;
                }
                addThread(ids, raw.startsWith(CUSTOM_PREFIX)
                        ? raw.substring(CUSTOM_PREFIX.length()) : raw);
            }
        }
        assertTrue(ids.size() >= MIN_EXPECTED_COLLECTION_TARGETS,
                "進捗が集めさせるスレッドが " + ids.size() + " 件しか読めていない(期待: "
                        + MIN_EXPECTED_COLLECTION_TARGETS + " 件以上)。thread_all の targets が"
                        + "消えるとこの検査が素通りするので下限で縛っている");
        return ids;
    }

    // ------------------------------------------------------------------------------------------
    // 小道具
    // ------------------------------------------------------------------------------------------

    private static void addThread(Set<String> out, String id) {
        if (id != null && id.startsWith(THREAD_PREFIX)) {
            out.add(id);
        }
    }

    private static YamlConfiguration load(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        assertTrue(Files.isRegularFile(path), "出荷 yml が見つからない: " + path.toAbsolutePath());
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(path));
        return cfg;
    }

    private static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

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
