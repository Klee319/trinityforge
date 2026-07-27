"use strict";

// items/material-lists.yml (tf-material-lists) スキーマ検証のテスト。

const { test } = require("node:test");
const assert = require("node:assert");
const { validate } = require("../lib/schema.js");

test("tf-material-lists: 正常なリストはエラーなし", () => {
  const errors = validate("tf-material-lists", {
    lists: {
      planks: { label: "板材", materials: ["OAK_PLANKS", "SPRUCE_PLANKS"] },
      wool: { materials: ["RED_WOOL"] }
    }
  });
  assert.deepStrictEqual(errors, []);
});

test("tf-material-lists: custom:アイテムIDを互換メンバーにできる", () => {
  assert.deepEqual(validate("tf-material-lists", {
    lists: { mixed: { materials: ["IRON_INGOT", "custom:external_core"] } }
  }), []);
});

test("external-items: MaterialとCMDを持つ台帳エントリだけを許可する", () => {
  assert.deepEqual(validate("external-items", {
    items: { external_core: { material: "PAPER", "custom-model-data": 123, "display-name": "外部コア" } }
  }), []);
  assert.ok(validate("external-items", { items: { bad: { material: "PAPER" } } }).length > 0);
});

test("tf-material-lists: 空ルート/lists無しは許容", () => {
  assert.deepStrictEqual(validate("tf-material-lists", null), []);
  assert.deepStrictEqual(validate("tf-material-lists", {}), []);
  assert.deepStrictEqual(validate("tf-material-lists", { lists: null }), []);
});

test("tf-material-lists: materials空/欠落はエラー", () => {
  const errors = validate("tf-material-lists", {
    lists: { empty: { materials: [] }, missing: { label: "x" } }
  });
  assert.strictEqual(errors.length, 2);
  assert.match(errors[0], /lists\.empty\.materials/);
  assert.match(errors[1], /lists\.missing\.materials/);
});

test("tf-material-lists: 不正なリストID・不正なMaterial名はエラー", () => {
  const errors = validate("tf-material-lists", {
    lists: {
      "Bad-ID": { materials: ["OAK_PLANKS"] },
      ok: { materials: ["oak_planks"] }
    }
  });
  assert.strictEqual(errors.length, 2);
  assert.match(errors[0], /lists\.Bad-ID/);
  assert.match(errors[1], /lists\.ok\.materials\[0\]/);
});
