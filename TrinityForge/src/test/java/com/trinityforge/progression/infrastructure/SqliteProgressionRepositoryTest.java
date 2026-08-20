package com.trinityforge.progression.infrastructure;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite integration tests: persistence, upsert, atomic unlock transaction,
 * rollback on failure, reopen durability, and independent-player isolation.
 * No MockBukkit required (pure JDBC + domain model).
 */
class SqliteProgressionRepositoryTest {

    private SqliteProgressionRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        repo = new SqliteProgressionRepository("jdbc:sqlite::memory:");
    }

    @AfterEach
    void tearDown() {
        repo.close();
    }

    // ---- domain boundary: unknown player ----

    @Test
    void loadUnknownPlayer_returnsEmpty() {
        assertTrue(repo.load(UUID.randomUUID()).isMissing());
    }

    @Test
    void loadPerkIds_emptyForNewPlayer() {
        assertTrue(repo.loadPerkIds(UUID.randomUUID()).orElseThrow().isEmpty());
    }

    @Test
    void loadPerkCosts_emptyForNewPlayer() {
        assertTrue(repo.loadPerkCosts(UUID.randomUUID()).orElseThrow().isEmpty());
    }

    @Test
    void unlockPerk_persistsPurchaseCost_roundTripsViaLoadPerkCosts() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 10L, 0L);
        assertTrue(repo.unlockPerk(player, "mining_perk_a", 2L));
        Map<String, Long> costs = repo.loadPerkCosts(player).orElseThrow();
        assertEquals(2L, costs.get("mining_perk_a"));
    }

    @Test
    void schemaV1Database_migratesPurchaseCostColumn(@TempDir Path tmpDir) throws Exception {
        Path dbFile = tmpDir.resolve("schema_v1.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE TABLE schema_version (version INTEGER NOT NULL, applied_at INTEGER NOT NULL)");
            s.executeUpdate("INSERT INTO schema_version (version, applied_at) VALUES (1, 0)");
            s.executeUpdate(
                    "CREATE TABLE player_skill_state ("
                            + "player_id TEXT NOT NULL, skill_id TEXT NOT NULL, level INTEGER NOT NULL DEFAULT 0,"
                            + "residual_exp REAL NOT NULL DEFAULT 0, total_exp REAL NOT NULL DEFAULT 0,"
                            + "prestige INTEGER NOT NULL DEFAULT 0, max_allowed_level INTEGER NOT NULL DEFAULT 100,"
                            + "updated_at INTEGER NOT NULL, PRIMARY KEY (player_id, skill_id))");
            s.executeUpdate(
                    "CREATE TABLE player_point_balances ("
                            + "player_id TEXT NOT NULL PRIMARY KEY, available_points INTEGER NOT NULL DEFAULT 0,"
                            + "spent_points INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
            s.executeUpdate(
                    "CREATE TABLE player_perk_states ("
                            + "player_id TEXT NOT NULL, perk_id TEXT NOT NULL, unlock_timestamp INTEGER NOT NULL,"
                            + "PRIMARY KEY (player_id, perk_id))");
        }

        try (SqliteProgressionRepository migrated =
                     new SqliteProgressionRepository("jdbc:sqlite:" + dbFile)) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("PRAGMA table_info(player_perk_states)")) {
                boolean hasPurchaseCost = false;
                while (rs.next()) {
                    if ("purchase_cost".equals(rs.getString("name"))) {
                        hasPurchaseCost = true;
                    }
                }
                assertTrue(hasPurchaseCost, "v1 DB must gain purchase_cost after migration");
            }
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT version FROM schema_version ORDER BY applied_at DESC LIMIT 1")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
            migrated.flush();
        }
    }

    /**
     * PRG-14 regression: a TRUE legacy v1 database — one that predates the {@code schema_version}
     * table entirely (unlike {@link #schemaV1Database_migratesPurchaseCostColumn}, which pre-seeds an
     * explicit {@code version=1} row) — must still gain the {@code purchase_cost} column. Before the
     * fix, {@code createSchema}'s {@code CREATE TABLE IF NOT EXISTS schema_version} silently created a
     * fresh empty version table for this exact case, and the immediately-following "table is empty ->
     * stamp SCHEMA_VERSION" logic stamped it straight to v2 without ever running the v1->v2 migration,
     * leaving {@code player_perk_states} without {@code purchase_cost} and bricking every subsequent
     * repository open with {@code SQLException: no such column: purchase_cost}.
     */
    @Test
    void trueLegacyDatabaseWithNoSchemaVersionTable_migratesPurchaseCostColumn(@TempDir Path tmpDir)
            throws Exception {
        Path dbFile = tmpDir.resolve("true_legacy_v1.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement()) {
            // No schema_version table at all -- this is the actual pre-versioning v1 shape.
            s.executeUpdate(
                    "CREATE TABLE player_skill_state ("
                            + "player_id TEXT NOT NULL, skill_id TEXT NOT NULL, level INTEGER NOT NULL DEFAULT 0,"
                            + "residual_exp REAL NOT NULL DEFAULT 0, total_exp REAL NOT NULL DEFAULT 0,"
                            + "prestige INTEGER NOT NULL DEFAULT 0, max_allowed_level INTEGER NOT NULL DEFAULT 100,"
                            + "updated_at INTEGER NOT NULL, PRIMARY KEY (player_id, skill_id))");
            s.executeUpdate(
                    "CREATE TABLE player_point_balances ("
                            + "player_id TEXT NOT NULL PRIMARY KEY, available_points INTEGER NOT NULL DEFAULT 0,"
                            + "spent_points INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
            s.executeUpdate(
                    "CREATE TABLE player_perk_states ("
                            + "player_id TEXT NOT NULL, perk_id TEXT NOT NULL, unlock_timestamp INTEGER NOT NULL,"
                            + "PRIMARY KEY (player_id, perk_id))");
        }

        try (SqliteProgressionRepository migrated =
                     new SqliteProgressionRepository("jdbc:sqlite:" + dbFile)) {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("PRAGMA table_info(player_perk_states)")) {
                boolean hasPurchaseCost = false;
                while (rs.next()) {
                    if ("purchase_cost".equals(rs.getString("name"))) {
                        hasPurchaseCost = true;
                    }
                }
                assertTrue(hasPurchaseCost,
                        "a true legacy (no schema_version table) v1 DB must gain purchase_cost");
            }
            // The bug's actual failure mode: the follow-on SELECT that references purchase_cost must
            // not throw once the repository is fully constructed.
            assertFalse(migrated.loadPerkCosts(UUID.randomUUID()).isFailed());
        }
    }

    /**
     * PRG-14 rescue path: a database already mis-stamped {@code schema_version = 2} by a pre-fix build
     * of this class (the exact bricked state the bug produced) must self-heal on next open — the
     * missing column is added and the repository opens successfully — rather than staying permanently
     * unopenable.
     */
    @Test
    void alreadyBrickedV2DatabaseMissingPurchaseCostColumn_isRescuedOnOpen(@TempDir Path tmpDir)
            throws Exception {
        Path dbFile = tmpDir.resolve("bricked_v2.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement s = c.createStatement()) {
            s.executeUpdate(
                    "CREATE TABLE schema_version (version INTEGER NOT NULL, applied_at INTEGER NOT NULL)");
            // Mis-stamped v2 (PRG-14's exact bug output) despite the column never having been added.
            s.executeUpdate("INSERT INTO schema_version (version, applied_at) VALUES (2, 0)");
            s.executeUpdate(
                    "CREATE TABLE player_skill_state ("
                            + "player_id TEXT NOT NULL, skill_id TEXT NOT NULL, level INTEGER NOT NULL DEFAULT 0,"
                            + "residual_exp REAL NOT NULL DEFAULT 0, total_exp REAL NOT NULL DEFAULT 0,"
                            + "prestige INTEGER NOT NULL DEFAULT 0, max_allowed_level INTEGER NOT NULL DEFAULT 100,"
                            + "updated_at INTEGER NOT NULL, PRIMARY KEY (player_id, skill_id))");
            s.executeUpdate(
                    "CREATE TABLE player_point_balances ("
                            + "player_id TEXT NOT NULL PRIMARY KEY, available_points INTEGER NOT NULL DEFAULT 0,"
                            + "spent_points INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)");
            s.executeUpdate(
                    "CREATE TABLE player_perk_states ("
                            + "player_id TEXT NOT NULL, perk_id TEXT NOT NULL, unlock_timestamp INTEGER NOT NULL,"
                            + "PRIMARY KEY (player_id, perk_id))");
        }

        try (SqliteProgressionRepository rescued =
                     new SqliteProgressionRepository("jdbc:sqlite:" + dbFile)) {
            assertFalse(rescued.loadPerkCosts(UUID.randomUUID()).isFailed(),
                    "repository must open and serve queries after self-healing the missing column");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("PRAGMA table_info(player_perk_states)")) {
                boolean hasPurchaseCost = false;
                while (rs.next()) {
                    if ("purchase_cost".equals(rs.getString("name"))) {
                        hasPurchaseCost = true;
                    }
                }
                assertTrue(hasPurchaseCost, "bricked DB must gain purchase_cost on rescue");
            }
        }

        // Re-opening a second time (column now present, version already 2) must remain idempotent and
        // must not attempt to re-add the column (which would throw "duplicate column name").
        try (SqliteProgressionRepository reopened =
                     new SqliteProgressionRepository("jdbc:sqlite:" + dbFile)) {
            assertFalse(reopened.loadPerkCosts(UUID.randomUUID()).isFailed());
        }
    }

    // ---- save + load skill state ----

    @Test
    void saveAndLoad_skillProgress() {
        UUID player = UUID.randomUUID();
        SkillProgress sp = new SkillProgress(5, 123L, 1000L, 0, 100);
        repo.saveSkillProgress(player, SkillId.MINING, sp);

        var loaded = repo.load(player);
        assertTrue(loaded.isFound());
        SkillProgress got = loaded.orElseThrow().skills().get(SkillId.MINING);
        assertNotNull(got);
        assertEquals(5,     got.level());
        assertEquals(123L,  got.residualExp());
        assertEquals(1000L, got.totalExp());
        assertEquals(0,     got.prestige());
        assertEquals(100,   got.maxAllowedLevel());
    }

    @Test
    void fractionalExp_survivesRoundTrip() {
        UUID player = UUID.randomUUID();
        repo.saveSkillProgress(player, SkillId.ARS_MAGIC,
                new SkillProgress(0, 0.1, 0.1, 0, 100));
        SkillProgress loaded = repo.load(player).orElseThrow().skills().get(SkillId.ARS_MAGIC);
        assertEquals(0.1, loaded.residualExp(), 0.000001);
        assertEquals(0.1, loaded.totalExp(), 0.000001);
    }

    @Test
    void upsert_updatesExistingSkillRow() {
        UUID player = UUID.randomUUID();
        repo.saveSkillProgress(player, SkillId.ARCHERY, new SkillProgress(1, 0L, 100L, 0, 100));
        repo.saveSkillProgress(player, SkillId.ARCHERY, new SkillProgress(3, 50L, 650L, 0, 100));

        SkillProgress got = repo.load(player).orElseThrow().skills().get(SkillId.ARCHERY);
        assertEquals(3,    got.level());
        assertEquals(50L,  got.residualExp());
        assertEquals(650L, got.totalExp());
    }

    @Test
    void multipleSkills_allLoadedForPlayer() {
        UUID player = UUID.randomUUID();
        repo.saveSkillProgress(player, SkillId.MINING,  new SkillProgress(5,  0L, 1000L, 0, 100));
        repo.saveSkillProgress(player, SkillId.ARCHERY, new SkillProgress(3,  0L,  500L, 0, 100));
        repo.saveSkillProgress(player, SkillId.POWER,   new SkillProgress(10, 0L, 8000L, 0, 256));

        PlayerProgression prog = repo.load(player).orElseThrow();
        assertEquals(3, prog.skills().size());
        assertEquals(5,  prog.skills().get(SkillId.MINING).level());
        assertEquals(3,  prog.skills().get(SkillId.ARCHERY).level());
        assertEquals(10, prog.skills().get(SkillId.POWER).level());
    }

    // ---- save + load point balance ----

    @Test
    void saveAndLoad_pointBalance() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 50L, 10L);

        PlayerProgression loaded = repo.load(player).orElseThrow();
        assertEquals(50L, loaded.availablePoints());
        assertEquals(10L, loaded.spentPoints());
    }

    @Test
    void upsert_updatesExistingBalanceRow() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 100L, 0L);
        repo.savePointBalance(player, 80L, 20L);

        PlayerProgression loaded = repo.load(player).orElseThrow();
        assertEquals(80L, loaded.availablePoints());
        assertEquals(20L, loaded.spentPoints());
    }

    // ---- perk unlock (atomic) ----

    @Test
    void unlockPerk_withSufficientPoints_succeeds() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 100L, 0L);

        assertTrue(repo.unlockPerk(player, "perk_a", 50L));
        assertTrue(repo.loadPerkIds(player).orElseThrow().contains("perk_a"));

        PlayerProgression prog = repo.load(player).orElseThrow();
        assertEquals(50L, prog.availablePoints());
        assertEquals(50L, prog.spentPoints());
    }

    @Test
    void unlockPerk_insufficientPoints_fails_noStateChange() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 10L, 0L);

        assertFalse(repo.unlockPerk(player, "perk_a", 50L));
        assertTrue(repo.loadPerkIds(player).orElseThrow().isEmpty());
        // Points must be unchanged.
        assertEquals(10L, repo.load(player).orElseThrow().availablePoints());
    }

    @Test
    void unlockPerk_alreadyUnlocked_fails_pointsUnchanged() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 200L, 0L);
        assertTrue(repo.unlockPerk(player, "perk_a", 50L));

        // Second attempt for the same perk.
        assertFalse(repo.unlockPerk(player, "perk_a", 50L));

        // Available points should be 200 - 50 = 150, not 100.
        assertEquals(150L, repo.load(player).orElseThrow().availablePoints());
        assertEquals(1, repo.loadPerkIds(player).orElseThrow().size());
    }

    @Test
    void unlockPerk_noBalanceRow_fails() {
        UUID player = UUID.randomUUID();
        assertFalse(repo.unlockPerk(player, "perk_a", 0L));
        assertTrue(repo.loadPerkIds(player).orElseThrow().isEmpty());
    }

    @Test
    void unlockMultiplePerks_sequentially() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 200L, 0L);

        assertTrue(repo.unlockPerk(player, "perk_a", 50L));
        assertTrue(repo.unlockPerk(player, "perk_b", 50L));

        Set<String> perks = repo.loadPerkIds(player).orElseThrow();
        assertTrue(perks.contains("perk_a"));
        assertTrue(perks.contains("perk_b"));
        assertEquals(100L, repo.load(player).orElseThrow().availablePoints());
    }

    @Test
    void prestige_resetsSkillRefundsNodesAndKeepsPermanentTier() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 0L, 3L);
        repo.saveSkillProgress(player, SkillId.MINING,
                new SkillProgress(100, 0.0, 50000.0, 0, 100));
        // Seed ordinary owned nodes without spending again.
        repo.savePointBalance(player, 3L, 0L);
        assertTrue(repo.unlockPerk(player, "mining_perk_a", 1L));
        assertTrue(repo.unlockPerk(player, "mining_perk_b", 2L));

        assertTrue(repo.prestige(player, SkillId.MINING, "mining_perk_",
                "mining_perk_ng1", new SkillProgress(0, 0.0, 0.0, 1, 100), 3L));

        PlayerProgression state = repo.load(player).orElseThrow();
        assertEquals(0, state.skills().get(SkillId.MINING).level());
        assertEquals(1, state.skills().get(SkillId.MINING).prestige());
        assertEquals(3L, state.availablePoints());
        assertEquals(Set.of("mining_perk_ng1"), repo.loadPerkIds(player).orElseThrow());
    }

    @Test
    void resetPlayer_removesAllRows() {
        UUID player = UUID.randomUUID();
        repo.savePointBalance(player, 5L, 0L);
        repo.saveSkillProgress(player, SkillId.FISHING,
                new SkillProgress(1, 0.5, 10.5, 0, 100));
        assertTrue(repo.unlockPerk(player, "fishing_perk_a", 1L));
        repo.resetPlayer(player);
        assertTrue(repo.load(player).isMissing());
        assertTrue(repo.loadPerkIds(player).orElseThrow().isEmpty());
    }

    // ---- flush ----

    @Test
    void flush_doesNotThrow() {
        assertDoesNotThrow(() -> repo.flush());
    }

    @Test
    void closeIdempotent() {
        repo.close();
        assertDoesNotThrow(() -> repo.close()); // second close must not throw
    }

    // ---- durability: close and reopen a file-based database ----

    @Test
    void durability_skillAndBalance_survivesReopen(@TempDir Path tmpDir) throws SQLException {
        Path dbFile = tmpDir.resolve("test_prog.db");
        UUID player = UUID.randomUUID();

        try (SqliteProgressionRepository r =
                     new SqliteProgressionRepository("jdbc:sqlite:" + dbFile)) {
            r.saveSkillProgress(player, SkillId.MINING, new SkillProgress(3, 50L, 650L, 0, 100));
            r.savePointBalance(player, 30L, 5L);
        }

        try (SqliteProgressionRepository r =
                     new SqliteProgressionRepository("jdbc:sqlite:" + dbFile)) {
            var loaded = r.load(player);
            assertTrue(loaded.isFound(), "Player data lost after reopen");
            PlayerProgression prog = loaded.orElseThrow();
            SkillProgress sp = prog.skills().get(SkillId.MINING);
            assertNotNull(sp, "MINING skill lost after reopen");
            assertEquals(3,    sp.level());
            assertEquals(50L,  sp.residualExp());
            assertEquals(650L, sp.totalExp());
            assertEquals(30L,  prog.availablePoints());
            assertEquals(5L,   prog.spentPoints());
        }
    }

    @Test
    void durability_perkState_survivesReopen(@TempDir Path tmpDir) throws SQLException {
        Path dbFile = tmpDir.resolve("test_perk.db");
        UUID player = UUID.randomUUID();

        try (SqliteProgressionRepository r =
                     new SqliteProgressionRepository("jdbc:sqlite:" + dbFile)) {
            r.savePointBalance(player, 50L, 0L);
            r.unlockPerk(player, "perk_ng1", 50L);
        }

        try (SqliteProgressionRepository r =
                     new SqliteProgressionRepository("jdbc:sqlite:" + dbFile)) {
            Set<String> perks = r.loadPerkIds(player).orElseThrow();
            assertTrue(perks.contains("perk_ng1"), "perk_ng1 lost after reopen");
            assertEquals(0L, r.load(player).orElseThrow().availablePoints());
        }
    }

    // ---- concurrent independent players ----

    @Test
    void independentPlayers_skillsDoNotInterfere() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        repo.saveSkillProgress(p1, SkillId.MINING, new SkillProgress(10, 0L, 5000L,  0, 100));
        repo.saveSkillProgress(p2, SkillId.MINING, new SkillProgress(20, 0L, 20000L, 0, 100));

        assertEquals(10, repo.load(p1).orElseThrow().skills().get(SkillId.MINING).level());
        assertEquals(20, repo.load(p2).orElseThrow().skills().get(SkillId.MINING).level());
    }

    @Test
    void independentPlayers_pointBalancesDoNotInterfere() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        repo.savePointBalance(p1, 100L, 0L);
        repo.savePointBalance(p2, 50L, 25L);

        assertEquals(100L, repo.load(p1).orElseThrow().availablePoints());
        assertEquals(50L,  repo.load(p2).orElseThrow().availablePoints());
    }

    @Test
    void independentPlayers_perkUnlocksDoNotInterfere() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();

        repo.savePointBalance(p1, 100L, 0L);
        repo.savePointBalance(p2, 100L, 0L);

        repo.unlockPerk(p1, "perk_a", 50L);
        // p2 deliberately does NOT unlock perk_a

        assertTrue(repo.loadPerkIds(p1).orElseThrow().contains("perk_a"));
        assertFalse(repo.loadPerkIds(p2).orElseThrow().contains("perk_a"));
    }
}
