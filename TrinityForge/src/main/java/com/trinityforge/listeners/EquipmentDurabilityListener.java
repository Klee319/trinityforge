package com.trinityforge.listeners;

import com.trinityforge.durability.EquipmentDurabilityService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Objects;

/**
 * TF独自の装備耐久ペナルティを被弾/死亡に配線する(2026-07-30)。
 *
 * <p><b>被弾</b>: {@link EntityDamageByEntityEvent} の MONITOR / {@code ignoreCancelled = true}。
 * エンティティ由来の一撃だけを対象にするのは、落下・溺水・飢餓・毒などバニラでも防具耐久を
 * 消費しない継続ダメージで耐久が溶けるのを避けるため(かつ「ダンジョンで攻撃を受けても耐久が
 * 減らない」という報告そのものへの対応であるため)。
 *
 * <p>{@code ignoreCancelled = true} なので、EliteMobs のインスタンスダンジョンがキャンセルする
 * <em>致死の一撃</em>ではここは走らない。その分は下の死亡ペナルティ(フォークから
 * {@code TrinityForge#applyDeathDurabilityPenalty} 経由で呼ばれる)が回収する。
 *
 * <p><b>死亡</b>: {@link PlayerDeathEvent} の MONITOR。ダンジョン内では EliteMobs が致死ダメージを
 * キャンセルして「ダウン」へ移すためこのイベントは発火せず、代わりにフォーク側の
 * {@code InstancePlayerManager#playerDeath} から TF の API を直接叩く。ここは通常世界での死亡
 * (＝{@code durability.dungeon-only: false} 運用時)を担う。
 */
public final class EquipmentDurabilityListener implements Listener {

    private final EquipmentDurabilityService service;

    public EquipmentDurabilityListener(EquipmentDurabilityService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (event.getFinalDamage() <= 0.0) {
            return;
        }
        service.applyOnHit(victim);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        service.applyOnDeath(event.getEntity());
    }
}
