package com.trinityforge.network;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.trinityforge.config.domains.NetworkConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理者用のサーバ間テレポート (2026-08-15)。
 *
 * <ul>
 *   <li>{@code /tpto <player>} … 自分が相手のところへ飛ぶ</li>
 *   <li>{@code /tphere <player>} … 相手を自分のところへ引っぱる</li>
 * </ul>
 *
 * <p>権限は {@code trinityforge.admin}。一般プレイヤー向けの申請制 TP は用意していない。
 *
 * <p><b>座標をネットワークに流さない設計にしてある。</b>「行き先の座標」は必ず
 * <b>行き先のサーバ自身</b>が {@link #pendingArrivals} に持ち、相手には
 * 「誰を寄こす／誰が行く」だけを伝える。ワールド名や座標を byte 列に詰めて渡すと、
 * ワールドが存在しない・チャンクが未読込などの失敗が全部「静かに座標 0,0 へ落ちる」形で出る。
 *
 * <p>流れ（{@code /tpto} の場合。A が S に居て、相手 B が T に居る）:
 * <ol>
 *   <li>S: {@code tp-locate{origin=S, requester=A, target=B}} を全サーバへ配る</li>
 *   <li>T: B が居るので {@code pendingArrivals[A] = B の座標} を控え、
 *       {@code tp-ack{origin=T, ...}} を返す</li>
 *   <li>S: ack を受けて A を {@code Connect} で T へ送る</li>
 *   <li>T: A の {@code PlayerJoinEvent} で控えた座標へテレポート</li>
 * </ol>
 * {@code /tphere} は向きが逆になるだけで同じ形。
 *
 * <p><b>プレイヤーが 1 人も居ないサーバには問い合わせが届かない。</b>
 * プラグインメッセージがプレイヤーの接続に相乗りするため。ただし無人なら相手も居ないので、
 * 「見つかりません」で正しい。
 */
public final class CrossServerTeleport implements Listener {

    public static final String SUB_LOCATE = "trinityforge:tp-locate";
    public static final String SUB_SUMMON = "trinityforge:tp-summon";
    public static final String SUB_ACK = "trinityforge:tp-ack";

    private static final String KIND_LOCATE = "locate";
    private static final String KIND_SUMMON = "summon";

    /** 控えた行き先の寿命。サーバ切替は数秒で終わるので 60 秒あれば十分に余裕がある。 */
    private static final long ARRIVAL_TTL_MILLIS = 60_000L;

    private final Plugin plugin;
    private final ProxyChannel channel;
    private final NetworkConfig config;

    /** このサーバに来る予定の人 → 着いたら飛ばす座標。キーは小文字の MCID。 */
    private final Map<String, PendingArrival> pendingArrivals = new ConcurrentHashMap<>();

    /** 返事待ちの問い合わせ。キーは小文字の依頼者名。 */
    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    public CrossServerTeleport(Plugin plugin, ProxyChannel channel, NetworkConfig config) {
        this.plugin = plugin;
        this.channel = channel;
        this.config = config;
        channel.addHandler(SUB_LOCATE, this::onLocate);
        channel.addHandler(SUB_SUMMON, this::onSummon);
        channel.addHandler(SUB_ACK, this::onAck);
    }

    // ---------------------------------------------------------------------------------------------
    //  コマンドの入口
    // ---------------------------------------------------------------------------------------------

    /** {@code /tpto <player>}: 実行者が相手のところへ飛ぶ。 */
    public void teleportTo(Player requester, String targetName) {
        if (!ready(requester)) {
            return;
        }
        Player local = Bukkit.getPlayerExact(targetName);
        if (local != null && local != requester) {
            requester.teleport(local.getLocation());
            requester.sendMessage(Component.text(local.getName() + " のところへ移動しました。", NamedTextColor.GREEN));
            return;
        }
        if (local == requester) {
            requester.sendMessage(Component.text("自分自身へは移動できません。", NamedTextColor.RED));
            return;
        }
        dispatch(requester, targetName, SUB_LOCATE, KIND_LOCATE);
    }

    /** {@code /tphere <player>}: 相手を実行者のところへ引っぱる。 */
    public void teleportHere(Player requester, String targetName) {
        if (!ready(requester)) {
            return;
        }
        Player local = Bukkit.getPlayerExact(targetName);
        if (local == requester) {
            requester.sendMessage(Component.text("自分自身は呼び寄せられません。", NamedTextColor.RED));
            return;
        }
        if (local != null) {
            local.teleport(requester.getLocation());
            local.sendMessage(Component.text(requester.getName() + " に呼び寄せられました。", NamedTextColor.YELLOW));
            requester.sendMessage(Component.text(local.getName() + " を呼び寄せました。", NamedTextColor.GREEN));
            return;
        }
        // 相手が別サーバに居る場合、行き先はこのサーバ。先に座標を控えてから呼びに行く。
        pendingArrivals.put(targetName.toLowerCase(Locale.ROOT),
                new PendingArrival(requester.getLocation().clone(), System.currentTimeMillis() + ARRIVAL_TTL_MILLIS));
        dispatch(requester, targetName, SUB_SUMMON, KIND_SUMMON);
    }

    private boolean ready(Player requester) {
        if (!config.teleportEnabled()) {
            requester.sendMessage(Component.text("サーバ間テレポートは network.yml で無効になっています。",
                    NamedTextColor.RED));
            return false;
        }
        if (channel.serverName() == null) {
            channel.requestServerName();
            requester.sendMessage(Component.text(
                    "プロキシからサーバ名をまだ受け取れていません。少し待ってからやり直してください。",
                    NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private void dispatch(Player requester, String targetName, String subChannel, String kind) {
        String origin = channel.serverName();
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(origin);
        out.writeUTF(requester.getName());
        out.writeUTF(targetName);
        if (!channel.forward(subChannel, out.toByteArray())) {
            requester.sendMessage(Component.text(
                    "他サーバへ問い合わせられませんでした（プロキシに繋がっていない可能性があります）。",
                    NamedTextColor.RED));
            pendingArrivals.remove(targetName.toLowerCase(Locale.ROOT));
            return;
        }

        String key = requester.getName().toLowerCase(Locale.ROOT);
        pendingRequests.put(key, new PendingRequest(requester.getUniqueId(), targetName, kind));
        requester.sendMessage(Component.text(targetName + " を探しています…", NamedTextColor.GRAY));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PendingRequest expired = pendingRequests.remove(key);
            if (expired == null) {
                return; // 返事が来て解決済み。
            }
            pendingArrivals.remove(targetName.toLowerCase(Locale.ROOT));
            Player stillHere = Bukkit.getPlayer(expired.requesterId());
            if (stillHere != null) {
                stillHere.sendMessage(Component.text(
                        targetName + " はどのサーバにも見つかりませんでした。", NamedTextColor.RED));
            }
        }, Math.max(1L, config.teleportTimeoutSeconds() * 20L));
    }

    // ---------------------------------------------------------------------------------------------
    //  受信
    // ---------------------------------------------------------------------------------------------

    /** 「そちらに B は居ますか。居るなら A を寄こします」への応答側。 */
    private void onLocate(ByteArrayDataInput in) {
        String origin = in.readUTF();
        String requesterName = in.readUTF();
        String targetName = in.readUTF();
        if (channel.isSelf(origin) || !config.teleportEnabled()) {
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            return;
        }
        pendingArrivals.put(requesterName.toLowerCase(Locale.ROOT),
                new PendingArrival(target.getLocation().clone(), System.currentTimeMillis() + ARRIVAL_TTL_MILLIS));
        sendAck(requesterName, targetName, KIND_LOCATE);
    }

    /** 「そちらの B をこちらへ寄こしてください」への応答側。 */
    private void onSummon(ByteArrayDataInput in) {
        String origin = in.readUTF();
        String requesterName = in.readUTF();
        String targetName = in.readUTF();
        if (channel.isSelf(origin) || !config.teleportEnabled()) {
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            return;
        }
        sendAck(requesterName, targetName, KIND_SUMMON);
        target.sendMessage(Component.text(requesterName + " に呼び寄せられました。", NamedTextColor.YELLOW));
        channel.connect(target, origin);
    }

    /** 問い合わせ元へ「居ました」を返す。 */
    private void sendAck(String requesterName, String targetName, String kind) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(channel.serverName() == null ? "" : channel.serverName());
        out.writeUTF(requesterName);
        out.writeUTF(targetName);
        out.writeUTF(kind);
        channel.forward(SUB_ACK, out.toByteArray());
    }

    private void onAck(ByteArrayDataInput in) {
        String origin = in.readUTF();
        String requesterName = in.readUTF();
        String targetName = in.readUTF();
        String kind = in.readUTF();
        if (channel.isSelf(origin)) {
            return;
        }
        PendingRequest request = pendingRequests.remove(requesterName.toLowerCase(Locale.ROOT));
        if (request == null) {
            return; // 自分が出した問い合わせではない（他サーバ宛の ack が回ってきただけ）。
        }
        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null) {
            return;
        }
        if (KIND_LOCATE.equals(kind)) {
            requester.sendMessage(Component.text(
                    targetName + " は " + config.displayName(origin) + " に居ます。移動します…", NamedTextColor.GREEN));
            channel.connect(requester, origin);
        } else {
            requester.sendMessage(Component.text(
                    targetName + " を " + config.displayName(origin) + " から呼び寄せています…", NamedTextColor.GREEN));
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  到着
    // ---------------------------------------------------------------------------------------------

    /**
     * 控えておいた行き先へ飛ばす。
     *
     * <p><b>参加した瞬間には飛ばさない。</b>HuskSync がインベントリを復元し終える前に動かすと
     * 復元処理と噛み合って座標が巻き戻ることがあるので、{@code arrival-delay-ticks} だけ待つ。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        String key = event.getPlayer().getName().toLowerCase(Locale.ROOT);
        PendingArrival arrival = pendingArrivals.remove(key);
        if (arrival == null) {
            return;
        }
        if (arrival.isExpired()) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                return;
            }
            player.teleport(arrival.location());
            player.sendMessage(Component.text("到着しました。", NamedTextColor.GREEN));
        }, config.arrivalDelayTicks());
    }

    /** 期限切れの控えを捨てる。定期タスクから呼ぶ。 */
    public void purgeExpired() {
        pendingArrivals.values().removeIf(PendingArrival::isExpired);
    }

    private record PendingArrival(Location location, long expiresAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAtMillis;
        }
    }

    private record PendingRequest(UUID requesterId, String targetName, String kind) {
    }
}
