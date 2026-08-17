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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refreshSupportBuff(event.getPlayer());
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
