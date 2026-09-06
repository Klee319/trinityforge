package com.trinityforge.listeners;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/**
 * カタログ革防具の染色・鍛冶型トリムで、バニラ結果に差し替わったあとも個体データを残す。
 *
 * <p>{@code ItemFactory#create}/{@code stamp}/{@code assemble} は使わない。create は
 * {@code template.color()} で色を上書きし、stamp は新しい rollSeed を焼きかねる。
 * 入力個体を clone し、バニラ結果から色または trim だけを写す。
 */
final class CatalogCosmeticPreserve {

    private CatalogCosmeticPreserve() {
    }

    static boolean isArmorTrimTemplate(ItemStack template) {
        if (template == null || template.getType().isAir()) {
            return false;
        }
        return template.getType().name().endsWith("_ARMOR_TRIM_SMITHING_TEMPLATE");
    }

    /**
     * 作業台の革防具染色(防具1点 + 染料のみ、結果は同じ Material)。
     * 2点の同種修理は {@link CraftQualityListener#isVanillaSameItemRepair} 側。
     */
    static boolean isLeatherDyeCraft(ItemStack[] matrix, ItemStack result) {
        if (matrix == null || result == null || result.getType().isAir()) {
            return false;
        }
        if (!(result.getItemMeta() instanceof LeatherArmorMeta)) {
            return false;
        }
        Material resultType = result.getType();
        ItemStack armor = null;
        int dyes = 0;
        int other = 0;
        for (ItemStack slot : matrix) {
            if (slot == null || slot.getType().isAir()) {
                continue;
            }
            if (slot.getItemMeta() instanceof LeatherArmorMeta && slot.getType() == resultType) {
                if (armor != null) {
                    return false;
                }
                armor = slot;
            } else if (isDye(slot.getType())) {
                dyes++;
            } else {
                other++;
            }
        }
        return armor != null && dyes >= 1 && other == 0;
    }

    static ItemStack leatherArmorIngredient(ItemStack[] matrix, Material resultType) {
        if (matrix == null || resultType == null) {
            return null;
        }
        for (ItemStack slot : matrix) {
            if (slot == null || slot.getType().isAir()) {
                continue;
            }
            if (slot.getType() == resultType && slot.getItemMeta() instanceof LeatherArmorMeta) {
                return slot;
            }
        }
        return null;
    }

    /**
     * 入力個体を残し、バニラ結果の染料色だけを写す。vanilla が空ならそのまま返す。
     */
    static ItemStack applyColorOnto(ItemStack base, ItemStack vanillaResult) {
        if (base == null || base.getType().isAir()
                || vanillaResult == null || vanillaResult.getType().isAir()) {
            return vanillaResult;
        }
        ItemStack preserved = base.clone();
        if (vanillaResult.getItemMeta() instanceof LeatherArmorMeta from
                && preserved.getItemMeta() instanceof LeatherArmorMeta to) {
            to.setColor(from.getColor());
            preserved.setItemMeta(to);
        }
        return preserved;
    }

    /**
     * 入力個体を残し、バニラ結果の防具トリムだけを写す。vanilla が空ならそのまま返す
     * (結果を捏造して素材だけ消費する穴を開けない)。
     */
    static ItemStack applyTrimOnto(ItemStack base, ItemStack vanillaResult) {
        if (base == null || base.getType().isAir()
                || vanillaResult == null || vanillaResult.getType().isAir()) {
            return vanillaResult;
        }
        ItemStack preserved = base.clone();
        if (vanillaResult.getItemMeta() instanceof ArmorMeta from
                && preserved.getItemMeta() instanceof ArmorMeta to
                && from.hasTrim()) {
            to.setTrim(from.getTrim());
            preserved.setItemMeta(to);
        }
        return preserved;
    }

    private static boolean isDye(Material material) {
        return material != null && material.name().endsWith("_DYE");
    }
}
