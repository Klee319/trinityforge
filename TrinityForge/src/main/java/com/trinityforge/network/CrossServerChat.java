package com.trinityforge.network;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.trinityforge.config.domains.NetworkConfig;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * サーバをまたぐチャット (2026-08-15)。
 *
 * <p>main / resource / dev のどこで喋っても、3 台すべてに
 * {@code 【資源】mcid: hello} の形で流れる。
 *
 * <p><b>自分のサーバの発言も同じ書式に書き換える。</b>ローカルだけバニラの
 * {@code <mcid> hello} のままにすると、同じ画面に 2 種類の書式が並んで
 * 「どれが他サーバの発言か」が読めなくなる。書き換えは
 * {@link AsyncChatEvent#renderer} で行うので、{@code event.message()} は素のまま残り、
 * DiscordSRV など他プラグインの取り込みには影響しない。
 *
 * <p><b>Discord へは各サーバの DiscordSRV が自分の発言だけを投げる。</b>
 * 他サーバから受け取った分は {@code Bukkit.broadcast} で出しており
 * チャットイベントではないので、DiscordSRV には拾われない。
 * つまり中継しても Discord 側で二重投稿にはならない。
 */
public final class CrossServerChat implements Listener {

    public static final String SUB_CHANNEL = "trinityforge:chat";

    private final Plugin plugin;
    private final ProxyChannel channel;
    private final NetworkConfig config;

    public CrossServerChat(Plugin plugin, ProxyChannel channel, NetworkConfig config) {
        this.plugin = plugin;
        this.channel = channel;
        this.config = config;
        channel.addHandler(SUB_CHANNEL, this::onRemoteMessage);
    }

    /**
     * 送信と表示の書き換えを 1 か所でやる。
     *
     * <p>{@code MONITOR} + {@code ignoreCancelled} なのは、他プラグイン（ミュートや NG ワード）が
     * キャンセルした発言を他サーバへ配ってしまわないため。renderer は全ハンドラの後に適用されるので、
     * MONITOR で差し替えても表示にはちゃんと効く。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!config.chatEnabled()) {
            return;
        }
        String origin = channel.serverName();
        if (origin == null) {
            // まだプロキシに名前を聞けていない。書式だけ変えて中身が変わったように見せるより、
            // バニラのまま出して「共有がまだ始まっていない」と分かるほうがよい。
            channel.requestServerName();
            return;
        }

        String display = config.displayName(origin);
        event.renderer((source, sourceDisplayName, message, viewer) ->
                render(display, PlainTextComponentSerializer.plainText().serialize(sourceDisplayName), message));

        String playerName = PlainTextComponentSerializer.plainText()
                .serialize(event.getPlayer().displayName());
        String body = PlainTextComponentSerializer.plainText().serialize(event.message());

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(origin);
        out.writeUTF(playerName);
        out.writeUTF(body);
        // AsyncChatEvent は非同期。sendPluginMessage はメインスレッド専用なので載せ替える。
        channel.forwardAsync(SUB_CHANNEL, out.toByteArray());
    }

    /** 他サーバから届いた発言をこのサーバへ出す。 */
    private void onRemoteMessage(ByteArrayDataInput in) {
        String origin = in.readUTF();
        String playerName = in.readUTF();
        String body = in.readUTF();

        // Forward の宛先 ALL が送信元を含むかは実装依存。自分発なら捨てて二重表示を防ぐ。
        if (channel.isSelf(origin)) {
            return;
        }
        if (!config.chatEnabled()) {
            return;
        }
        Component rendered = render(config.displayName(origin), playerName, Component.text(body));
        Bukkit.broadcast(rendered);
        plugin.getLogger().info("[" + config.displayName(origin) + "] " + playerName + ": " + body);
    }

    private Component render(String serverDisplay, String playerName, Component message) {
        return ChatFormat.render(config.chatFormat(), serverDisplay, playerName, message,
                warning -> plugin.getLogger().warning("[network.yml] " + warning));
    }
}
