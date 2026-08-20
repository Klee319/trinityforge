package com.trinityforge.progression;

import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * スキル別EXP倍率（2026-08-02 柱5-3）の配線検証。
 *
 * <p><b>なぜ要るか</b>: 「樵の大斧は伐採EXPだけ +15%」のような単発装備の個性を、
 * {@code use-skill} で表現してはいけない（{@code use-skill} は装備要件であって分類マーカーではなく、
 * 採取ツールにも付いているので <b>斧で殴っただけで伐採EXPが入る</b>）。
 * 表現手段はこの倍率ステだけなので、「対象スキルにだけ効く」「他スキルに漏れない」
 * 「一律ぶんと加算で合成される」の3点を固定する。
 */
class PerSkillExpBonusTest {

    private static NativeProgressionService service(
            SqliteProgressionRepository repository, NativeSkillCatalog catalog,
            java.util.function.ToDoubleFunction<UUID> all, PerSkillExpBonus perSkill) {
        return new NativeProgressionService(repository, catalog, all, new PlayerLockRegistry(),
                ExpDiminishingCurve.NONE, null, null, perSkill);
    }

    @Test
    @DisplayName("スキル別倍率は対象スキルにだけ乗り、他スキルへは漏れない")
    void perSkillBonusAppliesOnlyToItsOwnSkill() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService service = service(repository, catalog, id -> 0.0,
                    (playerId, skillId) -> SkillId.WOODCUTTING.equals(skillId) ? 0.15 : 0.0);
            UUID player = UUID.randomUUID();

            service.grantExp(player, SkillId.WOODCUTTING, 100.0);
            service.grantExp(player, SkillId.MINING, 100.0);

            assertEquals(115.0, service.progress(player, SkillId.WOODCUTTING)
                    .orElseThrow().totalExp(), 1.0e-6, "伐採には +15% が乗るはず");
            assertEquals(100.0, service.progress(player, SkillId.MINING)
                    .orElseThrow().totalExp(), 1.0e-6, "採掘へ漏れてはいけない");
        }
    }

    @Test
    @DisplayName("一律ぶんとスキル別ぶんは加算で合成される(掛け算ではない)")
    void perSkillBonusCombinesAdditivelyWithTheGlobalBonus() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService service = service(repository, catalog, id -> 0.10,
                    (playerId, skillId) -> 0.20);
            UUID player = UUID.randomUUID();

            service.grantExp(player, SkillId.FARMING, 100.0);

            // 130 = 100 * (1 + 0.10 + 0.20)。別々に掛けると 100*1.10*1.20 = 132 になる。
            assertEquals(130.0, service.progress(player, SkillId.FARMING)
                    .orElseThrow().totalExp(), 1.0e-6);
        }
    }

    @Test
    @DisplayName("スキル別倍率を渡さない既存の構築子は挙動が変わらない")
    void constructorsWithoutThePerSkillHookAreUnchanged() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository legacyRepo =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:");
             SqliteProgressionRepository explicitRepo =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService legacy = new NativeProgressionService(
                    legacyRepo, catalog, id -> 0.10, new PlayerLockRegistry(), ExpDiminishingCurve.NONE);
            NativeProgressionService explicitNone = service(
                    explicitRepo, catalog, id -> 0.10, PerSkillExpBonus.NONE);
            UUID player = UUID.randomUUID();

            legacy.grantExp(player, SkillId.DIGGING, 100.0);
            explicitNone.grantExp(player, SkillId.DIGGING, 100.0);

            assertEquals(legacy.progress(player, SkillId.DIGGING).orElseThrow().totalExp(),
                    explicitNone.progress(player, SkillId.DIGGING).orElseThrow().totalExp(), 1.0e-9);
            assertEquals(110.0, legacy.progress(player, SkillId.DIGGING)
                    .orElseThrow().totalExp(), 1.0e-6);
        }
    }
}
