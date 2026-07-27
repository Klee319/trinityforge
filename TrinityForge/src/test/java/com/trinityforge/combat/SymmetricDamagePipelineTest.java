package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Summation of physical + magical components, active-only filtering, and whole-attack dodge. */
class SymmetricDamagePipelineTest {

    private static final double EPS = 1e-9;
    private static final double NO_DODGE = 0.0;

    @Test
    void negativeBaseComponentRemainsActiveAndCanReachNegativeFloor() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, -100.0);
        double result = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, AttackStats.plain(-10), DefenseStats.NONE)),
                NO_DODGE);
        assertEquals(-10.0, result, EPS);
    }

    @Test
    void sumsActiveComponents() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, 1.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, AttackStats.plain(10), DefenseStats.NONE),
                new ComponentInput(DamageType.MAGICAL, AttackStats.plain(6), DefenseStats.NONE)), NO_DODGE);
        assertEquals(16.0, total, EPS);
    }

    @Test
    void skipsComponentsWithZeroDefaultDamage() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, 1.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, AttackStats.plain(10), DefenseStats.NONE),
                new ComponentInput(DamageType.MAGICAL, AttackStats.plain(0), DefenseStats.NONE)), NO_DODGE);
        assertEquals(10.0, total, EPS);
    }

    @Test
    void critResolverDrivesCritPerComponent() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.ALWAYS, DodgeResolver.NEVER, 1.0);
        AttackStats critAtk = new AttackStats(10, 0, 0, 0.5, 1.0, 0, 1, 0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, critAtk, DefenseStats.NONE)), NO_DODGE);
        assertEquals(20.0, total, EPS); // 10 * (1 + 1.0)
    }

    @Test
    void dodgeAvoidsWholeAttack() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.ALWAYS, 1.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, AttackStats.plain(10), DefenseStats.NONE),
                new ComponentInput(DamageType.MAGICAL, AttackStats.plain(6), DefenseStats.NONE)), 0.5);
        assertEquals(0.0, total, EPS); // dodge collapses every component, magical included
    }

    @Test
    void dodgeAppliesToMagicalOnlyAttack() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.ALWAYS, 1.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.MAGICAL, AttackStats.plain(8), DefenseStats.NONE)), 0.5);
        assertEquals(0.0, total, EPS); // 魔法も回避可 (Q3)
    }

    @Test
    void noDodgeWhenRollFails() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, 1.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, AttackStats.plain(10), DefenseStats.NONE)), 1.0);
        assertEquals(10.0, total, EPS); // chance high but resolver never dodges -> full damage
    }

    @Test
    void zeroDodgeChanceNeverDodges() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.ALWAYS, 1.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, AttackStats.plain(10), DefenseStats.NONE)), NO_DODGE);
        assertEquals(10.0, total, EPS); // even ALWAYS resolver does not dodge a 0 chance
    }

    @Test
    void fixedDamageOnlyComponentIsNoLongerSkipped() {
        // T1 (2026-07-25 潜在バグ修正): defaultDamage=0 かつ flatBonusDamage=0 でも fixedDamage が
        // 非0なら isActive()=true となり、パイプラインで処理される。以前はここで丸ごとスキップされ
        // ダメージ0になっていた。DefenseStats.NONE(守備力0)なので step2以降は 0 のまま素通りし、
        // step7 で minClamp(1.0)まで持ち上がってから step8 で fixedDamage が純加算される: 1.0 + 9.0 = 10.0.
        AttackStats fixedOnly = new AttackStats(0, 0, 0, 0, 0, 0, 1, 9.0);
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, 1.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, fixedOnly, DefenseStats.NONE)), NO_DODGE);
        assertEquals(10.0, total, EPS);
    }

    @Test
    void fixedDamageOnlyComponentBypassesHeavyDefense() {
        // 守備力が大きくても fixedDamage は貫通する: base=0-50=-50 -> minClamp(1.0)で床 -> +9.0 = 10.0.
        // 守備力の大小に関わらず結果が変わらないことが「全防御貫通」の直接証拠。
        AttackStats fixedOnly = new AttackStats(0, 0, 0, 0, 0, 0, 1, 9.0);
        DefenseStats heavyArmor = new DefenseStats(0.9, 0.9, 0.9, 50, 0.9);
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, 1.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, fixedOnly, heavyArmor)), NO_DODGE);
        assertEquals(10.0, total, EPS);
    }

    @Test
    void fixedDamageOnlyMagicalComponentIsAlsoNoLongerSkipped() {
        // isActive() は DamageType を問わず共通のロジック(ComponentInput)なので、魔法属性でも同様に
        // fixedDamage-only が処理対象になることを確認する。
        AttackStats fixedOnly = new AttackStats(0, 0, 0, 0, 0, 0, 1, 4.0);
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, 0.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.MAGICAL, fixedOnly, DefenseStats.NONE)), NO_DODGE);
        assertEquals(4.0, total, EPS); // minClamp=0 -> base 0 floors at 0 -> +4.0
    }

    @Test
    void stillSkipsComponentWithAllThreeAttackerStatsZero() {
        // defaultDamage / flatBonusDamage / fixedDamage が全て0なら、旧来通りスキップされ続ける
        // (percentBonusDamage や damageModifier など他ステが非0でも起動しない = 既存挙動を保つ)。
        AttackStats allZero = new AttackStats(0, 0, 0.5, 0.2, 1.0, 0.3, 2.0, 0);
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.ALWAYS, DodgeResolver.NEVER, 5.0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, allZero, DefenseStats.NONE)), NO_DODGE);
        assertEquals(0.0, total, EPS); // still inactive -> contributes nothing, not even the minClamp floor
    }

    @Test
    void existingFixedDamageWeaponsAreUnaffectedByT1SinceTheyWereAlreadyActive() {
        // T1回帰防止: 全34本の固定ダメージ武器は attack-power(defaultDamage) が非0なので、
        // isActive() は変更前から true だった(defaultDamage!=0 の項が既にtrueを返す)。
        // 今回の変更は isActive() の判定を「false→true」に広げる方向の OR 追加のみであり、
        // 既にtrueだったケースの真偽値は一切変わらない。ここでは代表2本
        // (item-stats.yml: IRON_SPEAR attack-power=9.78/fixed-damage=2.55,
        //  NETHERITE_SWORD#21 attack-power=205.2/fixed-damage=256.5)の実値でパイプライン結果が
        // 変更前と一致することを具体的な数値で固定する。
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, 1.0);

        AttackStats ironSpear = new AttackStats(9.78, 0, 0, 0, 0, 0, 1, 2.55);
        double ironSpearDamage = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, ironSpear, DefenseStats.NONE)), NO_DODGE);
        assertEquals(9.78 + 2.55, ironSpearDamage, EPS);

        AttackStats netheriteSword21 = new AttackStats(205.2, 0, 0, 0, 0, 0, 1, 256.5);
        double netheriteSwordDamage = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, netheriteSword21, DefenseStats.NONE)), NO_DODGE);
        assertEquals(205.2 + 256.5, netheriteSwordDamage, EPS);
    }

    @Test
    void hybridComponentsKeepIndependentMinimumDamage() {
        SymmetricDamagePipeline pipeline =
                new SymmetricDamagePipeline(CritResolver.NEVER, DodgeResolver.NEVER, 0.0);
        DefenseStats fullyReduced = new DefenseStats(0, 1, 0, 0, 0);
        double total = pipeline.compute(List.of(
                new ComponentInput(DamageType.PHYSICAL, AttackStats.plain(10), fullyReduced, 1.0),
                new ComponentInput(DamageType.MAGICAL, AttackStats.plain(10), fullyReduced, 2.0)),
                NO_DODGE);
        assertEquals(3.0, total, EPS);
    }
}
