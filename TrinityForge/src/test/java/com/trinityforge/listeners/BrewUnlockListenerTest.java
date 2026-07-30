package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrewUnlockListenerTest {

    @Test
    void sameIngredientWithNoMatchingBottleBaseRemainsVanillaUsable() {
        CraftingFeaturesConfig features = mock(CraftingFeaturesConfig.class);
        BrewPotionSpec spec = new BrewPotionSpec(
                "WATER", "REDSTONE", mock(PotionEffectType.class), 200, 0);
        when(features.brewUnlocks()).thenReturn(
                Map.of("water_redstone", new BrewUnlockGroup(List.of(spec))));

        BrewerInventory inventory = mock(BrewerInventory.class);
        ItemStack ingredient = mock(ItemStack.class);
        when(ingredient.getType()).thenReturn(Material.REDSTONE);
        when(inventory.getIngredient()).thenReturn(ingredient);
        ItemStack bottle = mock(ItemStack.class);
        PotionMeta bottleMeta = mock(PotionMeta.class);
        when(bottle.getType()).thenReturn(Material.POTION);
        when(bottle.getItemMeta()).thenReturn(bottleMeta);
        when(bottleMeta.getBasePotionType()).thenReturn(PotionType.AWKWARD);
        when(inventory.getItem(0)).thenReturn(bottle);

        BrewEvent event = mock(BrewEvent.class);
        when(event.getContents()).thenReturn(inventory);

        BrewUnlockListener listener = new BrewUnlockListener(
                mock(DedicatedEffectsConfig.class), features, mock(Plugin.class));
        listener.onBrew(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void matchingIngredientAndBottleBaseStillRequiresUnlock() {
        CraftingFeaturesConfig features = mock(CraftingFeaturesConfig.class);
        BrewPotionSpec spec = new BrewPotionSpec(
                "WATER", "REDSTONE", mock(PotionEffectType.class), 200, 0);
        when(features.brewUnlocks()).thenReturn(
                Map.of("water_redstone", new BrewUnlockGroup(List.of(spec))));

        BrewerInventory inventory = mock(BrewerInventory.class);
        ItemStack ingredient = mock(ItemStack.class);
        when(ingredient.getType()).thenReturn(Material.REDSTONE);
        when(inventory.getIngredient()).thenReturn(ingredient);
        ItemStack bottle = mock(ItemStack.class);
        PotionMeta bottleMeta = mock(PotionMeta.class);
        when(bottle.getType()).thenReturn(Material.POTION);
        when(bottle.getItemMeta()).thenReturn(bottleMeta);
        when(bottleMeta.getBasePotionType()).thenReturn(PotionType.WATER);
        when(inventory.getItem(0)).thenReturn(bottle);

        BrewEvent event = mock(BrewEvent.class);
        when(event.getContents()).thenReturn(inventory);

        BrewUnlockListener listener = new BrewUnlockListener(
                mock(DedicatedEffectsConfig.class), features, mock(Plugin.class));
        listener.onBrew(event);

        verify(event).setCancelled(true);
    }

    @Test
    void unlockedBottleCannotBypassLockedBottleWithSameIngredient() {
        CraftingFeaturesConfig features = mock(CraftingFeaturesConfig.class);
        PotionEffectType effect = mock(PotionEffectType.class);
        BrewPotionSpec unlockedSpec = new BrewPotionSpec("WATER", "REDSTONE", effect, 200, 0);
        BrewPotionSpec lockedSpec = new BrewPotionSpec("AWKWARD", "REDSTONE", effect, 200, 0);
        when(features.brewUnlocks()).thenReturn(Map.of(
                "unlocked", new BrewUnlockGroup(List.of(unlockedSpec)),
                "locked", new BrewUnlockGroup(List.of(lockedSpec))));

        Player viewer = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(eq(viewer), eq("brew:unlocked"))).thenReturn(true);
        when(dedicatedEffects.isActive(eq(viewer), eq("brew:locked"))).thenReturn(false);

        BrewerInventory inventory = mock(BrewerInventory.class);
        ItemStack ingredient = mock(ItemStack.class);
        when(ingredient.getType()).thenReturn(Material.REDSTONE);
        when(inventory.getIngredient()).thenReturn(ingredient);
        when(inventory.getViewers()).thenReturn(List.of(viewer));
        ItemStack unlockedBottle = potion(PotionType.WATER);
        ItemStack lockedBottle = potion(PotionType.AWKWARD);
        when(inventory.getItem(0)).thenReturn(unlockedBottle);
        when(inventory.getItem(1)).thenReturn(lockedBottle);

        BrewEvent event = mock(BrewEvent.class);
        when(event.getContents()).thenReturn(inventory);

        BrewUnlockListener listener = new BrewUnlockListener(
                dedicatedEffects, features, mock(Plugin.class));
        listener.onBrew(event);

        verify(event).setCancelled(true);
    }

    private static ItemStack potion(PotionType base) {
        ItemStack bottle = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(bottle.getType()).thenReturn(Material.POTION);
        when(bottle.getItemMeta()).thenReturn(meta);
        when(meta.getBasePotionType()).thenReturn(base);
        return bottle;
    }
}
