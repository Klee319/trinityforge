"use strict";

// catalog.yml レシピの method="inventory"(インベントリ内2×2クラフト) と
// reversible(解凍を許可)フラグのスキーマ検証テスト。

const { test } = require("node:test");
const assert = require("node:assert");
const { validate } = require("../lib/schema.js");

function catalogDoc(recipe) {
  return {
    items: {
      sample: {
        material: "STICK",
        recipe
      }
    }
  };
}

test("inventory: 2×2の shaped レシピはエラーなし", () => {
  const errors = validate("catalog", catalogDoc({
    method: "inventory",
    type: "shaped",
    shape: ["AB", "BA"],
    ingredients: { A: "STICK", B: "STICK" }
  }));
  assert.deepStrictEqual(errors, []);
});

test("inventory: shape が3行目まであると上限超過エラー", () => {
  const errors = validate("catalog", catalogDoc({
    method: "inventory",
    type: "shaped",
    shape: ["AB", "BA", "AB"],
    ingredients: { A: "STICK", B: "STICK" }
  }));
  assert.ok(errors.some((e) => /items\.sample\.recipe\.shape: 最大2行までです/.test(e)), JSON.stringify(errors));
});

test("inventory: shape の各行が3文字目まであると上限超過エラー", () => {
  const errors = validate("catalog", catalogDoc({
    method: "inventory",
    type: "shaped",
    shape: ["ABC", "BA"],
    ingredients: { A: "STICK", B: "STICK", C: "STICK" }
  }));
  assert.ok(errors.some((e) => /items\.sample\.recipe\.shape\[0\]: 各行は2文字以内である必要があります/.test(e)), JSON.stringify(errors));
});

test("inventory: shapeless ingredients は最大4個まで", () => {
  const ok = validate("catalog", catalogDoc({
    method: "inventory",
    type: "shapeless",
    ingredients: ["STICK", "STICK", "STICK", "STICK"]
  }));
  assert.deepStrictEqual(ok, []);
  const errors = validate("catalog", catalogDoc({
    method: "inventory",
    type: "shapeless",
    ingredients: ["STICK", "STICK", "STICK", "STICK", "STICK"]
  }));
  assert.ok(errors.some((e) => /items\.sample\.recipe\.ingredients: shapeless は最大4個までです/.test(e)), JSON.stringify(errors));
});

test("workbench: shapeless ingredients は従来通り最大9個まで (回帰確認)", () => {
  const nine = Array(9).fill("STICK");
  assert.deepStrictEqual(validate("catalog", catalogDoc({ method: "workbench", type: "shapeless", ingredients: nine })), []);
  const ten = Array(10).fill("STICK");
  const errors = validate("catalog", catalogDoc({ method: "workbench", type: "shapeless", ingredients: ten }));
  assert.ok(errors.some((e) => /shapeless は最大9個までです/.test(e)), JSON.stringify(errors));
});

test("reversible: workbench + 素材全同一(shaped)はエラーなし", () => {
  const errors = validate("catalog", catalogDoc({
    method: "workbench",
    type: "shaped",
    shape: ["A A", " A "],
    ingredients: { A: "OAK_LOG" },
    reversible: true
  }));
  assert.deepStrictEqual(errors, []);
});

test("reversible: inventory + 素材全同一(shapeless)はエラーなし", () => {
  const errors = validate("catalog", catalogDoc({
    method: "inventory",
    type: "shapeless",
    ingredients: ["OAK_LOG", "OAK_LOG"],
    reversible: true
  }));
  assert.deepStrictEqual(errors, []);
});

test("reversible: 素材が異なる場合はエラー", () => {
  const errors = validate("catalog", catalogDoc({
    method: "workbench",
    type: "shaped",
    shape: ["AB", "  "],
    ingredients: { A: "OAK_LOG", B: "STICK" },
    reversible: true
  }));
  assert.ok(errors.some((e) => /items\.sample\.recipe\.reversible: 素材が全て同一のレシピにのみ設定できます/.test(e)), JSON.stringify(errors));
});

test("reversible: method が ritual/combine/netherite だとエラー", () => {
  const errRitual = validate("catalog", catalogDoc({
    method: "ritual",
    "core-item": "DIAMOND",
    "pedestal-items": [],
    source: 0,
    reversible: true
  }));
  assert.ok(errRitual.some((e) => /items\.sample\.recipe\.reversible: workbench または inventory のレシピにのみ設定できます/.test(e)), JSON.stringify(errRitual));

  const errCombine = validate("catalog", catalogDoc({
    method: "combine",
    "source-item": "a",
    "addition-item": "b",
    reversible: true
  }));
  assert.ok(errCombine.some((e) => /reversible: workbench または inventory のレシピにのみ設定できます/.test(e)), JSON.stringify(errCombine));
});

test("reversible: 真偽値以外はエラー", () => {
  const errors = validate("catalog", catalogDoc({
    method: "workbench",
    type: "shaped",
    shape: ["A", ""],
    ingredients: { A: "OAK_LOG" },
    reversible: "yes"
  }));
  assert.ok(errors.some((e) => /items\.sample\.recipe\.reversible: true\/false である必要があります/.test(e)), JSON.stringify(errors));
});

test("recipe.method に inventory を指定してもmethod自体はエラーにならない", () => {
  const errors = validate("catalog", catalogDoc({ method: "inventory", type: "shapeless", ingredients: ["STICK"] }));
  assert.deepStrictEqual(errors, []);
});
