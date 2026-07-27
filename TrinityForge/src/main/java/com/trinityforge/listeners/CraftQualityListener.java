package com.trinityforge.listeners;

import com.trinityforge.integration.ars.ArsProgressionBridge;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.config.domains.CraftQualityConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.CraftQualityPolicy;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.CraftRollMods;
import com.trinityforge.stats.EquipmentSlotResolver;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.MaterialTier;
import com.trinityforge.stats.PreviewRollSeeds;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Stamps crafted equipment with TrinityForge rollSeed + quality (ITEM_ECONOMY_SPEC 5.2c/5.2d).
 *
 * <p>{@link PrepareItemCraftEvent} (MONITOR) stamps a <em>stable preview</em> at the skill-derived
 * quality mode so the result slot shows TF lore/attributes instead of Valhalla vanilla stats.
 * {@link CraftItemEvent} (MONITOR) always re-stamps with a fresh split-normal roll after Valhalla's
 * {@code HIGHEST} handler finishes, then schedules a next-tick sweep so NUMBER_KEY / shift /
 * emptied-result paths cannot leave {@link PreviewRollSeeds#CRAFT} behind.
 *
 * <p>Bind: vanilla {@code minecraft:} recipes and crafts without a catalog id stay
 * {@link BindType#TRADEABLE} (no owner). Catalog-matched results keep template bind / SOULBOUND stamp.
 */
public final class CraftQualityListener implements Listener {

    private final Plugin plugin;
    private final ItemFactory itemFactory;
    private final CraftQualityService craftQualityService;
    private final CraftQualityConfig config;
    private final SkillExpConfig skillExp;
    private final ItemCatalogConfig itemCatalog;
    private final ItemStatsConfig itemStats;

    public CraftQualityListener(Plugin plugin, ItemFactory itemFactory,
                                CraftQualityService craftQualityService, CraftQualityConfig config,
                                SkillExpConfig skillExp, ItemCatalogConfig itemCatalog,
                                ItemStatsConfig itemStats) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.craftQualityService = Objects.requireNonNull(craftQualityService, "craftQualityService");
        this.config = Objects.requireNonNull(config, "config");
        this.skillExp = Objects.requireNonNull(skillExp, "skillExp");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getViewers().isEmpty() || !(event.getViewers().get(0) instanceof Player player)) {
            return;
        }
        CraftingInventory inventory = event.getInventory();
        ItemStack result = inventory.getResult();
        if (!isStampableEquipment(result) || !hasStatsProfile(result)) {
            return;
        }
        Set<String> candidates = candidatesFor(result);
        // タスクA(2026-07-26): プレビューは mode(最頻値) ではなく「実クラフトが絶対にこれ未満を出さない」
        // 保証下限を表示する(実クラフトはガウシアンで上下にブレるため mode 表示だと下振れで実際より
        // 良く見えてしまう)。実クラフト経路(onCraft/restampIfPreview)の rollQuality は変更しない。
        int minQuality = craftQualityService.minimumQuality(player, candidates, qualityModeOffsetFor(result));
        ItemStack stamped = result.clone();
        applyCatalogIdentityAndBind(stamped, event.getRecipe());
        CraftRollMods mods = craftQualityService.craftRollMods(player);
        itemFactory.stamp(stamped, PreviewRollSeeds.CRAFT, minQuality, mods);
        inventory.setResult(stamped);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // MONITOR では結果枠が既に空のことがある — CurrentItem / Cursor も候補にする。
        ItemStack source = firstStampable(
                event.getInventory().getResult(),
                event.getCurrentItem(),
                player.getItemOnCursor());
        if (source != null && hasStatsProfile(source)) {
            Set<String> candidates = candidatesFor(source);
            int rolled = craftQualityService.rollQuality(player, candidates, qualityModeOffsetFor(source));
            ItemStack stamped = source.clone();
            applyCatalogIdentityAndBind(stamped, event.getRecipe());
            CraftRollMods mods = craftQualityService.craftRollMods(player);
            itemFactory.stamp(stamped, ThreadLocalRandom.current().nextLong(), rolled, mods);
            stampSoulboundOwnerOnCraft(stamped, player);
            event.getInventory().setResult(stamped.clone());
            event.setCurrentItem(stamped.clone());
            if (!event.isShiftClick()) {
                player.setItemOnCursor(stamped.clone());
            }
            if (candidates.contains(ArsProgressionBridge.ARS_SMITHING)) {
                ArsProgressionBridge.grantSmithingExp(plugin, player, skillExp.arsSmithingExpPerCraft());
            }
            // PRG-13 (2026-07-25): SMITHING EXPの唯一のソース。categorySkill(weapon/armor/tool -> SMITHING,
            // CraftQualityConfig)で線引き済みなのでバニラの土ブロック等クラフトでは発生しない。
            // shift-clickの一括クラフトでもCraftItemEventは1回しか飛ばない(Bukkit標準の挙動)ため、
            // exp-per-craftは作成した個数に関わらず1回分だけ付与される(ars-smithingと同じ、意図的)。
            if (candidates.contains(SkillId.SMITHING)) {
                ArsProgressionBridge.grantSkillExp(plugin, player, SkillId.SMITHING, skillExp.smithingExpPerCraft());
            }
        }
        // 常に次tickでプレビュー漏れを回収 (NUMBER_KEY / shift / 結果枠空 など)。
        plugin.getServer().getScheduler().runTask(plugin, () -> restampPreviewCrafts(player));
    }

    /**
     * <b>複製バグ修正 (プレビューからのドラッグ/回収取り出し)</b>:
     * {@link #onPrepareCraft} はクラフト結果枠に品質プレビュー品(実体のある {@link ItemStack})を差し込む。
     * 正規の取り出しは {@link CraftItemEvent}(={@link InventoryClickEvent} のサブクラス、Bukkitが素材消費を伴う
     * クラフトとして発火)で行われる。一方、ドラッグ開始時の pickup や {@code COLLECT_TO_CURSOR}(ダブルクリック)
     * など<em>素材消費を伴わない</em>取り出しは「素の {@link InventoryClickEvent}」として飛ぶため、これを
     * クラフト結果枠上でブロックする。差し込んだプレビュー品が素材消費なしに手元へ出て、その後 sweep/restamp で
     * 本刻印へ昇格し、盤面は減らないので無限回収できていたのが原因。
     *
     * <p>{@code event instanceof CraftItemEvent} なら正規クラフト(消費あり)なので一切触らない。shift/数字キーの
     * 取り出しも {@link CraftItemEvent} として飛ぶため影響しない。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCraftResultClick(InventoryClickEvent event) {
        if (event instanceof CraftItemEvent) {
            return; // 正規クラフト取り出し(素材消費あり) — 許可
        }
        if (!isCraftingResultSlot(event.getView(), event.getRawSlot())) {
            return;
        }
        // クラフト結果枠に対する非クラフト操作(ドラッグ開始pickup / COLLECT_TO_CURSOR 等)は
        // 素材消費を伴わない複製経路になりうるため、取り出し系アクションをキャンセルする。
        switch (event.getAction()) {
            case NOTHING, CLONE_STACK -> {
                // 何も動かさない / クリエイティブ複製(権限側の別問題) — 無害なので許可
            }
            default -> event.setCancelled(true);
        }
    }

    /**
     * クラフト結果枠に触れるドラッグをキャンセルする(ドラッグ経由でのプレビュー品取り出しを封じる保険)。
     * 通常のクラフトはドラッグを使わないため実害はない。
     *
     * <p><b>2026-07-28 追加(実サーバで確認された複製)</b>: 「結果枠をつかんでインベントリへ
     * ドラッグ＆ドロップすると素材が減らずにアイテムだけ増え、再ログイン後も残る」という報告。
     * {@link InventoryDragEvent#getRawSlots()} は<b>置き先のスロットしか持たない</b> —
     * 引き出し元(結果枠)は入らない。そのため結果枠を起点にした quick-craft ドラッグは
     * 上のループを素通りし、しかも {@link CraftItemEvent} を経由しないので素材も消費されない。
     * 置き先だけを見るガードでは原理的に塞げないので、<b>カーソルの中身が今まさに結果枠に
     * 乗っている品と同一なら、結果枠から出てきたものとみなして落とす</b>。
     *
     * <p>誤爆する条件は「現在のクラフト結果とまったく同じアイテムを手に持ったまま、
     * クラフト画面でドラッグする」ときだけで、その場合もアイテムはカーソルに残るので失われない
     * (クリックで置ける)。複製は経済が壊れる不可逆な事故なので、この非対称は意図的に厳しい側へ倒す。
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCraftResultDrag(InventoryDragEvent event) {
        InventoryView view = event.getView();
        for (int rawSlot : event.getRawSlots()) {
            if (isCraftingResultSlot(view, rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
        if (!draggedOutOfCraftingResult(event, view)) {
            return;
        }
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player) {
            // キャンセルしただけだとクライアント側に「持てている」残像が残るため、明示的に同期し直す。
            plugin.getServer().getScheduler().runTask(plugin, player::updateInventory);
        }
    }

    /**
     * カーソルの中身が、そのクラフト画面の結果枠に今乗っている品と同一か(=結果枠から出てきた疑い)。
     * 直接ユニットテストするため package-private(このパッケージの「純粋ヘルパーは package-private」慣習に従う)。
     */
    static boolean draggedOutOfCraftingResult(InventoryDragEvent event, InventoryView view) {
        if (view == null || !(view.getTopInventory() instanceof CraftingInventory crafting)) {
            return false;
        }
        ItemStack cursor = event.getOldCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return false;
        }
        ItemStack result = crafting.getResult();
        return result != null && !result.getType().isAir() && result.isSimilar(cursor);
    }

    /** 生の作業台/インベントリ 2×2/3×3 クラフトの結果スロットか(かまど等の RESULT は対象外)。 */
    private static boolean isCraftingResultSlot(InventoryView view, int rawSlot) {
        if (view == null || rawSlot < 0 || rawSlot >= view.countSlots()) {
            return false;
        }
        if (view.getSlotType(rawSlot) != InventoryType.SlotType.RESULT) {
            return false;
        }
        InventoryType topType = view.getTopInventory().getType();
        return topType == InventoryType.WORKBENCH || topType == InventoryType.CRAFTING;
    }

    /**
     * インベントリ／カーソルに残ったクラフト・プレビュー刻印を本物の品質ロールで上書きする。
     */
    private void restampPreviewCrafts(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (restampIfPreview(stack, player)) {
                inv.setItem(i, stack);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (restampIfPreview(cursor, player)) {
            player.setItemOnCursor(cursor);
        }
    }

    private boolean restampIfPreview(ItemStack stack, Player player) {
        if (!isStampableEquipment(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        ItemData data = ItemData.of(meta);
        Optional<Long> seed = data.rollSeed();
        if (seed.isEmpty() || seed.get() != PreviewRollSeeds.CRAFT) {
            return false;
        }
        // ステータス未設定(item-stats プロファイル無し)のアイテムには品質を付けない。
        // 通常は onPrepareCraft 側で弾かれるが、万一プレビュー刻印が残っていたらここでも保険をかける。
        if (!hasStatsProfile(stack)) {
            return false;
        }
        Set<String> candidates = candidatesFor(stack);
        int rolled = craftQualityService.rollQuality(player, candidates, qualityModeOffsetFor(stack));
        // Preview restamp has no recipe handle — unbound when no catalog id (vanilla / uncataloged).
        applyCatalogIdentityAndBind(stack, null);
        CraftRollMods mods = craftQualityService.craftRollMods(player);
        itemFactory.stamp(stack, ThreadLocalRandom.current().nextLong(), rolled, mods);
        stampSoulboundOwnerOnCraft(stack, player);
        return true;
    }

    /**
     * Restores catalog identity when material+CMD matches a template, then forces TRADEABLE
     * (clears owner) for vanilla {@code minecraft:} recipes and for any stack still without a
     * catalog id.
     */
    private void applyCatalogIdentityAndBind(ItemStack stamped, Recipe recipe) {
        CatalogIdentity.ensure(stamped, itemCatalog);
        if (shouldLeaveUnbound(recipe, stamped)) {
            forceTradeableUnbound(stamped);
        }
    }

    private static boolean shouldLeaveUnbound(Recipe recipe, ItemStack stack) {
        if (isVanillaMinecraftRecipe(recipe)) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta == null || ItemData.of(meta).catalogId().isEmpty();
    }

    private static boolean isVanillaMinecraftRecipe(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) {
            return false;
        }
        NamespacedKey key = keyed.getKey();
        return key != null && "minecraft".equals(key.getNamespace());
    }

    private static void forceTradeableUnbound(ItemStack stamped) {
        ItemMeta meta = stamped.getItemMeta();
        if (meta == null) {
            return;
        }
        ItemData data = ItemData.of(meta);
        data.setBindType(BindType.TRADEABLE);
        data.clearOwner();
        stamped.setItemMeta(meta);
    }

    private static ItemStack firstStampable(ItemStack... stacks) {
        for (ItemStack stack : stacks) {
            if (isStampableEquipment(stack)) {
                return stack;
            }
        }
        return null;
    }

    private static boolean isStampableEquipment(ItemStack result) {
        return result != null && !result.getType().isAir()
                && MaterialTier.of(result.getType()).isEquipment();
    }

    private Set<String> candidatesFor(ItemStack result) {
        Set<String> categories = EquipmentSlotResolver.statCategories(result.getType());
        return CraftQualityPolicy.candidateSkills(categories, config.categorySkill());
    }

    /**
     * このクラフト結果に {@code stats/item-stats.yml} のステータス定義があるか。
     * ステータスを持たないただの素材/装備(バニラ相当)には品質を付与しない
     * ({@link PickupQualityListener#stampIfEligible} と同じ規準)。
     */
    private boolean hasStatsProfile(ItemStack result) {
        if (result == null || result.getType().isAir()) {
            return false;
        }
        Integer cmd = result.hasItemMeta()
                ? DerivedItemStats.customModelDataOf(result.getItemMeta()) : null;
        return itemStats.profileFor(result.getType(), cmd).isPresent();
    }

    /**
     * 品質基準値 (item-stats {@code quality-mode-offset}): クラフト結果アイテムの MATERIAL#cmd
     * (無ければ MATERIAL) に定義されたオフセット。未定義は 0 = クラフトユーザの品質ポイント通り。
     */
    private int qualityModeOffsetFor(ItemStack result) {
        Integer cmd = result.hasItemMeta()
                ? DerivedItemStats.customModelDataOf(result.getItemMeta()) : null;
        return itemStats.qualityModeOffsetFor(result.getType(), cmd);
    }

    private void stampSoulboundOwnerOnCraft(ItemStack stamped, Player crafter) {
        ItemMeta meta = stamped.getItemMeta();
        if (meta == null) {
            return;
        }
        ItemData data = ItemData.of(meta);
        if (data.owner().isPresent()) {
            return;
        }
        Optional<BindType> bindType = data.bindType();
        if (bindType.isEmpty() || !bindType.get().autoStampsOwner()) {
            return;
        }
        Optional<Long> rollSeed = data.rollSeed();
        if (rollSeed.isEmpty()) {
            return;
        }
        data.setOwner(crafter.getUniqueId());
        stamped.setItemMeta(meta);
        itemFactory.stamp(stamped, rollSeed.get(), data.quality());
    }
}
