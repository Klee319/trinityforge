package com.trinityforge.combat;

import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>重武器(HEAVY_WEAPONS)と軽武器(LIGHT_WEAPONS)の実効DPS差を、出荷 yml の実測で固定する</b>
 * 回帰テスト(2026-08-01 U5)。
 *
 * <h2>なぜ要るか</h2>
 * 武器クラス間のDPSパリティを守るテストはこれまで1本も無く、片方のクラスだけを触った瞬間に
 * バランスが無言で壊れる状態だった。実際 2026-08-01 の要件1b({@code efb822a})で
 * HEAVY_WEAPONS だけに {@code attack-power ×1.25 / attack-speed ×0.85} を掛けた結果、
 * 実効DPSが軽武器比 <b>+24%</b> まで開いていたが、誰も気付けなかった。
 *
 * <h2>実効DPSの正しい測り方 — {@code attack-power × attack-speed} は誤り</h2>
 * 素朴な積で測ると重武器が軽武器の半分(H/L≈0.49)という<b>真逆</b>の結論が出る。実機の
 * 手数は次の2つで頭打ちになるので、両方を必ず入れる:
 * <ol>
 *   <li><b>被弾側の無敵時間(10 tick)</b>。同一の敵へ入る有効打は最大 2発/秒。よって
 *       {@code attack-speed} 4.12 の軽武器は 2.0 相当までしか活かせず、手数の優位を失う。
 *       TF は無敵時間を変更していない(({@code NoDamageTicks} を触るコードが無い)ので、
 *       この上限はバニラのまま効く。</li>
 *   <li><b>連打減衰({@link MeleeChargeMultiplier})</b>。振り間隔を詰めるほど一撃が軽くなる。
 *       パラメータは出荷 {@code combat/damage.yml} から読むので、あちらを触れば
 *       ここも自動で追随する(ハードコードしない)。</li>
 * </ol>
 * プレイヤーは振り間隔 {@code T} を自由に選べるので、{@code T ≥ 0.5秒}(=無敵時間の下限)の
 * 範囲で DPS が最大になる振り方を採る前提で比較する。{@code chargeMult(T)/T} は
 * {@code T} に対して<b>下に凸</b>(内部の停留点は極小)なので、最大値は必ず端点
 * ——「上限一杯の連打(T=0.5秒)」か「フルチャージ(T=1/AS秒)」のどちらか——に来る。
 * ここへさらに {@code damage-modifier} の期待値を掛けたものを実効DPSとする。
 *
 * <h2>比較の単位 — 帯 × 同格アーキタイプ</h2>
 * 帯は {@code use-level-requirement}。同格の対応付けは出荷データの
 * {@code attack-speed} 署名で決まっている({@link #ARCHETYPE_PAIRS})。
 * 帯平均や「帯の最強同士」で測ると、斧/メイス/槍/トライデントのような役割の違う品が
 * 混ざって差が薄まり、+24% の開きが +8% にしか見えなくなる。
 */
class WeaponDpsParityTest {

    /** 被弾側の無敵時間 10 tick = 同一の敵へ入る有効打の上限(発/秒)。 */
    private static final double HIT_RATE_CAP_PER_SECOND = 2.0;

    /** 上記の裏返し: これより短い間隔で振っても有効打にならない(tick)。 */
    private static final int MIN_SWING_INTERVAL_TICKS = 10;

    /** U5 の目標帯の上端(+15%)。丸め誤差ぶんだけ緩めた実効しきい値。 */
    private static final double MAX_HEAVY_OVER_LIGHT = 1.16;

    /**
     * 目標帯の下端。重武器が軽武器を大きく下回ると「重武器を選ぶ理由が無い」になる。
     * GOLD 帯だけは {@code damage-modifier} が全品 0.98〜0.99 で揃っており、重武器側の
     * damage-modifier 優位(0.84〜0.90 vs 0.50〜0.65)が消える設計になっているため、
     * 構造的に軽武器有利へ振れる。その帯を落とさない高さに置いてある。
     */
    private static final double MIN_HEAVY_OVER_LIGHT = 0.85;

    /**
     * 同格アーキタイプの対応表 {@code {重武器の武器種, 軽武器の武器種}}。
     * 対応は ウォーハンマー/剣、大斧/レイピア、大剣/短剣。
     *
     * <p><b>2026-08-09: attack-speed 署名から武器種へ鍵を張り替えた。</b> 元は
     * {@code {1.003, 1.600} / {0.833, 1.780} / {0.952, 2.000}} という attack-speed の組で
     * 同格を引いていたが、これは「武器種ごとに attack-speed が一意」という前提に乗った
     * <b>代理キー</b>でしかなかった。小数を表示桁(1桁)へ揃える校正でウォーハンマー 1.003 と
     * 大剣 0.952 がどちらも 1.0 になり、代理キーが2つの武器種を区別できなくなった
     * (両方のペアでウォーハンマーが選ばれ、大剣/短剣の比が 1.28 に化けた)。
     * 武器種そのもので引けば、この表は attack-speed の校正から独立する。
     *
     * <p>張り替え前後で出荷データの実測値は変わらない(どちらも30件成立・比 0.893〜1.152)
     * ことを確認済み。<b>意味は変えていない</b>。
     */
    private static final String[][] ARCHETYPE_PAIRS = {
        {"warhammer", "sword"},
        {"greataxe", "rapier"},
        {"grate_sword", "dagger"},
    };

    /**
     * id の接尾辞から武器種を引く。長い接尾辞を先に置く(grate_sword が sword に食われないため)。
     * {@code WeaponTierParityTest.TYPES} と同じ規約。
     */
    private static final List<String> TYPES = List.of(
            "grate_sword", "greataxe", "warhammer", "crossbow", "trident", "javelin",
            "halberd", "scythe", "rapier", "dagger", "spear", "sword", "mace", "wand", "bow", "axe");

    /**
     * 同格の対応から外す一点物。キーは<b>item-stats.yml のエントリ名</b>(catalog の id ではない)。
     * 広辞苑({@code BOOK#100004} = koujien)は「特殊武器」カテゴリの一点物で
     * {@code attack-speed} 2.0 なので、attack-speed 署名で同格を引いていた頃は
     * <b>Lv0 帯で短剣/鎌の代表として大剣の比較対象になっていた</b>(アーキタイプの代表ではない)。
     * 武器種で引くようになった今は id に武器種の接尾辞が無いので二重に外れるが、
     * 「一点物はアーキタイプの代表にしない」という意図の明示として残す。
     */
    private static final Set<String> ARCHETYPE_EXEMPT = Set.of("BOOK#100004");

    /**
     * 実際に比較できたペアの下限。出荷データでは 10帯 × 3ペア = 30件 成立する。
     * ここを設けないと、対応表が古くなった瞬間に「1件も比較しないまま緑」になる。
     */
    private static final int MIN_COMPARISONS = 27;

    private record Weapon(String id, String type, String skill, int band,
                          double attackPower, double attackSpeed, double damageModifier) {
    }

    // === モデル ===

    /**
     * 振り間隔を最適化したときの「1秒あたりの実効的な一撃回数」。
     * 端点2つ(上限一杯の連打 / フルチャージ)の大きい方。
     * 減衰カーブそのものは本番コード {@link MeleeChargeMultiplier} を呼んで使う。
     */
    static double bestHitRateFactor(double attackSpeed, boolean chargeEnabled,
                                    double chargeMin, double chargeExponent) {
        double atCap = MeleeChargeMultiplier.compute(
                chargeEnabled, MIN_SWING_INTERVAL_TICKS, attackSpeed, chargeMin, chargeExponent)
                * HIT_RATE_CAP_PER_SECOND;
        // フルチャージで振ると 1/AS 秒に1発 = AS 発/秒。ただし無敵時間の上限は越えられない。
        double atFullCharge = Math.min(attackSpeed, HIT_RATE_CAP_PER_SECOND);
        return Math.max(atCap, atFullCharge);
    }

    /**
     * {@code damage-modifier} は毎撃 {@code Uniform[min(1,端点), max(1,端点)]} で振られるので、
     * DPS 比較にはその期待値を使う(端点そのものではない)。正規化は本番コードを共用する。
     */
    static double expectedDamageModifier(double authored) {
        double endpoint = ComponentDamageCalculator.normalizeDamageModifierEndpoint(authored);
        return (Math.min(1.0, endpoint) + Math.max(1.0, endpoint)) / 2.0;
    }

    static double effectiveDps(Weapon w, boolean chargeEnabled, double chargeMin, double chargeExponent) {
        return w.attackPower()
                * expectedDamageModifier(w.damageModifier())
                * bestHitRateFactor(w.attackSpeed(), chargeEnabled, chargeMin, chargeExponent);
    }

    // === 出荷 yml の読み出し ===

    private static YamlConfiguration shipped(String path) throws IOException {
        try (InputStream in = WeaponDpsParityTest.class.getClassLoader()
                .getResourceAsStream(path.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    /** {@code catalog.yml} の {@code "MATERIAL#CMD"} → id。武器種は id の接尾辞からしか引けない。 */
    private static Map<String, String> catalogIdByStatKey() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        ConfigurationSection catalogItems = shipped("items/catalog.yml").getConfigurationSection("items");
        assertNotNull(catalogItems, "出荷 catalog.yml に items セクションが無い");
        for (String id : catalogItems.getKeys(false)) {
            ConfigurationSection def = catalogItems.getConfigurationSection(id);
            if (def == null) {
                continue;
            }
            String material = def.getString("material");
            if (material == null) {
                continue;
            }
            out.put(def.contains("custom-model-data")
                    ? material + "#" + def.getInt("custom-model-data") : material, id);
        }
        return out;
    }

    private static List<Weapon> shippedWeapons() throws IOException {
        ConfigurationSection items = shipped(ItemStatsConfig.PATH).getConfigurationSection("items");
        assertNotNull(items, "出荷 item-stats.yml に items セクションが無い");
        Map<String, String> idByStatKey = catalogIdByStatKey();
        List<Weapon> out = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item == null) {
                continue;
            }
            String skill = item.getString("use-skill");
            if (!"HEAVY_WEAPONS".equals(skill) && !"LIGHT_WEAPONS".equals(skill)) {
                continue;
            }
            ConfigurationSection fixed = item.getConfigurationSection("fixed");
            if (fixed == null || !fixed.isSet("attack-power") || !fixed.isSet("attack-speed")) {
                continue;
            }
            String lower = idByStatKey.getOrDefault(id, id).toLowerCase(java.util.Locale.ROOT);
            String type = TYPES.stream()
                    .filter(t -> lower.endsWith(t) || lower.endsWith(t + "_tf"))
                    .findFirst().orElse(null);
            out.add(new Weapon(id, type, skill, item.getInt("use-level-requirement", 0),
                    fixed.getDouble("attack-power"), fixed.getDouble("attack-speed"),
                    fixed.getDouble("damage-modifier", 1.0)));
        }
        assertTrue(out.size() > 100,
                "出荷 item-stats.yml から拾えた近接武器が " + out.size()
                        + " 件しかない。use-skill か fixed の構造が変わっている疑い。");
        assertTrue(out.stream().filter(w -> w.type() != null).count() > 100,
                "武器種を引けた近接武器が少なすぎる。catalog.yml の id 規約か TYPES の接尾辞が変わっている疑い"
                        + "(ここが空振りすると同格の対応が1件も成立しない)。");
        return out;
    }

    private static Optional<Weapon> strongest(List<Weapon> pool, String skill, int band, String type,
                                              boolean chargeEnabled, double chargeMin, double chargeExponent) {
        return pool.stream()
                .filter(w -> w.skill().equals(skill) && w.band() == band
                        && !ARCHETYPE_EXEMPT.contains(w.id())
                        && type.equals(w.type()))
                .max((a, b) -> Double.compare(
                        effectiveDps(a, chargeEnabled, chargeMin, chargeExponent),
                        effectiveDps(b, chargeEnabled, chargeMin, chargeExponent)));
    }

    // === 本体 ===

    @Test
    @DisplayName("同格ペアの実効DPS比 H/L が、どの帯でも 0.85〜1.16 に収まる(U5: 目標帯 +0〜+15%)")
    void heavyStaysWithinFifteenPercentOfLight() throws IOException {
        YamlConfiguration damage = shipped(CombatDamageConfig.PATH);
        boolean chargeEnabled = damage.getBoolean("melee-charge.enabled", true);
        double chargeMin = damage.getDouble("melee-charge.min-multiplier", 0.2);
        double chargeExponent = damage.getDouble("melee-charge.exponent", 2.0);
        List<Weapon> weapons = shippedWeapons();

        Map<String, Double> observed = new LinkedHashMap<>();
        int comparisons = 0;
        for (int band : new TreeSet<>(weapons.stream().map(Weapon::band).toList())) {
            for (String[] pair : ARCHETYPE_PAIRS) {
                Optional<Weapon> heavy = strongest(weapons, "HEAVY_WEAPONS", band, pair[0],
                        chargeEnabled, chargeMin, chargeExponent);
                Optional<Weapon> light = strongest(weapons, "LIGHT_WEAPONS", band, pair[1],
                        chargeEnabled, chargeMin, chargeExponent);
                if (heavy.isEmpty() || light.isEmpty()) {
                    continue;
                }
                double hd = effectiveDps(heavy.get(), chargeEnabled, chargeMin, chargeExponent);
                double ld = effectiveDps(light.get(), chargeEnabled, chargeMin, chargeExponent);
                // W-72 のリーチ補正は「重武器 = 長リーチ」を意図的に弱くしているので、
                // その意図ぶんを割り戻してから帯へ当てる。割り戻さないと、重武器/軽武器の
                // パリティは壊れていないのに(=このテストが守っている設計は無傷なのに)落ちる。
                double intended = WeaponTierParityTest.REACH_DPS_FACTOR.getOrDefault(pair[0], 1.0)
                        / WeaponTierParityTest.REACH_DPS_FACTOR.getOrDefault(pair[1], 1.0);
                double ratio = hd / ld / intended;
                comparisons++;
                String label = "Lv" + band + " " + heavy.get().id() + " / " + light.get().id();
                observed.put(label, ratio);
                String reachNote = Math.abs(intended - 1.0) < 1e-9 ? ""
                        : String.format("(素の比 %.4f ÷ W-72 のリーチ補正 %.4f)", hd / ld, intended);
                assertTrue(ratio <= MAX_HEAVY_OVER_LIGHT,
                        label + " の実効DPS比が " + String.format("%.4f", ratio) + reachNote + " で上限 "
                                + MAX_HEAVY_OVER_LIGHT + " を超えている。重武器の目標帯は"
                                + "「軽武器と同等〜+15%」。attack-power を上げたなら下げ直すこと"
                                + "(attack-speed ×0.85 は一撃の重さで差を付ける設計の根幹なので、"
                                + "そちらで調整しない)。実効DPS = attack-power × damage-modifier期待値 ×"
                                + " 手数係数、手数係数は無敵時間10tick(最大2発/秒)と melee-charge で決まる。");
                assertTrue(ratio >= MIN_HEAVY_OVER_LIGHT,
                        label + " の実効DPS比が " + String.format("%.4f", ratio) + reachNote + " で下限 "
                                + MIN_HEAVY_OVER_LIGHT + " を割っている。重武器は手数を捨てている分、"
                                + "一撃で取り返せないと選ぶ理由が無くなる。");
            }
        }
        assertTrue(comparisons >= MIN_COMPARISONS,
                "同格ペアの比較が " + comparisons + " 件しか成立しなかった(最低 " + MIN_COMPARISONS
                        + " 件)。ARCHETYPE_PAIRS は catalog.yml の id 接尾辞(武器種)で同格を"
                        + "対応付けているので、武器種の命名規約を変えたらこの表と TYPES も更新すること。"
                        + "更新しないと『1件も比較しないまま緑』になる。実測: " + observed);
    }

    @Test
    @DisplayName("どの帯でも、重武器1本が『その帯の最強の軽武器』を +15% 超で上回らない")
    void noHeavyWeaponOutDpsesTheBestLightOption() throws IOException {
        YamlConfiguration damage = shipped(CombatDamageConfig.PATH);
        boolean chargeEnabled = damage.getBoolean("melee-charge.enabled", true);
        double chargeMin = damage.getDouble("melee-charge.min-multiplier", 0.2);
        double chargeExponent = damage.getDouble("melee-charge.exponent", 2.0);
        List<Weapon> weapons = shippedWeapons();

        for (int band : new TreeSet<>(weapons.stream().map(Weapon::band).toList())) {
            double bestLight = weapons.stream()
                    .filter(w -> w.band() == band && w.skill().equals("LIGHT_WEAPONS"))
                    .mapToDouble(w -> effectiveDps(w, chargeEnabled, chargeMin, chargeExponent))
                    .max().orElse(0.0);
            if (bestLight <= 0.0) {
                continue;
            }
            for (Weapon heavy : weapons) {
                if (heavy.band() != band || !heavy.skill().equals("HEAVY_WEAPONS")) {
                    continue;
                }
                double ratio = effectiveDps(heavy, chargeEnabled, chargeMin, chargeExponent) / bestLight;
                assertTrue(ratio <= MAX_HEAVY_OVER_LIGHT,
                        "Lv" + band + " の " + heavy.id() + " が、同帯で最も強い軽武器を "
                                + String.format("%.1f%%", (ratio - 1.0) * 100.0)
                                + " 上回っている。ARCHETYPE_PAIRS が古くなっていても効く"
                                + "『重武器が一方的に上位互換にならない』側の歯止め。");
            }
        }
    }

    // === モデル自体の自己検証(思い違いで真逆の結論を出さないための固定) ===

    @Test
    @DisplayName("手数係数は無敵時間で頭打ちになる(速い軽武器は attack-speed 4.12 でも 2.0 相当)")
    void hitRateIsCappedByInvulnerabilityWindow() {
        assertTrue(bestHitRateFactor(4.12, true, 0.1, 1.6) == HIT_RATE_CAP_PER_SECOND,
                "attack-speed 4.12 の手数係数が 2.0 になっていない。無敵時間10tickの上限が"
                        + "モデルから抜けると、軽武器を過大評価して H/L が真逆に出る。");
        assertTrue(bestHitRateFactor(2.0, true, 0.1, 1.6) == HIT_RATE_CAP_PER_SECOND,
                "attack-speed 2.0 は上限ちょうど。");
        // 遅い武器はフルチャージで振るのが最適(= attack-speed そのものが手数係数になる)。
        assertTrue(Math.abs(bestHitRateFactor(1.6, true, 0.1, 1.6) - 1.6) < 1.0e-9,
                "attack-speed 1.6 の最適は毎回フルチャージ = 1.6 発/秒相当。");
        assertTrue(Math.abs(bestHitRateFactor(0.408, true, 0.1, 1.6) - 0.408) < 1.0e-9,
                "attack-speed 0.408(メイス)の最適も毎回フルチャージ。");
        // 極端に遅い品(U6 の最低値 0.1)は、フルチャージを待つより減衰込みで連打した方が速い。
        assertTrue(bestHitRateFactor(0.1, true, 0.1, 1.6) > 0.1,
                "attack-speed 0.1 では『減衰込みの連打』の方が『10秒待ってフルチャージ』より"
                        + "DPSが高い。この端点を落とすと最低速武器のDPSを過小評価する。");
    }

    @Test
    @DisplayName("damage-modifier は端点でなく期待値で効く(0.65 なら ×0.825)")
    void damageModifierUsesItsExpectedValue() {
        assertTrue(Math.abs(expectedDamageModifier(0.65) - 0.825) < 1.0e-9);
        assertTrue(Math.abs(expectedDamageModifier(1.0) - 1.0) < 1.0e-9);
    }
}
