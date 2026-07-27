package com.github.klee319.dpschecker.calculator;

import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

public record DamageStats(
        DamageCause cause,
        double dps,
        double average,
        double max,
        double min,
        double total,
        int hitCount
) {}
