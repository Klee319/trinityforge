# Task

Phase 0 contract freeze for Native Progression Stabilization: characterization tests,
wiring matrix, and admin level-down node stripping (user follow-up).

# Files Changed

- `docs/NATIVE_PROGRESSION_WIRING_MATRIX.md` (new living wiring matrix)
- `TrinityForge/src/test/java/com/trinityforge/progression/audit/NativeProgressionStabilizationContractsTest.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/NativeExperienceDispatcher.java` (test ctor)
- `TrinityForge/src/main/java/com/trinityforge/progression/NativeProgressionAdminService.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/repository/ProgressionRepository.java`
- `TrinityForge/src/main/java/com/trinityforge/progression/infrastructure/sqlite/SqliteProgressionRepository.java`
- `TrinityForge/src/test/java/com/trinityforge/progression/NativeProgressionAdminServiceTest.java`

# Details

- Froze fail-open `load`/`loadPerkIds` after close, classpath-only catalog size=16,
  prestige refund = live YAML cost, and dispatcher batch drop-on-exception.
- Documented 16 EXP producers and 40 native reward keys with LIVE vs INERT disposition.
- Admin level decrease now strips owned ordinary nodes with `node.level > newLevel` and
  refunds current YAML cost in the same SQLite transaction (user-requested change).

# Verification

- `NativeProgressionStabilizationContractsTest` + `NativeProgressionAdminServiceTest` green
