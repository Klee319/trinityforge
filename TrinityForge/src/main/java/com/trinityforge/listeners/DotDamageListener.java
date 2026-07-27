package com.trinityforge.listeners;

import com.trinityforge.combat.SymmetricCombatService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 毒・ウィザーのDoTを出血と同じ仕様に揃える: バニラのイベントはそのまま流しつつ、
 * ベースダメージへ被ダメージ軽減%（{@code damage-reduction}）だけを適用する。
 * 回避/防御率/耐性/守備力/防具強度は一切参照しない（出血 = {@code bleedFinalDamageFlat} と同一式）。
 *
 * <p>BASE差し替えに加えてエンジンのRESISTANCE modifierを0化する: ポーションRESISTANCEは
 * TF耐性%への加算に一本化済みで、DoTは仕様上その耐性を参照しないため、バニラの-20%/lvを
 * 残すと出血とポーション保持者だけ挙動が割れる。盾・衝撃吸収など他のmodifierは無傷で残す。
 */
public final class DotDamageListener implements Listener {

    // TODO(M2+): CombatListener と同じ deprecated DamageModifier 折り込み。移行時に一緒に更新する。
    @SuppressWarnings("deprecation")
    private static final EntityDamageEvent.DamageModifier[] DAMAGE_MODIFIERS =
            EntityDamageEvent.DamageModifier.values();

    private final SymmetricCombatService combatService;

    public DotDamageListener(SymmetricCombatService combatService) {
        this.combatService = combatService;
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDotDamage(EntityDamageEvent event) {
        switch (event.getCause()) {
            case POISON, WITHER -> {
            }
            default -> {
                return;
            }
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        double base = event.getDamage();
        if (base <= 0.0) {
            return;
        }
        double finalDamage = combatService.bleedFinalDamageFlat(victim, base);
        if (finalDamage != base) {
            event.setDamage(Math.max(0.0, finalDamage));
        }
        // ポーションRESISTANCEの二重適用防止(TF耐性へ折り込み済み、かつDoTは耐性を参照しない仕様)。
        for (EntityDamageEvent.DamageModifier modifier : DAMAGE_MODIFIERS) {
            if (modifier == EntityDamageEvent.DamageModifier.RESISTANCE && event.isApplicable(modifier)) {
                event.setDamage(modifier, 0.0);
            }
        }
    }
}
