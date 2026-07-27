# Task

Phase 1 hardening: progression schema v2 with stored perk purchase costs, prestige refunds from stored costs (not live YAML), resilient EXP dispatcher drain, and shutdown ordering so the SQLite repository always closes.

# Files Changed

- `TrinityForge/src/main/java/com/trinityforge/progression/repository/ProgressionRepository.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/infrastructure/sqlite/SqliteProgressionRepository.java`
- `TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativePerkService.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/NativeExperienceDispatcher.java`
- `TrinityForge/src/main/java/com/trinityforge/TrinityForge.java`
- `TrinityForge/src/test/java/com/trinityforge/progression/infrastructure/SqliteProgressionRepositoryTest.java`
- `TrinityForge/src/test/java/com/trinityforge/progression/audit/NativeProgressionStabilizationContractsTest.java`

# Details

## LoadResult fail-closed (already done before this task)

`SqliteProgressionRepository.load` / `loadPerkIds` return `LoadResult.failed` when the repository is closed or SQL fails; unknown players remain `LoadResult.missing()`. Callers (e.g. `NativePerkService`, `NativeProgressionService`) must not treat `failed()` as empty progression.

## Schema v2 + `purchase_cost`

- `SCHEMA_VERSION = 2`.
- New databases create `player_perk_states` with `purchase_cost INTEGER NOT NULL DEFAULT 0`.
- On open: read `schema_version`; empty table inserts version 2; version 1 runs `ALTER TABLE … ADD COLUMN purchase_cost …` and bumps version to 2.
- `unlockPerk` persists `purchase_cost = pointCost` on insert.
- `ProgressionRepository.loadPerkCosts(UUID)` returns `LoadResult<Map<String, Long>>` (perk id → stored cost).

**Migration backfill:** existing v1 unlock rows keep `purchase_cost = 0` after migration. Prestige refund for those rows therefore refunds 0 until the player unlocks again post-v2 (when cost is stored). No YAML backfill at migration time (simplicity).

## Prestige refund

`NativePerkService.prestige` sums stored costs from `loadPerkCosts` for owned ordinary tree nodes; live `node.cost()` is no longer used for refund.

## EXP dispatcher

`drain()` processes each pending entry in its own try/catch. `IllegalStateException` re-queues the entry for up to 3 attempts within the same drain; `IllegalArgumentException` is logged and dropped. Sibling entries continue.

## Shutdown

`TrinityForge.onDisable` wraps `experienceDispatcher.close()` in `try` and runs `progressionRepository.close()` in `finally` so the DB closes even if dispatcher shutdown throws.

# Verification

```text
.\gradlew test --no-daemon
BUILD SUCCESSFUL
```

New/updated tests:

- `unlockPerk_persistsPurchaseCost_roundTripsViaLoadPerkCosts`
- `schemaV1Database_migratesPurchaseCostColumn`
- `desired_prestigeRefundUsesStoredPurchaseCost`
- `dispatcherIsolatesBatchEntriesAndRetriesTransientFailures`

Pre-v2 characterization test `prestigeRefundUsesLiveYamlCost_characterizesMutableConfigRefund` disabled (superseded).
