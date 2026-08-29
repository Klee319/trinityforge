package com.trinityforge.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * アドオン乗算チャネルの<b>レイヤ</b>を固定する(2026-08-22 W-186)。
 *
 * <p>それまでは ArsPaper のスレッド「セット効果(乗算モード)」の倍率が全部
 * {@link AddonCombatStats#MULTIPLIER_LAYER_ID}("addon")という1レイヤへ<b>固定で</b>入っていた。
 * TF の合成規則は「レイヤ内は Σ(v-1) を足し、レイヤ同士は掛ける」なので、
 * 装備側にも同じステの倍率があると<b>掛け算で二重に乗る</b>(攻撃力%が典型)。
 *
 * <p>ここで縛るのは3つ:
 * <ol>
 *   <li>レイヤIDが PDC を往復しても壊れないこと(コーデック)</li>
 *   <li><b>レイヤ導入前に書かれた PDC が読めること</b> —— PDC はサーバ再起動をまたいで
 *       プレイヤーに残るので、旧形式 {@code "key=value"} を捨てると古い値を持ったままの
 *       プレイヤーだけ倍率が無言で消える</li>
 *   <li>レイヤを合わせると<b>足し算</b>・ずらすと<b>掛け算</b>になるという、この変更の目的そのもの</li>
 * </ol>
 */
class AddonMultiplierLayerTest {

    /** ステキーは canonical 形(小文字・ハイフンはアンダースコアへ。{@code StatKeys#canonical})で書く。 */
    private static Map<String, Double> stat(String key, double value) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put(key, value);
        return out;
    }

    @Test
    @DisplayName("レイヤ付き倍率は PDC 文字列を往復してもレイヤごと保たれる")
    void layeredCodecRoundTrips() {
        Map<String, Map<String, Double>> source = new LinkedHashMap<>();
        source.put("layer_1", stat("attack-power", 0.25));
        Map<String, Double> addon = new LinkedHashMap<>();
        addon.put("crit-chance", 0.05);
        addon.put("penetration", 0.04);
        source.put(AddonCombatStats.MULTIPLIER_LAYER_ID, addon);

        String encoded = AddonCombatStats.encodeLayered(source);
        assertTrue(encoded.contains("layer_1@attack_power=0.25"),
                "レイヤIDが符号化されていない: " + encoded);

        Map<String, Map<String, Double>> back = AddonCombatStats.parseLayered(encoded);
        assertEquals(2, back.size(), "レイヤ数が変わった: " + back);
        assertEquals(0.25, back.get("layer_1").get("attack_power"), 1e-9);
        assertEquals(0.05, back.get(AddonCombatStats.MULTIPLIER_LAYER_ID).get("crit_chance"), 1e-9);
        assertEquals(0.04, back.get(AddonCombatStats.MULTIPLIER_LAYER_ID).get("penetration"), 1e-9);
    }

    @Test
    @DisplayName("レイヤ導入前の PDC(レイヤ無し)は既定レイヤとして読める(古い値を無言で消さない)")
    void legacyUnlayeredStringStillParses() {
        Map<String, Map<String, Double>> back = AddonCombatStats.parseLayered("attack-power=0.25;crit-chance=0.05");
        assertEquals(Map.of(AddonCombatStats.MULTIPLIER_LAYER_ID,
                        Map.of("attack_power", 0.25, "crit_chance", 0.05)),
                back,
                "@ を含まないトークンは既定レイヤへ落とす契約。捨てると、更新直後に再ログインしていない"
                        + "プレイヤーだけ倍率が消える(PDC はサーバ再起動をまたいで残る)");
    }

    @Test
    @DisplayName("レイヤIDが空/空白のときは既定レイヤへ落ちる(符号化・復号の両方向)")
    void blankLayerFallsBackToDefault() {
        Map<String, Map<String, Double>> source = new LinkedHashMap<>();
        source.put("  ", stat("attack-power", 0.1));
        assertEquals(AddonCombatStats.MULTIPLIER_LAYER_ID + "@attack_power=0.1",
                AddonCombatStats.encodeLayered(source));
        assertEquals(Map.of(AddonCombatStats.MULTIPLIER_LAYER_ID, Map.of("attack_power", 0.1)),
                AddonCombatStats.parseLayered("@attack-power=0.1"));
    }

    @Test
    @DisplayName("壊れたトークンは1件だけ捨て、残りのレイヤは生き残る")
    void malformedTokensDoNotAbortTheParse() {
        Map<String, Map<String, Double>> back =
                AddonCombatStats.parseLayered("layer_1@attack-power=xx;layer_1@attack-power=0.2;=0.5;layer_9@");
        assertEquals(Map.of("layer_1", Map.of("attack_power", 0.2)), back,
                "壊れたトークンで全体を落としてはいけない。空になったレイヤも残してはいけない");
    }

    @Test
    @DisplayName("同じレイヤなら足し算・別レイヤなら掛け算になる(W-186 が変えたのはここ)")
    void sameLayerAddsWhileDifferentLayersMultiply() {
        // 装備側: layer_1(攻撃力%)に +20%。
        // PlayerCombatAggregate は受け取った Map をコピーするだけで canonical 化しないので、
        // ここは合流後の姿(canonical キー)をそのまま書く。
        Map<String, Map<String, Double>> merged = new LinkedHashMap<>();
        merged.put("layer_1", stat("attack_power", 0.20));

        // スレッドのセット効果が layer_1 を名指しした場合 → 同じレイヤの中で Σ(v-1)。
        Map<String, Map<String, Double>> sameLayer = new LinkedHashMap<>(merged);
        sameLayer.put("layer_1", Map.of("attack_power", 0.20 + 0.25));
        assertEquals(1.45, aggregate(sameLayer).multiplierFor("attack_power"), 1e-9,
                "同一レイヤは 1 + Σ(v-1) = 1 + 0.20 + 0.25");

        // レイヤ無指定(=既定レイヤ)のままだと別レイヤ扱い → 掛け算で二重に乗る。
        Map<String, Map<String, Double>> splitLayer = new LinkedHashMap<>(merged);
        splitLayer.put(AddonCombatStats.MULTIPLIER_LAYER_ID, stat("attack_power", 0.25));
        assertEquals(1.20 * 1.25, aggregate(splitLayer).multiplierFor("attack_power"), 1e-9,
                "別レイヤは積。出荷 thread-sets.yml の攻撃力%はこの状態を避けるため layer_1 を明示している");
    }

    private static PlayerCombatAggregate aggregate(Map<String, Map<String, Double>> multipliers) {
        return new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), multipliers, null);
    }
}
