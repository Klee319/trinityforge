package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.pdc.BindType;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.CatalogIdentity;
import com.trinityforge.stats.CraftQualityPolicy;
import com.trinityforge.stats.CraftQualityService;
import com.trinityforge.stats.DerivedItemStats;
import com.trinityforge.stats.EmptyThreadStackNormalizer;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.PlayerLootLuckSource;
import com.trinityforge.stats.PreviewRollSeeds;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 未刻印(hasRollSeed() == false)だが {@code stats/item-stats.yml} に設定のあるアイテムが、地面からの取得・
 * チェストからの取り出し・クリエイティブでの取得・インベントリ内移動などでプレイヤーの手元に入ったとき、
 * 品質を {@link QualityConfig} の split-normal 分布(mode=0)で決めて rollSeed とともに刻印し TF品にする。
 *
 * <p><b>ちらつき/増殖/消滅の修正(ユーザー指摘)</b>: 旧実装は {@link InventoryClickEvent} の MONITOR で
 * {@code getCurrentItem()}/{@code getCursor()} を「対象外アイテムでも無条件に」書き戻していたため、どんな
 * アイテムをクリックしても毎回クライアントへ再送信が走り、ちらつき・増殖・消滅を招いていた。新実装は
 * イベント中にアイテムを一切書き換えず、代わりに「次tickで当該プレイヤーのインベントリを1回だけ走査し、
 * <em>実際に刻印したスロットだけ</em> setItem で書き戻す」方式にした。これにより:
 * <ul>
 *   <li>対象外/既刻印アイテムは書き戻されない(=再送信ゼロ) → ちらつき解消</li>
 *   <li>イベント処理中(MONITOR)にカーソル/スロットを書き換えないので shift-click 等での増殖/消滅が起きない</li>
 *   <li>次tickにインベントリ全体(収納/防具/オフハンド/カーソル)を見るので、shift-clickでのチェスト取り出しや
 *       クリエイティブ取得も確実に刻印される(旧実装の未カバー箇所)</li>
 * </ul>
 * 同一tickに複数イベントが来ても走査は {@code pendingSweep} で1回に集約する。
 */
public final class PickupQualityListener implements Listener {

    private final Plugin plugin;
    private final ItemFactory itemFactory;
    private final ItemStatsConfig itemStats;
    private final QualityTiersConfig qualityTiers;
    private final QualityConfig quality;
    private final ItemCatalogConfig itemCatalog;
    private final PlayerLootLuckSource lootLuck;
    // 品質未決定マーカー(儀式クラフト成果物)を回収者のステータスで解決するために使う。
    // null 可: この経路を持たない構成/テストでは通常のドロップ品経路へフォールバックする。
    private final CraftQualityService craftQuality;
    // 同一tick内での走査重複を防ぐ(イベントが連続で来ても1プレイヤー1tick1走査)。
    private final Set<UUID> pendingSweep = ConcurrentHashMap.newKeySet();
    // ArsPaper スレッド(ThreadItem)専用の品質再刻印関数(W-53)。詳細は下の interface/defaultメソッド。
    private final ArsThreadQualityRestamper arsThreadRestamper;

    public PickupQualityListener(Plugin plugin, ItemFactory itemFactory, ItemStatsConfig itemStats,
                                  QualityTiersConfig qualityTiers, QualityConfig quality,
                                  ItemCatalogConfig itemCatalog, PlayerLootLuckSource lootLuck) {
        this(plugin, itemFactory, itemStats, qualityTiers, quality, itemCatalog, lootLuck, null);
    }

    public PickupQualityListener(Plugin plugin, ItemFactory itemFactory, ItemStatsConfig itemStats,
                                  QualityTiersConfig qualityTiers, QualityConfig quality,
                                  ItemCatalogConfig itemCatalog, PlayerLootLuckSource lootLuck,
                                  CraftQualityService craftQuality) {
        this(plugin, itemFactory, itemStats, qualityTiers, quality, itemCatalog, lootLuck, craftQuality,
                PickupQualityListener::defaultArsThreadRestamp);
    }

    /**
     * テスト用シーム。{@code arsThreadRestamper} は「ArsPaper のスレッド(ThreadItem)専用の品質再刻印」を
     * 行う関数で、本番は必ず {@link #defaultArsThreadRestamp} が入る({@code CraftQualityService} 込み8引数
     * コンストラクタ経由)。差し替え可能にしてある理由は {@code GiveItemCommand.ThreadQualityRestamper} と
     * 同じ(本番実装が {@code Bukkit.getPluginManager().getPlugin("ArsPaper")} 越しのリフレクションで、
     * ユニットテストからは実際の ArsPaper プラグイン/ThreadItem の実体を用意できないため)。
     */
    PickupQualityListener(Plugin plugin, ItemFactory itemFactory, ItemStatsConfig itemStats,
                          QualityTiersConfig qualityTiers, QualityConfig quality,
                          ItemCatalogConfig itemCatalog, PlayerLootLuckSource lootLuck,
                          CraftQualityService craftQuality, ArsThreadQualityRestamper arsThreadRestamper) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory");
        this.itemStats = Objects.requireNonNull(itemStats, "itemStats");
        this.qualityTiers = Objects.requireNonNull(qualityTiers, "qualityTiers");
        this.quality = Objects.requireNonNull(quality, "quality");
        this.itemCatalog = Objects.requireNonNull(itemCatalog, "itemCatalog");
        this.lootLuck = Objects.requireNonNull(lootLuck, "lootLuck");
        this.craftQuality = craftQuality;
        this.arsThreadRestamper = Objects.requireNonNull(arsThreadRestamper, "arsThreadRestamper");
    }

    /** ArsPaper {@code ItemKeys.THREAD_ITEM_TYPE} と同じ名前空間/キー(コンパイル依存なしで読む)。 */
    private static final NamespacedKey ARS_THREAD_ITEM_TYPE_KEY =
            new NamespacedKey("arspaper", "thread_item_type");

    /**
     * {@code meta} が ArsPaper のスレッド(ThreadItem)かどうかの<b>安価な</b>事前判定(reflectionなし、
     * PDCキーの有無だけ見る)。{@link #stampIfEligible} が「品質rollを行うかどうか」の分岐に使う —
     * 通常アイテム(圧倒的多数)については、この判定だけで早期returnし、reflectionを一切呼ばない。
     */
    static boolean hasArsThreadMarker(ItemMeta meta) {
        return meta.getPersistentDataContainer().has(ARS_THREAD_ITEM_TYPE_KEY, PersistentDataType.STRING);
    }

    /**
     * {@link ArsThreadQualityRestamper} の本番実装。ArsPaper 側 {@code ThreadItem#restampWithQuality}
     * (メソッド名/シグネチャは合わせて変更する契約。{@code GiveItemCommand#defaultThreadRestamp} と同じ
     * 契約対象)へ reflection 経由で委譲する。
     *
     * <p><b>なぜ必要か(W-53、根本原因2/2)</b>: 各スレッドは {@code item-stats.yml} に
     * {@code MATERIAL#cmd} の profile <b>を持つ</b>({@code socketed-only-stats: true} 等の
     * フラグだけの薄いentryだが、{@code ItemStatsConfig#load} は fixed/per-quality/random が空でも
     * 必ず {@code ItemStatProfile} を1件生成するので {@code profileFor(...)} は空にならない ——
     * 「スレッドは profile を持たない」という誤診で {@code itemStats.profileFor(...).isEmpty()} を
     * スレッド検出に使わないこと)。したがって {@code stampIfEligible} の通常ゲートは<b>スレッドも
     * 通してしまう</b>。問題は「品質rollへ到達できるか」ではなく「刻印を汎用
     * {@code ItemFactory#stamp}(lore 全体を {@code ItemAssembler#assemble} で再組み立てする経路)に
     * 通してよいか」——スレッドの lore はスレッド専用体裁(効果説明/スロット案内/バックパック行、
     * {@code ThreadItem#fullLore})なので、汎用stampをそのまま通すと上書きされる。そのため PDCマーカー
     * ({@link #hasArsThreadMarker})でスレッドだと判れば、{@code itemFactory.stamp} には絶対に
     * フォールスルーさせず、必ず {@code ThreadItem#restampWithQuality}(lore もスレッド専用体裁で
     * 組み直す)経由にするか、それが失敗したら無刻印のまま諦める({@link #stampIfEligible} 末尾参照)。
     */
    static boolean defaultArsThreadRestamp(ItemStack stack, int quality) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !hasArsThreadMarker(meta)) {
            return false;
        }
        String typeId = meta.getPersistentDataContainer()
                .get(ARS_THREAD_ITEM_TYPE_KEY, PersistentDataType.STRING);
        if (typeId == null) {
            return false;
        }
        try {
            Plugin ars = Bukkit.getPluginManager().getPlugin("ArsPaper");
            if (ars == null) {
                return false;
            }
            Object registry = ars.getClass().getMethod("getItemRegistry").invoke(ars);
            Object opt = registry.getClass().getMethod("get", String.class)
                    .invoke(registry, "thread_" + typeId);
            if (!(opt instanceof Optional<?> optional) || optional.isEmpty()) {
                return false;
            }
            Object customItem = optional.get();
            java.lang.reflect.Method restamp =
                    customItem.getClass().getMethod("restampWithQuality", ItemStack.class, int.class);
            return (boolean) restamp.invoke(customItem, stack, quality);
        } catch (ReflectiveOperationException ex) {
            Logger.getLogger(PickupQualityListener.class.getName())
                    .log(Level.FINE, "[pickup] ArsPaper thread quality restamp failed for " + typeId, ex);
            return false;
        }
    }

    /**
     * {@link ArsThreadQualityRestamper} 本体。{@code stack} が ArsPaper のスレッド(ThreadItem)なら
     * {@code quality} で再刻印して {@code true}、そうでなければ {@code false}
     * (呼び出し側は通常の {@code itemStats} ゲートへフォールバックする)。
     */
    @FunctionalInterface
    interface ArsThreadQualityRestamper {
        boolean restampIfThread(ItemStack stack, int quality);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            scheduleSweep(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleSweep(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleSweep(player);
        }
    }

    // InventoryCreativeEvent は InventoryClickEvent のサブクラスだが専用の HandlerList を持つため
    // onClick では拾えない。クリエイティブでのアイテム取得を刻印するために別ハンドラで受ける。
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleSweep(player);
        }
    }

    /**
     * Paper のスロット変化イベント: クリエイティブの中クリック複製・ホットバー差し替えなど、
     * InventoryCreativeEvent を飛ばす経路もここで拾う。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        scheduleSweep(event.getPlayer());
    }

    // チェスト等のコンテナを閉じた後の保険(コンテナ操作の取りこぼしを次tickでまとめて刻印)。
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scheduleSweep(player);
        }
    }

    /** 次tickに1回だけ当該プレイヤーのインベントリ走査を予約する(同一tickの重複はまとめる)。 */
    private void scheduleSweep(Player player) {
        UUID id = player.getUniqueId();
        if (!pendingSweep.add(id)) {
            return; // このtickでは既に予約済み
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingSweep.remove(id);
            if (player.isOnline()) {
                sweepInventory(player);
            }
        });
    }

    /**
     * プレイヤーのインベントリ(収納/ホットバー/防具/オフハンド)とカーソルを走査し、刻印対象を刻印して
     * <em>変化したスロットだけ</em> 書き戻す。変化が無ければ setItem を呼ばないので再送信によるちらつきは起きない。
     * package-private: テストから直接呼べるようにしている。
     */
    public void sweepInventory(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            // 品質 / catalog identity / owner は独立に成立しうるので、いずれか変化したら書き戻す。
            boolean identity = CatalogIdentity.ensure(stack, itemCatalog);
            boolean normalized = EmptyThreadStackNormalizer.normalize(stack, itemCatalog);
            boolean stamped = stampIfEligible(stack, player);
            boolean ownerStamped = stampOwnerIfEligible(stack, playerId);
            if (identity || normalized || stamped || ownerStamped) {
                inventory.setItem(slot, stack);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            boolean identity = CatalogIdentity.ensure(cursor, itemCatalog);
            boolean normalized = EmptyThreadStackNormalizer.normalize(cursor, itemCatalog);
            boolean stamped = stampIfEligible(cursor, player);
            boolean ownerStamped = stampOwnerIfEligible(cursor, playerId);
            if (identity || normalized || stamped || ownerStamped) {
                player.setItemOnCursor(cursor);
            }
        }
    }

    /**
     * {@code stack} が「未刻印」かつ「item-stats.ymlに設定のあるアイテム」であれば {@code quality.yml} の
     * split-normal 分布(mode=0、loot/creative 用ベース)で品質を決めて刻印し、{@code true} を返す。
     * {@link ItemStack#hasItemMeta()} が false のバニラ既定品(クリエイティブ取得など)も対象 —
     * {@code getItemMeta()} で組み立て可能なら刻印する。
     */
    boolean stampIfEligible(ItemStack stack, Player player) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        // 空スレッドは個体差を持たない消耗品。TF の品質刻印を入れると roll_seed が個体ごとに違い
        // スタックが割れる(W-68)。Ars 実体のまま揃える。
        if (EmptyThreadStackNormalizer.isEmptyThread(stack)) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        ItemData data = ItemData.of(meta);
        // 儀式クラフト成果物の「品質未決定」マーカー(2026-08-04): 台座の上に置かれた時点では回収者が
        // 確定していないので、最初にインベントリへ入ったこのプレイヤーの Ars鍛冶ステータスで品質を決め、
        // itemFactory.stamp でフル再組み立て(lore/属性)してからマーカーを剥がす。
        // 旧実装は儀式時点で PDC だけ書いて再組み立てを呼んでいなかったため「手に持つまでステータスが
        // つかない」不具合になっていた。詳細は PdcKeys#ITEM_PENDING_CRAFT_QUALITY。
        // craftQuality == null(この経路を配線していない構成/テスト)のときは印を残したまま素通りし、
        // 下の通常ドロップ品経路に任せる ── 品質が付かないより loot 分布で付く方がまし。
        if (data.pendingCraftQuality() && craftQuality != null) {
            int crafted = craftQuality.rollArsSmithingQuality(player, stack);
            data.setCraftRollMods(craftQuality.craftRollMods(player));
            data.clearPendingCraftQuality();
            stack.setItemMeta(meta);
            itemFactory.stamp(stack, ThreadLocalRandom.current().nextLong(), crafted);
            return true;
        }
        if (data.hasRollSeed()) {
            // Prepare プレビュー刻印は「刻印済み」扱いだと永久に残る — 品質を保ったまま本物seedへ差し替え。
            Optional<Long> seed = data.rollSeed();
            if (seed.isEmpty() || !PreviewRollSeeds.isPreview(seed.get())) {
                return false;
            }
            stack.setItemMeta(meta);
            itemFactory.stamp(stack, ThreadLocalRandom.current().nextLong(), data.quality());
            return true;
        }
        Integer cmd = DerivedItemStats.customModelDataOf(meta);
        // ArsPaper のスレッド(ThreadItem)は実は item-stats.yml に profile を持つ(各 MATERIAL#CMD の
        // socketed-only-stats/offhand-stats-apply フラグ行だけの薄いentryだが、
        // ItemStatsConfig#load はfixed/per-quality/randomが空でも常にItemStatProfileを1件生成するため、
        // itemStats.profileFor(...) は空にならない)。だから profileFor だけでは非スレッドと区別できない —
        // 区別が必要なのは「品質rollを行うか」ではなく「刻印を汎用 ItemFactory#stamp 経路に通してよいか」
        // のほう(W-53、根本原因2/2)。スレッドの lore はスレッド専用体裁
        // (ThreadItem#fullLore/equipmentStyleRollLore)なので、汎用stampの lore 再組み立て
        // (ItemAssembler#assemble)を直接通すと上書きされてしまう。そのため PDCマーカー
        // ({@link #hasArsThreadMarker})でスレッドを検出したら、成否に関わらず itemFactory.stamp
        // には絶対に流さず、必ず ThreadItem#restampWithQuality 経由(またはfail-openで無刻印)にする。
        boolean maybeArsThread = hasArsThreadMarker(meta);
        boolean hasStatsProfile = itemStats.profileFor(stack.getType(), cmd).isPresent();
        if (!hasStatsProfile && !maybeArsThread) {
            return false;
        }
        int tierCount = qualityTiers.tiers().size();
        if (tierCount <= 0) {
            return false;
        }
        int maxQuality = quality.maxQuality();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // 品質基準値 (item-stats quality-mode-offset, 2026-07-23 stat-gate-overhaul §6.6 適用拡大):
        // 幸運由来のmode加算に、このアイテムの基準値オフセットを加える。スレッドの薄いprofileエントリは
        // quality-mode-offset を書いていないので、qualityModeOffsetFor は既定の0を返す
        // (=幸運由来のmode加算だけが乗る)。
        int mode = quality.lootBaseQuality() + lootLuck.qualityModeBonus(player, rng)
                + itemStats.qualityModeOffsetFor(stack.getType(), cmd);
        int rolled = CraftQualityPolicy.resolveQualityNormal(
                mode,
                rng.nextGaussian(),
                quality.spreadUp(),
                quality.spreadDown(),
                maxQuality);
        if (maybeArsThread) {
            // スレッドは成否に関わらずここで確定させる — hasStatsProfileがtrueでも(実際は常にtrue)
            // 下の汎用itemFactory.stampへは絶対にフォールスルーさせない(スレッド専用loreの上書き防止)。
            // restamperがfalse(ArsPaper未ロード/reflection失敗)を返したら無刻印のまま諦める
            // (品質が付かないより安全 — 汎用stampで実害あるlore破壊を起こすよりまし)。
            return arsThreadRestamper.restampIfThread(stack, rolled);
        }
        if (!hasStatsProfile) {
            return false;
        }
        // Ensure meta is attached before stamp (creative default stacks may lack hasItemMeta()).
        stack.setItemMeta(meta);
        itemFactory.stamp(stack, ThreadLocalRandom.current().nextLong(), rolled);
        return true;
    }

    /**
     * {@code stack} が「owner未設定」かつ「bindTypeが{@link BindType#SOULBOUND}」であれば、
     * {@code playerId} を owner としてスタンプし {@code true} を返す(呼び出し側が書き戻す)。
     * {@link BindType#OWNER_BOUND} はコマンド専用のため自動スタンプしない。
     * {@link BindType#TRADEABLE}・bindType未設定・既にowner設定済みは対象外(冪等)。
     */
    boolean stampOwnerIfEligible(ItemStack stack, UUID playerId) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        ItemData data = ItemData.of(meta);
        if (data.owner().isPresent()) {
            return false;
        }
        Optional<BindType> bindType = data.bindType();
        if (bindType.isEmpty() || !bindType.get().autoStampsOwner()) {
            return false;
        }
        Optional<Long> rollSeed = data.rollSeed();
        if (rollSeed.isEmpty()) {
            // 未刻印(rollSeed無し)はまだ品質刻印もされていない=再組立ての基準が無い。次tickのスイープで
            // stampIfEligible が先に刻印してから拾われる。
            return false;
        }
        data.setOwner(playerId);
        stack.setItemMeta(meta);
        itemFactory.stamp(stack, rollSeed.get(), data.quality());
        return true;
    }
}
