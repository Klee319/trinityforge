package com.trinityforge.stats;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code stats/item-stats.yml} のスレッド45種(CMD 300001-300045)の厳選定義を固定する
 * (2026-08-03 ユーザー指摘「一部スレッドが名称と効果が一致していない(マナ増幅のスレッドなど)」)。
 *
 * <h2>なぜ机上で落とす必要があるのか</h2>
 * <p>スレッドの「性格」は<b>どこにも宣言されていない</b>。名前と CustomModelData は
 * ArsPaper フォークの {@code ThreadType} enum、効果は TF 側のこの yml、セット効果は
 * フォークの {@code thread-sets.yml} という<b>3ファイルに分かれていて相互参照が無い</b>。
 * そのため「マナ増幅のスレッドの主ステが会心ダメージ」のような食い違いが起きても、
 * <b>起動もテストも通り、ゲーム内で数値を見比べるまで誰も気づかない</b>。
 * 実際に 2026-08-02 の40種化ではテーマ割り当てが名称とほぼ無関係になっていて、
 * ユーザーからの指摘で初めて判明した。
 *
 * <p>そこで<b>この表が主ステの唯一の宣言</b>になる。スレッドを増やす・テーマを変えるときは
 * ここを直すのが先で、yml だけ直すとこのテストが落ちる。
 *
 * <h2>組み立ての規約(45件で統一。崩すと厳選が機能しなくなる)</h2>
 * <ul>
 *   <li>{@code per-quality:} は<b>主ステ1件だけ</b>(品質0..9で伸びる軸)。</li>
 *   <li>{@code random:} は<b>主ステ + サブ4種</b>の5件。主ステは {@code random} の先頭。</li>
 *   <li>{@code advanced.randomize-grants: true} + {@code grant-chances} でサブ4種を各 0.45。
 *       <b>主ステは grant-chances に書かない</b>(書くと主ステが確率で消え、名前どおりの
 *       効果を持たない個体が出る)。</li>
 *   <li>{@code offhand-stats-apply: false}(スレッド自体をオフハンドに持って効かせない)。</li>
 * </ul>
 *
 * <h2>主ステに選んではいけないキー2種</h2>
 * <ul>
 *   <li><b>バニラ属性へ投影されるキー</b>({@link AttributeProjection#defaults()} の全キー)。
 *       スレッド個体は生成時に {@code ItemFactory#stamp} を通り、{@code ItemAssembler#assemble} が
 *       これらを<b>バニラ属性として実際に付けてしまう</b> ── 装備に挿さず<b>手に持つだけで</b>
 *       最大体力や移動速度が上がる。現状無事なのは「割り当てたステがどれとも重ならない」からに過ぎない。</li>
 *   <li><b>{@link StatVocabulary.Channel#ATTRIBUTE} のキー</b>。装着スレッドのステは
 *       フォークの {@code ArmorManaListener} → {@code AddonCombatStats}(プレイヤーPDC)へ流れ、
 *       TF は {@code PlayerCombatAggregate#totalOf} でしか読まない。属性チャネルは
 *       {@code PerkAttributeApplier} 側の別経路なので、書いても<b>無言で効かない</b>。</li>
 * </ul>
 *
 * <p>マナ系5キー({@code mana-bonus} / {@code mana-regen} / {@code hit-mana-recovery} /
 * {@code damage-mana-recovery} / {@code mana-cost-reduction-percent})は 2026-08-03 に
 * フォーク側 {@code ThreadManaStatRouting} を足して初めて実効化した。それ以前は addon チャネルへ
 * 流れるだけで誰も読まず、書いても無言で死んでいた。
 */
class ShippedThreadItemStatsTest {

    private static final String ITEM_STATS = "src/main/resources/stats/item-stats.yml";
    private static final String LORE = "src/main/resources/stats/lore.yml";

    /** サブステの付与確率。45件で統一(平均1.8種が付く)。 */
    private static final double GRANT_CHANCE = 0.45;

    /**
     * CMD → (スレッド名, 主ステ)。名称に対応する軸を主ステに据えるのがこの表の役目。
     *
     * <p>常時ポーション効果・飛行・バックパックのスレッドは<b>名称の効果を別経路で既に配っている</b>
     * (フォークの {@code ThreadType} のポーション効果 / 飛行フラグ / バックパック)ため、
     * 主ステは「その効果に近い戦闘軸」を据えている。ステ語彙側に対応キーが無いものも同じ扱い:
     * 例えば体力増強の {@code max-health} と迅速の {@code move-speed} は上の「選んではいけないキー」に
     * 当たるので使えない。
     *
     * <p><b>フォークの {@code thread-sets.yml} のしきい値1段目と同じキーに揃えてある</b>
     * (2026-08-03 追加24種はそこで名称どおりの軸を配っている)。同じ軸に揃えると
     * 「1個挿しただけの効果」と「集めたときの効果」が同じ方向に伸びる。
     * <b>この一致は機械では検査できない</b> ── フォークのリソースは {@code .gitignore} 除外で
     * ワークツリーに存在しないことがあるため、TF 側のテストからは読めない。片方だけ直すと静かにずれる。
     */
    private static final Map<Integer, Thread> THREADS = threads();

    private record Thread(String name, String primary) {
    }

    private static Map<Integer, Thread> threads() {
        Map<Integer, Thread> m = new LinkedHashMap<>();
        // --- マナ・魔法系 ---
        m.put(300002, new Thread("マナ回復速度上昇", "mana-regen"));
        m.put(300003, new Thread("マナ最大値上昇", "mana-bonus"));
        m.put(300012, new Thread("被弾マナ回復", "hit-mana-recovery"));
        m.put(300013, new Thread("攻撃マナ回復", "damage-mana-recovery"));
        m.put(300014, new Thread("詠唱効率", "mana-cost-reduction-percent"));
        m.put(300017, new Thread("マナ増幅", "mana-bonus"));
        m.put(300018, new Thread("循環", "mana-regen"));
        m.put(300019, new Thread("源流節約", "source-cost-reduction"));
        // --- 制作系 ---
        m.put(300020, new Thread("匠", "workbench-quality-bonus"));
        m.put(300021, new Thread("儀式師", "ritual-quality-bonus"));
        m.put(300022, new Thread("倹約", "material-refund-chance"));
        m.put(300023, new Thread("解体", "disassembly-return-bonus"));
        m.put(300031, new Thread("選書", "enchant-luck"));
        // --- 採取・生活系 ---
        m.put(300024, new Thread("豊鉱", "mining-fortune"));
        m.put(300025, new Thread("潮読み", "fishing-luck"));
        m.put(300026, new Thread("実り", "harvest-extra-drop-chance"));
        m.put(300027, new Thread("年輪", "woodcutting-extra-drop-chance"));
        m.put(300029, new Thread("研鑽", "skill-exp-bonus"));
        m.put(300033, new Thread("持久", "hunger-save-chance"));
        m.put(300034, new Thread("美食", "food-restore-bonus"));
        // --- 戦闘系 ---
        m.put(300028, new Thread("戦利品", "mob-drop-bonus"));
        m.put(300030, new Thread("経験", "vanilla-exp-bonus"));
        m.put(300032, new Thread("治癒", "health-regen-bonus"));
        m.put(300035, new Thread("棘", "reflect-percent"));
        m.put(300036, new Thread("昏倒", "stun-chance"));
        m.put(300037, new Thread("速攻", "cooldown-reduction"));
        m.put(300038, new Thread("射手", "ammo-save-chance"));
        m.put(300040, new Thread("幸運", "loot-luck"));
        // --- 常時効果系(名称の効果はポーション/飛行/バックパックで配っている) ---
        m.put(300004, new Thread("迅速", "dodge-chance"));
        m.put(300005, new Thread("跳躍", "crit-chance"));
        m.put(300006, new Thread("暗視", "crit-chance"));
        m.put(300007, new Thread("耐火", "magic-resistance"));
        m.put(300008, new Thread("イルカの好意", "dodge-chance"));
        m.put(300009, new Thread("コンジットパワー", "magic-flat-defense"));
        m.put(300010, new Thread("村の英雄", "attack-power"));
        m.put(300011, new Thread("体力増強", "phys-flat-defense"));
        m.put(300015, new Thread("飛行", "dodge-chance"));
        m.put(300016, new Thread("バックパック", "phys-flat-defense"));
        m.put(300039, new Thread("浮遊", "dodge-chance"));
        // --- 2026-08-03 追加: レシピを持たない5種(ガチャ景品専用) ---
        m.put(300041, new Thread("調香", "potion-quality-bonus"));
        m.put(300042, new Thread("養蜂", "hive-harvest-fortune"));
        m.put(300043, new Thread("牧人", "breeding-extra-child-chance"));
        m.put(300044, new Thread("鑑識", "mob-drop-quality"));
        m.put(300045, new Thread("削岩", "gathering-efficiency"));
        return Map.copyOf(m);
    }

    /** 効果を持たない「空のスレッド」。ステ節を一切持たないことだけを確認する。 */
    private static final int EMPTY_CMD = 300001;

    private static ConfigurationSection items() {
        File file = new File(ITEM_STATS);
        assertTrue(file.isFile(), "出荷 item-stats.yml が見つからない: " + file.getAbsolutePath());
        ConfigurationSection items = YamlConfiguration.loadConfiguration(file).getConfigurationSection("items");
        assertNotNull(items, "item-stats.yml に items: が無い");
        return items;
    }

    /** CMD → そのスレッドの items エントリ(キーは {@code MATERIAL#CMD})。 */
    private static Map<Integer, ConfigurationSection> threadEntries(ConfigurationSection items) {
        Map<Integer, ConfigurationSection> out = new LinkedHashMap<>();
        for (String key : items.getKeys(false)) {
            int hash = key.indexOf('#');
            if (hash < 0) continue;
            int cmd;
            try {
                cmd = Integer.parseInt(key.substring(hash + 1));
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (cmd < 300001 || cmd > 300045) continue;
            ConfigurationSection entry = items.getConfigurationSection(key);
            assertNotNull(entry, key + " のエントリが節になっていない");
            assertFalse(out.containsKey(cmd), "CMD " + cmd + " のエントリが2件ある(材質違いの重複): " + key);
            out.put(cmd, entry);
        }
        return out;
    }

    @Test
    @DisplayName("スレッド45種(300001-300045)が漏れなく1件ずつ定義されている")
    void everyThreadHasExactlyOneEntry() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());

        Set<Integer> expected = new TreeSet<>(THREADS.keySet());
        expected.add(EMPTY_CMD);
        Set<Integer> missing = new TreeSet<>(expected);
        missing.removeAll(entries.keySet());
        Set<Integer> unexpected = new TreeSet<>(entries.keySet());
        unexpected.removeAll(expected);

        assertEquals(Set.of(), missing,
                "item-stats.yml に定義の無いスレッドがある。ステ節が無いスレッドは"
                        + "『装着しても何も起きない』ので、増種時はこの表と yml の両方を足すこと");
        assertEquals(Set.of(), unexpected,
                "この表に無い CMD のスレッドが yml にある。増種したなら THREADS へ追記して"
                        + "主ステを宣言すること(宣言が無いと名称と効果の食い違いを検出できない): " + unexpected);
        assertEquals(45, entries.size(), "スレッドの総数が45件でない");
    }

    @Test
    @DisplayName("空のスレッドはステ節を一切持たない")
    void emptyThreadHasNoStats() {
        ConfigurationSection entry = threadEntries(items()).get(EMPTY_CMD);
        assertNotNull(entry, "空のスレッド(300001)が見つからない");
        for (String section : List.of("fixed", "per-quality", "random", "advanced")) {
            assertFalse(entry.contains(section),
                    "空のスレッドに " + section + " がある。効果を持たないことが仕様なので、"
                            + "ここへ書くと『空』が最強のスレッドになる");
        }
    }

    @Test
    @DisplayName("主ステが名称に対応する軸になっている(名称と効果の食い違いの検出)")
    void primaryStatMatchesTheThreadName() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());
        List<String> wrong = new ArrayList<>();

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue; // 件数は別テストが見る
            Thread thread = declared.getValue();

            ConfigurationSection perQuality = entry.getConfigurationSection("per-quality");
            assertNotNull(perQuality, thread.name() + "のスレッド(" + declared.getKey() + ")に per-quality が無い");
            List<String> pqKeys = new ArrayList<>(perQuality.getKeys(false));
            assertEquals(1, pqKeys.size(),
                    thread.name() + "のスレッドの per-quality が1件でない(主ステ1件だけが規約): " + pqKeys);

            List<String> randomKeys = new ArrayList<>(randomSection(entry, thread).getKeys(false));
            String primary = randomKeys.isEmpty() ? "(なし)" : randomKeys.get(0);

            if (!thread.primary().equals(pqKeys.get(0)) || !thread.primary().equals(primary)) {
                wrong.add(thread.name() + "のスレッド(" + declared.getKey() + "): 宣言=" + thread.primary()
                        + " / per-quality=" + pqKeys.get(0) + " / random先頭=" + primary);
            }
        }

        assertEquals(List.of(), wrong,
                "主ステが宣言と食い違っている。名称と効果が一致しないスレッドは"
                        + "『マナ増幅なのに会心ダメージが伸びる』という形でユーザーに見つかる: " + wrong);
    }

    @Test
    @DisplayName("組み立ての規約: 主ステ+サブ4種 / サブは各0.45の確率付与 / 主ステは確率ゲートしない")
    void rollLayoutFollowsTheSharedConvention() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue;
            Thread thread = declared.getValue();
            String where = thread.name() + "のスレッド(" + declared.getKey() + ")";

            ConfigurationSection random = randomSection(entry, thread);
            List<String> randomKeys = new ArrayList<>(random.getKeys(false));
            assertEquals(5, randomKeys.size(), where + " の random が5件(主ステ+サブ4種)でない: " + randomKeys);
            assertEquals(randomKeys.size(), new LinkedHashSet<>(randomKeys).size(),
                    where + " の random にキーの重複がある: " + randomKeys);

            for (String key : randomKeys) {
                ConfigurationSection range = random.getConfigurationSection(key);
                assertNotNull(range, where + " の random." + key + " が {min,max} の節でない");
                assertTrue(range.contains("min") && range.contains("max"),
                        where + " の random." + key + " に min/max が揃っていない");
                double min = range.getDouble("min");
                double max = range.getDouble("max");
                assertTrue(min <= max, where + " の random." + key + " が min>max: " + min + " > " + max);
            }

            assertTrue(entry.getBoolean("advanced.randomize-grants", false),
                    where + " の advanced.randomize-grants が true でない。false だとサブ4種が"
                            + "全部確定で付き、個体差が『品質だけ』に潰れる");

            ConfigurationSection grants = entry.getConfigurationSection("advanced.grant-chances");
            assertNotNull(grants, where + " に advanced.grant-chances が無い");
            Set<String> grantKeys = new LinkedHashSet<>(grants.getKeys(false));
            assertEquals(4, grantKeys.size(), where + " の grant-chances が4件でない: " + grantKeys);
            assertFalse(grantKeys.contains(thread.primary()),
                    where + " の主ステ(" + thread.primary() + ")が grant-chances に載っている。"
                            + "主ステが確率で付かない個体が出ると、名前どおりの効果を持たないスレッドになる");

            Set<String> expectedGrants = new LinkedHashSet<>(randomKeys);
            expectedGrants.remove(thread.primary());
            assertEquals(expectedGrants, grantKeys,
                    where + " の grant-chances が random のサブ4種と一致しない(random に無いキーを"
                            + "確率付与しても何も起きない)");
            for (String key : grantKeys) {
                assertEquals(GRANT_CHANCE, grants.getDouble(key), 1e-9,
                        where + " の grant-chances." + key + " が 0.45 でない(45件で揃える)");
            }

            assertFalse(entry.getBoolean("offhand-stats-apply", false),
                    where + " の offhand-stats-apply が true。スレッド自体をオフハンドに持つだけで"
                            + "ステが乗る(装着させる意味が消える)");
        }
    }

    @Test
    @DisplayName("主ステにバニラ属性へ投影されるキーを使っていない(手に持つだけで効く穴)")
    void primaryStatIsNeverProjectedToAVanillaAttribute() {
        Set<String> projected = AttributeProjection.defaults().entries().keySet().stream()
                .map(StatKeys::canonical)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertFalse(projected.isEmpty(), "属性投影テーブルが空(テストの前提が壊れている)");

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            String canonical = StatKeys.canonical(declared.getValue().primary());
            if (projected.contains(canonical)) {
                offenders.add(declared.getValue().name() + "→" + declared.getValue().primary());
            }
        }
        assertEquals(List.of(), offenders,
                "スレッド個体は生成時に ItemFactory#stamp → ItemAssembler#assemble を通るので、"
                        + "投影対象キーを持たせると『装着せず手に持つだけでバニラ属性が付く』: " + offenders);
    }

    @Test
    @DisplayName("主ステが ATTRIBUTE チャネルでない(装着スレッドの経路では無言で死ぬ)")
    void primaryStatIsReadableThroughTheAddonChannel() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            if (StatVocabulary.isAttribute(declared.getValue().primary())) {
                offenders.add(declared.getValue().name() + "→" + declared.getValue().primary());
            }
        }
        assertEquals(List.of(), offenders,
                "ATTRIBUTE チャネルのステは PerkAttributeApplier 側の経路で、装着スレッドが流れる"
                        + "AddonCombatStats → PlayerCombatAggregate#totalOf からは読まれない: " + offenders);
    }

    @Test
    @DisplayName("主ステが stats/lore.yml の語彙に存在する(綴り間違いは無言で0になる)")
    void primaryStatExistsInTheLoreVocabulary() {
        File file = new File(LORE);
        assertTrue(file.isFile(), "出荷 lore.yml が見つからない: " + file.getAbsolutePath());
        YamlConfiguration lore = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection stats = lore.getConfigurationSection("stats");
        Set<String> vocabulary = (stats == null ? lore : stats).getKeys(false).stream()
                .map(StatKeys::canonical)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertTrue(vocabulary.size() > 50, "lore.yml の語彙が読めていない(" + vocabulary.size() + "件)");

        List<String> unknown = new ArrayList<>();
        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            if (!vocabulary.contains(StatKeys.canonical(declared.getValue().primary()))) {
                unknown.add(declared.getValue().name() + "→" + declared.getValue().primary());
            }
        }
        assertEquals(List.of(), unknown,
                "語彙に無いステキーは lore にも出ず、消費側も読まないので完全な no-op になる: " + unknown);
    }

    private static ConfigurationSection randomSection(ConfigurationSection entry, Thread thread) {
        ConfigurationSection random = entry.getConfigurationSection("random");
        assertNotNull(random, thread.name() + "のスレッドに random が無い");
        return random;
    }
}
