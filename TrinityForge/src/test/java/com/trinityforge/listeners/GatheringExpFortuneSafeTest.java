package com.trinityforge.listeners;

import com.trinityforge.config.domains.SkillExpConfig.GatheringExpMode;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatheringExpFortuneSafeTest {

    @Test
    void fortuneStackSizeDoesNotMultiplyExp() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.IRON_ORE", 80.0, "mining_break.RAW_IRON", 40.0),
                Map.of());
        ItemStack one = new ItemStack(Material.RAW_IRON, 1);
        ItemStack many = new ItemStack(Material.RAW_IRON, 64);
        double a = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "IRON_ORE", List.of(one));
        double b = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "IRON_ORE", List.of(many));
        assertEquals(a, b, 0.0);
        assertEquals(40.0, a, 0.0);
    }

    @Test
    void unlistedDropsYieldZeroEvenIfBlockListed() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.IRON_ORE", 80.0),
                Map.of());
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "IRON_ORE",
                List.of(new ItemStack(Material.DIRT, 1)));
        assertEquals(0.0, exp, 0.0);
    }

    // ------------------------------------------------------------------
    // 2026-07-26 EXP調整タスク1: gathering.exp-mode (block value vs drop sum vs max)
    // ------------------------------------------------------------------

    /**
     * 「既定config では現行挙動と一致する」ことの直接検証。旧実装(4引数オーバーロード、無条件で
     * DROP_SUM)と、新5引数オーバーロードへ明示的にDROP_SUMを渡した結果が、DEEPSLATE_DIAMOND_ORE
     * (ブロック側600) / DIAMOND(ドロップ側200)という「ブロック値とドロップ値が意図的に違う」
     * ケースで完全一致することを確認する。
     */
    @Test
    void defaultModeMatchesLegacyDropSumBehaviorExactly() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.DEEPSLATE_DIAMOND_ORE", 600.0, "mining_break.DIAMOND", 200.0),
                Map.of());
        List<ItemStack> drops = List.of(new ItemStack(Material.DIAMOND, 1));

        double legacyOverload = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "DEEPSLATE_DIAMOND_ORE", drops);
        double explicitDropSum = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "DEEPSLATE_DIAMOND_ORE", drops, GatheringExpMode.DROP_SUM);
        double defaultModeFromConfig = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "DEEPSLATE_DIAMOND_ORE", drops, null);

        assertEquals(200.0, legacyOverload, 0.0);
        assertEquals(legacyOverload, explicitDropSum, 0.0);
        assertEquals(legacyOverload, defaultModeFromConfig, 0.0);
    }

    @Test
    void blockValueModeUsesBlockSideValueNotDropSum() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.DEEPSLATE_DIAMOND_ORE", 600.0, "mining_break.DIAMOND", 200.0),
                Map.of());
        List<ItemStack> drops = List.of(new ItemStack(Material.DIAMOND, 1));

        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "DEEPSLATE_DIAMOND_ORE", drops, GatheringExpMode.BLOCK_VALUE);

        // 「深層岩バリアントはドロップが同じでも高い」という意図した設計がBLOCK_VALUEでは反映される。
        assertEquals(600.0, exp, 0.0);
    }

    @Test
    void blockValueModeStillZeroOnEmptyDrops() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.DEEPSLATE_DIAMOND_ORE", 600.0),
                Map.of());
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "DEEPSLATE_DIAMOND_ORE", List.of(), GatheringExpMode.BLOCK_VALUE);
        // 素手破壊(ドロップ空)でEXPを稼ぐ抜け道はBLOCK_VALUEモードでも塞がれたまま。
        assertEquals(0.0, exp, 0.0);
    }

    @Test
    void blockValueModeFortuneSafeStackSizeIgnored() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.DEEPSLATE_DIAMOND_ORE", 600.0),
                Map.of());
        double one = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "DEEPSLATE_DIAMOND_ORE",
                List.of(new ItemStack(Material.DIAMOND, 1)), GatheringExpMode.BLOCK_VALUE);
        double many = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "DEEPSLATE_DIAMOND_ORE",
                List.of(new ItemStack(Material.DIAMOND, 64)), GatheringExpMode.BLOCK_VALUE);
        // 幸運で個数が増えてもブロック値は変わらない(幸運での増殖は起きない)。
        assertEquals(one, many, 0.0);
        assertEquals(600.0, one, 0.0);
    }

    @Test
    void maxModePicksTheLargerOfBlockAndDropValues() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.IRON_ORE", 80.0, "mining_break.RAW_IRON", 200.0),
                Map.of());
        List<ItemStack> drops = List.of(new ItemStack(Material.RAW_IRON, 1));

        // ドロップ側(200)がブロック側(80)より大きいケース: MAXはドロップ側を選ぶ。
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "IRON_ORE", drops, GatheringExpMode.MAX);
        assertEquals(200.0, exp, 0.0);
    }

    @Test
    void maxModeNeverReturnsLessThanEitherComponent() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.DEEPSLATE_DIAMOND_ORE", 600.0, "mining_break.DIAMOND", 200.0),
                Map.of());
        List<ItemStack> drops = List.of(new ItemStack(Material.DIAMOND, 1));

        // ブロック側(600)がドロップ側(200)より大きいケース: MAXはブロック側を選ぶ。
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "DEEPSLATE_DIAMOND_ORE", drops, GatheringExpMode.MAX);
        assertEquals(600.0, exp, 0.0);
    }
}
