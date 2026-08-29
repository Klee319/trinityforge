package com.trinityforge.listeners;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.pdc.ItemData;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.SpecialRewardService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * パーティクルシードの<b>発生位置</b> (2026-08-25 / W-242、実サーバ要望
 * 「パーティクルシードはツールが適用された位置の方がいいんじゃない？（壊したブロック、殴った敵の位置）
 * 現状プレイヤーの位置になっている。但し飛び道具（触媒含む）は個別実装がいるかもね？」)。
 *
 * <p><b>着手前</b>: {@code ParticleEffectService.burstAt} が常に
 * {@code origin.getLocation().add(0, 1, 0)} を使っていたので、リスナーが
 * {@code BlockBreakEvent} / {@code EntityDamageByEntityEvent} で<b>当たった座標を受け取っているのに</b>
 * 捨てていた。症状は「遠くのブロックを掘っても粒子は自分の足元から出る」。
 *
 * <p>発生位置は MockBukkit からは観測できない({@code spawnParticle} の呼び出しを記録しない)ので、
 * {@link ParticleSeedListener#burstForTest} で撃つ口を差し替えて基準座標を捕まえる。
 */
class ParticleSeedAnchorTest {

    private static final String SEED_ID = "test-seed";
    private static final String PLAYER_PINNED_SEED = "pinned-seed";

    private ServerMock server;
    private PlayerMock player;
    private ParticleSeedListener listener;
    private final List<Location> anchors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        SpecialRewardsConfig config = mock(SpecialRewardsConfig.class);
        when(config.particleSeeds()).thenReturn(Map.of(
                SEED_ID, new SpecialRewardsConfig.ParticleSeed(SEED_ID, "ignored", Particle.FLAME, 5),
                PLAYER_PINNED_SEED, new SpecialRewardsConfig.ParticleSeed(
                        PLAYER_PINNED_SEED, "ignored", Particle.FLAME,
                        SpecialRewardsConfig.Emission.of(SpecialRewardsConfig.Shape.AURA),
                        null, false, SpecialRewardsConfig.Anchor.PLAYER)));
        player = server.addPlayer();
        player.teleport(new Location(player.getWorld(), 100.0, 70.0, 100.0));
        SpecialRewardService rewards = mock(SpecialRewardService.class);
        listener = new ParticleSeedListener(config, rewards);
        listener.burstForTest((origin, anchor, particle, emission, yaw) -> anchors.add(anchor));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack seededTool(Material material, String seedId) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setParticleSeed(seedId);
        stack.setItemMeta(meta);
        return stack;
    }

    private BlockBreakEvent breakAt(int x, int y, int z) {
        Block block = player.getWorld().getBlockAt(x, y, z);
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getBlock()).thenReturn(block);
        return event;
    }

    @Test
    @DisplayName("ブロック破壊: 粒子は壊したブロックの中心に出る（プレイヤーの足元ではない）")
    void blockBreakAnchorsOnTheBrokenBlock() {
        player.getInventory().setItemInMainHand(seededTool(Material.DIAMOND_PICKAXE, SEED_ID));

        listener.onBlockBreak(breakAt(105, 64, 110));

        assertEquals(1, anchors.size());
        Location anchor = anchors.get(0);
        assertEquals(105.5, anchor.getX(), 1e-9);
        assertEquals(64.5, anchor.getY(), 1e-9);
        assertEquals(110.5, anchor.getZ(), 1e-9);
        assertTrue(anchor.distance(player.getLocation()) > 1.0,
                "プレイヤーの座標が使われている(W-242 の退行)");
    }

    @Test
    @DisplayName("攻撃: 粒子は殴った相手の胴に出る")
    void meleeAnchorsOnTheVictim() {
        player.getInventory().setItemInMainHand(seededTool(Material.DIAMOND_SWORD, SEED_ID));
        ZombieMock target = new ZombieMock(server, UUID.randomUUID());
        target.teleport(new Location(player.getWorld(), 103.0, 70.0, 100.0));

        listener.onAttack(new EntityDamageByEntityEvent(
                player, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0));

        assertEquals(1, anchors.size());
        assertEquals(103.0, anchors.get(0).getX(), 1e-9);
        assertTrue(anchors.get(0).getY() > 70.0, "足元ではなく胴の高さへ持ち上げること");
    }

    @Test
    @DisplayName("origin: player を書いたシードだけは従来どおりプレイヤーに出る（逃げ道が生きている）")
    void playerPinnedSeedStaysOnThePlayer() {
        player.getInventory().setItemInMainHand(seededTool(Material.DIAMOND_PICKAXE, PLAYER_PINNED_SEED));

        listener.onBlockBreak(breakAt(105, 64, 110));

        assertEquals(1, anchors.size());
        assertEquals(player.getLocation().getX(), anchors.get(0).getX(), 1e-9);
        assertEquals(player.getLocation().getZ(), anchors.get(0).getZ(), 1e-9);
    }

    /**
     * 飛び道具は<b>放った瞬間の武器</b>で決まる。着弾時に手を見る作りだと、
     * 撃ってから持ち替えたときに別のシードが出る(あるいは出なくなる)。
     */
    @Test
    @DisplayName("飛び道具: 放った瞬間の弓の刻印が矢へ移り、着弾点で出る（持ち替えても変わらない）")
    void projectileCarriesTheSeedFromLaunchToImpact() {
        player.getInventory().setItemInMainHand(seededTool(Material.BOW, SEED_ID));

        Arrow arrow = mock(Arrow.class);
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(arrow.getPersistentDataContainer()).thenReturn(container);
        when(arrow.getShooter()).thenReturn(player);

        listener.onProjectileLaunch(new ProjectileLaunchEvent(arrow));
        org.mockito.Mockito.verify(container).set(
                PdcKeys.ITEM_PARTICLE_SEED, PersistentDataType.STRING, SEED_ID);

        // 撃ったあとに素手へ持ち替える ── ここで刻印が失われてはいけない。
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        when(container.get(PdcKeys.ITEM_PARTICLE_SEED, PersistentDataType.STRING)).thenReturn(SEED_ID);
        Location impact = new Location(player.getWorld(), 120.0, 66.0, 130.0);
        when(arrow.getLocation()).thenReturn(impact);

        listener.onProjectileHit(new ProjectileHitEvent(arrow, null, player.getWorld().getBlockAt(120, 66, 130)));

        assertEquals(1, anchors.size());
        assertEquals(120.0, anchors.get(0).getX(), 1e-9);
        assertEquals(130.0, anchors.get(0).getZ(), 1e-9);
    }

    @Test
    @DisplayName("刻印の無い弓は矢に何も書かない（無関係な飛び道具にPDCを付けない）")
    void unseededBowTagsNothing() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.BOW));
        Arrow arrow = mock(Arrow.class);
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        when(arrow.getPersistentDataContainer()).thenReturn(container);
        when(arrow.getShooter()).thenReturn(player);

        listener.onProjectileLaunch(new ProjectileLaunchEvent(arrow));

        org.mockito.Mockito.verifyNoInteractions(container);
    }

    @Test
    @DisplayName("矢が刺さった一撃は近接側では扱わない（同じ着弾で二重に出さない）")
    void projectileHitIsNotHandledAsMelee() {
        player.getInventory().setItemInMainHand(seededTool(Material.BOW, SEED_ID));
        ZombieMock target = new ZombieMock(server, UUID.randomUUID());
        Arrow arrow = mock(Arrow.class);

        listener.onAttack(new EntityDamageByEntityEvent(
                arrow, target, EntityDamageEvent.DamageCause.PROJECTILE, 1.0));

        assertTrue(anchors.isEmpty(),
                "近接経路が飛び道具の一撃も拾っている(着弾点と手持ちの武器で二重に出る)");
    }
}
