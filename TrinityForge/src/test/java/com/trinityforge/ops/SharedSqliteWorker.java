package com.trinityforge.ops;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.progression.repository.LoadResult;

import java.util.UUID;

/**
 * 別 JVM から同一の SQLite ファイルを叩くための子プロセス本体（{@link SharedSqliteConcurrencyTest} が起動する）。
 *
 * <p>2サーバ構成では {@code plugins/TrinityForge/} をディレクトリジャンクションで共有し、
 * <b>2つの Paper プロセスが同じ {@code player_progression.db} を読み書きする</b>。同一 JVM 内の
 * 2コネクションでは、SQLite が別プロセス間で使うファイルロックと共有メモリ WAL インデックス
 * （{@code -shm}）の経路を通らないため、本当に検証したい条件を再現できない。よって実際に
 * プロセスを分ける。
 *
 * <p>終了コード 0 = 成功。標準出力の最終行に機械可読な結果を 1 行だけ出す。
 */
public final class SharedSqliteWorker {

    private SharedSqliteWorker() {
    }

    public static void main(String[] args) {
        try {
            System.out.println(run(args));
            System.exit(0);
        } catch (Exception ex) {
            // SQLITE_BUSY ("database is locked") はここに落ちる。親テストは非ゼロ終了として検出する。
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static String run(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: <dbPath> <command> <playerUuid> [...]");
        }
        String jdbcUrl = "jdbc:sqlite:" + args[0];
        String command = args[1];
        UUID playerId = UUID.fromString(args[2]);

        try (SqliteProgressionRepository repo = new SqliteProgressionRepository(jdbcUrl)) {
            return switch (command) {
                case "unlock" -> {
                    // 2つの子プロセスを本当に同時に競合させるため、壁時計で足並みを揃えてから走り出す。
                    // JVM の起動ばらつき（数百 ms）を吸収しないと、そもそも競合が起きない。
                    awaitStartBarrier(Long.parseLong(args[6]));
                    yield unlock(repo, playerId, args[3], Integer.parseInt(args[4]),
                            Integer.parseInt(args[5]), Long.parseLong(args[7]));
                }
                case "bump" -> bump(repo, playerId, args[3], Integer.parseInt(args[4]));
                case "read" -> read(repo, playerId, args[3]);
                default -> throw new IllegalArgumentException("unknown command: " + command);
            };
        }
    }

    /** {@code startAtEpochMillis} まで待つ（過ぎていれば即座に戻る）。 */
    private static void awaitStartBarrier(long startAtEpochMillis) throws InterruptedException {
        long remaining = startAtEpochMillis - System.currentTimeMillis();
        if (remaining > 0) {
            Thread.sleep(remaining);
        }
    }

    /**
     * パークを 1 ポイントずつ解放し、成功件数を返す。範囲は意図的に重ねて競合させる。
     *
     * <p>{@code from < to} なら昇順、{@code from > to} なら降順に走る。<b>2つのプロセスを逆向きに
     * 走らせるため</b>の指定であり、同方向だと先行した側が毎回わずかに先んじるロックステップに陥って
     * 片側が全件さらってしまう（実測で 60 対 0 になった）。逆向きなら中央で衝突して付与が分散する。
     *
     * <p>{@code unlockPerk} は SQL 例外を握り潰して {@code false} を返す（{@link SqliteErrorProbe} 参照）
     * ため、握り潰された件数も併せて報告する。
     */
    private static String unlock(SqliteProgressionRepository repo, UUID playerId, String perkPrefix,
                                 int from, int to, long pauseMillis) throws InterruptedException {
        boolean ascending = from < to;
        int lowest = Math.min(from, to);
        int highest = Math.max(from, to);
        int granted = 0;
        try (SqliteErrorProbe probe = new SqliteErrorProbe()) {
            for (int n = 0; n < highest - lowest; n++) {
                int i = ascending ? lowest + n : highest - 1 - n;
                if (repo.unlockPerk(playerId, perkPrefix + i, 1L)) {
                    granted++;
                }
                // 試行間にわずかな間を置く。詰めて回すと先に書き込みロックを取った側が
                // 一気に全部さらってしまい、「両者が奪い合った」証拠にならない。
                if (pauseMillis > 0) {
                    Thread.sleep(pauseMillis);
                }
            }
            for (String failure : probe.failures()) {
                System.err.println("[worker] swallowed SQL failure: " + failure);
            }
            return "granted=" + granted + " sqlErrors=" + probe.failures().size();
        }
    }

    /** スキルレベルを書き込む（もう一方のプロセスから読めることの確認用）。 */
    private static String bump(SqliteProgressionRepository repo, UUID playerId, String skillId, int level) {
        repo.saveSkillProgress(playerId, skillId, new SkillProgress(level, 0.0, level * 100.0, 0, 100));
        repo.flush();
        return "level=" + level;
    }

    /** スキルレベルを読み出す。未登録は {@code level=-1}。 */
    private static String read(SqliteProgressionRepository repo, UUID playerId, String skillId) {
        LoadResult<PlayerProgression> result = repo.load(playerId);
        if (!result.isFound()) {
            return "level=-1";
        }
        SkillProgress progress = result.orElseThrow().skills().get(skillId);
        return "level=" + (progress == null ? -1 : progress.level());
    }
}
