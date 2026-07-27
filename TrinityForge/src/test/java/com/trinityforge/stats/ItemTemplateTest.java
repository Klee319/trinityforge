package com.trinityforge.stats;

import com.trinityforge.pdc.BindType;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTemplateTest {

    @Test
    void rejectsNegativeUseLevel() {
        assertThrows(IllegalArgumentException.class, () -> new ItemTemplate(
                "x", Material.DIAMOND_SWORD, null, null, BindType.SOULBOUND, -1, "HEAVY_WEAPONS"));
    }

    @Test
    void hasUseRequirementWhenSkillAndLevelSet() {
        ItemTemplate t = new ItemTemplate(
                "x", Material.DIAMOND_SWORD, null, null, BindType.SOULBOUND, 20, "HEAVY_WEAPONS");
        assertTrue(t.hasUseRequirement());
    }

    @Test
    void noUseRequirementWhenLevelZero() {
        ItemTemplate t = new ItemTemplate(
                "x", Material.DIAMOND_SWORD, null, null, BindType.TRADEABLE, 0, "HEAVY_WEAPONS");
        assertFalse(t.hasUseRequirement());
    }

    @Test
    void noUseRequirementWhenSkillBlank() {
        ItemTemplate t = new ItemTemplate(
                "x", Material.DIAMOND_SWORD, null, null, BindType.TRADEABLE, 20, "  ");
        assertFalse(t.hasUseRequirement());
    }

    @Test
    void backCompatConstructorDefaultsColorNullAndGlowFalse() {
        ItemTemplate t = new ItemTemplate("x", Material.DIAMOND_SWORD, null, null,
                BindType.TRADEABLE, 0, null, java.util.List.of(), null);
        assertNull(t.color());
        assertFalse(t.enchantGlow());
    }

    @Test
    void canonicalConstructorCarriesColorAndGlow() {
        ItemTemplate t = new ItemTemplate("vest", Material.LEATHER_CHESTPLATE, null, null,
                BindType.TRADEABLE, 0, null, java.util.List.of(), (RecipeSpec) null, "#8B0000", true);
        assertEquals("#8B0000", t.color());
        assertTrue(t.enchantGlow());
    }
}
