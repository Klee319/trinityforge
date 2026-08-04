package com.trinityforge.progression;

import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.catalog.SkillCatalogEntry;
import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.core.XpTransitionService;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 既存被害者の復旧経路 (2026-08-04): 修正前の {@code NativePerkService#prestigeUnderLock} は
 * POWER のプレステージ時に level/EXP を 0 にリセットしていた。既にこの状態になっているプレイヤー
 * （level 0 / prestige &gt;= 1 の POWER）を、次回の {@code /trinityforge reload}
 * ({@link ProgressionCurveReconciler#recalculateAll()} 経由)で救済する回帰テスト。
 */
class ProgressionCurveReconcilerPowerHealingTest {

    @Test
    @DisplayName("プレステージ済み(level0/tier1)のPOWERは、他スキルの現在レベルから再導出されて引き上がる")
    void recalculatePlayerHealsStuckPowerAfterOldBuggyPrestige() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            UUID player = UUID.randomUUID();
            SkillCatalogEntry miningEntry = catalog.get(SkillId.MINING);
            SkillCatalogEntry powerEntry = catalog.get(SkillId.POWER);

            // MINING は健全にLv20まで育っている(=本来ならPOWERも相応にレベルアップしているはず)。
            double twentyLevels = new XpTransitionService(miningEntry.curve()).cumulativeExpForLevel(20);
            repository.saveSkillProgress(player, SkillId.MINING,
                    new SkillProgress(20, 0.0, twentyLevels, 0, miningEntry.maxLevel()));

            // 修正前の prestigeUnderLock が実際に書き込んでいた「被害者の署名」: level/exp 0、prestige 1。
            repository.saveSkillProgress(player, SkillId.POWER,
                    new SkillProgress(0, 0.0, 0.0, 1, powerEntry.maxLevel()));

            // 期待値はderivePowerProgress自体と同じ式(この時点でMININGはtier0のまま=近似ではなく厳密一致)。
            // reconciler実行「前」のシードされた状態から計算する(MININGはtotalExpと矛盾しないので
            // reconcilerのメインループでも書き換わらず、実行後と同じ入力になる)。
            PlayerProgression seeded = repository.load(player).orElseThrow();
            SkillProgress expected = NativeProgressionService.derivePowerProgress(
                    catalog, seeded, 1, powerEntry.maxLevel());

            new ProgressionCurveReconciler(repository, catalog).recalculatePlayer(player);

            PlayerProgression after = repository.load(player).orElseThrow();
            SkillProgress healedPower = after.skills().get(SkillId.POWER);
            assertTrue(healedPower.level() > 0,
                    "被害者のPOWERは復旧経路を通ると0のままではいけない");
            assertEquals(1, healedPower.prestige(), "プレステージ段自体は変えない");
            assertEquals(expected.totalExp(), healedPower.totalExp(), 1e-6);
            assertEquals(expected.level(), healedPower.level());
        }
    }

    @Test
    @DisplayName("既に導出値以上のPOWERは巻き戻されない(一方向ガード)")
    void recalculatePlayerNeverLowersAnAlreadyHigherPowerValue() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            UUID player = UUID.randomUUID();
            SkillCatalogEntry powerEntry = catalog.get(SkillId.POWER);

            // 他スキルは一切育っていない(derive結果は0に近い)が、POWER自体は既に高いtotalExpを持つ
            // (=修正後に正しく積み上がった状態、または管理者が意図的に付与した状態を模す)。
            double highExp = new XpTransitionService(powerEntry.curve()).cumulativeExpForLevel(50);
            repository.saveSkillProgress(player, SkillId.POWER,
                    new SkillProgress(50, 0.0, highExp, 1, powerEntry.maxLevel()));

            new ProgressionCurveReconciler(repository, catalog).recalculatePlayer(player);

            PlayerProgression after = repository.load(player).orElseThrow();
            SkillProgress power = after.skills().get(SkillId.POWER);
            assertEquals(50, power.level(), "導出値より高い既存値を下げてはいけない");
            assertEquals(highExp, power.totalExp(), 1e-6);
        }
    }
}
