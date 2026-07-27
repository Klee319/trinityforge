package com.trinityforge.progression;

import com.trinityforge.config.domains.SkillExpConfig;
import com.trinityforge.progression.core.SkillId;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-07-26 EXP調整タスク3: {@link SkillExpDiminishingCurve} の検証。
 * 「既定config(level-diminishing.*未指定)では常に1.0(=現行挙動と完全一致)」であること、
 * 有効化した場合にレベルに応じて減ること、gathering/combatを片方だけ有効化できること、
 * 対象外スキル(SMITHING等)は触らないことを確認する。
 */
class SkillExpDiminishingCurveTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("SkillExpDiminishingCurveTest");
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
    void defaultConfigAlwaysReturnsOneForGatheringAndCombatSkills(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "combat:\n  exp-per-hit: 1.0\n");
        SkillExpDiminishingCurve curve = new SkillExpDiminishingCurve(config);

        assertEquals(1.0, curve.multiplierFor(SkillId.MINING, 1));
        assertEquals(1.0, curve.multiplierFor(SkillId.MINING, 500));
        assertEquals(1.0, curve.multiplierFor(SkillId.HEAVY_WEAPONS, 500));
        assertEquals(1.0, curve.multiplierFor(SkillId.ARS_MAGIC, 500));
    }

    @Test
    void gatheringEnabledReducesMultiplierAsLevelRises(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                level-diminishing:
                  gathering-enabled: true
                  combat-enabled: false
                  formula: '1 / (1 + %level% / 50)'
                  floor: 0.2
                """);
        SkillExpDiminishingCurve curve = new SkillExpDiminishingCurve(config);

        double atLevel0 = curve.multiplierFor(SkillId.MINING, 1);
        double atLevel50 = curve.multiplierFor(SkillId.MINING, 50);
        double atLevel150 = curve.multiplierFor(SkillId.MINING, 150);

        assertTrue(atLevel0 > atLevel50, "multiplier must strictly decrease as level rises");
        assertTrue(atLevel50 > atLevel150, "multiplier must strictly decrease as level rises");
        assertEquals(0.5, atLevel50, 1e-9);
        assertEquals(0.25, atLevel150, 1e-9);
    }

    @Test
    void combatOnlyEnabledLeavesGatheringUntouched(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                level-diminishing:
                  gathering-enabled: false
                  combat-enabled: true
                  formula: '1 / (1 + %level% / 50)'
                  floor: 0.2
                """);
        SkillExpDiminishingCurve curve = new SkillExpDiminishingCurve(config);

        assertEquals(1.0, curve.multiplierFor(SkillId.MINING, 100),
                "gathering-enabled=false must leave gathering skills at multiplier 1.0");
        assertTrue(curve.multiplierFor(SkillId.HEAVY_WEAPONS, 100) < 1.0,
                "combat-enabled=true must reduce combat skills");
    }

    @Test
    void floorClampsMultiplierEvenAtVeryHighLevel(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                level-diminishing:
                  gathering-enabled: true
                  combat-enabled: false
                  formula: '1 / (1 + %level% / 50)'
                  floor: 0.2
                """);
        SkillExpDiminishingCurve curve = new SkillExpDiminishingCurve(config);
        assertEquals(0.2, curve.multiplierFor(SkillId.MINING, 100_000), 1e-9);
    }

    @Test
    void unrelatedSkillsAreNeverAffectedEvenWhenBothEnabled(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                level-diminishing:
                  gathering-enabled: true
                  combat-enabled: true
                  formula: '1 / (1 + %level% / 50)'
                  floor: 0.2
                """);
        SkillExpDiminishingCurve curve = new SkillExpDiminishingCurve(config);

        assertEquals(1.0, curve.multiplierFor(SkillId.SMITHING, 100));
        assertEquals(1.0, curve.multiplierFor(SkillId.ALCHEMY, 100));
        assertEquals(1.0, curve.multiplierFor(SkillId.ENCHANTING, 100));
        assertEquals(1.0, curve.multiplierFor(SkillId.FISHING, 100));
        assertEquals(1.0, curve.multiplierFor(SkillId.POWER, 100));
    }

    @Test
    void malformedFormulaFailsSafeToOne(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                level-diminishing:
                  gathering-enabled: true
                  formula: 'not a valid formula ###'
                """);
        SkillExpDiminishingCurve curve = new SkillExpDiminishingCurve(config);
        assertEquals(1.0, curve.multiplierFor(SkillId.MINING, 50));
    }
}
