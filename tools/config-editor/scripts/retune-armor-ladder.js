"use strict";

// Single source of truth for stats/item-stats.yml's physical-armor phys-flat-defense /
// phys-resistance / max-health (T2/T3 of the 2026-07-25 軽装7セット review, on top of the original
// combat-rebalance 2026-07-25 pass). See docs/design/2026-07-25 combat rebalance memory + the
// 2026-07-25 軽装レビュー brief for the full derivation. The companion test
// (test/armor-ladder.test.js) re-derives the same ladder independently from the same TABLE/WEIGHTS
// constants (duplicated there on purpose, so the test is a real check and not a tautology against
// this file's internals).
//
// IMPORTANT — kept in sync with shipped data (2026-07-25 軽装レビュー): PHYS_GROUPS now also
// covers the new 7 light-armor families (bone_guard/copper_stud/carapace_mail/gilded_thread/
// abyssal_scale/phantom_shroud/withered_silk, CMD 200124-200151) that were originally shipped
// without going through this script. If you add or renumber any physical armor entry in
// items/catalog.yml, add/update its PHYS_GROUPS row here too and re-run
// `SKIP_SPEARS=1 node scripts/retune-armor-ladder.js` (SKIP_SPEARS is required on every re-run
// after the first — the spear section applies a one-shot 0.85x attack-power multiplier that must
// never be applied twice). durability is intentionally NOT touched by this script (see
// test/armor-ladder.test.js's "新規軽装7セット" block for why the light-armor durability ladder is
// allowed to be non-monotonic).
//
// WARNING — do not try to make a set's armor-defense-rate AND phys-resistance both exceed a
// weaker-level neighbor's at the same time (2026-07-25 軽装レビュー T3-2 discovery): physResFor()
// solves physRes so that (1-defRate)*(1-physRes) == TABLE[level].m exactly. TABLE.m is NOT
// monotonic by level (it is solved from hits-to-survive + HP growth per band, not from a simple
// increasing curve) — e.g. Lv20's m=0.696 is LARGER (weaker) than Lv10's m=0.609. If you raise a
// Lv20 set's armor-defense-rate sum until defRate exceeds (1 - TABLE[20].m) = 0.304, physResFor
// clamps to 0 and phys-resistance cannot be raised at all — pushing armor-defense-rate up at a
// weak-m level makes phys-resistance WORSE, not better. This was hit for real trying to fix
// CHAINMAIL_*(Lv20) vs COPPER_*(Lv10)'s armor-defense-rate ordering: any CHAINMAIL custom sum
// above ~8 zeroes its phys-resistance while still not reaching COPPER's raw 11. Verified
// (independently of this script) that CHAINMAIL still wins on real net damage anyway, because
// phys-flat-defense (subtracted first in the 8-stage pipeline, before defRate/physRes ever apply)
// is what actually dominates — see the item-stats.yml comment above CHAINMAIL_HELMET and the
// "T3-2 regression lock" test in test/armor-ladder.test.js. Do not re-attempt this fix by raising
// CHAINMAIL_*'s armor-defense-rate; TABLE must not be touched either (each row is solved jointly
// with that level's HP band — moving one m value alone desyncs the whole ladder's calibration).

const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const targetPath = path.resolve(__dirname, "..", "..", "..",
  "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml");

const round2 = (x) => Math.round(x * 100) / 100;
const round4 = (x) => Math.round(x * 10000) / 10000;

// ---------------------------------------------------------------------------------------------
// Confirmed target ladder (orchestrator-provided, derived from A(Lv)=7*1.03^Lv and the m formula).
// hp = TOTAL player max health including vanilla 20; f = SUM of 4-piece phys-flat-defense.
const TABLE = {
  0:   { a: 7.0,   m: 0.780, hpMin: 20, hpMax: 20, fMin: 2.7,   fMax: 5.7 },
  10:  { a: 9.4,   m: 0.609, hpMin: 23, hpMax: 24, fMin: 3.1,   fMax: 7.4 },
  20:  { a: 12.6,  m: 0.696, hpMin: 26, hpMax: 28, fMin: 6.4,   fMax: 10.6 },
  30:  { a: 17.0,  m: 0.480, hpMin: 30, hpMax: 33, fMin: 6.7,   fMax: 13.6 },
  40:  { a: 22.8,  m: 0.522, hpMin: 34, hpMax: 38, fMin: 12.1,  fMax: 19.2 },
  55:  { a: 35.6,  m: 0.486, hpMin: 38, hpMax: 44, fMin: 22.6,  fMax: 31.1 },
  70:  { a: 55.4,  m: 0.380, hpMin: 43, hpMax: 50, fMin: 36.8,  fMax: 48.8 },
  85:  { a: 86.3,  m: 0.318, hpMin: 46, hpMax: 55, fMin: 62.1,  fMax: 77.7 },
  100: { a: 134.5, m: 0.357, hpMin: 50, hpMax: 60, fMin: 111.2, fMax: 126.1 },
};

// Per-piece weight split (matches the vanilla armor-point ratio already used across every
// existing armor entry in this file: helmet=3, chest=8, legs=6, boots=3 -> /20).
const WEIGHTS = { HELMET: 0.15, CHESTPLATE: 0.4, LEGGINGS: 0.3, BOOTS: 0.15 };
const PARTS = ["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"];

// Real (unmodified) vanilla Attribute.ARMOR totals per material, full 4-piece set.
const VANILLA_ARMOR_POINTS = {
  LEATHER: 7, COPPER: 11, GOLDEN: 11, CHAINMAIL: 12, IRON: 15, DIAMOND: 20, NETHERITE: 20,
};

function defenseRateFor(items, keys, vanillaMaterial) {
  const armorSum = keys.reduce((sum, k) => sum + (items[k]?.fixed?.["armor-defense-rate"] || 0), 0);
  const totalPoints = armorSum + VANILLA_ARMOR_POINTS[vanillaMaterial];
  return Math.min(totalPoints * 0.015, 0.8);
}

// physRes solved from m = (1 - defRate) * (1 - physRes)  [damage-reduction kept at 0]
function physResFor(defRate, mTarget) {
  return Math.max(0, 1 - mTarget / (1 - defRate));
}

// ---------------------------------------------------------------------------------------------
// Groups: 11 physical armor bands (T1/T2) + spear weapon family (T4).
// Each physical group carries its own vanillaMaterial (for defense-rate math) even when the
// CMD variant reuses another material's model (e.g. the source-gem set reuses DIAMOND_HELMET).
const PHYS_GROUPS = [
  { level: 0,   weight: "light", vanillaMaterial: "LEATHER",   keys: partKeys("LEATHER") },
  { level: 10,  weight: "light", vanillaMaterial: "COPPER",    keys: partKeys("COPPER") },
  { level: 20,  weight: "light", vanillaMaterial: "CHAINMAIL", keys: partKeys("CHAINMAIL") },
  { level: 30,  weight: "light", vanillaMaterial: "DIAMOND",   keys: partKeys("DIAMOND", "200101,200102,200103,200104") },
  { level: 30,  weight: "heavy", vanillaMaterial: "IRON",      keys: partKeys("IRON") },
  { level: 40,  weight: "heavy", vanillaMaterial: "GOLDEN",    keys: partKeys("GOLDEN") },
  { level: 55,  weight: "heavy", vanillaMaterial: "DIAMOND",   keys: partKeys("DIAMOND"), flipSkillTo: "HEAVY_ARMOR" },
  { level: 70,  weight: "heavy", vanillaMaterial: "NETHERITE", keys: partKeys("NETHERITE") },
  { level: 85,  weight: "heavy", vanillaMaterial: "NETHERITE", keys: partKeys("NETHERITE", "150,153,156,159") },
  { level: 100, weight: "light", vanillaMaterial: "NETHERITE", keys: partKeys("NETHERITE", "149,152,155,158") },
  { level: 100, weight: "heavy", vanillaMaterial: "NETHERITE", keys: partKeys("NETHERITE", "148,151,154,157") },
  // 2026-07-25 軽装7セット28点 (骨鎧/銅鋲の革鎧/甲殻鎧/金糸の装束/深海鱗の鎧/幻膜の外套/蝕みの絹):
  // 全てLEATHER素材ベース (material: LEATHER_HELMET等) にCMDのみ差し替えたカタログエントリ。
  // 各セットのdurabilityは「同レベル帯の対置装備 × 0.85」で別途手動設定済み(このスクリプトは
  // durabilityに触れない)。ここではphys-flat-defense/phys-resistance/max-healthのみをTABLEの
  // 既存levelに合わせて算出する — durabilityの対置元と異なり、こちらはvanillaMaterial=LEATHER
  // (VANILLA_ARMOR_POINTS.LEATHER=7)を使う: 素のLEATHER胴で拾えるarmor-defense-rate加点と同じ
  // 土台を共有するのが自然という判断 (colorのみ違うLEATHER派生アイテムであるため)。
  { level: 10, weight: "light", vanillaMaterial: "LEATHER", keys: partKeys("LEATHER", "200124,200125,200126,200127") },
  { level: 20, weight: "light", vanillaMaterial: "LEATHER", keys: partKeys("LEATHER", "200128,200129,200130,200131") },
  { level: 30, weight: "light", vanillaMaterial: "LEATHER", keys: partKeys("LEATHER", "200132,200133,200134,200135") },
  { level: 40, weight: "light", vanillaMaterial: "LEATHER", keys: partKeys("LEATHER", "200136,200137,200138,200139") },
  { level: 55, weight: "light", vanillaMaterial: "LEATHER", keys: partKeys("LEATHER", "200140,200141,200142,200143") },
  { level: 70, weight: "light", vanillaMaterial: "LEATHER", keys: partKeys("LEATHER", "200144,200145,200146,200147") },
  { level: 85, weight: "light", vanillaMaterial: "LEATHER", keys: partKeys("LEATHER", "200148,200149,200150,200151") },
];

function partKeys(material, cmdCsv) {
  if (!cmdCsv) {
    return { HELMET: `${material}_HELMET`, CHESTPLATE: `${material}_CHESTPLATE`,
      LEGGINGS: `${material}_LEGGINGS`, BOOTS: `${material}_BOOTS` };
  }
  const [h, c, l, b] = cmdCsv.split(",");
  return { HELMET: `${material}_HELMET#${h}`, CHESTPLATE: `${material}_CHESTPLATE#${c}`,
    LEGGINGS: `${material}_LEGGINGS#${l}`, BOOTS: `${material}_BOOTS#${b}` };
}

// Magic-robe (LEATHER#2000xx) groups: 3 families x 3 levels. Physical defense is derived from
// 60% of an interpolated "reference light armor" curve built from TABLE (Lv50/Lv80 have no real
// light band, so we log-interpolate the table's f values themselves).
const ROBE_LEVELS = [20, 50, 80];
const ROBE_FAMILIES = {
  guard: { 20: "200001,200002,200003,200004", 50: "200011,200012,200013,200014", 80: "200021,200022,200023,200024" },
  magic: { 20: "200031,200032,200033,200034", 50: "200041,200042,200043,200044", 80: "200051,200052,200053,200054" },
  weave: { 20: "200061,200062,200063,200064", 50: "200071,200072,200073,200074", 80: "200081,200082,200083,200084" },
};

function lerpLog(x0, y0, x1, y1, x) {
  const t = (x - x0) / (x1 - x0);
  return Math.exp(Math.log(y0) + t * (Math.log(y1) - Math.log(y0)));
}

function referenceF(level) {
  if (TABLE[level]) return { fMin: TABLE[level].fMin, fMax: TABLE[level].fMax };
  if (level > 40 && level < 55) {
    return {
      fMin: lerpLog(40, TABLE[40].fMin, 55, TABLE[55].fMin, level),
      fMax: lerpLog(40, TABLE[40].fMax, 55, TABLE[55].fMax, level),
    };
  }
  if (level > 70 && level < 85) {
    return {
      fMin: lerpLog(70, TABLE[70].fMin, 85, TABLE[85].fMin, level),
      fMax: lerpLog(70, TABLE[70].fMax, 85, TABLE[85].fMax, level),
    };
  }
  throw new Error(`no interpolation range for level ${level}`);
}

// Reference light-armor physRes per level, needed for the robe's 60% physRes figure. Uses the
// same defRate math as PHYS_GROUPS, evaluated against the *already-written* item-stats doc
// (this function is called after PHYS_GROUPS are applied, so it reads the final phys-resistance).
function referencePhysRes(items, level) {
  const known = { 20: partKeys("CHAINMAIL"), 40: partKeys("GOLDEN"), 55: partKeys("DIAMOND"), 70: partKeys("NETHERITE"), 85: partKeys("NETHERITE", "150,153,156,159") };
  function totalPhysRes(keys) {
    return PARTS.reduce((sum, p) => sum + (items[keys[p]]?.fixed?.["phys-resistance"] || 0), 0);
  }
  if (known[level]) return totalPhysRes(known[level]);
  if (level > 40 && level < 55) {
    const t = (level - 40) / 15;
    return totalPhysRes(known[40]) + t * (totalPhysRes(known[55]) - totalPhysRes(known[40]));
  }
  if (level > 70 && level < 85) {
    const t = (level - 70) / 15;
    return totalPhysRes(known[70]) + t * (totalPhysRes(known[85]) - totalPhysRes(known[70]));
  }
  throw new Error(`no physRes interpolation range for level ${level}`);
}

// ---------------------------------------------------------------------------------------------
const original = fs.readFileSync(targetPath, "utf8");
const itemsHeaderMatch = original.match(/\r?\nitems:\r?\n/);
if (!itemsHeaderMatch) throw new Error("could not locate the 'items:' top-level key to split off the header comment block");
const header = original.slice(0, itemsHeaderMatch.index + 1);
const doc = YAML.parse(original);
const items = doc.items;

const report = { physGroups: [], robeGroups: [], spears: [] };

// --- T1/T2: physical armor ladder ---------------------------------------------------------------
for (const group of PHYS_GROUPS) {
  const row = TABLE[group.level];
  const defRate = defenseRateFor(items, Object.values(group.keys), group.vanillaMaterial);
  const physRes = physResFor(defRate, row.m);
  const bonusHpMin = row.hpMin - 20;
  const bonusHpMax = row.hpMax - 20;

  for (const part of PARTS) {
    const key = group.keys[part];
    const entry = items[key];
    if (!entry) throw new Error(`missing item-stats entry for ${key} (level ${group.level}/${group.weight})`);
    const w = WEIGHTS[part];
    entry.fixed = entry.fixed || {};

    entry.fixed["phys-flat-defense"] = round2(row.fMin * w);
    entry.random = entry.random || {};
    entry.random["phys-flat-defense"] = { min: 0, max: round2((row.fMax - row.fMin) * w) };

    entry.fixed["phys-resistance"] = round4(physRes * w);

    if (bonusHpMax > 0) {
      entry.fixed["max-health"] = round2(bonusHpMin * w);
      if (bonusHpMax > bonusHpMin) {
        entry.random["max-health"] = { min: 0, max: round2((bonusHpMax - bonusHpMin) * w) };
      }
    }

    if (group.flipSkillTo) entry["use-skill"] = group.flipSkillTo;
    if (group.weight === "heavy" && group.flipSkillTo) {
      // DIAMOND vanilla light->heavy conversion: drop the light-armor dodge, add heavy armor-strength.
      delete entry.fixed["dodge-chance"];
      if (part === "HELMET" || part === "BOOTS") entry.fixed["armor-strength"] = 0.14;
    }
  }

  report.physGroups.push({
    level: group.level, weight: group.weight, vanillaMaterial: group.vanillaMaterial,
    defRate: round2(defRate), physRes: round2(physRes),
    hp: [row.hpMin, row.hpMax], f: [row.fMin, row.fMax],
  });
}

// --- T3: magic robe physical-defense floor (random-only, keeps fixed.* undefined on purpose --
// item-stat-coverage.test.js "ソースジェム防具と魔法系防具だけが魔法防御を持つ" asserts
// entry.fixed["phys-flat-defense"]/["phys-resistance"] stay undefined for these 36 entries; the
// engine still applies random-roll stats on top of an implicit 0 base (see item-stats.yml header
// comment), so a random{min>0,...} floor removes the "physical instant death" bug without
// touching the coverage test's asserted invariant.
for (const [family, levels] of Object.entries(ROBE_FAMILIES)) {
  for (const level of ROBE_LEVELS) {
    const cmdCsv = levels[level];
    const keys = partKeys("LEATHER", cmdCsv);
    const { fMin, fMax } = referenceF(level);
    const refPhysRes = referencePhysRes(items, level);
    const fCenter = (fMin + fMax) / 2 * 0.6;
    const fSpreadCenter = (fMax - fMin) / 2 * 0.6;
    const resCenter = refPhysRes * 0.6;

    for (const part of PARTS) {
      const key = keys[part];
      const entry = items[key];
      if (!entry) throw new Error(`missing robe entry ${key} (${family} Lv${level})`);
      const w = WEIGHTS[part];
      entry.random = entry.random || {};
      entry.random["phys-flat-defense"] = {
        min: round4((fCenter - fSpreadCenter) * w * 0.7),
        max: round4((fCenter + fSpreadCenter) * w * 1.3),
      };
      entry.random["phys-resistance"] = { min: round4(resCenter * w * 0.7), max: round4(resCenter * w * 1.3) };
    }
    report.robeGroups.push({ family, level, fCenter60: round2(fCenter), resCenter60: round2(resCenter) });
  }
}

// --- T4: spear fixed-damage + 15% attack-power nerf ---------------------------------------------
// Guarded by SKIP_SPEARS so this script can be safely re-run to fix rounding on the armor/robe
// sections above without compounding the one-shot 0.85x attack-power multiplier a second time.
const spearCategory = process.env.SKIP_SPEARS ? null : (doc._editor.categories.weapon || []).find((c) => c.label === "槍");
if (!process.env.SKIP_SPEARS && !spearCategory) throw new Error("槍 category not found");
if (spearCategory) {
function levelForSpear(entry) {
  return entry["use-level-requirement"] ?? 0;
}
function aFor(level) {
  return 7.0 * Math.pow(1.03, level);
}
for (const key of spearCategory.itemIds) {
  const entry = items[key];
  if (!entry?.fixed) throw new Error(`spear ${key} missing fixed block`);
  const level = levelForSpear(entry);
  const fixedDamage = round2(aFor(level) * 0.15);
  entry.fixed["fixed-damage"] = fixedDamage;
  const oldAp = entry.fixed["attack-power"];
  entry.fixed["attack-power"] = round2(oldAp * 0.85);
  if (entry["per-quality"]?.["attack-power"] != null) {
    entry["per-quality"]["attack-power"] = round2(entry["per-quality"]["attack-power"] * 0.85);
  }
  if (entry.random?.["attack-power"]) {
    entry.random["attack-power"].min = round2(entry.random["attack-power"].min * 0.85);
    entry.random["attack-power"].max = round2(entry.random["attack-power"].max * 0.85);
  }
  report.spears.push({ key, level, fixedDamage, newAttackPower: entry.fixed["attack-power"], oldAttackPower: oldAp });
}
}

fs.writeFileSync(targetPath, `${header}${YAML.stringify(doc)}`, "utf8");
console.log(JSON.stringify(report, null, 2));
