package com.trinityforge.listeners;

import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * TrinityForge 装備の砥石利用: エンチャント除去は許可し、TF 由来 PDC（品質/ロール等）を維持する。
 *
 * <p>Paper の砥石処理は多くの場合 PDC を結果 ItemStack へ引き継ぐが、環境差で
 * {@link PdcKeys#ITEM_CATALOG_ID} / {@link PdcKeys#ITEM_ROLL_SEED} が欠落することがある。
 * その場合のみ上スロット入力から該当キーをコピーするセーフガードを掛ける。
 * materials.yml 素材のみの組み合わせは ArsPaper {@code CustomItemListener} が結果を空にする。
 */
public final class GrindstonePreserveListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        ItemStack upper = event.getInventory().getItem(0);
        ItemStack lower = event.getInventory().getItem(1);
        ItemStack result = event.getResult();
        if (result == null || result.getType().isAir()) {
            return;
        }

        ItemStack primary = firstNonEmpty(upper, lower);
        if (primary == null || !hasTfItemIdentity(primary)) {
            return;
        }

        ItemStack preserved = preserveTfKeys(primary, result);
        event.setResult(preserved);
    }

    private static ItemStack firstNonEmpty(ItemStack first, ItemStack second) {
        if (first != null && !first.getType().isAir()) {
            return first;
        }
        if (second != null && !second.getType().isAir()) {
            return second;
        }
        return null;
    }

    private static boolean hasTfItemIdentity(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemData data = ItemData.of(item.getItemMeta());
        return data.catalogId().isPresent()
            || data.hasRollSeed()
            || data.quality() > ItemData.MIN_QUALITY
            || data.bindType().isPresent()
            || data.owner().isPresent();
    }

    private static ItemStack preserveTfKeys(ItemStack source, ItemStack result) {
        ItemStack out = result.clone();
        if (!source.hasItemMeta() || !out.hasItemMeta()) {
            return out;
        }

        ItemMeta sourceMeta = source.getItemMeta();
        ItemMeta resultMeta = out.getItemMeta();
        ItemData src = ItemData.of(sourceMeta);
        ItemData res = ItemData.of(resultMeta);
        PersistentDataContainer srcPdc = sourceMeta.getPersistentDataContainer();
        PersistentDataContainer resPdc = resultMeta.getPersistentDataContainer();

        src.catalogId().ifPresent(id -> {
            if (res.catalogId().isEmpty()) {
                res.setCatalogId(id);
            }
        });
        if (src.hasRollSeed() && !res.hasRollSeed()) {
            res.setRollSeed(src.rollSeed().orElseThrow());
        }
        if (src.quality() > ItemData.MIN_QUALITY && res.quality() <= ItemData.MIN_QUALITY) {
            res.setQuality(src.quality());
        }
        copyStringIfMissing(srcPdc, resPdc, PdcKeys.ITEM_BIND_TYPE);
        copyStringIfMissing(srcPdc, resPdc, PdcKeys.ITEM_OWNER);
        copyIntegerIfMissing(srcPdc, resPdc, PdcKeys.ITEM_DATA_VERSION);
        copyIntegerIfMissing(srcPdc, resPdc, PdcKeys.ITEM_TABLE_GENERATION);
        copyStringIfMissing(srcPdc, resPdc, PdcKeys.ITEM_USE_SKILL);
        copyIntegerIfMissing(srcPdc, resPdc, PdcKeys.ITEM_USE_LEVEL_REQ);
        copyStringIfMissing(srcPdc, resPdc, PdcKeys.ITEM_TOOL_ENCHANT_BONUS);
        copyDoubleIfMissing(srcPdc, resPdc, PdcKeys.ITEM_CRAFT_ROLL_UP);
        copyDoubleIfMissing(srcPdc, resPdc, PdcKeys.ITEM_CRAFT_ROLL_DOWN_REDUCTION);
        copyDoubleIfMissing(srcPdc, resPdc, PdcKeys.ITEM_CRAFT_ROLL_INSET_DELTA);

        // BindType enum round-trip for typed access above; also restore parsed bind if only string was copied.
        src.bindType().ifPresent(bt -> {
            if (res.bindType().isEmpty()) {
                res.setBindType(bt);
            }
        });
        src.owner().ifPresent(owner -> {
            if (res.owner().isEmpty()) {
                res.setOwner(owner);
            }
        });

        out.setItemMeta(resultMeta);
        return out;
    }

    private static void copyStringIfMissing(
            PersistentDataContainer src, PersistentDataContainer dst, org.bukkit.NamespacedKey key) {
        if (dst.has(key, PersistentDataType.STRING)) {
            return;
        }
        String value = src.get(key, PersistentDataType.STRING);
        if (value != null) {
            dst.set(key, PersistentDataType.STRING, value);
        }
    }

    private static void copyIntegerIfMissing(
            PersistentDataContainer src, PersistentDataContainer dst, org.bukkit.NamespacedKey key) {
        if (dst.has(key, PersistentDataType.INTEGER)) {
            return;
        }
        Integer value = src.get(key, PersistentDataType.INTEGER);
        if (value != null) {
            dst.set(key, PersistentDataType.INTEGER, value);
        }
    }

    private static void copyDoubleIfMissing(
            PersistentDataContainer src, PersistentDataContainer dst, org.bukkit.NamespacedKey key) {
        if (dst.has(key, PersistentDataType.DOUBLE)) {
            return;
        }
        Double value = src.get(key, PersistentDataType.DOUBLE);
        if (value != null) {
            dst.set(key, PersistentDataType.DOUBLE, value);
        }
    }
}
