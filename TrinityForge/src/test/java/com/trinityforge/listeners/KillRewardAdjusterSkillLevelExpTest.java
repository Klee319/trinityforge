package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.mobs.DungeonLevelReward;
import com.trinityforge.mobs.MobLevelCutoff;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.core.SkillId;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * レベル差の足きり(={@code combat/damage.yml} の {@code level-cutoff.under-level})が
 * <b>何のレベルと比べるか</b>を固定する(2026-08-22 ユーザー指示)。
 *
 * <ul>
 *   <li>職業(スキル)EXP → <b>そのEXPが入る職業のレベル</b>({@link KillRewardAdjuster#skillExpMultiplier})</li>
 *   <li>バニラの経験値オーブ・TF追加ドロップ → <b>戦闘レベル</b>({@link KillRewardAdjuster#expMultiplier})</li>
 * </ul>
 *
 * <p><b>なぜ分けたか。</b> 戦闘レベルは {@code progression/combat-level.yml} の pillar 写像で
 * 全スキルを1つに畳んだ値で、単一特化のプレイヤーでは最高スキルの約 2/3 にしかならない
 * (top1 の divisor が 1.5)。つまり<b>畳んだ結果とEXPの帰属先が別物</b>だった。
 * このテストはその食い違いを両方向とも実際の数で固定する ──
 * 呼び出し側を {@code expMultiplier} へ戻すと下の2本が同時に落ちる。
 */
class KillRewardAdjusterSkillLevelExpTest {

    /** 軽武器100 の純特化。戦闘レベルは 100/1.5 = 67 にしかならない。 */
    private static final int COMBAT_LEVEL_OF_PURE_SPECIALIST = 67;
    private static final int LIGHT_WEAPONS_LEVEL = 100;
    /** 同じ人の魔法は未着手。 */
    private static final int ARS_MAGIC_LEVEL = 1;
    private static final int MOB_LEVEL = 100;

    /** 出荷値と同じ形: アイテムは20差で完全遮断 / 経験値は15差から 0.067 ずつ減って30差で0。 */
    private static final MobLevelCutoff SHIPPED_SHAPE = new MobLevelCutoff(
            -1, 1.0, 1.0, 20, 0.0, 0.0, 0.0,
            1.0, -1.0, 0.067, 0.0, 0.0, 15);

    private ServerMock server;
    private WorldMock overworld;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        overworld = server.addSimpleWorld("overworld");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private KillRewardAdjuster adjuster() {
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(COMBAT_LEVEL_OF_PURE_SPECIALIST);
        when(combatService.skillLevelOf(any(), eq(SkillId.LIGHT_WEAPONS))).thenReturn(LIGHT_WEAPONS_LEVEL);
        when(combatService.skillLevelOf(any(), eq(SkillId.ARS_MAGIC))).thenReturn(ARS_MAGIC_LEVEL);
        CombatDamageConfig damageConfig = mock(CombatDamageConfig.class);
        when(damageConfig.levelCutoff()).thenReturn(SHIPPED_SHAPE);
        when(damageConfig.dungeonLevelReward()).thenReturn(DungeonLevelReward.NONE);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any(Player.class)))
                .thenReturn(new PlayerCombatAggregate(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()));
        // ダンジョン上乗せが混ざらないよう、判定を常に false にする。
        return new KillRewardAdjuster(damageConfig, combatService, aggregator, world -> false);
    }

    private Zombie mob() {
        Zombie zombie = overworld.spawn(overworld.getSpawnLocation(), Zombie.class);
        zombie.getPersistentDataContainer().set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, MOB_LEVEL);
        return zombie;
    }

    @Test
    @DisplayName("軽武器100 の人は Lv100 モブから軽武器EXPを満額もらえる(戦闘レベル67で判定すると削られていた)")
    void skillExpUsesTheOwningSkillLevel() {
        Player player = server.addPlayer();
        KillRewardAdjuster adjuster = adjuster();

        // 軽武器スキル(100) vs モブ(100) = レベル差 0 なので足きりは発動しない。
        assertEquals(1.0, adjuster.skillExpMultiplier(player, mob(), SkillId.LIGHT_WEAPONS), 1e-9,
                "軽武器スキルがモブと同レベルなのに削られている(戦闘レベルで判定していないか)");

        // 戦闘レベル(67) で判定すると差 33 → 15差起点・30差で0の帯を超えるので 0。
        // 同じキルでもバニラEXPは 0 ── ここが「畳んだ値と帰属先の食い違い」の実物。
        assertEquals(0.0, adjuster.expMultiplier(player, mob()), 1e-9,
                "バニラEXPは戦闘レベル基準のままであること");
    }

    @Test
    @DisplayName("同じ人が魔法1のまま Lv100 モブを狩っても魔法EXPは入らない(戦闘レベル基準では素通りしていた穴)")
    void laggingSkillIsNoLongerShieldedByTheOtherPillars() {
        Player player = server.addPlayer();
        KillRewardAdjuster adjuster = adjuster();

        // 魔法(1) vs モブ(100) = 差 99。30差で0の帯を大きく超えるので完全に0。
        assertEquals(0.0, adjuster.skillExpMultiplier(player, mob(), SkillId.ARS_MAGIC), 1e-9,
                "遅れている職業が、伸びている柱の戦闘レベルに守られて素通りしている");
    }

    @Test
    @DisplayName("スキルidが無い/未知でもレベル0扱いで落ちない")
    void unknownSkillFallsBackToLevelZero() {
        Player player = server.addPlayer();
        KillRewardAdjuster adjuster = adjuster();

        assertEquals(0.0, adjuster.skillExpMultiplier(player, mob(), null), 1e-9);
        assertEquals(0.0, adjuster.skillExpMultiplier(player, mob(), "NOT_A_SKILL"), 1e-9);
    }

    @Test
    @DisplayName("TF追加ドロップは戦闘レベル基準のまま(職業レベルへ道連れにしない)")
    void itemCutoffStillUsesTheCombatLevel() {
        Player player = server.addPlayer();
        KillRewardAdjuster adjuster = adjuster();

        // 戦闘レベル 67 vs モブ 100 = 差 33 >= item-threshold 20 → 完全遮断。
        // 軽武器スキルが100(差0)でもここは緩まない。
        assertTrue(adjuster.blocksItems(player, mob()),
                "アイテム側まで職業レベル基準に巻き込まれている");
    }
}
