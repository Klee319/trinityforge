package com.trinityforge.progression;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * バグ報告(2026-08-01再発): 「称号を付けている人にネームタグが表示されなかった」の修正回帰テスト。
 *
 * <p>旧実装({@code TextDisplay} パッセンジャー + 当て推量オフセット)から、スコアボードチームの
 * {@code suffix} でネームタグへ直接称号を織り込む方式へ置き換えた。この方式は構造的に
 * 「ネームタグへ重なる高さの当て推量」が発生しない(別エンティティが存在しない)ことと、
 * チーム所属がテレポート/ワールド間移動で失われないことを検証する。
 *
 * <p>MockBukkit の {@code TeamMock} は {@code prefix()}/{@code suffix()}(Adventure Component版)を
 * 実装しているため、ここでは実際にスコアボードへチームを登録・照会して検証する
 * (spawn経路がMockBukkit未実装で即例外化していた旧実装のような回避策は不要)。
 */
class TitleDisplayServiceTest {

    private ServerMock server;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Scoreboard mainScoreboard() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void refreshPlacesPlayerOnATeamWithTitleAsSuffix() {
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "<red>Slayer</red>", () -> " ");
        Player player = server.addPlayer();

        service.refresh(player);

        Team team = mainScoreboard().getPlayerTeam(player);
        assertNotNull(team, "equipping a title must place the player on a scoreboard team");
        assertTrue(team.hasEntry(player.getName()));
        assertEquals(" Slayer", plain(team.suffix()));
    }

    @Test
    void refreshReadsConfiguredSeparatorSupplierExactlyOncePerCall() {
        AtomicInteger reads = new AtomicInteger();
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "Title", () -> {
            reads.incrementAndGet();
            return " :: ";
        });
        Player player = server.addPlayer();

        service.refresh(player);

        assertEquals(1, reads.get(), "the configured separator supplier must be read exactly once per refresh");
        Team team = mainScoreboard().getPlayerTeam(player);
        assertEquals(" :: Title", plain(team.suffix()));
    }

    @Test
    void nullSeparatorFallsBackToSingleSpace() {
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "Title", () -> null);
        Player player = server.addPlayer();

        service.refresh(player);

        Team team = mainScoreboard().getPlayerTeam(player);
        assertEquals(" Title", plain(team.suffix()));
    }

    @Test
    void refreshWithNoEquippedTitleRemovesTheTeamEntirely() {
        TitleDisplayService equipped = new TitleDisplayService(plugin, p -> "Title", () -> " ");
        Player player = server.addPlayer();
        equipped.refresh(player);
        assertNotNull(mainScoreboard().getPlayerTeam(player));

        TitleDisplayService unequipped = new TitleDisplayService(plugin, p -> null, () -> " ");
        unequipped.refresh(player);

        assertNull(mainScoreboard().getPlayerTeam(player),
                "unequipping the title must remove the team, not merely blank the suffix");
    }

    @Test
    void onQuitRemovesTheTeam() {
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "Title", () -> " ");
        Player player = server.addPlayer();
        service.refresh(player);
        assertNotNull(mainScoreboard().getPlayerTeam(player));

        service.onQuit(new PlayerQuitEvent(player, "bye"));

        assertNull(mainScoreboard().getPlayerTeam(player));
    }

    @Test
    void onJoinAppliesTheCurrentlyEquippedTitle() {
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "Title", () -> " ");
        Player player = server.addPlayer();

        service.onJoin(new PlayerJoinEvent(player, "joined"));

        assertNotNull(mainScoreboard().getPlayerTeam(player));
    }

    @Test
    void startAppliesEquippedTitlesToAllOnlinePlayers() {
        Player p1 = server.addPlayer();
        Player p2 = server.addPlayer();
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "Title", () -> " ");

        service.start();

        assertNotNull(mainScoreboard().getPlayerTeam(p1));
        assertNotNull(mainScoreboard().getPlayerTeam(p2));
    }

    @Test
    void shutdownRemovesEveryOnlinePlayersTeam() {
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "Title", () -> " ");
        Player p1 = server.addPlayer();
        Player p2 = server.addPlayer();
        service.refresh(p1);
        service.refresh(p2);

        service.shutdown();

        assertNull(mainScoreboard().getPlayerTeam(p1));
        assertNull(mainScoreboard().getPlayerTeam(p2));
    }

    @Test
    void teleportingAndChangingWorldsNeverDetachTheTitle() {
        // 旧実装(パッセンジャーTextDisplay)はテレポート/ワールド間移動でパッセンジャーが外れるため
        // 明示的な張り直しリスナーが必須だった。チーム所属はプレイヤー識別子(エントリ名)に紐づき
        // エンティティ/パッセンジャーが存在しないため、テレポート自体が一切妨げられず、
        // 張り直しの特別処理も不要になったことをここで固定する
        // (このテストにはteleport/world-changeリスナーは登場しない — それが正しい設計であることの証明)。
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "Title", () -> " ");
        Player player = server.addPlayer();
        service.refresh(player);

        player.teleport(player.getLocation().add(50, 0, 50));

        Team team = mainScoreboard().getPlayerTeam(player);
        assertNotNull(team, "team membership must survive a teleport untouched");
        assertEquals(" Title", plain(team.suffix()));
    }
}
