package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.FarmingGimmickConfig;
import com.trinityforge.gathering.ChainBreakExpGrant;
import com.trinityforge.pdc.PlayerData;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.Item;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FarmingHarvestListener#onBlockBreak}: 2026-07-25 gather-rework-active-framework §1(area-harvest
 * SCALE化・tier解決)/§2 B-2(プレイヤートグルOFFで無効化)の回帰確認。
 */
class FarmingHarvestListenerTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private FarmingGimmickConfig gimmickConfig;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(FarmingGimmickConfig.class);
        player = server.addPlayer();
        // 2026-07-27: 範囲収穫にツール判定(GatheringToolMatcher)が入ったため、素手のままだと
        // 発動しない。素のバニラの鍬はマテリアル推論で FARMING として通る。
        // (auto-replant 側は意図的にツール判定なしなので、素手でも従来どおり動く)
        player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.DIAMOND_HOE));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private FarmingHarvestListener listener() {
        return new FarmingHarvestListener(MockBukkit.createMockPlugin(), dedicatedEffects, gimmickConfig,
                new FeedbackLayer());
    }

    private static Block matureWheat(PlayerMock player, int x, int z) {
        Block block = player.getWorld().getBlockAt(x, 64, z);
        block.setType(Material.WHEAT);
        Ageable ageable = (Ageable) block.getBlockData();
        ageable.setAge(ageable.getMaximumAge());
        block.setBlockData(ageable);
        return block;
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    void rightClickMatureCropHarvestsReplantsAndSuppressesNativeInteractExperience() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.empty());
        ChainBreakExpGrant expGrant = mock(ChainBreakExpGrant.class);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), expGrant),
                plugin);
        IgnoredCancelledMonitor nativeInteractProbe = new IgnoredCancelledMonitor();
        server.getPluginManager().registerEvents(nativeInteractProbe, plugin);
        BreakPathProbe breakPathProbe = new BreakPathProbe();
        server.getPluginManager().registerEvents(breakPathProbe, plugin);

        Block crop = matureWheat(player, 0, 0);
        ((BlockMock) crop).setDrops(List.of(
                new ItemStack(Material.WHEAT),
                new ItemStack(Material.WHEAT_SEEDS, 2)));
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                crop, BlockFace.UP, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(event);

        assertEquals(Material.WHEAT, crop.getType());
        assertEquals(0, ((Ageable) crop.getBlockData()).getAge(),
                "right-click harvest must immediately replant the crop at age 0");
        assertEquals(1, droppedAmount(Material.WHEAT),
                "right-click harvest must drop the harvested produce");
        assertEquals(1, droppedAmount(Material.WHEAT_SEEDS),
                "replanting must consume exactly one seed from the harvested drops");
        assertTrue(event.isCancelled(),
                "handled interaction must be cancelled so MONITOR ignoreCancelled listeners do not double-grant EXP");
        assertEquals(0, nativeInteractProbe.calls,
                "NativeSkillExperienceListener's ignoreCancelled right-click path must be suppressed");
        assertEquals(1, breakPathProbe.calls,
                "normal BlockBreakEvent listeners must receive the authorized harvest exactly once");
        verifyNoInteractions(expGrant);
    }

    private int droppedAmount(Material material) {
        return player.getWorld().getEntitiesByClass(Item.class).stream()
                .map(Item::getItemStack)
                .filter(stack -> stack.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    @Test
    void rightClickDoesNotHarvestWhenBreakProtectionCancelsSyntheticEvent() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), mock(ChainBreakExpGrant.class)),
                plugin);
        CancellingBreakProtection protection = new CancellingBreakProtection();
        server.getPluginManager().registerEvents(protection, plugin);

        Block crop = matureWheat(player, 0, 0);
        ((BlockMock) crop).setDrops(List.of(new ItemStack(Material.WHEAT)));
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                crop, BlockFace.UP, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(event);

        Ageable ageable = (Ageable) crop.getBlockData();
        assertEquals(ageable.getMaximumAge(), ageable.getAge(),
                "cancelled break authorization must leave the crop untouched");
        assertEquals(0, droppedAmount(Material.WHEAT),
                "cancelled break authorization must not drop the crop");
        assertEquals(1, protection.calls);
        assertTrue(event.isCancelled(),
                "the intercepted right click must remain cancelled after break authorization is denied");
    }

    @Test
    void rightClickHonorsSyntheticBreakDropSuppressionWhileReplanting() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), mock(ChainBreakExpGrant.class)),
                plugin);
        SuppressingDropsBreakListener dropSuppressor = new SuppressingDropsBreakListener();
        server.getPluginManager().registerEvents(dropSuppressor, plugin);

        Block crop = matureWheat(player, 0, 0);
        ((BlockMock) crop).setDrops(List.of(new ItemStack(Material.WHEAT)));
        PlayerInteractEvent event = new PlayerInteractEvent(
                player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItemInMainHand(),
                crop, BlockFace.UP, EquipmentSlot.HAND);

        server.getPluginManager().callEvent(event);

        assertEquals(0, ((Ageable) crop.getBlockData()).getAge(),
                "drop suppression must not prevent the authorized replant");
        assertEquals(0, droppedAmount(Material.WHEAT),
                "setDropItems(false) on the synthetic break must suppress base drops");
        assertEquals(1, dropSuppressor.calls);
        assertTrue(event.isCancelled());
    }

    @Test
    void areaHarvestToggleOffLeavesNeighborUnharvestedEvenWhenUnlocked() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(false);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
        PlayerData.of(player).setAreaHarvestEnabled(false);

        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.WHEAT, neighbor.getType(), "toggle OFF must not area-harvest the neighbor");
    }

    @Test
    void areaHarvestHarvestsNeighborWhenToggleOnAndUnlocked() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(false);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
        // areaHarvestEnabled defaults to true; no explicit set needed.

        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(), "toggle ON + unlocked must area-harvest the neighbor");
    }

    // --- W-142(2026-08-19) 実サーバ報告「範囲収穫機能が機能していない」 ---

    /**
     * 範囲収穫のゲートは {@code onBlockBreak} 側にしか無く、auto-replant を解放したプレイヤーの
     * <b>右クリック収穫では一度も到達しなかった</b>。area-harvest を持つノード(C)は auto-replant を
     * 持つノード(B)の子なので、解放した全員が右クリック収穫を先に持っている = 主要な収穫動作では
     * 機能ゼロに見えていた。
     */
    @Test
    void rightClickHarvestAlsoAreaHarvestsTheNeighbor() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), mock(ChainBreakExpGrant.class)),
                plugin);

        Block crop = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);

        server.getPluginManager().callEvent(rightClick(crop));

        // 隣は「収穫(=AIR)」まで済んでいれば範囲収穫が走った証拠(植え直しは1tick後のタスク)。
        assertEquals(Material.AIR, neighbor.getType(),
                "右クリック収穫からも範囲収穫が発動すること");
        assertEquals(Material.WHEAT, crop.getType(), "起点はその場で age0 に植え直される");
        assertEquals(0, ((Ageable) crop.getBlockData()).getAge());
    }

    @Test
    void rightClickHarvestDoesNotAreaHarvestWhenTheToggleIsOff() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
        PlayerData.of(player).setAreaHarvestEnabled(false);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), mock(ChainBreakExpGrant.class)),
                plugin);

        Block crop = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);

        server.getPluginManager().callEvent(rightClick(crop));

        assertEquals(Material.WHEAT, crop.getType(), "自動再植そのものは効いたまま");
        assertEquals(0, ((Ageable) crop.getBlockData()).getAge());
        assertEquals(7, ((Ageable) neighbor.getBlockData()).getAge(),
                "トグルOFFなら右クリック収穫でも隣に手を出さない");
    }

    /** 右クリック収穫は鍬を要求する(破壊経路と同じゲート)。 */
    @Test
    void rightClickHarvestDoesNotAreaHarvestWithoutAHoe() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.STICK));
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(
                new FarmingHarvestListener(plugin, dedicatedEffects, gimmickConfig,
                        new FeedbackLayer(), mock(ChainBreakExpGrant.class)),
                plugin);

        Block crop = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);

        server.getPluginManager().callEvent(rightClick(crop));

        assertEquals(0, ((Ageable) crop.getBlockData()).getAge(),
                "素手/棒でも自動再植は従来どおり効く(意図的にツール判定なし)");
        assertEquals(7, ((Ageable) neighbor.getBlockData()).getAge(),
                "鍬でなければ範囲収穫は発動しない");
    }

    private PlayerInteractEvent rightClick(Block block) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), block, BlockFace.UP, EquipmentSlot.HAND);
    }

    // --- 2026-07-31 G1 round2 レビュー指摘7: 範囲収穫もルートテーブルを1回だけ引く ---

    /**
     * area-harvest の共通スタブ(半径1・auto-replant は引数で切り替え)。
     */
    private void stubAreaHarvest(boolean autoReplant) {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(autoReplant);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.areaHarvestRadius(1)).thenReturn(1);
    }

    private FarmingHarvestListener listener(ChainBreakExpGrant expGrant) {
        return new FarmingHarvestListener(MockBukkit.createMockPlugin(), dedicatedEffects, gimmickConfig,
                new FeedbackLayer(), expGrant);
    }

    @Test
    void areaHarvestRollsTheLootTableOnceSoTheExpAndTheDropsCannotDisagree() {
        // 旧実装は EXP 用に1回(grantExpFor -> getDrops)、実ドロップ用にもう1回
        // (breakNaturally / readDrops)引いていた。確率ドロップだと「EXPの根拠」と「手に入る物」が
        // 食い違い、抽選コストも2倍だった(一括伐採/一括採掘は既に1回化済みで、範囲収穫だけ残っていた)。
        //
        // 「2回引いたか」を観測するために、EXP付与の瞬間にブロックの抽選結果を差し替える —
        // 2回目を引く実装なら差し替え後の DIAMOND が落ちる。1回だけ引く実装なら落ちない。
        stubAreaHarvest(false);
        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);
        ((BlockMock) neighbor).setDrops(List.of(new ItemStack(Material.WHEAT)));

        List<ItemStack> grantedToExp = new java.util.ArrayList<>();
        ChainBreakExpGrant expGrant = (p, block, drops, tool) -> {
            grantedToExp.addAll(drops);
            ((BlockMock) block).setDrops(List.of(new ItemStack(Material.DIAMOND, 5)));
        };

        listener(expGrant).onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(), "隣接マスは収穫されること(前提の確認)");
        assertEquals(1, grantedToExp.size());
        assertEquals(Material.WHEAT, grantedToExp.get(0).getType());
        assertEquals(1, droppedAmount(Material.WHEAT),
                "EXPの根拠になった抽選結果がそのまま地面に出ること");
        assertEquals(0, droppedAmount(Material.DIAMOND),
                "テーブルを2回引いていないこと(2回目を引く実装ならここで DIAMOND が落ちる)");
    }

    @Test
    void areaHarvestWithAutoReplantSubtractsOneSeedFromTheSameSingleRoll() {
        // 自動再植ありの経路も同じ1回の抽選結果から種1個を差し引くこと(旧実装はここでも引き直していた)。
        stubAreaHarvest(true);
        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);
        ((BlockMock) neighbor).setDrops(List.of(
                new ItemStack(Material.WHEAT),
                new ItemStack(Material.WHEAT_SEEDS, 2)));

        ChainBreakExpGrant expGrant = (p, block, drops, tool) ->
                ((BlockMock) block).setDrops(List.of(new ItemStack(Material.DIAMOND, 5)));

        listener(expGrant).onBlockBreak(breakEvent(origin));

        assertEquals(1, droppedAmount(Material.WHEAT));
        assertEquals(1, droppedAmount(Material.WHEAT_SEEDS), "再植のぶん種1個を差し引くこと");
        assertEquals(0, droppedAmount(Material.DIAMOND), "テーブルを2回引いていないこと");

        server.getScheduler().performTicks(3L);
        assertEquals(Material.WHEAT, neighbor.getType(), "1tick後に age0 で再植されること");
        assertEquals(0, ((Ageable) neighbor.getBlockData()).getAge());
    }

    // --- 2026-07-31 G1 round2 レビュー指摘4: setType + dropItemNaturally は doTileDrops を見る ---

    @Test
    void areaHarvestDropsNothingWhenTheDoTileDropsGameRuleIsOff() {
        // 旧 breakNaturally は NMS の popResource 経由で doTileDrops を見ていたが、
        // World#dropItemNaturally は一切見ない。範囲収穫だけがドロップを出す非対称を消す。
        player.getWorld().setGameRule(org.bukkit.GameRules.BLOCK_DROPS, false);
        stubAreaHarvest(false);
        Block origin = matureWheat(player, 0, 0);
        Block neighbor = matureWheat(player, 1, 0);
        ((BlockMock) neighbor).setDrops(List.of(new ItemStack(Material.WHEAT)));

        listener(mock(ChainBreakExpGrant.class)).onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(), "doTileDrops=false でも収穫自体は起きること");
        assertEquals(0, droppedAmount(Material.WHEAT),
                "doTileDrops=false ならアイテムは湧かないこと(バニラと同じ)");
    }

    @Test
    void autoReplantToggleOffLeavesVanillaDropsUncancelledEvenWhenUnlocked() {
        when(dedicatedEffects.isActive(any(), eq("auto-replant"))).thenReturn(true);
        when(dedicatedEffects.valueMax(any(), eq("area-harvest"))).thenReturn(OptionalDouble.empty());
        PlayerData.of(player).setAutoReplantEnabled(false);

        Block origin = matureWheat(player, 0, 0);
        BlockBreakEvent event = breakEvent(origin);

        listener().onBlockBreak(event);

        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setDropItems(false);
    }

    private static final class IgnoredCancelledMonitor implements Listener {
        private int calls;

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onInteract(PlayerInteractEvent event) {
            calls++;
        }
    }

    private static final class BreakPathProbe implements Listener {
        private int calls;

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onBlockBreak(BlockBreakEvent event) {
            calls++;
        }
    }

    private static final class CancellingBreakProtection implements Listener {
        private int calls;

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent event) {
            calls++;
            event.setCancelled(true);
        }
    }

    private static final class SuppressingDropsBreakListener implements Listener {
        private int calls;

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent event) {
            calls++;
            event.setDropItems(false);
        }
    }
}
