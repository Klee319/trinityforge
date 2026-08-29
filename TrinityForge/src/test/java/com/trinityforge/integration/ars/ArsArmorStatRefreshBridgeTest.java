package com.trinityforge.integration.ars;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Paper plugin class loaders are isolated. ArsPaper declares TrinityForge as its dependency, so
 * ArsPaper can see TF classes, but the reverse is not true: TF's own loader cannot resolve
 * {@code ArmorManaListener}. The bridge must therefore resolve its reflective API through
 * ArsPaper's own plugin loader (mirrors {@link ArsRecipeBrowserBridgeTest}'s seam).
 */
class ArsArmorStatRefreshBridgeTest {

    @Test
    @DisplayName("ArsPaper自身のクラスローダーからArmorManaListenerを解決してstatic recalculateを叩く")
    void refreshInvokesStaticRecalculateThroughArsPaperLoader() {
        List<String> requested = new ArrayList<>();
        ClassLoader arsPluginLoader = arsPluginLoader(requested);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        TestArmorManaListener.recalculated = null;

        assertTrue(ArsArmorStatRefreshBridge.refresh(player, arsPluginLoader));
        assertEquals(player, TestArmorManaListener.recalculated,
                "static recalculateArmorBonus(Player) must be invoked with the same player");
        assertEquals(List.of("com.arspaper.item.ArmorManaListener"), requested,
                "the bridge must ask ArsPaper's loader instead of TF's isolated loader");
    }

    @Test
    @DisplayName("ArsPaperが未導入(クラスが見えない)ならfail-softでfalseを返す")
    void refreshFailsSoftWhenListenerClassMissing() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        ClassLoader missingLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if ("com.arspaper.item.ArmorManaListener".equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };

        assertFalse(ArsArmorStatRefreshBridge.refresh(player, missingLoader));
    }

    private ClassLoader arsPluginLoader(List<String> requested) {
        return new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                requested.add(name);
                return switch (name) {
                    case "com.arspaper.item.ArmorManaListener" -> TestArmorManaListener.class;
                    default -> super.loadClass(name, resolve);
                };
            }
        };
    }

    /** {@code com.arspaper.item.ArmorManaListener} の最小スタンドイン(static recalculateArmorBonus)。 */
    public static final class TestArmorManaListener {
        private static Player recalculated;

        public static void recalculateArmorBonus(Player player) {
            recalculated = player;
        }
    }
}
