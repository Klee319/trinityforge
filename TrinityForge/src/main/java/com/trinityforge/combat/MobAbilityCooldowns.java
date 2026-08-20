package com.trinityforge.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 「このモブがこの技を次に撃てる時刻」だけを持つ台帳（2026-07-31）。
 *
 * <p><b>PDC に書かないのは意図的</b>。クールダウンは戦闘中しか意味を持たない揮発値で、
 * PDC に書くとモブ1体ごとにエンティティデータが膨らみ、チャンク保存にも乗ってしまう。
 * モブが消えたぶんは {@link #forget(UUID)} と {@link #purge(java.util.Set)} で落とす。
 *
 * <p>メインスレッド専用（Bukkit のエンティティ操作と同じスレッドからしか触らない）ので同期は取らない。
 */
public final class MobAbilityCooldowns {

    /** モブUUID → (技ID → 次に撃てる時刻ms)。 */
    private final Map<UUID, Map<String, Long>> readyAt = new HashMap<>();
    private final LongSupplier clock;

    public MobAbilityCooldowns() {
        this(System::currentTimeMillis);
    }

    /** テスト用に時刻を差し替えられる構築子。 */
    public MobAbilityCooldowns(LongSupplier clock) {
        this.clock = clock;
    }

    /** 今この技を撃てるか。未記録（初見）なら撃てる。 */
    public boolean ready(UUID mobId, String abilityId) {
        Map<String, Long> perAbility = readyAt.get(mobId);
        if (perAbility == null) {
            return true;
        }
        Long ready = perAbility.get(abilityId);
        return ready == null || clock.getAsLong() >= ready;
    }

    /** 発動した記録を付ける。 */
    public void arm(UUID mobId, String abilityId, long cooldownMillis) {
        readyAt.computeIfAbsent(mobId, id -> new HashMap<>())
                .put(abilityId, clock.getAsLong() + Math.max(0L, cooldownMillis));
    }

    /** そのモブの記録を捨てる（死亡/アンロード時）。 */
    public void forget(UUID mobId) {
        readyAt.remove(mobId);
    }

    /**
     * 生きているモブの集合に含まれないエントリを落とす。
     *
     * <p>これが無いと、死亡イベントを取りこぼしたモブ（チャンクアンロード、ワールド削除、
     * EliteMobs のインスタンスワールド破棄）の分が永久に残る。インスタンスダンジョンは
     * <b>入場ごとに新しいワールドを作って捨てる</b>運用なので、取りこぼしは必ず起きる。
     */
    public void purge(java.util.Set<UUID> aliveMobIds) {
        Iterator<UUID> it = readyAt.keySet().iterator();
        while (it.hasNext()) {
            if (!aliveMobIds.contains(it.next())) {
                it.remove();
            }
        }
    }

    /** 追跡中のモブ数（テストと診断用）。 */
    public int trackedMobs() {
        return readyAt.size();
    }
}
