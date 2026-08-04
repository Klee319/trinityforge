# Native Progression Wiring Matrix (Stabilization)

**Status:** Post Medium+ EXP/save hardening (2026-07-22).

## Authority

| Domain | Authority | Reload |
|---|---|---|
| Curves / caps / gathering tables / armor·smith·enchant·alchemy·fishing scalars | `plugins/TrinityForge/skills/base/*_progression.yml` | `/tf reload` atomic |
| Light/heavy/archery weapon kill EXP + Ars craft/magic event EXP | `stats/skill-exp.yml` | `/tf reload` |
| Skill trees / buffs / native maps | `skilltree/*.yml` | `/tf reload` (abort if skill lost) |
| Persistence | SQLite via `Cached → Executor → Sqlite` | — |

## EXP producers

| Skill | Source | Config keys |
|---|---|---|
| LIGHT/HEAVY/ARCHERY | confirmed mob kill | `combat.kill-exp.*`（N5 / 2026-07-31: ARCHERY も命中トリガから討伐トリガへ統一。旧 `bow_exp_base` 等の per-hit 係数は削除済み） |
| LIGHT/HEAVY_ARMOR | damage taken | `exp_damage_piece`, `exp_multiplier_point`, entity/PvP scalars |
| POWER | other skill level-ups | `experience.exp_gain` |
| MINING/DIGGING/WOODCUTTING/FARMING | block break | tables; mining `exp_multiplier_mine`; **block listed + drop material listed**; Fortune stack size ignored (unique mats) |
| MINING (blast) | TNT explode (player-sourced) | same tables × `exp_multiplier_blast` |
| FARMING | harvest/breed/entity drops/shear | matching editable action table |
| WOODCUTTING | log stripping | `woodcutting_strip` |
| ALCHEMY | brew | recipe/result/ingredient table × manual/auto × quality |
| FISHING | catch | `fishing_catch.*` / `fishing_catch_exp` |
| SMITHING | completed equipment craft | `smithing.exp-per-craft` |
| ENCHANTING | enchant table | nested `exp_gain` base/level/material/item tables + spent-level conversion |
| ARS_SMITHING | completed Ars equipment craft | `ars-smithing.exp-per-craft` + use-level scaling |
| ARS_MAGIC | magic kill + magic block break | `ars-magic.kill-exp`, `ars-magic.block-break-exp` |

All grants × `(1 + power_allskillexpmultiplier_add)`.

## Native rewards

41 keys registered (`NativeRewardRegistry`). Combat/survival/attribute/coating/Ars consumers wired. ArsPaper reads `ArsNativeBridge` for mana/glyph/tier/regen addends (plus dedicated-effects).

## Persistence

- `LoadResult` fail-closed; failed snapshot does not invent starting points.
- Prestige refund: stored `purchase_cost`, else current YAML `node.cost()` for pre-v2 rows.
- Single-thread DB executor serializes SQLite.
