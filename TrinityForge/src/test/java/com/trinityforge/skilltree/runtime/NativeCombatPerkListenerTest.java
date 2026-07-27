package com.trinityforge.skilltree.runtime;

import org.junit.jupiter.api.Test;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeCombatPerkListenerTest {

    @Test
    void zeroAccuracyKeepsConfiguredBaselineJitter() {
        assertEquals(0.08, NativeCombatPerkListener.bowJitter(0.0), 1e-9);
    }

    @Test
    void positiveAccuracyReducesJitterAndCapsAtPerfect() {
        assertEquals(0.03, NativeCombatPerkListener.bowJitter(0.05), 1e-9);
        assertEquals(0.0, NativeCombatPerkListener.bowJitter(0.10), 1e-9);
    }

    @Test
    void stunTicksWithoutBonusMatchesLegacyFormula() {
        // 旧実装: 25 + 20*min(1, stunChance)。durationBonus=0では従来の挙動を完全維持する。
        assertEquals(25, NativeCombatPerkListener.stunTicks(0.0, 0.0));
        assertEquals(35, NativeCombatPerkListener.stunTicks(0.5, 0.0));
        assertEquals(45, NativeCombatPerkListener.stunTicks(1.0, 0.0));
        assertEquals(45, NativeCombatPerkListener.stunTicks(5.0, 0.0), "stunChance自体は従来どおり1.0で頭打ち");
    }

    @Test
    void stunTicksAppliesDurationBonusMultiplicatively() {
        // base=25 (stunChance=0), +50% -> 37.5 -> round 38
        assertEquals(38, NativeCombatPerkListener.stunTicks(0.0, 0.5));
        // base=45 (stunChance=1.0), +100% -> 90 (< cap 100)
        assertEquals(90, NativeCombatPerkListener.stunTicks(1.0, 1.0));
    }

    @Test
    void stunTicksNeverExceedsAbsoluteCap() {
        assertEquals(NativeCombatPerkListener.MAX_STUN_DURATION_TICKS,
                NativeCombatPerkListener.stunTicks(1.0, 10.0),
                "stun_duration_bonusでどれだけ盛っても絶対上限(ハメ殺し防止)を超えてはならない");
    }

    @Test
    void stunTicksFloorsAtOneEvenWithNegativeBonus() {
        assertEquals(1, NativeCombatPerkListener.stunTicks(0.0, -0.99));
    }

    @Test
    void meleeEffectsRunAfterCombatGateAndPipeline() throws Exception {
        Method method = NativeCombatPerkListener.class.getDeclaredMethod(
                "onMelee", org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertEquals(EventPriority.HIGHEST, handler.priority());
    }

    /**
     * 回帰テスト(2026-07-26): {@code onMelee} は「殴った」ときだけ効かなければならない。
     *
     * <p>以前は「ダメージ元がPlayer」だけで通していたため、ArsPaperの魔法ダメージ
     * ({@code TrinityForgeBridge#applyMagicDamage} が causingEntity=詠唱者で
     * {@code EntityDamageByEntityEvent} を発火する)にも、近接専用の melee_knockback / stun_chance が
     * 乗っていた。{@code CombatListener} と同じ MELEE_CAUSES(ENTITY_ATTACK / ENTITY_SWEEP_ATTACK)
     * でゲートされていることを、集合の中身そのもので固定する。
     *
     * <p>MockBukkitでイベントを実発火して検証しないのは、この漏れの発生源が
     * ArsPaperフォーク側(TFのテスト対象外)のダメージ発火だからで、TF側で保証すべき契約は
     * 「MELEE_CAUSES 以外を弾くこと」そのものであるため。
     */
    @Test
    void meleeOnlyPerksAreGatedToRealMeleeCausesSoMagicDoesNotTriggerThem() throws Exception {
        java.lang.reflect.Field field =
                NativeCombatPerkListener.class.getDeclaredField("MELEE_CAUSES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Set<org.bukkit.event.entity.EntityDamageEvent.DamageCause> causes =
                (java.util.Set<org.bukkit.event.entity.EntityDamageEvent.DamageCause>) field.get(null);

        assertEquals(
                java.util.EnumSet.of(
                        org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                        org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK),
                causes,
                "近接専用ステの発火条件を広げると、Ars魔法や反射ダメージへ漏れる");
        assertEquals(false,
                causes.contains(org.bukkit.event.entity.EntityDamageEvent.DamageCause.MAGIC),
                "MAGIC(Ars魔法)で近接ノックバック/スタンが乗ってはならない");
    }
}
