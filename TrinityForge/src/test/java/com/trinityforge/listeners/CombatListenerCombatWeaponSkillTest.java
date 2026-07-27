package com.trinityforge.listeners;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * バグ2(2026-07-28): 採取用ツール(斧/ツルハシ/シャベル/クワ/釣竿)で敵を殴ると、そのツールの
 * use-skill(採取スキル)へ戦闘EXPが付与されてしまっていた回帰の再発防止テスト。
 * {@link CombatListener#isCombatWeaponSkill} は「戦闘の武器スキルEXPをこの経路で付与してよいか」
 * を判定する純粋関数で、戦闘武器スキル3つ(HEAVY_WEAPONS/LIGHT_WEAPONS/ARCHERY)だけがtrue。
 */
class CombatListenerCombatWeaponSkillTest {

    @ParameterizedTest
    @ValueSource(strings = {"HEAVY_WEAPONS", "LIGHT_WEAPONS", "ARCHERY"})
    void combatWeaponSkillsAreAllowed(String skill) {
        assertTrue(CombatListener.isCombatWeaponSkill(skill));
    }

    /** 本体: 採取ツールのuse-skillは戦闘EXP経路から除外される(バグ再発防止の本体)。 */
    @ParameterizedTest
    @ValueSource(strings = {"WOODCUTTING", "MINING", "DIGGING", "FARMING", "FISHING"})
    void gatheringSkillsAreRejected(String skill) {
        assertFalse(CombatListener.isCombatWeaponSkill(skill));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARS_MAGIC", "HEAVY_ARMOR", "LIGHT_ARMOR", "SMITHING"})
    void nonWeaponCombatAdjacentSkillsAreRejected(String skill) {
        assertFalse(CombatListener.isCombatWeaponSkill(skill));
    }

    @Test
    void nullIsRejected() {
        assertFalse(CombatListener.isCombatWeaponSkill(null));
    }

    @Test
    void emptyStringIsRejected() {
        assertFalse(CombatListener.isCombatWeaponSkill(""));
    }

    @Test
    void unknownSkillIsRejected() {
        assertFalse(CombatListener.isCombatWeaponSkill("NOT_A_REAL_SKILL"));
    }
}
