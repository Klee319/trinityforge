package com.trinityforge.config;

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
 * Regression tests for the "silent config corruption" fix: {@link ConfigDomain#load} must not
 * report success on a syntactically broken YAML file, and must never lose the previously-loaded
 * (or pure-default) snapshot when that happens.
 *
 * <p>{@link Plugin} cannot normally be instantiated without a running Bukkit server, so tests here
 * use a minimal dynamic proxy implementing only the three methods {@link ConfigDomain#load} calls
 * ({@code getDataFolder}, {@code getLogger}, {@code saveResource}). Every test pre-creates the
 * target file so {@code file.exists()} is true and {@code saveResource} is never invoked; the proxy
 * fails the test loudly if that assumption is ever violated.
 */
class ConfigDomainTest {

    private static final String RESOURCE_PATH = "test/domain.yml";

    // Deliberately invalid YAML: tab characters are not permitted for indentation by the YAML
    // spec, so SnakeYAML (via Bukkit's YamlConfiguration#load) reliably throws on this.
    private static final String BROKEN_YAML = "value: 1.0\n\tbad-tab-indent: true\n";

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> {
            return switch (method.getName()) {
                case "getDataFolder" -> dataFolder;
                case "getLogger" -> Logger.getLogger("ConfigDomainTest");
                case "saveResource" -> throw new AssertionError(
                        "saveResource() must not be called when the file already exists on disk");
                case "toString" -> "FakePlugin";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static ConfigSchema schema() {
        return new ConfigSchema()
                .field(SchemaField.number("value", SchemaField.Type.DOUBLE, 9.0, 0.0, 100.0));
    }

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), content);
    }

    @Test
    void getReturnsPureSchemaDefaultsBeforeAnyLoad() {
        // H-2: get() must never throw IllegalStateException, even if load() was never called
        // (e.g. saveResource() failed during onEnable and load() bailed out before this point).
        ConfigDomain domain = new ConfigDomain(RESOURCE_PATH, schema());
        assertEquals(9.0, domain.get().getDouble("value"), 0.0);
    }

    @Test
    void malformedYamlOnFirstLoadReturnsFalseAndKeepsPureDefaults(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, RESOURCE_PATH);
        write(file, BROKEN_YAML);

        ConfigDomain domain = new ConfigDomain(RESOURCE_PATH, schema());
        boolean loaded = domain.load(fakePlugin(tempDir));

        assertFalse(loaded, "malformed YAML must not be reported as a successful load");
        assertEquals(9.0, domain.get().getDouble("value"), 0.0,
                "a first-load syntax error must leave the pure schema-default snapshot in place");
    }

    @Test
    void malformedYamlOnReloadKeepsThePreviouslySuccessfulSnapshot(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, RESOURCE_PATH);
        write(file, "value: 42.0\n");

        ConfigDomain domain = new ConfigDomain(RESOURCE_PATH, schema());
        assertTrue(domain.load(fakePlugin(tempDir)));
        assertEquals(42.0, domain.get().getDouble("value"), 0.0);

        write(file, BROKEN_YAML);
        boolean reloaded = domain.load(fakePlugin(tempDir));

        assertFalse(reloaded, "malformed YAML must not be reported as a successful reload");
        assertEquals(42.0, domain.get().getDouble("value"), 0.0,
                "a reload syntax error must not silently roll balance values back to schema "
                        + "defaults; the last successfully-loaded snapshot must be retained");
    }
}
