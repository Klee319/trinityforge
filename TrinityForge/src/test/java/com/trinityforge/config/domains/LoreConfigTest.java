package com.trinityforge.config.domains;

import com.trinityforge.stats.LoreLayout;
import com.trinityforge.stats.LoreValueFormat;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the parse helpers of {@link LoreConfig}: color validation, format parsing, and
 * layout parsing with fail-soft fallbacks.
 */
class LoreConfigTest {

    private static final Logger LOG = Logger.getLogger("LoreConfigTest");

    @Test
    @DisplayName("validColor accepts a named color")
    void validColorAcceptsNamed() {
        assertEquals("green", LoreConfig.validColor("green", "red", "positive-color", LOG));
    }

    @Test
    @DisplayName("validColor accepts a #rrggbb hex color")
    void validColorAcceptsHex() {
        assertEquals("#abcdef", LoreConfig.validColor("#abcdef", "red", "positive-color", LOG));
    }

    @Test
    @DisplayName("validColor falls back to default on an invalid color")
    void validColorFallsBackOnInvalid() {
        assertEquals("red", LoreConfig.validColor("notacolor", "red", "negative-color", LOG));
    }

    @Test
    @DisplayName("parseFormat null/blank -> FLAT")
    void parseFormatNullOrBlankIsFlat() {
        assertEquals(LoreValueFormat.FLAT, LoreConfig.parseFormat(null));
        assertEquals(LoreValueFormat.FLAT, LoreConfig.parseFormat("  "));
    }

    @Test
    @DisplayName("parseFormat maps a valid name case-insensitively")
    void parseFormatValidName() {
        assertEquals(LoreValueFormat.PERCENT, LoreConfig.parseFormat("percent"));
    }

    @Test
    @DisplayName("parseFormat throws on an unknown format (caught/skipped at load level)")
    void parseFormatUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> LoreConfig.parseFormat("bogus"));
    }

    @Test
    @DisplayName("parseLayout(null) returns defaults")
    void parseLayoutNullReturnsDefaults() {
        LoreLayout layout = LoreConfig.parseLayout(null, LOG);
        LoreLayout def = LoreLayout.defaults();
        assertEquals(def.positiveColor(), layout.positiveColor());
        assertEquals(def.negativeColor(), layout.negativeColor());
        assertTrue(layout.header().isEmpty());
        assertTrue(layout.footer().isEmpty());
    }

    @Test
    @DisplayName("parseLayout falls back to default color for an invalid configured color")
    void parseLayoutInvalidColorFallsBack() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                layout:
                  positive-color: "not-a-color"
                  negative-color: "#112233"
                  line-template: "<icon><value> <name>"
                """);
        LoreLayout layout = LoreConfig.parseLayout(cfg.getConfigurationSection("layout"), LOG);
        assertEquals("green", layout.positiveColor());     // invalid -> default
        assertEquals("#112233", layout.negativeColor());   // valid hex kept
        assertEquals("<icon><value> <name>", layout.lineTemplate());
    }

    @Test
    @DisplayName("score-line-template を読む。欠落/空は既定テンプレートへフォールバック")
    void scoreLineTemplateParsed() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                layout:
                  score-line-template: "<tier> スコア:<score>"
                """);
        LoreLayout layout = LoreConfig.parseLayout(cfg.getConfigurationSection("layout"), LOG);
        assertEquals("<tier> スコア:<score>", layout.scoreLineTemplate());

        cfg.loadFromString("""
                layout:
                  score-line-template: ""
                """);
        layout = LoreConfig.parseLayout(cfg.getConfigurationSection("layout"), LOG);
        assertEquals(LoreLayout.DEFAULT_SCORE_LINE_TEMPLATE, layout.scoreLineTemplate());

        cfg.loadFromString("""
                layout:
                  line-template: "<icon><value> <name>"
                """);
        layout = LoreConfig.parseLayout(cfg.getConfigurationSection("layout"), LOG);
        assertEquals(LoreLayout.DEFAULT_SCORE_LINE_TEMPLATE, layout.scoreLineTemplate());
    }

    @Test
    @DisplayName("colors欠落時は従来のハードコード相当のデフォルト(fixed=white/roll=green/負=negative-color)")
    void colorRulesDefaultWhenAbsent() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                layout:
                  negative-color: "#112233"
                """);
        LoreLayout layout = LoreConfig.parseLayout(cfg.getConfigurationSection("layout"), LOG);
        assertEquals("white", layout.colors().fixedPositive());
        assertEquals("green", layout.colors().rollPositive());
        assertEquals("#112233", layout.colors().fixedNegative());
        assertEquals("#112233", layout.colors().rollNegative());
        // chance系は未設定なら無効(空) -> 通常色にフォールバック
        assertEquals("white", layout.colors().colorFor(1.0, false, true));
    }

    @Test
    @DisplayName("layout.colors.{fixed,roll} の positive/negative/chance-* を読む")
    void colorRulesParsed() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                layout:
                  colors:
                    fixed:
                      positive: white
                      negative: dark_red
                      chance-positive: aqua
                    roll:
                      positive: green
                      negative: red
                      chance-negative: dark_purple
                """);
        LoreLayout layout = LoreConfig.parseLayout(cfg.getConfigurationSection("layout"), LOG);
        assertEquals("aqua", layout.colors().colorFor(1.0, false, true));       // fixed+chance+positive
        assertEquals("dark_red", layout.colors().colorFor(-1.0, false, true));  // fixed chance-negative未設定→通常
        assertEquals("dark_purple", layout.colors().colorFor(-1.0, true, true));// roll+chance+negative
        assertEquals("green", layout.colors().colorFor(1.0, true, false));      // rollの通常positive
    }

    @Test
    @DisplayName("multiplier-layers はリスト順を保って id/name を読む(不正エントリは無視)")
    void multiplierLayersParsed() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                multiplier-layers:
                  - id: burst
                    name: バースト
                    stat: attack-power
                  - id: aura
                  - name: idなしは無視
                  - 42
                """);
        var layers = LoreConfig.parseMultiplierLayers(cfg.getList("multiplier-layers"));
        assertEquals(2, layers.size());
        assertEquals("burst", layers.get(0).id());
        assertEquals("バースト", layers.get(0).name());
        assertEquals("attack_power", layers.get(0).statKey());
        assertEquals("aura", layers.get(1).id());
        assertEquals("aura", layers.get(1).name()); // name欠落はidへフォールバック
    }

    @Test
    @DisplayName("bindセクション省略時はdefaults()を返す(旧ハードコード文言と一致)")
    void parseBindReturnsDefaultsWhenSectionAbsent() {
        LoreConfig.BindLore bind = LoreConfig.parseBind(null);
        assertEquals(LoreConfig.BindLore.defaults(), bind);
        assertEquals("<gray>使用可能レベル: <white><level></white> <gray><skill></gray></gray>",
                bind.useRequirementLine());
    }

    @Test
    @DisplayName("bind.use-requirement-line / show-use-requirement はconfigから読める(張りぼて修正の回帰防止)")
    void parseBindReadsUseRequirementFields() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                bind:
                  show-use-requirement: false
                  use-requirement-line: "<red>Lv<level> - <skill></red>"
                """);
        LoreConfig.BindLore bind = LoreConfig.parseBind(cfg.getConfigurationSection("bind"));
        assertTrue(!bind.showUseRequirement());
        assertEquals("<red>Lv<level> - <skill></red>", bind.useRequirementLine());
    }
}
