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
 * 出荷 {@code stats/item-stats.yml} のスレッド80種(CMD 300001-300080)の厳選定義を固定する
 * (2026-08-03 ユーザー指摘「一部スレッドが名称と効果が一致していない(マナ増幅のスレッドなど)」)。
 *
 * <h2>なぜ机上で落とす必要があるのか</h2>
 * <p>スレッドの「性格」は<b>どこにも宣言されていない</b>。名前と CustomModelData は
 * ArsPaper フォークの {@code ThreadType} enum / {@code threads.yml}、効果は TF 側のこの yml、
 * セット効果はフォークの {@code thread-sets.yml} という<b>3ファイルに分かれていて相互参照が無い</b>。
 * そのため「マナ増幅のスレッドの主ステが会心ダメージ」のような食い違いが起きても、
 * <b>起動もテストも通り、ゲーム内で数値を見比べるまで誰も気づかない</b>。
 * 実際に 2026-08-02 の40種化ではテーマ割り当てが名称とほぼ無関係になっていて、
 * ユーザーからの指摘で初めて判明した。
 *
 * <p>そこで<b>この表が主ステの唯一の宣言</b>になる。スレッドを増やす・テーマを変えるときは
 * ここを直すのが先で、yml だけ直すとこのテストが落ちる。
 *
 * <h2>2026-08-21: スレッドを3種類に分けた(ユーザー指示)</h2>
 * <p>指示は「非戦闘系効果のスレッド(常時効果系含む)から戦闘関連ステータスの効果を削除」。
 * それまでは<b>全45種が一律で「主ステ + 戦闘サブ4種」</b>だったため、採取・制作・マナのスレッドを
 * 挿すだけで会心や貫通が付いてきていた ── 名前と効果が食い違うだけでなく、戦闘スレッドを選ぶ
 * 意味そのものが消えていた。そこで {@link Kind} で3種類に分け、規約を種類ごとに変える。
 *
 * <ul>
 *   <li>{@link Kind#COMBAT} … 戦闘そのものが正体のスレッド。
 *       主ステ1件 + {@code random} 2件 + サブ1種を 0.45 で確率付与
 *       (2026-08-25 (W-254) にサブ4種から絞った。下の見出し参照)。</li>
 *   <li>{@link Kind#DOMAIN} … 採取・制作・マナなど非戦闘のスレッド。<b>主ステ(と、名称が2軸を
 *       指す場合のみその2軸)だけ</b>を持ち、{@code advanced} ブロックは持たない
 *       (0.45 の確率付与は戦闘サブ専用の仕組みだったため、サブが消えると置き場所が無い)。</li>
 *   <li>{@link Kind#STATLESS} … 常時ポーション効果・飛行・バックパックのスレッド。
 *       <b>ステ節を1つも持たない</b>。名称の効果はフォーク側({@code ThreadType} のポーション効果 /
 *       飛行フラグ / バックパック)が配っており、以前はそこへ「近い戦闘軸」を無理に足していた。</li>
 * </ul>
 *
 * <p>この分類は {@link #nonCombatThreadsCarryNoCombatStats()} が実データで検査する ──
 * 「DOMAIN / STATLESS のスレッドに ATTACK / DEFENSE チャネルのステが1件でもあれば落ちる」。
 * 表を書き換えただけでは通らないので、逆流(誰かが戦闘サブを足し戻す)をここで止められる。
 *
 * <h2>2026-08-25 (W-254): サブ4種 → サブ1種、強ステは主軸専用に</h2>
 * <p>ユーザー確定要件は2つ:
 * <ol>
 *   <li>「各スレッドを特定のステータスにとがらせ、微強化する」</li>
 *   <li>「回避、ダメージ軽減等の強いステータスに尖ったスレッドは種類を2個以下、あって3種類に絞る
 *       (100%達成の防止)。また耐性や守備力、攻撃力などとりあえず感覚でまんべんなくついている
 *       ステータスも減らし、数種類のスレッドにまとめる。現状のどのスレッドでも代用できるは
 *       かえってコンテンツの道を減らしている」</li>
 * </ol>
 * <p>実際、{@code percent-bonus-damage} は34種のうち<b>17種にサブとして</b>ぶら下がっていた ──
 * どのスレッドを挿しても同じステが付くので「このスレッドを選ぶ理由」が主ステ1行しか無かった。
 * そこで<b>サブを1種へ絞り</b>、さらに<b>上限で壊れる割合ステ</b>
 * ({@code dodge-chance} / {@code damage-reduction} / {@code armor-strength} /
 *  {@code phys-resistance} / {@code magic-resistance} / {@code defense-rate} /
 *  {@code percent-bonus-damage})は<b>主軸にしか置かない</b>ことにした。
 * 主軸に置く種類数は {@link #strongStatsAreOnlyEverAPrimaryAxis()} が実データで検査する。
 * <p>主軸の数値は +15%。ただし {@code percent-bonus-damage} を主軸に持つ5種だけは据え置き ──
 * {@code ShippedThreadBandIndependenceTest} の帯目標(Lv100 = +59%)に対して
 * 「1本 3.6% × 枠16」で余裕が 1.4pt しか無く、上げると即座に超過する(実測で確認)。
 *
 * <h2>組み立ての規約(COMBAT のみ。崩すと厳選が機能しなくなる)</h2>
 * <ul>
 *   <li>{@code per-quality:} は<b>主ステ1件だけ</b>(品質0..9で伸びる軸)。</li>
 *   <li>{@code random:} は<b>主ステ + サブ1種</b>の2件。主ステは {@code random} の先頭。</li>
 *   <li>{@code advanced.randomize-grants: true} + {@code grant-chances} でサブ1種を 0.45。
 *       <b>主ステは grant-chances に書かない</b>。</li>
 *   <li>{@code offhand-stats-apply: false}(スレッド自体をオフハンドに持って効かせない)。</li>
 * </ul>
 *
 * <h2>「主ステを grant-chances に 1.0 で書く」を禁じる理由(2026-08-14 に実装で確定)</h2>
 * <b>付与の挙動は変わらない</b>。{@code DerivedItemStats#resolveGrantedKeys} は
 * {@code chance >= 1.0} を短絡して必ず付与し、{@code ItemStatProfile#grantChance} は
 * <b>未記載も 1.0</b> を返すので、書いても書かなくても主ステは必ず付く。
 *
 * <p>変わるのは<b>ロアの色</b>。{@code ItemAssembler} は
 * {@code profile.grantChances().keySet()} を「付与確率つきステ」の集合として
 * {@code LoreComposeRequest} へ渡し、{@code LoreColorRules} が
 * {@code layout.colors.{fixed,roll}.chance-positive/negative} の色へ切り替える。
 * 出荷 {@code stats/lore.yml} は今その色を書いていないので<b>今日は見た目も変わらない</b>が、
 * 誰かが色を足した瞬間に<b>必ず付く主ステが「確率で付くステ」の色で出る</b>ようになる
 * ── 表示が仕様と逆の嘘をつく。
 *
 * <p>つまり「今は無害・意図は読めない」ので、<b>意図が読める側(= 書かない)</b>へ寄せた。
 * {@code grant-chances} は「保証されないステの一覧」であって、確定ステの置き場ではない。
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

    /** サブステの付与確率(COMBAT のみ。平均1.8種が付く)。 */
    private static final double GRANT_CHANCE = 0.45;

    /** スレッドの CMD 帯。増種したらここを伸ばす。 */
    private static final int THREAD_CMD_MIN = 300001;
    private static final int THREAD_CMD_MAX = 300080;

    /** スレッドの種類。規約が種類ごとに違う(クラス Javadoc 参照)。 */
    private enum Kind {
        /** 戦闘そのものが正体。主ステ + 戦闘サブ1種(2026-08-25 に4種から絞った)。 */
        COMBAT,
        /** 採取・制作・マナなど非戦闘。主ステ(名称が2軸を指すならその2軸)だけ。 */
        DOMAIN,
        /** 効果をフォーク側(ポーション/飛行/バックパック)が配るのでステ節を持たない。 */
        STATLESS
    }

    /**
     * CMD → (スレッド名, 主ステ, 種類)。名称に対応する軸を主ステに据えるのがこの表の役目。
     *
     * <p><b>フォークの {@code thread-sets.yml} のしきい値1段目と同じキーに揃えてある</b>。
     * 同じ軸に揃えると「1個挿しただけの効果」と「集めたときの効果」が同じ方向に伸びる。
     * <b>この一致は機械では検査できない</b> ── フォークのリソースは {@code .gitignore} 除外で
     * ワークツリーに存在しないことがあるため、TF 側のテストからは読めない。片方だけ直すと静かにずれる。
     */
    private static final Map<Integer, Thread> THREADS = threads();

    private record Thread(String name, String primary, Kind kind) {
        static Thread domain(String name, String primary) {
            return new Thread(name, primary, Kind.DOMAIN);
        }

        static Thread combat(String name, String primary) {
            return new Thread(name, primary, Kind.COMBAT);
        }

        static Thread statless(String name) {
            return new Thread(name, null, Kind.STATLESS);
        }
    }

    private static Map<Integer, Thread> threads() {
        Map<Integer, Thread> m = new LinkedHashMap<>();
        // --- 効果を持たない「空のスレッド」 ---
        m.put(300001, Thread.statless("空"));
        // --- マナ・魔法系 ---
        m.put(300002, Thread.domain("マナ回復速度上昇", "mana-regen"));
        m.put(300003, Thread.domain("マナ最大値上昇", "mana-bonus"));
        m.put(300012, Thread.domain("被弾マナ回復", "hit-mana-recovery"));
        m.put(300013, Thread.domain("攻撃マナ回復", "damage-mana-recovery"));
        m.put(300014, Thread.domain("詠唱効率", "mana-cost-reduction-percent"));
        m.put(300017, Thread.domain("マナ増幅", "mana-bonus"));
        m.put(300018, Thread.domain("循環", "mana-regen"));
        m.put(300019, Thread.domain("源流節約", "source-cost-reduction"));
        // --- 制作系 ---
        m.put(300020, Thread.domain("匠", "workbench-quality-bonus"));
        m.put(300021, Thread.domain("儀式師", "ritual-quality-bonus"));
        m.put(300022, Thread.domain("倹約", "material-refund-chance"));
        m.put(300023, Thread.domain("解体", "disassembly-return-bonus"));
        m.put(300031, Thread.domain("選書", "enchant-luck"));
        // --- 採取・生活系 ---
        m.put(300024, Thread.domain("豊鉱", "mining-fortune"));
        m.put(300025, Thread.domain("潮読み", "fishing-luck"));
        m.put(300026, Thread.domain("実り", "harvest-extra-drop-chance"));
        m.put(300027, Thread.domain("年輪", "woodcutting-extra-drop-chance"));
        m.put(300029, Thread.domain("研鑽", "skill-exp-bonus"));
        m.put(300033, Thread.domain("持久", "hunger-save-chance"));
        m.put(300034, Thread.domain("美食", "food-restore-bonus"));
        // --- 戦闘に隣接するが効果はドロップ/経験値側(=非戦闘扱い) ---
        m.put(300028, Thread.domain("戦利品", "mob-drop-bonus"));
        m.put(300030, Thread.domain("経験", "vanilla-exp-bonus"));
        m.put(300040, Thread.domain("幸運", "loot-luck"));
        m.put(300041, Thread.domain("調香", "potion-quality-bonus"));
        m.put(300042, Thread.domain("養蜂", "hive-harvest-fortune"));
        m.put(300043, Thread.domain("牧人", "breeding-extra-child-chance"));
        m.put(300044, Thread.domain("鑑識", "mob-drop-quality"));
        m.put(300045, Thread.domain("削岩", "gathering-efficiency"));
        // --- 戦闘系(2026-08-21 以前からの5種) ---
        m.put(300032, Thread.combat("治癒", "health-regen-bonus"));
        m.put(300035, Thread.combat("棘", "reflect-percent"));
        m.put(300036, Thread.combat("昏倒", "stun-chance"));
        m.put(300037, Thread.combat("速攻", "cooldown-reduction"));
        m.put(300038, Thread.combat("射手", "ammo-save-chance"));
        // --- 常時効果系(名称の効果はポーション/飛行/バックパックで配っている) ---
        // 2026-08-21: ここに据えていた「近い戦闘軸」(dodge-chance / crit-chance など)を全部外した。
        // ポーション効果で速くなるスレッドが会心も配る理由が無く、戦闘スレッドと役割が被っていた。
        // 2026-08-22: 空のままだと「常時効果しか無い＝厳選もセット効果も無い」札になるので、
        // 幸運のスレッドと同じ形(per-quality + random の1軸 / セット効果は3個・5個の2段)で
        // 【常時効果に関連する GENERAL 系ステ】を据え直した。戦闘ステは引き続き1件も持たない。
        // ATTRIBUTE 系(move-speed 等)は名前の上では一番近いが、スレッドの寄与経路には
        // 乗らない(下の statfulThreadsNeverApplyFromTheOffhand の javadoc 参照)ので使えない。
        m.put(300004, Thread.domain("迅速", "gathering-efficiency"));           // 速く動く=作業も速い
        m.put(300005, Thread.domain("跳躍", "hunger-save-chance"));             // 跳ね回っても腹が減りにくい
        m.put(300006, Thread.domain("暗視", "mining-fortune"));                 // 暗い坑道で掘る
        m.put(300007, Thread.domain("耐火", "brew-speed-bonus"));               // 火を扱う=醸造台
        m.put(300008, Thread.domain("イルカの好意", "ocean-fishing-bonus"));     // 海を泳ぐ
        m.put(300009, Thread.domain("コンジットパワー", "fishing-luck"));         // 水中に留まる=釣り
        m.put(300010, Thread.domain("村の英雄", "mob-drop-quality"));            // 襲撃を退けた者への報い
        m.put(300011, Thread.domain("体力増強", "food-restore-bonus"));          // 体力=回復と満腹
        m.put(300015, Thread.domain("飛行", "vanilla-exp-bonus"));              // 行動範囲が広がる=経験が増える
        m.put(300016, Thread.domain("バックパック", "food-save-chance"));        // 常用=食料の持ち
        m.put(300039, Thread.domain("浮遊", "break-vanilla-exp-bonus"));         // 空中での作業=破壊EXP
        // --- 2026-08-21 追加: 戦闘系24種 ---
        // 前半14種は武器アーキタイプとの「デザイナーズコンボ」、後半10種は汎用。
        // 主ステは武器の性格に寄せてある(短剣=会心率、弩=貫通、大剣=被ダメ軽減…)。
        m.put(300046, Thread.combat("剣士", "percent-bonus-damage"));
        m.put(300047, Thread.combat("狂戦士", "crit-damage"));
        m.put(300048, Thread.combat("槍衾", "penetration"));
        m.put(300049, Thread.combat("刈り手", "bleed-chance"));
        m.put(300050, Thread.combat("打擲", "armor-strength"));
        m.put(300051, Thread.combat("暗殺者", "crit-chance"));
        m.put(300052, Thread.combat("決闘者", "dodge-chance"));
        m.put(300053, Thread.combat("巨戟", "percent-bonus-damage"));
        m.put(300054, Thread.combat("大剣士", "damage-reduction"));
        m.put(300055, Thread.combat("遠矢", "crit-damage"));
        m.put(300056, Thread.combat("潮呼び", "magic-resistance"));
        m.put(300057, Thread.combat("震撼", "percent-bonus-damage"));
        m.put(300058, Thread.combat("弩手", "penetration"));
        m.put(300059, Thread.combat("呪刃", "percent-bonus-damage"));
        m.put(300060, Thread.combat("猛攻", "percent-bonus-damage"));
        m.put(300061, Thread.combat("精確", "crit-chance"));
        m.put(300062, Thread.combat("追撃", "crit-damage"));
        m.put(300063, Thread.combat("穿孔", "penetration"));
        m.put(300064, Thread.combat("流血", "bleed-chance"));
        m.put(300065, Thread.combat("堅陣", "damage-reduction"));
        m.put(300066, Thread.combat("抗魔", "magic-resistance"));
        m.put(300067, Thread.combat("鉄皮", "phys-resistance"));
        m.put(300068, Thread.combat("疾影", "dodge-chance"));
        m.put(300069, Thread.combat("剛靭", "armor-strength"));
        // --- 2026-08-23 追加(W-187): トレジャーチェスト専用10種 ---
        // 構造物のルートチェストからしか出ない枠。主ステは**既存75種が主軸に使っていない軸**
        // だけで組んである(ユーザー指示「住み分けできない(相互互換が発生する)ステータス校正に
        // するな」)。内訳は 近接3(範囲/出血量/空中)・遠距離4(距離/精度/矢速/貫通)・防御3。
        // ⚠ ATTRIBUTE チャネル(attack-speed-bonus / attack-reach / max-health /
        //   knockback-resistance / move-speed)をここへ足してはいけない ── 装着経路では
        //   無言で効かないうえ、AttributeProjection の投影対象なので
        //   **装着せず手に持つだけでバニラ属性が付く**。2026-08-23 に attack-speed-bonus で
        //   一度組んで、下の2本のガードが実際に落ちたので差し替えた。
        // ⚠ flat-defense(守備力)もここへ足してはいけない ── DefenseStatBridge が
        //   phys/magic の typed キーがあるとき無視する後方互換の別名なので、
        //   既存スレッドが phys-flat-defense を配っている以上つねに無視される側になる。
        m.put(300070, Thread.combat("渦動", "aoe-damage-rate"));
        m.put(300071, Thread.combat("瀉血", "bleed-damage-rate"));
        m.put(300072, Thread.combat("墜撃", "power-attack-damage"));
        m.put(300073, Thread.combat("遠見", "distance-damage-bonus"));
        m.put(300074, Thread.combat("精射", "bow-accuracy"));
        m.put(300075, Thread.combat("疾矢", "arrow-velocity"));
        m.put(300076, Thread.combat("貫矢", "arrow-piercing"));
        m.put(300077, Thread.combat("城塞", "defense-rate"));
        m.put(300078, Thread.combat("鉄壁", "phys-flat-defense"));
        m.put(300079, Thread.combat("護法", "magic-flat-defense"));
        // --- 2026-08-25 追加(W-256): 透明化の常時効果 ---
        // ユーザー依頼「透明化エフェクトのつく隠密のスレッドの追加」。効果の実体は
        // フォークの ThreadType.STEALTH(PotionEffectType.INVISIBILITY)なので、
        // TF 側にステ節は【持たない】= STATLESS。
        m.put(300080, Thread.statless("隠密"));
        return Map.copyOf(m);
    }

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
            if (cmd < THREAD_CMD_MIN || cmd > THREAD_CMD_MAX) continue;
            ConfigurationSection entry = items.getConfigurationSection(key);
            assertNotNull(entry, key + " のエントリが節になっていない");
            assertFalse(out.containsKey(cmd), "CMD " + cmd + " のエントリが2件ある(材質違いの重複): " + key);
            out.put(cmd, entry);
        }
        return out;
    }

    @Test
    @DisplayName("スレッド80種(300001-300080)が漏れなく1件ずつ定義されている")
    void everyThreadHasExactlyOneEntry() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());

        Set<Integer> expected = new TreeSet<>(THREADS.keySet());
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
        assertEquals(THREADS.size(), entries.size(), "スレッドの総数が " + THREADS.size() + " 件でない");
    }

    @Test
    @DisplayName("ステを持たないスレッド(常時効果系・空)はステ節を一切持たない")
    void statlessThreadsHaveNoStats() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());
        List<String> offenders = new ArrayList<>();

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            if (declared.getValue().kind() != Kind.STATLESS) continue;
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue; // 件数は別テストが見る
            for (String section : List.of("fixed", "per-quality", "random", "advanced")) {
                if (entry.contains(section)) {
                    offenders.add(declared.getValue().name() + "のスレッド(" + declared.getKey()
                            + ") に " + section + " がある");
                }
            }
        }

        assertEquals(List.of(), offenders,
                "効果をフォーク側(ポーション効果/飛行/バックパック)が配るスレッドに item-stats のステがある。"
                        + "ここへ書くと『名前と関係ないステが付いてくる』スレッドに戻る: " + offenders);
    }

    @Test
    @DisplayName("非戦闘スレッドは戦闘ステ(ATTACK/DEFENSE)を1件も配らない — 2026-08-21 のユーザー指示")
    void nonCombatThreadsCarryNoCombatStats() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());
        List<String> offenders = new ArrayList<>();

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            if (declared.getValue().kind() == Kind.COMBAT) continue;
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue;
            for (String section : List.of("fixed", "per-quality", "random")) {
                ConfigurationSection block = entry.getConfigurationSection(section);
                if (block == null) continue;
                for (String stat : block.getKeys(false)) {
                    if (StatVocabulary.isAttack(stat) || StatVocabulary.isDefense(stat)) {
                        offenders.add(declared.getValue().name() + "のスレッド(" + declared.getKey()
                                + ")." + section + "." + stat);
                    }
                }
            }
        }

        assertEquals(List.of(), offenders,
                "採取・制作・マナ・常時効果のスレッドに戦闘ステが混ざっている。"
                        + "全種が一律で戦闘サブ4種を配っていた状態へ戻ると、戦闘系スレッドを選ぶ意味が"
                        + "消える(2026-08-21 のユーザー指示で剥がした): " + offenders);
    }

    @Test
    @DisplayName("主ステが名称に対応する軸になっている(名称と効果の食い違いの検出)")
    void primaryStatMatchesTheThreadName() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());
        List<String> wrong = new ArrayList<>();

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            Thread thread = declared.getValue();
            if (thread.kind() == Kind.STATLESS) continue;
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue; // 件数は別テストが見る

            ConfigurationSection perQuality = entry.getConfigurationSection("per-quality");
            assertNotNull(perQuality, thread.name() + "のスレッド(" + declared.getKey() + ")に per-quality が無い");
            List<String> pqKeys = new ArrayList<>(perQuality.getKeys(false));
            assertFalse(pqKeys.isEmpty(),
                    thread.name() + "のスレッドの per-quality が空(主ステが伸びない)");
            if (thread.kind() == Kind.COMBAT) {
                assertEquals(1, pqKeys.size(),
                        thread.name() + "のスレッドの per-quality が1件でない(戦闘系は主ステ1件だけが規約): "
                                + pqKeys);
            }

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
    @DisplayName("非戦闘スレッドは per-quality と random が同じ軸だけを持つ(サブ枠も確率付与も無い)")
    void domainThreadsOnlyRollTheirOwnAxis() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());
        List<String> offenders = new ArrayList<>();

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            Thread thread = declared.getValue();
            if (thread.kind() != Kind.DOMAIN) continue;
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue;
            String where = thread.name() + "のスレッド(" + declared.getKey() + ")";

            Set<String> pqKeys = new LinkedHashSet<>(entry.getConfigurationSection("per-quality").getKeys(false));
            Set<String> randomKeys = new LinkedHashSet<>(randomSection(entry, thread).getKeys(false));
            if (!pqKeys.equals(randomKeys)) {
                offenders.add(where + ": per-quality=" + pqKeys + " と random=" + randomKeys + " が一致しない");
            }
            if (entry.contains("advanced")) {
                // 0.45 の確率付与は戦闘サブ枠のための仕組み。サブが無いのに残っていると、
                // 主ステが「確率で付くステ」の色でロアに出る(LoreColorRules)。
                offenders.add(where + ": advanced ブロックが残っている(サブ枠が無いので置き場所が無い)");
            }
        }

        assertEquals(List.of(), offenders,
                "非戦闘スレッドの形が規約から外れている: " + offenders);
    }

    /**
     * 「そこへ集中させると上限へ張り付く」割合ステ。
     *
     * <p>上限は {@code combat/damage.yml}: {@code max-mitigation-rate} 0.9 /
     * {@code max-dodge-chance} 0.9 / {@code max-crit-reduction} 1.0。張り付いた先では
     * 装備を更新しても一切効かなくなるので、<b>その先の育成が無意味</b>という壊れ方をする。
     *
     * <p>{@code percent-bonus-damage} は上限を持たないが、同じ「どのスレッドにも付いてくる」
     * 状態(34種中17種にサブとしてぶら下がっていた)だったのでここに含める。
     * ただし種類数の上限({@link #MAX_THREADS_PER_CAPPED_STAT})は<b>掛けない</b> ──
     * 上限張り付きが起きないステなので、総量の歯止めは
     * {@code ShippedThreadBandIndependenceTest} の帯目標が別に持っている。
     */
    private static final Set<String> STRONG_RATE_STATS = Set.of(
            "dodge-chance", "damage-reduction", "armor-strength",
            "phys-resistance", "magic-resistance", "defense-rate", "percent-bonus-damage");

    /** 上限で壊れる割合ステを主軸に持ってよいスレッドの種類数(ユーザー指示「2個以下、あって3種類」)。 */
    private static final int MAX_THREADS_PER_CAPPED_STAT = 3;

    @Test
    @DisplayName("W-254: 上限で壊れる割合ステは主軸にしか現れない / 主軸に持つ種類も3種まで")
    void strongStatsAreOnlyEverAPrimaryAxis() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());
        List<String> asSecondary = new ArrayList<>();
        Map<String, List<Integer>> asPrimary = new java.util.TreeMap<>();

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            Thread thread = declared.getValue();
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue;
            ConfigurationSection random = entry.getConfigurationSection("random");
            if (random == null) continue;
            for (String key : random.getKeys(false)) {
                if (!STRONG_RATE_STATS.contains(key)) continue;
                if (key.equals(thread.primary())) {
                    asPrimary.computeIfAbsent(key, k -> new ArrayList<>()).add(declared.getKey());
                } else {
                    asSecondary.add(thread.name() + "のスレッド(" + declared.getKey() + ")."
                            + key + " が副次枠にある(主軸は " + thread.primary() + ")");
                }
            }
        }

        // ユーザー確定要件「回避やダメージ軽減等の一部100%に達成するとバランスの壊れる
        // ステータスを防ぐ」。副次に置くと、主軸が別のスレッドを何本挿しても同じステが
        // 積み上がる ── 1装備1本の制限(ThreadApplicationPolicy.DEFAULT_MAX_STACK)を
        // 副次経路がすり抜けてしまう。
        assertEquals(List.of(), asSecondary,
                "上限で壊れる割合ステが副次枠に残っている(1装備1本の制限を副次経路がすり抜ける): "
                        + asSecondary);

        List<String> tooMany = new ArrayList<>();
        asPrimary.forEach((stat, cmds) -> {
            if (stat.equals("percent-bonus-damage")) {
                return; // 上限を持たないステ。総量は帯目標テストが縛る(上の Javadoc 参照)。
            }
            if (cmds.size() > MAX_THREADS_PER_CAPPED_STAT) {
                tooMany.add(stat + " を主軸に持つスレッドが " + cmds.size() + " 種: " + cmds);
            }
        });
        assertEquals(List.of(), tooMany,
                "ユーザー指示『強いステータスに尖ったスレッドは2個以下、あって3種類』を超えている: "
                        + tooMany);

        assertFalse(asPrimary.isEmpty(),
                "強ステを主軸に持つスレッドが1件も見つからない(CMD 帯か節の構造が変わった可能性)");
    }

    @Test
    @DisplayName("戦闘系の組み立て規約: 主ステ+サブ1種 / サブは0.45の確率付与 / 主ステは確率ゲートしない")
    void rollLayoutFollowsTheSharedConvention() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            Thread thread = declared.getValue();
            if (thread.kind() != Kind.COMBAT) continue;
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue;
            String where = thread.name() + "のスレッド(" + declared.getKey() + ")";

            ConfigurationSection random = randomSection(entry, thread);
            List<String> randomKeys = new ArrayList<>(random.getKeys(false));
            // 2026-08-25 (W-254): サブ4種 → 【サブ1種】。ユーザー確定要件
            //   「各スレッドを特定のステータスにとがらせ」「どのスレッドでも代用できるのは
            //     かえってコンテンツの道を減らしている」。
            // サブが4種あると、どのスレッドを挿しても同じ4ステが付いてくるので
            // 「このスレッドを選ぶ理由」が主ステの1行しか無くなる。
            assertEquals(2, randomKeys.size(),
                    where + " の random が2件(主ステ+サブ1種)でない: " + randomKeys);
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
                    where + " の advanced.randomize-grants が true でない。false だとサブ枠が"
                            + "確定で付き、個体差が『品質だけ』に潰れる");

            ConfigurationSection grants = entry.getConfigurationSection("advanced.grant-chances");
            assertNotNull(grants, where + " に advanced.grant-chances が無い");
            Set<String> grantKeys = new LinkedHashSet<>(grants.getKeys(false));
            assertEquals(1, grantKeys.size(), where + " の grant-chances が1件でない: " + grantKeys);
            assertFalse(grantKeys.contains(thread.primary()),
                    where + " の主ステ(" + thread.primary() + ")が grant-chances に載っている。"
                            + "主ステが確率で付かない個体が出ると、名前どおりの効果を持たないスレッドになる");

            Set<String> expectedGrants = new LinkedHashSet<>(randomKeys);
            expectedGrants.remove(thread.primary());
            assertEquals(expectedGrants, grantKeys,
                    where + " の grant-chances が random のサブ1種と一致しない(random に無いキーを"
                            + "確率付与しても何も起きない)");
            for (String key : grantKeys) {
                assertEquals(GRANT_CHANCE, grants.getDouble(key), 1e-9,
                        where + " の grant-chances." + key + " が 0.45 でない(戦闘系で揃える)");
            }
        }
    }

    @Test
    @DisplayName("ステを持つスレッドは offhand-stats-apply が false(オフハンドに持つだけで効く穴)")
    void statfulThreadsNeverApplyFromTheOffhand() {
        Map<Integer, ConfigurationSection> entries = threadEntries(items());
        List<String> offenders = new ArrayList<>();

        for (Map.Entry<Integer, Thread> declared : THREADS.entrySet()) {
            Thread thread = declared.getValue();
            if (thread.kind() == Kind.STATLESS) continue;
            ConfigurationSection entry = entries.get(declared.getKey());
            if (entry == null) continue;
            if (entry.getBoolean("offhand-stats-apply", false)) {
                offenders.add(thread.name() + "のスレッド(" + declared.getKey() + ")");
            }
        }

        assertEquals(List.of(), offenders,
                "offhand-stats-apply が true のスレッドがある。スレッド自体をオフハンドに持つだけで"
                        + "ステが乗る(装着させる意味が消える): " + offenders);
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
            if (declared.getValue().primary() == null) continue;
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
            if (declared.getValue().primary() == null) continue;
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
            if (declared.getValue().primary() == null) continue;
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
