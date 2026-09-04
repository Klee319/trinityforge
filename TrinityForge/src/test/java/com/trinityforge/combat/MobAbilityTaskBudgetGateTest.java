package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import com.trinityforge.config.domains.MobOverridesConfig;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 機構5(予告予算)が {@link MobAbilityTask} の抽選段階へ実際に配線されていることの回帰
 * （{@code MobAbilityEngagementGateTest} の「配線が呼ばれている」テストを予告予算向けに写したもの）。
 *
 * <p>候補を<b>予告付きの技1本だけ</b>にしてあるのは、
 * 「候補が全部落ちたら空振りにし、別の技へ振り替えない」という
 * {@code TelegraphBudget} の設計意図（javadoc参照）をそのまま固定するため。
 */
class MobAbilityTaskBudgetGateTest {

    private ServerMock server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            MockBukkit.unmock();
        }
    }

    private MobAbility telegraphedAbility() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                abilities:
                  cast_slam:
                    type: ground_slam
                    chance: 1.0
                    range: 24
                    cooldown-seconds: 0
                    cast-seconds: 1.0
                """);
        MobAbility ability = MobAbilitiesConfig
                .parse(cfg.getConfigurationSection("abilities"), Logger.getLogger("MobAbilityTaskBudgetGateTest"))
                .abilities().get("cast_slam");
        assertTrue(ability.telegraphed(), "テスト用テンプレートが予告付きとして解釈されていない");
        return ability;
    }

    private MobAbilityTask taskWithSingleCandidate(MobAbility ability, TelegraphBudget budget,
                                                    ZombieMock zombie, PlayerMock player,
                                                    MobAbilityExecutor executor) {
        MobAbilitiesConfig abilities = mock(MobAbilitiesConfig.class);
        when(abilities.requireTarget()).thenReturn(false);
        when(abilities.requireLineOfSight()).thenReturn(false);
        when(abilities.ability("cast_slam")).thenReturn(ability);
        when(abilities.globalCooldownMillis()).thenReturn(0L);

        MobOverridesConfig overrides = mock(MobOverridesConfig.class);
        when(overrides.abilitiesFor(anyString(), anyString())).thenReturn(List.of("cast_slam"));

        when(executor.budget()).thenReturn(budget);

        return new MobAbilityTask(MockBukkit.createMockPlugin("TrinityForge"), abilities, overrides, executor,
                new MobAbilityCooldowns(() -> 0L), new Random(1));
    }

    @Test
    @DisplayName("予算が埋まっていると、予告付き1本だけの候補は空振りになり executor.execute は呼ばれない")
    void telegraphedOnlyCandidateMisfiresWhenBudgetIsFull() throws Exception {
        server = MockBukkit.mock();
        WorldMock world = server.addSimpleWorld("budget_world");
        PlayerMock player = server.addPlayer("Victim");
        player.setLocation(new Location(world, 0, 64, 0));
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.setLocation(new Location(world, 3, 64, 0));

        MobAbility ability = telegraphedAbility();

        TelegraphBudget budget = new TelegraphBudget();
        // TOTAL_LIMIT=2 を先に埋めておく(他の技由来という想定)。
        budget.tryReserve(player.getUniqueId(), UUID.randomUUID(), "other-1", false, System.currentTimeMillis() + 10_000_000L);
        budget.tryReserve(player.getUniqueId(), UUID.randomUUID(), "other-2", false, System.currentTimeMillis() + 10_000_000L);

        MobAbilityExecutor executor = mock(MobAbilityExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(true);

        MobAbilityTask task = taskWithSingleCandidate(ability, budget, zombie, player, executor);

        assertFalse(task.tryFire(zombie, player),
                "予算が埋まっている予告付き技しか候補が無いのに発動している(振り替え先が無いはずなのに撃った)");
        verify(executor, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("予算に空きがあれば、同じ予告付き技が候補に残り executor.execute が呼ばれる")
    void telegraphedCandidateFiresWhenBudgetHasRoom() throws Exception {
        server = MockBukkit.mock();
        WorldMock world = server.addSimpleWorld("budget_world_ok");
        PlayerMock player = server.addPlayer("Victim2");
        player.setLocation(new Location(world, 0, 64, 0));
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.setLocation(new Location(world, 3, 64, 0));

        MobAbility ability = telegraphedAbility();
        TelegraphBudget budget = new TelegraphBudget(); // 空

        MobAbilityExecutor executor = mock(MobAbilityExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(true);

        MobAbilityTask task = taskWithSingleCandidate(ability, budget, zombie, player, executor);

        assertTrue(task.tryFire(zombie, player), "予算に空きがあるのに予告付き技が候補から落ちている");
        verify(executor).execute(zombie, player, ability);
    }
}
