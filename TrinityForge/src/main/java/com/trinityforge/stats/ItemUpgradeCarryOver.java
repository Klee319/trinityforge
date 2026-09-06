package com.trinityforge.stats;

import com.trinityforge.pdc.PdcKeys;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 装備の作り直し（儀式でコアが別の装備になる経路）で、品質とロール以外の個体データを
 * 成果物へ写す。
 *
 * <p>成果物は {@code ItemFactory#createIdentityOnly} でまっさらに作るため、コアに付いていた
 * エンチャント・スレッド・バックパック・パーティクルシードなどは乗らない。品質と rollSeed は
 * {@link RitualCraftFinalizer} が儀式実行者基準で新規ロールするので、こちらでは写さない。
 *
 * <p>新しい成果物の identity（カタログ id・Ars の {@code custom_item_id}／ティア、使用可能レベル、
 * bind-type）は上書きしない。
 */
public final class ItemUpgradeCarryOver {

    private static final String ARS_NAMESPACE = "arspaper";

    /**
     * 新しい成果物側が持つ identity／組み立て用キー。コアから写すと見習いが魔術師に見えても
     * PDC は旧品のまま、という無言の壊れ方になる。
     */
    private static final Set<String> SKIP_TF_KEYS = Set.of(
            PdcKeys.ITEM_ROLL_SEED.getKey(),
            PdcKeys.ITEM_QUALITY.getKey(),
            PdcKeys.ITEM_PENDING_CRAFT_QUALITY.getKey(),
            PdcKeys.ITEM_CRAFT_ROLL_UP.getKey(),
            PdcKeys.ITEM_CRAFT_ROLL_DOWN_REDUCTION.getKey(),
            PdcKeys.ITEM_CRAFT_ROLL_INSET_DELTA.getKey(),
            PdcKeys.ITEM_CATALOG_ID.getKey(),
            PdcKeys.ITEM_BIND_TYPE.getKey(),
            PdcKeys.ITEM_USE_LEVEL_REQ.getKey(),
            PdcKeys.ITEM_USE_SKILL.getKey(),
            PdcKeys.ITEM_DATA_VERSION.getKey(),
            PdcKeys.ITEM_TABLE_GENERATION.getKey(),
            PdcKeys.ITEM_TOOL_ENCHANT_BONUS.getKey(),
            "tool-enchants");

    private static final Set<String> SKIP_ARS_KEYS = Set.of(
            "custom_item_id",
            "armor_tier",
            "wand_tier",
            "book_tier",
            "armor_set_id");

    private static final List<PersistentDataType<?, ?>> PDC_TYPES = List.of(
            PersistentDataType.STRING,
            PersistentDataType.INTEGER,
            PersistentDataType.LONG,
            PersistentDataType.BYTE,
            PersistentDataType.SHORT,
            PersistentDataType.FLOAT,
            PersistentDataType.DOUBLE,
            PersistentDataType.BOOLEAN,
            PersistentDataType.BYTE_ARRAY,
            PersistentDataType.INTEGER_ARRAY,
            PersistentDataType.LONG_ARRAY,
            PersistentDataType.TAG_CONTAINER);

    private ItemUpgradeCarryOver() {
    }

    /**
     * エンチャント以外の個体データ（PDC・トリム・修繕コスト）を写す。
     * {@link RitualCraftFinalizer} の stamp より<b>前</b>に呼ぶ（スレッド枠ボーナス等が
     * 組み立てに乗る必要があるため）。
     */
    public static void copyPersistent(ItemStack from, ItemStack to) {
        if (from == null || to == null || !from.hasItemMeta() || to.getType().isAir()) {
            return;
        }
        ItemMeta fromMeta = from.getItemMeta();
        ItemMeta toMeta = to.getItemMeta();
        if (fromMeta == null || toMeta == null) {
            return;
        }
        boolean wrote = copyPdc(fromMeta.getPersistentDataContainer(), toMeta.getPersistentDataContainer());
        wrote |= copyTrim(fromMeta, toMeta);
        wrote |= copyRepairCost(fromMeta, toMeta);
        if (wrote) {
            to.setItemMeta(toMeta);
        }
    }

    /**
     * コアに付いていたエンチャントを成果物へ写す。品質 stamp の tool-enchant 適用が
     * プレイヤー付与分を剥がさないよう、<b>stamp の後</b>に呼ぶ。
     *
     * <p>付与可否({@code canEnchantItem})で絞り込まない。カタログ品は見た目のために
     * 本来の武器種と違う Material を土台にすることがあり（杖など）、ここで絞ると
     * 正しいエンチャントの方が消える。上限突破は {@code addUnsafeEnchantment} で残す。
     */
    public static void copyEnchantments(ItemStack from, ItemStack to) {
        if (from == null || to == null || to.getType().isAir()) {
            return;
        }
        for (Map.Entry<Enchantment, Integer> entry : from.getEnchantments().entrySet()) {
            Enchantment ench = entry.getKey();
            int fromBase = entry.getValue();
            if (fromBase <= 0) {
                continue;
            }
            if (to.getEnchantmentLevel(ench) >= fromBase) {
                continue;
            }
            to.addUnsafeEnchantment(ench, fromBase);
        }
    }

    private static boolean copyPdc(PersistentDataContainer src, PersistentDataContainer dst) {
        boolean wrote = false;
        for (NamespacedKey key : src.getKeys()) {
            if (skip(key)) {
                continue;
            }
            wrote |= copyPdcValue(src, dst, key);
        }
        return wrote;
    }

    private static boolean skip(NamespacedKey key) {
        String ns = key.getNamespace();
        String name = key.getKey();
        if (PdcKeys.NAMESPACE.equals(ns)) {
            return SKIP_TF_KEYS.contains(name);
        }
        if (ARS_NAMESPACE.equals(ns)) {
            return SKIP_ARS_KEYS.contains(name);
        }
        return false;
    }

    private static boolean copyPdcValue(PersistentDataContainer src, PersistentDataContainer dst,
                                        NamespacedKey key) {
        for (PersistentDataType<?, ?> type : PDC_TYPES) {
            try {
                if (copyTyped(src, dst, key, type)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // MockBukkit 未実装の型や、キーに合わない型。次の型を試す。
            }
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean copyTyped(PersistentDataContainer src, PersistentDataContainer dst,
                                     NamespacedKey key, PersistentDataType type) {
        if (!src.has(key, type)) {
            return false;
        }
        Object value = src.get(key, type);
        if (value == null) {
            return false;
        }
        dst.set(key, type, value);
        return true;
    }

    private static boolean copyTrim(ItemMeta fromMeta, ItemMeta toMeta) {
        if (!(fromMeta instanceof ArmorMeta fromArmor) || !(toMeta instanceof ArmorMeta toArmor)) {
            return false;
        }
        try {
            if (!fromArmor.hasTrim()) {
                return false;
            }
            ArmorTrim trim = fromArmor.getTrim();
            if (trim == null) {
                return false;
            }
            toArmor.setTrim(trim);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean copyRepairCost(ItemMeta fromMeta, ItemMeta toMeta) {
        if (!(fromMeta instanceof Repairable fromRepair) || !(toMeta instanceof Repairable toRepair)) {
            return false;
        }
        try {
            int cost = fromRepair.getRepairCost();
            if (cost <= 0) {
                return false;
            }
            toRepair.setRepairCost(cost);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
