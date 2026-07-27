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
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
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
 */
public final class GachaListener implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String GACHA_RATE_BONUS_KEY = StatKeys.canonical("gacha_rate_bonus");

    private final Plugin plugin;
    private final GachaConfig gachaConfig;
    private final QualityConfig quality;
    private final PlayerStatAggregator aggregator;
    private final CrossPluginItemResolver itemResolver;

    public GachaListener(Plugin plugin, GachaConfig gachaConfig, ItemCatalogConfig itemCatalog,
                         ItemFactory itemFactory, QualityConfig quality, PlayerStatAggregator aggregator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.gachaConfig = Objects.requireNonNull(gachaConfig, "gachaConfig");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.itemResolver = new CrossPluginItemResolver(
                Objects.requireNonNull(itemCatalog, "itemCatalog"),
                Objects.requireNonNull(itemFactory, "itemFactory"));
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
        if (heldStack.getType().isAir() || !heldStack.hasItemMeta()) {
            return;
        }
        // 券識別 (2026-07-23 stat-gate-overhaul §1 緊急修正1): tf_gacha_ticket* は Ars materials.yml へ
        // 移動済みのため、TF PDC単独読みでは不発 — dual-PDC読みの CrossPluginItemResolver.idOf を使う。
        Optional<String> catalogId = CrossPluginItemResolver.idOf(heldStack);
        if (catalogId.isEmpty()) {
            return;
        }
        Optional<GachaTicket> ticket = gachaConfig.ticket(catalogId.get());
        if (ticket.isEmpty()) {
            return;
        }

        // This is a recognized ticket item: it never falls through to vanilla right-click behaviour,
        // regardless of whether the draw below succeeds.
        event.setCancelled(true);

        Optional<GachaPool> pool = gachaConfig.pool(ticket.get().poolId());
        if (pool.isEmpty()) {
            sendError(player, "ガチャ設定が不正です(プール未定義)。管理者に連絡してください。");
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
        int currentPity = playerData.gachaPityCount(ticket.get().poolId());
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
        playerData.setGachaPityCount(ticket.get().poolId(), outcome.updatedPityCount());
        player.sendMessage(MINI_MESSAGE.deserialize(
                "<green>ガチャ券を使用しました！ <white><item></white> ×<amount> を獲得しました！</green>",
                Placeholder.unparsed("item", drawn.itemId()),
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
