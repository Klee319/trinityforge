package com.trinityforge.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PercentStatNormalizeTest {

    @Test
    @DisplayName("damage-modifier 75 (percent points) becomes 0.75")
    void damageModifierPercentPoints() {
        assertEquals(0.75, PercentStatNormalize.coerce("damage-modifier", 75.0), 1e-9);
        assertEquals(0.70, PercentStatNormalize.coerce("damage-modifier", 70.0), 1e-9);
        assertEquals(0.75, PercentStatNormalize.coerce("damage-modifier", 0.75), 1e-9);
        assertEquals(1.2, PercentStatNormalize.coerce("damage-modifier", 1.2), 1e-9);
    }

    @Test
    @DisplayName("crit-damage is not coerced (10 may mean +1000%)")
    void critDamageUntouched() {
        assertEquals(10.0, PercentStatNormalize.coerce("crit-damage", 10.0), 1e-9);
    }

    @Test
    @DisplayName("armor-defense-rate stays armor points (not ÷100)")
    void armorDefenseRateUntouched() {
        assertEquals(8.0, PercentStatNormalize.coerce("armor-defense-rate", 8.0), 1e-9);
        assertEquals(2.0, PercentStatNormalize.coerce("armor-defense-rate", 2.0), 1e-9);
        assertFalse(PercentStatNormalize.isRateKey("armor-defense-rate"));
    }

    @Test
    @DisplayName("2026-07-23 stat-gate-overhaul: new PERCENT keys are rate keys and coerce 75 -> 0.75")
    void newPercentKeysAreRateKeys() {
        String[] newPercentKeys = {
                "bow-accuracy", "ammo-save-chance", "distance-damage-bonus", "arrow-velocity",
                "bow-cooldown-reduction", "melee-knockback", "stun-chance", "power-attack-damage",
                "health-regen-bonus", "hunger-save-chance", "mob-drop-bonus",
                "skill-exp-bonus", "cooldown-reduction", "haste-active-mining-cooldown-reduction",
                "gacha-rate-bonus", "suspicious-respawn-chance",
                "hive-harvest-fortune", "food-save-chance",
                "source-cost-reduction", "material-refund-chance", "ingredient-save-chance",
                "fishing-luck"
        };
        for (String key : newPercentKeys) {
            assertTrue(PercentStatNormalize.isRateKey(key), key + " should be a rate key");
            assertEquals(0.75, PercentStatNormalize.coerce(key, 75.0), 1e-9, key + " should coerce 75 -> 0.75");
        }
    }

    @Test
    @DisplayName("new INTEGER/FLAT keys (arrow-piercing, craft-*) are NOT rate keys")
    void newNonPercentKeysAreNotRateKeys() {
        assertFalse(PercentStatNormalize.isRateKey("arrow-piercing"));
        assertFalse(PercentStatNormalize.isRateKey("craft-roll-inset"));
    }

    @Test
    @DisplayName("enchant-cost-reduction is a rate; stun-duration-bonus is a flat tick count")
    void stunDurationUsesTicksInsteadOfPercentNormalization() {
        assertTrue(PercentStatNormalize.isRateKey("enchant-cost-reduction"));
        assertEquals(0.75, PercentStatNormalize.coerce("enchant-cost-reduction", 75.0), 1e-9);
        assertFalse(PercentStatNormalize.isRateKey("stun-duration-bonus"));
        assertEquals(50.0, PercentStatNormalize.coerce("stun-duration-bonus", 50.0), 1e-9);
        assertEquals(5.0, PercentStatNormalize.coerce("stun-duration-bonus", 0.2), 1e-9,
                "legacy addends must be converted before different sources are aggregated");
        assertEquals(1.0, PercentStatNormalize.coerce("stun-duration-bonus", 1.0), 1e-9,
                "one tick is a valid value in the new unit and must not be treated as legacy 100%");
        assertEquals(-1.0, PercentStatNormalize.coerce("stun-duration-bonus", -1.0), 1e-9);
    }

    @Test
    @DisplayName("2026-07-23 仕様確定: lapis-cost-reduction is a FLAT count (individual units reduced), not a rate")
    void lapisCostReductionIsNotARateKey() {
        assertFalse(PercentStatNormalize.isRateKey("lapis-cost-reduction"));
        assertEquals(1.0, PercentStatNormalize.coerce("lapis-cost-reduction", 1.0), 1e-9);
        assertEquals(75.0, PercentStatNormalize.coerce("lapis-cost-reduction", 75.0), 1e-9,
                "must not be divided by 100 like the RATE_KEYS members are");
    }
}
