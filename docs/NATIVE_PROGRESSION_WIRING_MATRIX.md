# Native Progression Wiring Matrix (Stabilization)

**Status:** Post Medium+ EXP/save hardening (2026-07-22).

## Authority

| Domain | Authority | Reload |
|---|---|---|
| Curves / caps / gathering tables / armor·smith·enchant·alchemy·fishing scalars | `plugins/TrinityForge/skills/base/*_progression.yml` | `/tf reload` atomic |
| Combat weapon hit EXP + Ars craft/cast EXP | `stats/skill-exp.yml` | `/tf reload` |
| Skill trees / buffs / native maps | `skilltree/*.yml` | `/tf reload` (abort if skill lost) |
| Persistence | SQLite via `Cached → Executor → Sqlite` | — |

## EXP producers

| Skill | Source | Config keys |
|---|---|---|
| LIGHT/HEAVY/ARCHERY | CombatListener | `combat.exp-per-hit` / `combat.by-skill` |
| LIGHT/HEAVY_ARMOR | damage taken | `exp_multiplier_point`, `exp_damage_piece` |
| POWER | other skill level-ups | `experience.exp_gain` |
| MINING/DIGGING/WOODCUTTING/FARMING | block break | tables; mining `exp_multiplier_mine`; **block listed + drop material listed**; Fortune stack size ignored (unique mats) |
| MINING (blast) | TNT explode (player-sourced) | same tables × `exp_multiplier_blast` |
| ALCHEMY | brew | `alchemy_brew_exp` × manual/auto (`InventoryClick` vs hopper move) |
| FISHING | catch | `fishing_catch.*` / `fishing_catch_exp` |
| SMITHING | item durability loss | `durability_tools/armors_exp_multiplier_stack` |
| ENCHANTING | enchant table | `exp_gain.experience_spent_conversion` |
| ALCHEMY | brew | `alchemy_brew_exp` × `multiplier_manual` |
| ARS_* | ArsPaper / craft | `skill-exp.yml` ars-* |

All grants × `(1 + power_allskillexpmultiplier_add)`.

**Removed (2026-07-26):** `daily_limit*`, `daily_limit_decay_percent`, `daily_limit_warning`, `pvp_multiplier`, `is_chunk_nerfed`, `spawner_spawned_multiplier`, `max_health_limitation`, `durability_chunk_limit`, `diminishing_returns`, `mace_exp_multiplier`, `infinity_multiplier` — これら11種は ValhallaMMO 時代の名残で TF 側に消費者が1つも無く（`SkillCatalogEntry.rate()` の呼び出しは全て文字列リテラルなので literal grep 0件＝到達不能と断定できる）、`skills/base/*_progression.yml` から物理的に除去済み（66件/15ファイル、`tools/scripts/strip-dead-progression-keys.py`）。名前の似た `prestige_decay_rate` は消費者2件で**生きている**ので混同しないこと。

**Not consumed (legacy / historical):** weapon base combat tables inside `skills/base` (combat uses skill-exp only). Editor hides these.

## Native rewards

41 keys registered (`NativeRewardRegistry`). Combat/survival/attribute/coating/Ars consumers wired. ArsPaper reads `ArsNativeBridge` for mana/glyph/tier/regen addends (plus dedicated-effects).

## Persistence

- `LoadResult` fail-closed; failed snapshot does not invent starting points.
- Prestige refund: stored `purchase_cost`, else current YAML `node.cost()` for pre-v2 rows.
- Single-thread DB executor serializes SQLite.
