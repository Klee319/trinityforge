package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 「頭上の称号」と「装備パーティクル」の位置決めを固定する (2026-08-25 / W-247・W-248、
 * 2026-08-29 に称号の先読みを外した W-299)。
 *
 * <h2>なぜ tick() を直接呼ばないのか</h2>
 * MockBukkit は {@code TextDisplay} の生成と {@code Player#spawnParticle} の観測を実装していない
 * ので、追従の本体を丸ごと動かすテストは書けない(踏むと FAILED ではなく <b>SKIPPED に化ける</b>)。
 * 称号の位置決め({@code nextAnchorFor})とパーティクルの向き({@code emitYaw})を叩く。
 * パーティクルの先読みを外すと軌跡のテストが落ちる。称号の先読みは W-299 で外してある。
 */
class FollowLagCompensationTest {

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

    /**
     * ★ 2026-08-29: 称号の先読みは外す。本人には称号を出さない(W-263)ので、
     * 先読みは「他人から見た補間中のネームタグ」より前へ出すだけになる。
     * 先読みを戻すと走り・方向転換で中心がずれる。
     */
    @Test
    @DisplayName("称号はサーバ位置に置く（先読みしない。見るのは他人だけ）")
    void titleFollowStaysOnTheServerPosition() {
        PlayerMock player = server.addPlayer();
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "<white>x", () -> 0.0);

        Location start = player.getLocation();
        Location first = service.nextAnchorFor(player);
        assertEquals(start.getX(), first.getX(), 1e-9);

        player.teleport(start.clone().add(0.3, 0.0, -0.2));
        Location second = service.nextAnchorFor(player);
        assertEquals(start.getX() + 0.3, second.getX(), 1e-6,
                "先読みが残っているとネームタグより前へ出て中心がずれる");
        assertEquals(start.getZ() - 0.2, second.getZ(), 1e-6);
    }

    @Test
    @DisplayName("ジャンプしても称号の足元からの高さは先読みで伸ばさない")
    void titleDoesNotLeadVerticallyDuringJump() {
        PlayerMock player = server.addPlayer();
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "<white>x", () -> 0.0);
        Location start = player.getLocation();
        service.nextAnchorFor(player);

        player.teleport(start.clone().add(0.0, 0.4, 0.0));
        Location jumped = service.nextAnchorFor(player);
        double expectedY = player.getLocation().getY()
                + TitleDisplayService.titleAnchorY(player.getHeight(), player.getEyeHeight(), 0.0);
        assertEquals(expectedY, jumped.getY(), 1e-6,
                "縦の先読みが残るとジャンプ中にネームタグとの隙間が伸びる");
    }

    @Test
    @DisplayName("称号の高さは頭上のまま")
    void titleHeightIsStillAboveTheHead() {
        PlayerMock player = server.addPlayer();
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "<white>x", () -> 0.0);
        Location anchor = service.nextAnchorFor(player);
        assertEquals(player.getLocation().getY()
                        + TitleDisplayService.titleAnchorY(player.getHeight(), player.getEyeHeight(), 0.0),
                anchor.getY(), 1e-9);
    }

    /**
     * ★ 軌跡(TRAIL)が「走った跡」になっていることの回帰。向いている方向を使う実装に戻すと、
     * 横歩き・後ろ歩きで軌跡が体の前や横へ出る。
     */
    @Test
    @DisplayName("軌跡は進んでいる向きを使う（他の形状は向いている方向のまま）")
    void trailUsesTravelDirectionAndOtherShapesDoNot() {
        // 東(+X)へ進んでいる = yaw -90 相当。向いているのは南(yaw 0)。
        double[] movingEast = {0.3, 0.0, 0.0};
        assertEquals(-90.0, ParticleEffectService.emitYaw(
                SpecialRewardsConfig.Emission.of(SpecialRewardsConfig.Shape.TRAIL), 0.0f, movingEast), 1e-6);
        // 他の形状は向いている方向をそのまま使う。
        assertEquals(0.0, ParticleEffectService.emitYaw(
                SpecialRewardsConfig.Emission.of(SpecialRewardsConfig.Shape.ARC), 0.0f, movingEast), 1e-9);
        assertNotEquals(0.0, ParticleEffectService.emitYaw(
                SpecialRewardsConfig.Emission.of(SpecialRewardsConfig.Shape.TRAIL), 0.0f, movingEast), 1e-9);
    }

    @Test
    @DisplayName("止まっている軌跡は向いている方向の後ろへ出る")
    void standingTrailFallsBackToTheLookDirection() {
        double[] still = {0.0, 0.0, 0.0};
        assertEquals(35.0, ParticleEffectService.emitYaw(
                SpecialRewardsConfig.Emission.of(SpecialRewardsConfig.Shape.TRAIL), 35.0f, still), 1e-9);
    }
}
