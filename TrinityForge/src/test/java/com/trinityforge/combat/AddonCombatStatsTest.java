package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AddonCombatStats} codec: the compact {@code "key=value;key=value"} PDC string that the ArsPaper
 * fork writes (via {@link AddonCombatStats#encode}) and TrinityForge reads for the armor-thread stat
 * channel. Both sides share this codec through the TF jar, so the round-trip and the fail-open parsing of
 * malformed input are the contract that keeps writer and reader in agreement. (The Bukkit PDC read in
 * {@code read(Player)} is thin glue over {@link #parse} and is exercised live, not here.)
 */
class AddonCombatStatsTest {

    @Test
    void roundTripPreservesCanonicalKeysAndValues() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("attack-power", 2.0);      // kebab in -> canonical (snake) out
        stats.put("crit_chance", 0.05);
        stats.put("flat-defense", 3.5);
        stats.put("damage-reduction", -0.1); // a negative (debuff) survives

        Map<String, Double> back = AddonCombatStats.parse(AddonCombatStats.encode(stats));

        assertEquals(2.0, back.get("attack_power"), 0.0);
        assertEquals(0.05, back.get("crit_chance"), 0.0);
        assertEquals(3.5, back.get("flat_defense"), 0.0);
        assertEquals(-0.1, back.get("damage_reduction"), 0.0);
        assertEquals(4, back.size());
    }

    @Test
    void zeroAndNonFiniteValuesAreDropped() {
        Map<String, Double> stats = new LinkedHashMap<>();
        stats.put("attack-power", 0.0);                 // no-op -> not stored
        stats.put("crit-chance", Double.NaN);           // garbage -> not stored
        stats.put("penetration", Double.POSITIVE_INFINITY);
        stats.put("flat-defense", 1.0);                 // the only real one

        String encoded = AddonCombatStats.encode(stats);
        assertEquals("flat_defense=1.0", encoded);

        Map<String, Double> back = AddonCombatStats.parse(encoded);
        assertEquals(1, back.size());
        assertEquals(1.0, back.get("flat_defense"), 0.0);
    }

    @Test
    void emptyMapEncodesToEmptyString() {
        assertEquals("", AddonCombatStats.encode(Map.of()));
    }

    @Test
    void parseOfNullOrBlankIsEmpty() {
        assertTrue(AddonCombatStats.parse(null).isEmpty());
        assertTrue(AddonCombatStats.parse("").isEmpty());
        assertTrue(AddonCombatStats.parse("   ").isEmpty());
    }

    @Test
    void malformedTokensAreSkippedNotFatal() {
        // A blank token, a keyless "=5", a valueless "foo=", a non-numeric value, and a leading empty
        // segment are all skipped; the two well-formed tokens still parse.
        Map<String, Double> back = AddonCombatStats.parse(
                ";attack-power=2.0;=5;foo=;crit-chance=oops;;flat-defense=1.5;");
        assertEquals(2, back.size());
        assertEquals(2.0, back.get("attack_power"), 0.0);
        assertEquals(1.5, back.get("flat_defense"), 0.0);
        assertFalse(back.containsKey("crit_chance"));
    }

    @Test
    void duplicateKeysSumOnParse() {
        Map<String, Double> back = AddonCombatStats.parse("attack-power=2.0;attack_power=3.0");
        assertEquals(1, back.size());
        assertEquals(5.0, back.get("attack_power"), 0.0);
    }
}
