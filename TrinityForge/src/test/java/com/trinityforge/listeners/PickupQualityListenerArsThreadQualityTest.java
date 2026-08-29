package com.trinityforge.listeners;

import com.trinityforge.config.domains.ItemCatalogConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.QualityConfig;
import com.trinityforge.config.domains.QualityTiersConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.stats.ItemFactory;
import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.PlayerLootLuckSource;
import com.trinityforge.stats.QualityTier;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W-53(2026-08-18)の回帰テスト: ArsPaper のスレッド(ThreadItem)は
 * {@code item-stats.yml} に <b>薄い(socketed-only-stats等のフラグのみの)profileを実際に持つ</b>
 * ({@code ItemStatsConfig#load} は fixed/per-quality/random が空でも必ず {@code ItemStatProfile} を
 * 生成するため)。したがって「スレッドは profile を持たないので通常ゲートに引っかからない」という
 * 早合点は誤り —— 実際に起きるのは逆で、<b>通常ゲートが素通りしてしまい、スレッドが汎用
 * {@code ItemFactory#stamp}(lore全体を再組み立てする経路)に流れ込み、スレッド専用loreを
 * 破壊しかねない</b>という別種の危険がある(詳細は {@link PickupQualityListener#defaultArsThreadRestamp}
 * のjavadoc)。このテストは「profileの有無に関係なく、PDCマーカーで検出したスレッドは必ず
 * {@link PickupQualityListener.ArsThreadQualityRestamper} 経由になり、汎用stampには絶対に
 * フォールスルーしない」ことを固定する。ArsPaper 本体は用意せず、パッケージ非公開シーム経由で検証する。
 */
class PickupQualityListenerArsThreadQualityTest {

    /** ArsPaper {@code ItemKeys.THREAD_ITEM_TYPE} と同じ名前空間/キー(本番実装が読むのと同じキー)。 */
    private static final NamespacedKey THREAD_ITEM_TYPE_KEY =
            new NamespacedKey("arspaper", "thread_item_type");

    private ServerMock server;
    private ItemFactory itemFactory;
    private ItemStatsConfig itemStats;
    private QualityTiersConfig qualityTiers;
    private QualityConfig quality;
    private ItemCatalogConfig itemCatalog;
    private PlayerLootLuckSource lootLuck;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        itemFactory = mock(ItemFactory.class);
        itemStats = mock(ItemStatsConfig.class);
        qualityTiers = mock(QualityTiersConfig.class);
        quality = mock(QualityConfig.class);
        when(quality.maxQuality()).thenReturn(9);
        when(quality.spreadUp()).thenReturn(1.5);
        when(quality.spreadDown()).thenReturn(1.5);
        itemCatalog = mock(ItemCatalogConfig.class);
        when(itemCatalog.all()).thenReturn(java.util.Map.of());
        lootLuck = new PlayerLootLuckSource(java.util.logging.Logger.getLogger("test"), null);
        when(qualityTiers.tiers()).thenReturn(
                java.util.Collections.nCopies(3, new QualityTier("common", "")));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PickupQualityListener listenerWithRestamper(
            PickupQualityListener.ArsThreadQualityRestamper restamper) {
        return new PickupQualityListener(
                MockBukkit.createMockPlugin(), itemFactory, itemStats, qualityTiers, quality,
                itemCatalog, lootLuck, null, restamper);
    }

    /** ArsPaper のPDCマーカーだけ立てた「未刻印スレッド」相当のスタック(材質はゲート判定に無関係)。 */
    private static ItemStack markedUnstampedThreadStack() {
        ItemStack stack = new ItemStack(Material.LEATHER);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(THREAD_ITEM_TYPE_KEY, PersistentDataType.STRING, "speed");
        stack.setItemMeta(meta);
        return stack;
    }

    /** 実物どおりの「フラグのみ・stat値ゼロ」のスレッドprofile(出荷item-stats.ymlの実態)。 */
    private static ItemStatProfile threadShapedThinProfile() {
        return new ItemStatProfile(Map.of(), Map.of(), Map.of());
    }

    @Test
    @DisplayName("item-statsのprofileが無くても、PDCのthread_item_typeマーカーだけでスレッド専用"
            + "restamperが呼ばれ、通常のitemFactory.stampは呼ばれない")
    void invokesThreadRestamperWhenMarkerIsPresentEvenWithoutAStatsProfile() {
        when(itemStats.profileFor(any(), any())).thenReturn(Optional.empty());
        AtomicInteger restampedQuality = new AtomicInteger(Integer.MIN_VALUE);
        PickupQualityListener listener = listenerWithRestamper((stack, q) -> {
            restampedQuality.set(q);
            return true; // ThreadItem#restampWithQuality が成功したことを装う。
        });
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, markedUnstampedThreadStack());

        listener.sweepInventory(player);

        assertTrue(restampedQuality.get() >= 0, "restamperへ実際にロールした品質が渡ること");
        verify(itemFactory, never()).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("【本命】item-statsのprofileが実在(出荷ymlの実態)しても、PDCマーカーがあれば"
            + "必ずスレッド専用restamper経由になり、汎用itemFactory.stampには絶対にフォールスルーしない"
            + "(profileFor()の有無でスレッド判定した旧設計だとここが漏れて汎用stampがloreを破壊する)")
    void neverFallsThroughToGenericStampWhenAThinStatsProfileExistsForTheThread() {
        // 出荷item-stats.ymlの実態: 各スレッドのMATERIAL#CMDにはsocketed-only-stats等のフラグのみの
        // 薄いentryが実在する(fixed/per-quality/randomは空でもItemStatProfile自体は生成される)。
        when(itemStats.profileFor(Material.LEATHER, null))
                .thenReturn(Optional.of(threadShapedThinProfile()));
        AtomicInteger restamperCalls = new AtomicInteger();
        PickupQualityListener listener = listenerWithRestamper((stack, q) -> {
            restamperCalls.incrementAndGet();
            return true;
        });
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, markedUnstampedThreadStack());

        listener.sweepInventory(player);

        assertEquals(1, restamperCalls.get(), "profileが実在してもスレッド専用経路が呼ばれること");
        verify(itemFactory, never()).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("マーカーがあり、かつprofileが実在(スレッドの実態)しても、restamperがfalse"
            + "(ArsPaper未ロード/reflection失敗)を返したら、汎用stampへは絶対にフォールスルーせず"
            + "無刻印のまま諦める(lore破壊よりましという安全側の選択)")
    void doesNotFallBackToGenericStampWhenTheRestamperDeclinesEvenWithAStatsProfile() {
        when(itemStats.profileFor(Material.LEATHER, null))
                .thenReturn(Optional.of(threadShapedThinProfile()));
        PickupQualityListener listener = listenerWithRestamper((stack, q) -> false);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, markedUnstampedThreadStack());

        listener.sweepInventory(player);

        verify(itemFactory, never()).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("マーカーが無い通常アイテムはrestamperを呼ばず、profileがあれば従来どおりitemFactory.stampが呼ばれる"
            + "(非スレッドAras品/バニラ装備の既存挙動を壊さない回帰ガード)")
    void normalItemsWithAProfileStillGoThroughTheGenericStampPath() {
        when(itemStats.profileFor(any(), any())).thenReturn(Optional.empty());
        when(itemStats.profileFor(Material.DIAMOND_SWORD, null))
                .thenReturn(Optional.of(threadShapedThinProfile()));
        AtomicInteger restamperCalls = new AtomicInteger();
        PickupQualityListener listener = listenerWithRestamper((stack, q) -> {
            restamperCalls.incrementAndGet();
            return true;
        });
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));

        listener.sweepInventory(player);

        assertEquals(0, restamperCalls.get(), "マーカーの無いアイテムでrestamperを呼んではいけない");
        verify(itemFactory).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("既にrollSeed刻印済みのスレッド(儀式クラフト等)は、マーカーがあってもrestamperを一切呼ばない"
            + "(hasRollSeed()ガードが従来どおり先に効く。二重ロール/冪等性の回帰ガード)")
    void alreadyStampedThreadsNeverReachTheRestamper() {
        when(itemStats.profileFor(any(), any())).thenReturn(Optional.empty());
        ItemStack stack = markedUnstampedThreadStack();
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setRollSeed(123L);
        ItemData.of(meta).setQuality(4);
        stack.setItemMeta(meta);
        AtomicInteger restamperCalls = new AtomicInteger();
        PickupQualityListener listener = listenerWithRestamper((s, q) -> {
            restamperCalls.incrementAndGet();
            return true;
        });
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, stack);

        listener.sweepInventory(player);

        assertEquals(0, restamperCalls.get());
        verify(itemFactory, never()).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("本番既定(defaultArsThreadRestamp)はArsPaper未ロード環境で例外を投げずfail-openする")
    void productionDefaultFailsOpenWithoutArsPaperLoaded() {
        when(itemStats.profileFor(any(), any())).thenReturn(Optional.empty());
        // 公開コンストラクタ(本番既定のdefaultArsThreadRestamp)経由。ArsPaperはこのテスト環境に無い。
        PickupQualityListener listener = new PickupQualityListener(
                MockBukkit.createMockPlugin(), itemFactory, itemStats, qualityTiers, quality,
                itemCatalog, lootLuck);
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, markedUnstampedThreadStack());

        listener.sweepInventory(player); // 例外を投げずに完走すること。
        verify(itemFactory, never()).stamp(any(ItemStack.class), anyLong(), anyInt());
    }

    @Test
    @DisplayName("スレッドは最大スタック1にする（ホッパーで特異点へ粗悪が重ならない）")
    void threadStacksAreCappedToOne() {
        ItemStack stack = markedUnstampedThreadStack();
        stack.setAmount(8);
        PickupQualityListener.uniquifyThreadStack(stack);
        assertEquals(1, stack.getMaxStackSize());
    }

    @Test
    @DisplayName("プレイヤー不在のホッパー経路でもスレッド専用restamperが呼ばれる")
    void hopperPathStampsThreadsWithoutAPlayer() {
        when(itemStats.profileFor(any(), any())).thenReturn(Optional.empty());
        AtomicInteger restamped = new AtomicInteger(0);
        PickupQualityListener listener = listenerWithRestamper((stack, q) -> {
            restamped.incrementAndGet();
            return true;
        });
        assertTrue(listener.stampIfEligible(markedUnstampedThreadStack(), null));
        assertEquals(1, restamped.get());
        verify(itemFactory, never()).stamp(any(ItemStack.class), anyLong(), anyInt());
    }
}
