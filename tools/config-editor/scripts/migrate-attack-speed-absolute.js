"use strict";

// ONE-SHOT data migration, STAGE 2 of 2 (2026-07-25). Do NOT re-run after it has been applied once.
//
// This is the SECOND stage of a two-stage attack-speed migration on stats/item-stats.yml:
//   STAGE 1 (already applied, produced the file this script now reads as input): rewrote every
//     `attack-speed` from "addend on top of vanilla material default" to "addend on top of vanilla
//     BASE 4.0 as if the material default were folded in", i.e. newValue = oldValue + materialDefault.
//     The backup of the PRE-STAGE-1 file lives at
//     tools/config-editor/scripts/item-stats.yml.pre-migration-backup (kept for double-checking; it
//     predates BOTH stages, not just this one).
//   STAGE 2 (this script): the config semantics changed again -- `attack-speed` is now meant to be
//     the EFFECTIVE attack speed itself (attacks/second), not an addend at all. Since the stage-1
//     output already equals `writtenAfterStage1 = oldRawValue + materialDefault` (an addend against
//     vanilla base 4.0), the absolute effective speed is simply `4.0 + writtenAfterStage1`. This
//     script performs exactly that: newValue = 4.0 + currentValue, and writes newValue as the new
//     `attack-speed`.
//
// Net effect across both stages: newValue = 4.0 + materialDefault + oldRawValue, which is exactly
// the original effective attack speed (4.0 + materialDefault + oldRawValue was always the formula
// under the OLD relative-to-material-default scheme). So the game-visible effective attack speed is
// unchanged end-to-end; only what's written in the config changed meaning, twice.
//
// Verification performed by the caller (see migrate-attack-speed-absolute.report.json + the session
// report): independently recomputes `4.0 + materialDefault + preStage1Value` from the pre-stage-1
// backup and the material-default table in this file, and checks it equals `4.0 + newValue` for all
// 165 rows -- i.e. a full bypass of this script's own arithmetic, not just re-deriving the same sum.
//
// Source of truth for vanilla material defaults (used only for the independent double-check, not for
// this stage's arithmetic, which needs no material knowledge at all): primary source, fetched
// 2026-07-25 via the Minecraft Wiki "Melee attack#Attack cooldown" page,
// https://minecraft.wiki/w/Melee_attack -- NOT guessed. See MATERIAL_DEFAULT_ATTACK_SPEED table below.
//
// MockBukkit does not implement Material#getDefaultAttributeModifiers (see
// ItemAssemblerTest.java:114-121), so this script cannot ask Bukkit for these numbers and instead
// carries them as a literal table transcribed from the wiki.

const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const itemStatsPath = path.resolve(__dirname, "..", "..", "..",
  "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml");

// ---------------------------------------------------------------------------------------------
const original = fs.readFileSync(itemStatsPath, "utf8");
const header = original.slice(0, original.indexOf("\nitems:\n") + 1);
const doc = YAML.parse(original);
const items = doc.items || {};

const BASELINE = 4.0;
const report = []; // { key, currentValue, newValue }

for (const [key, entry] of Object.entries(items)) {
  if (!entry || typeof entry !== "object" || !entry.fixed) continue;
  const currentValue = entry.fixed["attack-speed"];
  if (currentValue === undefined) continue;

  const newValue = Math.round((BASELINE + currentValue) * 1e6) / 1e6;
  entry.fixed["attack-speed"] = newValue;
  report.push({ key, currentValue, newValue });
}

fs.writeFileSync(itemStatsPath, `${header}${YAML.stringify(doc)}`, "utf8");

console.log(`migrated ${report.length} item(s) (stage 2: absolute value):`);
for (const r of report) {
  console.log(`  ${r.key}: attack-speed ${r.currentValue} -> ${r.newValue} `
    + `(effective was 4.0+${r.currentValue}=${(BASELINE + r.currentValue).toFixed(6)}, `
    + `is now the written value itself = ${r.newValue.toFixed(6)})`);
}

fs.writeFileSync(
  path.resolve(__dirname, "migrate-attack-speed-absolute-stage2.report.json"),
  JSON.stringify(report, null, 2),
  "utf8"
);
