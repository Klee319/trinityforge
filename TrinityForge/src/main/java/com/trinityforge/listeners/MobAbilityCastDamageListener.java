package com.trinityforge.listeners;

import com.trinityforge.combat.MobAbilityInterrupts;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 敵の詠唱を「殴って中断する」ための被ダメージ観測点（機構8、2026-09-04）。
 *
 * <p>{@code MONITOR} で {@code ignoreCancelled=true} にしているのは、この観測が
 * <b>ダメージ量を変えない・イベントを一切書き換えない</b>純粋な副作用だから。他のリスナーが
 * 先にキャンセルしたダメージ（無敵時間・防御スキルで無効化された一撃）まで中断判定に含めると、
 * 「実際には受けていないダメージで詠唱が止まる」という理不尽になるため {@code ignoreCancelled} で除く。
 *
 * <p>台帳が空のとき（進行中の詠唱が1件も無い）は {@link MobAbilityInterrupts#hasActiveCasts()}
 * で早期リターンする。TF はモブ数が多いダンジョンを想定しているので、詠唱していないモブへの
 * 通常戦闘ダメージの大半をここで捨てる。
 */
public final class MobAbilityCastDamageListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!MobAbilityInterrupts.hasActiveCasts()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity caster)) {
            return;
        }
        MobAbilityInterrupts.notifyDamage(caster, event.getFinalDamage());
    }
}
