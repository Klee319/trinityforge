package com.trinityforge.combat;

import com.trinityforge.TrinityForge;
import com.trinityforge.TrinityForgeSingletonTestSupport;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.ItemStatsConfig;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.listeners.CombatListener;
import com.trinityforge.progression.LocationExpDiminishing;
import com.trinityforge.progression.NativeExperienceDispatcher;
import com.trinityforge.progression.RoleBuffResolver;
import com.trinityforge.progression.SkillLevelSource;
import com.trinityforge.skilltree.runtime.PerkBuffResolver;
import com.trinityforge.skilltree.runtime.SkillPerkStatSource;

import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CombatListener}: 2026-07-27 牧場対策の
 * {@code combat/mob-level-table.yml} {@code no-skill-exp-mobs} 抑止(武器スキルEXP側)。
 *
 * <p>バニラEXPオーブは対象外(このテストが検証するのは {@link NativeExperienceDispatcher#grant}
 * = TrinityForgeの戦闘スキルEXPだけ)。武器は {@code use-skill: HEAVY_WEAPONS} を持つ
 * {@code DIAMOND_SWORD} を使い、victim の EntityType が {@code no-skill-exp-mobs} に載っていれば
 * 討伐してもEXPが一切付与されないこと、載っていないモブには命中時ではなく討伐確定時に一度だけ
 * 付与されることを確認する。
 */
@SuppressWarnings("removal") // deprecated-for-removal event ctors are the only test-constructable ones.
class CombatListenerNoSkillExpMobsTest {

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

    private static void writeItemStats(File dir) throws IOException {
        File itemStats = new File(dir, ItemStatsConfig.PATH);
        Files.createDirectories(itemStats.getParentFile().toPath());
        Files.writeString(itemStats.toPath(), """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 5.0, damage-modifier: 1.0 }
                    use-skill: HEAVY_WEAPONS
                    use-level-requirement: 0
                """);
    }

    private static void writeMobLevelTable(File dir, String yaml) throws IOException {
        File file = new File(dir, MobLevelTableConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
    }

    private static final String DAMAGE_YAML = """
            physical:
              base-coefficient: 1.0
              min-component-damage: 0.0
            melee-charge:
              enabled: false
            """;

    /** {@code includeMobLevelTable}=false なら旧12引数コンストラクタ(mobLevelTable未配線)で組み立てる。 */
    private CombatListener listener(File dir, boolean includeMobLevelTable) throws IOException {
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
        return includeMobLevelTable
                ? new CombatListener(plugin, svc, cm.itemStats(),
                        damage, SkillLevelSource.EMPTY, bleed, perks, aggregator,
                        cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                        new RoleBuffResolver(cm.roleBuffs()), cm.mobLevelTable())
                : new CombatListener(plugin, svc, cm.itemStats(),
                        damage, SkillLevelSource.EMPTY, bleed, perks, aggregator,
                        cm.useRequirements(), cm.skillExp(), cm.craftingFeatures(),
                        new RoleBuffResolver(cm.roleBuffs()));
    }

    private static EntityDamageByEntityEvent strike(
            CombatListener listener, Player attacker, LivingEntity victim) {
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 6.0);
        listener.onEntityDamageByEntity(event);
        return event;
    }

    private static void kill(
            CombatListener listener, Player attacker, LivingEntity victim,
            EntityDamageByEntityEvent finalHit) {
        ((LivingEntityMock) victim).setKiller(attacker);
        victim.setLastDamageCause(finalHit);
        DamageSource source = DamageSource.builder(DamageType.GENERIC_KILL)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        listener.onCombatKill(new EntityDeathEvent(victim, source, new ArrayList<>()));
    }

    @Test
    void noSkillExpMobsBlocksWeaponSkillExpForTargetedType(@TempDir File dir) throws IOException {
        writeMobLevelTable(dir, "no-skill-exp-mobs: [ZOMBIE]\n");
        CombatListener listener = listener(dir, true);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        EntityDamageByEntityEvent finalHit = strike(listener, attacker, victim);
        kill(listener, attacker, victim, finalHit);

        verify(dispatcher, never()).grant(any(), anyString(), anyDouble());
    }

    @Test
    void mobsNotInNoSkillExpMobsStillGrantWeaponSkillExp(@TempDir File dir) throws IOException {
        writeMobLevelTable(dir, "no-skill-exp-mobs: [ZOMBIE]\n");
        CombatListener listener = listener(dir, true);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        // 2026-07-28 以降、EXPが入るのは skill-exp.yml の entity-type-multipliers に行があるモブだけ
        // なので、「no-skill-exp-mobs に無い」ことを見るには載っているモブ(SKELETON)を使う。
        Skeleton victim = world.spawn(world.getSpawnLocation(), Skeleton.class);

        EntityDamageByEntityEvent finalHit = strike(listener, attacker, victim);
        verify(dispatcher, never()).grant(any(), anyString(), anyDouble());

        kill(listener, attacker, victim, finalHit);
        kill(listener, attacker, victim, finalHit);

        verify(dispatcher, times(1))
                .grant(eq(attacker.getUniqueId()), eq("HEAVY_WEAPONS"), anyDouble());
    }

    @Test
    void mobsMissingFromEntityTypeMultipliersGrantNoWeaponSkillExp(@TempDir File dir) throws IOException {
        // 2026-07-28 ユーザー要望「モブ定義にないモブは経験値なし」。CHICKEN は skill-exp.yml の
        // entity-type-multipliers に行が無い = unlisted-entity-multiplier(既定0.0)が効く。
        writeMobLevelTable(dir, "no-skill-exp-mobs: [ZOMBIE]\n");
        CombatListener listener = listener(dir, true);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        Chicken victim = world.spawn(world.getSpawnLocation(), Chicken.class);

        EntityDamageByEntityEvent finalHit = strike(listener, attacker, victim);
        kill(listener, attacker, victim, finalHit);

        verify(dispatcher, never()).grant(any(), anyString(), anyDouble());
    }

    @Test
    void backCompat12ArgConstructorNeverSuppresses(@TempDir File dir) throws IOException {
        // mobLevelTable未配線(旧コンストラクタ)なら、そのモブがどのEntityTypeでも抑止は一切効かない
        // (既存呼び出し元/テストの後方互換)。
        CombatListener listener = listener(dir, false);
        Player attacker = server.addPlayer();
        attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
        Zombie victim = world.spawn(world.getSpawnLocation(), Zombie.class);

        EntityDamageByEntityEvent finalHit = strike(listener, attacker, victim);
        verify(dispatcher, never()).grant(any(), anyString(), anyDouble());

        kill(listener, attacker, victim, finalHit);

        verify(dispatcher).grant(eq(attacker.getUniqueId()), eq("HEAVY_WEAPONS"), anyDouble());
    }

    @Test
    void missingProgressionCatalogFailsSafeWithoutLegacyPerHitExp(@TempDir File dir) throws Exception {
        CombatListener listener = listener(dir, false);
        Player attacker = server.addPlayer();
        Chicken victim = world.spawn(world.getSpawnLocation(), Chicken.class);
        var method = CombatListener.class.getDeclaredMethod(
                "archeryExpAmount", ItemStack.class, double.class, Player.class, LivingEntity.class);
        method.setAccessible(true);

        double amount = (double) method.invoke(
                listener, new ItemStack(Material.BOW), 100.0, attacker, victim);

        assertEquals(0.0, amount,
                "catalog未配線時に削除済みのper-hit EXPへフォールバックしてはならない");
    }
}
