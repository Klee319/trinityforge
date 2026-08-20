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

/**
 * 「1SPを何POWERレベルごとに与えるか」({@code stats/skill-exp.yml: power.levels-per-skill-point})
 * の回帰テスト (2026-08-04 ユーザー要望)。
 *
 * <p>式が付与({@link NativeProgressionService})・管理コマンド({@link NativeProgressionAdminService})・
 * reload時再計算({@link ProgressionCurveReconciler})の3箇所に散っており、<b>どれか1つでも
 * 旧式のまま残ると「レベルアップで増えた点が reload で消える」</b>という追跡困難な食い違いになる。
 * そのため3経路それぞれに対して同じ設定値での期待値を固定する。
 */
class SkillPointPerPowerLevelTest {

    @Test
    @DisplayName("式本体: 初期3点 + POWERレベル ÷ 間隔(切り捨て)。0以下の間隔は1として扱う")
    void earnedPointsDividesPowerLevelByTheConfiguredInterval() {
        assertEquals(3L, PlayerProgression.earnedPoints(0, 1));
        assertEquals(13L, PlayerProgression.earnedPoints(10, 1), "間隔1は従来挙動(1レベル1点)");
        assertEquals(8L, PlayerProgression.earnedPoints(10, 2));
        assertEquals(5L, PlayerProgression.earnedPoints(11, 5), "端数は切り捨て");
        assertEquals(3L, PlayerProgression.earnedPoints(4, 5), "間隔未満は0点");

        // 設定ミスの丸め: 0 はゼロ除算、負はレベルとともに点が減る意味不明な挙動になるので両方1扱い。
        assertEquals(13L, PlayerProgression.earnedPoints(10, 0));
        assertEquals(13L, PlayerProgression.earnedPoints(10, -3));
        assertEquals(3L, PlayerProgression.earnedPoints(-50, 1), "負のレベルは0扱い");
    }

    @Test
    @DisplayName("reload時再計算が間隔を反映してポイント残高を書き直す")
    void reconcilerAppliesTheConfiguredInterval() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            UUID player = UUID.randomUUID();
            SkillCatalogEntry powerEntry = catalog.get(SkillId.POWER);
            double exp = new XpTransitionService(powerEntry.curve()).cumulativeExpForLevel(20);
            // prestige 0 = 復旧経路(derivePowerProgress)には入らないので、検証対象はポイント残高だけ。
            repository.saveSkillProgress(player, SkillId.POWER,
                    new SkillProgress(20, 0.0, exp, 0, powerEntry.maxLevel()));
            repository.savePointBalance(player, 23L, 0L);

            new ProgressionCurveReconciler(repository, catalog, () -> 4).recalculatePlayer(player);

            PlayerProgression after = repository.load(player).orElseThrow();
            assertEquals(8L, after.availablePoints(), "3 + 20/4 = 8 になっていない");
        }
    }

    @Test
    @DisplayName("既定(間隔1)の再計算は従来どおり 3 + レベル")
    void reconcilerDefaultsToOnePointPerLevel() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            UUID player = UUID.randomUUID();
            SkillCatalogEntry powerEntry = catalog.get(SkillId.POWER);
            double exp = new XpTransitionService(powerEntry.curve()).cumulativeExpForLevel(20);
            repository.saveSkillProgress(player, SkillId.POWER,
                    new SkillProgress(20, 0.0, exp, 0, powerEntry.maxLevel()));
            repository.savePointBalance(player, 0L, 0L);

            new ProgressionCurveReconciler(repository, catalog).recalculatePlayer(player);

            PlayerProgression after = repository.load(player).orElseThrow();
            assertEquals(23L, after.availablePoints());
        }
    }

    @Test
    @DisplayName("EXP付与によるレベルアップ時の残高も間隔を反映する")
    void grantAppliesTheConfiguredInterval() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            UUID player = UUID.randomUUID();
            NativeProgressionService service = new NativeProgressionService(
                    repository, catalog, id -> 0.0, new PlayerLockRegistry(),
                    ExpDiminishingCurve.NONE, null, null, (id, skillId) -> 0.0, () -> 3);

            // MINING を育てて POWER を間接的に上げる(付与経路そのものを通す)。
            SkillCatalogEntry mining = catalog.get(SkillId.MINING);
            double toLevel30 = new XpTransitionService(mining.curve()).cumulativeExpForLevel(30);
            service.grantExp(player, SkillId.MINING, toLevel30);

            PlayerProgression after = repository.load(player).orElseThrow();
            long powerLevel = after.skills().get(SkillId.POWER).level();
            assertEquals(PlayerProgression.earnedPoints(powerLevel, 3), after.availablePoints(),
                    "付与経路が power.levels-per-skill-point を見ていない (POWER Lv" + powerLevel + ")");
        }
    }
}
