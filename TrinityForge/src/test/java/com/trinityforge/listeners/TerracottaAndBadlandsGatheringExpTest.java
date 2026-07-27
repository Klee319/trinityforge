package com.trinityforge.listeners;

import com.trinityforge.config.domains.SkillExpConfig.GatheringExpMode;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * バグ1(2026-07-28): バッドランドのテラコッタを掘っても採掘EXPが0になっていた回帰の再発防止テスト。
 * {@code skills/base/mining_progression.yml} の {@code mining_break} 表にテラコッタ等の自然生成
 * ブロックを追加した対応の、{@link NativeSkillExperienceListener#gatheringExp} 純粋関数レベルの検証。
 * 出荷既定の {@code drop_mode: drop_sum} を明示指定し、「ドロップ品の材質名に対する値の合計」という
 * 実運用モードで検証する。
 */
class TerracottaAndBadlandsGatheringExpTest {

    @Test
    void terracottaSelfDropsGrantsPositiveExpUnderDropSum() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.TERRACOTTA", 8.0),
                Map.of());
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "TERRACOTTA",
                List.of(new ItemStack(Material.TERRACOTTA, 1)), GatheringExpMode.DROP_SUM);
        assertTrue(exp > 0.0, "TERRACOTTA を掘って自身をドロップすれば drop_sum で正のEXPになるべき");
        assertEquals(8.0, exp, 0.0);
    }

    @Test
    void coloredTerracottaSelfDropsGrantsPositiveExpUnderDropSum() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.RED_TERRACOTTA", 8.0, "mining_break.ORANGE_TERRACOTTA", 8.0),
                Map.of());
        double red = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "RED_TERRACOTTA",
                List.of(new ItemStack(Material.RED_TERRACOTTA, 1)), GatheringExpMode.DROP_SUM);
        double orange = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "ORANGE_TERRACOTTA",
                List.of(new ItemStack(Material.ORANGE_TERRACOTTA, 1)), GatheringExpMode.DROP_SUM);
        assertTrue(red > 0.0);
        assertTrue(orange > 0.0);
    }

    /**
     * SEA_LANTERN のように「ブロックと違うものを落とす」ケース。ブロック側の行はゲート(値>0)にしか
     * 使われず、実際に付与されるEXPはドロップ側(PRISMARINE_CRYSTALS)の行が無いと drop_sum では 0 のまま
     * になる — この落とし穴を踏んでいないことを固定する。
     */
    @Test
    void seaLanternDropsPrismarineCrystalsAndStillGrantsPositiveExpUnderDropSum() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.SEA_LANTERN", 24.0, "mining_break.PRISMARINE_CRYSTALS", 24.0),
                Map.of());
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "SEA_LANTERN",
                List.of(new ItemStack(Material.PRISMARINE_CRYSTALS, 3)), GatheringExpMode.DROP_SUM);
        assertTrue(exp > 0.0,
                "SEA_LANTERN はドロップがPRISMARINE_CRYSTALSに変わるので、ドロップ側の行が無いと drop_sum で0のまま");
        assertEquals(24.0, exp, 0.0);
    }

    /** 回帰防止: ブロック側だけ登録されドロップ側の行が無いと、意図せず0のまま(落とし穴の直接確認)。 */
    @Test
    void blockOnlyRowWithoutDropRowStillYieldsZeroUnderDropSum() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.SEA_LANTERN", 24.0),
                Map.of());
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "SEA_LANTERN",
                List.of(new ItemStack(Material.PRISMARINE_CRYSTALS, 3)), GatheringExpMode.DROP_SUM);
        assertEquals(0.0, exp, 0.0);
    }

    /** 回帰防止: 表に無いブロック(非採掘ブロック)は0のまま。 */
    @Test
    void unlistedBlockYieldsZero() {
        SkillCatalogEntry entry = new SkillCatalogEntry(
                "MINING", 100, "1", level -> 1L,
                Map.of("mining_break.TERRACOTTA", 8.0),
                Map.of());
        double exp = NativeSkillExperienceListener.gatheringExp(
                entry, "mining_break", "OAK_LEAVES",
                List.of(new ItemStack(Material.OAK_LEAVES, 1)), GatheringExpMode.DROP_SUM);
        assertEquals(0.0, exp, 0.0);
    }
}
