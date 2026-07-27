package com.trinityforge.mob;

import com.trinityforge.config.domains.DisplayConfig;
import com.trinityforge.integration.TrainingDummies;
import com.trinityforge.pdc.PdcKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Cosmetic-only per-hit damage popup: when a player lands a hit on a non-player {@link
 * LivingEntity}, a short-lived {@link TextDisplay} showing the rounded final damage floats above
 * the victim for {@link DisplayConfig#damagePopupDurationTicks()}.
 *
 * <p>Purely event-driven (unlike {@link FocusHpDisplay}, no periodic tick task is needed): each hit
 * spawns its own display and schedules its own one-shot removal. Displays are so short-lived
 * (default 15 ticks = 0.75s) that tracking them in a set purely for a forced shutdown-removal would
 * add state for no real benefit (KISS) — {@code shutdown()} is intentionally omitted; only an
 * enable-time orphan sweep (crash recovery) is provided, mirroring FocusHpDisplay's sweep.
 */
public final class DamagePopupDisplay implements Listener {

    private static final double EYE_HEIGHT_OFFSET = 0.65;

    private final Plugin plugin;
    private final DisplayConfig displayConfig;

    public DamagePopupDisplay(Plugin plugin, DisplayConfig displayConfig) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.displayConfig = Objects.requireNonNull(displayConfig, "displayConfig");
    }

    /** Arms the listener: sweeps any orphaned popup displays left behind by a crash. */
    public void start() {
        sweepOrphans();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!displayConfig.damagePopupEnabled()) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim) || victim instanceof Player) {
            return;
        }
        if (victim instanceof ArmorStand) {
            // ArmorStand implements LivingEntity in the Bukkit API (it has "health"/can be damaged) but
            // is a decoration, not a mob with meaningful HP; showing a damage number above it reads as a
            // bug. Excluded unconditionally (not made configurable) because this is a type-correctness
            // fix, not a policy choice — nothing about it should ever want the popup.
            return;
        }
        if (TrainingDummies.isTrainingDummy(victim)) {
            return;
        }
        double finalDamage = event.getFinalDamage();
        if (finalDamage < displayConfig.damagePopupMinDamage()) {
            return;
        }
        spawnPopup(victim, finalDamage);
    }

    /** Player damager, or the player shooter of a projectile damager (bow/crossbow/trident). */
    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    private void spawnPopup(LivingEntity victim, double finalDamage) {
        Location location = victim.getLocation();
        location.add(0.0, victim.getEyeHeight() + EYE_HEIGHT_OFFSET, 0.0);
        Component text = Component.text(String.valueOf(Math.round(finalDamage)));

        TextDisplay display = victim.getWorld().spawn(location, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.getPersistentDataContainer().set(PdcKeys.DAMAGE_POPUP_DISPLAY, PersistentDataType.BYTE, (byte) 1);
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setSeeThrough(false);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(140, 16, 16, 20));
            d.setTextOpacity((byte) 210);
            d.text(text);
        });

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (display.isValid()) {
                display.remove();
            }
        }, displayConfig.damagePopupDurationTicks());
    }

    private void sweepOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay display
                        && display.getPersistentDataContainer().has(PdcKeys.DAMAGE_POPUP_DISPLAY, PersistentDataType.BYTE)) {
                    display.remove();
                }
            }
        }
    }
}
