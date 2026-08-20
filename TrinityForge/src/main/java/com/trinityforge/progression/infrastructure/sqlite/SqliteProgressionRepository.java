package com.trinityforge.progression.infrastructure.sqlite;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.repository.LoadResult;
import com.trinityforge.progression.repository.ProgressionRepository;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite-backed {@link ProgressionRepository}.
 *
 * <p><b>Schema version:</b> 2 (see {@link #SCHEMA_VERSION}).
 *
 * <p><b>Connection settings applied at startup:</b>
 * <ul>
 *   <li>{@code PRAGMA foreign_keys = ON}</li>
 *   <li>{@code PRAGMA busy_timeout = 5000} (5 s)</li>
 *   <li>{@code PRAGMA journal_mode = WAL} (enables concurrent readers + one writer)</li>
 * </ul>
 *
 * <p><b>Upsert semantics:</b> all writes use
 * {@code INSERT … ON CONFLICT … DO UPDATE SET} (SQLite 3.24+, never REPLACE) so that
 * row-level timestamps and other fields not present in the partial update are preserved.
 *
 * <p><b>Atomic perk unlock:</b> {@link #unlockPerk} wraps the balance deduction and perk
 * insertion in a single transaction. Any failure rolls back both changes.
 *
 * <p><b>Thread safety:</b> the underlying JDBC {@link Connection} and {@link PreparedStatement}s
 * are not thread-safe. Production wraps this class in {@code ExecutorProgressionRepository}
 * so all calls run on a single DB thread.
 *
 * <p><b>Injection for tests:</b> pass {@code "jdbc:sqlite::memory:"} as {@code jdbcUrl} to
 * get a fresh in-memory database; pass a {@code "jdbc:sqlite:/path/to/file.db"} for tests
 * that need to verify reopen durability.
 */
public final class SqliteProgressionRepository implements ProgressionRepository {

    private static final Logger LOG = Logger.getLogger(SqliteProgressionRepository.class.getName());
    private static final int SCHEMA_VERSION = 2;

    private final Connection conn;
    private volatile boolean closed = false;

    // Prepared statements (created once in the constructor, closed in close()).
    private PreparedStatement stmtLoadSkills;
    private PreparedStatement stmtLoadBalance;
    private PreparedStatement stmtLoadPerks;
    private PreparedStatement stmtLoadPerkCosts;
    private PreparedStatement stmtUpsertSkill;
    private PreparedStatement stmtUpsertBalance;
    private PreparedStatement stmtCheckBalance;
    private PreparedStatement stmtCheckPerk;
    private PreparedStatement stmtDeductPoints;
    private PreparedStatement stmtInsertPerk;

    /**
     * Opens (or creates) the SQLite database at the given JDBC URL, applies PRAGMAs,
     * creates the schema if missing, and prepares all statements.
     *
     * @param jdbcUrl e.g. {@code "jdbc:sqlite:/path/to/player_progression.db"} or
     *                {@code "jdbc:sqlite::memory:"}
     * @throws SQLException if the connection or schema setup fails
     */
    public SqliteProgressionRepository(String jdbcUrl) throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl, immediateTransactionProperties());
        try {
            configure(c);
            createSchema(c);
            migrateSchema(c);
            prepareStatements(c);
        } catch (SQLException | RuntimeException e) {
            try { c.close(); } catch (Exception ignored) {}
            throw e;
        }
        this.conn = c;
    }

    // ---- ProgressionRepository ------------------------------------------------------------------

    @Override
    public synchronized LoadResult<PlayerProgression> load(UUID playerId) {
        if (closed) {
            return LoadResult.failed(new IllegalStateException("progression repository is closed"));
        }
        String pid = playerId.toString();
        Map<String, SkillProgress> skills = new LinkedHashMap<>();
        long availablePoints = 0L;
        long spentPoints = 0L;
        boolean hasBalance = false;
        try {
            stmtLoadSkills.setString(1, pid);
            try (ResultSet rs = stmtLoadSkills.executeQuery()) {
                while (rs.next()) {
                    skills.put(rs.getString("skill_id"), new SkillProgress(
                            rs.getInt("level"),
                            rs.getDouble("residual_exp"),
                            rs.getDouble("total_exp"),
                            rs.getInt("prestige"),
                            rs.getInt("max_allowed_level")));
                }
            }
            stmtLoadBalance.setString(1, pid);
            try (ResultSet rs = stmtLoadBalance.executeQuery()) {
                if (rs.next()) {
                    availablePoints = rs.getLong("available_points");
                    spentPoints     = rs.getLong("spent_points");
                    hasBalance      = true;
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[progression] Failed to load player " + playerId, e);
            return LoadResult.failed(e);
        }

        if (skills.isEmpty() && !hasBalance) {
            return LoadResult.missing();
        }

        PlayerProgression prog = PlayerProgression.empty(playerId)
                .withPoints(availablePoints, spentPoints);
        for (Map.Entry<String, SkillProgress> entry : skills.entrySet()) {
            prog = prog.withSkill(entry.getKey(), entry.getValue());
        }
        return LoadResult.found(prog);
    }

    @Override
    public synchronized void saveSkillProgress(UUID playerId, String skillId, SkillProgress progress) {
        try {
            executeSkillUpsert(playerId, skillId, progress);
        } catch (SQLException e) {
            // Contract: a normal return means the write committed. Never swallow — a dropped write
            // that looks successful lets the cache invalidate and silently lose data.
            throw new IllegalStateException(
                    "Failed to save skill " + skillId + " for " + playerId, e);
        }
    }

    @Override
    public synchronized void savePointBalance(UUID playerId, long availablePoints, long spentPoints) {
        try {
            stmtUpsertBalance.setString(1, playerId.toString());
            stmtUpsertBalance.setLong(2, availablePoints);
            stmtUpsertBalance.setLong(3, spentPoints);
            stmtUpsertBalance.setLong(4, System.currentTimeMillis());
            stmtUpsertBalance.executeUpdate();
        } catch (SQLException e) {
            // Contract: a normal return means the write committed. Never swallow — see saveSkillProgress.
            throw new IllegalStateException(
                    "Failed to save point balance for " + playerId, e);
        }
    }

    @Override
    public synchronized void saveProgressionTransition(UUID playerId, String skillId, SkillProgress skillProgress,
                                          SkillProgress powerProgress,
                                          long availablePoints, long spentPoints) {
        try {
            conn.setAutoCommit(false);
            try {
                executeSkillUpsert(playerId, skillId, skillProgress);
                if (powerProgress != null && !"POWER".equals(skillId)) {
                    executeSkillUpsert(playerId, "POWER", powerProgress);
                }
                stmtUpsertBalance.setString(1, playerId.toString());
                stmtUpsertBalance.setLong(2, availablePoints);
                stmtUpsertBalance.setLong(3, spentPoints);
                stmtUpsertBalance.setLong(4, System.currentTimeMillis());
                stmtUpsertBalance.executeUpdate();
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "Failed to atomically save progression for " + playerId, ex);
        }
    }

    @Override
    public synchronized void saveAdminProgressionEdit(
            UUID playerId, String skillId, SkillProgress skillProgress,
            SkillProgress powerProgress, long availablePoints, long spentPoints,
            String prestigePerkPrefix, int prestigeCount,
            Collection<String> stripPerkIds) {
        String pid = playerId.toString();
        try {
            conn.setAutoCommit(false);
            try {
                executeSkillUpsert(playerId, skillId, skillProgress);
                if (powerProgress != null && !"POWER".equals(skillId)) {
                    executeSkillUpsert(playerId, "POWER", powerProgress);
                }
                stmtUpsertBalance.setString(1, pid);
                stmtUpsertBalance.setLong(2, availablePoints);
                stmtUpsertBalance.setLong(3, spentPoints);
                stmtUpsertBalance.setLong(4, System.currentTimeMillis());
                stmtUpsertBalance.executeUpdate();

                if (stripPerkIds != null && !stripPerkIds.isEmpty()) {
                    try (PreparedStatement delete = conn.prepareStatement(
                            "DELETE FROM player_perk_states"
                                    + " WHERE player_id = ? AND perk_id = ?")) {
                        for (String perkId : stripPerkIds) {
                            if (perkId == null || perkId.isBlank()) continue;
                            delete.setString(1, pid);
                            delete.setString(2, perkId);
                            delete.addBatch();
                        }
                        delete.executeBatch();
                    }
                }

                if (prestigePerkPrefix != null) {
                    List<String> existingTiers = new ArrayList<>();
                    stmtLoadPerks.setString(1, pid);
                    try (ResultSet rs = stmtLoadPerks.executeQuery()) {
                        while (rs.next()) {
                            String perkId = rs.getString(1);
                            if (perkId.matches(
                                    java.util.regex.Pattern.quote(prestigePerkPrefix)
                                            + "[1-9][0-9]*")) {
                                existingTiers.add(perkId);
                            }
                        }
                    }
                    try (PreparedStatement delete = conn.prepareStatement(
                            "DELETE FROM player_perk_states"
                                    + " WHERE player_id = ? AND perk_id = ?")) {
                        for (String perkId : existingTiers) {
                            delete.setString(1, pid);
                            delete.setString(2, perkId);
                            delete.addBatch();
                        }
                        delete.executeBatch();
                    }
                    long now = System.currentTimeMillis();
                    for (int tier = 1; tier <= prestigeCount; tier++) {
                        insertPerkRow(pid, prestigePerkPrefix + tier, now, 0L);
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "Failed to atomically save admin progression edit for " + playerId, ex);
        }
    }

    @Override
    public synchronized boolean unlockPerk(UUID playerId, String perkId, long pointCost) {
        String pid = playerId.toString();
        try {
            conn.setAutoCommit(false);
            try {
                // Check sufficient points — no balance row = 0 points.
                long available = 0L;
                boolean hasRow = false;
                stmtCheckBalance.setString(1, pid);
                try (ResultSet rs = stmtCheckBalance.executeQuery()) {
                    if (rs.next()) {
                        available = rs.getLong(1);
                        hasRow    = true;
                    }
                }
                if (!hasRow || available < pointCost) {
                    conn.rollback();
                    return false;
                }

                // Check perk not already unlocked.
                stmtCheckPerk.setString(1, pid);
                stmtCheckPerk.setString(2, perkId);
                try (ResultSet rs = stmtCheckPerk.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return false;
                    }
                }

                long now = System.currentTimeMillis();

                stmtDeductPoints.setLong(1, pointCost);
                stmtDeductPoints.setLong(2, pointCost);
                stmtDeductPoints.setLong(3, now);
                stmtDeductPoints.setString(4, pid);
                stmtDeductPoints.executeUpdate();

                insertPerkRow(pid, perkId, now, pointCost);

                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE,
                    "[progression] Failed to unlock perk " + perkId + " for " + playerId, e);
            return false;
        }
    }

    @Override
    public synchronized boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                             String prestigePerkId, SkillProgress resetProgress, long refundPoints) {
        return prestige(playerId, skillId, ordinaryPerkPrefix, prestigePerkId,
                resetProgress, refundPoints, Set.of());
    }

    @Override
    public synchronized boolean prestige(UUID playerId, String skillId, String ordinaryPerkPrefix,
                             String prestigePerkId, SkillProgress resetProgress, long refundPoints,
                             Set<String> retainedOrdinaryPerkIds) {
        String pid = playerId.toString();
        Set<String> retained = retainedOrdinaryPerkIds == null
                ? Set.of() : Set.copyOf(retainedOrdinaryPerkIds);
        try {
            conn.setAutoCommit(false);
            try {
                stmtCheckPerk.setString(1, pid);
                stmtCheckPerk.setString(2, prestigePerkId);
                try (ResultSet rs = stmtCheckPerk.executeQuery()) {
                    if (rs.next()) {
                        conn.rollback();
                        return false;
                    }
                }
                stmtUpsertSkill.setString(1, pid);
                stmtUpsertSkill.setString(2, skillId);
                stmtUpsertSkill.setInt(3, resetProgress.level());
                stmtUpsertSkill.setDouble(4, resetProgress.residualExp());
                stmtUpsertSkill.setDouble(5, resetProgress.totalExp());
                stmtUpsertSkill.setInt(6, resetProgress.prestige());
                stmtUpsertSkill.setInt(7, resetProgress.maxAllowedLevel());
                stmtUpsertSkill.setLong(8, System.currentTimeMillis());
                stmtUpsertSkill.executeUpdate();
                String retainedPlaceholders = retained.isEmpty() ? ""
                        : " AND perk_id NOT IN ("
                                + String.join(",", java.util.Collections.nCopies(
                                        retained.size(), "?"))
                                + ")";
                try (PreparedStatement delete = conn.prepareStatement(
                        "DELETE FROM player_perk_states WHERE player_id = ?"
                                + " AND perk_id LIKE ? AND perk_id NOT LIKE ?"
                                + retainedPlaceholders)) {
                    delete.setString(1, pid);
                    delete.setString(2, ordinaryPerkPrefix + "%");
                    delete.setString(3, ordinaryPerkPrefix + "ng%");
                    int parameter = 4;
                    for (String retainedPerkId : retained) {
                        delete.setString(parameter++, retainedPerkId);
                    }
                    delete.executeUpdate();
                }
                try (PreparedStatement refund = conn.prepareStatement(
                        "UPDATE player_point_balances SET available_points = available_points + ?,"
                                + " spent_points = MAX(0, spent_points - ?), updated_at = ?"
                                + " WHERE player_id = ?")) {
                    refund.setLong(1, Math.max(0L, refundPoints));
                    refund.setLong(2, Math.max(0L, refundPoints));
                    refund.setLong(3, System.currentTimeMillis());
                    refund.setString(4, pid);
                    refund.executeUpdate();
                }
                insertPerkRow(pid, prestigePerkId, System.currentTimeMillis(), 0L);
                conn.commit();
                return true;
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            LOG.log(Level.SEVERE, "[progression] Failed to prestige " + skillId
                    + " for " + playerId, ex);
            return false;
        }
    }

    @Override
    public synchronized LoadResult<Set<String>> loadPerkIds(UUID playerId) {
        if (closed) {
            return LoadResult.failed(new IllegalStateException("progression repository is closed"));
        }
        Set<String> result = new LinkedHashSet<>();
        try {
            stmtLoadPerks.setString(1, playerId.toString());
            try (ResultSet rs = stmtLoadPerks.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING,
                    "[progression] Failed to load perks for " + playerId, e);
            return LoadResult.failed(e);
        }
        return LoadResult.found(Collections.unmodifiableSet(result));
    }

    @Override
    public synchronized LoadResult<Map<String, Long>> loadPerkCosts(UUID playerId) {
        if (closed) {
            return LoadResult.failed(new IllegalStateException("progression repository is closed"));
        }
        Map<String, Long> result = new LinkedHashMap<>();
        try {
            stmtLoadPerkCosts.setString(1, playerId.toString());
            try (ResultSet rs = stmtLoadPerkCosts.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("perk_id"), rs.getLong("purchase_cost"));
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING,
                    "[progression] Failed to load perk costs for " + playerId, e);
            return LoadResult.failed(e);
        }
        return LoadResult.found(Collections.unmodifiableMap(result));
    }

    @Override
    public synchronized Collection<UUID> listPlayerIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT player_id FROM player_skill_state"
                        + " UNION SELECT DISTINCT player_id FROM player_point_balances"
                        + " UNION SELECT DISTINCT player_id FROM player_perk_states");
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                try {
                    ids.add(UUID.fromString(rs.getString(1)));
                } catch (IllegalArgumentException ignored) {
                    // skip corrupt ids
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "[progression] Failed to list player ids", e);
            throw new IllegalStateException("Failed to list progression player ids", e);
        }
        return Collections.unmodifiableSet(ids);
    }

    @Override
    public synchronized void resetPlayer(UUID playerId) {
        try {
            conn.setAutoCommit(false);
            try {
                for (String table : List.of(
                        "player_perk_states", "player_skill_state", "player_point_balances")) {
                    try (PreparedStatement statement = conn.prepareStatement(
                            "DELETE FROM " + table + " WHERE player_id = ?")) {
                        statement.setString(1, playerId.toString());
                        statement.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to reset progression for " + playerId, ex);
        }
    }

    @Override
    public synchronized void flush() {
        if (closed) return;
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(PASSIVE)");
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[progression] WAL checkpoint failed", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        flush();
        closed = true;
        closeQuietly(stmtLoadSkills);
        closeQuietly(stmtLoadBalance);
        closeQuietly(stmtLoadPerks);
        closeQuietly(stmtLoadPerkCosts);
        closeQuietly(stmtUpsertSkill);
        closeQuietly(stmtUpsertBalance);
        closeQuietly(stmtCheckBalance);
        closeQuietly(stmtCheckPerk);
        closeQuietly(stmtDeductPoints);
        closeQuietly(stmtInsertPerk);
        try {
            conn.close();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "[progression] Failed to close DB connection", e);
        }
    }

    // ---- Internal helpers -----------------------------------------------------------------------

    /**
     * Forces every explicit transaction to open with {@code BEGIN IMMEDIATE} instead of the JDBC
     * driver's default {@code BEGIN DEFERRED}.
     *
     * <p><b>Why this matters:</b> every transactional method here is a check-then-act
     * ({@link #unlockPerk} reads the balance, then deducts it). Under {@code BEGIN DEFERRED} the
     * transaction starts as a reader and only tries to take the write lock at the first UPDATE. If
     * another <em>connection</em> committed in between, SQLite fails that upgrade with
     * {@code SQLITE_BUSY_SNAPSHOT} — and <b>the busy handler is never invoked for that error</b>,
     * because waiting cannot fix an already-stale snapshot. {@code PRAGMA busy_timeout} therefore
     * does not protect these methods; they just fail. {@code BEGIN IMMEDIATE} takes the write lock
     * up front, so contention becomes an ordinary lock wait that {@code busy_timeout} absorbs.
     *
     * <p>With a single connection this changes nothing (all methods are {@code synchronized} and
     * production funnels calls through one DB thread). It becomes load-bearing as soon as a second
     * connection touches the same file — which is exactly what the resource-server split does when
     * {@code plugins/TrinityForge/} is shared between two servers by a directory junction.
     *
     * @see com.trinityforge.ops.SharedSqliteConcurrencyTest ops/reports/shared-sqlite-concurrency.md
     */
    private static Properties immediateTransactionProperties() {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setTransactionMode(org.sqlite.SQLiteConfig.TransactionMode.IMMEDIATE);
        return config.toProperties();
    }

    private static void configure(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA busy_timeout = 5000");
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
        }
    }

    private static void createSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS schema_version (\n"
                + "    version    INTEGER NOT NULL,\n"
                + "    applied_at INTEGER NOT NULL\n"
                + ")");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_skill_state (\n"
                + "    player_id       TEXT    NOT NULL,\n"
                + "    skill_id        TEXT    NOT NULL,\n"
                + "    level           INTEGER NOT NULL DEFAULT 0,\n"
                + "    residual_exp    REAL    NOT NULL DEFAULT 0,\n"
                + "    total_exp       REAL    NOT NULL DEFAULT 0,\n"
                + "    prestige        INTEGER NOT NULL DEFAULT 0,\n"
                + "    max_allowed_level INTEGER NOT NULL DEFAULT 100,\n"
                + "    updated_at      INTEGER NOT NULL,\n"
                + "    PRIMARY KEY (player_id, skill_id)\n"
                + ")");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_point_balances (\n"
                + "    player_id        TEXT    NOT NULL PRIMARY KEY,\n"
                + "    available_points INTEGER NOT NULL DEFAULT 0,\n"
                + "    spent_points     INTEGER NOT NULL DEFAULT 0,\n"
                + "    updated_at       INTEGER NOT NULL\n"
                + ")");

            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS player_perk_states (\n"
                + "    player_id        TEXT    NOT NULL,\n"
                + "    perk_id          TEXT    NOT NULL,\n"
                + "    unlock_timestamp INTEGER NOT NULL,\n"
                + "    purchase_cost    INTEGER NOT NULL DEFAULT 0,\n"
                + "    PRIMARY KEY (player_id, perk_id)\n"
                + ")");

            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM schema_version")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    insertSchemaVersion(s.getConnection(), SCHEMA_VERSION);
                }
            }
        }
    }

    /**
     * PRG-14 fix: {@code createSchema}'s {@code CREATE TABLE IF NOT EXISTS schema_version} always
     * leaves a fresh, empty {@code schema_version} table for a true legacy v1 database (one that
     * predates the {@code schema_version} table entirely) — the same as for a brand-new database — so
     * {@code createSchema} stamps it {@code SCHEMA_VERSION} (2) immediately, "before" this method ever
     * sees a chance to tell the two cases apart by version number alone. Trusting that stamped number
     * would skip the {@code purchase_cost} column migration for the legacy DB and leave
     * {@code player_perk_states} without it, so the next statement ({@code SELECT perk_id,
     * purchase_cost FROM player_perk_states}) fails with {@code SQLException: no such column} and the
     * whole repository (and therefore the plugin) fails to start.
     *
     * <p>Rather than special-case the version number at stamp time (fragile: it has to reason about
     * what {@code createSchema} did before this call), this method checks the <em>physical</em> schema
     * directly — does {@code player_perk_states} already have the {@code purchase_cost} column? — and
     * adds it if missing, independent of whatever {@code version} says. This is naturally idempotent
     * (a second run sees the column already present and no-ops) and also <b>rescues an already-bricked
     * database</b>: one that a pre-fix build of this class already mis-stamped as v2 while the column
     * was still missing. In both cases the version row is (re)stamped to {@link #SCHEMA_VERSION} only
     * after the column is confirmed present.
     */
    private static void migrateSchema(Connection c) throws SQLException {
        int version = readSchemaVersion(c);
        if (version == 0) {
            insertSchemaVersion(c, SCHEMA_VERSION);
            version = SCHEMA_VERSION;
        }
        boolean columnAdded = ensurePurchaseCostColumn(c);
        if (columnAdded) {
            updateSchemaVersion(c, SCHEMA_VERSION);
            if (version < SCHEMA_VERSION) {
                LOG.info("[progression] Migrated progression schema from v" + version + " to v"
                        + SCHEMA_VERSION + " (purchase_cost backfill defaults to 0 for existing unlocks)");
            } else {
                LOG.warning("[progression] Rescued a progression DB that was previously mis-stamped as"
                        + " schema v" + SCHEMA_VERSION + " without the purchase_cost column (PRG-14) —"
                        + " column added, backfilled to 0.");
            }
        }
    }

    /** Adds {@code player_perk_states.purchase_cost} if missing. Returns true iff it was added. */
    private static boolean ensurePurchaseCostColumn(Connection c) throws SQLException {
        if (columnExists(c, "player_perk_states", "purchase_cost")) {
            return false;
        }
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                    "ALTER TABLE player_perk_states"
                            + " ADD COLUMN purchase_cost INTEGER NOT NULL DEFAULT 0");
        }
        return true;
    }

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int readSchemaVersion(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version FROM schema_version ORDER BY applied_at DESC LIMIT 1")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private static void insertSchemaVersion(Connection c, int version) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)")) {
            stmt.setInt(1, version);
            stmt.setLong(2, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }

    private static void updateSchemaVersion(Connection c, int version) throws SQLException {
        try (PreparedStatement stmt = c.prepareStatement(
                "UPDATE schema_version SET version = ?, applied_at = ?")) {
            stmt.setInt(1, version);
            stmt.setLong(2, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }

    private void prepareStatements(Connection c) throws SQLException {
        stmtLoadSkills = c.prepareStatement(
                "SELECT skill_id, level, residual_exp, total_exp, prestige, max_allowed_level"
                + " FROM player_skill_state WHERE player_id = ?");

        stmtLoadBalance = c.prepareStatement(
                "SELECT available_points, spent_points"
                + " FROM player_point_balances WHERE player_id = ?");

        stmtLoadPerks = c.prepareStatement(
                "SELECT perk_id FROM player_perk_states WHERE player_id = ?");

        stmtLoadPerkCosts = c.prepareStatement(
                "SELECT perk_id, purchase_cost FROM player_perk_states WHERE player_id = ?");

        stmtUpsertSkill = c.prepareStatement(
                "INSERT INTO player_skill_state"
                + " (player_id, skill_id, level, residual_exp, total_exp,"
                + "  prestige, max_allowed_level, updated_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT(player_id, skill_id) DO UPDATE SET"
                + "   level             = excluded.level,"
                + "   residual_exp      = excluded.residual_exp,"
                + "   total_exp         = excluded.total_exp,"
                + "   prestige          = excluded.prestige,"
                + "   max_allowed_level = excluded.max_allowed_level,"
                + "   updated_at        = excluded.updated_at");

        stmtUpsertBalance = c.prepareStatement(
                "INSERT INTO player_point_balances"
                + " (player_id, available_points, spent_points, updated_at)"
                + " VALUES (?, ?, ?, ?)"
                + " ON CONFLICT(player_id) DO UPDATE SET"
                + "   available_points = excluded.available_points,"
                + "   spent_points     = excluded.spent_points,"
                + "   updated_at       = excluded.updated_at");

        stmtCheckBalance = c.prepareStatement(
                "SELECT available_points FROM player_point_balances WHERE player_id = ?");

        stmtCheckPerk = c.prepareStatement(
                "SELECT 1 FROM player_perk_states WHERE player_id = ? AND perk_id = ?");

        stmtDeductPoints = c.prepareStatement(
                "UPDATE player_point_balances"
                + " SET available_points = available_points - ?,"
                + "     spent_points     = spent_points + ?,"
                + "     updated_at       = ?"
                + " WHERE player_id = ?");

        stmtInsertPerk = c.prepareStatement(
                "INSERT INTO player_perk_states (player_id, perk_id, unlock_timestamp, purchase_cost)"
                + " VALUES (?, ?, ?, ?)");
    }

    private void insertPerkRow(String playerId, String perkId, long unlockTimestamp, long purchaseCost)
            throws SQLException {
        stmtInsertPerk.setString(1, playerId);
        stmtInsertPerk.setString(2, perkId);
        stmtInsertPerk.setLong(3, unlockTimestamp);
        stmtInsertPerk.setLong(4, purchaseCost);
        stmtInsertPerk.executeUpdate();
    }

    private static void closeQuietly(PreparedStatement stmt) {
        if (stmt != null) {
            try { stmt.close(); } catch (SQLException ignored) {}
        }
    }

    private void executeSkillUpsert(UUID playerId, String skillId,
                                    SkillProgress progress) throws SQLException {
        stmtUpsertSkill.setString(1, playerId.toString());
        stmtUpsertSkill.setString(2, skillId);
        stmtUpsertSkill.setInt(3, progress.level());
        stmtUpsertSkill.setDouble(4, progress.residualExp());
        stmtUpsertSkill.setDouble(5, progress.totalExp());
        stmtUpsertSkill.setInt(6, progress.prestige());
        stmtUpsertSkill.setInt(7, progress.maxAllowedLevel());
        stmtUpsertSkill.setLong(8, System.currentTimeMillis());
        stmtUpsertSkill.executeUpdate();
    }
}
