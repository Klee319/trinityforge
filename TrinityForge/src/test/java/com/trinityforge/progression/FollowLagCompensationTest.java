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
 * 「頭上の称号」と「装備パーティクル」の<b>追従の遅れを詰めた分</b>が実際に効いていることを固定する
 * (2026-08-25 / W-247・W-248)。
 *
 * <h2>なぜ tick() を直接呼ばないのか</h2>
 * MockBukkit は {@code TextDisplay} の生成と {@code Player#spawnParticle} の観測を実装していない
 * ので、追従の本体を丸ごと動かすテストは書けない(踏むと FAILED ではなく <b>SKIPPED に化ける</b>)。
 * そこで<b>位置と向きの決定</b>だけを切り出した口({@code nextAnchorFor} / {@code emitYaw})を叩く。
 * どちらも実装が実際に使っている唯一の経路なので、先読みを外すとここが落ちる。
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
     * ★ 称号の遅れ(W-248)の回帰。先読みを外すと「サーバ位置＝1tick前の位置」に置かれるので、
     * 2回目の呼び出しでも移動ぶんが乗らずここが落ちる。
     */
    @Test
    @DisplayName("称号は1tickぶん先読みした位置へ運ばれる（ネームタグは本体と同じ動きをするので）")
    void titleFollowLeadsByOneTickOfMovement() {
        PlayerMock player = server.addPlayer();
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "<white>x", () -> 0.0);

        Location start = player.getLocation();
        Location first = service.nextAnchorFor(player);
        // 前回位置が無い1回目は先読みしない(移動量が不明)。
        assertEquals(start.getX(), first.getX(), 1e-9);

        player.teleport(start.clone().add(0.3, 0.0, -0.2));
        Location second = service.nextAnchorFor(player);
        assertEquals(start.getX() + 0.3 + 0.3, second.getX(), 1e-6,
                "移動後の位置に、さらに1tickぶんの移動量が乗っていない(＝遅れが残る)");
        assertEquals(start.getZ() - 0.2 - 0.2, second.getZ(), 1e-6);
    }

    @Test
    @DisplayName("称号の高さは先読みに関係なく頭上のまま（横方向だけ先読みする話ではない確認）")
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
