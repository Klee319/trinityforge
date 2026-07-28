package com.trinityforge.progression;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * ロール変更の可否判定と適用(2026-07-28)。{@code /tf role set <combat> [support]} と
 * {@code /tf role set} のGUI({@link RoleSelectGui})が同じゲート・同じ副作用を通るようにするための
 * 単一の出所 — 片方だけ「戦闘中でも変更できる」といった穴が開かないようにする。
 */
public final class RoleChangeService {

    /** この距離内に敵モブが居るとロール変更を拒否する(戦闘中の付け替え防止)。 */
    private static final double MONSTER_SCAN_RADIUS = 16.0;

    private final RoleBuffsConfig roleBuffs;
    private final RoleBuffListener roleBuffListener;

    public RoleChangeService(RoleBuffsConfig roleBuffs, RoleBuffListener roleBuffListener) {
        this.roleBuffs = Objects.requireNonNull(roleBuffs, "roleBuffs");
        this.roleBuffListener = Objects.requireNonNull(roleBuffListener, "roleBuffListener");
    }

    public RoleBuffsConfig config() {
        return roleBuffs;
    }

    /**
     * ロール変更が可能か。
     *
     * @return 変更できない理由(プレイヤーへそのまま出せる日本語)。変更できるなら {@link Optional#empty()}
     */
    public Optional<String> denyReason(Player player) {
        if (player == null) {
            return Optional.of("プレイヤー専用コマンドです。");
        }
        if (!roleBuffs.allowRoleCommand()) {
            return Optional.of("コマンドによるロール変更は無効です。");
        }
        if (player.getNearbyEntities(MONSTER_SCAN_RADIUS, MONSTER_SCAN_RADIUS, MONSTER_SCAN_RADIUS)
                .stream().anyMatch(Monster.class::isInstance)) {
            return Optional.of("近くに敵モブがいるためロール変更できません。");
        }
        return Optional.empty();
    }

    /** 戦闘職を設定する。未知のIDなら {@code false}(呼び出し側がメッセージを出す)。 */
    public boolean setCombat(Player player, String rawId) {
        String id = normalize(rawId);
        if (id == null || !roleBuffs.combatRoles().containsKey(id)) {
            return false;
        }
        PlayerData.of(player).setRolePrimary(id);
        roleBuffListener.refreshSupportBuff(player);
        return true;
    }

    /** 補助職を設定する。未知のIDなら {@code false}。 */
    public boolean setSupport(Player player, String rawId) {
        String id = normalize(rawId);
        if (id == null || !roleBuffs.supportRoles().containsKey(id)) {
            return false;
        }
        PlayerData.of(player).setRoleSupport(id);
        roleBuffListener.refreshSupportBuff(player);
        return true;
    }

    /** 戦闘職・補助職をどちらも解除する。 */
    public void clear(Player player) {
        PlayerData.of(player).clearRoles();
        roleBuffListener.refreshSupportBuff(player);
    }

    /** 設定ファイルのキーと同じ正規化(小文字・前後空白除去)。空文字/null は {@code null}。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
