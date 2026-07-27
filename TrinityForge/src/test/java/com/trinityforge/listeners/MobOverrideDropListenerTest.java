package com.trinityforge.listeners;

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
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver, new SplittableRandom(0));

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
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver, new SplittableRandom(0));

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
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver, new SplittableRandom(0));

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
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver, new SplittableRandom(0));

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
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver, new SplittableRandom(0));

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
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver, new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size());
        assertEquals(Material.BONE, event.getDrops().get(0).getType());
    }
}
