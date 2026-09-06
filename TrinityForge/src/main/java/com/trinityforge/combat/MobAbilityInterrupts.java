package com.trinityforge.combat;

import org.bukkit.entity.LivingEntity;

/**
 * 「殴る／スタンで詠唱を止める」の配線口（機構8、2026-09-04）。
 *
 * <p>詠唱の進行台帳は {@link MobAbilityExecutor} 側にあるが、被ダメージ・スタンの発火源は
 * {@code EntityDamageEvent}（{@link MobAbilityCastDamageListener}）と
 * {@code NativeCombatPerkListener} の {@code stun_chance} 付与という<b>別々のリスナー</b>にある。
 * それぞれから毎回 {@link MobAbilityExecutor} のインスタンスを引き回すのは配線が増えるだけなので、
 * ここに volatile static の橋渡しを1本置く。TF は {@link MobAbilityExecutor} を1インスタンスしか
 * 作らないので複数登録は通常起きないが、起きた場合は<b>後勝ち</b>（最後に {@link #register} した
 * インスタンスだけが有効）とする。
 */
public final class MobAbilityInterrupts {

    private static volatile MobAbilityExecutor current;

    private MobAbilityInterrupts() {
    }

    /** {@code TrinityForge.java} が起動時に1回呼ぶ。 */
    public static void register(MobAbilityExecutor executor) {
        current = executor;
    }

    /** プラグイン無効化時などに呼ぶ（無くても実害は小さいが、テスト間の汚染を避けるために用意）。 */
    public static void unregister(MobAbilityExecutor executor) {
        if (current == executor) {
            current = null;
        }
    }

    /** 進行中の詠唱が1件でもあるか。無ければ呼び出し元は以降の処理を早期リターンできる。 */
    public static boolean hasActiveCasts() {
        MobAbilityExecutor executor = current;
        return executor != null && executor.hasActiveCasts();
    }

    /** そのエンティティがダメージを受けたことを通知する（{@code MobAbilityCastDamageListener} から）。 */
    public static void notifyDamage(LivingEntity caster, double finalDamage) {
        MobAbilityExecutor executor = current;
        if (executor != null && caster != null) {
            executor.onCasterDamaged(caster, finalDamage);
        }
    }

    /** そのエンティティがスタンを受けたことを通知する（{@code NativeCombatPerkListener} から）。 */
    public static void notifyStun(LivingEntity caster) {
        MobAbilityExecutor executor = current;
        if (executor != null && caster != null) {
            executor.onCasterStunned(caster);
        }
    }
}
