package com.trinityforge.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpDecayAdminCommandTest {

    @Test
    @DisplayName("2h / 30m / 90s / 単位なし秒をミリ秒へ直す")
    void parsesHourMinuteSecondAndBareSeconds() {
        assertEquals(2L * 60L * 60L * 1000L,
                ExpDecayAdminCommand.parseDurationMillis("2h").orElseThrow());
        assertEquals(30L * 60L * 1000L,
                ExpDecayAdminCommand.parseDurationMillis("30m").orElseThrow());
        assertEquals(90_000L,
                ExpDecayAdminCommand.parseDurationMillis("90s").orElseThrow());
        assertEquals(3600_000L,
                ExpDecayAdminCommand.parseDurationMillis("3600").orElseThrow());
        assertEquals(2L * 60L * 60L * 1000L,
                ExpDecayAdminCommand.parseDurationMillis("2H").orElseThrow());
    }

    @Test
    @DisplayName("0・負・ゴミは拒否する")
    void rejectsZeroNegativeAndGarbage() {
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("0").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("0s").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("-2h").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("abc").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("2hours").isEmpty());
        assertTrue(ExpDecayAdminCommand.parseDurationMillis("").isEmpty());
    }

    @Test
    @DisplayName("reset の非同期側は DB だけ消し、メモリをもう一度 clear しない")
    void resetWipesStoreWithoutClearingMemoryAgain() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/trinityforge/command/ExpDecayAdminCommand.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("diminishing.clearPlayer"),
                "メインスレッドで即時リセットしないと、コマンド直後の付与が古い蓄積のままになる");
        assertTrue(source.contains("wipeStored"),
                "DB を消さないと、次の定期保存や再ログインで古い蓄積が戻る");
        assertFalse(source.contains("persistence.resetPlayer"),
                "resetPlayer はメモリも消すので、非同期完了までに稼いだ分まで消える");
    }
}
