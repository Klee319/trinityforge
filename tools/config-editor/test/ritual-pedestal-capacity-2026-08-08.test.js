"use strict";

// ---------------------------------------------------------------------------
// 儀式レシピが要求する台座の数は 16 を超えてはいけない (2026-08-08)
//
// ArsPaper の RitualManager#findNearbyPedestals は「コアから PEDESTAL_DISTANCE 離れた
// 正方形リングの外周」だけを走査する。そのリングは **16 マスしかない**ので、
// 17 個以上の台座を要求するレシピは**どう置いてもマッチしない = 永久にクラフト不可**になる。
// しかもエラーも警告も出ず、プレイヤーからは「素材は合っているのに反応しない」に見える。
//
// 実際に 9 件が超えていた:
//   TF catalog.yml   harvest_hoe / herb_hat / leyline_shovel / bedrock_greaves … 各19
//   Ars sourcelinks  volcanic / mycelial / alchemical / vitalic / botanical の _v … 各18
//
// 数え方の注意: `pedestal-items` の行数ではなく **`xN` 記法を展開した合計**が台座の数。
// 行数だけ見ると 3 行なので、行数で検査するテストはこの 9 件を素通りさせる。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const yaml = require("yaml");

// 台座リング (max(|x|,|z|) == PEDESTAL_DISTANCE の外周) のマス数。
const PEDESTAL_RING_SLOTS = 16;

const REPO = path.join(__dirname, "..", "..", "..");

function pedestalQuantity(entry) {
  const m = String(entry).match(/\sx(\d+)\s*$/i);
  return m ? Number(m[1]) : 1;
}

function collectRituals(doc, file) {
  const out = [];
  (function walk(node, p) {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) { node.forEach((v, i) => walk(v, `${p}[${i}]`)); return; }
    const ped = node["pedestal-items"];
    if (Array.isArray(ped)) {
      out.push({
        file, path: p, items: ped.slice(),
        total: ped.reduce((a, v) => a + pedestalQuantity(v), 0)
      });
    }
    for (const [k, v] of Object.entries(node)) walk(v, p ? `${p}.${k}` : k);
  })(doc, "");
  return out;
}

function loadRituals(relPath) {
  const full = path.join(REPO, relPath);
  if (!fs.existsSync(full)) return null; // フォークは .gitignore 除外。クリーンクローンには無い
  return collectRituals(yaml.parse(fs.readFileSync(full, "utf8")), relPath);
}

test("xN 記法を展開して数える (行数で数えると超過を見逃す)", () => {
  assert.equal(pedestalQuantity("WHEAT x16"), 16);
  assert.equal(pedestalQuantity("custom:hard_metal x2"), 2);
  assert.equal(pedestalQuantity("HAY_BLOCK"), 1);
  // 行数 3 でも台座は 19 必要、という取りこぼしの形そのもの
  const rows = ["WHEAT x16", "custom:hard_metal x2", "HAY_BLOCK x1"];
  assert.equal(rows.length, 3);
  assert.equal(rows.reduce((a, v) => a + pedestalQuantity(v), 0), 19);
});

test("出荷 catalog.yml の儀式レシピは台座16枠に収まる", () => {
  const rituals = loadRituals("TrinityForge/src/main/resources/items/catalog.yml");
  assert.ok(rituals && rituals.length > 0, "catalog.yml に儀式レシピが1件も無いのは検査が壊れている証拠");
  const over = rituals.filter((r) => r.total > PEDESTAL_RING_SLOTS);
  assert.deepEqual(over.map((r) => `${r.path}=${r.total}`), [],
    "台座リングは16マスしかないので、17以上を要求するレシピは永久にクラフトできない");
});

test("ArsPaper 側の儀式レシピも台座16枠に収まる (フォークが存在するときだけ)", () => {
  const files = [
    "fork-handoff/arspaper/fork/src/main/resources/sourcelinks.yml",
    "fork-handoff/arspaper/fork/src/main/resources/materials.yml",
    "fork-handoff/arspaper/fork/src/main/resources/threads.yml",
    "fork-handoff/arspaper/fork/src/main/resources/spellbooks.yml",
    "fork-handoff/arspaper/fork/src/main/resources/functional-items.yml",
    "fork-handoff/arspaper/fork/src/main/resources/sourcejars.yml"
  ];
  const found = files.map(loadRituals).filter(Boolean);
  if (found.length === 0) return; // フォーク未取得の環境では検査対象が無い
  const over = found.flat().filter((r) => r.total > PEDESTAL_RING_SLOTS);
  assert.deepEqual(over.map((r) => `${r.file}:${r.path}=${r.total}`), []);
});
