package com.trinityforge.config.domains;

import com.trinityforge.stats.MaterialLists;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialListsConfigTest {

    private static final Logger LOG = Logger.getLogger("MaterialListsConfigTest");

    @AfterEach
    void resetLists() {
        MaterialLists.update(Map.of(), Map.of());
    }

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return config;
    }

    @Test
    void parsesListsWithLabelsIntoSnapshot() {
        boolean ok = MaterialListsConfig.parseInto(yaml("""
                lists:
                  planks:
                    label: 板材
                    materials: [OAK_PLANKS, SPRUCE_PLANKS]
                  wool:
                    materials: [RED_WOOL]
                """).getConfigurationSection("lists"), LOG);
        assertTrue(ok);
        assertEquals(Set.of(Material.OAK_PLANKS, Material.SPRUCE_PLANKS),
                MaterialLists.resolve("planks"));
        assertEquals(Set.of(Material.RED_WOOL), MaterialLists.resolve("wool"));
        assertEquals("板材", MaterialLists.labelOf("planks"));
        // ラベル未指定は id にフォールバック
        assertEquals("wool", MaterialLists.labelOf("wool"));
        assertEquals(Set.of("planks", "wool"), Set.copyOf(MaterialLists.ids()));
    }

    @Test
    void unknownMaterialIsIgnoredButListSurvives() {
        boolean ok = MaterialListsConfig.parseInto(yaml("""
                lists:
                  planks:
                    materials: [OAK_PLANKS, NOT_A_MATERIAL]
                """).getConfigurationSection("lists"), LOG);
        assertTrue(ok);
        assertEquals(Set.of(Material.OAK_PLANKS), MaterialLists.resolve("planks"));
    }

    @Test
    void customMembersAreRetainedAlongsideMaterials() {
        boolean ok = MaterialListsConfig.parseInto(yaml("""
                lists:
                  mixed:
                    materials: [IRON_INGOT, custom:external_core]
                """).getConfigurationSection("lists"), LOG);
        assertTrue(ok);
        assertEquals(Set.of(Material.IRON_INGOT), MaterialLists.resolve("mixed"));
        assertEquals(Set.of("external_core"), MaterialLists.resolveCustomIds("mixed"));
    }

    @Test
    void emptyOrMalformedListIsSkippedWithFailureFlag() {
        boolean ok = MaterialListsConfig.parseInto(yaml("""
                lists:
                  empty:
                    materials: []
                  broken: not-a-section
                  good:
                    materials: [DIAMOND]
                """).getConfigurationSection("lists"), LOG);
        assertFalse(ok);
        assertFalse(MaterialLists.exists("empty"));
        assertFalse(MaterialLists.exists("broken"));
        assertEquals(Set.of(Material.DIAMOND), MaterialLists.resolve("good"));
    }

    @Test
    void missingRootYieldsEmptySnapshot() {
        assertTrue(MaterialListsConfig.parseInto(null, LOG));
        assertTrue(MaterialLists.ids().isEmpty());
        assertTrue(MaterialLists.resolve("anything").isEmpty());
    }
}
