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
 * {@link AlchemyQualityConfig}: {@code stats/alchemy-quality.yml} schema defaults + config-driven
 * overrides, consumed by {@link com.trinityforge.listeners.PotionQualityListener}. Also fixes the
 * "0.5 == +1 every 2 quality points" design decision at the config-default level.
 */
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
        assertEquals(20.0, config.durationTicksPerQuality());
        assertEquals(0.5, config.amplifierPerQuality());
        assertEquals(10.0, config.lingeringSplashDurationTicksPerQuality());
    }

    @Test
    void defaultAmplifierPerQualityYieldsPlusOneEveryTwoQualityPoints() {
        AlchemyQualityConfig config = new AlchemyQualityConfig();
        double perQuality = config.amplifierPerQuality();
        assertEquals(0, (int) Math.floor(perQuality * 1), "1 quality point: floor(0.5*1)=0, no amplifier yet");
        assertEquals(1, (int) Math.floor(perQuality * 2), "2 quality points: floor(0.5*2)=1, +1 amplifier");
        assertEquals(1, (int) Math.floor(perQuality * 3), "3 quality points: floor(0.5*3)=1, still +1");
        assertEquals(2, (int) Math.floor(perQuality * 4), "4 quality points: floor(0.5*4)=2, +2 amplifier");
    }

    @Test
    void bundledYamlResourceLoadsWithoutValidationIssues(@TempDir File tempDir) throws IOException {
        File source = new File("src/main/resources/" + AlchemyQualityConfig.PATH);
        File dest = new File(tempDir, AlchemyQualityConfig.PATH);
        Files.createDirectories(dest.getParentFile().toPath());
        Files.copy(source.toPath(), dest.toPath());

        AlchemyQualityConfig config = new AlchemyQualityConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)), "bundled alchemy-quality.yml must parse without validation issues");
        assertEquals(20.0, config.durationTicksPerQuality());
    }

    @Test
    void valuesAreConfigDriven(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, AlchemyQualityConfig.PATH);
        write(file, "duration-ticks-per-quality: 40.0\namplifier-per-quality: 1.0\n"
                + "lingering-splash-duration-ticks-per-quality: 5.0\n");

        AlchemyQualityConfig config = new AlchemyQualityConfig();
        assertTrue(config.domain().load(fakePlugin(tempDir)));
        assertEquals(40.0, config.durationTicksPerQuality());
        assertEquals(1.0, config.amplifierPerQuality());
        assertEquals(5.0, config.lingeringSplashDurationTicksPerQuality());
    }
}
