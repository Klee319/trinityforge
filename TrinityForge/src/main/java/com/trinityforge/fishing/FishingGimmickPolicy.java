package com.trinityforge.fishing;

import org.bukkit.Material;

import java.util.Set;

/**
 * Pure helpers shared by the釣りスキルツリーB-alpha/B-beta系dedicated-effect consumer
 * ({@code FishingGimmickListener}): junk/treasure classification for {@code junk-to-scrap} and
 * {@code fish-sell-toggle}. Bukkit-event-free so both are unit-testable with fixed inputs. The
 * percent-chance roll used by {@code gacha-ticket-4/5} reuses
 * {@link com.trinityforge.mining.MiningGimmickPolicy#percentRoll} directly (same generic 0-100
 * percent-roll utility already shared by the mining/food gimmick listeners; not duplicated here).
 */
public final class FishingGimmickPolicy {

    private FishingGimmickPolicy() {
    }

    /** True when {@code material} is configured as a釣りの「ゴミ」枠 item. Null-safe. */
    public static boolean isJunk(Material material, Set<Material> junkMaterials) {
        if (material == null || junkMaterials == null) {
            return false;
        }
        return junkMaterials.contains(material);
    }

    /** True when {@code material} is configured as a釣りの「宝」枠 item. Null-safe. */
    public static boolean isTreasure(Material material, Set<Material> treasureMaterials) {
        if (material == null || treasureMaterials == null) {
            return false;
        }
        return treasureMaterials.contains(material);
    }
}
