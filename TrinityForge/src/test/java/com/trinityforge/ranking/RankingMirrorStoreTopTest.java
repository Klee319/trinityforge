package com.trinityforge.ranking;

import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ランキング上位 N 件取得（外部プラグイン UserRankBoard 向け公開 API の中核）。
 *
 * <p>ここで固定しているのは 4 点。
 * <ol>
 *   <li><b>降順で並ぶ</b> — 順位表なので当たり前だが、これが崩れると全部無意味になる。</li>
 *   <li><b>値 0 の行は返さない</b> — 参加しただけのプレイヤーが 0 で埋める順位表を出さない。</li>
 *   <li><b>未知の stat は空リスト</b>（例外にしない）。列名は SQL のプレースホルダにできないため
 *       許可リストで解決しており、リストに無い文字列を SQL へ埋めてはいけない
 *       （＝ SQL インジェクション）。</li>
 *   <li><b>limit の境界</b> — 0 以下は空、上限で頭打ち。</li>
 *   <li><b>プレステージが順位に効く</b> — プレステージはレベルを 0 へ戻すので、生のレベルで
 *       並べると最も育っているプレイヤーが順位表から消える。</li>
 * </ol>
 */
class RankingMirrorStoreTopTest {

    @TempDir
    Path tempDir;

    private String jdbcUrl() {
        return "jdbc:sqlite:" + tempDir.resolve("player_progression.db").toAbsolutePath();
    }

    // ---- ミラー 4 項目 --------------------------------------------------------------

    @Test
    @DisplayName("top は値の降順で返し、件数は limit で切られる")
    void topReturnsDescendingAndRespectsLimit() throws SQLException {
        UUID low = UUID.randomUUID();
        UUID mid = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(low, "Low", new RankingStats(1, 0, 0, 0));
            store.save(mid, "Mid", new RankingStats(50, 0, 0, 0));
            store.save(high, "High", new RankingStats(99, 0, 0, 0));

            List<RankingEntry> top = store.top("collection_items", 2);

            assertEquals(2, top.size(), "limit で切られること");
            assertEquals(high, top.get(0).uuid());
            assertEquals(99L, top.get(0).value());
            assertEquals("High", top.get(0).name());
            assertEquals(mid, top.get(1).uuid());
            assertEquals(50L, top.get(1).value());
        }
    }

    @Test
    @DisplayName("値が 0 の行は順位表に出さない")
    void topExcludesZeroValuedRows() throws SQLException {
        UUID scored = UUID.randomUUID();
        UUID zero = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(scored, "Scored", new RankingStats(0, 0, 0, 3));
            // 討伐数だけ 0 のプレイヤー。他の項目には値がある。
            store.save(zero, "Zero", new RankingStats(10, 10, 10, 0));

            List<RankingEntry> top = store.top("mob_kills", 10);

            assertEquals(1, top.size(), "mob_kills が 0 の行は落ちること");
            assertEquals(scored, top.get(0).uuid());
        }
    }

    @Test
    @DisplayName("4 項目それぞれが独立した列を見る")
    void eachStatReadsItsOwnColumn() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(11, 22, 33, 44));

            assertEquals(11L, store.top("collection_items", 5).get(0).value());
            assertEquals(22L, store.top("collection_mobs", 5).get(0).value());
            assertEquals(33L, store.top("glyphs_unlocked", 5).get(0).value());
            assertEquals(44L, store.top("mob_kills", 5).get(0).value());
        }
    }

    @Test
    @DisplayName("未知の stat は空リスト（列名を外から受けて SQL に埋めない）")
    void unknownStatReturnsEmptyInsteadOfInjectingSql() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(11, 22, 33, 44));

            assertTrue(store.top("updated_at", 5).isEmpty(), "許可リスト外の実在列も拒否する");
            assertTrue(store.top("player_name", 5).isEmpty());
            assertTrue(store.top(null, 5).isEmpty());
            // 文字列連結していたら DROP まで通ってしまう入力。空リストで返り、
            // 次の save が成功する＝表がまだ生きていることまで確認する。
            assertTrue(store.top("collection_items; DROP TABLE player_ranking_stats", 5).isEmpty());
            store.save(id, "Klee", new RankingStats(12, 22, 33, 44));
            assertEquals(12L, store.top("collection_items", 5).get(0).value());
        }
    }

    @Test
    @DisplayName("limit 0 以下は空、上限は MAX_TOP_LIMIT で頭打ち")
    void limitBoundaries() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(11, 0, 0, 0));

            assertTrue(store.top("collection_items", 0).isEmpty());
            assertTrue(store.top("collection_items", -1).isEmpty());
            assertEquals(1, store.top("collection_items", Integer.MAX_VALUE).size(),
                    "上限は内部で丸められ、例外にも全件走査にもならない");
            assertEquals(1, store.top("collection_items", 1).size());
        }
    }

    @Test
    @DisplayName("stat 名は大文字・前後空白を吸収する")
    void statNameIsNormalized() throws SQLException {
        UUID id = UUID.randomUUID();
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            store.save(id, "Klee", new RankingStats(11, 0, 0, 0));
            assertEquals(1, store.top("  Collection_Items ", 5).size());
        }
    }

    // ---- スキルレベル --------------------------------------------------------------

    @Test
    @DisplayName("スキルレベルの上位は降順、Lv0 は除外、名前はミラーから引く")
    void topSkillLevelOrdersDescendingAndSkipsLevelZero() throws Exception {
        UUID top = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID zero = UUID.randomUUID();
        String url = jdbcUrl();
        try (SqliteProgressionRepository repository = new SqliteProgressionRepository(url)) {
            repository.saveSkillProgress(top, "MINING", new SkillProgress(42, 0, 0, 0, 100));
            repository.saveSkillProgress(second, "MINING", new SkillProgress(7, 0, 0, 0, 100));
            repository.saveSkillProgress(zero, "MINING", new SkillProgress(0, 0, 0, 0, 100));
            // 別スキルの高レベルが MINING の順位表へ混ざらないこと。
            repository.saveSkillProgress(zero, "FARMING", new SkillProgress(99, 0, 0, 0, 100));

            try (RankingMirrorStore store = new RankingMirrorStore(url)) {
                store.save(top, "TopMiner", RankingStats.EMPTY);

                List<RankingEntry> ranking = store.topSkillLevel("MINING", 10);

                assertEquals(2, ranking.size(), "Lv0 は出さない");
                assertEquals(top, ranking.get(0).uuid());
                assertEquals(42L, ranking.get(0).value());
                assertEquals("TopMiner", ranking.get(0).name(), "名前はミラー行から引く");
                assertEquals(second, ranking.get(1).uuid());
                assertEquals("", ranking.get(1).name(),
                        "ミラー未登録なら空文字（呼び出し側でフォールバックできる）");
            }
        }
    }

    @Test
    @DisplayName("スキル ID は大文字へ正規化して照合する（保存時と同じ規則）")
    void topSkillLevelNormalizesSkillId() throws Exception {
        UUID id = UUID.randomUUID();
        String url = jdbcUrl();
        try (SqliteProgressionRepository repository = new SqliteProgressionRepository(url)) {
            repository.saveSkillProgress(id, "MINING", new SkillProgress(5, 0, 0, 0, 100));
            try (RankingMirrorStore store = new RankingMirrorStore(url)) {
                assertEquals(1, store.topSkillLevel("mining", 10).size());
                assertEquals(1, store.topSkillLevel(" Mining ", 10).size());
                assertTrue(store.topSkillLevel("", 10).isEmpty());
                assertTrue(store.topSkillLevel(null, 10).isEmpty());
                assertTrue(store.topSkillLevel("MINING", 0).isEmpty());
            }
        }
    }

    @Test
    @DisplayName("全スキル合計は POWER を除いて足す（派生成長の二重計上を避ける）")
    void topTotalSkillLevelSumsAllSkillsExceptPower() throws Exception {
        UUID generalist = UUID.randomUUID();
        UUID specialist = UUID.randomUUID();
        String url = jdbcUrl();
        try (SqliteProgressionRepository repository = new SqliteProgressionRepository(url)) {
            repository.saveSkillProgress(generalist, "MINING", new SkillProgress(10, 0, 0, 0, 100));
            repository.saveSkillProgress(generalist, "FARMING", new SkillProgress(11, 0, 0, 0, 100));
            repository.saveSkillProgress(specialist, "MINING", new SkillProgress(20, 0, 0, 0, 100));
            // POWER が合計に入ると specialist(20+50=70) が generalist(21) を抜いてしまう。
            repository.saveSkillProgress(specialist, "POWER", new SkillProgress(50, 0, 0, 0, 100));

            try (RankingMirrorStore store = new RankingMirrorStore(url)) {
                List<RankingEntry> ranking = store.topTotalSkillLevel(10);

                assertEquals(2, ranking.size());
                assertEquals(generalist, ranking.get(0).uuid(), "POWER を除けば 21 > 20");
                assertEquals(21L, ranking.get(0).value());
                assertEquals(specialist, ranking.get(1).uuid());
                assertEquals(20L, ranking.get(1).value());
            }
        }
    }

    @Test
    @DisplayName("進行表が未作成の DB でも例外にならず空リスト")
    void skillQueriesReturnEmptyWhenProgressionTableIsMissing() throws SQLException {
        // RankingMirrorStore は player_ranking_stats しか作らない。
        try (RankingMirrorStore store = new RankingMirrorStore(jdbcUrl())) {
            assertTrue(store.topSkillLevel("MINING", 10).isEmpty());
            assertTrue(store.topTotalSkillLevel(10).isEmpty());
        }
    }

    // ---- プレステージ --------------------------------------------------------------

    @Test
    @DisplayName("プレステージ 1 段は上限レベル 1 周ぶんとして数える（Lv0 でも最上位に残る）")
    void topSkillLevelCountsPrestigeAsAFullLevelCap() throws Exception {
        UUID prestiged = UUID.randomUUID();
        UUID nearCap = UUID.randomUUID();
        String url = jdbcUrl();
        try (SqliteProgressionRepository repository = new SqliteProgressionRepository(url)) {
            // プレステージ直後の実際の状態: レベルも累計 EXP も 0 に戻り、段だけが 1 上がる。
            repository.saveSkillProgress(prestiged, "MINING", new SkillProgress(0, 0, 0, 1, 100));
            repository.saveSkillProgress(nearCap, "MINING", new SkillProgress(99, 0, 12345, 0, 100));

            try (RankingMirrorStore store = new RankingMirrorStore(url)) {
                List<RankingEntry> ranking = store.topSkillLevel("MINING", 10);

                assertEquals(2, ranking.size(),
                        "プレステージ直後(Lv0)の行が順位表から消えないこと");
                assertEquals(prestiged, ranking.get(0).uuid(), "1 段(=100) > Lv99");
                assertEquals(100L, ranking.get(0).value(), "値は実効レベル");
                assertEquals(nearCap, ranking.get(1).uuid());
                assertEquals(99L, ranking.get(1).value());
            }
        }
    }

    @Test
    @DisplayName("実効レベルが同じなら、プレステージ段の多いほうを上にする")
    void topSkillLevelBreaksTiesByPrestigeBeforeTotalExp() throws Exception {
        UUID prestiged = UUID.randomUUID();
        UUID atCap = UUID.randomUUID();
        String url = jdbcUrl();
        try (SqliteProgressionRepository repository = new SqliteProgressionRepository(url)) {
            // どちらも実効レベル 100。プレステージ側は累計 EXP まで 0 に戻っているため、
            // 累計 EXP だけで割ると未プレステージの Lv100 に必ず負ける。
            repository.saveSkillProgress(prestiged, "MINING", new SkillProgress(0, 0, 0, 1, 100));
            repository.saveSkillProgress(atCap, "MINING", new SkillProgress(100, 0, 999999, 0, 100));

            try (RankingMirrorStore store = new RankingMirrorStore(url)) {
                List<RankingEntry> ranking = store.topSkillLevel("MINING", 10);

                assertEquals(2, ranking.size());
                assertEquals(prestiged, ranking.get(0).uuid());
                assertEquals(100L, ranking.get(0).value());
                assertEquals(atCap, ranking.get(1).uuid());
            }
        }
    }

    @Test
    @DisplayName("スキル合計もプレステージを足す（プレステージした瞬間に合計が下がらない）")
    void topTotalSkillLevelIncludesPrestige() throws Exception {
        UUID prestiged = UUID.randomUUID();
        UUID generalist = UUID.randomUUID();
        String url = jdbcUrl();
        try (SqliteProgressionRepository repository = new SqliteProgressionRepository(url)) {
            // 採掘を 1 回プレステージし、他は育てていないプレイヤー: 実効合計 100。
            repository.saveSkillProgress(prestiged, "MINING", new SkillProgress(0, 0, 0, 1, 100));
            // 生のレベル合計だと 21 対 0 でこちらが勝ってしまう。
            repository.saveSkillProgress(generalist, "MINING", new SkillProgress(10, 0, 0, 0, 100));
            repository.saveSkillProgress(generalist, "FARMING", new SkillProgress(11, 0, 0, 0, 100));

            try (RankingMirrorStore store = new RankingMirrorStore(url)) {
                List<RankingEntry> ranking = store.topTotalSkillLevel(10);

                assertEquals(2, ranking.size(), "合計 0 扱いで消えないこと");
                assertEquals(prestiged, ranking.get(0).uuid());
                assertEquals(100L, ranking.get(0).value());
                assertEquals(generalist, ranking.get(1).uuid());
                assertEquals(21L, ranking.get(1).value());
            }
        }
    }

    @Test
    @DisplayName("上限レベルはスキル行の値を使う（上限が違えば 1 段の重みも変わる）")
    void effectiveLevelUsesEachRowsOwnLevelCap() throws Exception {
        UUID small = UUID.randomUUID();
        UUID large = UUID.randomUUID();
        String url = jdbcUrl();
        try (SqliteProgressionRepository repository = new SqliteProgressionRepository(url)) {
            repository.saveSkillProgress(small, "MINING", new SkillProgress(5, 0, 0, 1, 50));
            repository.saveSkillProgress(large, "MINING", new SkillProgress(5, 0, 0, 1, 100));

            try (RankingMirrorStore store = new RankingMirrorStore(url)) {
                List<RankingEntry> ranking = store.topSkillLevel("MINING", 10);

                assertEquals(105L, ranking.get(0).value());
                assertEquals(large, ranking.get(0).uuid());
                assertEquals(55L, ranking.get(1).value());
                assertEquals(small, ranking.get(1).uuid());
            }
        }
    }
}
