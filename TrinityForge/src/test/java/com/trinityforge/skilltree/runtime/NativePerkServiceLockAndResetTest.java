package com.trinityforge.skilltree.runtime;

import com.trinityforge.progression.NativeProgressionService;
import com.trinityforge.progression.catalog.NativeSkillCatalog;
import com.trinityforge.progression.core.PlayerProgression;
import com.trinityforge.progression.core.SkillId;
import com.trinityforge.progression.core.SkillProgress;
import com.trinityforge.progression.infrastructure.sqlite.SqliteProgressionRepository;
import com.trinityforge.skilltree.Prestige;
import com.trinityforge.skilltree.SkillNode;
import com.trinityforge.skilltree.SkillRole;
import com.trinityforge.skilltree.SkillTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * スキルノードロック（プレステージ後も維持）とスキルツリーリセット（レベル維持・SP返却）
 * — 2026-07-27 追加の機能アイテム2種が依存する進行系の契約。
 */
class NativePerkServiceLockAndResetTest {

    private static SkillNode node(String id, int cost) {
        return new SkillNode(id, id, 10, SkillRole.MAIN, null, null, "STONE", cost, "",
                Map.of(), Map.of(), List.of(), List.of(), List.of());
    }

    private static SkillTree miningTree() {
        Prestige prestige = new Prestige(true, 10, "P", "", Map.of(), Map.of(), 1);
        return new SkillTree(SkillId.MINING, "Mining", "IRON_PICKAXE", "2,10", prestige,
                Map.of("A", node("A", 2), "B", node("B", 3)));
    }

    /** 残高10 / ノードA(2)・B(3)を解放済み / MINING Lv10 の状態を作る。 */
    private static void seed(SqliteProgressionRepository repository, UUID player) {
        repository.savePointBalance(player, 10L, 0L);
        assertTrue(repository.unlockPerk(player, "mining_perk_a", 2L));
        assertTrue(repository.unlockPerk(player, "mining_perk_b", 3L));
        repository.saveSkillProgress(player, SkillId.MINING,
                new SkillProgress(10, 0.0, 1000.0, 0, 100));
    }

    @Test
    @DisplayName("ロックしたノードはプレステージ後も解放済みのまま残り、SPは返却されない")
    void lockedPerkSurvivesPrestigeWithoutRefund() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            NativePerkService perks = new NativePerkService(progression, () -> List.of(miningTree()));
            perks.setLockedPerkSupplier(id -> Set.of("mining_perk_a"));
            UUID player = UUID.randomUUID();
            seed(repository, player);

            assertEquals(NativePerkService.PrestigeResult.PRESTIGED,
                    perks.prestige(player, SkillId.MINING));

            Set<String> owned = repository.loadPerkIds(player).orElseThrow();
            assertTrue(owned.contains("mining_perk_a"), "ロック済みノードは維持される");
            assertFalse(owned.contains("mining_perk_b"), "ロックしていないノードは剥がれる");
            assertTrue(owned.contains("mining_perk_ng1"), "プレステージperkは付与される");

            PlayerProgression after = repository.load(player).orElseThrow();
            // 返却はBの3のみ。Aを返却してから無償再付与すると、払っていないSPが増える。
            assertEquals(8L, after.availablePoints());
            assertEquals(2L, after.spentPoints());
            assertEquals(0, after.skills().get(SkillId.MINING).level(), "プレステージはレベルを0に戻す");
            assertEquals(1, after.skills().get(SkillId.MINING).prestige());
        }
    }

    @Test
    @DisplayName("ロック供給元を配線しなければ従来どおり全ノードが剥がれて全額返却される")
    void withoutLockSupplierPrestigeIsUnchanged() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            NativePerkService perks = new NativePerkService(progression, () -> List.of(miningTree()));
            UUID player = UUID.randomUUID();
            seed(repository, player);

            assertEquals(NativePerkService.PrestigeResult.PRESTIGED,
                    perks.prestige(player, SkillId.MINING));

            Set<String> owned = repository.loadPerkIds(player).orElseThrow();
            assertFalse(owned.contains("mining_perk_a"));
            assertFalse(owned.contains("mining_perk_b"));
            PlayerProgression after = repository.load(player).orElseThrow();
            assertEquals(10L, after.availablePoints());
            assertEquals(0L, after.spentPoints());
        }
    }

    @Test
    @DisplayName("ツリーリセットはレベルとプレステージ段を維持したままノードを全解除しSPを返却する")
    void resetTreeKeepsLevelAndRefundsPoints() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            NativePerkService perks = new NativePerkService(progression, () -> List.of(miningTree()));
            UUID player = UUID.randomUUID();
            repository.savePointBalance(player, 10L, 0L);
            assertTrue(repository.unlockPerk(player, "mining_perk_a", 2L));
            assertTrue(repository.unlockPerk(player, "mining_perk_b", 3L));
            repository.saveSkillProgress(player, SkillId.MINING,
                    new SkillProgress(10, 4.0, 1000.0, 2, 100));

            assertEquals(NativePerkService.ResetResult.RESET,
                    perks.resetTree(player, SkillId.MINING));

            Set<String> owned = repository.loadPerkIds(player).orElseThrow();
            assertFalse(owned.contains("mining_perk_a"));
            assertFalse(owned.contains("mining_perk_b"));
            PlayerProgression after = repository.load(player).orElseThrow();
            assertEquals(10L, after.availablePoints(), "消費したSPが全額戻る");
            assertEquals(0L, after.spentPoints());
            SkillProgress skill = after.skills().get(SkillId.MINING);
            assertEquals(10, skill.level(), "レベルは維持される");
            assertEquals(2, skill.prestige(), "プレステージ段も維持される");
        }
    }

    @Test
    @DisplayName("解除できるノードが無ければリセットは何もしない")
    void resetTreeWithNoPerksIsNoop() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            NativePerkService perks = new NativePerkService(progression, () -> List.of(miningTree()));
            UUID player = UUID.randomUUID();
            repository.savePointBalance(player, 10L, 0L);
            repository.saveSkillProgress(player, SkillId.MINING,
                    new SkillProgress(10, 0.0, 1000.0, 0, 100));

            assertEquals(NativePerkService.ResetResult.NOTHING_TO_RESET,
                    perks.resetTree(player, SkillId.MINING));
            assertEquals(10L, repository.load(player).orElseThrow().availablePoints());
        }
    }

    @Test
    @DisplayName("未知のスキルIDはリセット対象外")
    void resetTreeUnknownSkill() throws Exception {
        NativeSkillCatalog catalog = NativeSkillCatalog.load(getClass().getClassLoader());
        try (SqliteProgressionRepository repository =
                     new SqliteProgressionRepository("jdbc:sqlite::memory:")) {
            NativeProgressionService progression = new NativeProgressionService(repository, catalog);
            NativePerkService perks = new NativePerkService(progression, () -> List.of(miningTree()));
            assertEquals(NativePerkService.ResetResult.UNKNOWN_SKILL,
                    perks.resetTree(UUID.randomUUID(), "NOT_A_SKILL"));
        }
    }
}
