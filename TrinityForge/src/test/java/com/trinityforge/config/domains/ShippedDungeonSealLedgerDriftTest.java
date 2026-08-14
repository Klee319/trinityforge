package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「ダンジョンの印」の台帳が4ファイルに分散していて同期していない</b>のを機械的に落とす
 * drift 検出テスト (2026-08-14 追加)。
 *
 * <h2>4つの台帳と、既にある検査で埋まっていない穴</h2>
 * 印({@code dungeon_seal_*})の一覧は現在<b>4箇所</b>に重複して書かれている:
 * <ol>
 *   <li>{@code combat/mob-overrides.yml} の踏破ボスの {@code drops}
 *       ── <b>印の実体はここでしか生まれない</b>(＝ここに無い印は入手経路が存在しない)</li>
 *   <li>{@code items/material-lists.yml} の {@code lists.dungeon_seals.materials}
 *       ── {@code key_binder} のレシピが {@code list:dungeon_seals} で引く実素材リスト</li>
 *   <li>{@code progression/collection.yml} の図鑑カテゴリ {@code entries} ── 図鑑の枠</li>
 *   <li>{@code progression/achievements.yml} の {@code collection.scope: item} の
 *       {@code targets} ── アチーブメント「全踏破」の達成条件</li>
 * </ol>
 * 既存の検査は<b>限られた辺しか見ていない</b>:
 * <ul>
 *   <li>{@link ShippedDungeonKeyReachabilityTest#dungeonSealsListMatchesCollectionEntries}
 *       … 2 ↔ 3 の一致</li>
 *   <li>{@link ShippedAchievementTreeTest#collectionItemTargetsAreKnownIds}
 *       … 4 の各IDが実在するか(<b>存在しないIDの検出はこちらの担当。ここで重複させない</b>)</li>
 * </ul>
 *
 * <p><b>どれも「4 が台帳を全部覆っているか」を見ていない。</b>
 * 4 に載っている ID がすべて実在しても、<b>台帳の一部しか列挙していなければ</b>
 * 「全踏破」は一部踏破で解除される ── そして<b>条件が緩む方向の drift はプレイヤーからの
 * 報告にも現れない</b>ので、この穴でしか気づけない。
 *
 * <p><b>2026-08-14 時点で実際にそうなっていた</b>: 印を 19 種 → 28 種へ組み替えたとき
 * ({@code dungeon_seal_enchant_trial} 単一を {@code dungeon_seal_enchant_trial_1}〜{@code _10} へ分割)、
 * 1〜3 は 28 種へ更新されたが 4 は 19 件のまま残った。
 * このうち「廃止IDを1件要求している」側は {@link ShippedAchievementTreeTest} が拾うが、
 * <b>「新設された 10 種を1件も要求していない」側は誰も拾わない</b>。
 * 廃止IDを消すだけの手当てをすると {@link ShippedAchievementTreeTest} は緑に戻り、
 * <b>10 ダンジョンを踏破しなくても「全踏破」が解除される</b>状態が固定される。
 *
 * <h2>ドロップ表を台帳の起点にする理由</h2>
 * 1(mob-overrides のドロップ表)を基準に置いているのは、<b>実体が生まれる唯一の場所</b>だから。
 * 2〜4 だけを突き合わせると「3ファイルとも同じ幻の印を載せている」状態を全員で見逃せてしまう
 * (＝図鑑に枠があり、レシピが要求し、アチーブメントも要求するのに、<b>どのモブも落とさない</b>
 * ので永久に達成不能)。ドロップ表を起点にすればこの向きの drift も同時に落ちる。
 *
 * <h2>許可リストにしない</h2>
 * 期待する ID を<b>このファイルに書き写さない</b>。4つの出荷 yml をそれぞれ実読し、
 * 接頭辞 {@code dungeon_seal} で拾って突き合わせるだけにしてある。過去に「実在しない ID を
 * 『実在する』と許可リストへ書いて検査ごと無効化した」事故があるため、
 * 写しを持った時点でこのテストの意味が消える。印が増減してもこのファイルは修正不要。
 *
 * <p>ArsPaper 側の {@code materials.yml}(印の見た目とアイテム定義の本体)は
 * <b>意図的に見ていない</b>: fork は {@code .gitignore} 除外でクリーンなクローンには存在せず、
 * 参照するとチェックアウトの状態でテストの意味が変わってしまうため。
 */
class ShippedDungeonSealLedgerDriftTest {

    private static final String MOB_OVERRIDES = "src/main/resources/combat/mob-overrides.yml";
    private static final String MATERIAL_LISTS = "src/main/resources/items/material-lists.yml";
    private static final String COLLECTION = "src/main/resources/progression/collection.yml";
    private static final String ACHIEVEMENTS = "src/main/resources/progression/achievements.yml";

    /** 印の ID 接頭辞。ID を列挙せず接頭辞だけで拾うので、印が増減してもこのテストは追随不要。 */
    private static final String SEAL_PREFIX = "dungeon_seal";

    /** editor が custom アイテムに付ける接頭辞。{@code CollectionListener#addWatched} と同じ扱い。 */
    private static final String CUSTOM_PREFIX = "custom:";

    @BeforeEach
    void setUp() {
        // YamlConfiguration の読み取り自体はサーバ不要だが、兄弟の Shipped* 系と同じ土俵に揃えておく
        // (将来 Material 解決を足したときにここだけ落ちる、という形の罠を作らないため)。
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("複数の印を要求するアチーブメントは、モブが落とす印の集合と過不足なく一致する")
    void multiSealAchievementCoversTheWholeSealLedger() {
        Set<String> ledger = sealLedger();

        // 「印のアチーブメント」を id 直書きで選ばない(id を書き写した時点で許可リストになる)。
        // targets が全部 dungeon_seal* で、かつ2件以上 = 「複数の印を集める」を意図した節、と内容で選ぶ。
        // 1件だけのもの(goal_worldbinder = 束縛者の印)は「全踏破」ではないので対象外。
        List<ItemAchievement> sealAchievements = itemScopedAchievements().stream()
                .filter(a -> a.targets().size() > 1)
                .filter(a -> a.targets().stream().allMatch(t -> normalizeId(t).startsWith(SEAL_PREFIX)))
                .toList();
        assertFalse(sealAchievements.isEmpty(),
                "印を複数要求するアチーブメントが1件も無い(「全踏破」が消えたか、achievements.yml の"
                        + "構造が変わった)。0件のまま緑で通すとこの検査が丸ごと無効になるので落とす");

        for (ItemAchievement achievement : sealAchievements) {
            Set<String> targets = new TreeSet<>();
            achievement.targets().forEach(t -> targets.add(normalizeId(t)));

            // 両方向の集合比較。assertEquals(Set, Set) は片側にしか無い要素を両方とも差分に出す。
            assertEquals(ledger, targets,
                    achievement.id() + " が要求する印の集合が台帳と食い違っている。"
                            + "台帳(" + ledger.size() + "種) = combat/mob-overrides.yml のドロップ表"
                            + "(items/material-lists.yml と progression/collection.yml が一致していることも"
                            + "上で確認済み)。"
                            + "台帳にあってここに無い印 = そのダンジョンを踏破しなくても『全踏破』が解除される。"
                            + "ここにあって台帳に無い印 = 落とすモブが居ないので永久に達成不能。"
                            + " ── 印を増減させたら【4ファイルとも】直すこと。");

            // threshold は「列挙件数と同じ = 全部そろったら」だけを許す。
            // 一般の scope:item には広げない: thread_variety は 15 種のうち 5 種で達成する
            // 意図的な部分達成なので、全体へ広げると正しい設定を落としてしまう。
            long threshold = achievement.threshold();
            if (threshold >= 0) {
                assertEquals(achievement.targets().size(), (int) threshold,
                        achievement.id() + " の collection.threshold(" + threshold + ") が "
                                + "targets 件数(" + achievement.targets().size() + ")と違う。"
                                + "印を増やしたのに threshold を据え置くと『全踏破』が"
                                + "【一部踏破で解除される】方向に黙って緩む");
            }
            assertFalse(achievement.percent(),
                    achievement.id() + " の collection.percent が true になっている。"
                            + "印は百分率ではなく実数で数える前提(台帳との1対1照合が崩れる)");
        }
    }

    @Test
    @DisplayName("印は1体につき1種類・確率1.0・個数1固定(踏破の証明として成立する形)")
    void everySealDropsExactlyOncePerKill() {
        List<DropRow> sealDrops = allDropRows().stream()
                .filter(row -> normalizeId(row.item()).startsWith(SEAL_PREFIX))
                .toList();
        assertFalse(sealDrops.isEmpty(), "mob-overrides.yml から印のドロップを1件も読めていない");

        for (DropRow row : sealDrops) {
            String id = normalizeId(row.item());
            assertEquals(1.0d, row.chance(), 1.0e-9,
                    id + " の chance が 1.0 ではない。印は『踏破したら必ず1個』が前提で、"
                            + "確率にすると『全踏破』が運任せになる");
            assertEquals(1, row.min(), id + " の min が 1 ではない");
            assertEquals(1, row.max(), id + " の max が 1 ではない。"
                    + "複数落ちると図鑑の重複と key_binder の『印5個』要求が壊れる");
        }
    }

    // === 読み取りヘルパ(すべて出荷 yml を実読する。期待値の写しを持たない) ===

    /**
     * 印の台帳。<b>起点は {@code mob-overrides.yml} のドロップ表</b>(実体が生まれる唯一の場所)。
     * {@code material-lists.yml} と {@code collection.yml} が<b>それと一致していることが前提</b>
     * なので、食い違っていたらここで落とす(2 ↔ 3 の一致は
     * {@link ShippedDungeonKeyReachabilityTest#dungeonSealsListMatchesCollectionEntries} も見ているが、
     * 食い違ったままアチーブメント側の照合へ進むと「どれが正か」が決まらず
     * <b>意味のない差分</b>が出るだけなので、ここでも前段として確認する)。
     */
    private static Set<String> sealLedger() {
        Set<String> fromDrops = new TreeSet<>();
        for (DropRow row : allDropRows()) {
            String id = normalizeId(row.item());
            if (id.startsWith(SEAL_PREFIX)) {
                fromDrops.add(id);
            }
        }
        Set<String> fromLists = sealIdsIn(MATERIAL_LISTS, "materials");
        Set<String> fromCollection = sealIdsIn(COLLECTION, "entries");
        assertFalse(fromDrops.isEmpty(), "mob-overrides.yml から印のドロップを1件も読めていない");
        assertFalse(fromLists.isEmpty(), "material-lists.yml から印を1件も読めていない");
        assertFalse(fromCollection.isEmpty(), "collection.yml から印を1件も読めていない");

        assertEquals(fromDrops, fromLists,
                "mob-overrides.yml が落とす印と material-lists.yml の lists.dungeon_seals が食い違っている。"
                        + "落とすのにリストに無い印 = key_binder の素材候補から漏れている。"
                        + "リストにあって落ちない印 = key_binder が永久にクラフト不可になりうる");
        assertEquals(fromDrops, fromCollection,
                "mob-overrides.yml が落とす印と collection.yml の図鑑カテゴリが食い違っている。"
                        + "落とすのに枠が無い印 = 図鑑に載らない。"
                        + "枠があって落ちない印 = 図鑑 100% が永久に達成不能");
        return fromDrops;
    }

    /** 指定 yml の {@code <keySuffix>} で終わるキーのリストから、印の ID を接頭辞で拾う。 */
    private static Set<String> sealIdsIn(String path, String keySuffix) {
        Set<String> out = new TreeSet<>();
        for (String token : listEntriesUnder(path, keySuffix)) {
            String id = normalizeId(token);
            if (id.startsWith(SEAL_PREFIX)) {
                out.add(id);
            }
        }
        return out;
    }

    /** {@code custom:} 接頭辞と {@code " x3"} のような個数指定を落とした素の ID。 */
    private static String normalizeId(String raw) {
        String token = raw == null ? "" : raw.trim();
        if (token.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
            token = token.substring(CUSTOM_PREFIX.length()).trim();
        }
        int space = token.indexOf(' ');
        return space < 0 ? token : token.substring(0, space).trim();
    }

    /**
     * {@code scope: item} のアチーブメント。<b>targets の組み立ては
     * {@code AchievementsConfig#parseCollectionTargets} と同じ規則</b>にしてある
     * (複数キーを正とし、単数キーがそこに無ければ先頭へ足す)。ここがずれると
     * 「本番では条件に入っているのにテストは見ていない ID」が生まれる。
     *
     * <p>{@code AchievementsConfig#load} を通さず生の yml を読んでいるのは、
     * ローダは<b>不正な節を warning 付きで丸ごと捨てる</b>ため。捨てられた節はこの検査からも
     * 消えてしまい、「壊れているほど緑に近づく」という逆向きのテストになる。
     */
    private static List<ItemAchievement> itemScopedAchievements() {
        ConfigurationSection root = loadShipped(ACHIEVEMENTS).getConfigurationSection("achievements");
        assertNotNull(root, "achievements.yml の achievements セクションが読めない");

        List<ItemAchievement> out = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection trigger = root.getConfigurationSection(id + ".trigger");
            if (trigger == null || !"static".equalsIgnoreCase(trigger.getString("type", ""))) {
                continue;
            }
            String scope = trigger.getString("collection.scope", "all").trim().toLowerCase(Locale.ROOT);
            if (!scope.equals("item")) {
                continue;
            }
            List<String> targets = new ArrayList<>();
            for (String raw : trigger.getStringList("collection.targets")) {
                String token = String.valueOf(raw).trim();
                if (!token.isEmpty() && !targets.contains(token)) {
                    targets.add(token);
                }
            }
            String single = trigger.getString("collection.target", "").trim();
            if (!single.isEmpty() && !targets.contains(single)) {
                targets.add(0, single);
            }
            out.add(new ItemAchievement(id, List.copyOf(targets),
                    trigger.getLong("collection.threshold", -1L),
                    trigger.getBoolean("collection.percent", false)));
        }
        assertFalse(out.isEmpty(),
                "achievements.yml から scope: item のアチーブメントを1件も読めていない"
                        + "(構造が変わったならこのテストも直すこと)");
        return out;
    }

    /** @param threshold 明示指定されていなければ {@code -1}(既定は「targets 件数」なので照合不要)。 */
    private record ItemAchievement(String id, List<String> targets, long threshold, boolean percent) {
    }

    /** {@code mob-overrides.yml} のドロップ1行。 */
    private record DropRow(String owner, String item, double chance, int min, int max) {
    }

    /**
     * {@code mob-overrides.yml} の全ドロップ行。<b>階層(scopes → mobs → drops)を決め打ちしない</b>:
     * {@code Map} を要素に持つリストのうち {@code item} キーを持つ行、という<b>内容</b>で拾うので、
     * ダンジョンの入れ子構造が変わってもこのテストは追随不要。
     * 深さで決め打ちすると、構造変更時に「0件読めて全部緑」という最悪の壊れ方をする。
     */
    private static List<DropRow> allDropRows() {
        List<DropRow> out = new ArrayList<>();
        for (Map.Entry<String, Object> node : loadShipped(MOB_OVERRIDES).getValues(true).entrySet()) {
            if (!(node.getValue() instanceof List<?> rows)) {
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
                out.add(new DropRow(node.getKey(), String.valueOf(item),
                        toDouble(map.get("chance"), 1.0d),
                        (int) toDouble(map.get("min"), 1.0d),
                        (int) toDouble(map.get("max"), 1.0d)));
            }
        }
        assertFalse(out.isEmpty(),
                MOB_OVERRIDES + " から item を持つドロップ行を1件も読めていない"
                        + "(構造が変わっている。空のまま続けると検査が丸ごと無効化される)");
        return out;
    }

    private static double toDouble(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * {@code <keySuffix>} で終わるキーに紐づくリストの要素を、ネストの深さに依存せず集める
     * ({@code ShippedDungeonKeyReachabilityTest#droppedItemIds} と同じ流儀)。
     * 深さで決め打ちしないので、カテゴリ階層が変わってもこのテストは追随不要。
     */
    private static List<String> listEntriesUnder(String path, String keySuffix) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Object> node : loadShipped(path).getValues(true).entrySet()) {
            if (!node.getKey().endsWith(keySuffix) || !(node.getValue() instanceof List<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                if (row != null) {
                    out.add(String.valueOf(row));
                }
            }
        }
        assertFalse(out.isEmpty(),
                path + " から '" + keySuffix + "' のリストを1件も読めていない"
                        + "(キー名か構造が変わっている。空のまま続けると検査が丸ごと無効化される)");
        return out;
    }

    private static YamlConfiguration loadShipped(String path) {
        File file = new File(path);
        assertTrue(file.isFile(), "出荷リソースが見つからない: " + file.getAbsolutePath());
        return YamlConfiguration.loadConfiguration(file);
    }
}
