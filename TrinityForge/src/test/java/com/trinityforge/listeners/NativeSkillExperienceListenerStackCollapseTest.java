package com.trinityforge.listeners;

import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-03 実サーバ報告「サトウキビの根元を壊すと経験値が入らなかった」の回帰
 * ({@link NativeSkillExperienceListener#onBlockBreak} が呼ぶ
 * {@code grantStackCollapseChain} の統合テスト)。
 *
 * <p>この機構は {@link com.trinityforge.gathering.ChainBreakSupport#breakChain} を経由し、その内部で
 * {@code World#getGameRuleValue(GameRules.BLOCK_DROPS)} を読む。{@code GameRules} の静的初期化は
 * Bukkit レジストリ(=起動中のサーバ)を要求するため、{@link org.bukkit.Bukkit#getServer()} が
 * 一度も設定されない状態では {@code ExceptionInInitializerError} になる。そのため
 * {@link MockBukkit#mock()} で最低限のサーバー登録だけ行うが、<b>{@code Block}/{@code World} 自体は
 * 実 {@code WorldMock} ではなく生 Mockito モックで組む</b> — {@code MockBukkit} の
 * {@code Block#getDrops(tool, player)} は<b>無言で空コレクションを返す</b>既知の罠があり
 * ({@link TreeFellingListenerTest} 686行目コメント参照)、それだと
 * {@code gatheringExp} の「ドロップが空なら0」ガードに常に落ちて EXP が絶対に出ない
 * (このテストを最初に実 {@code WorldMock} で組んだときに踏んだ)。生モックなら
 * {@code getDrops} を明示的にスタブできるので、{@link NativeSkillExperienceListenerCropMaturityTest}
 * と同じ組み方を踏襲しつつ、{@code GameRules} 読み取りのためだけに {@code MockBukkit} を起動する。
 */
class NativeSkillExperienceListenerStackCollapseTest {

    private static final SkillCatalogEntry FARMING = new SkillCatalogEntry(
            SkillId.FARMING, 100, "1", level -> 1L,
            Map.of("block_drops.SUGAR_CANE", 24.0),
            Map.of());

    @BeforeEach
    void setUp() {
        // GameRules.BLOCK_DROPS の静的初期化に Bukkit サーバー登録が要る(javadoc参照)。
        // Block/World 自体はこの後 Mockito の生モックで組むので WorldMock は使わないが、
        // GameRules のレジストリ読み込みは MockBukkit が実ワールドを一つも持たないままだと
        // 失敗する(IncompatiblePaperVersionException/NPE)ため、ダミーの実ワールドを1つ
        // 作らせて初期化させておく(このワールド自体はテストで使わない)。
        MockBukkit.mock().addSimpleWorld("bootstrap");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * 根元(=手植えで設置マーク済みの想定)を壊すと、バニラの物理挙動で上に伸びた2段も連鎖崩壊する。
     * 連鎖崩壊は{@code BlockBreakEvent}を伴わないため、対策前はこの2段のEXPがまるごと消えていた。
     * 根元自体は仕様どおり0のまま、育った2段は個別にEXPが入る。
     */
    @Test
    void rootBreakGrantsNothingButGrownCascadeSegmentsGrantFarmingExp() {
        Fixture fixture = fixture();
        List<Block> column = stackedColumn(fixture.world(), fixture.player(), 0, 64, 0, 3);
        Block root = column.get(0);
        when(fixture.placedBlockTracker().clearIfPlaced(root)).thenReturn(true); // 根元だけ手植え済みマーク。

        fixture.listener().onBlockBreak(breakEvent(root, fixture.player()));

        verify(fixture.dispatcher(), times(2))
                .grant(fixture.player().getUniqueId(), SkillId.FARMING, 24.0);
    }

    /**
     * 抜け道が無いことの回帰: サトウキビは手植えで積み上げることもできる(各段が個別に設置マークを
     * 持つ)。この場合は根元だけでなく連鎖で崩れる段も個別にガードへ掛かるため、
     * 「手植えで積んで根元だけ壊す」を繰り返してもEXPファームにはならない。
     */
    @Test
    void handPlacedColumnStaysFullyBlockedEvenWithCascade() {
        Fixture fixture = fixture();
        List<Block> column = stackedColumn(fixture.world(), fixture.player(), 1, 70, 1, 3);
        for (Block block : column) {
            when(fixture.placedBlockTracker().clearIfPlaced(block)).thenReturn(true);
        }

        fixture.listener().onBlockBreak(breakEvent(column.get(0), fixture.player()));

        verify(fixture.dispatcher(), never()).grant(any(), anyString(), anyDouble());
    }

    /** 単体(連鎖なし): 根元だけ・上に何も育っていないなら、従来どおり0のまま(仕様として妥当)。 */
    @Test
    void placedSingleSugarCaneWithNoGrowthAboveGrantsNothing() {
        Fixture fixture = fixture();
        List<Block> column = stackedColumn(fixture.world(), fixture.player(), 2, 64, 2, 1);
        Block root = column.get(0);
        when(fixture.placedBlockTracker().clearIfPlaced(root)).thenReturn(true);

        fixture.listener().onBlockBreak(breakEvent(root, fixture.player()));

        verify(fixture.dispatcher(), never()).grant(any(), anyString(), anyDouble());
    }

    // ============================================================
    // 組み立て
    // ============================================================

    private record Fixture(NativeSkillExperienceListener listener,
                           NativeExperienceDispatcher dispatcher,
                           Player player,
                           World world,
                           PlacedBlockTracker placedBlockTracker) {
    }

    private static Fixture fixture() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.FARMING)).thenReturn(FARMING);
        PlacedBlockTracker placedBlockTracker = mock(PlacedBlockTracker.class);
        when(placedBlockTracker.clearIfPlaced(any())).thenReturn(false);

        World world = mock(World.class);
        Player player = player();

        NativeSkillExperienceListener listener = new NativeSkillExperienceListener(
                fakePlugin(), dispatcher, catalog, placedBlockTracker);
        return new Fixture(listener, dispatcher, player, world, placedBlockTracker);
    }

    private static Player player() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        // ItemStack の実コンストラクタはサーバ実装(ItemFactory)を要求するのでモックで済ませる。
        // モックの生成は thenReturn() の引数内で行わない(入れ子の when() で
        // UnfinishedStubbingException になる)。
        ItemStack hoe = stack(Material.IRON_HOE);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(hoe);
        return player;
    }

    /**
     * 縦に{@code count}段連結したサトウキビの列を組み立てる({@code BlockFace.UP}方向)。
     * 各段は個別の{@link Block}モックで、{@code getRelative(UP)}が次の段(最上段は非該当材質の
     * 番人ブロック)を返すよう配線し、{@code world.getBlockAt(x,y,z)}も同じインスタンスを返すよう
     * 揃える({@link com.trinityforge.gathering.ChainBreakSupport#breakChain}は座標から
     * {@code getBlockAt}経由で再取得するため、取り違えると連鎖判定がすり抜ける)。
     */
    private static List<Block> stackedColumn(World world, Player player, int x, int baseY, int z, int count) {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int y = baseY + i;
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.SUGAR_CANE);
            when(block.getX()).thenReturn(x);
            when(block.getY()).thenReturn(y);
            when(block.getZ()).thenReturn(z);
            when(block.getWorld()).thenReturn(world);
            List<ItemStack> drops = List.of(stack(Material.SUGAR_CANE));
            when(block.getDrops(any(), eq(player))).thenReturn(drops);
            when(world.getBlockAt(x, y, z)).thenReturn(block);
            blocks.add(block);
        }
        // 番人ブロック: 列の直上、サトウキビではない(=連鎖走査/連鎖破壊のどちらもここで止まる)。
        Block sentinel = mock(Block.class);
        when(sentinel.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(x, baseY + count, z)).thenReturn(sentinel);
        for (int i = 0; i < count; i++) {
            Block next = (i + 1 < count) ? blocks.get(i + 1) : sentinel;
            when(blocks.get(i).getRelative(BlockFace.UP)).thenReturn(next);
        }
        return blocks;
    }

    private static ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        return stack;
    }

    private static BlockBreakEvent breakEvent(Block block, Player player) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private static Plugin fakePlugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                NativeSkillExperienceListenerStackCollapseTest.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "TrinityForge";
                    case "namespace" -> "trinityforge";
                    case "toString" -> "FakePlugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
