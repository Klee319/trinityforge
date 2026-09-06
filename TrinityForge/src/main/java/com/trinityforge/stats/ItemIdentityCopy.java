package com.trinityforge.stats;

import com.trinityforge.pdc.ItemData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * 作り直しやバニラ結果差し替えのあと、厳選の入力（rollSeed / quality / catalog id）を元の個体から戻す。
 *
 * <p>{@link ItemUpgradeCarryOver} は儀式用に品質とロールを<strong>意図的に写さない</strong>。
 * エンチャント台・金床の本付け・カタログ合成は「同じ個体の続き」なので、こちらで写す。
 */
public final class ItemIdentityCopy {

    private ItemIdentityCopy() {
    }

    /**
     * {@code from} が持っている rollSeed / quality / catalog id を {@code to} へ上書きする。
     * {@code to} が空、または {@code from} にキーが無い項目は触らない。
     */
    public static void copyRollQualityCatalog(ItemStack from, ItemStack to) {
        if (from == null || to == null || from.getType().isAir() || to.getType().isAir()) {
            return;
        }
        if (!from.hasItemMeta()) {
            return;
        }
        ItemMeta fromMeta = from.getItemMeta();
        ItemMeta toMeta = to.getItemMeta();
        if (fromMeta == null || toMeta == null) {
            return;
        }
        ItemData src = ItemData.of(fromMeta);
        ItemData dst = ItemData.of(toMeta);
        boolean wrote = false;
        if (src.hasRollSeed()) {
            dst.setRollSeed(src.rollSeed().orElseThrow());
            wrote = true;
        }
        if (src.hasQuality()) {
            dst.setQuality(src.quality());
            wrote = true;
        }
        if (src.catalogId().isPresent()) {
            dst.setCatalogId(src.catalogId().orElseThrow());
            wrote = true;
        }
        if (wrote) {
            to.setItemMeta(toMeta);
        }
    }
}
