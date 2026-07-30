package com.trinityforge.config.domains;

import com.trinityforge.pdc.ItemData;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
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
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FoodGimmickConfig} のデフォルト/明示上書き/無効値ガード/不正Material・PotionEffectTypeの
 * スキップを検証する。他のconfigローダーテストと同じ、リフレクションで作る偽{@link Plugin}パターン
 * ({@link FarmingGimmickConfigTest}参照)。
 */
class FoodGimmickConfigTest {

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
            case "getLogger" -> Logger.getLogger("FoodGimmickConfigTest");
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

    private static FoodGimmickConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, FoodGimmickConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        FoodGimmickConfig config = new FoodGimmickConfig();
        config.load(fakePlugin(tempDir));
        return config;
    }

    @Test
    void defaultsWhenSectionsAbsent(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, "junk-food-materials: []\n");
        assertTrue(config.junkFoodMaterials().isEmpty());
        assertTrue(config.junkfoodImmunityCancelledEffects().isEmpty());
        assertEquals(2.0, config.junkfoodInversionJunkSaturationBonus(), 0.0);
        assertEquals(1.0, config.junkfoodInversionNonJunkSaturationPenalty(), 0.0);
        assertEquals(4.0, config.satietyBuffSaturationBonus(), 0.0);
    }

    @Test
    void honorsExplicitOverrides(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                junk-food-materials:
                  - ROTTEN_FLESH
                  - SPIDER_EYE
                junkfood-immunity:
                  cancelled-debuff-effects:
                    - HUNGER
                    - POISON
                junkfood-inversion:
                  junk-saturation-bonus: 3.5
                  non-junk-saturation-penalty: 2.5
                satiety-buff:
                  saturation-bonus: 6.0
                """);
        assertEquals(Set.of(Material.ROTTEN_FLESH, Material.SPIDER_EYE), config.junkFoodMaterials());
        assertEquals(Set.of(PotionEffectType.HUNGER, PotionEffectType.POISON),
                config.junkfoodImmunityCancelledEffects());
        assertEquals(3.5, config.junkfoodInversionJunkSaturationBonus(), 0.0);
        assertEquals(2.5, config.junkfoodInversionNonJunkSaturationPenalty(), 0.0);
        assertEquals(6.0, config.satietyBuffSaturationBonus(), 0.0);
    }

    @Test
    void invalidMaterialAndEffectNamesAreSkippedNotThrown(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                junk-food-materials:
                  - ROTTEN_FLESH
                  - NOT_A_REAL_MATERIAL
                junkfood-immunity:
                  cancelled-debuff-effects:
                    - HUNGER
                    - NOT_A_REAL_EFFECT
                """);
        assertEquals(Set.of(Material.ROTTEN_FLESH), config.junkFoodMaterials());
        assertEquals(Set.of(PotionEffectType.HUNGER), config.junkfoodImmunityCancelledEffects());
    }

    @Test
    void negativeOrNonFiniteSaturationValuesFallBackToDefault(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                junkfood-inversion:
                  junk-saturation-bonus: -1.0
                  non-junk-saturation-penalty: .NaN
                satiety-buff:
                  saturation-bonus: -2.0
                """);
        assertEquals(2.0, config.junkfoodInversionJunkSaturationBonus(), 0.0);
        assertEquals(1.0, config.junkfoodInversionNonJunkSaturationPenalty(), 0.0);
        assertEquals(4.0, config.satietyBuffSaturationBonus(), 0.0);
        assertFalse(config.junkFoodMaterials().contains(Material.AIR));
    }

    @Test
    void customFoodsParsesTheTwoSeededEntries(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                custom-foods:
                  compressed_bread_1x:
                    food-level: 20
                    saturation: 18.0
                  compressed_cooked_beef_1x:
                    food-level: 20
                    saturation: 20.0
                """);
        assertEquals(2, config.customFoods().size());
        FoodGimmickConfig.CustomFood bread = config.customFood("compressed_bread_1x").orElseThrow();
        assertEquals(20, bread.foodLevel());
        assertEquals(18.0, bread.saturation(), 0.0);
        FoodGimmickConfig.CustomFood beef = config.customFood("compressed_cooked_beef_1x").orElseThrow();
        assertEquals(20, beef.foodLevel());
        assertEquals(20.0, beef.saturation(), 0.0);
        assertTrue(config.customFood("not-configured").isEmpty());
    }

    @Test
    void customFoodOutOfRangeFoodLevelIsClamped(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                custom-foods:
                  over-cap:
                    food-level: 999
                    saturation: 5.0
                """);
        FoodGimmickConfig.CustomFood overCap = config.customFood("over-cap").orElseThrow();
        assertEquals(20, overCap.foodLevel());
        assertEquals(5.0, overCap.saturation(), 0.0);
    }

    // --- 2026-07-27 カスタムアイテムのゴミ食対応 ------------------------------------------------

    private static ItemStack stampedCatalogItem(Material material, String catalogId) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        ItemData.of(meta).setCatalogId(catalogId);
        stack.setItemMeta(meta);
        return stack;
    }

    @Test
    void customPrefixTokenParsesAsCatalogIdNotMaterial(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                junk-food-materials:
                  - ROTTEN_FLESH
                  - custom:tf_rotten_ration
                """);
        assertEquals(Set.of(Material.ROTTEN_FLESH), config.junkFoodMaterials());
        assertEquals(Set.of("tf_rotten_ration"), config.junkFoodCatalogIds());
    }

    @Test
    void isJunkFoodMatchesCustomCatalogIdViaPdcStamp(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                junk-food-materials:
                  - ROTTEN_FLESH
                  - custom:tf_rotten_ration
                """);
        ItemStack customJunk = stampedCatalogItem(Material.BREAD, "tf_rotten_ration");
        assertTrue(config.isJunkFood(customJunk));

        // Same base Material as a legitimate non-junk custom item -> must NOT match by Material fallback.
        ItemStack customNonJunk = stampedCatalogItem(Material.BREAD, "tf_fresh_bread");
        assertFalse(config.isJunkFood(customNonJunk));
    }

    @Test
    void isJunkFoodMatchesPlainVanillaMaterial(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                junk-food-materials:
                  - ROTTEN_FLESH
                """);
        assertTrue(config.isJunkFood(new ItemStack(Material.ROTTEN_FLESH)));
        assertFalse(config.isJunkFood(new ItemStack(Material.COOKED_BEEF)));
        assertFalse(config.isJunkFood(null));
    }
}
