"use strict";

// 敵対的レビュー指摘6 (2026-08-02): items/catalog.yml の draft は真偽値以外を書けてしまうと
// Bukkit の ConfigurationSection#getBoolean が非boolean値を false(=出荷扱い)と読むため、
// 運用者が「準備中」のつもりで書いた `draft: "yes"` / `draft: 1` が実際には配線されてしまう。
// validateCatalog がこれを保存時に弾くことを固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema.js");

function catalogDoc(draftValue) {
  return {
    items: {
      sample_item: {
        material: "DIAMOND",
        draft: draftValue
      }
    }
  };
}

test("draft: true はエラーなし", () => {
  assert.deepStrictEqual(validate("catalog", catalogDoc(true)), []);
});

test("draft: false はエラーなし", () => {
  assert.deepStrictEqual(validate("catalog", catalogDoc(false)), []);
});

test("draft 未指定はエラーなし", () => {
  const errors = validate("catalog", {
    items: { sample_item: { material: "DIAMOND" } }
  });
  assert.deepStrictEqual(errors, []);
});

test("draft: \"yes\" (文字列) はエラー", () => {
  const errors = validate("catalog", catalogDoc("yes"));
  assert.ok(errors.some((e) => /items\.sample_item\.draft: 真偽値/.test(e)), JSON.stringify(errors));
});

test("draft: 1 (数値) はエラー", () => {
  const errors = validate("catalog", catalogDoc(1));
  assert.ok(errors.some((e) => /items\.sample_item\.draft: 真偽値/.test(e)), JSON.stringify(errors));
});
