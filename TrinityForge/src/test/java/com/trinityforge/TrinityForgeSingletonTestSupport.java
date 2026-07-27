package com.trinityforge;

import java.lang.reflect.Field;

/**
 * Reflective control of the {@link TrinityForge#getInstance()} singleton for tests that exercise code
 * paths depending on it (e.g. {@code dungeon-only-exp} gates reading {@code TrinityForge.getInstance()
 * .dungeonWorldRegistry()} / {@code .config()}) without booting a real plugin lifecycle. {@code instance}
 * is only ever set by {@link TrinityForge#onEnable()} / cleared by {@link TrinityForge#onDisable()}, so a
 * unit test that constructs a listener directly (MockBukkit's {@code createMockPlugin()} has no lifecycle,
 * and pure-Mockito tests have no plugin at all) leaves it {@code null} — this class lets such a test stub
 * it for the duration of one test and restore {@code null} afterward so no stub leaks into a later test
 * (tests run in the same JVM / classloader, so the static field is shared).
 *
 * <p>Test-only: lives under {@code src/test/java} and is never referenced from {@code src/main/java}.
 */
public final class TrinityForgeSingletonTestSupport {

    private TrinityForgeSingletonTestSupport() {
    }

    /** Publishes {@code instance} as the value {@link TrinityForge#getInstance()} returns. */
    public static void set(TrinityForge instance) {
        setInstanceField(instance);
    }

    /** Restores {@link TrinityForge#getInstance()} to {@code null} (its un-enabled/disabled state). */
    public static void clear() {
        setInstanceField(null);
    }

    private static void setInstanceField(TrinityForge value) {
        try {
            Field field = TrinityForge.class.getDeclaredField("instance");
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to set TrinityForge.instance for test", ex);
        }
    }
}
