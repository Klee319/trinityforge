package com.trinityforge.combat;

import java.util.Objects;

/**
 * The full defender resolution for one damage component: the per-component {@link DefenseStats} plus
 * the type-independent whole-attack {@code dodgeChance} (回避, LD-13). Bundled so the service resolves
 * a victim once per hit instead of deriving equipped armor twice (defense + dodge).
 *
 * <p>{@code dodgeChance} is capped at 1 here (B2), while negative values are retained and naturally
 * never proc: armor 回避 rolls are summed additively across
 * pieces ({@code PlayerDefenseResolver}) with no per-source ceiling, so four pieces at 0.3 would total
 * 1.2 and — since {@link DodgeResolver#RANDOM} dodges when {@code random < chance} — dodge every hit
 * (permanent invincibility). Capping at the single bundling choke keeps every source symmetric.
 */
public record DefenderProfile(DefenseStats stats, double dodgeChance) {

    public DefenderProfile {
        Objects.requireNonNull(stats, "stats");
        dodgeChance = Double.isFinite(dodgeChance) ? Math.min(1.0, dodgeChance) : 0.0;
    }
}
