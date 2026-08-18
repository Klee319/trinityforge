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

    private static final String SELECT =
            "SELECT skill_id, amount, updated_at FROM daily_exp_window WHERE player_uuid = ?";

    private static final String UPSERT =
            "INSERT INTO daily_exp_window (player_uuid, skill_id, amount, updated_at)\n"
            + "VALUES (?, ?, ?, ?)\n"
            + "ON CONFLICT(player_uuid, skill_id) DO UPDATE SET\n"
            + "    amount     = excluded.amount,\n"
            + "    updated_at = excluded.updated_at";

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
                        rs.getString(1), rs.getDouble(2), rs.getLong(3)));
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
