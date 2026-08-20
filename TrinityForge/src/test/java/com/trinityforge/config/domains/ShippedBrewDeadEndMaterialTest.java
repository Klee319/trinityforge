package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewPotionSpec;
import com.trinityforge.config.domains.CraftingFeaturesConfig.BrewUnlockGroup;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出荷 {@code progression/crafting-features.yml} の醸造に、行き止まり素材3種の出口があることを固定する
 * (2026-08-01 追加コンテンツプラン 柱4)。
 *
 * <p>対象は {@code pillager_plate} / {@code piglin_ear} / {@code skeleton_horse_bone}。
 * この3種は {@code combat/mob-level-table.yml} の {@code add-drops} で入手経路だけは持っているのに、
 * 消費先が(フォーク側の)コア素材レシピ1本しかなく、醸造には1件も登場していなかった。
 *
 * <p><b>{@code custom:} 接頭辞まで見ているのが要点。</b>{@code ingredient} は
 * {@code CrossPluginItemResolver.idOf} で照合されるため、素の {@code pillager_plate} と書くと
 * バニラ {@code Material} 名として解釈されて一致せず、<b>警告も出ないまま永久に醸造できない</b>
 * (パーサは type だけを検証し ingredient は素通しする)。テストで綴りごと固定するしかない。
 *
 * <p>ArsPaper の {@code materials.yml} は {@code .gitignore} 除外でこのリポジトリに存在しないため、
 * 「そのIDが実在するか」までは検証できない。ここで固定するのは<b>書き方と、出口があること</b>。
 */
class ShippedBrewDeadEndMaterialTest {

    /** 素材ID -> (醸造グループ, 期待する効果)。プランの柱4の表がそのまま出典。 */
    private static final List<String[]> EXPECTED = List.of(
            new String[] {"custom:pillager_plate", "survivor-brew", "FIRE_RESISTANCE"},
            new String[] {"custom:piglin_ear", "hunter-hex", "NAUSEA"},
            new String[] {"custom:skeleton_horse_bone", "apex-brew", "SPEED"});

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
    @DisplayName("行き止まり素材3種が、それぞれ指定の醸造グループに custom: 付きで居る")
    void deadEndMaterialsHaveABrewingOutlet() {
        Map<String, BrewUnlockGroup> groups = config.brewUnlocks();
        assertFalse(groups.isEmpty(), "brew-unlocks が1件も読めていない");

        for (String[] expected : EXPECTED) {
            String ingredient = expected[0];
            String groupId = expected[1];
            String effect = expected[2];

            BrewUnlockGroup group = groups.get(groupId);
            assertNotNull(group, "醸造グループ '" + groupId + "' が無い");

            List<BrewPotionSpec> hits = group.potions().stream()
                    .filter(p -> ingredient.equals(p.ingredient()))
                    .toList();
            assertEquals(1, hits.size(),
                    "'" + groupId + "' に ingredient '" + ingredient + "' がちょうど1件あること"
                            + "(0件なら行き止まりのまま。素の綴りで書くとバニラMaterial扱いになり"
                            + "無言で一致しなくなる)。現在の ingredient 一覧: "
                            + group.potions().stream().map(BrewPotionSpec::ingredient).toList());

            BrewPotionSpec spec = hits.get(0);
            PotionEffectType type = CraftingFeaturesConfig.resolvePotionEffectType(effect);
            assertNotNull(type, "期待効果 '" + effect + "' が PotionEffectType として解決できない");
            assertEquals(type, spec.type(),
                    ingredient + " の効果はプラン柱4の指定どおり " + effect + " であること");
            assertTrue(spec.durationTicks() > 0, ingredient + " の duration が正でない");
        }
    }

    /**
     * 追加分が既存の醸造を潰していないことの確認。バニラが出発点にしないベース({@code THICK})に
     * 揃えておかないと、{@code customMixes} がバニラより先に評価されて<b>バニラのレシピを奪う</b>。
     */
    @Test
    @DisplayName("追加した3件のベースは THICK(バニラのレシピを奪わない)")
    void addedBrewsUseTheSafeBase() {
        Map<String, BrewUnlockGroup> groups = config.brewUnlocks();
        for (String[] expected : EXPECTED) {
            BrewPotionSpec spec = groups.get(expected[1]).potions().stream()
                    .filter(p -> expected[0].equals(p.ingredient()))
                    .findFirst().orElseThrow();
            assertEquals("THICK", spec.base(),
                    expected[0] + " のベースは THICK にすること"
                            + "(バニラが出発点にするベースだと、そのバニラレシピを潰す)");
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
