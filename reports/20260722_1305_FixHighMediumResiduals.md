# Fix High–Medium Residual Progression Issues

**Task:** Close remaining High/Medium audit findings (Fortune EXP, blast EXP, alchemy auto/manual, curve recalc, preload, locks, dead commands, Ars drift, charged-shot gate).

## Files Changed (summary)
- `NativeSkillExperienceListener` — Fortune-safe unique drop materials; TNT blast mining × `exp_multiplier_blast`; brew manual/auto via hoppers; armor piece flat without `*0.01`
- `ProgressionCurveReconciler` + `/tf reload` — rewrite levels from stored total EXP after curve/cap change; reconcile POWER points
- `ProgressionRepository.listPlayerIds` (+ Sqlite/Executor/Cached)
- `CachedProgressionRepository` — no monitor across DB I/O; `preload`
- `ProgressionPreloadListener` — async join warm
- `NativeProgressionService` — per-player locks (no `synchronized(repository)` across executor)
- `NativeCombatPerkListener` / `NativePerkRewardResolver` — charged-shot gate + boolean toggles; stronger stun
- `NativeAttributeBridge` — set bonus at ≥2 pieces; setamount amplifies
- skilltree `commands:` stripped (dedicated-effects remain); base YAML headers de-Valhalla’d
- `light/heavy_armor_progression.yml` — TF-scaled `exp_damage_piece: 0.25`
- `external/ArsPaper` ManaManager + TrinityForgeBridge — native mana/regen (+ glyph/tier helpers)
- Tests: `GatheringExpFortuneSafeTest`

## Verification
`./gradlew test` — BUILD SUCCESSFUL

## Report
`reports/20260722_1305_FixHighMediumResiduals.md`
