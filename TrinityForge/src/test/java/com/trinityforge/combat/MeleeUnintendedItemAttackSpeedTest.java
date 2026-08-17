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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>「殴る前提でない品」の {@code attack-speed} が規約どおりであることを、出荷 yml の実測で固定する</b>
 * 回帰テスト(2026-08-01 U6 発、2026-08-14 に判定軸を作り直し)。
 *
 * <h2>何が起きていたか</h2>
 * 弓 / クロスボウ / トライデント / Ars触媒(杖)は遠隔・詠唱で使う品なのに、
 * <ul>
 *   <li>弓・クロスボウは {@code attack-speed: 4}(出荷表の最大値)</li>
 *   <li>杖は <b>キー自体が無い</b> → {@link AttackSpeedResolver} は「未定義なら TF は一切干渉しない」
 *       方針なのでバニラ既定 4.0 のまま</li>
 *   <li>トライデントは 0.48</li>
 * </ul>
 * だった。一方 {@code CombatListener} は {@code use-skill} を見て殴打の撃破を
 * ARCHERY / LIGHT_WEAPONS の台帳へ積むので、<b>本来の使い方をせず素振りするだけで
 * レベリングが成立する</b>状態になっていた。
 *
 * <h2>「最低値」を 0 にしてはいけない</h2>
 * {@link AttackSpeedResolver#resolveAbsolute} は著者値が <b>0以下なら「著者ミス」と判断して
 * 4.0(バニラ基礎)へフォールバック</b>する。つまり 0 を書くと意図と真逆に最速へ戻る。
 * 実際に書ける最低値は {@code combat/damage.yml} の {@code attack-speed.min-effective}
 * (出荷 0.1、Java 側で [0.01, 4.0] にクランプ)で、遠隔武器にはその値と一致することを要求する。
 *
 * <p>なお {@code attack-speed} の唯一の適用先はバニラの {@code Attribute.ATTACK_SPEED}
 * ({@code PerkAttributeApplier#applyAttackSpeed}、プレイヤー単位・メインハンド専用)で、
 * 消費先は連打減衰({@code MeleeChargeMultiplier})だけである。
 * <b>弓の引き絞り・トライデントの投擲・魔法の詠唱には一切影響しない。</b>落ちるのは殴りだけ。
 *
 * <h2>2026-08-14: 判定軸を material から use-skill へ、対象からトライデントを外した</h2>
 * <ol>
 *   <li><b>判定軸</b>: 旧実装は材質名の集合 {@code {BOW, CROSSBOW, TRIDENT}} で拾っていたが、
 *       {@code DIAMOND_SWORD#1099}「Revolution」は <b>材質が剣の弓</b>
 *       ({@code use-skill: ARCHERY} で {@code bow-accuracy}/{@code arrow-velocity} を持つ)なので
 *       材質照合の守備範囲外だった。今 0.1 なのは偶然で、誰かが触っても検出されない穴だったため、
 *       <b>{@code use-skill} で拾う</b>ように変えた。逆向きの穴(弓の {@code use-skill} を書き換えて
 *       検査から逃がす)を塞ぐため、材質が弓/クロスボウの品が ARCHERY 集合に含まれることも同時に要求する。</li>
 *   <li><b>トライデントは近接武器として扱う</b>: {@code use-skill} が剣と同じ LIGHT_WEAPONS なので
 *       「素振りだけで ARCHERY が上がる」抜け道は原理的に存在しない
 *       ({@code UseSkillDefaults}「TRIDENT は ARCHERY ではなく LIGHT_WEAPONS」)。
 *       弓/クロスボウを 1.6 にすると近接DPSが同帯最良近接の 1.03〜1.13 倍になり
 *       「弓がゲーム内最強の殴り武器」になるのに対し、トライデントは 0.88〜0.90 倍で剣を超えない。</li>
 *   <li><b>触媒の不変条件を attack-speed の上限から item-cooldown の存在へ移した</b>:
 *       旧 {@code CATALYST_MAX_ATTACK_SPEED = 1.2} の前提「触媒が近接武器より速いと杖が最強の
 *       殴り武器になる」は実測で否定されている ―― 触媒の振り間隔は
 *       {@code CombatListener#meleeWeaponOnCooldown} が主命中をイベントごとキャンセルすることで
 *       {@code item-cooldown} 秒に律速されるので、{@code attack-speed} をいくつにしても
 *       手数は変わらない。<b>本当に守るべきは「触媒が item-cooldown を持つこと」</b>で、
 *       これが無いと詠唱CTが一切掛からない上に素早い殴り武器にもなる
 *       ({@code ENDER_EYE#85} が実際にその状態だった)。上限は剣と同じ 1.6 に置く。</li>
 * </ol>
 *
 * <h2>2026-08-18: 触媒を「剣と同じ 1.6」から「遠隔と同じ最低値 0.1」へ移した</h2>
 * 実サーバ報告「杖は素殴りでも敵を倒せる程度の DPS が出てしまう」。上の 2026-08-14 の
 * <b>「手数は変わらないから 1.6 でよい」は片側しか見ていなかった</b> —— 手数(発/秒)は確かに
 * {@code item-cooldown} で決まるが、<b>1発あたりの威力は {@code MeleeChargeMultiplier} で決まり、
 * そちらは attack-speed の関数</b>である。
 * <ul>
 *   <li>倍率 = {@code min + (経過tick ÷ (20/AS))^exp × (1-min)}（出荷 min 0.1 / exp 1.6）。</li>
 *   <li>AS 1.6 ならフルチャージまで 12.5 tick。CT は 2.07〜3.45 秒(41〜69 tick)なので
 *       <b>毎回きっかりフルチャージ = 倍率 1.0</b>。つまり「CTが長い」ことが逆に殴りを最適化していた。</li>
 *   <li>AS 0.1 ならフルチャージまで 200 tick。CT では 20〜35% しか溜まらず倍率は
 *       <b>0.20〜0.26</b>。殴りだけが 1/4 になる。</li>
 * </ul>
 * <b>詠唱は一切変わらない</b> —— attack-speed の適用先はバニラの {@code Attribute.ATTACK_SPEED}
 * だけで、詠唱DPS({@code attack-power ÷ item-cooldown})にも {@code item-cooldown} にも触れない。
 * 杖の {@code attack-power} は詠唱DPSから逆算した値なので全武器中で最大級(Lv100 で 80,965 =
 * 剣の 2.1 倍)であり、それをフルチャージで振れることが報告の直接の原因だった。
 */
class MeleeUnintendedItemAttackSpeedTest {

    /** 遠隔武器の判定軸。材質ではなく use-skill で拾う(材質が剣の弓 DIAMOND_SWORD#1099 を取りこぼさない)。 */
    private static final String RANGED_SKILL = "ARCHERY";

    /** 杖・触媒。材質は BLAZE_ROD / ENDER_EYE / *_SWORD とばらけているので use-skill で拾う。 */
    private static final String CATALYST_SKILL = "ARS_MAGIC";

    /**
     * 材質そのものが遠隔武器である品。<b>判定には使わない</b> ―― use-skill 軸から漏らされていないか
     * を確かめる逆方向のガードにだけ使う(この集合の品は必ず ARCHERY 側で拾われていること)。
     */
    private static final Set<String> RANGED_MATERIALS = Set.of("BOW", "CROSSBOW");

    /** 近接武器の最速は剣の 1.6。触媒はここまで許すが、超えたら剣より速い殴り武器になる。 */
    private static final double SWORD_ATTACK_SPEED = 1.6;

    /**
     * 出荷データで実際に該当する件数。
     * ARCHERY 32(弓16 + クロスボウ15 + 材質が剣の弓 DIAMOND_SWORD#1099) + 触媒12 = 44。
     * トライデント16本は use-skill が LIGHT_WEAPONS なので対象外(近接武器として扱う)。
     */
    private static final int EXPECTED_TARGETS = 44;

    private static YamlConfiguration shipped(String path) throws IOException {
        try (InputStream in = MeleeUnintendedItemAttackSpeedTest.class.getClassLoader()
                .getResourceAsStream(path.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + path);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static double shippedMinEffective() throws IOException {
        return shipped(CombatDamageConfig.PATH).getDouble("attack-speed.min-effective", 0.1);
    }

    private static ConfigurationSection shippedItems() throws IOException {
        ConfigurationSection items = shipped(ItemStatsConfig.PATH).getConfigurationSection("items");
        assertNotNull(items, "出荷 item-stats.yml に items セクションが無い");
        return items;
    }

    /** 攻撃力を持つ品だけが「殴れる品」。持たない品は殴っても素手同然なので検査対象外。 */
    private static ConfigurationSection attackCapableFixed(ConfigurationSection items, String id) {
        ConfigurationSection item = items.getConfigurationSection(id);
        if (item == null) {
            return null;
        }
        ConfigurationSection fixed = item.getConfigurationSection("fixed");
        return (fixed != null && fixed.isSet("attack-power")) ? fixed : null;
    }

    @Test
    @DisplayName("遠隔武器(use-skill=ARCHERY)は最低値に固定、触媒はCTを持ち剣より速くない")
    void everyMeleeUnintendedItemIsPinnedToTheMinimumAttackSpeed() throws IOException {
        double minimum = shippedMinEffective();
        assertTrue(minimum > 0.0,
                "combat/damage.yml の attack-speed.min-effective が " + minimum
                        + "。0以下だと AttackSpeedResolver が著者ミス扱いで 4.0 へ戻すので、"
                        + "そもそも『最低値』として使えない。");

        ConfigurationSection items = shippedItems();

        List<String> checked = new ArrayList<>();
        List<String> missingKey = new ArrayList<>();
        List<String> tooFast = new ArrayList<>();
        List<String> missingCooldown = new ArrayList<>();
        List<String> rangedMaterialNotArchery = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            ConfigurationSection fixed = attackCapableFixed(items, id);
            if (fixed == null) {
                continue;
            }
            String skill = items.getConfigurationSection(id).getString("use-skill");
            boolean ranged = RANGED_SKILL.equals(skill);
            boolean catalyst = CATALYST_SKILL.equals(skill);

            String material = id.contains("#") ? id.substring(0, id.indexOf('#')) : id;
            if (RANGED_MATERIALS.contains(material) && !ranged) {
                // 逆方向のガード: use-skill を書き換えるだけで検査から逃げられないようにする。
                rangedMaterialNotArchery.add(id + "(use-skill=" + skill + ")");
            }
            if (!ranged && !catalyst) {
                continue;
            }
            checked.add(id);
            if (!fixed.isSet("attack-speed")) {
                missingKey.add(id);
                continue;
            }
            double speed = fixed.getDouble("attack-speed");
            // 触媒も「CTを持つこと」は不変条件のまま。これが無いと詠唱CTが一切掛からない。
            if (catalyst && !(fixed.getDouble("item-cooldown") > 0.0)) {
                missingCooldown.add(id);
            }
            // 遠隔武器も触媒も「素振りが本来の使い方でない」ので最低値に張り付ける。
            if (Math.abs(speed - minimum) > 1.0e-9) {
                tooFast.add(id + "=" + speed + "(" + (ranged ? "遠隔" : "触媒") + "は " + minimum + " 固定)");
            }
        }

        assertTrue(rangedMaterialNotArchery.isEmpty(),
                "材質が弓/クロスボウなのに use-skill が ARCHERY でない品がある: " + rangedMaterialNotArchery
                        + "。この検査は use-skill 軸で拾うので、use-skill を書き換えると検査ごとすり抜ける。");
        assertTrue(missingKey.isEmpty(),
                "attack-speed キーが無い品がある: " + missingKey
                        + "。キーが無いと TF は一切干渉せず【バニラ既定 4.0 = 最速】のままになる。"
                        + "『書いていないから遅い』ではないので、必ず明示的に書くこと"
                        + "(遠隔は " + minimum + "、触媒は " + SWORD_ATTACK_SPEED + " 以下)。");
        assertTrue(missingCooldown.isEmpty(),
                "item-cooldown を持たない触媒がある: " + missingCooldown
                        + "。SpellCaster の汎用CTパス(TrinityForgeBridge#itemCooldownSeconds)が 0 になり"
                        + "詠唱CTが一切掛からなくなる上に、振り間隔を律速するものが無くなって"
                        + "素早い殴り武器にもなる。");
        assertTrue(tooFast.isEmpty(),
                "殴る前提でない品の attack-speed が規約から外れている: " + tooFast
                        + "。遠隔は素振りだけで ARCHERY のレベリングが成立してしまい、"
                        + "触媒が剣(1.6)より速いと最速の殴り武器になる。");
        assertEquals(EXPECTED_TARGETS, checked.size(),
                "対象件数が変わっている(実測 " + checked.size() + " / 想定 " + EXPECTED_TARGETS + ")。"
                        + "遠隔武器や触媒を増減したなら EXPECTED_TARGETS も更新すること。対象: " + checked);
    }

    /**
     * トライデントだけが「CTを持つ近接武器」。触媒は 2026-08-18 にこの規約から外して
     * 遠隔と同じ最低値へ移した（下の理由）。
     */
    @Test
    @DisplayName("トライデントの attack-speed は剣と同じ 1.6（CT持ちでも近接武器として扱う）")
    void tridentsSwingAtSwordSpeed() throws IOException {
        ConfigurationSection items = shippedItems();

        List<String> offSpec = new ArrayList<>();
        int tridents = 0;
        for (String id : items.getKeys(false)) {
            ConfigurationSection fixed = attackCapableFixed(items, id);
            if (fixed == null || !"TRIDENT".equals(id.contains("#") ? id.substring(0, id.indexOf('#')) : id)) {
                continue;
            }
            tridents++;
            if (Math.abs(fixed.getDouble("attack-speed") - SWORD_ATTACK_SPEED) > 1.0e-9) {
                offSpec.add(id + "=" + fixed.getDouble("attack-speed"));
            }
        }

        assertTrue(offSpec.isEmpty(),
                "トライデントの attack-speed が剣(" + SWORD_ATTACK_SPEED + ")と違う: " + offSpec
                        + "。use-skill が剣と同じ LIGHT_WEAPONS なので『素振りだけで ARCHERY が上がる』"
                        + "抜け道は原理的に無く、1.6 でも同帯最良近接を超えない。");
        assertEquals(16, tridents, "トライデントの件数が変わっている(実測 " + tridents + ")");
    }

    @Test
    @DisplayName("最低値 0.1 は AttackSpeedResolver に著者ミス扱いされない(0 を書くと 4.0 へ戻る)")
    void theMinimumIsNotMistakenForAnAuthoringError() throws IOException {
        double minimum = shippedMinEffective();
        AttackSpeedResolver.AbsoluteResult ok = AttackSpeedResolver.resolveAbsolute(
                minimum, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        assertTrue(!ok.authoringWarning(),
                "最低値 " + minimum + " が著者ミス判定になっている。");
        assertTrue(Math.abs(ok.addNumberAmount() - (minimum - AttackSpeedResolver.VANILLA_BASE)) < 1.0e-9,
                "最低値の ADD_NUMBER 量が『最低値 - バニラ基礎4.0』になっていない。");

        AttackSpeedResolver.AbsoluteResult zero = AttackSpeedResolver.resolveAbsolute(
                0.0, 0.0, AttackSpeedResolver.AUTHORING_FALLBACK);
        assertTrue(zero.authoringWarning(),
                "0 を著者ミスとして検出できていない。");
        assertTrue(Math.abs(zero.addNumberAmount()) < 1.0e-9,
                "0 を書くと 4.0(バニラ基礎)へ戻る = 最速になる。これが『最低値を0にしてはいけない』理由。");
    }
}
