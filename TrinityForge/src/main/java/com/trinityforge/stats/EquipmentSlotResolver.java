package com.trinityforge.stats;

import org.bukkit.Material;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Pure inference of which equipment slot an item's attribute modifiers should be scoped to, from
 * its {@link Material} alone (COMBAT_SYSTEM_SPEC 5). Without this, {@link AttributeApplier}'s
 * previous fixed {@code EquipmentSlotGroup.ANY} scope let an armor stat apply from *any* slot the
 * item occupied, including a bare mainhand/offhand hold, so a player could double-dip an armor
 * bonus by holding a second copy while wearing one.
 *
 * <p>A weapon/tool resolves to {@link Category#MAINHAND} (matching where it is already wielded, so
 * this narrows nothing new for that class of item); an armor piece resolves to its matching armor
 * slot so it only contributes while actually worn. Anything this class cannot categorize (blocks,
 * food, decorative materials, horse armor, ...) falls back to {@link Category#ANY}, preserving the
 * previous behaviour for those materials — see {@link AttributeApplier} for how {@link Category}
 * maps onto the real Bukkit {@code EquipmentSlotGroup}.
 *
 * <p>Bukkit-free apart from {@link Material} (a plain enum, safe off a live server), so the
 * classification rule is fully unit-testable.
 */
public final class EquipmentSlotResolver {

    /**
     * Slot category a material's attribute modifiers should be scoped to. Deliberately not the
     * Bukkit {@code EquipmentSlotGroup} type itself so this class stays testable without a live
     * server; {@link AttributeApplier} is the thin adapter that maps a {@link Category} onto the
     * actual slot-group constant.
     */
    public enum Category { MAINHAND, HEAD, CHEST, LEGS, FEET, ANY }

    private EquipmentSlotResolver() {
    }

    /** Item stat-category names for I7 ({@code stats/roll.yml applies-to}). */
    public static final String CATEGORY_WEAPON = "weapon";
    public static final String CATEGORY_ARMOR = "armor";
    public static final String CATEGORY_TOOL = "tool";
    public static final String CATEGORY_OTHER = "other";

    /**
     * The stat-categories a material belongs to — a SET, since an item can be several things at once
     * (DESIGN 2026-07-15): an <b>axe is both {@code weapon} and {@code tool}</b>. Swords/bows/crossbows/
     * spears/tridents/maces are {@code weapon}; pickaxes/shovels/hoes/fishing-rods/shears/flint&amp;steel are
     * {@code tool}; armor slots are {@code armor}; anything else is {@code other}. These categories drive
     * weapon detection for the weapon-base-formula and the crafting-skill selection
     * ({@code CraftQualityPolicy}).
     *
     * <p>This is the SOLE classification, by material name only (the {@code stats/item-categories.yml}
     * override config was removed 2026-07-19 as unused; there is no longer a way to re-classify a
     * material without a code change here).
     */
    public static Set<String> statCategories(Material material) {
        Objects.requireNonNull(material, "material");
        Set<String> categories = new LinkedHashSet<>();
        if (isCombatWeapon(material.name())) {
            categories.add(CATEGORY_WEAPON);
        }
        if (isTool(material.name())) {
            categories.add(CATEGORY_TOOL);
        }
        switch (resolve(material)) {
            case HEAD, CHEST, LEGS, FEET -> categories.add(CATEGORY_ARMOR);
            default -> { /* weapon/tool handled above */ }
        }
        if (categories.isEmpty()) {
            categories.add(CATEGORY_OTHER);
        }
        return categories;
    }

    /** Combat-primary weapons that roll {@code weapon} stats. The axe is BOTH (see {@link #isTool}). */
    private static boolean isCombatWeapon(String name) {
        return name.endsWith("_SWORD")
                || name.endsWith("_SPEAR")
                || name.endsWith("_AXE")
                || name.equals("BOW")
                || name.equals("CROSSBOW")
                || name.equals("TRIDENT")
                || name.equals("MACE");
    }

    /**
     * Production tools that roll {@code tool} stats. The axe is included here too (weapon+tool
     * dual-classification), as are the utility tools ({@code FISHING_ROD}/{@code SHEARS}/
     * {@code FLINT_AND_STEEL}) that {@link #resolve} leaves at {@link Category#ANY}.
     */
    private static boolean isTool(String name) {
        return name.endsWith("_PICKAXE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.equals("FISHING_ROD")
                || name.equals("SHEARS")
                || name.equals("FLINT_AND_STEEL");
    }

    /**
     * カボチャとスカル/ヘッド系のうち、プレイヤーが実際に頭スロットへ装備できるもの（レーンC）。
     * {@code name.endsWith("_HEAD")} のような接尾辞判定は {@code PISTON_HEAD}（技術ブロック、頭スロットに
     * 装備できない）を巻き込むため使わない。明示列挙で固定する。Material 定数を直接参照すると将来の版で
     * 存在しない名前がコンパイルエラーになりうるので、このクラスの既存の流儀どおり文字列比較で書く。
     */
    private static final Set<String> HEAD_EQUIPPABLE = Set.of(
            "CARVED_PUMPKIN",
            "PLAYER_HEAD",
            "ZOMBIE_HEAD",
            "SKELETON_SKULL",
            "WITHER_SKELETON_SKULL",
            "CREEPER_HEAD",
            "DRAGON_HEAD",
            "PIGLIN_HEAD");

    /** Classifies {@code material} into the slot its attribute modifiers should be scoped to. */
    public static Category resolve(Material material) {
        Objects.requireNonNull(material, "material");
        String name = material.name();

        if (name.endsWith("_HELMET") || name.equals("TURTLE_HELMET") || HEAD_EQUIPPABLE.contains(name)) {
            return Category.HEAD;
        }
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) {
            return Category.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return Category.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return Category.FEET;
        }
        if (isWeaponOrTool(name)) {
            return Category.MAINHAND;
        }
        return Category.ANY;
    }

    /**
     * Weapon/tool materials meant to be wielded in the mainhand. Deliberately excludes
     * off-hand-oriented items (shield) and utility tools with no combat-stat relevance (fishing
     * rod, flint and steel, shears) — those stay at {@link Category#ANY}, matching the previous
     * unscoped behaviour rather than asserting a debatable slot for them.
     */
    private static boolean isWeaponOrTool(String name) {
        return name.endsWith("_SWORD")
                || name.endsWith("_SPEAR")
                || name.endsWith("_AXE")
                || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.equals("BOW")
                || name.equals("CROSSBOW")
                || name.equals("TRIDENT")
                || name.equals("MACE");
    }
}
