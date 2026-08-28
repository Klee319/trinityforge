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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseRequirementsConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("UseRequirementsConfigTest");
            case "saveResource" -> throw new AssertionError("file exists; saveResource must not be called");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    @Test
    void enforceDefaultsTrueWhenKeyIsAbsent(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, UseRequirementsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "# no enforce key\n");
        UseRequirementsConfig config = new UseRequirementsConfig();
        assertTrue(config.load(fakePlugin(tempDir)));
        assertTrue(config.enforce());
    }

    @Test
    void enforceHonorsExplicitFalse(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, UseRequirementsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), "enforce: false\n");
        UseRequirementsConfig config = new UseRequirementsConfig();
        assertTrue(config.load(fakePlugin(tempDir)));
        assertFalse(config.enforce());
    }
}
