package com.trinityforge.fishing;

import org.bukkit.Material;

import java.util.Set;

/**
 * Pure helpers shared by the釣りスキルツリーB-alpha/B-beta系dedicated-effect consumer
 * ({@code FishingGimmickListener}): junk/treasure classification for {@code junk-to-scrap} and
 * {@code fish-sell-toggle}. Bukkit-event-free so both are unit-testable with fixed inputs.
 *
 * <p>2026-07-27 correction: this Javadoc previously claimed {@code gacha-ticket-4/5} rolled its drop
 * chance via {@code MiningGimmickPolicy.percentRoll}; that was never actually the case (verified: no
 * caller in the codebase references {@code percentRoll} for gacha-ticket-4/5, and that helper has
 * since been deleted anyway). {@code tf_gacha_ticket_4}/{@code tf_gacha_ticket_5} are weighted
 * fishing-category drop-table entries resolved by {@code DropTablePolicy} inside
 * {@code FishingGimmickListener}, gated by the {@code drop:fishing:item:<id>} perk unlock — not a
 * percent-roll at all.
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
