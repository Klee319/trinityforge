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

    // --- 2026-08-09新設: unregistered-custom-food-ban(「81倍は食用にしない」) -------------------------

    // 2026-08-09補足反映: 判定基準は「custom-foodsへの登録の有無」そのもの。個別ID除外は無い
    // (tf_crystal_appleもcustom-foods側に満腹度を登録して解決する)。excluded-materialsだけが
    // ユーザー指定の明示除外(グロウベリー系)として残る。
    private static final String UNREGISTERED_BAN_YAML = """
            custom-foods:
              compressed_bread_1x:
                food-level: 20
                saturation: 18.0
              tf_crystal_apple:
                food-level: 4
                saturation: 9.6
            unregistered-custom-food-ban:
              enabled: true
              excluded-materials:
                - GLOW_BERRIES
              message: "このアイテムは食料として登録されていません"
            """;

    @Test
    void unregisteredCustomIdIsBanned(@TempDir File tempDir) throws IOException {
        // apple_2x相当(カタログ/materials定義済み+custom-foodsに満腹度設定が無い) = 素材として扱い禁止。
        FoodGimmickConfig config = loaded(tempDir, UNREGISTERED_BAN_YAML);
        ItemStack apple2x = stampedCatalogItem(Material.APPLE, "apple_2x");
        assertTrue(config.isBannedUnregisteredCustomFood(apple2x));
    }

    @Test
    void registeredCustomFoodIsNotBanned(@TempDir File tempDir) throws IOException {
        // compressed_bread_1x相当(カタログ/materials定義済み+custom-foodsに満腹度設定済み) = 食料として食べられる。
        FoodGimmickConfig config = loaded(tempDir, UNREGISTERED_BAN_YAML);
        ItemStack compressedBread = stampedCatalogItem(Material.BREAD, "compressed_bread_1x");
        assertFalse(config.isBannedUnregisteredCustomFood(compressedBread));
    }

    @Test
    void plainVanillaFoodWithNoCustomIdIsNotBanned(@TempDir File tempDir) throws IOException {
        // カスタムID(カタログ/materials定義)を持たない素のバニラAPPLEは対象外(今まで通り食べられる)。
        FoodGimmickConfig config = loaded(tempDir, UNREGISTERED_BAN_YAML);
        assertFalse(config.isBannedUnregisteredCustomFood(new ItemStack(Material.APPLE)));
    }

    @Test
    void glowBerriesBasedCustomFoodIsExcludedByMaterial(@TempDir File tempDir) throws IOException {
        // glow_berries_2x相当: ベースMaterialがGLOW_BERRIESの明示除外に載っているため禁止しない
        // (custom-foodsに未登録でも、ユーザー指定の例外として食べられる)。
        FoodGimmickConfig config = loaded(tempDir, UNREGISTERED_BAN_YAML);
        ItemStack glowBerries2x = stampedCatalogItem(Material.GLOW_BERRIES, "glow_berries_2x");
        assertFalse(config.isBannedUnregisteredCustomFood(glowBerries2x));
    }

    @Test
    void tfCrystalAppleIsEdibleBecauseItIsRegisteredInCustomFoods(@TempDir File tempDir) throws IOException {
        // tf_crystal_apple: ハードコードのID除外ではなく、custom-foodsへの登録(満腹度4/隠し満腹度9.6、
        // バニラのエンチャント金リンゴ相当)で「食料として正しい」を表現する(2026-08-09補足反映)。
        FoodGimmickConfig config = loaded(tempDir, UNREGISTERED_BAN_YAML);
        ItemStack crystalApple = stampedCatalogItem(Material.ENCHANTED_GOLDEN_APPLE, "tf_crystal_apple");
        assertFalse(config.isBannedUnregisteredCustomFood(crystalApple));
        assertEquals(4, config.customFood("tf_crystal_apple").orElseThrow().foodLevel());
        assertEquals(9.6, config.customFood("tf_crystal_apple").orElseThrow().saturation(), 0.0);
    }

    @Test
    void unregisteredCustomFoodBanCanBeDisabled(@TempDir File tempDir) throws IOException {
        FoodGimmickConfig config = loaded(tempDir, """
                unregistered-custom-food-ban:
                  enabled: false
                """);
        ItemStack apple2x = stampedCatalogItem(Material.APPLE, "apple_2x");
        assertFalse(config.isBannedUnregisteredCustomFood(apple2x));
    }

    @Test
    void unregisteredCustomFoodBanDefaultsToEnabledWithGlowBerriesExcludedAndDefaultMessage(
            @TempDir File tempDir) throws IOException {
        // unregistered-custom-food-banセクション自体が省略されたときの既定値: 有効・
        // GLOW_BERRIESが既定除外・既定文言。
        FoodGimmickConfig config = loaded(tempDir, "junk-food-materials: []\n");
        assertTrue(config.unregisteredCustomFoodBanEnabled());
        ItemStack apple2x = stampedCatalogItem(Material.APPLE, "apple_2x");
        assertTrue(config.isBannedUnregisteredCustomFood(apple2x));
        ItemStack glowBerries = stampedCatalogItem(Material.GLOW_BERRIES, "glow_berries_2x");
        assertFalse(config.isBannedUnregisteredCustomFood(glowBerries));
        assertEquals("このアイテムは食料として登録されていません", config.unregisteredCustomFoodBanMessage());
    }
}
