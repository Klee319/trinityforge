package com.trinityforge.farming;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-08-01 U9: 成熟ガードが「成熟しないと収穫できない作物」だけに掛かることの拘束テスト。
 *
 * <p>Bukkit の {@link Ageable} は age を持つだけの意味しか無く、サトウキビ/コンブ/竹/ねじれツタ/
 * 泣きツタ/光ツタ/サボテンの age は「次の段を伸ばすまでのカウンタ」で最大値に達すると 0 に戻る。
 * これらを未成熟扱いにすると収穫できる状態でも永久にEXPが入らなくなる（実際に農業EXPが壊れていた）。
 */
class CropMaturityTest {

    /** age が周回する（＝成熟の概念を持たない）植物。どのageでも収穫扱いを妨げてはいけない。 */
    private enum CyclingPlant {
        SUGAR_CANE(Material.SUGAR_CANE, 15),
        KELP(Material.KELP, 25),
        CACTUS(Material.CACTUS, 15),
        BAMBOO(Material.BAMBOO, 1),
        TWISTING_VINES(Material.TWISTING_VINES, 25),
        WEEPING_VINES(Material.WEEPING_VINES, 25),
        CAVE_VINES(Material.CAVE_VINES, 25),
        CHORUS_FLOWER(Material.CHORUS_FLOWER, 5),
        MANGROVE_PROPAGULE(Material.MANGROVE_PROPAGULE, 4),
        FROSTED_ICE(Material.FROSTED_ICE, 3),
        FIRE(Material.FIRE, 15);

        private final Material material;
        private final int maximumAge;

        CyclingPlant(Material material, int maximumAge) {
            this.material = material;
            this.maximumAge = maximumAge;
        }
    }

    @ParameterizedTest
    @EnumSource(CyclingPlant.class)
    void cyclingPlantsAreNeverTreatedAsImmature(CyclingPlant plant) {
        for (int age = 0; age <= plant.maximumAge; age++) {
            assertFalse(CropMaturity.isImmatureCrop(ageableBlock(plant.material, age, plant.maximumAge)),
                    plant.material + " age=" + age + " は成熟ガードの対象外でなければならない");
        }
        assertFalse(CropMaturity.isMaturityGated(plant.material),
                plant.material + " は成熟概念を持たないのでホワイトリストに入れてはいけない");
    }

    @ParameterizedTest
    @CsvSource({
            "WHEAT,7",
            "CARROTS,7",
            "POTATOES,7",
            "BEETROOTS,3",
            "NETHER_WART,3",
            "COCOA,2",
            "SWEET_BERRY_BUSH,3",
            "TORCHFLOWER_CROP,2",
            "PITCHER_CROP,4",
            "MELON_STEM,7",
            "PUMPKIN_STEM,7",
    })
    void maturingCropsAreGatedUntilFullyGrown(Material crop, int maximumAge) {
        assertTrue(CropMaturity.isMaturityGated(crop), crop + " は成熟ガードの対象");
        for (int age = 0; age < maximumAge; age++) {
            assertTrue(CropMaturity.isImmatureCrop(ageableBlock(crop, age, maximumAge)),
                    crop + " age=" + age + " は未成熟として弾かれなければならない");
        }
        assertFalse(CropMaturity.isImmatureCrop(ageableBlock(crop, maximumAge, maximumAge)),
                crop + " が完熟(age=maximumAge)なら収穫扱いを妨げない");
    }

    @Test
    void nonPlantBlocksAndNullAreNeverImmature() {
        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        assertFalse(CropMaturity.isImmatureCrop(stone));
        assertFalse(CropMaturity.isImmatureCrop(null));
        assertFalse(CropMaturity.isMaturityGated(null));
    }

    @Test
    void gatedCropWithoutAgeableDataIsNotTreatedAsImmature() {
        // 防御的: ホワイトリストに載っていてもBlockDataがAgeableでなければ判定材料が無い。
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.WHEAT);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        assertFalse(CropMaturity.isImmatureCrop(block));
    }

    private static Block ageableBlock(Material material, int age, int maximumAge) {
        Block block = mock(Block.class);
        Ageable data = mock(Ageable.class);
        when(block.getType()).thenReturn(material);
        when(block.getBlockData()).thenReturn(data);
        when(data.getAge()).thenReturn(age);
        when(data.getMaximumAge()).thenReturn(maximumAge);
        return block;
    }
}
