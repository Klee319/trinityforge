package com.github.klee319.dpschecker.dummy;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import java.util.UUID;

public record DamageRecord(
        long timestamp,
        double finalDamage,
        double rawDamage,
        DamageCause cause,
        UUID attackerUuid
) {
    public DamageRecord(double finalDamage, double rawDamage, DamageCause cause, UUID attackerUuid) {
        this(System.currentTimeMillis(), finalDamage, rawDamage, cause, attackerUuid);
    }
}
