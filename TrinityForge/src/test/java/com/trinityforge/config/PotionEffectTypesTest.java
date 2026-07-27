package com.trinityforge.config;

import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link PotionEffectTypes#resolve(String)} の解決経路(現行名/レガシー名/namespaced小文字/不正名)を検証する。
 */
class PotionEffectTypesTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void resolvesCurrentBukkitEnumName() {
        PotionEffectType type = PotionEffectTypes.resolve("HASTE");
        assertNotNull(type);
        assertEquals(PotionEffectType.HASTE, type);
    }

    @Test
    void resolvesLegacyJumpAliasToJumpBoost() {
        PotionEffectType type = PotionEffectTypes.resolve("JUMP");
        assertNotNull(type, "legacy JUMP alias must resolve to JUMP_BOOST");
        assertEquals(PotionEffectType.JUMP_BOOST, type);
    }

    @Test
    void resolvesLegacyFastDiggingAliasToHaste() {
        PotionEffectType type = PotionEffectTypes.resolve("FAST_DIGGING");
        assertNotNull(type);
        assertEquals(PotionEffectType.HASTE, type);
    }

    @Test
    void resolvesLegacySlowAliasToSlowness() {
        PotionEffectType type = PotionEffectTypes.resolve("SLOW");
        assertNotNull(type, "legacy SLOW alias must resolve to SLOWNESS");
        assertEquals(PotionEffectType.SLOWNESS, type);
    }

    @Test
    void resolvesNamespacedLowercaseKey() {
        PotionEffectType type = PotionEffectTypes.resolve("jump_boost");
        assertNotNull(type);
        assertEquals(PotionEffectType.JUMP_BOOST, type);
    }

    @Test
    void returnsNullForInvalidName() {
        assertNull(PotionEffectTypes.resolve("NOT_A_REAL_EFFECT"));
    }

    @Test
    void returnsNullForBlankOrNull() {
        assertNull(PotionEffectTypes.resolve(""));
        assertNull(PotionEffectTypes.resolve(null));
    }
}
