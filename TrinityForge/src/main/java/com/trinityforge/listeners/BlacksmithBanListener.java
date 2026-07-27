package com.trinityforge.listeners;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.MerchantInventory;

/**
 * 村人の鍛冶職（防具/道具/武器鍛冶）との取引を禁止する。
 * Paper 1.21.11 には {@code BLACKSMITH} 定数が無いため、黒エプロンの3職を対象とする。
 */
public final class BlacksmithBanListener implements Listener {

    private static final Component TRADE_BLOCKED = Component.text(
            "鍛冶村人との取引はできません。", NamedTextColor.RED);

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory merchant)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(merchant.getHolder() instanceof Villager villager)) {
            return;
        }
        if (isBlacksmithProfession(villager.getProfession())) {
            event.setCancelled(true);
            player.sendMessage(TRADE_BLOCKED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractVillager(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        if (isBlacksmithProfession(villager.getProfession())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(TRADE_BLOCKED);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        if (isBlacksmithProfession(event.getProfession())) {
            event.setProfession(Villager.Profession.NONE);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        stripBlacksmithProfession(event.getEntity());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        stripBlacksmithProfession(event.getEntity());
    }

    private static void stripBlacksmithProfession(org.bukkit.entity.Entity entity) {
        if (entity instanceof Villager villager) {
            if (isBlacksmithProfession(villager.getProfession())) {
                villager.setProfession(Villager.Profession.NONE);
            }
            return;
        }
        if (entity instanceof ZombieVillager zombieVillager) {
            if (isBlacksmithProfession(zombieVillager.getVillagerProfession())) {
                zombieVillager.setVillagerProfession(Villager.Profession.NONE);
            }
        }
    }

    /** 防具/道具/武器鍛冶（黒エプロン職）を「鍛冶」として扱う。 */
    private static boolean isBlacksmithProfession(Villager.Profession profession) {
        return profession == Villager.Profession.ARMORER
            || profession == Villager.Profession.TOOLSMITH
            || profession == Villager.Profession.WEAPONSMITH;
    }
}
