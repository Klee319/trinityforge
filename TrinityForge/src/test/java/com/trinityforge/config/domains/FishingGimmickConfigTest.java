package com.trinityforge.config.domains;

import com.trinityforge.stats.DropTableConfig;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FishingGimmickConfig} のデフォルト/明示上書きを検証する: 特に {@code fishing.group-ratio}/
 * {@code fishing.groups}(§2.3/§4の宝/ゴミ抽選テーブル)と {@code fishing.skill-id/luck-per-level/
 * bonus-per-level}(旧 gathering.yml 統合、§D)、および {@link FishingGimmickConfig#dropTablesEmpty()}
 * のフォールバック判定。
 */
class FishingGimmickConfigTest {

    // T4(ocean-biomes)テストは実バイオームレジストリの解決に生きたサーバー相当が必要なため
    // MockBukkitを立てる(他のケースは純粋にYAML読取のみで不要)。
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("FishingGimmickConfigTest");
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

    private static FishingGimmickConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, FishingGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        FishingGimmickConfig config = new FishingGimmickConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultsWhenSectionsAbsent(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, "xp-bottle-store:\n  store-amount: 100\n");
        assertTrue(config.junkMaterials().isEmpty());
        assertTrue(config.treasureMaterials().isEmpty());
        assertEquals(15.0, config.treasurePercent(), 0.0);
        assertTrue(config.groups().isEmpty());
        assertTrue(config.dropTablesEmpty(), "no fishing.groups section -> fallback mode");
        assertEquals("FISHING", config.fishingSkillId());
        assertEquals(0.005, config.luckPerLevel(), 0.0);
        assertEquals(0.02, config.bonusPerLevel(), 0.0);
    }

    @Test
    void honorsGroupRatioAndGroupsOverride(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                fishing:
                  luck-per-level: 0.01
                  bonus-per-level: 0.03
                  group-ratio:
                    treasure-percent: 25.0
                  groups:
                    treasure:
                      categories:
                        treasure_vanilla:
                          display-name: "Treasure"
                          entries:
                            - item: NAME_TAG
                              weight: 1
                              amount: 1
                    junk:
                      categories:
                        junk_vanilla:
                          display-name: "Junk"
                          entries:
                            - item: BONE
                              weight: 1
                              amount: 1
                """);
        assertEquals("FISHING", config.fishingSkillId());
        assertEquals(0.01, config.luckPerLevel(), 0.0);
        assertEquals(0.03, config.bonusPerLevel(), 0.0);
        assertEquals(25.0, config.treasurePercent(), 0.0);
        assertFalse(config.dropTablesEmpty());
        assertEquals(2, config.groups().size());
        DropTableConfig.Category treasureVanilla = config.groups().get("treasure").get("treasure_vanilla");
        assertEquals("NAME_TAG", treasureVanilla.entries().get(0).item());
        // Fishing group categories carry no individual trigger (the group itself is chosen by ratio).
        assertEquals(0.0, treasureVanilla.triggerChancePercent(), 0.0);
    }

    // ---- 2026-08-15追加: fishing.unlock-groups (機能解放追加テーブル) ----

    @Test
    void unlockGroupsDefaultsToEmptyWhenSectionAbsent(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, "xp-bottle-store:\n  store-amount: 100\n");
        assertTrue(config.unlockGroups().isEmpty());
        assertTrue(config.dropTablesEmpty(), "unlock-groups absent + groups absent -> still fallback mode");
    }

    @Test
    void unlockGroupsParsesLikeGroupsAndEscapesFallbackModeAlone(@TempDir File tempDir) throws IOException {
        // unlock-groupsだけが非空(groupsは空)でもfallback判定を抜けることを確認する
        // (dropTablesEmptyはgroups/unlock-groupsの両方を見る)。
        FishingGimmickConfig config = loaded(tempDir, """
                fishing:
                  unlock-groups:
                    treasure:
                      categories:
                        treasure_unlock:
                          display-name: "Unlock Treasure"
                          entries:
                            - item: DIAMOND
                              weight: 1
                              amount: 1
                """);
        assertTrue(config.groups().isEmpty());
        assertFalse(config.dropTablesEmpty(), "a non-empty unlock-groups alone must exit fallback mode");
        assertEquals(1, config.unlockGroups().size());
        DropTableConfig.Category treasureUnlock = config.unlockGroups().get("treasure").get("treasure_unlock");
        assertEquals("DIAMOND", treasureUnlock.entries().get(0).item());
    }

    @Test
    void fishGroupParsesLikeTreasureAndJunkGroups(@TempDir File tempDir) throws IOException {
        // T1(2026-07-25): fishing.groups.fish(「通常の魚」枠)はtreasure/junkと同じ
        // DropTableConfig.parseCategoriesを通る単なる3つ目のグループとして扱われる。
        FishingGimmickConfig config = loaded(tempDir, """
                fishing:
                  groups:
                    fish:
                      categories:
                        fish_vanilla:
                          display-name: "Fish"
                          entries:
                            - item: COD
                              weight: 60
                              amount: 1
                """);
        assertFalse(config.dropTablesEmpty(), "a non-empty fish group alone must exit fallback mode");
        assertEquals(1, config.groups().size());
        DropTableConfig.Category fishVanilla = config.groups().get("fish").get("fish_vanilla");
        assertEquals("COD", fishVanilla.entries().get(0).item());
        assertEquals(60, fishVanilla.entries().get(0).weight());
    }

    @Test
    void legacyMaterialListsStayAvailableAsFallback(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                junk-materials:
                  - BONE
                  - STRING
                treasure-materials:
                  - NAME_TAG
                """);
        assertEquals(Set.of(Material.BONE, Material.STRING), config.junkMaterials());
        assertEquals(Set.of(Material.NAME_TAG), config.treasureMaterials());
        assertTrue(config.dropTablesEmpty());
    }

    @Test
    void nonFiniteLuckPerLevelFallsBackToDefault(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                fishing:
                  luck-per-level: .nan
                """);
        assertEquals(0.005, config.luckPerLevel(), 0.0);
    }

    @Test
    void treasurePercentIsClampedTo0To100(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                fishing:
                  group-ratio:
                    treasure-percent: 150.0
                """);
        assertEquals(100.0, config.treasurePercent(), 0.0);
    }

    @Test
    void xpBottleReturnRateDefaultsTo1WhenAbsent(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, "xp-bottle-store:\n  store-amount: 100\n");
        assertEquals(1.0, config.xpBottleReturnRate(), 0.0);
    }

    @Test
    void xpBottleReturnRateHonorsExplicitValue(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 0.9
                """);
        assertEquals(0.9, config.xpBottleReturnRate(), 0.0);
    }

    @Test
    void xpBottleReturnRateIsClampedTo0To1(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 1.5
                """);
        assertEquals(1.0, config.xpBottleReturnRate(), 0.0);
    }

    // ---- 2026-07-26 tier-expand: xp-bottle-store.tiers ----

    @Test
    void tieredXpBottleAccessorsFallBackToGlobalScalarWhenTiersUndefined(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 0.9
                """);
        assertEquals(100, config.xpBottleStoreAmount(1));
        assertEquals(100, config.xpBottleStoreAmount(99));
        assertEquals(0.9, config.xpBottleReturnRate(1), 0.0);
    }

    @Test
    void tieredXpBottleAccessorsResolveFloorEntryFromTiersTable(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 1.0
                  tiers:
                    1: { store-amount: 100, return-rate: 0.8 }
                    2: { store-amount: 200, return-rate: 1.0 }
                """);
        assertEquals(100, config.xpBottleStoreAmount(1));
        assertEquals(0.8, config.xpBottleReturnRate(1), 0.0);
        assertEquals(200, config.xpBottleStoreAmount(2));
        assertEquals(1.0, config.xpBottleReturnRate(2), 0.0);
        assertEquals(200, config.xpBottleStoreAmount(99), "floor resolve above the highest defined tier");
    }

    @Test
    void malformedXpBottleTierRowIsSkippedWithoutThrowing(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                xp-bottle-store:
                  store-amount: 100
                  return-rate: 1.0
                  tiers:
                    1: { store-amount: -5, return-rate: 0.8 }
                    2: { store-amount: 200, return-rate: 1.5 }
                    notanumber: { store-amount: 300, return-rate: 0.5 }
                """);
        // All three rows invalid -> table stays empty -> falls back to the global scalar.
        assertEquals(100, config.xpBottleStoreAmount(1));
        assertEquals(1.0, config.xpBottleReturnRate(1), 0.0);
    }

    // ---- T2 (fish-sell): fish-sell.prices / fish-sell.max-sells-per-minute ----

    @Test
    void fishSellDefaultsWhenSectionAbsent(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, "xp-bottle-store:\n  store-amount: 100\n");
        assertTrue(config.fishSellPrices().isEmpty());
        assertTrue(config.fishSellPriceOf(Material.COD).isEmpty());
        assertEquals(20, config.fishSellMaxPerMinute());
    }

    @Test
    void fishSellPricesParsesMaterialToPriceMap(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                fish-sell:
                  prices:
                    COD: 2.0
                    SALMON: 3.5
                  max-sells-per-minute: 5
                """);
        assertEquals(2.0, config.fishSellPriceOf(Material.COD).orElseThrow(), 0.0);
        assertEquals(3.5, config.fishSellPriceOf(Material.SALMON).orElseThrow(), 0.0);
        assertTrue(config.fishSellPriceOf(Material.PUFFERFISH).isEmpty(), "unregistered Material must not be sellable");
        assertEquals(5, config.fishSellMaxPerMinute());
    }

    @Test
    void fishSellPricesSkipsNonPositivePriceButAcceptsNonMaterialTokens(@TempDir File tempDir) throws IOException {
        // T2(2026-07-25経済連携拡張): fish-sell.prices はMaterial限定ではなくなった。Materialとして
        // 解決できないキー(例: カスタムアイテムID)も、価格がfinite/positiveなら受理される
        // (ロード時点ではカタログ/Ars registryを検証しない、判断はFishingGimmickConfig#parseFishSellPrices
        // のjavadoc参照)。
        FishingGimmickConfig config = loaded(tempDir, """
                fish-sell:
                  prices:
                    tf_custom_fish: 5.0
                    COD: 0.0
                    SALMON: -1.0
                    PUFFERFISH: 4.0
                """);
        assertEquals(5.0, config.fishSellPriceOf("tf_custom_fish").orElseThrow(), 0.0);
        assertTrue(config.fishSellPriceOf(Material.COD).isEmpty());
        assertTrue(config.fishSellPriceOf(Material.SALMON).isEmpty());
        assertEquals(4.0, config.fishSellPriceOf(Material.PUFFERFISH).orElseThrow(), 0.0);
    }

    @Test
    void fishSellPriceOfStringLooksUpCustomIdTokenExactly(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                fish-sell:
                  prices:
                    tf_golden_koi: 12.5
                """);
        assertEquals(12.5, config.fishSellPriceOf("tf_golden_koi").orElseThrow(), 0.0);
        assertTrue(config.fishSellPriceOf("TF_GOLDEN_KOI").isEmpty(), "token lookup is exact-case, not folded");
        assertTrue(config.fishSellPriceOf((String) null).isEmpty());
        assertTrue(config.fishSellPriceOf("").isEmpty());
    }

    @Test
    void fishSellMaxPerMinuteFallsBackToDefaultWhenNonPositive(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                fish-sell:
                  max-sells-per-minute: 0
                """);
        assertEquals(20, config.fishSellMaxPerMinute());
    }

    // ---- T4 (ocean-biomes) ----

    @Test
    void oceanBiomeKeysDefaultToVanillaOceanFamilyWhenAbsent(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, "xp-bottle-store:\n  store-amount: 100\n");
        assertTrue(config.oceanBiomeKeys().contains("ocean"));
        assertTrue(config.oceanBiomeKeys().contains("deep_ocean"));
        assertTrue(config.oceanBiomeKeys().contains("frozen_ocean"));
        assertTrue(config.isOceanBiome(Biome.OCEAN));
        assertFalse(config.isOceanBiome(Biome.PLAINS));
        assertFalse(config.isOceanBiome(null));
    }

    @Test
    void oceanBiomeKeysHonorsExplicitOverride(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                fishing:
                  ocean-biomes:
                    - ocean
                """);
        assertEquals(Set.of("ocean"), config.oceanBiomeKeys());
        assertTrue(config.isOceanBiome(Biome.OCEAN));
        assertFalse(config.isOceanBiome(Biome.DEEP_OCEAN), "deep_ocean was intentionally excluded by the override");
    }

    @Test
    void oceanBiomeKeysAllowsExplicitEmptyOverrideToDisableTheBonus(@TempDir File tempDir) throws IOException {
        FishingGimmickConfig config = loaded(tempDir, """
                fishing:
                  ocean-biomes: []
                """);
        assertTrue(config.oceanBiomeKeys().isEmpty());
        assertFalse(config.isOceanBiome(Biome.OCEAN));
    }

    @Test
    void bundledDropTablesDoNotCreateMetadataLessBooksOrPotions(@TempDir File tempDir) throws IOException {
        String bundled;
        try (var input = FishingGimmickConfigTest.class.getResourceAsStream("/stats/fishing-gimmick.yml")) {
            if (input == null) {
                throw new AssertionError("missing bundled stats/fishing-gimmick.yml");
            }
            bundled = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        FishingGimmickConfig config = loaded(tempDir, bundled);

        List<String> itemIds = config.groups().values().stream()
                .flatMap(categories -> categories.values().stream())
                .flatMap(category -> category.entries().stream())
                .map(DropTableConfig.Entry::item)
                .toList();

        assertFalse(itemIds.contains("ENCHANTED_BOOK"),
                "a bare material fallback creates an enchanted book with no stored enchantments");
        assertFalse(itemIds.contains("POTION"),
                "a bare material fallback creates an ordinary water bottle with no potion effect");
    }
}
