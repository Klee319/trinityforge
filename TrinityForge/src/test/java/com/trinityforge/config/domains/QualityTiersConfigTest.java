package com.trinityforge.config.domains;

import com.trinityforge.stats.QualityTier;
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
 * Quality-tiers loader (ITEM_ECONOMY_SPEC 5): the tier list length defines the number of quality steps,
 * and each tier names one quality level. Same minimal-{@link Plugin}-proxy pattern as
 * {@code QualityConfigTest}.
 */
class QualityTiersConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("QualityTiersConfigTest");
            case "saveResource" -> throw new AssertionError("file exists; saveResource must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static QualityTiersConfig loadFrom(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, QualityTiersConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        QualityTiersConfig config = new QualityTiersConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void tierCountDrivesEffectiveMaxQuality(@TempDir File tempDir) throws IOException {
        QualityTiersConfig config = loadFrom(tempDir, """
                tiers:
                  - { name: "粗製", color: "gray" }
                  - { name: "常品", color: "white" }
                  - { name: "上製", color: "green" }
                """);
        assertEquals(3, config.tiers().size());
        assertEquals(2, config.effectiveMaxQuality(), "3 tiers -> quality 0..2");
    }

    @Test
    void tierForNamesTheLevelAndClampsOutOfRange(@TempDir File tempDir) throws IOException {
        QualityTiersConfig config = loadFrom(tempDir, """
                tiers:
                  - { name: "粗製", color: "gray" }
                  - { name: "三神", color: "gradient:#ffd76a:#ff7a3c" }
                """);
        assertEquals("粗製", config.tierFor(0).map(QualityTier::name).orElseThrow());
        assertEquals("三神", config.tierFor(1).map(QualityTier::name).orElseThrow());
        assertEquals("三神", config.tierFor(99).map(QualityTier::name).orElseThrow(), "clamped to last tier");
        assertEquals("粗製", config.tierFor(-5).map(QualityTier::name).orElseThrow(), "clamped to first tier");
    }

    @Test
    void emptyTiersMeanNoOverride(@TempDir File tempDir) throws IOException {
        QualityTiersConfig config = loadFrom(tempDir, "tiers: []\n");
        assertEquals(-1, config.effectiveMaxQuality(), "no tiers -> fall back to numeric max-quality");
        assertTrue(config.tierFor(0).isEmpty());
    }
}
