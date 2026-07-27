"use strict";

// public/js/tf-crafting-features.js の純関数(Node/ブラウザ両対応)のテスト。
// オーバーエンチャ「任意ID方式」(2026-07-23 editor改修) の一意性・妥当性検証が対象。

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  uniqueKey,
  renameKey,
  isValidOverEnchantId,
  checkOverEnchantIdAvailable
} = require("../public/js/tf-crafting-features.js");

test("uniqueKey: 未使用のbaseはそのまま返す", () => {
  assert.equal(uniqueKey({}, "over-enchant-1"), "over-enchant-1");
});

test("uniqueKey: 衝突したら連番を振る", () => {
  const map = { "over-enchant-1": {}, "over-enchant-1_1": {} };
  assert.equal(uniqueKey(map, "over-enchant-1"), "over-enchant-1_2");
});

test("renameKey: キー順を保ったままキー名を差し替える", () => {
  const map = { a: 1, b: 2, c: 3 };
  renameKey(map, "b", "renamed");
  assert.deepEqual(Object.keys(map), ["a", "renamed", "c"]);
  assert.equal(map.renamed, 2);
});

test("isValidOverEnchantId: 半角英数字・ハイフン・アンダースコアのみ許可", () => {
  assert.equal(isValidOverEnchantId("over-enchant_1"), true);
  assert.equal(isValidOverEnchantId("Over-Enchant-2"), true);
  assert.equal(isValidOverEnchantId(""), false);
  assert.equal(isValidOverEnchantId("   "), false);
  assert.equal(isValidOverEnchantId("不正id"), false);
  assert.equal(isValidOverEnchantId("has space"), false);
  assert.equal(isValidOverEnchantId(null), false);
  assert.equal(isValidOverEnchantId(undefined), false);
});

test("checkOverEnchantIdAvailable: 空文字はエラー", () => {
  const err = checkOverEnchantIdAvailable({}, "over-enchant-1", "   ");
  assert.equal(err, "IDを入力してください");
});

test("checkOverEnchantIdAvailable: 不正文字はエラー", () => {
  const err = checkOverEnchantIdAvailable({}, "over-enchant-1", "不正 id");
  assert.match(err, /半角英数字/);
});

test("checkOverEnchantIdAvailable: 他プロファイルと重複していればエラー", () => {
  const map = { "over-enchant-1": {}, "over-enchant-2": {} };
  const err = checkOverEnchantIdAvailable(map, "over-enchant-1", "over-enchant-2");
  assert.equal(err, "同じIDが既にあります");
});

test("checkOverEnchantIdAvailable: 自分自身と同じIDは許可 (実質rename無し)", () => {
  const map = { "over-enchant-1": {} };
  const err = checkOverEnchantIdAvailable(map, "over-enchant-1", "over-enchant-1");
  assert.equal(err, null);
});

test("checkOverEnchantIdAvailable: 妥当かつ未使用のIDはOK", () => {
  const map = { "over-enchant-1": {} };
  const err = checkOverEnchantIdAvailable(map, "over-enchant-1", "custom-profile-x");
  assert.equal(err, null);
});
