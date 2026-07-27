package com.trinityforge.config.domains;

import com.trinityforge.config.domains.MobProfileConfig.ParseResult;
import com.trinityforge.mobs.MobProfile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobProfileConfigTest {

    private static final Logger LOG = Logger.getLogger("MobProfileConfigTest");
    private static final double DELTA = 1.0e-9;

    private static ParseResult parse(String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(yaml);
        return MobProfileConfig.parse(cfg.getConfigurationSection("profiles"), LOG);
    }

    @Test
    void parsesProfileWithSharedArmorStrength() throws Exception {
        ParseResult r = parse("""
                profiles:
                  debt_collector:
                    level: 10
                    dungeon-theme: "physical"
                    armor-strength: 5.0
                    physical: { defense-rate: 0.2, resistance: 0.1, damage-reduction: 0.0, flat-defense: 3.0 }
                    magical:  { defense-rate: 0.0, resistance: 0.0, damage-reduction: 0.0, flat-defense: 0.0 }
                """);
        assertEquals(0, r.skipped());
        MobProfile p = r.profiles().get("debt_collector");
        assertNotNull(p);
        assertEquals(10, p.level());
        assertEquals("physical", p.dungeonTheme());
        assertEquals(0.2, p.physical().defenseRate(), DELTA);
        assertEquals(3.0, p.physical().flatDefense(), DELTA);
        assertEquals(5.0, p.armorStrength(), DELTA);
        assertEquals(5.0, p.magical().armorStrength(), DELTA); // shared across components
    }

    @Test
    void ignoresInformationalKeysAndBlankTheme() throws Exception {
        ParseResult r = parse("""
                profiles:
                  plain:
                    level: 1
                    dungeon-theme: ""
                    source-name: "&cBoss"
                    entity-type: "ZOMBIE"
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        MobProfile p = r.profiles().get("plain");
        assertNotNull(p);
        assertNull(p.dungeonTheme());
    }

    @Test
    void emptyProfilesYieldNone() throws Exception {
        ParseResult r = parse("profiles: {}\n");
        assertEquals(0, r.skipped());
        assertTrue(r.profiles().isEmpty());
    }

    @Test
    void missingDefenseSectionsYieldZeroStatsWithSharedArmorStrength() throws Exception {
        ParseResult r = parse("""
                profiles:
                  bare:
                    level: 4
                    armor-strength: 7.0
                """);
        MobProfile p = r.profiles().get("bare");
        assertNotNull(p);
        assertEquals(0.0, p.physical().defenseRate(), DELTA);
        assertEquals(0.0, p.physical().flatDefense(), DELTA);
        assertEquals(0.0, p.magical().resistance(), DELTA);
        assertEquals(7.0, p.armorStrength(), DELTA); // shared, even with no defense sections
    }

    @Test
    void defenseRateAboveOneIsClampedToOne() throws Exception {
        ParseResult r = parse("""
                profiles:
                  typo:
                    level: 1
                    physical: { defense-rate: 5.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        assertEquals(5.0, r.profiles().get("typo").physical().defenseRate(), DELTA);
    }

    @Test
    void negativeFlatDefenseIsPreserved() throws Exception {
        ParseResult r = parse("""
                profiles:
                  neg:
                    level: 1
                    physical: { flat-defense: -3.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        assertEquals(-3.0, r.profiles().get("neg").physical().flatDefense(), DELTA);
    }

    @Test
    void negativeArmorStrengthIsPreserved() throws Exception {
        ParseResult r = parse("""
                profiles:
                  negarmor:
                    level: 1
                    armor-strength: -9.0
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        assertEquals(-9.0, r.profiles().get("negarmor").armorStrength(), DELTA);
    }

    @Test
    void negativeLevelEntryIsSkipped() throws Exception {
        ParseResult r = parse("""
                profiles:
                  bad:
                    level: -1
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                  good:
                    level: 2
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.profiles().size());
        assertTrue(r.profiles().containsKey("good"));
    }

    @Test
    void missingAttackSectionYieldsUnconfiguredAttack() throws Exception {
        ParseResult r = parse("""
                profiles:
                  plainmob:
                    level: 3
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        MobProfile p = r.profiles().get("plainmob");
        assertNotNull(p);
        assertTrue(!p.hasAttack(), "no attack: block must parse as an unconfigured attack");
    }

    @Test
    void parsesAttackBlockWithNegativeFieldsPreserved() throws Exception {
        ParseResult r = parse("""
                profiles:
                  attacker:
                    level: 8
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                    attack:
                      attack-power: 12.0
                      flat-bonus-damage: 2.0
                      crit-chance: 0.25
                      penetration: -0.5
                """);
        MobProfile p = r.profiles().get("attacker");
        assertNotNull(p);
        assertTrue(p.hasAttack());
        assertEquals(12.0, p.attack().defaultDamage(), DELTA);
        assertEquals(2.0, p.attack().flatBonusDamage(), DELTA);
        assertEquals(0.25, p.attack().critChance(), DELTA);
        assertEquals(-0.5, p.attack().penetration(), DELTA);
    }

    @Test
    void parsesMaxHealthWhenConfigured() throws Exception {
        ParseResult r = parse("""
                profiles:
                  tanky:
                    level: 10
                    max-health: 120.0
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        MobProfile p = r.profiles().get("tanky");
        assertNotNull(p);
        assertEquals(120.0, p.maxHealth(), DELTA);
        assertTrue(p.hasMaxHealth());
    }

    @Test
    void absentMaxHealthIsUnconfigured() throws Exception {
        ParseResult r = parse("""
                profiles:
                  vanillahp:
                    level: 3
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        MobProfile p = r.profiles().get("vanillahp");
        assertNotNull(p);
        assertEquals(0.0, p.maxHealth(), DELTA);
        assertTrue(!p.hasMaxHealth(), "absent max-health must parse as unconfigured (0)");
    }

    @Test
    void negativeMaxHealthEntryIsSkipped() throws Exception {
        ParseResult r = parse("""
                profiles:
                  badhp:
                    level: 1
                    max-health: -5.0
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                  goodhp:
                    level: 1
                    max-health: 50.0
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.profiles().size());
        assertTrue(r.profiles().containsKey("goodhp"));
    }

    @Test
    void dynamicFlagDefaultsFalseWhenAbsent() throws Exception {
        ParseResult r = parse("""
                profiles:
                  fixedmob:
                    level: 3
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        MobProfile p = r.profiles().get("fixedmob");
        assertNotNull(p);
        assertTrue(!p.dynamic(), "absent dynamic: key must parse as false");
    }

    @Test
    void dynamicFlagRoundTripsFromConfig() throws Exception {
        ParseResult r = parse("""
                profiles:
                  dynamicmob:
                    level: 1
                    dynamic: true
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        MobProfile p = r.profiles().get("dynamicmob");
        assertNotNull(p);
        assertTrue(p.dynamic());
    }

    @Test
    void skipsNonSectionEntryButKeepsRest() throws Exception {
        ParseResult r = parse("""
                profiles:
                  bad: 5
                  good:
                    level: 2
                    physical: { defense-rate: 0.0 }
                    magical:  { defense-rate: 0.0 }
                """);
        assertEquals(1, r.skipped());
        assertEquals(1, r.profiles().size());
        assertTrue(r.profiles().containsKey("good"));
    }
}
