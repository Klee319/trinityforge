package com.trinityforge.progression;

import com.trinityforge.config.domains.SpecialRewardsConfig;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ParticleSeedDelivery}: パーティクルシードを解放したら<b>実際にシード素材が手に入る</b>ことを
 * 固定する (2026-08-21 実サーバ報告「パーティクルシードを入手しても実装がないのでは？
 * アイテムが入手できなかった」)。
 *
 * <p>着手前は {@code special: [seed_*]} の付与がプレイヤーPDCへIDを1つ書くだけで、
 * アイテムも案内も出なかった ── プレイヤーから見た挙動は「何も起きない」。
 */
class ParticleSeedDeliveryTest {

    private static final String SEED_ID = "seed_flame";
    private static final String TITLE_ID = "title_dragonslayer";

    private ServerMock server;
    private SpecialRewardsConfig config;
    private CrossPluginItemResolver itemResolver;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        config = mock(SpecialRewardsConfig.class);
        when(config.particleSeeds()).thenReturn(Map.of(SEED_ID,
                new SpecialRewardsConfig.ParticleSeed(SEED_ID, "BLAZE_POWDER", Particle.FLAME, 6)));
        itemResolver = mock(CrossPluginItemResolver.class);
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ParticleSeedDelivery delivery() {
        return new ParticleSeedDelivery(config, itemResolver, Logger.getLogger("test"));
    }

    @Test
    @DisplayName("シードを解放するとシード素材が1個手に入る")
    void unlockingASeedHandsOverTheSeedItem() {
        when(itemResolver.create("BLAZE_POWDER")).thenReturn(Optional.of(new ItemStack(Material.BLAZE_POWDER, 64)));

        assertTrue(delivery().deliver(player, SEED_ID));

        ItemStack held = player.getInventory().getItem(0);
        assertEquals(Material.BLAZE_POWDER, held == null ? null : held.getType());
        // 64個スタックを解決しても渡すのは1個(seed-item は「素材の指定」であって個数指定ではない)。
        assertEquals(1, held.getAmount());
    }

    @Test
    @DisplayName("称号/パーティクル/未定義IDには手を出さない")
    void nonSeedRewardsAreLeftAlone() {
        assertFalse(delivery().deliver(player, TITLE_ID));
        assertFalse(delivery().deliver(player, "does_not_exist"));
        assertFalse(delivery().deliver(player, null));
        assertFalse(delivery().deliver(null, SEED_ID));
        assertTrue(player.getInventory().isEmpty());
    }

    @Test
    @DisplayName("seed-item が実体化できなくても案内は出す(「何も起きない」に戻さない)")
    void anUnresolvableSeedItemStillAnnounces() {
        when(itemResolver.create("BLAZE_POWDER")).thenReturn(Optional.empty());

        assertTrue(delivery().deliver(player, SEED_ID));

        assertTrue(player.getInventory().isEmpty());
        assertTrue(player.nextMessage() != null, "解放の通知が1件も送られていない");
    }

    @Test
    @DisplayName("itemResolver 未注入でも落ちない(fail-soft)")
    void aMissingResolverIsFailSoft() {
        ParticleSeedDelivery withoutResolver =
                new ParticleSeedDelivery(config, null, Logger.getLogger("test"));

        assertTrue(withoutResolver.deliver(player, SEED_ID));
        assertTrue(player.getInventory().isEmpty());
    }
}
