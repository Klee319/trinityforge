package com.trinityforge.skilltree.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * Aggregated unlocked-perk TF buffs: combat attack/defense maps plus vanilla-attribute buffs
 * ({@code move_speed}, {@code attack_speed}, …) applied by {@link PerkAttributeApplier}.
 */
public record PerkBuffs(Map<String, Double> attack, Map<String, Double> defense,
                        Map<String, Double> attributes, Map<String, Double> general,
                        Map<String, Map<String, Double>> multipliers) {

    public static final PerkBuffs EMPTY =
            new PerkBuffs(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

    public PerkBuffs {
        Objects.requireNonNull(attack, "attack");
        Objects.requireNonNull(defense, "defense");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(general, "general");
        Objects.requireNonNull(multipliers, "multipliers");
        attack = Map.copyOf(attack);
        defense = Map.copyOf(defense);
        attributes = Map.copyOf(attributes);
        general = Map.copyOf(general);
        java.util.LinkedHashMap<String, Map<String, Double>> multiplierCopy = new java.util.LinkedHashMap<>();
        multipliers.forEach((layer, stats) -> multiplierCopy.put(layer, Map.copyOf(stats)));
        multipliers = Map.copyOf(multiplierCopy);
    }

    /** Backward-compatible ctor without general buffs / multiplier layers. */
    public PerkBuffs(Map<String, Double> attack, Map<String, Double> defense,
                     Map<String, Double> attributes) {
        this(attack, defense, attributes, Map.of(), Map.of());
    }

    /** Backward-compatible ctor used by older call sites/tests. */
    public PerkBuffs(Map<String, Double> attack, Map<String, Double> defense) {
        this(attack, defense, Map.of());
    }
}
