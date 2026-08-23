"use strict";

// public/js/functional-items.js の純関数(Node/ブラウザ両対応)のテスト。
// 2026-07-25 functional-items.yml 単一ファイル統合後のスキーマが対象:
// display-name / lore / enchant-glow / material(3件のみ) / recipe の5キー。
// 内部ID(items.<id>のキー名そのもの)とCustomModelDataはプログラム側固定のため
// このファイルはそもそも編集APIを持たない(旧: 静的ミラー+catalog/items companion保存は
// 単一ファイル化により全廃)。

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  FUNCTIONAL_ITEM_IDS,
  MATERIAL_EDITABLE_IDS,
  isMaterialEditable,
  normalizeFunctionalItemsData,
  serializeFunctionalItemsData
} = require("../public/js/functional-items.js");

test("FUNCTIONAL_ITEM_IDS: 8アイテムちょうど揃っている", () => {
  const ids = FUNCTIONAL_ITEM_IDS.slice().sort();
  assert.deepEqual(ids, [
    "dominion_wand", "infinity_source_core", "pedestal", "ritual_core", "scribing_table",
    "source_berry", "teleport_compass", "waystone"
  ].sort());
});

test("MATERIAL_EDITABLE_IDS: 保持アイテム3種のみ(ブロック系5種は含まない)", () => {
  const ids = MATERIAL_EDITABLE_IDS.slice().sort();
  assert.deepEqual(ids, ["dominion_wand", "source_berry", "teleport_compass"].sort());
  for (const blockId of ["pedestal", "ritual_core", "scribing_table", "waystone",
                         "infinity_source_core"]) {
    assert.ok(!MATERIAL_EDITABLE_IDS.includes(blockId), `${blockId} は material 編集不可のはず`);
  }
});

test("isMaterialEditable: 保持アイテムはtrue、ブロック系はfalse", () => {
  assert.equal(isMaterialEditable("dominion_wand"), true);
  assert.equal(isMaterialEditable("teleport_compass"), true);
  assert.equal(isMaterialEditable("source_berry"), true);
  assert.equal(isMaterialEditable("pedestal"), false);
  assert.equal(isMaterialEditable("ritual_core"), false);
  assert.equal(isMaterialEditable("scribing_table"), false);
  assert.equal(isMaterialEditable("waystone"), false);
  assert.equal(isMaterialEditable("unknown_id"), false);
});

test("normalizeFunctionalItemsData: 欠けている正典idを空オブジェクトで補完する", () => {
  const src = { items: { dominion_wand: { "display-name": "ワンド" } } };
  const out = normalizeFunctionalItemsData(src);
  for (const id of FUNCTIONAL_ITEM_IDS) {
    assert.ok(Object.prototype.hasOwnProperty.call(out.items, id), `${id} が補完されていない`);
  }
  assert.deepEqual(out.items.dominion_wand, { "display-name": "ワンド" });
  // イミュータブル: 元データは変更されない
  assert.equal(Object.prototype.hasOwnProperty.call(src.items, "pedestal"), false);
});

test("normalizeFunctionalItemsData: 既存エントリの中身は変更しない", () => {
  const src = { items: { source_berry: { material: "GLOW_BERRIES", recipe: { method: "inventory" } } } };
  const out = normalizeFunctionalItemsData(src);
  assert.deepEqual(out.items.source_berry, { material: "GLOW_BERRIES", recipe: { method: "inventory" } });
});

test("normalizeFunctionalItemsData: 未知のidは温存する(手編集データを壊さない)", () => {
  const src = { items: { some_future_item: { "display-name": "未知" } } };
  const out = normalizeFunctionalItemsData(src);
  assert.deepEqual(out.items.some_future_item, { "display-name": "未知" });
});

test("serializeFunctionalItemsData: 空のlore配列はキーごと削除する", () => {
  const src = { items: { dominion_wand: { "display-name": "ワンド", lore: [] } } };
  const out = serializeFunctionalItemsData(src);
  assert.equal(Object.prototype.hasOwnProperty.call(out.items.dominion_wand, "lore"), false);
});

test("serializeFunctionalItemsData: 非空のlore配列は保持する", () => {
  const src = { items: { dominion_wand: { lore: ["行1", "行2"] } } };
  const out = serializeFunctionalItemsData(src);
  assert.deepEqual(out.items.dominion_wand.lore, ["行1", "行2"]);
});

test("serializeFunctionalItemsData: イミュータブル(元データは変更されない)", () => {
  const src = { items: { dominion_wand: { lore: [] } } };
  serializeFunctionalItemsData(src);
  assert.deepEqual(src.items.dominion_wand.lore, []);
});
