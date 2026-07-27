# ConfigEditor Spec Overhaul — remaining facade / follow-up gaps

This file tracks items that are partially wired so they are not mistaken for finished runtime.

調査の詳細・POWER/EliteMobs方針: [`docs/EDITOR_POWER_ELITEMOBS_PLAN.md`](../../docs/EDITOR_POWER_ELITEMOBS_PLAN.md)

## Done (runtime + editor)

- BindType `TRADEABLE` rename + legacy `MATERIAL_TRADEABLE` alias
- SOULBOUND craft/first-pickup stamp; OWNER_BOUND command-only (`/tf bind setowner|clear`)
- Non-owner **use** gate (`OwnerBindListener`); trade not blocked by design
- Catalog hub tabs (weapon/armor/tool/material/catalyst/spellbook/thread); ritual effects out of hub
- Nested `_editor.categories` meta; color + enchant-glow on catalog form; recipe method workbench|ritual
- Item-stats hub; use-level/use-skill on item-stats UI; Java `ItemUseRequirement` + `ItemFactory` prefers item-stats
- Spellbook `max-glyphs` + GUI driven off config (hard cap 9)
- `SmithingCraftProfile` + `CraftQualityService` sums Ars + smithing craft bonuses; smithing.yml B-beta native quality; allowlist keys
- Skill-exp form: gain rates + progression curves (all skills) + SVG graph; multi-PUT save of `skills/base/*_progression.yml`
- TF native progression SoT: `skills/base/` curves plus `skilltree/` node definitions
- Catalog ritual → Ars: `RecipeSpec` ritual + `CatalogRitualBridge` → `CatalogRitualRegistrar` + `tfcatalog:` resolve/stamp
- Spellbooks form `setActivePart` for catalog/item-stats hubs
- **Editor Phase1 dedicated forms** (2026-07-21): dungeon-gates / themes / mob-profiles / mob-import / hate-rates (`tf-dungeon-forms.js`)
- **Editor Phase2 dedicated forms** (2026-07-21): gathering / *-gimmick×5 / villager-trades / role-buffs (`tf-lifestyle-forms.js`)
- **Editor Phase3 dedicated forms** (2026-07-21): dedicated-effects / ars-config / ban (`tf-phase3-forms.js`)
- **Editor UX batch** (2026-07-21, in progress): materials collapse summary, catalog ritual pedestal×N, status filter rename/placement, unlock-gate add-row fix, source TileState picker + in-card feeds, glyphs collapse/readonly IDs/XP label/spellbook tiers, ritual display-name/thread select/entities/Ars enchants, ban glyph select, thread-slot-expansion value wiring
- **crafting-features overhaul** (2026-07-21): multi-material coating/wood-repair, disassembly %×level + scrap fallback, over-enchant per-effect-ID caps, brew `custom:` ingredients + BrewUnlockListener, catalog selects in editor; skill-exp collapsible JP labels + curve placeholder help
- **crafting-features review fixes** (2026-07-21): brew gate (cancel without perk) + custom: force-start + nearby unlock; over-enchant stored-enchants on anvil + table +1; FAST_DIGGING→HASTE alias; wood-repair prepare spam removed; coating min(base,mat)+perk; dismantle air-only + fractional min-1
- **POWER LD-9 + skilltree** (2026-07-21): damage layers scrubbed; `skilltree/power.yml` vertical trunk (survival/utility only); Editor `skilltree-power`; weapon/archery starting+leveling damagemultiplier scrubbed
- **EliteMobs attack/magic** (2026-07-21): mob-import/profiles `attack:` (8-stat ramps) → spawn stamp → TF melee ownership; `magicalFinalDamageFromMob`/`magicalFinalDamageFlat`; EM script DAMAGE = magical via ability markers; fork jar refreshed + compiled
- skills/ars_magic|ars_smithing display metadata remains hidden; native curves are edited on skill-exp

## Dedicated GUI rollout

| Phase | Targets | Status |
|---|---|---|
| 1 | dungeon-gates, themes, mob-profiles, mob-import, hate-rates | Done |
| 2 | gathering, *-gimmick×5, villager-trades, role-buffs | Done (`tf-lifestyle-forms.js`) |
| 3 | dedicated-effects, ars-config, ban, skills-ars_* | Done (`tf-phase3-forms.js`) |

## Still incomplete / deferred

1. **tf-skilltree dedicated quality widgets**: Nodes can use `native` / `dedicated-effects` in YAML; dedicated skilltree UI knobs for quality/spread are still the generic dedicated-effect picker (no separate quality widget).
2. **Partial UI**: `combat/damage.yml` keys outside constants FIELD_SPECS; deprecated `mob-defaults.yml`.
3. ~~**POWER / 総合**~~: `skilltree/power.yml` + Editor facade — see plan doc §2.
4. ~~**EliteMobs offense/magic**~~: attack profile + magicalFromMob implemented — remaining: gates data, theme balance, live server verification (plan doc §3).

## Verification checklist (manual)

- [ ] SOULBOUND craft binds crafter; first pickup binds unbound SOULBOUND; OWNER_BOUND needs `/tf bind setowner`
- [ ] Non-owner cannot attack/use owned soulbound item
- [ ] Catalog save writes color/enchant-glow/ritual method; ritual crafts via Ars give TF catalog item
- [ ] Item-stats use-skill gates combat
- [ ] Spellbook max-glyphs limits SpellCraftingGui
- [ ] SMITHING B-beta perk increases craft quality mode
- [ ] Skill-exp screen shows/edits curves for all progression companions; save writes `skills/base/*`
- [ ] Phase1 dungeon/hate forms open and save without comment-loss surprise (backup OK)
