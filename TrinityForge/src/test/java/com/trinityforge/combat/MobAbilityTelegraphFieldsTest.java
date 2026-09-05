package com.trinityforge.combat;

import com.trinityforge.config.domains.MobAbilitiesConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MobAbility} に足した3フィールド（{@code cast-seconds} / {@code lethal} /
 * {@code vertical-radius}、2026-09-04 機構2）の丸めとパーサ対応を固定する。
 */
class MobAbilityTelegraphFieldsTest {

    private static final Logger LOG = Logger.getLogger("MobAbilityTelegraphFieldsTest");

    // ------------------------------------------------------------------
    // 旧 arity コンストラクタの既定値
    // ------------------------------------------------------------------

    @Test
    @DisplayName("21引数の旧コンストラクタは castSeconds=0 / lethal=false / verticalRadius=3.0 になる")
    void legacy21ArgConstructorDefaultsToNoTelegraph() {
        MobAbility ability = new MobAbility("t", "", MobAbility.Type.GROUND_SLAM, DamageType.PHYSICAL,
                1.0, 10.0, 0.3, 16.0, 4.0, 1, 45.0, "", "", 0.0,
                0.0, List.of(), "", 0, "", 1.0, 0.0);

        assertEquals(0.0, ability.castSeconds());
        assertFalse(ability.lethal());
        assertEquals(AbilityShapes.DEFAULT_VERTICAL_RADIUS, ability.verticalRadius());
        assertFalse(ability.telegraphed());
    }

    @Test
    @DisplayName("19引数のさらに旧いコンストラクタも同じ既定値になる")
    void legacy19ArgConstructorDefaultsToNoTelegraph() {
        MobAbility ability = new MobAbility("t", "", MobAbility.Type.CHARGE, DamageType.PHYSICAL,
                1.0, 10.0, 0.3, 16.0, 4.0, 1, 45.0, "", "", 0.0,
                0.0, List.of(), "", 0, "");

        assertEquals(0.0, ability.castSeconds());
        assertFalse(ability.lethal());
        assertEquals(AbilityShapes.DEFAULT_VERTICAL_RADIUS, ability.verticalRadius());
    }

    // ------------------------------------------------------------------
    // 丸め
    // ------------------------------------------------------------------

    @Test
    @DisplayName("0 < castSeconds < 0.5 は 0.5 へ切り上げる")
    void shortCastSecondsRoundsUpToHalfSecond() {
        MobAbility ability = ability(0.2);

        assertEquals(0.5, ability.castSeconds());
    }

    @Test
    @DisplayName("castSeconds > 2.5 は 2.5 へ丸める")
    void longCastSecondsClampsToMax() {
        MobAbility ability = ability(3.0);

        assertEquals(2.5, ability.castSeconds());
    }

    @Test
    @DisplayName("castSeconds = 0（未設定）はそのまま0（予告なし）")
    void zeroCastSecondsStaysZero() {
        assertEquals(0.0, ability(0.0).castSeconds());
        assertEquals(0.0, ability(-1.0).castSeconds());
        assertEquals(0.0, ability(Double.NaN).castSeconds());
    }

    @Test
    @DisplayName("verticalRadius は[0.5, 8.0]へ丸め、非有限は既定値3.0へ落ちる")
    void verticalRadiusIsClamped() {
        MobAbility low = abilityWithVerticalRadius(0.1);
        MobAbility high = abilityWithVerticalRadius(50.0);
        MobAbility nan = abilityWithVerticalRadius(Double.NaN);

        assertEquals(0.5, low.verticalRadius());
        assertEquals(8.0, high.verticalRadius());
        assertEquals(AbilityShapes.DEFAULT_VERTICAL_RADIUS, nan.verticalRadius());
    }

    // ------------------------------------------------------------------
    // telegraphTicks / telegraphed
    // ------------------------------------------------------------------

    @Test
    @DisplayName("telegraphTicks はcastSeconds>0ならcastTicksに一致する")
    void telegraphTicksMatchesCastTicksWhenPositive() {
        MobAbility ability = new MobAbility("t", "", MobAbility.Type.BEAM, DamageType.PHYSICAL,
                1.0, 10.0, 0.3, 16.0, 4.0, 1, 45.0, "", "", 0.0,
                0.0, List.of(), "", 0, "", 1.0, 0.0, 1.5, false, 3.0);

        assertEquals(30, ability.castTicks());
        assertEquals(30, ability.telegraphTicks());
        assertTrue(ability.telegraphed());
    }

    @Test
    @DisplayName("delayed_zone は castSeconds=0 でも delayTicks() を予告として読む（後方互換）")
    void delayedZoneFallsBackToDelayTicks() {
        MobAbility ability = new MobAbility("t", "", MobAbility.Type.DELAYED_ZONE, DamageType.PHYSICAL,
                1.0, 10.0, 0.3, 16.0, 4.0, 1, 45.0, "", "", 1.5,
                0.0, List.of(), "", 0, "", 1.0, 0.0, 0.0, false, 3.0);

        assertEquals(0, ability.castTicks());
        assertEquals(ability.delayTicks(), ability.telegraphTicks());
        assertTrue(ability.telegraphed());
    }

    @Test
    @DisplayName("delayed_zone 以外の型で castSeconds=0 なら telegraphTicks=0（予告なし）")
    void nonDelayedZoneWithNoCastIsNotTelegraphed() {
        MobAbility ability = new MobAbility("t", "", MobAbility.Type.GROUND_SLAM, DamageType.PHYSICAL,
                1.0, 10.0, 0.3, 16.0, 4.0, 1, 45.0, "", "", 0.0,
                0.0, List.of(), "", 0, "", 1.0, 0.0, 0.0, false, 3.0);

        assertEquals(0, ability.telegraphTicks());
        assertFalse(ability.telegraphed());
    }

    // ------------------------------------------------------------------
    // パーサ
    // ------------------------------------------------------------------

    @Test
    @DisplayName("パーサが cast-seconds / lethal / vertical-radius を読む")
    void parserReadsTelegraphFields() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                abilities:
                  mark:
                    type: ground_slam
                    cast-seconds: 1.2
                    lethal: true
                    vertical-radius: 5.0
                """);
        MobAbilitiesConfig.ParseResult result =
                MobAbilitiesConfig.parse(cfg.getConfigurationSection("abilities"), LOG);

        assertEquals(0, result.skipped());
        MobAbility ability = result.abilities().get("mark");
        assertEquals(1.2, ability.castSeconds());
        assertTrue(ability.lethal());
        assertEquals(5.0, ability.verticalRadius());
    }

    @Test
    @DisplayName("パーサの既定値は castSeconds=0 / lethal=false / verticalRadius=3.0")
    void parserDefaultsToNoTelegraph() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString("""
                abilities:
                  plain:
                    type: charge
                """);
        MobAbilitiesConfig.ParseResult result =
                MobAbilitiesConfig.parse(cfg.getConfigurationSection("abilities"), LOG);

        MobAbility ability = result.abilities().get("plain");
        assertEquals(0.0, ability.castSeconds());
        assertFalse(ability.lethal());
        assertEquals(AbilityShapes.DEFAULT_VERTICAL_RADIUS, ability.verticalRadius());
    }

    // ------------------------------------------------------------------

    private static MobAbility ability(double castSeconds) {
        return new MobAbility("t", "", MobAbility.Type.BEAM, DamageType.PHYSICAL,
                1.0, 10.0, 0.3, 16.0, 4.0, 1, 45.0, "", "", 0.0,
                0.0, List.of(), "", 0, "", 1.0, 0.0, castSeconds, false, 3.0);
    }

    private static MobAbility abilityWithVerticalRadius(double verticalRadius) {
        return new MobAbility("t", "", MobAbility.Type.GROUND_SLAM, DamageType.PHYSICAL,
                1.0, 10.0, 0.3, 16.0, 4.0, 1, 45.0, "", "", 0.0,
                0.0, List.of(), "", 0, "", 1.0, 0.0, 0.0, false, verticalRadius);
    }
}
