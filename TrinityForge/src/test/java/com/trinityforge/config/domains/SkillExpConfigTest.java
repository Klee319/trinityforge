package com.trinityforge.config.domains;

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

/** Covers the editable active EXP formulas and their validation boundaries. */
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

    /**
     * PRG-13 (2026-07-25): SMITHING EXP moved from durability-consumption to crafting
     * (weapon/armor/tool via {@code CraftQualityListener}). Mirrors the ars-smithing.exp-per-craft
     * coverage above: default, explicit override, negative-clamp.
     */
    @Test
    void smithingExpPerCraftDefaultsToFifteenWhenAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "{}\n");
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
        SkillExpConfig config = loaded(tempDir, "{}\n");
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

    @Test
    void weaponKillExpUsesSkillBaseMobLevelHealthAndEntityType(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  kill-exp:
                    base:
                      HEAVY_WEAPONS: 30
                      LIGHT_WEAPONS: 20
                    per-mob-level: 2
                    per-max-health: 0.5
                    entity-type-multipliers:
                      ZOMBIE: 1.5
                """);

        // (heavy base 30 + level 10*2 + maxHealth 20*0.5) * zombie 1.5 = 90
        assertEquals(90.0,
                config.combatKillExp(SkillId.HEAVY_WEAPONS, "zombie", 10, 20.0), 1e-9);
        // 2026-07-28 ユーザー要望: entity-type-multipliers に行が無いモブは討伐EXPを一切生まない
        // (unlisted-entity-multiplier の既定 0.0)。旧挙動は 1.0 フォールバックだった。
        assertEquals(0.0,
                config.combatKillExp(SkillId.LIGHT_WEAPONS, "unknown_future_mob", 10, 20.0), 1e-9);
    }

    @Test
    void unlistedEntityMultiplierCanRestoreTheOldNeutralFallback(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  kill-exp:
                    base:
                      LIGHT_WEAPONS: 20
                    per-mob-level: 2
                    per-max-health: 0.5
                    unlisted-entity-multiplier: 1.0
                    entity-type-multipliers:
                      ZOMBIE: 1.5
                ars-magic:
                  kill-exp:
                    base: 15
                    per-mob-level: 3
                    per-max-health: 0.25
                    unlisted-entity-multiplier: 1.0
                    entity-type-multipliers:
                      WITHER: 4
                """);

        // (20 + 10*2 + 20*0.5) * 1.0 = 50
        assertEquals(50.0,
                config.combatKillExp(SkillId.LIGHT_WEAPONS, "unknown_future_mob", 10, 20.0), 1e-9);
        // (15 + 5*3 + 100*0.25) * 1.0 = 55
        assertEquals(55.0, config.arsMagicKillExp("unknown_future_mob", 5, 100.0), 1e-9);
    }

    @Test
    void arsMagicKillAndBlockExpSettingsAreConfigDriven(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                ars-magic:
                  kill-exp:
                    enabled: true
                    base: 15
                    per-mob-level: 3
                    per-max-health: 0.25
                    entity-type-multipliers:
                      WITHER: 4
                  block-break-exp:
                    enabled: true
                    source-multiplier: 1.75
                """);

        // (15 + level 5*3 + maxHealth 100*0.25) * wither 4 = 220
        assertEquals(220.0, config.arsMagicKillExp("wither", 5, 100.0), 1e-9);
        assertEquals(true, config.arsMagicKillExpEnabled());
        assertEquals(true, config.arsMagicBlockBreakExpEnabled());
        assertEquals(1.75, config.arsMagicBlockBreakSourceMultiplier(), 1e-9);
    }

    @Test
    void expFormulasClampMalformedNegativeInputs(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                combat:
                  kill-exp:
                    base:
                      HEAVY_WEAPONS: -10
                    per-mob-level: -2
                    per-max-health: -1
                    entity-type-multipliers:
                      ZOMBIE: -3
                ars-magic:
                  kill-exp:
                    base: -1
                    per-mob-level: -2
                    per-max-health: -3
                  block-break-exp:
                    source-multiplier: -4
                """);

        assertEquals(0.0, config.combatKillExp(SkillId.HEAVY_WEAPONS, "ZOMBIE", -5, -20.0));
        assertEquals(0.0, config.arsMagicKillExp("ZOMBIE", -5, -20.0));
        assertEquals(0.0, config.arsMagicBlockBreakSourceMultiplier());
    }

    // ------------------------------------------------------------------
    // 2026-07-26 EXP調整タスク3: level-diminishing.*
    // ------------------------------------------------------------------

    @Test
    void levelDiminishingDefaultsToDisabledWhenAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "{}\n");
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

    // ------------------------------------------------------------------
    // 2026-07-28 使用可能レベル連動EXP: use-level-scaling.*
    // ------------------------------------------------------------------

    @Test
    void useLevelScalingDefaultsWhenSectionAbsent(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, "{}\n");
        assertEquals(true, config.useLevelScalingEnabled());
        assertEquals(3.0, config.useLevelScalingMaxMultiplier());
        // 未設定でも既定挙動(1.0 = 影響なし)を保つ後方互換: per-levelが空でも例外にならない。
        assertEquals(1.0, config.useLevelExpMultiplier(SkillId.SMITHING, 100));
    }

    @Test
    void useLevelExpMultiplierDisabledAlwaysReturnsOne(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                use-level-scaling:
                  enabled: false
                  per-level:
                    smithing: 0.01
                """);
        assertEquals(1.0, config.useLevelExpMultiplier(SkillId.SMITHING, 100));
    }

    @Test
    void useLevelExpMultiplierZeroLevelIsAlwaysOne(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                use-level-scaling:
                  enabled: true
                  per-level:
                    smithing: 0.01
                """);
        assertEquals(1.0, config.useLevelExpMultiplier(SkillId.SMITHING, 0));
        assertEquals(1.0, config.useLevelExpMultiplier(SkillId.SMITHING, -10));
    }

    @Test
    void useLevelExpMultiplierMatchesExpectedValuesForEachItemStatsLevel(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                use-level-scaling:
                  enabled: true
                  max-multiplier: 3.0
                  per-level:
                    woodcutting: 0.01
                """);
        // item-stats.yml の実際の使用可能レベル9段(0/10/20/30/40/55/70/85/100)。
        assertEquals(1.00, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 0), 1e-9);
        assertEquals(1.10, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 10), 1e-9);
        assertEquals(1.20, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 20), 1e-9);
        assertEquals(1.30, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 30), 1e-9);
        assertEquals(1.40, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 40), 1e-9);
        assertEquals(1.55, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 55), 1e-9);
        assertEquals(1.70, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 70), 1e-9);
        assertEquals(1.85, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 85), 1e-9);
        assertEquals(2.00, config.useLevelExpMultiplier(SkillId.WOODCUTTING, 100), 1e-9);
    }

    @Test
    void useLevelExpMultiplierClampsToMaxMultiplier(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                use-level-scaling:
                  enabled: true
                  max-multiplier: 1.5
                  per-level:
                    mining: 0.5
                """);
        // 素の式なら 1 + 100*0.5 = 51.0 だが、max-multiplierで1.5に頭打ちされる。
        assertEquals(1.5, config.useLevelExpMultiplier(SkillId.MINING, 100));
    }

    @Test
    void useLevelExpMultiplierIsCaseInsensitiveForSkillId(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                use-level-scaling:
                  enabled: true
                  per-level:
                    digging: 0.01
                """);
        assertEquals(1.10, config.useLevelExpMultiplier(SkillId.DIGGING, 10), 1e-9);
        assertEquals(1.10, config.useLevelExpMultiplier("digging", 10), 1e-9);
    }

    @Test
    void useLevelExpMultiplierUndefinedSkillIsAlwaysOne(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                use-level-scaling:
                  enabled: true
                  per-level:
                    smithing: 0.01
                    woodcutting: 0.01
                    mining: 0.01
                    digging: 0.01
                """);
        // FARMINGは要件どおり対象外(per-levelに行が無い) — 高レベルでも常に1.0。
        assertEquals(1.0, config.useLevelExpMultiplier(SkillId.FARMING, 100));
    }

    @Test
    void useLevelExpMultiplierNegativePerLevelNeverDropsBelowOne(@TempDir File tempDir) throws IOException {
        SkillExpConfig config = loaded(tempDir, """
                use-level-scaling:
                  enabled: true
                  per-level:
                    mining: -0.01
                """);
        assertEquals(1.0, config.useLevelExpMultiplier(SkillId.MINING, 100));
    }

    /**
     * 出荷値ドリフト検知(2026-07-28): {@code stats/skill-exp.yml} の {@code use-level-scaling} 出荷値が
     * このクラスの想定既定値と食い違っていないことを固定する({@link GimmickTierYamlDriftTest} と
     * 同じ「実クラスパスの本物のymlを本物のローダーで読む」流儀)。
     *
     * <p>2026-07-30 の再調整で {@code smithing} の per-level が 0.01 -> 1.3、{@code ars-smithing} が
     * 0.01 -> 0.1 に上がり、それに合わせて {@code max-multiplier} が 3.0 -> 100 になった
     * (per-level 1.3 では使用可能レベル 2 で 3.0 に張り付いてしまい、上限が実質的な打ち切りになるため)。
     */
    @Test
    void shippedSkillExpYamlMatchesExpectedUseLevelScalingDefaults() throws Exception {
        SkillExpConfig config = new SkillExpConfig();
        java.io.File dataFolder = java.nio.file.Files.createTempDirectory("skill-exp-drift").toFile();
        try (java.io.InputStream in = SkillExpConfigTest.class.getClassLoader()
                .getResourceAsStream(SkillExpConfig.PATH)) {
            assertTrue(in != null, "bundled " + SkillExpConfig.PATH + " must be on the test classpath");
            File file = new File(dataFolder, SkillExpConfig.PATH);
            Files.createDirectories(file.getParentFile().toPath());
            Files.copy(in, file.toPath());
        }
        assertTrue(config.load(fakePlugin(dataFolder)), SkillExpConfig.PATH + " must load OK");

        assertEquals(true, config.useLevelScalingEnabled());
        assertEquals(100.0, config.useLevelScalingMaxMultiplier());
        assertEquals(0.1, config.useLevelScalingPerLevel().get("ars-smithing"));
        assertEquals(1.3, config.useLevelScalingPerLevel().get("smithing"));
        assertEquals(0.01, config.useLevelScalingPerLevel().get("woodcutting"));
        assertEquals(0.01, config.useLevelScalingPerLevel().get("mining"));
        assertEquals(0.01, config.useLevelScalingPerLevel().get("digging"));
        assertEquals(null, config.useLevelScalingPerLevel().get("farming"));
    }
}
