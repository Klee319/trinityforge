package com.trinityforge.progression;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * バグ報告「称号を付けている人にネームタグが表示されなかった」の回帰テスト。
 *
 * <p><b>何を固定しているか</b>: 称号は頭上の<b>別行</b>に出す(2026-08-03 のユーザー判断)。
 * したがって「別エンティティを作らない」方式でバグを回避することはできず、
 * <b>置く高さそのものを正しく決める</b>必要がある。真因は旧実装が高さを
 * 「パッセンジャーのマウント点からの相対値」で持っていたこと ── マウント点(高さ×0.75=1.35)を
 * 計算に入れていなかったため、0.35 でも 0.75 でもネームタグ(高さ+0.5=2.3)より下にしか
 * 行かず、重なって名前を隠していた。
 *
 * <p>そこでこのテストは {@link TitleDisplayService#titleAnchorY(double, double)} を直接叩き、
 * <b>どんな入力でも称号がネームタグより上に出る</b>ことを固定する。これは旧実装の 0.35 / 0.75 を
 * 両方とも落とせる検査であり、「見た目を実サーバで確認しないと分からない」状態から抜けている。
 *
 * <p>spawn 経路そのものは MockBukkit が {@code TextDisplay} の生成を実装しておらず例外化するため
 * ここでは踏まない(踏むと SKIPPED に化けて緑のまま壊れる — common-traps.md)。
 * 称号未装備でエンティティを作らない経路だけ実挙動で確認する。
 */
class TitleDisplayServiceTest {

    /** バニラがネームタグを描画する高さ(足元から 高さ+0.5)。テスト側にも独立して書き、実装と突き合わせる。 */
    private static final double VANILLA_NAMETAG_Y_FOR_STANDING_PLAYER = 1.8 + 0.5;
    /** TextDisplay 1行ぶんの文字高の概算。ネームタグと「触れずに」離れているかの判定に使う。 */
    private static final double ONE_LINE_TEXT_HEIGHT = 0.25;

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

    @Test
    void defaultClearancePlacesTheTitleClearOfTheVanillaNametag() {
        double anchor = TitleDisplayService.titleAnchorY(1.8, 0.4);

        assertEquals(2.95, anchor, 1e-9,
                "立ち状態(高さ1.8) + ネームタグ(0.5) + 名前の行1つ分(0.25) + 既定余白(0.4) = 2.95");
        assertTrue(anchor - ONE_LINE_TEXT_HEIGHT / 2
                        > VANILLA_NAMETAG_Y_FOR_STANDING_PLAYER + ONE_LINE_TEXT_HEIGHT / 2,
                "称号の下端がネームタグの上端より上に無いと、名前に重なって隠す(報告されたバグそのもの)");
    }

    @Test
    void theTwoHistoricalGuessesWouldBothHaveOverlappedTheNametag() {
        // 旧実装は「マウント点(高さ×0.75 = 1.35)+ config値」を称号の高さにしていた。
        // 現在の式が返す値と比べることで、旧実装がなぜ名前を隠したかを数値で残す。
        double oldMountPoint = 1.8 * 0.75;
        double oldAnchorWith035 = oldMountPoint + 0.35;
        double oldAnchorWith075 = oldMountPoint + 0.75;

        assertTrue(oldAnchorWith035 < VANILLA_NAMETAG_Y_FOR_STANDING_PLAYER,
                "0.35 は 1.70 でネームタグ(2.3)のはるか下だった");
        assertTrue(oldAnchorWith075 < VANILLA_NAMETAG_Y_FOR_STANDING_PLAYER,
                "0.75 でも 2.10 にしかならず、ネームタグ(2.3)に届いていなかった");
        assertTrue(TitleDisplayService.titleAnchorY(1.8, 0.4) > oldAnchorWith075,
                "現在の式は旧実装のどの当て推量よりも高い位置へ置く");
    }

    @Test
    void hideFromOwnerHidesTheEntityFromTheOwningPlayer() {
        var player = server.addPlayer();
        var entity = player.getWorld().spawnEntity(player.getLocation(), org.bukkit.entity.EntityType.ARMOR_STAND);

        TitleDisplayService.hideFromOwner(plugin, player, entity);

        assertFalse(player.canSee(entity),
                "自分の称号を本人に送ると統合版で当たり判定が付き、Java でも一人称で邪魔になる");
    }

    @Test
    void othersCanSeeATitleTheOwnerCannot() {
        var owner = server.addPlayer();
        var other = server.addPlayer();
        var entity = owner.getWorld().spawnEntity(owner.getLocation(), org.bukkit.entity.EntityType.ARMOR_STAND);

        TitleDisplayService.hideFromOwner(plugin, owner, entity);
        TitleDisplayService.showToEveryoneExceptOwner(plugin, owner, entity);

        assertFalse(owner.canSee(entity), "本人の一人称／F5 の両方から消す(サーバは視点を知らない)");
        assertTrue(other.canSee(entity), "他人からは称号が見えること");
    }

    @Test
    void followTickReHidesFromTheOwnerAfterTeleport() throws Exception {
        // teleport が hideEntity を巻き戻すので、追従tickでも掛け直す。TextDisplay spawn は
        // MockBukkit 未実装のためソースで固定する(踏むと SKIPPED 化する — common-traps.md)。
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/trinityforge/progression/TitleDisplayService.java"));
        int teleport = source.indexOf("display.teleport(target);");
        assertTrue(teleport >= 0, "追従 tick の teleport が無い");
        int rehide = source.indexOf("hideFromOwner(plugin, player, display);", teleport);
        assertTrue(rehide > teleport, "teleport のあとに hideFromOwner が無いと本人に再表示される");
        assertTrue(source.contains("setVisibleByDefault(false)"),
                "既定可視のままでは本人の一人称に称号が出る");
        assertFalse(source.contains("motion.sample"),
                "称号の先読みは本人非表示のあと、他人のネームタグより前へ出すだけになる");
    }

    @Test
    void anyNonNegativeClearanceStaysAtOrAboveTheNametag() {
        for (double clearance : new double[]{0.0, 0.1, 0.4, 1.0, 5.0}) {
            assertTrue(TitleDisplayService.titleAnchorY(1.8, clearance) >= VANILLA_NAMETAG_Y_FOR_STANDING_PLAYER,
                    "余白 " + clearance + " でも称号がネームタグより下に来てはいけない");
        }
    }

    @Test
    void brokenClearanceFallsBackInsteadOfBreakingTheFollow() {
        // 非有限値だけは既定へ戻す。NaN を teleport 先に入れると追従が丸ごと壊れる。
        double fallback = TitleDisplayService.titleAnchorY(1.8, 0.4);

        assertEquals(fallback, TitleDisplayService.titleAnchorY(1.8, Double.NaN), 1e-9);
        assertEquals(fallback, TitleDisplayService.titleAnchorY(1.8, Double.POSITIVE_INFINITY), 1e-9);
        assertEquals(fallback, TitleDisplayService.titleAnchorY(1.8, Double.NEGATIVE_INFINITY), 1e-9);
    }

    @Test
    void negativeClearanceLowersTheTitleInsteadOfRaisingIt() {
        // ⚠ 2026-08-24(W-212)の回帰テスト。以前は負値を既定 0.4 へ落としていたので、
        //   「0 まで下げた人がさらに下げようとすると【逆に 0.4 上がる】」という最悪の挙動だった。
        double atZero = TitleDisplayService.titleAnchorY(1.8, 0.0);
        double lowered = TitleDisplayService.titleAnchorY(1.8, -0.3);

        assertEquals(atZero - 0.3, lowered, 1e-9, "負の余白はそのぶん下げる(既定へ戻さない)");
        assertTrue(lowered < atZero, "下げたいのに上がってはいけない");
        // ⚠ ここで「ネームタグと重ならない」までは主張しない。TextDisplay のテキストが
        //   アンカーの上か中心かはサーバ側から観測できず、余白 0 でも実機に隙間が残っていた
        //   (だから 0.3 下げる要望が来た)。つまり式の 0.25 は見積りで、真の重なり判定はできない。
        //   守れるのは「下げる指示が下げる向きに働く」ことと、下限より下へは行かないことだけ。
    }

    @Test
    void shippedClearanceStaysWithinTheConfiguredFloor() throws Exception {
        // 出荷 yml の値が config 側の下限より下だと、起動ごとに警告が出て意図した位置にならない。
        // 「Java の下限」と「出荷 yml」を必ず同時に動かすためのガード。
        java.lang.reflect.Field floor = com.trinityforge.config.domains.SpecialRewardsConfig.class
                .getDeclaredField("MIN_TITLE_NAMETAG_CLEARANCE");
        floor.setAccessible(true);
        double min = (double) floor.get(null);

        String yaml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/progression/special-rewards.yml"));
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?m)^\\s{2}nametag-clearance:\\s*(-?[0-9.]+)").matcher(yaml);
        assertTrue(matcher.find(), "出荷 yml に nametag-clearance が無い");
        double shipped = Double.parseDouble(matcher.group(1));

        assertTrue(shipped >= min,
                "出荷値 " + shipped + " が下限 " + min + " を下回っている(起動ごとに警告が出る)");
        assertTrue(shipped <= 0.0,
                "出荷値は「ネームタグ上端 + 余白」の 0 以下(=下げる側)であること: " + shipped);
    }

    @Test
    void sneakingHeightIsFollowedInsteadOfHardCodingTheStandingHeight() {
        // スニーク中の当たり判定高さは 1.5。ネームタグもそれに追随して 2.0 へ下がるので、
        // 称号も一緒に下がらないと「しゃがむと称号だけ浮く」ことになる。
        assertEquals(1.5 + 0.5 + 0.25 + 0.4, TitleDisplayService.titleAnchorY(1.5, 0.4), 1e-9);
        assertTrue(TitleDisplayService.titleAnchorY(1.5, 0.4) < TitleDisplayService.titleAnchorY(1.8, 0.4),
                "しゃがんだら称号も下がる(高さを定数で埋め込んでいない証明)");
    }

    @Test
    void poseChangeSnapsTeleportDurationSoTheGapToTheNametagDoesNotStretch() {
        // ネームタグのオフセットは姿勢パケットで即変わる。称号だけ duration=3 で補間すると
        // スニーク／立ち上がりで隙間が伸び縮みする。
        double standing = TitleDisplayService.titleAnchorY(1.8, 1.62, -0.3);
        double sneaking = TitleDisplayService.titleAnchorY(1.5, 1.27, -0.3);
        assertEquals(0, TitleDisplayService.followTeleportDuration(standing, sneaking, 3));
        assertEquals(0, TitleDisplayService.followTeleportDuration(sneaking, standing, 3));
        assertEquals(3, TitleDisplayService.followTeleportDuration(standing, standing, 3));
    }

    @Test
    void jumpDoesNotSnapTeleportDurationBecauseTheOffsetIsUnchanged() {
        // ジャンプは足元 Y が動くだけで titleAnchorY は同じ。補間を切ると本体より先に着く。
        double standing = TitleDisplayService.titleAnchorY(1.8, 1.62, -0.3);
        assertEquals(3, TitleDisplayService.followTeleportDuration(standing, standing + 1e-9, 3));
    }

    @Test
    void ridingPostureKeepsTheTitleAboveTheEyes() {
        // W-135: トロッコ搭乗などで姿勢が変わると当たり判定の高さは 0.6 前後まで縮むが、
        // Location の原点は座席側へ上がるので「高さ+0.5」で置くと目の前に来て視界を塞ぐ。
        double crouchedHitbox = 0.6;
        double eyes = 1.27;

        double anchor = TitleDisplayService.titleAnchorY(crouchedHitbox, eyes, 0.4);

        assertTrue(anchor > eyes, "称号は必ず目線より上に置くこと(視界を塞がない): " + anchor);
        assertEquals(eyes + 0.5 + 0.25 + 0.4, anchor, 1e-9, "目線の高さが基準になること");
    }

    @Test
    void standingPostureIsUnchangedByTheEyeHeightArgument() {
        // 立ち状態は 高さ(1.8) > 目線(1.62) なので、従来どおり高さが基準のまま。
        assertEquals(TitleDisplayService.titleAnchorY(1.8, 0.4),
                TitleDisplayService.titleAnchorY(1.8, 1.62, 0.4), 1e-9,
                "目線引数を足しても、立ち状態の位置は1mmも動かないこと");
    }

    @Test
    void brokenEyeHeightIsIgnoredRatherThanBreakingTheAnchor() {
        double heightOnly = TitleDisplayService.titleAnchorY(1.8, 0.4);

        assertEquals(heightOnly, TitleDisplayService.titleAnchorY(1.8, 0.0, 0.4), 1e-9);
        assertEquals(heightOnly, TitleDisplayService.titleAnchorY(1.8, -1.0, 0.4), 1e-9);
        assertEquals(heightOnly, TitleDisplayService.titleAnchorY(1.8, Double.NaN, 0.4), 1e-9);
    }

    @Test
    void brokenHeightFallsBackToTheStandingHeight() {
        double standing = TitleDisplayService.titleAnchorY(1.8, 0.4);

        assertEquals(standing, TitleDisplayService.titleAnchorY(0.0, 0.4), 1e-9);
        assertEquals(standing, TitleDisplayService.titleAnchorY(-1.0, 0.4), 1e-9);
        assertEquals(standing, TitleDisplayService.titleAnchorY(Double.NaN, 0.4), 1e-9);
    }

    /**
     * <b>2026-08-21: 称号をプレイヤーへ騎乗させてはいけない。</b>
     *
     * <p>実サーバ報告「ネームタグが表示されていない(他人の名前も見えない)」。切り分けで
     * <b>称号を外している人のネームタグは出る</b>ことが確認され、称号表示が原因と確定した。
     * 高さの重なり(W-174)ではない —— W-174 の幾何修正は稼働 jar に入っていることを
     * 逆アセンブルで確認済みで、実サーバの clearance 0.1 でも称号はネームタグの上に居る。
     * 2026-08-03〜08-19 の「テレポート追従の独立エンティティ」では同じ高さで名前が見えており、
     * クライアント騎乗(W-153)を足した翌日に消えた。差分は騎乗だけだった。
     *
     * <p>この件は<b>2度</b>「高さの問題」として直され、2度とも再発している。もっともらしい説明で
     * 塞いだつもりになるのを防ぐため、ここでは高さではなく<b>騎乗機構の不在そのもの</b>を固定する。
     * 追従の遅れを消したくなって騎乗を戻すと、代わりにプレイヤーの名前が消える。
     */
    @Test
    void theTitleIsNeverMountedOnThePlayer() {
        for (String gone : new String[] {"mountTranslationY", "passengerAttachmentY",
                "mountPairFor", "mountPairs", "setMountBridgeActive"}) {
            for (Method method : TitleDisplayService.class.getDeclaredMethods()) {
                assertNotEquals(gone, method.getName(),
                        "称号のクライアント騎乗が戻っている(" + gone
                                + ")。騎乗するとプレイヤーのネームタグが消える —— javadoc 参照");
            }
        }
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.trinityforge.progression.TitleDisplayMountBridge"),
                "騎乗ブリッジが復活している。ネームタグが消えるので戻してはいけない");
    }

    /**
     * テレポート追従(＝唯一の経路)が名前の行と重ならないこと。
     *
     * <p>⚠ 2026-08-24(W-212)以降、余白は<b>負にもできる</b>(実機で見て詰めるため)。
     * この検査が守るのは「余白 0 以上なら絶対に重ならない」という部分だけで、
     * 負にしたときの重なりは<b>運用者が選んだ結果</b>として許す。下限は
     * {@code SpecialRewardsConfig.MIN_TITLE_NAMETAG_CLEARANCE} が持つ。
     */
    @Test
    void teleportFollowedTitleNeverOverlapsTheVanillaNametagEither() {
        double[][] postures = {{1.8, 1.62}, {1.5, 1.27}, {0.6, 1.27}};
        for (double[] posture : postures) {
            double height = posture[0];
            double eyes = posture[1];
            for (double clearance : new double[] {0.0, 0.1, 0.2, 0.4, 1.0}) {
                double anchor = TitleDisplayService.titleAnchorY(height, eyes, clearance);
                assertTrue(anchor >= height + 0.5 + 0.25,
                        "テレポート追従時の高さ " + anchor + " が名前の行と重なる");
            }
        }
    }

    @Test
    void refreshWithNoEquippedTitleNeverSpawnsAnythingAndNeverReadsTheClearance() {
        AtomicInteger reads = new AtomicInteger();
        TitleDisplayService service = new TitleDisplayService(plugin, p -> null, () -> {
            reads.incrementAndGet();
            return 0.4;
        });
        Player player = server.addPlayer();

        // 称号未装備なら spawn 経路(MockBukkit未実装で例外化する)へ入らないことが前提。
        assertDoesNotThrow(() -> service.refresh(player));
        assertEquals(0, reads.get(), "称号が無いのに表示位置を計算しに行ってはいけない");
    }

    @Test
    void refreshWithABlankTitleIsTreatedAsUnequipped() {
        TitleDisplayService service = new TitleDisplayService(plugin, p -> "   ", () -> 0.4);
        Player player = server.addPlayer();

        assertDoesNotThrow(() -> service.refresh(player));
    }
}
