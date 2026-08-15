package com.trinityforge.config.domains;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>出荷 {@code skilltree/*.yml} が配る「実数(FLAT)系」ステが、装備側の土俵から桁で外れていないことを固定する</b>
 * (2026-08-14 実サーバ報告「武器・装備関連のスキルツリーで付与されるステータスが現行のゲームバランスを
 * 考慮できていない。守備力が +0.1 のように実質意味のない値になっている」)。
 *
 * <h2>なぜ要るか — この壊れ方は一切エラーを出さない</h2>
 * <ul>
 *   <li><b>守備力は %軽減より前の素の引き算</b>({@code ComponentDamageCalculator} step2)なので
 *       「1ポイント＝被ダメージ1」。報告時点の値 {@code 0.05} はゾンビ級の一撃(Lv10 で 9.3)の 0.5%、
 *       装備1点(革の胸当て 8.2)の 0.6% で、<b>取っても何も起きないのに正常にロードされる</b>。
 *       yml として妥当なのでローダも警告を出さない。</li>
 *   <li><b>本当の症状は「値が小さい」ことではなく「帯に追随しないこと」だった。</b>
 *       α路線の6ノードは Lv10 から Lv90 まで 0.05 のまま1ミリも増えず、同じツリーの
 *       {@code magic-resistance}/{@code damage-reduction} が 2〜10% の割合系で正しく伸びる隣で
 *       実数系だけが取り残されていた。したがって「合計値」だけを固定しても再発を検出できない。</li>
 *   <li><b>比較対象を直書きすると意味が失われる。</b> 装備側の {@code item-stats.yml} は日々書き換わるので、
 *       基準値はここで<b>実データから読む</b>。装備側が伸びてツリーだけ据え置かれた場合にも落ちる。</li>
 * </ul>
 *
 * <h2>{@code attack-power} と {@code reflect-flat} を禁止しているのはなぜか</h2>
 * 装備の {@code attack-power} は帯とともに指数で伸びる(出荷値の中央値 828 / 最大 80,965)。
 * <b>定数を何倍にしても帯が上がった瞬間に無意味へ戻る</b>ので、実数のままでは原理的に直せない
 * (スレッドの実数ダメージを割合へ振り替えたのと同じ理由)。{@code reflect-flat} も同様で、
 * 反射先のモブHPは百万単位、かつ装備側は {@code reflect-flat} を1件も配っていない。
 * どちらも割合系({@code percent-bonus-damage} / {@code reflect-percent})へ振り替え済み。
 */
class ShippedSkillTreeFlatStatScaleTest {

    private static final String TREE_DIR = "src/main/resources/skilltree";
    private static final String ITEM_STATS = "src/main/resources/stats/item-stats.yml";

    /** 装備1点(最良の防具1部位)の何割を、ツリー全体の付与が持つべきかの下限。 */
    private static final double MIN_RATIO_TO_ONE_ARMOR_PIECE = 0.25;

    /** 帯追随の下限: 同一路線の最上位ノードは最下位ノードの何倍以上か。モブ攻撃力は Lv10→Lv90 で 3.2 倍。 */
    private static final double MIN_TOP_OVER_BOTTOM = 2.5;

    /**
     * 実数のまま置くと帯に追随できないため、スキルツリーの buffs では使わないと決めたキー。
     * 「使っていないキーの一覧」ではなく<b>禁止の宣言</b>なので、増やすときは理由を書くこと。
     */
    private static final List<String> BANNED_FLAT_KEYS = List.of("attack-power", "reflect-flat",
            // 2026-08-15(W-30): bleed-damage は 1tick あたりの実数。装備側は帯とともに指数で伸びる
            // (Lv0 34 → Lv80以降 4,500)ので、ツリーが定数で配ると低帯で壊れ高帯で no-op になる
            // ── 軽剣ツリー全取りの 150 は Lv0 で武器の +440%、Lv100 で +3.3% だった。
            // 割合キー bleed-damage-rate(出血させた一撃の最終ダメージに対する比)へ振り替え済み。
            "bleed-damage");

    @Test
    @DisplayName("守備力(実数)の付与は装備1点の土俵から桁で外れておらず、物理より魔法が大きい")
    void flatDefenseGrants_stayOnTheEquipmentScale() {
        Map<String, Double> treeTotals = new LinkedHashMap<>();
        treeTotals.put("phys-flat-defense", sumAcrossAllTrees("phys-flat-defense"));
        treeTotals.put("magic-flat-defense", sumAcrossAllTrees("magic-flat-defense"));

        double bestPhysPiece = bestSingleArmorPiece("phys-flat-defense");
        double bestMagicPiece = bestSingleArmorPiece("magic-flat-defense");

        // 空振り検知: 装備側/ツリー側のどちらかが 0 なら、比率の判定そのものが無意味になる。
        assertTrue(bestPhysPiece > 0 && bestMagicPiece > 0,
                "item-stats.yml から防具の守備力を1件も拾えていない。検査対象が消えているので"
                        + "この比較は空振りしている (phys=" + bestPhysPiece + " magic=" + bestMagicPiece + ")");
        assertTrue(treeTotals.get("phys-flat-defense") > 0 && treeTotals.get("magic-flat-defense") > 0,
                "skilltree/*.yml から守備力の付与を1件も拾えていない。検査対象が消えているので空振りしている");

        double physRatio = treeTotals.get("phys-flat-defense") / bestPhysPiece;
        double magicRatio = treeTotals.get("magic-flat-defense") / bestMagicPiece;

        assertTrue(physRatio >= MIN_RATIO_TO_ONE_ARMOR_PIECE,
                "スキルツリー全体の phys-flat-defense 合計 " + treeTotals.get("phys-flat-defense")
                        + " が、最良の防具1点 " + bestPhysPiece + " の " + Math.round(physRatio * 100)
                        + "% しかない。守備力は %軽減より前の素の引き算なので、この比率まで落ちると"
                        + "ノードを取っても被ダメージが体感で変わらない");
        assertTrue(magicRatio >= MIN_RATIO_TO_ONE_ARMOR_PIECE,
                "スキルツリー全体の magic-flat-defense 合計 " + treeTotals.get("magic-flat-defense")
                        + " が、最良の防具1点 " + bestMagicPiece + " の " + Math.round(magicRatio * 100) + "% しかない");

        // 装備側の土俵は魔法のほうが大きい(最良4部位で phys 20.0 / magic 62.6)。物理と魔法に
        // 同じ値を配ると、魔法側だけ自分のスケールに対して過小になる ── 報告時点はまさにそれだった。
        assertTrue(treeTotals.get("magic-flat-defense") > treeTotals.get("phys-flat-defense"),
                "magic-flat-defense の合計 " + treeTotals.get("magic-flat-defense")
                        + " が phys-flat-defense の合計 " + treeTotals.get("phys-flat-defense")
                        + " 以下。装備側の土俵は魔法のほうが大きいので、同値だと魔法側だけ過小になる");
    }

    @Test
    @DisplayName("守備力(実数)の路線はレベル帯に追随して増える(定数のまま置かれていない)")
    void flatDefenseGrants_rampWithLevel() {
        for (String key : List.of("phys-flat-defense", "magic-flat-defense")) {
            List<double[]> byLevel = grantsByLevel("heavy_armor", key);
            assertTrue(byLevel.size() >= 5,
                    "heavy_armor.yml から " + key + " の付与を " + byLevel.size()
                            + " 件しか拾えていない。検査対象が消えているので空振りしている");

            double bottom = byLevel.get(0)[1];
            double top = byLevel.get(byLevel.size() - 1)[1];
            assertTrue(bottom > 0, key + " の最低レベルの付与が 0");
            assertTrue(top / bottom >= MIN_TOP_OVER_BOTTOM,
                    "heavy_armor の " + key + " が Lv" + (long) byLevel.get(0)[0] + " の " + bottom
                            + " から Lv" + (long) byLevel.get(byLevel.size() - 1)[0] + " の " + top
                            + " へ " + String.format("%.2f", top / bottom) + " 倍にしか伸びていない。"
                            + "モブ攻撃力は Lv10→Lv90 で 3.2 倍になるので、実数の守備力を定数で置くと"
                            + "高レベルほど価値がゼロへ漸近する(報告時点は全帯 0.05 の定数だった)");
        }
    }

    @Test
    @DisplayName("帯に追随できない実数キー(attack-power / reflect-flat)はスキルツリーが配らない")
    void bannedFlatKeys_areNotGrantedByAnyTree() {
        List<String> offenders = new ArrayList<>();
        for (File file : treeFiles()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : BANNED_FLAT_KEYS) {
                for (String where : buffBlocks(yaml)) {
                    ConfigurationSection buffs = yaml.getConfigurationSection(where);
                    if (buffs != null && buffs.isSet(key)) {
                        offenders.add(file.getName() + " " + where + "." + key + " = " + buffs.get(key));
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "帯に追随できない実数キーがスキルツリーに書かれている: " + offenders
                        + " —— 装備側の attack-power は帯とともに指数で伸びる(中央値 828 / 最大 80,965)ため"
                        + "定数では原理的に釣り合わない。割合系(percent-bonus-damage / reflect-percent)へ振り替えること");
    }

    @Test
    @DisplayName("弓術は実数ダメージではなく割合ダメージを配る(振替が消えていない)")
    void archery_grantsPercentDamage() {
        double total = sumAcrossAllTrees("percent-bonus-damage");
        assertTrue(total > 0,
                "skilltree のどこにも percent-bonus-damage が無い。弓術の attack-power からの振替が"
                        + "巻き戻ると、弓術ツリーはダメージ増加を1つも配らなくなる");
        assertFalse(BANNED_FLAT_KEYS.isEmpty(), "禁止キー一覧が空。この検査は空振りしている");
    }

    // ---- 出荷 yml の読み出し -------------------------------------------------

    private static List<File> treeFiles() {
        File dir = new File(TREE_DIR);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null || files.length == 0) {
            throw new AssertionError("出荷スキルツリーが見つからない: " + dir.getAbsolutePath());
        }
        List<File> list = new ArrayList<>(List.of(files));
        list.sort((a, b) -> a.getName().compareTo(b.getName()));
        return list;
    }

    /**
     * {@code nodes.<id>.buffs} / {@code nodes.<id>.mainhand-buffs} と、その prestige 版のパス一覧。
     *
     * <p><b>2026-08-15: {@code mainhand-buffs} を追加した。</b> それまでこの検査は {@code buffs} しか
     * 見ておらず、<b>軽量武器・重量武器の全ノードが検査対象の外にあった</b>(あちらは
     * {@code mainhand-buffs} で書く)。禁止キーの検査が「該当なし」で緑になっていたのは
     * 守れていたからではなく、見ていなかったから ── W-30 の出血ダメージ実数150 は
     * まさにその穴を通って出荷されていた。
     */
    private static List<String> buffBlocks(YamlConfiguration yaml) {
        List<String> paths = new ArrayList<>();
        for (String block : List.of("buffs", "mainhand-buffs")) {
            if (yaml.isConfigurationSection("prestige." + block)) {
                paths.add("prestige." + block);
            }
        }
        ConfigurationSection nodes = yaml.getConfigurationSection("nodes");
        if (nodes != null) {
            for (String id : nodes.getKeys(false)) {
                for (String block : List.of("buffs", "mainhand-buffs")) {
                    if (nodes.isConfigurationSection(id + "." + block)) {
                        paths.add("nodes." + id + "." + block);
                    }
                }
            }
        }
        return paths;
    }

    private static double sumAcrossAllTrees(String key) {
        double sum = 0;
        for (File file : treeFiles()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String where : buffBlocks(yaml)) {
                ConfigurationSection buffs = yaml.getConfigurationSection(where);
                if (buffs != null && buffs.isSet(key)) {
                    sum += buffs.getDouble(key);
                }
            }
        }
        return sum;
    }

    /** 1ツリーの {@code key} 付与を {@code {level, value}} でレベル昇順に返す(prestige は除く)。 */
    private static List<double[]> grantsByLevel(String tree, String key) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(TREE_DIR, tree + ".yml"));
        List<double[]> out = new ArrayList<>();
        ConfigurationSection nodes = yaml.getConfigurationSection("nodes");
        if (nodes != null) {
            for (String id : nodes.getKeys(false)) {
                ConfigurationSection buffs = nodes.getConfigurationSection(id + ".buffs");
                if (buffs != null && buffs.isSet(key)) {
                    out.add(new double[] {nodes.getDouble(id + ".level"), buffs.getDouble(key)});
                }
            }
        }
        out.sort((a, b) -> Double.compare(a[0], b[0]));
        return out;
    }

    /**
     * 出荷 {@code item-stats.yml} で、防具1部位が配る {@code key} の最大値。
     * 値は素の数値か {@code {min, max}} のどちらでも書けるので両方を見る。
     */
    private static double bestSingleArmorPiece(String key) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(ITEM_STATS));
        ConfigurationSection items = yaml.getConfigurationSection("items");
        if (items == null) {
            throw new AssertionError("item-stats.yml に items セクションが無い: " + new File(ITEM_STATS).getAbsolutePath());
        }
        double best = 0;
        for (String itemKey : items.getKeys(false)) {
            String upper = itemKey.toUpperCase();
            boolean armor = upper.contains("HELMET") || upper.contains("CHESTPLATE")
                    || upper.contains("LEGGINGS") || upper.contains("BOOTS");
            if (!armor) {
                continue;
            }
            ConfigurationSection item = items.getConfigurationSection(itemKey);
            if (item != null) {
                best = Math.max(best, deepMax(item, key));
            }
        }
        return best;
    }

    private static double deepMax(ConfigurationSection section, String key) {
        double best = 0;
        for (String child : section.getKeys(false)) {
            if (child.equals(key)) {
                best = Math.max(best, section.isConfigurationSection(child)
                        ? section.getDouble(child + ".max")
                        : section.getDouble(child));
            } else if (section.isConfigurationSection(child)) {
                best = Math.max(best, deepMax(section.getConfigurationSection(child), key));
            }
        }
        return best;
    }
}
