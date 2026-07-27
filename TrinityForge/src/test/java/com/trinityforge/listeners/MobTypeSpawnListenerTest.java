package com.trinityforge.listeners;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobTypeSpawnListener}のMAX_HEALTHクランプ検知(タスク: モブHPが上限で無言に縮む事故の検出)。
 *
 * <p><b>MockBukkit回避策</b>: {@link MobTypeSpawnListener#applyMaxHealth}の
 * {@code catch (IllegalArgumentException)}経路は、実サーバー(Bukkit)の{@code LivingEntity#setHealth}が
 * 要求値がgetMaxHealth()を超えるとIllegalArgumentExceptionを投げることに依存しているが、
 * MockBukkitの{@code LivingEntityMock#setHealth}はそのバリデーションを行わず
 * {@code Math.min(value, getMaxHealth())}へ静かにクランプするだけで例外を投げない。そのため
 * {@link org.bukkit.event.entity.CreatureSpawnEvent}をMockBukkit経由で発火してもこのcatch経路自体は
 * 再現できない。よって{@link MobTypeSpawnListener#warnHealthCeilingClamp}(パッケージプライベート、
 * レート制限ロジックのみを切り出した純関数)を直接呼び出して検証する。
 */
class MobTypeSpawnListenerTest {

    @BeforeEach
    void resetRateLimitState() {
        MobTypeSpawnListener.resetHealthCeilingWarningStateForTests();
    }

    @AfterEach
    void tearDown() {
        MobTypeSpawnListener.resetHealthCeilingWarningStateForTests();
    }

    @Test
    void warnsOnceWhenAchievedCeilingIsMeaningfullyBelowRequested() {
        boolean firstCall = MobTypeSpawnListener.warnHealthCeilingClamp(5000.0, 1024.0);
        assertTrue(firstCall, "first observation of a clamp at a given ceiling must warn");
    }

    @Test
    void doesNotWarnAgainForTheSameCeiling() {
        MobTypeSpawnListener.warnHealthCeilingClamp(5000.0, 1024.0);
        boolean secondCall = MobTypeSpawnListener.warnHealthCeilingClamp(6000.0, 1024.0);
        assertFalse(secondCall, "repeat clamps at the same observed ceiling must not spam the log");
    }

    @Test
    void warnsAgainWhenTheObservedCeilingChanges() {
        MobTypeSpawnListener.warnHealthCeilingClamp(5000.0, 1024.0);
        boolean afterCeilingChange = MobTypeSpawnListener.warnHealthCeilingClamp(5000.0, 2048.0);
        assertTrue(afterCeilingChange, "a newly observed ceiling value must warn again");
    }

    @Test
    void doesNotWarnInTheNormalCaseWhereAchievedMeetsRequested() {
        boolean call = MobTypeSpawnListener.warnHealthCeilingClamp(500.0, 500.0);
        assertFalse(call, "no clamp occurred (achieved == requested) -> must not warn");
    }

    @Test
    void doesNotWarnWhenAchievedExceedsRequested() {
        // Defensive: an achieved ceiling >= requested is never a clamp, even if not exactly equal
        // (e.g. floating rounding in the caller's favor).
        boolean call = MobTypeSpawnListener.warnHealthCeilingClamp(500.0, 5000.0);
        assertFalse(call, "achieved ceiling above requested is never a clamp");
    }

    @Test
    void toleratesFloatingNoiseWithoutWarning() {
        // A sub-hundredth difference must not be treated as a "meaningful" clamp.
        boolean call = MobTypeSpawnListener.warnHealthCeilingClamp(500.0, 499.999);
        assertFalse(call, "sub-0.01 differences are floating noise, not a real clamp");
    }
}
