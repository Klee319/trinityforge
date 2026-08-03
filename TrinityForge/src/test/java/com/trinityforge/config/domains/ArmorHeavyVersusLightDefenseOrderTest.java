package com.trinityforge.config.domains;

import com.trinityforge.stats.StatKeys;
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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code stats/item-stats.yml} の <b>重装(HEAVY_ARMOR) / 軽装(LIGHT_ARMOR) の防御序列</b>を固定する
 * (2026-08-03 要件#17)。
 *
 * <h2>なぜ要るか — 序列が指示と逆だった</h2>
 * ユーザー指示は「重装を防御寄りに強化・軽装を弱化・重装は移動速度低下」。移動速度だけは入っていたが、
 * 肝心の防御が逆だった。2026-07-25 のラダー調整が重装と軽装に
 * <b>同じ {@code phys-flat-defense} と同じ {@code max-health}</b> を配り、差を
 * 回避 / 移動速度 / {@code armor-defense-rate} / {@code phys-resistance} だけに置いたのだが、
 * その {@code phys-resistance} が<b>軽装のほうが高かった</b>(Lv60 のフルセットで軽装 0.449 / 重装 0.260)。
 * 回避の優位と合わさって<b>軽装のほうが実効被ダメージが約16%少ない</b>状態になっていた。
 * この手の「数値表の向きが逆」という不具合はコンパイルもテストも通ってしまうので、
 * <b>値そのものを縛るテストが無いと戻したことに誰も気付けない</b>。
 *
 * <h2>直せる軸が2本しか無い理由(重要)</h2>
 * {@code phys-flat-defense} と {@code max-health} の<b>絶対値</b>は
 * {@code tools/config-editor/test/armor-ladder.test.js} が出荷 yml を実読して固定している
 * (S理論値20発 / 厳選なし6発 ±15%、および新規軽装7セットの守備力合計 = TABLE の fMax ±1%)。
 * つまり「重装の守備力を上げる」「重装の体力を上げる」のどちらも、やった瞬間にラダーが落ちる。
 * そこで序列づけは<b>軽装側の {@code phys-resistance} を下げる</b>のと
 * <b>軽装側の {@code fixed.max-health} を 0 にする</b>(体力ボーナスを重装の取り柄にする)の2本だけで行う。
 * 守備力はラダーの持ち物なので<b>重装と軽装で同値のまま</b>にしてある —
 * それ自体も {@link #armorLadderKeepsIdenticalFlatDefenseBetweenHeavyAndLight()} で固定している。
 *
 * <h2>実効被ダメージ係数の定義</h2>
 * {@code ComponentDamageCalculator} のパイプライン(守備力を先に減算 → 防御率 → 耐性)と
 * {@code SymmetricDamagePipeline}(回避は攻撃全体を無効化)をそのままなぞり、体力プールで正規化する:
 * <pre>
 *   coef = (1 - 回避)
 *        × (1 - min(defense-rate-max, armor-defense-rate × defense-rate-per-point))
 *        × (1 - phys-resistance)
 *        × (referenceHit(Lv) - phys-flat-defense) / referenceHit(Lv)
 *        × 20 / (20 + max-health)
 * </pre>
 * 各ステは<b>1セット4部位の合計</b>で、{@code fixed} と {@code random} の中央値
 * ({@code (min+max)/2}) を足したもの(= 品質0・平均ロールの装備1式)。
 * {@code per-quality} は防具では耐久しか動かないので無視する。
 *
 * <p><b>基準攻撃力は帯ごとに変える(2026-08-03 の査読指摘で修正)</b>:
 * 守備力(減算)と率(乗算)を同じ土俵に載せるには「1発の生ダメージ」が要るが、
 * ここに定数 400 を置くと Lv0 で {@code (400-5.265)/400 = 0.987} となり守備力が事実上消える。
 * 守備力が重装/軽装で同値な帯では約分されて無害だが、<b>守備力が違う唯一の組
 * (Lv0 の素の革 vs 銅)だけは結論が変わる</b>——実際、定数 400 のままだと
 * 「閾値を通すために素の革の物理耐性を半減する」という不要な実データ改変を招いた。
 * {@code tools/config-editor/test/armor-ladder.test.js} が帯基準攻撃力を
 * {@code A(Lv) = 7.0 × 1.03^Lv} と定義しているので、こちらもそれに合わせる。
 *
 * <h2>比較する組み合わせ</h2>
 * 「同じレベル帯の全重装 × 全軽装」の総当たりでは<b>ない</b>。防具は
 * <ul>
 *   <li><b>物理系</b>(銅/鎖/鉄/金/ダイヤ/ネザライト ↔ 革カタログ「幻膜」系)</li>
 *   <li><b>魔法防御系</b>(守護シリーズ ↔ 魔織/糸シリーズ)</li>
 * </ul>
 * の2系統に分かれていて、<b>魔法防御系はこのテストの対象外</b>。あちらは
 * {@code tools/config-editor/test/item-stat-coverage.test.js} が
 * {@code phys-resistance} / {@code phys-flat-defense} / {@code max-health} を
 * 「mage_* には未設定であること」で縛っているため、要件#17 で使ってよい軸が1本も残らない
 * (直すにはあのテストの契約を先に変える必要がある)。物理系のうち表に載っていない装備
 * (ダンジョン/ロール報酬の一点物)は3本目のテストが部位・レベル単位で拾う。
 */
class ArmorHeavyVersusLightDefenseOrderTest {

    /** 出荷 {@code combat/damage.yml} の {@code vanilla-armor.defense-rate-per-point} と一致していること。 */
    private static final double DEFENSE_RATE_PER_POINT = 0.015;
    /** 出荷 {@code combat/damage.yml} の {@code vanilla-armor.defense-rate-max} と一致していること。 */
    private static final double DEFENSE_RATE_MAX = 0.8;
    /**
     * 帯基準攻撃力 {@code A(Lv) = BASE × GROWTH^Lv} の底。
     * {@code tools/config-editor/test/armor-ladder.test.js} の定義と一致していること。
     */
    private static final double REFERENCE_HIT_BASE = 7.0;
    /** 帯基準攻撃力の成長率。同上。 */
    private static final double REFERENCE_HIT_GROWTH = 1.03;

    /** そのレベル帯で想定する「1発の生ダメージ」。守備力(減算)を率(乗算)と同じ土俵に載せるためだけに使う。 */
    private static double referenceHit(int level) {
        return REFERENCE_HIT_BASE * Math.pow(REFERENCE_HIT_GROWTH, Math.max(0, level));
    }
    /** バニラのプレイヤー最大体力(max-health はここへの加算)。 */
    private static final double VANILLA_BASE_HEALTH = 20.0;
    /** 重装は軽装の何倍以下の被ダメージであるべきか(0.80 = 20%以上少ない)。 */
    private static final double REQUIRED_HEAVY_RATIO = 0.80;

    private static final String K_ARMOR_RATE = StatKeys.canonical("armor-defense-rate");
    private static final String K_DODGE = StatKeys.canonical("dodge-chance");
    private static final String K_PHYS_RESISTANCE = StatKeys.canonical("phys-resistance");
    private static final String K_PHYS_FLAT = StatKeys.canonical("phys-flat-defense");
    private static final String K_MAX_HEALTH = StatKeys.canonical("max-health");

    private static final String HEAVY = "HEAVY_ARMOR";
    private static final String LIGHT = "LIGHT_ARMOR";

    // === 出荷 yml の読み込み ===

    private static YamlConfiguration loadShippedYaml(String path) throws IOException {
        try (InputStream in = ArmorHeavyVersusLightDefenseOrderTest.class.getClassLoader()
                .getResourceAsStream(path.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /**
     * 防具1部位。{@code total} は {@code fixed + random の中央値}、{@code fixedOnly} は {@code fixed} だけ。
     * 2つ持つのは「表の値を戻した」ことと「random だけで賄っている(=触っていない)」ことを区別するため。
     */
    private record ArmorPiece(String id, String slot, int level, String useSkill,
                              Map<String, Double> total, Map<String, Double> fixedOnly) {
    }

    private static Map<String, ArmorPiece> shippedArmorPieces() throws IOException {
        ConfigurationSection items = loadShippedYaml(ItemStatsConfig.PATH).getConfigurationSection("items");
        assertNotNull(items, "出荷 item-stats.yml に items セクションが無い");

        Map<String, ArmorPiece> pieces = new LinkedHashMap<>();
        for (String itemId : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemId);
            if (item == null) {
                continue;
            }
            String useSkill = item.getString("use-skill");
            if (!HEAVY.equals(useSkill) && !LIGHT.equals(useSkill)) {
                continue;
            }
            String slot = slotOf(itemId);
            if (slot == null) {
                continue;
            }
            Map<String, Double> fixedOnly = new LinkedHashMap<>();
            accumulateFixed(fixedOnly, item.getConfigurationSection("fixed"));
            Map<String, Double> total = new LinkedHashMap<>(fixedOnly);
            accumulateRandomMidpoint(total, item.getConfigurationSection("random"));
            pieces.put(itemId, new ArmorPiece(itemId, slot,
                    item.getInt("use-level-requirement", 0), useSkill, total, fixedOnly));
        }
        assertTrue(pieces.size() >= 100,
                "出荷 item-stats.yml から拾えた防具が " + pieces.size()
                        + " 件しかない。use-skill(HEAVY_ARMOR/LIGHT_ARMOR)が消えているか、"
                        + "items セクションの構造が変わっている。");
        return pieces;
    }

    /** {@code LEATHER_HELMET#200124} → {@code HELMET}。4部位以外(盾など)は null。 */
    private static String slotOf(String itemId) {
        String material = itemId.contains("#") ? itemId.substring(0, itemId.indexOf('#')) : itemId;
        int underscore = material.lastIndexOf('_');
        if (underscore < 0) {
            return null;
        }
        String suffix = material.substring(underscore + 1);
        return switch (suffix) {
            case "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS" -> suffix;
            default -> null;
        };
    }

    private static void accumulateFixed(Map<String, Double> into, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String rawKey : section.getKeys(false)) {
            if (!section.isDouble(rawKey) && !section.isInt(rawKey) && !section.isLong(rawKey)) {
                continue;
            }
            into.merge(StatKeys.canonical(rawKey), section.getDouble(rawKey), Double::sum);
        }
    }

    private static void accumulateRandomMidpoint(Map<String, Double> into, ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String rawKey : section.getKeys(false)) {
            ConfigurationSection range = section.getConfigurationSection(rawKey);
            if (range == null) {
                continue;
            }
            double midpoint = (range.getDouble("min", 0.0) + range.getDouble("max", 0.0)) / 2.0;
            into.merge(StatKeys.canonical(rawKey), midpoint, Double::sum);
        }
    }

    // === 対になるシリーズの表 ===

    /** 1セット(4部位)。 */
    private record ArmorSet(String label, List<String> pieceIds) {
    }

    /**
     * 同じ必要レベルで対になる重装/軽装のフルセット。
     *
     * @param sameLadderBand 2026-07-25 ラダーの「同じ帯」から生成された対か。
     *                       true のときだけ守備力の完全同値を要求する
     *                       (素の革だけは銅より1帯下の入門装備なので false)。
     */
    private record Matchup(int level, ArmorSet heavy, ArmorSet light, boolean sameLadderBand) {
    }

    private static ArmorSet set(String label, String helmet, String chest, String legs, String boots) {
        return new ArmorSet(label, List.of(helmet, chest, legs, boots));
    }

    private static final ArmorSet COPPER =
            set("銅(重装)", "COPPER_HELMET", "COPPER_CHESTPLATE", "COPPER_LEGGINGS", "COPPER_BOOTS");
    private static final ArmorSet CHAINMAIL = set("鎖(重装)",
            "CHAINMAIL_HELMET", "CHAINMAIL_CHESTPLATE", "CHAINMAIL_LEGGINGS", "CHAINMAIL_BOOTS");
    private static final ArmorSet IRON =
            set("鉄(重装)", "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS");
    private static final ArmorSet GOLDEN =
            set("金(重装)", "GOLDEN_HELMET", "GOLDEN_CHESTPLATE", "GOLDEN_LEGGINGS", "GOLDEN_BOOTS");
    private static final ArmorSet DIAMOND = set("ダイヤ(重装)",
            "DIAMOND_HELMET", "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_BOOTS");
    private static final ArmorSet NETHERITE = set("ネザライト(重装)",
            "NETHERITE_HELMET", "NETHERITE_CHESTPLATE", "NETHERITE_LEGGINGS", "NETHERITE_BOOTS");

    private static final List<Matchup> MATCHUPS = List.of(
            // 物理系: バニラ素材(重装) ↔ 革カタログ(軽装)。魔法防御系は対象外(クラスの Javadoc 参照)。
            new Matchup(0, COPPER, set("素の革(軽装)",
                    "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS"), false),
            new Matchup(0, COPPER, set("骨鎧(軽装)",
                    "LEATHER_HELMET#200124", "LEATHER_CHESTPLATE#200125",
                    "LEATHER_LEGGINGS#200126", "LEATHER_BOOTS#200127"), true),
            new Matchup(10, CHAINMAIL, set("銅鋲の革鎧(軽装)",
                    "LEATHER_HELMET#200128", "LEATHER_CHESTPLATE#200129",
                    "LEATHER_LEGGINGS#200130", "LEATHER_BOOTS#200131"), true),
            new Matchup(15, IRON, set("甲殻鎧(軽装)",
                    "LEATHER_HELMET#200132", "LEATHER_CHESTPLATE#200133",
                    "LEATHER_LEGGINGS#200134", "LEATHER_BOOTS#200135"), true),
            new Matchup(35, GOLDEN, set("金糸の装束(軽装)",
                    "LEATHER_HELMET#200136", "LEATHER_CHESTPLATE#200137",
                    "LEATHER_LEGGINGS#200138", "LEATHER_BOOTS#200139"), true),
            new Matchup(45, DIAMOND, set("深海鱗の鎧(軽装)",
                    "LEATHER_HELMET#200140", "LEATHER_CHESTPLATE#200141",
                    "LEATHER_LEGGINGS#200142", "LEATHER_BOOTS#200143"), true),
            new Matchup(60, NETHERITE, set("幻膜の外套(軽装)",
                    "LEATHER_HELMET#200144", "LEATHER_CHESTPLATE#200145",
                    "LEATHER_LEGGINGS#200146", "LEATHER_BOOTS#200147"), true),
            new Matchup(80, set("要塞(重装)",
                    "NETHERITE_HELMET#150", "NETHERITE_CHESTPLATE#153",
                    "NETHERITE_LEGGINGS#156", "NETHERITE_BOOTS#159"),
                    set("蝕みの絹(軽装)",
                            "LEATHER_HELMET#200148", "LEATHER_CHESTPLATE#200149",
                            "LEATHER_LEGGINGS#200150", "LEATHER_BOOTS#200151"), true),
            new Matchup(100, set("不滅(重装)",
                    "NETHERITE_HELMET#148", "NETHERITE_CHESTPLATE#151",
                    "NETHERITE_LEGGINGS#154", "NETHERITE_BOOTS#157"),
                    set("天陰(軽装)",
                            "NETHERITE_HELMET#149", "NETHERITE_CHESTPLATE#152",
                            "NETHERITE_LEGGINGS#155", "NETHERITE_BOOTS#158"), true));

    // === 係数計算 ===

    private static List<ArmorPiece> resolve(Map<String, ArmorPiece> pieces, ArmorSet armorSet) {
        List<ArmorPiece> resolved = new ArrayList<>(4);
        for (String id : armorSet.pieceIds()) {
            ArmorPiece piece = pieces.get(id);
            assertNotNull(piece, armorSet.label() + " の " + id + " が出荷 item-stats.yml に無い");
            resolved.add(piece);
        }
        return resolved;
    }

    private static double sum(List<ArmorPiece> armorSet, String key, boolean fixedOnly) {
        double total = 0.0;
        for (ArmorPiece piece : armorSet) {
            total += (fixedOnly ? piece.fixedOnly() : piece.total()).getOrDefault(key, 0.0);
        }
        return total;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 1式装備したときの実効被ダメージ係数(小さいほど硬い)。{@code level} はその帯の基準攻撃力に使う。 */
    private static double damageTakenCoefficient(List<ArmorPiece> armorSet, int level) {
        double dodge = clamp01(sum(armorSet, K_DODGE, false));
        double defenseRate = Math.min(DEFENSE_RATE_MAX,
                Math.max(0.0, sum(armorSet, K_ARMOR_RATE, false) * DEFENSE_RATE_PER_POINT));
        double resistance = clamp01(sum(armorSet, K_PHYS_RESISTANCE, false));
        double flat = Math.max(0.0, sum(armorSet, K_PHYS_FLAT, false));
        double health = VANILLA_BASE_HEALTH + Math.max(0.0, sum(armorSet, K_MAX_HEALTH, false));
        double hit = referenceHit(level);
        double throughFlat = Math.max(0.0, hit - flat) / hit;
        return (1 - dodge) * (1 - defenseRate) * (1 - resistance) * throughFlat
                * (VANILLA_BASE_HEALTH / health);
    }

    // === テスト ===

    @Test
    @DisplayName("同レベル帯のフルセットで、重装の実効被ダメージが軽装より20%以上少ない(序列が逆転したら落ちる)")
    void heavyArmorTakesAtLeastTwentyPercentLessDamageThanLightAtEveryTier() throws IOException {
        Map<String, ArmorPiece> pieces = shippedArmorPieces();
        assertEquals(9, MATCHUPS.size(),
                "対になるシリーズの表が痩せている。物理系の帯を消したなら理由を Javadoc に書くこと");

        for (Matchup matchup : MATCHUPS) {
            double heavyCoef = damageTakenCoefficient(resolve(pieces, matchup.heavy()), matchup.level());
            double lightCoef = damageTakenCoefficient(resolve(pieces, matchup.light()), matchup.level());
            assertTrue(heavyCoef <= lightCoef * REQUIRED_HEAVY_RATIO,
                    String.format("Lv%d %s vs %s: 重装の実効被ダメージ係数 %.5f が軽装 %.5f の %.1f%% でしかない"
                                    + "(%.0f%% 以下であるべき)。軽装の phys-resistance を上げ戻したか、"
                                    + "軽装に fixed.max-health を戻したはず。",
                            matchup.level(), matchup.heavy().label(), matchup.light().label(),
                            heavyCoef, lightCoef, heavyCoef / lightCoef * 100.0,
                            REQUIRED_HEAVY_RATIO * 100.0));
        }
    }

    @Test
    @DisplayName("対になる重装/軽装で 物理耐性と最大体力の2軸がどちらも重装優位(片方だけ戻しても落ちる)")
    void everyDefenceAxisKeepsHeavyAheadOfLight() throws IOException {
        Map<String, ArmorPiece> pieces = shippedArmorPieces();

        for (Matchup matchup : MATCHUPS) {
            List<ArmorPiece> heavy = resolve(pieces, matchup.heavy());
            List<ArmorPiece> light = resolve(pieces, matchup.light());
            String where = String.format("Lv%d %s vs %s", matchup.level(),
                    matchup.heavy().label(), matchup.light().label());

            double heavyResistance = sum(heavy, K_PHYS_RESISTANCE, false);
            double lightResistance = sum(light, K_PHYS_RESISTANCE, false);
            assertTrue(heavyResistance > lightResistance,
                    where + ": 物理耐性 重装 " + heavyResistance + " ≦ 軽装 " + lightResistance
                            + "。軽装の phys-resistance は同レベル・同部位の重装の 0.60 倍が上限。");

            // 体力ボーナスは重装の取り柄。軽装の fixed は 0 でなければならない
            // (random の上振れ分は軽装にも残してあるので fixed だけを見る)。
            double heavyHealth = sum(heavy, K_MAX_HEALTH, true);
            double lightHealth = sum(light, K_MAX_HEALTH, true);
            assertEquals(0.0, lightHealth, 1e-9,
                    where + ": 軽装の fixed.max-health 合計が " + lightHealth
                            + "(0 であるべき)。ラダーが守備力と体力の絶対値を握っているので、"
                            + "軽装の体力を戻すと重装との差が消える。");
            assertTrue(heavyHealth > lightHealth,
                    where + ": 最大体力 重装 " + heavyHealth + " ≦ 軽装 " + lightHealth);
        }
    }

    @Test
    @DisplayName("守備力は 2026-07-25 のラダーの持ち物なので、同じ帯の重装/軽装で同値のまま(片方を動かすとJS側のラダーが落ちる)")
    void armorLadderKeepsIdenticalFlatDefenseBetweenHeavyAndLight() throws IOException {
        Map<String, ArmorPiece> pieces = shippedArmorPieces();

        for (Matchup matchup : MATCHUPS) {
            List<ArmorPiece> heavy = resolve(pieces, matchup.heavy());
            List<ArmorPiece> light = resolve(pieces, matchup.light());
            double heavyFlat = sum(heavy, K_PHYS_FLAT, true);
            double lightFlat = sum(light, K_PHYS_FLAT, true);
            String where = String.format("Lv%d %s vs %s", matchup.level(),
                    matchup.heavy().label(), matchup.light().label());

            if (matchup.sameLadderBand()) {
                assertEquals(heavyFlat, lightFlat, 1e-6,
                        where + ": 守備力(fixed 合計)が重装 " + heavyFlat + " / 軽装 " + lightFlat
                                + " とずれている。ここは要件#17 で触ってよい軸ではない —"
                                + " tools/config-editor/test/armor-ladder.test.js が"
                                + "「新規軽装7セットの守備力合計 = TABLE の fMax」と"
                                + "「S理論値20発/厳選なし6発」で両側を固定している。");
            } else {
                assertTrue(heavyFlat >= lightFlat,
                        where + ": 守備力(fixed 合計)が重装 " + heavyFlat + " < 軽装 " + lightFlat);
            }
        }
    }

    @Test
    @DisplayName("同じ必要レベル・同じ部位では軽装の物理耐性が重装を超えない(ダンジョン/ロール報酬の戻しも捕まえる)")
    void noLightArmorPieceOutResistsTheHeavyArmorOfItsOwnLevelAndSlot() throws IOException {
        Map<String, ArmorPiece> pieces = shippedArmorPieces();

        // 各 (必要レベル / 部位) バケットで「物理耐性が最も高い」重装と軽装を1つずつ拾う。
        Map<String, ArmorPiece> topHeavyByBucket = new TreeMap<>();
        Map<String, ArmorPiece> topLightByBucket = new TreeMap<>();
        for (ArmorPiece piece : pieces.values()) {
            String bucket = piece.level() + "/" + piece.slot();
            Map<String, ArmorPiece> target = HEAVY.equals(piece.useSkill())
                    ? topHeavyByBucket : topLightByBucket;
            ArmorPiece current = target.get(bucket);
            double candidate = piece.fixedOnly().getOrDefault(K_PHYS_RESISTANCE, 0.0);
            double incumbent = current == null ? -1.0
                    : current.fixedOnly().getOrDefault(K_PHYS_RESISTANCE, 0.0);
            if (current == null || candidate > incumbent) {
                target.put(bucket, piece);
            }
        }

        int checked = 0;
        for (Map.Entry<String, ArmorPiece> entry : topLightByBucket.entrySet()) {
            ArmorPiece heaviest = topHeavyByBucket.get(entry.getKey());
            if (heaviest == null) {
                // その必要レベル・部位に重装が存在しない(例: Lv25 のソースジェム系は軽装だけ)。
                continue;
            }
            double light = entry.getValue().fixedOnly().getOrDefault(K_PHYS_RESISTANCE, 0.0);
            double heavy = heaviest.fixedOnly().getOrDefault(K_PHYS_RESISTANCE, 0.0);
            assertTrue(light <= heavy + 1e-9,
                    entry.getKey() + ": 軽装 " + entry.getValue().id() + " の物理耐性 " + light
                            + " が同レベル同部位の重装 " + heaviest.id() + " の " + heavy + " を上回っている");
            checked++;
        }
        assertTrue(checked >= 20,
                "レベル×部位の突き合わせが " + checked + " 件しか成立しなかった。"
                        + "use-level-requirement か use-skill の付け方が変わっている疑いがある。");
    }

    @Test
    @DisplayName("damage.yml の defense-rate-per-point / defense-rate-max がこのテストの計算前提と一致している")
    void theShippedVanillaArmorConstantsMatchTheModelUsedHere() throws IOException {
        ConfigurationSection vanillaArmor =
                loadShippedYaml(CombatDamageConfig.PATH).getConfigurationSection("vanilla-armor");
        assertNotNull(vanillaArmor, "出荷 combat/damage.yml に vanilla-armor セクションが無い");
        assertEquals(DEFENSE_RATE_PER_POINT, vanillaArmor.getDouble("defense-rate-per-point"), 1e-9,
                "防具値1点あたりの軽減率が変わった。このテストの係数モデルも直すこと");
        assertEquals(DEFENSE_RATE_MAX, vanillaArmor.getDouble("defense-rate-max"), 1e-9,
                "防具値による軽減率の上限が変わった。このテストの係数モデルも直すこと");
    }
}
