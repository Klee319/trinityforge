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

/**
 * {@link SmithingGimmickConfig}: かまど精錬速度/精錬ボーナスのホッパー自動投入減衰係数
 * (auto-mode-multiplier)のデフォルト/明示上書き/範囲外クランプを検証する。同じリフレクション偽
 * {@link Plugin} パターン({@link WoodcuttingGimmickConfigTest}と同型)。
 */
class SmithingGimmickConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("SmithingGimmickConfigTest");
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

    private static SmithingGimmickConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, SmithingGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        SmithingGimmickConfig config = new SmithingGimmickConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultWhenAbsent(@TempDir File tempDir) throws IOException {
        SmithingGimmickConfig config = loaded(tempDir, "# empty\n");
        assertEquals(0.25, config.autoModeMultiplier(), 1e-9);
    }

    @Test
    void explicitOverride(@TempDir File tempDir) throws IOException {
        SmithingGimmickConfig config = loaded(tempDir, "auto-mode-multiplier: 0.5\n");
        assertEquals(0.5, config.autoModeMultiplier(), 1e-9);
    }

    @Test
    void outOfRangeFallsBackToDefault(@TempDir File tempDir) throws IOException {
        SmithingGimmickConfig config = loaded(tempDir, "auto-mode-multiplier: 1.5\n");
        assertEquals(0.25, config.autoModeMultiplier(), 1e-9);
    }

    @Test
    void negativeFallsBackToDefault(@TempDir File tempDir) throws IOException {
        SmithingGimmickConfig config = loaded(tempDir, "auto-mode-multiplier: -0.1\n");
        assertEquals(0.25, config.autoModeMultiplier(), 1e-9);
    }
}
