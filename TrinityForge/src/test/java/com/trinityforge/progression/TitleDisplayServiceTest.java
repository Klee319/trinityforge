package com.trinityforge.progression;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals(2.7, anchor, 1e-9, "立ち状態(高さ1.8)+既定余白0.4 は 1.8+0.5+0.4 = 2.7");
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
    void anyNonNegativeClearanceStaysAtOrAboveTheNametag() {
        for (double clearance : new double[]{0.0, 0.1, 0.4, 1.0, 5.0}) {
            assertTrue(TitleDisplayService.titleAnchorY(1.8, clearance) >= VANILLA_NAMETAG_Y_FOR_STANDING_PLAYER,
                    "余白 " + clearance + " でも称号がネームタグより下に来てはいけない");
        }
    }

    @Test
    void negativeOrBrokenClearanceFallsBackInsteadOfSinkingIntoTheNametag() {
        double fallback = TitleDisplayService.titleAnchorY(1.8, 0.4);

        assertEquals(fallback, TitleDisplayService.titleAnchorY(1.8, -1.0), 1e-9,
                "負の余白は既定へ戻す(そのまま使うと名前へ重なる)");
        assertEquals(fallback, TitleDisplayService.titleAnchorY(1.8, Double.NaN), 1e-9);
        assertEquals(fallback, TitleDisplayService.titleAnchorY(1.8, Double.POSITIVE_INFINITY), 1e-9);
    }

    @Test
    void sneakingHeightIsFollowedInsteadOfHardCodingTheStandingHeight() {
        // スニーク中の当たり判定高さは 1.5。ネームタグもそれに追随して 2.0 へ下がるので、
        // 称号も一緒に下がらないと「しゃがむと称号だけ浮く」ことになる。
        assertEquals(1.5 + 0.5 + 0.4, TitleDisplayService.titleAnchorY(1.5, 0.4), 1e-9);
        assertTrue(TitleDisplayService.titleAnchorY(1.5, 0.4) < TitleDisplayService.titleAnchorY(1.8, 0.4),
                "しゃがんだら称号も下がる(高さを定数で埋め込んでいない証明)");
    }

    @Test
    void ridingPostureKeepsTheTitleAboveTheEyes() {
        // W-135: トロッコ搭乗などで姿勢が変わると当たり判定の高さは 0.6 前後まで縮むが、
        // Location の原点は座席側へ上がるので「高さ+0.5」で置くと目の前に来て視界を塞ぐ。
        double crouchedHitbox = 0.6;
        double eyes = 1.27;

        double anchor = TitleDisplayService.titleAnchorY(crouchedHitbox, eyes, 0.4);

        assertTrue(anchor > eyes, "称号は必ず目線より上に置くこと(視界を塞がない): " + anchor);
        assertEquals(eyes + 0.5 + 0.4, anchor, 1e-9, "目線の高さが基準になること");
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
     * <b>2026-08-19 / W-153</b>: 実サーバ報告「称号の位置がネームタグと同期していない。
     * 少し遅れてついてくる」への対処でクライアント騎乗へ移した。騎乗した乗客の描画基準は
     * 足元ではなく<b>取付点(高さ×0.75)</b>になるので、平行移動はその差分でなければならない。
     * ここを当て推量の定数にしていたのが 2026-08-02 以前の「称号が名前に重なる」バグの正体。
     */
    @Test
    void mountTranslationIsTheGapBetweenTheAttachmentPointAndTheTitleAnchor() {
        double height = 1.8;
        double eyes = 1.62;
        double clearance = 0.4;

        double translation = TitleDisplayService.mountTranslationY(height, eyes, clearance);

        assertEquals(TitleDisplayService.titleAnchorY(height, eyes, clearance) - height * 0.75,
                translation, 1e-9, "平行移動は『置きたい絶対高さ - 取付点』でなければならない");
        // 取付点 + 平行移動 が、置きたい絶対高さに一致すること(= 実際の描画位置が正しい)。
        assertEquals(TitleDisplayService.titleAnchorY(height, eyes, clearance),
                height * 0.75 + translation, 1e-9);
    }

    @Test
    void mountedTitleStaysAboveTheVanillaNametagForEveryPosture() {
        // 立ち / スニーク / 搭乗(当たり判定が縮み目線が上に残る) の3姿勢で、
        // 取付点 + 平行移動 が必ずバニラのネームタグ(高さ+0.5)より上にあること。
        double[][] postures = {{1.8, 1.62}, {1.5, 1.27}, {0.6, 1.27}};
        for (double[] posture : postures) {
            double height = posture[0];
            double eyes = posture[1];
            for (double clearance : new double[] {0.0, 0.1, 0.4, 1.0}) {
                double rendered = height * 0.75
                        + TitleDisplayService.mountTranslationY(height, eyes, clearance);
                assertTrue(rendered >= height + 0.5,
                        "騎乗時の描画高さ " + rendered + " がネームタグ(" + (height + 0.5)
                                + ")より下。名前が隠れるバグへ逆戻りしている");
            }
        }
    }

    @Test
    void brokenHeightDoesNotBlowUpTheMountTranslation() {
        // 高さが壊れた値でも 1.8 として扱い、取付点もその 1.8 で引く(式の両側が同じ既定を使う)。
        assertEquals(TitleDisplayService.mountTranslationY(1.8, 1.62, 0.4),
                TitleDisplayService.mountTranslationY(Double.NaN, 1.62, 0.4), 1e-9);
        assertEquals(TitleDisplayService.mountTranslationY(1.8, 1.62, 0.4),
                TitleDisplayService.mountTranslationY(-1.0, 1.62, 0.4), 1e-9);
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
