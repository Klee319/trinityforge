package com.trinityforge.config.domains;

import com.trinityforge.config.domains.DungeonThemeConfig.ParseResult;
import com.trinityforge.mobs.ConversionPolicy;
import com.trinityforge.mobs.ConversionPolicy.DefenseRamp;
import com.trinityforge.mobs.ConversionPolicy.LevelSource;
import com.trinityforge.mobs.ConversionPolicy.Ramp;
import com.trinityforge.mobs.DungeonTheme;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DungeonThemeConfigTest {

    private static final Logger LOG = Logger.getLogger("DungeonThemeConfigTest");
    private static final double DELTA = 1.0e-9;

    private static ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return DungeonThemeConfig.parse(cfg.getConfigurationSection("themes"), LOG);
    }

    @Test
    void parsesThemeRamps() throws Exception {
        ParseResult r = parse("""
                themes:
                  physical_fortress:
                    physical:
                      defense-rate: { base: 0.1, per-level: 0.004 }
                    magical:
                      defense-rate: { base: 0.0, per-level: 0.0 }
                    armor-strength: { base: 0.0, per-level: 0.1 }
                """);
        assertEquals(0, r.skipped());
        DungeonTheme t = r.themes().get("physical_fortress");
        assertNotNull(t);
        assertEquals(0.1, t.physical().defenseRate().base(), DELTA);
        assertEquals(0.004, t.physical().defenseRate().perLevel(), DELTA);
        assertEquals(0.1, t.armorStrength().perLevel(), DELTA);
    }

    @Test
    void toPolicyKeepsLevelSettingsButOverridesDefenseAndTag() throws Exception {
        DungeonTheme t = parse("""
                themes:
                  arcane_ward:
                    magical:
                      defense-rate: { base: 0.2, per-level: 0.0 }
                """).themes().get("arcane_ward");
        Ramp zero = new Ramp(0.0, 0.0);
        DefenseRamp zeroDefense = new DefenseRamp(zero, zero, zero, zero);
        ConversionPolicy base = new ConversionPolicy(LevelSource.FIXED, 7, 3, "old",
                zeroDefense, zeroDefense, zero);

        ConversionPolicy out = t.toPolicy(base);

        assertEquals(LevelSource.FIXED, out.levelSource());
        assertEquals(7, out.fixedLevel());
        assertEquals(3, out.defaultLevel());
        assertEquals("arcane_ward", out.defaultTheme());                  // tag overridden
        assertEquals(0.2, out.magical().defenseRate().base(), DELTA);     // defense overridden
    }

    @Test
    void toPolicyForwardsGlobalAttackAndMaxHealthRamps() throws Exception {
        // A theme is a defense-bias preset only: the global attack ramp AND the global max-health
        // ramp must survive a themed import, otherwise `importmobs theme <t> <folder>` would silently
        // reset every mob's TF-driven HP (and attack) to 0. Regression guard for the DungeonTheme.toPolicy
        // back-compat-constructor trap.
        DungeonTheme t = parse("""
                themes:
                  arcane_ward:
                    magical:
                      defense-rate: { base: 0.2, per-level: 0.0 }
                """).themes().get("arcane_ward");
        Ramp zero = new Ramp(0.0, 0.0);
        DefenseRamp zeroDefense = new DefenseRamp(zero, zero, zero, zero);
        ConversionPolicy.AttackRamp attack = new ConversionPolicy.AttackRamp(
                new Ramp(3.0, 0.5), zero, zero, zero, zero, zero, new Ramp(1.0, 0.0), zero);
        Ramp maxHealth = new Ramp(20.0, 5.0);
        ConversionPolicy base = new ConversionPolicy(LevelSource.FIXED, 7, 3, "old",
                zeroDefense, zeroDefense, zero, attack, maxHealth);

        ConversionPolicy out = t.toPolicy(base);

        assertEquals(3.0, out.attack().attackPower().base(), DELTA);      // global attack preserved
        assertEquals(20.0, out.maxHealth().base(), DELTA);                // global HP preserved
        assertEquals(5.0, out.maxHealth().perLevel(), DELTA);
    }

    @Test
    void skipsNonSectionThemeButKeepsRest() throws Exception {
        ParseResult r = parse("""
                themes:
                  bad: 5
                  good:
                    physical:
                      defense-rate: { base: 0.0 }
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.themes().size());
        assertTrue(r.themes().containsKey("good"));
    }

    @Test
    void emptyThemesYieldNone() throws Exception {
        ParseResult r = parse("themes: {}\n");
        assertEquals(0, r.skipped());
        assertTrue(r.themes().isEmpty());
    }
}
