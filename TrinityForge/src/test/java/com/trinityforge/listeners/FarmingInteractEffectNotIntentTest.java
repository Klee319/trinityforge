package com.trinityforge.listeners;

import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.type.Beehive;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 農業の {@code block_interact} EXP を「クリックした(意図)」ではなく
 * 「実際に収穫が起きた(結果)」で配ることを固定する (2026-08-25 / W-245)。
 *
 * <h2>直している実バグ</h2>
 * バニラは<b>「スニーク中で、かつ手が空でない」ならブロックへの操作を丸ごと飛ばす</b>
 * (ブロックを設置するための仕様)。旧実装は {@link PlayerInteractEvent} だけを見て配っていたので、
 * <b>スニークしたまま熟したベリーや満タンの巣を連打すると、実が減らないまま農業EXPが無限に入った</b>。
 * {@link PlayerInteractEvent} はこのときキャンセルされないので {@code ignoreCancelled = true} では
 * 防げない。
 *
 * <p>これは W-210(盾を持つと斧の皮剥ぎが {@code PASS} になり、原木が残るので連打でEXP)と
 * <b>同じ形の穴</b>。同じ規則で塞ぐ: <b>意図ではなく結果を見る。</b>
 */
class FarmingInteractEffectNotIntentTest {

    private static final double BERRY_EXP = 12.0;
    private static final double HIVE_EXP = 20.0;

    private ServerMock server;
    private PlayerMock player;
    private NativeExperienceDispatcher dispatcher;
    private NativeSkillExperienceListener listener;
    private final List<Runnable> deferred = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        SkillCatalogEntry farming = new SkillCatalogEntry("FARMING", 100, "1", level -> 1L,
                Map.of("block_interact.SWEET_BERRY_BUSH", BERRY_EXP,
                        "block_interact.BEEHIVE", HIVE_EXP),
                Map.of());
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.FARMING)).thenReturn(farming);
        dispatcher = mock(NativeExperienceDispatcher.class);
        listener = new NativeSkillExperienceListener(
                plugin, dispatcher, catalog, mock(PlacedBlockTracker.class));
        // 次tick確認は「あとで走らせる箱」に溜める。テストが明示的に流すまで走らない。
        listener.honeyHarvestVerifierForTest(deferred::add);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void runDeferred() {
        List<Runnable> tasks = new ArrayList<>(deferred);
        deferred.clear();
        tasks.forEach(Runnable::run);
    }

    private PlayerInteractEvent rightClick(Block block, Material inHand) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                new ItemStack(inHand), block, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
    }

    private Block ripeBerryBush() {
        Block block = mock(Block.class);
        Ageable berries = mock(Ageable.class);
        when(block.getType()).thenReturn(Material.SWEET_BERRY_BUSH);
        when(block.getBlockData()).thenReturn(berries);
        when(berries.getAge()).thenReturn(3);
        return block;
    }

    /**
     * ★ 中核の回帰。ここが通らなくなる(= 右クリックだけで配る実装に戻る)と、スニーク連打の
     * 無限EXPが復活する。
     */
    @Test
    @DisplayName("熟したベリーへの右クリックだけでは配らない（収穫が起きた証拠が無い）")
    void berryRightClickAloneGrantsNothing() {
        listener.onFarmingInteract(rightClick(ripeBerryBush(), Material.GLASS_BOTTLE));

        verify(dispatcher, never()).grant(any(), eq(SkillId.FARMING), anyDouble());
    }

    @Test
    @DisplayName("収穫が確定したときだけ配る（PlayerHarvestBlockEvent 経由）")
    void berryGrantsOnActualHarvest() {
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.SWEET_BERRY_BUSH);

        listener.onFarmingHarvest(new org.bukkit.event.player.PlayerHarvestBlockEvent(
                player, block, EquipmentSlot.HAND, List.of(new ItemStack(Material.SWEET_BERRIES))));

        verify(dispatcher).grant(player.getUniqueId(), SkillId.FARMING, BERRY_EXP);
    }

    @Test
    @DisplayName("巣の蜜が実際に減ったときだけ配る")
    void hiveGrantsOnlyWhenHoneyActuallyDropped() {
        Block hiveBlock = mock(Block.class);
        Beehive hive = mock(Beehive.class);
        when(hiveBlock.getType()).thenReturn(Material.BEEHIVE);
        when(hiveBlock.getBlockData()).thenReturn(hive);
        when(hive.getMaximumHoneyLevel()).thenReturn(5);
        when(hive.getHoneyLevel()).thenReturn(5);

        listener.onFarmingInteract(rightClick(hiveBlock, Material.GLASS_BOTTLE));
        // 確認が走る前は配られていない。
        verify(dispatcher, never()).grant(any(), eq(SkillId.FARMING), anyDouble());

        // 実際に採れた ＝ 蜜量が落ちた。
        when(hive.getHoneyLevel()).thenReturn(0);
        runDeferred();
        verify(dispatcher, times(1)).grant(player.getUniqueId(), SkillId.FARMING, HIVE_EXP);
    }

    /**
     * スニーク中に起きていたのがこれ。クリックは届くがバニラの操作が走らないので蜜量は変わらない。
     */
    @Test
    @DisplayName("蜜量が変わらなければ配らない（スニーク連打で無限に入った経路）")
    void hiveGrantsNothingWhenHoneyStaysFull() {
        Block hiveBlock = mock(Block.class);
        Beehive hive = mock(Beehive.class);
        when(hiveBlock.getType()).thenReturn(Material.BEEHIVE);
        when(hiveBlock.getBlockData()).thenReturn(hive);
        when(hive.getMaximumHoneyLevel()).thenReturn(5);
        when(hive.getHoneyLevel()).thenReturn(5);

        for (int i = 0; i < 5; i++) {
            listener.onFarmingInteract(rightClick(hiveBlock, Material.GLASS_BOTTLE));
        }
        runDeferred();

        verify(dispatcher, never()).grant(any(), eq(SkillId.FARMING), anyDouble());
    }

    /**
     * {@code isHarvestableFarmingInteraction} は {@code XpBottleListener} が
     * 「このクリックはバニラの用途だから奪わない」を判断するのにも使っている。
     * EXP 経路を差し替えても、この判定自体は変えていないことを固定する
     * ── ここを一緒にいじると、ベリーに向けた瓶のクリックが経験値格納に化ける。
     */
    @Test
    @DisplayName("収穫判定の純関数は据え置き（経験値瓶がベリーのクリックを奪わないための土台）")
    void harvestPredicateIsUnchanged() {
        Block bush = ripeBerryBush();
        org.junit.jupiter.api.Assertions.assertTrue(
                NativeSkillExperienceListener.isHarvestableFarmingInteraction(bush, null));
        org.junit.jupiter.api.Assertions.assertFalse(
                NativeSkillExperienceListener.isHarvestableFarmingInteraction(
                        bush, new ItemStack(Material.BONE_MEAL)));
    }
}
