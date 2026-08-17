package com.trinityforge.listeners;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import com.trinityforge.progression.CombatLevelSource;
import com.trinityforge.progression.SkillLevelSource;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W-61（2026-08-18 ユーザー報告「データパックで生成された構造物のモブが Lv0 になる」）の回帰。
 *
 * <p><b>真因</b>: 構造物テンプレートの NBT に焼かれているモブは<b>チャンクが生成された時点で
 * 既に存在する</b>ので、{@code CreatureSpawnEvent} が一度も発火しない
 * （Bukkit の {@code SpawnReason.CHUNK_GEN} 自体が「もう呼ばれない」として非推奨になっている）。
 * その結果 {@link MobTypeSpawnListener#onSpawn} がそのモブに一切触れず、{@code MOB_LEVEL} PDC が
 * 付かないまま {@link MobData#level()} が既定の 0 を返していた。データパック固有の問題ではなく、
 * <b>バニラの前哨基地・海底神殿など構造物に最初から置かれているモブ全般</b>に効く。
 *
 * <p>したがってここで固定するのは「スポーンイベント以外の入口からでもレベルが刻まれること」と
 * 「その入口が何度呼ばれても刻み直さないこと」の2点。後者を落とすと、チャンクを読むたびに
 * ステータスが再計算されて HP が張り直される事故になる。
 */
class MobTypeSpawnListenerStructureBackfillTest {

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
        world = server.addSimpleWorld("structure_backfill_world");
        // 距離カーブが効く帯（ワールドスポーンから 2,000 ブロック）に置くので、
        // 「刻まれていない 0」と「計算して 0 になった」を取り違えずに済む。
        when(mobTypesConfig.maxLevel()).thenReturn(100);
        when(mobTypesConfig.dimensionBaseLevel(World.Environment.NORMAL)).thenReturn(0);
        when(mobTypesConfig.dimensionCoordinateCoefficient(World.Environment.NORMAL))
                .thenReturn(OptionalDouble.empty());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private MobTypeSpawnListener newListener() {
        return new MobTypeSpawnListener(tfPlugin, mobTypesConfig, configManager,
                SkillLevelSource.EMPTY, CombatLevelSource.EMPTY);
    }

    /** ワールドスポーンから 2,000 ブロック離れた地点に置いたゾンビ（構造物内の個体を模す）。 */
    private ZombieMock structureZombieFarFromSpawn() {
        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.setLocation(new Location(world, 2000, 64, 0));
        return zombie;
    }

    private void stubZombieDefinition() {
        // coordinate-coefficient 0.02 → 2,000 ブロックで +40 レベル。
        MobTypeDefinition def = new MobTypeDefinition(EntityType.ZOMBIE, 0, 0.02, 20.0,
                DefenseStats.NONE, DefenseStats.NONE, AttackStats.plain(0),
                MobLevelCoefficients.ZERO, List.of());
        when(mobTypesConfig.definition(EntityType.ZOMBIE)).thenReturn(Optional.of(def));
    }

    @Test
    @DisplayName("CreatureSpawnEvent を通っていないモブは MOB_LEVEL PDC を持たない（不具合の再現）")
    void aMobThatNeverFiredCreatureSpawnEventCarriesNoLevelKey() {
        stubZombieDefinition();
        ZombieMock zombie = structureZombieFarFromSpawn();

        assertFalse(zombie.getPersistentDataContainer()
                        .has(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER),
                "前提が崩れている: 何も通していない個体に既にレベルキーがある");
        assertEquals(0, MobData.of(zombie).level(),
                "キーが無いときの level() は既定の 0 を返す = 画面上は Lv0 に見える");
    }

    @Test
    @DisplayName("EntityAddToWorldEvent 経由でも距離ぶんのレベルが刻まれる（W-61 の修正点）")
    void addToWorldBackfillsTheLevelForStructurePlacedMobs() {
        stubZombieDefinition();
        MobTypeSpawnListener listener = newListener();
        ZombieMock zombie = structureZombieFarFromSpawn();

        listener.onEntityAddToWorld(new EntityAddToWorldEvent(zombie, world));

        assertTrue(zombie.getPersistentDataContainer()
                        .has(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER),
                "スポーンイベント以外の入口からレベルが刻まれない = W-61 が再発している");
        assertEquals(40, MobData.of(zombie).level(),
                "2,000 ブロック × coordinate-coefficient 0.02 = Lv40。0 のままなら受け皿が効いていない");
    }

    @Test
    @DisplayName("2回目以降の EntityAddToWorldEvent は刻印済みを見て何もしない（HP張り直し事故の防止）")
    void backfillIsIdempotentSoRepeatedChunkLoadsDoNotRestampStats() {
        stubZombieDefinition();
        MobTypeSpawnListener listener = newListener();
        ZombieMock zombie = structureZombieFarFromSpawn();

        listener.onEntityAddToWorld(new EntityAddToWorldEvent(zombie, world));
        int firstLevel = MobData.of(zombie).level();

        // 刻印後にワールドスポーンの近くへ移動させる。もし2回目で刻み直していたら
        // 距離が縮んだぶんレベルが下がるので、その差で「再刻印していないか」を見分けられる。
        zombie.setLocation(new Location(world, 0, 64, 0));
        listener.onEntityAddToWorld(new EntityAddToWorldEvent(zombie, world));

        assertEquals(firstLevel, MobData.of(zombie).level(),
                "チャンク読み込みのたびに刻み直すと、ステが再計算されて HP が張り直される");
    }

    @Test
    @DisplayName("アーマースタンドは受け皿の対象外（CreatureSpawnEvent の母集団に元々入っていない）")
    void armorStandsAreNotStampedByTheBackfill() {
        // EntityAddToWorldEvent は LivingEntity なら何でも飛ぶので、CreatureSpawnEvent より
        // 母集団が広い。絞らないと、ホログラム/装飾/他プラグインの表示用アーマースタンドが
        // defaults(max-health 800 + 防御ステ)を刻まれ、MOB_TYPE_STAMPED まで付いて
        // ドロップ処理の対象に入ってしまう。
        when(mobTypesConfig.definition(EntityType.ARMOR_STAND)).thenReturn(Optional.empty());
        when(mobTypesConfig.defaultMaxHealth()).thenReturn(OptionalDouble.of(800.0));
        MobTypeSpawnListener listener = newListener();

        org.mockbukkit.mockbukkit.entity.ArmorStandMock stand =
                new org.mockbukkit.mockbukkit.entity.ArmorStandMock(server, UUID.randomUUID());
        stand.setLocation(new Location(world, 2000, 64, 0));

        listener.onEntityAddToWorld(new EntityAddToWorldEvent(stand, world));

        assertFalse(stand.getPersistentDataContainer()
                        .has(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER),
                "アーマースタンドに mob-types の defaults が刻まれている");
    }

    @Test
    @DisplayName("EliteMobs 所有（MOB_PROFILE_ID）の個体は受け皿が一切触らない")
    void eliteMobsOwnedEntitiesAreLeftAlone() {
        stubZombieDefinition();
        MobTypeSpawnListener listener = newListener();
        ZombieMock zombie = structureZombieFarFromSpawn();
        zombie.getPersistentDataContainer()
                .set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, "some_em_boss.yml");

        listener.onEntityAddToWorld(new EntityAddToWorldEvent(zombie, world));

        assertFalse(zombie.getPersistentDataContainer()
                        .has(PdcKeys.MOB_LEVEL, PersistentDataType.INTEGER),
                "EM が自分で決めるレベルを受け皿が横取りしてはいけない");
    }
}
