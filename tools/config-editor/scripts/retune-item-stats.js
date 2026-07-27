"use strict";

// One-shot surgical retune of the SHIPPED stats/item-stats.yml (the live source of truth; the
// generator has diverged with un-shipped GOLD changes + dropped orphan CMDs, so we mutate the
// shipped file directly instead of regenerating). Applies exactly four requested changes and
// nothing else:
//   1. Durability on real weapons that had none (finite, vanilla-tier scaled, tool/armor convention).
//   2. Wider selection (random roll spread) on weapons: attack-power +-~18% and two rolled secondaries.
//   3. Weapon damage-modifier differentiated per family, remapped into the 0.50..0.90 band.
//   4. Tools receive an explicit use-skill (no use-level -> skill/lore association only, no use gate).
// Gold "gamble" items (random.damage-modifier present) keep their spread and modifier untouched
// except for durability. Special-roll endgame variants (phys-resistance / move-speed rolls) keep
// their secondary rolls; only their attack-power spread widens.

const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const targetPath = path.resolve(__dirname, "..", "..", "..",
  "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml");

const round1 = (x) => Math.round(x * 10) / 10;

// --- req1: vanilla durability by tier / special material -------------------------------------
const TIER_DURABILITY = {
  WOODEN: 59, WOOD: 59, STONE: 131, COPPER: 190, IRON: 250,
  GOLDEN: 32, GOLD: 32, DIAMOND: 1561, NETHERITE: 2031,
};
const SPECIAL_DURABILITY = { BOW: 384, CROSSBOW: 465, TRIDENT: 250, MACE: 500 };

function vanillaDurabilityFor(key) {
  const material = key.split("#")[0];
  if (SPECIAL_DURABILITY[material] != null) return SPECIAL_DURABILITY[material];
  const tier = material.split("_")[0];
  return TIER_DURABILITY[tier] ?? null; // null => non-damageable / unknown => skip
}

// --- req3: family -> damage-modifier, existing family ordering rescaled into [0.50, 0.90] ------
// raw family modifiers span 0.95(dagger)..1.08(greataxe); mod' = 0.50 + (raw-0.95)/0.13 * 0.40.
const FAMILY_MOD = {
  "短剣": 0.50, "鎌": 0.53, "レイピア": 0.56, "槍": 0.59, "トライデント": 0.62,
  "剣": 0.65, "メイス": 0.65, "弓": 0.65, "大剣": 0.72, "クロスボウ": 0.72,
  "モーニングスター": 0.78, "戦斧": 0.81, "ウォーハンマー": 0.84, "大斧": 0.90,
  "特殊武器": 0.65,
};
const DEFAULT_MOD = 0.65;

// --- req2: rolled secondaries by family (widened ~1.5x vs previous single-secondary spreads) ---
const STANDARD_SECS = new Set([
  "crit-chance", "crit-damage", "penetration", "bleed-chance", "power-attack-damage",
]);

function primarySecondary(family) {
  if (["短剣", "弓", "クロスボウ"].includes(family)) return ["crit-chance", { min: -0.05, max: 0.07 }];
  if (["レイピア", "槍", "トライデント"].includes(family)) return ["penetration", { min: -0.06, max: 0.09 }];
  if (family === "鎌") return ["bleed-chance", { min: -0.07, max: 0.11 }];
  if (family === "メイス") return ["power-attack-damage", { min: -0.12, max: 0.18 }];
  return ["crit-damage", { min: -0.12, max: 0.18 }];
}
function secondarySecondary(primaryKey) {
  return primaryKey === "crit-damage"
    ? ["crit-chance", { min: -0.04, max: 0.06 }]
    : ["crit-damage", { min: -0.10, max: 0.15 }];
}

// --- req4: tool material suffix -> gathering skill --------------------------------------------
function toolSkillFor(key) {
  const material = key.split("#")[0];
  if (material.endsWith("_PICKAXE")) return "MINING";
  if (material.endsWith("_SHOVEL")) return "DIGGING";
  if (material.endsWith("_HOE")) return "FARMING";
  if (material.endsWith("_AXE")) return "WOODCUTTING";
  if (material === "SHEARS") return "FARMING";
  if (material === "FISHING_ROD") return "FISHING";
  return null;
}

// ---------------------------------------------------------------------------------------------
const original = fs.readFileSync(targetPath, "utf8");
const header = original.slice(0, original.indexOf("\nitems:\n") + 1);
const doc = YAML.parse(original);
const items = doc.items || {};
const cats = doc._editor?.categories || {};

const keyToFamily = {};
for (const row of cats.weapon || []) {
  for (const id of row.itemIds || []) keyToFamily[id] = row.label;
}
const toolKeys = new Set();
for (const row of cats.tool || []) for (const id of row.itemIds || []) toolKeys.add(id);

const report = { durability: 0, modifier: 0, widened: 0, apOnly: 0, gamble: 0, toolSkill: 0 };

for (const [key, entry] of Object.entries(items)) {
  if (!entry || typeof entry !== "object") continue;
  const fixed = entry.fixed || {};
  const isRealWeapon = fixed["damage-modifier"] != null && fixed["attack-power"] != null;

  if (isRealWeapon) {
    const random = entry.random || {};
    const isGamble = random["damage-modifier"] != null;
    const family = keyToFamily[key];

    // req1: durability if the weapon has none anywhere.
    const hasDurability = fixed.durability != null
      || entry.durability != null
      || (entry["per-quality"] && entry["per-quality"].durability != null);
    if (!hasDurability) {
      const dur = vanillaDurabilityFor(key);
      if (dur != null) {
        fixed.durability = Math.floor(dur * 0.6);
        entry["per-quality"] = entry["per-quality"] || {};
        entry["per-quality"].durability = Math.max(1, Math.round(dur * 0.1));
        report.durability++;
      }
    }

    if (isGamble) {
      // Preserve gold gamble spread + modifier as-is (Q4: keep current gold values).
      report.gamble++;
    } else {
      // req3: family-differentiated damage-modifier in the 0.50..0.90 band.
      fixed["damage-modifier"] = FAMILY_MOD[family] ?? DEFAULT_MOD;
      report.modifier++;

      // req2: widen attack-power spread; standard-roll weapons also gain a 2nd rolled secondary.
      const ap = fixed["attack-power"];
      random["attack-power"] = { min: round1(-ap * 0.16), max: round1(ap * 0.20) };
      const otherKeys = Object.keys(random).filter((k) => k !== "attack-power");
      const isStandard = otherKeys.every((k) => STANDARD_SECS.has(k));
      if (isStandard) {
        const [k1, r1] = primarySecondary(family);
        const [k2, r2] = secondarySecondary(k1);
        for (const k of otherKeys) delete random[k];
        random[k1] = r1;
        random[k2] = r2;
        report.widened++;
      } else {
        // Special-roll variant (phys-resistance / move-speed downside): keep its secondaries.
        report.apOnly++;
      }
    }
    entry.fixed = fixed;
    entry.random = random;
    continue;
  }

  // req4: tools -> explicit use-skill (skill/lore association; no use-level => no use gate lock).
  if (toolKeys.has(key) && fixed["attack-power"] == null) {
    if (!entry["use-skill"]) {
      const skill = toolSkillFor(key);
      if (skill) {
        entry["use-skill"] = skill;
        report.toolSkill++;
      }
    }
  }
}

fs.writeFileSync(targetPath, `${header}${YAML.stringify(doc)}`, "utf8");
console.log("retune complete:", JSON.stringify(report));
