"use strict";

// タスク2 (2026-07-26) の回帰テスト: public/js/tf-skilltree.js の
// window.TIER_SELECT_LOGIC.buildTierSelectOptions (DOM を持たない純関数)。
//
// スキルツリーの「機能解放」で feature:<id>(param="scale") の tier 値を自由入力から
// セレクトメニューへ変える際の候補生成ロジック。既存値が候補(定義済みtier)に無くても
// 黙って消さず、isKnownValue=false として呼び出し側が警告表示できるようにする。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

global.window = global.window || {};
if (!global.window.h) global.window.h = function h() { return {}; };

const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "tf-skilltree.js"), "utf8");
// eslint-disable-next-line no-new-func
new Function("window", src)(global.window);

const { buildTierSelectOptions } = global.window.TIER_SELECT_LOGIC;

test("空欄(未設定値)は常に候補の先頭にあり、既知の値として扱われる", () => {
  const result = buildTierSelectOptions([1, 2, 3], undefined);
  assert.equal(result.options[0].value, "");
  assert.equal(result.currentValue, "");
  assert.equal(result.isKnownValue, true);
});

test("定義済みtierは昇順・重複除去でオプション化される", () => {
  const result = buildTierSelectOptions([3, 1, 2, 2], 2);
  const values = result.options.map((o) => o.value);
  assert.deepEqual(values, ["", "1", "2", "3"]);
  assert.equal(result.isKnownValue, true);
});

test("現在値が定義済みtierに無い場合はisKnownValue=falseになる(黙って消さない)", () => {
  const result = buildTierSelectOptions([1, 2], 5);
  assert.equal(result.currentValue, "5");
  assert.equal(result.isKnownValue, false);
  // 5 自体は options リストには含まれない(呼び出し側が警告付きで別途 unshift する契約)。
  assert.ok(!result.options.some((o) => o.value === "5"));
});

test("definedTiers が空/非配列でも例外を投げず「空欄のみ」の候補になる", () => {
  assert.deepEqual(buildTierSelectOptions([], null).options.map((o) => o.value), [""]);
  assert.deepEqual(buildTierSelectOptions(null, null).options.map((o) => o.value), [""]);
  assert.deepEqual(buildTierSelectOptions(undefined, null).options.map((o) => o.value), [""]);
});

test("0以下・非整数のtier番号は無視する", () => {
  const result = buildTierSelectOptions([0, -1, 1.5, 2], 2);
  assert.deepEqual(result.options.map((o) => o.value), ["", "2"]);
});
