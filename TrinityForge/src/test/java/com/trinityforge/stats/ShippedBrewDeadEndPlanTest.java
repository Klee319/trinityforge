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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行き止まり素材3種（柱4）の醸造が、<b>実際に PotionMix の登録対象まで到達する</b>ことを固定する
 * （2026-08-02 追加）。
 *
 * <p><b>なぜ別テストが要るか</b>: {@code ShippedBrewDeadEndMaterialTest} は
 * {@code CraftingFeaturesConfig} のパース結果までしか見ておらず、
 * 「{@code brew-unlocks} のグループに居る」ことしか固定していない。
 * しかし {@link BrewPotionMixRegistrar#plan} は
 * <b>未知素材・バニラのレシピと衝突するベース・同じ(ベース,素材)ペアの重複</b>を
 * <b>WARNING を出すだけで黙って落とす</b>。つまり yml に書いてあっても登録されないことがあり、
 * その場合は「行き止まりを解消した」つもりで<b>何も解消していない</b>状態になる。
 *
 * <p>{@code plan} は package-private なのでこのテストは {@code com.trinityforge.stats} に置く
 * （呼べるようにするためだけに可視性を広げない）。
 */
class ShippedBrewDeadEndPlanTest {

    /** 素材ID -&gt; 醸造グループ。プラン柱4の表がそのまま出典。 */
    private static final List<String[]> EXPECTED = List.of(
            new String[] {"custom:pillager_plate", "survivor-brew"},
            new String[] {"custom:piglin_ear", "hunter-hex"},
            new String[] {"custom:skeleton_horse_bone", "apex-brew"});

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
    @DisplayName("行き止まり素材3種が plan() を通って実際に登録対象になる(黙って落とされない)")
    void deadEndMaterialsSurvivePlanning() {
        Logger log = Logger.getLogger(ShippedBrewDeadEndPlanTest.class.getName());
        List<BrewPotionMixRegistrar.MixPlan> plans =
                BrewPotionMixRegistrar.plan(config.brewUnlocks(), log);
        assertFalse(plans.isEmpty(), "plan() が1件も返していない");

        for (String[] expected : EXPECTED) {
            String ingredient = expected[0];
            String groupId = expected[1];

            List<BrewPotionMixRegistrar.MixPlan> hits = plans.stream()
                    .filter(mp -> groupId.equals(mp.groupId()))
                    .filter(mp -> ingredient.equals(mp.spec().ingredient()))
                    .toList();

            assertEquals(1, hits.size(),
                    "'" + ingredient + "' が plan() の結果に居ない。"
                            + "yml には書いてあるのに登録されない = 行き止まりのまま。"
                            + "plan() が落とす理由は「未知素材」「バニラのレシピと衝突するベース」"
                            + "「同じ(ベース,素材)ペアの重複」の3つで、いずれも WARNING しか出ない。"
                            + "現在 plan() が通した " + groupId + " の素材: "
                            + plans.stream().filter(mp -> groupId.equals(mp.groupId()))
                                    .map(mp -> mp.spec().ingredient()).toList());
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
