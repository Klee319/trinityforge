package com.trinityforge.config.domains;

import com.trinityforge.stats.ItemStatProfile;
import com.trinityforge.stats.StatRange;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ItemStatsConfig} parses the per-item overlay (fixed + per-quality) — the SOLE per-item stat
 * source — keyed by Material or {@code Material#CustomModelData}, canonicalizes stat keys, and resolves
 * {@code material#cmd} ahead of the plain {@code material}. A syntax error keeps the previous snapshot;
 * a malformed entry is skipped while the rest load. Uses a reflective fake {@link Plugin} (a headless
 * test has no real one) — the same pattern the stats package tests use.
 */
class ItemStatsConfigTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ItemStatsConfigTest");
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

    private static ItemStatsConfig config(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, ItemStatsConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        return new ItemStatsConfig();
    }

    @Test
    void parsesFixedWithCanonicalKeys(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed:
                      attack-power: 7.0
                      crit-chance: 0.05
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        ItemStatProfile profile = config.profileFor(Material.DIAMOND_SWORD, null).orElseThrow();
        assertEquals(7.0, profile.fixed().get("attack_power"), 0.0);
        assertEquals(0.05, profile.fixed().get("crit_chance"), 0.0);
    }

    @Test
    void parsesPerQualityWithCanonicalKeys(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed:
                      attack-power: 7.0
                    per-quality:
                      attack-power: 0.5
                      crit-chance: 0.01
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        ItemStatProfile profile = config.profileFor(Material.DIAMOND_SWORD, null).orElseThrow();
        assertEquals(0.5, profile.perQuality().get("attack_power"), 0.0);
        assertEquals(0.01, profile.perQuality().get("crit_chance"), 0.0);
    }

    @Test
    void absentPerQualitySectionResolvesToEmptyMap(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 7.0 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        ItemStatProfile profile = config.profileFor(Material.DIAMOND_SWORD, null).orElseThrow();
        assertTrue(profile.perQuality().isEmpty());
    }

    @Test
    void nonNumericPerQualityValueSkipsItemInsteadOfZeroStomp(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    per-quality:
                      attack-power: foo
                  GOLDEN_SWORD:
                    fixed: { attack-power: 2.0 }
                """);
        // A typo'd per-quality value must NOT be read as 0.0: the whole item is skipped (load false),
        // while the well-formed item still loads.
        assertFalse(config.load(fakePlugin(tempDir)));

        assertTrue(config.profileFor(Material.DIAMOND_SWORD, null).isEmpty());
        assertEquals(2.0, config.profileFor(Material.GOLDEN_SWORD, null).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void materialHashCmdEntryWinsOverPlainMaterial(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  BLAZE_ROD:
                    fixed: { attack-power: 3.0 }
                  "BLAZE_ROD#100012":
                    fixed: { attack-power: 6.0 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        // With a matching CMD the specific entry wins; a non-matching CMD (or none) falls back to material.
        assertEquals(6.0, config.profileFor(Material.BLAZE_ROD, 100012).orElseThrow()
                .fixed().get("attack_power"), 0.0);
        assertEquals(3.0, config.profileFor(Material.BLAZE_ROD, 999).orElseThrow()
                .fixed().get("attack_power"), 0.0);
        assertEquals(3.0, config.profileFor(Material.BLAZE_ROD, null).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void emptyCmdEntryDoesNotShadowPlainMaterialStats(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  BLAZE_ROD:
                    fixed: { attack-power: 3.0 }
                  "BLAZE_ROD#100012": {}
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        assertEquals(3.0, config.profileFor(Material.BLAZE_ROD, 100012).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void unconfiguredItemResolvesToEmpty(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        assertTrue(config.profileFor(Material.NETHERITE_AXE, null).isEmpty());
        assertTrue(config.profileFor(Material.NETHERITE_AXE, 42).isEmpty());
    }

    @Test
    void unknownMaterialIsSkippedButRestLoad(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  NOT_A_REAL_MATERIAL:
                    fixed: { attack-power: 1.0 }
                  GOLDEN_SWORD:
                    fixed: { attack-power: 2.0 }
                """);
        assertFalse(config.load(fakePlugin(tempDir)));

        assertEquals(2.0, config.profileFor(Material.GOLDEN_SWORD, null).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void nonNumericFixedValueSkipsItemInsteadOfZeroStomp(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed:
                      attack-power: foo
                  GOLDEN_SWORD:
                    fixed: { attack-power: 2.0 }
                """);
        // The typo'd fixed value must NOT be read as 0.0: the whole item is skipped (load false),
        // while the well-formed item still loads.
        assertFalse(config.load(fakePlugin(tempDir)));

        assertTrue(config.profileFor(Material.DIAMOND_SWORD, null).isEmpty());
        assertEquals(2.0, config.profileFor(Material.GOLDEN_SWORD, null).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void randomSectionParsesIntoStatRanges(@TempDir File tempDir) throws IOException {
        // The `random:` section (roll layer 段2) is read into the profile as canonicalized StatRanges,
        // alongside fixed/per-quality on the same entry. A random-only entry is a valid (non-empty)
        // profile carrying just its roll ranges.
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 7.0 }
                    random:
                      attack-power: { min: 1.0, max: 3.0 }
                  GOLDEN_SWORD:
                    random:
                      crit-chance: { min: 0.0, max: 0.2 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)), "a well-formed random: block must load");

        ItemStatProfile diamond = config.profileFor(Material.DIAMOND_SWORD, null).orElseThrow();
        assertEquals(7.0, diamond.fixed().get("attack_power"), 0.0);
        assertTrue(diamond.perQuality().isEmpty());
        StatRange diamondRoll = diamond.random().get("attack_power");
        assertEquals(1.0, diamondRoll.min(), 0.0);
        assertEquals(3.0, diamondRoll.max(), 0.0);

        // A random-only entry is a non-empty profile carrying only its roll range.
        ItemStatProfile golden = config.profileFor(Material.GOLDEN_SWORD, null).orElseThrow();
        assertTrue(golden.fixed().isEmpty());
        assertTrue(golden.perQuality().isEmpty());
        assertFalse(golden.isEmpty());
        StatRange goldenRoll = golden.random().get("crit_chance");
        assertEquals(0.0, goldenRoll.min(), 0.0);
        assertEquals(0.2, goldenRoll.max(), 0.0);
    }

    @Test
    void randomSectionDerivesQuantizationStepFromAuthoredDecimals(@TempDir File tempDir) throws IOException {
        // The roll quantization step (StatRange#step, formerly random-roll-pools' decimal-step) is
        // derived from the number of decimal places AUTHORED on min/max in the yml, not from
        // ConfigurationSection#getDouble (which cannot tell `200` from `200.0`).
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    random:
                      attack-power: { min: 200, max: 600 }
                  GOLDEN_SWORD:
                    random:
                      crit-chance: { min: 0.02, max: 0.05 }
                  IRON_SWORD:
                    random:
                      flat-bonus-damage: { min: 0.5, max: 2.0 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        StatRange bothIntegers = config.profileFor(Material.DIAMOND_SWORD, null).orElseThrow()
                .random().get("attack_power");
        assertEquals(1.0, bothIntegers.step(), 0.0, "both bounds authored as integers -> step=1");

        StatRange twoDecimals = config.profileFor(Material.GOLDEN_SWORD, null).orElseThrow()
                .random().get("crit_chance");
        assertEquals(0.01, twoDecimals.step(), 1e-12, "min: 0.02 / max: 0.05 -> step=0.01");

        StatRange oneDecimal = config.profileFor(Material.IRON_SWORD, null).orElseThrow()
                .random().get("flat_bonus_damage");
        assertEquals(0.1, oneDecimal.step(), 1e-12, "min: 0.5 / max: 2.0 -> step=0.1");
    }

    @Test
    void malformedRandomRangeSkipsTheItem(@TempDir File tempDir) throws IOException {
        // A random stat with min > max (or a non-{min,max} shape) is malformed: the whole item is
        // skipped with a warning (load returns false), matching the fixed/per-quality skip contract.
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    random:
                      attack-power: { min: 5.0, max: 1.0 }
                  GOLDEN_SWORD:
                    fixed: { attack-power: 2.0 }
                """);
        assertFalse(config.load(fakePlugin(tempDir)), "min > max must skip the item and report a warning");

        assertTrue(config.profileFor(Material.DIAMOND_SWORD, null).isEmpty());
        assertEquals(2.0, config.profileFor(Material.GOLDEN_SWORD, null).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void syntaxErrorKeepsPreviousSnapshot(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 7.0 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        // Overwrite with broken YAML and reload: the good snapshot must survive (load returns false).
        Files.writeString(new File(tempDir, ItemStatsConfig.PATH).toPath(), "items:\n  DIAMOND_SWORD: {\n");
        assertFalse(config.load(fakePlugin(tempDir)));

        Optional<ItemStatProfile> kept = config.profileFor(Material.DIAMOND_SWORD, null);
        assertEquals(7.0, kept.orElseThrow().fixed().get("attack_power"), 0.0);
    }

    @Test
    void nullMaterialResolvesToEmpty(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));
        assertTrue(config.profileFor(null, null).isEmpty());
    }

    // --- #5 fallback (未設定時のデフォルト) ---

    @Test
    void fallbackAbsentResolvesToEmpty(@TempDir File tempDir) throws IOException {
        // No fallback section -> fallback() empty, so an unconfigured item stays statless (回帰なし).
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));
        assertTrue(config.fallback().isEmpty());
    }

    @Test
    void fallbackParsesFixedPerQualityAndRandom(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items: {}
                fallback:
                  fixed:
                    attack-power: 2.0
                  per-quality:
                    crit-chance: 0.01
                  random:
                    penetration: { min: 0.0, max: 0.1 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        ItemStatProfile fallback = config.fallback().orElseThrow();
        assertEquals(2.0, fallback.fixed().get("attack_power"), 0.0);
        assertEquals(0.01, fallback.perQuality().get("crit_chance"), 0.0);
        StatRange roll = fallback.random().get("penetration");
        assertEquals(0.0, roll.min(), 0.0);
        assertEquals(0.1, roll.max(), 0.0);
    }

    @Test
    void emptyFallbackSectionIsInert(@TempDir File tempDir) throws IOException {
        // An all-empty fallback section is treated as "no fallback" so behaviour is unchanged.
        ItemStatsConfig config = config(tempDir, """
                items: {}
                fallback: {}
                """);
        assertTrue(config.load(fakePlugin(tempDir)));
        assertTrue(config.fallback().isEmpty());
    }

    @Test
    void categoryFallbackFixedAndLoreDefault(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items: {}
                fallback:
                  weapon:
                    fixed:
                      attack-power: 1.5
                      crit-chance: 0.0
                    lore-default:
                      attack-power: true
                      crit-chance: true
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        assertEquals(1.5, config.fallbackFixedFor(Material.DIAMOND_SWORD).get("attack_power"), 0.0);
        assertTrue(config.loreDefaultKeysFor(Material.DIAMOND_SWORD).contains("attack_power"));
        assertTrue(config.loreDefaultKeysFor(Material.DIAMOND_SWORD).contains("crit_chance"));
        // armor material must not pick up weapon-only fallback
        assertTrue(config.fallbackFixedFor(Material.DIAMOND_CHESTPLATE).isEmpty());
        assertTrue(config.loreDefaultKeysFor(Material.DIAMOND_CHESTPLATE).isEmpty());
    }

    @Test
    void categoryFallbackFillsGapsOnTopOfLegacy(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items: {}
                fallback:
                  fixed:
                    attack-power: 2.0
                  weapon:
                    fixed:
                      crit-chance: 0.05
                """);
        assertTrue(config.load(fakePlugin(tempDir)));
        Map<String, Double> fixed = config.fallbackFixedFor(Material.IRON_SWORD);
        assertEquals(2.0, fixed.get("attack_power"), 0.0);
        assertEquals(0.05, fixed.get("crit_chance"), 0.0);
    }

    // --- multipliers (乗算モード) / fallback-overrides (任意カテゴリ上書き) ---

    @Test
    void parsesMultiplierLayersWithVerbatimValues(@TempDir File tempDir) throws IOException {
        // 乗算値はパーセント正規化しない: x2 は 2.0 のまま (0.02 になってはいけない)。
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    fixed: { attack-power: 7.0 }
                    multipliers:
                      burst:
                        fixed:
                          attack-power: 2.0
                        per-quality:
                          crit-chance: 0.1
                      aura:
                        random:
                          attack-power: { min: 1.1, max: 1.5 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        ItemStatProfile profile = config.profileFor(Material.DIAMOND_SWORD, null).orElseThrow();
        assertEquals(2, profile.multipliers().size());
        assertEquals(2.0, profile.multipliers().get("burst").fixed().get("attack_power"), 0.0);
        assertEquals(0.1, profile.multipliers().get("burst").perQuality().get("crit_chance"), 0.0);
        assertEquals(1.1, profile.multipliers().get("aura").random().get("attack_power").min(), 0.0);
        assertEquals(1.5, profile.multipliers().get("aura").random().get("attack_power").max(), 0.0);
    }

    @Test
    void nonNumericMultiplierValueSkipsItem(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    multipliers:
                      burst:
                        fixed: { attack-power: foo }
                  GOLDEN_SWORD:
                    fixed: { attack-power: 2.0 }
                """);
        assertFalse(config.load(fakePlugin(tempDir)));
        assertTrue(config.profileFor(Material.DIAMOND_SWORD, null).isEmpty());
        assertEquals(2.0, config.profileFor(Material.GOLDEN_SWORD, null).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void fallbackOverrideReplacesDefaultForEditorCategory(@TempDir File tempDir) throws IOException {
        // 任意カテゴリ (cat-1) に属するアイテムは fallback-overrides.cat-1 が未設定フォールバックを
        // 完全に置き換える。属さないアイテムは従来の装備カテゴリフォールバックのまま。
        ItemStatsConfig config = config(tempDir, """
                items: {}
                fallback:
                  weapon:
                    fixed:
                      attack-power: 1.5
                fallback-overrides:
                  cat-1:
                    fixed:
                      attack-power: 9.0
                    lore-default:
                      attack-power: true
                _editor:
                  categories:
                    quality:
                      - id: cat-1
                        label: 特殊武器
                        itemIds:
                          - DIAMOND_SWORD
                          - "BOW#100500"
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        assertEquals(9.0, config.fallbackFixedFor(Material.DIAMOND_SWORD, null).get("attack_power"), 0.0);
        assertTrue(config.loreDefaultKeysFor(Material.DIAMOND_SWORD, null).contains("attack_power"));
        // CMDキー (BOW#100500) でも解決される
        assertEquals(9.0, config.fallbackFixedFor(Material.BOW, 100500).get("attack_power"), 0.0);
        // カテゴリ外のアイテムは未設定(既定)フォールバック
        assertEquals(1.5, config.fallbackFixedFor(Material.IRON_SWORD, null).get("attack_power"), 0.0);
    }

    // --- dynamic registration (fork-facing API: registerDynamic/unregisterDynamic/clearDynamic) ---

    @Test
    void registerDynamicIsUsedWhenNoConfigEntryExists(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        config.registerDynamic("fork-catalyst", Material.BLAZE_ROD, 100012,
                new ItemStatProfile(Map.of("attack_power", 4.0), Map.of(), Map.of()));

        ItemStatProfile profile = config.profileFor(Material.BLAZE_ROD, 100012).orElseThrow();
        assertEquals(4.0, profile.fixed().get("attack_power"), 0.0);
    }

    @Test
    void configExactCmdEntryWinsOverDynamic(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  "BLAZE_ROD#100012":
                    fixed: { attack-power: 6.0 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        config.registerDynamic("fork-catalyst", Material.BLAZE_ROD, 100012,
                new ItemStatProfile(Map.of("attack_power", 4.0), Map.of(), Map.of()));

        // config's MATERIAL#cmd entry is the highest-priority source; dynamic never overrides it.
        assertEquals(6.0, config.profileFor(Material.BLAZE_ROD, 100012).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void dynamicWinsOverPlainMaterialConfigEntry(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  BLAZE_ROD:
                    fixed: { attack-power: 3.0 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        config.registerDynamic("fork-catalyst", Material.BLAZE_ROD, 100012,
                new ItemStatProfile(Map.of("attack_power", 4.0), Map.of(), Map.of()));

        // dynamic (MATERIAL#cmd, more specific) wins over the plain-MATERIAL config entry, per the
        // documented precedence: config-cmd > dynamic > config-plain.
        assertEquals(4.0, config.profileFor(Material.BLAZE_ROD, 100012).orElseThrow()
                .fixed().get("attack_power"), 0.0);
        // A CMD that the dynamic registration does not cover still falls back to the plain config entry.
        assertEquals(3.0, config.profileFor(Material.BLAZE_ROD, 999).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void unregisterDynamicRemovesJustThatEntry(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        config.registerDynamic("fork-catalyst", Material.BLAZE_ROD, 100012,
                new ItemStatProfile(Map.of("attack_power", 4.0), Map.of(), Map.of()));
        assertTrue(config.profileFor(Material.BLAZE_ROD, 100012).isPresent());

        config.unregisterDynamic(Material.BLAZE_ROD, 100012);
        assertTrue(config.profileFor(Material.BLAZE_ROD, 100012).isEmpty());
    }

    @Test
    void clearDynamicRemovesOnlyItsOwnNamespace(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        config.registerDynamic("fork-a", Material.BLAZE_ROD, 1,
                new ItemStatProfile(Map.of("attack_power", 1.0), Map.of(), Map.of()));
        config.registerDynamic("fork-b", Material.BLAZE_ROD, 2,
                new ItemStatProfile(Map.of("attack_power", 2.0), Map.of(), Map.of()));

        config.clearDynamic("fork-a");

        assertTrue(config.profileFor(Material.BLAZE_ROD, 1).isEmpty(), "fork-a's entry must be gone");
        assertEquals(2.0, config.profileFor(Material.BLAZE_ROD, 2).orElseThrow()
                .fixed().get("attack_power"), 0.0, "fork-b's entry must survive fork-a's clear");
    }

    @Test
    void configReloadDoesNotClearDynamicRegistrations(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        config.registerDynamic("fork-catalyst", Material.BLAZE_ROD, 100012,
                new ItemStatProfile(Map.of("attack_power", 4.0), Map.of(), Map.of()));

        // A fresh /trinityforge reload (config load()) must not wipe a fork's dynamic registrations.
        assertTrue(config.load(fakePlugin(tempDir)));

        assertEquals(4.0, config.profileFor(Material.BLAZE_ROD, 100012).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void registerDynamicMapOverloadBuildsProfileEquivalentToRecordOverload(@TempDir File tempDir)
            throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        config.registerDynamic("fork-catalyst", Material.BLAZE_ROD, 100012,
                Map.of("attack_power", 5.0), Map.of("crit_chance", 0.02), Map.of(), 10, true);

        ItemStatProfile profile = config.profileFor(Material.BLAZE_ROD, 100012).orElseThrow();
        assertEquals(5.0, profile.fixed().get("attack_power"), 0.0);
        assertEquals(0.02, profile.perQuality().get("crit_chance"), 0.0);
        assertEquals(10, profile.durability());
        assertTrue(profile.offhandApplies());
    }

    @Test
    void registerDynamicIgnoresNullArgumentsFailSafe(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        // Must not throw for any of these; must simply no-op.
        config.registerDynamic(null, Material.BLAZE_ROD, 1, new ItemStatProfile(Map.of(), Map.of(), Map.of()));
        config.registerDynamic("ns", null, 1, new ItemStatProfile(Map.of(), Map.of(), Map.of()));
        config.registerDynamic("ns", Material.BLAZE_ROD, 1, null);
        config.unregisterDynamic(null, 1);
        config.clearDynamic(null);

        assertTrue(config.profileFor(Material.BLAZE_ROD, 1).isEmpty());
    }

    // --- offhandStatsApply (fork-facing API: resolves the same precedence as profileFor) ---

    @Test
    void offhandStatsApplyReflectsConfigProfile(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  BLAZE_ROD:
                    offhand-stats-apply: true
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        assertTrue(config.offhandStatsApply(Material.BLAZE_ROD, null));
    }

    @Test
    void offhandStatsApplyReflectsDynamicProfile(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        config.registerDynamic("fork-catalyst", Material.BLAZE_ROD, 100012,
                new ItemStatProfile(Map.of(), Map.of(), Map.of(), null, true));

        assertTrue(config.offhandStatsApply(Material.BLAZE_ROD, 100012));
    }

    @Test
    void offhandStatsApplyDefaultsFalseWhenUnregistered(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, "items: {}\n");
        assertTrue(config.load(fakePlugin(tempDir)));

        assertFalse(config.offhandStatsApply(Material.NETHERITE_AXE, null));
        assertFalse(config.offhandStatsApply(null, null));
    }

    @Test
    void malformedFallbackIsIgnoredAndFlaggedButItemsStillLoad(@TempDir File tempDir) throws IOException {
        // A typo'd fallback value must not zero-stomp: fallback is disabled (empty) and load reports false,
        // while the well-formed items: entries still load.
        ItemStatsConfig config = config(tempDir, """
                items:
                  GOLDEN_SWORD:
                    fixed: { attack-power: 2.0 }
                fallback:
                  fixed:
                    attack-power: foo
                """);
        assertFalse(config.load(fakePlugin(tempDir)));

        assertTrue(config.fallback().isEmpty());
        assertEquals(2.0, config.profileFor(Material.GOLDEN_SWORD, null).orElseThrow()
                .fixed().get("attack_power"), 0.0);
    }

    @Test
    void qualityModeOffsetParsesAndPrefersCmdSpecificEntry(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    quality-mode-offset: 1
                  "DIAMOND_SWORD#62":
                    quality-mode-offset: -2
                  GOLDEN_SWORD:
                    fixed: { attack-power: 2.0 }
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        // material#cmd 完全一致が最優先、無ければ素のmaterial、どちらも無ければ0 (=デフォルト)。
        assertEquals(-2, config.qualityModeOffsetFor(Material.DIAMOND_SWORD, 62));
        assertEquals(1, config.qualityModeOffsetFor(Material.DIAMOND_SWORD, 999));
        assertEquals(1, config.qualityModeOffsetFor(Material.DIAMOND_SWORD, null));
        assertEquals(0, config.qualityModeOffsetFor(Material.GOLDEN_SWORD, null));
        assertEquals(0, config.qualityModeOffsetFor(Material.IRON_SWORD, null));
    }

    @Test
    void nonIntegerQualityModeOffsetSkipsItemButOthersStillLoad(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  DIAMOND_SWORD:
                    quality-mode-offset: "high"
                  GOLDEN_SWORD:
                    quality-mode-offset: 3
                """);
        assertFalse(config.load(fakePlugin(tempDir)));

        assertEquals(0, config.qualityModeOffsetFor(Material.DIAMOND_SWORD, null));
        assertEquals(3, config.qualityModeOffsetFor(Material.GOLDEN_SWORD, null));
    }

    @Test
    void bundledGatheringToolsCarryTierUseLevelAndQualityBaseline(@TempDir File tempDir) throws IOException {
        File bundled = new File("src/main/resources/" + ItemStatsConfig.PATH);
        File installed = new File(tempDir, ItemStatsConfig.PATH);
        Files.createDirectories(installed.getParentFile().toPath());
        Files.copy(bundled.toPath(), installed.toPath());

        ItemStatsConfig config = new ItemStatsConfig();
        assertTrue(config.load(fakePlugin(tempDir)));

        assertGatheringTier(config, Material.WOODEN_PICKAXE, null, "MINING", 0, 0);
        assertGatheringTier(config, Material.STONE_PICKAXE, null, "MINING", 5, 0);
        assertGatheringTier(config, Material.COPPER_PICKAXE, null, "MINING", 15, -1);
        assertGatheringTier(config, Material.IRON_PICKAXE, null, "MINING", 25, -2);
        assertGatheringTier(config, Material.GOLDEN_PICKAXE, null, "MINING", 35, -2);
        assertGatheringTier(config, Material.DIAMOND_PICKAXE, null, "MINING", 45, -4);
        assertGatheringTier(config, Material.NETHERITE_PICKAXE, null, "MINING", 60, -6);

        assertGatheringTier(config, Material.WOODEN_SHOVEL, null, "DIGGING", 0, 0);
        assertGatheringTier(config, Material.STONE_SHOVEL, null, "DIGGING", 5, 0);
        assertGatheringTier(config, Material.COPPER_SHOVEL, null, "DIGGING", 15, -1);
        assertGatheringTier(config, Material.IRON_SHOVEL, null, "DIGGING", 25, -2);
        assertGatheringTier(config, Material.GOLDEN_SHOVEL, null, "DIGGING", 35, -2);
        assertGatheringTier(config, Material.DIAMOND_SHOVEL, null, "DIGGING", 45, -4);
        assertGatheringTier(config, Material.NETHERITE_SHOVEL, null, "DIGGING", 60, -6);

        assertGatheringTier(config, Material.WOODEN_HOE, null, "FARMING", 0, 0);
        assertGatheringTier(config, Material.STONE_HOE, null, "FARMING", 5, 0);
        assertGatheringTier(config, Material.COPPER_HOE, null, "FARMING", 15, -1);
        assertGatheringTier(config, Material.IRON_HOE, null, "FARMING", 25, -2);
        assertGatheringTier(config, Material.GOLDEN_HOE, null, "FARMING", 35, -2);
        assertGatheringTier(config, Material.DIAMOND_HOE, null, "FARMING", 45, -4);
        assertGatheringTier(config, Material.NETHERITE_HOE, null, "FARMING", 60, -6);

        assertGatheringTier(config, Material.WOODEN_AXE, 200105, "WOODCUTTING", 0, 0);
        assertGatheringTier(config, Material.STONE_AXE, 200106, "WOODCUTTING", 5, 0);
        assertGatheringTier(config, Material.COPPER_AXE, 200107, "WOODCUTTING", 15, -1);
        assertGatheringTier(config, Material.IRON_AXE, 200108, "WOODCUTTING", 25, -2);
        assertGatheringTier(config, Material.GOLDEN_AXE, 200109, "WOODCUTTING", 35, -2);
        assertGatheringTier(config, Material.DIAMOND_AXE, 200110, "WOODCUTTING", 45, -4);
        assertGatheringTier(config, Material.NETHERITE_AXE, 200111, "WOODCUTTING", 60, -6);

        // Named special tiers follow their catalog identity, not their backing vanilla material.
        assertGatheringTier(config, Material.DIAMOND_PICKAXE, 73, "MINING", 30, -3);
        assertGatheringTier(config, Material.NETHERITE_PICKAXE, 185, "MINING", 100, -8);
        assertGatheringTier(config, Material.DIAMOND_SHOVEL, 74, "DIGGING", 30, -3);
        assertGatheringTier(config, Material.NETHERITE_SHOVEL, 188, "DIGGING", 100, -8);
        assertGatheringTier(config, Material.DIAMOND_HOE, 76, "FARMING", 30, -3);
        assertGatheringTier(config, Material.NETHERITE_HOE, 194, "FARMING", 100, -8);
        assertGatheringTier(config, Material.DIAMOND_AXE, 75, "WOODCUTTING", 30, -3);
        assertGatheringTier(config, Material.NETHERITE_AXE, 191, "WOODCUTTING", 100, -8);
    }

    private static void assertGatheringTier(ItemStatsConfig config, Material material, Integer cmd,
                                            String skill, int level, int qualityModeOffset) {
        var requirement = config.useRequirementFor(material, cmd).orElseThrow(
                () -> new AssertionError("missing use requirement: " + material + "#" + cmd));
        assertEquals(skill, requirement.skill(), material + "#" + cmd + " skill");
        assertEquals(level, requirement.level(), material + "#" + cmd + " level");
        assertEquals(qualityModeOffset, config.qualityModeOffsetFor(material, cmd),
                material + "#" + cmd + " quality baseline");
    }

    @Test
    void topLevelCategoryResolvesCmdSpecificEditorWeapon(@TempDir File tempDir) throws IOException {
        ItemStatsConfig config = config(tempDir, """
                items:
                  "BOOK#100004":
                    fixed: { attack-power: 10 }
                _editor:
                  categories:
                    weapon:
                      - id: special-weapons
                        label: 特殊武器
                        itemIds: ["BOOK#100004"]
                """);
        assertTrue(config.load(fakePlugin(tempDir)));

        assertEquals(Optional.of("weapon"),
                config.topLevelCategoryFor(Material.BOOK, 100004));
        assertEquals(Optional.empty(),
                config.topLevelCategoryFor(Material.BOOK, 999));
    }
}
