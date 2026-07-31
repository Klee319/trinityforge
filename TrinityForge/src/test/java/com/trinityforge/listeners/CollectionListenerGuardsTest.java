package com.trinityforge.listeners;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 図鑑記録の3つのガード(2026-07-31、Wave 1 の反証レビュー指摘)。
 *
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
        private final CollectionListener listener;
        private CountingList<CollectionConfig.Category> categories;

        Fixture(Plugin plugin) {
            this.config = mock(CollectionConfig.class);
            when(config.enabled()).thenReturn(true);
            when(config.catalogItemsEnabled()).thenReturn(true);
            when(config.mobKillsEnabled()).thenReturn(true);
            when(config.tiers()).thenReturn(List.of());
            setWatched("DIAMOND");

            AchievementsConfig achievements = mock(AchievementsConfig.class);
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

    @Test
    @DisplayName("Plugin を渡さない構築では即時走査へ落とす(サーバ未起動の単体テスト用)")
    void joinScanRunsImmediatelyWithoutAPlugin() {
        Fixture f = fixture();
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));

        join(f.listener, player);

        assertTrue(recorded(player, "DIAMOND"));
    }
}
