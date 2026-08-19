package com.trinityforge.mail;

import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * メール（{@link MailMessage}）を {@code player_progression.db} の {@code mail} 表へ永続化する
 * （2026-08-19 / W-155）。
 *
 * <p><b>なぜ共有 SQLite なのか。</b> 要件は「送信者がログアウト中でも送れる」＝受信箱はプレイヤーの
 * PDC に置けない（PDC はオンラインのプレイヤーにしか書けない）。この DB がある
 * {@code plugins/TrinityForge/} は NTFS ジャンクションで全バックエンドに共有されているので、
 * ここへ書けば<b>メイン⇄資源のどちらへログインしても同じ受信箱</b>が見える。
 * {@code DailyExpWindowStore} / {@code RankingMirrorStore} と同じく<b>独立コネクション</b>で開く。
 *
 * <p><b>受け取りの原子性がこの機能の肝。</b> 「未受取か調べる → 渡す → 受取済みにする」と書くと、
 * 2つのバックエンドに同時ログインした場合や、GUI を連打した場合に<b>同じ添付が2回渡って複製</b>する。
 * {@link #claim} は {@code UPDATE ... WHERE id=? AND claimed_at=0} の<b>1文</b>で、
 * 更新行数が 1 だったときだけ「自分が受け取った」と判定する（check-then-act を作らない）。
 * {@code transaction_mode=IMMEDIATE} も同じ理由で必須
 * （{@code SharedSqliteConcurrencyTest} が実測した前提。{@code BEGIN DEFERRED} だと
 * {@code SQLITE_BUSY_SNAPSHOT} が {@code busy_timeout} をすり抜ける）。
 *
 * <p>JDBC の {@link Connection} は thread-safe ではないので全メソッドを {@code synchronized} にする。
 * 呼び出しは非同期スレッドから来る（{@link MailService}）。
 */
public final class MailStore implements AutoCloseable {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS mail ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " recipient_uuid TEXT NOT NULL,"
            + " sender_name TEXT NOT NULL,"
            + " subject TEXT NOT NULL,"
            + " body TEXT NOT NULL,"
            + " items BLOB,"
            + " created_at INTEGER NOT NULL,"
            + " expires_at INTEGER NOT NULL,"
            + " claimed_at INTEGER NOT NULL DEFAULT 0"
            + ")";

    /** 受信箱の引き方（宛先＋未受取）にそのまま効く索引。人数×通数が増えても全走査にしない。 */
    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_mail_inbox ON mail(recipient_uuid, claimed_at)";

    private static final String COLUMNS =
            "id, recipient_uuid, sender_name, subject, body, items, created_at, expires_at, claimed_at";

    private static final String INSERT =
            "INSERT INTO mail (recipient_uuid, sender_name, subject, body, items, created_at, expires_at,"
            + " claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0)";

    private static final String SELECT_INBOX =
            "SELECT " + COLUMNS + " FROM mail WHERE recipient_uuid = ? AND claimed_at = 0"
            + " AND (expires_at = 0 OR expires_at > ?) ORDER BY created_at ASC, id ASC";

    private static final String COUNT_INBOX =
            "SELECT COUNT(*) FROM mail WHERE recipient_uuid = ? AND claimed_at = 0"
            + " AND (expires_at = 0 OR expires_at > ?)";

    private static final String SELECT_ONE =
            "SELECT " + COLUMNS + " FROM mail WHERE id = ?";

    /**
     * 受け取り。<b>1文で「未受取なら受取済みにする」</b>を済ませる。
     * 期限も同じ条件に入れる（期限切れを受け取れてしまうと、失効の意味が無くなる）。
     */
    private static final String CLAIM =
            "UPDATE mail SET claimed_at = ? WHERE id = ? AND recipient_uuid = ? AND claimed_at = 0"
            + " AND (expires_at = 0 OR expires_at > ?)";

    /** 渡す直前に失敗したときの取り消し（受け取ったことにしたのにアイテムを渡せなかった場合）。 */
    private static final String UNCLAIM =
            "UPDATE mail SET claimed_at = 0 WHERE id = ? AND claimed_at = ?";

    private static final String DELETE_CLAIMED_BEFORE =
            "DELETE FROM mail WHERE claimed_at > 0 AND claimed_at < ?";

    private static final String DELETE_EXPIRED =
            "DELETE FROM mail WHERE expires_at > 0 AND expires_at < ?";

    private final Connection conn;

    public MailStore(String jdbcUrl) throws SQLException {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Connection c = DriverManager.getConnection(jdbcUrl, immediateTransactionProperties());
        try {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout = 5000");
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = NORMAL");
                s.executeUpdate(CREATE_TABLE);
                s.executeUpdate(CREATE_INDEX);
            }
        } catch (SQLException | RuntimeException e) {
            try { c.close(); } catch (Exception ignored) { /* 元の例外を潰さない */ }
            throw e;
        }
        this.conn = c;
    }

    /**
     * まとめて送る（一斉送信は人数ぶんの行になるので、1トランザクションで入れる）。
     *
     * @return 実際に入った通数
     */
    public synchronized int insertAll(Collection<MailMessage> messages) throws SQLException {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement stmt = conn.prepareStatement(INSERT)) {
            int inserted = 0;
            for (MailMessage message : messages) {
                if (message == null || message.recipientId() == null) {
                    continue;
                }
                stmt.setString(1, message.recipientId().toString());
                stmt.setString(2, message.senderName());
                stmt.setString(3, message.subject());
                stmt.setString(4, message.body());
                stmt.setBytes(5, encode(message.attachments()));
                stmt.setLong(6, message.createdAtMillis());
                stmt.setLong(7, message.expiresAtMillis());
                stmt.executeUpdate();
                inserted++;
            }
            conn.commit();
            return inserted;
        } catch (SQLException | RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) { /* 元の例外を潰さない */ }
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /** 未受取・未失効のメールを古い順に返す。 */
    public synchronized List<MailMessage> inbox(UUID recipientId, long nowMillis) throws SQLException {
        Objects.requireNonNull(recipientId, "recipientId");
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_INBOX)) {
            stmt.setString(1, recipientId.toString());
            stmt.setLong(2, nowMillis);
            return readAll(stmt);
        }
    }

    /** 未受取・未失効の通数（ログイン時の通知用。全文を読まない）。 */
    public synchronized int countUnclaimed(UUID recipientId, long nowMillis) throws SQLException {
        Objects.requireNonNull(recipientId, "recipientId");
        try (PreparedStatement stmt = conn.prepareStatement(COUNT_INBOX)) {
            stmt.setString(1, recipientId.toString());
            stmt.setLong(2, nowMillis);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 1通を id で引く（受け取り確定後に添付を読むため）。無ければ {@code null}。 */
    public synchronized MailMessage find(long id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_ONE)) {
            stmt.setLong(1, id);
            List<MailMessage> rows = readAll(stmt);
            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    /**
     * 受け取りを確定する。<b>戻り値が true のときだけ添付を渡してよい</b>。
     *
     * <p>更新行数で判定するので、同時に2箇所から呼ばれても true になるのは1回だけ
     * （＝添付が複製されない）。
     */
    public synchronized boolean claim(long id, UUID recipientId, long nowMillis) throws SQLException {
        Objects.requireNonNull(recipientId, "recipientId");
        try (PreparedStatement stmt = conn.prepareStatement(CLAIM)) {
            stmt.setLong(1, nowMillis);
            stmt.setLong(2, id);
            stmt.setString(3, recipientId.toString());
            stmt.setLong(4, nowMillis);
            return stmt.executeUpdate() == 1;
        }
    }

    /**
     * 受け取りを取り消して未受取へ戻す（渡す直前に受取者が落ちた等）。
     *
     * <p>{@code claimedAtMillis} を条件に入れるのは、取り消す前に別経路が受け取り直していた場合に
     * <b>そちらの受け取りまで巻き戻さない</b>ため。
     */
    public synchronized boolean unclaim(long id, long claimedAtMillis) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(UNCLAIM)) {
            stmt.setLong(1, id);
            stmt.setLong(2, claimedAtMillis);
            return stmt.executeUpdate() == 1;
        }
    }

    /**
     * 掃除。受取済みで {@code claimedBefore} より古い行と、失効して {@code expiredBefore} より
     * 古い行を消す。行を残し続けると受信箱の引きが重くなるだけで誰の役にも立たない。
     *
     * @return 消した行数
     */
    public synchronized int purge(long claimedBefore, long expiredBefore) throws SQLException {
        int removed = 0;
        try (PreparedStatement stmt = conn.prepareStatement(DELETE_CLAIMED_BEFORE)) {
            stmt.setLong(1, claimedBefore);
            removed += stmt.executeUpdate();
        }
        try (PreparedStatement stmt = conn.prepareStatement(DELETE_EXPIRED)) {
            stmt.setLong(1, expiredBefore);
            removed += stmt.executeUpdate();
        }
        return removed;
    }

    private static List<MailMessage> readAll(PreparedStatement stmt) throws SQLException {
        List<MailMessage> out = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID recipient;
                try {
                    recipient = UUID.fromString(rs.getString(2));
                } catch (IllegalArgumentException broken) {
                    continue; // 壊れた行は読み飛ばす（受信箱ごと開けなくなる方が困る）
                }
                out.add(new MailMessage(
                        rs.getLong(1), recipient, rs.getString(3), rs.getString(4), rs.getString(5),
                        decode(rs.getBytes(6)), rs.getLong(7), rs.getLong(8), rs.getLong(9)));
            }
        }
        return out;
    }

    /**
     * 添付をバイト列にする。{@code ItemStack.serializeItemsAsBytes} は
     * <b>PDC もカスタムモデルデータも含めて</b> Bukkit 側が面倒を見るので、TF のカタログ品や
     * 品質つきの装備をそのまま送れる（自前で組み直すと必ずどれかの情報が落ちる）。
     */
    private static byte[] encode(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return ItemStack.serializeItemsAsBytes(items.toArray(new ItemStack[0]));
    }

    private static List<ItemStack> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(bytes);
        List<ItemStack> out = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                out.add(item);
            }
        }
        return out;
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (Exception ignored) {
            // シャットダウン経路。ここで投げても復旧手段が無い。
        }
    }

    /**
     * {@code BEGIN IMMEDIATE} を強制する。理由は {@code DailyExpWindowStore} と同じで、
     * 資源サーバ分離により<b>2 プロセスが同じファイルを触る</b>ため。
     */
    private static Properties immediateTransactionProperties() {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        return config.toProperties();
    }
}
