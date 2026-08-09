package com.trinityforge.listeners;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.CombatLevelSource;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.WolfMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * M-2 の {@code EntityTameEvent}/{@code EntitiesLoadEvent} 経路を MockBukkit で実際に発火させて
 * 検証する。<b>このクラスの目的の半分は「MockBukkitがTameable/EntityTameEventをどこまで
 * 実装しているかの確認」自体</b>（作業指示より）。もし {@code UnimplementedOperationException}
 * (=TestAbortedExceptionのサブクラス)でSKIPPEDへ化ける場合は、その旨を報告に書くこと。
 */
class MobTypeSpawnListenerTamedIntegrationTest {

    private ServerMock server;
    private Plugin tfPlugin;
    private MobTypesConfig mobTypesConfig;
    private ConfigManager configManager;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        tfPlugin = MockBukkit.createMockPlugin("TrinityForge");
        mobTypesConfig = mock(MobTypesConfig.class);
        configManager = mock(ConfigManager.class);
        world = server.addSimpleWorld("tamed_test_world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MobTypeSpawnListener newListener(CombatLevelSource combatLevelSource) {
        return new MobTypeSpawnListener(tfPlugin, mobTypesConfig, configManager,
                com.trinityforge.progression.SkillLevelSource.EMPTY, combatLevelSource);
    }

    private WolfMock spawnUntamedWolf() {
        WolfMock wolf = new WolfMock(server, UUID.randomUUID());
        wolf.setLocation(new Location(world, 0, 64, 0));
        return wolf;
    }

    @Test
    @DisplayName("EntityTameEventで飼い主の総合戦闘レベルがそのままモブのレベルになる(満タンスポーン)")
    void tameStampsOwnersCombatLevel() {
        when(mobTypesConfig.tamedLevelPolicy())
                .thenReturn(new MobTypesConfig.TamedLevelPolicy(true, 1.0, 1, 0));
        when(mobTypesConfig.maxLevel()).thenReturn(100);
        MobTypeDefinition def = new MobTypeDefinition(EntityType.WOLF, 0, 0.0, 40.0,
                DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(0),
                MobLevelCoefficients.ZERO, List.of());
        when(mobTypesConfig.definition(EntityType.WOLF)).thenReturn(Optional.of(def));

        PlayerMock owner = server.addPlayer();
        CombatLevelSource combatLevelSource = id -> id.equals(owner.getUniqueId()) ? 20 : 0;
        MobTypeSpawnListener listener = newListener(combatLevelSource);

        WolfMock wolf = spawnUntamedWolf();
        wolf.setOwner(owner);
        wolf.setTamed(true);

        listener.onTame(new EntityTameEvent(wolf, owner));

        assertEquals(21, MobData.of(wolf).level(), "base-level(1) + 総合戦闘レベル(20) = 21");
        AttributeInstance attr = wolf.getAttribute(Attribute.MAX_HEALTH);
        assertEquals(40.0, attr.getBaseValue(), 1.0e-9);
        assertEquals(40.0, wolf.getHealth(), 1.0e-9, "テイム直後は満タン想定");
    }

    @Test
    @DisplayName("EliteMobs所有(MOB_PROFILE_ID)のモブはEntityTameEventで一切触らない")
    void eliteMobsOwnedTameIsUntouched() {
        when(mobTypesConfig.tamedLevelPolicy())
                .thenReturn(new MobTypesConfig.TamedLevelPolicy(true, 1.0, 1, 0));

        PlayerMock owner = server.addPlayer();
        CombatLevelSource combatLevelSource = id -> 50;
        MobTypeSpawnListener listener = newListener(combatLevelSource);

        WolfMock wolf = spawnUntamedWolf();
        wolf.getPersistentDataContainer().set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, "em_boss");
        wolf.setOwner(owner);
        wolf.setTamed(true);

        listener.onTame(new EntityTameEvent(wolf, owner));

        assertEquals(0, MobData.of(wolf).level(), "EliteMobs所有マーカーがある個体はM-2の対象外");
    }

    @Test
    @DisplayName("EntitiesLoadEventで、既に手懐け済みのモブへ飼い主の現在の総合戦闘レベルを再適用する"
            + "(現在HP比率を維持)")
    void entitiesLoadReappliesLevelPreservingHealthRatio() {
        when(mobTypesConfig.tamedLevelPolicy())
                .thenReturn(new MobTypesConfig.TamedLevelPolicy(true, 1.0, 1, 0));
        when(mobTypesConfig.maxLevel()).thenReturn(100);
        MobTypeDefinition def = new MobTypeDefinition(EntityType.WOLF, 0, 0.0, 40.0,
                DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(0),
                MobLevelCoefficients.ZERO, List.of());
        when(mobTypesConfig.definition(EntityType.WOLF)).thenReturn(Optional.of(def));

        PlayerMock owner = server.addPlayer();
        // テイム時点では飼い主の総合戦闘レベルは0(base-levelのみ = Lv1)。
        int[] ownerLevel = {0};
        CombatLevelSource combatLevelSource = id -> id.equals(owner.getUniqueId()) ? ownerLevel[0] : 0;
        MobTypeSpawnListener listener = newListener(combatLevelSource);

        WolfMock wolf = spawnUntamedWolf();
        wolf.setOwner(owner);
        wolf.setTamed(true);
        listener.onTame(new EntityTameEvent(wolf, owner));
        assertEquals(1, MobData.of(wolf).level());

        // 半分削られた状態でチャンクが再読み込みされる状況を模擬する。
        wolf.setHealth(20.0); // maxHealth=40.0の半分

        // 飼い主がレベルアップした後にチャンクへ再読み込みされる。
        ownerLevel[0] = 30;
        listener.onEntitiesLoad(new EntitiesLoadEvent(wolf.getLocation().getChunk(), List.of(wolf)));

        assertEquals(31, MobData.of(wolf).level(), "base-level(1) + 新しい総合戦闘レベル(30) = 31へ追随すること");
        assertEquals(20.0, wolf.getHealth(), 1.0e-9,
                "現在HPの比率(50%)を維持すること(渡し忘れると全快/即死する既知の事故)");
    }

    @Test
    @DisplayName("EntitiesLoadEventは未テイムの個体には一切触らない")
    void entitiesLoadSkipsUntamedMobs() {
        when(mobTypesConfig.tamedLevelPolicy())
                .thenReturn(new MobTypesConfig.TamedLevelPolicy(true, 1.0, 1, 0));
        CombatLevelSource combatLevelSource = id -> 50;
        MobTypeSpawnListener listener = newListener(combatLevelSource);

        WolfMock wolf = spawnUntamedWolf(); // isTamed() == false のまま

        listener.onEntitiesLoad(new EntitiesLoadEvent(wolf.getLocation().getChunk(), List.of(wolf)));

        assertEquals(0, MobData.of(wolf).level(), "未テイムの個体はM-2の対象外");
    }
}
