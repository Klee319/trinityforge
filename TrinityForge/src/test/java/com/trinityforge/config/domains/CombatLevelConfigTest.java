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

    /**
     * 出荷 {@code progression/combat-level.yml} の {@code pillars:} と、config に
     * {@code pillars:} が無いときに使われるコード側の既定値が一致していることを固定する。
     *
     * <p>2026-08-25 まで両者はずれていた(コード側が top1/1.0、出荷 yml が top1/1.5)。
     * {@code pillars:} を持たない config を読んだ環境だけ純特化プレイヤーの戦闘レベルが
     * 67 ではなく 100 になり、それを前提に書かれている {@code combat/damage.yml} の
     * level-cutoff が丸ごとずれる。既定値を書く場所が 2 つある以上、
     * 片方だけ直すと必ずまた割れるのでここで縛る。
     */
    @Test
    void shippedPillarsMatchTheCodeDefaultsSoAConfigWithoutPillarsBehavesTheSame(@TempDir File tempDir)
            throws IOException {
        String shipped;
        try (var stream = getClass().getResourceAsStream("/" + CombatLevelConfig.PATH)) {
            assertTrue(stream != null, "bundled " + CombatLevelConfig.PATH);
            shipped = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        boolean[] shippedResult = new boolean[1];
        var shippedModel = loaded(tempDir, shipped, shippedResult).model();
        assertTrue(shippedResult[0], "the bundled combat-level.yml must load cleanly");

        // pillars: を丸ごと欠いた config -> コード側の既定値が使われる。
        boolean[] fallbackResult = new boolean[1];
        var fallbackModel = loaded(tempDir, """
                skills:
                  LIGHT_WEAPONS: 1.0
                curve:
                  scale: 1.0
                  min-level: 0
                  max-level: 100
                """, fallbackResult).model();

        assertEquals(shippedModel.pillars(), fallbackModel.pillars(),
                "コード側の既定 pillars が出荷 yml とずれている");

        // 純特化 100 の戦闘レベルは 67(=100/1.5)。damage.yml の level-cutoff はこの値を前提にしている。
        assertEquals(67, shippedModel.compute(Map.of("LIGHT_WEAPONS", 100)),
                "純特化 100 の戦闘レベルは 67 のはず(damage.yml の level-cutoff の前提)");
    }
}
