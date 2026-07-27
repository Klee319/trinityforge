package com.trinityforge.combat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.particle.Particle;
import com.github.retrooper.packetevents.protocol.particle.type.ParticleTypes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerParticle;
import com.trinityforge.config.domains.DisplayConfig;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * バニラの被弾パーティクル({@code damage_indicator} = ハート状の赤いアイコン)の個数に上限を掛ける。
 *
 * <p><b>なぜパケット層なのか</b>: バニラは近接命中時に与ダメージへ比例した個数の
 * {@code damage_indicator} を {@code sendParticles} で直接ブロードキャストする。これは
 * {@code World#spawnParticle} 相当の内部呼び出しで、対応する Bukkit イベントが<b>存在しない</b>ため、
 * 通常のリスナーでは個数を触れない。TF はダメージ量を大きく引き上げているので、1ヒットで数百個湧いて
 * 画面が埋まる(2026-07-28 実サーバ報告)。送信直前のパケットで個数フィールドだけを丸めるのが唯一の手段。
 *
 * <p><b>ダメージ計算には一切影響しない</b> — 触るのは表示用パケットの個数フィールドのみ。
 *
 * <p><b>任意依存(fail-open)</b>: packetevents が無い環境ではこのクラスは一切ロードされない
 * ({@link #install} の呼び出し側がプラグイン存在を確認してから初めて参照する)。ロードや登録に
 * 失敗しても警告1行を出して機能ごと諦め、サーバ起動は止めない。
 *
 * <p><b>設定は毎パケット読み直す</b>ので {@code /tf reload} だけで上限値の変更が効く
 * (リスナーの再登録は不要)。
 */
public final class DamageIndicatorParticleLimiter extends PacketListenerAbstract {

    /** packetevents 本体のプラグイン名(plugin.yml の {@code name})。 */
    public static final String PACKETEVENTS_PLUGIN = "packetevents";

    private final DisplayConfig display;

    private DamageIndicatorParticleLimiter(DisplayConfig display) {
        super(PacketListenerPriority.NORMAL);
        this.display = display;
    }

    /**
     * リスナーを packetevents へ登録する。
     *
     * <p><b>呼び出し側の責務</b>: packetevents プラグインの存在を確認してから呼ぶこと
     * ({@link #PACKETEVENTS_PLUGIN})。このクラスは packetevents の型を直接参照しているため、
     * 未導入の環境で参照するとクラスロード自体が {@link NoClassDefFoundError} になる。
     *
     * @return 登録解除用の {@link Runnable}。登録できなかった場合は {@code null}(機能無効、起動は継続)。
     *         戻り値の型を packetevents に依存しない {@link Runnable} にしているのは、呼び出し側
     *         ({@code TrinityForge})がこれをフィールドに保持しても packetevents 不在環境で
     *         クラス解決が走らないようにするため。
     */
    public static Runnable install(Plugin plugin, DisplayConfig display) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(display, "display");
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null) {
            plugin.getLogger().warning("[display] packetevents API が未初期化のため "
                    + "damage_indicator パーティクル上限を有効化できませんでした。");
            return null;
        }
        PacketListenerCommon registered =
                api.getEventManager().registerListener(new DamageIndicatorParticleLimiter(display));
        plugin.getLogger().info("[display] damage_indicator パーティクル上限を有効化しました"
                + " (max-count=" + display.damageIndicatorMaxCount() + ", packetevents 経由)");
        // packetevents のリスナー登録は TF の無効化では自動的に外れない。外し忘れると hot-reload の
        // たびに多重登録され、古い DisplayConfig を掴んだリスナーが残り続ける。
        return () -> api.getEventManager().unregisterListener(registered);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.PARTICLE) {
            return;
        }
        int max = display.damageIndicatorMaxCount();
        if (max < 0) {
            return; // 制限しない — パケットのデコードすらしない
        }
        WrapperPlayServerParticle wrapper = new WrapperPlayServerParticle(event);
        if (!isDamageIndicator(wrapper.getParticle())) {
            return;
        }
        if (max == 0) {
            // 個数0のパーティクルパケットは「消える」のではなく『offset を速度として1個だけ飛ばす』
            // というバニラの特殊仕様になる。完全に消したいならパケットごと落とすしかない。
            event.setCancelled(true);
            return;
        }
        if (wrapper.getParticleCount() <= max) {
            return;
        }
        wrapper.setParticleCount(max);
        event.markForReEncode(true);
    }

    private static boolean isDamageIndicator(Particle<?> particle) {
        // ParticleType は registry 由来のシングルトンなので参照比較で足りるが、
        // 将来 packetevents 側が別インスタンスを返しても壊れないよう名前で比較する。
        return particle != null && particle.getType() != null
                && ParticleTypes.DAMAGE_INDICATOR.getName().equals(particle.getType().getName());
    }
}
