package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.UseRequirementsConfig;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.progression.UseRequirementPolicy;
import com.trinityforge.progression.UseRequirementResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * Gates tool / held-item actions on use-level / use-skill.
 * Requirement level {@code 0} (or blank skill) is unrestricted ({@link UseRequirementPolicy}).
 *
 * <p>Bows/crossbows/tridents are excluded from interact gating — blocking draw/right-click breaks
 * projectile velocity; those weapons are gated at shoot/throw time in {@link CombatListener}.
 */
public final class UseRequirementListener implements Listener {

    private final SkillLevelSource skillLevelSource;
    private final UseRequirementsConfig useRequirements;
    private final ItemStatsConfig itemStats;

    public UseRequirementListener(SkillLevelSource skillLevelSource,
                                  UseRequirementsConfig useRequirements,
                                  ItemStatsConfig itemStats) {
        this.skillLevelSource = Objects.requireNonNull(skillLevelSource, "skillLevelSource");
        this.useRequirements = Objects.requireNonNull(useRequirements, "useRequirements");
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer(), event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        ItemStack stack = event.getItem();
        if (stack != null && UseRequirementResolver.skipInteractGate(stack.getType())) {
            return;
        }
        if (blocked(event.getPlayer(), stack)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        ItemStack rod = fishingRod(main);
        if (rod == null) {
            rod = fishingRod(off);
        }
        if (rod != null && blocked(player, rod)) {
            event.setCancelled(true);
        }
    }

    private static ItemStack fishingRod(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return stack.getType() == Material.FISHING_ROD ? stack : null;
    }

    private boolean blocked(Player player, ItemStack tool) {
        if (!useRequirements.enforce()) {
            return false;
        }
        return UseRequirementResolver.resolve(tool, itemStats)
                .filter(req -> !UseRequirementPolicy.meets(
                        req.skill(), req.level(), skillLevelSource.levelsOf(player.getUniqueId())))
                .map(req -> {
                    player.sendActionBar(Component.text(
                            "この装備を使うには " + req.skill() + " Lv" + req.level() + " が必要です",
                            NamedTextColor.RED));
                    return true;
                })
                .orElse(false);
    }
}
