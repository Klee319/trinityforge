# Fix Config Ineffective EXP and Save (Medium+)

**Task:** Close Critical–Medium audit findings: dead EXP config, hardcoded producers, async SQLite races, archery no-op commands, Ars native consumption, docs/editor authority.

## Files Changed
- `NativeSkillCatalog` / `SkillCatalogEntry` — scalar `rates` (POWER/enchant/smithing/armor/alchemy/fishing/mining mults)
- `NativeSkillExperienceListener` — catalog-driven producers + mining block+drop gate
- `NativeProgressionService` / `NativeProgressionAdminService` — POWER EXP from `experience.exp_gain`
- `ExecutorProgressionRepository` + TrinityForge wiring (Cached → Executor → Sqlite)
- `NativePerkService` — prestige refund falls back to YAML cost when stored cost is 0
- `alchemy_progression.yml` / `fishing_progression.yml` — explicit TF keys; alchemy legacy daily_* moved
- `skilltree/archery.yml` — removed dead arrow `commands`
- `stats/skill-exp.yml`, `tools/config-editor/.../tf-forms.js` — combat authority clarified; dead editor knobs removed
- ArsPaper fork: `TrinityForgeBridge` + `ManaManager` consume `ArsNativeBridge` for mana/glyph/tier/regen
- Docs: wiring matrix + this report

## Details
| Former hardcode | Now |
|---|---|
| armor `*0.05` | `exp_multiplier_point` (+ optional `exp_damage_piece`) |
| smithing `damage*0.01` | `durability_tools/armors_exp_multiplier_stack` |
| enchant `*0.5` | `exp_gain.experience_spent_conversion` |
| alchemy `25` | `alchemy_brew_exp` × `multiplier_manual` |
| fishing fallback `20` | `fishing_catch_exp` |
| POWER `100` | `experience.exp_gain` on power YAML |

Mining grants only when block and at least one drop material are listed (empty drops → block amount).

SQLite calls serialize on `trinityforge-progression-db` thread.
