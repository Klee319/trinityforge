package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.mobs.MobLevelCutoff;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.LivingEntityMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LevelCutoffExpListener}: 「レベル差による足きり」をバニラの経験値オーブへ掛ける
 * (2026-08-09、{@code combat/damage.yml} の {@code level-cutoff})。
 *
 * <p>移設前(2026-07-27〜)は {@link MobOverrideExpListener} の中にあり、設定が
 * {@code combat/mob-overrides.yml} 由来だったため <b>EliteMobs が {@code MOB_PROFILE_ID} を
 * スタンプしたモブにしか掛からなかった</b>。ここで検証している「profileId が無いモブにも効く」は
 * 移設で初めて成立した挙動なので、旧実装に差し戻すと {@link #cutoffAppliesToMobsWithoutAProfileId} が落ちる。
 */
class LevelCutoffExpListenerTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("overworld");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static LevelCutoffExpListener listenerFor(int playerLevel, MobLevelCutoff cutoff) {
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(playerLevel);
        CombatDamageConfig damageConfig = mock(CombatDamageConfig.class);
        when(damageConfig.levelCutoff()).thenReturn(cutoff);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any(Player.class)))
                .thenReturn(new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        return new LevelCutoffExpListener(new KillRewardAdjuster(damageConfig, combatService, aggregator));
    }

    /**
     * @param mobLevel   {@code PdcKeys.MOB_LEVEL} に刻むレベル。{@code null} なら刻印しない
     *                   (= TF管理下でない野良モブ。{@code MobData#hasProfile()} が false になる)
     */
    private EntityDeathEvent deathEvent(Integer mobLevel, Player killer) {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        if (mobLevel != null) {
            zombie.getPersistentDataContainer().set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, mobLevel);
        }
        if (killer != null) {
            ((LivingEntityMock) zombie).setKiller(killer);
        }
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
        EntityDeathEvent event = new EntityDeathEvent(zombie, source, new ArrayList<ItemStack>());
        event.setDroppedExp(100);
        return event;
    }

    @Test
    void overLevelExpRateScalesTheDroppedExp() {
        // diff = 30 - 0 = 30 >= threshold 10 -> 発動。100 * 0.25 = 25。
        EntityDeathEvent event = deathEvent(0, server.addPlayer());
        listenerFor(30, new MobLevelCutoff(10, 0.25, null, null)).onDeath(event);
        assertEquals(25, event.getDroppedExp(), "100 * 0.25 = 25");
    }

    @Test
    void overLevelExpRateMinusOneZeroesOutTheDroppedExp() {
        EntityDeathEvent event = deathEvent(0, server.addPlayer());
        listenerFor(30, new MobLevelCutoff(10, -1.0, null, null)).onDeath(event);
        assertEquals(0, event.getDroppedExp(), "exp-rate=-1 は経験値を完全に0にする");
    }

    @Test
    void belowThresholdLeavesDroppedExpUntouched() {
        EntityDeathEvent event = deathEvent(0, server.addPlayer());
        listenerFor(5, new MobLevelCutoff(10, -1.0, null, null)).onDeath(event);
        assertEquals(100, event.getDroppedExp(), "diff(5) が threshold(10) 未満なら発動しない");
    }

    @Test
    void unconfiguredCutoffLeavesDroppedExpUntouched() {
        // 「未設定 = 触らない」。既定の damage.yml(threshold -1 / rate 1.0)がこの状態。
        EntityDeathEvent event = deathEvent(0, server.addPlayer());
        listenerFor(30, MobLevelCutoff.NONE).onDeath(event);
        assertEquals(100, event.getDroppedExp());
    }

    @Test
    void underLevelCutoffNeverAffectsExp() {
        // under-level は TF追加ドロップ専用の足きり。経験値には一切関与しない。
        EntityDeathEvent event = deathEvent(50, server.addPlayer());
        listenerFor(1, new MobLevelCutoff(null, null, null, 20)).onDeath(event);
        assertEquals(100, event.getDroppedExp(), "under-level は経験値を触らない");
    }

    @Test
    void cutoffAppliesToMobsWithoutAProfileId() {
        // 移設の目的そのもの: MOB_PROFILE_ID の無いモブ(= EliteMobs 管理外)でも、レベル刻印さえあれば効く。
        // 旧実装は profileId が空の時点で return していたのでここが 100 のままだった。
        EntityDeathEvent event = deathEvent(0, server.addPlayer());
        listenerFor(30, new MobLevelCutoff(10, 0.5, null, null)).onDeath(event);
        assertEquals(50, event.getDroppedExp(), "profileId が無くてもレベル差の足きりは効く");
    }

    @Test
    void mobWithoutALevelStampIsNeverJudged() {
        // レベル刻印すら無いモブは MobData#level() が 0 を返すため、そのまま比べると
        // 「プレイヤーが格上」が常に成立してしまう。hasProfile() で弾いているのを固定する。
        EntityDeathEvent event = deathEvent(null, server.addPlayer());
        listenerFor(30, new MobLevelCutoff(10, 0.0, null, null)).onDeath(event);
        assertEquals(100, event.getDroppedExp(), "レベル刻印の無いモブは足きりの対象外");
    }

    @Test
    void nonPlayerKillIsIgnored() {
        // モブ同士の相打ち・溶岩・落下死。バニラ自体が 0 を返すので実害は無いが判定を揃える。
        EntityDeathEvent event = deathEvent(0, null);
        listenerFor(30, new MobLevelCutoff(10, 0.0, null, null)).onDeath(event);
        assertEquals(100, event.getDroppedExp());
    }

    @Test
    void zeroExpKillIsLeftAlone() {
        EntityDeathEvent event = deathEvent(0, server.addPlayer());
        event.setDroppedExp(0);
        listenerFor(30, new MobLevelCutoff(10, 0.25, null, null)).onDeath(event);
        assertEquals(0, event.getDroppedExp());
    }

    @Test
    void roundingKeepsSmallExpKillsFromVanishing() {
        // 3 * 0.25 = 0.75 -> Math.round で 1。切り捨てだと「わずかな経験値が常に消える」ことになる。
        EntityDeathEvent event = deathEvent(0, server.addPlayer());
        event.setDroppedExp(3);
        listenerFor(30, new MobLevelCutoff(10, 0.25, null, null)).onDeath(event);
        assertEquals(1, event.getDroppedExp(), "端数は四捨五入する(0にはしない)");
    }
}
