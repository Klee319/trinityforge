package com.github.klee319.dpschecker.listener;

import com.github.klee319.dpschecker.calculator.DpsSessionManager;
import com.github.klee319.dpschecker.dummy.DamageRecord;
import com.github.klee319.dpschecker.dummy.DummyEntity;
import com.github.klee319.dpschecker.dummy.DummyManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public class DamageListener implements Listener {

    private final JavaPlugin plugin;
    private final DummyManager dummyManager;
    private final DpsSessionManager sessionManager;

    public DamageListener(JavaPlugin plugin, DummyManager dummyManager, DpsSessionManager sessionManager) {
        this.plugin = plugin;
        this.dummyManager = dummyManager;
        this.sessionManager = sessionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        Optional<DummyEntity> opt = dummyManager.getDummyByEntity(event.getEntity());
        if (opt.isEmpty()) return;

        DummyEntity dummy = opt.get();

        java.util.UUID attackerUuid = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof Player player) {
                attackerUuid = player.getUniqueId();
            }
        }

        // Cap final damage at remaining HP to prevent inflated stats on killing blow
        double finalDamage = event.getFinalDamage();
        if (event.getEntity() instanceof LivingEntity living) {
            finalDamage = Math.min(finalDamage, living.getHealth());
        }

        DamageRecord record = new DamageRecord(
                finalDamage,
                event.getDamage(),
                event.getCause(),
                attackerUuid
        );

        dummy.onDamage(record);
        sessionManager.onDamage(dummy, record);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (event.getAction() != EntityPotionEffectEvent.Action.ADDED) return;
        if (event.getNewEffect() == null) return;
        Optional<DummyEntity> opt = dummyManager.getDummyByEntity(event.getEntity());
        if (opt.isEmpty()) return;
        DummyEntity dummy = opt.get();
        if (dummy.isUndead()) return;

        // When undead is OFF, invert instant-heal / instant-damage behaviour so the
        // zombie reacts the way a living entity would.
        var type = event.getNewEffect().getType();
        int level = event.getNewEffect().getAmplifier(); // 0-based amplifier

        if (type.equals(org.bukkit.potion.PotionEffectType.INSTANT_HEALTH)) {
            event.setCancelled(true);
            applyHeal(dummy, 4.0 * Math.pow(2.0, level));
        } else if (type.equals(org.bukkit.potion.PotionEffectType.INSTANT_DAMAGE)) {
            event.setCancelled(true);
            applyMagicDamage(dummy, 6.0 * Math.pow(2.0, level));
        }
    }

    private void applyHeal(DummyEntity dummy, double amount) {
        Zombie z = dummy.getEntity();
        if (z == null || z.isDead()) return;
        double next = Math.min(dummy.getMaxHp(), z.getHealth() + amount);
        z.setHealth(next);
        dummy.updateBossBar();
    }

    private void applyMagicDamage(DummyEntity dummy, double amount) {
        Zombie z = dummy.getEntity();
        if (z == null || z.isDead()) return;
        z.damage(amount);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDeath(EntityDeathEvent event) {
        Optional<DummyEntity> opt = dummyManager.getDummyByEntity(event.getEntity());
        if (opt.isEmpty()) return;

        DummyEntity dummy = opt.get();
        event.getDrops().clear();
        event.setDroppedExp(0);

        FileConfiguration config = plugin.getConfig();
        if (config.getBoolean("dummy.auto-respawn", true)) {
            Bukkit.getScheduler().runTaskLater(plugin, dummy::respawn, 20L);
        } else {
            // When auto-respawn is disabled, remove the dummy from manager to free quota
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                dummyManager.removeDummy(dummy.getDummyUuid());
            }, 1L);
        }
    }
}
