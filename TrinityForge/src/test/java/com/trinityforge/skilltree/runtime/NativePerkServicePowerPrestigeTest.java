package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.core.XpTransitionService;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * バグ修正回帰 (2026-08-04): ユーザー報告「"総合"(POWER) をプレステージすると、総合ツリーが
 * コマンドを使ってもパークを解放できなくなる」。
 *
 * <p>真因: POWER は他スキルのレベルアップ({@link NativeProgressionService#grantExp}内の間接加算)
 * からしか値が積み上がらないのに、{@link NativePerkService#prestigeUnderLock} は全スキル共通の
 * ロジックで POWER も level=0 にリセットしていた。他スキルが既に育っていると、リセット後の POWER が
 * 二度と各ノードの {@code level} 要求に届かず恒久的に解放不能になる。修正は POWER の場合だけ、
 * 他スキルの現在レベルから grantExp と同じ式で再導出した値を使う({@link NativePerkService}の
 * {@code prestigeUnderLock} 参照)。
 */
class NativePerkServicePowerPrestigeTest {

    private static SkillNode node(String id, int level, int cost) {
        return new SkillNode(id, id, level, SkillRole.MAIN, null, null, "NETHER_STAR", cost, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    private static SkillTree powerTree(int atLevel, int nodeLevelRequirement) {
        Prestige prestige = new Prestige(true, atLevel, "P", "", Map.of(), Map.of(), 5);
        return new SkillTree(SkillId.POWER, "Power", "NETHER_STAR", "2,10", prestige,
                Map.of("A", node("A", nodeLevelRequirement, 1)));
    }

    private static SkillTree smithingTree() {
        Prestige prestige = new Prestige(true, 10, "P", "", Map.of(), Map.of(), 2);
        return new SkillTree(SkillId.SMITHING, "Smithing", "ANVIL", "2,10", prestige,
                Map.of("A", node("A", 10, 2)));
    }

    @Test
    @DisplayName("他スキルが育った状態でPOWERをプレステージしても、レベルが0にならず再導出値になる")
    void powerPrestigeDerivesLevelInsteadOfResettingToZero() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            UUID player = UUID.randomUUID();

            // MINING を育てて POWER を間接的にレベルアップさせる(実際のゲームプレイ経路と同一)。
            double exp = new XpTransitionService(catalog.get(SkillId.MINING).curve()).cumulativeExpForLevel(20);
            progression.grantExp(player, SkillId.MINING, exp);

            SkillProgress beforePrestige = progression.progress(player, SkillId.POWER).orElseThrow();
            assertTrue(beforePrestige.level() > 0, "前提: MINING成長でPOWERが自然にレベルアップしていること");

            NativePerkService perks = new NativePerkService(progression,
                    () -> List.of(powerTree(beforePrestige.level(), 1)));

            assertEquals(NativePerkService.PrestigeResult.PRESTIGED,
                    perks.prestige(player, SkillId.POWER));

            SkillProgress afterPrestige = progression.progress(player, SkillId.POWER).orElseThrow();
            assertEquals(1, afterPrestige.prestige());
            assertEquals(beforePrestige.totalExp(), afterPrestige.totalExp(), 1e-6,
                    "MININGはtier0のままプレステージしたので、再導出値はプレステージ直前の"
                            + "organic合計と厳密に一致するはず(近似ではない)");
            assertEquals(beforePrestige.level(), afterPrestige.level(),
                    "POWERレベルを0にリセットしてはいけない(修正前のバグ挙動)");
        }
    }

    @Test
    @DisplayName("プレステージ後もPOWERノードのunlockがLEVEL_TOO_LOWにならない")
    void unlockAfterPowerPrestigeIsNotLevelTooLow() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            UUID player = UUID.randomUUID();
            repository.savePointBalance(player, 10L, 0L);

            double exp = new XpTransitionService(catalog.get(SkillId.MINING).curve()).cumulativeExpForLevel(20);
            progression.grantExp(player, SkillId.MINING, exp);
            int powerLevelBefore = progression.progress(player, SkillId.POWER).orElseThrow().level();
            assertTrue(powerLevelBefore >= 1, "テスト前提が崩れている(catalogのpower.exp_per_skill_level既定が変わった?)");

            // ノード要求レベル = プレステージ前のPOWERレベルそのもの。修正前ならプレステージ直後に
            // 恒久的にLEVEL_TOO_LOWになる値。
            NativePerkService perks = new NativePerkService(progression,
                    () -> List.of(powerTree(powerLevelBefore, powerLevelBefore)));

            assertEquals(NativePerkService.PrestigeResult.PRESTIGED,
                    perks.prestige(player, SkillId.POWER));

            NativePerkService.UnlockResult result = perks.unlock(player, SkillId.POWER, "A");
            assertNotEquals(NativePerkService.UnlockResult.LEVEL_TOO_LOW, result,
                    "POWERを0リセットしていると恒久的にここがLEVEL_TOO_LOWになる(元バグ)");
            assertEquals(NativePerkService.UnlockResult.UNLOCKED, result);
        }
    }

    @Test
    @DisplayName("SMITHING等、POWER以外のプレステージは従来どおりレベル0にリセットされる(退行防止)")
    void nonPowerSkillPrestigeStillResetsLevelToZero() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            NativePerkService perks = new NativePerkService(progression, () -> List.of(smithingTree()));
            UUID player = UUID.randomUUID();
            repository.savePointBalance(player, 10L, 0L);
            repository.saveSkillProgress(player, SkillId.SMITHING,
                    new SkillProgress(10, 0.0, 1000.0, 0, 100));

            assertEquals(NativePerkService.PrestigeResult.PRESTIGED,
                    perks.prestige(player, SkillId.SMITHING));

            SkillProgress after = progression.progress(player, SkillId.SMITHING).orElseThrow();
            assertEquals(0, after.level(), "POWER以外は従来どおり0リセットのまま(退行防止)");
            assertEquals(1, after.prestige());
        }
    }

    @Test
    @DisplayName("SP残高はPOWERプレステージ直後・その後のEXP付与でも不当に減らない")
    void spBalanceDoesNotCrashAfterPowerPrestige() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            UUID player = UUID.randomUUID();

            double exp = new XpTransitionService(catalog.get(SkillId.MINING).curve()).cumulativeExpForLevel(20);
            progression.grantExp(player, SkillId.MINING, exp);
            long spBefore = progression.snapshot(player).availablePoints();
            int powerLevelBefore = progression.progress(player, SkillId.POWER).orElseThrow().level();

            NativePerkService perks = new NativePerkService(progression,
                    () -> List.of(powerTree(powerLevelBefore, 1)));
            assertEquals(NativePerkService.PrestigeResult.PRESTIGED,
                    perks.prestige(player, SkillId.POWER));

            long spRightAfterPrestige = progression.snapshot(player).availablePoints();
            assertTrue(spRightAfterPrestige >= spBefore,
                    "プレステージ直後にSP残高が減ってはいけない");

            // 次の(別スキルへの)EXP付与でPOWERレベルが読み直されて可用SPが再計算される経路も確認する。
            progression.grantExp(player, SkillId.FARMING, 1.0);
            long spAfterNextGrant = progression.snapshot(player).availablePoints();
            assertTrue(spAfterNextGrant >= spBefore,
                    "修正前は次のgrantExpでPOWERレベル0が読み直されてSPが激減した");
        }
    }
}
