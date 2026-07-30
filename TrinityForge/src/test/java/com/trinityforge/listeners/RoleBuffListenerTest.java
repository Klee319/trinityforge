package com.trinityforge.listeners;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.PotionBuffSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleBuffListenerTest {

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
    void persistedRolesLoadedIntoNewPlayerInstanceAreReadOnJoinAndReapplySupportEffect() {
        Player beforeLogout = server.addPlayer();
        PlayerData.of(beforeLogout).setRoles("tank", "digger");

        Player player = server.addPlayer();
        beforeLogout.getPersistentDataContainer()
                .copyTo(player.getPersistentDataContainer(), true);

        RoleBuffListener listener = new RoleBuffListener(configWithDiggerSpeed());
        listener.onJoin(new PlayerJoinEvent(player, Component.empty()));

        assertEquals("tank", PlayerData.of(player).rolePrimary().orElseThrow());
        assertEquals("digger", PlayerData.of(player).roleSupport().orElseThrow());
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals(0, player.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
    }

    private static RoleBuffsConfig configWithDiggerSpeed() {
        PotionBuffSpec potion = new PotionBuffSpec(PotionEffectType.SPEED, 999_999, 0);
        SupportRoleSpec digger = new SupportRoleSpec(
                "digger", "土工", "DIGGING", 1.2, potion, "IRON_SHOVEL", List.of());
        RoleBuffsConfig config = mock(RoleBuffsConfig.class);
        when(config.supportRoles()).thenReturn(Map.of("digger", digger));
        when(config.supportRole("digger")).thenReturn(digger);
        return config;
    }
}
