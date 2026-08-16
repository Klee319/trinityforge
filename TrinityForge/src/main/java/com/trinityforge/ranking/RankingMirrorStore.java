package com.trinityforge.ranking;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * ランキング用の集計値を {@code player_progression.db} の {@code player_ranking_stats} 表へ写す。
 *
 * <p><b>なぜ写すのか。</b> スキルレベルは同じ SQLite ファイルに入っており、その
 * {@code plugins/TrinityForge/} は NTFS ディレクトリジャンクションで全バックエンド（メイン／資源）に
 * 共有されているため、どのサーバから読んでも自動的に同じ値になる。しかし
 * <b>図鑑登録数とグリフ解放数はプレイヤー PDC、討伐数はバニラ統計</b>にしかなく、どちらも
 * 「そのサーバにログイン中の本人」からしか読めない。
 * <ul>
 *   <li>Bukkit には {@code OfflinePlayer} の PDC を読む API が存在しない。</li>
 *   <li>バニラ統計ファイルはバックエンドごとに別のワールドフォルダにある。</li>
 *   <li>HuskSync はサーバ移動時に<b>本人へ</b>流し込むだけで、他サーバのプラグインが
 *       他人の値を引く経路は提供していない。</li>
 * </ul>
 * つまりミラーが無いと「資源サーバのランキングにメインにいるプレイヤーが出ない／古い値で出る」。
 * 共有 DB へ写しておけば、どのバックエンドからでも・オフラインでも同じ値を引ける。
 *
 * <p><b>単調増加でしか更新しない（ON CONFLICT で MAX を取る）。</b> HuskSync は
 * {@code PlayerJoinEvent} より<b>後</b>にデータを流し込む。素直に上書きすると
 * 「同期前のローカル値（小さい）が共有値を潰す」事故が起きるが、この 4 項目はいずれも
 * 減らない量なので MAX を取れば<b>反映が遅れることはあっても後退はしない</b>。
 * 逆に言うと、意図的にリセットしたときは {@link #delete(UUID)} を呼ばないと古い値が残り続ける。
 *
 * <p><b>接続は独立。</b> 進行データ用の {@code SqliteProgressionRepository} とは別コネクションで
 * 同じファイルを開く。2 プロセス・複数コネクションでの同時アクセスが壊れないことは
 * {@code SharedSqliteConcurrencyTest} が実測済みで、その前提である
 * {@code transaction_mode=IMMEDIATE} をここでも同じように指定している
 * （{@code BEGIN DEFERRED} だと {@code SQLITE_BUSY_SNAPSHOT} が {@code busy_timeout} をすり抜ける）。
 *
 * <p><b>スレッド安全性。</b> JDBC の {@link Connection} は thread-safe ではないため全メソッドを
 * {@code synchronized} にしている。書き込みは非同期スレッド、読み出しはプレースホルダ解決
 * （メインスレッドのこともある）から来るので、この直列化が必要。
 */
public final class RankingMirrorStore implements AutoCloseable {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS player_ranking_stats (\n"
            + "    player_uuid      TEXT PRIMARY KEY,\n"
            + "    player_name      TEXT NOT NULL,\n"
            + "    collection_items INTEGER NOT NULL DEFAULT 0,\n"
            + "    collection_mobs  INTEGER NOT NULL DEFAULT 0,\n"
            + "    glyphs_unlocked  INTEGER NOT NULL DEFAULT 0,\n"
            + "    mob_kills        INTEGER NOT NULL DEFAULT 0,\n"
            + "    updated_at       INTEGER NOT NULL\n"
            + ")";

    // 名前だけは常に最新で上書きする(改名しても表示が古いままにならないように)。
    // 数値 4 項目は MAX で単調増加。クラス javadoc の「後退させない」がここに実装されている。
    private static final String UPSERT =
            "INSERT INTO player_ranking_stats\n"
            + "    (player_uuid, player_name, collection_items, collection_mobs,\n"
            + "     glyphs_unlocked, mob_kills, updated_at)\n"
            + "VALUES (?, ?, ?, ?, ?, ?, ?)\n"
            + "ON CONFLICT(player_uuid) DO UPDATE SET\n"
            + "    player_name      = excluded.player_name,\n"
            + "    collection_items = MAX(player_ranking_stats.collection_items, excluded.collection_items),\n"
            + "    collection_mobs  = MAX(player_ranking_stats.collection_mobs,  excluded.collection_mobs),\n"
            + "    glyphs_unlocked  = MAX(player_ranking_stats.glyphs_unlocked,  excluded.glyphs_unlocked),\n"
            + "    mob_kills        = MAX(player_ranking_stats.mob_kills,        excluded.mob_kills),\n"
            + "    updated_at       = excluded.updated_at";

    private static final String SELECT =
            "SELECT collection_items, collection_mobs, glyphs_unlocked, mob_kills\n"
            + "  FROM player_ranking_stats WHERE player_uuid = ?";

    private static final String DELETE =
            "DELETE FROM player_ranking_stats WHERE player_uuid = ?";

    private final Connection conn;
    private final PreparedStatement stmtUpsert;
    private final PreparedStatement stmtSelect;
    private final PreparedStatement stmtDelete;
    private final LongSupplier clockMillis;

    public RankingMirrorStore(String jdbcUrl) throws SQLException {
        this(jdbcUrl, System::currentTimeMillis);
    }

    RankingMirrorStore(String jdbcUrl, LongSupplier clockMillis) throws SQLException {
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
            this.stmtUpsert = c.prepareStatement(UPSERT);
            this.stmtSelect = c.prepareStatement(SELECT);
            this.stmtDelete = c.prepareStatement(DELETE);
        } catch (SQLException | RuntimeException e) {
            try { c.close(); } catch (Exception ignored) { /* 元の例外を潰さない */ }
            throw e;
        }
        this.conn = c;
    }

    /**
     * 集計値を書き込む。既存行がある場合、数値 4 項目は<b>大きい方だけが残る</b>。
     *
     * @param playerId プレイヤー UUID
     * @param playerName 表示名（ランキングの氏名列用。常に最新で上書きされる）
     * @param stats 書き込む集計値
     */
    public synchronized void save(UUID playerId, String playerName, RankingStats stats) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(stats, "stats");
        stmtUpsert.setString(1, playerId.toString());
        stmtUpsert.setString(2, playerName == null ? "" : playerName);
        stmtUpsert.setInt(3, stats.collectionItems());
        stmtUpsert.setInt(4, stats.collectionMobs());
        stmtUpsert.setInt(5, stats.glyphsUnlocked());
        stmtUpsert.setInt(6, stats.mobKills());
        stmtUpsert.setLong(7, clockMillis.getAsLong());
        stmtUpsert.executeUpdate();
    }

    /** 行が無ければ {@link RankingStats#EMPTY} を返す（オフライン／未ログインのプレイヤー）。 */
    public synchronized RankingStats load(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        stmtSelect.setString(1, playerId.toString());
        try (ResultSet rs = stmtSelect.executeQuery()) {
            if (!rs.next()) {
                return RankingStats.EMPTY;
            }
            return new RankingStats(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
        }
    }

    /**
     * 行を消す。単調増加更新のため、進行リセット時にこれを呼ばないと古い値が永久に残る。
     */
    public synchronized void delete(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId, "playerId");
        stmtDelete.setString(1, playerId.toString());
        stmtDelete.executeUpdate();
    }

    @Override
    public synchronized void close() {
        closeQuietly(stmtUpsert);
        closeQuietly(stmtSelect);
        closeQuietly(stmtDelete);
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
