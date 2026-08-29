package com.trinityforge.progression;

import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追従の遅れを詰める「1tick先読み」を固定する (2026-08-25 / W-247・W-248、実サーバ報告
 * 「パーティクルの位置同期が遅い」「ネームタグの位置同期がまだネームタグより遅い」)。
 *
 * <p><b>直している遅れの正体</b>: 毎tick読む {@code player.getLocation()} はクライアントが
 * 送ってきた位置で、<b>自分の本体(＝クライアントが予測して即座に描くもの)とネームタグ</b>より
 * 必ず後ろに居る。称号の補間長を本体と揃えても(W-212)このズレは残る ── 揃えたのは
 * <em>補間の長さ</em>で、<em>目標地点そのものが過去</em>だったから。
 */
class MotionLeadTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // --- 純関数(先読み量の算出) ---------------------------------------------------------

    @Test
    @DisplayName("1tickの移動量 × 先読みtick が先読み量になる")
    void leadScalesWithTicks() {
        double[] lead = MotionLead.leadOffset(0.2, 0.0, -0.1, 1.0);
        assertEquals(0.2, lead[0], 1e-9);
        assertEquals(0.0, lead[1], 1e-9);
        assertEquals(-0.1, lead[2], 1e-9);
    }

    /**
     * ★ 安全弁の中核。テレポート・ダンジョン転送・ノックバックでは1tickの差分が数千ブロックになる。
     * 縮めるのではなく<b>丸ごと捨てる</b>のが正解 ── 縮めると「1ブロックだけズレた場所に出る」
     * という原因の分からない不整合になる。
     */
    @Test
    @DisplayName("移動量が上限を超えたら先読みしない（テレポートで称号や粒子が飛ばない）")
    void hugeJumpsAreNotLedAtAll() {
        double[] teleported = MotionLead.leadOffset(5000.0, 0.0, -3000.0, 1.0);
        assertEquals(0.0, teleported[0], 1e-9);
        assertEquals(0.0, teleported[1], 1e-9);
        assertEquals(0.0, teleported[2], 1e-9);
        // 上限のすぐ内側は先読みする(=「速い移動」を一律に捨ててはいない)。
        double[] fast = MotionLead.leadOffset(0.9, 0.0, 0.0, 1.0);
        assertEquals(0.9, fast[0], 1e-9);
    }

    @Test
    @DisplayName("先読み 0 以下・非有限値は先読みしない（NaN を teleport に渡すと追従が丸ごと壊れる）")
    void brokenInputsAreIgnored() {
        assertEquals(0.0, MotionLead.leadOffset(0.2, 0.0, 0.0, 0.0)[0], 1e-9);
        assertEquals(0.0, MotionLead.leadOffset(0.2, 0.0, 0.0, -1.0)[0], 1e-9);
        assertEquals(0.0, MotionLead.leadOffset(Double.NaN, 0.0, 0.0, 1.0)[0], 1e-9);
        assertEquals(0.0, MotionLead.leadOffset(0.2, 0.0, 0.0, Double.NaN)[0], 1e-9);
    }

    // --- 純関数(進行方向の yaw / TRAIL の「後ろ」) ---------------------------------------

    @Test
    @DisplayName("進行方向の yaw を出せる（Bukkit の南=0・時計回り）")
    void travelYawFollowsBukkitConvention() {
        // +Z(南)へ進んでいる → yaw 0
        assertEquals(0.0, MotionLead.travelYaw(0.0, 0.3, 123.0), 1e-6);
        // -X(西)へ進んでいる → yaw 90
        assertEquals(90.0, MotionLead.travelYaw(-0.3, 0.0, 123.0), 1e-6);
        // -Z(北)へ進んでいる → yaw 180
        assertEquals(180.0, Math.abs(MotionLead.travelYaw(0.0, -0.3, 123.0)), 1e-6);
    }

    @Test
    @DisplayName("止まっているときは向いている方向へ倒す（軌跡が勝手に回らない）")
    void travelYawFallsBackWhenStandingStill() {
        assertEquals(42.0, MotionLead.travelYaw(0.0, 0.0, 42.0), 1e-9);
        assertEquals(42.0, MotionLead.travelYaw(0.001, -0.001, 42.0), 1e-9);
        assertEquals(42.0, MotionLead.travelYaw(Double.NaN, 0.0, 42.0), 1e-9);
    }

    // --- 状態を持つ側(前回位置との差分) --------------------------------------------------

    @Test
    @DisplayName("初回の sample は先読みしない（前回位置が無いので移動量が不明）")
    void firstSampleHasNoLead() {
        PlayerMock player = server.addPlayer();
        double[] lead = new MotionLead().sample(player, 1.0);
        assertEquals(0.0, lead[0], 1e-9);
        assertEquals(0.0, lead[2], 1e-9);
    }

    @Test
    @DisplayName("2回目の sample が「前回からの移動量」を先読みにする")
    void secondSampleLeadsByTheObservedMovement() {
        PlayerMock player = server.addPlayer();
        MotionLead motion = new MotionLead();
        Location start = player.getLocation();
        motion.sample(player, 1.0);

        player.teleport(start.clone().add(0.25, 0.0, -0.1));
        double[] lead = motion.sample(player, 1.0);
        assertEquals(0.25, lead[0], 1e-6);
        assertEquals(-0.1, lead[2], 1e-6);
    }

    /**
     * ワールドを跨いだ差分は「移動量」ではない(座標系が別)。ここを見落とすと
     * ダンジョン転送のたびに称号と粒子が変な場所へ飛ぶ。
     */
    @Test
    @DisplayName("ワールドを跨いだら先読みしない")
    void worldChangeResetsTheLead() {
        PlayerMock player = server.addPlayer();
        MotionLead motion = new MotionLead();
        motion.sample(player, 1.0);

        WorldMock other = server.addSimpleWorld("other");
        player.teleport(new Location(other, 10.0, 64.0, 10.0));
        double[] lead = motion.sample(player, 1.0);
        assertEquals(0.0, lead[0], 1e-9);
        assertEquals(0.0, lead[2], 1e-9);
    }

    @Test
    @DisplayName("forget したら次の sample は初回扱い（退出・張り直しで持ち越さない）")
    void forgetDropsTheHistory() {
        PlayerMock player = server.addPlayer();
        MotionLead motion = new MotionLead();
        motion.sample(player, 1.0);
        assertTrue(motion.trackedCount() > 0);

        motion.forget(player.getUniqueId());
        assertEquals(0, motion.trackedCount());

        player.teleport(player.getLocation().clone().add(0.3, 0.0, 0.0));
        assertEquals(0.0, motion.sample(player, 1.0)[0], 1e-9, "忘れた直後は初回扱い");
    }
}
