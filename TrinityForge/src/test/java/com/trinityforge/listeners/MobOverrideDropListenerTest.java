package com.trinityforge.listeners;

import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MobOverrideDropListener}: gating on {@code MOB_PROFILE_ID}, world/mob resolution, custom item
 * resolution, and unresolvable-id fail-open. The pure config-parse/priority side is covered by
 * {@code MobOverridesConfigTest}; this covers the listener's own {@code EntityDeathEvent} wiring.
 */
class MobOverrideDropListenerTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("my_dungeon");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MobOverridesConfig loadedConfig(File dataFolder, String yaml) throws Exception {
        File file = new File(dataFolder, MobOverridesConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        MobOverridesConfig config = new MobOverridesConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    /** A {@link SymmetricCombatService} stub whose {@code combatLevelOf} always returns {@code level}
     *  (2026-07-27 足きり新設 — 大半のテストは足きり無関係なので固定値で十分)。 */
    private static SymmetricCombatService combatServiceAtLevel(int level) {
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(level);
        return combatService;
    }

    private static org.bukkit.plugin.Plugin fakePlugin(File dataFolder) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("MobOverrideDropListenerTest");
            case "saveResource" -> null;
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.plugin.Plugin.class.getClassLoader(),
                new Class<?>[]{org.bukkit.plugin.Plugin.class}, handler);
    }

    /** Stamps {@code profileId} (when non-null) and a default player killer, then builds the event. */
    private EntityDeathEvent deathEventFor(org.bukkit.entity.LivingEntity entity, String profileId) {
        return deathEventFor(entity, profileId, server.addPlayer());
    }

    /**
     * @param killer the entity's {@code getKiller()} value, or {@code null} to simulate a non-player
     *               kill (2026-07-26 H2: mob infighting/lava/fall damage etc. — the listener must gate
     *               on this being a player).
     */
    private EntityDeathEvent deathEventFor(org.bukkit.entity.LivingEntity entity, String profileId,
                                            org.bukkit.entity.Player killer) {
        if (profileId != null) {
            entity.getPersistentDataContainer().set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, profileId);
        }
        if (killer != null) {
            ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) entity).setKiller(killer);
        }
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
        List<ItemStack> drops = new ArrayList<>();
        return new EntityDeathEvent(entity, source, drops);
    }

    @Test
    void mobWithoutProfileIdIsSkipped(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(0), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, null); // no MOB_PROFILE_ID stamped
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "no profile id -> no override drop table to resolve");
    }

    @Test
    void nonPlayerKillIsIgnored(@TempDir File dir) throws Exception {
        // 2026-07-26 H2: モブ同士の相打ち・溶岩・落下死等(getKiller() == null)ではドロップを追加しない。
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(0), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief", null); // no killer = non-player kill
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "non-player kill must not roll the override drop table");
    }

    @Test
    void worldScopedDropsApplyToStampedMob(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                  my_dungeon:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: DIAMOND, chance: 1.0, min: 2, max: 2 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(0), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size(), "world scope (my_dungeon) drops replace default scope entirely");
        assertEquals(Material.DIAMOND, event.getDrops().get(0).getType());
        assertEquals(2, event.getDrops().get(0).getAmount());
    }

    @Test
    void unknownMobIdYieldsNoDrops(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      some_other_mob:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(0), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty());
    }

    @Test
    void customCatalogIdResolvesToTheCorrectItem(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: "custom:tf_core_meat", chance: 1.0, min: 3, max: 3 }
                """);
        ItemStack builtStack = new ItemStack(Material.LEATHER);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create("tf_core_meat")).thenReturn(Optional.of(builtStack));
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(0), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size());
        assertEquals(Material.LEATHER, event.getDrops().get(0).getType());
        assertEquals(3, event.getDrops().get(0).getAmount());
    }

    @Test
    void unresolvableCustomIdIsSkippedWithoutThrowing(@TempDir File dir) throws Exception {
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: "custom:not_a_real_catalog_id", chance: 1.0, min: 1, max: 1 }
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create("not_a_real_catalog_id")).thenReturn(Optional.empty());
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(0), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size());
        assertEquals(Material.BONE, event.getDrops().get(0).getType());
    }

    // --- level-cutoff (2026-07-27 「レベル差による足きり」) ---

    @Test
    void overLevelDropRateMinusOneBlocksTfDropsEntirely(@TempDir File dir) throws Exception {
        // over-level.drop-rate == -1 means "TF追加ドロップを一切付けない" once the diff hits the threshold.
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                        level-cutoff:
                          over-level:
                            threshold: 10
                            drop-rate: -1
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        // mob level defaults to 0 (MobData.DEFAULT_LEVEL); a level-30 player makes diff=30 >= threshold 10.
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(30), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "over-level drop-rate=-1 must block every TF-added drop");
    }

    @Test
    void overLevelDropRateBelowThresholdLeavesDropsUntouched(@TempDir File dir) throws Exception {
        // Same config as above, but the killer's combat level is too low to trigger the cutoff.
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                        level-cutoff:
                          over-level:
                            threshold: 10
                            drop-rate: -1
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(5), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size(), "diff (5) below threshold (10) must not trigger the cutoff");
    }

    @Test
    void overLevelDropRateScalesChanceRatherThanBlocking(@TempDir File dir) throws Exception {
        // drop-rate 0.0 (not -1) scales the chance down to zero via multiplication instead of an outright
        // block — same observable zero-drop outcome here, but exercises the multiplier path, not blocksItems.
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                        level-cutoff:
                          over-level:
                            threshold: 10
                            drop-rate: 0.0
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(30), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "chance * drop-rate(0.0) must never roll a hit");
    }

    @Test
    void underLevelItemThresholdBlocksTfDropsEntirely(@TempDir File dir) throws Exception {
        // under-level: the mob is item-threshold-or-more levels ABOVE the player.
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                        level-cutoff:
                          under-level:
                            item-threshold: 20
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        // Player level 1; the mob's stamped combat level (PdcKeys.MOB_LEVEL, what MobData#level() reads)
        // is set directly below to 50, independent of stats.level (which only affects MobProfile#resolve).
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(1), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        zombie.getPersistentDataContainer().set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, 50);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "under-level item-threshold must block every TF-added drop");
    }

    @Test
    void mobLevelCutoffWinsOverScopeLevelCutoff(@TempDir File dir) throws Exception {
        // 4-tier priority: a mob-level block must win over a scope-level block in the same scope.
        MobOverridesConfig config = loadedConfig(dir, """
                overrides:
                  default:
                    level-cutoff:
                      over-level:
                        threshold: 5
                        drop-rate: -1
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                        level-cutoff:
                          over-level:
                            threshold: 999
                            drop-rate: -1
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        // diff=30 would trigger the scope-level block (threshold 5) but NOT the mob-level block
        // (threshold 999), which must win.
        MobOverrideDropListener listener =
                new MobOverrideDropListener(config, resolver, combatServiceAtLevel(30), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size(),
                "the mob-specific level-cutoff block must win over the scope-wide one entirely");
    }
}
