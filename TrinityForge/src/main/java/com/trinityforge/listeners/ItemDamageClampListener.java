package com.trinityforge.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/** Keeps custom-durability item damage within Paper's valid range. */
public final class ItemDamageClampListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        int damage = event.getDamage();
        if (damage <= 0) {
            if (damage < 0) event.setDamage(0);
            return;
        }
        ItemStack item = event.getItem();
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return;
        int max = damageable.hasMaxDamage()
                ? damageable.getMaxDamage() : item.getType().getMaxDurability();
        if (max <= 0) return;
        int remaining = Math.max(0, max - damageable.getDamage());
        if (remaining > 0 && damage > remaining) event.setDamage(remaining);
    }
}
