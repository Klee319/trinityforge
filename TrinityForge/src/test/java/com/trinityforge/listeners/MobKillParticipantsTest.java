package com.trinityforge.listeners;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 討伐図鑑の参加者台帳（2026-08-22、実サーバ報告「図鑑のモブにラストキルしか反映されない」）。
 *
 * <p>ここで縛るのは<b>算数と寿命</b>だけ。「誰を参加者と見なすか」の判定は
 * {@code CollectionListener#onMobDamaged} 側にあり、そちらは MockBukkit を通した
 * {@code CollectionListenerGuardsTest} で固定してある。
 */
class MobKillParticipantsTest {

    private static final UUID VICTIM = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @Test
    @DisplayName("削った全員を返す(とどめを刺していない人こそが直したかった対象)")
    void everyAttackerIsReturned() {
        MobKillParticipants participants = new MobKillParticipants();
        participants.record(VICTIM, ALICE, 1_000L);
        participants.record(VICTIM, BOB, 2_000L);

        assertEquals(Set.of(ALICE, BOB), participants.consume(VICTIM, 3_000L));
    }

    @Test
    @DisplayName("同じ人が何度殴っても1回しか数えない")
    void repeatedHitsCollapse() {
        MobKillParticipants participants = new MobKillParticipants();
        for (int i = 0; i < 50; i++) {
            participants.record(VICTIM, ALICE, 1_000L + i);
        }

        assertEquals(Set.of(ALICE), participants.consume(VICTIM, 2_000L));
    }

    @Test
    @DisplayName("consume は台帳から外す(同じ死亡で二重に配らない)")
    void consumeRemoves() {
        MobKillParticipants participants = new MobKillParticipants();
        participants.record(VICTIM, ALICE, 1_000L);

        assertEquals(Set.of(ALICE), participants.consume(VICTIM, 1_500L));
        assertTrue(participants.consume(VICTIM, 1_600L).isEmpty(), "2回目は空");
        assertEquals(0, participants.trackedCount());
    }

    @Test
    @DisplayName("期限切れの参加は返さない(別の戦闘まで引き継がない)")
    void expiredParticipationIsDropped() {
        MobKillParticipants participants = new MobKillParticipants();
        participants.record(VICTIM, ALICE, 1_000L);

        long tooLate = 1_000L + MobKillParticipants.PARTICIPATION_TTL_MILLIS + 1L;
        assertTrue(participants.consume(VICTIM, tooLate).isEmpty());
    }

    @Test
    @DisplayName("期限切れのあとの記録は新しい戦闘として始まる(古い参加者を蘇らせない)")
    void recordAfterExpiryStartsFresh() {
        MobKillParticipants participants = new MobKillParticipants();
        participants.record(VICTIM, ALICE, 1_000L);

        long tooLate = 1_000L + MobKillParticipants.PARTICIPATION_TTL_MILLIS + 1L;
        participants.record(VICTIM, BOB, tooLate);

        assertEquals(Set.of(BOB), participants.consume(VICTIM, tooLate + 1L),
                "期限切れの行に追記すると、去っていった人が新しい討伐で復活してしまう");
    }

    @Test
    @DisplayName("殴られ続けている間は期限が延びる(長期戦で参加が消えない)")
    void ongoingCombatKeepsParticipation() {
        MobKillParticipants participants = new MobKillParticipants();
        long now = 1_000L;
        participants.record(VICTIM, ALICE, now);
        // TTL の 8 割ずつ間を空けて殴り続ける。触るたびに期限が延びなければ途中で消える。
        for (int i = 0; i < 5; i++) {
            now += (long) (MobKillParticipants.PARTICIPATION_TTL_MILLIS * 0.8);
            participants.record(VICTIM, ALICE, now);
        }

        assertEquals(Set.of(ALICE), participants.consume(VICTIM, now + 1L));
    }

    @Test
    @DisplayName("追跡数は上限で頭打ちになる(死なずに消えたモブが積み上がらない)")
    void trackedVictimsAreBounded() {
        MobKillParticipants participants = new MobKillParticipants();
        for (int i = 0; i < 10_000; i++) {
            participants.record(UUID.randomUUID(), ALICE, 1_000L);
        }

        assertTrue(participants.trackedCount() <= 4_096,
                "上限が効いていない: " + participants.trackedCount());
    }

    @Test
    @DisplayName("clear で消える(プレイヤーの死亡など、図鑑と無関係な行を残さない)")
    void clearRemoves() {
        MobKillParticipants participants = new MobKillParticipants();
        participants.record(VICTIM, ALICE, 1_000L);

        participants.clear(VICTIM);

        assertTrue(participants.consume(VICTIM, 1_100L).isEmpty());
    }

    @Test
    @DisplayName("null は黙って無視する(呼び出し側に null チェックを散らかさない)")
    void nullsAreIgnored() {
        MobKillParticipants participants = new MobKillParticipants();
        participants.record(null, ALICE, 1_000L);
        participants.record(VICTIM, null, 1_000L);
        participants.clear(null);

        assertTrue(participants.consume(null, 1_000L).isEmpty());
        assertEquals(0, participants.trackedCount());
    }
}
