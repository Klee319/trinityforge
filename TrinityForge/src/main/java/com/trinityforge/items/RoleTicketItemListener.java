package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.RoleSelectGui;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * ロール付け直し券({@code role_reselect_ticket})を右クリックしたら {@link RoleSelectGui} を
 * 「券モード」({@link RoleSelectGui#open(org.bukkit.entity.Player, boolean)} の {@code ticketMode=true})
 * で開く。通常のクールダウン・交戦中ガードを無視して選び直せるが、確定は
 * {@link RoleSelectGui} 側のゲート・消費ロジックに委ねる(ここでは「開く」ことしかしない)。
 *
 * <p>{@code ignoreCancelled} を付けない理由は {@link EquipmentTicketItemListener} と同じ
 * (空クリックが {@code isCancelled()==true} で生成される Bukkit の仕様、
 * {@code docs/agent-context/common-traps.md} 参照)。
 */
public final class RoleTicketItemListener implements Listener {

    private final RoleSelectGui roleSelectGui;

    public RoleTicketItemListener(RoleSelectGui roleSelectGui) {
        this.roleSelectGui = Objects.requireNonNull(roleSelectGui, "roleSelectGui");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        ItemStack held = event.getItem();
        if (held == null || !held.hasItemMeta()) {
            return;
        }
        String catalogId = ItemData.of(held.getItemMeta()).catalogId().orElse(null);
        if (!RoleSelectGui.TICKET_CATALOG_ID.equals(catalogId)) {
            return;
        }
        event.setCancelled(true);
        roleSelectGui.open(event.getPlayer(), true);
    }
}
