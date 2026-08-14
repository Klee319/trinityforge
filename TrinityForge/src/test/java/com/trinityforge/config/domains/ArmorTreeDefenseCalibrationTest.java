package com.trinityforge.config.domains;

import com.trinityforge.stats.AttributeProjection;
import com.trinityforge.stats.PercentStatNormalize;
import com.trinityforge.stats.StatKeys;
import com.trinityforge.stats.StatVocabulary;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装備ツリー(軽装備 / 重装備)が配る防御ステの<b>単位</b>と<b>総量</b>を固定する (2026-08-15)。
 *
 * <h2>直した不具合1 — 「防御力」が2つの単位を1キーで運んでいた</h2>
 * {@code armor-defense-rate} は
 * <ul>
 *   <li><b>アイテム側</b>: バニラ防具値(点数)。{@code AttributeProjection} が {@code Attribute.ARMOR} へ
 *       ADD_NUMBER し、{@code combat/damage.yml} の {@code vanilla-armor.defense-rate-per-point}(0.015/点)を
 *       通して初めて軽減率になる。</li>
 *   <li><b>パーク側</b>: {@code [0,1]} の乗算軽減率そのもの。{@code PlayerDefenseResolver} が
 *       {@code DefenseStats#defenseRate} へ直結する。</li>
 * </ul>
 * を同じキーで運んでいた。ロア表示は {@code FLAT} 1本なので「防御力 +8」(防具値)と
 * 「防御力 +0.1」(10%軽減)が同じ書式で並び、実効の差が7倍あっても見分けが付かない。
 * さらに {@link PercentStatNormalize} は防具値のほうを守るためにこのキーを%矯正の対象外にしていたので、
 * <b>パーク側に {@code 10} と書くと 1000% 軽減として通っていた</b>。
 * まず割合のほうを {@code defense-rate}(PERCENT表示・%矯正あり)へ分離し、
 * <b>同日中に「防具値は直感的でない」というユーザー判断で防具値ステ自体を廃止した</b> —
 * アイテム側の点数も {@code 1点 = 1.5%軽減}({@code vanilla-armor.defense-rate-per-point} と同率)で
 * 換算して {@code defense-rate} へ統合し、{@code Attribute.ARMOR} への写像も撤去した。
 * TFスタンプ装備のバニラ防具バーは常に空({@code AttributeApplier} が材質既定を復元しない)なので、
 * バニラ防具ミラー経由の二重計上も無い。
 *
 * <h2>直した不具合2 — 軽装備ツリーの守備力がツリー全取りでも装備の 1/6 だった</h2>
 * 2026-08-14 の較正は<b>ノード単価</b>だけを「重装備の半分」に揃えたが、守備力を配るノードが
 * 軽装備は2個・重装備は7個なので、<b>ツリー全取りの合計</b>では phys 0.8 / magic 2.4 =
 * 重装備(4.6 / 13.8)の 17% にしかならなかった。
 * ノード単価を合わせても総量が合わないという壊れ方はレビューで見落とされやすいので、
 * <b>合計側を直接縛る</b>のがこのテスト。
 *
 * <h2>土俵(なぜこの数字か)</h2>
 * 基準は「その帯で実際に着る装備4部位の合計」。出荷 {@code stats/item-stats.yml} の実測で
 * Lv100 の4部位合計は重装 phys 13.80 / magic 62.60、軽装 phys 13.95 / magic 45.00。
 * 重装備ツリー全取り(4.6 / 13.8)が<b>おおよそ装備1部位ぶん</b>に当たるので、
 * 軽装備はその半分(= 0.5 部位ぶん)を目標に置く。
 */
class ArmorTreeDefenseCalibrationTest {

    private static final String LIGHT = "skilltree/light_armor.yml";
    private static final String HEAVY = "skilltree/heavy_armor.yml";
    private static final String LORE = "stats/lore.yml";

    private static final String K_PHYS_FLAT = "phys-flat-defense";
    private static final String K_MAGIC_FLAT = "magic-flat-defense";
    private static final String K_DEFENSE_RATE = "defense-rate";
    private static final String K_ARMOR_DEFENSE_RATE = "armor-defense-rate";

    /** 出荷スキルツリー16本。パーク側の旧キー残存を全ツリーで見るため。 */
    private static final List<String> SHIPPED_TREES = List.of(
            "alchemy", "archery", "ars_magic", "ars_smithing", "digging", "enchanting", "farming",
            "fishing", "heavy_armor", "heavy_weapons", "light_armor", "light_weapons", "mining",
            "power", "smithing", "woodcutting");

    private static YamlConfiguration load(String path) throws IOException {
        try (InputStream in = ArmorTreeDefenseCalibrationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static String readRaw(String path) throws IOException {
        try (InputStream in = ArmorTreeDefenseCalibrationTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // === ツリー全取りの合計 ===

    /**
     * 「1キャラが取り切れる最大」の合計。ギリシャ路線 ({@code group}) は排他なので、
     * 同じ group からは<b>最大値の1ノードだけ</b>を採る。set-buffs は装備部位数に依存するので
     * 対象外(守備力を配っているのは buffs / mainhand-buffs だけ)。
     */
    private static double maxObtainable(YamlConfiguration tree, String statKey) {
        double total = 0.0;
        ConfigurationSection prestige = tree.getConfigurationSection("prestige.buffs");
        if (prestige != null) {
            total += prestige.getDouble(statKey, 0.0);
        }
        ConfigurationSection nodes = tree.getConfigurationSection("nodes");
        assertNotNull(nodes, "nodes セクションが無い");

        Map<String, Double> bestPerGroup = new LinkedHashMap<>();
        for (String nodeId : nodes.getKeys(false)) {
            ConfigurationSection node = nodes.getConfigurationSection(nodeId);
            if (node == null) {
                continue;
            }
            double value = 0.0;
            for (String block : List.of("buffs", "mainhand-buffs")) {
                ConfigurationSection buffs = node.getConfigurationSection(block);
                if (buffs != null) {
                    value += buffs.getDouble(statKey, 0.0);
                }
            }
            if (value == 0.0) {
                continue;
            }
            String group = node.getString("group");
            if (group == null || group.isBlank()) {
                total += value;
            } else {
                bestPerGroup.merge(group, value, Math::max);
            }
        }
        for (double best : bestPerGroup.values()) {
            total += best;
        }
        return total;
    }

    @Test
    @DisplayName("軽装備ツリー全取りの守備力は重装備のちょうど半分(ノード単価だけ合わせて合計が1/6のまま、に戻ると落ちる)")
    void lightArmorTreeGrantsExactlyHalfOfTheHeavyArmorTreesFlatDefense() throws IOException {
        YamlConfiguration light = load(LIGHT);
        YamlConfiguration heavy = load(HEAVY);

        double heavyPhys = maxObtainable(heavy, K_PHYS_FLAT);
        double heavyMagic = maxObtainable(heavy, K_MAGIC_FLAT);
        double lightPhys = maxObtainable(light, K_PHYS_FLAT);
        double lightMagic = maxObtainable(light, K_MAGIC_FLAT);

        // 重装備側が痩せたら軽装備の目標も一緒にずれてしまうので、先に重装備を絶対値で固定する。
        // 8.4 の出どころは combat の設計線 F(L) = 0.5 * A(L):
        //   Lv100 のモブ基準攻撃力 A = 10.2 * 1.0148^100 = 44.3 → 守備力の総量の目標 22.2
        //   出荷 item-stats の最良装備4部位 phys 13.8 → ツリーの取り分 = 22.2 - 13.8 = 8.4
        // これ以上積むと残差が min-component-damage:1 に張り付き、全帯が「ゼロか爆発」の二択になる
        // (docs/agent-context/combat.md の 2026-08-12 再較正)。つまり 8.4 は目安ではなく上限。
        assertEquals(8.4, heavyPhys, 1e-6,
                "重装備ツリー全取りの物理守備力が " + heavyPhys + "。設計線 F(L)=0.5*A(L) から逆算した"
                        + "ツリーの取り分 8.4 から動いている。上へ動かすとダメージ下限へ張り付き、"
                        + "下へ動かすと「守備力だけ帯に追随しない」状態へ逆戻りする。");
        assertEquals(25.2, heavyMagic, 1e-6,
                "重装備ツリー全取りの魔法守備力が " + heavyMagic + "(25.2 であるべき)。"
                        + "物理の3倍なのは装備側の土俵が約3倍あるため(Lv100 の重装4部位で magic 62.6)。");

        assertEquals(heavyPhys / 2.0, lightPhys, 1e-6,
                "軽装備ツリー全取りの物理守備力が " + lightPhys + " で、重装備 " + heavyPhys
                        + " の半分になっていない。2026-08-14 の較正はノード単価だけを半分にしたので"
                        + "(守備力を配るノードが軽装2個 / 重装7個)、合計では 0.8 = 17% しかなく実質 no-op だった。");
        assertEquals(heavyMagic / 2.0, lightMagic, 1e-6,
                "軽装備ツリー全取りの魔法守備力が " + lightMagic + " で、重装備 " + heavyMagic
                        + " の半分になっていない");
    }

    @Test
    @DisplayName("軽装備の守備力は1ノードに寄せず4ノードへ分散する(単価で同レベルの重装備ノードを追い越さないため)")
    void lightArmorSpreadsItsFlatDefenseOverSeveralNodes() throws IOException {
        YamlConfiguration light = load(LIGHT);
        List<String> carriers = new ArrayList<>();

        ConfigurationSection prestige = light.getConfigurationSection("prestige.buffs");
        if (prestige != null && prestige.getDouble(K_PHYS_FLAT, 0.0) > 0.0) {
            carriers.add("prestige");
        }
        ConfigurationSection nodes = light.getConfigurationSection("nodes");
        assertNotNull(nodes, "nodes セクションが無い");
        for (String nodeId : nodes.getKeys(false)) {
            ConfigurationSection buffs = nodes.getConfigurationSection(nodeId + ".buffs");
            if (buffs != null && buffs.getDouble(K_PHYS_FLAT, 0.0) > 0.0) {
                carriers.add(nodeId);
            }
        }
        assertTrue(carriers.size() >= 4,
                "軽装備で守備力を配っているのが " + carriers + " の " + carriers.size() + " 箇所しかない。"
                        + "合計を1〜2ノードへ寄せると、同レベルの重装備ノードを単価で追い越してしまう"
                        + "(重装備は7ノードへ分散している)。");
    }

    // === 単位の分離 ===

    @Test
    @DisplayName("防御は defense-rate([0,1]の軽減率)1本 — 防具値(点数)の語彙・ロア・属性写像が全部消えている")
    void defenseIsCarriedByASingleRateKey() throws IOException {
        String rate = StatKeys.canonical(K_DEFENSE_RATE);
        String points = StatKeys.canonical(K_ARMOR_DEFENSE_RATE);

        assertEquals(StatVocabulary.Channel.DEFENSE, StatVocabulary.channelOf(rate),
                "defense-rate が DEFENSE チャネルに無い。PerkBuffResolver が channel NONE として"
                        + "パーク由来分を無言でドロップする。");
        assertEquals(StatVocabulary.Channel.NONE, StatVocabulary.channelOf(points),
                "armor-defense-rate(防具値) が語彙へ戻っている。「点数」と「割合」がまた1キーに同居する。");

        assertTrue(PercentStatNormalize.isRateKey(rate),
                "defense-rate が %矯正の対象外になっている。yml に 10 と書くと 1000% 軽減として通ってしまう"
                        + "(防具値と同居していた頃に実際にそうなっていた)。");

        // 防具値は Attribute.ARMOR へ写像しない。写像を戻すと、TFの防御率とバニラ防具ミラーで二重に軽減する。
        assertFalse(AttributeProjection.defaults().entries().containsKey(points),
                "armor_defense_rate の Attribute.ARMOR 写像が復活している(防御率と二重計上になる)");

        ConfigurationSection lore = load(LORE).getConfigurationSection("stats");
        assertNotNull(lore, "lore.yml に stats セクションが無い");
        assertEquals("PERCENT", lore.getString(K_DEFENSE_RATE + ".format"),
                "defense-rate のロア書式が PERCENT でない。割合を FLAT で出すと"
                        + "「防御力 +0.1」という単位不明の表示に戻る(ユーザー報告の元の症状)。");
        assertEquals("防御率", lore.getString(K_DEFENSE_RATE + ".name"));
        assertFalse(lore.contains(K_ARMOR_DEFENSE_RATE),
                "lore.yml に防具値のエントリが戻っている。表示だけ戻すと、実効ゼロのステが lore に出る。");
    }

    /**
     * 出荷 {@code stats/item-stats.yml} が防具値ではなく防御率で書かれていること、
     * かつ換算レート(1点=1.5%)の刻みを保っていること。
     * 防具値へ書き戻されると {@code DefenseStatBridge} が [0,1] の率として読むため、
     * たとえば「8点」が 800% 軽減になる(= 全ダメージ0)。
     */
    @Test
    @DisplayName("出荷 item-stats は防御率で書かれている — 防具値(点数)へ書き戻されていない")
    void shippedItemStatsCarryDefenseRateNotArmorPoints() throws IOException {
        List<String> offenders = new ArrayList<>();
        int rateLines = 0;
        for (String line : readRaw("stats/item-stats.yml").split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue; // 廃止の経緯を書いたコメントは対象外
            }
            if (trimmed.startsWith(K_ARMOR_DEFENSE_RATE + ":")) {
                offenders.add(trimmed);
            } else if (trimmed.startsWith(K_DEFENSE_RATE + ":")) {
                rateLines++;
                double value = Double.parseDouble(trimmed.substring((K_DEFENSE_RATE + ":").length()).trim());
                assertTrue(value > 0.0 && value <= 0.5,
                        "item-stats の defense-rate が [0,1] の軽減率の範囲を外れている: " + trimmed
                                + "。点数(1〜8)のまま書かれた疑いがある。");
            }
        }
        assertTrue(offenders.isEmpty(), "item-stats に防具値が残っている: " + offenders);
        assertTrue(rateLines >= 150,
                "item-stats の defense-rate が " + rateLines + " 行しかない(151 行あるはず)。"
                        + "防具値の一括換算が巻き戻された疑いがある。");
    }

    /**
     * 実数系ステが単位なしの裸の数字で出ていた件 (2026-08-15 のユーザー報告
     * 「守備力とかあるべきものに単位ついていないの違和感ある」)。
     *
     * <p><b>守備力は % ではない</b>。{@code ComponentDamageCalculator} のパイプラインで
     * 防御率 / 耐性 / 被ダメージ軽減という3つの<b>乗算</b>軽減より<b>前</b>に引かれる素の減算なので、
     * 1ポイント = 被ダメージ1。PERCENT にすると表示が ×100 されて 0.8 が 80% になる
     * (ロール3キーが FLAT のまま単位 {@code %} だけ付けているのと同じ理由)。
     * 正しい直し方は書式変更ではなく {@code unit} の付与。
     *
     * <p>件数の上限で縛るのは、**単位を1件でも剥がすと落ちる**ようにするため。
     * 「単位を持つべきキーの一覧」を書くとその一覧自体が腐って検査ごと無効になる。
     */
    @Test
    @DisplayName("実数系ステは単位を持つ — 守備力/攻撃力などが裸の数字へ戻ったら落ちる")
    void realValuedStatsDeclareAUnit() throws IOException {
        ConfigurationSection stats = load(LORE).getConfigurationSection("stats");
        assertNotNull(stats, "lore.yml に stats セクションが無い");

        List<String> unitless = new ArrayList<>();
        for (String key : stats.getKeys(false)) {
            ConfigurationSection entry = stats.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            // PERCENT は % が自動で付くので unit を書くと二重になる(2026-08-12 の訂正)。
            if ("PERCENT".equals(entry.getString("format", "FLAT"))) {
                continue;
            }
            if (entry.getString("unit", "").isBlank()) {
                unitless.add(key);
            }
        }

        for (String mustHaveUnit : List.of(K_PHYS_FLAT, K_MAGIC_FLAT, "flat-defense",
                "max-health", "attack-power")) {
            assertFalse(unitless.contains(mustHaveUnit),
                    mustHaveUnit + " の unit が消えている。ロアに単位なしの裸の数字が出て、"
                            + "%軽減なのか実数の引き算なのか読めなくなる(2026-08-15 の報告の症状)。");
        }
        assertTrue(unitless.size() <= 6,
                "単位を持たない実数系ステが " + unitless.size() + " 件ある: " + unitless
                        + "。2026-08-15 に 28 → 6 件へ減らした(残り6件は運とクラフト品質σで、"
                        + "単位の意味そのものが未確定)。新しい実数ステを足すときは unit も書くこと。");
    }

    @Test
    @DisplayName("出荷スキルツリーは1本も armor-defense-rate(防具値) を配っていない — パーク側は defense-rate だけ")
    void noShippedTreeGrantsTheItemSideArmorPointsKey() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scopedHits = 0;
        for (String tree : SHIPPED_TREES) {
            String raw = readRaw("skilltree/" + tree + ".yml");
            for (String line : raw.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    continue; // 経緯を書いたコメントは対象外
                }
                if (trimmed.startsWith(K_ARMOR_DEFENSE_RATE + ":")) {
                    offenders.add(tree + ".yml: " + trimmed);
                } else if (trimmed.startsWith(K_DEFENSE_RATE + ":")) {
                    scopedHits++;
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "スキルツリーが armor-defense-rate を配っている: " + offenders
                        + "。これはアイテム側のバニラ防具値(点数)のキー。パークが割合として書くと"
                        + "%矯正が掛からず、0.1 が「防具値 +0.1点」として無視されるか、"
                        + "10 が 1000% 軽減として通る。");
        assertTrue(scopedHits >= 9,
                "defense-rate が出荷ツリーで " + scopedHits + " 箇所しか使われていない。"
                        + "軽装備(プレステージ + 主軸D + ギリシャβ 5本) で 7 箇所、"
                        + "重装備(プレステージ + 主軸D) で 2 箇所、計 9 箇所あるはず — "
                        + "旧キーへ戻された疑いがある。");
    }
}
