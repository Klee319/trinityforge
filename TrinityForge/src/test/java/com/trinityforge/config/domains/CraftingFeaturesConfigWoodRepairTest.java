package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.WoodRepairMaterial;
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
 * {@link CraftingFeaturesConfig} の {@code wood-repair.materials.<id>.quick-repair} パース検証:
 * 明示trueの尊重、キー省略時のfalseデフォルト、int-shorthand({@code materials: { id: 200 }})でも
 * quickRepair=falseになること。
 */
class CraftingFeaturesConfigWoodRepairTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesConfigWoodRepairTest");
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

    private static CraftingFeaturesConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "config must parse cleanly");
        return config;
    }

    @Test
    void quickRepairTrueIsParsedForTheSeededEntry(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                wood-repair:
                  materials:
                    compressed_wood_1x:
                      durability: 200
                      quick-repair: true
                """);
        WoodRepairMaterial mat = config.woodRepairMaterial("compressed_wood_1x");
        assertEquals(200, mat.durability());
        assertTrue(mat.quickRepair());
    }

    @Test
    void materialWithoutQuickRepairKeyDefaultsToFalse(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                wood-repair:
                  materials:
                    oak_plank_bundle:
                      durability: 50
                """);
        WoodRepairMaterial mat = config.woodRepairMaterial("oak_plank_bundle");
        assertEquals(50, mat.durability());
        assertFalse(mat.quickRepair());
    }

    @Test
    void intShorthandYieldsQuickRepairFalse(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                wood-repair:
                  materials:
                    compressed_wood_1x: 200
                """);
        WoodRepairMaterial mat = config.woodRepairMaterial("compressed_wood_1x");
        assertEquals(200, mat.durability());
        assertFalse(mat.quickRepair());
    }
}
