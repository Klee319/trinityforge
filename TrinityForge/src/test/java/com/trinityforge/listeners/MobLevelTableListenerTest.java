package com.trinityforge.listeners;

import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.domains.MobLevelTableConfig;
import com.trinityforge.dungeon.DungeonWorldRegistry;
import com.trinityforge.pdc.MobData;
import com.trinityforge.stats.CrossPluginItemResolver;
import org.bukkit.Material;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
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
 * {@link MobLevelTableListener}: 2026-07-25 レベルテーブルのモブ別ドロップ指定拡張 (§2-A mobs filter /
 * §2-B custom: resolution) 回帰テスト (テスト必須7/8/9/10). The pure config-parse side is covered by
 * {@code MobLevelTableConfigTest}; this covers the listener's own filtering/resolution behavior at
 * {@code EntityDeathEvent} time, always with {@code random = new SplittableRandom(0)} seeded via the
 * package-visible test constructor so {@code chance: 1.0} always rolls deterministically.
 */
class MobLevelTableListenerTest {

    private ServerMock server;
    private WorldMock world;
    private DungeonWorldRegistry dungeonWorldRegistry;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        dungeonWorldRegistry = new DungeonWorldRegistry();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Builds a real, loaded {@link MobLevelTableConfig} from inline YAML via a fake resource plugin. */
    private MobLevelTableConfig loadedConfig(File dataFolder, String yaml) throws Exception {
        File file = new File(dataFolder, MobLevelTableConfig.PATH);
        java.nio.file.Files.createDirectories(file.getParentFile().toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml);
        MobLevelTableConfig config = new MobLevelTableConfig();
        config.load(fakePlugin(dataFolder));
        return config;
    }

    private static org.bukkit.plugin.Plugin fakePlugin(File dataFolder) {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("MobLevelTableListenerTest");
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

    /** Stamps the mob level and a default player killer, then builds the event. */
    private EntityDeathEvent deathEventFor(org.bukkit.entity.LivingEntity entity, int level) {
        return deathEventFor(entity, level, server.addPlayer());
    }

    /**
     * @param killer the entity's {@code getKiller()} value, or {@code null} to simulate a non-player
     *               kill (2026-07-26 H2: the listener must gate on this being a player).
     */
    private EntityDeathEvent deathEventFor(org.bukkit.entity.LivingEntity entity, int level,
                                            org.bukkit.entity.Player killer) {
        MobData.stamp(entity, level, DefenseStats.NONE, DefenseStats.NONE);
        if (killer != null) {
            ((org.mockbukkit.mockbukkit.entity.LivingEntityMock) entity).setKiller(killer);
        }
        org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource
                .builder(org.bukkit.damage.DamageType.GENERIC_KILL).build();
        List<ItemStack> drops = new ArrayList<>();
        return new EntityDeathEvent(entity, source, drops);
    }

    @Test
    void nonPlayerKillIsIgnored(@TempDir File dir) throws Exception {
        // 2026-07-26 H2: getKiller() == null (モブ同士の相打ち・溶岩・落下死等)ではドロップもEXPも
        // 一切適用しない — remove-drops/add-drops/vanilla-exp のどれもスキップされること。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 1.0, min: 1, max: 1 }
                    vanilla-exp: 99
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, 0, null); // no killer = non-player kill
        int expBefore = event.getDroppedExp();
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "non-player kill must not add drops");
        assertEquals(expBefore, event.getDroppedExp(), "non-player kill must not overwrite droppedExp either");
    }

    @Test
    void mobsUnspecifiedAppliesToEveryMob(@TempDir File dir) throws Exception {
        // テスト必須7: mobs 未指定 -> 従来どおり全モブに適用(後方互換)。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 1.0, min: 1, max: 1 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent zombieEvent = deathEventFor(zombie, 0);
        listener.onDeath(zombieEvent);
        assertEquals(1, zombieEvent.getDrops().size(), "unfiltered entry must apply to ZOMBIE");

        Chicken chicken = world.spawn(world.getSpawnLocation(), Chicken.class);
        EntityDeathEvent chickenEvent = deathEventFor(chicken, 0);
        listener.onDeath(chickenEvent);
        assertEquals(1, chickenEvent.getDrops().size(), "unfiltered entry must ALSO apply to CHICKEN");
    }

    @Test
    void mobsSpecifiedAppliesOnlyToListedMobs(@TempDir File dir) throws Exception {
        // テスト必須8: mobs 指定 -> 該当モブのみ。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 1.0, min: 1, max: 1, mobs: [ZOMBIE] }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent zombieEvent = deathEventFor(zombie, 0);
        listener.onDeath(zombieEvent);
        assertEquals(1, zombieEvent.getDrops().size(), "ZOMBIE is in the mobs filter -> applies");

        Chicken chicken = world.spawn(world.getSpawnLocation(), Chicken.class);
        EntityDeathEvent chickenEvent = deathEventFor(chicken, 0);
        listener.onDeath(chickenEvent);
        assertTrue(chickenEvent.getDrops().isEmpty(), "CHICKEN is NOT in the mobs filter -> must not apply");
    }

    @Test
    void customCatalogIdResolvesToTheCorrectItem(@TempDir File dir) throws Exception {
        // テスト必須9: custom:<カタログID> が正しいアイテムとして解決される。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: "custom:tf_core_meat", chance: 1.0, min: 3, max: 3 }
                """);
        ItemStack builtStack = new ItemStack(Material.LEATHER);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create("tf_core_meat")).thenReturn(Optional.of(builtStack));
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, 0);
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size());
        ItemStack dropped = event.getDrops().get(0);
        assertEquals(Material.LEATHER, dropped.getType());
        assertEquals(3, dropped.getAmount(), "rolled count must overwrite the resolved stack's amount");
    }

    @Test
    void unresolvableCustomIdIsSkippedWithoutThrowing(@TempDir File dir) throws Exception {
        // テスト必須10: 解決不能なIDは警告+スキップで起動が止まらない(ここでは onDeath が例外を投げない
        // ことと、対応するドロップが単に発生しないことを確認する)。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: "custom:not_a_real_catalog_id", chance: 1.0, min: 1, max: 1 }
                      - { material: BONE, chance: 1.0, min: 1, max: 1 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        when(resolver.create("not_a_real_catalog_id")).thenReturn(Optional.empty());
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, 0);
        listener.onDeath(event);

        // Only the BONE entry produced a drop; the unresolvable custom: entry was silently skipped.
        assertEquals(1, event.getDrops().size());
        assertEquals(Material.BONE, event.getDrops().get(0).getType());
    }
}
