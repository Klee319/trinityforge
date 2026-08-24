package com.trinityforge.listeners;

import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Optional;

/**
 * Pure matching rules for particle-seed合成 (2026-07-23-stat-gate-overhaul §6.1). Bukkit-free where
 * possible so the shape of "which stack counts as this seed" / "which material counts as a tool" is
 * unit-testable without spinning up a full item stack.
 */
public final class ParticleSeedMatcher {

    private ParticleSeedMatcher() {
    }

    private static final String CUSTOM_PREFIX = "custom:";

    /**
     * {@code seedItemSpec} is either {@code custom:<catalogId>} (matched via cross-plugin catalog
     * identity) or a bare {@code Material} name (matched by type, case-insensitive).
     */
    public static boolean matchesSeed(ItemStack stack, String seedItemSpec) {
        if (stack == null || stack.getType().isAir() || seedItemSpec == null || seedItemSpec.isBlank()) {
            return false;
        }
        String spec = seedItemSpec.trim();
        if (spec.toLowerCase(Locale.ROOT).startsWith(CUSTOM_PREFIX)) {
            String wantedId = spec.substring(CUSTOM_PREFIX.length());
            Optional<String> actualId = CrossPluginItemResolver.idOf(stack);
            return actualId.isPresent() && actualId.get().equals(wantedId);
        }
        Material material = Material.matchMaterial(spec);
        return material != null && stack.getType() == material;
    }

    /** ツール/武器判定(パーティクルシードの付与先・使用トリガー対象)。 */
    public static boolean isToolOrWeapon(Material material) {
        if (material == null) {
            return false;
        }
        String n = material.name();
        if (n.equals("MACE") || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT")
                || n.equals("SHEARS") || n.equals("FISHING_ROD") || n.equals("FLINT_AND_STEEL")) {
            return true;
        }
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE");
    }
}
