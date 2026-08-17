package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.stats.BreakVanillaExpBonusKeys;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-08-01 実サーバ報告の回帰テスト — <b>破壊時バニラEXP解放がツリーを横断して漏れていた件</b>。
 *
 * <p>2026-08-01 当時、{@code feature:break-vanilla-exp} は mining / woodcutting / digging / farming の
 * <b>4ツリーすべて</b>が A ノード(Lv10)に置く<b>共有id</b>だった。
 * {@link NativeSkillExperienceListener} は以前これをツリー非限定の
 * {@code DedicatedEffectsConfig#isActive(player, id)} で見ていたため、
 * <b>採掘の A を1つ取っただけで、作物・原木・土の破壊でもバニラEXPが出ていた</b>
 * (=残り3ツリー分の解放をタダ取りできる)。
 *
 * <p>ここでは「どこか1本のツリーで解放している」状態(=2引数版なら true)を作ったうえで、
 * <b>破壊が属する採取スキルのツリーで解放していなければ発火しない</b>ことを縛る。
 * 修正を戻す(3引数→2引数)と {@link #miningUnlockDoesNotLeakToFarmingBreaks()} と
 * {@link #miningUnlockDoesNotLeakToWoodcuttingBreaks()} が RED になる。
 *
 * <p><b>2026-08-18 (W-58) 追記 — gate id 自体がスキルごとに分割された後もこのテストは有効</b>:
 * {@code break-vanilla-exp} は {@link BreakVanillaExpBonusKeys#featureId(String)} が導出する
 * スキル専用id(例: {@code break-vanilla-exp-mining})へ分割されたので、
 * {@code FLAG} という単一定数はもう存在しない — 各アサーションは
 * {@code BreakVanillaExpBonusKeys.featureId(SkillId.XXX)} で問い合わせるべき"そのスキル専用id"を
 * 明示的に使う。3引数呼び出しは belt-and-suspenders として引き続き維持されるため、このテストの
 * 「3引数→2引数への退行を検出する」という核心的な主張は変わらない
 * (id自体が分かれた今も、退行の実害はより小さくなっただけで、2引数呼び出しへ戻れば依然として
 * 検出できる退行であることを、このテストで固定する)。
 *
 * <p>MockBukkit を使わない({@code Block#getDrops} 等の未実装APIでテストが SKIPPED に化けるのを
 * 避ける)ため、Block/Player は Mockito モックで組む
 * — {@link NativeSkillExperienceListenerCropMaturityTest} と同じ方針。
 */
class NativeSkillExperienceListenerBreakVanillaExpScopeTest {

    private static final SkillCatalogEntry FARMING = new SkillCatalogEntry(
            SkillId.FARMING, 100, "1", level -> 1L,
            Map.of("block_drops.CARROTS", 40.0, "block_drops.CARROT", 10.0),
            Map.of());
    private static final SkillCatalogEntry WOODCUTTING = new SkillCatalogEntry(
            SkillId.WOODCUTTING, 100, "1", level -> 1L,
            Map.of("woodcutting_break.OAK_LOG", 12.0),
            Map.of());
    private static final SkillCatalogEntry MINING = new SkillCatalogEntry(
            SkillId.MINING, 100, "1", level -> 1L,
            Map.of("mining_break.STONE", 8.0, "mining_break.COBBLESTONE", 8.0),
            Map.of());

    // ============================================================
    // 本題: 採掘ツリーだけ解放したプレイヤー
    // ============================================================

    @Test
    void miningUnlockReleasesVanillaExpOnMiningBreaks() {
        Fixture fixture = fixtureUnlockedIn(SkillId.MINING);

        // 2026-08-18: ベース量が config 化され既定 0.25 になった(旧 1 の 1/4)。端数は持ち越すので
        // 「4回壊して1EXP」。1回だけ壊して giveExp(1) を期待していた旧アサーションはここで4回へ直した
        // (検証したいのは金額そのものではなく『自分のツリーの破壊なら解放経路まで到達する』こと)。
        for (int i = 0; i < 4; i++) {
            fixture.listener().onBlockBreak(breakEvent(plainBlock(Material.STONE, Material.COBBLESTONE,
                    fixture.player()), fixture.player()));
        }

        verify(fixture.player()).giveExp(1);
        // 照会が「採掘として扱われた破壊」であることを明示的に縛る(スコープを取り違えていないこと)。
        verify(fixture.dedicatedEffects(), org.mockito.Mockito.atLeastOnce()).isActive(fixture.player(),
                BreakVanillaExpBonusKeys.featureId(SkillId.MINING), SkillId.MINING);
    }

    @Test
    void miningUnlockDoesNotLeakToFarmingBreaks() {
        Fixture fixture = fixtureUnlockedIn(SkillId.MINING);
        Block carrots = ageableBlock(Material.CARROTS, 7, 7, Material.CARROT, fixture.player());

        fixture.listener().onBlockBreak(breakEvent(carrots, fixture.player()));

        // 農業EXP自体は入る(採取扱いの破壊ではある)。
        verify(fixture.dispatcher()).grant(fixture.player().getUniqueId(), SkillId.FARMING, 10.0);
        // だが農業ツリーのAを取っていないのでバニラEXPは出ない。
        verify(fixture.player(), never()).giveExp(anyInt());
        verify(fixture.dedicatedEffects()).isActive(fixture.player(),
                BreakVanillaExpBonusKeys.featureId(SkillId.FARMING), SkillId.FARMING);
    }

    @Test
    void miningUnlockDoesNotLeakToWoodcuttingBreaks() {
        Fixture fixture = fixtureUnlockedIn(SkillId.MINING);
        Block log = plainBlock(Material.OAK_LOG, Material.OAK_LOG, fixture.player());

        fixture.listener().onBlockBreak(breakEvent(log, fixture.player()));

        verify(fixture.dispatcher()).grant(fixture.player().getUniqueId(), SkillId.WOODCUTTING, 12.0);
        verify(fixture.player(), never()).giveExp(anyInt());
        verify(fixture.dedicatedEffects()).isActive(fixture.player(),
                BreakVanillaExpBonusKeys.featureId(SkillId.WOODCUTTING), SkillId.WOODCUTTING);
    }

    // ============================================================
    // 逆向き: 伐採ツリーだけ解放したプレイヤー
    // ============================================================

    @Test
    void woodcuttingUnlockReleasesVanillaExpOnlyOnWoodcuttingBreaks() {
        Fixture fixture = fixtureUnlockedIn(SkillId.WOODCUTTING);

        // ベース 0.25 なので4回で1EXP(2026-08-18 の config 化)。
        for (int i = 0; i < 4; i++) {
            fixture.listener().onBlockBreak(breakEvent(
                    plainBlock(Material.OAK_LOG, Material.OAK_LOG, fixture.player()), fixture.player()));
        }
        verify(fixture.player()).giveExp(1);

        for (int i = 0; i < 4; i++) {
            fixture.listener().onBlockBreak(breakEvent(
                    plainBlock(Material.STONE, Material.COBBLESTONE, fixture.player()), fixture.player()));
        }
        // 採掘破壊では増えない(=1回のまま)。解放していないツリーの破壊は端数も積まない。
        verify(fixture.player()).giveExp(anyInt());
    }

    // ============================================================
    // 採取扱いでない破壊はそもそもゲートを引かない
    // ============================================================

    @Test
    void nonGatheringBreakNeverConsultsTheGate() {
        Fixture fixture = fixtureUnlockedIn(SkillId.MINING);
        // どのカタログにも載っていないブロック。
        Block glass = plainBlock(Material.GLASS, Material.GLASS, fixture.player());

        fixture.listener().onBlockBreak(breakEvent(glass, fixture.player()));

        verify(fixture.player(), never()).giveExp(anyInt());
        verify(fixture.dedicatedEffects(), never()).isActive(any(Player.class), anyString(), any());
    }

    // ============================================================
    // 組み立て
    // ============================================================

    private record Fixture(NativeSkillExperienceListener listener,
                           NativeExperienceDispatcher dispatcher,
                           DedicatedEffectsConfig dedicatedEffects,
                           Player player) {
    }

    /**
     * {@code unlockedSkill} のツリーでだけ {@code feature:break-vanilla-exp-<unlockedSkill>} を
     * 解放している状態。
     *
     * <p>ツリー非限定の2引数版は<b>そのスキル専用idについて</b> <b>true</b> を返すように仕込んである —
     * これが2026-08-01修正前の実装が見ていた値で、「どこか1本で解放している」という事実そのもの。
     * 修正後の実装がこちらを読んでしまうと全ツリーで発火してしまうため、リークのテストはこの仕込みに
     * よって RED になる(2026-08-18のid分割後も、2引数への退行を検出するという核心は変わらない —
     * クラスjavadoc参照)。
     */
    private static Fixture fixtureUnlockedIn(String unlockedSkill) {
        NativeExperienceDispatcher dispatcher = mock(NativeExperienceDispatcher.class);
        NativeSkillCatalog catalog = mock(NativeSkillCatalog.class);
        when(catalog.get(SkillId.FARMING)).thenReturn(FARMING);
        when(catalog.get(SkillId.WOODCUTTING)).thenReturn(WOODCUTTING);
        when(catalog.get(SkillId.MINING)).thenReturn(MINING);
        PlacedBlockTracker placedBlockTracker = mock(PlacedBlockTracker.class);
        when(placedBlockTracker.clearIfPlaced(any())).thenReturn(false);

        Player player = player();
        String unlockedFlag = BreakVanillaExpBonusKeys.featureId(unlockedSkill);
        DedicatedEffectsConfig dedicatedEffects = mock(DedicatedEffectsConfig.class);
        when(dedicatedEffects.isActive(eq(player), eq(unlockedFlag))).thenReturn(true);
        when(dedicatedEffects.isActive(eq(player), eq(unlockedFlag), eq(unlockedSkill))).thenReturn(true);

        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(player)).thenReturn(
                new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        NativeSkillExperienceListener listener = new NativeSkillExperienceListener(
                fakePlugin(), dispatcher, catalog, placedBlockTracker, null, dedicatedEffects, aggregator);
        return new Fixture(listener, dispatcher, dedicatedEffects, player);
    }

    private static Player player() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack pickaxe = stack(Material.IRON_PICKAXE);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItemInMainHand()).thenReturn(pickaxe);
        return player;
    }

    private static Block plainBlock(Material material, Material drop, Player player) {
        Block block = mock(Block.class);
        // モックの生成は thenReturn() の引数内で行わない(入れ子の when() で
        // UnfinishedStubbingException になる — CropMaturityTest 側にも同じ注意書きがある)。
        List<ItemStack> drops = List.of(stack(drop));
        when(block.getType()).thenReturn(material);
        when(block.getDrops(any(), eq(player))).thenReturn(drops);
        return block;
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
        when(block.getDrops(any(), eq(player))).thenReturn(drops);
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
                NativeSkillExperienceListenerBreakVanillaExpScopeTest.class.getClassLoader(),
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
