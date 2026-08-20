"use strict";

// armor-set-buffs 全面移行 §1: skilltree/*.yml の set-buffs スキーマ用editor UI(tf-skilltree.js)。
// 既存の skilltree-mainhand-multipliers.test.js と同型のソーステキスト検証(DOMハーネス無し)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const source = fs.readFileSync(
  path.join(__dirname, "..", "public", "js", "tf-skilltree.js"),
  "utf8"
);

test("set-buffs is scoped to light_armor / heavy_armor only", () => {
  assert.match(source, /SET_BUFF_SKILLS = new Set\(\["light_armor", "heavy_armor"\]\)/);
  assert.match(source, /const supportsSetBuffs = SET_BUFF_SKILLS\.has\(/);
});

test("set-buffs section is wired into both node and prestige rendering", () => {
  assert.match(source, /if \(supportsSetBuffs\) pBody\.appendChild\(setBuffsSection\(P\)\)/);
  assert.match(source, /if \(supportsSetBuffs\) bodyChildren\.push\(setBuffsSection\(node\)\)/);
});

test("set-buffs renders exactly the 3 and 4 piece tiers, no multiplier mode", () => {
  assert.match(source, /for \(const tier of \[3, 4\]\)/);
  // set-multipliers is explicitly out of scope (decisions already made §1).
  assert.doesNotMatch(source, /set-multipliers/);
});

test("legacy native->buffs migration retargets setamount to armor-set-bonus, drops the dead " +
  "setdodgechance/setknockbackresistance mappings", () => {
  assert.match(source, /lightarmor_setamount_add: "armor-set-bonus"/);
  assert.match(source, /heavyarmor_setamount_add: "armor-set-bonus"/);
  assert.doesNotMatch(source, /lightarmor_setdodgechance_add:/);
  assert.doesNotMatch(source, /heavyarmor_setknockbackresistance_add:/);
});
