package com.trinityforge.testsupport;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * 再発防止(T3): MockBukkitの未実装APIが投げる {@code UnimplementedOperationException} は
 * {@code TestAbortedException} を継承しているため、JUnitはこれをFAILEDではなくSKIPPEDとして扱う。
 * その結果、アサーションが1つも実行されないままテストが「緑」に見える事故が起きた
 * ({@code PotionQualityListenerTest} で8件中5件が気づかれずスキップされていた実例)。
 *
 * <p>この {@link TestExecutionListener} はJUnit Platformの {@code ServiceLoader}
 * ({@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}) 経由で
 * 自動登録され、テスト実行結果が {@code ABORTED} になった原因の例外を検査する。
 * 原因が {@code org.mockbukkit.mockbukkit.exception.UnimplementedOperationException}
 * (またはそのサブクラス)であるテストを「気づかれていない素通り」として一覧化し、
 * {@code build/test-results/unintended-skips.txt} に書き出す。{@code build.gradle.kts} の
 * {@code test} タスクはこのファイルを実行後にチェックし、存在すればビルドを失敗させる。
 *
 * <p><b>意図的なスキップは対象外</b>: {@code @Disabled} は実行自体が始まらない
 * ({@code executionSkipped}、ここでは扱わない)。{@code Assumptions.assumeTrue/assumeFalse}
 * (例: {@code OfflineMobImportRunner} の環境変数ゲート)は素の {@code TestAbortedException} を
 * 投げるため、このリスナーはそれを無視する — 検出対象はMockBukkit由来の例外だけに絞ってある。
 */
public final class UnintendedSkipGuardListener implements TestExecutionListener {

    private static final String MARKER_FILE = "build/test-results/unintended-skips.txt";
    private static final String UNIMPLEMENTED_EXCEPTION_CLASS_NAME =
            "org.mockbukkit.mockbukkit.exception.UnimplementedOperationException";

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        // Start each run from a clean slate so a stale marker from a previous run never lingers.
        try {
            Path marker = Path.of(MARKER_FILE);
            Files.deleteIfExists(marker);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!testIdentifier.isTest()) {
            return;
        }
        if (testExecutionResult.getStatus() != TestExecutionResult.Status.ABORTED) {
            return;
        }
        Optional<Throwable> cause = testExecutionResult.getThrowable();
        if (cause.isEmpty() || !isUnimplementedOperationException(cause.get())) {
            return;
        }
        record(testIdentifier, cause.get());
    }

    private static boolean isUnimplementedOperationException(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (isAssignableFromByName(t.getClass())) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static boolean isAssignableFromByName(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (UNIMPLEMENTED_EXCEPTION_CLASS_NAME.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void record(TestIdentifier testIdentifier, Throwable cause) {
        String line = testIdentifier.getUniqueId() + " :: " + testIdentifier.getDisplayName()
                + " :: aborted by " + cause.getClass().getName()
                + " (" + String.valueOf(cause.getMessage()) + ")" + System.lineSeparator();
        try {
            Path marker = Path.of(MARKER_FILE);
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
