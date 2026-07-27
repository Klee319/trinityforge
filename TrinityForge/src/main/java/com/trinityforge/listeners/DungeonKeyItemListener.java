package com.trinityforge.listeners;

import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.mobs.DungeonEntryGui;
import com.trinityforge.mobs.DungeonGate;
import com.trinityforge.mobs.GateKeyMatcher;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ダンジョンゲートの鍵アイテムを右クリックしたら潜入確認GUIを開く(2026-07-27)。一致するゲートが
 * 0件なら何もしない(バニラの右クリック挙動をそのまま通す)。1件なら確認GUIへ、複数件なら選択GUIへ。
 * 一致した場合はイベントをキャンセルし、鍵アイテム本来の右クリック挙動(食べる/置く/投げる等)が
 * 同時に起きないようにする。
 */
public final class DungeonKeyItemListener implements Listener {

    private final DungeonGateConfig gateConfig;
    private final GateKeyMatcher keyMatcher;
    private final DungeonEntryGui gui;

    public DungeonKeyItemListener(DungeonGateConfig gateConfig, GateKeyMatcher keyMatcher, DungeonEntryGui gui) {
        this.gateConfig = Objects.requireNonNull(gateConfig, "gateConfig");
        this.keyMatcher = Objects.requireNonNull(keyMatcher, "keyMatcher");
        this.gui = Objects.requireNonNull(gui, "gui");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // 鍵付きゲートが1件も無い構成では、毎回の右クリックで全ゲートを舐めないよう即リターン
        // (ホットパス配慮)。
        if (!gateConfig.hasKeyGates()) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // オフハンドは対応するメインハンド側のイベントと同時に発火するため、二重発火防止で除外する。
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack held = event.getItem();
        if (held == null) {
            return;
        }
        List<DungeonGate> candidates = new ArrayList<>();
        for (DungeonGate gate : gateConfig.all().values()) {
            if (gate.keyRequired() && keyMatcher.matches(held, gate.keyItem())) {
                candidates.add(gate);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (candidates.size() == 1) {
            gui.openConfirm(player, candidates.get(0));
        } else {
            gui.openSelection(player, candidates);
        }
    }
}
