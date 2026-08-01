package com.trinityforge.progression;

import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.ItemUseRequirement;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves use-skill / use-level for an item at runtime. {@code stats/item-stats.yml} is the live
 * source of truth when a profile exists for the item's material (+ CMD); PDC is a fallback for
 * catalog-only stamps or items without an item-stats entry.
 */
public final class UseRequirementResolver {

    public record Resolved(String skill, int level, String role) {

        /** ロール条件を持たない従来どおりの結果。既存の呼び出し側はこちらのまま。 */
        public Resolved(String skill, int level) {
            this(skill, level, null);
        }

        public boolean hasSkill() {
            return skill != null && !skill.isBlank();
        }

        /** ロール専用装備か（{@code use-role} が書かれているか）。 */
        public boolean hasRole() {
            return role != null && !role.isBlank();
        }
    }

    private UseRequirementResolver() {
    }

    /**
     * @return the effective requirement, or empty when the item is unrestricted
     */
    public static Optional<Resolved> resolve(ItemStack stack, ItemStatsConfig itemStats) {
        Objects.requireNonNull(itemStats, "itemStats");
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        Integer cmd = stack.hasItemMeta()
                ? DerivedItemStats.customModelDataOf(stack.getItemMeta()) : null;

        if (itemStats.profileFor(stack.getType(), cmd).isPresent()) {
            Optional<ItemUseRequirement> live = itemStats.useRequirementFor(stack.getType(), cmd);
            if (live.isPresent() && (live.get().hasSkill() || live.get().hasRole())) {
                ItemUseRequirement req = live.get();
                return Optional.of(new Resolved(req.skill(), req.level(), req.role()));
            }
            // item-stats プロファイルはあるが use-skill 未設定 → ItemAssembler が catalog から
            // 刻印した PDC をフォールバック( loot/creative 等で editor 側 use gate のみあるケース)。
            if (stack.hasItemMeta()) {
                Optional<Resolved> pdc = fromPdc(stack);
                if (pdc.isPresent()) {
                    return pdc;
                }
            }
            return Optional.empty();
        }

        if (!stack.hasItemMeta()) {
            return Optional.empty();
        }
        return fromPdc(stack);
    }

    private static Optional<Resolved> fromPdc(ItemStack stack) {
        ItemData data = ItemData.of(stack.getItemMeta());
        return data.useSkill()
                .filter(skill -> !skill.isBlank())
                .map(skill -> new Resolved(skill, data.useLevelRequirement().orElse(0)));
    }

    /** Ranged weapons are gated at shoot/throw time, not on draw/right-click interact. */
    public static boolean skipInteractGate(Material material) {
        if (material == null) {
            return false;
        }
        return switch (material) {
            case BOW, CROSSBOW, TRIDENT -> true;
            default -> false;
        };
    }
}
