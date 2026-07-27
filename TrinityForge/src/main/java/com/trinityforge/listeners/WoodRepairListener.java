package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.WoodRepairMaterial;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.CatalogIdentity;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Objects;
import java.util.Optional;

/** Compressed-wood durability repair (woodcutting wood-repair-unlock). */
public final class WoodRepairListener implements Listener {

    private static final String UNLOCK = "wood-repair-unlock";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;

    public WoodRepairListener(DedicatedEffectsConfig dedicatedEffects, CraftingFeaturesConfig features) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        if (!dedicatedEffects.isActive(player, UNLOCK)) {
            return;
        }
        ItemStack left = event.getInventory().getFirstItem();
        ItemStack material = event.getInventory().getSecondItem();
        if (left == null || material == null || !(left.getItemMeta() instanceof Damageable damageable)) {
            return;
        }
        Optional<String> catalogId = material.hasItemMeta()
                ? CatalogIdentity.catalogIdOf(material.getItemMeta())
                : Optional.empty();
        if (catalogId.isEmpty()) {
            return;
        }
        WoodRepairMaterial mat = features.woodRepairMaterial(catalogId.get());
        if (mat == null) {
            return;
        }
        if (damageable.getDamage() <= 0) {
            return;
        }
        int repair = mat.durability();
        ItemStack result = left.clone();
        ItemMetaRepair.applyRepair(result, Math.min(damageable.getDamage(), repair));
        event.setResult(result);
        event.getInventory().setRepairCost(1);
        // No action-bar here: PrepareAnvil fires continuously while items sit in the anvil.
    }

    /**
     * Anvil-free quick-repair: cursor holds a {@code quick-repair: true} material, click target is a
     * damaged {@link Damageable} equipment piece in the player's own inventory view (survival
     * inventory or its built-in 2x2 crafting grid — {@link InventoryType#CRAFTING}). Consumes 1
     * material per click (LEFT or RIGHT), repairs the target by {@code min(damage, mat.durability())},
     * and cancels the event so vanilla's normal item-move does not also fire. No-ops (lets the vanilla
     * click proceed) for any other GUI, non-quick-repair material, or an undamaged/non-repairable target.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!dedicatedEffects.isActive(player, UNLOCK)) {
            return;
        }
        // Only the player's own inventory screen (bottom PlayerInventory + its built-in 2x2 crafting
        // grid) is InventoryType.CRAFTING; chests/anvils/workbench/etc use other types and must not be
        // touched here.
        if (event.getView().getType() != InventoryType.CRAFTING) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir() || !cursor.hasItemMeta()) {
            return;
        }
        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()
                || !(target.getItemMeta() instanceof Damageable targetMeta) || targetMeta.getDamage() <= 0) {
            return;
        }
        Optional<String> catalogId = CatalogIdentity.catalogIdOf(cursor.getItemMeta());
        if (catalogId.isEmpty()) {
            return;
        }
        WoodRepairMaterial mat = features.woodRepairMaterial(catalogId.get());
        if (mat == null || !mat.quickRepair()) {
            return;
        }

        int repairAmount = Math.min(targetMeta.getDamage(), mat.durability());
        ItemStack repaired = target.clone();
        ItemMetaRepair.applyRepair(repaired, repairAmount);
        event.setCurrentItem(repaired);

        ItemStack newCursor = cursor.getAmount() > 1 ? cursor.clone() : null;
        if (newCursor != null) {
            newCursor.setAmount(newCursor.getAmount() - 1);
        }
        player.setItemOnCursor(newCursor);
        event.setCancelled(true);
        player.sendActionBar(Component.text("装備を修繕しました。", NamedTextColor.GREEN));
    }

    private static final class ItemMetaRepair {
        private static void applyRepair(ItemStack stack, int amount) {
            if (!(stack.getItemMeta() instanceof Damageable d)) {
                return;
            }
            d.setDamage(Math.max(0, d.getDamage() - amount));
            stack.setItemMeta(d);
        }
    }
}
