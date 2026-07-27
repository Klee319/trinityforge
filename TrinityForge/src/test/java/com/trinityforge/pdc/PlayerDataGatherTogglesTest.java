package com.trinityforge.pdc;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDC round-trip for {@link PlayerData}'s gathering-feature toggles (2026-07-25
 * gather-rework-active-framework §2 B-2): {@code veinMiningEnabled}/{@code treeFellEnabled}/
 * {@code autoReplantEnabled}/{@code areaHarvestEnabled}, all default ON, independently settable.
 */
class PlayerDataGatherTogglesTest {

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
    void everyToggleDefaultsToOnWhenNeverSet() {
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);

        assertTrue(data.veinMiningEnabled());
        assertTrue(data.treeFellEnabled());
        assertTrue(data.autoReplantEnabled());
        assertTrue(data.areaHarvestEnabled());
    }

    @Test
    void eachToggleRoundTripsThroughPdcIndependently() {
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);

        data.setVeinMiningEnabled(false);

        assertFalse(data.veinMiningEnabled());
        assertTrue(data.treeFellEnabled(), "unrelated toggle must stay ON");
        assertTrue(data.autoReplantEnabled(), "unrelated toggle must stay ON");
        assertTrue(data.areaHarvestEnabled(), "unrelated toggle must stay ON");
    }

    @Test
    void toggleCanBeTurnedBackOn() {
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);

        data.setTreeFellEnabled(false);
        assertFalse(data.treeFellEnabled());

        data.setTreeFellEnabled(true);
        assertTrue(data.treeFellEnabled());
    }

    @Test
    void autoReplantAndAreaHarvestToggleIndependently() {
        Player player = server.addPlayer();
        PlayerData data = PlayerData.of(player);

        data.setAutoReplantEnabled(false);
        data.setAreaHarvestEnabled(false);

        assertFalse(data.autoReplantEnabled());
        assertFalse(data.areaHarvestEnabled());
        assertTrue(data.veinMiningEnabled());
        assertTrue(data.treeFellEnabled());
    }
}
