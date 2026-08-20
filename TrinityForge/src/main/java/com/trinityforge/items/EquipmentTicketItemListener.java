package com.trinityforge.items;

import com.trinityforge.pdc.ItemData;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ランダムステータス再抽選券/品質レベルアップ券を右クリックしたら対象選択GUI({@link EquipmentTicketGui})
 * を開く。カタログIDが一致する券が1件も登録されていなければ何もしない(バニラの右クリック挙動を素通し)。
 *
 * <p><b>{@code ignoreCancelled} を付けてはいけない。</b>{@link PlayerInteractEvent} は
 * {@code RIGHT_CLICK_AIR}(クリックしたブロックが {@code null})のとき生成された瞬間から
 * {@code isCancelled() == true} になる({@code useClickedBlock} が {@code DENY} 初期化されるため、
 * Paper 1.21.11 のバイトコードで確認済み)。{@code ignoreCancelled = true} を付けると Bukkit の
 * イベントバスが空クリックを一切配送しなくなる({@code DungeonKeyItemListener}/{@code GachaListener}
 * と同じ罠、{@code docs/agent-context/common-traps.md} 参照)。代わりに
 * {@link PlayerInteractEvent#useItemInHand()} が {@code DENY} かどうかで判定する。
 */
public final class EquipmentTicketItemListener implements Listener {

    private final EquipmentTicketGui gui;
    private final Map<String, EquipmentTicketEffect> effectsById;

    public EquipmentTicketItemListener(EquipmentTicketGui gui, List<EquipmentTicketEffect> effects) {
        this.gui = Objects.requireNonNull(gui, "gui");
        Map<String, EquipmentTicketEffect> map = new HashMap<>();
        for (EquipmentTicketEffect effect : Objects.requireNonNull(effects, "effects")) {
            map.put(effect.catalogId(), effect);
        }
        this.effectsById = Map.copyOf(map);
    }

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
        if (held == null || !held.hasItemMeta()) {
            return;
        }
        String catalogId = ItemData.of(held.getItemMeta()).catalogId().orElse(null);
        if (catalogId == null) {
            // 【必須】不変Map({@link Map#copyOf})は HashMap と違い get(null) で NPE を投げる
            // (ImmutableCollections.MapN#probe が pk.hashCode() を呼ぶ)。カタログIDを持たない
            // アイテムは「lore付きバニラ品・リネーム品・大半のTF品」を含み、それらでの右クリックが
            // すべてここを通るため、null を渡すとコンソールが NPE で埋まる(実サーバ報告の真因)。
            return;
        }
        EquipmentTicketEffect effect = effectsById.get(catalogId);
        if (effect == null) {
            return;
        }
        event.setCancelled(true);
        gui.open(event.getPlayer(), effect);
    }
}
