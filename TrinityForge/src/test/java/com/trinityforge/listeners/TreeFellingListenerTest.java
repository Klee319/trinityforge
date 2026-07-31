package com.trinityforge.listeners;

import com.trinityforge.active.ActiveSkillCooldownKeys;
import com.trinityforge.active.CooldownManager;
import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.WoodcuttingGimmickConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TreeFellingListener#onBlockBreakDropTables}: 2026-07-23 verifier指摘⑧ — drop-table roll is its
 * own {@code MONITOR}+{@code ignoreCancelled=true} handler, and excludes player-placed log/leaves blocks
 * ({@link PlacedBlockTracker#isPlaced}, 読み取り専用) so 原木設置→破壊のリンゴ量産 loop is closed.
 */
class TreeFellingListenerTest {

    private static final String COOLDOWN_REDUCTION_KEY = ActiveSkillCooldownKeys.forSkill("tree-fell");

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private WoodcuttingGimmickConfig gimmickConfig;
    private CrossPluginItemResolver itemResolver;
    private PlacedBlockTracker placedBlockTracker;
    private CooldownManager cooldowns;
    private PlayerStatAggregator aggregator;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(WoodcuttingGimmickConfig.class);
        itemResolver = mock(CrossPluginItemResolver.class);
        placedBlockTracker = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        cooldowns = new CooldownManager();
        aggregator = mock(PlayerStatAggregator.class);
        stubCooldownReduction(0.0);
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of());
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * Stubs {@code aggregator.aggregate(any()).totalOf(tree-fell-cooldown-reduction)} (PRG-07: the
     * shared {@link CooldownManager}-backed CT now reads this key instead of the private
     * {@code ConcurrentHashMap} the pre-fix listener used).
     */
    private void stubCooldownReduction(double value) {
        when(aggregator.aggregate(any())).thenReturn(new PlayerCombatAggregate(
                Map.of(COOLDOWN_REDUCTION_KEY, value), Map.of(), Map.of(), Map.of(), Map.of()));
    }

    private TreeFellingListener listener() {
        return listener(null);
    }

    private TreeFellingListener listener(com.trinityforge.gathering.ChainBreakExpGrant chainBreakExp) {
        return new TreeFellingListener(dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker,
                new FeedbackLayer(), cooldowns, aggregator, chainBreakExp);
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    void ignoresBlockThatIsNeitherLogNorLeaves() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void ignoresPlayerPlacedLogBlock() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.OAK_LOG);
        placedBlockTracker.markPlaced(block);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void logBreakRollsDropTableWhenNotPlacedByPlayer() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));
        when(itemResolver.create(eq("APPLE"))).thenReturn(Optional.of(new ItemStack(Material.APPLE)));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.OAK_LOG);

        int itemsBefore = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreakDropTables(breakEvent(block));

        int itemsAfter = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(1, itemsAfter - itemsBefore,
                "trigger-chance-percent=100 must always draw+drop the sole open entry");
    }

    @Test
    void treeFellToggleOffPreventsChainFellEvenWhenUnlocked() {
        // 2026-07-25 gather-rework-active-framework §2 B-2: プレイヤートグルOFFなら一括伐採しない。
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);
        PlayerData.of(player).setTreeFellEnabled(false);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.OAK_LOG);
        neighbor.setType(Material.OAK_LOG);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.OAK_LOG, neighbor.getType(), "toggle OFF must not chain-fell neighbors");
    }

    @Test
    void treeFellChainFellsNeighborWhenToggleOnAndUnlocked() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);
        // treeFellEnabled defaults to true; no explicit set needed.

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.OAK_LOG);
        neighbor.setType(Material.OAK_LOG);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(), "toggle ON + unlocked must chain-fell the neighbor");
    }

    // --- 2026-07-28 実サーバ報告「一括伐採で経験値が入らない / 耐久も減っていない」 ---

    @Test
    void chainFelledLogsGrantGatheringExpForEachBrokenBlock() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        origin.setType(Material.OAK_LOG);
        for (int y = 65; y <= 67; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }

        List<Material> granted = new java.util.ArrayList<>();
        listener((p, block, drops, tool) -> granted.add(block.getType()))
                .onBlockBreak(breakEvent(origin));

        // 起点はイベント本体(NativeSkillExperienceListener#onBlockBreak)が処理するのでここには来ない。
        // 連鎖分3本ぶんが「破壊前のマテリアル」で渡ること。
        assertEquals(List.of(Material.OAK_LOG, Material.OAK_LOG, Material.OAK_LOG), granted);
    }

    @Test
    void chainFelledLogsConsumeOneDurabilityPerBlock() {
        ItemStack axe = new ItemStack(Material.IRON_AXE);
        player.getInventory().setItemInMainHand(axe);
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        origin.setType(Material.OAK_LOG);
        for (int y = 65; y <= 66; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }

        listener().onBlockBreak(breakEvent(origin));

        ItemStack held = player.getInventory().getItemInMainHand();
        int damage = held.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d ? d.getDamage() : 0;
        assertEquals(2, damage, "連鎖破壊した2ブロックぶんの耐久が減ること(旧実装は0のままだった)");
    }

    @Test
    void tierOneDoesNotPadSevenLogTreeToEightBrokenBlocks() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        for (int y = 64; y < 71; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }

        List<Block> chainBreaks = new java.util.ArrayList<>();
        listener((p, block, drops, tool) -> chainBreaks.add(block))
                .onBlockBreak(breakEvent(origin));

        long logsAfterChain = java.util.stream.IntStream.range(64, 71)
                .filter(y -> player.getWorld().getBlockAt(0, y, 0).getType() == Material.OAK_LOG)
                .count();
        assertEquals(6, chainBreaks.size(),
                "A seven-log tree has only six additional logs; the Tier 1 ceiling must not pad it to eight");
        assertEquals(1, logsAfterChain,
                "only the event origin must remain for the original vanilla break");

        // MockBukkit does not perform the original event's vanilla break, so finish that one break
        // explicitly after the listener has chain-felled the six connected neighbors.
        origin.breakNaturally(player.getInventory().getItemInMainHand());
        long logsAfterOriginalBreak = java.util.stream.IntStream.range(64, 71)
                .filter(y -> player.getWorld().getBlockAt(0, y, 0).getType() == Material.OAK_LOG)
                .count();
        assertEquals(0, logsAfterOriginalBreak,
                "the complete operation must remove exactly the seven blocks that existed");
    }

    @Test
    void chainFellingBreaksToolAtCustomMaxDamageWithoutExceedingIt() {
        ItemStack axe = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = axe.getItemMeta();
        Damageable damageable = (Damageable) meta;
        damageable.setMaxDamage(2);
        damageable.setDamage(1);
        axe.setItemMeta(meta);
        player.getInventory().setItemInMainHand(axe);
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(8);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block firstNeighbor = player.getWorld().getBlockAt(0, 65, 0);
        Block secondNeighbor = player.getWorld().getBlockAt(0, 66, 0);
        origin.setType(Material.OAK_LOG);
        firstNeighbor.setType(Material.OAK_LOG);
        secondNeighbor.setType(Material.OAK_LOG);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, player.getInventory().getItemInMainHand().getType(),
                "custom max damage must break the tool before Damageable#setDamage exceeds its limit");
        assertEquals(Material.OAK_LOG, secondNeighbor.getType(),
                "chain felling must stop as soon as the custom-durability tool breaks");
    }

    // --- 2026-07-31 N1 実サーバ報告「伐採で底面と側面から壊したときと真上から壊したときとで壊れる範囲が違う」 ---

    /** 一括伐採の共通スタブ(tier1・上限 {@code maxExtra}・CT 10秒)。 */
    private void stubTreeFell(int maxExtra) {
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        when(dedicatedEffects.valueMax(any(), eq("tree-fell"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.treeFellMaxExtraLogs(1)).thenReturn(maxExtra);
        when(gimmickConfig.treeFellCooldownTicks()).thenReturn(200);
    }

    /**
     * {@code columnX} に高さ {@code height} の幹柱を立て、下から {@code struckY} 段目を叩いて、
     * 最終的に消えた段(相対y)を返す。MockBukkit はイベント本体のバニラ破壊を行わないので、
     * リスナー実行後に叩いた1本を明示的に壊して「実サーバで実際に消えるブロック」を再現する。
     */
    private java.util.Set<Integer> removedRelativeYs(int columnX, int struckY, int height) {
        for (int y = 64; y < 64 + height; y++) {
            player.getWorld().getBlockAt(columnX, y, 0).setType(Material.OAK_LOG);
        }
        // ケースごとにCTを空にする(同一テスト内で複数回叩くため)。
        cooldowns = new CooldownManager();
        Block origin = player.getWorld().getBlockAt(columnX, 64 + struckY, 0);

        listener().onBlockBreak(breakEvent(origin));
        origin.breakNaturally(player.getInventory().getItemInMainHand());

        java.util.Set<Integer> removed = new java.util.TreeSet<>();
        for (int y = 64; y < 64 + height; y++) {
            if (player.getWorld().getBlockAt(columnX, y, 0).getType() == Material.AIR) {
                removed.add(y - 64);
            }
        }
        return removed;
    }

    @Test
    void felledBlockSetIsIdenticalWhicheverBlockOfATreeUnderTheCapIsStruck() {
        // 上限(8)に収まる7段の木: 最下段/中段/最上段のどこを叩いても消えるブロックが完全に一致すること。
        stubTreeFell(8);

        java.util.Set<Integer> fromBottom = removedRelativeYs(0, 0, 7);
        java.util.Set<Integer> fromMiddle = removedRelativeYs(5, 3, 7);
        java.util.Set<Integer> fromTop = removedRelativeYs(10, 6, 7);

        assertEquals(java.util.Set.of(0, 1, 2, 3, 4, 5, 6), fromBottom, "木全体が倒れること");
        assertEquals(fromBottom, fromMiddle, "中段を叩いた結果が最下段と一致すること");
        assertEquals(fromBottom, fromTop, "最上段を叩いた結果が最下段と一致すること");
    }

    @Test
    void cappedTreeRemovesTheSameBlocksFromEveryStruckBlockInsideTheFelledWindow() {
        // 12段の木 + 上限8本 = 伐採される窓は根元から9段(連鎖8本 + 叩いた1本)。旧実装は起点相対に
        // 等方展開していたので、真上から叩くと根元が伐り残って切り株が浮いていた。
        stubTreeFell(8);
        java.util.Set<Integer> expected = java.util.Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8);

        assertEquals(expected, removedRelativeYs(0, 0, 12), "最下段を叩いた場合");
        assertEquals(expected, removedRelativeYs(5, 4, 12), "中段を叩いた場合");
        assertEquals(expected, removedRelativeYs(10, 8, 12), "窓の最上段を叩いた場合");
    }

    @Test
    void cappedTreeLeavesTheCrownNotTheStumpWhenStruckFromAbove() {
        // 窓より上を叩いた場合、叩いた1本はイベント本体が壊すので必ず消える(TF には止められない)。
        // 重要なのは連鎖対象が常に根元からで、樹冠側が残ること = 切り株が浮かないこと。
        stubTreeFell(8);

        java.util.Set<Integer> removed = removedRelativeYs(0, 11, 12);

        assertEquals(java.util.Set.of(0, 1, 2, 3, 4, 5, 6, 7, 11), removed,
                "根元から8本 + 叩いた1本が消え、残るのは樹冠側(8,9,10)であること");
    }

    @Test
    void cooldownIsNotConsumedWhenThereIsNothingToFell() {
        // 旧実装は走査より前にCTを消費していたので、1本も伐れない破壊でも10秒のCTを取られ、
        // 面を変えて試した2回目が無言で不発になっていた。
        stubTreeFell(8);
        Block lonely = player.getWorld().getBlockAt(0, 64, 0);
        lonely.setType(Material.OAK_LOG);

        listener().onBlockBreak(breakEvent(lonely));

        assertEquals(0L, cooldowns.remainingMillis(player.getUniqueId(), "tree-fell", 10_000L,
                        System.currentTimeMillis()),
                "連鎖対象0本ならCTを消費しないこと");
    }

    // --- 2026-07-31 N2 実サーバ要望「一括伐採時の葉の自動破壊がバニラより大幅に速くなるように」 ---

    /** 葉の巻き込みスタブ。{@code decayOnly} と枚数上限を明示する(モック既定の false/0 では機構ごと無効)。 */
    private void stubLeaves(int maxLeaves, boolean decayOnly) {
        when(gimmickConfig.treeFellBreakLeaves()).thenReturn(true);
        when(gimmickConfig.treeFellMaxLeaves(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(maxLeaves);
        when(gimmickConfig.treeFellLeavesDecayOnly()).thenReturn(decayOnly);
        when(gimmickConfig.treeFellLeavesPerTick()).thenReturn(0); // 同tickで全部壊す(テストの決定性)
    }

    /**
     * 12段の幹(y=64..75) + 上限8本 = 連鎖は y=65..72、y=73..75 が伐り残る。樹冠は伐り残した幹に
     * だけ接している — 旧実装のように「伐った丸太だけ」を種にすると到達できない配置。
     */
    private java.util.List<Block> tallTreeWithCanopyOnTheUnfelledTrunk() {
        for (int y = 64; y <= 75; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        Block nearLeaf = player.getWorld().getBlockAt(1, 75, 0);
        Block farLeaf = player.getWorld().getBlockAt(2, 75, 0);
        nearLeaf.setType(Material.OAK_LEAVES);
        farLeaf.setType(Material.OAK_LEAVES);
        return List.of(nearLeaf, farLeaf);
    }

    @Test
    void leafSeedsAreTheWholeTreeSoTheCanopyOnTheUnfelledTrunkIsReached() {
        stubTreeFell(8);
        stubLeaves(512, false);
        java.util.List<Block> canopy = tallTreeWithCanopyOnTheUnfelledTrunk();

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        assertEquals(Material.AIR, canopy.get(0).getType(),
                "伐り残した幹に接する葉へ到達すること(旧実装では0枚だった)");
        assertEquals(Material.AIR, canopy.get(1).getType(), "そこから葉を辿って広がること");
    }

    @Test
    void decayOnlyKeepsLeavesStillSupportedByTheUnfelledTrunk() {
        // leaves-decay-only=true は「バニラなら崩壊しない葉」に触らない。残った幹(y=75)から距離1/2の
        // 葉はバニラでも崩壊しないので残る — これが隣の木の樹冠を守る防波堤そのもの。
        stubTreeFell(8);
        stubLeaves(512, true);
        java.util.List<Block> canopy = tallTreeWithCanopyOnTheUnfelledTrunk();

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        assertEquals(Material.OAK_LEAVES, canopy.get(0).getType(), "残存原木に支えられた葉は壊さない");
        assertEquals(Material.OAK_LEAVES, canopy.get(1).getType(), "距離6以内なので同様に残す");
    }

    @Test
    void decayOnlyBreaksTheCanopyOnceItsSupportingTrunkIsGone() {
        stubTreeFell(8);
        stubLeaves(512, true);
        for (int y = 64; y <= 66; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        Block leafA = player.getWorld().getBlockAt(1, 66, 0);
        Block leafB = player.getWorld().getBlockAt(2, 66, 0);
        leafA.setType(Material.OAK_LEAVES);
        leafB.setType(Material.OAK_LEAVES);

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        assertEquals(Material.AIR, leafA.getType(), "支えが無くなった葉は即時に壊すこと");
        assertEquals(Material.AIR, leafB.getType());
    }

    @Test
    void decayOnlySkipsPersistentLeavesThatVanillaWouldNeverDecay() {
        // 装飾の葉壁(persistent=true)は上限を桁で上げても削らない。
        stubTreeFell(8);
        stubLeaves(512, true);
        for (int y = 64; y <= 66; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        Block natural = player.getWorld().getBlockAt(1, 66, 0);
        Block placed = player.getWorld().getBlockAt(2, 66, 0);
        natural.setType(Material.OAK_LEAVES);
        placed.setType(Material.OAK_LEAVES);
        org.bukkit.block.data.type.Leaves placedData = (org.bukkit.block.data.type.Leaves) placed.getBlockData();
        placedData.setPersistent(true);
        placed.setBlockData(placedData);

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        assertEquals(Material.AIR, natural.getType(), "自然生成の葉は壊すこと");
        assertEquals(Material.OAK_LEAVES, placed.getType(), "設置された葉(persistent)は壊さないこと");
    }

    @Test
    void leavesBeyondThePerTickBudgetAreDeferredToFollowingTicks() {
        // leaves-per-tick は「1tickあたりの処理枚数」。同tickで全部壊すとチャンク更新が集中するため。
        stubTreeFell(8);
        when(gimmickConfig.treeFellBreakLeaves()).thenReturn(true);
        when(gimmickConfig.treeFellMaxLeaves(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(512);
        when(gimmickConfig.treeFellLeavesDecayOnly()).thenReturn(true);
        when(gimmickConfig.treeFellLeavesPerTick()).thenReturn(2);
        for (int y = 64; y <= 66; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        for (int x = 1; x <= 5; x++) {
            player.getWorld().getBlockAt(x, 66, 0).setType(Material.OAK_LEAVES);
        }
        // 段階破壊は次tick以降のチャンクロードを確認するので、チャンクを明示的にロードしておく。
        player.getWorld().getChunkAt(0, 0);

        TreeFellingListener listener = listener();
        listener.setPlugin(MockBukkit.createMockPlugin());
        listener.onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        long brokenSameTick = java.util.stream.IntStream.rangeClosed(1, 5)
                .filter(x -> player.getWorld().getBlockAt(x, 66, 0).getType() == Material.AIR)
                .count();
        assertEquals(2, brokenSameTick, "同tickでは leaves-per-tick 枚だけ壊すこと");

        server.getScheduler().performTicks(5L);

        long brokenAfterTicks = java.util.stream.IntStream.rangeClosed(1, 5)
                .filter(x -> player.getWorld().getBlockAt(x, 66, 0).getType() == Material.AIR)
                .count();
        assertEquals(5, brokenAfterTicks, "残りは後続tickで壊し切ること");
    }

    @Test
    void felledLeavesConsumeNoToolDurability() {
        // 上限を桁で上げたので、葉で耐久を取ると「解放した途端に斧が即壊れる」。据え置きを固定する。
        stubTreeFell(8);
        stubLeaves(512, true);
        for (int y = 64; y <= 66; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        for (int x = 1; x <= 4; x++) {
            player.getWorld().getBlockAt(x, 66, 0).setType(Material.OAK_LEAVES);
        }

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        ItemStack held = player.getInventory().getItemInMainHand();
        int damage = held.getItemMeta() instanceof Damageable d ? d.getDamage() : 0;
        assertEquals(Material.AIR, player.getWorld().getBlockAt(4, 66, 0).getType(),
                "葉4枚が壊れていること(前提の確認)");
        assertEquals(2, damage, "耐久は連鎖伐採した原木2本ぶんだけ。葉は0であること");
    }

    @Test
    void shippedWoodcuttingProgressionGrantsNoExpForLeaves() {
        // 葉の採取EXPは0のまま据え置き(1回で最大1024枚壊すので、行を足すと桁で効く)。
        // ハードコードした既定値ではなく出荷ymlの実バイトを見る。
        String progression;
        try {
            progression = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/resources/skills/base/woodcutting_progression.yml"));
        } catch (java.io.IOException ex) {
            throw new AssertionError("出荷ymlが読めない: " + ex.getMessage(), ex);
        }
        for (String forbidden : List.of("_LEAVES", "SAPLING", "APPLE", "STICK")) {
            org.junit.jupiter.api.Assertions.assertFalse(progression.contains(forbidden),
                    "woodcutting_progression.yml に " + forbidden
                            + " の行があると連鎖破壊した葉に採取EXPが入る(据え置き方針に反する)");
        }
    }
}
