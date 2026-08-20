package com.trinityforge.progression;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.listeners.RoleBuffListener;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * ロール変更の可否判定と適用(2026-07-28)。ロール選択GUI({@link RoleSelectGui})・
 * 転職の証({@code role_reselect_ticket})・{@code /tf role clear} が同じゲート・同じ副作用を
 * 通るようにするための単一の出所 — 片方だけ「戦闘中でも変更できる」といった穴が開かないようにする。
 *
 * <p><b>2026-08-05 (W-28): {@code /tf role set} を廃止し、確認・変更の動線は {@code /tf status}
 * のロールアイコン → {@link RoleSelectGui} だけになった。</b>あわせて
 * {@code role-change.allow-change: false} を「アイテム消費でのみ変更可」の意味に定義した
 * （旧 {@code allow-command: false} は「一切変更不可」で、初回の就職すらできなかった）。
 *
 * <p><b>2026-07-31: 変更クールダウンを追加。</b>それまで変更は無制限・即時だったため、
 * 採掘するときだけ鉱夫・釣るときだけ漁師へ切り替えれば全系統に最大倍率が乗り、
 * 補助職の選択そのものが意味を失っていた（横の選択肢を増やす設計が「全部同時に持てる」で崩れる）。
 * 戦闘職と補助職で別々のクールダウンを持つのは、GUI で戦闘職を選んだ直後に補助職も選べる
 * 必要があるため（共通にすると片方を選んだ瞬間にもう片方が押せなくなる）。
 *
 * <p><b>ここには独立した 2 つの関門がある。混ぜないこと。</b>
 * <ul>
 *   <li><b>交戦中ガード</b>({@code role-change.nearby-enemy-radius}, 既定 0=無効) —
 *       「今この瞬間、戦闘中か」だけを見る。乗せ替え悪用の抑止はこれの仕事ではない。</li>
 *   <li><b>変更クールダウン</b>({@code role-change.cooldown-minutes}, 既定 120 分) —
 *       枠ごとの待ち時間。<b>{@code /tf role clear} → 即再選択という迂回路を塞いでいるのは
 *       こちら側だけ</b>({@link #clear(Player)} が両枠の刻印時刻を更新する)なので、
 *       交戦中ガードを無効にしても迂回路は開かない。</li>
 * </ul>
 */
public final class RoleChangeService {

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

    /** 券アイテム({@code role_reselect_ticket})の表示名。メッセージ内でだけ使う。 */
    private static final String TICKET_LABEL = "転職の証";

    /**
     * ロールの<b>解除</b>ができるか（プレイヤーかどうかと {@code allow-change} だけ）。
     *
     * <p>交戦中ガードもクールダウンも見ない。<b>{@code /tf role clear} のような「外すだけ」の
     * 動線用</b>で、ロールの説明を読む操作(GUIを開く)は誰でも通す
     * （実際に付け替える側は {@link #denyReasonForCombat(Player)} 等を通す）。
     *
     * <p><b>{@code allow-change: false}(=アイテム消費でのみ変更可)では解除も塞ぐ。</b>
     * 解除で枠を空にできると「初回の無料就職」を無限に再利用できてしまい、券が要らなくなる。
     *
     * @return 使えない理由(プレイヤーへそのまま出せる日本語)。使えるなら {@link Optional#empty()}
     */
    public Optional<String> changeDisabledReason(Player player) {
        if (player == null) {
            return Optional.of("プレイヤー専用コマンドです。");
        }
        if (!roleBuffs.allowRoleChange()) {
            return Optional.of("この鯖では職業の解除はできません(変更は「" + TICKET_LABEL + "」の使用時のみ)。");
        }
        return Optional.empty();
    }

    /**
     * ロールを<b>付け替え</b>られるか（枠に依存しない共通ゲートのみ）。
     *
     * <p>クールダウンと {@code allow-change} は枠ごとに見るので、ここでは見ない
     * （{@code allow-change: false} でも「まだ就いていない枠への初回就職」は通すため、
     * 枠の状態を知らないこの入口では判定できない）。{@link #denyReasonForCombat(Player)} /
     * {@link #denyReasonForSupport(Player)} が「共通ゲート + その枠の可否」を返す。
     *
     * @return 変更できない理由(プレイヤーへそのまま出せる日本語)。変更できるなら {@link Optional#empty()}
     */
    public Optional<String> denyReason(Player player) {
        if (player == null) {
            return Optional.of("プレイヤー専用コマンドです。");
        }
        double radius = roleBuffs.nearbyEnemyRadius();
        if (radius > 0.0 && engagedInCombat(player, radius)) {
            return Optional.of("戦闘中(敵に狙われている間)はロール変更できません。");
        }
        return Optional.empty();
    }

    /**
     * 「本当に交戦中か」。半径内の敵を Paper の {@link Enemy} で判定し、さらに
     * <b>{@link Mob#getTarget()} が自分のときだけ</b>交戦中とみなす。
     *
     * <p>距離だけで見ていた 2026-07-31 以前は、ネザーのゾンビピグリンや壁越し・地下洞窟のモブでも
     * 拒否されて「拠点や洞窟付近では常時変更不可」になっていた。敵対判定に Bukkit の
     * {@code Monster} を使ってはいけない（中立の PigZombie/Piglin/Enderman を拾い、
     * Slime/Ghast/Shulker/EnderDragon/Hoglin を落とす）のは、このプロジェクトで既に踏んだ落とし穴。
     *
     * <p>{@code Mob} でない {@link Enemy}（現行の paper-api には無いが将来増えうる）は
     * ターゲットを問い合わせられないので距離だけで拒否する。逆に、ターゲットを公開しない
     * ボス AI（EnderDragon 等は {@code getTarget()} が null のことがある）はガードを跨げる —
     * ここは「誤爆で常時変更不可になる」方を重く見た上での割り切り。
     */
    private static boolean engagedInCombat(Player player, double radius) {
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Enemy)) {
                continue;
            }
            if (!(entity instanceof Mob mob) || player.equals(mob.getTarget())) {
                return true;
            }
        }
        return false;
    }

    /** 戦闘職を変更できるか(共通ゲート + 戦闘職の可否)。 */
    public Optional<String> denyReasonForCombat(Player player) {
        Optional<String> shared = denyReason(player);
        if (shared.isPresent()) {
            return shared;
        }
        PlayerData data = PlayerData.of(player);
        return slotDenial("戦闘職", data.rolePrimary(), data.rolePrimaryChangedAt());
    }

    /** 補助職を変更できるか(共通ゲート + 補助職の可否)。 */
    public Optional<String> denyReasonForSupport(Player player) {
        Optional<String> shared = denyReason(player);
        if (shared.isPresent()) {
            return shared;
        }
        PlayerData data = PlayerData.of(player);
        return slotDenial("補助職", data.roleSupport(), data.roleSupportChangedAt());
    }

    /**
     * その枠を付け替えられるか。{@code allow-change} が
     * <ul>
     *   <li><b>true</b>(既定) — 従来どおりクールダウン制。</li>
     *   <li><b>false</b> — <b>まだ就いていない枠への初回就職だけ無料で通し</b>、それ以降は
     *       券({@code role_reselect_ticket})の所持時のみ通す。券のバイパスは呼び出し側
     *       ({@link RoleSelectGui})が「確定直前に手に持っているか」で判定するので、
     *       ここでは常に「券が必要」と答えてよい。</li>
     * </ul>
     *
     * <p>不許可モードで {@code first-choice-free: false} なら初回の就職も券が要る
     * （運営が全員へ券を配って始める運用。config の組み合わせとして成立させておく）。
     */
    private Optional<String> slotDenial(String slotLabel, Optional<String> currentRole, long changedAt) {
        if (!roleBuffs.allowRoleChange()) {
            if (currentRole.isEmpty() && roleBuffs.firstChoiceFree()) {
                return Optional.empty();
            }
            return Optional.of(slotLabel + "の変更は「" + TICKET_LABEL + "」を手に持って右クリックしたときだけできます。");
        }
        return cooldownDenial(slotLabel, changedAt);
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
