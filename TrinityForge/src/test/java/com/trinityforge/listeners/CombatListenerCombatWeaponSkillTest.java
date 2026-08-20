package com.trinityforge.listeners;

import com.trinityforge.progression.core.SkillId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * バグ2(2026-07-28): 採取用ツール(斧/ツルハシ/シャベル/クワ/釣竿)で敵を殴ると、そのツールの
 * use-skill(採取スキル)へ戦闘EXPが付与されてしまっていた回帰の再発防止テスト。
 * {@link CombatListener#isCombatWeaponSkill} は「戦闘の武器スキルEXPをこの経路で付与してよいか」
 * を判定する純粋関数で、戦闘武器スキル3つ(HEAVY_WEAPONS/LIGHT_WEAPONS/ARCHERY)だけがtrue。
 *
 * <p>N5(2026-07-31): ここには {@code isCombatWeaponSkill} のケースしか無く、
 * <b>実際に付与タイミングを決めている {@link CombatListener#isKillBasedCombatWeaponSkill} を
 * 1件もテストしていなかった</b>ため、ARCHERY がそちらから漏れて per-hit 付与になっていたことを
 * 誰も検出できなかった。両方を縛り、さらに「2つの集合が一致する(=per-hit の武器スキルEXP経路が
 * 存在しない)」という不変条件そのものを固定する。
 */
class CombatListenerCombatWeaponSkillTest {

    private static final List<String> COMBAT_WEAPON_SKILLS =
            List.of(SkillId.HEAVY_WEAPONS, SkillId.LIGHT_WEAPONS, SkillId.ARCHERY);

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

    // ------------------------------------------------------------------
    // N5(2026-07-31): isKillBasedCombatWeaponSkill(付与タイミングを決める本体)
    // ------------------------------------------------------------------

    /**
     * 本タスクの本体。ARCHERY がここで false だったため、弓術だけが命中イベント内で
     * 与ダメージ比例のEXPを即時付与していた(クールダウンも重複排除も無く、倒さずに撃ち続ける
     * だけで無制限に稼げた)。3スキルすべてが討伐時ベースであることを固定する。
     */
    @ParameterizedTest
    @ValueSource(strings = {"HEAVY_WEAPONS", "LIGHT_WEAPONS", "ARCHERY"})
    void everyCombatWeaponSkillPaysOnConfirmedKill(String skill) {
        assertTrue(CombatListener.isKillBasedCombatWeaponSkill(skill),
                skill + " は討伐確定時に一括で払う必要がある(per-hit 付与へ戻してはならない)");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ARS_MAGIC", "HEAVY_ARMOR", "LIGHT_ARMOR", "SMITHING",
        "WOODCUTTING", "MINING", "DIGGING", "FARMING", "FISHING", "NOT_A_REAL_SKILL"})
    void nonWeaponSkillsAreNotPaidByTheKillLedger(String skill) {
        assertFalse(CombatListener.isKillBasedCombatWeaponSkill(skill));
    }

    @Test
    void nullAndEmptyAreNotPaidByTheKillLedger() {
        assertFalse(CombatListener.isKillBasedCombatWeaponSkill(null));
        assertFalse(CombatListener.isKillBasedCombatWeaponSkill(""));
    }

    /**
     * 不変条件: 戦闘EXP経路に通るスキル(= {@code isCombatWeaponSkill}) は全て討伐時ベース
     * (= {@code isKillBasedCombatWeaponSkill})でなければならない。両者に差があると、その差集合の
     * スキルだけが「命中ごとに払う別方式」を必要とし、まさに今回の弓術バグと同じ形になる。
     *
     * <p>{@link SkillId#ALL} を回すので、将来スキルを増やして片方にだけ足した時点で落ちる。
     */
    @Test
    void combatWeaponSkillSetAndKillBasedSetAreIdentical() {
        for (String skill : SkillId.ALL) {
            assertTrue(CombatListener.isCombatWeaponSkill(skill)
                            == CombatListener.isKillBasedCombatWeaponSkill(skill),
                    skill + ": 戦闘EXP経路に通るのに討伐時ベースでない(または逆)。"
                            + "per-hit で払う武器スキルを作ってはならない");
        }
    }

    /** 前提の固定: 戦闘武器スキルは3つだけ(この一覧が増えたら上の不変条件テストも見直す)。 */
    @Test
    void exactlyThreeSkillsUseTheCombatWeaponExpPath() {
        List<String> actual = SkillId.ALL.stream()
                .filter(CombatListener::isCombatWeaponSkill)
                .toList();
        assertTrue(actual.containsAll(COMBAT_WEAPON_SKILLS) && actual.size() == 3,
                "戦闘武器スキルは HEAVY_WEAPONS/LIGHT_WEAPONS/ARCHERY の3つ: " + actual);
    }
}
