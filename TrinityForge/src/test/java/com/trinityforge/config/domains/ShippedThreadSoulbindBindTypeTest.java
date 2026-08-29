package com.trinityforge.config.domains;

import com.trinityforge.pdc.BindType;
import com.trinityforge.stats.ItemTemplate;
import com.trinityforge.stats.RecipeSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * スレッド魂縛は catalog の {@code bind-type} が正。CMD 帯のハードコードではない。
 *
 * <p>作業台 / インベントリクラフト / 儀式で作れるスレッドは {@code TRADEABLE}。
 * レシピの無いスレッド（宝箱・敵ドロップ・ガチャ等）は {@code SOULBOUND}。
 */
class ShippedThreadSoulbindBindTypeTest {

    private static final Logger LOG = Logger.getLogger("ShippedThreadSoulbindBindTypeTest");
    private static final String CATALOG = "src/main/resources/items/catalog.yml";
    private static final Set<RecipeSpec.Method> CREATABLE = Set.of(
            RecipeSpec.Method.WORKBENCH,
            RecipeSpec.Method.INVENTORY,
            RecipeSpec.Method.RITUAL);

    private static ItemCatalogConfig.ParseResult parseShipped() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(CATALOG);
        assertTrue(java.nio.file.Files.isRegularFile(path),
                "出荷カタログが見つからない: " + path.toAbsolutePath());
        org.bukkit.configuration.file.YamlConfiguration cfg =
                new org.bukkit.configuration.file.YamlConfiguration();
        cfg.loadFromString(java.nio.file.Files.readString(path));
        var items = cfg.getConfigurationSection("items");
        assertNotNull(items, CATALOG + " に items: 節が無い");
        return ItemCatalogConfig.parse(items, LOG);
    }

    private static boolean isCraftOrRitual(ItemTemplate template) {
        return template.recipes().stream().anyMatch(recipe -> CREATABLE.contains(recipe.method()));
    }

    @Test
    @DisplayName("作業台/儀式で作れるスレッドは TRADEABLE、それ以外は SOULBOUND")
    void craftableThreadsAreTradeableOthersSoulbound() throws Exception {
        var templates = parseShipped().templates();
        List<String> threads = templates.keySet().stream()
                .filter(id -> id.startsWith("thread_"))
                .sorted()
                .toList();
        assertTrue(threads.size() >= 40, "出荷スレッドが少なすぎる: " + threads.size());

        List<String> mismatches = new ArrayList<>();
        int tradeable = 0;
        int soulbound = 0;
        for (String id : threads) {
            ItemTemplate template = templates.get(id);
            BindType expected = isCraftOrRitual(template) ? BindType.TRADEABLE : BindType.SOULBOUND;
            if (expected == BindType.TRADEABLE) {
                tradeable++;
            } else {
                soulbound++;
            }
            if (template.bindType() != expected) {
                mismatches.add(id + " recipes=" + template.recipes().stream()
                        .map(r -> r.method().name().toLowerCase(Locale.ROOT))
                        .toList()
                        + " bind-type=" + template.bindType()
                        + " expected=" + expected);
            }
        }
        assertTrue(tradeable >= 1, "儀式/作業台スレッドが 0 件。parser が recipe を落としている");
        assertTrue(soulbound >= 1, "SOULBOUND スレッドが 0 件");
        assertEquals(List.of(), mismatches,
                "クラフト/儀式できるスレッドは TRADEABLE、レシピ無しは SOULBOUND: " + mismatches);
    }
}
