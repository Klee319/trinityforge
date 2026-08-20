package com.trinityforge.combat;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.listeners.CombatListener;
import com.trinityforge.progression.LocationExpDiminishing;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.LivingEntityMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W-136(2026-08-19 実サーバ報告「軽武器と重武器だけ討伐EXPが少ない」)の回帰。
 *
 * <h2>何が起きていたか</h2>
 * 武器スキルEXPは「命中で台帳へダメージを記録 → 討伐確定時に寄与ぶんだけ支払う」方式
 * ({@code CombatKillCreditTracker} + {@code onCombatKill})。ところが AoE スプラッシュは
 * <b>再入ガード {@code applyingAoe} によって {@code onEntityDamageByEntity} ごと早期return</b>
 * されるため、<b>台帳への記録も一緒に飛ばされていた</b>。結果:
 * <ul>
 *   <li>スプラッシュで止めを刺したモブ → 台帳が空 = EXPが<b>まるごと0</b></li>
 *   <li>主命中と併殺したモブ → 主命中ぶんの share しか立たず目減り</li>
 * </ul>
 * AoE を持つのは大剣(重武器)と鎌(軽武器)、およびパーク由来のパワーアタック範囲(近接全般)だけで、
 * <b>弓術には無い</b>。ユーザー報告の「軽武器と重武器だけ」はこの非対称そのものだった。
 *
 * <p>このテストは「主命中を1度も受けていない周囲のモブ」を討伐して、EXPが支払われることを固定する。
 * 修正を戻すと台帳が空になり {@code onCombatKill} が {@code credits.isEmpty()} で即returnするので、
 * grant が1回も呼ばれず失敗する。
 */
@SuppressWarnings("removal") // deprecated-for-removal event ctors are the only test-constructable ones.
class CombatListenerAoeSplashExpCreditTest {

    private ServerMock server;
    private WorldMock world;
    private NativeExperienceDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        TrinityForge tf = mock(TrinityForge.class);
        when(tf.dungeonWorldRegistry()).thenReturn(new DungeonWorldRegistry());
        LocationExpDiminishing diminishing = mock(LocationExpDiminishing.class);
        when(diminishing.multiplierForKillSpot(any(), any(), any(), anyBoolean())).thenReturn(1.0);
        when(tf.locationExpDiminishing()).thenReturn(diminishing);
        dispatcher = mock(NativeExperienceDispatcher.class);
        when(tf.experienceDispatcher()).thenReturn(dispatcher);
        TrinityForgeSingletonTestSupport.set(tf);
    }

    @AfterEach
    void tearDown() {
        TrinityForgeSingletonTestSupport.clear();
        MockBukkit.unmock();
    }

    /** 鎌(NETHERITE_HOE)相当の AoE 武器。半径3・主命中の30%・対象上限3。 */
    private static void writeItemStats(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  NETHERITE_HOE:
                    fixed:
                      attack-power: 50.0
                      damage-modifier: 1.0
                      aoe-radius: 3.0
                      aoe-damage-rate: 0.3
                      aoe-max-targets: 3
                    use-skill: LIGHT_WEAPONS
                    use-level-requirement: 0
                """);
    }

    private static final String DAMAGE_YAML = """
            physical:
              base-coefficient: 1.0
              min-component-damage: 0.0
            melee-charge:
              enabled: false
            aoe:
              hit-players: false
            """;

    private CombatListener listener(File dir) throws IOException {
        writeItemStats(dir);
        ConfigManager cm = CombatWiringSupport.loadedConfigManager(dir);
        CombatDamageConfig damage = CombatWiringSupport.combatDamageFrom(dir, DAMAGE_YAML);
        Plugin plugin = MockBukkit.createMockPlugin();
        PerkBuffResolver perks = new PerkBuffResolver(SkillPerkStatSource.EMPTY, () -> List.of());
        PlayerStatAggregator aggregator =
                new PlayerStatAggregator(cm.itemStats(), damage, perks, new RoleBuffResolver(cm.roleBuffs()));
        PlayerDefenseResolver defense = new PlayerDefenseResolver(damage.defenseStatKeys(), aggregator);
        SymmetricCombatService svc = new SymmetricCombatService(
                damage, cm.combatLevel(), cm.mobTypes(), SkillLevelSource.EMPTY, defense);
        BleedService bleed = new BleedService(plugin, svc, damage);
        return new CombatListener(plugin, svc, cm.itemStats(),
                damage, SkillLevelSource.EMPTY, bleed, perks, aggregator,
                cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                new RoleBuffResolver(cm.roleBuffs()));
    }

    private void strike(CombatListener listener, Player attacker, Zombie primary) {
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        listener.onEntityDamageByEntity(new EntityDamageByEntityEvent(
                attacker, primary, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0));
    }

    private void killByAttacker(CombatListener listener, Player attacker, Zombie victim) {
        ((LivingEntityMock) victim).setKiller(attacker);
        DamageSource source = DamageSource.builder(DamageType.GENERIC_KILL)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        listener.onCombatKill(new EntityDeathEvent(victim, source, new ArrayList<>()));
    }

    @Test
    void splashOnlyVictimStillEarnsWeaponSkillExp(@TempDir File dir) throws IOException {
        CombatListener listener = listener(dir);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.NETHERITE_HOE));

        Location spot = new Location(world, 0.0, 64.0, 0.0);
        Zombie primary = world.spawn(spot, Zombie.class);
        // 主命中からは 1 ブロックしか離さない(aoe-radius 3.0 の内側)。
        Zombie splashed = world.spawn(new Location(world, 1.0, 64.0, 0.0), Zombie.class);

        strike(listener, attacker, primary);

        // このモブは主命中を一度も受けていない。スプラッシュが台帳へ載っていなければ credits は空。
        killByAttacker(listener, attacker, splashed);

        verify(dispatcher, atLeastOnce())
                .grant(eq(attacker.getUniqueId()), eq("LIGHT_WEAPONS"), anyDouble());
    }

    @Test
    void splashActuallyReachesTheNeighbourMob(@TempDir File dir) throws IOException {
        // 上のテストが「そもそもスプラッシュが飛んでいない」ことで空振り成功しないための土台固定。
        CombatListener listener = listener(dir);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.NETHERITE_HOE));

        Zombie primary = world.spawn(new Location(world, 0.0, 64.0, 0.0), Zombie.class);
        Zombie splashed = world.spawn(new Location(world, 1.0, 64.0, 0.0), Zombie.class);
        double before = splashed.getHealth();

        strike(listener, attacker, primary);

        assertTrue(splashed.getHealth() < before,
                "AoE スプラッシュが隣のモブへ届いていない(このテストの前提が壊れている)");
    }
}
