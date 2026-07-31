package com.trinityforge.listeners;

import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.farming.AnimalDamagePolicy;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Objects;

/**
 * 畜産スキルツリー{@code animal-damage-4x}(flag): プレイヤーが動物(=Animals実装のうち、Paperの
 * {@code Enemy}=敵対を除いたもの)へ与える最終ダメージを倍率(既定4倍、
 * {@code stats/farming-gimmick.yml}で要調整)にする。
 *
 * <p><strong>TF戦闘パイプラインとの二重適用回避</strong>: {@link CombatListener#onEntityDamageByEntity}
 * が同じ{@link EntityDamageByEntityEvent}の{@code BASE}modifierを{@code EventPriority.HIGH}で確定させる
 * ({@code event.setDamage(...)}で上書き)。本リスナーはそれより後の{@code EventPriority.HIGHEST}で
 * {@code event.getDamage()}(=BASE modifier、CombatListenerが処理済みならTF確定後の最終ダメージ、
 * 未処理ならバニラの生ダメージ)を読んで単純に乗算するだけで、TFの物理/魔法計算式自体を再実行しない
 * ため二重適用にならない。CombatListenerがuse-level/アイテムCTゲートでイベントをキャンセルした場合は
 * {@code ignoreCancelled = true}により本ハンドラも自動的にスキップされる。
 */
public final class AnimalDamageListener implements Listener {

    private static final String EFFECT_ANIMAL_DAMAGE_4X = "animal-damage-4x";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final FarmingGimmickConfig gimmickConfig;

    public AnimalDamageListener(DedicatedEffectsConfig dedicatedEffects, FarmingGimmickConfig gimmickConfig) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.gimmickConfig = Objects.requireNonNull(gimmickConfig, "gimmickConfig");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event);
        if (attacker == null || !dedicatedEffects.isActive(attacker, EFFECT_ANIMAL_DAMAGE_4X)) {
            return;
        }
        Entity victim = event.getEntity();
        // 敵対判定は Paper の Enemy。Bukkit の Monster で見ると HOGLIN(Animals かつ Enemy だが
        // Monster ではない)が「動物」に化けて、ネザーでホグリンを4倍で殴れる。
        boolean eligible = AnimalDamagePolicy.eligibleVictim(victim instanceof Animals, victim instanceof Enemy);
        if (!eligible || !(victim instanceof LivingEntity)) {
            return;
        }
        double current = event.getDamage();
        double multiplied = AnimalDamagePolicy.multiply(current, gimmickConfig.animalDamageMultiplier());
        if (multiplied != current) {
            event.setDamage(multiplied);
        }
    }

    /** {@link CombatListener#resolveAttacker}と同じ「プレイヤーの近接/投射」解決(原因種別は問わず簡略化)。 */
    private static Player resolveAttacker(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        return null;
    }
}
