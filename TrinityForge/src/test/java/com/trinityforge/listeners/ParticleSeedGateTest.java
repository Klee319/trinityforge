package com.trinityforge.listeners;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.progression.SpecialRewardService;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * パーティクルシードの合成が<b>解放済みのシードでしかできない</b>ことを固定する
 * (2026-08-21 実サーバ報告「パーティクルシードを入手しても実装がないのでは？アイテムが入手できなかった」)。
 *
 * <p><b>着手前の状態</b>: {@code ParticleSeedListener#onPrepareCraft} は保有を一切見ておらず、
 * 出荷 {@code seed-item} はブレイズパウダー・青氷・銅インゴットといった<b>誰でも手に入るバニラ材</b>
 * だった。つまりアチーブメント報酬の {@code special: [seed_*]} は
 * <b>持っていても持っていなくても結果が同じ</b> ── 報酬IDを読む処理が
 * コードベースのどこにも無く、実質「何も付与していない」状態だった
 * (称号は {@code equipTitle}、パーティクルは {@code equipParticle} が読むが、シードには読み手が無かった)。
 * 例外もログも出ないので、気づけるのは受け取った本人が「何も起きない」と気づいたときだけ。
 *
 * <p>このテストは<b>ゲートを外すと落ちる</b>ように書いてある ── {@code isUnlocked} の判定を
 * 消すと {@link #anUnownedSeedIsNotCombinable} が失敗する。
 */
class ParticleSeedGateTest {

    private static final String OWNED = "seed_flame";
    private static final String NOT_OWNED = "seed_frost";

    private ServerMock server;
    private SpecialRewardsConfig config;
    private SpecialRewardService rewards;
    private PlayerMock player;
    private CraftingInventory inventory;
    private PrepareItemCraftEvent event;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        config = mock(SpecialRewardsConfig.class);
        rewards = mock(SpecialRewardService.class);
        player = server.addPlayer();

        inventory = mock(CraftingInventory.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getPlayer()).thenReturn(player);
        event = mock(PrepareItemCraftEvent.class);
        when(event.getInventory()).thenReturn(inventory);
        when(event.getView()).thenReturn(view);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 盤面に「道具 + シード素材」の2つだけを置く(合成が成立する唯一の形)。 */
    private void placeToolAndSeed(Material seedMaterial) {
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = new ItemStack(Material.DIAMOND_PICKAXE);
        matrix[4] = new ItemStack(seedMaterial);
        when(inventory.getMatrix()).thenReturn(matrix);
    }

    private void seeds(Map<String, SpecialRewardsConfig.ParticleSeed> defined) {
        when(config.particleSeeds()).thenReturn(defined);
    }

    private static SpecialRewardsConfig.ParticleSeed seed(String id, Material item) {
        return new SpecialRewardsConfig.ParticleSeed(id, item.name(), Particle.FLAME, 6);
    }

    @Test
    @DisplayName("解放していないシードは合成できない(結果スロットを書き換えない)")
    void anUnownedSeedIsNotCombinable() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(false);
        placeToolAndSeed(Material.BLAZE_POWDER);

        new ParticleSeedListener(config, rewards).onPrepareCraft(event);

        verify(inventory, never()).setResult(any());
    }

    @Test
    @DisplayName("解放済みなら道具に刻印した結果が入る")
    void anOwnedSeedStampsTheTool() {
        seeds(Map.of(OWNED, seed(OWNED, Material.BLAZE_POWDER)));
        when(rewards.isUnlocked(player, OWNED)).thenReturn(true);
        placeToolAndSeed(Material.BLAZE_POWDER);

        new ParticleSeedListener(config, rewards).onPrepareCraft(event);

        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory, times(1)).setResult(captor.capture());
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

        new ParticleSeedListener(config, rewards).onPrepareCraft(event);

        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory, times(1)).setResult(captor.capture());
        assertEquals(Optional.of(OWNED), ItemData.of(captor.getValue().getItemMeta()).particleSeed());
    }

    @Test
    @DisplayName("シードが1つも定義されていなければ保有判定にすら行かない(通常のクラフトを邪魔しない)")
    void anEmptyRegistryNeverTouchesTheBoard() {
        seeds(Map.of());
        placeToolAndSeed(Material.BLAZE_POWDER);

        new ParticleSeedListener(config, rewards).onPrepareCraft(event);

        verify(inventory, never()).setResult(any());
        verify(rewards, never()).isUnlocked(any(), any());
        assertTrue(config.particleSeeds().isEmpty());
    }
}
