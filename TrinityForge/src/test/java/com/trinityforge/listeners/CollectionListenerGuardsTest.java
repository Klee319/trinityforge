package com.trinityforge.listeners;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.CollectionService;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 図鑑記録のガード(2026-07-31、Wave 1 の反証レビュー指摘とその再レビュー指摘)。
 *
 * <p><b>再レビューで足したもの</b>:
 * <ul>
 *   <li><b>クリエイティブ由来マーカー</b>: 下の「クリエイティブ除外」はゲームモードしか見ないので、
 *       <b>クリエイティブで並べてサバイバルで走査させる</b>経路(HuskSync がインベントリだけ同期する
 *       構成では {@code /server} で移るだけで成立)を塞げず、図鑑16件が<b>チャット0行で</b>埋まっていた。
 *       出自をアイテムPDCに刻んで走査から外す。</li>
 *   <li><b>拾得も遡り登録の抑止に揃える</b>: 抑止が走査経路だけだと、カーソル経由で地面へ落ちて
 *       拾い直しになる取り出し操作がすり抜け、通知の出方が経路依存になっていた。</li>
 *   <li><b>プラグイン名の文字列引き</b>: 本番が通る経路(4引数コンストラクタ)と
 *       {@code paper-plugin.yml} の name 一致を固定する。</li>
 *   <li><b>achievements 側のキャッシュ無効化</b>: 監視集合のキャッシュキーは2本あるのに、
 *       検証していたのは categories 側だけだった。</li>
 * </ul>
 *
 * <p><b>Wave 1 で入れたガード</b>:
 * <ul>
 *   <li><b>クリエイティブ除外</b>: K-11 の修正で素のバニラ Material が記録対象になった副作用として、
 *       {@code collection.yml} の {@code items.structure} 16件(ECHO_SHARD / ELYTRA /
 *       TOTEM_OF_UNDYING …)が<b>全部クリエイティブインベントリから1クリックで出せる品</b>に
 *       なってしまった。並べてインベントリを閉じるだけで16件が一括登録され、報酬ティアの
 *       t1(10)/t2(30) を無条件に跨ぎ t3(60) の全体ブロードキャストにも寄る。
 *       {@code ops/RUNBOOK.md} が「メインの world は creative」と書いているので想定外の環境ではない。</li>
 *   <li><b>遡り登録フラグの消費条件</b>: フラグを「初回参加の走査」で消費すると、遺物系
 *       (エリトラ/トーテム/レコード/バナー模様)をチェストへしまっているプレイヤーでは
 *       走査が0件で終わってフラグだけ焼かれ、<b>後で取り出した瞬間に最大16行のチャットと
 *       全体ブロードキャストが出る</b>。消費は「実際に1件以上記録した走査」に限る。</li>
 *   <li><b>監視集合のキャッシュ</b>: {@code onPickup} は MONITOR で拾得1件ごとに走るのに、
 *       監視集合(約120件の itemCategories + 36件のアチーブメント、各エントリで
 *       {@code Material.matchMaterial})を毎回組み直していた。config snapshot が入れ替わったら
 *       必ず作り直すこと(捨て忘れると「editor で図鑑を編集しても反映されない」別のバグになる)。</li>
 *   <li><b>参加時走査の遅延</b>: HuskSync は {@code PlayerJoinEvent} より後に snapshot を適用する
 *       ことがあるので、参加時にインベントリと PDC を読むと同期前の値を見うる。</li>
 * </ul>
 */
class CollectionListenerGuardsTest {

    /** 参加時走査の遅延 tick 数(本体の {@code JOIN_SCAN_DELAY_TICKS} と揃える)。 */
    private static final long JOIN_SCAN_DELAY_TICKS = 40L;

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * for-each された回数を数える List。{@code config.itemCategories()} の戻り値として使うと
     * 「監視集合を何回組み直したか」が観測できる。<b>同一インスタンスを返し続ける</b>ことが
     * キャッシュの前提なので、getter 呼び出し回数ではなく走査回数を数える必要がある。
     */
    private static final class CountingList<E> extends AbstractList<E> {

        private final List<E> delegate;
        private int iterations;

        CountingList(List<E> delegate) {
            this.delegate = List.copyOf(delegate);
        }

        @Override
        public Iterator<E> iterator() {
            iterations++;
            return delegate.iterator();
        }

        @Override
        public E get(int index) {
            return delegate.get(index);
        }

        @Override
        public int size() {
            return delegate.size();
        }
    }

    /** config mock を握ったままにして、監視集合の組み直し回数と差し替えを観測できるようにする。 */
    private static final class Fixture {

        private final CollectionConfig config;
        private final AchievementsConfig achievements;
        private final CollectionListener listener;
        private CountingList<CollectionConfig.Category> categories;

        Fixture(Plugin plugin) {
            this.config = mock(CollectionConfig.class);
            when(config.enabled()).thenReturn(true);
            when(config.catalogItemsEnabled()).thenReturn(true);
            when(config.mobKillsEnabled()).thenReturn(true);
            when(config.tiers()).thenReturn(List.of());
            setWatched("DIAMOND");

            this.achievements = mock(AchievementsConfig.class);
            when(achievements.achievements()).thenReturn(List.of());

            ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
            when(catalog.all()).thenReturn(java.util.Map.of());

            CollectionService service = new CollectionService(config,
                    Logger.getLogger("CollectionListenerGuardsTest"));
            this.listener = plugin == null
                    ? new CollectionListener(config, service, catalog, achievements)
                    : new CollectionListener(config, service, catalog, achievements, plugin);
        }

        /** 図鑑カテゴリを差し替える(= reload と同じく snapshot のインスタンスが入れ替わる)。 */
        void setWatched(String... entries) {
            categories = new CountingList<>(List.of(
                    new CollectionConfig.Category("structure", "遺物", 1, List.of(entries))));
            when(config.itemCategories()).thenReturn(categories);
        }

        /**
         * アチーブメント側の監視対象({@code trigger.collection.scope: item} の targets)を差し替える。
         * reload と同じく snapshot のリストインスタンスが丸ごと入れ替わる。
         */
        void setWatchedAchievementTargets(String... targets) {
            AchievementsConfig.Trigger trigger = new AchievementsConfig.Trigger(
                    AchievementsConfig.TriggerType.STATIC, null,
                    AchievementsConfig.StatisticQualifier.NONE, 0, null,
                    "item", List.of(targets), false);
            AchievementsConfig.Rewards rewards = new AchievementsConfig.Rewards(
                    List.of(), List.of(), List.of(), 0, List.of(), java.util.Map.of());
            when(achievements.achievements()).thenReturn(List.of(new AchievementsConfig.Achievement(
                    "collector", "収集家", trigger, false, rewards)));
        }
    }

    private static Fixture fixture() {
        return new Fixture(null);
    }

    /**
     * 実機で {@code hasItemMeta()} が false になる「素のバニラ品」。MockBukkit の
     * {@code ItemStackMock} はコンストラクタで {@code itemMeta} を無条件に代入するので
     * 素のスタックでも true になり、本番条件をそのままでは表現できない
     * (詳細は {@code CollectionArsItemRecordingTest#bareVanilla})。
     */
    private static ItemStack bareVanilla(Material material) {
        return new ItemStack(material) {
            @Override
            public boolean hasItemMeta() {
                return false;
            }
        };
    }

    /** 偽装スタックを拾得経路へ直接渡す({@code Item} は interface なので clone されない)。 */
    private static void pickup(CollectionListener listener, Player player, ItemStack stack) {
        Item drop = mock(Item.class);
        when(drop.getItemStack()).thenReturn(stack);
        listener.onPickup(new EntityPickupItemEvent(player, drop, 0));
    }

    private static void closeInventory(CollectionListener listener, Player player) {
        listener.onInventoryClose(new InventoryCloseEvent(player.getOpenInventory()));
    }

    private static void join(CollectionListener listener, Player player) {
        listener.onJoin(new PlayerJoinEvent(player, Component.empty()));
    }

    private static boolean recorded(Player player, String entryId) {
        return PlayerData.of(player).collectionEntries().stream()
                .map(com.trinityforge.progression.CollectionRecord::parse)
                .filter(java.util.Objects::nonNull)
                .anyMatch(r -> r.id().equals("item:" + entryId));
    }

    private static List<String> drainMessages(Player player) {
        List<String> out = new ArrayList<>();
        String message;
        while ((message = ((org.mockbukkit.mockbukkit.entity.PlayerMock) player).nextMessage()) != null) {
            out.add(message);
        }
        return out;
    }

    private static boolean announced(Player player) {
        return drainMessages(player).stream().anyMatch(m -> m.contains("図鑑に登録"));
    }

    /** クリエイティブ由来マーカーが刻まれているか。 */
    private static boolean creativeOrigin(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        return meta != null && ItemData.of(meta).creativeOrigin();
    }

    /**
     * クリエイティブのアイテム生成(SetCreativeModeSlot パケット相当)を1件流す。
     *
     * <p>view を Mockito でモックするのは、MockBukkit の {@code InventoryView#convertSlot} が
     * <b>未実装</b>で、{@code InventoryClickEvent} のコンストラクタがそれを呼ぶため
     * ({@code UnimplementedOperationException} は {@code TestAbortedException} を継承するので、
     * そのままだとテストが FAILED ではなく<b>SKIPPED に化けて素通りする</b>)。
     * {@code InventoryCreativeEvent#getCursor}/{@code setCursor} は自前フィールドを読み書きするので
     * view には一切依存しない ── スタブが必要なのは convertSlot の1メソッドだけ。
     */
    private static InventoryCreativeEvent creativeSet(CollectionListener listener, ItemStack newItem) {
        org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
        when(view.convertSlot(0)).thenReturn(0);
        InventoryCreativeEvent event = new InventoryCreativeEvent(view,
                InventoryType.SlotType.CONTAINER, 0, newItem);
        listener.onCreativeSet(event);
        return event;
    }

    // --- 5-1: クリエイティブ/スペクテイター除外 ---

    @Test
    @DisplayName("クリエイティブの拾得は図鑑に記録しない")
    void creativePickupIsNotRecorded() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);

        pickup(f.listener, player, bareVanilla(Material.DIAMOND));

        assertFalse(recorded(player, "DIAMOND"),
                "items.structure の16件は全部クリエイティブインベントリから1クリックで出せる素のバニラ品なので、"
                        + "除外しないと図鑑と報酬ティアが無料で埋まる");
    }

    @Test
    @DisplayName("スペクテイターのインベントリ走査は図鑑に記録しない")
    void spectatorInventoryScanIsNotRecorded() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));
        player.setGameMode(GameMode.SPECTATOR);

        closeInventory(f.listener, player);

        assertFalse(recorded(player, "DIAMOND"));
    }

    @Test
    @DisplayName("クリエイティブのモブ討伐も図鑑に記録しない")
    void creativeMobKillIsNotRecorded() {
        Fixture f = fixture();
        Player killer = server.addPlayer();
        killer.setGameMode(GameMode.CREATIVE);
        org.mockbukkit.mockbukkit.world.WorldMock world = server.addSimpleWorld("kills");
        org.bukkit.entity.Zombie zombie = world.spawn(world.getSpawnLocation(), org.bukkit.entity.Zombie.class);
        ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) zombie).setKiller(killer);

        f.listener.onMobDeath(new EntityDeathEvent(zombie,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC_KILL).build(),
                new ArrayList<>()));

        assertFalse(PlayerData.of(killer).collectionEntries().stream()
                        .anyMatch(raw -> raw.contains("mob:ZOMBIE")),
                "クリエイティブなら任意のモブを即殺できるので、討伐図鑑24件も無料で埋まる");
    }

    @Test
    @DisplayName("サバイバルは従来どおり記録する(除外しすぎていないこと)")
    void survivalPickupIsStillRecorded() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);

        pickup(f.listener, player, bareVanilla(Material.DIAMOND));

        assertTrue(recorded(player, "DIAMOND"));
    }

    // --- 5-1 の続き: ゲームモードゲートを素通りする creative→survival 持ち込みを出自マーカーで塞ぐ ---

    @Test
    @DisplayName("クリエイティブでの拾得は記録せず、アイテムに出自マーカーを刻む")
    void creativePickupStampsTheCreativeOriginMarker() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        ItemStack stack = new ItemStack(Material.DIAMOND);

        pickup(f.listener, player, stack);

        assertFalse(recorded(player, "DIAMOND"), "ゲームモードゲートは従来どおり効く");
        assertTrue(creativeOrigin(stack),
                "印を刻まないと、サバイバルへ持ち込んで走査させるだけでゲートを素通りする");
    }

    @Test
    @DisplayName("クリエイティブのアイテム生成(InventoryCreativeEvent)に出自マーカーを刻む")
    void creativeInventorySetStampsTheCreativeOriginMarker() {
        Fixture f = fixture();
        // このイベントはサーバ側がクリエイティブのプレイヤーにしか通さない
        // (handleSetCreativeModeSlot が gameMode.isCreative() で門前払いする)ので、
        // ハンドラ側でゲームモードを再判定する必要はない。
        InventoryCreativeEvent event = creativeSet(f.listener, new ItemStack(Material.DIAMOND));

        assertTrue(creativeOrigin(event.getCursor()),
                "クリエイティブインベントリからの取り出しはこの経路で来る(通常のインベントリ操作は"
                        + "InventoryClickEvent なのでここには来ない)。刻んだ結果は setCursor で差し戻す");
    }

    /**
     * <b>HIGH 指摘の本体</b>: 走査には出自チェックが無かったため、クリエイティブで
     * {@code items.structure} の16件を並べてサバイバルへ移り、何かのインベントリを閉じるだけで
     * 16件が一括登録され {@code reward-tiers} の t1(10)/t2(30) を無条件に跨いでいた。
     * しかも「最初の productive な走査は静か」という抑止と噛み合って<b>チャット0行で</b>通るため、
     * 1件ごとに通知が出ていた修正前より検知可能性が下がっていた。
     */
    @Test
    @DisplayName("HIGH: クリエイティブで得た品はサバイバルの走査でも記録しない")
    void creativeOriginItemIsNotRecordedByASurvivalScan() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        ItemStack stack = new ItemStack(Material.DIAMOND);
        pickup(f.listener, player, stack); // クリエイティブ中の取得 = 印が付く
        player.getInventory().addItem(stack);
        // 走査対象に本当に入っていることを確かめる(空振りで緑になるのを防ぐ)。
        assertTrue(player.getInventory().contains(Material.DIAMOND),
                "走査するスロットにアイテムが入っていない = このテストは何も検証していない");

        // ops/RUNBOOK.md は「メインの world は creative」かつ HuskSync は game_mode: false
        // (ゲームモードは同期しない)でインベントリだけ同期する。/server resource で移るだけで
        // 「クリエイティブで出した品を持ったサバイバルプレイヤー」が成立する。
        player.setGameMode(GameMode.SURVIVAL);
        closeInventory(f.listener, player);

        assertFalse(recorded(player, "DIAMOND"),
                "走査時のゲームモードしか見ないと、creative→survival の持ち込みで図鑑16件が丸ごと埋まる");
        assertFalse(PlayerData.of(player).collectionBackfillDone(),
                "記録0件なので遡り登録フラグも焼かない");
    }

    @Test
    @DisplayName("サバイバルで拾った品には出自マーカーを刻まない(過剰に塞いでいないこと)")
    void survivalPickupIsNotStamped() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);
        ItemStack stack = new ItemStack(Material.DIAMOND);

        pickup(f.listener, player, stack);

        assertFalse(creativeOrigin(stack), "サバイバルの拾得に印を付けると図鑑が永久に埋まらなくなる");
        assertTrue(recorded(player, "DIAMOND"));
    }

    @Test
    @DisplayName("印の付いていない同じ Material は走査で従来どおり記録される")
    void unstampedItemIsStillRecordedByAScan() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));

        closeInventory(f.listener, player);

        assertTrue(recorded(player, "DIAMOND"));
    }

    @Test
    @DisplayName("クリエイティブの走査は遡り登録フラグも消費しない")
    void creativeScanDoesNotConsumeTheBackfillFlag() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));
        player.setGameMode(GameMode.CREATIVE);

        closeInventory(f.listener, player);

        assertFalse(PlayerData.of(player).collectionBackfillDone(),
                "除外された走査でフラグを焼くと、サバイバルへ戻った初回の一括登録が通知付きで流れる");
    }

    // --- 5-2: 遡り登録フラグは「実際に1件以上記録した走査」だけが消費する ---

    @Test
    @DisplayName("1件も記録しなかった走査では遡り登録フラグを消費しない")
    void unproductiveScanDoesNotConsumeTheBackfillFlag() {
        Fixture f = fixture();
        Player player = server.addPlayer(); // インベントリは空

        closeInventory(f.listener, player);
        join(f.listener, player);

        assertFalse(PlayerData.of(player).collectionBackfillDone(),
                "遺物系をチェストにしまっているプレイヤーは初回走査が0件になる。"
                        + "そこでフラグを焼くと、後で取り出した1回が通知の束になる");
    }

    @Test
    @DisplayName("最初に記録が発生した走査はインベントリ閉時でも静かに行う")
    void firstProductiveScanIsSilentEvenOnInventoryClose() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));
        drainMessages(player);

        closeInventory(f.listener, player);

        assertTrue(recorded(player, "DIAMOND"), "静かに行うだけで、記録そのものは通常どおり行う");
        assertFalse(announced(player),
                "チェストに16件しまってあるプレイヤーが初めて取り出した1回がここに来るので、"
                        + "参加時だけを静かにしても主要ケースで効かない");
        assertTrue(PlayerData.of(player).collectionBackfillDone(),
                "記録が発生した走査なのでフラグはここで消費される");
    }

    @Test
    @DisplayName("遡り登録が済んだ後の新規登録は従来どおり通知する")
    void laterDiscoveriesAreAnnounced() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));
        closeInventory(f.listener, player); // 遡り登録(静か)でフラグを消費
        assertTrue(PlayerData.of(player).collectionBackfillDone());
        drainMessages(player);

        f.setWatched("DIAMOND", "EMERALD");
        player.getInventory().addItem(new ItemStack(Material.EMERALD));
        closeInventory(f.listener, player);

        assertTrue(recorded(player, "EMERALD"));
        assertTrue(announced(player), "抑止は最初の productive な走査1回だけ");
    }

    // --- 5-3: 監視集合のフィールドキャッシュ ---

    @Test
    @DisplayName("拾得1件ごとに監視集合を組み直さない")
    void watchedSetIsBuiltOnceAcrossPickups() {
        Fixture f = fixture();
        Player player = server.addPlayer();

        pickup(f.listener, player, bareVanilla(Material.DIRT));
        pickup(f.listener, player, bareVanilla(Material.STONE));
        pickup(f.listener, player, bareVanilla(Material.DIAMOND));

        assertEquals(1, f.categories.iterations,
                "onPickup は MONITOR で拾得1件ごとに走る。連鎖採掘の落下物回収やモブファームで"
                        + "約120件の itemCategories 走査(各エントリで Material.matchMaterial)が毎tick積み上がる");
    }

    @Test
    @DisplayName("config snapshot が入れ替わったら監視集合を作り直す(reload/editor 編集の反映)")
    void watchedSetIsRebuiltWhenTheConfigSnapshotIsReplaced() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        pickup(f.listener, player, bareVanilla(Material.DIAMOND));

        // reload は volatile な snapshot リストを丸ごと差し替える。キャッシュはこれで捨てること。
        f.setWatched("DIAMOND", "EMERALD");
        pickup(f.listener, player, bareVanilla(Material.EMERALD));

        assertTrue(recorded(player, "EMERALD"),
                "キャッシュを捨て忘れると「editor で図鑑を編集しても反映されない」別のバグになる");
    }

    /**
     * キャッシュキーは categories と achievements の2本だが、achievements 側の無効化を
     * 検証するテストが無かった(Fixture が {@code List.of()} で固定していたため)。
     * その状態では achievements の識別子をキャッシュキーから落としても、
     * {@code achievements()} の呼び出しをキャッシュ外へ出しても<b>落ちるテストが1本も無い</b>。
     * それは「achievements.yml の trigger.collection-targets を editor で編集して保存 →
     * reload しても図鑑の監視集合に反映されない」というエラーの出ないバグそのもの。
     */
    @Test
    @DisplayName("achievements snapshot が入れ替わっても監視集合を作り直す")
    void watchedSetIsRebuiltWhenTheAchievementsSnapshotIsReplaced() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        // まず監視集合を組ませてキャッシュに載せる(EMERALD はどちらの config にも無い)。
        pickup(f.listener, player, bareVanilla(Material.EMERALD));
        assertFalse(recorded(player, "EMERALD"));

        // achievements.yml 側だけを差し替える(itemCategories は同一インスタンスのまま)。
        f.setWatchedAchievementTargets("EMERALD");
        pickup(f.listener, player, bareVanilla(Material.EMERALD));

        assertTrue(recorded(player, "EMERALD"),
                "アチーブメントの collection scope:item は監視集合の第2の供給源。"
                        + "こちらの差し替えを無視すると editor の編集が反映されない");
    }

    // --- 5-4: 参加時走査を HuskSync の snapshot 適用より後へ落とす ---

    @Test
    @DisplayName("参加時の走査は数十tick後に走る(HuskSync の snapshot 適用待ち)")
    void joinScanIsDeferred() {
        Plugin plugin = MockBukkit.createMockPlugin();
        Fixture f = new Fixture(plugin);
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));

        join(f.listener, player);

        assertFalse(recorded(player, "DIAMOND"),
                "HuskSync は PlayerJoinEvent より後に snapshot を適用しうるので、"
                        + "参加時に読むインベントリと PDC は同期前の値になりうる");

        server.getScheduler().performTicks(JOIN_SCAN_DELAY_TICKS + 1);

        assertTrue(recorded(player, "DIAMOND"), "遅延後には従来どおり走査される");
    }

    /**
     * null フォールバック(即時走査)の経路。4引数コンストラクタは
     * {@code Bukkit.getPluginManager().getPlugin("TrinityForge")} を引くので、
     * その名前のプラグインが居ないここでは {@code null} になり遅延しない。
     */
    @Test
    @DisplayName("プラグイン名で自分を引けない構築では即時走査へ落とす(サーバ未起動の単体テスト用)")
    void joinScanRunsImmediatelyWithoutAPlugin() {
        assertNull(server.getPluginManager().getPlugin(CollectionListener.OWN_PLUGIN_NAME),
                "この経路の前提: 名前引きが null になること");
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));

        join(f.listener, player);

        assertTrue(recorded(player, "DIAMOND"));
    }

    /**
     * <b>本番が実際に通る経路</b>: {@code TrinityForge#onEnable} は4引数コンストラクタで
     * {@code CollectionListener} を作るので、遅延に使う Plugin は
     * {@code getPlugin(OWN_PLUGIN_NAME)} の<b>文字列引き</b>で解決される。
     * この分岐を通るテストが無いと、{@code paper-plugin.yml} の name 変更・配線位置の移動・
     * PluginManager 登録前の構築などで getPlugin が null に化けた場合、
     * <b>HuskSync 対策の遅延が黙って消えてテストは全部緑のまま</b>になる。
     */
    @Test
    @DisplayName("Plugin を渡さない構築でも、プラグイン名で自分を引けたときは遅延する")
    void joinScanIsDeferredWhenTheOwnPluginIsResolvedByName() {
        MockBukkit.createMockPlugin(CollectionListener.OWN_PLUGIN_NAME);
        assertNotNull(server.getPluginManager().getPlugin(CollectionListener.OWN_PLUGIN_NAME),
                "名前引きの経路を通せていない(このテストが検証したい分岐に入っていない)");
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));

        join(f.listener, player);

        assertFalse(recorded(player, "DIAMOND"),
                "名前引きで解決できたなら遅延する。ここが緑にならないと遅延が消えても気づけない");

        server.getScheduler().performTicks(JOIN_SCAN_DELAY_TICKS + 1);

        assertTrue(recorded(player, "DIAMOND"), "遅延後には従来どおり走査される");
    }

    /**
     * 文字列引きの drift ガード。{@code paper-plugin.yml} の {@code name:} を変えたら
     * {@link CollectionListener#OWN_PLUGIN_NAME} も変えないと遅延が黙って消える
     * (例外もログも出ない)。ここで機械的に縛る。
     */
    @Test
    @DisplayName("OWN_PLUGIN_NAME が paper-plugin.yml の name と一致している")
    void ownPluginNameMatchesPaperPluginYml() throws Exception {
        String yml;
        try (InputStream in = CollectionListener.class.getResourceAsStream("/paper-plugin.yml")) {
            assertNotNull(in, "paper-plugin.yml がテストのクラスパスに無い");
            yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String declared = yml.lines()
                .filter(line -> line.startsWith("name:"))
                .map(line -> line.substring("name:".length()).trim())
                .findFirst()
                .orElse("");

        assertEquals(CollectionListener.OWN_PLUGIN_NAME, declared,
                "名前が食い違うと getPlugin が null に化け、参加時走査の遅延が黙って消える");
    }

    // --- 5-2 の続き: 拾得も走査と同じ抑止に揃える(通知の出方が経路依存だった) ---

    /**
     * 抑止が走査経路だけだと<b>地面を経由する取り出し</b>がすり抜ける。エンダーチェストから
     * 遺物をカーソルへ取って GUI を Esc/E で閉じるとカーソルのスタックは地面へ落ちて
     * 即座に拾い直しになり({@code getContents()} はカーソルを含まないので走査側では見えない)、
     * この経路は {@code onPickup} を通る。1件ずつ取り出す操作を繰り返すと抑止したかった
     * 通知の束と報酬ティアの全体告知がそのまま出るうえ、フラグは未消費のまま残るので
     * 後続のまとめ走査だけが無音になり、通知の出方が経路依存で一貫しなくなる。
     */
    @Test
    @DisplayName("最初に記録が発生した拾得も静かに行い、遡り登録フラグを消費する")
    void firstProductivePickupIsSilentAndConsumesTheBackfillFlag() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        drainMessages(player);

        pickup(f.listener, player, bareVanilla(Material.DIAMOND));

        assertTrue(recorded(player, "DIAMOND"), "静かに行うだけで、記録そのものは通常どおり行う");
        assertFalse(announced(player),
                "「拾得は常に通知」だと、エンダーチェストから1件ずつ取り出す操作で"
                        + "抑止したかった通知の束と報酬ティアの全体告知がそのまま出る");
        assertTrue(PlayerData.of(player).collectionBackfillDone(),
                "記録が発生した経路なのでフラグはここで消費される");
    }

    @Test
    @DisplayName("遡り登録が済んだ後の拾得は従来どおり通知する")
    void laterPickupsAreAnnounced() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        pickup(f.listener, player, bareVanilla(Material.DIAMOND)); // 遡り登録(静か)でフラグを消費
        assertTrue(PlayerData.of(player).collectionBackfillDone());
        drainMessages(player);

        f.setWatched("DIAMOND", "EMERALD");
        pickup(f.listener, player, bareVanilla(Material.EMERALD));

        assertTrue(recorded(player, "EMERALD"));
        assertTrue(announced(player), "抑止は最初の productive な記録1回だけ");
    }

    @Test
    @DisplayName("抑止は拾得と走査で一貫する(先に拾得で消費したら走査側は通知する)")
    void backfillSuppressionIsSharedBetweenPickupAndScan() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        pickup(f.listener, player, bareVanilla(Material.DIAMOND)); // 最初の productive = 静か
        drainMessages(player);

        f.setWatched("DIAMOND", "EMERALD");
        player.getInventory().addItem(new ItemStack(Material.EMERALD));
        closeInventory(f.listener, player);

        assertTrue(recorded(player, "EMERALD"));
        assertTrue(announced(player),
                "フラグは拾得と走査で共有する。経路ごとに別扱いすると通知の出方が経路依存になる");
    }
}
