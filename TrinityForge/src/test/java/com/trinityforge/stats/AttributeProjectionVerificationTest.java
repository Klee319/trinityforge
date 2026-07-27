package com.trinityforge.stats;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents TF-managed vanilla attribute keys and verifies double-count prevention rule
 * ({@link AttributeApplier#missingDefaults} skips TF-managed attributes).
 */
class AttributeProjectionVerificationTest {

    @Test
    void defaultsMapsSevenStatKeysToVanillaAttributes() {
        AttributeProjection projection = AttributeProjection.defaults();
        // 2026-07-25: attack-speed-bonus(割合・MULTIPLY_SCALAR_1)新設により6->7へ増加。
        // 2026-07-25 採掘効率: 属性ベース(mining-efficiency/mining-speed-bonus)は統合版/Geyserで
        // 正しく動かないため取り下げ、エンチャント連動方式(GatheringEfficiencyEnchantApplier)へ置換した。
        // 7へ戻る。
        assertEquals(7, projection.entries().size());
        assertTrue(projection.entries().containsKey("knockback_resistance"));
        assertTrue(projection.entries().containsKey("armor_defense_rate"));
        assertTrue(projection.entries().containsKey("max_health"));
        assertTrue(projection.entries().containsKey("move_speed"));
        assertTrue(projection.entries().containsKey("attack_speed"));
        assertTrue(projection.entries().containsKey("attack_speed_bonus"));
        assertTrue(projection.entries().containsKey("attack_reach"));
        // attack-power intentionally absent — combat pipeline only
        assertTrue(projection.project(Map.of("attack_power", 10.0)).isEmpty());
        // armor-strength(防具強度) は会心軽減率%へ役割変更したため vanilla toughness へは写像しない
        // (DefenseStatBridge が直接読む)。二重計上防止のため projection には載らない。
        assertTrue(projection.project(Map.of("armor_strength", 0.3)).isEmpty());
    }

    @Test
    void attackPowerDoesNotProjectToVanillaAttackDamage() {
        var specs = AttributeProjection.defaults().project(Map.of("attack_power", 15.0));
        assertTrue(specs.isEmpty(), "attack-power must not map to vanilla attack_damage (no double count)");
    }

    /**
     * 2026-07-25: {@code attack-speed} の絶対値化({@code written - baseline(4.0)})は
     * {@link AttributeApplier}(Bukkit層、メインハンド限定)の責務であって、この Bukkit-free な
     * projection 層は書かれた値をそのまま(スケール1.0なので変化なく)通すだけでなければならない —
     * 値を先取りして変換してはいけない(projectionはスロットを知らないので、絶対値化してよいかどうか
     * 判断できない)。
     */
    @Test
    void ordinaryNegativeAttackSpeedValuePassesThroughProjectionUnchanged() {
        var specs = AttributeProjection.defaults().project(Map.of("attack-speed", -0.16));
        assertEquals(1, specs.size());
        assertEquals(-0.16, specs.get(0).amount(), 1e-12);
    }

    @Test
    void absoluteAttackSpeedValuePassesThroughProjectionUnchanged() {
        // attack-speed: 4.0 (絶対値としては「バニラ基礎のまま」を意味する) も projection 層では変換せず
        // そのまま4.0として通す。0.0への変換(4.0-baseline)はAttributeApplier側の責務。
        var specs = AttributeProjection.defaults().project(Map.of("attack-speed", 4.0));
        assertEquals(1, specs.size());
        assertEquals(4.0, specs.get(0).amount(), 1e-12);
    }

    /**
     * 2026-07-25新設: attack-speed-bonus は必ず {@code MULTIPLY_SCALAR_1} で写像される(ADD_SCALARでは
     * ない)。ADD_SCALARだと base(4.0)だけに掛かってしまい、attack-speedで積み増した実効値を無視するため
     * (AttributeProjection.defaults()のコメント参照)。
     */
    @Test
    void attackSpeedBonusProjectsToMultiplyScalar1NotAddScalar() {
        var specs = AttributeProjection.defaults().project(Map.of("attack-speed-bonus", 0.10));
        assertEquals(1, specs.size());
        assertEquals("attack_speed", specs.get(0).attributeKey());
        assertEquals(AttributeProjection.AttributeOperation.MULTIPLY_SCALAR_1, specs.get(0).operation());
        assertEquals(0.10, specs.get(0).amount(), 1e-12);
    }
}
