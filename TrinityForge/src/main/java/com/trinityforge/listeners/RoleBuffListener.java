package com.trinityforge.listeners;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;

import java.util.Objects;

/** Applies support-role exclusive potion buffs (ROLE_SYSTEM_SPEC §5). */
public final class RoleBuffListener implements Listener {

    private final RoleBuffsConfig roleBuffs;

    public RoleBuffListener(RoleBuffsConfig roleBuffs) {
        this.roleBuffs = Objects.requireNonNull(roleBuffs, "roleBuffs");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refreshSupportBuff(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        refreshSupportBuff(event.getPlayer());
    }

    /** Synchronizes the player's role-exclusive potion effects to their current support role. */
    public void refreshSupportBuff(Player player) {
        if (player == null) {
            return;
        }
        roleBuffs.supportRoles().values().stream()
                .map(SupportRoleSpec::potionBuff)
                .filter(Objects::nonNull)
                .map(spec -> spec.type())
                .distinct()
                .forEach(player::removePotionEffect);
        PlayerData data = PlayerData.of(player);
        SupportRoleSpec support = data.roleSupport()
                .map(roleBuffs::supportRole)
                .orElse(null);
        if (support == null || support.potionBuff() == null) {
            return;
        }
        var spec = support.potionBuff();
        player.addPotionEffect(new PotionEffect(
                spec.type(), spec.durationTicks(), spec.amplifier(), true, false, true));
    }
}
