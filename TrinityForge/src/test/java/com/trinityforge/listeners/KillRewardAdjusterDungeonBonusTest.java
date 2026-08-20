package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.mobs.DungeonLevelReward;
import com.trinityforge.mobs.MobLevelCutoff;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link KillRewardAdjuster} の「ダンジョンの挑戦レベルに応じた報酬の上乗せ」(2026-08-18 W-80)。
 *
 * <p><b>ここが守る一線。</b> 2026-08-18 に一度、同じ狙いを「プレイヤーより高レベルのモブを倒したら
 * レベル差ぶん増やす」というグローバルな条件で実装して差し戻された ── ダンジョンと無関係な
 * オーバーワールドの高レベルモブにも効いてしまい、{@code level-cutoff.under-level}(低レベルのまま
 * 高レベルのモブを狩る行為の抑制、W-73)と真正面から衝突したため。
 * {@link #overworldKillGetsNoBonusEvenAtAHugeMobLevel} がその再発を止める。
 */
class KillRewardAdjusterDungeonBonusTest {

    private ServerMock server;
    private WorldMock overworld;
    private WorldMock dungeon;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        overworld = server.addSimpleWorld("overworld");
        dungeon = server.addSimpleWorld("em_id_the_mines_1");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** 出荷値と同じ形: pivot 35 / 5レベル刻み / drop ±8%毎(上限+50%・下限-30%) / exp ±4%毎(+25%・-20%)。 */
    private static final DungeonLevelReward SHIPPED_SHAPE =
            new DungeonLevelReward(true, 35, 5, 0.08, 0.5, 0.3, 0.04, 0.25, 0.2);

    private KillRewardAdjuster adjuster(int playerLevel, MobLevelCutoff cutoff, DungeonLevelReward reward) {
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(playerLevel);
        CombatDamageConfig damageConfig = mock(CombatDamageConfig.class);
        when(damageConfig.levelCutoff()).thenReturn(cutoff);
        when(damageConfig.dungeonLevelReward()).thenReturn(reward);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any(Player.class)))
                .thenReturn(new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        Set<String> dungeonWorlds = Set.of(dungeon.getName());
        return new KillRewardAdjuster(damageConfig, combatService, aggregator,
                world -> world != null && dungeonWorlds.contains(world.getName()));
    }

    private Zombie mob(WorldMock world, Integer mobLevel) {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        if (mobLevel != null) {
            zombie.getPersistentDataContainer().set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, mobLevel);
        }
        return zombie;
    }

    @Test
    void dungeonKillScalesBothDropChanceAndExpWithTheMobLevel() {
        KillRewardAdjuster adjuster = adjuster(50, MobLevelCutoff.NONE, SHIPPED_SHAPE);
        Player killer = server.addPlayer();
        // レベル50 = pivot 35 から3段上 -> drop +24%, exp +12%
        Zombie zombie = mob(dungeon, 50);
        assertEquals(1.24, adjuster.chanceMultiplier(killer, zombie), 1e-9);
        assertEquals(1.12, adjuster.expMultiplier(killer, zombie), 1e-9);
    }

    @Test
    void dungeonKillBelowThePivotPaysLessThanTheBaseline() {
        // 2026-08-18 ユーザー指示「低いレベルでは規定値より少なくし」。ここが 1.0 に戻ると
        // 頭打ちの倍率を下げた意味が消える(低い側で減らないなら高い側で大きく増やすしかなくなる)。
        KillRewardAdjuster adjuster = adjuster(50, MobLevelCutoff.NONE, SHIPPED_SHAPE);
        Player killer = server.addPlayer();
        Zombie zombie = mob(dungeon, 20);
        assertEquals(0.76, adjuster.chanceMultiplier(killer, zombie), 1e-9);
        assertEquals(0.88, adjuster.expMultiplier(killer, zombie), 1e-9);
    }

    @Test
    void overworldKillGetsNoBonusEvenAtAHugeMobLevel() {
        // 差し戻された実装はここが増えていた。ダンジョン限定であることの直接の証拠。
        KillRewardAdjuster adjuster = adjuster(50, MobLevelCutoff.NONE, SHIPPED_SHAPE);
        Player killer = server.addPlayer();
        Zombie zombie = mob(overworld, 50);
        assertEquals(1.0, adjuster.chanceMultiplier(killer, zombie), 1e-9);
        assertEquals(1.0, adjuster.expMultiplier(killer, zombie), 1e-9);
    }

    @Test
    void overworldKillIsNotPenalisedEitherAtALowMobLevel() {
        // 減少側もダンジョン限定であること。ここが 0.76 になると、オーバーワールドの低レベルモブの
        // 報酬まで一律で削れてしまい、ダンジョンと無関係な帯を壊す。
        KillRewardAdjuster adjuster = adjuster(50, MobLevelCutoff.NONE, SHIPPED_SHAPE);
        Player killer = server.addPlayer();
        Zombie zombie = mob(overworld, 20);
        assertEquals(1.0, adjuster.chanceMultiplier(killer, zombie), 1e-9);
        assertEquals(1.0, adjuster.expMultiplier(killer, zombie), 1e-9);
    }

    @Test
    void playerLevelDoesNotChangeTheBonus() {
        // 見るのはモブのレベルだけ。強い人が同じダンジョンを回しても上乗せは変わらない。
        Player killer = server.addPlayer();
        Zombie zombie = mob(dungeon, 50);
        assertEquals(adjuster(1, MobLevelCutoff.NONE, SHIPPED_SHAPE).chanceMultiplier(killer, zombie),
                adjuster(90, MobLevelCutoff.NONE, SHIPPED_SHAPE).chanceMultiplier(killer, zombie), 1e-9);
    }

    @Test
    void mobWithoutALevelStampGetsNoBonus() {
        // 刻印が無いモブは MobData#level() が 0 を返す。上乗せの根拠が無いので素通りさせる。
        KillRewardAdjuster adjuster = adjuster(50, MobLevelCutoff.NONE, SHIPPED_SHAPE);
        Player killer = server.addPlayer();
        assertEquals(1.0, adjuster.chanceMultiplier(killer, mob(dungeon, null)), 1e-9);
        assertEquals(1.0, adjuster.expMultiplier(killer, mob(dungeon, null)), 1e-9);
    }

    @Test
    void aBlockingCutoffStillWinsInsideTheDungeon() {
        // 高レベルの人に連れて行ってもらった低レベルは under-level で完全遮断される。
        // 上乗せは 0 に掛かるので 0 のまま ── 上乗せが抜け穴にならないことの証拠。
        MobLevelCutoff blocking = new MobLevelCutoff(null, null, null, 20);
        KillRewardAdjuster adjuster = adjuster(1, blocking, SHIPPED_SHAPE);
        Player killer = server.addPlayer();
        Zombie zombie = mob(dungeon, 60);
        assertEquals(0.0, adjuster.chanceMultiplier(killer, zombie), 1e-9);
    }

    @Test
    void disabledRewardLeavesEverythingUntouchedInsideTheDungeon() {
        KillRewardAdjuster adjuster = adjuster(50, MobLevelCutoff.NONE, DungeonLevelReward.NONE);
        Player killer = server.addPlayer();
        Zombie zombie = mob(dungeon, 80);
        assertEquals(1.0, adjuster.chanceMultiplier(killer, zombie), 1e-9);
        assertEquals(1.0, adjuster.expMultiplier(killer, zombie), 1e-9);
    }

    @Test
    void threeArgConstructorNeverAppliesTheDungeonBonus() {
        // ダンジョン判定を渡さない旧入口はテスト専用。渡し忘れたら上乗せは常に効かない(安全側)。
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(50);
        CombatDamageConfig damageConfig = mock(CombatDamageConfig.class);
        when(damageConfig.levelCutoff()).thenReturn(MobLevelCutoff.NONE);
        when(damageConfig.dungeonLevelReward()).thenReturn(SHIPPED_SHAPE);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any(Player.class)))
                .thenReturn(new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));

        KillRewardAdjuster adjuster = new KillRewardAdjuster(damageConfig, combatService, aggregator);
        assertEquals(1.0, adjuster.chanceMultiplier(server.addPlayer(), mob(dungeon, 80)), 1e-9);
    }
}
