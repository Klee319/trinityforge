package com.trinityforge.listeners;

import com.trinityforge.config.domains.AchievementsConfig;
import com.trinityforge.config.domains.CollectionConfig;
import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.progression.CollectionService;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 図鑑が ArsPaper 側で定義したアイテムを記録できることの契約(2026-07-31)。
 *
 * <p>{@code CollectionListener} は TF の catalog PDC しか読んでいなかったため、
 * ArsPaper の {@code materials.yml} / {@code sourcejars.yml} 由来のアイテム
 * (モブドロップ素材17件・ダンジョン踏破の証22件・ソースの階梯9件)が永久に記録されず、
 * 図鑑カテゴリに書いてある116件のうち<b>48件が「絶対に埋まらない枠」</b>として並んでいた。
 * 例外もログも出ないので、実物を集めてみるまで気づけない種類の不具合。
 *
 * <p>同時に、Ars の登録アイテムを無条件に記録しないことも固定する。Ars 側にはグリフ120件を
 * 含めて300件超の登録アイテムがあり、全部記録すると (1) プレイヤーPDCがその分膨らみ、
 * (2) 図鑑の報酬ティア(10/30/60/120/200件)の重みが黙って変わる。
 */
class CollectionArsItemRecordingTest {

    private static final NamespacedKey ARS_CUSTOM_ITEM_ID =
            new NamespacedKey("arspaper", "custom_item_id");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 図鑑カテゴリに ravager_hide(Ars 素材) と DIAMOND(バニラ) を載せた設定。 */
    private static CollectionListener listener() {
        CollectionConfig config = mock(CollectionConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.catalogItemsEnabled()).thenReturn(true);
        when(config.mobKillsEnabled()).thenReturn(true);
        when(config.itemCategories()).thenReturn(List.of(
                new CollectionConfig.Category("mob_parts", "討伐素材", 1,
                        List.of("ravager_hide", "DIAMOND", "custom:reality_thread_core"))));
        when(config.tiers()).thenReturn(List.of());

        AchievementsConfig achievements = mock(AchievementsConfig.class);
        when(achievements.achievements()).thenReturn(List.of());

        ItemCatalogConfig catalog = mock(ItemCatalogConfig.class);
        when(catalog.all()).thenReturn(java.util.Map.of());

        CollectionService service = new CollectionService(config, Logger.getLogger("CollectionArsItemRecordingTest"));
        return new CollectionListener(config, service, catalog, achievements);
    }

    private static ItemStack arsItem(Material material, String arsId) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(ARS_CUSTOM_ITEM_ID, PersistentDataType.STRING, arsId);
        stack.setItemMeta(meta);
        return stack;
    }

    /** インベントリを閉じたときの全スロット走査を通して記録させる(拾得と同じ解決経路)。 */
    private static void scan(Player player) {
        listener().onInventoryClose(new InventoryCloseEvent(player.getOpenInventory()));
    }

    /**
     * 実機で {@code hasItemMeta()} が false になる「素のバニラ品」(データコンポーネントの
     * patch が空)を作る。MockBukkit の {@code ItemStackMock} はコンストラクタで {@code itemMeta} を
     * 無条件に代入するため素のスタックでも {@code hasItemMeta()} が true になり、
     * <b>本番条件をそのままでは表現できない</b>ので偽装する。
     */
    private static ItemStack bareVanilla(Material material) {
        return new ItemStack(material) {
            @Override
            public boolean hasItemMeta() {
                return false;
            }
        };
    }

    /**
     * 偽装スタックを拾得経路へ<b>直接</b>渡す。{@code Inventory#setItem}/{@code addItem} や
     * {@code ItemMock} はすべて {@code ItemStack.clone()}(= {@code craftDelegate.clone()})を
     * 通すのでラッパのクラスが消え、{@code getContents()} は {@code ItemStackMirror} で包み直す。
     * 一方 {@code Item} は interface なので Mockito のモックは {@code getItemStack()} で
     * <b>参照をそのまま返す</b> — これが偽装をコードへ届ける唯一の経路。
     */
    private static void pickup(Player player, ItemStack stack) {
        Item drop = mock(Item.class);
        when(drop.getItemStack()).thenReturn(stack);
        listener().onPickup(new EntityPickupItemEvent(player, drop, 0));
    }

    private static void join(Player player) {
        listener().onJoin(new PlayerJoinEvent(player, Component.empty()));
    }

    /** PlayerMock の受信箱を空になるまで読み切る。 */
    private static List<String> drainMessages(Player player) {
        List<String> out = new java.util.ArrayList<>();
        String message;
        while ((message = ((org.mockbukkit.mockbukkit.entity.PlayerMock) player).nextMessage()) != null) {
            out.add(message);
        }
        return out;
    }

    /** PDC は {@code id|epochMillis|maxQualityPt} で入るので、ID だけを取り出して比べる。 */
    private static boolean recorded(Player player, String entryId) {
        return PlayerData.of(player).collectionEntries().stream()
                .map(com.trinityforge.progression.CollectionRecord::parse)
                .filter(java.util.Objects::nonNull)
                .anyMatch(r -> r.id().equals("item:" + entryId));
    }

    @Test
    @DisplayName("図鑑カテゴリに載っている Ars 素材は記録される")
    void arsItemListedInACategoryIsRecorded() {
        Player player = server.addPlayer();
        player.getInventory().addItem(arsItem(Material.LEATHER, "ravager_hide"));

        scan(player);

        assertTrue(recorded(player, "ravager_hide"),
                "ArsPaper の materials.yml 由来アイテムが図鑑に載らないと、"
                        + "カテゴリに書いた枠が永久に埋まらない");
    }

    @Test
    @DisplayName("custom: 接頭辞付きで書いたエントリも一致する")
    void customPrefixInTheConfigIsStripped() {
        Player player = server.addPlayer();
        player.getInventory().addItem(arsItem(Material.ECHO_SHARD, "reality_thread_core"));

        scan(player);

        assertTrue(recorded(player, "reality_thread_core"),
                "editor は custom アイテムを custom:<id> へ正規化するので、両方の書き方を通す必要がある");
    }

    @Test
    @DisplayName("設定から参照されていない Ars アイテムは記録しない")
    void unreferencedArsItemIsIgnored() {
        Player player = server.addPlayer();
        // グリフや魔導書のように「Ars に登録はされているが図鑑には出さない」アイテム。
        player.getInventory().addItem(arsItem(Material.PAPER, "glyph_projectile"));

        scan(player);

        assertFalse(recorded(player, "glyph_projectile"),
                "Ars の登録アイテムは300件超あるので、無条件に記録すると PDC が膨らみ"
                        + "報酬ティアの重みも黙って変わる");
    }

    @Test
    @DisplayName("図鑑に載っている Material を base に持つ Ars 品はカスタムIDで記録される(順序回帰)")
    void arsItemOnAWatchedVanillaMaterialResolvesToItsCustomId() {
        // 実配置と同じ衝突: materials.yml の warden_tendril / reality_thread_core は
        // base_material: ECHO_SHARD、source_condenser は HEART_OF_THE_SEA で、
        // どちらの Material も collection.yml の items.structure に載っている。
        // K-11 を「Material 判定を meta ゲートより前へ出す」だけで直すとこの3件が潰れる。
        Player player = server.addPlayer();
        player.getInventory().addItem(arsItem(Material.DIAMOND, "ravager_hide"));

        scan(player);

        assertTrue(recorded(player, "ravager_hide"));
        assertFalse(recorded(player, "DIAMOND"),
                "PDC 判定より先に Material を見ると、ECHO_SHARD 系のカスタム3件が"
                        + "item:ECHO_SHARD に潰れて新たに到達不能になる");
    }

    @Test
    @DisplayName("K-11: メタを持たない素のバニラ品も拾得で記録される")
    void bareVanillaStackWithoutItemMetaIsRecordedOnPickup() {
        Player player = server.addPlayer();

        pickup(player, bareVanilla(Material.DIAMOND));

        assertTrue(recorded(player, "DIAMOND"),
                "ルートチェストから出た無傷のバニラ品は実機で hasItemMeta() が false になる。"
                        + "ここで落とすと collection.yml の items.structure 16件が永久に埋まらず、"
                        + "goal_completionist(percent: 100) が構造的に達成不能になる");
    }

    @Test
    @DisplayName("K-11: メタ無しでも監視外の Material は記録しない")
    void bareVanillaStackOutsideWatchedSetIsIgnoredOnPickup() {
        Player player = server.addPlayer();

        pickup(player, bareVanilla(Material.DIRT));

        assertFalse(recorded(player, "DIRT"));
    }

    @Test
    @DisplayName("バニラ Material の監視は従来どおり効く")
    void watchedVanillaMaterialStillRecorded() {
        // ⚠️ このテストは K-11(素のバニラ品が1件も記録されない)が生きている間も緑だった
        // ＝「偽の緑」だった。MockBukkit の ItemStackMock はコンストラクタで itemMeta を
        // 無条件に代入するので new ItemStack(DIAMOND).hasItemMeta() が true になり、
        // 実機で落ちる meta ゲートをこの経路では踏まないため。実機条件を踏む版は
        // bareVanillaStackWithoutItemMetaIsRecordedOnPickup / CollectionEntryResolutionTest。
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));

        scan(player);

        assertTrue(recorded(player, "DIAMOND"));
    }

    @Test
    @DisplayName("監視外のバニラ Material は記録しない")
    void unwatchedVanillaMaterialIsIgnored() {
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIRT));

        scan(player);

        assertFalse(recorded(player, "DIRT"));
    }

    /**
     * MockBukkit の盲点を明文化する characterization test。
     *
     * <p>ここが赤くなったら MockBukkit 側が本番と同じ挙動になったということなので、
     * {@link #bareVanilla} の偽装は不要になる(そのとき初めて素のスタックを
     * インベントリ経路へ流すテストが本番条件になる)。K-11 が「緑なのに実機では死んでいる」
     * 状態で長期間生き残れた原因そのものなので、消さずに残す。
     */
    @Test
    @DisplayName("【MockBukkitの盲点】素のスタックでも hasItemMeta() が true になる")
    void mockBukkitReportsItemMetaOnBareStacks() {
        assertTrue(new ItemStack(Material.DIAMOND).hasItemMeta(),
                "実機の CraftItemStack#hasItemMeta() は getComponentsPatch().isEmpty() を見るので"
                        + "無傷のバニラ品では false。ここが false に変わったら bareVanilla() の偽装は外せる");
    }

    // --- 遡り登録の通知抑止 (K-11 の副作用対策) ---

    @Test
    @DisplayName("初回参加の走査は遡り登録として静かに行い、フラグを立てる")
    void firstJoinScanIsSilentAndMarksBackfillDone() {
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));
        drainMessages(player);

        join(player);

        assertTrue(recorded(player, "DIAMOND"), "静かに行うだけで、記録そのものは通常どおり行う");
        assertTrue(drainMessages(player).stream().noneMatch(m -> m.contains("図鑑に登録")),
                "修正で一斉に記録可能になった分が最大16行のチャットとして流れると事故に見える");
        assertTrue(PlayerData.of(player).collectionBackfillDone());
    }

    /**
     * 空のインベントリで参加してもフラグを消費しないこと。
     *
     * <p><b>2026-07-31 に仕様を変えた</b>: 元は「初回参加の走査でフラグを消費する」だったが、
     * 遺物系(エリトラ/トーテム/レコード/バナー模様)はチェストやエンダーチェストへ
     * しまってあることが多く、初回参加の走査は<b>0件で終わってフラグだけ焼かれていた</b>。
     * すると抑止したかった「最大16件のチャット＋全体ブロードキャスト」が、後でチェストから
     * 出した瞬間にそのまま出る。消費条件は「実際に1件以上記録した走査」に限る
     * (詳細は {@code CollectionListenerGuardsTest})。
     */
    @Test
    @DisplayName("記録が0件の参加ではフラグを消費せず、その後の初回登録を静かに通す")
    void emptyJoinKeepsTheBackfillFlagForTheFirstRealDiscovery() {
        Player player = server.addPlayer();
        join(player); // 空のインベントリ = 記録0件
        assertFalse(PlayerData.of(player).collectionBackfillDone(),
                "0件の走査でフラグを焼くと、チェストから出した1回が通知の束になる");

        player.getInventory().addItem(new ItemStack(Material.DIAMOND));
        drainMessages(player);

        join(player);

        assertTrue(recorded(player, "DIAMOND"));
        assertTrue(drainMessages(player).stream().noneMatch(m -> m.contains("図鑑に登録")),
                "最初に記録が発生した走査が遡り登録なので、ここが静かになる");
        assertTrue(PlayerData.of(player).collectionBackfillDone());
    }

    /**
     * インベントリ閉時の走査も遡り登録の対象にする。
     *
     * <p><b>2026-07-31 に仕様を変えた</b>: 元は「インベントリを閉じた走査は必ず通知する」
     * だったが、それは「参加時の走査だけが遡り扱い」という穴を仕様として固定していた。
     * チェストに遺物をしまっているプレイヤーが初めて取り出す経路は<b>まさにここ</b>なので、
     * 最初の productive な走査は経路を問わず静かに行う。2回目以降は従来どおり通知する
     * (ダンジョンloot直入れ・ガチャ・取引の新規入手)。
     */
    @Test
    @DisplayName("インベントリ閉時でも最初に記録が発生した走査は静かに行う")
    void firstProductiveInventoryCloseScanIsSilent() {
        Player player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND));
        drainMessages(player);

        scan(player);

        assertTrue(recorded(player, "DIAMOND"));
        assertTrue(drainMessages(player).stream().noneMatch(m -> m.contains("図鑑に登録")),
                "チェストから遺物を出した1回がこの経路で来るので、参加時だけを静かにしても効かない");
        assertTrue(PlayerData.of(player).collectionBackfillDone(),
                "記録が発生した走査なのでここでフラグを消費する");
    }
}
