package com.trinityforge.command;

import com.mojang.brigadier.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /tf recipes} (2026-07-28 新設): ArsPaper の {@code RecipeBrowserGui} への
 * リフレクション橋渡し({@link com.trinityforge.integration.ars.ArsRecipeBrowserBridge})を
 * 呼び出すコマンド側の分岐だけを検証する。
 *
 * <p>{@link RecipesCommand#open} は {@link InstanceCommand}/{@code BindCommand#resolveTarget} と
 * 同じ idiom で package-private にしてあり、Brigadier の {@code CommandSourceStack} を実際に
 * 組み立てずに直接呼べる。
 */
class RecipesCommandTest {

    private ServerMock server;
    private RecipesCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        command = new RecipesCommand();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("プレイヤー以外(コンソール)は断られる")
    void nonPlayerSenderIsRejected() {
        ConsoleCommandSender console = server.getConsoleSender();

        int result = command.open(console);

        assertEquals(0, result, "console sender must not succeed");
    }

    @Test
    @DisplayName("ArsPaper 不在(テスト環境)ではプレイヤーでも false 経路へ落ちる")
    void unavailableArsPaperFallsBackToFailureForPlayer() {
        PlayerMock player = server.addPlayer("Steve");

        // このテスト環境には ArsPaper (com.arspaper.gui.RecipeBrowserGui) は載っていないため、
        // ArsRecipeBrowserBridge.isAvailable() は必ず false になる — fail-soft 経路の表明。
        int result = command.open(player);

        assertEquals(0, result, "player must fail softly when ArsPaper is unavailable");
        assertEquals(Command.SINGLE_SUCCESS, 1, "sanity: SINGLE_SUCCESS constant is 1");

        String message = player.nextMessage();
        assertTrue(message != null && message.contains("ArsPaper が利用できないため実行できません"),
                "player must be told ArsPaper is unavailable: " + message);
    }
}
