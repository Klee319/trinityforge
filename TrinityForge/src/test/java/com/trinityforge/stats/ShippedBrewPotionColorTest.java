package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code BrewRecipeSupport#customPotion} が組み立てるポーションに、必ず色が焼き込まれることを
 * 出荷 {@code progression/crafting-features.yml} の {@code brew-unlocks} 全件を通して固定する
 * (2026-08-25)。
 *
 * <h2>なぜ必要か</h2>
 * TF は段階の異なる効果を一意に確定させるため、醸造結果の base を常に {@code WATER} へ倒して
 * 全て custom effects で表現する。Java 版クライアントは custom effects から色を都度計算して
 * 表示するため気づかれないが、<b>Geyser(統合版)は {@code PotionContents} の base から色を引く</b>
 * ため、{@link PotionMeta#setColor} を焼いていないと色が無い＝水入り瓶として描画される。
 *
 * <p>この回帰テストは、その原因箇所({@code setColor} を焼き忘れる)が再発したら
 * {@code getColor() == null} で確実に落ちる形にしてある。
 */
class ShippedBrewPotionColorTest {

    private CraftingFeaturesConfig config;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws IOException {
        MockBukkit.mock();
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = ShippedBrewPotionColorTest.class.getClassLoader()
                .getResourceAsStream(CraftingFeaturesConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + CraftingFeaturesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        CraftingFeaturesConfig loaded = new CraftingFeaturesConfig();
        assertTrue(loaded.load(fakePlugin(tempDir)), "出荷 crafting-features.yml がパースできない");
        this.config = loaded;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("出荷ymlの醸造カスタムポーションは、素材ごとに全部setColorされ水入り瓶に見えない")
    void everyShippedCustomPotionHasAColor() {
        Map<String, BrewUnlockGroup> groups = config.brewUnlocks();
        assertFalse(groups.isEmpty(), "brew-unlocks が1件も読めていない");

        int checked = 0;
        for (Map.Entry<String, BrewUnlockGroup> entry : groups.entrySet()) {
            for (BrewPotionSpec spec : entry.getValue().potions()) {
                ItemStack potion = BrewRecipeSupport.customPotion(Material.POTION, spec);
                assertTrue(potion.getItemMeta() instanceof PotionMeta, "PotionMeta が組み立てられていない");
                PotionMeta meta = (PotionMeta) potion.getItemMeta();
                assertNotNull(meta.getColor(),
                        "グループ '" + entry.getKey() + "' の素材 '" + spec.ingredient() + "'("
                                + spec.type() + ") が setColor されていない。"
                                + "統合版でこのポーションだけ水入り瓶に見える。");
                checked++;
            }
        }
        assertTrue(checked > 0, "検査対象の醸造spec が1件も無い(brew-unlocksの読み方が変わっていないか確認)");
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedBrewPotionColorTest");
            case "saveResource" -> throw new AssertionError(
                    "saveResource() must not be called when the file already exists on disk");
            case "toString" -> "FakePlugin";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class}, handler);
    }
}
