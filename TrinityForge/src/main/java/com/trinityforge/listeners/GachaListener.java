package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.GachaConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.gacha.GachaDraw;
import com.trinityforge.gacha.GachaEntry;
import com.trinityforge.gacha.GachaPool;
import com.trinityforge.gacha.GachaRateUp;
import com.trinityforge.gacha.GachaTicket;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.StatKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Handles right-clicking a "gacha ticket" item: draws one weighted prize from the ticket's
 * config-defined pool ({@code gacha.yml}), gives it to the player, and consumes one ticket. The
 * ticket item is identified via {@link CrossPluginItemResolver#idOf} (TF {@code ITEM_CATALOG_ID} PDC
 * tag, then the ArsPaper {@code custom_item_id} PDC tag — ticket ids now live in ArsPaper's
 * materials.yml) — never by display name, so a renamed/relabeled ticket item still works.
 *
 * <p>A prize's {@code item} id is resolved via the same {@link CrossPluginItemResolver} (TF catalog,
 * built via the shared {@link ItemFactory} with a random quality when {@code quality-random} is set,
 * then the ArsPaper registry, then a vanilla Material). Any resolution failure sends an error message
 * and leaves the ticket un-consumed — a player must never lose a ticket to a config typo.
 *
 * <p>{@link VoidRightClickBridge.Handler} 経由の虚空右クリックフォールバック(2026-08-02)は
 * 左クリック(空振り攻撃)との誤検出がありうる({@link VoidRightClickBridge} javadoc 参照)ため、
 * その経路では抽選を即実行せず、確認GUI({@link #openConfirm})を1枚挟んでから
 * {@link #drawAndConsume} を呼ぶ。券の消費・抽選確定は必ず {@link #onClick}(=実際の
 * {@link InventoryClickEvent})経由でのみ起こり、腕振りだけでは絶対に確定しない
 * (誤検出時の実害は「確認GUIが開くだけ」に抑えている)。通常の右クリック経路
 * ({@link #onInteract}、左右が確実に分かる)は今までどおり即実行。
 */
public final class GachaListener implements Listener, VoidRightClickBridge.Handler {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String GACHA_RATE_BONUS_KEY = StatKeys.canonical("gacha_rate_bonus");
    private static final int CONFIRM_GUI_SIZE = 9;
    private static final int CONFIRM_TICKET_SLOT = 2;
    private static final int CONFIRM_YES_SLOT = 4;
    private static final int CONFIRM_NO_SLOT = 6;

    private final Plugin plugin;
    private final GachaConfig gachaConfig;
    private final QualityConfig quality;
    private final PlayerStatAggregator aggregator;
    private final CrossPluginItemResolver itemResolver;
    private final ItemCatalogConfig itemCatalog;
    private final NamespacedKey confirmKey;
    private final NamespacedKey cancelKey;

    public GachaListener(Plugin plugin, GachaConfig gachaConfig, ItemCatalogConfig itemCatalog,
                         ItemFactory itemFactory, QualityConfig quality, PlayerStatAggregator aggregator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gachaConfig = Objects.requireNonNull(gachaConfig, "gachaConfig");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.itemResolver = new CrossPluginItemResolver(
                this.itemCatalog,
                Objects.requireNonNull(itemFactory, "itemFactory"));
        this.confirmKey = new NamespacedKey(this.plugin, "gacha_void_confirm_yes");
        this.cancelKey = new NamespacedKey(this.plugin, "gacha_void_confirm_no");
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            // Paper fires this event for both hands; only handle the main-hand instance so a ticket
            // in the off-hand does not double-draw.
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack heldStack = player.getInventory().getItemInMainHand();
        Optional<GachaTicket> ticket = resolveTicket(heldStack);
        if (ticket.isEmpty()) {
            return;
        }
        // This is a recognized ticket item: it never falls through to vanilla right-click behaviour,
        // regardless of whether the draw below succeeds.
        event.setCancelled(true);
        drawAndConsume(player, heldStack, ticket.get());
    }

    /**
     * {@link VoidRightClickBridge} 経由の虚空右クリックフォールバック(2026-08-02)。この経路は
     * 「虚空へ向けた左クリック(空振り攻撃)」との誤検出がありうる({@link VoidRightClickBridge}
     * javadoc 参照)ため、<b>ここでは抽選を確定させない</b>。判定(={@link #resolveTicket})は
     * {@link #onInteract} と共有するが、その先は即 {@link #drawAndConsume} を呼ばず
     * {@link #openConfirm} で確認GUIを1枚挟む。誤検出しても「確認GUIが開くだけ」で券は減らない。
     */
    @Override
    public boolean tryHandle(Player player, ItemStack mainhand) {
        Optional<GachaTicket> ticket = resolveTicket(mainhand);
        if (ticket.isEmpty()) {
            return false;
        }
        openConfirm(player, ticket.get());
        return true;
    }

    /** 虚空右クリックフォールバック専用の確認GUI。確定は必ず {@link #onClick} 経由でのみ起こる。 */
    private void openConfirm(Player player, GachaTicket ticket) {
        ConfirmSession session = new ConfirmSession(ticket.catalogId());
        Inventory inventory = Bukkit.createInventory(session, CONFIRM_GUI_SIZE,
                Component.text("ガチャ券を使用しますか？"));
        session.inventory = inventory;
        inventory.setItem(CONFIRM_TICKET_SLOT, ticketPreviewIcon());
        inventory.setItem(CONFIRM_YES_SLOT, confirmIcon());
        inventory.setItem(CONFIRM_NO_SLOT, cancelIcon());
        player.openInventory(inventory);
    }

    private ItemStack ticketPreviewIcon() {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("虚空右クリックを検知しました", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("素振りとの誤判定を避けるため、", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("使用するか確認します。", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack confirmIcon() {
        ItemStack stack = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("使用する", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(confirmKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack cancelIcon() {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("やめる", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(cancelKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ConfirmSession session)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta.getPersistentDataContainer().has(cancelKey, PersistentDataType.BYTE)) {
            player.closeInventory();
            return;
        }
        if (!meta.getPersistentDataContainer().has(confirmKey, PersistentDataType.BYTE)) {
            return;
        }
        player.closeInventory();
        // GUIを開いた時点の判定を信用せず、確定操作(=このクリック)の時点で改めて手元のアイテムを
        // 再検証する(DungeonEntryGui.attemptEntry と同じ「その場で再検証」の流儀)。券が減っている/
        // 持ち替えられている可能性があるため。
        ItemStack current = player.getInventory().getItemInMainHand();
        Optional<GachaTicket> ticket = resolveTicket(current);
        if (ticket.isEmpty() || !ticket.get().catalogId().equals(session.ticketCatalogId)) {
            sendError(player, "券が見当たりません。もう一度お試しください。");
            return;
        }
        drawAndConsume(player, current, ticket.get());
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ConfirmSession) {
            event.setCancelled(true);
        }
    }

    private static final class ConfirmSession implements InventoryHolder {
        private final String ticketCatalogId;
        private Inventory inventory;

        private ConfirmSession(String ticketCatalogId) {
            this.ticketCatalogId = ticketCatalogId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** 券識別 (2026-07-23 stat-gate-overhaul §1 緊急修正1): tf_gacha_ticket* は Ars materials.yml へ
     * 移動済みのため、TF PDC単独読みでは不発 — dual-PDC読みの CrossPluginItemResolver.idOf を使う。 */
    private Optional<GachaTicket> resolveTicket(ItemStack heldStack) {
        if (heldStack == null || heldStack.getType().isAir() || !heldStack.hasItemMeta()) {
            return Optional.empty();
        }
        Optional<String> catalogId = CrossPluginItemResolver.idOf(heldStack);
        if (catalogId.isEmpty()) {
            return Optional.empty();
        }
        return gachaConfig.ticket(catalogId.get());
    }

    /** 抽選〜券消費〜通知までの本体(通常の右クリック経路・虚空フォールバック経路の両方から呼ばれる)。 */
    private void drawAndConsume(Player player, ItemStack heldStack, GachaTicket ticket) {
        Optional<GachaPool> pool = gachaConfig.pool(ticket.poolId())
                .map(this::withoutDraftPrizes);
        if (pool.isEmpty()) {
            sendError(player, "ガチャ設定が不正です(プール未定義)。管理者に連絡してください。");
            return;
        }
        if (pool.get().entries().isEmpty()) {
            // 準備中の景品しか残らなかった。抽選に入ると必ず「解決失敗→券を消費しない」経路へ落ちて
            // 無限に引き直せてしまうので、ここで打ち切る(券も減らさないが、引く動作自体が成立しない)。
            plugin.getLogger().warning("[gacha] pool '" + ticket.poolId()
                    + "' は景品が全て準備中(draft)のため抽選できません");
            sendError(player, "このガチャは現在準備中です。");
            return;
        }

        // gacha_rate_bonus (装備+perk合算): PercentStatNormalize.RATE_KEYS already coerces this to a
        // [0,1] fraction at aggregation time (e.g. 20 -> 0.2). Pass the fraction straight through to
        // GachaRateUp — it must NOT be divided by 100 again (that bug hit BeekeepingListener /
        // MiningGimmickListener / FoodGimmickListener before this one; see GachaRateUp Javadoc).
        // Approximated by boosting the pool's minimum-weight ("rarest") entries' weight in a temporary
        // copy — GachaDraw/GachaPool/GachaEntry themselves are left untouched (see GachaRateUp for the
        // exact boost formula and rationale).
        double rateUpFraction = aggregator.aggregate(player).totalOf(GACHA_RATE_BONUS_KEY);
        GachaPool effectivePool = GachaRateUp.applyRateUp(pool.get(), rateUpFraction);

        // 天井(pity, ITEM_ECONOMY CR-9安全弁②): 抽選はrate-upブースト済みプールで行うが、
        // 「最高レア」の定義とカウンタ判定はconfigの元プールに固定する(GachaDraw参照)。
        // カウンタの保存は券消費が成立した後(景品解決失敗=券未消費のときに進めない)。
        com.trinityforge.pdc.PlayerData playerData = com.trinityforge.pdc.PlayerData.of(player);
        int currentPity = playerData.gachaPityCount(ticket.poolId());
        GachaDraw.PityDraw outcome;
        try {
            outcome = GachaDraw.drawWithPity(effectivePool, pool.get(), ThreadLocalRandom.current(), currentPity);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING,
                    "[gacha] draw failed for pool '" + effectivePool.id() + "'", ex);
            sendError(player, "抽選に失敗しました。管理者に連絡してください。");
            return;
        }
        GachaEntry drawn = outcome.entry();

        Optional<ItemStack> prize = resolvePrize(drawn);
        if (prize.isEmpty()) {
            // Guard: never consume the ticket when the configured prize item cannot be resolved/built.
            plugin.getLogger().warning("[gacha] prize item '" + drawn.itemId()
                    + "' could not be resolved (not a catalog id or vanilla Material); ticket not consumed");
            sendError(player, "景品の生成に失敗しました。券は消費されません。管理者に連絡してください。");
            return;
        }

        giveOrDrop(player, prize.get());
        consumeOneTicket(player, heldStack);
        playerData.setGachaPityCount(ticket.poolId(), outcome.updatedPityCount());
        // 表示名はID表記ではなく実際のアイテム名を出す(要件#34): 構築済み prize から
        // CollectionEntryNames.itemName で解決する(display-name → Material翻訳キー → 生IDの順で
        // フォールバック)。ログ側(下の資源解決失敗時など)は追跡のため生IDのまま残すこと。
        player.sendMessage(MINI_MESSAGE.deserialize(
                "<green>ガチャ券を使用しました！ <white><item></white> ×<amount> を獲得しました！</green>",
                Placeholder.component("item",
                        com.trinityforge.progression.CollectionEntryNames.itemName(drawn.itemId(), prize.get())),
                Placeholder.unparsed("amount", Integer.toString(prize.get().getAmount()))));
        if (outcome.pityTriggered()) {
            player.sendMessage(MINI_MESSAGE.deserialize(
                    "<gold>天井到達！最高レア枠が確定排出されました。</gold>"));
        }
    }

    /**
     * Resolves a drawn entry's {@code item} id via the shared {@link CrossPluginItemResolver}
     * (2026-07-23 stat-gate-overhaul §1 緊急修正1: catalog → ArsPaper registry → vanilla Material), so a
     * prize id moved to Ars materials.yml (compressed blocks, scrap, …) still resolves.
     */
    private Optional<ItemStack> resolvePrize(GachaEntry entry) {
        try {
            int rolledQuality = entry.qualityRandom()
                    ? ThreadLocalRandom.current().nextInt(quality.maxQuality() + 1)
                    : 0;
            Optional<ItemStack> built = itemResolver.create(
                    entry.itemId(), ThreadLocalRandom.current().nextLong(), rolledQuality);
            built.ifPresent(stack -> stack.setAmount(entry.amount()));
            return built;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "[gacha] failed to build prize '" + entry.itemId() + "'", ex);
            return Optional.empty();
        }
    }

    /**
     * 「準備中」(catalog.yml の {@code draft: true})の景品を抽選前にプールから外す。
     *
     * <p>準備中アイテムは {@code ItemCatalogConfig} がゲーム側の参照面から落としているので、
     * そのまま抽選すると {@link #resolvePrize} が必ず空を返す。ところが解決失敗時の設計は
     * <b>「券を消費しない」</b>(景品ロスト防止)なので、準備中の景品を1件でも残したまま運用すると
     * <b>券が減らないまま何度でも引ける</b>＝実質無限ガチャになる。だから
     * 「解決に失敗した」ではなく<b>「最初から候補に入れない」</b>で扱う。
     *
     * <p>重みは残ったエントリのぶんだけで再計算される(GachaDraw は総和を都度取る)ので、
     * 準備中を外した結果は「その景品が無い設定」と同じ挙動になる。
     */
    private GachaPool withoutDraftPrizes(GachaPool pool) {
        List<GachaEntry> live = pool.entries().stream()
                .filter(entry -> !itemCatalog.isDraft(entry.itemId()))
                .toList();
        return live.size() == pool.entries().size()
                ? pool
                : new GachaPool(pool.id(), live, pool.pityThreshold());
    }

    private void giveOrDrop(Player player, ItemStack prize) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(prize);
        if (leftover.isEmpty()) {
            return;
        }
        for (ItemStack remainder : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), remainder);
        }
    }

    private void consumeOneTicket(Player player, ItemStack heldStack) {
        int remaining = heldStack.getAmount() - 1;
        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        ItemStack updated = heldStack.clone();
        updated.setAmount(remaining);
        player.getInventory().setItemInMainHand(updated);
    }

    private void sendError(Player player, String message) {
        player.sendMessage(MINI_MESSAGE.deserialize("<red>" + message + "</red>"));
    }
}
