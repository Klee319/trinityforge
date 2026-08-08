package com.trinityforge.config.domains;

import com.trinityforge.config.domains.CraftingFeaturesConfig.WoodRepairMaterial;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CraftingFeaturesConfig} の {@code wood-repair.materials.<id>.quick-repair} パース検証:
 * 明示trueの尊重、キー省略時のfalseデフォルト、int-shorthand({@code materials: { id: 200 }})でも
 * quickRepair=falseになること。
 */
class CraftingFeaturesConfigWoodRepairTest {

    private static Plugin fakePlugin(File dataFolder) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> dataFolder;
            case "getLogger" -> Logger.getLogger("CraftingFeaturesConfigWoodRepairTest");
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

    private static CraftingFeaturesConfig loaded(File tempDir, String yaml) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        Files.writeString(file.toPath(), yaml);
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "config must parse cleanly");
        return config;
    }

    @Test
    void quickRepairTrueIsParsedForTheSeededEntry(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                wood-repair:
                  materials:
                    compressed_wood_1x:
                      durability: 200
                      quick-repair: true
                """);
        WoodRepairMaterial mat = config.woodRepairMaterial("compressed_wood_1x");
        assertEquals(200, mat.durability());
        assertTrue(mat.quickRepair());
    }

    @Test
    void materialWithoutQuickRepairKeyDefaultsToFalse(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                wood-repair:
                  materials:
                    oak_plank_bundle:
                      durability: 50
                """);
        WoodRepairMaterial mat = config.woodRepairMaterial("oak_plank_bundle");
        assertEquals(50, mat.durability());
        assertFalse(mat.quickRepair());
    }

    @Test
    void intShorthandYieldsQuickRepairFalse(@TempDir File tempDir) throws IOException {
        CraftingFeaturesConfig config = loaded(tempDir, """
                wood-repair:
                  materials:
                    compressed_wood_1x: 200
                """);
        WoodRepairMaterial mat = config.woodRepairMaterial("compressed_wood_1x");
        assertEquals(200, mat.durability());
        assertFalse(mat.quickRepair());
    }

    /**
     * 2026-08-01: 出荷 yml が実在しない素材IDを指していないことの回帰ガード。
     *
     * <p>出荷値は 2026-08-01 まで {@code compressed_wood_1x} だったが、この綴りは
     * <b>TF の items/catalog.yml にも ArsPaper の materials.yml にも存在しない</b>
     * (実在するのは {@code oak_wood_1x})。{@link com.trinityforge.listeners.WoodRepairListener} は
     * 素材IDが表に無ければ黙って return するだけなので、木材修繕は<b>エラーも警告も出さずに
     * 一度も発動しなかった</b>。素材IDの typo は原理的にこの形でしか現れないため、
     * 「出荷 yml が実在しないIDへ戻っていないこと」をここで固定する。
     *
     * <p>ArsPaper の materials.yml は {@code .gitignore} 除外でこのリポジトリに存在せず、
     * テストから実在確認はできない。そのため<b>既知の壊れ値そのものを禁じる</b>形にしている
     * (ID を変えるときは Ars の materials.yml を実際に見てからこの期待値も更新すること)。
     */
    @Test
    void shippedWoodRepairMaterialIdIsTheRealArsMaterialId(@TempDir File tempDir) throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = CraftingFeaturesConfigWoodRepairTest.class.getClassLoader()
                .getResourceAsStream(CraftingFeaturesConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + CraftingFeaturesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "出荷 crafting-features.yml がパースできない");

        Set<String> ids = config.woodRepairMaterials().keySet();
        assertFalse(ids.isEmpty(), "出荷 yml から wood-repair.materials が消えている(木材修繕が丸ごと無効になる)");
        assertFalse(ids.contains("compressed_wood_1x"),
                "出荷 wood-repair.materials が実在しないID 'compressed_wood_1x' を指している。"
                        + "この綴りは TF catalog.yml にも ArsPaper materials.yml にも無いので、"
                        + "WoodRepairListener は無言で return し木材修繕が永久に発動しない"
                        + "(実在するのは 'oak_wood_1x')。現在の値: " + ids);
        assertTrue(ids.contains("oak_wood_1x"),
                "出荷 wood-repair.materials に oak_wood_1x が無い。現在の値: " + ids);
    }

    /**
     * C-2(2026-08-08): 圧縮木材修繕は ArsPaper materials.yml の27種(9樹種 × 1x/2x/3x)すべてに対応し、
     * 樹種によらず「圧縮段」だけで耐久回復量が決まる(圧縮1段=9倍なので 200 -> 1800 -> 16200)。
     * quick-repair は全種 true。
     */
    @Test
    void shippedWoodRepairCoversAll27CompressedWoodVariantsWithTierOnlyDurability(@TempDir File tempDir)
            throws IOException {
        File file = new File(tempDir, CraftingFeaturesConfig.PATH);
        Files.createDirectories(file.getParentFile().toPath());
        try (var in = CraftingFeaturesConfigWoodRepairTest.class.getClassLoader()
                .getResourceAsStream(CraftingFeaturesConfig.PATH.replace('\\', '/'))) {
            assertNotNull(in, "出荷リソースが見つからない: " + CraftingFeaturesConfig.PATH);
            Files.write(file.toPath(), in.readAllBytes());
        }
        CraftingFeaturesConfig config = new CraftingFeaturesConfig();
        assertTrue(config.load(fakePlugin(tempDir)), "出荷 crafting-features.yml がパースできない");

        List<String> species = List.of("oak_wood", "birch_wood", "spruce_wood", "jungle_wood",
                "acacia_wood", "dark_oak_wood", "mangrove_wood", "pale_oak_wood", "cherry_blossom_wood");
        var mats = config.woodRepairMaterials();
        assertEquals(27, mats.size(), "9樹種 × 3段 = 27件であるはず。現在: " + mats.keySet());
        for (String base : species) {
            WoodRepairMaterial tier1 = mats.get(base + "_1x");
            WoodRepairMaterial tier2 = mats.get(base + "_2x");
            WoodRepairMaterial tier3 = mats.get(base + "_3x");
            assertNotNull(tier1, base + "_1x が無い");
            assertNotNull(tier2, base + "_2x が無い");
            assertNotNull(tier3, base + "_3x が無い");
            assertEquals(200, tier1.durability(), base + "_1x の耐久回復量");
            assertEquals(1800, tier2.durability(), base + "_2x の耐久回復量(200*9)");
            assertEquals(16200, tier3.durability(), base + "_3x の耐久回復量(1800*9)");
            assertTrue(tier1.quickRepair() && tier2.quickRepair() && tier3.quickRepair(),
                    base + " は全段 quick-repair: true であるはず");
        }
    }
}
