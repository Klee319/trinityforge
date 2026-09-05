package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.MobAbilitiesConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code FIXED_ZONE}（床に固定された持続領域、2026-09-04）の {@link MobAbilityExecutor} 統合テスト。
 */
class MobAbilityFixedZoneTest {

    private static final SkillLevelSource NO_SKILLS = id -> Map.of();

    private ServerMock server;
    private WorldMock world;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.createMockPlugin("TrinityForge");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private SymmetricCombatService combatService(File dir) throws IOException {
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, """
                physical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                magical:
                  base-coefficient: 1.0
                  min-component-damage: 1.0
                  scale-with-combat-level: false
                level-scaling:
                  per-level: 0.0
                defense:
                  max-dodge-chance: 0.0
                """);
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> java.util.List.of());
        PlayerStatAggregator aggregator = new PlayerStatAggregator(
                cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        return new SymmetricCombatService(damage, cm.combatLevel(), cm.mobTypes(), NO_SKILLS, defense);
    }

    private static MobAbility ability(String id, String yaml) throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("abilities:\n  " + id + ":\n" + yaml.stripIndent().indent(4));
        MobAbilitiesConfig.ParseResult result = MobAbilitiesConfig.parse(
                cfg.getConfigurationSection("abilities"), Logger.getLogger("MobAbilityFixedZoneTest"));
        MobAbility parsed = result.abilities().get(id);
        assertNotNull(parsed, "test ability '" + id + "' failed to parse (skipped=" + result.skipped() + ")");
        return parsed;
    }

    @Test
    @DisplayName("詠唱中は無ダメージ、展開後1秒ごとにhitが届く")
    void fixedZoneDealsNoDamageDuringCastThenTicksAfterExpand(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 1000, 64, 1000);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility zone = ability("test_fixed_zone", """
                type: fixed_zone
                cast-seconds: 1.0
                damage-percent: 0.0
                radius: 4.0
                range: 24
                duration-seconds: 2.0
                cooldown-seconds: 0
                """);
        assertEquals(20, zone.castTicks());

        assertTrue(executor.execute(mob, target, zone));
        assertTrue(hits.isEmpty(), "詠唱中にダメージが出てはいけない");

        server.getScheduler().performTicks(19);
        assertTrue(hits.isEmpty(), "詠唱完了前(19/20tick)にダメージが出た");

        server.getScheduler().performTicks(2); // 詠唱完了 -> 展開(1回目の刻み、delay 0)
        assertEquals(1, hits.size(), "展開直後に1回目の刻みが届いていない");
        assertEquals(List.of(true), hits);

        server.getScheduler().performTicks(20); // 展開後1秒 -> 2回目の刻み
        assertEquals(2, hits.size(), "展開後1秒で2回目の刻みが届いていない");

        server.getScheduler().performTicks(20); // duration-seconds=2 なので、これ以上は刻まない
        assertEquals(2, hits.size(), "duration-seconds を超えて刻み続けている");
    }

    @Test
    @DisplayName("展開時にtelegraph予算が解放される(展開までを予告として数え、展開時に解放)")
    void budgetIsReleasedAtExpansion(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        TelegraphBudget budget = new TelegraphBudget();
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), budget);

        Location loc = new Location(world, 1100, 64, 1100);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility zone = ability("test_fixed_zone_budget", """
                type: fixed_zone
                cast-seconds: 1.0
                damage-percent: 0.0
                radius: 4.0
                range: 24
                duration-seconds: 2.0
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, zone));
        server.getScheduler().performTicks(5);
        assertTrue(!budget.active(target.getUniqueId()).isEmpty(), "詠唱中は予告予算を握っているはず");

        server.getScheduler().performTicks(19); // 詠唱完了・展開
        assertTrue(budget.active(target.getUniqueId()).isEmpty(),
                "展開時に予告予算が解放されていない(展開後は床の持続効果として別枠のはず)");
    }

    @Test
    @DisplayName("術者が展開後に死んでも刻みが続く(床に固定されているのが本質)")
    void expandedZoneKeepsTickingAfterCasterDies(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 1200, 64, 1200);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility zone = ability("test_fixed_zone_death", """
                type: fixed_zone
                cast-seconds: 1.0
                damage-percent: 0.0
                radius: 4.0
                range: 24
                duration-seconds: 3.0
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, zone));
        server.getScheduler().performTicks(21); // 詠唱完了 -> 1回目の刻み
        assertEquals(1, hits.size());

        mob.setHealth(0.0);
        server.getScheduler().performTicks(20); // 2回目の刻み(術者は死亡済み)

        assertEquals(2, hits.size(), "術者が死んでも領域の刻みは続くはず");
    }
}
