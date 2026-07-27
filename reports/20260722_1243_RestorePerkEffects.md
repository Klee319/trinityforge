# Restore Perk Effects

**Task:** Phase 3 — register all 41 `native:` keys and wire runtime consumers so GUI text matches effects

## Files Changed
- `NativeRewardRegistry.java` + `NativeRewardRegistryContractTest.java`
- `NativeAttributeBridge.java`, `PerkAttributeApplier.java` (merge native + armor attrs)
- `NativeSurvivalPerkListener.java`, `NativeCombatPerkListener.java`
- `ArsNativeBridge.java` (Services registration)
- `NativeProgressionService.java` (all-skill EXP multiplier)
- `CombatListener.java` (weapon cooldown reduction)
- `WeaponCoatingListener.java` (native coating charge bonus)
- `TrinityForge.java` (wiring)
- `docs/NATIVE_PROGRESSION_WIRING_MATRIX.md`

## Details
- Contract test scans every skilltree YAML `native:` map; unregistered keys fail the build (41/41).
- Attribute-like POWER/weapon keys apply via vanilla Attribute modifiers on join/reload/unlock.
- Survival: regen boost, hunger-save chance, entity drop/EXP multiply, all-skill EXP multiply.
- Combat: parry while blocking, knockback, stun, aerial power-attack (+radius splash), archery ammo save / pierce / velocity / inaccuracy / charged knockback / CD.
- Coating unlock remains dedicated-effect gated; charges add from native totals (includes alchemy B-beta-2 +5 path).
- Ars Magic native totals exposed via `ArsNativeBridge` service for soft-depend forks (dedicated-effects remain primary unlock path where already present).
