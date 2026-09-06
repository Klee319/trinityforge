package com.trinityforge.listeners;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.WoodRepairMaterial;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.view.AnvilView;

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
    private static final int ANVIL_RESULT_SLOT = 2;
    private static final int REPAIR_LEVEL_COST = 1;

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
        int units = unitsToConsume(damageable.getDamage(), mat.durability(), material.getAmount());
        if (units <= 0) {
            return;
        }
        int repair = Math.min(damageable.getDamage(), units * mat.durability());
        ItemStack result = left.clone();
        ItemMetaRepair.applyRepair(result, repair);
        event.setResult(result);
        event.getInventory().setRepairCost(REPAIR_LEVEL_COST);
        if (event.getView() instanceof AnvilView anvilView) {
            anvilView.setRepairCost(REPAIR_LEVEL_COST);
            anvilView.setRepairItemCountCost(units);
            anvilView.setMaximumRepairCost(EnchantCostReductionListener.UNCAPPED_ANVIL_REPAIR_COST);
        }
        // No action-bar here: PrepareAnvil fires continuously while items sit in the anvil.
    }

    /**
     * 金床結果の取り出し。消費数は {@link #unitsToConsume} で決めた個数。
     * バニラに任せると {@code repairItemCountCost} が 0 のまま右枠を丸ごと消す
     * （パーティクルシードと同じ穴。2026-08-29 複数個修繕）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnvilResultTake(InventoryClickEvent event) {
        if (event.getRawSlot() != ANVIL_RESULT_SLOT) {
            return;
        }
        if (!(event.getInventory() instanceof AnvilInventory inventory)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack left = inventory.getFirstItem();
        ItemStack material = inventory.getSecondItem();
        if (!isUnlockedWoodRepair(player, left, material, dedicatedEffects, features)) {
            return;
        }
        event.setCancelled(true);
        ClickType click = event.getClick();
        boolean shift = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        if (!shift && click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        Damageable damageable = (Damageable) left.getItemMeta();
        WoodRepairMaterial mat = features.woodRepairMaterial(
                CrossPluginItemResolver.idOf(material).orElseThrow());
        int units = unitsToConsume(damageable.getDamage(), mat.durability(), material.getAmount());
        if (units <= 0) {
            return;
        }
        boolean creative = player.getGameMode() == GameMode.CREATIVE;
        if (!creative && player.getLevel() < REPAIR_LEVEL_COST) {
            player.sendActionBar(Component.text(
                    "レベルが足りません(必要 " + REPAIR_LEVEL_COST + ")", NamedTextColor.RED));
            return;
        }
        ItemStack result = left.clone();
        ItemMetaRepair.applyRepair(result, Math.min(damageable.getDamage(), units * mat.durability()));
        if (shift) {
            if (!player.getInventory().addItem(result).isEmpty()) {
                player.sendActionBar(Component.text("インベントリに空きがありません", NamedTextColor.RED));
                return;
            }
        } else {
            ItemStack cursor = player.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                player.sendActionBar(Component.text(
                        "カーソルを空にしてから取り出してください", NamedTextColor.RED));
                return;
            }
            player.setItemOnCursor(result);
        }
        inventory.setFirstItem(null);
        inventory.setSecondItem(consumeAmount(material, units));
        if (!creative) {
            player.setLevel(player.getLevel() - REPAIR_LEVEL_COST);
        }
        player.updateInventory();
        player.sendActionBar(Component.text("装備を修繕しました。", NamedTextColor.GREEN));
    }

    /**
     * 損傷 {@code damage} を {@code durabilityPerUnit} ずつ直すのに必要な個数を、手元のスタック数で
     * 頭打ちする。0 以下の入力は 0。
     */
    static int unitsToConsume(int damage, int durabilityPerUnit, int stackAmount) {
        if (damage <= 0 || durabilityPerUnit <= 0 || stackAmount <= 0) {
            return 0;
        }
        int needed = (damage + durabilityPerUnit - 1) / durabilityPerUnit;
        return Math.min(stackAmount, needed);
    }

    static ItemStack consumeAmount(ItemStack stack, int amount) {
        if (stack == null || stack.getAmount() <= amount) {
            return null;
        }
        ItemStack left = stack.clone();
        left.setAmount(stack.getAmount() - amount);
        return left;
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

        // 回復量の上限は「今の損傷」だが、その損傷自体が max_damage を超えていることがある
        // (上の applyRepair の javadoc 参照)。素の getDamage() を使うと、超過分まで
        // 「回復できる量」として数えてしまい、素材1個で無駄に消費される。
        int repairAmount = Math.min(
                ItemMetaRepair.clampDamage(target, targetMeta, targetMeta.getDamage()),
                mat.durability());
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

    static final class ItemMetaRepair {

        /**
         * {@code stack} の損傷を {@code amount} だけ回復する。
         *
         * <p><b>上限側もクランプする</b>(2026-08-21、実サーバログの
         * {@code IllegalArgumentException: Damage cannot exceed max damage} —— 修繕クリックのたびに
         * {@code InventoryClickEvent} が TF の中で落ちていた)。
         * 引き算しかしていないのに上限を超えるのは、<b>元の {@code damage} が既に
         * その装備の {@code max_damage} を超えている</b>ため。1.21 では最大耐久が
         * {@code max_damage} コンポーネントで決まり、TF は {@code item-stats.yml} の
         * {@code durability} から個体ごとに書き込む ── その値を下げる方向へ調整すると、
         * <b>既に配られている個体は「damage &gt; max_damage」のまま残る</b>。
         * Bukkit の {@code setDamage} はその値を弾くので、下限だけ見る実装では
         * <b>回復量がいくらであっても必ず落ちる</b>。
         *
         * <p>落ちた場所が {@code event.setCancelled(true)} より手前なので、症状は例外ログと
         * 「修繕したのに何も起きず、素材だけ普通に持ち替わる」になる。
         */
        static void applyRepair(ItemStack stack, int amount) {
            if (!(stack.getItemMeta() instanceof Damageable d)) {
                return;
            }
            d.setDamage(clampDamage(stack, d, d.getDamage() - amount));
            stack.setItemMeta(d);
        }

        /** {@code damage} を {@code [0, その装備の最大耐久]} へ収める。 */
        static int clampDamage(ItemStack stack, Damageable meta, int damage) {
            return Math.max(0, Math.min(maxDamageOf(stack, meta), damage));
        }

        /**
         * その装備の最大耐久。{@code max_damage} コンポーネントを持っていればそちら
         * (TF は個体ごとに書き込む)、無ければ素材の既定値。
         *
         * <p>どちらも 0 以下(＝耐久の概念が無いアイテム)なら {@link Integer#MAX_VALUE} を返して
         * クランプを効かせない ── そういうアイテムは {@code setDamage} 自体が上限を持たない。
         */
        private static int maxDamageOf(ItemStack stack, Damageable meta) {
            if (meta.hasMaxDamage()) {
                int custom = meta.getMaxDamage();
                if (custom > 0) {
                    return custom;
                }
            }
            int vanilla = stack.getType().getMaxDurability();
            return vanilla > 0 ? vanilla : Integer.MAX_VALUE;
        }
    }
}
