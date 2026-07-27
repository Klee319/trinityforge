"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { findOrphanAllocations, removeAllocationFromData } = require("../lib/cmd-removal");

test("catalog: 一致する items エントリを不変に削除する", () => {
  const data = { items: {
    fire_sword: { material: "IRON_SWORD", "custom-model-data": 12 },
    ice_sword: { material: "IRON_SWORD", "custom-model-data": 13 }
  } };
  const r = removeAllocationFromData("catalog", data, "IRON_SWORD", 12);
  assert.deepEqual(r.removed, [{ file: "catalog", id: "fire_sword" }]);
  assert.deepEqual(Object.keys(r.data.items), ["ice_sword"]);
  // 元データは破壊されない
  assert.ok(data.items.fire_sword);
  assert.notEqual(r.data, data);
});

test("catalog: 不一致(cmc違い)は削除せず元参照を返す", () => {
  const data = { items: { fire_sword: { material: "IRON_SWORD", "custom-model-data": 12 } } };
  const r = removeAllocationFromData("catalog", data, "IRON_SWORD", 99);
  assert.deepEqual(r.removed, []);
  assert.equal(r.data, data);
});

test("item-stats: MATERIAL#CMD キーだけを削除する", () => {
  const data = { items: {
    "IRON_SWORD#12": { "flat-bonus-damage": 5 },
    "IRON_SWORD": { "flat-bonus-damage": 1 },
    "IRON_SWORD#13": { "flat-bonus-damage": 6 }
  } };
  const r = removeAllocationFromData("item-stats", data, "IRON_SWORD", 12);
  assert.deepEqual(r.removed, [{ file: "item-stats", id: "IRON_SWORD#12" }]);
  assert.deepEqual(Object.keys(r.data.items).sort(), ["IRON_SWORD", "IRON_SWORD#13"]);
});

test("materials: base_material / custom_model_data 一致で削除", () => {
  const data = { materials: {
    ruby: { base_material: "DIAMOND", custom_model_data: 30 },
    sapphire: { base_material: "DIAMOND", custom_model_data: 31 }
  } };
  const r = removeAllocationFromData("materials", data, "DIAMOND", 30);
  assert.deepEqual(r.removed, [{ file: "materials", id: "ruby" }]);
  assert.deepEqual(Object.keys(r.data.materials), ["sapphire"]);
});

test("spellbooks: spell-books[] (BOOK固定) と catalysts{} を削除", () => {
  const data = {
    "spell-books": [
      { id: "fire_tome", "custom-model-data": 40 },
      { id: "ice_tome", "custom-model-data": 41 }
    ],
    catalysts: {
      fire_cat: { material: "BLAZE_ROD", "custom-model-data": 40 }
    }
  };
  const book = removeAllocationFromData("spellbooks", data, "BOOK", 40);
  assert.deepEqual(book.removed, [{ file: "spellbooks", id: "fire_tome" }]);
  assert.deepEqual(book.data["spell-books"].map((b) => b.id), ["ice_tome"]);
  // catalysts は BOOK では触らない
  assert.ok(book.data.catalysts.fire_cat);

  const cat = removeAllocationFromData("spellbooks", data, "BLAZE_ROD", 40);
  assert.deepEqual(cat.removed, [{ file: "spellbooks:catalysts", id: "fire_cat" }]);
  assert.deepEqual(Object.keys(cat.data.catalysts), []);
});

test("external-items / sourcejars / sourcelinks: material+cmc一致で削除", () => {
  const ext = removeAllocationFromData("external-items",
    { items: { a: { material: "STICK", "custom-model-data": 5 } } }, "STICK", 5);
  assert.deepEqual(ext.removed, [{ file: "external-items", id: "a" }]);

  const jar = removeAllocationFromData("sourcejars",
    { jars: { j: { material: "GLASS", "custom-model-data": 7 } } }, "GLASS", 7);
  assert.deepEqual(jar.removed, [{ file: "sourcejars", id: "j" }]);

  const link = removeAllocationFromData("sourcelinks",
    { items: { l: { material: "PAPER", "custom-model-data": 9 } } }, "PAPER", 9);
  assert.deepEqual(link.removed, [{ file: "sourcelinks", id: "l" }]);
});

test("findOrphanAllocations: item-stats だけが参照する行を孤児として返す", () => {
  const usage = [
    { material: "IRON_SWORD", cmd: 12, sources: [{ file: "item-stats", id: "IRON_SWORD#12" }] }, // 孤児
    { material: "IRON_SWORD", cmd: 13, sources: [
      { file: "catalog", id: "ice_sword" }, { file: "item-stats", id: "IRON_SWORD#13" }
    ] }, // 実体あり → 非孤児
    { material: "BOOK", cmd: 40, sources: [{ file: "spellbooks", id: "fire_tome" }] }, // 実体あり
    { material: "BLAZE_ROD", cmd: 41, sources: [] } // sources空は対象外
  ];
  const orphans = findOrphanAllocations(usage);
  assert.deepEqual(orphans.map((o) => `${o.material}#${o.cmd}`), ["IRON_SWORD#12"]);
});
