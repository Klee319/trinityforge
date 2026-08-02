package com.trinityforge.combat;

import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.stats.QualityRollModel;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WeaponAttackStatResolver#resolveItemStats(Material, Integer, int, long)} — the entry point the
 * ArsPaper fork uses to pull a single equipped thread's individually-rolled stats from armor (2026-08-02,
 * random-roll-pools removal: a thread is now just another {@code stats/item-stats.yml} entry with a
 * {@code random} section, resolved through the exact same {@code DerivedItemStats.profileStats} path as
 * a weapon). Exercises the roll layer's determinism contract directly, complementing {@link
 * WeaponAttackStatResolverTest}'s null-safety-only coverage of the sibling two-arg preview overload.
 */
class WeaponAttackStatResolverRollTest {

    private static final AttackStatKeys DEFAULT_KEYS = new AttackStatKeys(
            "flat-bonus-damage", "percent-bonus-damage", "crit-chance", "crit-damage",
            "penetration", "damage-modifier", "fixed-damage");

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("WeaponAttackStatResolverRollTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    // A live split-normal roll model wired in (spreadUp/spreadDown 0.5, no center-inset) — without this,
    // ItemStatsConfig's default rollModelSupplier returns null and the random layer stays inert (段2 is
    // a documented no-op when unwired), which would make the determinism/variance tests below vacuous.
    private static final QualityRollModel ROLL_MODEL = new QualityRollModel(100, 0.5, 0.5, 0.0);

    private static WeaponAttackStatResolver resolverWithRolledEntry(File tempDir) throws IOException {
        File file = new File(tempDir, ItemStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), """
                items:
                  DIAMOND_SWORD:
                    fixed:
                      attack-power: 10.0
                    random:
                      attack-power: { min: 0.0, max: 100.0 }
                """);
        ItemStatsConfig itemStats = new ItemStatsConfig();
        assertTrue(itemStats.load(fakePlugin(tempDir)), "well-formed item-stats.yml must load");
        itemStats.useRollModel(() -> ROLL_MODEL);
        return new WeaponAttackStatResolver(
                itemStats, new CombatDamageConfig(), DEFAULT_KEYS, new CraftingFeaturesConfig());
    }

    @Test
    void sameQualityAndRollSeedYieldTheSameStatsEveryTime(@TempDir File tempDir) throws IOException {
        WeaponAttackStatResolver resolver = resolverWithRolledEntry(tempDir);

        Map<String, Double> first = resolver.resolveItemStats(Material.DIAMOND_SWORD, null, 50, 12345L);
        Map<String, Double> second = resolver.resolveItemStats(Material.DIAMOND_SWORD, null, 50, 12345L);
        Map<String, Double> third = resolver.resolveItemStats(Material.DIAMOND_SWORD, null, 50, 12345L);

        assertEquals(first, second, "identical (quality, rollSeed) must derive identical stats");
        assertEquals(first, third, "identical (quality, rollSeed) must derive identical stats");
    }

    @Test
    void differentRollSeedsYieldDifferentStats(@TempDir File tempDir) throws IOException {
        // Two distinct DIAMOND_SWORD instances (different rollSeed) must be able to roll different
        // attack-power, proving the roll layer is actually seeded per-item and not pinned like the
        // two-arg preview overload (quality=0/rollSeed=0). A wide { min: 0, max: 100 } range makes a
        // same-value collision across seeds astronomically unlikely, so a single comparison is enough.
        WeaponAttackStatResolver resolver = resolverWithRolledEntry(tempDir);

        Map<String, Double> seedA = resolver.resolveItemStats(Material.DIAMOND_SWORD, null, 50, 111L);
        Map<String, Double> seedB = resolver.resolveItemStats(Material.DIAMOND_SWORD, null, 50, 222L);

        assertNotEquals(seedA.get("attack_power"), seedB.get("attack_power"),
                "different rollSeed must (almost certainly) roll a different attack-power");
    }
}
