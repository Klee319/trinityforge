# TrinityForge Native Progression — 16-Skill Dependency / Effect Matrix

**Purpose:** Characterization reference for Phase 1 of the TF Native Progression plan.
Each row freezes the observable contracts for one Valhalla skill: who produces EXP, who
reads the level, what native Valhalla rewards survive LD-9, which TF subsystems consume
the skill, and the migration disposition.

> **Status (2026-07-22):** Valhalla切離し前のcharacterizationを記録した履歴資料です。
> `ValhallaSkillLevelSource`等の名称は旧構成の証跡であり、現行ランタイムは
> `NativeProgressionService`＋SQLiteを権威とします。EXP Producer / Owner欄は誤設定を防ぐため
> 現行TFの付与経路に更新しています。

**Evidence sources:**
- `TrinityForge/src/main/resources/skills/base/<skill>_progression.yml` — level curve, EXP config
- `TrinityForge/src/main/resources/skilltree/<skill>.yml` — canonical node/buff/native definitions
- `TrinityForge/src/main/resources/progression/combat-level.yml` — combat pillar selection (LD-7)
- `TrinityForge/src/main/resources/stats/skill-exp.yml` — TF-owned EXP overrides
- `ValhallaSkillLevelSource.java`, `ValhallaSkillPerkStatSource.java`, `ArsBridge.java`

---

## Key to columns

| Column | Meaning |
|---|---|
| **Skill Key** | Valhalla `SkillRegistry` key (exact string used in `SkillRegistry.getSkill()`) |
| **EXP Producer** | What action awards EXP for this skill |
| **EXP Owner** | `Valhalla` = native action hook in ValhallaMMO; `TF` = `ArsBridge.grant*Exp()` called by TF/ArsPaper listener |
| **Level Source** | How TF reads the level: `ValhallaSkillLevelSource` (reflection, combat-level only), or `NOT combat` (Valhalla owns, TF does not actively read via `ValhallaSkillLevelSource`) |
| **Combat Pillar (LD-7)** | Whether this skill contributes to `CombatLevelModel` (combat-level.yml weight table) |
| **Native Valhalla Rewards** | `perk_rewards` keys written by Valhalla into its own skill pipeline (survive LD-9; TF does NOT apply these — Valhalla applies them) |
| **TF Consumers** | TF subsystems that read or depend on this skill's level/perks |
| **Migration Disposition** | What happens in the progression migration: `Reimplement` = TF overrides the perk tree via `ValhallaProgressionDeployer` and reads buffs via `PerkBuffResolver` (LD-9); `Keep native` = Valhalla's own behaviour is intentionally left in place |

---

## Matrix

### Combat Pillar Skills (LD-7) — consumed by `ValhallaSkillLevelSource` + `CombatLevelModel`

---

#### 1. LIGHT_WEAPONS

| Field | Value |
|---|---|
| **Skill Key** | `LIGHT_WEAPONS` |
| **EXP Producer** | Confirmed enemy kill attributed to a light weapon |
| **EXP Owner** | **TF** (`stats/skill-exp.yml` → `combat.kill-exp`; base + mob level + max health, then entity multiplier) |
| **Level Source** | `ValhallaSkillLevelSource` (configured in `combat-level.yml`, weight 1) |
| **Combat Pillar (LD-7)** | ✅ Yes — weight 1, `max-of-top-N` pillar |
| **Native Valhalla Rewards** | `lightweapons_attackspeedmultiplier_add` (leveling_perks +0.005/lv); parry parameters (`parryeffectiveduration`, `parryvulnerableduration`, `parrycooldown`, `parryenemydebuffduration`, `parryselfdebuffduration`, `parrydamagereduction`, `parrycooldownsuccessreduction`); `lightweapons_knockbackmultiplier_add`; `lightweapons_immunityreductionfraction_add`; `lightweapons_bleedchance_add`; `lightweapons_coatingunlocked_toggle`; `lightweapons_attackreachbonus_add`; prestige permanently-unlocked stats |
| **TF Consumers** | `CombatLevelModel` (combat-level weight), `PerkBuffResolver` (TF `buffs`: `attack-power`, `bleed-chance`, `bleed-damage`, `crit-chance`, `crit-damage`), `UseRequirementPolicy` (equip gate for light weapons) |
| **Migration Disposition** | **Reimplement** — TF deploys `skilltree/light_weapons.yml` perk tree via `ValhallaProgressionDeployer`; TF `buffs` applied by `PerkBuffResolver` (LD-9, never through `perk_rewards`); native Valhalla rewards listed above remain operative |

---

#### 2. HEAVY_WEAPONS

| Field | Value |
|---|---|
| **Skill Key** | `HEAVY_WEAPONS` |
| **EXP Producer** | Confirmed enemy kill attributed to a heavy weapon |
| **EXP Owner** | **TF** (`stats/skill-exp.yml` → `combat.kill-exp`; base + mob level + max health, then entity multiplier) |
| **Level Source** | `ValhallaSkillLevelSource` (weight 1) |
| **Combat Pillar (LD-7)** | ✅ Yes |
| **Native Valhalla Rewards** | `heavyweapons_attackspeedmultiplier_add` (starting_perks −0.3; leveling_perks); stagger/cleave parameters; knockback; bleed; parry variants |
| **TF Consumers** | `CombatLevelModel`, `PerkBuffResolver` (`attack-power`, `bleed-chance`, `bleed-damage`, `crit-chance`, `crit-damage`), `UseRequirementPolicy` |
| **Migration Disposition** | **Reimplement** |

---

#### 3. ARCHERY

| Field | Value |
|---|---|
| **Skill Key** | `ARCHERY` |
| **EXP Producer** | Arrow hits with bow / crossbow |
| **EXP Owner** | **Valhalla** (`bow_exp_base: 30`; `crossbow_exp_base: 40`; distance multiplier +0.75/10 blocks; `damage_exp_bonus: 0.1`; `pvp_multiplier: 0.1`) |
| **Level Source** | `ValhallaSkillLevelSource` (weight 1) |
| **Combat Pillar (LD-7)** | ✅ Yes |
| **Native Valhalla Rewards** | `archery_inaccuracy_add` (starting_perks +5); `archery_distancedamagebase_add` (−0.2 default); `archery_distancedamagebonus_add` (+0.1 default); arrow speed; multi-shot; crit; bleed-on-crit; piercing |
| **TF Consumers** | `CombatLevelModel`, `PerkBuffResolver` (`attack-power`, `penetration`, `crit-chance`, `crit-damage`), `UseRequirementPolicy` |
| **Migration Disposition** | **Reimplement** |

---

#### 4. ARS_MAGIC

| Field | Value |
|---|---|
| **Skill Key** | `ARS_MAGIC` (constant: `ArsBridge.ARS_MAGIC`) |
| **EXP Producer** | Enemy killed by Ars magic + block broken by an Ars spell |
| **EXP Owner** | **TF** (`ars-magic.kill-exp` + `ars-magic.block-break-exp` in `stats/skill-exp.yml`) |
| **Level Source** | `ValhallaSkillLevelSource` (weight 1); registered by `ArsBridge.registerArsMagic` |
| **Combat Pillar (LD-7)** | ✅ Yes — custom Valhalla skill, not a vanilla Valhalla skill |
| **Native Valhalla Rewards** | None — `starting_perks: {}`, `leveling_perks: {}` in `ars_magic_progression.yml` (all stats via TF `buffs`) |
| **TF Consumers** | `CombatLevelModel`, `PerkBuffResolver` (magic attack/damage/crit nodes from `skilltree/ars_magic.yml`), `ArsMagicProfile` (mana bonus, mana regen, glyph slots) |
| **Migration Disposition** | **Reimplement** — ArsPaper reports magic kill attribution and spell-driven block breaks to TF |

---

### Non-Combat Skills — Phase 1 legacy classification

---

#### 5. POWER

| Field | Value |
|---|---|
| **Skill Key** | `POWER` |
| **EXP Producer** | Static EXP grant at level-up events / admin grant |
| **EXP Owner** | **TrinityForge** (`NativeProgressionService`＋SQLite、max level 256) |
| **Level Source** | NOT a combat pillar; TF native progression snapshot |
| **Combat Pillar (LD-7)** | ❌ No |
| **Legacy Valhalla Rewards** | 旧starting/leveling perkは移植対象外。現行はPOWERレベル上昇ごとにTFネイティブの使用可能ポイントを+1 |
| **TF Consumers** | `PerkBuffResolver` (health, knockback resistance, luck, health regen, hunger-save, entity-drop multiplier, cooldown reduction via skill tree perks `a–g`), `UseRequirementPolicy` (level gate for non-combat actions) |
| **Migration Disposition** | **Completed** — `skilltree/power.yml`をTF GUI/報酬resolverが直接使用。POWERのPrestigeは無効 |

---

#### 6. LIGHT_ARMOR

| Field | Value |
|---|---|
| **Skill Key** | `LIGHT_ARMOR` |
| **EXP Producer** | Taking damage in light armor |
| **EXP Owner** | **Valhalla** (`exp_multiplier_point: 0.05`; `pvp_multiplier: 0.1`) |
| **Level Source** | NOT a combat pillar (LD-7 excluded); Valhalla owns; TF perks via `ValhallaSkillPerkStatSource` |
| **Combat Pillar (LD-7)** | ❌ No (explicitly excluded per LD-7 comment in `combat-level.yml`) |
| **Native Valhalla Rewards** | Evasion/dodge; phys resistance (light armor native stats); `lightweapons_perk_combo` cross-skill perk (requires LIGHT_WEAPONS + HEAVY_ARMOR 70) |
| **TF Consumers** | `PerkBuffResolver` (defense stats via `skilltree/light_armor.yml` TF buffs), `UseRequirementPolicy` |
| **Migration Disposition** | **Reimplement** |

---

#### 7. HEAVY_ARMOR

| Field | Value |
|---|---|
| **Skill Key** | `HEAVY_ARMOR` |
| **EXP Producer** | Taking damage in heavy armor |
| **EXP Owner** | **Valhalla** (`exp_multiplier_point: 0.05`; `pvp_multiplier: 0.1`) |
| **Level Source** | NOT a combat pillar (LD-7 excluded) |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | Stagger chance; block parameters; phys resistance; cross-skill perk with LIGHT_WEAPONS |
| **TF Consumers** | `PerkBuffResolver`, `UseRequirementPolicy` |
| **Migration Disposition** | **Reimplement** |

---

#### 8. MINING

| Field | Value |
|---|---|
| **Skill Key** | `MINING` |
| **EXP Producer** | Breaking ore/stone blocks (per-block table in `mining_progression.yml`) |
| **EXP Owner** | **TF native** (`exp_multiplier_mine`, `exp_multiplier_blast`, editable block/drop tables and `gathering.exp-mode`) |
| **Level Source** | NOT a combat pillar |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | `mining_miningspeedbonus_add`; `mining_miningdrops_add`; `mining_miningluck_add`; `mining_tntblastradius_add`; `mining_drillingunlocked_toggle`; `mining_veinminingunlocked_toggle`; `mining_blastfortunelevel_set`; `power_cookingspeedbonus_add` (cross-skill) |
| **TF Consumers** | `PerkBuffResolver` (`mining_fortune` via `skilltree/mining.yml` buffs), `UseRequirementPolicy` |
| **Migration Disposition** | **Reimplement** — cross-skill requires ENCHANTING 70 for fortune-blast perk (`other_levels_required`) |

---

#### 9. ENCHANTING

| Field | Value |
|---|---|
| **Skill Key** | `ENCHANTING` |
| **EXP Producer** | XP points spent enchanting items |
| **EXP Owner** | **Valhalla** (`experience_spent_conversion: 0.5` — half of spent XP converts to skill EXP) |
| **Level Source** | NOT a combat pillar |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | Extra enchantment levels; enchant quality bonuses; enchant cost reduction; cross-skill perk with MINING |
| **TF Consumers** | `PerkBuffResolver`, `UseRequirementPolicy` (crafting features gate in `crafting-features.yml`) |
| **Migration Disposition** | **Reimplement** |

---

#### 10. ALCHEMY

| Field | Value |
|---|---|
| **Skill Key** | `ALCHEMY` |
| **EXP Producer** | Brewing potions |
| **EXP Owner** | **Valhalla** (`exp_multiplier_quality: 0.00334` per quality point; `multiplier_automated: 0.25`) |
| **Level Source** | NOT a combat pillar |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | Potion quality bonuses; splash radius; potion duration; brew-unlock gates (via TF `crafting-features.yml`) |
| **TF Consumers** | `PerkBuffResolver`, `UseRequirementPolicy`, crafting feature gating (`brew-unlocks` in `crafting-features.yml`) |
| **Migration Disposition** | **Reimplement** |

---

#### 11. SMITHING

| Field | Value |
|---|---|
| **Skill Key** | `SMITHING` |
| **EXP Producer** | Completing an equipment craft |
| **EXP Owner** | **TF** (`stats/skill-exp.yml` → `smithing.exp-per-craft`) |
| **Level Source** | NOT a combat pillar |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | Craft quality; upswing/downswing modifiers; `SmithingCraftProfile` stats (`craftQuality`, `upswingModifier`, `downswingModifier`) |
| **TF Consumers** | `PerkBuffResolver`, `SmithingCraftProfile` (vanilla smithing craft quality path), `UseRequirementPolicy` |
| **Migration Disposition** | **Reimplement** |

---

#### 12. ARS_SMITHING

| Field | Value |
|---|---|
| **Skill Key** | `ARS_SMITHING` (constant: `ArsBridge.ARS_SMITHING`) |
| **EXP Producer** | Crafting Ars gear (`CraftQualityListener` → `ArsBridge.grantSmithingExp`) |
| **EXP Owner** | **TF** (100 EXP/craft; config: `stats/skill-exp.yml`) |
| **Level Source** | NOT a combat pillar; registered by `ArsBridge.registerArsSmithing` |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | None — `starting_perks: {}`, `leveling_perks: {}` (all stats via TF `buffs` through `ArsSmithingProfile`) |
| **TF Consumers** | `PerkBuffResolver`, `ArsSmithingProfile` (Ars craft quality, thread slots, lapis/source cost reduction, craft roll modifiers) |
| **Migration Disposition** | **Reimplement** — registration requires live-server smoke test (same timing concern as ARS_MAGIC) |

---

#### 13. FARMING

| Field | Value |
|---|---|
| **Skill Key** | `FARMING` |
| **EXP Producer** | Harvesting crops / interacting with farming blocks |
| **EXP Owner** | **Valhalla** (per-block `block_interact` table; e.g. BEEHIVE 400, SWEET_BERRY_BUSH 40) |
| **Level Source** | NOT a combat pillar |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | Crop yield multiplier; replant chance; animal breeding bonuses; compost speed |
| **TF Consumers** | `PerkBuffResolver`, role-buff system (`role-buffs.yml` support/farmer role grants `exp-skill: FARMING`) |
| **Migration Disposition** | **Reimplement** |

---

#### 14. FISHING

| Field | Value |
|---|---|
| **Skill Key** | `FISHING` |
| **EXP Producer** | Catching items while fishing |
| **EXP Owner** | **Valhalla** (per-item `fishing_catch` table; e.g. COD 200, TROPICAL_FISH 800, ENCHANTED_BOOK 600) |
| **Level Source** | NOT a combat pillar |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | Treasure loot bonuses; fishing speed; luck additions; rare drop rates |
| **TF Consumers** | `PerkBuffResolver` (`fishing_luck`, `fishing_bonus` via `skilltree/fishing.yml` buffs), role-buff system (scout role grants `exp-skill: FISHING`) |
| **Migration Disposition** | **Reimplement** |

---

#### 15. WOODCUTTING

| Field | Value |
|---|---|
| **Skill Key** | `WOODCUTTING` |
| **EXP Producer** | Stripping / chopping logs and wood |
| **EXP Owner** | **Valhalla** (per-block `woodcutting_strip` table; e.g. STRIPPED_OAK_LOG 20, various log blocks) |
| **Level Source** | NOT a combat pillar |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | Tree-felling (instant full tree chop); sapling drop rates; log-to-charcoal efficiency; wood variant bonuses |
| **TF Consumers** | `PerkBuffResolver`, `UseRequirementPolicy` |
| **Migration Disposition** | **Reimplement** |

---

#### 16. DIGGING

| Field | Value |
|---|---|
| **Skill Key** | `DIGGING` |
| **EXP Producer** | Breaking soil/sand/gravel/netherrack blocks |
| **EXP Owner** | **Valhalla** (per-block `digging_break` table; e.g. DIRT 8, SOUL_SAND 16, FARMLAND 8) |
| **Level Source** | NOT a combat pillar |
| **Combat Pillar (LD-7)** | ❌ No |
| **Native Valhalla Rewards** | Shovel speed bonus; special drops (flint rate from gravel, etc.); path creation |
| **TF Consumers** | `PerkBuffResolver`, `UseRequirementPolicy` |
| **Migration Disposition** | **Reimplement** |

---

## Summary Statistics

| Category | Count | Skills |
|---|---|---|
| **Combat pillars (LD-7)** | 4 | LIGHT_WEAPONS, HEAVY_WEAPONS, ARCHERY, ARS_MAGIC |
| **TF-owned EXP** | 2 | ARS_MAGIC, ARS_SMITHING |
| **Valhalla-owned EXP** | 14 | All others |
| **Custom Valhalla skills** | 2 | ARS_MAGIC, ARS_SMITHING (registered via ArsBridge) |
| **Vanilla Valhalla skills** | 14 | All others |
| **Migration: Reimplement** | 16 | All skills |
| **Migration: Retire** | 0 | — |

---

## Live-Only Verification Boundaries

These contracts were **not** testable without a running server and are documented here for the
next server-side smoke-test session:

| Item | Description |
|---|---|
| **ARS_MAGIC EXP path** | Verify Ars kill attribution and spell-driven block breaks each grant exactly one configured award |
| **ARS_SMITHING EXP path** | `CraftQualityListener` → `ArsBridge.grantSmithingExp` must fire per Ars-gear craft |
| **ArsBridge registration timing** | `ArsBridge.registerAll` must run AFTER ValhallaMMO has loaded its own skills (`load: BEFORE` in `paper-plugin.yml`); verify no `NoSuchElementException` from `SkillRegistry` |
| **PowerProfile perk-id spelling** | Actual perk-ids persisted to disk by Valhalla for a deployed `power.yml` tree must match `PerkNaming.perkId(skill, nodeId)` output |
| **`ValhallaSkillPerkStatSource` perk union** | The `(unlocked ∪ permanentlyUnlocked) − fake − permanentlyLocked` formula must match Valhalla's own `Skill#updateSkillStats` at runtime |
| **`leveling_perks` vs `perk_rewards` separation** | TF `buffs` must not appear in the deployed `perk_rewards`; verify with a diff of the deployed Valhalla config |
