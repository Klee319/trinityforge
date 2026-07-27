package com.trinityforge.listeners;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.trinityforge.progression.UseRequirementResolver;
import com.trinityforge.progression.UseRequirementService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 防具の装備ゲート (use-requirements): {@code enforce: true} のとき、use-skill / use-level 要件を
 * 満たさない防具の装備を取り消す。近接({@code CombatListener})・弓・ツール
 * ({@code UseRequirementListener})と同じ解決規則・同じ文言({@link UseRequirementService})。
 *
 * <p>{@link PlayerArmorChangeEvent} はキャンセル不可(Paper仕様)なので、MONITORで検知して
 * 1 tick 後に該当スロットから引き剥がし、インベントリへ戻す(満杯なら足元へドロップ)。
 * インベントリクリック/シフトクリック/右クリック装備/ディスペンサー装備の全経路がこの
 * イベントに集約されるため、入口ごとの個別ハンドリングは不要。
 */
public final class ArmorUseGateListener implements Listener {

    private final Plugin plugin;
    private final UseRequirementService gate;

    public ArmorUseGateListener(Plugin plugin, UseRequirementService gate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    // getSlotType()/SlotType はコンパイル対象の paper-api 1.21.1 で deprecated だが、代替の
    // getSlot() はこのAPIバージョンに存在しない。API更新時に getSlot() へ移行する。
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        ItemStack equipped = event.getNewItem();
        if (equipped == null || equipped.getType().isAir()) {
            return;
        }
        Player player = event.getPlayer();
        Optional<UseRequirementResolver.Resolved> denial = gate.denialFor(player, equipped);
        if (denial.isEmpty()) {
            return;
        }
        EquipmentSlot slot = equipmentSlotOf(event.getSlotType());
        ItemStack expected = equipped.clone();
        // イベント発火中のインベントリ書き換えを避けて1 tick後に引き剥がす。スロット内容が
        // その間に変わっていた場合(即持ち替え等)は何もしない(古い判定で現物を壊さない)。
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            ItemStack current = player.getInventory().getItem(slot);
            if (current == null || !current.isSimilar(expected)) {
                return;
            }
            player.getInventory().setItem(slot, null);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(current);
            leftover.values().forEach(rest ->
                    player.getWorld().dropItemNaturally(player.getLocation(), rest));
            player.sendActionBar(UseRequirementService.denialMessage(denial.get()));
        });
    }

    @SuppressWarnings("deprecation")
    private static EquipmentSlot equipmentSlotOf(PlayerArmorChangeEvent.SlotType slotType) {
        return switch (slotType) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
        };
    }
}
