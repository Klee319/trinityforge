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
 *
 * <p>{@link VoidRightClickBridge} も実装する(2026-08-02、実サーバ報告「虚空(何もない方向)へ右クリック
 * すると鍵が使えない」対応)。真因の確度は {@link VoidRightClickBridge} javadoc 参照(当初の
 * 「vanillaの使用挙動が無いアイテムは虚空右クリックでパケット自体を送らない」という診断は
 * 手にアイテムがある場合には裏付けが取れていない)。診断の真偽に関わらずこのブリッジは
 * 安全側に倒れる設計で、鍵の場合は特に{@link #open}が必ず確認GUI(潜入確定は
 * {@code InventoryClickEvent}経由)を挟むため、フォールバック誤検出(空振り攻撃との混同)が
 * 起きても「確認GUIが開くだけ」で実害が無い。
 */
public final class DungeonKeyItemListener implements Listener, VoidRightClickBridge.Handler {

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
        List<DungeonGate> candidates = matchingGates(held);
        if (candidates.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        open(event.getPlayer(), candidates);
    }

    /**
     * {@link VoidRightClickBridge} 経由の虚空右クリックフォールバック。判定・開くGUIは
     * {@link #onInteract} と全く同じ経路({@link #matchingGates}/{@link #open})を再利用する。
     * 二重発火防止は {@link VoidRightClickBridge} 側の「本物イベントを観測していたら発火しない」
     * ガードに一任する。
     */
    @Override
    public boolean tryHandle(Player player, ItemStack mainhand) {
        List<DungeonGate> candidates = matchingGates(mainhand);
        if (candidates.isEmpty()) {
            return false;
        }
        open(player, candidates);
        return true;
    }

    private List<DungeonGate> matchingGates(ItemStack held) {
        // 鍵付きゲートが1件も無い構成では、毎回の右クリックで全ゲートを舐めないよう即リターン
        // (ホットパス配慮)。
        if (!gateConfig.hasKeyGates() || held == null) {
            return List.of();
        }
        List<DungeonGate> candidates = new ArrayList<>();
        for (DungeonGate gate : gateConfig.all().values()) {
            if (gate.keyRequired() && keyMatcher.matches(held, gate.keyItem())) {
                candidates.add(gate);
            }
        }
        return candidates;
    }

    private void open(Player player, List<DungeonGate> candidates) {
        if (candidates.size() == 1) {
            gui.openConfirm(player, candidates.get(0));
        } else {
            gui.openSelection(player, candidates);
        }
    }
}
