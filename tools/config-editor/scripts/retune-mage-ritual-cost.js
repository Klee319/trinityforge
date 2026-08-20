"use strict";

// items/catalog.yml の【Ars向け装備（魔導防具3シリーズ×5段＝60点／魔導書2点）】の儀式コストを
// 組み直す (2026-08-19 / W-120 → W-121 で素材を全面改訂)。
//
// ■ なぜ要るか（実測）
// 帯(use-level-requirement)は 見習いLv20 / 魔術師Lv40 / 大魔導士Lv60 / 賢者Lv80 / 星詠みLv100 で、
// **星詠みは infinity 防具とまったく同じ Lv100 帯**。ところがソース要求量は次のとおりだった:
//   魔導・星詠み  20,000
//   infinity 防具 15,000,000〜30,000,000（部位別）
// つまり同帯の最終装備に対して【1/1000 以下】。ペデスタルも2〜4個で、
// 星詠み1点がソース結晶(18,000で焼べられる)1〜2個で作れてしまう状態だった。
//
// ■【台座の物理上限 = 16】ここを外すと組めないレシピになる（W-120 の実害）
// ArsPaper の RitualManager#findNearbyPedestals は「コアから XZ で max(|x|,|z|)==2 のリング」を
// 走査する。1段あたり 16 マス（5x5 の外周）で、Y は ±1 まで見るので理論上は 48 台まで置ける。
// そして **台座1台に置けるのは1個** で（RitualManager の consumePedestalItems / Pedestal は
// スタック数を見ない）、RitualRecipe#matches は
//   pedestalIngredients.size() != pedestalItems.size() -> false
// と【台数の完全一致】を要求する。つまり yml の "x16" は「16スタック」ではなく **16台** の意味。
// W-120 で書いた magebloom_fiber x24 は 24 台を要求し、上下段に台座を積まないと成立しない
// （見た目にもリング状にならず、実質「組めないレシピ」だった）。
// **このスクリプトは1レシピあたりの台座合計を 16 以下に必ず収める**（下の assert で強制）。
// 重さはソース量と素材のレアリティで出す。台数で出さない。
//
// ■ 素材の方針（ユーザー確定 / W-121）
//  - 「バニラ Ars の素材と同じになるように」。本家 Ars の儀式はソースジェム系だけでなく
//    ネザースター・残響の欠片・ネザライトインゴット・エンダーアイ・不死のトーテムなども使う
//    （このフォークの functional-items.yml / items.yml の既存儀式がまさにその作り）。
//    マジブルーム繊維とソースジェムだけの単調な構成をやめ、段が上がるごとに
//    バニラの上位素材を1〜2種ずつ足していく。
//  - 賢者・星詠みはそれより重く。既存のカスタム品（ソース階梯・圧縮素材・特殊ドロップ）を使う。
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

/** 台座リング1段の物理上限。これを超えるレシピは組めない（ファイル冒頭の解説参照）。 */
const MAX_PEDESTALS = 16;

/** 部位 → source の倍率。infinity 防具の 15/30/25/15 と同じ比。 */
const SLOT_WEIGHT = { helmet: 1.0, chestplate: 2.0, leggings: 1.6, boots: 1.0 };

/**
 * 段 → { 兜1点あたりの source, ペデスタル }。
 * ペデスタルは「バニラ Ars と同じ顔ぶれ」から始めて、段ごとにバニラ上位素材とソース階梯を重ねる。
 */
const TIERS = {
  // 見習い(Lv20) 8台: 本家 Ars の入門装備と同じ「マジブルーム繊維＋ソースジェム」に鉱物ブロックを添える。
  novice: {
    source: 5000,
    pedestals: [
      "custom:magebloom_fiber x4",
      "custom:source_gem x2",
      "GOLD_BLOCK",
      "LAPIS_BLOCK",
    ],
  },
  // 魔術師(Lv40) 11台: ソースジェムブロックが1個要るようになり、ネザー素材(ブレイズロッド)が入る。
  apprentice: {
    source: 50000,
    pedestals: [
      "custom:magebloom_fiber x4",
      "custom:source_gem x3",
      "custom:source_gem_block",
      "BLAZE_ROD x2",
      "AMETHYST_BLOCK",
    ],
  },
  // 大魔導士(Lv60) 12台: 「ソースを貯める設備」が前提になり(ソース結晶=18,000ソース相当)、
  //   古代都市(残響の欠片)とエンド(エンダーアイ)の素材が要求される。
  archmage: {
    source: 400000,
    pedestals: [
      "custom:magebloom_fiber x4",
      "custom:source_gem_block x2",
      "custom:source_crystal",
      "ECHO_SHARD x2",
      "ENDER_EYE x2",
      "DIAMOND_BLOCK",
    ],
  },
  // 賢者(Lv80) 13台: ネザースターとネザライトが入り、討伐由来の特殊ドロップと圧縮素材が乗る。
  sage: {
    source: 2000000,
    pedestals: [
      "custom:source_gem_block x4",
      "custom:source_crystal x2",
      "custom:dragon_scale x2",
      "custom:echo_shard_2x",
      "NETHERITE_INGOT x2",
      "NETHER_STAR",
      "CRYING_OBSIDIAN",
    ],
  },
  // 星詠み(Lv100) 15台: infinity 防具と同帯。ソース凝縮核(1個=60,000ソースで作り700,000を焼べる)と
  //   ネザースター2個・不死のトーテム2個。infinity の amethyst_block_2x を共有して系列の格を揃える。
  starseer: {
    source: 8000000,
    pedestals: [
      "custom:source_gem_block x4",
      "custom:source_condenser",
      "custom:warden_tendril x2",
      "custom:amethyst_block_2x x2",
      "NETHER_STAR x2",
      "NETHERITE_BLOCK",
      "TOTEM_OF_UNDYING x2",
      "END_CRYSTAL",
    ],
  },
};

/** 魔導書2点。装備ではないが同じ Ars 系の到達物なので、段位に合わせて一緒に引き上げる。 */
const SPELL_BOOKS = {
  // 13台。既存の「各種ブロックを並べる」構成を残したまま Ars 素材を足す。
  spell_book_apprentice: {
    source: 100000,
    pedestals: [
      "DIAMOND_BLOCK", "EMERALD_BLOCK", "LAPIS_BLOCK", "REDSTONE_BLOCK",
      "GOLD_BLOCK", "QUARTZ_BLOCK", "BLAZE_ROD",
      "custom:magebloom_fiber x4", "custom:source_gem_block x2",
    ],
  },
  // 13台。
  spell_book_archmage: {
    source: 1000000,
    pedestals: [
      "NETHER_STAR", "NETHERITE_BLOCK", "CRYING_OBSIDIAN", "ECHO_SHARD",
      "END_CRYSTAL", "ENDER_EYE", "TOTEM_OF_UNDYING",
      "custom:source_gem_block x4", "custom:source_crystal x2",
    ],
  },
};

/** "TOKEN xN" の N（省略時 1）。yml の記法と ArsPaper の parseIngredientCount に合わせる。 */
function countOf(entry) {
  const m = /\sx(\d+)$/.exec(entry);
  return m ? parseInt(m[1], 10) : 1;
}

function totalPedestals(list) {
  return list.reduce((sum, entry) => sum + countOf(entry), 0);
}

// 台座上限は「守れているつもり」で壊れるのが一番痛いので、走らせる前に必ず落とす。
for (const [name, plan] of Object.entries({ ...TIERS, ...SPELL_BOOKS })) {
  const n = totalPedestals(plan.pedestals);
  if (n > MAX_PEDESTALS) {
    throw new Error(`${name}: 台座 ${n} 台はリング1段(${MAX_PEDESTALS}台)に収まらない`);
  }
}

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
    touched.push(`${id.padEnd(38)} source=${String(plan.source).padStart(9)} 台座=${totalPedestals(plan.pedestals)}`);
    continue;
  }
  out.push(line);
}

fs.writeFileSync(targetPath, out.join("\n"), "utf8");
console.log(`retuned ${touched.length} ritual recipes`);
for (const t of touched) console.log("  " + t);
