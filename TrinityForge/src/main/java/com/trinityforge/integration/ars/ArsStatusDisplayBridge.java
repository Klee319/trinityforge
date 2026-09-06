package com.trinityforge.integration.ars;

import com.trinityforge.stats.StatKeys;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Projects ArsPaper's final mana values onto TrinityForge's status display.
 *
 * <p>The generic TF aggregate contains only stat contributions. ArsPaper's mana manager adds the
 * base value, thread counters and its own caps, so displaying {@code mana_bonus} directly makes
 * the status screen show a bonus where the player expects the actual maximum. Cost reduction has a
 * similar split: the manager owns the thread percentage while TF owns non-thread item/perk stats.
 * This bridge is display-only; combat and spell calculation remain unchanged.
 */
public final class ArsStatusDisplayBridge {

    private static final String MANA_BONUS = StatKeys.canonical("mana_bonus");
    private static final String COST_REDUCTION_PERCENT =
            StatKeys.canonical("mana_cost_reduction_percent");

    private ArsStatusDisplayBridge() {
    }

    /**
     * Replaces the two display values with the final Ars values when ArsPaper is available.
     * Reflection failures return a defensive copy of the original aggregate (fail-soft).
     */
    public static Map<String, Double> project(Player player, Map<String, Double> aggregate) {
        Objects.requireNonNull(aggregate, "aggregate");
        ArsValues values = read(player);
        return values == null ? new LinkedHashMap<>(aggregate)
                : project(aggregate, values.maxMana(), values.threadCostReductionPercent());
    }

    /** Pure projection seam for regression tests. */
    static Map<String, Double> project(Map<String, Double> aggregate, int maxMana,
                                       int threadCostReductionPercent) {
        Map<String, Double> projected = new LinkedHashMap<>(aggregate);
        if (maxMana >= 0) {
            projected.put(MANA_BONUS, (double) maxMana);
        }
        double tfPercent = projected.getOrDefault(COST_REDUCTION_PERCENT, 0.0);
        if (!Double.isFinite(tfPercent)) {
            tfPercent = 0.0;
        }
        double threadFraction = Math.max(0, Math.min(100, threadCostReductionPercent)) / 100.0;
        // SpellCaster adds the non-thread TF percentage after ManaManager's thread percentage.
        projected.put(COST_REDUCTION_PERCENT,
                Math.max(0.0, Math.min(1.0, threadFraction + tfPercent)));
        return projected;
    }

    private static ArsValues read(Player player) {
        if (player == null) {
            return null;
        }
        ClassLoader loader = ArsGuiBridgeSupport.arsPaperClassLoader();
        if (loader == null) {
            return null;
        }
        try {
            Class<?> arsClass = loader.loadClass("com.arspaper.ArsPaper");
            Object ars = arsClass.getMethod("getInstance").invoke(null);
            if (ars == null) {
                return null;
            }
            Object mana = arsClass.getMethod("getManaManager").invoke(ars);
            if (mana == null) {
                return null;
            }
            Method max = mana.getClass().getMethod("getMaxMana", Player.class);
            Method reduction = mana.getClass().getMethod("getCostReductionPercent", Player.class);
            Object maxValue = max.invoke(mana, player);
            Object reductionValue = reduction.invoke(mana, player);
            if (!(maxValue instanceof Number maxNumber) || !(reductionValue instanceof Number reductionNumber)) {
                return null;
            }
            return new ArsValues(maxNumber.intValue(), reductionNumber.intValue());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private record ArsValues(int maxMana, int threadCostReductionPercent) {
    }
}
