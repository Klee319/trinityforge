package com.trinityforge.woodcutting;

import org.bukkit.Material;

/**
 * Pure material-classification helpers backing the woodcutting skilltree flag-effect consumers
 * ({@code tree-fell} (2026-07-25 統合済み, 旧 small-tree-fell/large-tree-fell)/{@code apple-drop}/
 * {@code golden-apple-drop}/{@code crystal-apple-drop} — see the {@code dedicated-effects:} field on each node in {@code skilltree/*.yml}).
 * Classification is by
 * {@link Material} name suffix rather than an explicit config list: every vanilla log/stem/wood and
 * leaves variant (including stripped logs and future additions) is covered automatically, at the
 * cost of also matching any modded material that happens to share the suffix convention (要調整: add
 * an explicit allow/deny config list if that ever becomes a problem in practice).
 *
 * <p>Bukkit's {@link Material} enum needs no running server to evaluate, so this class is
 * unit-testable exactly like {@code MiningGimmickPolicy}.
 */
public final class WoodcuttingMaterials {

    private WoodcuttingMaterials() {
    }

    /**
     * True for any log-family block a tree is built from: {@code *_LOG}, {@code *_WOOD} (the
     * bark-all-sides variant), {@code *_STEM}/{@code *_HYPHAE} (nether "trees"), stripped or not.
     * Deliberately excludes leaves, saplings, and fungus.
     */
    public static boolean isLog(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    /** True for any leaves-family block (vanilla tree leaves and azalea leaves). */
    public static boolean isLeaves(Material material) {
        if (material == null) {
            return false;
        }
        return material.name().endsWith("_LEAVES");
    }

    /** True when {@code tool} is any axe (wood/stone/iron/gold/diamond/netherite), the felling tool. */
    public static boolean isAxe(Material tool) {
        if (tool == null) {
            return false;
        }
        return tool.name().endsWith("_AXE");
    }
}
