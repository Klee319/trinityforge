package com.trinityforge.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ランキングミラーの永続化。
 *
 * <p>ここで守っているのは 2 つ。
 * <ol>
 *   <li><b>単調増加</b> — HuskSync は {@code PlayerJoinEvent} より後にデータを流し込むため、
 *       同期前の小さい値でフラッシュが走りうる。上書きしてしまうと共有値が後退する。</li>
 *   <li><b>別コネクションから見える</b> — メインと資源は別プロセスで同じ DB ファイルを開く。
 *       片方の書き込みがもう片方から読めなければ「サーバ間で共通」が成立しない。</li>
 * </ol>
 */
class RankingMirrorStoreTest {

    @TempDir
    Path tempDir;

    private String jdbcUrl() {
        return "jdbc:sqlite:" + tempDir.resolve("player_progression.db").toAbsolutePath();
    }

    @Test
    @DisplayName("保存した値がそのまま読み戻せる")
    void saveThenLoadRoundTrips() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(31, 12, 7, 4200));

            RankingStats loaded = store.load(id);

            assertEquals(31, loaded.collectionItems());
            assertEquals(12, loaded.collectionMobs());
            assertEquals(43, loaded.collectionEntries(), "図鑑登録数はアイテム＋モブ");
            assertEquals(7, loaded.glyphsUnlocked());
            assertEquals(4200, loaded.mobKills());
        }
    }

    @Test
    @DisplayName("未登録プレイヤーは EMPTY（例外にしない）")
    void unknownPlayerLoadsEmpty() throws SQLException {
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            assertEquals(RankingStats.EMPTY, store.load(UUID.randomUUID()));
        }
    }

    @Test
    @DisplayName("小さい値で上書きしても値は後退しない（HuskSync 同期前フラッシュ対策）")
    void smallerValuesNeverLowerStoredValues() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(40, 20, 9, 5000));

            // HuskSync がまだ流し込んでいない状態のローカル値を想定（全部小さい）。
            store.save(id, "Klee", new RankingStats(0, 0, 0, 0));

            RankingStats loaded = store.load(id);
            assertEquals(40, loaded.collectionItems());
            assertEquals(20, loaded.collectionMobs());
            assertEquals(9, loaded.glyphsUnlocked());
            assertEquals(5000, loaded.mobKills());
        }
    }

    @Test
    @DisplayName("大きい値では更新される（項目ごとに独立して最大を取る）")
    void largerValuesRaiseStoredValuesPerColumn() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(40, 20, 9, 5000));

            // 図鑑アイテムと討伐数だけ増え、他は減った値で来たケース。
            store.save(id, "Klee", new RankingStats(41, 3, 9, 5100));

            RankingStats loaded = store.load(id);
            assertEquals(41, loaded.collectionItems(), "増えた列は上がる");
            assertEquals(20, loaded.collectionMobs(), "減った列は据え置き");
            assertEquals(9, loaded.glyphsUnlocked());
            assertEquals(5100, loaded.mobKills());
        }
    }

    @Test
    @DisplayName("表示名は常に最新で上書きされる（改名しても順位表が古い名前で残らない）")
    void playerNameIsAlwaysOverwritten() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "OldName", new RankingStats(5, 5, 5, 5));
            store.save(id, "NewName", new RankingStats(1, 1, 1, 1));

            assertEquals("NewName", storedName(id));
        }
    }

    @Test
    @DisplayName("delete で行が消える（リセット時に古い最大値を残さない）")
    void deleteRemovesRow() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(40, 20, 9, 5000));
            assertTrue(store.load(id).collectionItems() > 0);

            store.delete(id);

            assertEquals(RankingStats.EMPTY, store.load(id),
                    "delete しないと単調増加のせいでリセット後も古い値が残る");
        }
    }

    @Test
    @DisplayName("同じ DB ファイルを開いた別コネクションから読める（メイン⇔資源の共通化）")
    void secondConnectionSeesFirstConnectionsWrite() throws SQLException {
        UUID id = UUID.randomUUID();
        String url = jdbcUrl();
        try (RankingMirrorStore main = new RankingMirrorStore(url);
             RankingMirrorStore resource = new RankingMirrorStore(url)) {

            main.save(id, "Klee", new RankingStats(11, 22, 33, 44));

            RankingStats seenFromResource = resource.load(id);
            assertEquals(11, seenFromResource.collectionItems());
            assertEquals(22, seenFromResource.collectionMobs());
            assertEquals(33, seenFromResource.glyphsUnlocked());
            assertEquals(44, seenFromResource.mobKills());
        }
    }

    @Test
    @DisplayName("負値は 0 に潰れる（読み取り失敗を最大値として焼き付けない）")
    void negativeValuesAreClampedToZero() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(-5, -1, -9, -100));
            assertEquals(RankingStats.EMPTY, store.load(id));
        }
    }

    /** {@link RankingMirrorStore#load} は名前を返さないので、検証は生 JDBC で行う。 */
    private String storedName(UUID id) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT player_name FROM player_ranking_stats WHERE player_uuid = '" + id + "'")) {
            assertTrue(rs.next(), "行が存在すること");
            return rs.getString(1);
        }
    }
}
