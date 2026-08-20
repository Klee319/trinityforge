package com.trinityforge.listeners;

import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.BlockProjectileSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-07-30 バグ修正の回帰テスト: <b>モブの飛び道具ダメージにTFスケールが乗っていなかった</b>件。
 *
 * <p>{@code resolveMobAttacker} が近接({@code ENTITY_ATTACK} 等)だけを見ていたため、スケルトンの矢や
 * ブレイズの火球は「モブ攻撃者=null」→「プレイヤー攻撃者=null(発射者がプレイヤーでない)」で
 * リスナーを素通りし、モブレベル・attack-power・プレイヤーの守備/回避が一切効かないバニラダメージが
 * そのまま通っていた。
 */
class CombatListenerMobProjectileAttackerTest {

    private static EntityDamageByEntityEvent event(org.bukkit.entity.Entity damager,
                                                   EntityDamageEvent.DamageCause cause) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(damager);
        when(event.getCause()).thenReturn(cause);
        return event;
    }

    @Test
    void mobProjectileResolvesToItsShooter() {
        Skeleton skeleton = mock(Skeleton.class);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(skeleton);

        LivingEntity attacker = CombatListener.resolveMobAttacker(
                event(arrow, EntityDamageEvent.DamageCause.PROJECTILE));

        assertSame(skeleton, attacker, "矢の発射者(スケルトン)がTFの攻撃者として解決されること");
    }

    @Test
    void mobMeleeStillResolvesToTheDamagerItself() {
        Zombie zombie = mock(Zombie.class);

        assertSame(zombie, CombatListener.resolveMobAttacker(
                event(zombie, EntityDamageEvent.DamageCause.ENTITY_ATTACK)));
    }

    @Test
    void playerProjectileIsNotAMobAttacker() {
        // プレイヤーの矢は従来どおりプレイヤー側の経路(resolveAttacker)が担当する。
        // ここでモブ扱いにすると、同じ一撃にモブ用の計算が二重で掛かる。
        Player shooter = mock(Player.class);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(shooter);

        assertNull(CombatListener.resolveMobAttacker(
                event(arrow, EntityDamageEvent.DamageCause.PROJECTILE)));
    }

    @Test
    void dispenserProjectileHasNoLivingShooterSoItIsSkipped() {
        // ディスペンサー等の BlockProjectileSource は LivingEntity ではないので対象外(従来どおり)。
        BlockProjectileSource dispenser = mock(BlockProjectileSource.class);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(dispenser);

        assertNull(CombatListener.resolveMobAttacker(
                event(arrow, EntityDamageEvent.DamageCause.PROJECTILE)));
    }

    @Test
    void nonProjectileCauseFromAProjectileEntityIsIgnored() {
        // 爆発など TF が価格付けしない cause は、たとえ damager が飛び道具でも対象外。
        Skeleton skeleton = mock(Skeleton.class);
        Arrow arrow = mock(Arrow.class);
        when(arrow.getShooter()).thenReturn(skeleton);

        assertNull(CombatListener.resolveMobAttacker(
                event(arrow, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)));
    }
}
