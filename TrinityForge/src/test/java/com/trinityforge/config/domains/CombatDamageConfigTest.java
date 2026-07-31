package com.trinityforge.config.domains;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the config knob {@code magical.scale-with-combat-level}: default {@code true}
 * (魔法もcombatレベルで伸びる, server decision) and {@code false} restores the C2 bypass. Uses the same
 * reflective fake {@link Plugin} pattern as the other config-loader tests (a headless test has no real
 * server), pre-creating the file so {@code saveResource} is never called.
 */
class CombatDamageConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CombatDamageConfigTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }

    private static CombatDamageConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, CombatDamageConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CombatDamageConfig config = new CombatDamageConfig();
        config.domain().load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void magicalScaleWithCombatLevelDefaultsTrue(@TempDir File tempDir) throws IOException {
        // Key absent -> schema default = true = magic grows with combat level (server decision).
        CombatDamageConfig config = loaded(tempDir, "physical:\n  base-coefficient: 1.0\n");
        assertTrue(config.magicalScaleWithCombatLevel());
    }

    @Test
    void magicalScaleWithCombatLevelHonorsExplicitTrue(@TempDir File tempDir) throws IOException {
        CombatDamageConfig config = loaded(tempDir, """
                magical:
                  scale-with-combat-level: true
                """);
        assertTrue(config.magicalScaleWithCombatLevel());
    }

    @Test
    void magicalScaleWithCombatLevelHonorsExplicitFalse(@TempDir File tempDir) throws IOException {
        CombatDamageConfig config = loaded(tempDir, """
                magical:
                  scale-with-combat-level: false
                """);
        assertFalse(config.magicalScaleWithCombatLevel());
    }

    @Test
    void weaponBaseFormulaDefaults(@TempDir File tempDir) throws IOException {
        // Section absent -> schema defaults match the shipped combat/damage.yml: enabled=true, a=2.0, b=100.0.
        CombatDamageConfig config = loaded(tempDir, "physical:\n  base-coefficient: 1.0\n");
        assertTrue(config.weaponBaseFormulaEnabled());
        assertEquals(2.0, config.weaponBaseFormulaA(), 0.0);
        assertEquals(100.0, config.weaponBaseFormulaB(), 0.0);

        WeaponBaseFormula formula = config.weaponBaseFormula();
        assertTrue(formula.enabled());
        assertEquals(5.0, formula.baseAttackPower(20), 1e-9);
    }

    @Test
    void weaponBaseFormulaHonorsExplicitValues(@TempDir File tempDir) throws IOException {
        CombatDamageConfig config = loaded(tempDir, """
                weapon-base-formula:
                  enabled: false
                  a: 1.0
                  b: 50.0
                """);
        assertFalse(config.weaponBaseFormulaEnabled());
        assertEquals(1.0, config.weaponBaseFormulaA(), 0.0);
        assertEquals(50.0, config.weaponBaseFormulaB(), 0.0);
    }

    @Test
    void weaponBaseFormulaBNonPositiveFallsBackToDefault(@TempDir File tempDir) throws IOException {
        // b must be strictly > 0 (0除算回避). A non-positive b is out of range -> schema falls back to the
        // 100.0 default (with a load-time warning), so weaponBaseFormula() never divides by zero.
        CombatDamageConfig config = loaded(tempDir, """
                weapon-base-formula:
                  b: 0.0
                """);
        assertEquals(100.0, config.weaponBaseFormulaB(), 0.0);
        // The record still constructs cleanly with the fallen-back divisor.
        assertEquals(5.0, config.weaponBaseFormula().baseAttackPower(20), 1e-9);
    }

    @Test
    void weaponBaseFormulaNegativeBFallsBackToDefault(@TempDir File tempDir) throws IOException {
        CombatDamageConfig config = loaded(tempDir, """
                weapon-base-formula:
                  b: -100.0
                """);
        assertEquals(100.0, config.weaponBaseFormulaB(), 0.0);
    }

    // オフハンド合算のグローバルトグルは廃止し item-stats の per-item offhand-stats-apply へ移行
    // (検証は PlayerStatAggregatorTest 側)。

    // --- #6 configurable defense clamp (負クランプ) ---

    @Test
    void defenseClampDefaultsReproduceLegacyBounds(@TempDir File tempDir) throws IOException {
        // Section absent -> (0, 1, 0, 1e6): the legacy [0,1] rate / [0,∞) flat behaviour.
        CombatDamageConfig config = loaded(tempDir, "physical:\n  base-coefficient: 1.0\n");
        var clamp = config.defenseClamp();
        assertEquals(0.0, clamp.minRate(), 0.0);
        assertEquals(1.0, clamp.maxRate(), 0.0);
        assertEquals(0.0, clamp.minFlat(), 0.0);
        assertEquals(1_000_000.0, clamp.maxFlat(), 0.0);
    }

    @Test
    void defenseClampHonorsNegativeAndOverOneBounds(@TempDir File tempDir) throws IOException {
        // A PvP/heal config: negative amplification floor + over-mitigation ceiling (max-rate > 1 → heal).
        CombatDamageConfig config = loaded(tempDir, """
                defense:
                  min-rate: -0.5
                  max-rate: 2.0
                  min-flat: -10.0
                  max-flat: 100.0
                """);
        var clamp = config.defenseClamp();
        assertEquals(-0.5, clamp.minRate(), 0.0);
        assertEquals(2.0, clamp.maxRate(), 0.0);
        assertEquals(-10.0, clamp.minFlat(), 0.0);
        assertEquals(100.0, clamp.maxFlat(), 0.0);
    }

    @Test
    void minComponentDamageAcceptsNegativeFloor(@TempDir File tempDir) throws IOException {
        // #6 Part B: a negative min-component-damage lets final damage go negative (=heal).
        CombatDamageConfig config = loaded(tempDir, """
                physical:
                  min-component-damage: -50000.0
                magical:
                  min-component-damage: -75000.0
                """);
        assertEquals(-50000.0, config.minComponentDamage(), 0.0);
        assertEquals(-75000.0, config.magicalMinComponentDamage(), 0.0);
    }

    // --- #2 AoE hit-players toggle ---

    @Test
    void aoeHitPlayersDisabledByDefault(@TempDir File tempDir) throws IOException {
        CombatDamageConfig config = loaded(tempDir, "physical:\n  base-coefficient: 1.0\n");
        assertFalse(config.aoeHitPlayers());
    }

    @Test
    void aoeHitPlayersHonorsExplicitTrue(@TempDir File tempDir) throws IOException {
        CombatDamageConfig config = loaded(tempDir, """
                aoe:
                  hit-players: true
                """);
        assertTrue(config.aoeHitPlayers());
    }

    // --- #3 max-dodge-chance (B3 dodge sibling) ---

    @Test
    void maxDodgeChanceDefaultsToPointNine(@TempDir File tempDir) throws IOException {
        CombatDamageConfig config = loaded(tempDir, "physical:\n  base-coefficient: 1.0\n");
        assertEquals(0.9, config.maxDodgeChance(), 0.0);
    }

    @Test
    void maxDodgeChanceHonorsExplicitValue(@TempDir File tempDir) throws IOException {
        CombatDamageConfig config = loaded(tempDir, """
                defense:
                  max-dodge-chance: 0.5
                """);
        assertEquals(0.5, config.maxDodgeChance(), 0.0);
    }

    // --- 2026-07-31 D6: magical.attack-power-scale (杖の攻撃力を魔法へ加算する係数) ---

    @Test
    void magicalAttackPowerScaleDefaultsToOne(@TempDir File tempDir) throws IOException {
        // キー不在 -> スキーマ既定 1.0 = 仕様どおり100%加算(MAGIC_BALANCE_SPEC / COMBAT_SYSTEM_SPEC)。
        // ここが 0 に化けると「杖の攻撃力が魔法に乗らない」D6 の症状へ静かに戻るので固定する。
        CombatDamageConfig config = loaded(tempDir, "physical:\n  base-coefficient: 1.0\n");
        assertEquals(1.0, config.magicalAttackPowerScale(), 0.0);
    }

    @Test
    void magicalAttackPowerScaleHonorsZero(@TempDir File tempDir) throws IOException {
        // 0 は「この機能を完全にオフにする」正当な設定値なので、既定へ戻さずそのまま採用されること。
        CombatDamageConfig config = loaded(tempDir, """
                magical:
                  attack-power-scale: 0.0
                """);
        assertEquals(0.0, config.magicalAttackPowerScale(), 0.0);
    }

    @Test
    void magicalAttackPowerScaleHonorsFractionalAndAboveOne(@TempDir File tempDir) throws IOException {
        assertEquals(0.25, loaded(tempDir, """
                magical:
                  attack-power-scale: 0.25
                """).magicalAttackPowerScale(), 0.0);
    }

    @Test
    void magicalAttackPowerScaleOutOfRangeFallsBackToDefault(@TempDir File tempDir) throws IOException {
        // スキーマ範囲は [0,10]。範囲外は(警告付きで)既定 1.0 に戻る。
        assertEquals(1.0, loaded(tempDir, """
                magical:
                  attack-power-scale: 10.5
                """).magicalAttackPowerScale(), 0.0);
        assertEquals(1.0, loaded(tempDir, """
                magical:
                  attack-power-scale: -1.0
                """).magicalAttackPowerScale(), 0.0);
    }

    @Test
    void shippedDamageYamlDeclaresMagicalAttackPowerScale() throws IOException {
        // 出荷 yml が真源。スキーマ既定と出荷値が食い違うと「editor で開いて保存しただけで意味が変わる」
        // 事故になるため、出荷ファイルに明示的に 1 が書かれていることを固定する。
        String yaml = Files.readString(
                new File("src/main/resources/" + CombatDamageConfig.PATH).toPath());
        assertTrue(yaml.contains("attack-power-scale: 1"),
                "combat/damage.yml の magical: 節に attack-power-scale: 1 が必要");
    }
}
