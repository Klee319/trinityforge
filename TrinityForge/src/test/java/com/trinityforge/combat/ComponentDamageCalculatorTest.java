package com.trinityforge.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Step-by-step checks of the 8-step pipeline (COMBAT_SYSTEM_SPEC 2.1, post-armor-rework):
 * 1 base(加算) -&gt; 2 守備力(flat, 0クランプ) -&gt; 3 会心 -&gt; 4 防御率 -&gt; 5 耐性 -&gt; 6 ダメージ補正/軽減
 * -&gt; 7 床クランプ -&gt; 8 固定ダメージ(全防御貫通の純加算)。
 */
class ComponentDamageCalculatorTest {

    private static final double EPS = 1e-9;
    private static final double MIN = 1.0;

    @Test
    void vanillaBaselineDealsRawDamage() {
        // Plain attack vs no mitigation -> default damage passes through unchanged.
        double dmg = ComponentDamageCalculator.compute(
                AttackStats.plain(10), DefenseStats.NONE, false, MIN);
        assertEquals(10.0, dmg, EPS);
    }

    @Test
    void critMultipliesByCritDamage() {
        AttackStats atk = new AttackStats(10, 0, 0, 1.0, 0.5, 0, 1, 0);
        double dmg = ComponentDamageCalculator.compute(atk, DefenseStats.NONE, true, MIN);
        assertEquals(15.0, dmg, EPS); // 10 * (1 + 0.5)
    }

    @Test
    void penetrationIgnoresOnlyDefenseRate() {
        // 50% defense rate, 100% penetration -> defense rate fully ignored.
        AttackStats atk = new AttackStats(10, 0, 0, 0, 0, 1.0, 1, 0);
        DefenseStats def = new DefenseStats(0.5, 0, 0, 0, 0);
        assertEquals(10.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void penetrationDoesNotBypassResistance() {
        // 100% penetration must NOT reduce 30% resistance (step 5).
        AttackStats atk = new AttackStats(10, 0, 0, 0, 0, 1.0, 1, 0);
        DefenseStats def = new DefenseStats(0, 0.3, 0, 0, 0);
        assertEquals(7.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void flatDefenseSubtractsBeforePercentMitigation() {
        // NEW ORDER (spec 2.1 step2): 守備力は%軽減より先に引かれる。
        // base 20 -> flat 5 引いて 15 -> 耐性50% で 7.5。
        // (旧順序なら 20*0.5=10 の後に flat5 を引いて 5 になっていたはずで、この 7.5 という値自体が
        // 新しい前倒し順序を検証する = 守備力が%軽減で希釈されないことの直接証拠)。
        AttackStats atk = AttackStats.plain(20);
        DefenseStats def = new DefenseStats(0, 0.5, 0, 5, 0);
        assertEquals(7.5, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void armorStrengthIsNotSubtractedAtFlatStep() {
        // 防具強度は step2 の flat 減算に一切関与しない(会心軽減率として step3 専任): base 20 - 0 = 20.
        AttackStats atk = AttackStats.plain(20);
        DefenseStats def = new DefenseStats(0, 0, 0, 0, 0.6);
        assertEquals(20.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void fixedDamageAlwaysAddsRegardlessOfArmorStrength() {
        // 固定ダメージ(step8)は無条件加算であり、防具強度(会心軽減率)とは無関係: 20 + 5 = 25.
        AttackStats atk = new AttackStats(20, 0, 0, 0, 0, 0, 1, 5);
        DefenseStats def = new DefenseStats(0, 0, 0, 0, 0.8);
        assertEquals(25.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void clampFloorAppliesPerComponent() {
        // Heavy flat drives base negative -> step2's own 0-floor clamps it to 0, then step7's
        // minClamp (MIN) raises it to the configured floor.
        AttackStats atk = AttackStats.plain(5);
        DefenseStats def = new DefenseStats(0, 0, 0, 100, 0);
        assertEquals(MIN, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void fixedDamageAddsOnTopOfFlatReducedBase() {
        // base 20, flat 8 (step2) -> 12; no percent mitigation; fixed 5 adds unconditionally at
        // step8 -> 17. (旧「還付」意味論とは無関係の無条件加算であることを示す)
        AttackStats atk = new AttackStats(20, 0, 0, 0, 0, 0, 1, 5);
        DefenseStats def = new DefenseStats(0, 0, 0, 8, 0);
        assertEquals(17.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void fixedDamageCanExceedPreFlatSinceItIsUnconditionalNow() {
        // 旧仕様では固定ダメージは preFlat を超えて還付されなかったが、新仕様では純加算なので
        // preFlat(20)を超えて 112 まで届く: base 20 - flat8 = 12, + fixed100 = 112.
        AttackStats atk = new AttackStats(20, 0, 0, 0, 0, 0, 1, 100);
        DefenseStats def = new DefenseStats(0, 0, 0, 8, 0);
        assertEquals(112.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void fixedDamageAddsEvenWithoutAnyFlatArmor() {
        // 旧仕様では flat armor が無ければ固定ダメージは無意味(還付するものが無い)だったが、
        // 新仕様は無条件加算なので flat 0 でもそのまま加算される: 20 + 100 = 120.
        AttackStats atk = new AttackStats(20, 0, 0, 0, 0, 0, 1, 100);
        assertEquals(120.0, ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, MIN), EPS);
    }

    @Test
    void negativeFixedDamageRemainsARealPenalty() {
        AttackStats atk = new AttackStats(20, 0, 0, 0, 0, 0, 1, -5);
        assertEquals(15.0, ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, -100), EPS);
    }

    @Test
    void armorStrengthReducesOnlyTheCritSurplus() {
        // 防具強度(会心軽減率0.4)は会心の増加分だけを (1-0.4)=0.6 倍にする(通常分は不変):
        // crit: 10 * (1 + 0.5*0.6) = 10 * 1.3 = 13。
        AttackStats atk = new AttackStats(10, 0, 0, 1.0, 0.5, 0, 1, 0);
        DefenseStats def = new DefenseStats(0, 0, 0, 0, 0.4);
        assertEquals(13.0, ComponentDamageCalculator.compute(atk, def, true, MIN), EPS);
    }

    @Test
    void armorStrengthDoesNotAffectNonCritDamage() {
        // 会心が発生しなければ防具強度は一切効かない(通常ダメージには不干渉、flatステップでも引かない): 10 のまま。
        AttackStats atk = new AttackStats(10, 0, 0, 1.0, 0.5, 0, 1, 0);
        DefenseStats def = new DefenseStats(0, 0, 0, 0, 0.4);
        assertEquals(10.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void armorStrengthCritReductionClampsToOneSoCritNeverHeals() {
        // 防具強度が1.0以上でも会心の増加分を全て消すだけ(surplus×0)。会心ダメージが0%未満(=会心が
        // 回復側へ反転)になることはない: crit -> 10 * (1 + 0.5*0) = 10 (base を下回らない)。
        AttackStats atk = new AttackStats(10, 0, 0, 1.0, 0.5, 0, 1, 0);
        DefenseStats def = new DefenseStats(0, 0, 0, 0, 1.5); // 会心軽減率 1.5 は [0,1] へクランプ
        assertEquals(10.0, ComponentDamageCalculator.compute(atk, def, true, MIN), EPS);
    }

    @Test
    void negativeArmorStrengthAmplifiesIncomingCrit() {
        // 負の防具強度は呪いとして保持する: crit -> 10 * (1+0.5*(1-(-0.5))) = 17.5。
        AttackStats atk = new AttackStats(10, 0, 0, 1.0, 0.5, 0, 1, 0);
        DefenseStats def = new DefenseStats(0, 0, 0, 0, -0.5);
        assertEquals(17.5, ComponentDamageCalculator.compute(atk, def, true, MIN), EPS);
    }

    @Test
    void percentBonusDamageAppliesAtStep1BeforeAnyMitigation() {
        // spec 2.1 step1: base = default + 固定追加 + default×割合%。
        // base = 10 + 5 + 10*0.2 = 17 (防御なしなのでそのまま最終値)。
        AttackStats atk = new AttackStats(10, 5, 0.2, 0, 0, 0, 1, 0);
        assertEquals(17.0, ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, MIN), EPS);
    }

    @Test
    void percentBonusDamageIsMitigatedByDefenderAfterStep1() {
        // base = 10 + 10*0.2 = 12 (step1)、耐性50% (step5) -> 12*0.5 = 6。
        AttackStats atk = new AttackStats(10, 0, 0.2, 0, 0, 0, 1, 0);
        DefenseStats def = new DefenseStats(0, 0.5, 0, 0, 0);
        assertEquals(6.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void negativePercentBonusDamageIsClampedByStep2ThenMinClampFloor() {
        // ロール下限が負のアイテムを想定: -30% は step1のbaseを減算 (10 -> 7)、flatDefense0 なので
        // step2の0クランプは効かず(7>=0)、防御なしでそのまま7。
        AttackStats mild = new AttackStats(10, 0, -0.3, 0, 0, 0, 1, 0);
        assertEquals(7.0, ComponentDamageCalculator.compute(mild, DefenseStats.NONE, false, MIN), EPS);
        // -150% だと step1のbaseが 10 + 10*(-1.5) = -5 まで沈み、flatDefense0でも step2 の
        // 0クランプが真っ先に効いて 0 になる(旧仕様はここでクランプせず step7 まで負のまま通していた)。
        // その後 %軽減はすべて素通り(0のまま)、最後に step7 の minClamp(=MIN=1.0) が底上げする。
        AttackStats extreme = new AttackStats(10, 0, -1.5, 0, 0, 0, 1, 0);
        assertEquals(MIN, ComponentDamageCalculator.compute(extreme, DefenseStats.NONE, false, MIN), EPS);
    }

    @Test
    void step6DamageModifierEndpoint0_8RollsBetweenLoAndHi() {
        AttackStats atkLow = new AttackStats(10, 0, 0, 0, 0, 0, 0.8, 0);
        assertEquals(8.0, ComponentDamageCalculator.compute(atkLow, DefenseStats.NONE, false, MIN, 0.0), EPS);
        assertEquals(10.0, ComponentDamageCalculator.compute(atkLow, DefenseStats.NONE, false, MIN, 1.0), EPS);
    }

    @Test
    void step6DamageModifierEndpoint1_2RollsBetweenLoAndHi() {
        AttackStats atkHigh = new AttackStats(10, 0, 0, 0, 0, 0, 1.2, 0);
        assertEquals(10.0, ComponentDamageCalculator.compute(atkHigh, DefenseStats.NONE, false, MIN, 0.0), EPS);
        assertEquals(12.0, ComponentDamageCalculator.compute(atkHigh, DefenseStats.NONE, false, MIN, 1.0), EPS);
    }

    @Test
    void step6DamageModifierZeroIsARealZeroEndpoint() {
        AttackStats atk = new AttackStats(10, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(MIN, ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, MIN, 0.0), EPS);
        assertEquals(10.0, ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, MIN, 1.0), EPS);
    }

    @Test
    void step6NegativeDamageModifierCanReachNegativeDamageFloor() {
        AttackStats atk = new AttackStats(10, 0, 0, 0, 0, 0, -0.5, 0);
        assertEquals(-5.0,
                ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, -100.0, 0.0), EPS);
        assertEquals(10.0,
                ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, -100.0, 1.0), EPS);
    }

    @Test
    void step6LegacyIntegerPercentIsNormalizedToEndpoint() {
        // Legacy YAML 70 → endpoint 0.70 → Uniform[0.7, 1.0].
        AttackStats atk = new AttackStats(10, 0, 0, 0, 0, 0, 70, 0);
        assertEquals(7.0, ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, MIN, 0.0), EPS);
        assertEquals(10.0, ComponentDamageCalculator.compute(atk, DefenseStats.NONE, false, MIN, 1.0), EPS);
    }

    @Test
    void step6AppliesDefenderReductionAfterModifierRoll() {
        // base 10, endpoint 1.2 at u=1 → 12, then defender -20% → 9.6.
        AttackStats atk = new AttackStats(10, 0, 0, 0, 0, 0, 1.2, 0);
        DefenseStats def = new DefenseStats(0, 0, 0.2, 0, 0);
        assertEquals(9.6, ComponentDamageCalculator.compute(atk, def, false, MIN, 1.0), EPS);
    }

    @Test
    void clampFloorThenFixedDamageAddsOnTop() {
        // base 5, flat 100 (step2) -> 0クランプ -> step7 の minClamp(MIN=1.0) まで底上げ -> 1;
        // fixed 10 は無条件で加算される(旧仕様の還付上限は廃止) -> 1 + 10 = 11。
        AttackStats atk = new AttackStats(5, 0, 0, 0, 0, 0, 1, 10);
        DefenseStats def = new DefenseStats(0, 0, 0, 100, 0);
        assertEquals(11.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void fixedDamageAddsUnconditionallyEvenAfterHeavyPercentMitigation() {
        // base 2, defenseRate 90% -> 0.2 -> step7 の minClamp(MIN=1.0) まで底上げ -> 1;
        // fixed 50 は防御を無視してそのまま乗る(旧仕様はここで還付0=MIN止まりだった) -> 1 + 50 = 51。
        AttackStats atk = new AttackStats(2, 0, 0, 0, 0, 0, 1, 50);
        DefenseStats def = new DefenseStats(0.9, 0, 0, 0, 0);
        double result = ComponentDamageCalculator.compute(atk, def, false, MIN);
        assertEquals(51.0, result, EPS);
    }

    // --- 新規: 守備力前倒しの効果を直接検証する3件 ---

    @Test
    void armorGreaterThanBaseDamageClampsToFloorEvenOnCrit() {
        // 守備力(50)が素ダメージ(10)を大きく超える場合、会心が乗っても最終的に床値へ張り付く:
        // step1:10 -> step2: 10-50=-40 -> 0クランプ -> step3 crit: 0 * 1.5 = 0 -> ... -> step7: floor(MIN)。
        AttackStats atk = new AttackStats(10, 0, 0, 1.0, 0.5, 0, 1, 0);
        DefenseStats def = new DefenseStats(0, 0, 0, 50, 0);
        assertEquals(MIN, ComponentDamageCalculator.compute(atk, def, true, MIN), EPS);
    }

    @Test
    void fixedDamagePassesThroughFullDefenseRateResistanceAndHugeFlatDefense() {
        // 防御率100%・耐性100%・守備力100万でも固定ダメージは全防御貫通の純加算として素通りする:
        // base は step2〜step6 の間にどう転んでも0/床(MIN=1)に落ちるが、+fixed(7) は必ず上乗せされる。
        AttackStats atk = new AttackStats(10, 0, 0, 0, 0, 0, 1, 7);
        DefenseStats def = new DefenseStats(1.0, 1.0, 0, 1_000_000, 0);
        assertEquals(MIN + 7.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }

    @Test
    void negativeFlatDefenseAmplifiesDamage() {
        // defense.min-flat を負に運用した場合、守備力が負値だと step2 の base -= flat が被ダメージを
        // 増幅する。0クランプは0未満にしかかからないため、この増幅は妨げられない:
        // base 10 - (-10) = 20。
        AttackStats atk = AttackStats.plain(10);
        DefenseStats def = new DefenseStats(0, 0, 0, -10, 0);
        assertEquals(20.0, ComponentDamageCalculator.compute(atk, def, false, MIN), EPS);
    }
}
