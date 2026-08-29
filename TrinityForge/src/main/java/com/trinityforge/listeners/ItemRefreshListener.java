package com.trinityforge.listeners;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemAssembler;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemRefreshPolicy;
import com.trinityforge.stats.TableGeneration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;
import java.util.Optional;

/**
 * Re-applies the live stat/lore/attribute tables onto items already in play (SELECTION_SPEC 5: a
 * table edit + {@code /trinityforge reload} must reach existing items, not just newly-created ones).
 * {@code ItemFactory.create} is the only write path today ({@code ItemAssembler.assemble} is never
 * invoked again after an item leaves that factory), so an item rolled before a reload kept its stale
 * lore/attributes forever; this listener closes that gap at the three points an item re-enters
 * "visible/equipped" state without going back through the factory:
 *
 * <ul>
 *   <li>{@link PlayerItemHeldEvent} — the item in the newly-selected hotbar slot</li>
 *   <li>{@link PlayerArmorChangeEvent} — the newly-equipped armor piece (Paper-specific event)</li>
 *   <li>{@link PlayerJoinEvent} — the full inventory (including offhand / storage), so table
 *       edits reach already-owned threads and gear, not just the held item</li>
 *   <li>{@link InventoryCloseEvent} — chest take does not fire held/join; closing the inventory
 *       is when those stacks enter the player's own inventory</li>
 * </ul>
 *
 * <p>{@link ItemAssembler#assemble} is idempotent and replace-based, so re-running it on an
 * already-current item is safe; {@link ItemRefreshPolicy#needsRefresh} (a pure decision, see its own
 * tests) short-circuits that redundant work using the {@link TableGeneration} stamp
 * ({@code ItemData#tableGeneration}) so a hotbar switch does not re-derive stats and re-compose lore
 * on every tick for items that have not gone stale.
 *
 * <p>ArsPaper スレッドは {@code assemble} 禁止(W-53: 専用 lore が壊れる)。表が変わったあとの
 * 既存個体は品質と pt({@code rollSeed})を保ったまま {@code ThreadItem#refreshLoreKeepingIdentity}
 * へ委譲する。装着済みスレッドの数値そのものは装備 PDC の identity + 現行表で毎tick導出されるので、
 * 手持ちスタックの lore だけがこの経路の対象。スレッドは世代が最新でも持ち替え／参加／インベントリ
 * 閉鎖で組み直す（Ars 側のフォーマット変更は TF の table generation を動かさない）。
 */
public final class ItemRefreshListener implements Listener {

    private final ItemAssembler assembler;
    private final TableGeneration tableGeneration;
    private final ArsThreadLoreRefresher threadLoreRefresher;

    public ItemRefreshListener(ItemAssembler assembler, TableGeneration tableGeneration) {
        this(assembler, tableGeneration, PickupQualityListener::defaultArsThreadLoreRefresh);
    }

    /**
     * テスト用シーム。本番の reflection({@link PickupQualityListener#defaultArsThreadLoreRefresh})
     * は ArsPaper 実体が要るので、ユニットテストはここへ差し替える。
     */
    ItemRefreshListener(ItemAssembler assembler, TableGeneration tableGeneration,
                        ArsThreadLoreRefresher threadLoreRefresher) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.tableGeneration = Objects.requireNonNull(tableGeneration, "tableGeneration");
        this.threadLoreRefresher = Objects.requireNonNull(threadLoreRefresher, "threadLoreRefresher");
    }

    @FunctionalInterface
    interface ArsThreadLoreRefresher {
        boolean refreshKeepingIdentity(ItemStack stack);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        refresh(player.getInventory().getItem(event.getNewSlot()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        refresh(event.getNewItem());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        refreshPlayerInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            refreshPlayerInventory(player);
        }
    }

    /** Eagerly re-assembles every TF-stamped item in online players' inventories after a reload. */
    public void refreshAllOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayerInventory(player);
        }
    }

    private void refreshPlayerInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            refresh(inventory.getItem(slot));
        }
        for (ItemStack armor : inventory.getArmorContents()) {
            refresh(armor);
        }
        refresh(player.getItemOnCursor());
    }

    private void refresh(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        ItemData data = ItemData.of(meta);
        boolean hasRollSeed = data.hasRollSeed();
        boolean stale = ItemRefreshPolicy.needsRefresh(
                hasRollSeed, data.tableGeneration(), tableGeneration.current());
        // 旧 glow の HIDE_ENCHANTS は table generation を動かさないので、世代が最新でも一度 assemble する。
        boolean glowResidue = ItemFactory.hasLegacyGlowResidue(meta);
        boolean isArsThread = PickupQualityListener.hasArsThreadMarker(meta);
        // スレッド lore は Ars 側の組み直し。世代が最新でも持ち替えで届ける
        // (フォーマット変更は TF の table generation を動かさない)。
        if (!isArsThread && !stale && !glowResidue) {
            assembler.appendOwnerLoreIfMissing(stack);
            return;
        }
        if (isArsThread) {
            // assemble 禁止。失敗(Ars 未ロード等)でも汎用経路へ落としてはいけない。
            boolean refreshed = threadLoreRefresher.refreshKeepingIdentity(stack);
            if (refreshed) {
                ItemMeta after = stack.getItemMeta();
                if (after != null) {
                    ItemData.of(after).setTableGeneration(tableGeneration.current());
                    stack.setItemMeta(after);
                }
            }
            assembler.appendOwnerLoreIfMissing(stack);
            return;
        }
        Optional<Long> rollSeed = data.rollSeed();
        if (rollSeed.isEmpty()) {
            return;
        }
        assembler.assemble(meta, stack.getType(), rollSeed.get(), data.quality());
        stack.setItemMeta(meta);
    }
}
