package com.trinityforge.listeners;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;

/**
 * 装備の作り直し（鍛冶台ネザライト／金床 combine）で消える ArsPaper の装着データ。
 *
 * <p>2026-08-29 実サーバ報告「バックパック付き魔道装備の強化で中身消失」。
 * TF は成果物を {@code ItemFactory#create} でまっさらに作るため、エンチャント以外の
 * 外部 PDC は乗らない。
 *
 * <p>儀式のコア→新規装備は {@code ItemUpgradeCarryOver} がソケットに加えてエンチャント・
 * その他個体 PDC も写す。こちらはスミス台／金床専用のソケット転写。
 *
 * <p>写すのは「装着物」だけ。{@code custom_item_id} / {@code armor_tier} /
 * {@code armor_set_id} は新しい成果物の identity なので上書きしない。
 */
final class ArsSocketCarryOver {

    private static final String ARS_NAMESPACE = "arspaper";

    /**
     * {@code com.arspaper.item.ItemKeys} と {@code BackpackGui} のキー名に揃える。
     * フォークを compile 依存にしないため文字列で持つ。
     */
    private static final Set<String> SOCKET_KEYS = Set.of(
            "thread_slots",
            "thread_lore",
            "thread_slot_rolls",
            "thread_slot_owners",
            "thread_type",
            "backpack_data",
            "backpack_thread_data");

    private ArsSocketCarryOver() {
    }

    static void copy(ItemStack from, ItemStack to) {
        if (from == null || to == null || !from.hasItemMeta() || to.getType().isAir()) {
            return;
        }
        ItemMeta fromMeta = from.getItemMeta();
        ItemMeta toMeta = to.getItemMeta();
        if (fromMeta == null || toMeta == null) {
            return;
        }
        PersistentDataContainer src = fromMeta.getPersistentDataContainer();
        PersistentDataContainer dst = toMeta.getPersistentDataContainer();
        boolean wrote = false;
        for (NamespacedKey key : src.getKeys()) {
            if (!ARS_NAMESPACE.equals(key.getNamespace()) || !SOCKET_KEYS.contains(key.getKey())) {
                continue;
            }
            wrote |= copyValue(src, dst, key);
        }
        if (wrote) {
            to.setItemMeta(toMeta);
        }
    }

    private static boolean copyValue(PersistentDataContainer src, PersistentDataContainer dst,
                                     NamespacedKey key) {
        String stringVal = src.get(key, PersistentDataType.STRING);
        if (stringVal != null) {
            dst.set(key, PersistentDataType.STRING, stringVal);
            return true;
        }
        Integer intVal = src.get(key, PersistentDataType.INTEGER);
        if (intVal != null) {
            dst.set(key, PersistentDataType.INTEGER, intVal);
            return true;
        }
        byte[] bytes = src.get(key, PersistentDataType.BYTE_ARRAY);
        if (bytes != null) {
            dst.set(key, PersistentDataType.BYTE_ARRAY, bytes);
            return true;
        }
        return false;
    }
}
