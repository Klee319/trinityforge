package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.WoodRepairMaterial;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.Objects;
import java.util.Optional;

/**
 * Compressed-wood durability repair (woodcutting wood-repair-unlock).
 *
 * <p><b>2026-08-01</b>: 素材の識別は {@link CrossPluginItemResolver#idOf} を使う。以前は
 * {@code CatalogIdentity#catalogIdOf}(= TF の {@code trinityforge:catalog_id} PDC だけ)を読んでいたが、
 * {@code wood-repair.materials} が指す圧縮木材は <b>ArsPaper の materials.yml 側の実体</b>で、
 * {@code BaseCustomItem#createItemStack} が刻むのは {@code arspaper:custom_item_id} <b>だけ</b>。
 * TF 側の catalog PDC は付かないので、素材をどれだけ正しく設定しても
 * {@code catalogId.isEmpty()} で必ず早期 return し、<b>木材修繕は無言で一度も発動しなかった</b>。
 * 出荷 yml の ID が実在しない {@code compressed_wood_1x} だった件(同日修正)と合わせて二重に死んでおり、
 * ID だけ直しても直らない。両方読む唯一の合流点が {@link CrossPluginItemResolver#idOf}。
 */
public final class WoodRepairListener implements Listener {

    private static final String UNLOCK = "wood-repair-unlock";

    private final DedicatedEffectsConfig dedicatedEffects;
    private final CraftingFeaturesConfig features;

    public WoodRepairListener(DedicatedEffectsConfig dedicatedEffects, CraftingFeaturesConfig features) {
        this.dedicatedEffects = Objects.requireNonNull(dedicatedEffects, "dedicatedEffects");
        this.features = Objects.requireNonNull(features, "features");
    }

    /**
     * この金床の中身が「解放済みプレイヤーによる木材修繕」かどうか。
     *
     * <p><b>{@link CatalogVanillaOperationGuardListener#onPrepareAnvil}(HIGHEST)からも呼ばれる。</b>
     * あちらはカタログ品が素材として食われる操作を {@code setResult(null)} で潰すが、
     * 木材修繕はこのリスナー(HIGH)が結果を入れた<b>後</b>に走るため、除外しないと
     * <b>カタログ品(＝TF の武器・防具ほぼ全部)の木材修繕が金床で必ず無効化される</b>
     * ——「元からある(バニラの)防具しか木材で修繕できない」という 2026-08-05 の実サーバ報告の真因。
     *
     * <p>効果の保有まで見るのは、未解放のときにガードを緩めると
     * 「圧縮木材がバニラの修理素材として普通に食われる」穴が空くため(圧縮木材の素地は
     * バニラの修理素材そのものなので、バニラ側の結果が成立してしまう)。
     */
    static boolean isUnlockedWoodRepair(Player player, ItemStack target, ItemStack material,
                                        DedicatedEffectsConfig dedicatedEffects,
                                        CraftingFeaturesConfig features) {
        if (player == null || target == null || material == null) {
            return false;
        }
        if (!dedicatedEffects.isActive(player, UNLOCK)) {
            return false;
        }
        if (!(target.getItemMeta() instanceof Damageable damageable) || damageable.getDamage() <= 0) {
            return false;
        }
        Optional<String> materialId = CrossPluginItemResolver.idOf(material);
        return materialId.isPresent() && features.woodRepairMaterial(materialId.get()) != null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        ItemStack left = event.getInventory().getFirstItem();
        ItemStack material = event.getInventory().getSecondItem();
        if (!isUnlockedWoodRepair(player, left, material, dedicatedEffects, features)) {
            return;
        }
        Damageable damageable = (Damageable) left.getItemMeta();
        WoodRepairMaterial mat = features.woodRepairMaterial(
                CrossPluginItemResolver.idOf(material).orElseThrow());
        int repair = mat.durability();
        ItemStack result = left.clone();
        ItemMetaRepair.applyRepair(result, Math.min(damageable.getDamage(), repair));
        event.setResult(result);
        event.getInventory().setRepairCost(1);
        // No action-bar here: PrepareAnvil fires continuously while items sit in the anvil.
    }

    /**
     * Anvil-free quick-repair: cursor holds a {@code quick-repair: true} material, click target is a
     * damaged {@link Damageable} equipment piece in the player's own inventory view (survival
     * inventory or its built-in 2x2 crafting grid — {@link InventoryType#CRAFTING}). Consumes 1
     * material per click (LEFT or RIGHT), repairs the target by {@code min(damage, mat.durability())},
     * and cancels the event so vanilla's normal item-move does not also fire. No-ops (lets the vanilla
     * click proceed) for any other GUI, non-quick-repair material, or an undamaged/non-repairable target.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!dedicatedEffects.isActive(player, UNLOCK)) {
            return;
        }
        // Only the player's own inventory screen (bottom PlayerInventory + its built-in 2x2 crafting
        // grid) is InventoryType.CRAFTING; chests/anvils/workbench/etc use other types and must not be
        // touched here.
        if (event.getView().getType() != InventoryType.CRAFTING) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir() || !cursor.hasItemMeta()) {
            return;
        }
        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()
                || !isUnlockedWoodRepair(player, target, cursor, dedicatedEffects, features)) {
            return;
        }
        Damageable targetMeta = (Damageable) target.getItemMeta();
        WoodRepairMaterial mat = features.woodRepairMaterial(
                CrossPluginItemResolver.idOf(cursor).orElseThrow());
        if (!mat.quickRepair()) {
            return;
        }

        int repairAmount = Math.min(targetMeta.getDamage(), mat.durability());
        ItemStack repaired = target.clone();
        ItemMetaRepair.applyRepair(repaired, repairAmount);
        event.setCurrentItem(repaired);

        ItemStack newCursor = cursor.getAmount() > 1 ? cursor.clone() : null;
        if (newCursor != null) {
            newCursor.setAmount(newCursor.getAmount() - 1);
        }
        player.setItemOnCursor(newCursor);
        event.setCancelled(true);
        player.sendActionBar(Component.text("装備を修繕しました。", NamedTextColor.GREEN));
    }

    private static final class ItemMetaRepair {
        private static void applyRepair(ItemStack stack, int amount) {
            if (!(stack.getItemMeta() instanceof Damageable d)) {
                return;
            }
            d.setDamage(Math.max(0, d.getDamage() - amount));
            stack.setItemMeta(d);
        }
    }
}
