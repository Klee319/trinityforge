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
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
 *
 * <p><b>K-11 の副作用として入れた3つのガード (2026-07-31)</b>:
 * <ul>
 *   <li>クリエイティブ/スペクテイターを記録対象外にする({@link #excluded})。
 *       {@code items.structure} の16件が全部クリエイティブインベントリから出せる素のバニラ品なので、
 *       除外しないと図鑑と報酬ティアが無料で埋まる。</li>
 *   <li>遡り登録の通知抑止は「実際に1件以上記録した走査」だけがフラグを消費する
 *       ({@link #scanInventory})。参加時の走査だけを静かにしても、遺物をチェストへ
 *       しまっているプレイヤーには効かない。</li>
 *   <li>監視集合は config snapshot の同一性でキャッシュする({@link #watchedConfigIds()})。
 *       {@link #onPickup} が拾得1件ごとに約150エントリを走査していた。</li>
 * </ul>
 */
public final class CollectionListener implements Listener {

    /** editor が custom アイテムに付ける接頭辞。監視集合を作るときに落とす。 */
    private static final String CUSTOM_PREFIX = "custom:";

    /** 自プラグイン名。参加時走査を遅延させるスケジューラを引くためだけに使う。 */
    private static final String OWN_PLUGIN_NAME = "TrinityForge";

    /**
     * 参加時走査を遅らせる tick 数(2秒)。詳細は {@link #onJoin}。HuskSync の snapshot 適用が
     * 終わるのを待つのが目的なので、DB/Redis の往復に十分な余裕を持たせている。
     */
    private static final long JOIN_SCAN_DELAY_TICKS = 40L;

    private final CollectionConfig config;
    private final CollectionService service;
    private final ItemCatalogConfig catalog;
    private final AchievementsConfig achievements;

    /** 参加時走査の遅延に使う。null = 遅延せず即時走査(サーバ未起動の単体テスト)。 */
    private final Plugin plugin;

    /**
     * 監視集合のキャッシュ。<b>config snapshot のインスタンス同一性で無効化する</b>ので、
     * 明示的に捨てる呼び出しは要らない(捨て忘れると「editor で図鑑を編集しても反映されない」
     * という別のバグになるため、忘れようのない形にしてある)。詳細は {@link #watchedConfigIds()}。
     */
    private volatile WatchedSnapshot watchedCache;

    /**
     * @param categories   キャッシュ作成時点の {@code config.itemCategories()} インスタンス
     * @param achievements キャッシュ作成時点の {@code achievements.achievements()} インスタンス
     * @param watched      上記2つから組んだ監視集合(不変)
     */
    private record WatchedSnapshot(List<CollectionConfig.Category> categories,
                                   List<AchievementsConfig.Achievement> achievements,
                                   Set<String> watched) {
    }

    public CollectionListener(CollectionConfig config, CollectionService service,
                              ItemCatalogConfig catalog, AchievementsConfig achievements) {
        this(config, service, catalog, achievements, resolveOwnPlugin());
    }

    /**
     * @param plugin 参加時走査を遅延させるためのプラグイン。{@code null} なら遅延せず即時走査する
     *               (サーバが立っていない単体テスト用の逃げ道)。
     */
    public CollectionListener(CollectionConfig config, CollectionService service,
                              ItemCatalogConfig catalog, AchievementsConfig achievements,
                              Plugin plugin) {
        this.config = Objects.requireNonNull(config, "config");
        this.service = Objects.requireNonNull(service, "service");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.achievements = Objects.requireNonNull(achievements, "achievements");
        this.plugin = plugin;
    }

    /**
     * 自プラグインをプラグインマネージャから引く。配線(TrinityForge#onEnable)を変えずに
     * 参加時走査の遅延を効かせるための経路で、明示的に Plugin を渡す構築の方が望ましい。
     * サーバが未初期化(MockBukkit を使わない単体テスト)なら {@code null} を返す。
     */
    private static Plugin resolveOwnPlugin() {
        if (Bukkit.getServer() == null) {
            return null;
        }
        return Bukkit.getPluginManager().getPlugin(OWN_PLUGIN_NAME);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!config.catalogItemsEnabled() || !(event.getEntity() instanceof Player player)
                || excluded(player)) {
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

    /**
     * 参加時の全スロット走査。<b>数十tick遅らせてから</b>行う(2026-07-31)。
     *
     * <p><b>なぜ遅延が必要か</b>: 資源サーバ分離構成ではプレイヤーのインベントリと PDC を
     * HuskSync が同期しており、<b>snapshot の適用は {@link PlayerJoinEvent} より後に起きうる</b>
     * (DB/Redis からの取得を待つため)。参加した瞬間に読むと
     * <ul>
     *   <li>まだ前サーバの分が入っていないインベントリを走査してしまい、</li>
     *   <li>遡り登録フラグ({@code PLAYER_COLLECTION_BACKFILL_DONE})も同期前の値で読む</li>
     * </ul>
     * ことになる。後者は「毎回の参加が遡り扱いになる」＝新規登録の通知が永久に出ない形で
     * 黙って壊れる。走査を遅らせれば、読むのも書くのも snapshot 適用後になる。
     *
     * <p>通知の抑止条件は走査側({@link #scanInventory})に寄せてあるので、この経路と
     * インベントリ閉時の経路で挙動は同じになる。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.catalogItemsEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin == null) {
            scanInventory(player);
            return;
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                scanInventory(player);
            }
        }, JOIN_SCAN_DELAY_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        if (!config.mobKillsEnabled()) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer == null || event.getEntity() instanceof Player || excluded(killer)) {
            return;
        }
        service.record(killer, Set.of(CollectionService.mobEntryId(event.getEntityType().name())));
    }

    /**
     * インベントリ全スロットの差分登録。
     *
     * <p><b>通知の抑止(遡り登録)</b>: K-11 の修正で「既に持っていた素のバニラ品」が一斉に
     * 記録可能になったため、そのままだと1件ごとの「図鑑に登録」チャットが最大16行流れ、
     * {@code reward-tiers} の t3(60)/t4(120)/t5(200) を跨いだ分だけ {@code broadcast: true} の
     * サーバー全体告知が連続発火して<b>事故に見える</b>。そこで
     * <b>「そのプレイヤーで最初に1件以上記録した走査」だけを静かに行う</b>
     * (報酬そのものは通常どおり付与する)。
     *
     * <p>フラグを「初回参加の走査」で消費してはいけない。遺物系(エリトラ/トーテム/レコード/
     * バナー模様)はチェストやエンダーチェストにしまってあることが多く、初回参加の走査は
     * <b>0件で終わってフラグだけ焼かれる</b>。すると後でチェストから出した瞬間に、抑止したかった
     * 通知の束がそのまま出る。<b>記録が発生した走査だけがフラグを消費する</b>ことで、
     * 参加時でもインベントリ閉時でも「最初のまとまった追い付き分」を静かに通せる。
     */
    private void scanInventory(Player player) {
        if (!config.catalogItemsEnabled() || excluded(player)) {
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
        if (ids.isEmpty()) {
            return;
        }
        PlayerData data = PlayerData.of(player);
        boolean retroactive = !data.collectionBackfillDone();
        int newlyAdded = service.record(player, ids, !retroactive);
        if (retroactive && newlyAdded > 0) {
            // 実際に記録が発生した走査だけがフラグを消費する。品質ptの更新だけ(newlyAdded == 0)や
            // 既知エントリしか無かった走査では消費しない — 抑止したい「まとまった追い付き」は
            // まだ来ていないため。
            data.markCollectionBackfillDone();
        }
    }

    /**
     * 図鑑への記録対象外となるゲームモード。TF の進行系は
     * {@code EquipmentDurabilityService} / {@code ChainBreakSupport} /
     * {@code ArsMagicExperienceListener} / {@code BreedingBonusListener} /
     * {@code GatheringExtraDropListener} / {@code NativeSkillExperienceListener} と
     * 同じ流儀でクリエイティブ/スペクテイターを外す。
     *
     * <p>図鑑では特に重要で、{@code collection.yml} の {@code items.structure} 16件
     * (ECHO_SHARD / DRAGON_EGG / ELYTRA / TOTEM_OF_UNDYING / SNIFFER_EGG …)は
     * <b>全部クリエイティブインベントリから1クリックで取り出せる素のバニラ品</b>。
     * 除外しないと並べてインベントリを閉じるだけで16件が一括登録され、報酬ティアの
     * t1(10)/t2(30) を無条件に跨ぎ t3(60) の全体ブロードキャストにも寄る
     * ({@code ops/RUNBOOK.md} が「メインの world は creative」と書いているので想定外の環境ではない)。
     * 討伐側も同じで、クリエイティブなら任意のモブを即殺できる。
     */
    private static boolean excluded(Player player) {
        GameMode gm = player.getGameMode();
        return gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
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
     * <p><b>キャッシュの無効化は config snapshot のインスタンス同一性で行う</b>(2026-07-31)。
     * {@code CollectionConfig#itemCategories} と {@code AchievementsConfig#achievements} は
     * どちらも volatile な snapshot をそのまま返し、reload はその<b>リストを丸ごと差し替える</b>ので、
     * 参照が変わっていなければ内容も変わっていない。
     *
     * <p>キャッシュが必要な理由: {@link #onPickup} は MONITOR で<b>拾得1件ごとに</b>走るのに、
     * この集合は約120件の {@code itemCategories} エントリと36件のアチーブメントを走査し、
     * 各エントリで {@code Material.matchMaterial}(内部で正規化のため {@code Pattern.compile} が
     * 2回走る)を呼ぶ。連鎖採掘の落下物回収・モブファーム・複数人同時で 1〜3ms/tick が消える。
     *
     * <p><b>「reload で捨てる」を明示的な呼び出しにしないこと</b>: 捨て忘れると
     * 「editor で図鑑を編集して保存しても反映されない」という、エラーの出ない別のバグになる。
     * snapshot の同一性で判定すれば忘れようがない。
     */
    private Set<String> watchedConfigIds() {
        List<CollectionConfig.Category> categories = config.itemCategories();
        List<AchievementsConfig.Achievement> achievementList = achievements.achievements();
        WatchedSnapshot cached = watchedCache;
        if (cached != null && cached.categories() == categories
                && cached.achievements() == achievementList) {
            return cached.watched();
        }
        Set<String> watched = buildWatchedConfigIds(categories, achievementList);
        watchedCache = new WatchedSnapshot(categories, achievementList, watched);
        return watched;
    }

    /** 監視集合の組み立て本体。戻り値は不変(キャッシュとして共有されるため)。 */
    private static Set<String> buildWatchedConfigIds(
            List<CollectionConfig.Category> categories,
            List<AchievementsConfig.Achievement> achievementList) {
        Set<String> watched = new LinkedHashSet<>();
        for (CollectionConfig.Category category : categories) {
            category.entries().forEach(entry -> addWatched(watched, entry));
        }
        for (AchievementsConfig.Achievement achievement : achievementList) {
            AchievementsConfig.Trigger trigger = achievement.trigger();
            if (trigger == null || !"item".equals(trigger.collectionScope())) {
                continue;
            }
            trigger.collectionTargets().forEach(target -> addWatched(watched, target));
        }
        return Set.copyOf(watched);
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
