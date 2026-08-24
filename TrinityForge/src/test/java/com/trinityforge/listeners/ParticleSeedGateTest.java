package com.trinityforge.listeners;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.SpecialRewardService;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * パーティクルシードの付与が<b>金床</b>で成立し、かつ<b>解放済みのシードでしかできない</b>ことを固定する。
 *
 * <p><b>金床へ移した経緯</b>(2026-08-25 / W-214、実サーバ報告「パーティクルシードを作業台で付与できない。
 * 金床形式にしたい」): 旧実装は作業台の {@code PrepareItemCraftEvent} で結果枠を差し替えるだけで、
 * 素材の消費をバニラのクラフト盤面に任せていた。盤面に一致するレシピが無い以上そこはバニラにとって
 * ただの空の結果であり、さらに同じ {@code HIGH} に「カタログ品が乗ったら結果を消す」ガードが並んでいて
 * 登録順に依存していた。詳細は {@link ParticleSeedListener} のクラスjavadoc。
 *
 * <p><b>保有ゲートの経緯</b>(2026-08-21 実サーバ報告「パーティクルシードを入手しても実装がないのでは？」):
 * 着手前は保有を一切見ておらず、出荷 {@code seed-item} はブレイズパウダー等の<b>誰でも手に入るバニラ材</b>
 * だったので、アチーブメント報酬の {@code special: [seed_*]} は<b>持っていても持っていなくても結果が同じ</b>
 * ＝実質何も付与していなかった。{@link #anUnownedSeedIsNotApplicable} はゲートを消すと落ちる。
 */
class ParticleSeedGateTest {

    private static final String OWNED = "seed_flame";
    private static final String NOT_OWNED = "seed_frost";

    private ServerMock server;
    private SpecialRewardsConfig config;
    private SpecialRewardService rewards;
    private PlayerMock player;
    private AnvilInventory inventory;
    private PrepareAnvilEvent event;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        config = mock(SpecialRewardsConfig.class);
        rewards = mock(SpecialRewardService.class);
        player = server.addPlayer();

        inventory = mock(AnvilInventory.class);
        AnvilView view = mock(AnvilView.class);
        when(view.getPlayer()).thenReturn(player);
        event = mock(PrepareAnvilEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 金床に「左=道具 / 右=シード素材」を置く(付与が成立する唯一の形)。 */
    private void placeToolAndSeed(ItemStack tool, ItemStack seedItem) {
        when(inventory.getFirstItem()).thenReturn(tool);
        when(inventory.getSecondItem()).thenReturn(seedItem);
    }

    private void placeToolAndSeed(Material seedMaterial) {
        placeToolAndSeed(new ItemStack(Material.DIAMOND_PICKAXE), new ItemStack(seedMaterial));
    }

    private void seeds(Map<String, SpecialRewardsConfig.ParticleSeed> defined) {
        when(config.particleSeeds()).thenReturn(defined);
    }

    private static SpecialRewardsConfig.ParticleSeed seed(String id, Material item) {
        return new SpecialRewardsConfig.ParticleSeed(id, item.name(), Particle.FLAME, 6);
    }

    /** 結果スロットの通常左クリック。 */
    private InventoryClickEvent resultClick(ClickType click) {
        InventoryClickEvent click2 = mock(InventoryClickEvent.class);
        when(click2.getRawSlot()).thenReturn(ParticleSeedListener.ANVIL_RESULT_SLOT);
        when(click2.getInventory()).thenReturn(inventory);
        when(click2.getWhoClicked()).thenReturn(player);
        when(click2.getClick()).thenReturn(click);
        return click2;
    }

    @Test
    @DisplayName("解放していないシードは金床に結果が出ない")
    void anUnownedSeedIsNotApplicable() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(false);
        placeToolAndSeed(Material.BLAZE_POWDER);

        new ParticleSeedListener(config, rewards).onPrepareAnvil(event);

        verify(event, never()).setResult(any());
    }

    @Test
    @DisplayName("解放済みなら道具に刻印した結果が金床に出る")
    void anOwnedSeedStampsTheTool() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(true);
        placeToolAndSeed(Material.BLAZE_POWDER);

        new ParticleSeedListener(config, rewards).onPrepareAnvil(event);

        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(event, times(1)).setResult(captor.capture());
        ItemStack result = captor.getValue();
        assertEquals(Material.DIAMOND_PICKAXE, result.getType());
        assertEquals(Optional.of(OWNED), ItemData.of(result.getItemMeta()).particleSeed());
    }

    @Test
    @DisplayName("同じ素材を共有する2つのシードでは、解放済みの方が採用される")
    void aSharedSeedItemFallsThroughToTheOwnedSeed() {
        // 未解放の定義を先に置く。ここで走査を打ち切る(return null)実装だと、
        // 後ろにある解放済みのシードまで巻き添えで使えなくなる。
        Map<String, SpecialRewardsConfig.ParticleSeed> defined = new LinkedHashMap<>();
        defined.put(NOT_OWNED, seed(NOT_OWNED, Material.BLAZE_POWDER));
        defined.put(OWNED, seed(OWNED, Material.BLAZE_POWDER));
        seeds(defined);
        when(rewards.isUnlocked(player, NOT_OWNED)).thenReturn(false);
        when(rewards.isUnlocked(player, OWNED)).thenReturn(true);
        placeToolAndSeed(Material.BLAZE_POWDER);

        new ParticleSeedListener(config, rewards).onPrepareAnvil(event);

        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(event, times(1)).setResult(captor.capture());
        assertEquals(Optional.of(OWNED), ItemData.of(captor.getValue().getItemMeta()).particleSeed());
    }

    @Test
    @DisplayName("シードが1つも定義されていなければ保有判定にすら行かない(通常の金床操作を邪魔しない)")
    void anEmptyRegistryNeverTouchesTheAnvil() {
        seeds(Map.of());
        placeToolAndSeed(Material.BLAZE_POWDER);

        new ParticleSeedListener(config, rewards).onPrepareAnvil(event);

        verify(event, never()).setResult(any());
        verify(rewards, never()).isUnlocked(any(), any());
        assertTrue(config.particleSeeds().isEmpty());
    }

    @Test
    @DisplayName("既に同じシードが刻印された道具は対象外(素材とレベルだけ消えるのを防ぐ)")
    void alreadySeededToolIsNotOfferedTheSameSeedAgain() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(true);
        ItemStack tool = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = tool.getItemMeta();
        ItemData.of(meta).setParticleSeed(OWNED);
        tool.setItemMeta(meta);
        placeToolAndSeed(tool, new ItemStack(Material.BLAZE_POWDER));

        new ParticleSeedListener(config, rewards).onPrepareAnvil(event);

        verify(event, never()).setResult(any());
    }

    /**
     * <b>この付与の消費はバニラに任せられない</b>ことを固定する回帰テスト。
     *
     * <p>バニラの金床は素材の消費数を自分で計算した {@code repairItemCountCost} で決める。
     * プラグインが後から結果を差し込んだ場合その値は 0 のままで、バニラの取り出し処理は
     * <b>右スロットのスタックを丸ごと消す</b>。シード素材はブレイズパウダー等のバニラ材で
     * 64個まとめて持っているのが普通なので、1個の付与で64個消える事故になる。
     */
    @Test
    @DisplayName("取り出しで消えるシード素材はちょうど1個(残りは金床に残る)")
    void takingTheResultConsumesExactlyOneSeedItem() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(true);
        ItemStack stack = new ItemStack(Material.BLAZE_POWDER, 64);
        placeToolAndSeed(new ItemStack(Material.DIAMOND_PICKAXE), stack);
        player.setLevel(5);

        InventoryClickEvent click = resultClick(ClickType.LEFT);
        new ParticleSeedListener(config, rewards).onAnvilResultTake(click);

        verify(click).setCancelled(true);
        ArgumentCaptor<ItemStack> left = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).setSecondItem(left.capture());
        assertEquals(63, left.getValue().getAmount(), "1個だけ減る");
        verify(inventory).setFirstItem(null);
        assertEquals(4, player.getLevel(), "レベルコスト1を支払う");
        assertEquals(Optional.of(OWNED),
                ItemData.of(player.getItemOnCursor().getItemMeta()).particleSeed(),
                "刻印済みの道具がカーソルへ渡る");
    }

    @Test
    @DisplayName("最後の1個を使ったら右スロットは空になる")
    void takingTheLastSeedItemEmptiesTheSlot() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(true);
        placeToolAndSeed(new ItemStack(Material.DIAMOND_PICKAXE), new ItemStack(Material.BLAZE_POWDER, 1));
        player.setLevel(5);

        new ParticleSeedListener(config, rewards).onAnvilResultTake(resultClick(ClickType.LEFT));

        ArgumentCaptor<ItemStack> left = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).setSecondItem(left.capture());
        assertNull(left.getValue());
    }

    @Test
    @DisplayName("レベルが足りなければ素材もレベルも消費しない")
    void aPlayerWithoutTheLevelLosesNothing() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(true);
        placeToolAndSeed(new ItemStack(Material.DIAMOND_PICKAXE), new ItemStack(Material.BLAZE_POWDER, 8));
        player.setLevel(0);

        InventoryClickEvent click = resultClick(ClickType.LEFT);
        new ParticleSeedListener(config, rewards).onAnvilResultTake(click);

        // キャンセルはする(バニラへ落とすと素材だけ食われる)が、消費はしない。
        verify(click).setCancelled(true);
        verify(inventory, never()).setSecondItem(any());
        verify(inventory, never()).setFirstItem(any());
        assertEquals(0, player.getLevel());
        assertTrue(player.getItemOnCursor().getType().isAir());
    }

    @Test
    @DisplayName("TFの付与でない金床のクリックには一切触らない")
    void anUnrelatedAnvilClickIsLeftToVanilla() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(true);
        // 右スロットがシード素材ではない = 普通の金床操作。
        placeToolAndSeed(new ItemStack(Material.DIAMOND_PICKAXE), new ItemStack(Material.DIAMOND));

        InventoryClickEvent click = resultClick(ClickType.LEFT);
        new ParticleSeedListener(config, rewards).onAnvilResultTake(click);

        verify(click, never()).setCancelled(true);
        verify(inventory, never()).setFirstItem(any());
        verify(inventory, never()).setSecondItem(any());
    }
}
