package com.trinityforge.stats;

import com.trinityforge.combat.AttackStatBridge;
import com.trinityforge.combat.AttackStatKeys;
import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.ComponentDamageCalculator;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.domains.ItemStatsConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>スレッド(CMD 300001-300045)のダメージ寄与が「帯に比例する」ことを固定する</b>
 * 回帰テスト(2026-08-14 案E、2026-08-14 に導出方式へ作り直し)。
 *
 * <h2>何が壊れていたか</h2>
 * スレッドは帯(プレイヤーレベル)に依存しない<b>固定値</b>のステを配る。ところが
 * {@code attack-power} / {@code flat-bonus-damage} / {@code bleed-damage} / {@code fixed-damage} は
 * 「実際のHP量」を足す実数ステなので、装備が弱い低帯ほど相対的に巨大になる。
 * 出荷値では厳選 q15 上限の村の英雄(300010)が {@code attack-power} 1491 を配っていて、
 * これは Lv20 帯の最強剣(攻撃力 699.5)を<b>スレッド1本で超える</b>値だった。
 * 結果として Lv20〜Lv100 の TTK ぶれが実測 65 倍以上になっていた。
 *
 * <h2>ここで何を固定するか(値ではなく性質)</h2>
 * 個別の数値は設計判断なので固定しない。固定するのは
 * <b>「スレッドを1本足したときのダメージ倍率が、帯を変えても同じであること」</b>だけ。
 * 割合系ステ({@code percent-bonus-damage} / {@code crit-chance} / {@code crit-damage} /
 * {@code penetration} / {@code bleed-chance} …)はどれも武器由来の値に掛かるので、
 * 武器の攻撃力を 188 倍にしても倍率は<b>厳密に</b>変わらない。実数系ステを1つでも混ぜると即座にずれる。
 *
 * <h2>取りこぼしの塞ぎ方(2026-08-14 の作り直し)</h2>
 * 旧版の {@code expectedDamagePerHit} は {@link AttackStats} を<b>手で位置引数に並べて</b>組んでおり、
 * 「モデルが読むキー」は事実上の固定リストだった。{@link AttackStatKeys} にフィールドが増えても
 * モデル側が対応しなければ<b>そのキーは無視され、両帯で倍率が一致して素通り</b>する。
 * 件数10のピン留め({@code modelCoversEveryDamageInput})では追加と削除が同時に起きると気づけない。
 * そこで:
 * <ol>
 *   <li>モデルは<b>本物の {@link AttackStatBridge}</b> を通す。ブリッジが読むキーは全部
 *       自動的にモデルへ入るので、{@link AttackStatKeys} が育っても保守不要になる。
 *       {@link #everyBridgedAttackKeyActuallyMovesTheModel()} が
 *       「レコード要素を1つずつ揺らして期待ダメージが動くか」を<b>レコードから導出して</b>確認する
 *       (件数ではなく実挙動のピン留め)。</li>
 *   <li>ブリッジを通らない消費者({@code attack-power} は {@code CombatListener}、
 *       {@code bleed-*} は {@code BleedService})を将来の新キーまで含めて捕まえるため、
 *       {@link #noThreadCarriesAnAbsoluteAttackStat()} が
 *       「{@link StatVocabulary} で ATTACK チャネル、かつ出荷 {@code stats/lore.yml} の
 *       {@code format} が {@code FLAT}(=実数)」のステがスレッド枠に現れたら落とす。
 *       <b>許可リストではなく未知キーの検出</b>で、lore.yml に載っていないキーも「分類不能」として落とす。</li>
 * </ol>
 *
 * <h2>帯目標ガードの作り直し(2026-08-14 第2波)</h2>
 * 初版の {@code percentBonusDamageContributionMatchesTheBandTargets} は
 * <b>実装で裏の取れていない定数を2つ</b>置いていた:
 * <ul>
 *   <li>{@code THREAD_SET_PERCENT_BONUS = 0.05} を「セット効果の合計」として1つだけ足していた。
 *       実際の {@code ThreadSetConfig#cumulativeBonus} は<b>しきい値 &le; N の全ティアを合算</b>し、
 *       しかも {@code percent-bonus-damage} を配るセットは村の英雄(0.05/0.08)だけでなく
 *       {@code mana_regen}(0.04/0.06)と {@code night_vision}(0.03/0.05)もある。
 *       1種類ぶんの定数では最良編成を再現できない。</li>
 *   <li>{@code MAX_COPIES_PER_TYPE = 4} を強制するコードは無い。上限は
 *       <b>装備1点あたり</b>({@code ThreadGui} の重複/最大積載チェックが {@code threads.yml} の
 *       {@code stackable}/{@code max} を見る)で、プレイヤー全体では「1点あたりの上限 × 装着先の数」。
 *       {@code stackable: true, max: 3} の {@code mana_regen} は防具4部位だけでも12本挿さる。</li>
 * </ul>
 * そこで両方とも<b>フォークの yml と実装から導出</b>し、しきい値をまたぐ不連続を取りこぼさないよう
 * 最良編成を有界ナップサックで厳密に解く形にした。フォークは {@code .gitignore} 対象なので、
 * 持っていない環境では item-stats.yml だけで組んだ<b>下限側</b>のモデルだけが走る(空回りはしない)。
 *
 * <p><b>言えること / 言えないこと(正直な限界)</b>: 新しい実数ダメージステが増えても、
 * それが ATTACK チャネルに登録され lore.yml で FLAT と宣言される限り自動で捕まる。
 * 逆に「語彙にも lore にも登録せずスレッドへ書く」ケースは (2) が
 * 「lore.yml に format が無い = 分類不能」として落とすので、これも捕まる。
 * 捕まらないのは「PERCENT と宣言しておきながら実際は実数量として消費される」ステだけで、
 * これは宣言そのものが嘘なので lore 表示も同時に壊れる(=別のテストの守備範囲)。
 */
class ShippedThreadBandIndependenceTest {

    private static final String LORE_PATH = "stats/lore.yml";

    /** 厳選の上限品質(quality.yml の最大品質)。 */
    private static final int MAX_QUALITY = 15;

    /** 比較に使う2帯の武器攻撃力(Lv20 帯の最強剣 / Lv100 帯の最強剣。tmp の武器表より)。 */
    private static final double LOW_BAND_WEAPON = 699.5;
    private static final double HIGH_BAND_WEAPON = 131567.5;

    /** 武器側の会心・出血の代表値。値そのものは結論に効かない(倍率の比を見るだけ)。 */
    private static final double WEAPON_CRIT_CHANCE = 0.2;
    private static final double WEAPON_CRIT_DAMAGE = 0.5;
    private static final double WEAPON_BLEED_CHANCE = 0.16;
    /** 出荷武器の bleed-damage は攻撃力のおよそ1割で、帯とともに伸びる(NETHERITE_HOE#52 など)。 */
    private static final double WEAPON_BLEED_RATIO = 0.1;
    /** combat/damage.yml の bleed.ticks。出血1回の適用回数。 */
    private static final double BLEED_TICKS = 5.0;

    /**
     * モデルの防御側。どちらも帯に比例する形なので倍率のスケール不変性は壊れない
     * (割合はそのまま、守備力は攻撃力比)。0 にすると penetration がモデル上で完全に死ぬ。
     */
    private static final double DEFENDER_DEFENSE_RATE = 0.4;
    private static final double DEFENDER_FLAT_DEFENSE_RATIO = 0.5;

    /** 帯を 188 倍変えても倍率がこの範囲でしか動かないこと。割合系だけなら誤差は 1e-12 未満。 */
    private static final double TOLERANCE = 1.0e-6;

    /** 節ごと消えたことに気づくための下限(45種 - 空のスレッド1種)。 */
    private static final int MIN_EXPECTED_THREADS = 44;

    /**
     * モデルが解釈するダメージ入力キー(canonical)。
     * {@link AttackStatKeys} 側は<b>レコード要素から導出</b>し(手で並べない)、そこに
     * ブリッジを通らない3キー(攻撃力 + 出血2種)を足す。
     */
    private static Set<String> modelledDamageInputs() {
        Set<String> keys = new LinkedHashSet<>(bridgedAttackKeys());
        // AttackStats に入らないがダメージ量を決める3キー:
        //   attack-power は武器の基本ダメージ(defaultDamage)を置き換える。
        //   bleed-chance / bleed-damage は BleedService が読む DoT。
        keys.add(StatKeys.canonical("attack-power"));
        keys.add(StatKeys.canonical("bleed-chance"));
        keys.add(StatKeys.canonical("bleed-damage"));
        return Set.copyOf(keys);
    }

    /**
     * {@link AttackStatKeys#DEFAULT} が持つキー名を<b>レコード要素から</b>読み出す。
     * フィールドが増減しても自動追随するので、ここが固定リストにならない。
     */
    private static Set<String> bridgedAttackKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (RecordComponent component : AttackStatKeys.class.getRecordComponents()) {
            try {
                Object value = component.getAccessor().invoke(AttackStatKeys.DEFAULT);
                assertTrue(value instanceof String,
                        "AttackStatKeys の要素 " + component.getName() + " が String でない");
                keys.add(StatKeys.canonical((String) value));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("AttackStatKeys の要素を読めない: " + component.getName(), ex);
            }
        }
        return keys;
    }

    @Test
    @DisplayName("モデルは AttackStatKeys の全要素を実際に読んでいる(レコードから導出・件数ピン留めしない)")
    void everyBridgedAttackKeyActuallyMovesTheModel() {
        double bare = expectedDamagePerHit(LOW_BAND_WEAPON, Map.of());
        TreeMap<String, String> ignored = new TreeMap<>();
        for (String key : bridgedAttackKeys()) {
            // damage-modifier だけは「未設定=1.0」が中立なので、揺らすなら 1 以外にする。
            double probe = key.equals(StatKeys.canonical(AttackStatKeys.DAMAGE_MODIFIER)) ? 2.0 : 0.37;
            double moved = expectedDamagePerHit(LOW_BAND_WEAPON, Map.of(key, probe));
            if (Math.abs(moved - bare) <= 1.0e-9) {
                ignored.put(key, "揺らしても期待ダメージが " + bare + " のまま");
            }
        }
        assertEquals(Map.of(), ignored,
                "AttackStatKeys に載っているのにモデルが読んでいないキーがある。"
                        + "expectedDamagePerHit は AttackStatBridge を通しているので、"
                        + "ここが落ちるのはブリッジ側の未配線か、ダメージ式がそのキーを無視している場合:"
                        + " " + ignored);
    }

    @Test
    @DisplayName("スレッドは ATTACK チャネルの実数(FLAT)ステを配らない — 未知キーもここで落ちる")
    void noThreadCarriesAnAbsoluteAttackStat() {
        Map<String, String> loreFormats = shippedLoreFormats();
        Map<Integer, Map<String, Double>> threads = threadStats();
        assertTrue(threads.size() >= MIN_EXPECTED_THREADS,
                "スレッドが " + threads.size() + " 件しか読めていない(検査が空回りしている)");

        TreeMap<String, String> offenders = new TreeMap<>();
        for (Map.Entry<Integer, Map<String, Double>> thread : threads.entrySet()) {
            for (Map.Entry<String, Double> stat : thread.getValue().entrySet()) {
                String key = stat.getKey();
                String format = loreFormats.get(key);
                if (format == null) {
                    offenders.put(thread.getKey() + "|" + key,
                            "stats/lore.yml に format が無く実数か割合か判別できない(未知キー)");
                    continue;
                }
                if (StatVocabulary.channelOf(key) == StatVocabulary.Channel.ATTACK
                        && "FLAT".equalsIgnoreCase(format)) {
                    offenders.put(thread.getKey() + "|" + key,
                            "ATTACK チャネルの FLAT(実数)ステ = " + stat.getValue());
                }
            }
        }

        assertEquals(Map.of(), offenders,
                "スレッドが実数のダメージステを配っている(または分類不能なステを配っている)。"
                        + "スレッドは帯非依存の固定値なので、実数を配ると装備が弱い低帯だけ極端に強くなる。"
                        + "割合系(percent-bonus-damage / crit-chance / crit-damage / penetration /"
                        + " bleed-chance)へ振り替えること: " + offenders);
    }

    /**
     * 帯ごとの<b>防具</b>スレッド枠数と、割合ダメージ寄与の設計目標。
     * 枠数は出荷 item-stats.yml の防具の {@code thread-slots}(帯ごとに 2/3/4)× 防具4部位。
     */
    private static final Map<Integer, Integer> BAND_SLOTS = Map.of(20, 8, 60, 12, 100, 16);
    private static final Map<Integer, Double> BAND_TARGET_PERCENT_BONUS = Map.of(20, 0.39, 60, 0.49, 100, 0.59);

    /**
     * 装着先の部位数。同一種の本数上限は<b>装備1点あたり</b>で決まる
     * ({@code ThreadGui} の重複/最大積載チェック)ので、プレイヤー全体の上限は
     * 「1点あたりの上限 × 装着先の数」になる。
     *
     * <p><b>正直な過小評価</b>: 実際の {@code ArmorManaListener#collectThreadsInto} は
     * 防具4部位に加えて<b>メインハンドとオフハンドも</b>数える(武器にも {@code thread-slots} が
     * 1〜3 ある)。ここを 6 にすると {@link #BAND_SLOTS}(防具枠だけ)と噛み合わなくなるため、
     * モデルは防具4部位に揃えてある ── つまりここで出る最悪値は<b>真の最悪値より小さい</b>。
     */
    private static final int ARMOR_PIECES = 4;

    /** 設計目標からのずれの許容(ポイント)。 */
    private static final double BAND_TOLERANCE = 0.01;

    /** ArsPaper フォークの参照先。{@code .gitignore} 対象なのでクリーンな worktree には存在しない。 */
    private static final Path FORK_MAIN =
            Path.of("..", "fork-handoff", "arspaper", "fork", "src", "main");

    /** {@code ThreadType} の宣言 {@code NAME("id", "表示名", CMD, ...)} から id ↔ CMD を拾う。 */
    private static final Pattern THREAD_TYPE_DECL =
            Pattern.compile("\\(\\s*\"([a-z_]+)\"\\s*,\\s*\"[^\"]*\"\\s*,\\s*(3000\\d\\d)\\s*,");

    /** 最良編成の探索結果(合計寄与と、その内訳)。 */
    private record Optimum(double total, String build) {
    }

    @Test
    @DisplayName("帯ごとの割合ダメージ寄与が設計目標(+39%/+49%/+59%)どおり — 本数上限とセット効果を実仕様から導出して解く")
    void percentBonusDamageContributionMatchesTheBandTargets() throws IOException {
        Map<Integer, Map<String, Double>> threads = threadStats();
        assertTrue(threads.size() >= MIN_EXPECTED_THREADS,
                "スレッドが " + threads.size() + " 件しか読めていない(検査が空回りしている)");
        String key = StatKeys.canonical(AttackStatKeys.PERCENT_BONUS_DAMAGE);

        Map<Integer, Double> perCopy = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Double>> thread : threads.entrySet()) {
            double value = thread.getValue().getOrDefault(key, 0.0);
            if (value > 0) {
                perCopy.put(thread.getKey(), value);
            }
        }
        assertTrue(perCopy.size() * ARMOR_PIECES >= 16,
                "percent-bonus-damage を配るスレッドが少なすぎて最大枠(16)を埋められない: " + perCopy.size());

        // (1) フォーク非依存の下限側モデル。同一種の上限は 1部位1本 × 4部位 = 4、セット効果は 0。
        //     2026-08-18 以降 threads.yml の既定は「重複可・1装備 max 2」なので、この 4 は
        //     実仕様(最大 8)より小さい**意図的な下限**(明示的に max: 1 を書いた種の値)。
        //     ここが超えていたら実仕様でも必ず超えるので、フォークが無い環境でも空回りしない。
        Map<Integer, Integer> floorCaps = new LinkedHashMap<>();
        perCopy.keySet().forEach(cmd -> floorCaps.put(cmd, ARMOR_PIECES));
        TreeMap<String, String> offenders = new TreeMap<>();
        collectOverruns("item-stats のみ", perCopy, floorCaps, Map.of(), offenders);

        // (2) 実仕様モデル。同一種の上限は threads.yml の stackable/max から、
        //     セット効果は thread-sets.yml を ThreadSetConfig#cumulativeBonus と同じ累積規則で足す。
        Map<Integer, String> idByCmd = threadIdByCmd();
        if (idByCmd != null) {
            Map<String, Integer> capById = perItemCapById();
            Map<String, NavigableMap<Integer, Double>> setsById = percentBonusSetTiers();
            Map<Integer, Integer> realCaps = new LinkedHashMap<>();
            Map<Integer, NavigableMap<Integer, Double>> realSets = new LinkedHashMap<>();
            for (Integer cmd : perCopy.keySet()) {
                String id = idByCmd.get(cmd);
                assertNotNull(id, "CMD " + cmd + " に対応する ThreadType が見つからない");
                int perItem = capById.getOrDefault(id, 1);
                realCaps.put(cmd, (int) Math.min((long) perItem * ARMOR_PIECES, Integer.MAX_VALUE));
                NavigableMap<Integer, Double> tiers = setsById.get(id);
                if (tiers != null) {
                    realSets.put(cmd, tiers);
                }
            }
            collectOverruns("実仕様", perCopy, realCaps, realSets, offenders);
        }

        assertEquals(Map.of(), offenders,
                "厳選編成での割合ダメージ寄与が帯の設計目標を超えている。"
                        + "上限は『装備1点あたりの上限(threads.yml の stackable/max) × 部位数』で、"
                        + "セット効果は『しきい値 N 以下の全ティアを合算』(ThreadSetConfig#cumulativeBonus)。"
                        + "percent-bonus-damage のセット効果は hero_of_the_village だけでなく "
                        + "mana_regen / night_vision も配るので、1種類ぶんの定数では足りない: " + offenders);
    }

    /** 各帯について最良編成を解き、目標を超えた帯だけ {@code offenders} へ積む。 */
    private static void collectOverruns(String label, Map<Integer, Double> perCopy,
                                        Map<Integer, Integer> caps,
                                        Map<Integer, NavigableMap<Integer, Double>> sets,
                                        TreeMap<String, String> offenders) {
        for (Map.Entry<Integer, Integer> band : new TreeMap<>(BAND_SLOTS).entrySet()) {
            int slots = band.getValue();
            Optimum best = bestPercentBonus(slots, perCopy, caps, sets);
            double target = BAND_TARGET_PERCENT_BONUS.get(band.getKey());
            if (best.total() > target + BAND_TOLERANCE) {
                offenders.put(label + "/Lv" + band.getKey(),
                        String.format("枠%d で +%.1f%% (目標 +%.1f%%、超過 %+.1fpt) 編成=%s",
                                slots, best.total() * 100, target * 100,
                                (best.total() - target) * 100, best.build()));
            }
        }
    }

    /**
     * 枠数 {@code slots} を使い切って percent-bonus-damage の合計を最大化する編成を厳密に解く
     * (種ごとに「何本挿すか」を選ぶ有界ナップサック)。
     * セット効果はしきい値をまたぐと不連続に増えるので、単純な降順詰めでは最良編成を取り逃がす。
     */
    private static Optimum bestPercentBonus(int slots, Map<Integer, Double> perCopy,
                                            Map<Integer, Integer> caps,
                                            Map<Integer, NavigableMap<Integer, Double>> sets) {
        double[] dp = new double[slots + 1];
        String[] how = new String[slots + 1];
        java.util.Arrays.fill(dp, Double.NEGATIVE_INFINITY);
        dp[0] = 0.0;
        how[0] = "";
        for (Map.Entry<Integer, Double> entry : perCopy.entrySet()) {
            int cmd = entry.getKey();
            double value = entry.getValue();
            NavigableMap<Integer, Double> tiers = sets.get(cmd);
            int cap = Math.min(caps.getOrDefault(cmd, 1), slots);
            if (cap <= 0) continue;
            double[] next = dp.clone();
            String[] nextHow = how.clone();
            for (int used = 0; used <= slots; used++) {
                if (dp[used] == Double.NEGATIVE_INFINITY) continue;
                for (int n = 1; n <= cap && used + n <= slots; n++) {
                    double gain = n * value + cumulative(tiers, n);
                    if (dp[used] + gain > next[used + n] + 1.0e-12) {
                        next[used + n] = dp[used] + gain;
                        nextHow[used + n] = how[used] + cmd + "x" + n
                                + String.format("(+%.3f)", gain) + " ";
                    }
                }
            }
            dp = next;
            how = nextHow;
        }
        int bestAt = 0;
        for (int i = 0; i <= slots; i++) {
            if (dp[i] > dp[bestAt]) bestAt = i;
        }
        return new Optimum(dp[bestAt], how[bestAt]);
    }

    /** {@code ThreadSetConfig#cumulativeBonus} と同じ規則: しきい値 &le; count の全ティアを合算。 */
    private static double cumulative(NavigableMap<Integer, Double> tiers, int count) {
        if (tiers == null || count <= 0) return 0.0;
        double total = 0.0;
        for (double value : tiers.headMap(count, true).values()) {
            total += value;
        }
        return total;
    }

    /**
     * フォークの {@code ThreadType} から CMD → スレッド id。フォークが無い環境では {@code null}
     * (このリポジトリの規約: {@code SummonedMobPdcKeyContractTest} と同じく突き合わせは持っている環境だけ)。
     */
    private static Map<Integer, String> threadIdByCmd() throws IOException {
        Path source = FORK_MAIN.resolve(Path.of("java", "com", "arspaper", "item", "ThreadType.java"));
        if (!Files.isRegularFile(source)) {
            return null;
        }
        Map<Integer, String> out = new LinkedHashMap<>();
        Matcher matcher = THREAD_TYPE_DECL.matcher(Files.readString(source, StandardCharsets.UTF_8));
        while (matcher.find()) {
            out.put(Integer.parseInt(matcher.group(2)), matcher.group(1));
        }
        assertTrue(out.size() >= MIN_EXPECTED_THREADS,
                "ThreadType から CMD を " + out.size() + " 件しか読めていない(宣言の形が変わった?)");
        return out;
    }

    /**
     * フォークの {@code threads.yml} から「装備1点あたりの同一種の上限」。
     *
     * <p><b>2026-08-18</b>: フォーク側の既定が反転した(ユーザー確定要件「同一のスレッドを重複で
     * 入れられるようにしてほしい」)。{@code ThreadConfig#isStackable} は未記載を
     * {@code ThreadApplicationPolicy.DEFAULT_STACKABLE}(= true)、{@code #getMaxStack} は未記載を
     * {@code DEFAULT_MAX_STACK}(= 2)として読む ── かつては「未記載 = 1本」「max 未記載 = 無制限」
     * だった。ここの既定をフォークに合わせ忘れると、モデルが実際より弱い編成しか作らず
     * <b>帯目標の超過を緑で通す</b>(=検査の無効化)。
     */
    private static final boolean FORK_DEFAULT_STACKABLE = true;
    private static final int FORK_DEFAULT_MAX_STACK = 2;

    private static Map<String, Integer> perItemCapById() {
        ConfigurationSection threads = YamlConfiguration
                .loadConfiguration(FORK_MAIN.resolve(Path.of("resources", "threads.yml")).toFile())
                .getConfigurationSection("threads");
        assertNotNull(threads, "フォークの threads.yml に threads: が無い");
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String id : threads.getKeys(false)) {
            ConfigurationSection entry = threads.getConfigurationSection(id);
            boolean stackable = entry != null && entry.getBoolean("stackable", FORK_DEFAULT_STACKABLE);
            out.put(id, stackable
                    ? (entry == null ? FORK_DEFAULT_MAX_STACK : entry.getInt("max", FORK_DEFAULT_MAX_STACK))
                    : 1);
        }
        return out;
    }

    /** フォークの {@code thread-sets.yml} から「スレッド id → (しきい値 → percent-bonus-damage)」。 */
    private static Map<String, NavigableMap<Integer, Double>> percentBonusSetTiers() {
        ConfigurationSection root = YamlConfiguration
                .loadConfiguration(FORK_MAIN.resolve(Path.of("resources", "thread-sets.yml")).toFile())
                .getConfigurationSection("thread-sets");
        assertNotNull(root, "フォークの thread-sets.yml に thread-sets: が無い");
        String wanted = StatKeys.canonical(AttackStatKeys.PERCENT_BONUS_DAMAGE);
        Map<String, NavigableMap<Integer, Double>> out = new LinkedHashMap<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection thresholds = root.getConfigurationSection(id + ".thresholds");
            if (thresholds == null) continue;
            NavigableMap<Integer, Double> tiers = new TreeMap<>();
            for (String countKey : thresholds.getKeys(false)) {
                int count;
                try {
                    count = Integer.parseInt(countKey.trim());
                } catch (NumberFormatException notInt) {
                    continue;
                }
                if (count <= 0) continue;
                ConfigurationSection stats = thresholds.getConfigurationSection(countKey);
                if (stats == null) continue;
                for (String stat : stats.getKeys(false)) {
                    if (StatKeys.canonical(stat).equals(wanted)) {
                        tiers.merge(count, stats.getDouble(stat), Double::sum);
                    }
                }
            }
            if (!tiers.isEmpty()) {
                out.put(id, tiers);
            }
        }
        return out;
    }

    @Test
    @DisplayName("スレッド1本のダメージ倍率が帯によらず一定(実数ダメージ系が混ざると落ちる)")
    void everyThreadScalesWithTheEquipmentBand() {
        Map<Integer, Map<String, Double>> threads = threadStats();
        assertTrue(threads.size() >= MIN_EXPECTED_THREADS,
                "スレッドが " + threads.size() + " 件しか読めていない。CMD 帯(300001-300045)か"
                        + "節の構造が変わっていないか確認すること(期待: " + MIN_EXPECTED_THREADS + " 件以上)");

        TreeMap<String, String> offenders = new TreeMap<>();
        for (Map.Entry<Integer, Map<String, Double>> entry : threads.entrySet()) {
            double low = uplift(LOW_BAND_WEAPON, entry.getValue());
            double high = uplift(HIGH_BAND_WEAPON, entry.getValue());
            if (Math.abs(low - high) > TOLERANCE) {
                offenders.put(String.valueOf(entry.getKey()),
                        String.format("Lv20帯 x%.4f / Lv100帯 x%.4f (差 %.4f) 内訳=%s",
                                low, high, low - high, damageStatsOf(entry.getValue())));
            }
        }

        assertEquals(Map.of(), offenders,
                "帯によってダメージ倍率が変わるスレッドがある = 実数(HP量)のダメージステを配っている。"
                        + "スレッドは帯非依存の固定値なので、実数を配ると低帯だけ極端に強くなる"
                        + "(装備が伸びても値が伸びないため)。割合系("
                        + "percent-bonus-damage / crit-chance / crit-damage / penetration / bleed-chance)"
                        + "へ振り替えること: " + offenders);
    }

    /** スレッド1本を足したときのダメージ倍率(素の武器 = 1.0)。 */
    private static double uplift(double weaponAttack, Map<String, Double> thread) {
        double bare = expectedDamagePerHit(weaponAttack, Map.of());
        assertTrue(bare > 0, "素の武器のダメージが0以下(モデルの前提が壊れている)");
        return expectedDamagePerHit(weaponAttack, thread) / bare;
    }

    /**
     * 1撃あたりの期待ダメージ = 本物のパイプライン({@link AttackStatBridge} →
     * {@link ComponentDamageCalculator})の会心期待値 + 出血の期待値。
     *
     * <p><b>ブリッジを通すのが要点</b>: {@link AttackStats} を位置引数で組むと
     * {@link AttackStatKeys} が育ったときにモデルだけ取り残される。ここでは武器側の代表値と
     * スレッドのステを1つのマップへ合流させ、本番と同じブリッジで {@link AttackStats} を作る。
     *
     * <p><b>防御側を 0 にしない理由</b>: {@code penetration} は防御率%を削るステなので、
     * {@link DefenseStats#NONE} だと<b>モデルがそのキーを読んでいても値がまったく効かない</b>
     * (= 実質モデルから欠けているのと同じ)。防御率%(割合)と守備力(攻撃力に比例)を置くことで
     * penetration が効くようになり、かつ両方とも帯に比例するのでスケール不変性は保たれる
     * (守備力の 0.5 倍は {@code F(L)=0.5*A(L)} の較正に合わせた値)。
     */
    private static double expectedDamagePerHit(double weaponAttack, Map<String, Double> thread) {
        Map<String, Double> merged = new LinkedHashMap<>();
        merged.put(StatKeys.canonical(AttackStatKeys.CRIT_CHANCE), WEAPON_CRIT_CHANCE);
        merged.put(StatKeys.canonical(AttackStatKeys.CRIT_DAMAGE), WEAPON_CRIT_DAMAGE);
        thread.forEach((key, value) -> merged.merge(StatKeys.canonical(key), value, Double::sum));

        AttackStats attack = AttackStatBridge.bridge(merged, AttackStatKeys.DEFAULT)
                .withDefaultDamage(weaponAttack + stat(thread, "attack-power"));
        DefenseStats defense = new DefenseStats(
                DEFENDER_DEFENSE_RATE, 0.0, 0.0, weaponAttack * DEFENDER_FLAT_DEFENSE_RATIO, 0.0);
        double normal = ComponentDamageCalculator.compute(attack, defense, false, 0.0, 0.5);
        double crit = ComponentDamageCalculator.compute(attack, defense, true, 0.0, 0.5);
        double hit = (1 - attack.critChance()) * normal + attack.critChance() * crit;

        double bleed = (WEAPON_BLEED_CHANCE + stat(thread, "bleed-chance"))
                * (weaponAttack * WEAPON_BLEED_RATIO + stat(thread, "bleed-damage"))
                * BLEED_TICKS;
        return hit + bleed;
    }

    private static double stat(Map<String, Double> thread, String key) {
        return thread.getOrDefault(StatKeys.canonical(key), 0.0);
    }

    private static String damageStatsOf(Map<String, Double> thread) {
        Map<String, Double> shown = new LinkedHashMap<>();
        for (String key : modelledDamageInputs()) {
            if (thread.containsKey(key)) shown.put(key, thread.get(key));
        }
        return shown.toString();
    }

    /** 出荷 {@code stats/lore.yml} の canonical ステキー → {@code format}(FLAT/PERCENT/INTEGER)。 */
    private static Map<String, String> shippedLoreFormats() {
        try (InputStream in = ShippedThreadBandIndependenceTest.class.getClassLoader()
                .getResourceAsStream(LORE_PATH)) {
            assertNotNull(in, "出荷 " + LORE_PATH + " が classpath に無い");
            ConfigurationSection stats = YamlConfiguration
                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getConfigurationSection("stats");
            assertNotNull(stats, LORE_PATH + " に stats: が無い");
            Map<String, String> formats = new LinkedHashMap<>();
            for (String key : stats.getKeys(false)) {
                String format = stats.getString(key + ".format");
                if (format != null) {
                    formats.put(StatKeys.canonical(key), format);
                }
            }
            assertTrue(formats.size() >= 50,
                    LORE_PATH + " から format を " + formats.size() + " 件しか読めていない(検査が空回りしている)");
            return formats;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * CMD → 厳選 q15・上限ロールのステ(canonical キー)。
     * 実プレイヤーが到達できる最強個体を見るので、サブの付与確率は掛けない(厳選で引き直せるため)。
     */
    private static Map<Integer, Map<String, Double>> threadStats() {
        File file = new File("src/main/resources/" + ItemStatsConfig.PATH);
        assertTrue(file.isFile(), "出荷 item-stats.yml が見つからない: " + file.getAbsolutePath());
        ConfigurationSection items = YamlConfiguration.loadConfiguration(file).getConfigurationSection("items");
        assertNotNull(items, "item-stats.yml に items: が無い");

        Map<Integer, Map<String, Double>> out = new LinkedHashMap<>();
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
            if (entry == null) continue;

            Map<String, Double> stats = new LinkedHashMap<>();
            ConfigurationSection fixed = entry.getConfigurationSection("fixed");
            if (fixed != null) {
                for (String stat : fixed.getKeys(false)) {
                    stats.merge(StatKeys.canonical(stat), fixed.getDouble(stat), Double::sum);
                }
            }
            ConfigurationSection perQuality = entry.getConfigurationSection("per-quality");
            ConfigurationSection random = entry.getConfigurationSection("random");
            if (random != null) {
                for (String stat : random.getKeys(false)) {
                    ConfigurationSection range = random.getConfigurationSection(stat);
                    if (range == null) continue;
                    double step = perQuality == null ? 0.0 : perQuality.getDouble(stat, 0.0);
                    stats.merge(StatKeys.canonical(stat),
                            step * MAX_QUALITY + range.getDouble("max"), Double::sum);
                }
            }
            if (!stats.isEmpty()) out.put(cmd, stats);
        }
        return out;
    }

    @Test
    @DisplayName("スレッドを素手で握っても帯の最強剣を超えない(ダメージ量だけの検査。全ステ遮断は別テスト)")
    void bareHandedThreadCannotOutDamageTheBandsWeapon() {
        // 【守備範囲の注意】この検査は「素手 + スレッド1本のダメージ量」しか見ない。
        // 「手に持つだけでステが乗る」穴そのもの(守備系・採取系を含む全ステ)を塞いだかどうかは
        // ThreadSocketedOnlyAggregationTest が見る — 旧テスト名
        // (「手に持つだけでは素手のダメージ倍率しか動かない」)は守備範囲を過大に表現しており、
        // 実際には phys-flat-defense / damage-reduction / mining-fortune 等が素通りしていた。
        java.util.List<String> offenders = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Map<String, Double>> entry : threadStats().entrySet()) {
            // 素手 = defaultDamage 1.0(base-stats.yml の attack-power が 0 なのでバニラ値が残る)。
            double bareHandWithThread = expectedDamagePerHit(1.0, entry.getValue());
            double lowBandSword = expectedDamagePerHit(LOW_BAND_WEAPON, Map.of());
            if (bareHandWithThread >= lowBandSword) {
                offenders.add(entry.getKey() + ": 素手+スレッド " + String.format("%.1f", bareHandWithThread)
                        + " >= Lv20帯の最強剣 " + String.format("%.1f", lowBandSword)
                        + " 内訳=" + damageStatsOf(entry.getValue()));
            }
        }
        assertEquals(java.util.List.of(), offenders,
                "スレッドを素手で握るだけで帯の最強剣以上のダメージが出る。"
                        + "実数ダメージステを持たせてはいけない: " + offenders);
    }
}
