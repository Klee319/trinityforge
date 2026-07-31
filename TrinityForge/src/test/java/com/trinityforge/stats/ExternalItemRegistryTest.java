package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reload-ordering semantics for {@link ExternalItemRegistry}'s layered merge: TrinityForge's own
 * {@code items/external-items.yml} layer ({@link ExternalItemRegistry#update}) and each
 * contributing plugin's layer ({@link ExternalItemRegistry#updateExternalPlugin}, keyed by a
 * stable source name) must be independently replaceable without clobbering one another — this is
 * the fix for the {@code source_gem} craft-result bug: ArsPaper registers its {@code materials.yml}
 * items into a plugin layer, and a later TrinityForge {@code /trinityforge reload} (which reloads
 * {@code external-items.yml} via {@link #update}) must not wipe them back out.
 */
class ExternalItemRegistryTest {

    @AfterEach
    void tearDown() {
        // Reset both layers so tests never leak state into each other or into other test classes.
        ExternalItemRegistry.update(Map.of());
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of());
        ExternalItemRegistry.updateExternalPlugin("otherplugin", Map.of());
    }

    private static ExternalItemRegistry.Definition def(String id, Material material, int cmd) {
        return new ExternalItemRegistry.Definition(id, material, cmd, id);
    }

    @Test
    void tfReloadOfLocalLayerDoesNotWipeArsPaperLayer() {
        ExternalItemRegistry.updateExternalPlugin("arspaper",
                Map.of("source_gem", def("source_gem", Material.PRISMARINE_SHARD, 100011)));

        // TF reloads its own external-items.yml (empty file) — simulates ExternalItemsConfig.load().
        ExternalItemRegistry.update(Map.of());

        assertTrue(ExternalItemRegistry.find("source_gem").isPresent(),
                "TF reloading its local layer must not wipe ArsPaper's plugin layer");
    }

    @Test
    void arsPaperReloadOfItsOwnLayerDoesNotWipeTfLocalLayer() {
        ExternalItemRegistry.update(
                Map.of("some_addon_item", def("some_addon_item", Material.NETHER_STAR, 42)));
        ExternalItemRegistry.updateExternalPlugin("arspaper",
                Map.of("source_gem", def("source_gem", Material.PRISMARINE_SHARD, 100011)));

        // ArsPaper reloads (materials.yml changed, source_gem_block added) — replaces only its layer.
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of(
                "source_gem", def("source_gem", Material.PRISMARINE_SHARD, 100011),
                "source_gem_block", def("source_gem_block", Material.PRISMARINE, 100012)));

        assertTrue(ExternalItemRegistry.find("some_addon_item").isPresent(),
                "ArsPaper reloading its own layer must not wipe TF's local external-items.yml layer");
        assertTrue(ExternalItemRegistry.find("source_gem_block").isPresent());
    }

    @Test
    void arsPaperReloadReplacesRatherThanMergesItsOwnLayer() {
        ExternalItemRegistry.updateExternalPlugin("arspaper",
                Map.of("removed_material", def("removed_material", Material.STONE, 99)));

        // Next reload no longer defines removed_material (deleted from materials.yml).
        ExternalItemRegistry.updateExternalPlugin("arspaper",
                Map.of("source_gem", def("source_gem", Material.PRISMARINE_SHARD, 100011)));

        assertFalse(ExternalItemRegistry.find("removed_material").isPresent(),
                "a stale entry from a prior ArsPaper registration must not survive its own reload");
        assertTrue(ExternalItemRegistry.find("source_gem").isPresent());
    }

    @Test
    void arsPaperAbsentLeavesOnlyTheLocalLayer() {
        ExternalItemRegistry.update(
                Map.of("some_addon_item", def("some_addon_item", Material.NETHER_STAR, 42)));
        // ArsPaper never calls updateExternalPlugin at all in this scenario.

        assertTrue(ExternalItemRegistry.find("some_addon_item").isPresent());
        assertFalse(ExternalItemRegistry.find("source_gem").isPresent());
    }

    @Test
    void emptyPluginLayerUpdateClearsThatSourceOnly() {
        ExternalItemRegistry.update(
                Map.of("some_addon_item", def("some_addon_item", Material.NETHER_STAR, 42)));
        ExternalItemRegistry.updateExternalPlugin("arspaper",
                Map.of("source_gem", def("source_gem", Material.PRISMARINE_SHARD, 100011)));

        // Simulates ArsPaper disabling: pushes an empty map for its source.
        ExternalItemRegistry.updateExternalPlugin("arspaper", Map.of());

        assertFalse(ExternalItemRegistry.find("source_gem").isPresent());
        assertTrue(ExternalItemRegistry.find("some_addon_item").isPresent());
    }

    @Test
    void localLayerWinsAnIdCollisionAgainstAPluginLayer() {
        ExternalItemRegistry.updateExternalPlugin("arspaper",
                Map.of("source_gem", def("source_gem", Material.PRISMARINE_SHARD, 100011)));
        ExternalItemRegistry.update(
                Map.of("source_gem", def("source_gem", Material.PRISMARINE, 999)));

        ExternalItemRegistry.Definition resolved = ExternalItemRegistry.find("source_gem").orElseThrow();
        assertEquals(Material.PRISMARINE, resolved.material(),
                "an explicit external-items.yml entry must win an id collision with a plugin layer");
        assertEquals(999, resolved.customModelData());
    }

    // ------------------------------------------------------------------
    // pluginSourceOf — 「この material+CMD はどのプラグインの所有物か」 (D5)
    // ------------------------------------------------------------------

    @Test
    void pluginSourceOfNamesTheLayerThatClaimsTheMaterialAndCmd() {
        ExternalItemRegistry.updateExternalPlugin("arspaper",
                Map.of("source_berry", def("source_berry", Material.GLOW_BERRIES, 100010)));
        ExternalItemRegistry.updateExternalPlugin("otherplugin",
                Map.of("moon_dust", def("moon_dust", Material.SUGAR, 500)));

        assertEquals("arspaper",
                ExternalItemRegistry.pluginSourceOf(Material.GLOW_BERRIES, 100010).orElse(null));
        assertEquals("otherplugin",
                ExternalItemRegistry.pluginSourceOf(Material.SUGAR, 500).orElse(null));
    }

    @Test
    void pluginSourceOfIsEmptyForVanillaStacksAndUnknownCmds() {
        ExternalItemRegistry.updateExternalPlugin("arspaper",
                Map.of("source_berry", def("source_berry", Material.GLOW_BERRIES, 100010)));

        assertFalse(ExternalItemRegistry.pluginSourceOf(Material.GLOW_BERRIES, null).isPresent(),
                "CMD の無いスタックは誰の所有物でもない");
        assertFalse(ExternalItemRegistry.pluginSourceOf(Material.GLOW_BERRIES, 1).isPresent());
        assertFalse(ExternalItemRegistry.pluginSourceOf(null, 100010).isPresent());
    }

    @Test
    void pluginSourceOfIgnoresTheLocalExternalItemsLayer() {
        // external-items.yml は「TF が外部品を認識するため」の記述であって所有宣言ではないので、
        // ここに書いた品は「どのプラグインの所有物でもない」= 委譲の対象外でなければならない。
        ExternalItemRegistry.update(
                Map.of("some_addon_item", def("some_addon_item", Material.NETHER_STAR, 42)));

        assertTrue(ExternalItemRegistry.find(Material.NETHER_STAR, 42).isPresent(),
                "local layer は find では引ける");
        assertFalse(ExternalItemRegistry.pluginSourceOf(Material.NETHER_STAR, 42).isPresent(),
                "local layer は所有プラグインを持たない");
    }
}
