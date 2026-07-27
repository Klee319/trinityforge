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

/**
 * {@link GlyphDamageBoostConfig}: ars_magic.yml B-3「害悪強化」がどのグリフに
 * {@code glyph_damage_multiplier_bonus} を適用するかを決める config-driven な一覧
 * (harmに決め打ちしない設計の裏付け — 一覧を差し替えるだけで対象グリフを変えられることを検証する)。
 * 同じリフレクション偽{@link Plugin}パターン({@link WoodcuttingGimmickConfigTest}と同型)。
 */
class GlyphDamageBoostConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("GlyphDamageBoostConfigTest");
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

    private static GlyphDamageBoostConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, GlyphDamageBoostConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        GlyphDamageBoostConfig config = new GlyphDamageBoostConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultShipsWithHarmOnly(@TempDir File tempDir) throws IOException {
        GlyphDamageBoostConfig config = loaded(tempDir, "boosted-glyphs:\n  - harm\n");
        assertTrue(config.isBoosted("harm"));
        assertFalse(config.isBoosted("ignite"));
    }

    @Test
    void isBoostedIsCaseInsensitive(@TempDir File tempDir) throws IOException {
        GlyphDamageBoostConfig config = loaded(tempDir, "boosted-glyphs:\n  - harm\n");
        assertTrue(config.isBoosted("HARM"));
    }

    @Test
    void addingAnotherGlyphToTheListExtendsTheBoostWithoutCodeChange(@TempDir File tempDir) throws IOException {
        // 横展開の裏付け: harmに決め打ちせず、config側にIDを足すだけで他グリフにも適用できる。
        GlyphDamageBoostConfig config = loaded(tempDir, "boosted-glyphs:\n  - harm\n  - ignite\n");
        assertTrue(config.isBoosted("harm"));
        assertTrue(config.isBoosted("ignite"));
        assertFalse(config.isBoosted("freeze"));
    }

    @Test
    void emptyListMeansNoGlyphIsBoosted(@TempDir File tempDir) throws IOException {
        GlyphDamageBoostConfig config = loaded(tempDir, "boosted-glyphs: []\n");
        assertFalse(config.isBoosted("harm"));
    }

    @Test
    void nullGlyphIdIsNeverBoosted(@TempDir File tempDir) throws IOException {
        GlyphDamageBoostConfig config = loaded(tempDir, "boosted-glyphs:\n  - harm\n");
        assertFalse(config.isBoosted(null));
    }
}
