package com.trinityforge.listeners;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;

import java.util.Objects;

/** Applies support-role exclusive potion buffs (ROLE_SYSTEM_SPEC §5). */
public final class RoleBuffListener implements Listener {

    /**
     * 参加時の再付与を遅らせる tick 数(2秒)。{@code CollectionListener#JOIN_SCAN_DELAY_TICKS} と同値・同理由。
     *
     * <p><b>2026-08-19 修正(ユーザー報告「職業バフが付いていない人がまだいる／確認しているのは火炎耐性」)</b>:
     * HuskSync の snapshot 適用は {@link PlayerJoinEvent} より<b>後</b>で、その中身が
     * <ul>
     *   <li>{@code PotionEffects#apply} — <b>今付いている効果を全部 {@code removePotionEffect} してから</b>
     *       保存済みの効果だけを付け直す</li>
     *   <li>{@code PersistentData#apply} — {@code clearNBT()} してから snapshot の PDC を merge する</li>
     * </ul>
     * なので、参加イベントの中で付けたロールバフは<b>直後に必ず消される</b>し、
     * そこで読んだ PDC(＝ロール)も<b>捨てられる前の値</b>だった。
     * 結果、ロールバフは「HuskSync の効果 snapshot に相乗りして残っているだけ」で
     * <b>一度も付け直されない</b>状態になっており、{@code duration: 999999}(約13時間53分)を
     * 使い切るか、牛乳/{@code /effect clear}/浄化で消えると<b>二度と戻らなかった</b>
     * (再ログインでも直らないので「まだ付いていない人がいる」に見える)。
     */
    private static final long JOIN_REFRESH_DELAY_TICKS = 40L;

    /**
     * 定期リフレッシュの間隔(60秒)。<b>これが自己修復の本体</b>。
     *
     * <p>付け直しの機会が参加/リスポーン/ロール変更しか無いと、上記のどれか1つでも取りこぼした瞬間に
     * 「常時バフ」が永久に失われる。{@code role-buffs.yml} の説明文が<b>常時</b>と書いている以上、
     * 定期的に付け直すのが仕様どおりの挙動。弱い効果の {@code addPotionEffect} は
     * バニラの効果解決で「より強い既存効果」を上書きしないので、
     * プレイヤー自身が飲んだ上位ポーションを潰すこともない。
     */
    private static final long PERIODIC_REFRESH_TICKS = 20L * 60L;

    private final RoleBuffsConfig roleBuffs;
    /** リスポーン後の再付与を次tickへ逃がすために要る。テスト用に null を許す。 */
    private final org.bukkit.plugin.Plugin plugin;

    public RoleBuffListener(RoleBuffsConfig roleBuffs) {
        this(roleBuffs, null);
    }

    public RoleBuffListener(RoleBuffsConfig roleBuffs, org.bukkit.plugin.Plugin plugin) {
        this.roleBuffs = Objects.requireNonNull(roleBuffs, "roleBuffs");
        this.plugin = plugin;
    }

    /**
     * 参加時の再付与。<b>イベントの中では付けない</b> —— 理由は
     * {@link #JOIN_REFRESH_DELAY_TICKS} の javadoc（HuskSync が後から効果と PDC を丸ごと差し替える）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin == null) {
            refreshSupportBuff(player); // プラグイン未配線(テスト等)では従来どおり即時
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                refreshSupportBuff(player);
            }
        }, JOIN_REFRESH_DELAY_TICKS);
    }

    /**
     * オンライン全員のロールバフを定期的に付け直す（{@link #PERIODIC_REFRESH_TICKS} ごと）。
     * プラグイン有効化時に1回だけ呼ぶ。{@code plugin} 未配線なら何もしない。
     *
     * <p>参加/リスポーン/ロール変更だけでは、牛乳・{@code /effect clear}・浄化・
     * {@code duration} の満了・HuskSync の遅れた snapshot 適用のどれか1つで
     * 「常時バフ」が永久に落ちる。ここが唯一の自己修復経路。
     */
    public void startPeriodicRefresh() {
        if (plugin == null) {
            return;
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                refreshSupportBuff(online);
            }
        }, PERIODIC_REFRESH_TICKS, PERIODIC_REFRESH_TICKS);
    }

    /**
     * リスポーン後の再付与。
     *
     * <p><b>2026-08-17 修正 (ユーザー報告「死ぬとロール効果(バフ)が消える」)</b>:
     * {@link PlayerRespawnEvent} の<b>中で</b>付けたポーション効果は、そのあとに走る
     * サーバ側のリスポーン処理(エンティティの状態リセット)で捨てられる。
     * MONITOR まで待っても同じイベントの中なので手遅れで、<b>次tickへ逃がすのが唯一の手</b>。
     * ここを同期呼び出しに戻すと、死ぬたびにロールのバフだけが静かに消える。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (plugin == null) {
            refreshSupportBuff(player); // プラグイン未配線(テスト等)では従来どおり即時
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                refreshSupportBuff(player);
            }
        });
    }

    /**
     * Synchronizes the player's role-exclusive potion effects to their current support role.
     *
     * <p><b>2026-08-18 修正(ユーザー報告「幸運のエフェクトが消える」)</b>: 以前はここで
     * 「サポートロールが付け得るポーション型」を<b>全部無条件に</b> {@code removePotionEffect} していた。
     * {@code role-buffs.yml} の {@code fisher} が {@code LUCK} を付けるので、
     * <b>プレイヤーや管理コマンドが付けた幸運も毎回この一括除去で消えていた</b>。
     * 除去は {@link RolePotionOwnership} が「自前で付けた形」と認めた効果だけに限定する。
     */
    public void refreshSupportBuff(Player player) {
        if (player == null) {
            return;
        }
        PlayerData data = PlayerData.of(player);
        SupportRoleSpec support = data.roleSupport()
                .map(roleBuffs::supportRole)
                .orElse(null);
        var current = support == null ? null : support.potionBuff();
        // 旧ロールのバフを剥がす。current と同じ型は付け直しで上書きするので触らない
        // (剥がしてから付けると1tick分でも効果が切れる瞬間ができる)。
        roleBuffs.supportRoles().values().stream()
                .map(SupportRoleSpec::potionBuff)
                .filter(Objects::nonNull)
                .filter(spec -> current == null || !spec.type().equals(current.type()))
                .forEach(spec -> removeIfOwned(player, spec));
        if (current == null) {
            return;
        }
        player.addPotionEffect(new PotionEffect(
                current.type(), current.durationTicks(), current.amplifier(), true, false, true));
    }

    /** {@code spec} が付けたと形から判定できる効果だけを剥がす({@link RolePotionOwnership} が正本)。 */
    private static void removeIfOwned(Player player, RoleBuffsConfig.PotionBuffSpec spec) {
        PotionEffect existing = player.getPotionEffect(spec.type());
        if (existing == null) {
            return;
        }
        if (RolePotionOwnership.mayRemoveRoleBuff(spec.amplifier(), spec.durationTicks(),
                existing.getAmplifier(), existing.isAmbient(), existing.hasParticles(),
                existing.isInfinite(), existing.getDuration())) {
            player.removePotionEffect(spec.type());
        }
    }
}
