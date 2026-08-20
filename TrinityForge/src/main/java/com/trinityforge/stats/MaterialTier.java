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
    /**
     * 接頭辞だけでは足りないので、装備の「種類」を表す接尾辞も要求する。
     *
     * <p><b>2026-08-17 修正 (ユーザー報告「釣りで釣った鉱石がスタックできない」)</b>:
     * 判定が材質名の接頭辞だけだったため、{@code IRON_INGOT} / {@code COPPER_INGOT} /
     * {@code GOLD_INGOT} / {@code GOLDEN_APPLE} / {@code DIAMOND_BLOCK} などが軒並み
     * 「ティア装備」に化けていた。釣果がこの判定で装備扱いされると
     * {@code FishingQualityListener} が品質スタンプ(固有の rollSeed)を押すので、
     * <b>釣った鉱石が1個ずつ別物になってスタックできなくなる</b>。
     */
    private static final java.util.Set<String> EQUIPMENT_SUFFIXES = java.util.Set.of(
            "SWORD", "PICKAXE", "AXE", "SHOVEL", "HOE",
            "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS", "HORSE_ARMOR");

    private static boolean hasEquipmentSuffix(String name) {
        int underscore = name.indexOf('_');
        return underscore >= 0 && EQUIPMENT_SUFFIXES.contains(name.substring(underscore + 1));
    }

    public static MaterialTier of(Material material) {
        Objects.requireNonNull(material, "material");
        String name = material.name();
        if (!hasEquipmentSuffix(name)) {
            // 接頭辞が一致しても装備でないもの(インゴット/ブロック/金リンゴ 等)はここで落とす。
            // 接頭辞を持たない BOW などは下の switch が拾う。
            return switch (name) {
                case "BOW", "CROSSBOW", "TRIDENT", "MACE", "SHEARS", "FISHING_ROD",
                     "FLINT_AND_STEEL" -> WOODEN;
                default -> NONE;
            };
        }
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
