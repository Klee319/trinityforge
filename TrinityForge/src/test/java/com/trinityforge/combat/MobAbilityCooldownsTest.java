package com.trinityforge.combat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * クールダウン台帳の契約。<b>台帳が漏れるとメモリが増え続ける</b>のが一番の懸念で、
 * EliteMobs のインスタンスダンジョンは入場ごとにワールドを作って捨てるため
 * 死亡イベントの取りこぼしは必ず起きる。掃除が効くことを固定する。
 */
class MobAbilityCooldownsTest {

    @Test
    @DisplayName("未記録なら撃てる／撃った直後は撃てない／時間が経てば撃てる")
    void readyFollowsTheClock() {
        AtomicLong now = new AtomicLong(0L);
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns(now::get);
        UUID mob = UUID.randomUUID();

        assertTrue(cooldowns.ready(mob, "slam"));
        cooldowns.arm(mob, "slam", 5_000L);
        assertFalse(cooldowns.ready(mob, "slam"));

        now.set(4_999L);
        assertFalse(cooldowns.ready(mob, "slam"));
        now.set(5_000L);
        assertTrue(cooldowns.ready(mob, "slam"));
    }

    @Test
    @DisplayName("技ごとに独立（1つ撃っても他の技は撃てる）")
    void perAbilityIndependence() {
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns(() -> 0L);
        UUID mob = UUID.randomUUID();

        cooldowns.arm(mob, "slam", 5_000L);

        assertFalse(cooldowns.ready(mob, "slam"));
        assertTrue(cooldowns.ready(mob, "beam"));
    }

    @Test
    @DisplayName("モブごとに独立（同種の別個体が同時に撃てる）")
    void perMobIndependence() {
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns(() -> 0L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        cooldowns.arm(first, "slam", 5_000L);

        assertTrue(cooldowns.ready(second, "slam"));
    }

    @Test
    @DisplayName("purge は生存集合に無いモブを落とす（死亡イベント取りこぼしの回収）")
    void purgeDropsDeadMobs() {
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns(() -> 0L);
        UUID alive = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        cooldowns.arm(alive, "slam", 5_000L);
        cooldowns.arm(gone, "slam", 5_000L);
        assertEquals(2, cooldowns.trackedMobs());

        cooldowns.purge(Set.of(alive));

        assertEquals(1, cooldowns.trackedMobs());
        assertFalse(cooldowns.ready(alive, "slam"), "生きている側の記録は消さない");
        assertTrue(cooldowns.ready(gone, "slam"), "消えた側は初見扱いへ戻る");
    }

    @Test
    @DisplayName("forget は単体の記録を落とす")
    void forgetDropsOneMob() {
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns(() -> 0L);
        UUID mob = UUID.randomUUID();
        cooldowns.arm(mob, "slam", 5_000L);

        cooldowns.forget(mob);

        assertEquals(0, cooldowns.trackedMobs());
        assertTrue(cooldowns.ready(mob, "slam"));
    }
}
