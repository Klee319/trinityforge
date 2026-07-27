package com.trinityforge.pdc;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PDC round-trip for {@link PlayerData}'s gacha pity (天井) counter accessors
 * (ITEM_ECONOMY_SPEC CR-9 安全弁②). Counters are keyed per pool id so two pools never share state.
 */
class PlayerDataGachaPityTest {

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
    void gachaPityCountDefaultsToZeroWhenNeverSet() {
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);

        assertEquals(0, data.gachaPityCount("standard"));
    }

    @Test
    void setGachaPityCountRoundTripsThroughPdc() {
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);

        data.setGachaPityCount("standard", 7);

        assertEquals(7, data.gachaPityCount("standard"));
    }

    @Test
    void setGachaPityCountClampsNegativeToZero() {
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);

        data.setGachaPityCount("standard", -3);

        assertEquals(0, data.gachaPityCount("standard"));
    }

    @Test
    void countersAreIndependentPerPoolId() {
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);

        data.setGachaPityCount("standard", 5);
        data.setGachaPityCount("tier5", 12);

        assertEquals(5, data.gachaPityCount("standard"));
        assertEquals(12, data.gachaPityCount("tier5"));
    }
}
