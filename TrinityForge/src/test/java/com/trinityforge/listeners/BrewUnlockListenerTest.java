package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.BrewPotionMixRegistrar.MixPlan;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BrewUnlockListener#onBrew} の完成時ゲート。
 *
 * <p>2026-07-31 (D10 レビュー指摘#1(b)): 解放判定は「スタンドに記録された所有者」で行う。
 * 閲覧者・半径8ブロックのプレイヤーで判定していた旧実装は、解放者が醸造中(20秒)に離れるだけで
 * キャンセルへ落ち、キャンセルは素材を減らさないため<b>燃料無限消費ループ</b>を生んでいた。
 */
class BrewUnlockListenerTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    @Test
    void sameIngredientWithNoMatchingBottleBaseRemainsVanillaUsable() {
        BrewerInventory inventory = standWithIngredient(Material.REDSTONE, potion(PotionType.AWKWARD));
        BrewEvent event = brewEvent(inventory);

        listener(mock(DedicatedEffectsConfig.class), owners(null, null),
                plan("water_redstone", "WATER", "REDSTONE", 10)).onBrew(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void matchingIngredientAndBottleBaseStillRequiresUnlock() {
        BrewerInventory inventory = standWithIngredient(Material.REDSTONE, potion(PotionType.WATER));
        BrewEvent event = brewEvent(inventory);

        listener(mock(DedicatedEffectsConfig.class), owners(null, null),
                plan("water_redstone", "WATER", "REDSTONE", 10)).onBrew(event);

        verify(event).setCancelled(true);
    }

    @Test
    void unlockedBottleCannotBypassLockedBottleWithSameIngredient() {
        Player owner = mock(Player.class);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(eq(owner), eq("brew:unlocked"))).thenReturn(true);
        when(dedicatedEffects.isActive(eq(owner), eq("brew:locked"))).thenReturn(false);

        BrewerInventory inventory = standWithIngredient(Material.REDSTONE, potion(PotionType.WATER));
        ItemStack lockedBottle = potion(PotionType.AWKWARD); // 先に組む(when の中で stub すると壊れる)
        when(inventory.getItem(1)).thenReturn(lockedBottle);
        BrewEvent event = brewEvent(inventory);

        listener(dedicatedEffects, owners(OWNER, owner),
                plan("unlocked", "WATER", "REDSTONE", 10),
                plan("locked", "AWKWARD", "REDSTONE", 10)).onBrew(event);

        verify(event).setCancelled(true);
    }

    // ---- ヘルパー ----

    private static BrewUnlockListener listener(DedicatedEffectsConfig dedicatedEffects,
                                               BrewStandOwners owners, MixPlan... plans) {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("BrewUnlockListenerTest"));
        return new BrewUnlockListener(dedicatedEffects, () -> List.of(plans), owners, plugin);
    }

    private static MixPlan plan(String groupId, String base, String ingredient, int requirementLevel) {
        return new MixPlan(new NamespacedKey("trinityforge", "brew_" + groupId + "_1"), groupId,
                new BrewPotionSpec(base, ingredient, mock(PotionEffectType.class), 200, 0),
                requirementLevel);
    }

    private static BrewerInventory standWithIngredient(Material ingredientType, ItemStack bottle) {
        BrewerInventory inventory = mock(BrewerInventory.class);
        ItemStack ingredient = mock(ItemStack.class);
        when(ingredient.getType()).thenReturn(ingredientType);
        when(inventory.getIngredient()).thenReturn(ingredient);
        when(inventory.getItem(0)).thenReturn(bottle);
        return inventory;
    }

    private static BrewEvent brewEvent(BrewerInventory inventory) {
        BrewEvent event = mock(BrewEvent.class);
        when(event.getContents()).thenReturn(inventory);
        when(event.getResults()).thenReturn(new ArrayList<>());
        return event;
    }

    private static ItemStack potion(PotionType base) {
        ItemStack bottle = mock(ItemStack.class);
        PotionMeta meta = mock(PotionMeta.class);
        when(bottle.getType()).thenReturn(Material.POTION);
        when(bottle.getItemMeta()).thenReturn(meta);
        when(meta.getBasePotionType()).thenReturn(base);
        return bottle;
    }

    private static BrewStandOwners owners(UUID recorded, Player online) {
        return new BrewStandOwners() {
            @Override
            public Optional<UUID> ownerOf(BrewerInventory brew) {
                return Optional.ofNullable(recorded);
            }

            @Override
            public void remember(BrewerInventory brew, Player player) {
                // 記録の検証は BrewUnlockIngredientGateTest 側で行う。
            }

            @Override
            public Player online(UUID uuid) {
                return online;
            }
        };
    }
}
