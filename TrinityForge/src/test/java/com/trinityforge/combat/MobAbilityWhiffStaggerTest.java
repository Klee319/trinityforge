package com.trinityforge.combat;

import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.MobAbilitiesConfig;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 機構7「空振り硬直」（詠唱付きの技が誰にも当たらなかったときの自傷硬直、2026-09-04）の
 * {@link MobAbilityExecutor} 統合テスト。
 */
class MobAbilityWhiffStaggerTest {

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
                cfg.getConfigurationSection("abilities"), Logger.getLogger("MobAbilityWhiffStaggerTest"));
        MobAbility parsed = result.abilities().get(id);
        assertNotNull(parsed, "test ability '" + id + "' failed to parse (skipped=" + result.skipped() + ")");
        return parsed;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("誰にも当たらない解決でnoticeが出て、モブ共通クールダウン(GLOBAL_GAP_KEY)がarmedされる")
    void whiffTriggersStaggerNoticeAndArmsGlobalGap(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        List<Component> sent = new ArrayList<>();
        ActionBarRouter router = new ActionBarRouter(System::currentTimeMillis, (p, c) -> sent.add(c));
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                router, new TelegraphBudget());
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns();
        executor.attachCooldowns(cooldowns);

        Location loc = new Location(world, 3000, 64, 3000);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc.clone().add(50, 0, 0)); // radius(4)の外

        MobAbility slam = ability("whiff_slam", """
                type: ground_slam
                cast-seconds: 1.0
                whiff-stagger-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 64
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, slam));
        server.getScheduler().performTicks(21); // 詠唱完了(20tick) -> 解決(空振り)

        assertTrue(sent.stream().anyMatch(c -> plain(c).contains("体勢を崩した")),
                "空振り硬直の通知が出ていない: " + sent);
        assertFalse(cooldowns.ready(mob.getUniqueId(), MobAbilityTask.GLOBAL_GAP_KEY),
                "空振り硬直がモブ共通クールダウンを armed していない");
    }

    @Test
    @DisplayName("8秒以内の2回目の空振りは硬直しない(間引き)")
    void secondWhiffWithin8SecondsDoesNotStaggerAgain(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        List<Component> sent = new ArrayList<>();
        ActionBarRouter router = new ActionBarRouter(System::currentTimeMillis, (p, c) -> sent.add(c));
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                router, new TelegraphBudget());
        long[] clock = {1_000_000L};
        executor.setClock(() -> clock[0]);

        Location loc = new Location(world, 3100, 64, 3100);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc.clone().add(50, 0, 0));

        MobAbility slam = ability("whiff_slam_2", """
                type: ground_slam
                cast-seconds: 1.0
                whiff-stagger-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 64
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, slam));
        server.getScheduler().performTicks(21);
        long firstStaggerCount = sent.stream().filter(c -> plain(c).contains("体勢を崩した")).count();
        assertEquals(1, firstStaggerCount);

        clock[0] += 3_000L; // 8秒未満の経過
        assertTrue(executor.execute(mob, target, slam));
        server.getScheduler().performTicks(21);
        long secondStaggerCount = sent.stream().filter(c -> plain(c).contains("体勢を崩した")).count();

        assertEquals(1, secondStaggerCount, "8秒以内なのに2回目の空振りでも硬直してしまった");
    }

    @Test
    @DisplayName("1人でも当たれば硬直しない")
    void hittingAtLeastOneVictimSuppressesStagger(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        List<Component> sent = new ArrayList<>();
        ActionBarRouter router = new ActionBarRouter(System::currentTimeMillis, (p, c) -> sent.add(c));
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                router, new TelegraphBudget());
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns();
        executor.attachCooldowns(cooldowns);

        Location loc = new Location(world, 3200, 64, 3200);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc); // radius内 -> 当たる

        MobAbility slam = ability("whiff_slam_hit", """
                type: ground_slam
                cast-seconds: 1.0
                whiff-stagger-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 64
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, slam));
        server.getScheduler().performTicks(21);

        assertTrue(sent.stream().noneMatch(c -> plain(c).contains("体勢を崩した")),
                "命中したのに空振り硬直が発生した: " + sent);
        assertTrue(cooldowns.ready(mob.getUniqueId(), MobAbilityTask.GLOBAL_GAP_KEY),
                "命中したのにモブ共通クールダウンが armed されている");
    }
}
