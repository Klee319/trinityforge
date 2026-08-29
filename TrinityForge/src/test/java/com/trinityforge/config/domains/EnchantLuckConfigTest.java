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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EnchantLuckConfig}: {@code stats/enchant-luck.yml} schema defaults + config-driven overrides,
 * consumed by {@link com.trinityforge.listeners.EnchantLuckListener}.
 */
class EnchantLuckConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("EnchantLuckConfigTest");
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
    void schemaDefaultsMatchBundledYaml() {
        EnchantLuckConfig config = new EnchantLuckConfig();
        assertEquals(0.01, config.levelBoostChancePerLuck());
        assertEquals(2, config.levelBoostMaxSteps());
        assertEquals(0.02, config.overenchantBonusChancePerLuck());
        assertEquals(0.005, config.extraEnchantChancePerLuck());
        assertEquals(10.0, config.vanillaParityLuck());
        assertEquals(0.5, config.levelNerfChanceAtZero());
        assertEquals(2, config.levelNerfMaxSteps());
    }

    @Test
    void bundledYamlResourceLoadsWithoutValidationIssues(@TempDir File tempDir) throws IOException {
        // Copies the ACTUAL src/main/resources/stats/enchant-luck.yml bytes, proving the shipped file
        // itself parses cleanly under this schema (not just a hand-written test fixture).
        File source = new File("src/main/resources/" + EnchantLuckConfig.PATH);
        File dest = new File(tempDir, EnchantLuckConfig.PATH);
        Files.createDirectories(dest.getParentFile().toPath());
        Files.copy(source.toPath(), dest.toPath());

        EnchantLuckConfig config = new EnchantLuckConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)), "bundled enchant-luck.yml must parse without validation issues");
        // 出荷値。2026-07-30 に 0.01 -> 0.02 へ引き上げ(overenchant-bonus-chance-per-luck と同値に揃えた)。
        assertEquals(0.02, config.levelBoostChancePerLuck());
        assertEquals(10.0, config.vanillaParityLuck());
        assertEquals(0.5, config.levelNerfChanceAtZero());
        assertEquals(2, config.levelNerfMaxSteps());
    }

    @Test
    void valuesAreConfigDriven(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, EnchantLuckConfig.PATH);
        write(file, "level-boost-chance-per-luck: 0.5\nlevel-boost-max-steps: 4\n"
                + "overenchant-bonus-chance-per-luck: 0.3\nextra-enchant-chance-per-luck: 0.1\n"
                + "vanilla-parity-luck: 8\nlevel-nerf-chance-at-zero: 0.4\nlevel-nerf-max-steps: 3\n");

        EnchantLuckConfig config = new EnchantLuckConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)));
        assertEquals(0.5, config.levelBoostChancePerLuck());
        assertEquals(4, config.levelBoostMaxSteps());
        assertEquals(0.3, config.overenchantBonusChancePerLuck());
        assertEquals(0.1, config.extraEnchantChancePerLuck());
        assertEquals(8.0, config.vanillaParityLuck());
        assertEquals(0.4, config.levelNerfChanceAtZero());
        assertEquals(3, config.levelNerfMaxSteps());
    }
}
