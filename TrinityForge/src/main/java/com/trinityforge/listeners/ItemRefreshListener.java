package com.trinityforge.listeners;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemRefreshPolicy;
import com.trinityforge.stats.TableGeneration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;
import java.util.Optional;

/**
 * Re-applies the live stat/lore/attribute tables onto items already in play (SELECTION_SPEC 5: a
 * table edit + {@code /trinityforge reload} must reach existing items, not just newly-created ones).
 * {@code ItemFactory.create} is the only write path today ({@code ItemAssembler.assemble} is never
 * invoked again after an item leaves that factory), so an item rolled before a reload kept its stale
 * lore/attributes forever; this listener closes that gap at the three points an item re-enters
 * "visible/equipped" state without going back through the factory:
 *
 * <ul>
 *   <li>{@link PlayerItemHeldEvent} — the item in the newly-selected hotbar slot</li>
 *   <li>{@link PlayerArmorChangeEvent} — the newly-equipped armor piece (Paper-specific event)</li>
 *   <li>{@link PlayerJoinEvent} — mainhand + full armor set, for gear already held/worn at login</li>
 * </ul>
 *
 * <p>{@link ItemAssembler#assemble} is idempotent and replace-based, so re-running it on an
 * already-current item is safe; {@link ItemRefreshPolicy#needsRefresh} (a pure decision, see its own
 * tests) short-circuits that redundant work using the {@link TableGeneration} stamp
 * ({@code ItemData#tableGeneration}) so a hotbar switch does not re-derive stats and re-compose lore
 * on every tick for items that have not gone stale.
 */
public final class ItemRefreshListener implements Listener {

    private final ItemAssembler assembler;
    private final TableGeneration tableGeneration;

    public ItemRefreshListener(ItemAssembler assembler, TableGeneration tableGeneration) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.tableGeneration = Objects.requireNonNull(tableGeneration, "tableGeneration");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        refresh(player.getInventory().getItem(event.getNewSlot()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        refresh(event.getNewItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PlayerInventory inventory = event.getPlayer().getInventory();
        refresh(inventory.getItemInMainHand());
        for (ItemStack armorPiece : inventory.getArmorContents()) {
            refresh(armorPiece);
        }
    }

    /** Eagerly re-assembles every TF-stamped item in online players' inventories after a reload. */
    public void refreshAllOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerInventory(player);
        }
    }

    private void refreshPlayerInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            refresh(inventory.getItem(slot));
        }
        for (ItemStack armor : inventory.getArmorContents()) {
            refresh(armor);
        }
        refresh(player.getItemOnCursor());
    }

    private void refresh(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        boolean hasRollSeed = data.hasRollSeed();
        boolean stale = ItemRefreshPolicy.needsRefresh(
                hasRollSeed, data.tableGeneration(), tableGeneration.current());
        if (!stale) {
            return;
        }
        Optional<Long> rollSeed = data.rollSeed();
        if (rollSeed.isEmpty()) {
            return;
        }
        assembler.assemble(meta, stack.getType(), rollSeed.get(), data.quality());
        stack.setItemMeta(meta);
    }
}
