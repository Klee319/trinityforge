package com.trinityforge.mobs;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DungeonEntryExecutor}(2026-07-27): 「転送に失敗したら鍵を消費しない」という順序が
 * 守られていることを、実際のBukkitテレポートを行わずに検証する(転送成否を差し替え可能にした
 * ユニットテスト)。
 */
class DungeonEntryExecutorTest {

    @Test
    void consumesOnlyWhenAttemptSucceeds() {
        AtomicBoolean consumed = new AtomicBoolean(false);

        boolean result = DungeonEntryExecutor.executeIfSuccessful(() -> true, () -> consumed.set(true));

        assertTrue(result);
        assertTrue(consumed.get());
    }

    @Test
    void doesNotConsumeWhenAttemptFails() {
        AtomicBoolean consumed = new AtomicBoolean(false);

        boolean result = DungeonEntryExecutor.executeIfSuccessful(() -> false, () -> consumed.set(true));

        assertFalse(result);
        assertFalse(consumed.get());
    }
}
