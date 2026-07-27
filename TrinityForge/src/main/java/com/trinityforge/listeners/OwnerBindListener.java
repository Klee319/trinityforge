package com.trinityforge.listeners;

import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.OwnerBindPolicy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Objects;

/**
 * Blocks non-owners from using SOULBOUND / OWNER_BOUND items that already have an owner.
 * Trade is intentionally unrestricted; ownership gates use including armor equip
 * (inventory click / drag / right-click interact).
 */
public final class OwnerBindListener implements Listener {

    private static final Component DENIED = Component.text(
            "このアイテムは所有者以外は使用できません。", NamedTextColor.RED);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (denyIfNotOwner(player, player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (denyIfNotOwner(player, event.getBow())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof AbstractArrow) {
            // Bows/crossbows are owner-gated on EntityShootBowEvent; cancelling launch here
            // leaves a zero-velocity arrow at the shooter's feet.
            return;
        }
        ProjectileSource source = event.getEntity().getShooter();
        if (!(source instanceof Player player)) {
            return;
        }
        if (event.getEntity() instanceof Trident trident) {
            if (denyIfNotOwner(player, trident.getItem())) {
                event.setCancelled(true);
            }
            return;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        if (denyIfNotOwner(player, main)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        ItemStack stack = event.getItem();
        if (denyIfNotOwner(event.getPlayer(), stack)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (denyIfNotOwner(event.getPlayer(), event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (denyIfNotOwner(event.getPlayer(), event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int raw = event.getRawSlot();
        boolean armorRaw = raw >= 5 && raw <= 8;
        boolean armorInv = event.getSlot() >= 36 && event.getSlot() <= 39;
        if (armorRaw || armorInv) {
            ItemStack moving = event.getCursor();
            if (moving == null || moving.getType().isAir()) {
                moving = event.getCurrentItem();
            }
            if (event.isShiftClick()) {
                moving = event.getCurrentItem();
            }
            if (denyIfNotOwner(player, moving)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.isShiftClick() && event.getCurrentItem() != null && isArmorPiece(event.getCurrentItem())) {
            if (denyIfNotOwner(player, event.getCurrentItem())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispenseArmor(BlockDispenseArmorEvent event) {
        ItemStack stack = event.getItem();
        if (!enforcesOwnershipBind(stack)) {
            return;
        }
        if (!(event.getTargetEntity() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        if (denyIfNotOwner(player, stack)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot >= 5 && slot <= 8 && denyIfNotOwner(player, event.getOldCursor())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private static boolean isArmorPiece(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        String name = stack.getType().name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS") || name.equals("TURTLE_HELMET") || name.equals("ELYTRA")
                || name.equals("CARVED_PUMPKIN");
    }

    private static boolean enforcesOwnershipBind(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        return ItemData.of(stack.getItemMeta()).bindType()
                .map(BindType::enforcesOwnership)
                .orElse(false);
    }

    /** @return true when the action was denied (caller should cancel) */
    static boolean denyIfNotOwner(Player player, ItemStack stack) {
        Objects.requireNonNull(player, "player");
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        if (OwnerBindPolicy.mayUse(data.bindType(), data.owner(), player.getUniqueId())) {
            return false;
        }
        player.sendMessage(DENIED);
        return true;
    }
}
