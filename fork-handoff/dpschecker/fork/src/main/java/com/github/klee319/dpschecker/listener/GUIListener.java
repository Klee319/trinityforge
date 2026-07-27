package com.github.klee319.dpschecker.listener;

import com.github.klee319.dpschecker.gui.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

public class GUIListener implements Listener {

    private final JavaPlugin plugin;

    public GUIListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // EquipmentGUI handles raw-slot validation itself (allows item placement)
        if (holder instanceof EquipmentGUI gui) {
            gui.handleClick(player, event, plugin);
            return;
        }

        if (!(holder instanceof MainMenuGUI) && !(holder instanceof StatsGUI)
                && !(holder instanceof SettingsGUI) && !(holder instanceof EffectsGUI)
                && !(holder instanceof TfDefenseGUI)) {
            return;
        }

        // Reject clicks that target the player's own inventory (including shift-click into top)
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        int slot = event.getSlot();

        if (holder instanceof MainMenuGUI gui) {
            gui.handleClick(player, slot, plugin);
        } else if (holder instanceof StatsGUI gui) {
            gui.handleClick(player, slot, plugin);
        } else if (holder instanceof SettingsGUI gui) {
            gui.handleClick(player, slot, plugin);
        } else if (holder instanceof EffectsGUI gui) {
            gui.handleClick(player, slot, event.getClick(), plugin);
        } else if (holder instanceof TfDefenseGUI gui) {
            gui.handleClick(player, slot, plugin);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof MainMenuGUI || holder instanceof StatsGUI
                || holder instanceof SettingsGUI || holder instanceof EffectsGUI
                || holder instanceof TfDefenseGUI) {
            event.setCancelled(true);
        } else if (holder instanceof EquipmentGUI) {
            // Cancel drag if any slot is in the top inventory
            boolean touchesTop = event.getRawSlots().stream().anyMatch(slot -> slot < 27);
            if (touchesTop) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof EquipmentGUI gui) {
            gui.onClose();
        }
    }
}
