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

class AlchemyQualityConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("AlchemyQualityConfigTest");
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
        AlchemyQualityConfig config = new AlchemyQualityConfig();
        assertEquals(1.0, config.durationPercentPerTenthPoint());
    }

    @Test
    void durationMultiplierIsOnePercentPerTenthPoint() {
        assertEquals(1.01, AlchemyQualityConfig.durationMultiplier(0.1, 1.0), 1e-9);
        assertEquals(1.10, AlchemyQualityConfig.durationMultiplier(1.0, 1.0), 1e-9);
        assertEquals(0.90, AlchemyQualityConfig.durationMultiplier(-1.0, 1.0), 1e-9);
        assertEquals(1.0, AlchemyQualityConfig.durationMultiplier(0.0, 1.0), 1e-9);
    }

    @Test
    void bundledYamlResourceLoadsWithoutValidationIssues(@TempDir File tempDir) throws IOException {
        File source = new File("src/main/resources/" + AlchemyQualityConfig.PATH);
        File dest = new File(tempDir, AlchemyQualityConfig.PATH);
        Files.createDirectories(dest.getParentFile().toPath());
        Files.copy(source.toPath(), dest.toPath());

        AlchemyQualityConfig config = new AlchemyQualityConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)),
                "bundled alchemy-quality.yml must parse without validation issues");
        assertEquals(1.0, config.durationPercentPerTenthPoint());
    }

    @Test
    void valuesAreConfigDriven(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, AlchemyQualityConfig.PATH);
        write(file, "duration-percent-per-tenth-point: 2.0\n");

        AlchemyQualityConfig config = new AlchemyQualityConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)));
        assertEquals(2.0, config.durationPercentPerTenthPoint());
        assertEquals(1.20, AlchemyQualityConfig.durationMultiplier(1.0, config.durationPercentPerTenthPoint()), 1e-9);
    }
}
