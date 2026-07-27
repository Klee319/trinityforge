package com.trinityforge.stats;

import org.bukkit.Material;

import java.util.Objects;

/**
 * The material tier of a piece of equipment (ITEM_ECONOMY_SPEC 5, ValhallaMMO {@code vanillaAttributes}
 * 踏襲). Resolved from a {@link Material}'s name alone so a plain vanilla item — even one with no
 * TrinityForge PDC — maps to a tier, letting the material-base stat layer apply live to every piece of
 * equipment (Q1 = 全装備にライブ適用). The tier feeds the per-tier×category base-stat table
 * ({@code stats/roll.yml} の {@code material-base} セクション); the randomized roll (rollSeed + quality) is
 * added on top.
 *
 * <p>Ladder (weak → strong, DESIGN 2026-07-15): 木 → 石 → 銅 → 鉄 → 金 → ダイヤ → ネザライト. Armour-only
 * classes ({@link #LEATHER}, {@link #CHAINMAIL}, {@link #TURTLE}) sit alongside; {@link #GOLDEN} is its
 * own rung (vanilla gold: fast/low-durability), not forced into the linear order. {@link #NONE} is any
 * material that is not tiered equipment (blocks, food, ...). Bukkit-free apart from {@link Material}
 * (a plain enum, safe off a live server), so the classification is fully unit-testable.
 */
public enum MaterialTier {

    LEATHER,
    WOODEN,
    STONE,
    CHAINMAIL,
    COPPER,
    IRON,
    GOLDEN,
    TURTLE,
    DIAMOND,
    NETHERITE,
    NONE;

    /** The {@code stats/roll.yml} {@code material-base} key for this tier (lower-cased enum name). */
    public String configKey() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * The tier of {@code material}, or {@link #NONE} when it is not tiered equipment. Matches on the
     * material-name prefix (tools/weapons/armor share a tier prefix, e.g. {@code IRON_}), with the two
     * special armour helmets ({@code TURTLE_HELMET}) and the leather/chainmail armour classes handled
     * explicitly.
     */
    public static MaterialTier of(Material material) {
        Objects.requireNonNull(material, "material");
        String name = material.name();
        if (name.startsWith("NETHERITE_")) {
            return NETHERITE;
        }
        if (name.startsWith("DIAMOND_")) {
            return DIAMOND;
        }
        if (name.startsWith("GOLDEN_") || name.startsWith("GOLD_")) {
            return GOLDEN;
        }
        if (name.startsWith("IRON_")) {
            return IRON;
        }
        if (name.startsWith("COPPER_")) {
            return COPPER;
        }
        if (name.startsWith("STONE_")) {
            return STONE;
        }
        if (name.startsWith("WOODEN_") || name.startsWith("WOOD_")) {
            return WOODEN;
        }
        if (name.startsWith("CHAINMAIL_")) {
            return CHAINMAIL;
        }
        if (name.startsWith("LEATHER_")) {
            return LEATHER;
        }
        if (name.equals("TURTLE_HELMET")) {
            return TURTLE;
        }
        // Un-prefixed tiered equipment: bows, tridents, maces, shears, fishing rods, flint & steel.
        return switch (name) {
            case "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHEARS", "FISHING_ROD", "FLINT_AND_STEEL" -> WOODEN;
            default -> NONE;
        };
    }

    /** True when the material is tiered equipment (has a material-base entry to look up). */
    public boolean isEquipment() {
        return this != NONE;
    }
}
