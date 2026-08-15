package com.trinityforge.network;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * プロキシ(Velocity)経由でバックエンド同士がやりとりするための土台 (2026-08-15)。
 *
 * <p>使うのは <b>旧 BungeeCord プラグインメッセージチャンネル</b>。Velocity 4.1.0-SNAPSHOT でも
 * {@code BungeeCordMessageResponder} が {@code Connect} / {@code Forward} / {@code GetServer} を
 * 実装しており（jar を逆アセンブルして確認）、{@code velocity.toml} の
 * {@code bungee-plugin-message-channel = true} で有効になっている。
 * このおかげで<b>プロキシ用プラグインも Redis 依存も足さずに</b>サーバ間通信ができる。
 *
 * <p><b>踏みやすい前提が 3 つある。</b>
 * <ol>
 *   <li><b>プラグインメッセージはプレイヤーの接続に相乗りする。</b>オンラインが 0 人のサーバは
 *       送ることも受け取ることもできない。チャットや TP は「誰かが居る」ことが前提なので実害は無いが、
 *       起動直後に送ろうとしても届かない。</li>
 *   <li><b>{@code sendPluginMessage} はメインスレッドから呼ぶ。</b>チャットイベントは非同期なので、
 *       送信は必ずここでメインスレッドへ載せ替える。</li>
 *   <li><b>{@code Forward} の宛先 {@code ALL} が送信元サーバを含むかは実装依存。</b>
 *       二重表示という分かりにくい壊れ方をするので、payload の先頭に必ず送信元サーバ名を入れ、
 *       受信側で自分発を捨てる（{@link #isSelf(String)}）。</li>
 * </ol>
 *
 * <p>自分のサーバ名は {@code velocity.toml} の {@code [servers]} に書いた名前
 * （{@code main} / {@code resource} / {@code dev}）で、プロキシに {@code GetServer} で聞く。
 * <b>config に書かせない</b>のは {@code plugins/TrinityForge} が 3 台でジャンクション共有されていて
 * サーバごとに違う値を置けないため。
 */
public final class ProxyChannel implements PluginMessageListener, Listener {

    /** Bukkit 側のチャンネル名。Paper が {@code bungeecord:main} へ読み替える。 */
    public static final String CHANNEL = "BungeeCord";

    private static final String SUB_GET_SERVER = "GetServer";
    private static final String SUB_CONNECT = "Connect";
    private static final String SUB_FORWARD = "Forward";
    private static final String TARGET_ALL = "ALL";

    /** {@code Forward} の payload 長は short で書くので、超える手前で捨てる。 */
    private static final int MAX_PAYLOAD_BYTES = 30000;

    private final Plugin plugin;
    private final Map<String, Consumer<ByteArrayDataInput>> handlers = new ConcurrentHashMap<>();

    /** プロキシが名乗った自分のサーバ名。{@code GetServer} の返事が来るまでは null。 */
    private volatile String serverName;

    public ProxyChannel(Plugin plugin) {
        this.plugin = plugin;
    }

    /** 送受信チャンネルを登録する。onEnable から 1 回だけ呼ぶ。 */
    public void register() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
    }

    public void unregister() {
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
    }

    /**
     * サブチャンネルの受信ハンドラを登録する。
     * ハンドラは<b>メインスレッドで</b>呼ばれる（プラグインメッセージの受信自体がメインスレッド）。
     */
    public void addHandler(String subChannel, Consumer<ByteArrayDataInput> handler) {
        handlers.put(subChannel, handler);
    }

    /** プロキシが名乗った自分のサーバ名。まだ聞けていなければ null。 */
    public String serverName() {
        return serverName;
    }

    /** {@code origin} が自分自身なら true。自分が投げた Forward を拾い直さないための判定。 */
    public boolean isSelf(String origin) {
        String self = this.serverName;
        return self != null && self.equalsIgnoreCase(origin);
    }

    /**
     * プロキシに自分のサーバ名を聞く。オンラインが 0 人だと送れないので、
     * 参加イベントからも呼んで取り直せるようにしてある。
     */
    public void requestServerName() {
        if (serverName != null) {
            return;
        }
        Player carrier = carrier();
        if (carrier == null) {
            return;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(SUB_GET_SERVER);
        carrier.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    /**
     * 全バックエンドへ payload を配る。
     *
     * <p><b>メインスレッドから呼ぶこと。</b>非同期から呼びたい場合は {@link #forwardAsync} を使う。
     *
     * @return 実際に送れたら true（オンラインが 0 人・サーバ名未取得・payload 過大なら false）
     */
    public boolean forward(String subChannel, byte[] payload) {
        if (payload.length > MAX_PAYLOAD_BYTES) {
            plugin.getLogger().warning("[network] " + subChannel + " の payload が大きすぎるので送りません: "
                    + payload.length + " bytes");
            return false;
        }
        Player carrier = carrier();
        if (carrier == null) {
            return false;
        }
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(SUB_FORWARD);
        out.writeUTF(TARGET_ALL);
        out.writeUTF(subChannel);
        out.writeShort(payload.length);
        out.write(payload);
        carrier.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
        return true;
    }

    /** 非同期スレッドから安全に {@link #forward} する。 */
    public void forwardAsync(String subChannel, byte[] payload) {
        if (Bukkit.isPrimaryThread()) {
            forward(subChannel, payload);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> forward(subChannel, payload));
    }

    /**
     * プレイヤーを別サーバへ移す。
     * <b>移すプレイヤー自身の接続から送る必要がある</b>ので、対象が居るサーバで呼ぶこと。
     */
    public void connect(Player player, String targetServer) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(SUB_CONNECT);
        out.writeUTF(targetServer);
        player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String subChannel;
        try {
            subChannel = in.readUTF();
        } catch (IllegalStateException ex) {
            return;
        }

        if (SUB_GET_SERVER.equals(subChannel)) {
            String name = in.readUTF();
            if (serverName == null) {
                plugin.getLogger().info("[network] プロキシが名乗ったこのサーバの名前: " + name);
            }
            serverName = name;
            return;
        }

        Consumer<ByteArrayDataInput> handler = handlers.get(subChannel);
        if (handler == null) {
            return;
        }
        // Forward で来たものは [short 長さ][本体] が続く。ここを読み飛ばすと以降が全部ずれる。
        short length = in.readShort();
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            return;
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        try {
            handler.accept(ByteStreams.newDataInput(payload));
        } catch (RuntimeException ex) {
            // 1 通の壊れたメッセージでチャンネル全体を落とさない。
            plugin.getLogger().warning("[network] " + subChannel + " の処理に失敗しました: " + ex);
        }
    }

    /**
     * 参加のたびに自分のサーバ名を聞き直す。
     *
     * <p>無人だと {@code GetServer} を送る相手が居ないので、起動時の 1 回だけでは
     * 名前を取れないまま最初の発言を迎える。参加を足がかりにするのが一番確実。
     * すでに分かっていれば {@link #requestServerName()} 側で何もしない。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        requestServerName();
    }

    /** プラグインメッセージを載せる「運び役」。誰でもよいので最初の 1 人を使う。 */
    private Player carrier() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            return online;
        }
        return null;
    }
}
