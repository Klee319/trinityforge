package com.trinityforge.skilltree.runtime;

import com.trinityforge.pdc.ItemData;
import org.bukkit.inventory.ItemStack;

/**
 * スキルツリーGUI内で使う機能アイテム (2026-07-27)。
 *
 * <p>実体は {@code items/catalog.yml} の通常のカタログアイテムで、ここでは「どのカタログIDが
 * どの機能に対応するか」だけを持つ。判定は表示名ではなく PDC のカタログID
 * ({@link ItemData#catalogId()}) で行う — 表示名や素材はエディタでいつでも変えられるため。
 *
 * <p>カタログにこのIDのアイテムが定義されていなければ、単にその機能アイテムが世に存在しないだけで、
 * スキルツリーGUIの挙動は従来どおり(解放/プレステージ)になる。
 */
public final class SkillTreeItems {

    /** ノードのロック(プレステージしても解放を維持)を切り替えるアイテムのカタログID。 */
    public static final String NODE_LOCK = "skill_node_lock";
    /** スキルツリーの振り直し(レベル維持・SP返却)アイテムのカタログID。 */
    public static final String TREE_RESET = "skill_tree_reset";

    private SkillTreeItems() {
    }

    /**
     * アイテムが機能アイテムなら対応するカタログIDを返す。
     *
     * @return {@link #NODE_LOCK} / {@link #TREE_RESET} / どちらでもなければ {@code null}
     */
    public static String functionOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        String catalogId = ItemData.of(stack.getItemMeta()).catalogId().orElse(null);
        if (NODE_LOCK.equals(catalogId) || TREE_RESET.equals(catalogId)) {
            return catalogId;
        }
        return null;
    }
}
