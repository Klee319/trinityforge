package com.trinityforge.progression;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.CombatRoleSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Resolves a player's combat/support role into TF stat-channel injections (ROLE_SYSTEM_SPEC §2.1).
 */
public final class RoleBuffResolver {

    public record Contribution(Map<String, Double> attackBuffs, Map<String, Double> defenseBuffs,
                               double hateThreatMultiplier, String expSkill, double expMultiplier) {
        public static final Contribution EMPTY =
                new Contribution(Map.of(), Map.of(), 1.0, null, 1.0);
    }

    private final RoleBuffsConfig config;

    public RoleBuffResolver(RoleBuffsConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Contribution contributionFor(Player player) {
        if (player == null) {
            return Contribution.EMPTY;
        }
        PlayerData data = PlayerData.of(player);
        CombatRoleSpec combat = data.rolePrimary().map(config::combatRole).orElse(null);
        SupportRoleSpec support = data.roleSupport().map(config::supportRole).orElse(null);

        Map<String, Double> attack = new LinkedHashMap<>();
        Map<String, Double> defense = new LinkedHashMap<>();
        double hateMult = 1.0;
        String expSkill = null;
        double expMult = 1.0;

        if (combat != null) {
            mergeCanonical(attack, combat.attackBuffs());
            mergeCanonical(defense, combat.defenseBuffs());
            hateMult = Math.max(1.0, combat.hateThreatMultiplier());
        }
        if (support != null) {
            if (support.expSkill() != null && !support.expSkill().isBlank()) {
                expSkill = support.expSkill();
                expMult = support.expMultiplier();
            }
        }
        return new Contribution(
                Map.copyOf(attack), Map.copyOf(defense), hateMult, expSkill, expMult);
    }

    public OptionalDouble expMultiplierForSkill(Player player, String skillType) {
        Contribution c = contributionFor(player);
        if (c.expSkill() == null || skillType == null) {
            return OptionalDouble.empty();
        }
        if (c.expSkill().equalsIgnoreCase(skillType)) {
            return OptionalDouble.of(c.expMultiplier());
        }
        return OptionalDouble.empty();
    }

    private static void mergeCanonical(Map<String, Double> target, Map<String, Double> source) {
        source.forEach((key, value) -> target.merge(StatKeys.canonical(key), value, Double::sum));
    }
}
