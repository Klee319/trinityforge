package com.trinityforge.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1: {@link EconomyBridge} must stay fully inert (no throw, {@code false}/{@code 0.0}) when Vault is
 * not installed — the core requirement behind "vaultが入っている前提で(なくても効果がないだけで運用可能)".
 */
class EconomyBridgeTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("TrinityForge");
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void resolve_withoutVaultPlugin_isUnavailable() {
        EconomyBridge bridge = EconomyBridge.resolve(plugin);

        assertFalse(bridge.available());
    }

    @Test
    void resolve_withoutVaultPlugin_allMethodsNoOpWithoutThrowing() {
        EconomyBridge bridge = EconomyBridge.resolve(plugin);

        assertFalse(bridge.deposit(player, 100.0));
        assertFalse(bridge.withdraw(player, 100.0));
        assertEquals(0.0, bridge.balance(player));
    }

    @Test
    void unavailable_allMethodsNoOpWithoutThrowing() {
        EconomyBridge bridge = EconomyBridge.unavailable();

        assertFalse(bridge.available());
        assertFalse(bridge.deposit(player, 50.0));
        assertFalse(bridge.withdraw(player, 50.0));
        assertEquals(0.0, bridge.balance(player));
    }

    @Test
    void unavailable_nullPlayerNeverThrows() {
        EconomyBridge bridge = EconomyBridge.unavailable();

        assertFalse(bridge.deposit(null, 10.0));
        assertFalse(bridge.withdraw(null, 10.0));
        assertEquals(0.0, bridge.balance(null));
    }

    @Test
    void of_withProvider_depositDelegatesToEconomy() {
        Economy economy = mock(Economy.class);
        when(economy.depositPlayer(player, 25.0))
                .thenReturn(new EconomyResponse(25.0, 125.0, EconomyResponse.ResponseType.SUCCESS, null));
        EconomyBridge bridge = EconomyBridge.of(economy);

        assertTrue(bridge.available());
        assertTrue(bridge.deposit(player, 25.0));
    }

    @Test
    void of_withProvider_withdrawFailureReturnsFalse() {
        Economy economy = mock(Economy.class);
        when(economy.withdrawPlayer(player, 999.0))
                .thenReturn(new EconomyResponse(0.0, 5.0, EconomyResponse.ResponseType.FAILURE, "insufficient funds"));
        EconomyBridge bridge = EconomyBridge.of(economy);

        assertFalse(bridge.withdraw(player, 999.0));
    }

    @Test
    void of_withProvider_balanceDelegatesToEconomy() {
        Economy economy = mock(Economy.class);
        when(economy.getBalance(player)).thenReturn(42.5);
        EconomyBridge bridge = EconomyBridge.of(economy);

        assertEquals(42.5, bridge.balance(player));
    }

    @Test
    void of_withProvider_nonPositiveAmountIsRejectedWithoutCallingEconomy() {
        Economy economy = mock(Economy.class);
        EconomyBridge bridge = EconomyBridge.of(economy);

        assertFalse(bridge.deposit(player, 0.0));
        assertFalse(bridge.deposit(player, -5.0));
        assertFalse(bridge.deposit(player, Double.NaN));
    }

    @Test
    void of_economyThrowing_degradesToFalseInsteadOfPropagating() {
        Economy economy = mock(Economy.class);
        when(economy.depositPlayer(player, 10.0)).thenThrow(new RuntimeException("boom"));
        EconomyBridge bridge = EconomyBridge.of(economy);

        assertFalse(bridge.deposit(player, 10.0));
    }
}
