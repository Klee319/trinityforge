package com.trinityforge.config.domains;

import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CraftingFeaturesPotionAliasTest {

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
    void fastDiggingAliasResolvesToHaste() {
        PotionEffectType type = CraftingFeaturesConfig.resolvePotionEffectType("FAST_DIGGING");
        assertNotNull(type, "FAST_DIGGING legacy alias must resolve");
        assertEquals(PotionEffectType.HASTE, type);
    }

    @Test
    void hasteResolvesDirectly() {
        PotionEffectType type = CraftingFeaturesConfig.resolvePotionEffectType("HASTE");
        assertNotNull(type);
        assertEquals(PotionEffectType.HASTE, type);
    }
}
