package com.trinityforge.listeners;

import com.trinityforge.config.domains.DungeonGateConfig;
import com.trinityforge.mobs.DungeonEntryGui;
import com.trinityforge.mobs.DungeonGate;
import com.trinityforge.mobs.GateKeyMatcher;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
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
 * <p><b>虚空(何もない方向)への右クリックについて(2026-08-03 訂正)</b>: 2026-08-02 に
 * 「vanillaの使用挙動が無いアイテムは虚空右クリックでパケット自体を送らない」という診断で
 * 腕振り経由のフォールバック({@code VoidRightClickBridge})を足したが、<b>その診断は誤りだった</b>。
 * 真因は {@link #onInteract} の {@code ignoreCancelled = true} で、詳細はそのjavadocに書いた。
 * フォールバックは真因の修正とともに撤去した。
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

    /**
     * <b>{@code ignoreCancelled} を付けてはいけない(2026-08-03 実サーバ報告
     * 「ダンジョンの鍵を虚空に向けて右クリックしても使えない」の真因)。</b>
     * {@link PlayerInteractEvent#isCancelled()} は {@code useInteractedBlock() == DENY} と等価で、
     * コンストラクタが「クリックしたブロックが {@code null} なら {@code useClickedBlock = DENY}」と
     * 初期化する(Paper 1.21.11 の {@code PlayerInteractEvent} バイトコードで確認済み)。
     * つまり <b>{@code RIGHT_CLICK_AIR} は生成された瞬間から常に「キャンセル済み」</b>であり、
     * {@code ignoreCancelled = true} を付けた購読者には Bukkit のイベントバスが一切配送しない。
     * ブロックに向けた右クリック({@code RIGHT_CLICK_BLOCK})だけ動いていたのはこのため。
     *
     * <p>キャンセル判定の代わりに {@link PlayerInteractEvent#useItemInHand()} を見る。こちらは
     * 「アイテムの使用が拒否されたか」だけを表す独立したフィールドで、空クリックでも
     * {@code DEFAULT} のまま。他プラグインが {@code setCancelled(true)} を呼んだ場合は Bukkit 側で
     * {@code useItemInHand} も {@code DENY} になるため、本当のキャンセルは従来どおり尊重される。
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // オフハンドは対応するメインハンド側のイベントと同時に発火するため、二重発火防止で除外する。
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
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
