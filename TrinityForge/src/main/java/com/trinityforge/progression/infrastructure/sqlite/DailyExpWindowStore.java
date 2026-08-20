package com.trinityforge.progression.infrastructure.sqlite;

import com.trinityforge.progression.DailyExpDiminishing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 日次EXP逓減の蓄積（{@link DailyExpDiminishing} の指数移動窓）を
 * {@code player_progression.db} の {@code daily_exp_window} 表へ永続化する。
 *
 * <p><b>なぜ永続化するのか。</b> 2026-07-31 の導入時は「サーバ再起動を選べるのは運営だけだから
 * 悪用経路にならない」と考えて意図的にメモリのみにしていたが、実際には
 * 退出時に {@code forget} で捨てていたため<b>プレイヤーが再ログインするだけで等倍へ戻せた</b>。
 * 逓減が効き始めた瞬間に入り直せばよいので、「1日の稼ぎ総量を薄める」という目的が
 * 1ミリも達成されていなかった（2026-08-18 ユーザー指摘「これは直さないとダメ」）。
 *
 * <p><b>接続は独立。</b> {@code SqliteProgressionRepository} とは別コネクションで同じファイルを開く
 * （{@code RankingMirrorStore} と同じ形）。この DB がある {@code plugins/TrinityForge/} は
 * NTFS ジャンクションで全バックエンドに共有されているので、ここへ書けば
 * <b>メイン⇄資源のサーバ移動でも逓減が引き継がれる</b>。
 * {@code transaction_mode=IMMEDIATE} は必須 —— 下の {@link #save} は read-modify-write なので、
 * {@code BEGIN DEFERRED} だと {@code SQLITE_BUSY_SNAPSHOT} が {@code busy_timeout} をすり抜ける
 * （{@code SharedSqliteConcurrencyTest} が実測済みの前提）。
 *
 * <p><b>書き込みは「現在時刻へ減衰させた上での大きい方」を残す（単純上書きではない）。</b>
 * Velocity のサーバ移動では<b>移動先の join が移動元の quit より先に起きる</b>ため、
 * 移動先が読むのは必ず少し古い値になる。素直に上書きすると、あとから来た移動元の quit 保存を
 * 移動先の（古い値に基づく）保存が潰し、<b>サーバを往復するだけで逓減が消える</b>。
 * 蓄積は「稼ぐと増える／時間で減る」だけなので、両方を同じ現在時刻まで減衰させてから
 * 大きい方を採れば、遅れて反映されることはあっても<b>後退はしない</b>。
 *
 * <p><b>スレッド安全性。</b> JDBC の {@link Connection} は thread-safe ではないので全メソッドを
 * {@code synchronized} にする。保存はログアウト時と定期保存（どちらも非同期スレッド）、
 * 読み込みは join 直後の非同期タスクから来る。
 */
public final class DailyExpWindowStore implements AutoCloseable {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS daily_exp_window (\n"
            + "    player_uuid TEXT NOT NULL,\n"
            + "    skill_id    TEXT NOT NULL,\n"
            + "    amount      REAL NOT NULL,\n"
            + "    updated_at  INTEGER NOT NULL,\n"
            + "    PRIMARY KEY (player_uuid, skill_id)\n"
            + ")";

    /**
     * 逓減の発動時刻の列(2026-08-19 / W-154)。<b>既存DBには無いので後付けする</b>
     * ({@link #CREATE_TABLE} は {@code IF NOT EXISTS} なので、既に表がある環境では列定義の変更が
     * 一切反映されない —— ここを忘れると「新品の環境だけ動いて実サーバでは動かない」という
     * 一番気づきにくい形で壊れる)。
     */
    private static final String ADD_LOCKED_AT_COLUMN =
            "ALTER TABLE daily_exp_window ADD COLUMN locked_at INTEGER NOT NULL DEFAULT 0";

    /** 一括解除(W-154)の「適用済みの合言葉」を覚えておく表。 */
    private static final String CREATE_META_TABLE =
            "CREATE TABLE IF NOT EXISTS daily_exp_meta (\n"
            + "    key   TEXT PRIMARY KEY,\n"
            + "    value TEXT NOT NULL\n"
            + ")";

    private static final String RESET_ID_KEY = "reset_id";

    private static final String SELECT =
            "SELECT skill_id, amount, updated_at, locked_at FROM daily_exp_window "
            + "WHERE player_uuid = ?";

    private static final String UPSERT =
            "INSERT INTO daily_exp_window (player_uuid, skill_id, amount, updated_at, locked_at)\n"
            + "VALUES (?, ?, ?, ?, ?)\n"
            + "ON CONFLICT(player_uuid, skill_id) DO UPDATE SET\n"
            + "    amount     = excluded.amount,\n"
            + "    updated_at = excluded.updated_at,\n"
            + "    locked_at  = excluded.locked_at";

    private static final String DELETE_ROW =
            "DELETE FROM daily_exp_window WHERE player_uuid = ? AND skill_id = ?";

    private static final String DELETE_PLAYER =
            "DELETE FROM daily_exp_window WHERE player_uuid = ?";

    /**
     * これ未満の蓄積は行ごと捨てる。逓減の刻み（既定 100,000 EXP）に対して 1 EXP は
     * 倍率に一切影響しないので、減衰しきった行を残し続けて表を太らせる意味が無い。
     */
    private static final double NEGLIGIBLE_AMOUNT = 1.0;

    private final Connection conn;
    private final PreparedStatement stmtSelect;
    private final PreparedStatement stmtUpsert;
    private final PreparedStatement stmtDeleteRow;
    private final PreparedStatement stmtDeletePlayer;
    private final LongSupplier clockMillis;

    public DailyExpWindowStore(String jdbcUrl) throws SQLException {
        this(jdbcUrl, System::currentTimeMillis);
    }

    /**
     * 時刻源を差し替えられる構築子（テスト用）。減衰の計算に時刻が入るので、
     * {@link com.trinityforge.progression.DailyExpDiminishing} 側と<b>同じ時計</b>を渡さないと
     * 「保存した瞬間に何十時間ぶんも減衰した」ことになって検証が成立しない。
     */
    public DailyExpWindowStore(String jdbcUrl, LongSupplier clockMillis) throws SQLException {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        Connection c = DriverManager.getConnection(jdbcUrl, immediateTransactionProperties());
        try {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA busy_timeout = 5000");
                s.execute("PRAGMA journal_mode = WAL");
                s.execute("PRAGMA synchronous = NORMAL");
                s.executeUpdate(CREATE_TABLE);
                s.executeUpdate(CREATE_META_TABLE);
                // 既存DBへの列追加。2回目以降は「duplicate column name」で失敗するのが正常。
                // SQLite の ALTER TABLE は IF NOT EXISTS を持たないので、例外で判定するしかない。
                try {
                    s.executeUpdate(ADD_LOCKED_AT_COLUMN);
                } catch (SQLException alreadyMigrated) {
                    // 列は既にある。何もしない。
                }
            }
            this.stmtSelect = c.prepareStatement(SELECT);
            this.stmtUpsert = c.prepareStatement(UPSERT);
            this.stmtDeleteRow = c.prepareStatement(DELETE_ROW);
            this.stmtDeletePlayer = c.prepareStatement(DELETE_PLAYER);
        } catch (SQLException | RuntimeException e) {
            try { c.close(); } catch (Exception ignored) { /* 元の例外を潰さない */ }
            throw e;
        }
        this.conn = c;
    }

    /**
     * そのプレイヤーの保存済み蓄積を、<b>保存時刻のまま</b>返す（減衰は
     * {@link DailyExpDiminishing#restore} 側で1回だけ掛ける）。行が無ければ空。
     */
    public synchronized List<DailyExpDiminishing.WindowSnapshot> load(UUID playerId)
            throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        stmtSelect.setString(1, playerId.toString());
        List<DailyExpDiminishing.WindowSnapshot> out = new ArrayList<>();
        try (ResultSet rs = stmtSelect.executeQuery()) {
            while (rs.next()) {
                out.add(new DailyExpDiminishing.WindowSnapshot(
                        rs.getString(1), rs.getDouble(2), rs.getLong(3), rs.getLong(4)));
            }
        }
        return out;
    }

    /**
     * 蓄積を書き込む。既存行があれば<b>両方を現在時刻へ減衰させた上で大きい方</b>を残す
     * （理由はクラス javadoc の「後退させない」）。減衰しきった行は消す。
     *
     * @param playerId     プレイヤー UUID
     * @param entries      {@link DailyExpDiminishing#snapshot(UUID)} の結果。空なら何もしない
     * @param windowMillis 指数減衰の時定数（{@code daily-diminishing.window-hours}）
     */
    public synchronized void save(UUID playerId,
                                  Collection<DailyExpDiminishing.WindowSnapshot> entries,
                                  double windowMillis) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        if (entries == null || entries.isEmpty()) {
            return;
        }
        double window = windowMillis > 0.0 && Double.isFinite(windowMillis) ? windowMillis : 1.0;
        long now = clockMillis.getAsLong();
        Map<String, DailyExpDiminishing.WindowSnapshot> existing = new HashMap<>();
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            for (DailyExpDiminishing.WindowSnapshot row : load(playerId)) {
                existing.put(row.skillId(), row);
            }
            for (DailyExpDiminishing.WindowSnapshot entry : entries) {
                if (entry == null || entry.skillId() == null || entry.skillId().isBlank()) {
                    continue;
                }
                double incoming = decayTo(now, entry, window);
                DailyExpDiminishing.WindowSnapshot stored = existing.get(entry.skillId());
                double current = stored == null ? 0.0 : decayTo(now, stored, window);
                double keep = Math.max(incoming, current);
                if (!Double.isFinite(keep) || keep < NEGLIGIBLE_AMOUNT) {
                    if (stored != null) {
                        stmtDeleteRow.setString(1, playerId.toString());
                        stmtDeleteRow.setString(2, entry.skillId());
                        stmtDeleteRow.executeUpdate();
                    }
                    continue;
                }
                stmtUpsert.setString(1, playerId.toString());
                stmtUpsert.setString(2, entry.skillId());
                stmtUpsert.setDouble(3, keep);
                stmtUpsert.setLong(4, now);
                stmtUpsert.setLong(5, earlierLock(entry, stored));
                stmtUpsert.executeUpdate();
            }
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) { /* 元の例外を潰さない */ }
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    /** そのプレイヤーの行を全部消す（進行リセット用。呼ばないと古い蓄積が残り続ける）。 */
    public synchronized void delete(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        stmtDeletePlayer.setString(1, playerId.toString());
        stmtDeletePlayer.executeUpdate();
    }

    /**
     * 逓減の発動時刻は<b>早い方</b>を残す(2026-08-19 / W-154)。
     *
     * <p>蓄積量と違って「大きい方」ではない。遅い方を採ると、メイン⇄資源のサーバ移動や再ログインの
     * たびに期限が後ろへずれて、<b>24時間経っても解除されない</b>。0 は「未発動」なので候補から外す。
     */
    private static long earlierLock(DailyExpDiminishing.WindowSnapshot incoming,
                                    DailyExpDiminishing.WindowSnapshot stored) {
        long a = incoming == null ? 0L : incoming.lockedAtMillis();
        long b = stored == null ? 0L : stored.lockedAtMillis();
        if (a <= 0L) {
            return Math.max(0L, b);
        }
        if (b <= 0L) {
            return a;
        }
        return Math.min(a, b);
    }

    /**
     * 合言葉が前回適用したものと違えば、<b>全プレイヤーの蓄積を1回だけ全消し</b>する
     * (2026-08-19 / W-154、ユーザー指示「修正時に全員の既にかかっているロックを解除したい」)。
     *
     * <p>適用済みの合言葉をDBへ残すので、再起動を繰り返しても2度は消えない。
     * <b>フラグ(true/false)にしなかった理由</b>: true のままだと再起動のたびに消えて逓減が
     * 永久に効かなくなり、false へ戻し忘れてもその事故が誰にも気づかれない。
     *
     * @return 実際に削除した行数。要求が無い/適用済みなら {@code -1}
     */
    public synchronized int applyResetIfRequested(String resetId) throws SQLException {
        if (resetId == null || resetId.isBlank()) {
            return -1;
        }
        String applied = null;
        try (PreparedStatement select =
                     conn.prepareStatement("SELECT value FROM daily_exp_meta WHERE key = ?")) {
            select.setString(1, RESET_ID_KEY);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    applied = rs.getString(1);
                }
            }
        }
        if (resetId.equals(applied)) {
            return -1;
        }
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            int deleted;
            try (Statement s = conn.createStatement()) {
                deleted = s.executeUpdate("DELETE FROM daily_exp_window");
            }
            try (PreparedStatement upsert = conn.prepareStatement(
                    "INSERT INTO daily_exp_meta (key, value) VALUES (?, ?)\n"
                    + "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                upsert.setString(1, RESET_ID_KEY);
                upsert.setString(2, resetId);
                upsert.executeUpdate();
            }
            conn.commit();
            return deleted;
        } catch (SQLException | RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) { /* 元の例外を潰さない */ }
            throw e;
        } finally {
            conn.setAutoCommit(autoCommit);
        }
    }

    private static double decayTo(long now, DailyExpDiminishing.WindowSnapshot row, double window) {
        if (row == null || !Double.isFinite(row.amount()) || row.amount() <= 0.0) {
            return 0.0;
        }
        long elapsed = Math.max(0L, now - row.updatedAtMillis());
        if (elapsed == 0L) {
            return row.amount();
        }
        return row.amount() * Math.exp(-((double) elapsed) / window);
    }

    @Override
    public synchronized void close() {
        closeQuietly(stmtSelect);
        closeQuietly(stmtUpsert);
        closeQuietly(stmtDeleteRow);
        closeQuietly(stmtDeletePlayer);
        closeQuietly(conn);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // シャットダウン経路。ここで投げても復旧手段が無い。
        }
    }

    /**
     * {@code BEGIN IMMEDIATE} を強制する。理由は
     * {@code SqliteProgressionRepository#immediateTransactionProperties} と同じで、
     * 資源サーバ分離により<b>2 プロセスが同じファイルを触る</b>ため。
     */
    private static Properties immediateTransactionProperties() {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        return config.toProperties();
    }
}
