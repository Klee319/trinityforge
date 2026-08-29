package com.trinityforge.stats;

import com.trinityforge.config.domains.CraftingFeaturesConfig;
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
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行き止まり素材3種（{@code guardian_spine} / {@code husk_cloth} / {@code pillager_plate}、W-233）が
 * {@link BrewPotionMixRegistrar#plan} を通っても登録対象に<b>現れないこと</b>を固定する
 * （2026-08-25 現仕様へ書き直し）。
 *
 * <p>そもそも {@code crafting-features.yml} の {@code brew-unlocks} にこの3種が ingredient として
 * 1件も存在しないので、{@code plan()} の出力にも現れないのが期待挙動。ユーザー判断は
 * 「出口は作らない、行き止まりのまま許容する」なので、これは<b>将来出口ができたら落ちる逆向きの
 * 検査ではなく</b>、現状（yml に無い→ plan() にも無い）をそのまま固定するテスト。
 *
 * <p>旧版はこのテストを「出口があるはず」の向きで書いており、存在しない素材/グループの組で
 * 恒久的に赤くなっていた。
 *
 * <p>{@code plan} は package-private なのでこのテストは {@code com.trinityforge.stats} に置く
 * （呼べるようにするためだけに可視性を広げない）。
 */
class ShippedBrewDeadEndPlanTest {

    private static final List<String> DEAD_END_INGREDIENTS =
            List.of("custom:guardian_spine", "custom:husk_cloth", "custom:pillager_plate");

    private CraftingFeaturesConfig config;

    @BeforeEach
    void setUp(@TempDir File tempDir) throws IOException {
        MockBukkit.mock();
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = ShippedBrewDeadEndPlanTest.class.getClassLoader()
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
    @DisplayName("行き止まり素材3種は plan() の結果にも現れない(行き止まりを許容する現仕様の固定)")
    void deadEndMaterialsStayAbsentFromPlan() {
        Logger log = Logger.getLogger(ShippedBrewDeadEndPlanTest.class.getName());
        List<BrewPotionMixRegistrar.MixPlan> plans =
                BrewPotionMixRegistrar.plan(config.brewUnlocks(), log);
        assertFalse(plans.isEmpty(), "plan() が1件も返していない(他の醸造グループまで巻き添えで壊れている)");

        for (String ingredient : DEAD_END_INGREDIENTS) {
            List<BrewPotionMixRegistrar.MixPlan> hits = plans.stream()
                    .filter(mp -> ingredient.equals(mp.spec().ingredient()))
                    .toList();
            assertTrue(hits.isEmpty(),
                    "'" + ingredient + "' は行き止まりのまま許容する仕様(W-233)だが、"
                            + "plan() の結果に出現している。出口を作ったなら、この固定テストごと更新すること。");
        }
    }

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("ShippedBrewDeadEndPlanTest");
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
