package com.trinityforge.progression;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * 称号の表示体を<b>クライアント側でだけ</b>プレイヤーへ騎乗させ、頭上表示のズレを消す
 * (2026-08-19 / W-153、実サーバ報告「称号の位置がネームタグと同期していない。少し遅れてついてくる」)。
 *
 * <h2>なぜテレポート追従では直らないのか</h2>
 * {@link TitleDisplayService} は表示体を毎tickテレポートさせて追従させている。しかし
 * クライアントは<b>プレイヤー本体と表示体を別々に補間する</b>(本体は移動パケットを既定3tickかけて
 * 補間し、表示体は {@code teleport_duration} ぶんで補間する)。この2つは原理的に一致しないので、
 * 補間長をどう調整しても「本体と称号がぴったり同じ動きをする」状態にはならない。
 * 2026-08-19 の W-135 で補間長を更新間隔に揃えて<b>揺れ</b>は消えたが、<b>ズレ</b>は残っていた。
 *
 * <h2>なぜ本当に騎乗させないのか</h2>
 * Paper のドキュメントは「装飾ネームタグは表示体をパッセンジャーにする」ことを勧めており、
 * 騎乗させればクライアントは乗騎の描画位置から乗客の位置を毎フレーム決めるので<b>ズレは構造的に消える</b>。
 * ところがサーバ側で本当に {@code addPassenger} すると、
 * <b>パッセンジャーを持つエンティティは {@code Entity#teleport} が失敗する</b>
 * (PaperMC/Paper#10168。しかも {@code PlayerTeleportEvent} すら発火しないので他プラグインからは
 * 原因が見えない)。ダンジョン入口の転送やサーバ間移動が無言で壊れるため、この道は 2026-08-03 に
 * 一度捨てられている。
 *
 * <p>そこで<b>パケットだけ</b>で騎乗させる。サーバ側では表示体は独立エンティティのままなので
 * テレポートは一切妨げず、クライアントから見ると称号はプレイヤーに固定されて動く。
 *
 * <h2>いつ送るか</h2>
 * 騎乗パケットは<b>クライアントが両者を認識した後</b>に届かないと捨てられる。追跡開始のたびに
 * 送り直す必要があるので、送信される {@code SPAWN_ENTITY} を覗いて
 * <b>乗騎(プレイヤー)か乗客(表示体)のどちらかが湧いた受信者に対してだけ</b>直後に送る
 * (1.20.2 以降はプレイヤーの湧きも {@code SPAWN_ENTITY})。定期的な総当たり再送より、
 * 必要な相手へ必要なときだけ送るほうが安く、取りこぼしも無い。
 *
 * <h2>任意依存(fail-open)</h2>
 * packetevents が無い環境ではこのクラスを<b>参照してはならない</b>(packetevents の型を直接持つので
 * クラスロードが {@link NoClassDefFoundError} になる)。呼び出し側がプラグイン存在を確認してから
 * 初めて触ること。導入されていなければ {@link TitleDisplayService} は従来どおりの
 * テレポート追従のまま動く(ズレは残るが表示は出る)。
 */
public final class TitleDisplayMountBridge extends PacketListenerAbstract {

    /** packetevents 本体のプラグイン名(plugin.yml の {@code name})。 */
    public static final String PACKETEVENTS_PLUGIN = "packetevents";

    private final TitleDisplayService service;

    private TitleDisplayMountBridge(TitleDisplayService service) {
        super(PacketListenerPriority.MONITOR);
        this.service = service;
    }

    /**
     * リスナーを packetevents へ登録し、{@link TitleDisplayService} に騎乗モードを通知する。
     *
     * @return 登録解除用の {@link Runnable}(packetevents に依存しない型で返すのは、呼び出し側が
     *         フィールドに保持しても未導入環境でクラス解決が走らないようにするため)。
     *         登録できなければ {@code null}(機能無効、起動は継続)。
     */
    public static Runnable install(Plugin plugin, TitleDisplayService service) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(service, "service");
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null) {
            plugin.getLogger().warning("[title-display] packetevents API が未初期化のため、"
                    + "称号のクライアント騎乗を有効化できませんでした(従来のテレポート追従のまま動きます)。");
            return null;
        }
        PacketListenerCommon registered =
                api.getEventManager().registerListener(new TitleDisplayMountBridge(service));
        service.setMountBridgeActive(true);
        plugin.getLogger().info("[title-display] 称号をクライアント側でプレイヤーへ騎乗させます"
                + "(頭上表示のズレ対策、packetevents 経由)");
        return () -> {
            service.setMountBridgeActive(false);
            api.getEventManager().unregisterListener(registered);
        };
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SPAWN_ENTITY) {
            return;
        }
        if (service.mountPairs().isEmpty()) {
            // 誰も称号を出していない間はパケットのデコードすらしない(SPAWN_ENTITY は数が出る)。
            return;
        }
        int spawnedEntityId = new WrapperPlayServerSpawnEntity(event).getEntityId();
        int[] pair = service.mountPairFor(spawnedEntityId);
        if (pair == null) {
            return;
        }
        User user = event.getUser();
        int vehicleId = pair[0];
        int passengerId = pair[1];
        // 必ず「湧かせたあと」に送る。先に送ると、クライアントがまだ知らないエンティティ id への
        // 騎乗指示になって捨てられる。
        event.getTasksAfterSend().add(() ->
                user.sendPacket(new WrapperPlayServerSetPassengers(vehicleId, new int[] {passengerId})));
    }
}
