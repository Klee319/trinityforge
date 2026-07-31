package com.trinityforge.listeners;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.CollectionService;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DerivedItemStats;
import org.bukkit.Material;
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
import java.util.LinkedHashSet;
import java.util.Locale;
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
 *
 * <p><b>ArsPaper 側のアイテム (2026-07-31)</b>: PDC刻印の読み取りは
 * {@link com.trinityforge.stats.CrossPluginItemResolver#idOf} 経由にしてある。TF の catalog PDC しか
 * 見ていなかったため、ArsPaper の {@code materials.yml} / {@code sourcejars.yml} で定義したアイテム
 * (モブドロップ素材・ダンジョン踏破の証・ソースの階梯)が<b>永久に記録されず</b>、図鑑カテゴリに
 * 書いてある 116 件のうち 48 件が「絶対に埋まらない枠」として並び続けていた。
 *
 * <p><b>バニラアイテム (2026-07-29)</b>: 以前はカタログ品しか記録できなかったため、
 * アチーブメントの「アイテム条件」にバニラアイテムを書いても進捗が永久に0のままだった。
 * {@code item:<MATERIAL>} も記録できるようにしたが、拾った物を無条件に記録すると
 * プレイヤーPDCが全Material分まで膨らむ。記録するのは<b>設定から参照されている</b>
 * Material だけに限る:
 * <ul>
 *   <li>{@code progression/collection.yml} の図鑑カテゴリ entries に書かれた Material</li>
 *   <li>{@code progression/achievements.yml} の {@code collection.scope: item} 対象の Material</li>
 * </ul>
 * どちらの config も reload で差し替わるので、監視集合はキャッシュせず毎回引き直す。
 */
public final class CollectionListener implements Listener {

    /** editor が custom アイテムに付ける接頭辞。監視集合を作るときに落とす。 */
    private static final String CUSTOM_PREFIX = "custom:";

    private final CollectionConfig config;
    private final CollectionService service;
    private final ItemCatalogConfig catalog;
    private final AchievementsConfig achievements;

    public CollectionListener(CollectionConfig config, CollectionService service,
                              ItemCatalogConfig catalog, AchievementsConfig achievements) {
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.achievements = Objects.requireNonNull(achievements, "achievements");
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
        // ArsPaper 側で定義したアイテム。2026-07-31 まで見ていなかったため、materials.yml /
        // sourcejars.yml 由来のアイテム(モブドロップ素材17件・ダンジョン踏破の証22件・
        // ソースの階梯9件 = 図鑑カテゴリ116件中48件)が永久に記録されず、図鑑に
        // 「絶対に埋まらない枠」として並び続けていた。
        Optional<String> arsId = CrossPluginItemResolver.arsIdOf(stack);
        if (arsId.isPresent()) {
            // バニラ Material と同じく設定から参照されているIDだけに絞る。Ars の登録アイテムは
            // グリフ120件を含めて300件超あり、無条件に記録するとプレイヤーPDCがそれだけ膨らみ、
            // かつ図鑑の報酬ティア(10/30/60/120/200件)の重みが黙って変わってしまう。
            return watchedConfigIds().contains(arsId.get()) ? arsId : Optional.empty();
        }
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        if (cmd == null) {
            return trackedVanillaId(stack.getType());
        }
        Optional<String> template = CatalogIdentity.find(catalog, stack.getType(), cmd).map(t -> t.id());
        return template.isPresent() ? template : trackedVanillaId(stack.getType());
    }

    /** 設定から参照されている Material だけ {@code item:<MATERIAL>} として図鑑に載せる。 */
    private Optional<String> trackedVanillaId(Material material) {
        if (material == null || material.isAir()) {
            return Optional.empty();
        }
        return watchedConfigIds().contains(material.name())
                ? Optional.of(material.name())
                : Optional.empty();
    }

    /**
     * 図鑑カテゴリとアチーブメントのアイテム条件に書かれたIDの集合。
     *
     * <p>Material 名(大文字)と ArsPaper のカスタムID(小文字)の両方が入る。両者は表記が衝突しないので
     * 1つの集合で足りる。TF カタログIDも混ざるが、カタログ品はこの集合を経由せず PDC 刻印で
     * 記録されるため実害はない。
     *
     * <p>どちらの config も reload で差し替わるのでキャッシュしない。
     */
    private Set<String> watchedConfigIds() {
        Set<String> watched = new LinkedHashSet<>();
        for (CollectionConfig.Category category : config.itemCategories()) {
            category.entries().forEach(entry -> addWatched(watched, entry));
        }
        for (AchievementsConfig.Achievement achievement : achievements.achievements()) {
            AchievementsConfig.Trigger trigger = achievement.trigger();
            if (trigger == null || !"item".equals(trigger.collectionScope())) {
                continue;
            }
            trigger.collectionTargets().forEach(target -> addWatched(watched, target));
        }
        return watched;
    }

    /**
     * Material なら正規化した Material 名、そうでなければそのままカスタムIDとして登録する。
     * editor が付ける {@code custom:} 接頭辞は落とす(他ドメインと同じ扱い)。
     */
    private static void addWatched(Set<String> out, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String token = raw.trim();
        if (token.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
            token = token.substring(CUSTOM_PREFIX.length()).trim();
        }
        if (token.isEmpty()) {
            return;
        }
        Material material = Material.matchMaterial(token.toUpperCase(Locale.ROOT));
        out.add(material != null && material.isItem() ? material.name() : token);
    }
}
