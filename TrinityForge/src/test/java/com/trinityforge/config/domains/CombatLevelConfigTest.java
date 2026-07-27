package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the non-finite skill-weight guard: a {@code .nan} / {@code .inf} weight (which SnakeYAML
 * happily parses as a valid Double) must be skipped with a warning rather than poison the combat-level
 * math, while the rest of the weight table still loads. Uses the reflective fake {@link Plugin} pattern
 * shared by the other config-loader tests.
 */
class CombatLevelConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CombatLevelConfigTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static CombatLevelConfig loaded(File tempDir, String yaml, boolean[] outResult) throws IOException {
        File file = new File(tempDir, CombatLevelConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CombatLevelConfig config = new CombatLevelConfig();
        outResult[0] = config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void nonFiniteWeightsAreSkippedButTheRestLoad(@TempDir File tempDir) throws IOException {
        boolean[] result = new boolean[1];
        CombatLevelConfig config = loaded(tempDir, """
                skills:
                  LIGHT_WEAPONS: 1.0
                  HEAVY_WEAPONS: .nan
                  ARCHERY: .inf
                pillars:
                  - top: 2
                    divisor: 2.5
                curve:
                  scale: 1.0
                  min-level: 0
                  max-level: 100
                """, result);

        // Two skipped weights -> load reports not-fully-clean (false).
        assertFalse(result[0]);

        Map<String, Double> weights = config.model().skillWeights();
        assertTrue(weights.containsKey("LIGHT_WEAPONS"));
        assertEquals(1.0, weights.get("LIGHT_WEAPONS"), 0.0);
        assertFalse(weights.containsKey("HEAVY_WEAPONS"), "a .nan weight must be skipped");
        assertFalse(weights.containsKey("ARCHERY"), "a .inf weight must be skipped");
    }

    @Test
    void allFiniteWeightsLoadCleanly(@TempDir File tempDir) throws IOException {
        boolean[] result = new boolean[1];
        CombatLevelConfig config = loaded(tempDir, """
                skills:
                  LIGHT_WEAPONS: 1.0
                  ARCHERY: 2.0
                pillars:
                  - top: 2
                    divisor: 2.5
                curve:
                  scale: 1.0
                  min-level: 0
                  max-level: 100
                """, result);

        assertTrue(result[0]);
        assertEquals(2.0, config.model().skillWeights().get("ARCHERY"), 0.0);
    }
}
