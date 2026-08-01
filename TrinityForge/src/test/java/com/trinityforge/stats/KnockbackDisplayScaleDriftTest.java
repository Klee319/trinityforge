package com.trinityforge.stats;

import com.trinityforge.skilltree.runtime.NativeCombatPerkListener;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ノックバック2キーの「単位 m」と実挙動の桁を合わせる {@code display-scale} が
 * Java 側の velocity 係数とずれていないことを機械照合する (2026-07-31 レビュー指摘4)。
 *
 * <p>背景: {@code melee-knockback} / {@code arrow-knockback} の内部値は距離ではなく
 * <b>velocity への加算</b>({@code 係数 × 値} blocks/tick)。単位を {@code m} に統一した一方で
 * 表示が生値のままだったため、運営者が {@code melee-knockback: 2} を「2m 押し出す」意図で書くと
 * 実際は約8ブロック飛ぶ(表記の約4倍)状態だった。内部値は据え置きにして
 * {@link StatDisplaySpec#toDisplayValue} で表示だけ換算する方式にしたので、
 * <b>Java の係数を変えたら yml の display-scale も直さなければならない</b>。
 * その2箇所のドリフトはどのテストにも引っかからないのでここで縛る。
 */
class KnockbackDisplayScaleDriftTest {

    /** 端数は yml 側で丸めている(3.888…→3.9)ので、係数×11.11 との差はこの範囲まで許す。 */
    private static final double ROUNDING_TOLERANCE = 0.06;

    @Test
    @DisplayName("display-scale は Java の velocity 係数 × 減衰換算(1/(1-0.91)) と一致する")
    void displayScaleMatchesTheJavaVelocityCoefficients() throws Exception {
        ConfigurationSection stats = loreStats();
        assertScale(stats, "melee-knockback",
                NativeCombatPerkListener.MELEE_KNOCKBACK_VELOCITY_PER_UNIT);
        assertScale(stats, "arrow-knockback",
                NativeCombatPerkListener.ARROW_KNOCKBACK_VELOCITY_PER_UNIT);
    }

    private static void assertScale(ConfigurationSection stats, String key, double velocityPerUnit) {
        ConfigurationSection entry = stats.getConfigurationSection(key);
        assertNotNull(entry, key + " が stats/lore.yml に無い");
        assertEquals("m", entry.getString("unit"), key + " の unit が m でない");
        assertTrue(entry.contains("display-scale"),
                key + " に display-scale が無い(単位 m のまま生値を出すと桁が合わない)");
        double declared = entry.getDouble("display-scale");
        double expected = velocityPerUnit * NativeCombatPerkListener.VELOCITY_TO_BLOCKS;
        assertEquals(expected, declared, ROUNDING_TOLERANCE,
                key + " の display-scale (" + declared + ") が Java の係数 " + velocityPerUnit
                        + " × " + NativeCombatPerkListener.VELOCITY_TO_BLOCKS + " と合っていない");
    }

    @Test
    @DisplayName("換算は StatDisplaySpec に一本化されていて lore とチャット/GUIで同じ値が出る")
    void loreAndChatRenderTheSameConvertedValue() {
        StatDisplaySpec spec = new StatDisplaySpec("melee-knockback", "追撃ノックバック", "",
                LoreValueFormat.FLAT, 1, 106, true, true, "m", StatCategory.ATTACK, null, null, 3.9);

        assertEquals(3.9, spec.toDisplayValue(1.0), 1e-9, "内部値1が 3.9m として表示されること");
        assertEquals("+3.9m", spec.renderValue(1.0), "lore の表示が換算後の値になっていない");
        assertEquals("+3.9m", StatValueRenderer.render(spec, 1.0),
                "チャット/GUI の表示が lore と食い違っている(換算が2重 or 未適用)");
        assertEquals("+0.0m", spec.renderValue(0.0), "0 は換算しても 0 のまま");
        // 内部値2を「2m」と読ませていたのが指摘の実害。換算後は実挙動どおり約8m と出る。
        assertEquals("+7.8m", spec.renderValue(2.0));
    }

    @Test
    @DisplayName("display-scale 未宣言のステは従来どおり生値で表示される")
    void unscaledStatsAreUnchanged() {
        StatDisplaySpec spec = new StatDisplaySpec("phys-flat-defense", "物理守備力", "",
                LoreValueFormat.FLAT, 1, 60, true, true, "", StatCategory.DEFENSE);

        assertEquals(StatDisplaySpec.DEFAULT_DISPLAY_SCALE, spec.displayScale(), 1e-9);
        assertEquals(2.5, spec.toDisplayValue(2.5), 1e-9);
        assertEquals("+2.5", spec.renderValue(2.5));
        assertEquals("+2.5", StatValueRenderer.render(spec, 2.5));
    }

    private static ConfigurationSection loreStats() throws Exception {
        try (InputStream in = KnockbackDisplayScaleDriftTest.class.getClassLoader()
                .getResourceAsStream("stats/lore.yml")) {
            assertNotNull(in, "出荷リソース stats/lore.yml が見つからない");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            ConfigurationSection stats = yaml.getConfigurationSection("stats");
            assertNotNull(stats, "stats/lore.yml に stats: セクションが無い");
            return stats;
        }
    }
}
