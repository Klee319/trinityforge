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
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 「EXP解呪の良薬」（{@link ExpCleanseTonic}）を右クリックしたら、日次逓減を全スキル一括で動かす。
 *
 * <p><b>効かなかったときは消費しない。</b> 逓減が掛かっていない／並の 70% 上限に既に届いている／
 * 極の無効化が既に 2 時間以上残っている、のどれでも券を返して知らせる。
 *
 * <p><b>メモリを削ってから DB を削る。</b> {@code DailyExpWindowStore#save} は
 * 「メモリと保存済みの大きい方」を残すので、DB を先に削ると間に挟まった定期保存が
 * 削る前のメモリ値を書き戻してしまう。
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
        event.setCancelled(true);
        drink(event.getPlayer(), tonic);
    }

    /** 効果本体。テストから直接呼べるように分けてある。 */
    public void drink(Player player, ExpCleanseTonic tonic) {
        if (player == null || tonic == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (tonic.grantsImmunity()) {
            if (diminishing.immuneRemainingMillis(playerId) >= tonic.immunityMillis()) {
                player.sendMessage("§eすでに減衰が無効化されています。§7(良薬は消費していません)");
                return;
            }
            diminishing.grantImmunity(playerId, tonic.immunityMillis());
            consumeOne(player);
            player.sendMessage("§aEXP取得量の減衰を §f2時間§a 無効化しました。");
            if (persistence != null) {
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                        () -> persistence.persistImmunity(playerId));
            }
            return;
        }
        DailyExpDiminishing.Settings current = settings.get();
        int changed = diminishing.relieveDecay(current, playerId,
                tonic.decayReduceFraction(), tonic.multiplierCeiling());
        if (changed <= 0) {
            player.sendMessage("§eいま解呪できるEXPの目減りはありません。§7(良薬は消費していません)");
            return;
        }
        consumeOne(player);
        if (tonic.multiplierCeiling() < 1.0) {
            player.sendMessage("§a" + changed + " 個のスキルの減衰量を §f"
                    + tonic.decayReducePercent() + "%§a 減らしました。"
                    + " §7(取得量は " + tonic.ceilingPercent() + "% まで)");
        } else {
            player.sendMessage("§a" + changed + " 個のスキルの減衰量を §f"
                    + tonic.decayReducePercent() + "%§a 減らしました。");
        }
        if (persistence != null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin,
                    () -> persistence.persistRelievedAmounts(playerId));
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
