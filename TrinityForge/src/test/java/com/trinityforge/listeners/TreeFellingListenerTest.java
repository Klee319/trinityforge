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
import com.trinityforge.woodcutting.TreeScan;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        // 2026-07-31 G1 指摘6b: 走査上限は config 由来になった。Mockito 既定の 0 だと
        // 「木が1本も見えない」ので機構ごと死ぬ。Java の既定値と同じ値を全テストの土台に置く。
        when(gimmickConfig.treeFellScanLimit()).thenReturn(TreeScan.TREE_SCAN_LIMIT);
        // 2026-07-31 G1 round2 指摘2: 距離上限も同じ理由で出荷既定を土台に置く。Mockito 既定の 0 は
        // 「無制限」の意味なので、放置すると<b>全テストが第二の歯止めを通らない</b>状態で緑になる
        // (指摘9 で問題にされた「スタブ漏れで出荷され得ない設定を検証していた」と同型の罠)。
        when(gimmickConfig.treeFellMaxHorizontalDistance())
                .thenReturn(WoodcuttingGimmickConfig.DEFAULT_MAX_HORIZONTAL_DISTANCE);
        when(gimmickConfig.treeFellMaxVerticalDistance())
                .thenReturn(WoodcuttingGimmickConfig.DEFAULT_MAX_VERTICAL_DISTANCE);
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
    void ignoresPlayerPlacedLeafBlock() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));

        // 設置ブロック除外の検証。葉で書くこと — 原木はそもそも対象外になったので
        // 原木で書くと「設置チェックが効いている」ことを何も確かめないテストに化ける。
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.OAK_LEAVES);
        placedBlockTracker.markPlaced(block);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void leafBreakRollsDropTableWhenNotPlacedByPlayer() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));
        when(itemResolver.create(eq("APPLE"))).thenReturn(Optional.of(new ItemStack(Material.APPLE)));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.OAK_LEAVES);

        int itemsBefore = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreakDropTables(breakEvent(block));

        int itemsAfter = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(1, itemsAfter - itemsBefore,
                "trigger-chance-percent=100 must always draw+drop the sole open entry");
    }

    /**
     * 2026-08-17(ユーザー報告「原木破壊で金リンゴが出る」)。リンゴ系のドロップテーブルは
     * 葉からのみ。原木は対象外(この検証を戻すとリンゴが原木からも落ちる)。
     */
    @Test
    void logBreakDoesNotRollDropTable() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.OAK_LOG);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
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

    // --- 2026-08-24 実サーバ報告「リンゴなど伐採の追加ドロップが一括伐採時に出ない」 ---

    /**
     * 幹3本 + 樹冠(葉18枚)を建て、{@code chainDropRollsMax} を上限にして起点を叩く。
     *
     * @return 一括伐採後にワールドへ落ちたアイテムエンティティの増加数
     */
    private int fellTreeWithCanopy(int chainDropRollsMax) {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));
        when(itemResolver.create(eq("APPLE"))).thenReturn(Optional.of(new ItemStack(Material.APPLE)));
        stubTreeFell(8);
        // 葉を実際に壊させる。decay-only は既定(mock の false)のまま = 候補をそのまま壊す。
        when(gimmickConfig.treeFellBreakLeaves()).thenReturn(true);
        when(gimmickConfig.treeFellMaxLeaves(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(64);
        when(gimmickConfig.treeFellChainDropRollsMax()).thenReturn(chainDropRollsMax);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        for (int y = 64; y <= 66; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        for (int y = 67; y <= 68; y++) {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    player.getWorld().getBlockAt(x, y, z).setType(Material.OAK_LEAVES);
                }
            }
        }

        int itemsBefore = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        listener().onBlockBreak(breakEvent(origin));
        long leavesLeft = java.util.stream.IntStream.rangeClosed(67, 68)
                .mapToLong(y -> java.util.stream.IntStream.rangeClosed(-1, 1)
                        .mapToLong(x -> java.util.stream.IntStream.rangeClosed(-1, 1)
                                .filter(z -> player.getWorld().getBlockAt(x, y, z).getType()
                                        == Material.OAK_LEAVES)
                                .count())
                        .sum())
                .sum();
        assertTrue(18 - leavesLeft > 3,
                "上限との min を検証するテストなので、葉が上限より多く壊れていること(実際: "
                        + (18 - leavesLeft) + "枚)");
        return player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size() - itemsBefore;
    }

    /**
     * 連鎖破壊した葉は {@code BlockBreakEvent} を発火しないため、修正前は一括伐採で追加ドロップの
     * 抽選が1回も走っていなかった。上限 3 で「壊した枚数と上限の小さいほう」= 3回引くこと。
     */
    @Test
    void chainFelledLeavesRollDropTableUpToTheConfiguredCap() {
        assertEquals(3, fellTreeWithCanopy(3),
                "trigger-chance-percent=100 の1カテゴリを上限回数ぶん引くこと(修正前は0個)");
    }

    /** 上限0は「この経路の抽選を行わない」= 2026-08-24 以前の挙動。 */
    @Test
    void chainDropRollCapOfZeroDisablesTheChainRollEntirely() {
        assertEquals(0, fellTreeWithCanopy(0), "0以下なら1回も引かないこと");
    }

    /** 葉を1枚も壊さない伐採(break-leaves=false)では抽選しないこと。 */
    @Test
    void chainFellWithoutBrokenLeavesDoesNotRollDropTable() {
        DropTableConfig.Category category = new DropTableConfig.Category("apple", "Apple", 100.0,
                List.of(new DropTableConfig.Entry("APPLE", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("apple", category));
        stubTreeFell(8);
        when(gimmickConfig.treeFellBreakLeaves()).thenReturn(false);
        when(gimmickConfig.treeFellChainDropRollsMax()).thenReturn(8);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        for (int y = 64; y <= 66; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }

        listener().onBlockBreak(breakEvent(origin));

        verifyNoInteractions(itemResolver);
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

    /** CTの残りms(基準10秒)。0なら未消費。 */
    private long remainingCooldownMillis() {
        return cooldowns.remainingMillis(player.getUniqueId(), "tree-fell", 10_000L,
                System.currentTimeMillis());
    }

    @Test
    void cooldownIsNotConsumedWhenThereIsNothingToFell() {
        // 旧実装は走査より前にCTを消費していたので、1本も伐れない破壊でも10秒のCTを取られ、
        // 面を変えて試した2回目が無言で不発になっていた。
        // 2026-07-31 G1 指摘4: 旧テストは treeFellBreakLeaves() をスタブせず Mockito 既定の false で
        // 通っていた = 出荷され得ない設定でしか検証していなかった。出荷既定(true)で固定する。
        stubTreeFell(8);
        stubLeaves(512, true);
        Block lonely = player.getWorld().getBlockAt(0, 64, 0);
        lonely.setType(Material.OAK_LOG);

        listener().onBlockBreak(breakEvent(lonely));

        assertEquals(0L, remainingCooldownMillis(),
                "連鎖0本かつ葉の計画0枚ならCTを消費しないこと(break-leaves: true でも)");
    }

    @Test
    void cooldownIsConsumedWhenOnlyTheLeafCleanupHasWorkToDo() {
        // 「葉の計画が0枚のときだけ消費しない」であって「原木0本なら消費しない」ではない。
        // 1本木の最後の1本を叩いて葉だけが掃除されるケースでは仕事があるのでCTを取る。
        stubTreeFell(8);
        stubLeaves(512, true);
        Block lonely = player.getWorld().getBlockAt(0, 64, 0);
        lonely.setType(Material.OAK_LOG);
        Block leaf = player.getWorld().getBlockAt(1, 64, 0);
        leaf.setType(Material.OAK_LEAVES);

        listener().onBlockBreak(breakEvent(lonely));

        assertEquals(Material.AIR, leaf.getType(), "支えが消える葉は掃除されること");
        assertTrue(remainingCooldownMillis() > 0L, "葉の掃除が走ったのでCTを消費すること");
    }

    @Test
    void breakingWhileOnCooldownDoesNotFellTheSecondTree() {
        // 2026-07-31 G1 指摘3: 旧実装はCT判定を走査の後ろへ移した副作用で、CT中の空振り破壊でも
        // 毎回フルスキャンの代金を払っていた。ここは「CT中は何も伐れない」という結果側の確認で、
        // 「走査の代金を払わない」本題は breakingWhileOnCooldownReadsTheWorldZeroTimes が縛る。
        stubTreeFell(8);
        stubLeaves(512, true);
        for (int y = 64; y <= 70; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        for (int y = 64; y <= 70; y++) {
            player.getWorld().getBlockAt(20, y, 0).setType(Material.OAK_LOG);
        }
        TreeFellingListener listener = listener();

        listener.onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));
        assertTrue(remainingCooldownMillis() > 0L, "1本目でCTを消費している(前提の確認)");

        listener.onBlockBreak(breakEvent(player.getWorld().getBlockAt(20, 64, 0)));

        for (int y = 64; y <= 70; y++) {
            assertEquals(Material.OAK_LOG, player.getWorld().getBlockAt(20, y, 0).getType(),
                    "CT中なので2本目の木は1本も伐れないこと y=" + y);
        }
    }

    @Test
    void breakingWhileOnCooldownReadsTheWorldZeroTimes() {
        // 2026-07-31 G1 round2 指摘9: 旧テストは `verify(never()).treeFellScanLimit()` という
        // <b>実装の呼び出し形状</b>に依存していたので、走査入口のキャッシュ化やコンストラクタ移動で
        // 「CT前の早期 return を消してもテストは緑」になり得た。本題は「走査の代金を払わない」なので、
        // ワールドへのアクセス回数そのもので縛る — 走査は必ず getBlockAt を通るため、
        // 「World に一度も触っていない」なら走査は始まっていない。
        stubTreeFell(8);
        stubLeaves(512, true);
        for (int y = 64; y <= 70; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        TreeFellingListener listener = listener();
        listener.onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));
        assertTrue(remainingCooldownMillis() > 0L, "1本目でCTを消費している(前提の確認)");

        // 2本目は World をモックにして「1回も読まれない」ことを直接観測する。
        org.bukkit.World probedWorld = mock(org.bukkit.World.class);
        Block struck = mock(Block.class);
        when(struck.getType()).thenReturn(Material.OAK_LOG);
        when(struck.getWorld()).thenReturn(probedWorld);
        when(struck.getX()).thenReturn(20);
        when(struck.getY()).thenReturn(64);
        when(struck.getZ()).thenReturn(20);

        listener.onBlockBreak(breakEvent(struck));

        verifyNoInteractions(probedWorld);
        org.mockito.Mockito.verify(struck, org.mockito.Mockito.never()).getWorld();
    }

    // --- 2026-07-31 G1 round2 レビュー指摘2 記録に依存しない第二の歯止め(叩いた位置からの距離) ---

    @Test
    void anUnmarkedLogWallBeyondTheDistanceCapIsNeverFelled() {
        // WorldEdit / schematic / ピストン移動 / チャンク上限FIFO で記録から落ちた丸太は
        // PlacedBlockTracker では覆えない(=「自然木」に見える)。距離の歯止めが最後の防波堤。
        // 30本の横一列の右端(x=29)を叩く。水平上限8なので x=21..28 だけが伐れ、x=0..20 は残ること。
        stubTreeFell(64);
        stubLeaves(512, true);
        for (int x = 0; x <= 29; x++) {
            player.getWorld().getBlockAt(x, 64, 0).setType(Material.OAK_LOG);
        }

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(29, 64, 0)));

        for (int x = 0; x <= 20; x++) {
            assertEquals(Material.OAK_LOG, player.getWorld().getBlockAt(x, 64, 0).getType(),
                    "叩いた位置から水平8を超える丸太(視界外)は消えないこと x=" + x);
        }
        for (int x = 21; x <= 28; x++) {
            assertEquals(Material.AIR, player.getWorld().getBlockAt(x, 64, 0).getType(),
                    "距離の内側は従来どおり伐れること x=" + x);
        }
    }

    @Test
    void theDistanceCapCanBeOpenedUpFromConfigAndZeroMeansUnlimited() {
        // レバーとして効くことの確認(0以下 = 無制限 = 2026-07-31 以前の挙動)。
        stubTreeFell(64);
        stubLeaves(512, true);
        when(gimmickConfig.treeFellMaxHorizontalDistance()).thenReturn(0);
        for (int x = 0; x <= 29; x++) {
            player.getWorld().getBlockAt(x, 64, 0).setType(Material.OAK_LOG);
        }

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(29, 64, 0)));

        assertEquals(Material.AIR, player.getWorld().getBlockAt(0, 64, 0).getType(),
                "水平上限を0(無制限)にすると列の反対側まで伐れること");
    }

    @Test
    void aThirtyBlockTallNaturalTreeIsUnaffectedByTheShippedVerticalCap() {
        // 既定 8/32 は実在するバニラ樹木の寸法より大きいので自然樹の伐採は変わらないこと。
        // (実在する最大級 = 高さ30段程度・水平 ±6程度。ここでは高さ30の柱で確認する)
        stubTreeFell(128);
        stubLeaves(512, true);
        for (int y = 64; y <= 93; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        for (int y = 65; y <= 93; y++) {
            assertEquals(Material.AIR, player.getWorld().getBlockAt(0, y, 0).getType(),
                    "高さ30段の木は既定の垂直上限32に当たらず全部倒れること y=" + y);
        }
    }

    // --- 2026-07-31 G1 round2 レビュー指摘3 走査中のPDC読みはチャンクごとに1回 ---

    @Test
    void theScanReadsThePlacedMarkArrayOncePerChunkNotOncePerScannedBlock() {
        // PDC の LONG_ARRAY は copy-on-read なので「読んだ回数 = 配列を複製した回数」。
        // 旧実装は走査述語から isPlaced を直接呼んでいたので、読んだ回数が<b>走査したブロック数に
        // 比例</b>し、マークが多いチャンクでの伐採1回で数十MBの短命オブジェクトを生んでいた。
        // ここでは「木の大きさを変えても読んだ回数が変わらない」ことで比例していないことを固定する。
        stubTreeFell(64);
        when(gimmickConfig.treeFellBreakLeaves()).thenReturn(false); // 葉は別経路なので切り離す

        for (int y = 64; y <= 65; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        long beforeSmall = placedBlockTracker.chunkReadCount();
        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));
        long smallTreeReads = placedBlockTracker.chunkReadCount() - beforeSmall;

        cooldowns = new CooldownManager();
        for (int y = 64; y <= 88; y++) {
            player.getWorld().getBlockAt(5, y, 5).setType(Material.OAK_LOG);
        }
        long beforeBig = placedBlockTracker.chunkReadCount();
        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(5, 64, 5)));
        long bigTreeReads = placedBlockTracker.chunkReadCount() - beforeBig;

        assertEquals(Material.AIR, player.getWorld().getBlockAt(5, 88, 5).getType(),
                "25本の木がちゃんと伐れていること(前提の確認 — 走査していなければこのテストは無意味)");
        assertEquals(1L, smallTreeReads, "同一チャンク内なら2本の木でもPDC読みは1回");
        assertEquals(smallTreeReads, bigTreeReads,
                "木が2本から25本に増えてもPDC読みの回数は増えないこと(アロケーションが走査量に比例しない)");
    }

    // --- 2026-07-31 G1 round2 レビュー指摘10 設置丸太は葉のBFSの種にもならない ---

    @Test
    void strikingAPlacedLogDoesNotSeedTheLeafScanWithIt() {
        // 旧実装は wholeTree が base を無条件に木へ入れていたので、設置丸太を叩くとその1本が
        // 葉のBFSの種になり(かつ「これから消えるもの」として支持から外れるので)、面隣接の自然葉が
        // 支持なしと判定されて壊れ、CTまで取られていた。
        stubTreeFell(64);
        stubLeaves(512, true);
        Block placed = player.getWorld().getBlockAt(0, 64, 0);
        placed.setType(Material.OAK_LOG);
        placedBlockTracker.markPlaced(placed);
        Block leaf = player.getWorld().getBlockAt(1, 64, 0);
        leaf.setType(Material.OAK_LEAVES);

        listener().onBlockBreak(breakEvent(placed));

        assertEquals(Material.OAK_LEAVES, leaf.getType(),
                "設置丸太は木ではないので葉のBFSの種にもならないこと");
        assertEquals(0L, remainingCooldownMillis(), "仕事が無いのでCTも取らないこと");
    }

    @Test
    void scanLimitCapsHowMuchOfTheTreeIsSeen() {
        // 2026-07-31 G1 指摘6b: 走査上限が config レバーとして効くこと。3本しか見えなければ
        // 上限8本でも連鎖は2本(base+2 のうち origin を除いた分)で止まる。
        stubTreeFell(8);
        when(gimmickConfig.treeFellScanLimit()).thenReturn(3);
        for (int y = 64; y <= 73; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        long remaining = java.util.stream.IntStream.rangeClosed(64, 73)
                .filter(y -> player.getWorld().getBlockAt(0, y, 0).getType() == Material.OAK_LOG)
                .count();
        assertEquals(8, remaining, "scan-limit=3 なら見えるのは3本、連鎖対象はそのうち2本だけ");
    }

    // --- 2026-07-31 G1 レビュー指摘1 丸太建築(設置された丸太)を木として扱わない ---

    /** {@code positions} の各座標に OAK_LOG を置き、設置済みとして記録する。 */
    private java.util.List<Block> placedLogs(int[][] positions) {
        java.util.List<Block> blocks = new java.util.ArrayList<>();
        for (int[] xyz : positions) {
            Block block = player.getWorld().getBlockAt(xyz[0], xyz[1], xyz[2]);
            block.setType(Material.OAK_LOG);
            placedBlockTracker.markPlaced(block);
            blocks.add(block);
        }
        return blocks;
    }

    @Test
    void aPlayerBuiltLogWallIsNotATreeSoStrikingItsTopLeavesTheRestAlone() {
        // OAK_LOG で組んだ壁(柱 y=64..70 + 最下段の横一列 x=0..5)の上端を叩く。
        // 「常に根元から伐る」を素で適用すると、クリック位置から水平に離れた y=64 の行が消えていた。
        stubTreeFell(64);
        stubLeaves(512, true);
        java.util.List<int[]> wall = new java.util.ArrayList<>();
        for (int y = 64; y <= 70; y++) {
            wall.add(new int[] {0, y, 0});
        }
        for (int x = 1; x <= 5; x++) {
            wall.add(new int[] {x, 64, 0});
        }
        java.util.List<Block> blocks = placedLogs(wall.toArray(new int[0][]));
        Block top = player.getWorld().getBlockAt(0, 70, 0);

        listener().onBlockBreak(breakEvent(top));

        for (Block block : blocks) {
            assertEquals(Material.OAK_LOG, block.getType(),
                    "設置された丸太は木ではないので1本も連鎖破壊しないこと: " + block.getLocation());
        }
        assertEquals(0L, remainingCooldownMillis(), "仕事が無いのでCTも取らないこと");
    }

    @Test
    void placedLogsStackedOnANaturalTreeStopTheScanInsteadOfExtendingIt() {
        // 自然木(y=64..68)の上にプレイヤーが丸太を積んだ(y=69..70)状況。走査は設置分で止まり、
        // 自然木だけが伐れること。
        stubTreeFell(64);
        stubLeaves(512, true);
        for (int y = 64; y <= 68; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }
        java.util.List<Block> placed = placedLogs(new int[][] {{0, 69, 0}, {0, 70, 0}});

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        for (int y = 65; y <= 68; y++) {
            assertEquals(Material.AIR, player.getWorld().getBlockAt(0, y, 0).getType(),
                    "自然木の部分は伐れること y=" + y);
        }
        for (Block block : placed) {
            assertEquals(Material.OAK_LOG, block.getType(), "設置分は残ること");
        }
    }

    @Test
    void aNaturalTreeIsStillFullyFelledWhenNothingIsMarkedAsPlaced() {
        // 指摘1の対処で自然木が退行していないことの確認(PlacedBlockTracker は空)。
        stubTreeFell(8);
        stubLeaves(512, true);
        for (int y = 64; y <= 70; y++) {
            player.getWorld().getBlockAt(0, y, 0).setType(Material.OAK_LOG);
        }

        listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));

        for (int y = 65; y <= 70; y++) {
            assertEquals(Material.AIR, player.getWorld().getBlockAt(0, y, 0).getType(),
                    "自然木は従来どおり全部倒れること y=" + y);
        }
    }

    // 2026-07-31 G1 レビュー指摘5(ルートテーブルの抽選を1回だけにする)の回帰ガードは
    // com.trinityforge.gathering.ChainBreakSupportSingleRollTest にある。MockBukkit の
    // Block#getDrops(tool, player) は<b>無言で空コレクションを返す</b>ので、この実ワールド上の
    // テストでは抽選も落下物も観測できない(数えても常に0になる)。

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
    void staggeredLeafBreakingWarnsOnceWhenTheSchedulerOwnerCannotBeResolved() {
        // 2026-07-31 G1 指摘7: setPlugin 未注入 + getProvidingPlugin 解決失敗のとき、旧実装は無言で
        // 「同tickに最大1024枚破壊」(yml 自身が非推奨と書く挙動)へ落ちて観測手段が無かった。
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

        java.util.List<java.util.logging.LogRecord> warnings = new java.util.ArrayList<>();
        java.util.logging.Logger log =
                java.util.logging.Logger.getLogger(TreeFellingListener.class.getName());
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.WARNING) {
                    warnings.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(capture);
        try {
            // setPlugin を呼ばない = MockBukkit のクラスローダでは getProvidingPlugin が解決に失敗する。
            listener().onBlockBreak(breakEvent(player.getWorld().getBlockAt(0, 64, 0)));
        } finally {
            log.removeHandler(capture);
        }

        long brokenSameTick = java.util.stream.IntStream.rangeClosed(1, 5)
                .filter(x -> player.getWorld().getBlockAt(x, 66, 0).getType() == Material.AIR)
                .count();
        assertEquals(5, brokenSameTick, "縮退として同tickで全部壊すこと(機能自体は失わない)");
        assertEquals(1, warnings.size(), "縮退したことを WARNING で1回だけ知らせること");
        assertTrue(warnings.get(0).getMessage().contains("段階破壊"),
                "何が縮退したか分かるメッセージであること: " + warnings.get(0).getMessage());
    }

    @Test
    void shippedWoodcuttingProgressionGrantsNoExpForLeaves() {
        // 葉の採取EXPは0のまま据え置き(1回で最大1024枚壊すので、行を足すと桁で効く)。
        // 2026-07-31 G1 指摘8: 旧実装は yml の生文字列 contains 判定だったので、日本語コメントに
        // 「リンゴ(APPLE)」と書いた瞬間に無関係な失敗になった。yml をパースして実データだけを見る。
        org.bukkit.configuration.file.YamlConfiguration progression =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.File("src/main/resources/skills/base/woodcutting_progression.yml"));
        org.bukkit.configuration.ConfigurationSection experience =
                progression.getConfigurationSection("experience");
        org.junit.jupiter.api.Assertions.assertNotNull(experience,
                "出荷ymlの experience セクションが読めない(パスかスキーマが変わった)");

        List<String> forbidden = List.of("_LEAVES", "SAPLING", "APPLE", "STICK");
        for (String action : experience.getKeys(false)) {
            org.bukkit.configuration.ConfigurationSection table =
                    experience.getConfigurationSection(action);
            if (table == null) {
                continue; // max_level / exp_level_curve のようなスカラー行。
            }
            for (String material : table.getKeys(false)) {
                String upper = material.toUpperCase(java.util.Locale.ROOT);
                for (String banned : forbidden) {
                    org.junit.jupiter.api.Assertions.assertFalse(upper.contains(banned),
                            "woodcutting_progression.yml の experience." + action + " に " + material
                                    + " の行があると連鎖破壊した葉/苗木/リンゴ/棒に採取EXPが入る"
                                    + "(据え置き方針に反する)");
                }
            }
        }
    }
}
