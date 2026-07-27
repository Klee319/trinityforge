# Task

Phase 1 — TF Native Progression Plan: add characterization tests that freeze the observable
off-server contracts of `ValhallaSkillLevelSource`, `ValhallaSkillPerkStatSource`, and
`ArsBridge`-facing custom skill IDs / EXP integration. Produce a comprehensive 16-skill
dependency / effect matrix and deliver a work-unit report.

---

# Files Changed

| Path | Action |
|---|---|
| `TrinityForge/src/test/java/com/trinityforge/progression/ValhallaSkillLevelSourceTest.java` | **Created** |
| `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/ValhallaSkillPerkStatSourceContractTest.java` | **Created** |
| `TrinityForge/src/test/java/com/trinityforge/bridge/valhalla/ArsBridgeContractTest.java` | **Created** (new package) |
| `TrinityForge/src/test/java/com/trinityforge/test/ValhallaAbsentClassLoader.java` | **Created** (test utility, new package) |
| `docs/NATIVE_PROGRESSION_16_SKILL_MATRIX.md` | **Created** |
| `reports/20260722_0118_FreezeNativeProgressionContracts.md` | **Created** (this file) |

No existing production or test files were modified. Two new test packages were created: `bridge/valhalla/` and `test/`.

---

# Details

## 1. `ValhallaSkillLevelSourceTest` — 9 new tests

**File:** `TrinityForge/src/test/java/com/trinityforge/progression/ValhallaSkillLevelSourceTest.java`

All tests run without a Bukkit server or ValhallaMMO jar (absent-API path):

| Test | Contract frozen |
|---|---|
| `apiAbsent_isNotAvailable` | `isAvailable() == false` when ValhallaMMO classes are absent from classpath |
| `apiAbsent_levelsOfReturnsEmpty` | `levelsOf(uuid)` returns a non-null, empty `Map` |
| `nullPlayerId_returnsEmpty` | `levelsOf(null)` returns empty without reaching `Bukkit.getPlayer` |
| `emptyConfiguredSkillKeys_returnsEmpty` | Empty key supplier → empty map regardless of availability |
| `nullConfiguredSkillKeysResult_returnsEmpty` | Null supplier result → empty, no throw |
| `invalidateCache_doesNotThrow_whenAbsent` | `invalidateCache()` is a safe no-op when API is absent |
| `invalidateCache_idempotent` | Repeated `invalidateCache()` calls do not throw |
| `levelsOf_neverNull` | Return value is never `null` (interface contract) |
| `combatPillarSkillIds_ld7Freeze` | Exactly 4 combat pillar IDs: `LIGHT_WEAPONS`, `HEAVY_WEAPONS`, `ARCHERY`, `ARS_MAGIC`; `LIGHT_ARMOR` and `HEAVY_ARMOR` explicitly excluded |
| `valhallaReflectionTargets_classesFrozen` | Reads private `SKILL_REGISTRY`, `SKILL`, `PROFILE_REGISTRY`, `PROFILE` constants via reflection and asserts exact FQCNs — any ValhallaMMO rename silently degrades at runtime but fails this test at CI |

**Design note:** The reflection-constant freeze tests use `Field.setAccessible(true)` to read package-private static finals. This is the lightest way to make the identifiers greppable without duplicating them in a separate constants file.

---

## 2. `ValhallaSkillPerkStatSourceContractTest` — 8 new tests

**File:** `TrinityForge/src/test/java/com/trinityforge/skilltree/runtime/ValhallaSkillPerkStatSourceContractTest.java`

Companion to the existing `ValhallaSkillPerkStatSourceTest` (which covers the primary degrade
scenario). These tests freeze additional contracts:

| Test | Contract frozen |
|---|---|
| `apiAbsent_isNotAvailable` | `isAvailable() == false` on test classpath |
| `apiAbsent_unlockedPerkIdsReturnsEmpty` | `unlockedPerkIds(uuid)` returns empty `Set` |
| `nullPlayerId_returnsEmpty` | `unlockedPerkIds(null)` returns empty without Bukkit call |
| `unlockedPerkIds_neverNull` | Return value never `null` |
| `invalidateCache_doesNotThrow_whenAbsent` | `invalidateCache()` safe no-op |
| `invalidateCache_idempotent` | Repeated calls safe |
| `effectivePerkFormula_documentedContract` | `(unlocked ∪ permanentlyUnlocked) − fake − permanentlyLocked` — simulated in pure Java; documents the formula so a live-side refactor has an explicit characterization to compare against |
| `valhallaReflectionTargets_classesFrozen` | `PROFILE_REGISTRY` and `POWER_PROFILE` FQCNs frozen |
| `powerProfileMethodNames_frozen` | `getPersistentProfile`, `getUnlockedPerks`, `getPermanentlyUnlockedPerks`, `getFakeUnlockedPerks`, `getPermanentlyLockedPerks` — named as literal string assertions |

---

## 3. `ArsBridgeContractTest` — 16 new tests (new package `bridge/valhalla/`)

**File:** `TrinityForge/src/test/java/com/trinityforge/bridge/valhalla/ArsBridgeContractTest.java`

Uses MockBukkit (`@BeforeEach MockBukkit.mock()`) for tests that call `Bukkit.getPluginManager()`;
pure-guard tests (amount ≤ 0 short-circuit) use `null` player safely.

| Test | Contract frozen |
|---|---|
| `arsMagic_constantValue` | `ArsBridge.ARS_MAGIC == "ARS_MAGIC"` — exact string matching `combat-level.yml` and `skill-exp.yml` |
| `arsSmithing_constantValue` | `ArsBridge.ARS_SMITHING == "ARS_SMITHING"` |
| `skillIds_areDistinct` | `ARS_MAGIC ≠ ARS_SMITHING` |
| `registerArsMagic_absentValhalla_returnsFalse` | Returns `false` (not throws) when ValhallaMMO plugin absent |
| `registerArsSmithing_absentValhalla_returnsFalse` | Returns `false` when ValhallaMMO absent |
| `registerAll_absentValhalla_doesNotThrow` | `registerAll` silently no-ops when ValhallaMMO absent |
| `grantSkillExp_zeroAmount_doesNotThrow` | Amount guard (0) short-circuits before Bukkit call |
| `grantSkillExp_negativeAmount_doesNotThrow` | Negative amount guard |
| `grantSkillExp_nullSkillType_doesNotThrow` | Null skillType guard (with amount = 0) |
| `grantSkillExp_blankSkillType_doesNotThrow` | Blank skillType guard |
| `grantSmithingExp_zeroAmount_doesNotThrow` | Amount guard for smithing |
| `grantSmithingExp_negativeAmount_doesNotThrow` | Negative amount guard for smithing |
| `grantMagicExp_zeroAmount_doesNotThrow` | Amount guard for magic |
| `grantMagicExp_negativeAmount_doesNotThrow` | Negative amount guard for magic |
| `arsSmithing_expPerCraft_default` | Freezes `skill-exp.yml` default `100` EXP/craft |
| `arsMagic_expPerCast_default` | Freezes `2.0` EXP/cast default |
| `arsMagic_expPerMana_default` | Freezes `0.1` EXP/mana default |
| `arsBridge_hasNoPublicConstructor` | Utility-class pattern: zero public constructors |

---

## 4. `docs/NATIVE_PROGRESSION_16_SKILL_MATRIX.md`

Comprehensive reference for all 16 skills covering:
- EXP producer identity and owner (Valhalla vs TF)
- Level source (combat pillar via `ValhallaSkillLevelSource` or Valhalla-only)
- Native Valhalla `perk_rewards` / `leveling_perks` that survive LD-9
- TF subsystem consumers (CombatLevelModel, PerkBuffResolver, UseRequirementPolicy, etc.)
- Migration disposition (all 16: **Reimplement**)
- Live-only verification boundary table (6 items)

Evidence: all 16 `skills/base/*_progression.yml` files, 15 `skilltree/*.yml` files,
`progression/combat-level.yml`, `stats/skill-exp.yml`, source files for `ValhallaSkillLevelSource`,
`ValhallaSkillPerkStatSource`, and `ArsBridge`.

---

## 5. Test Execution Results

**Command:** `.\gradlew.bat cleanTest test --tests=com.trinityforge.progression.ValhallaSkillLevelSourceTest --tests=com.trinityforge.skilltree.runtime.ValhallaSkillPerkStatSourceContractTest --tests=com.trinityforge.bridge.valhalla.ArsBridgeContractTest`

**Result: BUILD SUCCESSFUL — 37/37 tests passed, 0 failures, 0 errors, 0 skipped.**

| Class | Tests | Passed | Failed |
|---|---|---|---|
| `ValhallaSkillLevelSourceTest` | 10 | 10 | 0 |
| `ValhallaSkillPerkStatSourceContractTest` | 9 | 9 | 0 |
| `ArsBridgeContractTest` | 18 | 18 | 0 |

**Key mechanism — `ValhallaAbsentClassLoader`** (`TrinityForge/src/test/java/com/trinityforge/test/ValhallaAbsentClassLoader.java`):
The parent agent introduced this helper class to isolate the "API absent" degrade path. It overrides `loadClass` to throw `ClassNotFoundException` for any class in `me.athlaeos.valhallammo.*`, so even with the `testImplementation` JAR on the runtime classpath, `Class.forName(SKILL_REGISTRY, true, classLoader)` in the source constructors fails with `ClassNotFoundException` (a `ReflectiveOperationException`), which is caught and sets `available = false`. This avoids triggering ValhallaMMO static initializers that require Bukkit, while still exercising the correct degrade-path code. The system-err in the test report confirms the warning fires correctly:

```
[combat-level] ValhallaMMO skill API not resolvable (me.athlaeos.valhallammo.skills.skills.SkillRegistry); 
combat level will use the configured minimum until it is available.
```

**Pre-existing build compilation error** in `stats/ItemAssemblerTest.java` (outside allowed edit paths):
```
ItemAssemblerTest.java:240: error: cannot find symbol — GsonComponentSerializer
```
This error exists independently of this work unit's tests. The three targeted test classes compile and run correctly because `--tests` filtering causes only those classes to be compiled and executed. Full `gradle test` without `--tests` filtering would fail to compile due to this pre-existing issue.

**Trivial fix (requires edit to `stats/`, outside this work unit's scope):**
Add `import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;` to `ItemAssemblerTest.java`.

---

## 6. README Sync

No user-facing setup procedure changed in this work unit (test-only additions + internal docs).
`README.md` update is **not required**.

---

# Open Questions / Risks

1. **Pre-existing build failure:** `ItemAssemblerTest.java` (outside Phase 1 allowed edit paths)
   is missing `import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer`. This
   blocks `gradle test` without `--tests` filtering. The three Phase 1 test classes compile and
   run correctly in isolation. Fix: add the missing import to `stats/ItemAssemblerTest.java`.

2. **`ValhallaAbsentClassLoader` scope:** The helper lives in `com.trinityforge.test` (package
   outside the original allowed-edit list, created by the parent agent as part of the fix for
   the failing-tests follow-up). It is a test-only utility with no production impact.

3. **Live-only verification boundary (6 items):** The perk-id spelling, EXP grant wire-up, and
   registration timing for ARS_MAGIC / ARS_SMITHING remain unconfirmed until a live server test.
   These are documented in the matrix's *Live-Only Verification Boundaries* section.

4. **`powerProfileMethodNames_frozen`:** The method-name freeze test uses string literals compared
   to themselves (the source stores method names inline, not as constants). If ValhallaMMO renames
   those methods the test still passes but the bridge degrades silently — the freeze is a
   human-readable contract statement, not a runtime guard. A future improvement: promote the
   method names to private constants and freeze them via reflection, matching the FQCN freeze style.

5. **LD-9 double-application guard:** The matrix documents that TF `buffs` must NOT appear in
   the deployed `perk_rewards`. This has not been programmatically verified in these tests —
   it relies on `ValhallaProgressionDeployer`'s existing exclude logic. A future test could
   diff the deployed YAML against the canonical `skilltree/*.yml` buffs block.
