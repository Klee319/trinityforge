package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BrewOwnership}: 醸造所有者PDCの<b>単一定義</b>(キー文字列・書き込み規則・寿命)。
 *
 * <p>2026-07-31 レビュー指摘#1 の是正で、書き込み規則(先着優先 / 差し替え / 自動マーク /
 * 完了時クリア)もこのクラスへ寄せた。読み取り側だけでなく<b>書き込み側の規則</b>もここで固定する
 * (規則が2箇所に散ると「誰に許可を出すか」と「誰に報酬を付けるか」が食い違う)。
 */
class BrewOwnershipTest {

    private ServerMock server;
    private Plugin plugin;
    private Block block;
    private BrewingStand stand;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        block = server.addSimpleWorld("world").getBlockAt(0, 64, 0);
        block.setType(Material.BREWING_STAND);
        stand = (BrewingStand) block.getState();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void ownerOfIsEmptyWhenNeverWritten() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        assertTrue(ownership.ownerOf(stand).isEmpty());
        assertFalse(ownership.isAutomated(stand));
    }

    @Test
    void ownerOfReadsUuidWrittenByAnotherInstanceOfTheSamePlugin() {
        UUID id = UUID.randomUUID();
        // Simulates NativeSkillExperienceListener#rememberBrewer writing via its own BrewOwnership.
        BrewOwnership writer = new BrewOwnership(plugin);
        stand.getPersistentDataContainer().set(
                writer.lastBrewerKey(), PersistentDataType.STRING, id.toString());
        stand.getPersistentDataContainer().set(
                writer.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_MANUAL);
        stand.update();

        // A separately-constructed BrewOwnership (as used by PotionQualityListener) must see the same value.
        BrewOwnership reader = new BrewOwnership(plugin);
        Optional<UUID> owner = reader.ownerOf(stand);
        assertTrue(owner.isPresent());
        assertEquals(id, owner.get());
        assertFalse(reader.isAutomated(stand));
    }

    @Test
    void isAutomatedReflectsHopperMode() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        stand.getPersistentDataContainer().set(
                ownership.brewModeKey(), PersistentDataType.STRING, BrewOwnership.MODE_AUTO);
        stand.update();

        assertTrue(ownership.isAutomated(stand));
    }

    @Test
    void malformedUuidIsIgnoredNotThrown() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        stand.getPersistentDataContainer().set(
                ownership.lastBrewerKey(), PersistentDataType.STRING, "not-a-uuid");
        stand.update();

        assertTrue(ownership.ownerOf(stand).isEmpty());
    }

    // ---- 書き込み規則 (2026-07-31 レビュー指摘#1/#5) ----

    @Test
    void rememberOwnerWritesThroughToTheRealBlockNotJustTheSnapshot() {
        // PDC は BlockState のスナップショットに乗るので、update() を呼ばないと実体に届かない。
        // 別に取り直した BlockState から読めることでその書き戻しを確認する。
        BrewOwnership ownership = new BrewOwnership(plugin);
        Player player = server.addPlayer();

        assertTrue(ownership.rememberOwner(stand, player));

        BrewingStand refetched = (BrewingStand) block.getState();
        assertEquals(Optional.of(player.getUniqueId()), ownership.ownerOf(refetched));
        assertFalse(ownership.isAutomated(refetched), "手投入は manual");
    }

    @Test
    void rememberOwnerNeverOverwritesTheFirstRecordedOwner() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        Player first = server.addPlayer();
        Player second = server.addPlayer();

        assertTrue(ownership.rememberOwner(stand, first));
        assertFalse(ownership.rememberOwner(stand, second), "先着優先なので2人目は書かない");

        assertEquals(Optional.of(first.getUniqueId()),
                ownership.ownerOf((BrewingStand) block.getState()),
                "最後に触った人が所有者になれると、他人の醸造の報酬を最後のクリックで奪えるうえ、"
                        + "未解放プレイヤーが最後に触るだけで解放済みの台のゲート付き醸造を止められる");
    }

    @Test
    void replaceOwnerIsTheOnlyWayToMoveTheRecord() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        Player first = server.addPlayer();
        Player second = server.addPlayer();
        ownership.rememberOwner(stand, first);

        ownership.replaceOwner(stand, second);

        assertEquals(Optional.of(second.getUniqueId()),
                ownership.ownerOf((BrewingStand) block.getState()));
    }

    @Test
    void markAutomatedFlipsTheModeWithoutTouchingTheOwner() {
        BrewOwnership ownership = new BrewOwnership(plugin);
        Player player = server.addPlayer();
        ownership.rememberOwner(stand, player);

        ownership.markAutomated(stand);

        BrewingStand refetched = (BrewingStand) block.getState();
        assertTrue(ownership.isAutomated(refetched));
        assertEquals(Optional.of(player.getUniqueId()), ownership.ownerOf(refetched),
                "ホッパー投入で所有者そのものを書き換えてはいけない");
    }

    @Test
    void clearRemovesBothTheOwnerAndTheMode() {
        // 醸造が1回完了するたびに消える = 「1回手で触ると以降ずっと手動レートが効く」exploit を閉じる。
        // 同時に「ゲート付き醸造はサイクルごとに手投入が要る(ホッパー完全自動化はできない)」の根拠。
        BrewOwnership ownership = new BrewOwnership(plugin);
        Player player = server.addPlayer();
        ownership.rememberOwner(stand, player);
        ownership.markAutomated(stand);

        ownership.clear(stand);

        BrewingStand refetched = (BrewingStand) block.getState();
        assertTrue(ownership.ownerOf(refetched).isEmpty());
        assertFalse(ownership.isAutomated(refetched));
    }

    @Test
    void nullStandOrPlayerIsToleratedByEveryWritePath() {
        BrewOwnership ownership = new BrewOwnership(plugin);

        assertFalse(ownership.rememberOwner(null, server.addPlayer()));
        assertFalse(ownership.rememberOwner(stand, null));
        ownership.replaceOwner(null, null);
        ownership.markAutomated(null);
        ownership.clear(null);

        assertTrue(ownership.ownerOf((BrewingStand) block.getState()).isEmpty());
    }
}
