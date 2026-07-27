"use strict";

// respack-view.js の純関数コア (collectCmdTargets/applyCmdResults) の単体テスト。
// 「全アイテムCMD一括採番＆保存」ボタンが catalog.yml/materials.yml を横断して
// CMD未設定アイテムをどう収集し、割当結果をどう書き戻すかを検証する。

const { test } = require("node:test");
const assert = require("node:assert");
const CORE = require("../public/js/respack-view.js");

test("collectCmdTargets: catalogはcustom-model-dataが非整数なら対象、0は設定済み扱い", () => {
  const catalog = {
    items: {
      sword: { material: "IRON_SWORD" }, // 未設定
      hammer: { material: "STICK", "custom-model-data": 0 }, // 0は設定済み扱い(既存forms.js踏襲)
      nomat: { "custom-model-data": null } // materialなしは対象外
    }
  };
  const targets = CORE.collectCmdTargets(catalog, {});
  assert.deepStrictEqual(targets, [{ file: "catalog", id: "sword", material: "IRON_SWORD" }]);
});

test("collectCmdTargets: materialsはcustom_model_dataが0以下なら未設定扱い(既存作成時の既定値0)", () => {
  const materials = {
    materials: {
      core_a: { base_material: "BLAZE_ROD", custom_model_data: 0 }, // 未設定扱い
      core_b: { base_material: "STICK", custom_model_data: 12 }, // 設定済み
      core_c: { base_material: "AMETHYST_SHARD" }, // custom_model_data省略も未設定扱い
      nobase: { custom_model_data: 0 } // base_materialなしは対象外
    }
  };
  const targets = CORE.collectCmdTargets({}, materials);
  assert.deepStrictEqual(targets, [
    { file: "materials", id: "core_a", material: "BLAZE_ROD" },
    { file: "materials", id: "core_c", material: "AMETHYST_SHARD" }
  ]);
});

test("collectCmdTargets: catalog/materials両方を横断して集約する", () => {
  const catalog = { items: { sword: { material: "IRON_SWORD" } } };
  const materials = { materials: { core_a: { base_material: "BLAZE_ROD", custom_model_data: 0 } } };
  const targets = CORE.collectCmdTargets(catalog, materials);
  assert.deepStrictEqual(targets, [
    { file: "catalog", id: "sword", material: "IRON_SWORD" },
    { file: "materials", id: "core_a", material: "BLAZE_ROD" }
  ]);
});

test("collectCmdTargets: items/materialsが無い/不正な形でも例外を投げない", () => {
  assert.deepStrictEqual(CORE.collectCmdTargets({}, {}), []);
  assert.deepStrictEqual(CORE.collectCmdTargets(null, null), []);
  assert.deepStrictEqual(CORE.collectCmdTargets({ items: null }, { materials: [] }), []);
});

test("applyCmdResults: targetsと同順のresultsをcatalog/materialsそれぞれのフィールドへ書き戻す", () => {
  const catalog = { items: { sword: { material: "IRON_SWORD" } } };
  const materials = { materials: { core_a: { base_material: "BLAZE_ROD", custom_model_data: 0 } } };
  const targets = [
    { file: "catalog", id: "sword", material: "IRON_SWORD" },
    { file: "materials", id: "core_a", material: "BLAZE_ROD" }
  ];
  const results = [
    { id: "sword", material: "IRON_SWORD", cmd: 101 },
    { id: "core_a", material: "BLAZE_ROD", cmd: 102 }
  ];
  CORE.applyCmdResults(catalog, materials, targets, results);
  assert.strictEqual(catalog.items.sword["custom-model-data"], 101);
  assert.strictEqual(materials.materials.core_a.custom_model_data, 102);
});

test("applyCmdResults: 対応するエントリが存在しない/resultが欠けている場合は無視する", () => {
  const catalog = { items: {} };
  const materials = { materials: {} };
  const targets = [{ file: "catalog", id: "missing", material: "STICK" }];
  assert.doesNotThrow(() => CORE.applyCmdResults(catalog, materials, targets, []));
  assert.doesNotThrow(() => CORE.applyCmdResults(catalog, materials, targets, [null]));
});

test("collectCmdTargets → applyCmdResults の往復: 一括採番後は再収集すると対象0件になる", () => {
  const catalog = { items: { sword: { material: "IRON_SWORD" }, bow: { material: "STICK" } } };
  const materials = { materials: { core_a: { base_material: "BLAZE_ROD", custom_model_data: 0 } } };
  const targets = CORE.collectCmdTargets(catalog, materials);
  assert.strictEqual(targets.length, 3);
  const results = targets.map((t, i) => ({ id: t.id, material: t.material, cmd: 200 + i }));
  CORE.applyCmdResults(catalog, materials, targets, results);
  assert.deepStrictEqual(CORE.collectCmdTargets(catalog, materials), []);
});
