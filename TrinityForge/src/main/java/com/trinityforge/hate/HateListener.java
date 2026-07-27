package com.trinityforge.hate;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import com.trinityforge.progression.RoleBuffResolver;

import java.util.Objects;

/**
 * Wires the {@link HateService} to the Bukkit event bus: it feeds threat from combat and, more
 * importantly for gap C5, closes every leak vector by evicting stale data.
 *
 * <p>All handlers run at {@link EventPriority#MONITOR} and never mutate the events: this listener
 * only reads finalized damage and reclaims memory, so it cannot affect combat or other plugins.
 */
public final class HateListener implements Listener {

    private final HateService hateService;
    private final RoleBuffResolver roleBuffResolver;

    public HateListener(HateService hateService, RoleBuffResolver roleBuffResolver) {
        this.hateService = Objects.requireNonNull(hateService, "hateService");
        this.roleBuffResolver = Objects.requireNonNull(roleBuffResolver, "roleBuffResolver");
    }

    /**
     * Threat accumulation: a player damaging a {@link Mob}. Covers both melee (damager is the
     * player) and ranged attacks (damager is a {@link Projectile} whose shooter resolves to a
     * player) so bow/trident/etc. combat is not a free hate exemption. The victim is filtered to
     * {@code instanceof Mob} rather than merely "not a player" so armor stands, item frames, and
     * other non-combat {@link Entity} kinds never pollute the threat table.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null) {
            return;
        }
        Entity victim = event.getEntity();
        if (victim instanceof Mob) {
            double mult = roleBuffResolver.contributionFor(attacker).hateThreatMultiplier();
            hateService.recordDamage(victim, attacker, event.getFinalDamage() * mult);
        }
    }

    /**
     * Resolves the player responsible for a damage source: the damager itself when it is a
     * player, or - for projectile damage - the entity that shot it. Non-player shooters
     * (dispensers, skeletons, other mobs) yield {@code null} so their damage never generates hate.
     */
    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }

    // ---- Eviction hooks (leak fix C5) ----

    /** Mob died: drop its threat bucket. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        hateService.table().removeMob(event.getEntity().getUniqueId());
    }

    /**
     * Entity left the world: covers despawn, chunk unload, and world-unload removal in one hook.
     * For a player this also clears their attacker contributions.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        hateService.table().removeMob(entity.getUniqueId());
        if (entity instanceof Player player) {
            hateService.table().removeAttacker(player.getUniqueId());
        }
    }

    /** Player died (respawn does not remove them from the world): clear their contributions. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        hateService.table().removeAttacker(event.getEntity().getUniqueId());
    }

    /** Player logged out: clear their contributions. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        hateService.table().removeAttacker(event.getPlayer().getUniqueId());
    }

    /** World unloaded: bulk-drop every mob that belonged to it. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        hateService.table().removeMobsInWorld(event.getWorld().getUID());
    }
}
