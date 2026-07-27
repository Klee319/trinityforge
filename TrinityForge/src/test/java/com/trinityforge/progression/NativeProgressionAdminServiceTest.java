package com.trinityforge.progression;

import com.trinityforge.progression.NativeProgressionAdminService.EditMode;
import com.trinityforge.progression.NativeProgressionAdminService.EditStatus;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.XpTransitionService;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeProgressionAdminServiceTest {

    @Test
    void setAddAndSubtractNormalizeExpAndKeepPowerPointsConsistent() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionAdminService service =
                    new NativeProgressionAdminService(repository, catalog, List::of);
            UUID player = UUID.randomUUID();

            var set = service.edit(player, SkillId.MINING, EditMode.SET, 8, null);
            assertEquals(EditStatus.APPLIED, set.status());
            assertEquals(8, set.after().level());
            assertEquals(0.0, set.after().residualExp());
            assertEquals(new XpTransitionService(catalog.get(SkillId.MINING).curve())
                    .cumulativeExpForLevel(8), set.after().totalExp());
            // 2026-07-25 PRG-08: exp_gain 100 -> 240 (SP供給不足の是正)。8 MINING levels now grant
            // 1920 POWER exp, which lands POWER on level 2 (was level 1 under the old exp_gain=100) —
            // see NativeProgressionServiceTest for the same arithmetic spelled out.
            assertEquals(2, repository.load(player).orElseThrow()
                    .skills().get(SkillId.POWER).level());
            assertEquals(5L, repository.load(player).orElseThrow().availablePoints());

            assertEquals(10, service.edit(
                    player, SkillId.MINING, EditMode.ADD, 2, null).after().level());
            assertEquals(7, service.edit(
                    player, SkillId.MINING, EditMode.SUBTRACT, 3, null).after().level());
        }
    }

    @Test
    void rejectsOutOfRangeLevelWithoutChangingStoredState() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionAdminService service =
                    new NativeProgressionAdminService(repository, catalog, List::of);
            UUID player = UUID.randomUUID();
            service.edit(player, SkillId.MINING, EditMode.SET, 5, null);

            assertEquals(EditStatus.LEVEL_OUT_OF_RANGE,
                    service.edit(player, SkillId.MINING, EditMode.SUBTRACT, 6, null).status());
            assertEquals(EditStatus.LEVEL_OUT_OF_RANGE,
                    service.edit(player, SkillId.MINING, EditMode.ADD, 100, null).status());
            assertEquals(5, repository.load(player).orElseThrow()
                    .skills().get(SkillId.MINING).level());
        }
    }

    @Test
    void optionalPrestigeIsAbsoluteAndReconcilesPermanentTiers() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        Prestige prestige = new Prestige(true, 100, "Prestige", "", Map.of(), Map.of(), 3);
        SkillTree mining = new SkillTree(
                SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", prestige, Map.of());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionAdminService service =
                    new NativeProgressionAdminService(repository, catalog, () -> List.of(mining));
            UUID player = UUID.randomUUID();
            repository.savePointBalance(player, 10L, 0L);
            assertTrue(repository.unlockPerk(player, "mining_perk_a", 1L));

            var raised = service.edit(player, SkillId.MINING, EditMode.SET, 20, 2);
            assertEquals(EditStatus.APPLIED, raised.status());
            assertEquals(2, raised.after().prestige());
            assertEquals(
                    java.util.Set.of("mining_perk_a", "mining_perk_ng1", "mining_perk_ng2"),
                    repository.loadPerkIds(player).orElseThrow());

            var lowered = service.edit(player, SkillId.MINING, EditMode.ADD, 1, 1);
            assertEquals(1, lowered.after().prestige());
            assertEquals(
                    java.util.Set.of("mining_perk_a", "mining_perk_ng1"),
                    repository.loadPerkIds(player).orElseThrow());

            var preserved = service.edit(player, SkillId.MINING, EditMode.ADD, 1, null);
            assertEquals(1, preserved.after().prestige());
        }
    }

    @Test
    void rejectsPrestigeForDisabledSkillAndAboveConfiguredMaximum() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        Prestige prestige = new Prestige(true, 100, "Prestige", "", Map.of(), Map.of(), 2);
        SkillTree mining = new SkillTree(
                SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", prestige, Map.of());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionAdminService service =
                    new NativeProgressionAdminService(repository, catalog, () -> List.of(mining));
            UUID player = UUID.randomUUID();

            assertEquals(EditStatus.PRESTIGE_DISABLED,
                    service.edit(player, SkillId.POWER, EditMode.SET, 1, 1).status());
            assertEquals(EditStatus.PRESTIGE_OUT_OF_RANGE,
                    service.edit(player, SkillId.MINING, EditMode.SET, 1, 3).status());
            assertTrue(repository.load(player).isMissing());
        }
    }

    @Test
    void rejectsPowerReductionThatWouldInvalidateSpentPointLedger() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionAdminService service =
                    new NativeProgressionAdminService(repository, catalog, List::of);
            UUID player = UUID.randomUUID();
            service.edit(player, SkillId.POWER, EditMode.SET, 20, null);
            repository.savePointBalance(player, 8L, 15L);

            assertEquals(EditStatus.POINT_LEDGER_CONFLICT,
                    service.edit(player, SkillId.POWER, EditMode.SET, 0, null).status());
            assertEquals(20, repository.load(player).orElseThrow()
                    .skills().get(SkillId.POWER).level());
        }
    }

    @Test
    void levelDecreaseStripsOwnedNodesAboveNewLevelAndRefundsStoredCost() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        SkillNode low = new SkillNode(
                "A", "A", 10, SkillRole.MAIN, null, null, "STONE", 1, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
        SkillNode high = new SkillNode(
                "B", "B", 30, SkillRole.MAIN, "A", null, "STONE", 2, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
        SkillTree mining = new SkillTree(
                SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", null,
                Map.of("A", low, "B", high));
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionAdminService service =
                    new NativeProgressionAdminService(repository, catalog, () -> List.of(mining));
            UUID player = UUID.randomUUID();
            service.edit(player, SkillId.MINING, EditMode.SET, 40, null);
            repository.savePointBalance(player, 5L, 3L);
            assertTrue(repository.unlockPerk(player, "mining_perk_a", 0L));
            // Seed owned high node without changing the ledger again.
            repository.savePointBalance(player, 5L, 3L);
            assertTrue(repository.unlockPerk(player, "mining_perk_b", 0L));
            repository.savePointBalance(player, 5L, 3L);

            var lowered = service.edit(player, SkillId.MINING, EditMode.SET, 20, null);
            assertEquals(EditStatus.APPLIED, lowered.status());
            assertEquals(20, lowered.after().level());
            assertEquals(Set.of("mining_perk_a"), repository.loadPerkIds(player).orElseThrow());
            assertFalse(repository.loadPerkIds(player).orElseThrow().contains("mining_perk_b"));
            // B was unlocked for a STORED cost of 0 (free), so the refund is its stored cost 0 — NOT
            // the current YAML cost (2). spent stays 3; no phantom points are minted.
            assertEquals(3L, repository.load(player).orElseThrow().spentPoints());
        }
    }

    @Test
    void prestigeReconciliationDoesNotDeleteOrdinaryNgPrefixedNode() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        Prestige prestige = new Prestige(true, 100, "Prestige", "", Map.of(), Map.of(), 2);
        SkillTree mining = new SkillTree(
                SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", prestige, Map.of());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionAdminService service =
                    new NativeProgressionAdminService(repository, catalog, () -> List.of(mining));
            UUID player = UUID.randomUUID();
            repository.savePointBalance(player, 10L, 0L);
            assertTrue(repository.unlockPerk(player, "mining_perk_ng_bonus", 1L));
            assertTrue(repository.unlockPerk(player, "mining_perk_ng01", 1L));
            assertTrue(repository.unlockPerk(player, "mining_perk_ng0", 1L));

            assertEquals(EditStatus.APPLIED,
                    service.edit(player, SkillId.MINING, EditMode.SET, 10, 1).status());
            assertEquals(java.util.Set.of(
                            "mining_perk_ng_bonus", "mining_perk_ng01",
                            "mining_perk_ng0", "mining_perk_ng1"),
                    repository.loadPerkIds(player).orElseThrow());
        }
    }
}
