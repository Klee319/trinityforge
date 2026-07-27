package com.trinityforge.command;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BindCommand#resolveTarget(String)}: must never fabricate an offline-mode UUID for a name
 * that does not belong to any real player (the {@code Bukkit.getOfflinePlayer(String)} bug this
 * command used to have — a typo would permanently soulbind an item to an unrecoverable ghost UUID).
 */
class BindCommandTest {

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
    void resolvesAnExactOnlinePlayerByName() {
        PlayerMock online = server.addPlayer("Steve");

        Optional<OfflinePlayer> resolved = BindCommand.resolveTarget("Steve");

        assertTrue(resolved.isPresent());
        assertEquals(online.getUniqueId(), resolved.get().getUniqueId());
    }

    @Test
    void unknownNeverJoinedNameResolvesToEmptyRatherThanFabricatingAUuid() {
        // Nobody by this name has ever joined the mock server (no addPlayer call for it), so the old
        // Bukkit.getOfflinePlayer(String) fallback would have silently minted a fake offline-mode UUID.
        Optional<OfflinePlayer> resolved = BindCommand.resolveTarget("Definitely_Not_A_Real_Player_zzz");

        assertTrue(resolved.isEmpty(),
                "an unknown name must be rejected, never resolved to a fabricated ghost UUID");
    }

    @Test
    void resolvedTargetIsNeverAnArbitraryFabricatedUuidForATypo() {
        server.addPlayer("Steve");

        // A near-miss typo of a real name must not resolve to Steve's UUID nor any fabricated one.
        Optional<OfflinePlayer> resolved = BindCommand.resolveTarget("Stve");

        assertTrue(resolved.isEmpty());
    }
}
