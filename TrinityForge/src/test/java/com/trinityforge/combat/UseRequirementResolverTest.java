package com.trinityforge.combat;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.UseRequirementResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseRequirementResolverTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void itemStatsEntryOverridesStalePdc(@TempDir File dir) throws IOException {
        writeItemStats(dir, """
                items:
                  BOW:
                    use-skill: ARCHERY
                    use-level-requirement: 30
                    fixed: { attack-power: 10 }
                """);
        ItemStatsConfig config = loadedConfig(dir);

        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        ItemData.of(meta).setUseRequirement("LIGHT_WEAPONS", 80);
        bow.setItemMeta(meta);

        UseRequirementResolver.Resolved resolved = UseRequirementResolver.resolve(bow, config).orElseThrow();
        assertEquals("ARCHERY", resolved.skill());
        assertEquals(30, resolved.level());
    }

    @Test
    void cmdSpecificSkillFromItemStats(@TempDir File dir) throws IOException {
        writeItemStats(dir, """
                items:
                  IRON_SWORD#5103:
                    use-skill: HEAVY_WEAPONS
                    use-level-requirement: 40
                    fixed: { attack-power: 6 }
                """);
        ItemStatsConfig config = loadedConfig(dir);

        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setCustomModelData(5103);
        sword.setItemMeta(meta);

        UseRequirementResolver.Resolved resolved = UseRequirementResolver.resolve(sword, config).orElseThrow();
        assertEquals("HEAVY_WEAPONS", resolved.skill());
        assertEquals(40, resolved.level());
    }

    @Test
    void pdcFallbackWhenNoItemStatsProfile(@TempDir File dir) throws IOException {
        writeItemStats(dir, """
                items: {}
                """);
        ItemStatsConfig config = loadedConfig(dir);

        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = sword.getItemMeta();
        ItemData.of(meta).setUseRequirement("HEAVY_WEAPONS", 20);
        sword.setItemMeta(meta);

        UseRequirementResolver.Resolved resolved = UseRequirementResolver.resolve(sword, config).orElseThrow();
        assertEquals("HEAVY_WEAPONS", resolved.skill());
        assertEquals(20, resolved.level());
    }

    @Test
    void pdcFallbackWhenItemStatsProfileMissingUseSkill(@TempDir File dir) throws IOException {
        writeItemStats(dir, """
                items:
                  IRON_SWORD:
                    fixed: { attack-power: 10 }
                """);
        ItemStatsConfig config = loadedConfig(dir);

        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = sword.getItemMeta();
        ItemData.of(meta).setUseRequirement("HEAVY_WEAPONS", 25);
        sword.setItemMeta(meta);

        UseRequirementResolver.Resolved resolved = UseRequirementResolver.resolve(sword, config).orElseThrow();
        assertEquals("HEAVY_WEAPONS", resolved.skill());
        assertEquals(25, resolved.level());
    }

    @Test
    void levelOnlyRowInfersDefaultSkill(@TempDir File dir) throws IOException {
        writeItemStats(dir, """
                items:
                  IRON_SWORD#5104:
                    use-level-requirement: 20
                    fixed: { attack-power: 10 }
                """);
        ItemStatsConfig config = loadedConfig(dir);

        ItemStack spear = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = spear.getItemMeta();
        meta.setCustomModelData(5104);
        spear.setItemMeta(meta);

        UseRequirementResolver.Resolved resolved = UseRequirementResolver.resolve(spear, config).orElseThrow();
        assertEquals("LIGHT_WEAPONS", resolved.skill());
        assertEquals(20, resolved.level());
    }

    @Test
    void skillOnlyRowKeepsLevelZero(@TempDir File dir) throws IOException {
        writeItemStats(dir, """
                items:
                  IRON_HELMET:
                    use-skill: HEAVY_ARMOR
                    fixed: { armor-defense-rate: 1 }
                """);
        ItemStatsConfig config = loadedConfig(dir);

        ItemStack helm = new ItemStack(Material.IRON_HELMET);
        UseRequirementResolver.Resolved resolved = UseRequirementResolver.resolve(helm, config).orElseThrow();
        assertEquals("HEAVY_ARMOR", resolved.skill());
        assertEquals(0, resolved.level());
    }

    @Test
    void skipInteractGateForRangedWeapons() {
        assertTrue(UseRequirementResolver.skipInteractGate(Material.BOW));
        assertTrue(UseRequirementResolver.skipInteractGate(Material.CROSSBOW));
        assertFalse(UseRequirementResolver.skipInteractGate(Material.IRON_SWORD));
    }

    private static void writeItemStats(File dir, String yaml) throws IOException {
        File file = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
    }

    private static ItemStatsConfig loadedConfig(File dir) {
        return CombatWiringSupport.loadedConfigManager(dir).itemStats();
    }
}
