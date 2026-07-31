package com.trinityforge.gathering;

import com.trinityforge.mining.VeinMiningAlgorithm.BlockPos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChainBreakSupport} が連鎖1ブロックにつき<b>ルートテーブルを1回だけ引く</b>ことを固定する
 * (2026-07-31 G1 レビュー指摘5)。
 *
 * <p><b>なぜ World/Block をモックするのか</b>: MockBukkit の {@code Block#getDrops(tool, player)} は
 * 常に空コレクションを返す(未実装で例外を投げるのではなく<em>無言で空</em>)。したがって MockBukkit の
 * 実ワールドで「地面に落ちた物」を数えても常に0で、抽選回数も落下物の一致も検証できない。
 * ドロップを制御できる {@link Block} ダブルを使うのが唯一の道。
 *
 * <p>旧実装は {@code getDrops} で1回引いて EXP 側へ渡し、その直後の {@code breakNaturally(tool)} が
 * <em>同じテーブルをもう1回</em>引いて実際のドロップを撒いていた。つまり EXP({@code exp-mode: drop_sum})が
 * 実際に落ちた物とは別の抽選で計算され、抽選コストも常に2倍(葉は1回で最大1024枚)だった。
 */
class ChainBreakSupportSingleRollTest {

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** ドロップを固定した {@link Block} ダブル。{@code world.getBlockAt(x,y,z)} から返るよう配線する。 */
    private static Block stubBlock(World world, BlockPos pos, Material type, List<ItemStack> drops,
                                  ItemStack tool, PlayerMock player) {
        Block block = mock(Block.class);
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        when(world.getBlockAt(pos.x(), pos.y(), pos.z())).thenReturn(block);
        when(block.getType()).thenReturn(type);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(location);
        when(block.getDrops(tool, player)).thenReturn(drops);
        return block;
    }

    @Test
    void eachChainBrokenBlockRollsItsLootTableExactlyOnce() {
        World world = mock(World.class);
        ItemStack tool = new ItemStack(Material.IRON_AXE);
        BlockPos pos = new BlockPos(0, 64, 0);
        List<ItemStack> drops = List.of(new ItemStack(Material.OAK_LOG));
        Block block = stubBlock(world, pos, Material.OAK_LOG, drops, tool, player);

        int broken = ChainBreakSupport.breakChain(player, world, List.of(pos),
                material -> material == Material.OAK_LOG, tool, null, false);

        assertEquals(1, broken);
        verify(block, times(1)).getDrops(tool, player);
        verify(block, never()).breakNaturally(any(ItemStack.class));
        verify(block, never()).breakNaturally();
    }

    @Test
    void theStacksHandedToTheExpGrantAreExactlyTheStacksThatAreDropped() {
        World world = mock(World.class);
        ItemStack tool = new ItemStack(Material.IRON_AXE);
        BlockPos pos = new BlockPos(3, 70, -2);
        ItemStack log = new ItemStack(Material.OAK_LOG);
        ItemStack apple = new ItemStack(Material.APPLE, 2);
        Block block = stubBlock(world, pos, Material.OAK_LOG, List.of(log, apple), tool, player);

        Collection<ItemStack> granted = new ArrayList<>();
        ChainBreakExpGrant expGrant = (p, b, drops, t) -> granted.addAll(drops);

        ChainBreakSupport.breakChain(player, world, List.of(pos),
                material -> material == Material.OAK_LOG, tool, expGrant, false);

        assertEquals(List.of(log, apple), List.copyOf(granted),
                "EXPへ渡すのは実際に撒く抽選結果そのものであること");
        verify(block, times(1)).getDrops(tool, player);
        verify(block).setType(Material.AIR);
        verify(world).dropItemNaturally(block.getLocation(), log);
        verify(world).dropItemNaturally(block.getLocation(), apple);
    }

    @Test
    void emptyOrAirStacksAreNotSpawnedAsItemEntities() {
        // getDrops が「掘れない道具」で空を返すケース。ブロックは消えるが item entity は湧かないこと。
        World world = mock(World.class);
        ItemStack tool = new ItemStack(Material.WOODEN_HOE);
        BlockPos pos = new BlockPos(0, 64, 0);
        Block block = stubBlock(world, pos, Material.OAK_LOG, List.of(), tool, player);

        int broken = ChainBreakSupport.breakChain(player, world, List.of(pos),
                material -> material == Material.OAK_LOG, tool, null, false);

        assertEquals(1, broken, "ドロップが無くてもブロックは壊れること(breakNaturally と同じ)");
        verify(block).setType(Material.AIR);
        verify(world, never()).dropItemNaturally(any(Location.class), any(ItemStack.class));
    }
}
