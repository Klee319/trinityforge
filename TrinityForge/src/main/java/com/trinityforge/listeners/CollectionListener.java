package com.trinityforge.listeners;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PlayerData;
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
 *
 * <p><b>K-11 (2026-07-31): 素のバニラ品が1件も記録されていなかった</b>。上の Material 分岐を
 * 足したとき、PDC を読むための前置条件だった {@code !stack.hasItemMeta()} 早期 return を
 * {@code catalogIdOf} の冒頭に残したままだった。実機の {@code CraftItemStack#hasItemMeta()} は
 * 「データコンポーネントの patch(既定値からの差分)が空でないか」を見るので、ルートチェストから
 * 出た無傷の {@code ELYTRA} / {@code TOTEM_OF_UNDYING} / {@code ECHO_SHARD} などは <b>false</b> になり、
 * Material 分岐に到達できない。結果 {@code collection.yml} の {@code items.structure} 16件が
 * 永久に錠前で、{@code goal_completionist}(percent: 100)が構造的に達成不能だった。
 * {@link com.trinityforge.combat.ProjectileWeapon#store} で一度直したのと<b>同じ轍</b>
 * (bare-material の弓を {@code hasItemMeta()} で落としていた High bug)の再発。
 *
 * <p>直し方として「Material 判定を PDC 判定より前へ出す」のは<b>誤り</b>。`materials.yml` の
 * {@code warden_tendril} / {@code reality_thread_core} は base が {@code ECHO_SHARD}、
 * {@code source_condenser} は {@code HEART_OF_THE_SEA} で、どちらの Material も図鑑に載っている。
 * Material を先に見るとこのカスタム3件が {@code item:ECHO_SHARD} 等に潰れて新たに到達不能になり、
 * かつ意図的に監視外にした圧縮品({@code echo_shard_1x} 等)を持つだけで枠が無料で埋まる。
 * 正しい形は「meta ゲートを早期 return から分岐セレクタへ変え、Material 判定を meta 無しスタックの
 * fallback にする」こと。分岐順序が本質なので、順序は {@link #resolveEntryId} に純関数として
 * 切り出してある(MockBukkit では本番条件を再現できないため。理由は同メソッドの javadoc)。
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
            scanInventory(player, true);
        }
    }

    /**
     * 参加時の全スロット走査。<b>プレイヤーごとに初回だけ「遡り登録」として静かに行う</b>
     * (2026-07-31, K-11 の副作用対策)。
     *
     * <p>K-11 の修正で、既にインベントリに入っている素のバニラ品が一斉に記録可能になる。
     * 通常経路のまま通すと 1件ごとの「図鑑に登録」チャットが最大16行流れ、さらに
     * {@code reward-tiers} の t3(60)/t4(120)/t5(200) を跨いだプレイヤーの分だけ
     * {@code broadcast: true} のサーバー全体告知が連続発火して<b>事故に見える</b>。
     * そのため初回走査だけは通知を抑止し(報酬そのものは通常どおり付与する)、
     * 2回目以降の参加と、拾得・インベントリ操作による新規登録は従来どおり通知する。
     *
     * <p>フラグは走査が実際に走った回にだけ立てる。{@code catalog-items: false} の間に
     * 消費してしまうと、後から有効化したときの追い付き分が通知付きで流れてしまう。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.catalogItemsEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        PlayerData data = PlayerData.of(player);
        boolean retroactive = !data.collectionBackfillDone();
        scanInventory(player, !retroactive);
        if (retroactive) {
            data.markCollectionBackfillDone();
        }
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

    /**
     * @param announce false = 遡り登録。通知(1件ごとのチャットと報酬ティアの全体ブロードキャスト)を
     *                 抑止する。{@link #onJoin} の初回走査だけが false を渡す。
     */
    private void scanInventory(Player player, boolean announce) {
        if (!config.catalogItemsEnabled()) {
            return;
        }
        // 監視集合はスロットごとに組み直さない。K-11 の修正後は素のバニラ品も全部この経路を
        // 通るため、41スロット × 全エントリの Material.matchMaterial が毎回走ってしまう。
        Set<String> watched = watchedConfigIds();
        Map<String, Integer> ids = new LinkedHashMap<>();
        // getContents() はメイン36 + 防具4 + オフハンドの全スロットを含む。
        for (ItemStack stack : player.getInventory().getContents()) {
            catalogIdOf(stack, watched).ifPresent(id -> {
                String entryId = CollectionService.itemEntryId(id);
                int quality = qualityOf(stack);
                ids.merge(entryId, quality, Math::max);
            });
        }
        if (!ids.isEmpty()) {
            service.record(player, ids, announce);
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
        return catalogIdOf(stack, watchedConfigIds());
    }

    /**
     * {@link ItemStack} から「判定に必要な事実」を取り出して {@link #resolveEntryId} に渡すだけの
     * 薄いアダプタ。分岐順序はここに書かない。
     *
     * <p>{@code hasItemMeta()} が false のときは {@code getItemMeta()} を<b>呼ばない</b>。
     * meta を持たないスタックには PDC も CustomModelData も存在しえないし、CraftBukkit の
     * {@code getItemMeta()} は毎回 {@code CraftMetaItem} を新規生成するので、41スロット走査の
     * 前置フィルタとして性能上の意味もある(この性能上の意味が、K-11 で早期 return が
     * メソッド冒頭に残り続けた理由でもある)。
     */
    private Optional<String> catalogIdOf(ItemStack stack, Set<String> watched) {
        if (stack == null || stack.getType().isAir()) {
            return Optional.empty();
        }
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        return resolveEntryId(stack.getType().name(), meta != null,
                meta == null ? null : metaFactsOf(stack, meta), watched);
    }

    /**
     * 図鑑エントリIDの解決本体。<b>この分岐順序が K-11 の本質</b>なので、{@link ItemStack} から
     * 切り離して純関数にしてある。
     *
     * <p><b>なぜ純関数に切り出す必要があるのか</b>: MockBukkit 4.110.0 の
     * {@code ItemStackMock} はコンストラクタで {@code itemMeta} を無条件に代入し、
     * {@code hasItemMeta()} は {@code itemMeta != null && !ItemFactoryMock.equals(itemMeta, null)}
     * (中身は {@code Objects.equals}) を返すため、<b>素のスタックでも常に true</b> になる。
     * 実機の {@code CraftItemStack#hasItemMeta()} は {@code getComponentsPatch().isEmpty()} を
     * 見るので無傷のバニラ品は false。つまり MockBukkit を通したテストでは
     * <b>本番で壊れているコードが緑になる</b>(K-11 が実際にそうだった。SKIPPED 素通りと違い
     * アサーションが通ってしまうので更に危険)。さらに偽装したスタックを
     * {@code Inventory#setItem/addItem} や {@code Item} エンティティ経由で渡すと
     * すべて {@code ItemStack.clone()}(= {@code craftDelegate.clone()})やミラー包みで
     * ラッパのクラスが消えるため、インベントリ経路では本番条件を作れない。
     * <b>meta の有無を引数で受けるこの純関数だけが、MockBukkit に一切依存せずに
     * 「meta 無しスタック」の分岐を検証できる。</b>
     *
     * @param materialName スタックの Material 名(大文字)。AIR/null はアダプタ側で弾いている
     * @param hasItemMeta  実機 {@code CraftItemStack#hasItemMeta()} 相当。
     *                     <b>false は「記録対象外」ではなく「Material 判定だけで決まる」</b>という意味
     * @param facts        meta 由来の事実。{@code hasItemMeta} が false なら参照されない({@code null} 可)
     * @param watched      {@link #watchedConfigIds()} の監視集合
     */
    static Optional<String> resolveEntryId(String materialName, boolean hasItemMeta,
                                          MetaFacts facts, Set<String> watched) {
        if (!hasItemMeta) {
            // 素のバニラ品。PDC も CMD も存在しえないので Material 判定だけで決まる。
            // ここを早期 return(= Optional.empty())にしていたのが K-11。
            return trackedVanillaId(materialName, watched);
        }
        Optional<String> stamped = facts.stampedCatalogId();
        if (stamped.isPresent()) {
            return stamped;
        }
        // ArsPaper 側で定義したアイテム。2026-07-31 まで見ていなかったため、materials.yml /
        // sourcejars.yml 由来のアイテム(モブドロップ素材17件・ダンジョン踏破の証22件・
        // ソースの階梯9件 = 図鑑カテゴリ116件中48件)が永久に記録されず、図鑑に
        // 「絶対に埋まらない枠」として並び続けていた。
        //
        // この2つの PDC 判定は必ず Material 判定より先。warden_tendril / reality_thread_core は
        // base が ECHO_SHARD、source_condenser は HEART_OF_THE_SEA で、どちらの Material も
        // items.structure に載っているので、順序を逆にするとカスタム側が潰れる。
        Optional<String> arsId = facts.arsItemId();
        if (arsId.isPresent()) {
            // バニラ Material と同じく設定から参照されているIDだけに絞る。Ars の登録アイテムは
            // グリフ120件を含めて300件超あり、無条件に記録するとプレイヤーPDCがそれだけ膨らみ、
            // かつ図鑑の報酬ティア(10/30/60/120/200件)の重みが黙って変わってしまう。
            return watched.contains(arsId.get()) ? arsId : Optional.empty();
        }
        Integer cmd = facts.customModelData();
        if (cmd == null) {
            return trackedVanillaId(materialName, watched);
        }
        Optional<String> template = facts.catalogTemplateId(cmd);
        return template.isPresent() ? template : trackedVanillaId(materialName, watched);
    }

    /**
     * meta を持つスタックから読める事実の遅延アクセサ。{@link #resolveEntryId} を
     * {@link ItemStack} から切り離すための seam であり、meta を持たないスタックでは
     * どのメソッドも呼ばれない。遅延にしているのは、上位の判定で決着したときに
     * 下位のルックアップ(カタログ照合など)を走らせないため。
     */
    interface MetaFacts {

        /** TF カタログの PDC 刻印 ({@code trinityforge:catalog_id})。 */
        Optional<String> stampedCatalogId();

        /** ArsPaper の PDC 刻印 ({@code arspaper:custom_item_id})。 */
        Optional<String> arsItemId();

        /** CustomModelData。未設定なら {@code null}。 */
        Integer customModelData();

        /** material + CustomModelData がカタログテンプレートに一致すればそのID。 */
        Optional<String> catalogTemplateId(int customModelData);
    }

    private MetaFacts metaFactsOf(ItemStack stack, ItemMeta meta) {
        return new MetaFacts() {
            @Override
            public Optional<String> stampedCatalogId() {
                return CatalogIdentity.catalogIdOf(meta);
            }

            @Override
            public Optional<String> arsItemId() {
                return CrossPluginItemResolver.arsIdOf(stack);
            }

            @Override
            public Integer customModelData() {
                return DerivedItemStats.customModelDataOf(meta);
            }

            @Override
            public Optional<String> catalogTemplateId(int customModelData) {
                return CatalogIdentity.find(catalog, stack.getType(), customModelData).map(t -> t.id());
            }
        };
    }

    /** 設定から参照されている Material だけ {@code item:<MATERIAL>} として図鑑に載せる。 */
    private static Optional<String> trackedVanillaId(String materialName, Set<String> watched) {
        if (materialName == null || materialName.isBlank()) {
            return Optional.empty();
        }
        return watched.contains(materialName) ? Optional.of(materialName) : Optional.empty();
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
