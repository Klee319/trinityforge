package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
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
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code progression/crafting-features.yml} に、行き止まり素材3種の醸造出口が
 * <b>無いこと</b>を固定する（W-233 / 2026-08-25 決定）。
 *
 * <p>対象は {@code guardian_spine} / {@code husk_cloth} / {@code pillager_plate}。
 * この3種は {@code combat/mob-level-table.yml} の {@code add-drops} で入手経路を持ち、
 * {@code items/catalog.yml} のクラフト（防具・装備）で消費先も持っているが、
 * <b>醸造での消費先は無い</b>。ユーザー判断は「出口は作らない、行き止まりのまま許容する」で、
 * この状態を維持するのが仕様。
 *
 * <p>旧版のこのテストは異なる素材（{@code pillager_plate} / {@code piglin_ear} /
 * {@code skeleton_horse_bone}）と存在しないグループ（{@code survivor-brew} 等）を前提にしており、
 * 出荷 yml の実態と合っていなかった（赤いまま放置されていた）。実物（{@code brew-unlocks} の全内容）
 * で裏取りした結果、この3種はどのグループにも登場しないことを確認済み。
 */
class ShippedBrewDeadEndMaterialTest {

    /** 行き止まりのまま許容する素材3種（W-233）。 */
    private static final List<String> DEAD_END_INGREDIENTS =
            List.of("custom:guardian_spine", "custom:husk_cloth", "custom:pillager_plate");

    private CraftingFeaturesConfig config;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws IOException {
        MockBukkit.mock();
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = ShippedBrewDeadEndMaterialTest.class.getClassLoader()
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
    @DisplayName("行き止まり素材3種は、どの醸造グループの ingredient にも登場しない(意図した仕様)")
    void deadEndMaterialsHaveNoBrewingOutlet() {
        Map<String, BrewUnlockGroup> groups = config.brewUnlocks();
        assertFalse(groups.isEmpty(), "brew-unlocks が1件も読めていない");

        for (String ingredient : DEAD_END_INGREDIENTS) {
            List<String> hits = groups.values().stream()
                    .flatMap(g -> g.potions().stream())
                    .map(BrewPotionSpec::ingredient)
                    .filter(ingredient::equals)
                    .toList();
            assertTrue(hits.isEmpty(),
                    "'" + ingredient + "' は行き止まりのまま許容する仕様(W-233)だが、"
                            + "醸造グループに出現している。出口を作ったなら、この固定テストごと更新すること。");
        }
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedBrewDeadEndMaterialTest");
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
}
