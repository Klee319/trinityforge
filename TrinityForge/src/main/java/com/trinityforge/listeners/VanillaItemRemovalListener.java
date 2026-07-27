package com.trinityforge.listeners;

import com.trinityforge.stats.VanillaItemRemover;
import org.bukkit.Bukkit;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code progression/crafting-features.yml removed-vanilla-items} の適用: 対象アイテムの入手経路を
 * 遮断し(ロット/モブドロップ/ブロックドロップ/釣り/村人取引/拾得/ホッパー吸引)、既存所持を掃除する
 * ({@link #onJoin} + reload 時の {@link #sweepAllOnline()})。判定は {@link VanillaItemRemover} に委譲。
 */
public final class VanillaItemRemovalListener implements Listener {

    private final Plugin plugin;
    private final VanillaItemRemover remover;
    // 拾得直後の次tick再掃除の重複予約防止(プレイヤー単位、PickupQualityListener.pendingSweep と同じ方式)。
    private final Set<UUID> pendingPickupSweep = ConcurrentHashMap.newKeySet();

    public VanillaItemRemovalListener(Plugin plugin, VanillaItemRemover remover) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.remover = Objects.requireNonNull(remover, "remover");
    }

    // --- 入手経路の遮断 ---

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        if (!remover.hasTargets()) {
            return;
        }
        event.getLoot().removeIf(remover::shouldRemove);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!remover.hasTargets()) {
            return;
        }
        List<ItemStack> drops = new ArrayList<>(event.getDrops());
        if (drops.removeIf(remover::shouldRemove)) {
            event.getDrops().clear();
            event.getDrops().addAll(drops);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        if (!remover.hasTargets()) {
            return;
        }
        // BlockDropItemEvent fires AFTER the drops are already spawned as Item entities in the world
        // (getItems() reflects the live entity list, unlike EntityDeathEvent's not-yet-spawned
        // ItemStack list) — removing from the event list alone would leave the entity behind, so the
        // entity itself must be removed too.
        List<Item> items = new ArrayList<>(event.getItems());
        for (Item item : items) {
            if (remover.shouldRemove(item.getItemStack())) {
                event.getItems().remove(item);
                item.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (!remover.hasTargets() || event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!(event.getCaught() instanceof Item caught)) {
            return;
        }
        if (remover.shouldRemove(caught.getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * {@link EventPriority#HIGHEST}: {@link VillagerTradeListener#onInventoryOpen} runs at
     * {@code HIGH} and may call {@code villager.setRecipes(...)}; this must observe that final recipe
     * list, so it has to run strictly after — {@code HIGHEST} guarantees that regardless of
     * registration order (same-priority ordering is otherwise unspecified).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!remover.hasTargets()) {
            return;
        }
        if (!(event.getInventory() instanceof MerchantInventory merchant)) {
            return;
        }
        // Villager 限定だと行商人(WanderingTrader、Villager非継承のAbstractVillager別系統)を漏らすため
        // AbstractVillager で判定する(修正5)。
        if (!(merchant.getHolder() instanceof AbstractVillager villager)) {
            return;
        }
        List<MerchantRecipe> recipes = villager.getRecipes();
        List<MerchantRecipe> filtered = new ArrayList<>(recipes.size());
        boolean changed = false;
        for (MerchantRecipe recipe : recipes) {
            if (remover.shouldRemove(recipe.getResult())) {
                changed = true;
                continue;
            }
            filtered.add(recipe);
        }
        if (changed) {
            villager.setRecipes(filtered);
        }
    }

    /**
     * 修正2: 旧実装は {@code HIGH} で即 {@code setCancelled+item.remove()} していたため、identity復元する
     * {@link PickupQualityListener#onPickup}({@code MONITOR}+次tickスイープ)より先に走り、TF品として
     * 刻印される前(あるいはCMD照合が済む前)に破棄してしまう恐れがあった。{@code MONITOR}
     * ({@code ignoreCancelled=true})に下げ、ここでは何も破棄せず、ピックアップした本人のインベントリを
     * 次tickに1回だけ再スイープして {@link #sweepInventory} に判定・除去を委ねる(修正1の強化判定と合わせて
     * 未刻印CMD品/trinityforge-PDC保持品を確実に保護する)。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!remover.hasTargets()) {
            return;
        }
        if (event.getEntity() instanceof Player player) {
            schedulePickupSweep(player);
        }
    }

    /** ホッパー/ホッパー付きトロッコによる吸引(修正6): 恒久保管への回避exploitを防ぐ。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (!remover.hasTargets()) {
            return;
        }
        Item item = event.getItem();
        if (remover.shouldRemove(item.getItemStack())) {
            event.setCancelled(true);
            item.remove();
        }
    }

    /** 次tickに1回だけ当該プレイヤーのインベントリ再スイープを予約する(同一tickの重複はまとめる)。 */
    private void schedulePickupSweep(Player player) {
        UUID id = player.getUniqueId();
        if (!pendingPickupSweep.add(id)) {
            return; // このtickでは既に予約済み
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingPickupSweep.remove(id);
            if (player.isOnline()) {
                sweepInventory(player);
            }
        });
    }

    // --- 既存所持の掃除 ---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!remover.hasTargets()) {
            return;
        }
        sweepInventory(event.getPlayer());
    }

    /** reload で対象が増えた分をオンライン全員から即掃除するワンショット。 */
    public void sweepAllOnline() {
        if (!remover.hasTargets()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            sweepInventory(player);
        }
    }

    private void sweepInventory(Player player) {
        // PlayerInventory#getContents() はメイン36 + 防具4 + オフハンドの全スロットを含む
        // (CollectionListener と同じ前提)。
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (remover.shouldRemove(contents[slot])) {
                inv.setItem(slot, null);
            }
        }
        // 修正7: getContents() はカーソル(GUI操作中に掴んでいるアイテム)を含まないため別途走査する
        // (PickupQualityListener.sweepInventory と同じパターン)。クラフトグリッドは open な作業台
        // インベントリ側の一時状態のためここでは対象外とする(brief の任意範囲)。
        ItemStack cursor = player.getItemOnCursor();
        if (remover.shouldRemove(cursor)) {
            player.setItemOnCursor(null);
        }
    }
}
