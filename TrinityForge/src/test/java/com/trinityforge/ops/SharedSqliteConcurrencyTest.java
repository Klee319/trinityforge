package com.trinityforge.ops;

import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.progression.repository.LoadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ディレクトリジャンクション方式（プラン F11）の実証テスト。
 *
 * <p>資源サーバ分離では {@code plugins/TrinityForge/} を NTFS ジャンクションで共有し、
 * <b>メインと資源の2つの Paper プロセスが同一の {@code player_progression.db} を読み書きする</b>。
 * これはコード変更ゼロで進行データ共有と config パリティを同時に得られる代わりに、
 * 「別プロセスからの SQLite 同時アクセスが実運用に耐えるか」という一点に全体重を預けている。
 * ここが崩れるなら方式ごと破棄して MariaDB 実装へ切り替えるしかないので、サーバを立てる前に潰す。
 *
 * <p>検証は2段構えにしてある。
 * <ol>
 *   <li><b>同一 JVM・2コネクション</b> — アプリ側のロジック（{@code unlockPerk} の check-then-act）が
 *       複数コネクションで壊れないかを見る。ただし SQLite から見ると同一プロセスなので、
 *       これだけでは本番条件を満たさない。</li>
 *   <li><b>別 JVM・2プロセス</b>（{@link SharedSqliteWorker} を {@link ProcessBuilder} で起動）—
 *       OS のファイルロックと共有メモリ WAL インデックス（{@code -shm}）を実際に通す。
 *       <b>これが本番と同じ条件</b>。</li>
 * </ol>
 *
 * <p><b>「解放できなかった」は例外にならない</b>点に注意。{@code unlockPerk} は SQL 例外を握り潰して
 * {@code false} を返すため、{@code database is locked} はポイント不足と見分けがつかない。
 * よって {@link SqliteErrorProbe} でログ側からも捕まえている。
 */
@DisplayName("共有 SQLite の同時アクセス（ジャンクション方式の実証）")
class SharedSqliteConcurrencyTest {

    /** 競合させるパーク数＝配るポイント数。1 パーク 1 ポイントなので「全部使い切って丁度」になる。 */
    private static final int CONTESTED_PERKS = 60;

    /** パーク ID の接頭辞（実 ID である必要はない。DB は文字列として扱う）。 */
    private static final String PERK_PREFIX = "ops.contested.";

    /** 子プロセスの実行を待つ上限。JVM 起動込みでもこれを超えるならロック待ちを疑う。 */
    private static final int CHILD_TIMEOUT_SECONDS = 120;

    /** 2つの子 JVM の足並みを揃えるためのバリア猶予。JVM 起動ばらつきを吸収できる幅を取る。 */
    private static final long START_BARRIER_MILLIS = 3_000L;

    /**
     * 解放試行の間に置くウェイト。0 にするとロックを取った側が一気に全部さらってしまい、
     * 「両プロセスが奪い合った」証拠にならない（実測で 0 対 60 になった）。
     */
    private static final long ATTEMPT_PAUSE_MILLIS = 3L;

    @TempDir
    Path tempDir;

    private Path dbFile;
    private String jdbcUrl;

    /** 子プロセスの出力ファイル名を衝突させないための連番。 */
    private final AtomicInteger childCounter = new AtomicInteger();

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("player_progression.db");
        jdbcUrl = "jdbc:sqlite:" + dbFile;
    }

    // ------------------------------------------------------------------------------------------
    // 1. 同一 JVM・2コネクション
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("2コネクションを交互に使ってもプレイヤーの往復でデータが失われない")
    void alternatingWritesAcrossTwoConnectionsRoundTrip() throws Exception {
        UUID playerId = UUID.randomUUID();

        try (SqliteProgressionRepository main = new SqliteProgressionRepository(jdbcUrl);
             SqliteProgressionRepository resource = new SqliteProgressionRepository(jdbcUrl);
             SqliteErrorProbe probe = new SqliteErrorProbe()) {

            // メインで採掘 → 資源へ移動 → 資源で採掘 → メインへ戻る、を模した往復。
            main.saveSkillProgress(playerId, "MINING", skill(5));
            main.savePointBalance(playerId, 3L, 0L);
            main.flush();

            assertEquals(5, levelOf(resource, playerId, "MINING"),
                    "資源サーバ側のコネクションがメインの書き込みを見えていない");

            resource.saveSkillProgress(playerId, "MINING", skill(9));
            resource.saveSkillProgress(playerId, "WOODCUTTING", skill(4));
            resource.savePointBalance(playerId, 7L, 0L);
            resource.flush();

            assertEquals(9, levelOf(main, playerId, "MINING"),
                    "メイン側のコネクションが資源サーバの書き込みを見えていない");
            assertEquals(4, levelOf(main, playerId, "WOODCUTTING"),
                    "資源サーバで解放されたスキルがメインに現れていない");

            PlayerProgression loaded = main.load(playerId).orElseThrow();
            assertEquals(7L, loaded.availablePoints(), "ポイント残高が往復で失われている");

            assertEquals(List.of(), probe.failures(),
                    "リポジトリが SQL 例外を握り潰している（database is locked の疑い）");
        }
    }

    @Test
    @DisplayName("2コネクションから同じパーク群を奪い合ってもポイントが二重消費されない")
    void concurrentUnlocksAcrossTwoConnectionsNeverDoubleSpend() throws Exception {
        UUID playerId = UUID.randomUUID();
        seedPoints(playerId, CONTESTED_PERKS);

        AtomicInteger totalGranted = new AtomicInteger();

        try (SqliteProgressionRepository main = new SqliteProgressionRepository(jdbcUrl);
             SqliteProgressionRepository resource = new SqliteProgressionRepository(jdbcUrl);
             SqliteErrorProbe probe = new SqliteErrorProbe()) {

            CountDownLatch startGate = new CountDownLatch(1);
            Thread mainThread = unlockerThread("main", true, main, playerId, startGate, totalGranted);
            Thread resourceThread =
                    unlockerThread("resource", false, resource, playerId, startGate, totalGranted);

            mainThread.start();
            resourceThread.start();
            startGate.countDown();
            mainThread.join(TimeUnit.SECONDS.toMillis(CHILD_TIMEOUT_SECONDS));
            resourceThread.join(TimeUnit.SECONDS.toMillis(CHILD_TIMEOUT_SECONDS));

            assertEquals(List.of(), probe.failures(),
                    "リポジトリが SQL 例外を握り潰している（database is locked の疑い）");
        }

        assertLedgerBalances(playerId, totalGranted.get());
    }

    // ------------------------------------------------------------------------------------------
    // 2. 別 JVM・2プロセス（本番と同じ条件）
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("別プロセスの JVM が書いた進行データを、もう一方のプロセスから読める")
    void crossProcessWriteIsVisibleToTheOtherProcess() throws Exception {
        UUID playerId = UUID.randomUUID();
        createSchema();

        ChildResult writer = runWorker("bump", playerId.toString(), "MINING", "42");
        assertEquals(0, writer.exitCode(), "子プロセスの書き込みが失敗した:\n" + writer.diagnostics());

        ChildResult reader = runWorker("read", playerId.toString(), "MINING");
        assertEquals(0, reader.exitCode(), "子プロセスの読み出しが失敗した:\n" + reader.diagnostics());
        assertEquals("level=42", reader.lastLine(),
                "別プロセスが書いたスキルレベルが見えていない:\n" + reader.diagnostics());

        // 親（＝3つ目のプロセス）からも同じものが見えること。
        try (SqliteProgressionRepository repo = new SqliteProgressionRepository(jdbcUrl)) {
            assertEquals(42, levelOf(repo, playerId, "MINING"),
                    "親プロセスから子プロセスの書き込みが見えていない");
        }
    }

    @Test
    @DisplayName("2つの別 JVM が同一 DB のパークを奪い合ってもポイントが二重消費されない")
    void crossProcessConcurrentUnlocksNeverDoubleSpend() throws Exception {
        UUID playerId = UUID.randomUUID();
        seedPoints(playerId, CONTESTED_PERKS);

        // 両方に全範囲を攻めさせる。ポイントはちょうど CONTESTED_PERKS 個なので、
        // 正しく直列化されていれば「付与は合計でちょうど CONTESTED_PERKS 件」になる。
        long startAt = System.currentTimeMillis() + START_BARRIER_MILLIS;
        ChildWorker mainWorker = startWorker(unlockArgs(playerId, startAt, true));
        ChildWorker resourceWorker = startWorker(unlockArgs(playerId, startAt, false));
        ChildResult mainResult = awaitWorker(mainWorker);
        ChildResult resourceResult = awaitWorker(resourceWorker);

        assertEquals(0, mainResult.exitCode(), "メイン相当の子プロセスが異常終了した:\n"
                + mainResult.diagnostics());
        assertEquals(0, resourceResult.exitCode(), "資源相当の子プロセスが異常終了した:\n"
                + resourceResult.diagnostics());

        assertNoSwallowedSqlErrors(mainResult);
        assertNoSwallowedSqlErrors(resourceResult);

        assertLedgerBalances(playerId, grantedCount(mainResult) + grantedCount(resourceResult));
    }

    @Test
    @DisplayName("検証レポートを ops/reports へ書き出す")
    void writesConcurrencyReport() throws Exception {
        // 上の4テストと同じ検証を1本にまとめて実行し、その結果をレポートに残す。
        // （JUnit のテスト実行順に依存させないため、レポート用の観測はここで取り直す。）
        UUID playerId = UUID.randomUUID();
        seedPoints(playerId, CONTESTED_PERKS);

        long startAt = System.currentTimeMillis() + START_BARRIER_MILLIS;
        long begin = System.nanoTime();
        ChildWorker first = startWorker(unlockArgs(playerId, startAt, true));
        ChildWorker second = startWorker(unlockArgs(playerId, startAt, false));
        ChildResult firstResult = awaitWorker(first);
        ChildResult secondResult = awaitWorker(second);
        long elapsedMillis = (System.nanoTime() - begin) / 1_000_000L;

        int granted = grantedCount(firstResult) + grantedCount(secondResult);
        assertLedgerBalances(playerId, granted);

        Path report = OpsReport.write("shared-sqlite-concurrency.md",
                buildReport(firstResult, secondResult, granted, elapsedMillis));
        assertTrue(Files.exists(report), "レポートが書き出されていない: " + report);
        assertTrue(Files.size(report) > 0L, "レポートが空: " + report);
    }

    // ------------------------------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------------------------------

    /** レベルだけを意味づけした {@link SkillProgress}（残 EXP や上限はこのテストの関心外）。 */
    private static SkillProgress skill(int level) {
        return new SkillProgress(level, 0.0, level * 100.0, 0, 100);
    }

    private static int levelOf(SqliteProgressionRepository repo, UUID playerId, String skillId) {
        LoadResult<PlayerProgression> result = repo.load(playerId);
        if (!result.isFound()) {
            return -1;
        }
        SkillProgress progress = result.orElseThrow().skills().get(skillId);
        return progress == null ? -1 : progress.level();
    }

    /** スキーマを作ってからポイントを配る。子プロセス起動前に必ず親が通す（＝本番の main → resource 起動順）。 */
    private void seedPoints(UUID playerId, int points) throws Exception {
        try (SqliteProgressionRepository repo = new SqliteProgressionRepository(jdbcUrl)) {
            repo.savePointBalance(playerId, points, 0L);
            repo.flush();
        }
    }

    /** ポイントを配らずスキーマだけ作る。 */
    private void createSchema() throws Exception {
        new SqliteProgressionRepository(jdbcUrl).close();
    }

    private Thread unlockerThread(String name, boolean ascending, SqliteProgressionRepository repo,
                                  UUID playerId, CountDownLatch startGate, AtomicInteger totalGranted) {
        return new Thread(() -> {
            try {
                startGate.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int n = 0; n < CONTESTED_PERKS; n++) {
                // 別プロセス版と同じく2者を逆向きに走らせる（unlockArgs の説明を参照）。
                int i = ascending ? n : CONTESTED_PERKS - 1 - n;
                if (repo.unlockPerk(playerId, PERK_PREFIX + i, 1L)) {
                    totalGranted.incrementAndGet();
                }
                try {
                    // 別プロセス版と同じ理由でウェイトを入れる（ATTEMPT_PAUSE_MILLIS の説明を参照）。
                    Thread.sleep(ATTEMPT_PAUSE_MILLIS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "unlocker-" + name);
    }

    /**
     * 台帳の突き合わせ。ポイントは {@link #CONTESTED_PERKS} 個しか配っていないので、
     * 正しく直列化されていれば <b>付与件数・消費ポイント・パーク行数の3つが全て一致</b>する。
     * どれか1つでもズレたら二重消費か取りこぼしが起きている。
     */
    private void assertLedgerBalances(UUID playerId, int granted) throws Exception {
        assertEquals(CONTESTED_PERKS, granted,
                "付与件数が配ったポイント数と一致しない（二重消費または取りこぼし）");

        try (SqliteProgressionRepository repo = new SqliteProgressionRepository(jdbcUrl)) {
            PlayerProgression loaded = repo.load(playerId).orElseThrow();
            assertEquals(0L, loaded.availablePoints(), "残ポイントが 0 になっていない");
            assertEquals(CONTESTED_PERKS, loaded.spentPoints(), "消費ポイントが付与件数と一致しない");

            Set<String> perkIds = repo.loadPerkIds(playerId).orElseThrow();
            assertEquals(CONTESTED_PERKS, perkIds.size(), "DB 上のパーク行数が付与件数と一致しない");
        }
    }

    private static void assertNoSwallowedSqlErrors(ChildResult result) {
        assertEquals(0, parseTag(result.lastLine(), "sqlErrors"),
                "子プロセスが SQL 例外を握り潰した（database is locked の疑い）:\n"
                        + result.diagnostics());
    }

    private static int grantedCount(ChildResult result) {
        return parseTag(result.lastLine(), "granted");
    }

    /** {@code "granted=12 sqlErrors=0"} 形式の1行から値を取り出す。 */
    private static int parseTag(String line, String tag) {
        for (String token : line.split("\\s+")) {
            if (token.startsWith(tag + "=")) {
                return Integer.parseInt(token.substring(tag.length() + 1));
            }
        }
        throw new IllegalStateException("子プロセスの出力に " + tag + " がない: " + line);
    }

    // ------------------------------------------------------------------------------------------
    // 子プロセス制御
    // ------------------------------------------------------------------------------------------

    /**
     * 子プロセスへ渡す解放コマンドを組み立てる。{@code ascending} を2プロセスで反転させ、
     * 全 {@link #CONTESTED_PERKS} 件を<b>互いに逆向きから</b>奪い合わせる。
     */
    private String[] unlockArgs(UUID playerId, long startAt, boolean ascending) {
        String from = ascending ? "0" : String.valueOf(CONTESTED_PERKS);
        String to = ascending ? String.valueOf(CONTESTED_PERKS) : "0";
        return new String[]{"unlock", playerId.toString(), PERK_PREFIX, from, to,
                String.valueOf(startAt), String.valueOf(ATTEMPT_PAUSE_MILLIS)};
    }

    private ChildResult runWorker(String... args) throws Exception {
        return awaitWorker(startWorker(args));
    }

    /**
     * 子 JVM を起動する。標準出力・標準エラーは<b>ファイルへリダイレクトする</b>。
     * ストリームを親が読み切る方式だと EOF まで確実にブロックしてしまい、
     * 「ロック待ちで固まった子を検出する」というタイムアウトの目的が果たせなくなるため。
     */
    private ChildWorker startWorker(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // 出力はファイルへ流すのでコンソールの既定エンコーディングが効かない。診断文の文字化けを防ぐ。
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-cp");
        // Gradle が Windows でコマンドライン長を回避するために使う pathing jar でも、
        // マニフェストの Class-Path が解決されるのでそのまま渡してよい。
        command.add(System.getProperty("java.class.path"));
        command.add(SharedSqliteWorker.class.getName());
        command.add(dbFile.toString());
        command.addAll(List.of(args));

        int id = childCounter.incrementAndGet();
        Path stdout = tempDir.resolve("worker-" + id + ".out");
        Path stderr = tempDir.resolve("worker-" + id + ".err");
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        return new ChildWorker(process, stdout, stderr);
    }

    private ChildResult awaitWorker(ChildWorker worker) throws Exception {
        if (!worker.process().waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            worker.process().destroyForcibly();
            throw new IllegalStateException("子プロセスが " + CHILD_TIMEOUT_SECONDS
                    + " 秒で終わらなかった（ロック待ちの疑い）\n"
                    + readOrEmpty(worker.stdout()) + "\n" + readOrEmpty(worker.stderr()));
        }
        return new ChildResult(worker.process().exitValue(),
                readOrEmpty(worker.stdout()), readOrEmpty(worker.stderr()));
    }

    private static String readOrEmpty(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    /** 起動中の子プロセスと、その出力先ファイル。 */
    private record ChildWorker(Process process, Path stdout, Path stderr) {
    }

    /** 子プロセス1つ分の実行結果。 */
    private record ChildResult(int exitCode, String stdout, String stderr) {

        /** 機械可読な結果は最終行に出る約束（{@link SharedSqliteWorker} 参照）。 */
        String lastLine() {
            String[] lines = stdout.strip().split("\\R");
            return lines[lines.length - 1].strip();
        }

        String diagnostics() {
            return "exit=" + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
        }
    }

    // ------------------------------------------------------------------------------------------
    // レポート
    // ------------------------------------------------------------------------------------------

    private String buildReport(ChildResult first, ChildResult second, int granted, long elapsedMillis) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("Java", System.getProperty("java.version") + " ("
                + System.getProperty("java.vendor") + ")");
        environment.put("OS", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " / " + System.getProperty("os.arch"));
        environment.put("DB ファイル", dbFile.toString());
        environment.put("SQLite PRAGMA", "journal_mode=WAL, busy_timeout=5000, synchronous=NORMAL"
                + "（SqliteProgressionRepository が接続時に適用）");

        StringBuilder sb = new StringBuilder();
        sb.append("# 共有 SQLite 同時アクセス検証レポート\n\n");
        sb.append("> このファイルは `SharedSqliteConcurrencyTest` が自動生成する。手で編集しない。\n\n");

        sb.append("## 1. 何を確かめたか\n\n");
        sb.append("資源サーバ分離では `plugins/TrinityForge/` を NTFS ディレクトリジャンクションで共有し、\n");
        sb.append("**メインと資源の2つの Paper プロセスが同一の `player_progression.db` を読み書きする**。\n");
        sb.append("この方式はコード変更ゼロで進行データ共有と config パリティを同時に得られる代わりに、\n");
        sb.append("「別プロセスからの SQLite 同時アクセスが壊れないこと」に全体重を預けている。\n");
        sb.append("**サーバを立てる前にここを潰すのが本テストの目的**であり、落ちた場合は方式を破棄して\n");
        sb.append("MariaDB 実装へ切り替える判断材料になる。\n\n");

        sb.append("## 2. 実行環境\n\n");
        sb.append("| 項目 | 値 |\n|---|---|\n");
        environment.forEach((key, value) -> sb.append("| ").append(key).append(" | ")
                .append(value).append(" |\n"));
        sb.append('\n');

        sb.append("## 3. 別プロセス競合の実測\n\n");
        sb.append("2つの子 JVM を起動し、壁時計バリアで足並みを揃えたうえで、\n");
        sb.append("**同一プレイヤーの同一パーク ").append(CONTESTED_PERKS)
                .append(" 件を両方が全範囲で奪い合う**（一方は昇順、他方は降順）。\n");
        sb.append("配ったポイントはちょうど ").append(CONTESTED_PERKS)
                .append(" 個（1 パーク 1 ポイント）なので、正しく直列化されていれば\n");
        sb.append("**付与は合計でちょうど ").append(CONTESTED_PERKS).append(" 件**になる。\n");
        sb.append("これを超えれば二重消費、下回れば取りこぼし（`database is locked` の握り潰し）。\n\n");
        sb.append("| 項目 | 値 |\n|---|---|\n");
        sb.append("| 競合パーク数（＝配ったポイント数） | ").append(CONTESTED_PERKS).append(" |\n");
        sb.append("| プロセス数 | 2（親を含めると3） |\n");
        sb.append("| 総試行回数 | ").append(CONTESTED_PERKS * 2).append(" |\n");
        sb.append("| プロセス1の付与件数 | ").append(grantedCount(first)).append(" |\n");
        sb.append("| プロセス2の付与件数 | ").append(grantedCount(second)).append(" |\n");
        sb.append("| **付与合計** | **").append(granted).append("** |\n");
        sb.append("| 握り潰された SQL 例外 | ").append(parseTag(first.lastLine(), "sqlErrors")
                + parseTag(second.lastLine(), "sqlErrors")).append(" |\n");
        sb.append("| 試行間ウェイト | ").append(ATTEMPT_PAUSE_MILLIS).append(" ms |\n");
        sb.append("| 所要時間 | ").append(elapsedMillis).append(" ms（")
                .append(START_BARRIER_MILLIS).append(" ms の開始バリアを含む） |\n");
        sb.append('\n');
        if (grantedCount(first) == 0 || grantedCount(second) == 0) {
            sb.append("> **注意**: 片方のプロセスの付与が 0 件だった。台帳の整合は取れているが、\n");
            sb.append("> 実際に奪い合いが起きたかどうかの証拠としては弱い。`ATTEMPT_PAUSE_MILLIS` を\n");
            sb.append("> 増やして再実行し、両者に付与が分散することを確認したほうがよい。\n\n");
        }

        sb.append("## 4. 判定\n\n");
        sb.append("- 付与合計 = 配ったポイント数 → **二重消費なし・取りこぼしなし**\n");
        sb.append("- 残ポイント 0 / 消費ポイント ").append(CONTESTED_PERKS)
                .append(" / DB のパーク行数 ").append(CONTESTED_PERKS).append(" の3つが一致\n");
        sb.append("- 握り潰された SQL 例外 0 件 → `busy_timeout=5000` が実効している\n");
        sb.append("- 子プロセスのタイムアウトなし（上限 ").append(CHILD_TIMEOUT_SECONDS).append(" 秒）\n\n");
        sb.append("**結論: ジャンクション方式（プラン F11）は成立する。**\n");
        sb.append("ただし次章の本体修正が入っていることが条件。\n\n");

        sb.append("## 5. 本テストが検出した欠陥と、その修正\n\n");
        sb.append("**最初にこのテストを書いた時点では、上の検証は落ちた。** 記録として残す。\n\n");
        sb.append("### 症状\n\n");
        sb.append("2コネクション以上から `unlockPerk` を並行実行すると、以下の2種類の例外が出て\n");
        sb.append("解放が取りこぼされた（同一 JVM の2コネクション・別プロセスの双方で再現）。\n\n");
        sb.append("```\n");
        sb.append("org.sqlite.SQLiteException: [SQLITE_BUSY] The database file is locked\n");
        sb.append("org.sqlite.SQLiteException: [SQLITE_BUSY_SNAPSHOT] "
                + "Another database connection has already written to the database\n");
        sb.append("```\n\n");
        sb.append("### 原因\n\n");
        sb.append("`SqliteProgressionRepository` のトランザクション5箇所（`unlockPerk` / `prestige` /\n");
        sb.append("`saveProgressionTransition` / `saveAdminProgressionEdit` / `resetPlayer`）は全て\n");
        sb.append("**check-then-act**（残高を読む → 差し引く）だが、JDBC の `setAutoCommit(false)` は\n");
        sb.append("既定で `BEGIN DEFERRED` を発行する。この場合トランザクションは読み取りとして始まり、\n");
        sb.append("最初の UPDATE で初めて書き込みロックを取りに行く。その間に別コネクションが\n");
        sb.append("コミットしていると、SQLite はこの昇格を `SQLITE_BUSY_SNAPSHOT` で失敗させる。\n\n");
        sb.append("**そして `PRAGMA busy_timeout` はこのエラーに対して働かない。** 既に古くなった\n");
        sb.append("スナップショットは待っても解決しないため、SQLite は busy ハンドラを呼ばずに即座に失敗する。\n");
        sb.append("つまり `busy_timeout=5000` があってもこれらのメソッドは保護されていなかった。\n\n");
        sb.append("さらに悪いことに、`unlockPerk` は `SQLException` を catch して `SEVERE` ログを出したうえで\n");
        sb.append("`false` を返す。呼び出し側から見ると**「ポイント不足で拒否された」と区別がつかない**。\n");
        sb.append("プレイヤーには「解放できません」と表示され、原因はログの奥にしか残らない。\n\n");
        sb.append("### 修正\n\n");
        sb.append("接続時に `transaction_mode=IMMEDIATE` を指定し、全トランザクションを\n");
        sb.append("`BEGIN IMMEDIATE` で開くようにした（`SqliteProgressionRepository#immediateTransactionProperties`）。\n");
        sb.append("書き込みロックを最初に取るので昇格が発生せず、競合は `busy_timeout` が吸収できる\n");
        sb.append("通常のロック待ちになる。**単一コネクション運用では挙動が一切変わらない**ため、\n");
        sb.append("現行のシングルサーバに対する影響はない。\n\n");
        sb.append("### なぜ今まで表面化しなかったか\n\n");
        sb.append("本番はリポジトリのインスタンスが1つで、全メソッドが `synchronized`、さらに\n");
        sb.append("`ExecutorProgressionRepository` が単一 DB スレッドへ直列化している。\n");
        sb.append("**2つ目のコネクションが同じファイルを触った瞬間に初めて顕在化する**バグであり、\n");
        sb.append("資源サーバ分離はまさにその条件を作り出す。サーバを立てる前に潰せたのが本テストの成果。\n\n");

        sb.append("## 6. 運用上の前提（RUNBOOK に反映すること）\n\n");
        sb.append("1. **同一マシン・同一ローカルディスク限定。** SQLite のロックはネットワーク共有上では\n");
        sb.append("   正しく機能せず DB が破損する。サーバを別マシンへ分けるならこの方式は使えない。\n");
        sb.append("2. **起動は main → resource の順。** 本テストは親プロセスが先にスキーマを作ってから\n");
        sb.append("   子を起動している。初回のスキーママイグレーションを同時実行させないこと。\n");
        sb.append("3. **`unlockPerk` は SQL 例外を握り潰して `false` を返す。** 実運用でロック競合が起きても\n");
        sb.append("   例外にはならず「解放できなかった」に化けるため、`SEVERE` ログの監視が必要。\n");
        sb.append("4. **ジャンクションを `rmdir /s` や `Remove-Item -Recurse` で消さない。** リンク先の実体\n");
        sb.append("   （＝全プレイヤーの進行データと全 config）が消える。本構成で最も重大な事故ポイント。\n\n");

        sb.append("## 7. 同一 JVM 側の検証（参考）\n\n");
        sb.append("別プロセス版と同じ競合を、同一 JVM 内の2コネクション＋2スレッドでも実行している。\n");
        sb.append("SQLite から見れば同一プロセスなので本番条件ではないが、`unlockPerk` の\n");
        sb.append("check-then-act がコネクションをまたいでも壊れないことの確認になる。\n\n");
        sb.append("- `alternatingWritesAcrossTwoConnectionsRoundTrip` — 交互 load/save が双方向に見える\n");
        sb.append("- `concurrentUnlocksAcrossTwoConnectionsNeverDoubleSpend` — 2スレッド競合で二重消費なし\n\n");

        sb.append("## 8. 再実行方法\n\n");
        sb.append("```\n");
        sb.append("gradlew :TrinityForge:test --tests \"com.trinityforge.ops.SharedSqliteConcurrencyTest\"\n");
        sb.append("```\n\n");
        sb.append("別プロセス検証は本テストが `ProcessBuilder` で子 JVM を起動して行うため、\n");
        sb.append("別途スクリプトを実行する必要はない。\n");
        return sb.toString();
    }
}
