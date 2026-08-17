package com.trinityforge.combat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code stats/item-stats.yml} の<b>武器種ごとの実効DPSが同系列の剣に対して意図した帯に
 * 入っていること</b>を固定する (W-32 / W-34 / W-35, 2026-08-05)。
 *
 * <p>背景: 実サーバ報告「メイスの火力が低い」「杖の火力が低すぎる」「鎌の出血など tier に見合って
 * いないステの洗い直し」「武器ごとの攻撃リーチを再設計したい」。棚卸ししたところ、個別の値が
 * おかしいのではなく<b>武器種ごとの校正がそもそも噛み合っていなかった</b>:
 * <ul>
 *   <li>メイスは全段で剣の 29%。戦斧は 29〜45%、槍は段が上がるほど落ちて 30%。杖は 4〜6%。</li>
 *   <li>鎌の {@code attack-speed} は 2.12/3.12/4.12 だが<b>無敵時間 10 tick で毎秒2発に切り捨てられる</b>
 *       ので実効は全部 2.0。一方 木と金(golad)だけ 1.12 で、同じ武器種の中で半減していた。</li>
 *   <li>戦斧の {@code attack-speed} は 0.323 / 0.408 / 0.493 と段でばらつき、source_gem だけ 1.003。</li>
 *   <li>出血は総DPSの 1% しか出しておらず、鎌の「出血で削る」個性が数字として存在しなかった
 *       (cryocore 系列の大斧のほうが鎌より出血が大きいという逆転まで起きていた)。</li>
 *   <li>{@code revolution_bow} だけ {@code attack-speed} 1.6 のまま残り、<b>弓が同帯で最強の近接武器
 *       (剣の119%)</b>になっていた。2026-08-02 に「遠隔は殴る武器ではない」として弓/弩/トライデントを
 *       0.1 へ落とした一斉変更の取りこぼし。</li>
 *   <li>広辞苑({@code koujien})は BOOK 1個で作れるのに剣の 125%、しかも {@code attack-speed} 4。</li>
 * </ul>
 *
 * <p><b>実効DPS の定義</b>（すべて実コードから導出）:
 * <ul>
 *   <li>{@code CombatListener}: item に {@code attack-power} があるとバニラ攻撃力を捨てて置換する
 *       ({@code tfBaseReplaces})。近接には {@code MeleeChargeMultiplier} が掛かる。</li>
 *   <li>{@code DefaultDamageResolver}: {@code base × (1 + per-level × combatLevel)}。</li>
 *   <li>{@code ComponentDamageCalculator}: 会心期待値 {@code 1 + critChance × critDamage}、
 *       {@code damage-modifier} は endpoint に対する一様分布の期待値。</li>
 *   <li>無敵時間は {@code LivingEntity.invulnerableDuration(20) / 2 = 10 tick}。
 *       {@code MeleeChargeMultiplier} はフルチャージで 1.0 になるので、最適間隔での実効DPSは
 *       {@code perHit × min(attack-speed, 2.0)} に収束する（出荷 {@code melee-charge}
 *       min 0.1 / exp 1.6 では、連打の方が得になるのは {@code attack-speed < 0.28} の遠隔勢だけ）。</li>
 * </ul>
 *
 * <p><b>この形のテストにした理由</b>: 「値を1つずつ期待値と比べる」テストは、武器を1本足すたびに
 * 期待値表の更新が必要になり、更新を忘れた瞬間に検査が形骸化する。ここでは
 * <b>同系列の剣に対する比</b>という不変条件だけを見るので、武器が増えても勝手に守られる。
 *
 * <p><b>⚠ 上限との関係</b>: {@code combat/stat-caps.yml} の {@code attack-power}(127,500) /
 * {@code bleed-damage}(4,500) は<b>最終合算値</b>に効くので、超過分は「表示だけ上がって実効は
 * 伸びない死に設定」になる。上限そのものの検査は {@code ShippedStatCapsDriftTest} の担当。
 * ここで扱う武器の校正は<b>上限内に収まる範囲で</b>行っている。メイスの {@code attack-speed} を
 * 0.408 から 0.88 へ上げたのはこの制約が理由で、0.408 のままパリティに載せると
 * {@code attack-power} が上限の 2.09 倍必要になる。
 */
class WeaponTierParityTest {

    /** 無敵時間 10 tick による毎秒あたりの命中上限。これを超える attack-speed は切り捨てられる。 */
    private static final double MAX_RATE = 2.0;

    /** {@code combat/damage.yml} の {@code level-scaling.per-level}。 */
    private static final double PER_LEVEL = 0.01;

    /**
     * 武器種ごとの「同系列の剣に対する実効DPS比」の許容帯。
     * 剣が 100%。下限だけでなく<b>上限も</b>見る（剣より強い近接武器を作らないため）。
     */
    private static final Map<String, double[]> MELEE_BAND = Map.ofEntries(
            Map.entry("sword", new double[] {1.00, 1.00}),
            Map.entry("dagger", new double[] {0.82, 0.99}),
            Map.entry("rapier", new double[] {0.71, 0.86}),
            Map.entry("grate_sword", new double[] {0.64, 0.79}),
            Map.entry("greataxe", new double[] {0.71, 0.88}),
            Map.entry("warhammer", new double[] {0.82, 0.94}),
            Map.entry("scythe", new double[] {0.79, 0.89}),
            // 2026-08-18 (W-72): リーチを ±2.0 まで広げたのに合わせて、全部の帯を
            // 「リーチ 1.0 につき実効DPS 10%」で移動させた(下の REACH のコメント参照)。
            // 移動前の帯は sword 1.00 / dagger 0.75-0.90 / rapier 0.75-0.90 /
            // grate_sword 0.70-0.85 / greataxe 0.78-0.95 / warhammer 0.88-1.00 /
            // scythe 0.85-0.95 / spear 0.80-0.95 / axe 0.85-0.95 / mace 0.80-0.95 /
            // trident 0.85-0.95。丸めは必ず外側へ(下限は切り捨て・上限は切り上げ)。
            //
            // メイスだけは scaled の上限が 1.026 になるが、【近接の頂点は剣】という
            // noWeaponOutDpsesTheStrongestSwordOfItsLevel の不変条件と食い違うので 1.00 で頭を
            // 打たせている。power-attack-damage 0.35 があるので空中では約 1.15 倍になる。
            // 上限側を動かすときは attack-power の単品最大が stat-caps の余裕を食い潰さないか
            // 必ず確かめる(上のクラスコメント参照)。
            Map.entry("spear", new double[] {0.72, 0.86}),
            Map.entry("axe", new double[] {0.83, 0.94}),
            Map.entry("mace", new double[] {0.86, 1.00}),
            // 2026-08-14: トライデントを遠隔扱いから近接武器へ移した(下の
            // rangedWeaponsKeepTheirMinimumMeleeSpeed のコメント参照)。帯を持たせないと
            // 「近接として使う武器なのに帯の検査だけ素通り」という、この表がもともと
            // 塞いだはずの穴が復活する。
            Map.entry("trident", new double[] {0.78, 0.88})
    );

    /**
     * 武器種ごとの {@code attack-reach}（バニラの {@code entity_interaction_range} 3.0 への<b>加算</b>）。
     * 剣 = 0（＝バニラの剣そのまま）を基準に、体感できる刻みへ引き直した表 (W-35)。
     * ユーザー決定 2026-08-05「剣=バニラの剣の基準で」。
     *
     * <p><b>2026-08-18 (W-72) に幅を広げた</b>。ユーザー指示「各種武器のリーチにもっとがっつり
     * 差をつけていい。短剣は実効 1.5、槍は +2 くらいを目安に他も分散させる」。
     * 旧表の幅は −0.5〜+1.0（実効 2.5〜4.0）しかなく、実測では武器種の差が体感できなかった。
     *
     * <p><b>火力補正</b>: リーチを伸ばした武器はそのぶん実効DPSを下げ、縮めた武器は上げる。
     * 強さは<b>リーチ 1.0 あたり 10%</b>（ユーザー決定 2026-08-18。当初 5% で作ったが
     * 「もうちょっと大差を付けていい」で倍にした）。旧リーチからの差 ×10% を符号反転した倍率:
     * 短剣 ×1.10 / メイス ×1.08 / 剣 ×1.00 / 斧 ×0.98 / レイピア ×0.95 / ウォーハンマー ×0.94 /
     * 鎌 ×0.93 / 大剣・大斧・トライデント・狩人の投槍 ×0.92 / 槍・ハルバード ×0.90。
     * <b>これ以上強めると短剣が同帯の剣を追い越す</b>（12% が実質上限）。
     *
     * <p><b>掛け方は「倍率を掛ける」ではなく「目標DPSから逆算する」</b>。同時に会心率・会心倍率も
     * 武器種ごとに動かしており（下記）、会心は実効DPSの式に入るので単純に掛けると目標からずれる。
     * {@code attack-power} と {@code fixed-damage} は DPS に対して線形なので、
     * {@code s = (目標DPS − 出血) / ((AP×K' + FD) × rate)} を武器 1 本ずつ解いて掛けてある。
     * {@code bleed-damage} は<b>意図的に据え置いた</b> —— 上位段の鎌は {@code stat-caps} の
     * 上限 4,500 に張り付けてあり、下げると「上限張り付き」が外れて
     * {@link #scytheBleedIsMeaningfulUpToTheCap} の出血比率（8〜25%）に落ちるため。
     *
     * <p><b>武器種の個性</b>（同日・ユーザー要望「DPSは据え置いたまま武器種特有のステを尖らせる」。
     * 不変条件は {@code ShippedWeaponIdentityTest} が固定する）:
     * <ul>
     *   <li><b>範囲ダメージ</b>を大剣・大斧・ハルバード・鎌へ新設。出荷 216 本の武器は
     *       <b>1 本も {@code aoe-*} を持っていなかった</b>ので、まるごと空いていた差別化軸。</li>
     *   <li><b>出血を鎌へ集約</b>。トライデントが全 16 段で鎌と同率（0.16）の出血を持っていて
     *       鎌の個性を薄めていたので落とし、失ったぶんは単体火力へ振り替えた。
     *       鎌は出血率を 1.8 倍（0.16〜0.18 → 0.29〜0.32）。</li>
     *   <li><b>貫通を刺突へ集中</b>。槍・レイピア・トライデント・投槍・ハルバードは ×1.3、
     *       打撃（メイス・大斧 ×0.5 / ウォーハンマー ×0.35）は剣を下回るまで下げる。</li>
     *   <li><b>会心を二極化</b>。短剣 率×1.6 倍率×0.85 / レイピア 率×1.5 倍率×0.9、
     *       大斧・ウォーハンマー 率×0.5 倍率×1.15 / 大剣・メイス 率×0.6 倍率×1.12。
     *       期待値（1 + 率×倍率）のズレは上の逆算が吸収するので、変わるのは分散だけ。</li>
     * </ul>
     */
    private static final Map<String, Double> REACH = new LinkedHashMap<>();

    static {
        REACH.put("dagger", -1.5);       // 1.50 最短。密着して刺す武器
        REACH.put("mace", -0.8);         // 2.20 短い打撃武器
        REACH.put("sword", 0.0);         // 3.00 基準
        REACH.put("bow", 0.0);
        REACH.put("crossbow", 0.0);
        REACH.put("wand", 0.0);
        REACH.put("axe", 0.4);           // 3.40 片手斧
        REACH.put("rapier", 0.8);        // 3.80 刺突
        REACH.put("warhammer", 1.0);     // 4.00 両手の柄
        REACH.put("scythe", 1.2);        // 4.20 長柄
        REACH.put("grate_sword", 1.4);   // 4.40
        REACH.put("greataxe", 1.4);      // 4.40
        REACH.put("trident", 1.6);       // 4.60 投擲槍
        REACH.put("spear", 2.0);         // 5.00 武器種としては最長
        REACH.put("halberd", 2.0);       // 5.00 武器種としては最長
        // 狩人の投槍は 2026-08-02 に「game 内で最長」として決めた単発品。W-72 で槍が 2.0 まで
        // 伸びたので、その肩書きを保つために 1.4 → 2.2 へ引き上げた（槍の1段上）。
        REACH.put("javelin", 2.2);       // 5.20 game 内で最長
    }

    /**
     * W-72 (2026-08-18) で武器種ごとに掛けた<b>実効DPSの倍率</b>。上の {@link #REACH} と
     * 2本セットで初めて意味を持つ（リーチを伸ばした武器をそのぶん弱くする補正）。
     *
     * <p><b>{@code WeaponDpsParityTest} がこの表を読む。</b> あちらは「重武器 vs 同格の軽武器」の
     * 実効DPS比を 0.85〜1.16 で見るが、W-72 の補正はその比を<b>意図的に</b>動かす
     * （例: 大剣 ×0.92 に対して短剣 ×1.10 なので、ペアの比だけで 0.836 倍ずれる）。
     * 割り戻さずに帯へ当てると「重武器を選ぶ理由が無い」という<b>別の設計意図の検査が、
     * 実際には壊れていないのに落ちる</b>。ここを唯一の出どころにして両方から読む。
     */
    static final Map<String, Double> REACH_DPS_FACTOR = Map.ofEntries(
            Map.entry("dagger", 1.10),
            Map.entry("mace", 1.08),
            Map.entry("sword", 1.00),
            Map.entry("bow", 1.00),
            Map.entry("crossbow", 1.00),
            Map.entry("wand", 1.00),
            Map.entry("axe", 0.98),
            Map.entry("rapier", 0.95),
            Map.entry("warhammer", 0.94),
            Map.entry("scythe", 0.93),
            Map.entry("grate_sword", 0.92),
            Map.entry("greataxe", 0.92),
            Map.entry("trident", 0.92),
            Map.entry("javelin", 0.92),
            Map.entry("spear", 0.90),
            Map.entry("halberd", 0.90));

    /** id の接尾辞から武器種を引く。長い接尾辞を先に置く（grate_sword が sword に食われないため）。 */
    private static final List<String> TYPES = List.of(
            "grate_sword", "greataxe", "warhammer", "crossbow", "trident", "javelin",
            "halberd", "scythe", "rapier", "dagger", "spear", "sword", "mace", "wand", "bow", "axe");

    /**
     * 接尾辞に武器種を持たない<b>名前付き一点物</b>の武器種。
     * {@code catalog.yml} の {@code _editor.categories.weapon} が「鎌」に入れている2本は、
     * 接尾辞方式だと {@code type == null} になって<b>検査ごと素通りしていた</b>
     * (実際 {@code attack-speed} 4.12 / reach 0.1 / 出血2% のまま残っていた)。
     * 広辞苑は「特殊武器」カテゴリで武器種の規約が無いので {@code special} として扱い、
     * 帯とリーチの表からは外して「剣を超えない」側の歯止めだけを効かせる。
     */
    private static final Map<String, String> TYPE_ALIAS = Map.of(
            "winter_grim_reaper", "scythe",
            "fnis_peccati_profundi", "scythe",
            "koujien", "special");

    private record Weapon(String statKey, String id, String type, String series, int level,
                          double attackSpeed, double rate, double perHit, double bleed,
                          double reach) {
        double dps() {
            return perHit * rate + bleed;
        }
    }

    // ---------------------------------------------------------------- 読み込み

    private static YamlConfiguration shipped(String resource) {
        try (InputStream in = WeaponTierParityTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "出荷リソースが見つからない: " + resource);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(resource + " を読めない", ex);
        }
    }

    /** {@code fixed} と {@code random}（min/max の中央＝期待値）を足した実効値。品質0で評価する。 */
    private static double stat(ConfigurationSection item, String name) {
        double v = 0.0;
        ConfigurationSection fixed = item.getConfigurationSection("fixed");
        if (fixed != null) {
            v += fixed.getDouble(name, 0.0);
        }
        ConfigurationSection random = item.getConfigurationSection("random");
        if (random != null) {
            ConfigurationSection range = random.getConfigurationSection(name);
            if (range != null) {
                v += (range.getDouble("min") + range.getDouble("max")) / 2.0;
            }
        }
        return v;
    }

    /**
     * {@code attack-power} を持つ全アイテム。<b>武器種が引けないものも {@code type == null} で残す</b> —
     * ここで捨てると名前付き一点物が検査ごと素通りする（2026-08-05 に実際に3本取りこぼしていた）。
     */
    private static List<Weapon> loadWeapons() {
        YamlConfiguration stats = shipped("stats/item-stats.yml");
        YamlConfiguration catalog = shipped("items/catalog.yml");

        // catalog の "MATERIAL#CMD" -> id。素の MATERIAL エントリは id が引けないのでキーをそのまま使う。
        Map<String, String> idByStatKey = new HashMap<>();
        ConfigurationSection catalogItems = catalog.getConfigurationSection("items");
        if (catalogItems != null) {
            for (String id : catalogItems.getKeys(false)) {
                ConfigurationSection def = catalogItems.getConfigurationSection(id);
                if (def == null) {
                    continue;
                }
                String material = def.getString("material");
                if (material == null) {
                    continue;
                }
                String key = def.contains("custom-model-data")
                        ? material + "#" + def.getInt("custom-model-data")
                        : material;
                idByStatKey.put(key, id);
            }
        }

        ConfigurationSection items = stats.getConfigurationSection("items");
        assertNotNull(items, "item-stats.yml に items: が無い");
        List<Weapon> out = new ArrayList<>();
        for (String statKey : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(statKey);
            if (item == null) {
                continue;
            }
            ConfigurationSection fixed = item.getConfigurationSection("fixed");
            // attack-power を持たないアイテムは tfBaseReplaces にならない＝武器として評価しない。
            if (fixed == null || !fixed.contains("attack-power")) {
                continue;
            }
            String id = idByStatKey.getOrDefault(statKey, statKey);
            String lower = id.toLowerCase(java.util.Locale.ROOT);
            String alias = TYPE_ALIAS.get(lower);
            String type = alias != null ? alias
                    : TYPES.stream().filter(t -> lower.endsWith(t) || lower.endsWith(t + "_tf"))
                    .findFirst().orElse(null);
            // 系列(series) = id から武器種の接尾辞を落とした残り。Lv100 には binder / infinity /
            // emberforge / abyss / cryocore / hero の 6 系列が並び、系列ごとに強さが違う
            // (hero は infinity の約6割)。したがって比の分母は【同じ系列の剣】でなければならず、
            // 「同レベル帯で最強の剣」を分母にすると弱い系列の武器を系列内の剣より強く見積もる。
            // 一点物(alias)には系列が無いので null。
            String series = null;
            if (type != null && alias == null) {
                String suffix = lower.endsWith(type + "_tf") ? type + "_tf" : type;
                String head = lower.substring(0, lower.length() - suffix.length())
                        .replaceAll("_$", "");
                series = head.isEmpty() ? null : head;
            }
            int level = item.getInt("use-level-requirement", 0);
            double attackSpeed = stat(item, "attack-speed");
            double cooldown = stat(item, "item-cooldown");
            // 杖は近接で振らず詠唱で撃つので、実効レートは item-cooldown の逆数。
            double rate = "wand".equals(type) && cooldown > 0
                    ? 1.0 / cooldown
                    : (attackSpeed > 0 ? Math.min(attackSpeed, MAX_RATE) : MAX_RATE);
            double modifier = stat(item, "damage-modifier");
            double perHit = stat(item, "attack-power")
                    * (1 + PER_LEVEL * level)
                    * (1 + stat(item, "crit-chance") * stat(item, "crit-damage"))
                    * ((Math.min(1, 1 + modifier) + Math.max(1, 1 + modifier)) / 2.0)
                    + stat(item, "fixed-damage");
            // 出血は victim ごとに1件しか持たず(BleedService は Map#put で上書き)、
            // tick-interval 20 tick で bleed-damage を刻む。毎秒2発当たる前提では常に更新
            // され続けるので、発症後の定常DPS ≒ bleed-damage。
            double bleed = stat(item, "bleed-chance") > 0 ? stat(item, "bleed-damage") : 0.0;
            out.add(new Weapon(statKey, id, type, series, level,
                    attackSpeed, rate, perHit, bleed, stat(item, "attack-reach")));
        }
        assertFalse(out.isEmpty(), "武器を1件も読めていない(この検査は空振りしている)");
        return out;
    }

    /** 系列ごとの剣の実効DPS。比の分母。 */
    private static Map<String, Double> swordBySeries(List<Weapon> weapons) {
        Map<String, Double> out = new TreeMap<>();
        for (Weapon w : weapons) {
            if (!"sword".equals(w.type()) || w.series() == null) {
                continue;
            }
            out.merge(w.series(), w.dps(), Math::max);
        }
        return out;
    }

    /** レベル帯ごとの「最強の剣」の実効DPS。系列に剣が無い一点物の分母。 */
    private static Map<Integer, Double> swordByLevel(List<Weapon> weapons) {
        Map<Integer, Double> out = new TreeMap<>();
        for (Weapon w : weapons) {
            if ("sword".equals(w.type())) {
                out.merge(w.level(), w.dps(), Math::max);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 検査

    @Test
    @DisplayName("近接武器の実効DPSが同系列の剣に対して意図した帯に入っている")
    void meleeDpsStaysInsideItsBand() {
        List<Weapon> weapons = loadWeapons();
        Map<String, Double> sword = swordBySeries(weapons);
        // 空振り防止: 剣のはしごが読めていること。
        assertTrue(sword.size() >= 12, "剣の系列が " + sword.size() + " しか無い: " + sword.keySet());

        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (Weapon w : weapons) {
            // 不変 Map は get(null) で NPE を投げるので、型不明を先に落とす。
            double[] band = w.type() == null ? null : MELEE_BAND.get(w.type());
            if (band == null) {
                continue;
            }
            Double denominator = w.series() == null ? null : sword.get(w.series());
            if (denominator == null || denominator <= 0) {
                // 系列に剣が無い単発品(woodsman_greataxe / golad_scythe / 名前付き一点物 等)は
                // 公平な分母が無いのでここでは比較しない。「剣を超えない」側の歯止めは
                // noWeaponOutDpsesTheStrongestSwordOfItsLevel が全品に効かせる。
                continue;
            }
            compared++;
            double ratio = w.dps() / denominator;
            if (ratio < band[0] - 1e-6 || ratio > band[1] + 1e-6) {
                problems.add(String.format(
                        "%s (%s, Lv%d): 剣の %.0f%% は帯 %.0f〜%.0f%% の外",
                        w.id(), w.type(), w.level(), ratio * 100, band[0] * 100, band[1] * 100));
            }
        }
        // 空振り防止: 系列の剣と突き合わせられた武器が十分な本数あること。
        assertTrue(compared >= 100, "比較できた武器が " + compared + " 本しかない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(), "武器種の実効DPSが帯から外れている:\n"
                + String.join("\n", problems));
    }

    @Test
    @DisplayName("どの武器も『同レベル帯で最強の剣』を超えない（一点物・型不明も含む）")
    void noWeaponOutDpsesTheStrongestSwordOfItsLevel() {
        List<Weapon> weapons = loadWeapons();
        Map<Integer, Double> sword = swordByLevel(weapons);
        assertTrue(sword.size() >= 8, "剣のレベル帯が " + sword.size() + " しか無い: " + sword.keySet());

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Weapon w : weapons) {
            Double denominator = sword.get(w.level());
            if (denominator == null || denominator <= 0 || "sword".equals(w.type())) {
                continue;
            }
            checked++;
            double ratio = w.dps() / denominator;
            if (ratio > 1.0 + 1e-6) {
                problems.add(String.format("%s (%s, Lv%d): 同帯で最強の剣の %.0f%%",
                        w.id(), w.type() == null ? "型不明" : w.type(), w.level(), ratio * 100));
            }
        }
        assertTrue(checked >= 200, "比較できた武器が " + checked + " 本しかない(この検査は空振りしている)");
        // この歯止めが無いと、武器種の接尾辞を持たない一点物が帯の検査を素通りする。
        // 2026-08-05 の実測では revolution_bow(剣の119%) と koujien(125%) がここに引っかかった。
        assertTrue(problems.isEmpty(),
                "近接の実効DPSが同帯の剣を超えている武器がある(近接武器の頂点は剣):\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("同じ武器種の attack-speed が段でばらついていない")
    void attackSpeedIsUniformWithinAType() {
        Map<String, Map<Double, List<String>>> byType = new TreeMap<>();
        for (Weapon w : loadWeapons()) {
            // 不変 Map は containsKey(null) でも NPE を投げる。
            if (w.type() == null || !MELEE_BAND.containsKey(w.type())) {
                continue;
            }
            byType.computeIfAbsent(w.type(), k -> new TreeMap<>())
                    .computeIfAbsent(w.attackSpeed(), k -> new ArrayList<>()).add(w.id());
        }
        List<String> problems = new ArrayList<>();
        for (var e : byType.entrySet()) {
            if (e.getValue().size() > 1) {
                problems.add(e.getKey() + ": " + e.getValue());
            }
        }
        assertTrue(problems.isEmpty(),
                "同じ武器種の中で attack-speed が違う（段ごとに強さが飛ぶ）:\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("attack-speed に 2.0 超（無敵時間で切り捨てられる死に設定）が無い")
    void noAttackSpeedAboveTheInvulnerabilityCeiling() {
        List<Weapon> weapons = loadWeapons();
        List<String> over = new ArrayList<>();
        for (Weapon w : weapons) {
            if (w.attackSpeed() > MAX_RATE + 1e-9) {
                over.add(w.id() + " = " + w.attackSpeed());
            }
        }
        // 空振り防止: 型が引けない品まで含めて全部見ていること(この検査を type 付きに絞ると
        // koujien の 4 / Winter_Grim_Reaper の 4.12 を取りこぼす)。
        assertTrue(weapons.size() >= 210, "武器を " + weapons.size() + " 件しか読めていない");
        assertTrue(over.isEmpty(),
                "無敵時間は 10 tick なので毎秒2発が上限。2.0 超は表示と実効が食い違うだけの死に設定:\n"
                        + String.join("\n", over));
    }

    @Test
    @DisplayName("遠隔武器(弓/弩)の近接 attack-speed が最低値で揃っている")
    void rangedWeaponsKeepTheirMinimumMeleeSpeed() {
        // 2026-08-02 決定「遠隔武器を近接武器として振り回すと本来の用途より強い場面があったので
        // 近接攻撃速度を最低値にする」。revolution_bow だけこの一斉変更から漏れて 1.6 のまま残り、
        // 同レベル帯で最強の近接武器になっていた。
        //
        // 2026-08-14: トライデントをこの集合から外した。理由は2つとも機構レベルで確かめてある。
        //  (1) EXPの抜け道が無い: 武器スキルEXPは CombatListener#onCombatKill の討伐時ダメージ寄与
        //      配分で use-skill の台帳へ積まれる。トライデントの use-skill は剣と同じ LIGHT_WEAPONS
        //      なので「素振りだけで別スキルが上がる」問題が原理的に起きない。弓/弩は ARCHERY なので
        //      矢を1本も撃たずに ARCHERY が上がる ―― こちらは今も最低値に固定する。
        //  (2) 強さが剣を超えない: attack-speed 1.6 でも同系列の剣の 87.6〜92.3% にしかならない
        //      (弓は 103〜104%、弩は 112〜113% で剣を超える)。上限側の歯止めは MELEE_BAND の
        //      trident 帯 と noWeaponOutDpsesTheStrongestSwordOfItsLevel が引き続き効かせる。
        Set<String> ranged = Set.of("bow", "crossbow");
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Weapon w : loadWeapons()) {
            // 不変 Set は contains(null) でも NPE を投げる。
            if (w.type() == null || !ranged.contains(w.type())) {
                continue;
            }
            checked++;
            if (Math.abs(w.attackSpeed() - 0.1) > 1e-9) {
                problems.add(w.id() + " (" + w.type() + "): attack-speed " + w.attackSpeed());
            }
        }
        assertTrue(checked >= 30, "遠隔武器を " + checked + " 本しか読めていない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(),
                "遠隔武器の近接 attack-speed は 0.1(damage.yml の attack-speed.min-effective)で揃える:\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("attack-reach が武器種ごとの表と一致している（剣=バニラ3.0が基準）")
    void reachMatchesThePerTypeTable() {
        List<String> problems = new ArrayList<>();
        // 2026-08-18 (W-72): 狩人の投槍(hunter_javelin)を除外リストから表側へ移した。
        // 槍が 2.0 まで伸びたので 1.4 のままでは「game 内で最長」という決定(2026-08-02)が
        // 成り立たず、除外しておくと<b>その矛盾を検査が一切見なくなる</b>。javelin 型は
        // この1本しか無いので、表に 2.2 を書けば武器種の規約としてそのまま守れる。
        int checked = 0;
        for (Weapon w : loadWeapons()) {
            Double expected = REACH.get(w.type());
            if (expected == null) {
                continue;
            }
            checked++;
            if (Math.abs(w.reach() - expected) > 1e-6) {
                problems.add(String.format("%s (%s): attack-reach %.2f（表は %.2f = 実効 %.2f）",
                        w.id(), w.type(), w.reach(), expected, 3.0 + expected));
            }
        }
        assertTrue(checked >= 200, "リーチを見られた武器が " + checked + " 本しかない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(), "attack-reach が武器種の表と違う:\n"
                + String.join("\n", problems));
    }

    /**
     * {@code combat/stat-caps.yml} の {@code bleed-damage} 上限。出血は
     * {@code SymmetricCombatService#bleedFinalDamageFlat} 経由で<b>防御側ステをほぼ全部無視して
     * 体力へ直接入る</b>({@code BleedService#applyOneTick})ので、この上限が絶対の天井になる。
     * 「総DPSの何%」で出血を設計すると Lv100 では上限の 5.9 倍になり、超過分は無言で消える。
     */
    private static final double BLEED_CAP = 4500.0;

    @Test
    @DisplayName("鎌の出血が『総DPSの15%、ただし上限4500で打ち切り』になっている")
    void scytheBleedIsMeaningfulUpToTheCap() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Weapon w : loadWeapons()) {
            if (!"scythe".equals(w.type())) {
                continue;
            }
            checked++;
            if (w.bleed() <= 0) {
                problems.add(w.id() + ": 鎌なのに出血が無い");
                continue;
            }
            if (w.bleed() > BLEED_CAP + 1e-6) {
                problems.add(String.format("%s: 出血 %.0f が上限 %.0f を超えている（超過分は無言で消える）",
                        w.id(), w.bleed(), BLEED_CAP));
                continue;
            }
            double share = w.bleed() / w.dps();
            // 上限に張り付いている段では share がそれ以上伸ばせない。張り付いていない段は 15% 狙い。
            boolean atCap = Math.abs(w.bleed() - BLEED_CAP) < 1e-6;
            if (!atCap && (share < 0.08 || share > 0.25)) {
                problems.add(String.format("%s: 出血が総DPSの %.1f%%（狙いは 15%%、許容 8〜25%%）",
                        w.id(), share * 100));
            }
        }
        assertTrue(checked >= 12, "鎌を " + checked + " 本しか読めていない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("同じレベル帯で鎌が最も出血が大きい（出血は鎌の個性）")
    void scytheHasTheLargestBleedInItsLevel() {
        List<Weapon> weapons = loadWeapons();
        Map<Integer, Double> bestScythe = new TreeMap<>();
        for (Weapon w : weapons) {
            if ("scythe".equals(w.type()) && w.bleed() > 0) {
                bestScythe.merge(w.level(), w.bleed(), Math::max);
            }
        }
        assertTrue(bestScythe.size() >= 8, "鎌のレベル帯が " + bestScythe.size() + " しか無い");
        List<String> problems = new ArrayList<>();
        for (Weapon w : weapons) {
            if ("scythe".equals(w.type()) || w.bleed() <= 0) {
                continue;
            }
            Double scythe = bestScythe.get(w.level());
            if (scythe != null && w.bleed() > scythe + 1e-6) {
                problems.add(String.format("%s (%s, Lv%d): 出血 %.0f が同帯の鎌 %.0f を上回っている",
                        w.id(), w.type() == null ? "型不明" : w.type(), w.level(), w.bleed(), scythe));
            }
        }
        // 2026-08-05 の棚卸し前は cryocore_greataxe(4,164) が鎌(2,736)を上回っていた。
        assertTrue(problems.isEmpty(), "出血の一番手は鎌であること:\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("杖の詠唱DPS（attack-power / item-cooldown）が剣の 50% 前後にある")
    void wandCastingDpsIsTheIntendedModestBand() {
        List<Weapon> weapons = loadWeapons();
        // 杖は「同じ系列の剣」があればそれを分母に、無ければ同レベル帯で最強の剣に落とす
        // (magic / boundary 系列には剣が無いが、abyss には abyss_sword がある)。
        Map<String, Double> bySeries = swordBySeries(weapons);
        Map<Integer, Double> sword = swordByLevel(weapons);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Weapon w : weapons) {
            if (!"wand".equals(w.type())) {
                continue;
            }
            Double denominator = w.series() != null && bySeries.containsKey(w.series())
                    ? bySeries.get(w.series())
                    : sword.get(w.level());
            if (denominator == null) {
                continue;
            }
            checked++;
            double ratio = w.dps() / denominator;
            if (ratio < 0.40 || ratio > 0.60) {
                problems.add(String.format("%s (Lv%d): 詠唱DPSが剣の %.0f%%（狙いは 47.5%%、許容 40〜60%%）",
                        w.id(), w.level(), ratio * 100));
            }
        }
        assertTrue(checked >= 8, "杖を " + checked + " 本しか読めていない(この検査は空振りしている)");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }
}
