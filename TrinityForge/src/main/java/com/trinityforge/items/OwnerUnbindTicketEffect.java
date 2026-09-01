package com.trinityforge.items;

import com.trinityforge.listeners.PickupQualityListener;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 魂縛解きの符({@code owner_unbind_ticket})の効果本体。対象の所有者を消し、
 * bind を {@link BindType#TRADEABLE} へ変える。{@code /tf bind clear} は owner UUID だけ消すので、
 * {@code SOULBOUND} のままだと次の拾得で {@code PickupQualityListener} が所有者を焼き直す。
 *
 * <p>スレッド lore は {@code ItemFactory#stamp} で上書きされる(W-53)ため、Ars のスレッドマーカーが
 * ある個体は PDC だけ書き、stamp しない。
 */
public final class OwnerUnbindTicketEffect implements EquipmentTicketEffect {

    public static final String CATALOG_ID = "owner_unbind_ticket";

    private static final NamespacedKey ARS_THREAD_ITEM_TYPE =
            new NamespacedKey("arspaper", "thread_item_type");

    /**
     * Ars が持つ魂縛所有者の<b>控え</b>。真実は TF の {@link ItemData} 側だが、Ars の
     * スレッド専用 lore と枠からの返却はこちらを読んで「魂縛: 名前」を書き戻す。
     * ここを消さずに TF 側だけ解くと、表示は残り、枠へ入れて出すと再び縛られる。
     */
    private static final NamespacedKey ARS_THREAD_SOULBOUND_OWNER =
            new NamespacedKey("arspaper", "thread_soulbound_owner");

    private final ItemFactory itemFactory;

    public OwnerUnbindTicketEffect(ItemFactory itemFactory) {
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
    }

    @Override
    public String catalogId() {
        return CATALOG_ID;
    }

    @Override
    public String title() {
        return "魂縛解き";
    }

    @Override
    public boolean eligible(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        if (EquipmentTicketEffect.isConsumableTicketItem(stack)) {
            return false;
        }
        ItemData data = ItemData.of(stack.getItemMeta());
        if (data.owner().isPresent()) {
            return true;
        }
        return data.bindType().filter(BindType::enforcesOwnership).isPresent();
    }

    @Override
    public List<Component> previewLore(ItemStack stack) {
        ItemData data = ItemData.of(stack.getItemMeta());
        List<Component> lines = new ArrayList<>();
        String bindNow = data.bindType().map(BindType::storageValue).orElse("未設定");
        lines.add(Component.text("現在: 所有者"
                + (data.owner().isPresent() ? "あり" : "なし")
                + " / " + bindNow, NamedTextColor.GRAY));
        lines.add(Component.text("変化後: 所有者なし / TRADEABLE", NamedTextColor.GOLD));
        lines.add(Component.text("拾っても再び所有者は焼き付きません", NamedTextColor.AQUA));
        return lines;
    }

    @Override
    public Optional<ItemStack> apply(ItemStack stack) {
        if (!eligible(stack)) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Optional.empty();
        }
        ItemData data = ItemData.of(meta);
        data.clearOwner();
        data.setBindType(BindType.TRADEABLE);
        stack.setItemMeta(meta);
        ItemMeta written = stack.getItemMeta();
        if (written != null && isArsThread(written)) {
            // 汎用 stamp はスレッド専用 lore を壊すので通さない(W-53)。代わりに Ars 側の
            // 控えを消し、Ars 自身の lore 組み直しを呼ぶ。これをやらないと「解いたのに
            // 魂縛の行が残る」= 使っても何も起きていないように見える。
            clearArsSoulboundOwner(stack);
            PickupQualityListener.defaultArsThreadLoreRefresh(stack);
            return Optional.of(stack);
        }
        Optional<Long> seed = ItemData.of(written != null ? written : meta).rollSeed();
        if (seed.isPresent()) {
            itemFactory.stamp(stack, seed.get(), ItemData.of(stack.getItemMeta()).quality());
        }
        return Optional.of(stack);
    }

    @Override
    public String appliedMessage() {
        return "所有者を解き、取引可能な品にしました。";
    }

    /** Ars 側の魂縛控えを消す。TF だけ解いても Ars が書き戻すので、2本まとめて解く。 */
    private static void clearArsSoulboundOwner(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null
                || !meta.getPersistentDataContainer().has(
                        ARS_THREAD_SOULBOUND_OWNER, PersistentDataType.STRING)) {
            return;
        }
        meta.getPersistentDataContainer().remove(ARS_THREAD_SOULBOUND_OWNER);
        stack.setItemMeta(meta);
    }

    private static boolean isArsThread(ItemMeta meta) {
        return meta.getPersistentDataContainer().has(ARS_THREAD_ITEM_TYPE, PersistentDataType.STRING);
    }
}
