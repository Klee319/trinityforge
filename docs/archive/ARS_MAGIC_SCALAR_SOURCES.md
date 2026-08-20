# Ars Magic Progression Scalar — Source Inventory (Design Input)

- **Status**: Investigation / research only. No code changed.
- **Purpose**: Enumerate, with file:line evidence, the per-player "magic progression" signals the
  **arspaper fork** (and the ValhallaMMO bridge) actually exposes, so the TrinityForge combat-score
  "magic pillar" can be wired to a single, clean, monotonic, gear-independent progression scalar
  (DESIGN constraint: magic damage comes from the catalyst/glyphs, **not** a player-level term —
  see `MAGIC_BALANCE_SPEC.md` §2 / §1 row ③).
- **Date**: 2026-06-29

---

## 1. Where things live (paths found)

| Component | Path |
|---|---|
| arspaper fork (authoritative source) | `C:\Users\T-319\Documents\Program\ClaudeCodeDev\products\minecraft\Ars_paper\` |
| arspaper fork (handoff copy) | `...\minecraft\trinityforge\fork-handoff\arspaper\` |
| arspaper (decompiled NeoForge ref) | `...\minecraft\trinityforge\decompile\extract_ars\` |
| TrinityForge plugin source | `...\minecraft\trinityforge\TrinityForge\src\main\java\com\trinityforge\` |
| Design docs | `...\minecraft\trinityforge\docs\` |

**Key fact about the fork**: `Ars_paper` is **not** the NeoForge mod — it is a from-scratch **Paper
plugin** re-implementation of Ars Nouveau, package `com.arspaper`, with its **own public static API**
(`com.arspaper.api.ArsAPI`) and Bukkit event set (`com.arspaper.api.event.*`). It is **not** a
hard-fork that adds PDC keys to the upstream mod; it owns its own data model entirely.

**Bridge status**: TrinityForge has **no Ars/arspaper reference today** — `grep -rl arspaper TrinityForge/src`
returns nothing. The only existing cross-plugin bridge is
`progression\ValhallaSkillLevelSource.java` (reflection-based soft dependency on ValhallaMMO), which
is the template any Ars bridge should follow.

**Critical architectural finding**: The "magic progression" axis is **already modeled as a ValhallaMMO
skill tree**, not as an Ars-owned level.
- `docs\ADDON_INTEGRATION_SPEC.md:100` lists the **10 Valhalla skill trees**, two of which are
  `Ars魔法` (Ars magic) and `Ars鍛冶` (Ars smithing). Valhalla owns "perk truth / skill tree / level"
  (`ADDON_INTEGRATION_SPEC.md:17`).
- `docs\UNLOCK_SYSTEM_SPEC.md:145-158` (§5.9 "Ars魔法"): the **mainline A-E perks** of that tree are
  exactly *max mana / regen / glyph-slot count / usable Tier* — and they are explicitly **"ステ perk"
  (stat perks), not unlocks** (`:158`). ArsPaper merely *consumes* those perks (via `addManaModifier`
  / `ModifierType`); Valhalla holds the level.
- `TrinityForge\src\main\resources\progression\combat-level.yml:20` **already declares `ARS_MAGIC: 1.0`**
  (and `ARS_SMITHING: 1.0`) as a skill weight in the combat-level average.

So the magic pillar already has a *design-sanctioned* source: the Valhalla `ARS_MAGIC` skill level.
The Ars-native signals below are the alternative / cross-check candidates.

---

## 2. Candidate signal table

Legend for "Bridge-read feasibility": **API** = public `ArsAPI` static method (callable by
reflection, soft-dep, ValhallaSkillLevelSource style); **PDC** = read the player's PersistentDataContainer
key directly with a `NamespacedKey("arspaper", …)` — **zero Ars dependency**; **Valhalla** = read via
the existing reflection bridge.

| # | Signal | Source (file:line) | Growth mechanism | Monotonic? | Gear-independent? | Bridge-read feasibility | Notes |
|---|---|---|---|---|---|---|---|
| 1 | **Unlocked glyph count** | `ArsAPI.java:99` `getUnlockedGlyphs(Player)→Set`; total via `getAllGlyphIds()` `ArsAPI.java:106`; PDC store `ManaKeys.UNLOCKED_GLYPHS` (`ManaKeys.java:36`, key `arspaper:unlocked_glyphs`, JSON array) | Player scribes a glyph at the Scribing Table, paying **vanilla XP levels + materials** gated per-glyph (`gui\ScribingTableGui.java:142,169,208`); set grows by one (`ScribingTableGui.java:343-348`). API can also `unlockGlyph`/`lockGlyph` (`ArsAPI.java:75,87`). | **Yes** in normal play (scribing only adds; nothing in normal play locks). Non-monotonic only if an external plugin calls `lockGlyph`. | **Yes** — pure knowledge count, no armor/catalyst term. | **API** (`getUnlockedGlyphs().size()` / `getAllGlyphIds().size()` for a 0..1 ratio) **or PDC** (parse `arspaper:unlocked_glyphs` JSON). Also has events #6/#7 for a mirror. | **Strongest Ars-native scalar.** Breadth metric ("how much of the spellbook is known"), not power. Clean integer; normalizable. |
| 2 | **Glyph mana bonus (PDC)** | `ManaKeys.GLYPH_MANA_BONUS` (`ManaKeys.java:21`, key `arspaper:glyph_mana_bonus`); written `gui\GlyphUnlockAnimation.java:302` | `+= per-glyph-unlock-bonus` (config `mana.per-glyph-unlock-bonus`, default **5**) each glyph unlock (`GlyphUnlockAnimation.java:300-303`). It is `5 × glyphCount`. | **Yes** (same as #1, a linear proxy). | **Yes** — isolated from armor/thread/enchant terms. | **PDC** only (`arspaper:glyph_mana_bonus`, INTEGER). No dedicated API getter (folded into `getMaxMana`). | Effectively a pre-multiplied mirror of #1. Cheapest gear-clean read if you want a "magic capacity" number without parsing JSON. |
| 3 | **Max mana** | `ArsAPI.java:218` `getMaxMana(Player)`; impl `mana\ManaManager.java:81-103` | `default + glyphBonus + armorBonus + threadBonus + enchantBonus + worldBonus + apiModifier(MAX_MANA)` (`ManaManager.java:93-102`) | Mostly (glyph/api terms grow); can **drop** when armor/thread removed or on world change. | **NO** — explicitly includes `ARMOR_MANA_BONUS`, `THREAD_MANA_BONUS`, `ENCHANT_MANA_BONUS` (gear) and per-world overrides. | **API** (`getMaxMana`). | **Do not use raw.** Reintroduces the gear scaling `MAGIC_BALANCE_SPEC.md` §2 deliberately kills. Only the non-gear subset (`default + glyph + api perk`) is valid — and that is just #1/#2 + #5. |
| 4 | **Mana regen rate** | `ArsAPI.java:223` `getManaRegenRate`; impl `ManaManager.java:189-211` | `base + threadBonus + enchantBonus + armorBonus + worldBonus + apiModifier(REGEN_RATE)` | Mostly; same volatility as #3. | **NO** — same gear contamination (`ManaManager.java:202-204`). | **API**. | Same disqualifier as #3. Not a clean scalar. |
| 5 | **Valhalla-applied MAX_MANA modifier sum** | `ArsAPI.java:369` `sumModifier(UUID, ModifierType.MAX_MANA)`; enum `modifier\ModifierType.java:17`; mainline-perk intent `UNLOCK_SYSTEM_SPEC.md:158`, `MAGIC_BALANCE_SPEC.md:80` | Valhalla `Ars魔法` mainline perks push modifiers into ArsPaper via `addManaModifier` (`ArsAPI.java:228`). | Tracks the Valhalla perk level (monotonic unless prestige resets). | **Yes** (perk-driven, not gear). | **API** (`sumModifier`) **or** directly the Valhalla skill (#9). | This is the ArsPaper-side shadow of the Valhalla magic level. Reading the Valhalla level (#9) is more direct. |
| 6 | **Glyph-unlocked event** | `api\event\ArsGlyphUnlockedEvent.java` (fired `ArsAPI.java:82`, `ScribingTableGui.java:215`) | Fires after each unlock; carries `glyphId` + `UnlockSource`. | n/a (event) | n/a | **Event listener** — lets TF keep its own running count / PDC mirror. | Enables a TF-owned mirror updated incrementally (see §3). |
| 7 | **Glyph-locked event** | `api\event\ArsGlyphLockedEvent.java` (fired `ArsAPI.java:94`) | Fires on API-driven lock. | n/a | n/a | **Event listener**. | Needed only if a TF mirror must stay correct when something locks glyphs. |
| 8 | **Total mana consumed (lifetime)** | `ManaKeys.TOTAL_MANA_CONSUMED` (`ManaKeys.java:66`, key `arspaper:total_mana_consumed`, LONG); `ManaManager.getTotalManaConsumed` (`:334`) | Increments by spent mana every cast (`ManaManager.java:143,312`). | **Yes** (cumulative). | Yes. | **PDC** / API method. | **Usage/engagement** stat, not capability — grindable, decoupled from power. Good for leaderboards, poor as a capability scalar. |
| 9 | **Valhalla `ARS_MAGIC` skill level** | `combat-level.yml:20`; bridge `progression\ValhallaSkillLevelSource.java:90-120` (`Profile.getLevel()`); design `ADDON_INTEGRATION_SPEC.md:100`, `UNLOCK_SYSTEM_SPEC.md:158` | Player trains the Ars-magic skill tree; Valhalla owns XP→level. | **Yes** (except deliberate prestige reset, `PROGRESSION_SPEC.md:25`). | **Yes** — skill level, not gear. | **Valhalla** (already-wired reflection bridge; just needs the `ARS_MAGIC` skill key to exist in Valhalla and be returned by `levelsOf`). | **Design-sanctioned source.** Already in `combat-level.yml`. NOT a "player/character level" — it is the magic *skill-tree* level (the catalyst/glyph progression axis itself). |
| 10 | **Spell Tier / glyph-slot count** | concept `MAGIC_BALANCE_SPEC.md:70-81` (§4 "(c) slot-count + glyph-attribute gate"); `UNLOCK_SYSTEM_SPEC.md:158` | Valhalla `Ars魔法` mainline perk raises usable Tier / slots; applied as an ArsPaper modifier (`MAGIC_BALANCE_SPEC.md:80`). | Yes (perk-driven). | Yes. | **No clean getter today** — there is no `getSpellTier()` / `getSlotCount()` in `ArsAPI`. Would surface as a `ModifierType` sum or a Valhalla read. | Most semantically "magic power level"-like, but **not currently exposed as a scalar**. New API surface or a dedicated `ModifierType` would be needed. |

### Signals that do NOT exist (checked, absent)
- No `getSpellTier` / `getCasterLevel` / `getSlotCount` accessor on `ArsAPI` (§ checked whole class).
- No Ars-owned "spell caster level" PDC key — `ManaKeys` (`ManaKeys.java`) only has mana/bonus/stat
  keys; no level key.
- Glyph *items* are not modeled (`ArsAPI.getGlyphIdFromItem` returns `null`, `ArsAPI.java:129-132`).

---

## 3. Recommendation

### Primary: Valhalla `ARS_MAGIC` skill level (signal #9)
For a *combat-score pillar*, this is the correct scalar and it is already the design's intent:
- It is monotonic, gear-independent, and **already declared** in `combat-level.yml:20`.
- It uses the **existing** `ValhallaSkillLevelSource` bridge — **no new Ars dependency, no new code path**.
- It satisfies the stated constraint: `ARS_MAGIC` is the *magic skill-tree* level, which **is** the
  catalyst/glyph progression axis (`UNLOCK_SYSTEM_SPEC.md:158`) — it is explicitly **not** the player's
  character/combat level. The "no player-level term" rule is about not using a generic level; the
  magic-tree level is exactly the per-pillar progression the design wants.

The only thing to confirm is operational, not architectural: that a Valhalla skill with key `ARS_MAGIC`
actually exists and `levelsOf` returns it (see Open Questions).

### Secondary / Ars-native fallback: Unlocked glyph count (signal #1, mirror #2)
If a genuinely **Ars-native** pillar is required — e.g., because the `ARS_MAGIC` Valhalla skill is not
yet registered, or because design wants the pillar to track *actual spell-book capability* rather than
Valhalla XP — use **unlocked glyph count, normalized by total glyph count**:
`scalar = getUnlockedGlyphs(p).size() / max(1, getAllGlyphIds().size())` → a 0..1 magic-progression
ratio, scaled into the pillar's level band.
- Monotonic in normal play, fully gear-independent, and exposed both as **public API** (`ArsAPI.getUnlockedGlyphs` /
  `getAllGlyphIds`) and as a **raw PDC key** (`arspaper:unlocked_glyphs`) for a zero-dependency read.
- Caveat: it is a **breadth** metric (how many glyphs are known), not a **power/depth** metric. Per
  `MAGIC_BALANCE_SPEC.md` §2/§4, magic *power* comes from Tier (slots) + amplify glyphs + catalyst, not
  from the count of glyphs known. Glyph count is a reasonable monotonic *proxy for magic investment*,
  but it is not magic damage. Flag this to design.

### Explicitly avoid
- **Raw `getMaxMana` / `getManaRegenRate` (signals #3/#4)** as the scalar — they fold in
  `ARMOR_/THREAD_/ENCHANT_` gear bonuses (`ManaManager.java:94-96,202-204`) and per-world overrides,
  reintroducing exactly the gear scaling `MAGIC_BALANCE_SPEC.md` §2 removes. If a mana-based number is
  desired, read only the gear-free subset (`glyph_mana_bonus` + MAX_MANA perk modifier), which is just
  signals #2 + #5 re-derived.
- **`total_mana_consumed` (#8)** as a capability input — it is a grindable usage stat, not capability.

### Live read vs TrinityForge-owned PDC mirror
**Read live; do not build a TF mirror** (for the combat-scoring use case).
- The magic pillar is computed during combat, when the player is **online**, and both the Valhalla read
  and the Ars PDC read are cheap, main-thread-safe operations (the existing combat-level path already
  reads Valhalla live, `ValhallaSkillLevelSource.java:91-99`).
- ArsPaper already **persists the authoritative state** in the player's PDC (`arspaper:unlocked_glyphs`,
  `arspaper:glyph_mana_bonus`) and in `ArsPlayerDataStore` — a parallel TF mirror would be redundant and
  introduces a drift failure mode (a missed `ArsGlyphUnlockedEvent` silently desyncs the count).
- **Only build a TF PDC mirror if** the value is needed for **offline** players (e.g., a leaderboard or
  an offline difficulty pre-calc) or to **decouple TF from Ars's JSON format**. In that case, write the
  mirror on `ArsGlyphUnlockedEvent` / `ArsGlyphLockedEvent` (signals #6/#7) plus a join-time
  reconciliation against the authoritative `arspaper:unlocked_glyphs` to self-heal missed events.

---

## 4. Open questions (need a human design decision)

1. **Does the Valhalla `ARS_MAGIC` skill actually exist?** It appears only in `combat-level.yml:20`
   and nowhere else in the repo (no registration found in `VALHALLA_DEFAULT_SKILLS.md` or specs). If it
   is aspirational, the magic pillar currently has **no live source** and the Ars-native fallback (#1)
   is required until the skill is created. — *Decision: confirm/register the skill, or commit to the
   Ars-native scalar.*
2. **Breadth vs power**: should the magic pillar track *glyph breadth* (#1, "how much spellbook known"),
   *magic skill level* (#9, Valhalla XP), or *Tier/slot depth* (#10, actual spell power)? These diverge
   — a player can have a high Tier with few glyphs, or many glyphs at low Tier. `MAGIC_BALANCE_SPEC.md`
   §4 treats Tier as the power axis, but Tier is not exposed as a scalar.
3. **If Tier/slot is the desired axis (#10)**: it needs a **new exposed scalar** — either a
   `getSpellTier()` / `getSlotCount()` on `ArsAPI`, or a dedicated `ModifierType` + `sumModifier` read.
   Is adding that API surface to the fork acceptable?
4. **Double-counting with the unlock system**: glyph unlocks are *also* gated by Valhalla `Ars魔法` perks
   (`ADDON_INTEGRATION_SPEC.md:60`, `UNLOCK_SYSTEM_SPEC.md`). If the pillar uses *both* the Valhalla
   magic level (#9) and Ars glyph count (#1), they are correlated and the pillar would weight magic
   progress twice. Pick one source per pillar.
5. **Prestige resets** (`PROGRESSION_SPEC.md:25`): if the Valhalla magic skill prestiges (level reset +
   permanent bonus), signal #9 is **non-monotonic across prestige**, whereas glyph count (#1) is not.
   Which behavior does the magic pillar want at prestige?
6. **`lockGlyph` exposure**: signal #1 is only strictly monotonic if no plugin calls `ArsAPI.lockGlyph`.
   Confirm whether any server system (debuff, curse, event) is allowed to lock glyphs.
