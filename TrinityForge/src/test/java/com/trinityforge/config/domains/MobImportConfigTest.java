package com.trinityforge.config.domains;

import com.trinityforge.mobs.ConversionPolicy;
import com.trinityforge.mobs.ConversionPolicy.LevelSource;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MobImportConfig}: level-source parsing (with fallback) and a full parse
 * round-trip asserting every {@link ConversionPolicy} field comes through.
 */
class MobImportConfigTest {

    private static final Logger LOG = Logger.getLogger("MobImportConfigTest");
    private static final double DELTA = 1.0e-9;

    @Test
    @DisplayName("parseSource(null) defaults to ELITEMOBS")
    void parseSourceNullDefaultsElitemobs() {
        assertEquals(LevelSource.ELITEMOBS, MobImportConfig.parseSource(null, LOG));
    }

    @Test
    @DisplayName("parseSource is case-insensitive: 'fixed' -> FIXED")
    void parseSourceFixedCaseInsensitive() {
        assertEquals(LevelSource.FIXED, MobImportConfig.parseSource("fixed", LOG));
        assertEquals(LevelSource.FIXED, MobImportConfig.parseSource("  FiXeD ", LOG));
    }

    @Test
    @DisplayName("parseSource('garbage') falls back to ELITEMOBS")
    void parseSourceGarbageFallsBack() {
        assertEquals(LevelSource.ELITEMOBS, MobImportConfig.parseSource("garbage", LOG));
    }

    @Test
    @DisplayName("full parse maps every ConversionPolicy field from YAML")
    void fullParseRoundTrip() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                level:
                  source: fixed
                  fixed: 8
                  default: 3
                theme:
                  default: "arcane_ward"
                physical:
                  defense-rate: { base: 0.1, per-level: 0.01 }
                  flat-defense: { base: 2.0, per-level: 0.5 }
                magical:
                  resistance: { base: 0.2, per-level: 0.0 }
                armor-strength: { base: 1.0, per-level: 0.25 }
                max-health: { base: 30.0, per-level: 4.0 }
                attack:
                  attack-power: { base: 3.0, per-level: 0.5 }
                  crit-chance:  { base: 0.05, per-level: 0.005 }
                """);

        ConversionPolicy policy = MobImportConfig.parse(cfg, LOG);

        assertEquals(LevelSource.FIXED, policy.levelSource());
        assertEquals(8, policy.fixedLevel());
        assertEquals(3, policy.defaultLevel());
        assertEquals("arcane_ward", policy.defaultTheme());
        assertEquals(0.1, policy.physical().defenseRate().base(), DELTA);
        assertEquals(0.01, policy.physical().defenseRate().perLevel(), DELTA);
        assertEquals(2.0, policy.physical().flatDefense().base(), DELTA);
        assertEquals(0.5, policy.physical().flatDefense().perLevel(), DELTA);
        assertEquals(0.2, policy.magical().resistance().base(), DELTA);
        assertEquals(1.0, policy.armorStrength().base(), DELTA);
        assertEquals(0.25, policy.armorStrength().perLevel(), DELTA);
        assertEquals(3.0, policy.attack().attackPower().base(), DELTA);
        assertEquals(0.5, policy.attack().attackPower().perLevel(), DELTA);
        assertEquals(0.05, policy.attack().critChance().base(), DELTA);
        assertEquals(0.005, policy.attack().critChance().perLevel(), DELTA);
        assertEquals(30.0, policy.maxHealth().base(), DELTA);
        assertEquals(4.0, policy.maxHealth().perLevel(), DELTA);
    }

    @Test
    @DisplayName("absent max-health: section parses to the zero ramp (mob keeps EliteMobs HP)")
    void absentMaxHealthSectionYieldsZeroRamp() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                level:
                  source: elitemobs
                """);

        ConversionPolicy policy = MobImportConfig.parse(cfg, LOG);

        assertEquals(0.0, policy.maxHealth().base(), DELTA);
        assertEquals(0.0, policy.maxHealth().perLevel(), DELTA);
    }

    @Test
    @DisplayName("absent attack: section parses to the zero ramp (unconfigured attack)")
    void absentAttackSectionYieldsZeroRamp() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                level:
                  source: elitemobs
                """);

        ConversionPolicy policy = MobImportConfig.parse(cfg, LOG);

        assertEquals(ConversionPolicy.AttackRamp.ZERO, policy.attack());
    }

    @Test
    @DisplayName("unknown-mobs.synthesize: absent defaults to true, explicit false is honoured")
    void unknownMobsSynthesizeParses() throws Exception {
        YamlConfiguration absent = new YamlConfiguration();
        absent.loadFromString("level:\n  source: elitemobs\n");
        assertTrue(MobImportConfig.parseSynthesizeUnknown(absent),
                "configs written before this key existed keep the new default");

        YamlConfiguration off = new YamlConfiguration();
        off.loadFromString("unknown-mobs:\n  synthesize: false\n");
        assertFalse(MobImportConfig.parseSynthesizeUnknown(off));
    }
}
