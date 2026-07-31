package com.trinityforge.progression;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * ロール変更の可否判定と適用(2026-07-28)。{@code /tf role set <combat> [support]} と
 * {@code /tf role set} のGUI({@link RoleSelectGui})が同じゲート・同じ副作用を通るようにするための
 * 単一の出所 — 片方だけ「戦闘中でも変更できる」といった穴が開かないようにする。
 *
 * <p><b>2026-07-31: 変更クールダウンを追加。</b>それまで変更は無制限・即時だったため、
 * 採掘するときだけ鉱夫・釣るときだけ漁師へ切り替えれば全系統に最大倍率が乗り、
 * 補助職の選択そのものが意味を失っていた（横の選択肢を増やす設計が「全部同時に持てる」で崩れる）。
 * 戦闘職と補助職で別々のクールダウンを持つのは、GUI で戦闘職を選んだ直後に補助職も選べる
 * 必要があるため（共通にすると片方を選んだ瞬間にもう片方が押せなくなる）。
 */
public final class RoleChangeService {

    /** この距離内に敵モブが居るとロール変更を拒否する(戦闘中の付け替え防止)。 */
    private static final double MONSTER_SCAN_RADIUS = 16.0;

    private final RoleBuffsConfig roleBuffs;
    private final RoleBuffListener roleBuffListener;
    /** テストから時刻を差し替えるための時計。既定は実時刻。 */
    private final LongSupplier clock;

    public RoleChangeService(RoleBuffsConfig roleBuffs, RoleBuffListener roleBuffListener) {
        this(roleBuffs, roleBuffListener, System::currentTimeMillis);
    }

    RoleChangeService(RoleBuffsConfig roleBuffs, RoleBuffListener roleBuffListener, LongSupplier clock) {
        this.roleBuffs = Objects.requireNonNull(roleBuffs, "roleBuffs");
        this.roleBuffListener = Objects.requireNonNull(roleBuffListener, "roleBuffListener");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RoleBuffsConfig config() {
        return roleBuffs;
    }

    /**
     * ロール変更が可能か（枠に依存しない共通ゲートのみ）。
     *
     * <p>クールダウンは枠ごとなので、ここでは見ない。{@link #denyReasonForCombat(Player)} /
     * {@link #denyReasonForSupport(Player)} が「共通ゲート + その枠のクールダウン」を返す。
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

    /** 戦闘職を変更できるか(共通ゲート + 戦闘職のクールダウン)。 */
    public Optional<String> denyReasonForCombat(Player player) {
        Optional<String> shared = denyReason(player);
        if (shared.isPresent()) {
            return shared;
        }
        return cooldownDenial("戦闘職", PlayerData.of(player).rolePrimaryChangedAt());
    }

    /** 補助職を変更できるか(共通ゲート + 補助職のクールダウン)。 */
    public Optional<String> denyReasonForSupport(Player player) {
        Optional<String> shared = denyReason(player);
        if (shared.isPresent()) {
            return shared;
        }
        return cooldownDenial("補助職", PlayerData.of(player).roleSupportChangedAt());
    }

    /** その枠のクールダウン残り(ミリ秒)。待てる状態なら 0。 */
    public long combatCooldownRemainingMillis(Player player) {
        return cooldownRemaining(PlayerData.of(player).rolePrimaryChangedAt());
    }

    public long supportCooldownRemainingMillis(Player player) {
        return cooldownRemaining(PlayerData.of(player).roleSupportChangedAt());
    }

    private Optional<String> cooldownDenial(String slotLabel, long changedAt) {
        long remaining = cooldownRemaining(changedAt);
        if (remaining <= 0L) {
            return Optional.empty();
        }
        return Optional.of(slotLabel + "の変更はあと " + formatRemaining(remaining) + " 待つ必要があります。");
    }

    /**
     * クールダウン残り。判定に使うのは<b>刻印時刻だけ</b>で、枠が空かどうかは見ない。
     *
     * <p>「空の枠なら無料」にすると {@code /tf role clear} → 即再選択がクールダウンの
     * 完全な迂回路になる（解除すれば枠は空になる）。初回の逃げ道は「刻まない」側
     * ({@code first-choice-free}) で作ってあるので、ここで空欄を特別扱いする必要はない。
     */
    private long cooldownRemaining(long changedAt) {
        long cooldown = roleBuffs.roleChangeCooldownMillis();
        if (cooldown <= 0L || changedAt <= 0L) {
            return 0L;
        }
        long elapsed = clock.getAsLong() - changedAt;
        // 時計が巻き戻った(サーバの時刻修正等)ときは待たせない。待たせると復旧手段が無い。
        if (elapsed < 0L) {
            return 0L;
        }
        return Math.max(0L, cooldown - elapsed);
    }

    /** 残り時間を「1時間23分」「45分」「30秒」の形にする。コマンドとGUIで同じ表記にするため公開。 */
    public static String formatRemaining(long millis) {
        long totalSeconds = Math.max(1L, (millis + 999L) / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return minutes > 0L ? hours + "時間" + minutes + "分" : hours + "時間";
        }
        if (minutes > 0L) {
            return minutes + "分";
        }
        return seconds + "秒";
    }

    /**
     * 戦闘職を設定する。未知のIDなら {@code false}(呼び出し側がメッセージを出す)。
     *
     * <p>クールダウンを刻まないのは次の2ケース:
     * <ul>
     *   <li>同じIDを選び直した — 押し間違いで待ち時間が始まると取り返しがつかない。</li>
     *   <li>{@code first-choice-free} かつ枠が空だった — 始めたばかりの人が
     *       「1つ選んでみて説明を読み、選び直す」までは無料にする。</li>
     * </ul>
     */
    public boolean setCombat(Player player, String rawId) {
        String id = normalize(rawId);
        if (id == null || !roleBuffs.combatRoles().containsKey(id)) {
            return false;
        }
        PlayerData data = PlayerData.of(player);
        Optional<String> before = data.rolePrimary();
        if (before.filter(id::equals).isPresent()) {
            return true;
        }
        data.setRolePrimary(id);
        if (!stampsFreely(before)) {
            data.setRolePrimaryChangedAt(clock.getAsLong());
        }
        roleBuffListener.refreshSupportBuff(player);
        return true;
    }

    /** 補助職を設定する。未知のIDなら {@code false}。 */
    public boolean setSupport(Player player, String rawId) {
        String id = normalize(rawId);
        if (id == null || !roleBuffs.supportRoles().containsKey(id)) {
            return false;
        }
        PlayerData data = PlayerData.of(player);
        Optional<String> before = data.roleSupport();
        if (before.filter(id::equals).isPresent()) {
            return true;
        }
        data.setRoleSupport(id);
        if (!stampsFreely(before)) {
            data.setRoleSupportChangedAt(clock.getAsLong());
        }
        roleBuffListener.refreshSupportBuff(player);
        return true;
    }

    /** 空の枠を初めて埋めるときはクールダウンを刻まないか。 */
    private boolean stampsFreely(Optional<String> previousRole) {
        return previousRole.isEmpty() && roleBuffs.firstChoiceFree();
    }

    /**
     * 戦闘職・補助職をどちらも解除する。
     *
     * <p>解除でもクールダウンは刻む。刻まないと「解除 → 即再選択」がクールダウンの
     * 完全な迂回路になる(初回無料の判定は「枠が空か」なので、解除した直後は空になる)。
     */
    public void clear(Player player) {
        PlayerData data = PlayerData.of(player);
        long now = clock.getAsLong();
        data.clearRoles();
        data.setRolePrimaryChangedAt(now);
        data.setRoleSupportChangedAt(now);
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
