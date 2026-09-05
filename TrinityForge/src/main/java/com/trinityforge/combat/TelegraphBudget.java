package com.trinityforge.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * プレイヤー1人に同時に向けられる「予告付きの技」の本数を制限する台帳（2026-09-04）。
 *
 * <p><b>なぜプレイヤー単位か。</b>「部屋」や「戦闘グループ」を単位にすると、インスタンスワールドが
 * 入場のたびに名前を変える都合でキーを一意に定義できない。プレイヤー UUID は入場・退出を跨いでも
 * 変わらないので、これだけが定義の一意な単位になる。
 *
 * <p><b>なぜ予約は詠唱開始時に取り、解放は「解決・不発・中断・術者の死亡・対象の退出」の
 * 全部で行うか。</b>詠唱を始めた瞬間から、そのプレイヤーは実質的にその技へ晒されている
 * （回避の選択肢を削られている）ので予約はそこで取る。だが詠唱の結末は一通りではない
 * （最後まで解決する・主対象を見失って不発になる・術者が死んで消える・対象が退出する）
 * ので、そのどの経路を通っても必ず枠を返さなければ、実際には解決していない技が
 * 永久に枠を握り続けて他の技を撃てなくする。
 *
 * <p><b>なぜ「候補が全部落ちたら別の技へ振り替えない」のか。</b>この規約自体は本クラスの
 * 責務ではなく、本クラスを使う抽選側（`MobAbilityTask`）の約束だが、台帳の設計意図を
 * 守るためにここへ明記する。予算が埋まっているときに「軽い技なら空いているから」と
 * 振り替えると、プレイヤーへの圧力が一番高い瞬間ほど身軽な技が飛んでくるという、
 * 予告予算を設けた目的（致命の同時発生を抑える）と正反対の挙動になる。抽選が候補を
 * 全部落としたら、その回は素直に空振りにする。
 *
 * <p>Bukkit 非依存（{@link UUID} と時刻だけ）。メインスレッド専用なので同期は取らない。
 */
public final class TelegraphBudget {

    /** 同時に持てる致命予告の本数上限。 */
    public static final int LETHAL_LIMIT = 1;

    /** 同時に持てる予告の合計本数上限（致命・通常を問わない）。 */
    public static final int TOTAL_LIMIT = 2;

    /** 解決予定時刻を過ぎてもこの猶予だけ待ってから自動解放する（release 漏れへの保険）。 */
    public static final long EXPIRY_GRACE_MILLIS = 1000L;

    /** 1件の予約。 */
    public record Reservation(
            UUID player, UUID caster, String abilityId, boolean lethal, long resolveAtMillis) {}

    /** プレイヤーUUID → 進行中の予約一覧。 */
    private final Map<UUID, List<Reservation>> reservations = new HashMap<>();

    private final LongSupplier nowMillis;

    public TelegraphBudget() {
        this(System::currentTimeMillis);
    }

    /** テスト用に時刻を差し替えられる構築子。 */
    public TelegraphBudget(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    /** そのプレイヤーへ新しい予告を予約できるか。期限切れの掃除を先に済ませてから数える。 */
    public boolean canReserve(UUID player, boolean lethal) {
        List<Reservation> active = purgeExpiredFor(player);
        if (active == null || active.isEmpty()) {
            return true;
        }
        if (active.size() >= TOTAL_LIMIT) {
            return false;
        }
        if (lethal) {
            long lethalCount = active.stream().filter(Reservation::lethal).count();
            return lethalCount < LETHAL_LIMIT;
        }
        return true;
    }

    /** 予約できるときだけ登録して返す。取れなければ空。 */
    public Optional<Reservation> tryReserve(
            UUID player, UUID caster, String abilityId, boolean lethal, long resolveAtMillis) {
        if (!canReserve(player, lethal)) {
            return Optional.empty();
        }
        Reservation reservation = new Reservation(player, caster, abilityId, lethal, resolveAtMillis);
        reservations.computeIfAbsent(player, id -> new ArrayList<>()).add(reservation);
        return Optional.of(reservation);
    }

    /** 予約を外す。既に無ければ何もしない（多重解放を許容する）。 */
    public void release(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        List<Reservation> list = reservations.get(reservation.player());
        if (list == null) {
            return;
        }
        list.remove(reservation);
        if (list.isEmpty()) {
            reservations.remove(reservation.player());
        }
    }

    /** その術者の予約を、対象プレイヤーを問わず全部外す（術者の死亡・消滅時）。外した数を返す。 */
    public int releaseCaster(UUID caster) {
        int removed = 0;
        Iterator<Map.Entry<UUID, List<Reservation>>> entries = reservations.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, List<Reservation>> entry = entries.next();
            List<Reservation> list = entry.getValue();
            Iterator<Reservation> it = list.iterator();
            while (it.hasNext()) {
                if (it.next().caster().equals(caster)) {
                    it.remove();
                    removed++;
                }
            }
            if (list.isEmpty()) {
                entries.remove();
            }
        }
        return removed;
    }

    /** そのプレイヤーの予約を全部外す（退出・死亡・ワールド変更時）。外した数を返す。 */
    public int releasePlayer(UUID player) {
        List<Reservation> removed = reservations.remove(player);
        return removed == null ? 0 : removed.size();
    }

    /** そのプレイヤーの進行中の予約一覧（期限切れ掃除後の不変コピー）。 */
    public List<Reservation> active(UUID player) {
        List<Reservation> list = purgeExpiredFor(player);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** 全プレイヤーぶんの期限切れ予約を掃除する。外した数を返す。 */
    public int purgeExpired() {
        int removed = 0;
        Iterator<Map.Entry<UUID, List<Reservation>>> entries = reservations.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, List<Reservation>> entry = entries.next();
            List<Reservation> list = entry.getValue();
            int before = list.size();
            list.removeIf(this::isExpired);
            removed += before - list.size();
            if (list.isEmpty()) {
                entries.remove();
            }
        }
        return removed;
    }

    /** 1プレイヤーぶんの期限切れだけを掃除し、そのプレイヤーの現行リストを返す（無ければ null）。 */
    private List<Reservation> purgeExpiredFor(UUID player) {
        List<Reservation> list = reservations.get(player);
        if (list == null) {
            return null;
        }
        list.removeIf(this::isExpired);
        if (list.isEmpty()) {
            reservations.remove(player);
            return null;
        }
        return list;
    }

    private boolean isExpired(Reservation reservation) {
        return nowMillis.getAsLong() > reservation.resolveAtMillis() + EXPIRY_GRACE_MILLIS;
    }
}
