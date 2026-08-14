"use strict";

// One-shot, deterministic authoring helper for the shipped item-stat table. It deliberately
// writes only static YAML: runtime balancing never depends on this script. It only emits fields
// consumed by ItemStatsConfig; unsupported legacy fields must not be copied back into the table.
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const root = path.resolve(__dirname, "..", "..", "..");
const catalogPath = path.join(root, "TrinityForge", "src", "main", "resources", "items", "catalog.yml");
const targetPath = path.join(root, "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml");

// ---------------------------------------------------------------------------
// 実行ゲート (2026-08-02 追加)
//
// このスクリプトは出荷 item-stats.yml を【丸ごと上書きする】。ところが tiers 表は
// 手で入れた調整を1つも持っていないため、うっかり走らせると以下が無言で巻き戻る:
//
//   ・U5 重武器 attack-power ×1.157 (目標帯 +15% への引き下げ)
//   ・U6 遠隔武器の attack-speed 0.1 (近接素振りの抑止)
//   ・yml 内の日本語コメント全部 (YAML.stringify は再生成なのでコメントを持てない)
//   ・generator が生成しない孤児CMD (GOLDEN_SWORD#59 / WOODEN_SWORD#60 / #51 …)
//
// 「気づけない事故」なので、意図の表明なしには走らせない。復旧は git checkout だけなので
// 実害は小さいが、他セッションの WIP を巻き込むと戻せなくなる。
// ---------------------------------------------------------------------------
if (!process.argv.includes("--force")) {
  console.error(`generate-item-stats.js は出荷 item-stats.yml を丸ごと上書きします。

  上書きすると失われるもの:
    - U5 重武器 attack-power ×1.157 (この generator の tiers 表は持っていません)
    - U6 遠隔武器 attack-speed 0.1
    - yml 内の日本語コメント全部 (再生成なのでコメントは復元されません)
    - generator が生成しない孤児CMD (GOLDEN_SWORD#59 / WOODEN_SWORD#60 / #51 など)

  出荷 yml が真源です。表を書き換えたいときは generator ではなく yml を直接編集するか、
  設定エディタから保存してください。

  それでも再生成する場合:  node scripts/generate-item-stats.js --force
  実行後は必ず  git diff -- TrinityForge/src/main/resources/stats/item-stats.yml  で
  意図しない巻き戻りが無いか確認してください。`);
  process.exit(1);
}

const catalog = YAML.parse(fs.readFileSync(catalogPath, "utf8"));

const tiers = {
  WOOD:      { level: 0, offset:  0, power: 75,    threads: 0 },
  STONE:     { level: 10, offset: -1, power: 125,  threads: 0 },
  COPPER:    { level: 20, offset: -2, power: 200,  threads: 0 },
  IRON:      { level: 30, offset: -3, power: 350,  threads: 0 },
  SOURCE:    { level: 30, offset: -3, power: 320,  threads: 2 },
  GOLD:      { level: 40, offset: -4, power: 550,  threads: 0 },
  DIAMOND:   { level: 55, offset: -5, power: 1500, threads: 0 },
  NETHERITE: { level: 70, offset: -6, power: 4500, threads: 0 },
  WITHER:    { level: 85, offset: -7, power: 15000, threads: 2 },
  DRAGON:    { level: 100, offset: -8, power: 28000, threads: 3 },
  INFINITY:  { level: 100, offset: -8, power: 45000, threads: 0 }
};

// Gathering tools deliberately progress earlier than weapons. The quality offset follows the same
// ten-level baseline convention, while named special tiers keep their shared tier values.
const gatheringToolTiers = {
  WOOD:      { level: 0,  offset:  0 },
  STONE:     { level: 0,  offset:  0 },
  COPPER:    { level: 10, offset: -1 },
  IRON:      { level: 20, offset: -2 },
  GOLD:      { level: 20, offset: -2 },
  DIAMOND:   { level: 40, offset: -4 },
  NETHERITE: { level: 60, offset: -6 }
};

const family = {
  "剣":         { power: 1.00, speed:  0.00, reach: 0.0, crit: .08, critDamage: .50, pen: .05, modifier: 1.00 },
  "短剣":       { power: 0.68, speed:  0.45, reach: -.45, crit: .12, critDamage: .55, pen: .04, modifier: .95 },
  "レイピア":   { power: 0.78, speed:  0.18, reach: .10, crit: .07, critDamage: .45, pen: .20, modifier: .97 },
  "鎌":         { power: 0.76, speed:  0.12, reach: .10, crit: .06, critDamage: .45, pen: .06, modifier: .96, bleed: true },
  "モーニングスター": { power: 1.14, speed: -.32, reach: .70, crit: .04, critDamage: .65, pen: .08, modifier: 1.04, fixed: true },
  "ウォーハンマー":   { power: 1.23, speed: -.42, reach: .22, crit: .05, critDamage: .70, pen: .10, modifier: 1.06, fixed: true },
  "戦斧":       { power: 1.19, speed: -.42, reach: .05, crit: .06, critDamage: .60, pen: .08, modifier: 1.05 },
  "大斧":       { power: 1.36, speed: -.62, reach: .28, crit: .04, critDamage: .72, pen: .08, modifier: 1.08 },
  "大剣":       { power: 1.12, speed: -.48, reach: .25, crit: .05, critDamage: .58, pen: .06, modifier: 1.02 },
  "槍":         { power: 0.82, speed: -.16, reach: 1.00, crit: .05, critDamage: .48, pen: .24, modifier: .98 },
  "メイス":     { power: 1.00, speed: -.12, reach: .00, crit: .06, critDamage: .55, pen: .08, modifier: 1.00, powerAttack: true },
  "トライデント": { power: .90, speed: -.62, reach: .50, crit: .06, critDamage: .50, pen: .20, modifier: .99, bleed: true, archery: true },
  "弓":         { power: .78, speed: 0, reach: 0, crit: .18, critDamage: .72, pen: .10, modifier: 1.00, bow: true },
  "クロスボウ": { power: .92, speed: 0, reach: 0, crit: .11, critDamage: .85, pen: .13, modifier: 1.02, crossbow: true }
};

const armorTiers = {
  LEATHER:    { weight: 2, level: 0, offset: 0, durability: [55, 80, 75, 65], threads: 0 },
  CHAINMAIL:  { weight: 3, level: 20, offset: -2, durability: [165, 240, 225, 195], threads: 0 },
  COPPER:     { weight: 5, level: 10, offset: -1, durability: [176, 256, 240, 208], threads: 0 },
  IRON:       { weight: 7, level: 30, offset: -3, durability: [165, 240, 225, 195], threads: 0 },
  GOLDEN:     { weight: 10, level: 40, offset: -4, durability: [77, 112, 105, 91], threads: 0 },
  DIAMOND:    { weight: 5, level: 55, offset: -5, durability: [363, 528, 495, 429], threads: 0 },
  NETHERITE:  { weight: 8, level: 70, offset: -6, durability: [407, 592, 555, 481], threads: 0 },
  SOURCE:     { weight: 4, level: 30, offset: -3, durability: [363, 528, 495, 429], threads: 2 },
  WITHER:     { weight: 9, level: 85, offset: -7, durability: [580, 840, 790, 680], threads: 2 },
  DRAGON:     { weight: 1, level: 100, offset: -8, durability: [650, 940, 885, 760], threads: 3 },
  INFINITY:   { weight: 10, level: 100, offset: -8, durability: [900, 1300, 1220, 1050], threads: 0 }
};

// 防具値(点数)の重量別テーブル。2026-08-15 に防具値ステ(armor-defense-rate)を廃止したので、
// ここは「はしごの目盛り」としてだけ残し、出力は DEFENSE_RATE_PER_POINT を掛けた
// 防御率(defense-rate)にする。目盛りを点数のまま持つのは、既存のはしご設計
// (最良4部位で重量10 = 20点 = 30%軽減)をそのまま読めるようにするため。
const armorPoints = { 1: 4, 2: 5, 3: 6, 4: 8, 5: 10, 7: 14, 8: 16, 9: 18, 10: 20 };
// combat/damage.yml の vanilla-armor.defense-rate-per-point と同値(1点=1.5%軽減)。
const DEFENSE_RATE_PER_POINT = 0.015;
const armorResistance = { 1: .02, 2: .03, 3: .045, 4: .065, 5: .09, 7: .15, 8: .20, 9: .26, 10: .32 };
const armorFlat = { 1: 8, 2: 12, 3: 20, 4: 30, 5: 45, 7: 90, 8: 135, 9: 200, 10: 280 };
const armorStrength = { 1: .04, 2: .06, 3: .08, 4: .11, 5: .14, 7: .24, 8: .30, 9: .36, 10: .42 };
const armorDodge = { 1: .18, 2: .15, 3: .12, 4: .10, 5: .07, 7: .03, 8: .02, 9: .01, 10: 0 };
const armorMove = { 1: .030, 2: .022, 3: .015, 4: .010, 5: .005, 7: -.008, 8: -.014, 9: -.020, 10: -.028 };
const slot = {
  HELMET: { short: "helmet", share: .15, strength: .5, dodge: .6, move: 0 },
  CHESTPLATE: { short: "chestplate", share: .40, strength: 0, dodge: .4, move: 0 },
  LEGGINGS: { short: "leggings", share: .30, strength: 0, dodge: 0, move: .7 },
  BOOTS: { short: "boots", share: .15, strength: .5, dodge: 0, move: .3 }
};

const toolDurability = {
  WOODEN: 59, STONE: 131, COPPER: 190, IRON: 250, GOLDEN: 32, DIAMOND: 1561, NETHERITE: 2031,
  SHEARS: 238, FISHING_ROD: 64
};

// Finite weapon durability (vanilla-equivalent by tier; ranged/heavy specials overridden per family).
const weaponTierDurability = {
  WOOD: 59, STONE: 131, COPPER: 190, IRON: 250, SOURCE: 250, GOLD: 32,
  DIAMOND: 1561, NETHERITE: 2031, WITHER: 2031, DRAGON: 2031, INFINITY: 2031
};

// Tool material -> gathering skill (explicit use-skill; no use-level => association/lore only, no gate).
function toolSkillFor(name) {
  const n = String(name || "").toUpperCase();
  if (n.endsWith("_PICKAXE") || n === "PICKAXE") return "MINING";
  if (n.endsWith("_SHOVEL") || n === "SHOVEL") return "DIGGING";
  if (n.endsWith("_HOE") || n === "HOE") return "FARMING";
  if (n.endsWith("_AXE") || n === "AXE") return "WOODCUTTING";
  if (n === "SHEARS") return "FARMING";
  if (n === "FISHING_ROD") return "FISHING";
  return null;
}

// Differentiate weapon damage-modifier: rescale the raw family band (0.95..1.08) into 0.50..0.90.
function remapModifier(rawModifier) { return round(0.50 + (rawModifier - 0.95) / 0.13 * 0.40, 2); }

const items = {};
const endgameTiers = new Set(["WITHER", "DRAGON", "INFINITY"]);
function add(key, value) { items[key] = value; }
function itemKey(item) { return item["custom-model-data"] == null ? item.material : `${item.material}#${item["custom-model-data"]}`; }
function round(value, decimals = 3) { const p = 10 ** decimals; return Math.round(value * p) / p; }
function tierFor(id) {
  const value = id.toLowerCase();
  if (value.includes("infinity")) return "INFINITY";
  if (value.includes("dragon") || value.includes("fnis_peccati")) return "DRAGON";
  if (value.includes("wither") || value.includes("winter_grim")) return "WITHER";
  if (value.includes("source_gem")) return "SOURCE";
  if (value.includes("netherite") || value.includes("nethrite") || value.includes("nether_star")) return "NETHERITE";
  if (value.includes("diamond") || value.includes("revolution")) return "DIAMOND";
  if (value.includes("golden") || value.includes("golad") || value.includes("gold_")) return "GOLD";
  if (value.includes("iron")) return "IRON";
  if (value.includes("copper")) return "COPPER";
  if (value.includes("stone") || value.includes("sharpstone")) return "STONE";
  return "WOOD";
}
function weaponSkill(profile) { return profile.archery || profile.bow || profile.crossbow ? "ARCHERY" : profile.heavy ? "HEAVY_WEAPONS" : "LIGHT_WEAPONS"; }
function standardWeaponRandom(target, familyName) {
  // Wider selection: attack-power spread +-~18% plus two rolled secondaries per family.
  const random = {
    "attack-power": { min: round(-target * .16, 1), max: round(target * .20, 1) }
  };
  let primaryKey;
  if (["短剣", "弓", "クロスボウ"].includes(familyName)) {
    primaryKey = "crit-chance";
    random["crit-chance"] = { min: -.05, max: .07 };
  } else if (["レイピア", "槍", "トライデント"].includes(familyName)) {
    primaryKey = "penetration";
    random.penetration = { min: -.06, max: .09 };
  } else if (familyName === "鎌") {
    primaryKey = "bleed-chance";
    random["bleed-chance"] = { min: -.07, max: .11 };
  } else if (familyName === "メイス") {
    primaryKey = "power-attack-damage";
    random["power-attack-damage"] = { min: -.12, max: .18 };
  } else {
    primaryKey = "crit-damage";
    random["crit-damage"] = { min: -.12, max: .18 };
  }
  if (primaryKey === "crit-damage") {
    random["crit-chance"] = { min: -.04, max: .06 };
  } else {
    random["crit-damage"] = { min: -.10, max: .15 };
  }
  return random;
}
function weaponEntry(tierName, familyName) {
  const t = tiers[tierName];
  const f = family[familyName] || family["剣"];
  const specialHeavy = ["モーニングスター", "ウォーハンマー", "戦斧", "大斧", "大剣", "メイス"].includes(familyName);
  const effective = { ...f, heavy: specialHeavy };
  const target = t.power * f.power;
  const copper = tierName === "COPPER";
  const gold = tierName === "GOLD";
  const bowLowQuality = f.bow;
  const fixedPower = round(target * (copper ? .60 : gold ? .72 : bowLowQuality ? 1.18 : .84), 1);
  const perQualityPower = round(target * (copper ? .10 : gold ? .03 : bowLowQuality ? -.045 : f.crossbow ? .065 : .04), 1);
  const fixed = {
    "attack-power": fixedPower,
    "attack-speed": f.speed,
    "attack-reach": f.reach,
    "crit-chance": f.crit,
    "crit-damage": f.critDamage,
    penetration: f.pen,
    // 武器種ごとにダメージ補正を差別化する。全ティア一律に family 本来値を 0.50〜0.90 帯へ再マッピング。
    "damage-modifier": remapModifier(f.modifier)
  };
  if (f.bleed) Object.assign(fixed, { "bleed-chance": .16, "bleed-damage": round(target * .08, 1) });
  if (f.fixed) fixed["fixed-damage"] = round(target * .05, 1);
  if (f.powerAttack) fixed["power-attack-damage"] = .35;
  if (f.bow || f.crossbow) Object.assign(fixed, {
    "bow-accuracy": f.bow ? .07 : .04,
    "arrow-velocity": f.crossbow ? .12 : .06,
    "distance-damage-bonus": f.bow ? .15 : .08,
    "ammo-save-chance": f.bow ? .08 : .04,
    "move-speed": f.bow ? .015 : .008
  });
  if (t.threads) fixed["thread-slots"] = t.threads;
  if (tierName === "WITHER") Object.assign(fixed, { "move-speed": -.018, "dodge-chance": -.02 });
  // 有限耐久（バニラ相当・ツール/防具と同じ 60% base + 10%/quality 方式）。
  const weaponDur = f.bow ? 384 : f.crossbow ? 465
    : familyName === "トライデント" ? 250 : familyName === "メイス" ? 500
      : weaponTierDurability[tierName] || 2031;
  fixed.durability = Math.floor(weaponDur * .6);
  const result = {
    fixed,
    "per-quality": {
      "attack-power": perQualityPower,
      "crit-chance": f.crossbow ? .012 : .003,
      "crit-damage": .02,
      penetration: .004,
      durability: Math.max(1, Math.round(weaponDur * .1))
    },
    "use-level-requirement": t.level,
    "use-skill": weaponSkill(effective),
    "quality-mode-offset": t.offset
  };
  // 厳選幅は実際の fixed attack-power 基準（デプロイ中の item-stats と一致させる）。
  result.random = standardWeaponRandom(fixedPower, familyName);
  if (gold) result.random = {
    // 品質上限付近の中心値を従来以上に押し上げず、上下の厳選幅だけを拡大する。
    "attack-power": { min: round(-target * 1.10, 1), max: round(target * 1.00, 1) },
    "crit-chance": { min: -.34, max: .26 },
    "crit-damage": { min: -.70, max: .70 },
    penetration: { min: -.25, max: .225 },
    // 最終値も負まで落ちるギャンブル幅。戦闘結果は共通の min-component-damage だけで制御する。
    "damage-modifier": { min: -1.60, max: 1.65 }
  };
  if (tierName === "WITHER") result.random = {
    "attack-power": { min: round(-target * .18, 1), max: round(target * .20, 1) },
    "phys-resistance": { min: -.08, max: .04 },
    "move-speed": { min: -.020, max: .004 }
  };
  return result;
}
function toolEntry(durability, tierName = "", skill = null) {
  const result = {
    fixed: { durability: Math.floor(durability * .6) },
    "per-quality": { durability: Math.max(1, Math.round(durability * .1)) }
  };
  // Gold's material identity is high variance. Keep the Q4 center near vanilla durability while
  // allowing meaningful low/high rolls without ever driving the resolved maximum below 1.
  if (tierName === "GOLD") {
    result.random = {
      durability: { min: -Math.floor(durability * .25), max: Math.ceil(durability * .5) }
    };
  }
  if (skill) {
    const tier = gatheringToolTiers[tierName] || tiers[tierName];
    if (tier) {
      result["use-level-requirement"] = tier.level;
      result["quality-mode-offset"] = tier.offset;
    }
    result["use-skill"] = skill;
  }
  return result;
}
function armorEntry(config, slotName, magic = null, health = 0, physical = true, mana = 0) {
  const s = slot[slotName];
  const index = ["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"].indexOf(slotName);
  const fixed = {
    "defense-rate": round(Math.max(1, Math.round(armorPoints[config.weight] * s.share)) * DEFENSE_RATE_PER_POINT),
    durability: Math.floor(config.durability[index] * .6)
  };
  if (physical) {
    fixed["phys-resistance"] = round(armorResistance[config.weight] * s.share);
    fixed["phys-flat-defense"] = round(armorFlat[config.weight] * s.share, 1);
  }
  if (s.strength) fixed["armor-strength"] = round(armorStrength[config.weight] * s.strength);
  if (s.dodge && armorDodge[config.weight]) fixed["dodge-chance"] = round(armorDodge[config.weight] * s.dodge);
  if (s.move && armorMove[config.weight]) fixed["move-speed"] = round(armorMove[config.weight] * s.move);
  if (config.threads) fixed["thread-slots"] = config.threads;
  if (health) fixed["max-health"] = round(health * s.share, 1);
  if (mana) fixed["mana-bonus"] = round(mana * s.share, 1);
  if (magic) {
    fixed["magic-resistance"] = round(magic.resistance * s.share);
    fixed["magic-flat-defense"] = round(magic.flat * s.share, 1);
  }
  if (config.weight === 9) fixed["move-speed"] = (fixed["move-speed"] || 0) - .005;
  return {
    fixed,
    "per-quality": { durability: Math.max(1, Math.round(config.durability[index] * .1)) },
    "use-level-requirement": config.level,
    "use-skill": config.weight <= 5 ? "LIGHT_ARMOR" : "HEAVY_ARMOR",
    "quality-mode-offset": config.offset
  };
}
function catalystEntry(tierName) {
  const t = tiers[tierName];
  const target = t.power * .28;
  return {
    fixed: {
      "attack-power": round(target * .84, 1), "mana-bonus": Math.round(t.power / 45),
      "mana-regen": round(Math.max(1, t.power / 5000), 2), "source-cost-reduction": round(Math.min(.35, .03 + t.level / 500)),
      "thread-slots": Math.max(1, t.threads || 1)
    },
    "per-quality": { "attack-power": round(target * .035, 1), "mana-bonus": Math.max(1, Math.round(t.power / 450)) },
    "use-level-requirement": t.level,
    "use-skill": "ARS_MAGIC",
    "quality-mode-offset": t.offset
  };
}

// Vanilla weapons, armor and ordinary tools.  Exact catalog entries below override these profiles.
for (const tierName of ["WOOD", "STONE", "COPPER", "IRON", "GOLD", "DIAMOND", "NETHERITE"]) {
  const material = tierName === "WOOD" ? "WOODEN" : tierName === "GOLD" ? "GOLDEN" : tierName;
  add(`${material}_SWORD`, weaponEntry(tierName, "剣"));
  add(`${material}_SPEAR`, weaponEntry(tierName, "槍"));
  for (const tool of ["PICKAXE", "SHOVEL", "HOE"]) add(`${material}_${tool}`, toolEntry(toolDurability[material], tierName, toolSkillFor(tool)));
}
add("BOW", weaponEntry("WOOD", "弓"));
add("CROSSBOW", weaponEntry("IRON", "クロスボウ"));
add("TRIDENT", weaponEntry("DIAMOND", "トライデント"));
add("MACE", weaponEntry("DIAMOND", "メイス"));
add("SHEARS", toolEntry(toolDurability.SHEARS, "", toolSkillFor("SHEARS")));
add("FISHING_ROD", toolEntry(toolDurability.FISHING_ROD, "", toolSkillFor("FISHING_ROD")));
// Material名を直接使えるバニラ防具だけをここで生成する。
// SOURCE/WITHER/DRAGON/INFINITY はカタログの Material#CMD キーとして後段で生成する。
for (const name of ["LEATHER", "CHAINMAIL", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"]) {
  const config = armorTiers[name];
  for (const part of Object.keys(slot)) add(`${name}_${part}`, armorEntry(config, part));
}
add("TURTLE_HELMET", armorEntry({ ...armorTiers.CHAINMAIL, weight: 3 }, "HELMET"));

// Catalog weapon/catalyst/tool/other entries. Other items receive an explicit empty profile so they
// remain editable and categorized without changing gameplay until a stat is authored.
for (const tab of ["weapon", "catalyst", "tool", "other"]) {
  for (const category of catalog._editor.categories[tab] || []) {
    for (const id of category.itemIds || []) {
      const item = catalog.items[id];
      if (!item) continue;
      const key = itemKey(item);
      const tierName = tierFor(id);
      add(key, tab === "weapon" ? weaponEntry(tierName, category.label)
        : tab === "catalyst" ? catalystEntry(tierName)
          : tab === "tool" ? toolEntry(toolDurability[item.material.split("_")[0]] || toolDurability.NETHERITE, tierName, toolSkillFor(item.material))
            : {});
    }
  }
}

// Source-gem and endgame armor.
for (const part of Object.keys(slot)) {
  const source = catalog.items[`source_gem_${slot[part].short}`];
  add(itemKey(source), armorEntry(armorTiers.SOURCE, part));
  for (const [prefix, tierName] of [["wither", "WITHER"], ["dragon", "DRAGON"], ["infinity", "INFINITY"]]) {
    const item = catalog.items[`${prefix}_${slot[part].short}`];
    add(itemKey(item), armorEntry(armorTiers[tierName], part, null, tierName === "DRAGON" ? 30 : tierName === "INFINITY" ? 50 : 0));
  }
}

const mageConfig = {
  guardian: { weight: 7, threads: 1, magic: [{ resistance: .10, flat: 40 }, { resistance: .18, flat: 100 }, { resistance: .28, flat: 220 }], mana: [15, 40, 90] },
  arcane: { weight: 5, threads: 2, magic: [{ resistance: .08, flat: 25 }, { resistance: .16, flat: 70 }, { resistance: .25, flat: 160 }], mana: [35, 90, 200] },
  weaver: { weight: 1, threads: 4, magic: [{ resistance: .05, flat: 15 }, { resistance: .10, flat: 45 }, { resistance: .18, flat: 100 }], mana: [20, 55, 120] }
};
const mageRanks = [["novice", 20, -3, 0, [180, 260, 245, 210]], ["apprentice", 50, -5, 1, [300, 440, 410, 350]], ["archmage", 80, -7, 2, [480, 700, 655, 560]]];
for (const [kind, spec] of Object.entries(mageConfig)) {
  for (const [rank, level, offset, index, durability] of mageRanks) {
    for (const part of Object.keys(slot)) {
      const item = catalog.items[`mage_${kind}_${rank}_${slot[part].short}`];
      const config = { weight: spec.weight, level, offset, durability, threads: spec.threads };
      const entry = armorEntry(config, part, spec.magic[index], 0, false, spec.mana[index]);
      entry.fixed["mana-regen"] = round((index + 1) * (kind === "arcane" ? 2 : 1.2), 1);
      add(itemKey(item), entry);
    }
  }
}

// Editor categories follow the catalog's tab/category names, with vanilla entries inserted into
// their matching groups so no configured item becomes an unfindable orphan.
const categories = structuredClone(catalog._editor.categories);
// Catalog categories store custom ids, while item-stats is keyed by Material or Material#CMD.
// Convert every catalog member before adding vanilla entries so nested-category filtering uses
// the same identifiers as the actual items map (not orphan aliases such as wooden_wand).
for (const rows of Object.values(categories)) {
  for (const row of rows || []) {
    row.itemIds = [...new Set((row.itemIds || []).map((id) => {
      const item = catalog.items[id];
      return item ? itemKey(item) : id;
    }))];
  }
}
function category(tab, label) { return (categories[tab] || []).find((row) => row.label === label); }
function append(tab, label, values) { const row = category(tab, label); if (row) row.itemIds = [...new Set([...(row.itemIds || []), ...values])]; }
append("weapon", "剣", ["WOODEN_SWORD", "STONE_SWORD", "COPPER_SWORD", "IRON_SWORD", "GOLDEN_SWORD", "DIAMOND_SWORD", "NETHERITE_SWORD"]);
append("weapon", "槍", ["WOODEN_SPEAR", "STONE_SPEAR", "COPPER_SPEAR", "IRON_SPEAR", "GOLDEN_SPEAR", "DIAMOND_SPEAR", "NETHERITE_SPEAR"]);
append("weapon", "弓", ["BOW"]);
append("weapon", "トライデント", ["TRIDENT"]);
append("weapon", "メイス", ["MACE"]);
append("weapon", "クロスボウ", ["CROSSBOW"]);
for (const [label, prefix] of [["ツルハシ", "PICKAXE"], ["斧", "AXE"], ["シャベル", "SHOVEL"], ["クワ", "HOE"]]) {
  if (prefix !== "AXE") {
    append("tool", label, ["WOODEN", "STONE", "COPPER", "IRON", "GOLDEN", "DIAMOND", "NETHERITE"].map((m) => `${m}_${prefix}`));
  }
}
append("tool", "ハサミ", ["SHEARS"]); append("tool", "釣り竿", ["FISHING_ROD"]);
for (const [label, name] of [["革", "LEATHER"], ["銅", "COPPER"], ["チェーン", "CHAINMAIL"], ["鉄", "IRON"], ["金", "GOLDEN"], ["ダイヤ", "DIAMOND"], ["ネザライト", "NETHERITE"]]) {
  append("armor", label, Object.keys(slot).map((part) => `${name}_${part}`));
}
append("armor", "革", ["TURTLE_HELMET"]);
append("armor", "守護", mageRanks.flatMap(([rank]) => Object.keys(slot).map((part) => `LEATHER_${part}#${rank === "novice" ? 20000 : rank === "apprentice" ? 20001 : 20002}${({ HELMET: 1, CHESTPLATE: 2, LEGGINGS: 3, BOOTS: 4 })[part]}`)));
append("armor", "魔導", mageRanks.flatMap(([rank]) => Object.keys(slot).map((part) => `LEATHER_${part}#${rank === "novice" ? 20003 : rank === "apprentice" ? 20004 : 20005}${({ HELMET: 1, CHESTPLATE: 2, LEGGINGS: 3, BOOTS: 4 })[part]}`)));
append("armor", "魔織", mageRanks.flatMap(([rank]) => Object.keys(slot).map((part) => `LEATHER_${part}#${rank === "novice" ? 20006 : rank === "apprentice" ? 20007 : 20008}${({ HELMET: 1, CHESTPLATE: 2, LEGGINGS: 3, BOOTS: 4 })[part]}`)));
append("armor", "エンドラ", Object.keys(slot).map((part) => itemKey(catalog.items[`dragon_${slot[part].short}`])));
append("armor", "ウィザー", Object.keys(slot).map((part) => itemKey(catalog.items[`wither_${slot[part].short}`])));
append("armor", "インフィニティ", Object.keys(slot).map((part) => itemKey(catalog.items[`infinity_${slot[part].short}`])));

const itemTabs = {};
for (const [tab, rows] of Object.entries(categories)) for (const row of rows || []) for (const id of row.itemIds || []) itemTabs[id] = tab;
const orders = Object.fromEntries(Object.entries(categories).map(([tab, rows]) => [tab, (rows || []).flatMap((row) => row.itemIds || [])]));
const generated = { items, _editor: { categories, itemTabs, orders } };
const original = fs.readFileSync(targetPath, "utf8");
const header = original.slice(0, original.indexOf("\nitems:\n") + 1);
fs.writeFileSync(targetPath, `${header}${YAML.stringify(generated)}`, "utf8");
console.log(`wrote ${Object.keys(items).length} item-stat profiles`);
// 注意: このスクリプトは source tree の item-stats.yml のみを書き換える(deployミラーはしない)。
// エディタの保存経路と違い稼働サーバへ自動反映されないため、実サーバに反映するには
// エディタで item-stats を一度保存する(mirrorToDeployが走る)か、deploy先へ手動同期し、
// 最後に /trinityforge reload かサーバ再起動を行うこと。
console.log("⚠ deployミラーは未実施です。実サーバ反映にはエディタで再保存 or deploy先へ同期 + /trinityforge reload が必要です。");
// ⚠ 既知の乖離: 出荷中(deploy)の item-stats.yml には、この generator が生成しない孤児CMD
//   (GOLDEN_SWORD#59 / WOODEN_SWORD#60 / WOODEN_SWORD#51 等)や、GOLD武器のギャンブル幅・GOLD防具の
//   旧値が含まれる。再生成すると孤児は消え、GOLD系は generator 値に上書きされる。4要件
//   (武器耐久 / 厳選幅 / ダメージ補正0.50〜0.90 / ツール use-skill) は本 generator にも反映済みだが、
//   稼働サーバへは常に deploy 中の item-stats.yml を真源として扱い、単純再生成前に diff で乖離を確認すること。
