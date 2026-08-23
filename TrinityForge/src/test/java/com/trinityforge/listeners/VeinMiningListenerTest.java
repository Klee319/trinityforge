package com.trinityforge.listeners;

import com.trinityforge.active.FeedbackLayer;
import com.trinityforge.config.domains.DedicatedEffectsConfig;
import com.trinityforge.config.domains.MiningGimmickConfig;
import com.trinityforge.pdc.PlayerData;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link VeinMiningListener#onBlockBreakDropTables}: 2026-07-23 verifier指摘⑧ — drop-table roll is its
 * own {@code MONITOR}+{@code ignoreCancelled=true} handler, gated on the ore-block list AND excluding
 * player-placed blocks ({@link PlacedBlockTracker#isPlaced}, 読み取り専用).
 */
class VeinMiningListenerTest {

    private ServerMock server;
    private DedicatedEffectsConfig dedicatedEffects;
    private MiningGimmickConfig gimmickConfig;
    private CrossPluginItemResolver itemResolver;
    private PlacedBlockTracker placedBlockTracker;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        dedicatedEffects = mock(DedicatedEffectsConfig.class);
        gimmickConfig = mock(MiningGimmickConfig.class);
        itemResolver = mock(CrossPluginItemResolver.class);
        placedBlockTracker = new PlacedBlockTracker(MockBukkit.createMockPlugin());
        when(dedicatedEffects.dropGatePerks()).thenReturn(Map.of());
        player = server.addPlayer();
        // 2026-07-27: 一括破壊にツール判定(GatheringToolMatcher)が入ったため、素手のままだと
        // 発動しない。素のバニラのツルハシはマテリアル推論で MINING として通る。
        player.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.DIAMOND_PICKAXE));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private VeinMiningListener listener() {
        return listener(null);
    }

    private VeinMiningListener listener(com.trinityforge.gathering.ChainBreakExpGrant chainBreakExp) {
        return new VeinMiningListener(dedicatedEffects, gimmickConfig, itemResolver, placedBlockTracker,
                new FeedbackLayer(), chainBreakExp);
    }

    private BlockBreakEvent breakEvent(Block block) {
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.isCancelled()).thenReturn(false);
        when(event.getBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    @Test
    void ignoresBlockNotInOreList() {
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        DropTableConfig.Category category = new DropTableConfig.Category("gacha", "Gacha", 100.0,
                List.of(new DropTableConfig.Entry("tf_gacha_ticket_1", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.STONE);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void ignoresPlayerPlacedOreBlock() {
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        DropTableConfig.Category category = new DropTableConfig.Category("gacha", "Gacha", 100.0,
                List.of(new DropTableConfig.Entry("tf_gacha_ticket_1", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha", category));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.DIAMOND_ORE);
        placedBlockTracker.markPlaced(block);

        listener().onBlockBreakDropTables(breakEvent(block));

        verifyNoInteractions(itemResolver);
    }

    @Test
    void oreBreakRollsDropTableWhenNotPlacedByPlayer() {
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        DropTableConfig.Category category = new DropTableConfig.Category("gacha", "Gacha", 100.0,
                List.of(new DropTableConfig.Entry("tf_gacha_ticket_1", 1, 1)), false);
        when(gimmickConfig.dropTables()).thenReturn(Map.of("gacha", category));
        when(itemResolver.create(eq("tf_gacha_ticket_1")))
                .thenReturn(Optional.of(new ItemStack(Material.PAPER)));

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        block.setType(Material.DIAMOND_ORE);

        int itemsBefore = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();

        listener().onBlockBreakDropTables(breakEvent(block));

        int itemsAfter = player.getWorld().getEntitiesByClass(org.bukkit.entity.Item.class).size();
        assertEquals(1, itemsAfter - itemsBefore,
                "trigger-chance-percent=100 must always draw+drop the sole open entry");
    }

    @Test
    void veinMiningToggleOffPreventsChainBreakEvenWhenUnlocked() {
        // 2026-07-25 gather-rework-active-framework §2 B-2: プレイヤートグルOFFなら一括破壊しない。
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);
        PlayerData.of(player).setVeinMiningEnabled(false);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        neighbor.setType(Material.DIAMOND_ORE);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.DIAMOND_ORE, neighbor.getType(), "toggle OFF must not chain-break neighbors");
    }

    @Test
    void placedOriginOreBlockStillTriggersChainBreak() {
        // 2026-08-24 ユーザー要望「鉱石の一括破壊は手置きのものにも適用されるようにしてほしい」。
        // 元は GTH-02(シルクタッチ回収 → 並べて設置 → 幸運で連鎖)を止めるため起点が設置ブロックなら
        // 発動しない仕様だった。要望により発動させ、代わりに報酬側(EXP)を落とす方式へ切り替えた。
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        neighbor.setType(Material.DIAMOND_ORE);
        placedBlockTracker.markPlaced(origin);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(),
                "手置きの鉱石を起点にしても連鎖破壊が起きること(2026-08-24 の要望)");
    }

    @Test
    void placedOriginOreBlockKeepsItsVanillaExperienceOrb() {
        // 2026-08-24 差し戻しの固定。一度ここで setExpToDrop(0) を入れて「鉱石ブロックを砕いた
        // ときのバニラEXPまで消したらダメでは？」と差し戻された。
        //
        // 要望の「バニラEXPは反映されないように」が指すのは<b>TF のステ「破壊時バニラEXP」</b>
        // (ブロック破壊におまけの経験値を配るパーク)であって、<b>鉱石固有の経験値オーブではない</b>。
        // 前者は NativeSkillExperienceListener の設置マークガードが既に弾いている。
        // 後者はバニラが設置ブロックでも等しく出すので、ここで消すとバニラからの無言の乖離になる。
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        placedBlockTracker.markPlaced(origin);

        BlockBreakEvent event = breakEvent(origin);
        listener().onBlockBreak(event);

        org.mockito.Mockito.verify(event, org.mockito.Mockito.never())
                .setExpToDrop(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void naturalOriginOreBlockKeepsItsVanillaExperienceOrb() {
        // 自然生成側も同様に expToDrop を触らない。設置/自然の両方を固定しておくのは、
        // 「設置だけ落とす」実装を足したときにどちらの側から入れても落ちるようにするため。
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        origin.setType(Material.DIAMOND_ORE);

        BlockBreakEvent event = breakEvent(origin);
        listener().onBlockBreak(event);

        org.mockito.Mockito.verify(event, org.mockito.Mockito.never()).setExpToDrop(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void veinMiningChainBreaksNeighborWhenToggleOnAndUnlocked() {
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);
        // veinMiningEnabled defaults to true; no explicit set needed.

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        Block neighbor = player.getWorld().getBlockAt(1, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        neighbor.setType(Material.DIAMOND_ORE);

        listener().onBlockBreak(breakEvent(origin));

        assertEquals(Material.AIR, neighbor.getType(), "toggle ON + unlocked must chain-break the neighbor");
    }

    // --- 2026-07-28 実サーバ報告「一括採掘で経験値が入らない / 耐久も減っていない」 ---

    @Test
    void chainBrokenOresGrantGatheringExpAndConsumeDurabilityPerBlock() {
        org.bukkit.inventory.ItemStack pickaxe =
                new org.bukkit.inventory.ItemStack(Material.IRON_PICKAXE);
        player.getInventory().setItemInMainHand(pickaxe);
        when(gimmickConfig.oreBlocks()).thenReturn(Set.of(Material.DIAMOND_ORE));
        when(dedicatedEffects.valueMax(any(), eq("vein-mining"))).thenReturn(OptionalDouble.of(1.0));
        when(gimmickConfig.veinMiningMaxExtraBlocks(1)).thenReturn(8);

        Block origin = player.getWorld().getBlockAt(0, 64, 0);
        origin.setType(Material.DIAMOND_ORE);
        for (int x = 1; x <= 3; x++) {
            player.getWorld().getBlockAt(x, 64, 0).setType(Material.DIAMOND_ORE);
        }

        java.util.List<Material> granted = new java.util.ArrayList<>();
        listener((p, block, drops, tool) -> granted.add(block.getType()))
                .onBlockBreak(breakEvent(origin));

        // 起点はイベント本体が処理するので連鎖分3個ぶんだけがここに来る。
        assertEquals(3, granted.size(), "連鎖破壊した3ブロックぶんのEXPが入ること(旧実装は0だった)");
        assertEquals(Material.DIAMOND_ORE, granted.get(0), "破壊前のマテリアルで渡ること");

        org.bukkit.inventory.ItemStack held = player.getInventory().getItemInMainHand();
        int damage = held.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d ? d.getDamage() : 0;
        assertEquals(3, damage, "連鎖破壊した3ブロックぶんの耐久が減ること(旧実装は0のままだった)");
    }
}
