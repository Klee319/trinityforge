package com.trinityforge.listeners;

import com.trinityforge.combat.PlayerCombatAggregate;
import com.trinityforge.combat.PlayerStatAggregator;
import com.trinityforge.combat.SymmetricCombatService;
import com.trinityforge.config.domains.CombatDamageConfig;
import com.trinityforge.config.domains.MobOverridesConfig;
import com.trinityforge.mobs.MobLevelCutoff;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.stats.CrossPluginItemResolver;
import com.trinityforge.stats.StatKeys;
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
import java.util.Map;
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

    /** 足きり無し・ドロップ増加ステ無しの {@link KillRewardAdjuster}(大半のテストはどちらも無関係)。 */
    private static KillRewardAdjuster adjuster(int playerLevel) {
        return adjuster(playerLevel, MobLevelCutoff.NONE, 0.0);
    }

    private static KillRewardAdjuster adjuster(int playerLevel, MobLevelCutoff cutoff) {
        return adjuster(playerLevel, cutoff, 0.0);
    }

    /**
     * 2026-08-09: 足きりの設定源が {@code combat/mob-overrides.yml} から共通の
     * {@code combat/damage.yml} へ移ったので、テストも yml ではなく
     * {@link CombatDamageConfig#levelCutoff()} をスタブして与える。
     *
     * @param playerLevel {@code combatLevelOf} が常に返す討伐者の戦闘レベル
     * @param cutoff      レベル差による足きり設定
     * @param dropBonus   {@code mob_drop_bonus} のプレイヤー総合値(0.0 = ボーナス無し)
     */
    private static KillRewardAdjuster adjuster(int playerLevel, MobLevelCutoff cutoff, double dropBonus) {
        SymmetricCombatService combatService = mock(SymmetricCombatService.class);
        when(combatService.combatLevelOf(any())).thenReturn(playerLevel);
        CombatDamageConfig damageConfig = mock(CombatDamageConfig.class);
        when(damageConfig.levelCutoff()).thenReturn(cutoff);
        PlayerStatAggregator aggregator = mock(PlayerStatAggregator.class);
        when(aggregator.aggregate(any(org.bukkit.entity.Player.class))).thenReturn(new PlayerCombatAggregate(
                Map.of(StatKeys.canonical("mob_drop_bonus"), dropBonus), Map.of(), Map.of(), Map.of(), Map.of()));
        return new KillRewardAdjuster(damageConfig, combatService, aggregator);
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
                new MobOverrideDropListener(config, resolver, adjuster(0), new SplittableRandom(0));

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
                new MobOverrideDropListener(config, resolver, adjuster(0), new SplittableRandom(0));

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
                new MobOverrideDropListener(config, resolver, adjuster(0), new SplittableRandom(0));

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
                new MobOverrideDropListener(config, resolver, adjuster(0), new SplittableRandom(0));

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
                new MobOverrideDropListener(config, resolver, adjuster(0), new SplittableRandom(0));

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
                new MobOverrideDropListener(config, resolver, adjuster(0), new SplittableRandom(0));

        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size());
        assertEquals(Material.BONE, event.getDrops().get(0).getType());
    }

    // --- レベル差による足きり (2026-08-09 に combat/damage.yml へ移設) ---
    // 設定は yml ではなく KillRewardAdjuster 経由。yml に level-cutoff: を書いても効かなくなったので、
    // これらのテストは移設前の実装に戻すと(足きりが mob-overrides 側の未設定=NONE を読むため)全て落ちる。

    /**
     * レベルを刻印した(= {@code MobData#hasProfile()} が true になる)ゾンビ。
     * 足きりは刻印の無いモブを判定しない({@code level()} が 0 を返し「常にプレイヤーが格上」に
     * 化けるため)ので、足きりのテストでは必ずレベルを刻む必要がある。
     */
    private Zombie leveledZombie(int level) {
        Zombie zombie = world.spawn(world.getSpawnLocation(), Zombie.class);
        zombie.getPersistentDataContainer().set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, level);
        return zombie;
    }

    /** BONE を確率1.0で1個落とすだけの共通 config。足きりの効き方だけを見たいので中身は最小限。 */
    private MobOverridesConfig boneDropConfig(File dir) throws Exception {
        return loadedConfig(dir, """
                overrides:
                  default:
                    mobs:
                      goblin_chief:
                        drops:
                          - { item: BONE, chance: 1.0, min: 1, max: 1 }
                """);
    }

    @Test
    void overLevelDropRateMinusOneBlocksTfDropsEntirely(@TempDir File dir) throws Exception {
        // over-level.drop-rate == -1 = 「TF追加ドロップを一切付けない」。
        MobOverridesConfig config = boneDropConfig(dir);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        // モブのレベルは既定0(MobData.DEFAULT_LEVEL)。Lv30のプレイヤーなら diff=30 >= threshold 10。
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver,
                adjuster(30, new MobLevelCutoff(10, null, -1.0, null)), new SplittableRandom(0));

        Zombie zombie = leveledZombie(0);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "over-level drop-rate=-1 must block every TF-added drop");
    }

    @Test
    void overLevelDropRateBelowThresholdLeavesDropsUntouched(@TempDir File dir) throws Exception {
        // 同じ足きり設定でも、討伐者のレベルが閾値に届かなければ発動しない。
        MobOverridesConfig config = boneDropConfig(dir);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver,
                adjuster(5, new MobLevelCutoff(10, null, -1.0, null)), new SplittableRandom(0));

        Zombie zombie = leveledZombie(0);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size(), "diff (5) below threshold (10) must not trigger the cutoff");
    }

    @Test
    void overLevelDropRateScalesChanceRatherThanBlocking(@TempDir File dir) throws Exception {
        // drop-rate 0.0 は -1 と違い「遮断」ではなく「確率へ 0.0 を乗算」。観測結果は同じ0個だが、
        // blocksItems ではなく chanceMultiplier 経路を通ることを確かめる。
        MobOverridesConfig config = boneDropConfig(dir);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver,
                adjuster(30, new MobLevelCutoff(10, null, 0.0, null)), new SplittableRandom(0));

        Zombie zombie = leveledZombie(0);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "chance * drop-rate(0.0) must never roll a hit");
    }

    @Test
    void underLevelItemThresholdBlocksTfDropsEntirely(@TempDir File dir) throws Exception {
        // under-level: モブのほうが item-threshold 以上に格上のとき。
        MobOverridesConfig config = boneDropConfig(dir);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver,
                adjuster(1, new MobLevelCutoff(null, null, null, 20)), new SplittableRandom(0));

        Zombie zombie = leveledZombie(50);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertTrue(event.getDrops().isEmpty(), "under-level item-threshold must block every TF-added drop");
    }

    // --- ドロップ増加ステ mob_drop_bonus (2026-08-09 新規: 以前はTF追加ドロップに一切載っていなかった) ---

    @Test
    void mobDropBonusScalesTfAddedDropCount(@TempDir File dir) throws Exception {
        // 倍率 = 1 + bonus。1.0 のボーナスなら 1個 → 2個。移設前は NativeSurvivalPerkListener が
        // event.getDrops() の中身にしか掛けておらず、TF追加ドロップは常に1個のままだった。
        MobOverridesConfig config = boneDropConfig(dir);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver,
                adjuster(0, MobLevelCutoff.NONE, 1.0), new SplittableRandom(0));

        Zombie zombie = leveledZombie(0);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(1, event.getDrops().size(), "個数が増えるのであってスタックが増えるのではない");
        assertEquals(2, event.getDrops().get(0).getAmount(), "mob_drop_bonus 1.0 は TF追加ドロップを2倍にする");
    }

    @Test
    void mobDropBonusIsCappedAtTripleTheBaseAmount(@TempDir File dir) throws Exception {
        // 上限3倍(MobDropRoller.MAX_BONUS_FACTOR)。10.0 を積んでも 11倍にはならない。
        MobOverridesConfig config = boneDropConfig(dir);
        CrossPluginItemResolver resolver = mock(CrossPluginItemResolver.class);
        MobOverrideDropListener listener = new MobOverrideDropListener(config, resolver,
                adjuster(0, MobLevelCutoff.NONE, 10.0), new SplittableRandom(0));

        Zombie zombie = leveledZombie(0);
        EntityDeathEvent event = deathEventFor(zombie, "goblin_chief");
        listener.onDeath(event);

        assertEquals(3, event.getDrops().get(0).getAmount(), "倍率は3倍で頭打ちになる");
    }
}
