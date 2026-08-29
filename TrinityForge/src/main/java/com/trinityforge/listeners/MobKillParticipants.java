package com.trinityforge.listeners;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 討伐図鑑のための「そのモブを削ったのは誰か」台帳（2026-08-22、実サーバ報告
 * 「図鑑のモブにラストキルしか反映されない」）。
 *
 * <p><b>なぜ専用の台帳が要るのか。</b> 図鑑は以前
 * {@code EntityDeathEvent} の {@code getKiller()} だけを見ていた。これは<b>とどめを刺した1人</b>しか
 * 返さないので、複数人で削ったモブは最後の一撃を入れた人の図鑑にしか載らない。
 * 一方<b>戦闘EXPは寄与比で参加者全員に配っている</b>（{@link CombatKillCreditTracker}）ので、
 * 同じ討伐で「EXPは山分け・図鑑は独占」という食い違いになっていた。
 *
 * <p><b>それを流用しなかった理由。</b> {@link CombatKillCreditTracker} が記録するのは
 * <b>重武器・軽武器・弓術のダメージだけ</b>（{@code isKillBasedCombatWeaponSkill} で fail-closed）。
 * 魔法だけで削った人・召喚で削った人は載らないので、図鑑の帰属には使えない。
 * さらに {@code consume} が台帳を<b>取り除く</b>実装なので、同じ {@code EntityDeathEvent} を
 * MONITOR で受ける2本のリスナーが呼ぶと<b>登録順しだいで片方が空を受け取る</b>
 * （Bukkit は同一優先度の呼び出し順を保証しない）。ここを共有すると、無警告で
 * 「図鑑が入る日と入らない日がある」形で壊れる。
 *
 * <p>そこで図鑑側は「誰が削ったか」だけを持つ独立した台帳を持つ。スキルも寄与比も要らないので
 * {@code Set<UUID>} で足りる。
 *
 * <p><b>寿命の管理。</b> 対象は「まだ死んでいないモブ」なので、死なずに消えた分
 * （デスポーン・チャンクアンロード）は誰も片付けない。そこで
 * <ul>
 *   <li>victim 単位の LRU 上限（{@link #MAX_TRACKED_VICTIMS}）</li>
 *   <li>{@link #PARTICIPATION_TTL_MILLIS} を過ぎた行の掃除（{@link #SWEEP_INTERVAL} 回の記録ごと）</li>
 * </ul>
 * の2段で必ず有限になる。1体あたりの {@code Set} は<b>実際にそのモブを殴ったプレイヤー数</b>までしか
 * 増えないので（＝現実には同時接続数が上限）、こちら側に別の上限は要らない。
 *
 * <p><b>退出したプレイヤーを忘れない</b>のは意図的（{@link CombatKillCreditTracker#forgetAttacker}
 * と対称ではない）。EXP は「居ない人に配れない」ので忘れる必要があるが、図鑑は
 * <b>再ログインしてから討伐が確定した場合にも入ってよい</b>収集要素であり、
 * 落ちている間に仲間が倒したモブを取りこぼす方が報告として不自然になる。
 * 死亡時に {@code Bukkit.getPlayer(uuid)} が null なら結局スキップされるので、
 * オフラインへ書き込む事故にはならない。
 *
 * <p>メインスレッド専用（{@code EntityDamageEvent} / {@code EntityDeathEvent} からしか触らない）。
 */
final class MobKillParticipants {

    private static final int MAX_TRACKED_VICTIMS = 4_096;

    /** 参加の有効期間。{@link CombatKillCreditTracker#CREDIT_TTL_MILLIS} と揃えてある。 */
    static final long PARTICIPATION_TTL_MILLIS = 5 * 60 * 1_000L;

    private static final int SWEEP_INTERVAL = 128;

    private static final class Participation {

        private final Set<UUID> playerIds = new LinkedHashSet<>();
        private long touchedAtMillis;

        private Participation(long touchedAtMillis) {
            this.touchedAtMillis = touchedAtMillis;
        }
    }

    private final Map<UUID, Participation> byVictim =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Participation> eldest) {
                    return size() > MAX_TRACKED_VICTIMS;
                }
            };

    private int recordsSinceSweep;

    void record(UUID victimId, UUID playerId) {
        record(victimId, playerId, System.currentTimeMillis());
    }

    void record(UUID victimId, UUID playerId, long nowMillis) {
        if (victimId == null || playerId == null) {
            return;
        }
        Participation participation = byVictim.get(victimId);
        if (participation == null || expired(participation, nowMillis)) {
            // 期限切れの行は「別の戦闘」なので引き継がない。掃除より先に当たった場合の入口。
            participation = new Participation(nowMillis);
            byVictim.put(victimId, participation);
        }
        participation.touchedAtMillis = nowMillis;
        participation.playerIds.add(playerId);
        if (++recordsSinceSweep >= SWEEP_INTERVAL) {
            recordsSinceSweep = 0;
            byVictim.values().removeIf(candidate -> expired(candidate, nowMillis));
        }
    }

    /** 参加者を取り出して台帳から外す。期限切れなら空（＝その戦闘は無かったものとして扱う）。 */
    Set<UUID> consume(UUID victimId) {
        return consume(victimId, System.currentTimeMillis());
    }

    Set<UUID> consume(UUID victimId, long nowMillis) {
        if (victimId == null) {
            return Set.of();
        }
        Participation participation = byVictim.remove(victimId);
        if (participation == null || expired(participation, nowMillis)) {
            return Set.of();
        }
        return Set.copyOf(participation.playerIds);
    }

    void clear(UUID victimId) {
        if (victimId != null) {
            byVictim.remove(victimId);
        }
    }

    int trackedCount() {
        return byVictim.size();
    }

    private static boolean expired(Participation participation, long nowMillis) {
        return nowMillis - participation.touchedAtMillis > PARTICIPATION_TTL_MILLIS;
    }
}
