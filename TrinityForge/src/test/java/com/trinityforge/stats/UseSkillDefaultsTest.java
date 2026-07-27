package com.trinityforge.stats;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseSkillDefaultsTest {

    @Test
    void removedLegacyCmdDoesNotClassifyUnrelatedMaterial() {
        assertTrue(UseSkillDefaults.infer(Material.STICK, 1_981_826).isEmpty());
    }

    @Test
    void removedLegacyCmdDoesNotOverrideBaseMaterialClassification() {
        assertEquals("LIGHT_WEAPONS",
                UseSkillDefaults.infer(Material.DIAMOND_SWORD, 1_981_827).orElseThrow());
    }

    @Test
    void ironArmorInfersHeavyArmor() {
        assertEquals("HEAVY_ARMOR",
                UseSkillDefaults.infer(Material.IRON_CHESTPLATE, null).orElseThrow());
    }

    @Test
    void leatherArmorInfersLightArmor() {
        assertEquals("LIGHT_ARMOR",
                UseSkillDefaults.infer(Material.LEATHER_HELMET, null).orElseThrow());
    }

    @Test
    void bowInfersArchery() {
        assertEquals("ARCHERY", UseSkillDefaults.infer(Material.BOW, null).orElseThrow());
    }

    @Test
    void nonEquipmentEmpty() {
        assertTrue(UseSkillDefaults.infer(Material.STONE, null).isEmpty());
    }
}
