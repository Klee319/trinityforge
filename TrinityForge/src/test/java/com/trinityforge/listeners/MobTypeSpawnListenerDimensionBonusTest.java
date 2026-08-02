package com.trinityforge.listeners;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobProfile;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指摘4(2026-08-02)の回帰テスト: {@code dimensions.<ENV>.base-level} が EliteMobs 所有モブ
 * ({@link MobData#profileId()}/{@link MobData#dungeonTheme()} が付いたモブ、取り込んだ EM モブの
 * ほぼ全部がこれに該当する)に一切乗らなかったバグ。{@link MobTypeSpawnListener#onSpawn} の2つの
 * early-return 経路（{@code mob-types} にEntityType定義がある場合／無い場合の両方）が
 * {@link MobTypeSpawnListener#applyScaledProfile} を一切通らないため、EM モブの base level に
 * ディメンションの下駄が乗らなかった。
 *
 * <p>修正後は「EM が既に決めた MOB_LEVEL に下駄を加算するだけ」で、EM 自身が刻んだ防御/攻撃の値は
 * 一切書き換えないことも合わせて固定する。
 */
class MobTypeSpawnListenerDimensionBonusTest {

    private ServerMock server;
    private Plugin tfPlugin;
    private MobTypesConfig mobTypesConfig;
    private ConfigManager configManager;
    private MobTypeSpawnListener listener;
    private WorldMock netherWorld;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        tfPlugin = MockBukkit.createMockPlugin("TrinityForge");
        mobTypesConfig = mock(MobTypesConfig.class);
        configManager = mock(ConfigManager.class);
        listener = new MobTypeSpawnListener(tfPlugin, mobTypesConfig, configManager);

        netherWorld = server.addSimpleWorld("nether_test_world");
        netherWorld.setEnvironment(World.Environment.NETHER);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ZombieMock spawnEliteOwnedZombie(int stampedLevel) {
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.setLocation(new org.bukkit.Location(netherWorld, 0, 64, 0));
        // EliteMobs所有マーカー(MOB_PROFILE_ID)とEM自身が既に決めたMOB_LEVELを模擬する。
        zombie.getPersistentDataContainer().set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, "test_boss");
        zombie.getPersistentDataContainer().set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, stampedLevel);
        return zombie;
    }

    @Test
    void dimensionBaseLevelIsAddedToAnEliteOwnedMobWithNoMobTypesEntry() {
        // ZOMBIEはmob-types.ymlにエントリを持たない(definition()が空) -> data.hasProfile()経由の
        // early-return。ここに指摘4のバグがあった。
        when(mobTypesConfig.definition(EntityType.ZOMBIE)).thenReturn(Optional.empty());
        when(mobTypesConfig.dimensionBaseLevel(World.Environment.NETHER)).thenReturn(20);

        ZombieMock zombie = spawnEliteOwnedZombie(10);
        listener.onSpawn(new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL));

        assertEquals(30, MobData.of(zombie).level(),
                "EliteMobsが既に刻んだレベル(10) + dimensions.NETHER.base-level(20) = 30が乗ること");
    }

    @Test
    void dimensionBaseLevelIsAddedWithoutClobberingEliteOwnedDefenseWhenMobTypesEntryExists() {
        // ZOMBIEがmob-types.ymlにエントリを持つケース。isEliteMobsOwned()の早期returnがここにもあり、
        // かつ「EMが刻んだ防御/攻撃を上書きしない」ことも同時に固定する(下駄以外は一切触らない)。
        DefenseStats mobTypesPhysical = new DefenseStats(0.9, 0.9, 0.9, 99.0, 0.9);
        MobTypeDefinition def = new MobTypeDefinition(EntityType.ZOMBIE, 5, 0.0, null,
                mobTypesPhysical, DefenseStats.NONE, AttackStats.plain(0),
                MobLevelCoefficients.ZERO, List.of());
        when(mobTypesConfig.definition(EntityType.ZOMBIE)).thenReturn(Optional.of(def));
        when(mobTypesConfig.dimensionBaseLevel(World.Environment.NETHER)).thenReturn(20);

        ZombieMock zombie = spawnEliteOwnedZombie(10);
        // EliteMobs自身が既に刻んだ(mob-types.ymlの値とは全く違う)防御値。
        DefenseStats eliteOwnedPhysical = new DefenseStats(0.2, 0.1, 0.0, 3.0, 0.05);
        MobData.stamp(zombie, 10, eliteOwnedPhysical, DefenseStats.NONE);
        // MobData.stamp() が MOB_PROFILE_ID まで消すことはない(別キー)ので所有マーカーは残っている。

        listener.onSpawn(new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL));

        MobData after = MobData.of(zombie);
        assertEquals(30, after.level(), "mob-typesにEntityType定義があってもEM所有なら下駄だけが乗ること");
        assertEquals(eliteOwnedPhysical, after.defenseFor(com.trinityforge.combat.DamageType.PHYSICAL),
                "EliteMobsが刻んだ防御値はmob-types.ymlの値で上書きされないこと");
    }

    @Test
    void zeroDimensionBonusLeavesEliteOwnedLevelUntouched() {
        // 下駄が0(未設定)のときは従来どおり完全に無干渉であること。
        when(mobTypesConfig.definition(EntityType.ZOMBIE)).thenReturn(Optional.empty());
        when(mobTypesConfig.dimensionBaseLevel(World.Environment.NETHER)).thenReturn(0);

        ZombieMock zombie = spawnEliteOwnedZombie(10);
        listener.onSpawn(new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL));

        assertEquals(10, MobData.of(zombie).level(), "下駄0なら従来どおりEMの値のまま");
    }

    /**
     * 2026-08-03(棚卸し指摘の回帰テスト): 下駄が MOB_LEVEL(攻撃力側)だけでなく、
     * {@link ConfigManager#resolveRuntimeProfile} 経由の再解決で MAX_HEALTH 属性にも反映されること。
     */
    @Test
    void dimensionBaseLevelAlsoReappliesMaxHealthViaConfigManager() {
        when(mobTypesConfig.definition(EntityType.ZOMBIE)).thenReturn(Optional.empty());
        when(mobTypesConfig.dimensionBaseLevel(World.Environment.NETHER)).thenReturn(20);

        MobProfile boosted = new MobProfile("test_boss", 30, null,
                DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(0), 500.0);
        when(configManager.resolveRuntimeProfile("test_boss", 30, 0L, "nether_test_world"))
                .thenReturn(Optional.of(boosted));

        ZombieMock zombie = spawnEliteOwnedZombie(10);
        listener.onSpawn(new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL));

        AttributeInstance attr = zombie.getAttribute(Attribute.MAX_HEALTH);
        assertEquals(500.0, attr.getBaseValue(), 1.0e-9,
                "下駄込みレベルで再解決したmaxHealthがMAX_HEALTH属性に反映されること");
        assertEquals(500.0, zombie.getHealth(), 1.0e-9, "満タンスポーンなので現在HPも新しい上限まで入ること");
    }

    /** profileId が無い(dungeonThemeだけの)個体は解決キーが無いため、HP再解決は何もしない(安全側)。 */
    @Test
    void noProfileIdSkipsMaxHealthReapplyButStillAdjustsLevel() {
        when(mobTypesConfig.definition(EntityType.ZOMBIE)).thenReturn(Optional.empty());
        when(mobTypesConfig.dimensionBaseLevel(World.Environment.NETHER)).thenReturn(20);

        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.setLocation(new org.bukkit.Location(netherWorld, 0, 64, 0));
        zombie.getPersistentDataContainer().set(PdcKeys.MOB_DUNGEON_THEME, PersistentDataType.STRING, "test_theme");
        zombie.getPersistentDataContainer().set(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER, 10);
        double baseHealthBefore = zombie.getAttribute(Attribute.MAX_HEALTH).getBaseValue();

        listener.onSpawn(new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL));

        assertEquals(30, MobData.of(zombie).level(), "profileId不在でもレベルの下駄自体は乗ること");
        assertEquals(baseHealthBefore, zombie.getAttribute(Attribute.MAX_HEALTH).getBaseValue(), 1.0e-9,
                "profileId不在ならMAX_HEALTHは一切触らないこと");
    }
}
