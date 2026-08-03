package com.trinityforge.listeners;

import com.trinityforge.combat.AttackStats;
import com.trinityforge.combat.DefenseStats;
import com.trinityforge.config.ConfigManager;
import com.trinityforge.config.domains.MobTypesConfig;
import com.trinityforge.mobs.MobLevelCoefficients;
import com.trinityforge.mobs.MobStatScaling;
import com.trinityforge.mobs.MobTypeDefinition;
import com.trinityforge.pdc.MobData;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.CreatureSpawnEvent;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 要件#63の残り(2026-08-03)の回帰テスト: フィールドモブの攻撃力に「Lv45 以降だけ効く加算専用の
 * 第2区間」が実際に<b>刻印まで届く</b>ことを固定する。
 *
 * <p>なぜ挙動テストなのか: この修正は yml へキーを足しただけでは何も起きない。
 * {@code MobTypesConfig} が読み、{@code MobTypeSpawnListener#applyScaledProfile} が
 * {@code MobStatScaling.scaleAttack} の結果へ足し、{@code MobData.stampAttack} まで到達して
 * 初めてプレイヤーへのダメージが変わる。このリポジトリで最も多い事故が
 * 「直したメソッドまで制御が到達していない」なので、PDC に刻まれた実値で確認する。
 *
 * <p>期待値は「本番と同じ {@code MobStatScaling.scaleAttack} の戻り値 ＋ 期待する上乗せ量(リテラル)」で
 * 組み立てる。成長カーブの式をテスト側で再実装しないので、カーブを変えてもこのテストは
 * <b>上乗せ分だけ</b>を見張り続ける。
 */
class MobTypeSpawnListenerAttackHighLevelTest {

    private static final double DELTA = 1.0e-9;

    /** 出荷 mob-types.yml の ZOMBIE 相当(base 5.5 / growth 1.033 / penetration 0.05)。 */
    private static final AttackStats BASE_ATTACK = new AttackStats(5.5, 0, 0, 0, 0, 0.05, 1, 0);

    private static final MobLevelCoefficients COEFFS = new MobLevelCoefficients(
            0.0, 0.0,
            new MobLevelCoefficients.DefenseCoeffs(0, 0, 0, 0),
            new MobLevelCoefficients.DefenseCoeffs(0, 0, 0, 0),
            new MobLevelCoefficients.AttackCoeffs(0, 0, 0, 0.002, 0, 0, 0, 0, 1.033, 1.0));

    /** 出荷値: Lv45 から 1 レベルにつき +0.25(ダンジョン側 combat/mob-import.yml と同値)。 */
    private static final MobTypesConfig.AttackPowerHighLevelPhase SHIPPED_PHASE =
            new MobTypesConfig.AttackPowerHighLevelPhase(45.0, 0.25);

    private ServerMock server;
    private MobTypesConfig mobTypesConfig;
    private MobTypeSpawnListener listener;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Plugin tfPlugin = MockBukkit.createMockPlugin("TrinityForge");
        mobTypesConfig = mock(MobTypesConfig.class);
        listener = new MobTypeSpawnListener(tfPlugin, mobTypesConfig, mock(ConfigManager.class));

        world = server.addSimpleWorld("overworld_test");
        world.setEnvironment(World.Environment.NORMAL);

        // 次元の下駄は無効(このテストが見たいのは攻撃力の高レベル区間だけ)。
        when(mobTypesConfig.maxLevel()).thenReturn(100);
        when(mobTypesConfig.dimensionBaseLevel(World.Environment.NORMAL)).thenReturn(0);
        when(mobTypesConfig.dimensionCoordinateCoefficient(World.Environment.NORMAL))
                .thenReturn(OptionalDouble.empty());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * 実効レベルが引数どおりになるよう coordinate-coefficient を 0 にした定義を刻ませ、
     * PDC に刻まれた攻撃力を返す。
     */
    private double stampedAttackPowerAt(int level, AttackStats base, MobLevelCoefficients coeffs,
                                        MobTypesConfig.AttackPowerHighLevelPhase phase) {
        MobTypeDefinition def = new MobTypeDefinition(EntityType.ZOMBIE, level, 0.0, null,
                DefenseStats.NONE, DefenseStats.NONE, base, coeffs, List.of());
        when(mobTypesConfig.definition(EntityType.ZOMBIE)).thenReturn(Optional.of(def));
        when(mobTypesConfig.attackPowerHighLevel(EntityType.ZOMBIE)).thenReturn(phase);

        ZombieMock zombie = new ZombieMock(server, UUID.randomUUID());
        zombie.setLocation(new Location(world, 0, 64, 0));
        listener.onSpawn(new CreatureSpawnEvent(zombie, CreatureSpawnEvent.SpawnReason.NATURAL));

        MobData data = MobData.of(zombie);
        assertTrue(data.hasAttackProfile(),
                "攻撃ステが刻印されていない。MobData.stampAttack まで制御が届いていない");
        assertEquals(level, data.level(), "実効レベルがテストの想定と違う(距離や次元下駄が混ざっている)");
        return data.attackStats().defaultDamage();
    }

    /** 上乗せ無しの素の値(本番と同じ関数で計算する。式をテスト側で再実装しない)。 */
    private static double baselineAt(int level, AttackStats base, MobLevelCoefficients coeffs) {
        return MobStatScaling.scaleAttack(base, coeffs.attack(), level).defaultDamage();
    }

    @Test
    @DisplayName("Lv44(開始レベル未満)では攻撃力が 1 ミリも変わらない(既存バランスの後方互換)")
    void belowTheBreakpointNothingIsAdded() {
        double stamped = stampedAttackPowerAt(44, BASE_ATTACK, COEFFS, SHIPPED_PHASE);
        assertEquals(baselineAt(44, BASE_ATTACK, COEFFS), stamped, DELTA,
                "Lv45 未満に上乗せが乗っている。低レベル帯のバランスが黙って変わる");
    }

    @Test
    @DisplayName("Lv45(開始レベルちょうど)でも 0 を足す(境界で被弾が不連続に跳ねない)")
    void atTheBreakpointNothingIsAdded() {
        double stamped = stampedAttackPowerAt(45, BASE_ATTACK, COEFFS, SHIPPED_PHASE);
        assertEquals(baselineAt(45, BASE_ATTACK, COEFFS), stamped, DELTA,
                "開始レベルちょうどで段差ができている");
    }

    @Test
    @DisplayName("Lv60 で攻撃力に +3.75 が乗る(乗らなければ高レベル帯が「硬いだけで痛くない」に戻る)")
    void aboveTheBreakpointAddsTheConfiguredSlopeAtLevelSixty() {
        double baseline = baselineAt(60, BASE_ATTACK, COEFFS);
        double stamped = stampedAttackPowerAt(60, BASE_ATTACK, COEFFS, SHIPPED_PHASE);

        assertEquals(baseline + 0.25 * (60 - 45), stamped, DELTA,
                "Lv60 の上乗せ(0.25 × 15 = 3.75)が刻印されていない。yml にキーを足しても "
                        + "MobTypeSpawnListener#applyScaledProfile で加算していなければここで落ちる");
        assertTrue(stamped > baseline, "上乗せが実際に攻撃力を増やしていること");
    }

    @Test
    @DisplayName("Lv80 で +8.75 / Lv100 で +13.75 が乗る(高レベルほど上乗せが積み上がる)")
    void theSlopeAccumulatesWithLevel() {
        assertEquals(baselineAt(80, BASE_ATTACK, COEFFS) + 8.75,
                stampedAttackPowerAt(80, BASE_ATTACK, COEFFS, SHIPPED_PHASE), DELTA,
                "Lv80 の上乗せ(0.25 × 35)が違う");
        assertEquals(baselineAt(100, BASE_ATTACK, COEFFS) + 13.75,
                stampedAttackPowerAt(100, BASE_ATTACK, COEFFS, SHIPPED_PHASE), DELTA,
                "Lv100 の上乗せ(0.25 × 55)が違う");
    }

    @Test
    @DisplayName("高レベル区間を未設定(NONE)にすると Lv100 でも一切上乗せされない(後方互換)")
    void unsetPhaseKeepsTheHistoricalCurveExactly() {
        double stamped = stampedAttackPowerAt(100, BASE_ATTACK, COEFFS,
                MobTypesConfig.AttackPowerHighLevelPhase.NONE);
        assertEquals(baselineAt(100, BASE_ATTACK, COEFFS), stamped, DELTA,
                "高レベル区間を書いていない既存 config のモブの攻撃力が変わっている");
    }

    @Test
    @DisplayName("attack が全部 0 でも高レベル区間だけ設定すれば攻撃ステが刻印される"
            + "(刻印条件に入れ忘れると「yml に書いたのに何も起きない」死んだ設定になる)")
    void highLevelPhaseAloneStillStampsAnAttackProfile() {
        double stamped = stampedAttackPowerAt(60, AttackStats.plain(0),
                new MobLevelCoefficients(0.0, 0.0,
                        new MobLevelCoefficients.DefenseCoeffs(0, 0, 0, 0),
                        new MobLevelCoefficients.DefenseCoeffs(0, 0, 0, 0),
                        new MobLevelCoefficients.AttackCoeffs(0, 0, 0, 0, 0, 0, 0, 0)),
                SHIPPED_PHASE);
        assertEquals(3.75, stamped, DELTA,
                "base も係数も 0 で高レベル区間だけ設定した場合、Lv60 の攻撃力は上乗せ分の 3.75 になること");
    }
}
