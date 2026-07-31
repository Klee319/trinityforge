"use strict";

// public/js/simulator.js (window.SIMULATOR_LOGIC) の純関数テスト。
// 2026-07-31 (F4 指摘5): 魔法の基礎ダメージが「spellBase × 触媒倍率」の【乗算】でモデル化されており、
// 実装(MagicStatSourcePolicy#effectiveBase = spellBase + attackPower × magical.attack-power-scale の
// 【加算】)と形が違って約400倍ずれていた。調整に使う唯一のツールなので式の形をテストで縛る。
// ブラウザ用 IIFE (window.h 前提) なので drop-table-logic.test.js と同じ手法で window を最小スタブする。

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
global.window.h = () => ({});
require("../public/js/simulator.js");

const { initModel, scaledAttackPower, deriveAttackPower, deriveDefaultDamage } = global.window.SIMULATOR_LOGIC;

function magicModel(overrides) {
  const model = initModel(null);
  model.mode = "magical";
  return Object.assign(model, overrides || {});
}

test("魔法基礎は spellBase + attack-power × 係数(加算)", () => {
  const m = magicModel({ spellBase: 27, catalystAttackPower: 10584 });
  m.c.magAttackPowerScale = 1.0;
  assert.equal(deriveAttackPower(m), 27 + 10584);
});

test("係数0で杖の attack-power は魔法へ一切乗らない", () => {
  const m = magicModel({ spellBase: 27, catalystAttackPower: 10584 });
  m.c.magAttackPowerScale = 0;
  assert.equal(deriveAttackPower(m), 27);
});

test("旧モデル(乗算)には戻っていない: attack-power 0 でも spellBase が消えない", () => {
  // 乗算モデルだと catalystAttackPower(旧 catalystMult)=0 で基礎が0に落ちてしまう。
  const m = magicModel({ spellBase: 27, catalystAttackPower: 0 });
  assert.equal(deriveAttackPower(m), 27);
});

test("係数は Java スキーマと同じ [0,10] にクランプ、負の attack-power は0扱い", () => {
  assert.equal(scaledAttackPower(100, 999), 1000);
  assert.equal(scaledAttackPower(100, -5), 0);
  assert.equal(scaledAttackPower(-100, 1), 0);
  assert.equal(scaledAttackPower(Number.NaN, 1), 0);
});

test("魔法の既定値は combat/damage.yml の magical.attack-power-scale から取る", () => {
  const model = initModel({ fields: { "magical.attack-power-scale": 0.25 } });
  assert.equal(model.c.magAttackPowerScale, 0.25);
  // 未設定なら Java 既定の 1.0。
  assert.equal(initModel(null).c.magAttackPowerScale, 1.0);
});

test("レベル倍率は加算後の基礎に掛かる(実装と同じ順序)", () => {
  const m = magicModel({ spellBase: 27, catalystAttackPower: 10584, combatLevel: 100 });
  m.c.magAttackPowerScale = 1.0;
  m.c.magScale = true;
  m.c.perLevel = 0.01;
  m.c.magCoef = 1.0;
  // (27 + 10584) * (1 + 0.01*100) = 21222 — レビュー指摘に出てくる実測値と一致する。
  assert.equal(deriveDefaultDamage(m), 21222);
});

test("simulator.js に触媒倍率(乗算)モデルの残骸が無い", () => {
  const source = fs.readFileSync(
    path.join(__dirname, "..", "public", "js", "simulator.js"), "utf8");
  assert.ok(!source.includes("catalystMult"),
    "旧 catalystMult(乗算モデル)は撤去済みである必要がある");
  assert.ok(source.includes("magical.attack-power-scale"),
    "新設キーをフォームの既定値として取り込む必要がある");
});
