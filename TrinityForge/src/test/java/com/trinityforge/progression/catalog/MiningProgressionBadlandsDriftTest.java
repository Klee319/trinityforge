package com.trinityforge.progression.catalog;

import com.trinityforge.progression.core.SkillId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ドリフト検知(バグ1, 2026-07-28): 出荷 yml {@code skills/base/mining_progression.yml} の
 * {@code mining_break} 表に、バッドランドのテラコッタ等の対応で追加したブロック名/ドロップ材質名が
 * 実際に載っていることを、実クラスパスの本物のリソースを本物のローダー({@link NativeSkillCatalog})
 * で読んで固定する({@code GimmickTierYamlDriftTest} と同じ「実物を読む」流儀)。
 */
class MiningProgressionBadlandsDriftTest {

    private static SkillCatalogEntry mining() {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(
                MiningProgressionBadlandsDriftTest.class.getClassLoader());
        SkillCatalogEntry entry = catalog.get(SkillId.MINING);
        assertTrue(entry != null, "MINING entry must load from classpath skills/base/mining_progression.yml");
        return entry;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "TERRACOTTA", "WHITE_TERRACOTTA", "ORANGE_TERRACOTTA", "MAGENTA_TERRACOTTA",
            "LIGHT_BLUE_TERRACOTTA", "YELLOW_TERRACOTTA", "LIME_TERRACOTTA", "PINK_TERRACOTTA",
            "GRAY_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA", "CYAN_TERRACOTTA", "PURPLE_TERRACOTTA",
            "BLUE_TERRACOTTA", "BROWN_TERRACOTTA", "GREEN_TERRACOTTA", "RED_TERRACOTTA",
            "BLACK_TERRACOTTA"
    })
    @DisplayName("テラコッタ17種は mining_break 表に実在する(自己ドロップなのでブロック名=ドロップ名の1行で足りる)")
    void terracottaVariantsArePresent(String material) {
        double exp = mining().expFor("mining_break", material);
        assertTrue(exp > 0.0, "mining_break." + material + " は正の値で登録されているべき");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SANDSTONE", "RED_SANDSTONE"})
    void sandstoneVariantsArePresent(String material) {
        assertTrue(mining().expFor("mining_break", material) > 0.0);
    }

    /**
     * SEA_LANTERN は落とし穴の直接固定: ブロック側の行(ゲート用)だけでなく、実際にドロップする
     * PRISMARINE_CRYSTALS 側の行も無いと drop_sum モードで EXP は 0 のままになる。
     */
    @Test
    @DisplayName("SEA_LANTERN はブロック側とドロップ側(PRISMARINE_CRYSTALS)の両方の行がある")
    void seaLanternHasBothBlockAndDropRows() {
        SkillCatalogEntry entry = mining();
        assertTrue(entry.expFor("mining_break", "SEA_LANTERN") > 0.0,
                "mining_break.SEA_LANTERN(ゲート用)が正の値で登録されているべき");
        assertTrue(entry.expFor("mining_break", "PRISMARINE_CRYSTALS") > 0.0,
                "mining_break.PRISMARINE_CRYSTALS(drop_sumで実際に加算される側)が正の値で登録されているべき");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRISMARINE", "PRISMARINE_BRICKS", "DARK_PRISMARINE"})
    void prismarineStructureBlocksArePresent(String material) {
        assertTrue(mining().expFor("mining_break", material) > 0.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NETHER_BRICKS", "RED_NETHER_BRICKS", "MAGMA_BLOCK"})
    void netherFortressBlocksArePresent(String material) {
        assertTrue(mining().expFor("mining_break", material) > 0.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "STONE_BRICKS", "CRACKED_STONE_BRICKS", "CHISELED_STONE_BRICKS", "MOSSY_STONE_BRICKS",
            "MOSSY_COBBLESTONE", "POLISHED_DEEPSLATE", "DEEPSLATE_BRICKS", "CRACKED_DEEPSLATE_BRICKS",
            "DEEPSLATE_TILES", "CRACKED_DEEPSLATE_TILES", "CHISELED_DEEPSLATE",
            "POLISHED_TUFF", "TUFF_BRICKS", "CHISELED_TUFF", "CHISELED_TUFF_BRICKS"
    })
    void structureStoneVariantsArePresent(String material) {
        assertTrue(mining().expFor("mining_break", material) > 0.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ICE", "PACKED_ICE", "BLUE_ICE"})
    void iceVariantsArePresent(String material) {
        assertTrue(mining().expFor("mining_break", material) > 0.0);
    }

    @Test
    void pointedDripstoneIsPresent() {
        assertTrue(mining().expFor("mining_break", "POINTED_DRIPSTONE") > 0.0);
    }

    /** クラフト専用の装飾ブロックは意図的に対象外(*_GLAZED_TERRACOTTA は入れない方針の固定)。 */
    @Test
    @DisplayName("GLAZED_TERRACOTTAはクラフト専用ブロックのため意図的に未登録")
    void glazedTerracottaIsIntentionallyAbsent() {
        double exp = mining().expFor("mining_break", "WHITE_GLAZED_TERRACOTTA");
        assertTrue(exp == 0.0, "*_GLAZED_TERRACOTTA はクラフト専用ブロックなので mining_break に載せない方針");
    }
}
