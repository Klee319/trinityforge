# Stabilize Forks Artifacts

**Task:** Phase 4 — versioned API artifact + prevent thin JAR mis-distribution

## Files Changed
- `TrinityForge/build.gradle.kts` (`jar` classifier `thin`, `apiJar`, `releaseAssembly`)
- `external/ArsPaper/build.gradle.kts` (prefer synced `libs/TrinityForge.jar` / release API)
- `TrinityForge/README.md`

## Details
- Deployable artifact remains shadow `*-all.jar` only; plain jar is classifier `thin`.
- `apiJar` packages public integration/PDC/API classes for fork compileOnly use.
- `releaseAssembly` stages `build/release/TrinityForge-all.jar` + `TrinityForge-api.jar` and syncs the API jar into:
  - `fork-handoff/arspaper/fork/libs/TrinityForge.jar`
  - `fork-handoff/elitemobs/elitemobs-fork/libs/TrinityForge.jar`
  - `fork-handoff/dpschecker/fork/libs/TrinityForge.jar`
  - `external/ArsPaper/libs/TrinityForge.jar`
- Guards against copying the thin jar into `build/release/`.

## Verification
- `./gradlew test shadowJar releaseAssembly` — BUILD SUCCESSFUL
