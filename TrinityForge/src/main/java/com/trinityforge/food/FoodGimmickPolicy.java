package com.trinityforge.food;

import org.bukkit.Material;

import java.util.Set;

/**
 * Pure helpers shared by the food/満腹スキルツリーのdedicated-effect consumer
 * ({@code FoodGimmickListener}): junk-food classification and the
 * {@code junkfood-inversion} recovery-amount adjustment. Bukkit-event-free so both are
 * unit-testable with fixed inputs. The percent-chance roll used by
 * {@code no-food-consume-chance} reuses {@link com.trinityforge.mining.MiningGimmickPolicy#percentRoll}
 * directly (same generic 0-100 percent-roll utility already shared by the farming gimmick
 * listeners; not duplicated here).
 */
public final class FoodGimmickPolicy {

    private FoodGimmickPolicy() {
    }

    /** True when {@code material} is configured as a "ゴミ食"(junk food) item. Null-safe. */
    public static boolean isJunkFood(Material material, Set<Material> junkFoodMaterials) {
        if (material == null || junkFoodMaterials == null) {
            return false;
        }
        return junkFoodMaterials.contains(material);
    }

    /**
     * {@code junkfood-inversion} 用の隠し満腹度(saturation)補正量: ゴミ食なら {@code junkBonus} を加算、
     * 非ゴミ食(かつ{@code applyPenaltyToNonJunk}=true)なら {@code nonJunkPenalty} を減算する。結果は
     * 0未満にはクランプしない(呼び出し側で {@code player.getSaturation() + adjustment} を計算後、
     * Bukkit側で自然に0未満は0にクランプされる)。非有限値/負値の {@code junkBonus}/{@code nonJunkPenalty}
     * は0として扱う(設定ミスで加算/減算が暴走しないためのガード)。
     */
    public static double inversionSaturationAdjustment(boolean junk, double junkBonus, double nonJunkPenalty) {
        double safeBonus = safeNonNegative(junkBonus);
        double safePenalty = safeNonNegative(nonJunkPenalty);
        return junk ? safeBonus : -safePenalty;
    }

    private static double safeNonNegative(double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            return 0.0;
        }
        return value;
    }
}
