package com.trinityforge.integration.ars;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Paper plugin class loaders are isolated. ArsPaper declares TrinityForge as its dependency, so
 * ArsPaper can see TF classes, but the reverse is not true: TF's own loader cannot resolve Ars GUI
 * classes. The bridge must therefore resolve its reflective API through ArsPaper's plugin loader.
 */
class ArsRecipeBrowserBridgeTest {

    @Test
    @DisplayName("ArsPaper自身のクラスローダーからレシピGUI APIを解決する")
    void availabilityUsesArsPaperPluginClassLoader() {
        List<String> requested = new ArrayList<>();
        ClassLoader arsPluginLoader = arsPluginLoader(requested);

        assertTrue(ArsRecipeBrowserBridge.isAvailable(arsPluginLoader));
        assertEquals(List.of("com.arspaper.gui.RecipeBrowserGui"), requested,
                "the bridge must ask ArsPaper's loader instead of TF's isolated loader");
    }

    @Test
    @DisplayName("GUIを開くときもArsPaper自身のクラスローダーを使う")
    void openUsesArsPaperPluginClassLoader() {
        List<String> requested = new ArrayList<>();
        ClassLoader arsPluginLoader = arsPluginLoader(requested);
        Player player = mock(Player.class);
        TestRecipeBrowserGui.opened = false;

        assertTrue(ArsRecipeBrowserBridge.open(player, arsPluginLoader));
        assertTrue(TestRecipeBrowserGui.opened);
        assertEquals(List.of("com.arspaper.gui.RecipeBrowserGui"), requested);
    }

    private ClassLoader arsPluginLoader(List<String> requested) {
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                requested.add(name);
                return switch (name) {
                    case "com.arspaper.gui.RecipeBrowserGui" -> TestRecipeBrowserGui.class;
                    case "com.arspaper.gui.BaseGui" -> TestBaseGui.class;
                    default -> super.loadClass(name, resolve);
                };
            }
        };
    }

    public static final class TestRecipeBrowserGui {
        private static boolean opened;

        public TestRecipeBrowserGui(Player player) {
        }

        public void open() {
            opened = true;
        }
    }

    public abstract static class TestBaseGui {
        public void open() {
        }
    }
}
