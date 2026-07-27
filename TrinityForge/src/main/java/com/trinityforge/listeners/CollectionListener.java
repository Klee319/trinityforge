package com.trinityforge.listeners;

import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.CollectionService;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.DerivedItemStats;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * コレクション図鑑 (M7) の記録フィード。
 *
 * <p><b>アイテム</b>: カタログ(items/catalog.yml)アイテムの入手を記録する。拾得
 * ({@link EntityPickupItemEvent})に加え、直接インベントリへ入る経路(ダンジョンloot直入れ・
 * ガチャ・村人取引・クラフト結果の取り出し等)を取りこぼさないよう、インベントリを閉じた時と
 * 参加時に全スロットを走査して差分登録する(既知エントリはPDC書き込みなしで抜けるため軽量)。
 *
 * <p><b>討伐</b>: プレイヤーがキラーのモブ死亡を {@code mob:<ENTITY_TYPE>} として記録する
 * (EliteMobs個体もベースのEntityTypeで記録される)。
 *
 * <p>カタログID解決はPDC刻印を優先し、未刻印でも material+CustomModelData がテンプレートに
 * 一致すれば図鑑対象にする({@link CatalogIdentity#ensure} と同じくCMD無しのバニラスタックは
 * カタログ照合しない — 偶然materialが同じだけの vanilla /give を誤登録しないため)。
 */
public final class CollectionListener implements Listener {

    private final CollectionConfig config;
    private final CollectionService service;
    private final ItemCatalogConfig catalog;

    public CollectionListener(CollectionConfig config, CollectionService service,
                              ItemCatalogConfig catalog) {
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!config.catalogItemsEnabled() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        catalogIdOf(stack).ifPresent(id -> service.record(player,
                Map.of(CollectionService.itemEntryId(id), qualityOf(stack))));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scanInventory(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scanInventory(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        if (!config.mobKillsEnabled()) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player) {
            return;
        }
        service.record(killer, Set.of(CollectionService.mobEntryId(event.getEntityType().name())));
    }

    private void scanInventory(Player player) {
        if (!config.catalogItemsEnabled()) {
            return;
        }
        Map<String, Integer> ids = new LinkedHashMap<>();
        // getContents() はメイン36 + 防具4 + オフハンドの全スロットを含む。
        for (ItemStack stack : player.getInventory().getContents()) {
            catalogIdOf(stack).ifPresent(id -> {
                String entryId = CollectionService.itemEntryId(id);
                int quality = qualityOf(stack);
                ids.merge(entryId, quality, Math::max);
            });
        }
        if (!ids.isEmpty()) {
            service.record(player, ids);
        }
    }

    /** 品質ptの取得(0-100)。品質PDC未刻印(Ars/vanilla)なら0。 */
    private static int qualityOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return 0;
        }
        return ItemData.of(stack.getItemMeta()).quality();
    }

    private Optional<String> catalogIdOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.getItemMeta();
        Optional<String> stamped = CatalogIdentity.catalogIdOf(meta);
        if (stamped.isPresent()) {
            return stamped;
        }
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        if (cmd == null) {
            return Optional.empty();
        }
        return CatalogIdentity.find(catalog, stack.getType(), cmd).map(t -> t.id());
    }
}
