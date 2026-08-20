package com.trinityforge.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * メール受信箱の契約（2026-08-19 / W-155）。
 *
 * <p><b>ここで守りたいのは「添付が複製されない」「お詫びの品が消えない」の2点</b>。
 * どちらも実サーバで起きてからでは取り返しがつかない（配った物は回収できない）。
 * 受け取りの原子性は {@link MailStore#claim} の更新行数だけで決まるので、そこを直接叩いて固定する。
 *
 * <p>添付そのものの直列化（{@code ItemStack.serializeItemsAsBytes}）は Bukkit の実装が要るため
 * ここでは踏まない。MockBukkit は未実装 API を SKIPPED に化けさせるので、
 * 踏むと「緑のまま壊れている」状態を作ってしまう（common-traps.md）。
 */
class MailStoreTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private MailStore store;

    @BeforeEach
    void open() throws SQLException {
        store = new MailStore("jdbc:sqlite::memory:");
    }

    @AfterEach
    void close() {
        if (store != null) {
            store.close();
        }
    }

    private static MailMessage mail(UUID recipient, String subject, long expiresAt) {
        return MailMessage.outgoing(recipient, "運営", subject, "お詫びです", List.of(), NOW, expiresAt);
    }

    @Test
    @DisplayName("送った通数だけ受信箱に並ぶ（古い順）")
    void insertedMailShowsUpInTheInbox() throws SQLException {
        UUID player = UUID.randomUUID();
        assertEquals(2, store.insertAll(List.of(
                mail(player, "1通目", NOW + 30 * DAY), mail(player, "2通目", NOW + 30 * DAY))));

        List<MailMessage> inbox = store.inbox(player, NOW);

        assertEquals(2, inbox.size());
        assertEquals("1通目", inbox.get(0).subject());
        assertEquals(2, store.countUnclaimed(player, NOW));
    }

    @Test
    @DisplayName("他人のメールは見えない（宛先で必ず絞る）")
    void theInboxIsScopedToTheRecipient() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        store.insertAll(List.of(mail(a, "Aへ", 0L), mail(b, "Bへ", 0L)));

        assertEquals(1, store.inbox(a, NOW).size());
        assertEquals("Aへ", store.inbox(a, NOW).get(0).subject());
    }

    @Test
    @DisplayName("受け取れるのは1回だけ —— 2回目の claim は false（添付の複製を作らない）")
    void claimingTwiceOnlySucceedsOnce() throws SQLException {
        UUID player = UUID.randomUUID();
        store.insertAll(List.of(mail(player, "報酬", 0L)));
        long id = store.inbox(player, NOW).get(0).id();

        assertTrue(store.claim(id, player, NOW), "1回目は受け取れること");
        assertFalse(store.claim(id, player, NOW + 1),
                "2回目まで true になると、GUI連打や2サーバ同時ログインで添付が複製する");
        assertTrue(store.inbox(player, NOW + 2).isEmpty(), "受取済みは受信箱から消えること");
    }

    @Test
    @DisplayName("宛先が違う相手は受け取れない（idを推測しても他人宛は取れない）")
    void anotherPlayerCannotClaimSomeoneElsesMail() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID thief = UUID.randomUUID();
        store.insertAll(List.of(mail(owner, "報酬", 0L)));
        long id = store.inbox(owner, NOW).get(0).id();

        assertFalse(store.claim(id, thief, NOW));
        assertEquals(1, store.countUnclaimed(owner, NOW), "他人の claim で消えてはいけない");
    }

    @Test
    @DisplayName("期限切れは受信箱に出ず、受け取りもできない")
    void expiredMailIsNeitherListedNorClaimable() throws SQLException {
        UUID player = UUID.randomUUID();
        store.insertAll(List.of(mail(player, "期限切れ", NOW + DAY)));
        long id = store.find(1L).id();

        assertTrue(store.inbox(player, NOW + 2 * DAY).isEmpty());
        assertEquals(0, store.countUnclaimed(player, NOW + 2 * DAY));
        assertFalse(store.claim(id, player, NOW + 2 * DAY));
    }

    @Test
    @DisplayName("期限0は無期限（既定で勝手に失効させない）")
    void zeroExpiryMeansNeverExpires() throws SQLException {
        UUID player = UUID.randomUUID();
        store.insertAll(List.of(mail(player, "無期限", 0L)));

        assertEquals(1, store.inbox(player, NOW + 3650 * DAY).size());
    }

    @Test
    @DisplayName("受け取りを取り消すと未受取へ戻る（渡す直前に失敗しても品が消えない）")
    void unclaimPutsTheMailBack() throws SQLException {
        UUID player = UUID.randomUUID();
        store.insertAll(List.of(mail(player, "報酬", 0L)));
        long id = store.inbox(player, NOW).get(0).id();
        assertTrue(store.claim(id, player, NOW));

        assertTrue(store.unclaim(id, NOW));

        assertEquals(1, store.countUnclaimed(player, NOW), "取り消したら受信箱へ戻ること");
    }

    @Test
    @DisplayName("取り消しは自分が打った受け取りだけを戻す（別経路の受け取りを巻き戻さない）")
    void unclaimOnlyRevertsTheMatchingClaim() throws SQLException {
        UUID player = UUID.randomUUID();
        store.insertAll(List.of(mail(player, "報酬", 0L)));
        long id = store.inbox(player, NOW).get(0).id();
        store.claim(id, player, NOW);

        // 別の時刻で受け取ったことになっている行を、古い時刻で取り消そうとする。
        assertFalse(store.unclaim(id, NOW - 1));
        assertEquals(0, store.countUnclaimed(player, NOW), "巻き戻ってはいけない");
    }

    @Test
    @DisplayName("掃除は古い受取済み／失効ぶんだけ消す（未受取は消さない）")
    void purgeRemovesOnlyOldClaimedAndExpiredMail() throws SQLException {
        UUID player = UUID.randomUUID();
        store.insertAll(List.of(
                mail(player, "受取済み", 0L),
                mail(player, "失効", NOW + DAY),
                mail(player, "未受取", 0L)));
        long claimedId = store.inbox(player, NOW).get(0).id();
        store.claim(claimedId, player, NOW);

        int removed = store.purge(NOW + 8 * DAY, NOW + 8 * DAY);

        assertEquals(2, removed);
        List<MailMessage> left = store.inbox(player, NOW + 9 * DAY);
        assertEquals(1, left.size());
        assertEquals("未受取", left.get(0).subject(), "未受取のメールを掃除で消してはいけない");
    }

    @Test
    @DisplayName("本文・件名・送信者はそのまま往復する")
    void textFieldsRoundTrip() throws SQLException {
        UUID player = UUID.randomUUID();
        store.insertAll(List.of(new MailMessage(0L, player, "Klee319", "お詫び",
                "<gold>不具合のお詫びです</gold>\n2行目", List.of(), NOW, 0L, 0L)));

        MailMessage loaded = store.inbox(player, NOW).get(0);

        assertNotNull(loaded);
        assertEquals("Klee319", loaded.senderName());
        assertEquals("お詫び", loaded.subject());
        assertEquals("<gold>不具合のお詫びです</gold>\n2行目", loaded.body());
        assertFalse(loaded.hasAttachments());
    }
}
