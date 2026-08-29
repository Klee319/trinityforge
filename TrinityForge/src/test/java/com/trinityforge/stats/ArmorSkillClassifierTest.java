package com.trinityforge.stats;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorSkillClassifierTest {

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
    @DisplayName("ダイヤモンド基材でも use-skill が LIGHT_ARMOR なら軽装")
    void diamondPieceWithLightArmorTagIsLight() {
        ItemStack helmet = new ItemStack(Material.DIAMOND_HELMET);
        ItemMeta meta = helmet.getItemMeta();
        ItemData.of(meta).setUseRequirement(SkillId.LIGHT_ARMOR, 25);
        helmet.setItemMeta(meta);

        assertTrue(ArmorSkillClassifier.isLight(helmet),
                "ソースジェムはダイヤモンド基材だが item-stats は LIGHT_ARMOR");
    }

    @Test
    @DisplayName("use-skill が HEAVY_ARMOR なら革でも重装")
    void leatherPieceWithHeavyArmorTagIsHeavy() {
        ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE);
        ItemMeta meta = chest.getItemMeta();
        ItemData.of(meta).setUseRequirement(SkillId.HEAVY_ARMOR, 1);
        chest.setItemMeta(meta);

        assertFalse(ArmorSkillClassifier.isLight(chest));
    }

    @Test
    @DisplayName("PDC が無いダイヤモンドは素材既定の重装")
    void untaggedDiamondIsHeavy() {
        assertFalse(ArmorSkillClassifier.isLight(new ItemStack(Material.DIAMOND_CHESTPLATE)));
    }

    @Test
    @DisplayName("PDC が無い革は素材既定の軽装")
    void untaggedLeatherIsLight() {
        assertTrue(ArmorSkillClassifier.isLight(new ItemStack(Material.LEATHER_BOOTS)));
    }
}
