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
 * 機構8「中断」（殴る／スタンで詠唱を止める、2026-09-04）の {@link MobAbilityExecutor} 統合テスト。
 *
 * <p>{@link MobAbilityInterrupts} を経由せず、{@code onCasterDamaged}/{@code onCasterStunned}
 * （パッケージ非公開）を直接呼んで検証する。static な橋渡し自体は薄い委譲でしかなく、
 * ここでは中断の判定本体（Executor側の台帳とロック）を確認する。
 */
class MobAbilityInterruptTest {

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
                cfg.getConfigurationSection("abilities"), Logger.getLogger("MobAbilityInterruptTest"));
        MobAbility parsed = result.abilities().get(id);
        assertNotNull(parsed, "test ability '" + id + "' failed to parse (skipped=" + result.skipped() + ")");
        return parsed;
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("interruptible な詠唱は最大HPの閾値以上のダメージで中断し、通知とロックがかかる")
    void interruptibleCastIsInterruptedByEnoughDamage(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        List<Component> sent = new ArrayList<>();
        ActionBarRouter router = new ActionBarRouter(System::currentTimeMillis, (p, c) -> sent.add(c));
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                router, new TelegraphBudget());
        MobAbilityCooldowns cooldowns = new MobAbilityCooldowns();
        executor.attachCooldowns(cooldowns);
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 2000, 64, 2000);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility summon = ability("interruptible_summon", """
                type: summon
                interruptible: true
                interrupt-damage-fraction: 0.1
                interrupt-lockout-seconds: 5
                cast-seconds: 1.5
                damage-percent: 0.0
                summon-type: ZOMBIE
                count: 1
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, summon));
        server.getScheduler().performTicks(5);
        sent.clear();

        double maxHealth = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        executor.onCasterDamaged(mob, maxHealth * 0.1); // ちょうど閾値ぶん

        server.getScheduler().performTicks(30); // 詠唱が最後まで残っていたら resolve するはずの猶予

        assertTrue(hits.isEmpty(), "中断されたのに解決している");
        assertTrue(sent.stream().anyMatch(c -> plain(c).contains("詠唱中断")),
                "中断の通知(詠唱中断)が出ていない: " + sent);
        assertFalse(cooldowns.ready(mob.getUniqueId(), "interruptible_summon"),
                "中断ロックが掛かっていない");
    }

    @Test
    @DisplayName("interruptible な詠唱はスタン通知でも即中断する")
    void interruptibleCastIsInterruptedByStun(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 2100, 64, 2100);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility summon = ability("stun_interrupt_summon", """
                type: summon
                interruptible: true
                cast-seconds: 1.5
                damage-percent: 0.0
                summon-type: ZOMBIE
                count: 1
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, summon));
        server.getScheduler().performTicks(5);

        executor.onCasterStunned(mob);
        server.getScheduler().performTicks(30);

        assertTrue(hits.isEmpty(), "スタン中断されたのに解決している");
    }

    @Test
    @DisplayName("interruptible=false の詠唱はダメージを受けても中断しない")
    void nonInterruptibleCastIgnoresDamage(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                new ActionBarRouter(), new TelegraphBudget());
        List<Boolean> hits = new ArrayList<>();
        executor.setHitObserver((player, telegraphed) -> hits.add(telegraphed));

        Location loc = new Location(world, 2200, 64, 2200);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility slam = ability("non_interruptible_slam", """
                type: ground_slam
                cast-seconds: 1.0
                damage-percent: 50.0
                radius: 4.0
                range: 24
                cooldown-seconds: 0
                """);
        assertFalse(slam.interruptible(), "既定は中断不可のはず");

        assertTrue(executor.execute(mob, target, slam));
        server.getScheduler().performTicks(5);

        double maxHealth = mob.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        executor.onCasterDamaged(mob, maxHealth * 10.0); // 極端に大きなダメージでも中断不可なら無視

        server.getScheduler().performTicks(20);

        assertEquals(List.of(true), hits, "中断不可の詠唱がダメージで止まってしまった");
    }

    @Test
    @DisplayName("同じ詠唱の中断は1回だけ(finish の多重発火を許さない)")
    void interruptFiresAtMostOncePerCast(@TempDir File dir) throws Exception {
        SymmetricCombatService combat = combatService(dir);
        List<Component> sent = new ArrayList<>();
        ActionBarRouter router = new ActionBarRouter(System::currentTimeMillis, (p, c) -> sent.add(c));
        MobAbilityExecutor executor = new MobAbilityExecutor(plugin, combat, () -> 0.35, w -> 1.0,
                router, new TelegraphBudget());

        Location loc = new Location(world, 2300, 64, 2300);
        Zombie mob = world.spawn(loc, Zombie.class);
        Player target = server.addPlayer();
        target.teleport(loc);

        MobAbility summon = ability("double_interrupt_summon", """
                type: summon
                interruptible: true
                cast-seconds: 1.5
                damage-percent: 0.0
                summon-type: ZOMBIE
                count: 1
                range: 24
                cooldown-seconds: 0
                """);

        assertTrue(executor.execute(mob, target, summon));
        server.getScheduler().performTicks(5);

        executor.onCasterStunned(mob);
        int noticeCountAfterFirst = (int) sent.stream().filter(c -> plain(c).contains("詠唱中断")).count();
        assertTrue(noticeCountAfterFirst >= 1);

        // 台帳から既に外れているので2回目は no-op のはず(例外にならないことも確認)
        executor.onCasterStunned(mob);
        int noticeCountAfterSecond = (int) sent.stream().filter(c -> plain(c).contains("詠唱中断")).count();
        assertEquals(noticeCountAfterFirst, noticeCountAfterSecond,
                "2回目のスタン通知で中断がもう一度発火した");
    }
}
