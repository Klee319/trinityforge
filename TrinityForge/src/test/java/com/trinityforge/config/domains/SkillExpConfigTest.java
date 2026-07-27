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

/**
 * Covers {@code combat.same-target-cooldown-seconds} (武器スキルEXP無限farm fix): defaults to 10 seconds
 * when absent, honors an explicit override, and never goes negative on a misconfigured value. Uses the
 * same reflective fake {@link Plugin} pattern as {@link CombatDamageConfigTest}.
 */
class SkillExpConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("SkillExpConfigTest");
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

    private static SkillExpConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, SkillExpConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        SkillExpConfig config = new SkillExpConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void sameTargetCooldownDefaultsToTenSecondsWhenAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "combat:\n  exp-per-hit: 1.0\n");
        assertEquals(10.0, config.combatSameTargetCooldownSeconds());
    }

    @Test
    void sameTargetCooldownHonorsExplicitValue(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  exp-per-hit: 1.0
                  same-target-cooldown-seconds: 25
                """);
        assertEquals(25.0, config.combatSameTargetCooldownSeconds());
    }

    @Test
    void sameTargetCooldownClampsNegativeToZero(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  exp-per-hit: 1.0
                  same-target-cooldown-seconds: -5
                """);
        assertEquals(0.0, config.combatSameTargetCooldownSeconds());
    }

    /**
     * PRG-13 (2026-07-25): SMITHING EXP moved from durability-consumption to crafting
     * (weapon/armor/tool via {@code CraftQualityListener}). Mirrors the ars-smithing.exp-per-craft
     * coverage above: default, explicit override, negative-clamp.
     */
    @Test
    void smithingExpPerCraftDefaultsToFifteenWhenAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "combat:\n  exp-per-hit: 1.0\n");
        assertEquals(15.0, config.smithingExpPerCraft());
    }

    @Test
    void smithingExpPerCraftHonorsExplicitValue(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                smithing:
                  exp-per-craft: 42
                """);
        assertEquals(42.0, config.smithingExpPerCraft());
    }

    @Test
    void smithingExpPerCraftClampsNegativeToZero(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                smithing:
                  exp-per-craft: -3
                """);
        assertEquals(0.0, config.smithingExpPerCraft());
    }

    // ------------------------------------------------------------------
    // 2026-07-26 EXP調整タスク1: gathering.exp-mode
    // ------------------------------------------------------------------

    @Test
    void gatheringExpModeDefaultsToDropSumWhenAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "combat:\n  exp-per-hit: 1.0\n");
        assertEquals(SkillExpConfig.GatheringExpMode.DROP_SUM, config.gatheringExpMode());
    }

    @Test
    void gatheringExpModeHonorsBlockValue(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                gathering:
                  exp-mode: block_value
                """);
        assertEquals(SkillExpConfig.GatheringExpMode.BLOCK_VALUE, config.gatheringExpMode());
    }

    @Test
    void gatheringExpModeHonorsMax(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                gathering:
                  exp-mode: max
                """);
        assertEquals(SkillExpConfig.GatheringExpMode.MAX, config.gatheringExpMode());
    }

    @Test
    void gatheringExpModeFallsBackToDropSumOnUnknownValue(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                gathering:
                  exp-mode: totally_bogus
                """);
        assertEquals(SkillExpConfig.GatheringExpMode.DROP_SUM, config.gatheringExpMode());
    }

    // ------------------------------------------------------------------
    // 2026-07-26 EXP調整タスク2: combat.mode / damage-scale / mob-level-scale
    // ------------------------------------------------------------------

    @Test
    void combatDamageScaledModeDefaultsToFalseWhenAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "combat:\n  exp-per-hit: 1.0\n");
        assertEquals(false, config.combatDamageScaledMode());
        assertEquals(0.1, config.combatDamageScale());
        assertEquals(0.02, config.combatMobLevelScale());
    }

    @Test
    void combatDamageScaledModeHonorsExplicitValues(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  mode: damage_scaled
                  damage-scale: 0.5
                  mob-level-scale: 0.1
                """);
        assertEquals(true, config.combatDamageScaledMode());
        assertEquals(0.5, config.combatDamageScale());
        assertEquals(0.1, config.combatMobLevelScale());
    }

    // ------------------------------------------------------------------
    // 2026-07-26 EXP調整タスク3: level-diminishing.*
    // ------------------------------------------------------------------

    @Test
    void levelDiminishingDefaultsToDisabledWhenAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "combat:\n  exp-per-hit: 1.0\n");
        assertEquals(false, config.gatheringExpDiminishingEnabled());
        assertEquals(false, config.combatExpDiminishingEnabled());
        assertEquals("1 / (1 + %level% / 50)", config.expDiminishingFormula());
        assertEquals(0.2, config.expDiminishingFloor());
    }

    @Test
    void levelDiminishingHonorsExplicitValues(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                level-diminishing:
                  gathering-enabled: true
                  combat-enabled: true
                  formula: '1 - %level% / 200'
                  floor: 0.05
                """);
        assertEquals(true, config.gatheringExpDiminishingEnabled());
        assertEquals(true, config.combatExpDiminishingEnabled());
        assertEquals("1 - %level% / 200", config.expDiminishingFormula());
        assertEquals(0.05, config.expDiminishingFloor());
    }
}
