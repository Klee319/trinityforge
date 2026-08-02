package com.trinityforge.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「腕振りはしたが、それが右クリックだったのか左クリックだったのかサーバから確実には分からない」
 * ケース向けの補助橋渡し(2026-08-02、実サーバ報告「ガチャ券/ダンジョンの鍵がブロックに向いてでないと
 * 使えない」対応)。
 *
 * <p><b>診断の確度について(重要)</b>: 当初「右クリック対象のアイテムに vanilla の『使用』挙動が
 * 無い場合、虚空(何にも当たらない方向)への右クリックは {@code ServerboundUseItemPacket} 自体を
 * 送らない」という診断でこのクラスを書いたが、Paper 本体の課題管理
 * (<a href="https://github.com/PaperMC/Paper/issues/5951">PaperMC/Paper#5951</a>、
 * メンテナ回答含む)を確認したところ、**この診断は手に何らかのアイテムを持っている場合には
 * 裏付けが取れなかった**(確認できたのは「完全な素手」で虚空右クリックした場合に限り
 * {@link PlayerInteractEvent} が発火しないという、より狭いケースのみ)。ガチャ券/ダンジョンの鍵は
 * 常に手にアイテムがある状態で使うため、当初の診断がこの機構の直接の裏付けにはならない。
 * 一方で {@link CombatListener} の javadoc（{@code onMissSwing}/{@code onArmSwing}）に既出の通り、
 * <b>左クリック(空振り攻撃)</b>で虚空を狙った場合に {@link PlayerInteractEvent} が発火しないことは
 * このリポジトリで実際に確認済みの事実であり、{@link PlayerAnimationEvent}(ARM_SWING) だけが
 * 確実に発火する。**このクラスは「元の右クリック診断が真であってもなくても安全に機能する」
 * ように設計されている**: 何らかの理由で {@link PlayerInteractEvent} が飛ばなかった腕振りだけを
 * 拾い、対応する {@link Handler} へ橋渡しする。診断が誤りで実際には右クリックが常に発火する
 * 環境だったとしても、このブリッジは単に「本物のイベントが来なかった場合の保険」として働くだけで
 * 害はない — ただし下記の通り、左クリック(空振り攻撃)との誤検出は現実にありうるので、
 * **Handler 側は取り返しのつかない処理(アイテム消費・抽選確定等)をこの経路で確定させてはいけない**。
 *
 * <p><b>サーバ側で左右クリックを区別できない</b>: {@link PlayerAnimationEvent} は ARM_SWING が
 * 左クリックでも右クリックでも同じように発火し、vanilla のプロトコル上サーバはどちらかを
 * 判別する情報を持たない。そこで「次 tick までに {@link PlayerInteractEvent} も
 * {@link EntityDamageByEntityEvent}(自分が攻撃者)も一切観測されなかった」場合に限り
 * フォールバック候補とみなす(1 tick 遅延判定で同一 tick 内のイベント到達順序の非決定性を吸収する)。
 * これにより通常のブロック操作・モブ攻撃(=対応するアイテムを持ったままの左クリックでヒットした場合)は
 * 誤発動しない。<b>しかし「登録アイテムを持ったまま、モブにも当たらず何も無い方向へ左クリック
 * (空振り攻撃)した」ケースは、サーバから見て虚空への右クリックと本質的に区別できない</b>
 * (vanilla 自体のプロトコル制約であり、このブリッジのバグではない)。
 *
 * <p><b>Handler 実装者への必須要件</b>: 上記の誤検出が実際に起こりうる前提で実装すること。
 * 具体的には、この経路(={@link Handler#tryHandle})から呼ばれたときは、アイテムの消費や
 * 取り返しのつかない確定処理を<b>即座に行ってはいけない</b>。確認GUI/確認メッセージを1枚挟み、
 * その後の明確な操作(インベントリクリック等、swing/interact 経路と別系統のイベント)を経てから
 * 確定させること(実例: {@link GachaListener#tryHandle} は抽選を即実行せず確認GUIを開く。
 * {@link DungeonKeyItemListener#tryHandle} はもともと確認GUIを挟む設計だったため対応済み)。
 * ※かつてこの javadoc は「誤発動しても実害が無い」と書いていたが誤りで、ガチャ券のような
 * 消費確定型の処理では実害(券の意図しない消費)があったため訂正した。
 */
public final class VoidRightClickBridge implements Listener {

    /**
     * 対応するアイテムなら処理して {@code true} を返す(消費させない場合は {@code false})。
     * <b>この経路は左クリック(空振り攻撃)との誤検出がありうるため、取り返しのつかない確定処理
     * (アイテム消費・抽選確定等)をここで直接行ってはいけない</b>(クラス javadoc 参照)。
     */
    @FunctionalInterface
    public interface Handler {
        boolean tryHandle(Player player, ItemStack mainhand);
    }

    private final Plugin plugin;
    private final List<Handler> handlers = new CopyOnWriteArrayList<>();
    /** この tick 中に「本物のイベント」(PlayerInteractEvent か自分が攻撃者のダメージ)を観測したプレイヤー。 */
    private final Set<UUID> sawRealEvent = ConcurrentHashMap.newKeySet();

    public VoidRightClickBridge(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void register(Handler handler) {
        handlers.add(Objects.requireNonNull(handler, "handler"));
    }

    /** 通常経路で発火した(=虚空フォールバックは不要)ことを記録するだけ。他の判断には一切関与しない。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        sawRealEvent.add(event.getPlayer().getUniqueId());
    }

    /** モブ/プレイヤーを実際に攻撃した(=左クリックの swing だった)ことを記録する。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            sawRealEvent.add(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (handlers.isEmpty() || event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack mainhand = player.getInventory().getItemInMainHand();
        if (mainhand.getType().isAir()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ItemStack swingItem = mainhand.clone();
        // 同tick内での PlayerInteractEvent / EntityDamageByEntityEvent の到達順は保証されないため、
        // 次tickまで待ってから「本当に他の本物イベントが無かったか」を確認する。
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean hadRealEvent = sawRealEvent.remove(uuid);
            if (hadRealEvent || !player.isOnline()) {
                return;
            }
            ItemStack current = player.getInventory().getItemInMainHand();
            if (!current.isSimilar(swingItem)) {
                return; // 判定までの間に持ち替えられた: この swing が何に対する操作だったか特定できない
            }
            for (Handler handler : handlers) {
                if (handler.tryHandle(player, current)) {
                    return;
                }
            }
        });
    }
}
