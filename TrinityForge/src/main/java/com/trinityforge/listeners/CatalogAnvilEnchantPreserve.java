package com.trinityforge.listeners;

import com.trinityforge.stats.ItemIdentityCopy;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 金床でエンチャント本をカタログ品へ付けたとき、バニラが結果の Material を差し替えるのを防ぐ。
 *
 * <p>2026-08-29 実サーバ報告「広辞苑がエンチャント本になる」。広辞苑は {@code BOOK#100004} で、
 * バニラは「本 + エンチャント本 = エンチャント本」とするため、CMD も PDC も落ちて素の
 * {@code ENCHANTED_BOOK} になる。同じ穴はカタログの BOOK 全般（および結果の type が
 * 左枠と変わる任意のカタログ品）に開いている。
 *
 * <p>左枠の identity を残し、バニラ結果側のエンチャント（本なら格納エンチャント）と
 * 金床の改名だけを写す。type が変わらない剣＋本でも、バニラ結果から品質 PDC が落ちることが
 * あるので rollSeed / quality / catalog id は左枠から必ず戻す。
 */
final class CatalogAnvilEnchantPreserve {

    private CatalogAnvilEnchantPreserve() {
    }

    static ItemStack preserveIfTypeChanged(ItemStack first, ItemStack vanillaResult) {
        ItemStack preserved = preserveType(first, vanillaResult);
        ItemIdentityCopy.copyRollQualityCatalog(first, preserved);
        return preserved;
    }

    private static ItemStack preserveType(ItemStack first, ItemStack vanillaResult) {
        if (first == null || first.getType().isAir()
                || vanillaResult == null || vanillaResult.getType().isAir()) {
            return vanillaResult;
        }
        if (first.getType() == vanillaResult.getType()) {
            return vanillaResult;
        }
        ItemStack preserved = first.clone();
        copyEnchantsAndRename(vanillaResult, preserved);
        return preserved;
    }

    private static void copyEnchantsAndRename(ItemStack from, ItemStack to) {
        ItemMeta toMeta = to.getItemMeta();
        if (toMeta == null) {
            return;
        }
        from.getEnchantments().forEach((enchantment, level) ->
                toMeta.addEnchant(enchantment, Math.max(toMeta.getEnchantLevel(enchantment), level), true));
        ItemMeta fromMeta = from.getItemMeta();
        if (fromMeta instanceof EnchantmentStorageMeta stored) {
            stored.getStoredEnchants().forEach((enchantment, level) ->
                    toMeta.addEnchant(enchantment, Math.max(toMeta.getEnchantLevel(enchantment), level), true));
        }
        if (fromMeta != null && fromMeta.hasDisplayName()) {
            toMeta.displayName(fromMeta.displayName());
        }
        to.setItemMeta(toMeta);
    }
}
