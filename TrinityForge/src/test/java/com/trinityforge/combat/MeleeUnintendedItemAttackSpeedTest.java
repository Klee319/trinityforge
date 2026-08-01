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
 * <b>「殴る前提でない品」の {@code attack-speed} が最低値に張り付いていることを、出荷 yml の実測で固定する</b>
 * 回帰テスト(2026-08-01 U6)。
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
 * (出荷 0.1、Java 側で [0.01, 4.0] にクランプ)で、ここではその値と一致することを要求する。
 *
 * <p>なお {@code attack-speed} はバニラの {@code Attribute.ATTACK_SPEED} にしか写像しないので、
 * 弓の引き絞り・トライデントの投擲・魔法の詠唱には影響しない。落ちるのは殴りだけである。
 */
class MeleeUnintendedItemAttackSpeedTest {

    /** 材質そのものが遠隔武器である品(CMD違いも含めて全部)。 */
    private static final Set<String> RANGED_MATERIALS = Set.of("BOW", "CROSSBOW", "TRIDENT");

    /** 杖・触媒。材質は BLAZE_ROD / ENDER_EYE と揃っていないので use-skill で拾う。 */
    private static final String CATALYST_SKILL = "ARS_MAGIC";

    /** 出荷データで実際に該当する件数(弓16 + クロスボウ16 + トライデント16 + 触媒12)。 */
    private static final int EXPECTED_TARGETS = 60;

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

    @Test
    @DisplayName("弓/クロスボウ/トライデント/触媒の attack-speed は全て最低値(damage.yml の min-effective)")
    void everyMeleeUnintendedItemIsPinnedToTheMinimumAttackSpeed() throws IOException {
        double minimum = shippedMinEffective();
        assertTrue(minimum > 0.0,
                "combat/damage.yml の attack-speed.min-effective が " + minimum
                        + "。0以下だと AttackSpeedResolver が著者ミス扱いで 4.0 へ戻すので、"
                        + "そもそも『最低値』として使えない。");

        ConfigurationSection items = shipped(ItemStatsConfig.PATH).getConfigurationSection("items");
        assertNotNull(items, "出荷 item-stats.yml に items セクションが無い");

        List<String> checked = new ArrayList<>();
        List<String> missingKey = new ArrayList<>();
        List<String> tooFast = new ArrayList<>();
        for (String id : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(id);
            if (item == null) {
                continue;
            }
            ConfigurationSection fixed = item.getConfigurationSection("fixed");
            if (fixed == null || !fixed.isSet("attack-power")) {
                continue; // 攻撃力を持たない品は殴っても素手同然なので対象外。
            }
            String material = id.contains("#") ? id.substring(0, id.indexOf('#')) : id;
            boolean target = RANGED_MATERIALS.contains(material)
                    || CATALYST_SKILL.equals(item.getString("use-skill"));
            if (!target) {
                continue;
            }
            checked.add(id);
            if (!fixed.isSet("attack-speed")) {
                missingKey.add(id);
            } else if (Math.abs(fixed.getDouble("attack-speed") - minimum) > 1.0e-9) {
                tooFast.add(id + "=" + fixed.getDouble("attack-speed"));
            }
        }

        assertTrue(missingKey.isEmpty(),
                "attack-speed キーが無い品がある: " + missingKey
                        + "。キーが無いと TF は一切干渉せず【バニラ既定 4.0 = 最速】のままになる。"
                        + "『書いていないから遅い』ではないので、必ず明示的に " + minimum + " を書くこと。");
        assertTrue(tooFast.isEmpty(),
                "殴る前提でない品の attack-speed が最低値(" + minimum + ")になっていない: " + tooFast
                        + "。素振りだけで ARCHERY / LIGHT_WEAPONS のレベリングが成立してしまう。");
        assertEquals(EXPECTED_TARGETS, checked.size(),
                "対象件数が変わっている(実測 " + checked.size() + " / 想定 " + EXPECTED_TARGETS + ")。"
                        + "遠隔武器や触媒を増減したなら EXPECTED_TARGETS も更新すること。対象: " + checked);
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
