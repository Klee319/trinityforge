package com.trinityforge.active;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link CooldownManager}: per-(player, skillId) check-and-consume + remaining-time + quit-cleanup. */
class CooldownManagerTest {

    private final UUID player = UUID.randomUUID();

    @Test
    void firstUseAlwaysConsumes() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "haste-active-mining", 1000L, 0L));
    }

    @Test
    void secondUseBeforeCooldownElapsedIsRefused() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "skill", 1000L, 0L));
        assertFalse(manager.tryConsume(player, "skill", 1000L, 500L));
    }

    @Test
    void secondUseAfterCooldownElapsedIsConsumed() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "skill", 1000L, 0L));
        assertTrue(manager.tryConsume(player, "skill", 1000L, 1000L));
    }

    @Test
    void differentSkillsHaveIndependentCooldowns() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "skill-a", 1000L, 0L));
        assertTrue(manager.tryConsume(player, "skill-b", 1000L, 0L));
    }

    @Test
    void differentPlayersHaveIndependentCooldowns() {
        CooldownManager manager = new CooldownManager();
        UUID otherPlayer = UUID.randomUUID();
        assertTrue(manager.tryConsume(player, "skill", 1000L, 0L));
        assertTrue(manager.tryConsume(otherPlayer, "skill", 1000L, 0L));
    }

    @Test
    void remainingMillisIsZeroWhenNeverUsed() {
        CooldownManager manager = new CooldownManager();
        assertEquals(0L, manager.remainingMillis(player, "skill", 1000L, 0L));
    }

    @Test
    void remainingMillisCountsDownAndNeverGoesNegative() {
        CooldownManager manager = new CooldownManager();
        manager.tryConsume(player, "skill", 1000L, 0L);
        assertEquals(600L, manager.remainingMillis(player, "skill", 1000L, 400L));
        assertEquals(0L, manager.remainingMillis(player, "skill", 1000L, 5000L));
    }

    @Test
    void clearDropsEveryCooldownForThatPlayer() {
        CooldownManager manager = new CooldownManager();
        manager.tryConsume(player, "skill", 1000L, 0L);
        manager.clear(player);
        assertTrue(manager.tryConsume(player, "skill", 1000L, 100L));
    }

    @Test
    void failedConsumeDoesNotResetTheOriginalLastUseTimestamp() {
        CooldownManager manager = new CooldownManager();
        manager.tryConsume(player, "skill", 1000L, 0L);
        manager.tryConsume(player, "skill", 1000L, 300L); // refused, must not shift the clock
        assertEquals(700L, manager.remainingMillis(player, "skill", 1000L, 300L));
    }

    // --- applyReduction: shared スキルCT短縮 clamp (2026-07-25 CT短縮ステータス分離 §1-B) ---

    @Test
    void applyReductionShrinksCooldownForPositiveFraction() {
        // 50% reduction: multiplier = max(0.05, 1.0 - min(0.9, 0.5)) = 0.5.
        assertEquals(500L, CooldownManager.applyReduction(1000L, 0.5));
    }

    @Test
    void applyReductionCapsAt90PercentReduction() {
        // Anything >= 0.9 clamps to the same floor (10% of the original length), matching
        // CombatListener.startItemCooldown's cooldown-reduction clamp shape.
        assertEquals(100L, CooldownManager.applyReduction(1000L, 0.9));
        assertEquals(100L, CooldownManager.applyReduction(1000L, 5.0));
    }

    @Test
    void applyReductionIncreasesCooldownForNegativeFraction() {
        // -50% ("CT増加" per ユーザー要望): multiplier = 1.0 - (-0.5) = 1.5.
        assertEquals(1500L, CooldownManager.applyReduction(1000L, -0.5));
    }

    @Test
    void applyReductionZeroFractionLeavesCooldownUnchanged() {
        assertEquals(1000L, CooldownManager.applyReduction(1000L, 0.0));
    }

    @Test
    void applyReductionIgnoresNonFiniteFraction() {
        assertEquals(1000L, CooldownManager.applyReduction(1000L, Double.NaN));
        assertEquals(1000L, CooldownManager.applyReduction(1000L, Double.POSITIVE_INFINITY));
    }

    @Test
    void applyReductionNeverProducesNegativeOrGoesBelowZeroBaseMillis() {
        assertEquals(0L, CooldownManager.applyReduction(0L, 0.9));
        assertEquals(0L, CooldownManager.applyReduction(-100L, 0.5));
    }

    // --- 2026-08-18 (W-59): 共有CTグループ(ActiveSkill#cooldownGroup())の非対称CT長 ---
    //
    // haste-active-mining/haste-active-digging のように、同じ key(cooldownGroup)を共有する2つの
    // ActiveSkillがそれぞれ違うcooldownMillisを持つ場合、「バケツを最後に消費した側の長さ」だけが
    // ロック期間を決める。これが崩れると、CTが短い方のスキルが先にバケツを取り戻した瞬間、長い方の
    // CTが事実上その短い長さへ切り下げられてしまう(=グループ共有が「短い方のCTへ両方とも縮む」バグに
    // 化ける)。以下はその不変条件を固定するテスト。

    @Test
    void sharedKeyLocksAtTheLengthOfTheConsumingCallNotTheQueryingCall() {
        CooldownManager manager = new CooldownManager();
        // 長いCT(1000ms)を持つ側(例: haste-active-mining)が先にバケツを消費する。
        assertTrue(manager.tryConsume(player, "haste-active", 1000L, 0L));

        // 短いCT(200ms)を持つ側(haste-active-digging)が t=100 で同じキーを試みても、
        // バケツをロックしているのは1000msの側 — 200msではなく1000msを基準に拒否されなければならない。
        assertFalse(manager.tryConsume(player, "haste-active", 200L, 100L));
        assertEquals(900L, manager.remainingMillis(player, "haste-active", 200L, 100L),
                "残り時間も『問い合わせた側の200ms』ではなく『消費した側の1000ms』基準で計算されること");
    }

    @Test
    void whenTheShortCooldownSideActuallyConsumesItsOwnLengthGovernsUntilNextConsume() {
        CooldownManager manager = new CooldownManager();
        assertTrue(manager.tryConsume(player, "haste-active", 1000L, 0L));

        // 1000ms経過後、短いCT(200ms)側が正当にバケツを消費する。
        assertTrue(manager.tryConsume(player, "haste-active", 200L, 1000L));

        // 消費した瞬間から200ms未満はロックされたまま(長いCT側が試みてもダメ)。
        assertFalse(manager.tryConsume(player, "haste-active", 1000L, 1150L));
        // 200ms経過後は解放される。
        assertTrue(manager.tryConsume(player, "haste-active", 1000L, 1200L));
    }

    @Test
    void twoDifferentActiveSkillFakesSharingACooldownGroupAreMutuallyExclusive() {
        // ActiveSkill#cooldownGroup()を同じ定数へオーバーライドした2つの独立スキルが、
        // ActivationDispatcher/CooldownManagerを介して実際にCTバケツを取り合うことを固定する
        // (id()自体は互いに異なる — CooldownManagerのキーはcooldownGroup()であって id() ではない)。
        CooldownManager manager = new CooldownManager();
        String miningId = "fake-haste-active-mining";
        String diggingId = "fake-haste-active-digging";
        String sharedGroup = "fake-haste-active-group";

        assertTrue(manager.tryConsume(player, sharedGroup, 1000L, 0L));
        // つるはしで撃った直後にシャベルへ持ち替えて連発しようとしても、共有キー(sharedGroup)へは
        // ブロックされる(idが違うだけで別バケツにはならない)。
        assertFalse(manager.tryConsume(player, sharedGroup, 800L, 50L));
        // 参考として、もし誤って id() 単位の別々のキーを使ってしまっていたら(=バグの再現)、
        // どちらも独立して消費できてしまう — その"誤り"を対比として明示しておく。
        CooldownManager perIdBuggyManager = new CooldownManager();
        assertTrue(perIdBuggyManager.tryConsume(player, miningId, 1000L, 0L));
        assertTrue(perIdBuggyManager.tryConsume(player, diggingId, 800L, 50L),
                "id単位の別キーだと(意図的なバグ再現として)両方消費できてしまう — "
                        + "だからこそ本物の実装は cooldownGroup() で共有する");
    }
}
