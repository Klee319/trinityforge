package com.trinityforge.mobs;

import com.trinityforge.combat.DefenseStats;
import com.trinityforge.pdc.MobData;
import com.trinityforge.pdc.PdcKeys;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ゾンビ→ドラウンド等の変身でTFのモブステが消える不具合({@link MobTransformCarryOver})の回帰テスト。
 *
 * <p>Paper の {@code Mob#convertTo} は PDC も Attribute も新エンティティへコピーしないので、
 * 何もしないとダンジョンテーマ・スポナー由来・個体シード・防御/攻撃ステが丸ごと失われる。
 */
class MobTransformCarryOverTest {

    private ServerMock server;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        MobTransformCarryOver.clearForTests();
    }

    @AfterEach
    void tearDown() {
        MobTransformCarryOver.clearForTests();
        MockBukkit.unmock();
    }

    private static DefenseStats defense(double rate) {
        return new DefenseStats(rate, rate / 2.0, rate / 4.0, rate * 2.0, rate * 3.0);
    }

    @Test
    void carriesEveryStampedMobKeyToTheTransformedEntity() {
        Zombie from = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned to = world.spawn(world.getSpawnLocation(), Drowned.class);

        MobData.stampMobType(from, 42, defense(0.3), defense(0.5));
        from.getPersistentDataContainer()
                .set(PdcKeys.MOB_DUNGEON_THEME, PersistentDataType.STRING, "ice");
        from.getPersistentDataContainer()
                .set(PdcKeys.MOB_PROFILE_ID, PersistentDataType.STRING, "frost_boss");
        from.getPersistentDataContainer()
                .set(PdcKeys.MOB_ROLL_SEED, PersistentDataType.LONG, 1234567L);
        from.getPersistentDataContainer()
                .set(PdcKeys.MOB_SPAWNER_SPAWNED, PersistentDataType.BYTE, (byte) 1);

        MobTransformCarryOver.copyMobStamps(
                from.getPersistentDataContainer(), to.getPersistentDataContainer());

        MobData carried = MobData.of(to);
        assertEquals(42, carried.level(), "戦闘レベルが引き継がれていない");
        assertTrue(carried.isMobTypeStamped(), "mob-types 由来マーカーが引き継がれていない");
        assertEquals("ice", carried.dungeonTheme().orElse(null), "ダンジョンテーマが引き継がれていない");
        assertEquals("frost_boss", carried.profileId().orElse(null), "プロファイルidが引き継がれていない");
        assertEquals(1234567L, carried.rollSeed().orElse(-1L), "個体ばらつきシードが引き継がれていない");
        assertTrue(to.getPersistentDataContainer().has(PdcKeys.MOB_SPAWNER_SPAWNED, PersistentDataType.BYTE),
                "スポナー由来マーカーが引き継がれていない(水没させるだけでEXP抑制を回避できてしまう)");
        assertEquals(0.3, carried.defenseFor(com.trinityforge.combat.DamageType.PHYSICAL).defenseRate(), 1e-9);
        assertEquals(0.5, carried.defenseFor(com.trinityforge.combat.DamageType.MAGICAL).defenseRate(), 1e-9);
    }

    @Test
    void doesNotOverwriteValuesTheTransformedEntityAlreadyHas() {
        Zombie from = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned to = world.spawn(world.getSpawnLocation(), Drowned.class);
        MobData.stamp(from, 10, defense(0.1), defense(0.1));
        MobData.stamp(to, 99, defense(0.9), defense(0.9));

        MobTransformCarryOver.copyMobStamps(
                from.getPersistentDataContainer(), to.getPersistentDataContainer());

        assertEquals(99, MobData.of(to).level(),
                "変身先が既に持っている値は上書きしない(先に刻んだ側を尊重する)");
    }

    @Test
    void leavesKeysAbsentWhenTheSourceNeverHadThem() {
        Zombie from = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned to = world.spawn(world.getSpawnLocation(), Drowned.class);

        MobTransformCarryOver.copyMobStamps(
                from.getPersistentDataContainer(), to.getPersistentDataContainer());

        assertFalse(MobData.of(to).hasProfile(),
                "素のバニラモブから変身した場合、プロファイル無しのままであること");
        assertTrue(MobData.of(to).dungeonTheme().isEmpty());
    }

    @Test
    void healthRatioIsTheFractionOfMaxAndClampsToUnitRange() {
        assertEquals(0.5, MobTransformCarryOver.healthRatio(250.0, 500.0), 1e-9);
        assertEquals(1.0, MobTransformCarryOver.healthRatio(600.0, 500.0), 1e-9, "1を超えない");
        assertEquals(0.0, MobTransformCarryOver.healthRatio(-3.0, 500.0), 1e-9, "負にならない");
        assertEquals(1.0, MobTransformCarryOver.healthRatio(5.0, 0.0), 1e-9,
                "最大HPが0/不明なら全快扱い(0除算で壊さない)");
        assertEquals(1.0, MobTransformCarryOver.healthRatio(Double.NaN, 500.0), 1e-9);
    }

    @Test
    void carriesMaxHealthBaseAndKeepsTheDamageAlreadyDealt() {
        Zombie from = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned to = world.spawn(world.getSpawnLocation(), Drowned.class);
        from.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(500.0);
        from.setHealth(125.0);

        double ratio = MobTransformCarryOver.healthRatioOf(from);
        MobTransformCarryOver.copyMaxHealth(from, to, ratio);

        assertEquals(500.0,
                to.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue(), 1e-9,
                "最大HPが引き継がれていない(バニラ20へ戻ってしまう)");
        assertEquals(125.0, to.getHealth(), 1e-9,
                "削ったダメージが消えている(水に落とすだけで全回復してしまう)");
    }

    @Test
    void neverLeavesTheTransformedEntityAtZeroHealth() {
        Zombie from = world.spawn(world.getSpawnLocation(), Zombie.class);
        Drowned to = world.spawn(world.getSpawnLocation(), Drowned.class);
        from.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).setBaseValue(500.0);
        from.setHealth(0.4);

        MobTransformCarryOver.copyMaxHealth(from, to, MobTransformCarryOver.healthRatioOf(from));

        assertTrue(to.getHealth() >= 1.0,
                "変身の瞬間に0HPで即死させない(端数切り捨てで死ぬのは変身の副作用として不当)");
    }

    @Test
    void pendingRatioIsConsumedExactlyOnceAndDefaultsToFullHealth() {
        UUID id = UUID.randomUUID();
        assertEquals(1.0, MobTransformCarryOver.consumeHealthRatio(id), 1e-9,
                "未記録(通常スポーン)は満タン=1.0");

        MobTransformCarryOver.rememberHealthRatio(id, 0.25);
        assertEquals(0.25, MobTransformCarryOver.consumeHealthRatio(id), 1e-9);
        assertEquals(1.0, MobTransformCarryOver.consumeHealthRatio(id), 1e-9,
                "2回目は消費済みなので既定値へ戻る");
        assertEquals(0, MobTransformCarryOver.pendingCountForTests(), "消費後に滞留しない");
    }

    @Test
    void pendingMapIsBoundedSoAnUnconsumedEntryCannotLeak() {
        for (int i = 0; i < 2000; i++) {
            MobTransformCarryOver.rememberHealthRatio(UUID.randomUUID(), 0.5);
        }
        assertTrue(MobTransformCarryOver.pendingCountForTests() <= 512,
                "保留マップが無制限に伸びている: " + MobTransformCarryOver.pendingCountForTests());
    }

    @Test
    void carriedKeySetCoversEveryMobStampWritten() {
        // 内訳: level 1 + テーマ/プロファイルid 2 + シード 1 + マーカー2(mob-types由来/スポナー由来)
        // + double 18(防具強度1 回避1 物理4 魔法4 攻撃8)。
        assertEquals(24, MobTransformCarryOver.carriedKeyCount(),
                "引き継ぎ対象キーの数が変わった。PdcKeys に MOB_* を足したら "
                        + "MobTransformCarryOver にも足すこと(足し忘れると変身でそのステだけ静かに消える)");
    }
}
