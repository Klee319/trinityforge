package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quality is now config-variable (Q = 任意段階, ITEM_ECONOMY_SPEC 5): {@code max-quality} accepts
 * {@code [1, 100]} as the numeric fallback, and the effective max is overridden by the tier count when
 * quality-tiers are configured. {@code give-default-quality} stays config-driven. Follows the same
 * minimal-{@link Plugin}-proxy pattern as {@code ConfigDomainTest} since {@link Plugin} cannot be
 * instantiated without a live server.
 */
class QualityConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("QualityConfigTest");
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

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    @Test
    void numericDefaultsAreUsedWhenNoTierOverride() {
        QualityConfig config = new QualityConfig();
        assertEquals(9, config.maxQuality(), "schema default max-quality is 9 (fallback when no tiers)");
        assertEquals(3, config.giveDefaultQuality());
    }

    @Test
    void spreadUpAndDownDefaultToOnePointFive() {
        QualityConfig config = new QualityConfig();
        assertEquals(1.5, config.spreadUp(), "schema default spread-up is 1.5 (symmetric bell)");
        assertEquals(1.5, config.spreadDown(), "schema default spread-down is 1.5 (symmetric bell)");
    }

    @Test
    void spreadUpAndDownAreConfigDriven(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, QualityConfig.PATH);
        write(file, "spread-up: 3.0\nspread-down: 0.5\n");

        QualityConfig config = new QualityConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)));
        assertEquals(3.0, config.spreadUp(), "wider up side is read from spread-up");
        assertEquals(0.5, config.spreadDown(), "narrower down side is read from spread-down");
    }

    @Test
    void tierCountOverridesNumericMaxQuality() {
        QualityConfig config = new QualityConfig();
        config.useEffectiveMaxOverride(() -> 9); // 10 tiers -> quality 0..9
        assertEquals(9, config.maxQuality());
        config.useEffectiveMaxOverride(() -> 3); // 4 tiers -> quality 0..3
        assertEquals(3, config.maxQuality());
        config.useEffectiveMaxOverride(() -> -1); // no tiers -> numeric fallback (9)
        assertEquals(9, config.maxQuality());
    }

    @Test
    void giveDefaultQualityIsClampedToEffectiveMax() {
        QualityConfig config = new QualityConfig();
        config.useEffectiveMaxOverride(() -> 2); // only 3 tiers
        // default give-default-quality is 3, but the effective max is 2 -> clamped
        assertEquals(2, config.giveDefaultQuality());
    }

    @Test
    void maxQualityWithinRangeIsAccepted(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, QualityConfig.PATH);
        write(file, "max-quality: 50\n");

        QualityConfig config = new QualityConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)));
        assertEquals(50, config.maxQuality());
    }

    @Test
    void maxQualityAboveOneHundredFallsBackToDefault(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, QualityConfig.PATH);
        write(file, "max-quality: 101\n");

        QualityConfig config = new QualityConfig();
        boolean loaded = config.domain().load(fakePlugin(tempDir));

        assertFalse(loaded, "a max-quality above the [1,100] range must be rejected");
        assertEquals(9, config.maxQuality(), "must fall back to the schema default of 9");
    }

    @Test
    void maxQualityBelowOneFallsBackToDefault(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, QualityConfig.PATH);
        write(file, "max-quality: 0\n");

        QualityConfig config = new QualityConfig();
        assertFalse(config.domain().load(fakePlugin(tempDir)));
        assertEquals(9, config.maxQuality());
    }

    @Test
    void rollModelReflectsConfigAndEffectiveMaxQuality(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, QualityConfig.PATH);
        write(file, "roll-spread-up: 0.8\nroll-spread-down: 0.2\nroll-center-inset: 0.12\n");

        QualityConfig config = new QualityConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)));
        config.useEffectiveMaxOverride(() -> 5); // tiers override the numeric max-quality

        var model = config.rollModel();
        assertEquals(5, model.maxQuality(), "roll model uses the effective (tier-derived) max quality");
        assertEquals(0.8, model.rollSpreadUp(), 0.0);
        assertEquals(0.2, model.rollSpreadDown(), 0.0);
        assertEquals(0.12, model.rollCenterInset(), 0.0);
    }

    @Test
    void rollModelDefaultsMatchSchema() {
        var model = new QualityConfig().rollModel();
        assertEquals(0.15, model.rollSpreadUp(), 0.0, "schema default roll-spread-up is 0.15");
        assertEquals(0.15, model.rollSpreadDown(), 0.0, "schema default roll-spread-down is 0.15");
        assertEquals(0.0, model.rollCenterInset(), 0.0, "schema default roll-center-inset is 0.0");
    }

    @Test
    void giveDefaultQualityIsConfigDriven(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, QualityConfig.PATH);
        write(file, "give-default-quality: 1\n");

        QualityConfig config = new QualityConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)));
        assertEquals(1, config.giveDefaultQuality());
    }
}
