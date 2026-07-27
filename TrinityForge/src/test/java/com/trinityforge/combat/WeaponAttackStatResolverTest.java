package com.trinityforge.combat;

import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Null-safety check for the ItemStack -&gt; {@link AttackStats} public API (the ArsPaper catalyst-stat
 * entry point). The map-&gt;stats mapping itself is exercised by {@link AttackStatBridgeTest} and the
 * material/roll derivation by the stats package, so the resolver's own contribution to test is its
 * guard: a null item must resolve to {@link AttackStats#plain(0)} (defaultDamage 0, the pipeline
 * injects the level-scaled base). The AIR/empty-amount branch uses the same guard, but constructing a
 * real {@code ItemStack} — like exercising a populated weapon's {@code getItemMeta()} — needs a live
 * Bukkit ItemFactory, which is unavailable in a headless unit test (no MockBukkit on the classpath); the
 * populated path is covered by the shared {@code DerivedItemStats} + {@code AttackStatBridge} units it reuses.
 */
class WeaponAttackStatResolverTest {

    private static final AttackStatKeys DEFAULT_KEYS = new AttackStatKeys(
            "flat-bonus-damage", "percent-bonus-damage", "crit-chance", "crit-damage",
            "penetration", "damage-modifier", "fixed-damage");

    private static WeaponAttackStatResolver resolver() {
        return new WeaponAttackStatResolver(
                new ItemStatsConfig(),
                new CombatDamageConfig(), DEFAULT_KEYS, new CraftingFeaturesConfig());
    }

    @Test
    void nullItemYieldsPlainZero() {
        assertEquals(AttackStats.plain(0), resolver().forItem(null));
    }

    @Test
    void attackPowerOfNullItemIsZero() {
        // The catalyst attack-power entry point for the ArsPaper magic-hybrid path: a null item must
        // yield 0.0. The populated path reuses DerivedItemStats (covered by the stats package) and, like
        // forItem, needs a live Bukkit ItemFactory that is unavailable headless, so only the guard is
        // unit-tested here; the getOrDefault(attack-power) extraction it wraps is a trivial map read.
        assertEquals(0.0, resolver().attackPowerOf(null), 0.0);
    }
}
