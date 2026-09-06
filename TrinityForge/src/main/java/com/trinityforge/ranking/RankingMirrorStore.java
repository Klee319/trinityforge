package com.trinityforge.ranking;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * 上位 N 取得で使ってよい列の<b>許可リスト</b>。stat 名 → 実列名。
     *
     * <p>列名は SQL のプレースホルダにできないので、どうしても文字列として SQL に埋め込むしかない。
     * <b>呼び出し側から来た文字列をそのまま埋めると SQL インジェクションになる</b>ため、
     * ここにあるキーで引けたときだけ、この Map が持つ<b>定数側</b>の文字列を埋める。
     * 未知の stat は {@code null} になり {@link #top} が空リストを返す（例外にしない ──
     * 外部プラグインの typo で順位表が落ちるより、空で出るほうが被害が小さい）。
     */
    private static final Map<String, String> TOP_COLUMNS = Map.of(
            "collection_items", "collection_items",
            "collection_mobs", "collection_mobs",
            "glyphs_unlocked", "glyphs_unlocked",
            "mob_kills", "mob_kills");

    /**
     * 1 回の問い合わせで返す最大件数。外部プラグインが {@code Integer.MAX_VALUE} を渡しても
     * 全プレイヤー分をメモリへ載せないための安全弁。
     */
    public static final int MAX_TOP_LIMIT = 1000;

    /**
     * 全スキル合計から除外するスキル ID。POWER は「各スキルのレベルアップから派生して伸びる」
     * メタスキルなので、合計に入れると同じ成長を二重に数えてしまう。
     */
    private static final String POWER_SKILL_ID = "POWER";

    /**
     * スキルの<b>実効レベル</b>を求める SQL 式（{@code player_skill_state} の別名 {@code s} 前提）。
     *
     * <p><b>なぜ {@code level} をそのまま使えないのか。</b> プレステージすると
     * {@code NativePerkService#prestigeUnderLock} が {@code level} を 0 へ戻して {@code prestige} を
     * 1 段上げる。つまり生の {@code level} で並べると<b>最も育っているプレイヤーが順位表の最下位へ
     * 落ちる</b>し、{@code level > 0} で絞っていたため<b>プレステージ直後は行ごと消えていた</b>。
     *
     * <p>プレステージは {@code prestige.at-level}（出荷値ではどのスキルも上限レベルと同じ 100）へ
     * 到達しないと踏めないので、1 段 = 上限レベル 1 周ぶんの到達量として数えてよい。
     * よって「上限レベル × プレステージ段 + 現在レベル」が、プレステージを跨いで単調に増える
     * 唯一の量になる。順位表に出る数値もこの実効レベル（例: 上限 100 で 2 段目の Lv5 なら 205）。
     *
     * <p>外側の丸括弧を含めているのは {@code "SUM" + EFFECTIVE_LEVEL_EXPR} で合計へ包めるようにするため。
     */
    private static final String EFFECTIVE_LEVEL_EXPR = "(s.prestige * s.max_allowed_level + s.level)";

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

    // ---- 上位 N 件（順位表用） ------------------------------------------------------

    /**
     * ミラー 4 項目の上位 {@code limit} 件を降順で返す。
     *
     * <p><b>値が 0 の行は返さない。</b> 参加しただけのプレイヤーが 0 で並ぶ順位表には意味がなく、
     * 「0 人しかいない項目」も空リストで表現できたほうが呼び出し側が単純になる。
     *
     * @param stat {@code collection_items} / {@code collection_mobs} / {@code glyphs_unlocked}
     *             / {@code mob_kills} のいずれか。未知なら空リスト
     * @param limit 最大件数。0 以下なら空リスト。{@link #MAX_TOP_LIMIT} で頭打ち
     */
    public synchronized List<RankingEntry> top(String stat, int limit) throws SQLException {
        String column = stat == null ? null : TOP_COLUMNS.get(stat.trim().toLowerCase(Locale.ROOT));
        int capped = cappedLimit(limit);
        if (column == null || capped == 0) {
            return List.of();
        }
        // column は必ず TOP_COLUMNS の値（＝このクラスの定数）。呼び出し側の文字列は連結しない。
        String sql = "SELECT player_uuid, player_name, " + column
                + " FROM player_ranking_stats WHERE " + column + " > 0"
                + " ORDER BY " + column + " DESC, player_name ASC LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, capped);
            return readEntries(stmt);
        }
    }

    /**
     * 指定スキルの<b>実効レベル</b>上位 {@code limit} 件。スキル ID は保存時と同じく大文字へ
     * 正規化して照合する（{@code NativeProgressionService#normalizeSkillId} と同じ規則）。
     * 実効レベル 0（＝未プレステージの Lv0）の行は返さない。
     *
     * <p>返す値は生のレベルではなく {@link #EFFECTIVE_LEVEL_EXPR}（上限レベル × プレステージ段 +
     * 現在レベル）である。プレステージでレベルが 0 へ戻る仕様なので、生のレベルで並べると
     * 最も育っているプレイヤーが順位表から落ちる。
     *
     * <p>同値のときは<b>プレステージ段の多いほうを上</b>にする。プレステージは累計 EXP も 0 へ
     * 戻すため、総 EXP だけで割ると「Lv100・未プレステージ」が「2 段目・Lv0」を常に押しのける。
     *
     * <p>表示名は {@code player_ranking_stats} を LEFT JOIN して引く。まだミラーへ書かれていない
     * プレイヤーは名前が空文字になる（{@link RankingEntry} 参照）。
     */
    public synchronized List<RankingEntry> topSkillLevel(String skillId, int limit) throws SQLException {
        int capped = cappedLimit(limit);
        if (skillId == null || skillId.isBlank() || capped == 0 || !skillStateTableExists()) {
            return List.of();
        }
        String sql = "SELECT s.player_id, COALESCE(r.player_name, ''), "
                + EFFECTIVE_LEVEL_EXPR + " AS effective_level"
                + "  FROM player_skill_state s"
                + "  LEFT JOIN player_ranking_stats r ON r.player_uuid = s.player_id"
                + " WHERE s.skill_id = ? AND " + EFFECTIVE_LEVEL_EXPR + " > 0"
                + " ORDER BY effective_level DESC, s.prestige DESC, s.total_exp DESC LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, skillId.trim().toUpperCase(Locale.ROOT));
            stmt.setInt(2, capped);
            return readEntries(stmt);
        }
    }

    /**
     * 全スキルの<b>実効レベル</b>合計の上位 {@code limit} 件。合計 0 の行は返さない。
     *
     * <p><b>POWER は合計に含めない。</b> POWER は各スキルのレベルアップから自動的に伸びる
     * メタスキルなので、含めると同じ成長を二重計上することになる。
     *
     * <p>個別ランキングと同じく {@link #EFFECTIVE_LEVEL_EXPR} を足す。生のレベルを足すと
     * 「1 つでもプレステージした瞬間に合計が下がる」ことになり、順位表が育成を罰する。
     */
    public synchronized List<RankingEntry> topTotalSkillLevel(int limit) throws SQLException {
        int capped = cappedLimit(limit);
        if (capped == 0 || !skillStateTableExists()) {
            return List.of();
        }
        // SUM(...) で包むため、外側の丸括弧を含んだ定数をそのまま連結する。
        String sql = "SELECT s.player_id, COALESCE(MAX(r.player_name), ''), SUM"
                + EFFECTIVE_LEVEL_EXPR + " AS total"
                + "  FROM player_skill_state s"
                + "  LEFT JOIN player_ranking_stats r ON r.player_uuid = s.player_id"
                + " WHERE s.skill_id <> ?"
                + " GROUP BY s.player_id HAVING total > 0"
                + " ORDER BY total DESC LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, POWER_SKILL_ID);
            stmt.setInt(2, capped);
            return readEntries(stmt);
        }
    }

    /** 1 列目 = UUID 文字列、2 列目 = 表示名、3 列目 = 値、という並びの結果集合を読む。 */
    private static List<RankingEntry> readEntries(PreparedStatement stmt) throws SQLException {
        List<RankingEntry> entries = new ArrayList<>();
        try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID id;
                try {
                    id = UUID.fromString(rs.getString(1));
                } catch (IllegalArgumentException | NullPointerException ignored) {
                    continue; // 壊れた行は順位表から落とすだけにする。
                }
                entries.add(new RankingEntry(id, rs.getString(2), rs.getLong(3)));
            }
        }
        return List.copyOf(entries);
    }

    private static int cappedLimit(int limit) {
        return limit <= 0 ? 0 : Math.min(limit, MAX_TOP_LIMIT);
    }

    /**
     * {@code player_skill_state} は {@code SqliteProgressionRepository} が作る表で、このクラスは
     * 作らない。本番では進行リポジトリのほうが先に構築されるので必ず存在するが、
     * このクラス単体でミラーだけを開いたとき（テストや将来の分離）に
     * {@code no such table} で落とさないよう存在確認する。
     */
    private boolean skillStateTableExists() throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'player_skill_state'");
             ResultSet rs = stmt.executeQuery()) {
            return rs.next();
        }
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
