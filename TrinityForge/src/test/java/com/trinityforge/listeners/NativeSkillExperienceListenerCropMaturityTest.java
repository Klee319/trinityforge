package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-01 U9 回帰テスト: 破壊経路({@link NativeSkillExperienceListener#onBlockBreak})で
 *
 * <ul>
 *   <li>サトウキビは age に関係なく農業EXPが入る（age が周回する成長カウンタなので、以前は
 *       {@code instanceof Ageable} の成熟ガードに当たって常に0だった）</li>
 *   <li>未成熟の作物（ニンジン）は従来どおりEXPが入らない</li>
 *   <li>完熟した作物はEXPが入る</li>
 *   <li>成熟ガードで {@code return false} していたため道連れになっていた
 *       破壊時バニラEXP解放({@code break-vanilla-exp})も、サトウキビで発火する</li>
 * </ul>
 *
 * を縛る。MockBukkit を使わない（{@code Block#getDrops} 等の未実装APIでテストが SKIPPED に化けるのを
 * 避ける）ため、Block/Player は Mockito モックで組む。
 */
class NativeSkillExperienceListenerCropMaturityTest {

    private static final SkillCatalogEntry FARMING = new SkillCatalogEntry(
            SkillId.FARMING, 100, "1", level -> 1L,
            Map.of(
                    "block_drops.SUGAR_CANE", 24.0,
                    "block_drops.CARROTS", 40.0,
                    "block_drops.CARROT", 10.0),
            Map.of());

    @Test
    void sugarCaneGrantsFarmingExpAtEveryAge() {
        // age 0..15 の全段。伸びる直前(15)以外は「未成熟」に見えるのが以前のバグの正体。
        for (int age = 0; age <= 15; age++) {
            Fixture fixture = fixture();
            Block cane = ageableBlock(Material.SUGAR_CANE, age, 15, Material.SUGAR_CANE, fixture.player());

            fixture.listener().onBlockBreak(breakEvent(cane, fixture.player()));

            verify(fixture.dispatcher(), org.mockito.Mockito.description("age=" + age + " でEXPが入らない"))
                    .grant(fixture.player().getUniqueId(), SkillId.FARMING, 24.0);
        }
    }

    @Test
    void sugarCaneAlsoReachesBreakVanillaExpRelease() {
        Fixture fixture = fixture();
        Block cane = ageableBlock(Material.SUGAR_CANE, 0, 15, Material.SUGAR_CANE, fixture.player());

        fixture.listener().onBlockBreak(breakEvent(cane, fixture.player()));

        // BASE_BREAK_EXP=1、ボーナス0 → 1。以前は成熟ガードの return false でここまで来られなかった。
        verify(fixture.player()).giveExp(1);
    }

    @Test
    void immatureCarrotsStillGrantNothing() {
        Fixture fixture = fixture();
        // 未成熟のニンジンも壊せばニンジン1個を落とす（=ドロップ表に載っている）ので、ガードが無いと
        // EXPが入ってしまう。ここは従来どおり0のままでなければならない。
        Block carrots = ageableBlock(Material.CARROTS, 3, 7, Material.CARROT, fixture.player());

        fixture.listener().onBlockBreak(breakEvent(carrots, fixture.player()));

        verify(fixture.dispatcher(), never()).grant(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.player(), never()).giveExp(anyInt());
    }

    @Test
    void matureCarrotsGrantFarmingExp() {
        Fixture fixture = fixture();
        Block carrots = ageableBlock(Material.CARROTS, 7, 7, Material.CARROT, fixture.player());

        fixture.listener().onBlockBreak(breakEvent(carrots, fixture.player()));

        // DROP_SUM(プラグイン未起動時の既定)なのでドロップ品CARROT行の10。
        verify(fixture.dispatcher()).grant(fixture.player().getUniqueId(), SkillId.FARMING, 10.0);
        verify(fixture.player()).giveExp(1);
    }

    /**
     * 2026-08-03 実サーバ報告の回帰: 作物は必ずプレイヤーが種を植える({@code BlockPlaceEvent} を通り
     * {@link PlacedBlockTracker} に必ずマークが付く)ため、置く→壊すEXPファーム対策の設置マーク
     * ({@code clearIfPlaced})をそのままガードに使うと<b>自分の畑で収穫した完熟作物が例外なく
     * EXP対象から除外される</b>——農業というスキルの主経路そのものが常時0EXPになっていた
     * (ジャガイモ報告の真因)。成熟ガード対象の作物は設置マークがあってもEXPが入ること、
     * かつ未成熟なら(設置マークの有無に関わらず)従来通り0のままであることを両方縛る。
     */
    @Test
    void matureCropPlacedByPlayerStillGrantsFarmingExp() {
        Fixture fixture = fixture();
        Block carrots = ageableBlock(Material.CARROTS, 7, 7, Material.CARROT, fixture.player());
        when(fixture.placedBlockTracker().clearIfPlaced(carrots)).thenReturn(true);

        fixture.listener().onBlockBreak(breakEvent(carrots, fixture.player()));

        verify(fixture.dispatcher()).grant(fixture.player().getUniqueId(), SkillId.FARMING, 10.0);
        verify(fixture.player()).giveExp(1);
    }

    @Test
    void immatureCropPlacedByPlayerStillGrantsNothing() {
        Fixture fixture = fixture();
        Block carrots = ageableBlock(Material.CARROTS, 3, 7, Material.CARROT, fixture.player());
        when(fixture.placedBlockTracker().clearIfPlaced(carrots)).thenReturn(true);

        fixture.listener().onBlockBreak(breakEvent(carrots, fixture.player()));

        verify(fixture.dispatcher(), never()).grant(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.player(), never()).giveExp(anyInt());
    }

    /**
     * 対照実験: 成熟ガード対象<b>ではない</b>ブロック(=サトウキビ等)は、従来通り設置マークが
     * あればEXPを弾く。この例外は「必ずプレイヤーが植える成熟作物」だけに絞られていることを固定する
     * (掘削/採掘/伐採の「置く→壊す」ファーム対策を骨抜きにしていないことの回帰)。
     */
    @Test
    void placedSugarCaneStillBlockedByPlaceBreakGuard() {
        Fixture fixture = fixture();
        Block cane = ageableBlock(Material.SUGAR_CANE, 15, 15, Material.SUGAR_CANE, fixture.player());
        when(fixture.placedBlockTracker().clearIfPlaced(cane)).thenReturn(true);

        fixture.listener().onBlockBreak(breakEvent(cane, fixture.player()));

        verify(fixture.dispatcher(), never()).grant(org.mockito.ArgumentMatchers.any(), anyString(),
                org.mockito.ArgumentMatchers.anyDouble());
        verify(fixture.player(), never()).giveExp(anyInt());
    }

    // 2026-08-03: 連鎖崩壊(根元を壊すと上に育った段もまとめて消える)の回帰は
    // NativeSkillExperienceListenerStackCollapseTest 側にある。その機構は
    // ChainBreakSupport.breakChain 経由で GameRules.BLOCK_DROPS を読むため、静的初期化に
    // Bukkitレジストリ(=起動中のサーバ)を要求する — このファイルは意図して MockBukkit を
    // 使わない(冒頭javadoc参照)ので、生Mockitoモックの World/Block では
    // ExceptionInInitializerError になり両立しない。

    // ============================================================
    // 組み立て
    // ============================================================

    private record Fixture(NativeSkillExperienceListener listener,
                           NativeExperienceDispatcher dispatcher,
                           Player player,
                           PlacedBlockTracker placedBlockTracker) {
    }

    private static Fixture fixture() {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.FARMING)).thenReturn(FARMING);
        PlacedBlockTracker placedBlockTracker = mock(PlacedBlockTracker.class);
        when(placedBlockTracker.clearIfPlaced(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        Player player = player();
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        // 2026-08-01: 破壊時バニラEXPの解放判定はツリー限定になった(採掘ツリーの解放で作物にも
        // 出てしまっていたため)。ここは作物=FARMING の破壊なので FARMING で解放しておく。
        // ツリー横断の漏れそのものは NativeSkillExperienceListenerBreakVanillaExpScopeTest が縛る。
        // 2026-08-18 (W-58): gate id 自体がスキルごとに分割された(break-vanilla-exp-farming)。
        when(dedicatedEffects.isActive(eq(player), eq("break-vanilla-exp-farming"), eq(SkillId.FARMING)))
                .thenReturn(true);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        NativeSkillExperienceListener listener = new NativeSkillExperienceListener(
                fakePlugin(), dispatcher, catalog, placedBlockTracker, null, dedicatedEffects, aggregator);
        return new Fixture(listener, dispatcher, player, placedBlockTracker);
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

    private static Block ageableBlock(Material material, int age, int maximumAge, Material drop,
                                      Player player) {
        Block block = mock(Block.class);
        Ageable data = mock(Ageable.class);
        List<ItemStack> drops = List.of(stack(drop));
        when(block.getType()).thenReturn(material);
        when(block.getBlockData()).thenReturn(data);
        when(data.getAge()).thenReturn(age);
        when(data.getMaximumAge()).thenReturn(maximumAge);
        when(block.getDrops(org.mockito.ArgumentMatchers.any(), eq(player))).thenReturn(drops);
        return block;
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
                NativeSkillExperienceListenerCropMaturityTest.class.getClassLoader(),
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
