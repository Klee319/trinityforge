package com.trinityforge.skilltree.runtime;

import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerkMirrorServiceTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("DB読込失敗時はPDCのlast-known-goodを上書きしない")
    void failedNativeLoadKeepsLastKnownGoodMirror() throws Exception {
        PlayerData.of(player).setHeldPerks(List.of("mining_perk_a"));
        SqliteProgressionRepository repository =
                new SqliteProgressionRepository("jdbc:sqlite::memory:");
        repository.close();
        PerkMirrorService service = new PerkMirrorService(plugin,
                new NativeSkillPerkStatSource(repository), 100L);

        service.sync(player);

        assertEquals(List.of("mining_perk_a"), PlayerData.of(player).heldPerks());
    }

    @Test
    @DisplayName("DB読込成功の空集合はPDCの古いperkを消去する")
    void successfulEmptyNativeLoadClearsStaleMirror() throws Exception {
        PlayerData.of(player).setHeldPerks(List.of("mining_perk_a"));
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            PerkMirrorService service = new PerkMirrorService(plugin,
                    new NativeSkillPerkStatSource(repository), 100L);

            service.sync(player);

            assertTrue(PlayerData.of(player).heldPerks().isEmpty());
        }
    }
}
