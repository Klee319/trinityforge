package com.trinityforge.stats;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.text.MiniText;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 空のスレッド({@code thread_empty})をスタック可能にする(W-68)。
 *
 * <p>空スレッドの実体は ArsPaper({@code external-source: arspaper})で、新規個体は TF の
 * {@code roll_seed} を焼かない。旧個体・ガチャ経由で TF が品質刻印した個体は
 * {@code roll_seed} / {@code quality} / lore が個体ごとに違い、同じ見た目なのに重ならない。
 * ユニークな TF PDC を剥がし、表示名と lore をカタログ雛形へ戻す。Ars 側 PDC は残す。
 */
public final class EmptyThreadStackNormalizer {

    public static final String EMPTY_THREAD_ID = "thread_empty";

    /** Ars {@code ItemKeys.CUSTOM_ITEM_ID} — TF は ArsPaper にコンパイル依存しない。 */
    private static final NamespacedKey ARS_CUSTOM_ITEM_ID =
            new NamespacedKey("arspaper", "custom_item_id");

    private EmptyThreadStackNormalizer() {
    }

    public static boolean isEmptyThread(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Optional<String> catalogId = ItemData.of(meta).catalogId();
        if (catalogId.filter(EMPTY_THREAD_ID::equals).isPresent()) {
            return true;
        }
        String arsId = meta.getPersistentDataContainer().get(ARS_CUSTOM_ITEM_ID, PersistentDataType.STRING);
        return EMPTY_THREAD_ID.equals(arsId);
    }

    /**
     * 空スレッドなら TF 個体キーを剥がし、カタログの表示名・lore へ揃える。
     *
     * @return meta を書き戻したら true
     */
    public static boolean normalize(ItemStack stack, ItemCatalogConfig catalog) {
        Objects.requireNonNull(stack, "stack");
        if (!isEmptyThread(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        ItemData data = ItemData.of(meta);
        boolean changed = data.stripTrinityForgeIdentityForStacking();
        if (catalog != null) {
            Optional<ItemTemplate> template = catalog.template(EMPTY_THREAD_ID);
            if (template.isPresent()) {
                changed |= applyCatalogAppearance(meta, template.get());
            }
        }
        if (changed) {
            stack.setItemMeta(meta);
        }
        return changed;
    }

    private static boolean applyCatalogAppearance(ItemMeta meta, ItemTemplate template) {
        boolean changed = false;
        if (template.displayName() != null) {
            Component name = MiniText.render(template.displayName(), null);
            if (!Objects.equals(meta.displayName(), name)) {
                meta.displayName(name);
                changed = true;
            }
        }
        if (!template.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>(template.lore().size());
            for (String line : template.lore()) {
                lore.add(MiniText.render(line, null));
            }
            if (!Objects.equals(meta.lore(), lore)) {
                meta.lore(lore);
                changed = true;
            }
        }
        return changed;
    }
}
