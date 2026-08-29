package com.trinityforge.items;

import com.trinityforge.progression.DailyExpDiminishing;
import com.trinityforge.progression.DailyExpWindowPersistence;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 「EXP解呪の良薬」（{@link ExpCleanseTonic}）を右クリックしたら、日次逓減を全スキル一括で
 * その良薬の水準まで引き戻す（2026-08-24）。
 *
 * <p><b>効かなかったときは消費しない。</b> 逓減が一切掛かっていない人が誤って飲んだ場合に
 * 黙って1個消えるのが一番きつい壊れ方なので、切り下げが1件も起きなければ券を返して知らせる。
 *
 * <p><b>メモリを削ってから DB を削る。</b> {@code DailyExpWindowStore#save} は
 * 「メモリと保存済みの大きい方」を残すので、DB を先に削ると間に挟まった定期保存が
 * 削る前のメモリ値を書き戻してしまう。DB 側は SQLite を触るので非同期タスクへ逃がす。
 *
 * <p>{@code ignoreCancelled} を付けない理由は {@link RoleTicketItemListener} と同じ
 *（空クリックが {@code isCancelled()==true} で生成される Bukkit の仕様、
 * {@code docs/agent-context/common-traps.md} 参照）。
 */
public final class ExpCleanseTonicListener implements Listener {

    private final Plugin plugin;
    private final DailyExpDiminishing diminishing;
    private final Supplier<DailyExpDiminishing.Settings> settings;
    /** 未設定（DBを開けなかった環境）なら {@code null}。メモリだけ削って続ける。 */
    private final DailyExpWindowPersistence persistence;

    public ExpCleanseTonicListener(Plugin plugin,
                                   DailyExpDiminishing diminishing,
                                   Supplier<DailyExpDiminishing.Settings> settings,
                                   DailyExpWindowPersistence persistence) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.diminishing = Objects.requireNonNull(diminishing, "diminishing");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.persistence = persistence;
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
        ExpCleanseTonic tonic = ExpCleanseTonic.of(event.getItem());
        if (tonic == null) {
            return;
        }
        // 素材が POTION なので、止めないとバニラの「飲む」が走る。
        event.setCancelled(true);
        drink(event.getPlayer(), tonic);
    }

    /** 効果本体。テストから直接呼べるように分けてある。 */
    public void drink(Player player, ExpCleanseTonic tonic) {
        if (player == null || tonic == null) {
            return;
        }
        DailyExpDiminishing.Settings current = settings.get();
        int changed = diminishing.relieve(current, player.getUniqueId(), tonic.targetMultiplier());
        if (changed <= 0) {
            player.sendMessage("§eいま解呪できるEXPの目減りはありません。§7(良薬は消費していません)");
            return;
        }
        consumeOne(player);
        player.sendMessage("§a" + changed + " 個のスキルのEXP取得量を §f"
                + tonic.targetPercent() + "%§a 以上まで引き戻しました。");
        if (persistence != null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> persistence.capStored(player.getUniqueId(), tonic.targetMultiplier()));
        }
    }

    private static void consumeOne(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            return;
        }
        held.setAmount(held.getAmount() - 1);
    }
}
