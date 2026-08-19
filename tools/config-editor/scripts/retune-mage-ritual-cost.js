"use strict";

// items/catalog.yml の【Ars向け装備（魔導防具3シリーズ×5段＝60点／魔導書2点）】の儀式コストを
// 引き上げる (2026-08-19 / W-120)。
//
// ■ なぜ要るか（実測）
// 帯(use-level-requirement)は 見習いLv20 / 魔術師Lv40 / 大魔導士Lv60 / 賢者Lv80 / 星詠みLv100 で、
// **星詠みは infinity 防具とまったく同じ Lv100 帯**。ところがソース要求量は次のとおりだった:
//   魔導・星詠み  20,000
//   infinity 防具 15,000,000〜30,000,000（部位別）
// つまり同帯の最終装備に対して【1/1000 以下】。ペデスタルも2〜4個で、
// 星詠み1点がソース結晶(18,000で焼べられる)1〜2個で作れてしまう状態だった。
//
// ■ 方針（ユーザー確定）
//  - コストは「インフィニティ／ヒーローシリーズに寄せる」。
//  - 素材の基幹は【バニラ Ars と同じもの】= マジブルーム繊維 / ソースジェム / ソースジェムブロック。
//  - 賢者・星詠みはそれより重くする。既存のカスタム品（ソース階梯・圧縮素材・特殊ドロップ）を使う。
//
// ■ 部位別の重み
// infinity 防具の 15M/30M/25M/15M（兜/胴/脚/靴）と同じ比 1.0 / 2.0 / 1.6 / 1.0 を踏襲する。
// 素材(ペデスタル)は段ごとに共通で、部位差は source だけで付ける（現行データと同じ流儀）。
//
// ■ 到達点
// 星詠みフルセット = 44,800,000 ソース。infinity フルセット 85,000,000 の約 53%。
// 「最終装備の一歩手前」に収まり、かつソース機関(30,000,000)を組む動機になる水準。
//
// 実行: node tools/config-editor/scripts/retune-mage-ritual-cost.js
// ⚠ 冪等（値を代入するだけで倍率を掛けないので、二度流しても同じ結果になる）。

const fs = require("node:fs");
const path = require("node:path");

const targetPath = path.resolve(__dirname, "..", "..", "..",
  "TrinityForge", "src", "main", "resources", "items", "catalog.yml");

/** 部位 → source の倍率。infinity 防具の 15/30/25/15 と同じ比。 */
const SLOT_WEIGHT = { helmet: 1.0, chestplate: 2.0, leggings: 1.6, boots: 1.0 };

/**
 * 段 → { 兜1点あたりの source, ペデスタル }。
 * ペデスタルは「バニラ Ars の基幹素材」から始めて、上位2段でソース階梯と特殊ドロップを重ねる。
 */
const TIERS = {
  // 見習い(Lv20): 本家 Ars の入門装備と同じ「マジブルーム繊維＋ソースジェム」だけで組める。
  novice: {
    source: 5000,
    pedestals: ["custom:magebloom_fiber x8", "custom:source_gem x4"],
  },
  // 魔術師(Lv40): 同じ素材の量を増やし、ソースジェムブロックが1個要るようになる。
  apprentice: {
    source: 50000,
    pedestals: ["custom:magebloom_fiber x16", "custom:source_gem x8", "custom:source_gem_block x1"],
  },
  // 大魔導士(Lv60): ここから「ソースを貯める設備」が前提になる(ソース結晶=18,000ソース相当)。
  archmage: {
    source: 400000,
    pedestals: ["custom:magebloom_fiber x24", "custom:source_gem_block x4", "custom:source_crystal x1"],
  },
  // 賢者(Lv80): 基幹素材に加えて討伐由来の特殊ドロップと圧縮素材。nuclear(Lv80)と同じ「重い素材」の作り。
  sage: {
    source: 2000000,
    pedestals: [
      "custom:source_gem_block x8",
      "custom:source_crystal x4",
      "custom:dragon_scale x2",
      "custom:echo_shard_2x x2",
    ],
  },
  // 星詠み(Lv100): infinity 防具と同帯。ソース凝縮核(1個=60,000ソースで作り700,000を焼べる)を要求し、
  // 圧縮素材と最上位ドロップを重ねる。infinity の amethyst_block_2x を共有して系列の格を揃える。
  starseer: {
    source: 8000000,
    pedestals: [
      "custom:source_gem_block x16",
      "custom:source_condenser x2",
      "custom:warden_tendril x4",
      "custom:amethyst_block_2x x4",
    ],
  },
};

/** 魔導書2点。装備ではないが同じ Ars 系の到達物なので、段位に合わせて一緒に引き上げる。 */
const SPELL_BOOKS = {
  spell_book_apprentice: {
    source: 100000,
    pedestals: [
      "DIAMOND_BLOCK", "EMERALD_BLOCK", "LAPIS_BLOCK", "REDSTONE_BLOCK",
      "GOLD_BLOCK", "QUARTZ_BLOCK", "BLAZE_ROD",
      "custom:magebloom_fiber x16", "custom:source_gem_block x4",
    ],
  },
  spell_book_archmage: {
    source: 1000000,
    pedestals: [
      "NETHER_STAR", "NETHERITE_BLOCK", "CRYING_OBSIDIAN", "ECHO_SHARD",
      "END_CRYSTAL", "ENDER_EYE", "TOTEM_OF_UNDYING",
      "custom:source_gem_block x8", "custom:source_crystal x2",
    ],
  },
};

/** id からプラン(source とペデスタル)を引く。対象外なら null。 */
function planFor(id) {
  if (SPELL_BOOKS[id]) return SPELL_BOOKS[id];
  const m = /^mage_(?:arcane|weaver|guardian)_(novice|apprentice|archmage|sage|starseer)_(helmet|chestplate|leggings|boots)$/
    .exec(id);
  if (!m) return null;
  const tier = TIERS[m[1]];
  return { source: Math.round(tier.source * SLOT_WEIGHT[m[2]]), pedestals: tier.pedestals };
}

const lines = fs.readFileSync(targetPath, "utf8").split(/\r?\n/);
const out = [];
const touched = [];

let id = null;
let plan = null;
let inRecipe = false;
let skippingPedestals = false;

for (const line of lines) {
  const item = /^ {2}([a-z0-9_]+):\s*$/.exec(line);
  if (item) {
    id = item[1];
    plan = planFor(id);
    inRecipe = false;
    skippingPedestals = false;
    out.push(line);
    continue;
  }
  if (!plan) { out.push(line); continue; }

  if (/^ {4}recipe:\s*$/.test(line)) { inRecipe = true; out.push(line); continue; }
  if (/^ {4}\S/.test(line)) { inRecipe = false; skippingPedestals = false; out.push(line); continue; }
  if (!inRecipe) { out.push(line); continue; }

  // 旧ペデスタル行(- xxx)を読み飛ばし、ヘッダの直後に新しい一覧を書く。
  if (/^ {6}pedestal-items:\s*$/.test(line)) {
    out.push(line);
    for (const entry of plan.pedestals) out.push(`        - ${entry}`);
    skippingPedestals = true;
    continue;
  }
  if (skippingPedestals) {
    if (/^ {8}-\s/.test(line)) continue; // 旧エントリを捨てる
    skippingPedestals = false;
  }
  if (/^ {6}source:\s*\d+\s*$/.test(line)) {
    out.push(`      source: ${plan.source}`);
    touched.push(`${id.padEnd(38)} source=${plan.source}`);
    continue;
  }
  out.push(line);
}

fs.writeFileSync(targetPath, out.join("\n"), "utf8");
console.log(`retuned ${touched.length} ritual recipes`);
for (const t of touched) console.log("  " + t);
