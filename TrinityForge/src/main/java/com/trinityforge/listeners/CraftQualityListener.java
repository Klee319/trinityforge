package com.trinityforge.listeners;

import com.trinityforge.integration.ars.ArsProgressionBridge;
import com.trinityforge.progression.UseRequirementResolver;
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
import com.trinityforge.stats.ArsItemGiveBridge;
import com.trinityforge.stats.CrossPluginItemResolver;
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
import org.bukkit.inventory.meta.Damageable;
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
        if (event.isRepair()
                || isVanillaSameItemRepair(inventory.getMatrix(), result)
                || !isStampableCraftResult(result)
                || (!hasStatsProfile(result) && !isArsQualityStamped(result))) {
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

    /**
     * <b>複製バグ修正 (2026-07-28 その2 — 実サーバで確認された本命)</b>:
     * ここで {@code player.setItemOnCursor(...)} を呼んではいけない。
     *
     * <p>CraftBukkit の {@code handleContainerClick} は<b>イベントを発火してから</b>
     * {@code AbstractContainerMenu.clicked(...)}(=バニラの実処理)を走らせる。つまりこのハンドラ
     * (MONITOR)でカーソルを書き換えると、バニラは「カーソルが空 → 結果枠を取る」経路ではなく
     * 「カーソルに既に同じ品がある → マージする」経路に入る:
     * <pre>
     *   } else if (slot.mayPlace(carried)) {       // 結果枠は常に false
     *   } else if (isSameItemSameComponents(slotItem, carried)) {
     *       slot.tryRemove(carried.getCount(), carried.getMaxStackSize() - carried.getCount(), player)
     *           .ifPresent(taken -&gt; { carried.grow(...); slot.onTake(player, taken); });
     *   }
     * </pre>
     * 装備・道具は最大スタック 1 なので上限は {@code 1 - 1 = 0}、{@code tryRemove} は空 Optional を
     * 返し <b>{@code ResultSlot#onTake} が一度も呼ばれない</b> = <b>素材が消費されない</b>。
     * それでいてプレイヤーの手にはこちらが載せた完成品が残るため、盤面はそのまま・結果枠もそのままで
     * いくらでもアイテムが増える(=報告された「リザルトから無限に回収できる」複製)。
     * クライアントは「素材が減って結果を取った」と予測しているので、直後のサーバ同期で盤面が
     * 巻き戻り、これが「取ろうとするとちらつく」の正体でもある。
     *
     * <p>結果枠({@link CraftItemEvent#setCurrentItem})だけを差し替えれば、バニラが正規の
     * 「カーソルが空 → {@code tryRemove(count, MAX_VALUE)} → {@code onTake}(素材消費) → カーソルへ」
     * を実行し、刻印済みの完成品がそのまま手に渡る。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Only the crafting inventory result proves that this event produced an item. CurrentItem
        // and cursor may contain unrelated equipment when another listener has already cleared the
        // result, and must never be treated as the crafted output.
        ItemStack source = event.getInventory().getResult();
        if (producesCraftedItem(event.getAction(), event.getCursor())
                && isStampableCraftResult(source)
                && !isVanillaSameItemRepair(event.getInventory().getMatrix(), source)
                && (hasStatsProfile(source) || isArsQualityStamped(source))) {
            Set<String> candidates = candidatesFor(source);
            int rolled = craftQualityService.rollQuality(player, candidates, qualityModeOffsetFor(source));
            ItemStack stamped = source.clone();
            applyCatalogIdentityAndBind(stamped, event.getRecipe());
            CraftRollMods mods = craftQualityService.craftRollMods(player);
            itemFactory.stamp(stamped, ThreadLocalRandom.current().nextLong(), rolled, mods);
            stampSoulboundOwnerOnCraft(stamped, player);
            // 結果枠だけを差し替える。カーソルには絶対に触らないこと(理由は下記)。
            event.getInventory().setResult(stamped.clone());
            event.setCurrentItem(stamped.clone());
            int craftOperations = event.isShiftClick()
                    ? craftOperationCount(
                            true,
                            event.getInventory().getMatrix(),
                            player.getInventory().getStorageContents(),
                            stamped)
                    : 1;
            if (candidates.contains(ArsProgressionBridge.ARS_SMITHING)) {
                ArsProgressionBridge.grantSmithingExpForResult(
                        plugin,
                        player,
                        stamped,
                        skillExp.arsSmithingExpPerCraft() * craftOperations);
            }
            // PRG-13 (2026-07-25): SMITHING EXPの唯一のソース。categorySkill(weapon/armor/tool -> SMITHING,
            // CraftQualityConfig)で線引き済みなのでバニラの土ブロック等クラフトでは発生しない。
            // shift-clickはイベントが1回でも複数回クラフトされるため、素材数と収納容量の安全側の
            // 下限で実操作回数を求める。通常クリックは常に1回。
            if (candidates.contains(SkillId.SMITHING)) {
                // 使用可能レベル連動EXP (2026-07-28): 「作成したツール/装備」= 品質スタンプ済みの stamped
                // 自身の使用可能レベルを見る。ARS_SMITHING(上のgrantSmithingExp)には掛けない —
                // 要件は「鍛冶」であってArs鍛冶ではないため、この線引きは意図的。
                int useLevel = UseRequirementResolver.resolve(stamped, itemStats)
                        .map(UseRequirementResolver.Resolved::level)
                        .orElse(0);
                // 素材ベースEXP (2026-07-30): 完成品に使用可能レベルが無いものは EXP を一切出さない。
                // 解体で素材へ戻せる装備を作り直し続ける無限EXP経路(unzipサイクル)を塞ぐため、
                // 「素材の合計」方式に切り替えるのと同時に導入した必須のゲート。
                if (useLevel > 0) {
                    double multiplier = skillExp.useLevelExpMultiplier(SkillId.SMITHING, useLevel);
                    double base = smithingBaseExp(event.getInventory().getMatrix());
                    ArsProgressionBridge.grantSkillExp(plugin, player, SkillId.SMITHING,
                            base * multiplier * craftOperations);
                }
            }
        }
        // 常に次tickでプレビュー漏れを回収 (NUMBER_KEY / shift / 結果枠空 など)。
        plugin.getServer().getScheduler().runTask(plugin, () -> restampPreviewCrafts(player));
    }

    /**
     * 1回のクラフト操作で得る鍛冶EXPの素点。クラフト盤面に置かれた素材<b>1個ずつ</b>に
     * {@code smithing.exp-per-material} の値を掛けて合計する(2026-07-30「鍛冶のレベルが上がりにくい」対応)。
     *
     * <p>表が空のサーバ(yml未更新)では従来の定額 {@code smithing.exp-per-craft} に落とす。
     * 表に無い素材は 0 として扱う — 「未設定の素材は無報酬」が設計意図なので、
     * ここで暗黙の既定値を出してはいけない。
     */
    private double smithingBaseExp(ItemStack[] matrix) {
        var perMaterial = skillExp.smithingExpPerMaterial();
        if (perMaterial.isEmpty() || matrix == null) {
            return skillExp.smithingExpPerCraft();
        }
        double total = 0.0;
        for (ItemStack ingredient : matrix) {
            if (ingredient == null || ingredient.getType().isAir()) continue;
            Double value = perMaterial.get(materialToken(ingredient));
            if (value != null) {
                total += value * ingredient.getAmount();
            }
        }
        return total;
    }

    /**
     * 素材トークン: TFカタログ品/ArsPaperカスタム品は {@code custom:<id>}、それ以外は Material 名。
     *
     * <p><b>2026-08-01 修正</b>: 以前はTFカタログのPDC({@code ItemData#catalogId})しか読んでおらず、
     * 出荷 {@code smithing.exp-per-material} の {@code custom:} 行はほぼ全てが ArsPaper の
     * materials.yml 由来のID(source_gem / magebloom_fiber / hard_metal …)なので、
     * <b>盤面に置いても1行も引けず0EXPだった</b>。実装は
     * {@link ArsProgressionBridge#materialToken}(TF/Ars 両方のPDCを読む)へ一本化し、
     * 儀式経路と作業台経路で素材の数え方がずれないようにしている。
     */
    private static String materialToken(ItemStack stack) {
        return ArsProgressionBridge.materialToken(stack);
    }

    /**
     * <b>鍛冶EXP誤付与の修正 (2026-07-30)</b>: このクリックが本当に<em>素材を消費してアイテムを
     * 作り出す</em>かどうか。{@link CraftItemEvent} は「クラフト結果枠がクリックされた」だけで発火し、
     * <b>バニラが実際にクラフトを行うかどうかは一切見ていない</b>ため、これを見ずにEXPを付与すると
     * 「関係ないアイテムをカーソルに持って結果枠を左クリック」するだけでクリック回数ぶん鍛冶EXPが
     * 入っていた(報告された不具合。品質の振り直しも同時に起きていた)。
     *
     * <p>判定は Paper の {@code ServerGamePacketListenerImpl#handleContainerClick} が
     * {@link InventoryAction} を決める規則と、バニラ {@code AbstractContainerMenu#clicked} が実際に
     * 取り出す条件を突き合わせたもの(1.21.11 のソースで確認):
     * <ul>
     *   <li>{@code PICKUP_*} — 結果枠は {@code mayPlace} が常に false なので、カーソルが空か
     *       「同一アイテムでスタック上限に収まる」ときしか発生しない = 必ず取り出しが起きる。
     *       逆に<b>別アイテムを持っている/スタックが満杯なら action は {@code NOTHING} になる</b>
     *       (これが今回の穴)。</li>
     *   <li>{@code MOVE_TO_OTHER_INVENTORY}(shift) — 枠に品があれば必ず立つ。移動先が満杯で
     *       実際には作られない場合は {@link #craftOperationCount} が 0 を返すのでEXPも0になる。</li>
     *   <li>{@code HOTBAR_SWAP}(数字キー/F) — 結果枠では「対象のホットバー枠が空」のときだけ立つ
     *       (埋まっていれば {@code mayPlace} が false なので {@code NOTHING})ので、必ず取り出しが起きる。</li>
     *   <li>{@code DROP_*_SLOT}(Q) — action 自体はカーソルの中身に関係なく立つが、バニラ側は
     *       {@code ClickType.THROW && carried.isEmpty()} でしか処理しない。カーソルが空のときだけ許可する。</li>
     *   <li>それ以外({@code NOTHING} / {@code CLONE_STACK} / {@code COLLECT_TO_CURSOR} /
     *       {@code UNKNOWN} / バンドル系 …)はクラフトを伴わないので false。</li>
     * </ul>
     * 純関数なのでユニットテストから直接叩ける。
     */
    static boolean producesCraftedItem(InventoryAction action, ItemStack cursor) {
        if (action == null) {
            return false;
        }
        return switch (action) {
            case PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
                 MOVE_TO_OTHER_INVENTORY, HOTBAR_SWAP -> true;
            case DROP_ALL_SLOT, DROP_ONE_SLOT -> cursor == null || cursor.getType().isAir();
            default -> false;
        };
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
     * クラフト結果枠を置き先に含むドラッグをキャンセルする(不変条件の明示。通常のクラフトはドラッグを
     * 使わないため実害はない)。
     *
     * <p><b>2026-07-28: 「カーソルの中身 == 結果枠の中身なら落とす」ヒューリスティックを撤去した。</b>
     * 当初これを「結果枠を起点にしたドラッグ複製」の対策として入れたが、真因は
     * {@link #onCraft} のカーソル書き換え(そちらの javadoc 参照)であり、ドラッグ経路では複製は
     * 原理的に起こらない:
     * <ul>
     *   <li>バニラの quick-craft({@code ClickType.QUICK_CRAFT})は<b>カーソルの中身をスロットへ
     *       配るだけ</b>で、スロットから取り出す処理を一切持たない。</li>
     *   <li>配布先に採用される条件は {@code slot.mayPlace(carried)} であり、クラフト結果枠
     *       ({@code ResultSlot})はこれが常に false。つまり結果枠はドラッグの置き先にも起点にもならない。</li>
     * </ul>
     * 一方でこのヒューリスティックは「作ったばかりの品を手に持ったまま盤面でドラッグする」という
     * ごく普通の操作を必ず巻き込み(直後は カーソル == 結果枠 が成立する)、キャンセル＋
     * {@code updateInventory()} による<b>画面のちらつきを自分で生んでいた</b>。
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
        if (!isStampableCraftResult(stack)) {
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
        // TFステータス未設定でも、Ars側が品質対象と宣言する魔導書/触媒は同じロールを確定する。
        if (!hasStatsProfile(stack) && !isArsQualityStamped(stack)) {
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

    private boolean isStampableCraftResult(ItemStack result) {
        if (result == null || result.getType().isAir()) {
            return false;
        }
        return MaterialTier.of(result.getType()).isEquipment()
                || isArsQualityStamped(result)
                || hasQualityBearingStatsProfile(result);
    }

    /**
     * {@code stats/item-stats.yml} が「品質で変動する層」({@code per-quality} / {@code random}、
     * 乗算レイヤ内のものも含む)を定義しているアイテムか。
     *
     * <p><b>2026-08-04 実サーバ報告「広辞苑の品質がクラフト時に上がらない」の真因。</b>
     * {@link #isStampableCraftResult} の門は<b>バニラ Material が装備扱いか</b>
     * ({@link MaterialTier#isEquipment()})だけを見ていた。TF のアイテムは
     * {@code MATERIAL#CMD} 単位で {@code item-stats.yml} にステータスを持つので、
     * <b>ベース素材がバニラ装備でないTF品は per-quality を定義していても品質ロールに一度も到達せず、
     * 常に品質0でクラフトされていた</b>。広辞苑({@code BOOK#100004})だけの話ではなく、
     * 杖・触媒({@code BLAZE_ROD#4000xx})など「素材が装備でない全てのステータス付きTF品」が
     * 同じ穴に落ちていた(ユーザー指摘「バグの事象の一つに過ぎない」の通り)。
     *
     * <p>バニラの土ブロック等まで巻き込まないのは、{@code item-stats.yml} に
     * {@code MATERIAL#CMD} のプロファイルが無ければここが {@code false} を返すため
     * (呼び出し側 {@link #onCraft} も {@code hasStatsProfile || isArsQualityStamped} を AND している)。
     *
     * <p><b>付随して残る制限(意図的)</b>: {@link #candidatesFor} が引く生産スキルは
     * {@code EquipmentSlotResolver.statCategories(Material)} 由来なので、BOOK/BLAZE_ROD では
     * 空集合になり<b>スキルレベル由来の mode 加算は 0</b> になる。それでも
     * {@code workbench_quality_bonus}(作業台品質)・幸運・ばらつきは効くので品質は動く。
     * ここで {@code use-skill} を生産スキルに流用してはいけない — {@code use-skill} は
     * 「使用要件」であって分類マーカーではない(採取ツールにも付いており、流用すると誤爆する)。
     */
    private boolean hasQualityBearingStatsProfile(ItemStack result) {
        Integer cmd = result.hasItemMeta()
                ? DerivedItemStats.customModelDataOf(result.getItemMeta()) : null;
        return itemStats.profileFor(result.getType(), cmd)
                .filter(com.trinityforge.stats.ItemStatProfile::qualityApplies)
                .isPresent();
    }

    private Set<String> candidatesFor(ItemStack result) {
        if (isArsQualityStamped(result)) {
            return Set.of(ArsProgressionBridge.ARS_SMITHING);
        }
        Set<String> categories = EquipmentSlotResolver.statCategories(result.getType());
        return CraftQualityPolicy.candidateSkills(categories, config.categorySkill());
    }

    private boolean isArsQualityStamped(ItemStack result) {
        return CrossPluginItemResolver.idOf(result)
                .map(ArsItemGiveBridge::isQualityStamped)
                .orElse(false);
    }

    static boolean isVanillaSameItemRepair(ItemStack[] matrix, ItemStack result) {
        if (matrix == null || result == null || result.getAmount() != 1 || result.getType().isAir()) {
            return false;
        }
        ItemStack first = null;
        ItemStack second = null;
        for (ItemStack ingredient : matrix) {
            if (ingredient == null || ingredient.getType().isAir()) {
                continue;
            }
            if (ingredient.getAmount() != 1 || second != null) {
                return false;
            }
            if (first == null) {
                first = ingredient;
            } else {
                second = ingredient;
            }
        }
        if (first == null || second == null
                || first.getType() != second.getType()
                || result.getType() != first.getType()) {
            return false;
        }
        if (!(first.getItemMeta() instanceof Damageable firstMeta)
                || !(second.getItemMeta() instanceof Damageable secondMeta)
                || !(result.getItemMeta() instanceof Damageable resultMeta)) {
            return false;
        }
        int maxDamage = effectiveMaxDamage(first, firstMeta);
        if (maxDamage <= 0
                || effectiveMaxDamage(second, secondMeta) != maxDamage
                || effectiveMaxDamage(result, resultMeta) != maxDamage) {
            return false;
        }
        int firstRemaining = remainingDurability(maxDamage, firstMeta.getDamage());
        int secondRemaining = remainingDurability(maxDamage, secondMeta.getDamage());
        int repairBonus = maxDamage * 5 / 100;
        int expectedDamage = Math.max(
                0, maxDamage - Math.min(maxDamage, firstRemaining + secondRemaining + repairBonus));
        return resultMeta.getDamage() == expectedDamage;
    }

    static int craftOperationCount(boolean shiftClick, ItemStack[] matrix,
                                   ItemStack[] destinationStorage, ItemStack result) {
        if (!shiftClick) {
            return 1;
        }
        if (matrix == null || destinationStorage == null || result == null
                || result.getType().isAir() || result.getAmount() <= 0) {
            return 0;
        }
        int ingredientLimit = Integer.MAX_VALUE;
        boolean hasIngredient = false;
        for (ItemStack ingredient : matrix) {
            if (ingredient == null || ingredient.getType().isAir() || ingredient.getAmount() <= 0) {
                continue;
            }
            hasIngredient = true;
            ingredientLimit = Math.min(ingredientLimit, ingredient.getAmount());
        }
        if (!hasIngredient) {
            return 0;
        }

        long itemCapacity = 0L;
        int resultStackLimit = result.getMaxStackSize();
        for (ItemStack destination : destinationStorage) {
            if (destination == null || destination.getType().isAir()) {
                itemCapacity += resultStackLimit;
            } else if (destination.isSimilar(result)) {
                itemCapacity += Math.max(0, resultStackLimit - destination.getAmount());
            }
        }
        long destinationLimit = itemCapacity / result.getAmount();
        return (int) Math.min(ingredientLimit, Math.min(destinationLimit, Integer.MAX_VALUE));
    }

    private static int remainingDurability(int maxDamage, int damage) {
        return maxDamage - Math.min(maxDamage, Math.max(0, damage));
    }

    private static int effectiveMaxDamage(ItemStack item, Damageable meta) {
        return meta.hasMaxDamage() ? meta.getMaxDamage() : item.getType().getMaxDurability();
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
