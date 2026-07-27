package com.trinityforge.combat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 課題3 (CMB-02, 2026-07-25): {@link EliteCombatDelegation} マーカーの深度カウンタ挙動を検証する
 * ({@link MobAbilityDamage} と同じ確立済みパターン)。
 */
class EliteCombatDelegationTest {

    @AfterEach
    void resetMarker() {
        // 各テストの後始末: 何らかのアサート失敗でclear漏れが起きても後続テストへ波及させない。
        while (EliteCombatDelegation.isActive()) {
            EliteCombatDelegation.clear();
        }
    }

    @Test
    void inactiveByDefault() {
        assertFalse(EliteCombatDelegation.isActive());
    }

    @Test
    void markActivatesAndClearDeactivates() {
        EliteCombatDelegation.mark();
        assertTrue(EliteCombatDelegation.isActive());
        EliteCombatDelegation.clear();
        assertFalse(EliteCombatDelegation.isActive());
    }

    @Test
    void nestedMarksRequireMatchingClears() {
        // LOWEST(mark)→…→MONITOR(clear) の1ペアだけでなく、理論上のネストにも耐えることを確認
        // (depthカウンタなので、単純booleanと違い外側の印を内側のclearが誤って消さない)。
        EliteCombatDelegation.mark();
        EliteCombatDelegation.mark();
        assertTrue(EliteCombatDelegation.isActive());
        EliteCombatDelegation.clear();
        assertTrue(EliteCombatDelegation.isActive(), "1回目のclearでは外側のmarkがまだ残っている");
        EliteCombatDelegation.clear();
        assertFalse(EliteCombatDelegation.isActive());
    }

    @Test
    void extraClearWithoutMarkNeverUnderflowsOrThrows() {
        assertFalse(EliteCombatDelegation.isActive());
        EliteCombatDelegation.clear();
        EliteCombatDelegation.clear();
        assertFalse(EliteCombatDelegation.isActive());
        // 以後のmark/clearが正常に対をなすことも確認(負のdepthに落ちていないこと)。
        EliteCombatDelegation.mark();
        assertTrue(EliteCombatDelegation.isActive());
        EliteCombatDelegation.clear();
        assertFalse(EliteCombatDelegation.isActive());
    }
}
