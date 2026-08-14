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

    // ------------------------------------------------------------------------------------------
    // 2026-08-14 フィールドドロップ配線: where(場所) / baby(子供) / chance-by-level(レベル比例確率)
    // ------------------------------------------------------------------------------------------

    /** {@code where:} 判定用: このテスト内でダンジョン扱いにするワールドを作る。 */
    private WorldMock dungeonWorld(String name) {
        WorldMock dungeon = server.addSimpleWorld(name);
        dungeonWorldRegistry.register(dungeon.getUID());
        return dungeon;
    }

    @Test
    void whereFieldDropsOutsideDungeonsButNotInside(@TempDir File dir) throws Exception {
        // 討伐素材は「フィールドの該当モブから。ダンジョンモブには適用しない」がユーザー指示。
        // ダンジョンモブにも MOB_LEVEL は刻まれる(mob-import.yml の synthesize)ので、mobs: だけでは
        // 絞れない —— ワールドで切れていることをここで固定する。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 1.0, min: 1, max: 1, mobs: [ZOMBIE], where: field }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie fieldMob = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent fieldEvent = deathEventFor(fieldMob, 0);
        listener.onDeath(fieldEvent);
        assertEquals(1, fieldEvent.getDrops().size(), "where: field はフィールドで落ちること");

        WorldMock dungeon = dungeonWorld("em_id_the_deep_mines_1");
        Zombie dungeonMob = dungeon.spawn(dungeon.getSpawnLocation(), Zombie.class);
        EntityDeathEvent dungeonEvent = deathEventFor(dungeonMob, 0);
        listener.onDeath(dungeonEvent);
        assertTrue(dungeonEvent.getDrops().isEmpty(),
                "where: field はダンジョンインスタンス内では1個も落ちてはいけない");
    }

    @Test
    void whereDungeonDropsInsideDungeonsButNotOutside(@TempDir File dir) throws Exception {
        // ガチャ券【I】は「ダンジョン内のモブに限定」がユーザー指示。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 1.0, min: 1, max: 1, where: dungeon }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie fieldMob = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent fieldEvent = deathEventFor(fieldMob, 0);
        listener.onDeath(fieldEvent);
        assertTrue(fieldEvent.getDrops().isEmpty(),
                "where: dungeon はフィールドでは1個も落ちてはいけない");

        WorldMock dungeon = dungeonWorld("em_id_the_city_1");
        Zombie dungeonMob = dungeon.spawn(dungeon.getSpawnLocation(), Zombie.class);
        EntityDeathEvent dungeonEvent = deathEventFor(dungeonMob, 0);
        listener.onDeath(dungeonEvent);
        assertEquals(1, dungeonEvent.getDrops().size(), "where: dungeon はダンジョン内で落ちること");
    }

    @Test
    void whereOmittedStillDropsEverywhere(@TempDir File dir) throws Exception {
        // 後方互換: where を書いていない既存エントリの挙動は変わらない。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 1.0, min: 1, max: 1 }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie fieldMob = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent fieldEvent = deathEventFor(fieldMob, 0);
        listener.onDeath(fieldEvent);
        assertEquals(1, fieldEvent.getDrops().size());

        WorldMock dungeon = dungeonWorld("em_id_the_quarry_1");
        Zombie dungeonMob = dungeon.spawn(dungeon.getSpawnLocation(), Zombie.class);
        EntityDeathEvent dungeonEvent = deathEventFor(dungeonMob, 0);
        listener.onDeath(dungeonEvent);
        assertEquals(1, dungeonEvent.getDrops().size());
    }

    @Test
    void babyTrueDropsOnlyForBabyMobs(@TempDir File dir) throws Exception {
        // 速攻のスレッドは「子ゾンビ」限定。Bukkit に BABY_ZOMBIE という EntityType は無いので、
        // mobs: [ZOMBIE] だけでは大人のゾンビにも落ちてしまう。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 1.0, min: 1, max: 1, mobs: [ZOMBIE], baby: true }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie adult = world.spawn(world.getSpawnLocation(), Zombie.class);
        adult.setAdult();
        EntityDeathEvent adultEvent = deathEventFor(adult, 0);
        listener.onDeath(adultEvent);
        assertTrue(adultEvent.getDrops().isEmpty(), "baby: true は大人のゾンビでは落ちてはいけない");

        Zombie baby = world.spawn(world.getSpawnLocation(), Zombie.class);
        baby.setBaby();
        EntityDeathEvent babyEvent = deathEventFor(baby, 0);
        listener.onDeath(babyEvent);
        assertEquals(1, babyEvent.getDrops().size(), "baby: true は子ゾンビで落ちること");
    }

    @Test
    void babyFilterNeverMatchesMobsWithoutAnAgeConcept(@TempDir File dir) throws Exception {
        // Ageable でないモブ(年齢の概念が無い)に baby: を書くと【常に不一致=1個も落ちない】。
        // ロード時に警告を出す仕様なので、挙動としてはここで固定する。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - { material: BONE, chance: 1.0, min: 1, max: 1, baby: true }
                      - { material: STICK, chance: 1.0, min: 1, max: 1, baby: false }
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        org.bukkit.entity.Skeleton skeleton =
                world.spawn(world.getSpawnLocation(), org.bukkit.entity.Skeleton.class);
        EntityDeathEvent event = deathEventFor(skeleton, 0);
        listener.onDeath(event);
        assertTrue(event.getDrops().isEmpty(),
                "SKELETON は Ageable ではないので baby: true も baby: false も一致しない");
    }

    @Test
    void chanceByLevelActuallyGatesTheRollAtRuntime(@TempDir File dir) throws Exception {
        // カーブの端点を 0.0 と 1.0 にして、レベルだけで「絶対落ちない/必ず落ちる」を作る。
        // chance: 0.0(素の値)を無視してカーブを見ていることも同時に固定できる。
        MobLevelTableConfig config = loadedConfig(dir, """
                tiers:
                  - min-level: 0
                    add-drops:
                      - material: BONE
                        chance: 0.0
                        chance-by-level: { from-level: 0, from-chance: 0.0, to-level: 100, to-chance: 1.0 }
                        min: 1
                        max: 1
                """);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobLevelTableListener listener = new MobLevelTableListener(
                config, dungeonWorldRegistry, resolver, new SplittableRandom(0));

        Zombie low = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent lowEvent = deathEventFor(low, 0);
        listener.onDeath(lowEvent);
        assertTrue(lowEvent.getDrops().isEmpty(), "Lv0 はカーブ下端 0.0 なので絶対に落ちない");

        Zombie high = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent highEvent = deathEventFor(high, 100);
        listener.onDeath(highEvent);
        assertEquals(1, highEvent.getDrops().size(), "Lv100 はカーブ上端 1.0 なので必ず落ちる");
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
