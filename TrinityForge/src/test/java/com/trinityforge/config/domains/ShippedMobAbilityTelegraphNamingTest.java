package com.trinityforge.config.domains;

import com.trinityforge.combat.MobAbility;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 出荷 {@code combat/mob-abilities.yml} の技名・色規約を固定する
 * （2026-09-04、design/2026-09-02-telegraph-mechanics-spec.md 機構4「表示形式」節）。
 *
 * <p>3つを検査する:
 * <ul>
 *   <li>技名（MiniMessageタグを剥がした表示文字列）が全角6文字以内であること
 *       （統合版の文字幅制約でアクションバーの5段バーに収める必要がある）</li>
 *   <li>型ごとに規約色のタグを含むこと（赤=離れる・敵中心 / 金=退く / 紫=横へ避ける /
 *       水=遮蔽へ / 緑=止めに行く）</li>
 *   <li>{@code cast-seconds} が1件も書かれていないこと（第1波では挙動変更禁止）</li>
 * </ul>
 */
class ShippedMobAbilityTelegraphNamingTest {

    private static final Logger LOG = Logger.getLogger("ShippedMobAbilityTelegraphNamingTest");
    private static final int MAX_NAME_LENGTH = 6;

    /** 型→規約色タグ（design doc の5色規約）。 */
    private static final Map<MobAbility.Type, String> EXPECTED_COLOR = new EnumMap<>(MobAbility.Type.class);

    static {
        // 赤（離れる・敵中心）
        EXPECTED_COLOR.put(MobAbility.Type.GROUND_SLAM, "<red>");
        EXPECTED_COLOR.put(MobAbility.Type.CHARGE, "<red>");
        EXPECTED_COLOR.put(MobAbility.Type.AURA, "<red>");
        // 金（床から退く）
        EXPECTED_COLOR.put(MobAbility.Type.DELAYED_ZONE, "<gold>");
        // 紫（横へ避ける・線）
        EXPECTED_COLOR.put(MobAbility.Type.BEAM, "<light_purple>");
        EXPECTED_COLOR.put(MobAbility.Type.TELEPORT_STRIKE, "<light_purple>");
        EXPECTED_COLOR.put(MobAbility.Type.PROJECTILE_VOLLEY, "<light_purple>");
        EXPECTED_COLOR.put(MobAbility.Type.PROJECTILE_RAIN, "<light_purple>");
        // 水（遮蔽へ）
        EXPECTED_COLOR.put(MobAbility.Type.VORTEX_PULL, "<aqua>");
        EXPECTED_COLOR.put(MobAbility.Type.REPULSE, "<aqua>");
        // 緑（止めに行く・術者）
        EXPECTED_COLOR.put(MobAbility.Type.SUMMON, "<green>");
    }

    private static String colorFor(MobAbility.Type type) {
        String color = EXPECTED_COLOR.get(type);
        if (color == null) {
            fail("型 " + type + " の規約色がテスト側の対応表に無い（対応表を更新すること）");
        }
        return color;
    }

    @Test
    @DisplayName("出荷テンプレートは全て6文字以内の技名・規約色を持ち、cast-secondsを書いていない")
    void shippedTemplatesFollowNamingAndColorConvention() throws Exception {
        YamlConfiguration yaml = loadShippedYaml();
        var abilitiesSection = yaml.getConfigurationSection("abilities");
        assertTrue(abilitiesSection != null && !abilitiesSection.getKeys(false).isEmpty(),
                "出荷 combat/mob-abilities.yml に abilities セクションが無い");

        MobAbilitiesConfig.ParseResult result = MobAbilitiesConfig.parse(abilitiesSection, LOG);
        assertEquals(0, result.skipped(), "出荷テンプレートは1件も壊れていないはず");

        for (String id : abilitiesSection.getKeys(false)) {
            var entry = abilitiesSection.getConfigurationSection(id);
            assertTrue(entry != null, "'" + id + "' はセクションであるべき");

            MobAbility ability = result.abilities().get(id.trim().toLowerCase(java.util.Locale.ROOT));
            assertTrue(ability != null, "'" + id + "' がパース結果に無い");

            String rawDisplayName = entry.getString("display-name", "");
            String stripped = stripMiniMessageTags(rawDisplayName);
            assertTrue(codePointLength(stripped) <= MAX_NAME_LENGTH,
                    "'" + id + "' の技名が" + MAX_NAME_LENGTH + "文字を超えている: \"" + stripped
                            + "\" (" + codePointLength(stripped) + "文字)");

            String expectedColor = colorFor(ability.type());
            assertTrue(rawDisplayName.contains(expectedColor),
                    "'" + id + "' (" + ability.type() + ") は規約色 " + expectedColor
                            + " を含むべき。実際の display-name: " + rawDisplayName);

            assertFalse(entry.contains("cast-seconds"),
                    "'" + id + "' に cast-seconds が書かれている（第1波では挙動変更禁止のはず）");
        }
    }

    private static YamlConfiguration loadShippedYaml() throws Exception {
        try (InputStream in = ShippedMobAbilityTelegraphNamingTest.class.getClassLoader()
                .getResourceAsStream(MobAbilitiesConfig.PATH)) {
            assertTrue(in != null, "クラスパスに " + MobAbilitiesConfig.PATH + " が見つからない");
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    /** MiniMessage タグ（{@code <...>} / {@code </...>}）を取り除いた表示文字列。 */
    private static String stripMiniMessageTags(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("</?[^>]+>", "");
    }

    /** サロゲートペア（絵文字等）を1文字として数える。日本語は基本多言語面なので通常のlength()と一致する。 */
    private static int codePointLength(String s) {
        return s.codePointCount(0, s.length());
    }
}
