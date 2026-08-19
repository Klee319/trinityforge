package com.trinityforge.listeners;

import com.trinityforge.config.domains.RoleBuffsConfig;
import com.trinityforge.config.domains.RoleBuffsConfig.PotionBuffSpec;
import com.trinityforge.config.domains.RoleBuffsConfig.SupportRoleSpec;
import com.trinityforge.pdc.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleBuffListenerTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void persistedRolesLoadedIntoNewPlayerInstanceAreReadOnJoinAndReapplySupportEffect() {
        Player beforeLogout = server.addPlayer();
        PlayerData.of(beforeLogout).setRoles("tank", "digger");

        Player player = server.addPlayer();
        beforeLogout.getPersistentDataContainer()
                .copyTo(player.getPersistentDataContainer(), true);

        RoleBuffListener listener = new RoleBuffListener(configWithDiggerSpeed());
        listener.onJoin(new PlayerJoinEvent(player, Component.empty()));

        assertEquals("tank", PlayerData.of(player).rolePrimary().orElseThrow());
        assertEquals("digger", PlayerData.of(player).roleSupport().orElseThrow());
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals(0, player.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
    }

    /**
     * 2026-08-18 ユーザー報告「幸運のエフェクトが消える」の TF 側の回帰ガード。
     *
     * <p>{@code role-buffs.yml} の {@code fisher} は LUCK を付けるので、旧実装の
     * 「サポートロールが付け得る型を全部剥がす」一括除去は、<b>ロールが fisher でなくても</b>
     * プレイヤーが付けた LUCK をログイン/リスポーンのたびに消していた。
     */
    @Test
    void manuallyGrantedLuckSurvivesTheRoleBuffRefresh() {
        Player player = server.addPlayer();
        PlayerData.of(player).setRoles("tank", "digger");
        // /effect give @s luck 30 の形: ambient=false, particles=true。
        player.addPotionEffect(new PotionEffect(PotionEffectType.LUCK, 600, 0, false, true, true));

        new RoleBuffListener(configWithDiggerSpeedAndFisherLuck()).refreshSupportBuff(player);

        assertTrue(player.hasPotionEffect(PotionEffectType.LUCK),
                "プレイヤー起因の幸運がロールバフ同期で消えている(ユーザー報告そのもの)");
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED), "現ロールのバフが付いていない");
    }

    @Test
    void infiniteLuckFromThreadsSurvivesTheRoleBuffRefresh() {
        Player player = server.addPlayer();
        PlayerData.of(player).setRoles("tank", "digger");
        // Ars のスレッド由来: 無期限・ambient=true・particles=false(形は自前バフと同じでも無期限)。
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.LUCK, PotionEffect.INFINITE_DURATION, 0, true, false, true));

        new RoleBuffListener(configWithDiggerSpeedAndFisherLuck()).refreshSupportBuff(player);

        assertTrue(player.hasPotionEffect(PotionEffectType.LUCK),
                "スレッド由来の無期限幸運をロールバフ同期が剥がしている");
    }

    @Test
    void previousRoleBuffIsStillRemovedWhenTheRoleChanges() {
        Player player = server.addPlayer();
        PlayerData.of(player).setRoles("tank", "digger");
        // 直前まで fisher だった状態を再現: 自前で付けた形(ambient=true/particles=false/有限)。
        player.addPotionEffect(new PotionEffect(PotionEffectType.LUCK, 999_999, 0, true, false, true));

        new RoleBuffListener(configWithDiggerSpeedAndFisherLuck()).refreshSupportBuff(player);

        assertFalse(player.hasPotionEffect(PotionEffectType.LUCK),
                "旧ロールのバフが残っている(ロールを変えても前のバフが効き続ける)");
        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
    }

    /**
     * <b>2026-08-19 ユーザー報告「職業バフが付いていない人がまだいる／確認しているのは火炎耐性」の回帰ガード。</b>
     *
     * <p>HuskSync の snapshot 適用は {@link PlayerJoinEvent} より後で、
     * {@code PotionEffects#apply} が<b>今付いている効果を全部剥がしてから</b>保存済みの効果を付け直す。
     * 参加イベントの中で付けると必ずその除去に巻き込まれるので、
     * <b>参加時の再付与は遅延していなければならない</b>。
     */
    @Test
    void joinReapplyIsDeferredPastTheHuskSyncSnapshotWindow() {
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        Player player = server.addPlayer();
        PlayerData.of(player).setRoles("tank", "digger");

        RoleBuffListener listener = new RoleBuffListener(configWithDiggerSpeed(), plugin);
        listener.onJoin(new PlayerJoinEvent(player, Component.empty()));

        assertFalse(player.hasPotionEffect(PotionEffectType.SPEED),
                "参加イベントの中で付けると HuskSync の効果差し替えに消される");

        server.getScheduler().performTicks(41L);

        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED),
                "HuskSync の適用が終わったあとに付け直されていない");
    }

    /**
     * 常時バフの自己修復。牛乳・{@code /effect clear}・浄化・{@code duration} 満了・
     * HuskSync の遅れた適用のどれで落ちても、次の定期リフレッシュで戻ること。
     * これが無いと、一度失ったプレイヤーは<b>再ログインしても戻らない</b>。
     */
    @Test
    void periodicRefreshRestoresABuffThatWasClearedAfterJoin() {
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        Player player = server.addPlayer();
        PlayerData.of(player).setRoles("tank", "digger");

        RoleBuffListener listener = new RoleBuffListener(configWithDiggerSpeed(), plugin);
        listener.refreshSupportBuff(player);
        listener.startPeriodicRefresh();

        player.removePotionEffect(PotionEffectType.SPEED); // 牛乳などで全消しされた状態
        assertFalse(player.hasPotionEffect(PotionEffectType.SPEED));

        server.getScheduler().performTicks(20L * 60L + 1L);

        assertTrue(player.hasPotionEffect(PotionEffectType.SPEED),
                "定期リフレッシュが無いと、一度落ちた常時バフは二度と戻らない");
    }

    private static RoleBuffsConfig configWithDiggerSpeed() {
        PotionBuffSpec potion = new PotionBuffSpec(PotionEffectType.SPEED, 999_999, 0);
        SupportRoleSpec digger = new SupportRoleSpec(
                "digger", "土工", "DIGGING", 1.2, potion, "IRON_SHOVEL", List.of());
        RoleBuffsConfig config = mock(RoleBuffsConfig.class);
        when(config.supportRoles()).thenReturn(Map.of("digger", digger));
        when(config.supportRole("digger")).thenReturn(digger);
        return config;
    }

    /** 出荷 yml と同じ形: サポートロールのうち fisher だけが LUCK を付ける。 */
    private static RoleBuffsConfig configWithDiggerSpeedAndFisherLuck() {
        SupportRoleSpec digger = new SupportRoleSpec(
                "digger", "土工", "DIGGING", 1.2,
                new PotionBuffSpec(PotionEffectType.SPEED, 999_999, 0), "IRON_SHOVEL", List.of());
        SupportRoleSpec fisher = new SupportRoleSpec(
                "fisher", "漁師", "FISHING", 1.2,
                new PotionBuffSpec(PotionEffectType.LUCK, 999_999, 0), "FISHING_ROD", List.of());
        RoleBuffsConfig config = mock(RoleBuffsConfig.class);
        when(config.supportRoles()).thenReturn(Map.of("digger", digger, "fisher", fisher));
        when(config.supportRole("digger")).thenReturn(digger);
        when(config.supportRole("fisher")).thenReturn(fisher);
        return config;
    }
}
