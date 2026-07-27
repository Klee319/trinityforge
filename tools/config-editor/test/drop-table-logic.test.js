"use strict";

// public/js/tf-lifestyle-forms.js (window.DROP_TABLE_LOGIC) の純関数群の単体テスト。
// 設計書 2026-07-23-stat-gate-overhaul.md §4 準拠のドロップテーブル整形ロジック。
// ブラウザ用 IIFE (window.h 前提) なので、merge.test.js と同じ手法で window.h を最小スタブして読み込む。
// 実際のDOM構築(dropTableEditor)はこのファイルではテストしない(ブラウザ環境依存のため)。

const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
global.window.h = () => ({});
require("../public/js/tf-lifestyle-forms.js");

const {
  clampMinInt, normalizeDropEntry, normalizeDropCategory, resolveDropTableContainer
} = global.window.DROP_TABLE_LOGIC;

test("clampMinInt: 整数へ丸め、min未満はminへ寄せる", () => {
  assert.equal(clampMinInt(5, 1), 5);
  assert.equal(clampMinInt(5.9, 1), 5);
  assert.equal(clampMinInt(0, 1), 1);
  assert.equal(clampMinInt(-3, 1), 1);
  assert.equal(clampMinInt(null, 1), 1);
  assert.equal(clampMinInt(undefined, 1), 1);
  assert.equal(clampMinInt("abc", 1), 1);
  assert.equal(clampMinInt("7", 1), 7);
});

test("normalizeDropEntry: item/weight/amount の既定値を補う", () => {
  assert.deepEqual(normalizeDropEntry({}), { item: "", weight: 1, amount: 1 });
  assert.deepEqual(normalizeDropEntry({ item: "tf_scrap", weight: 10, amount: 3 }), { item: "tf_scrap", weight: 10, amount: 3 });
  assert.deepEqual(normalizeDropEntry({ item: "IRON_INGOT", weight: 0, amount: -5 }), { item: "IRON_INGOT", weight: 1, amount: 1 });
  assert.deepEqual(normalizeDropEntry(null), { item: "", weight: 1, amount: 1 });
});

test("normalizeDropCategory: display-name/entries を補い、triggerChance指定時のみ発動率%を補う", () => {
  const withTrigger = normalizeDropCategory({}, { triggerChance: true });
  assert.equal(withTrigger["display-name"], "");
  assert.deepEqual(withTrigger.entries, []);
  assert.equal(withTrigger["trigger-chance-percent"], 0);

  const withoutTrigger = normalizeDropCategory({}, { triggerChance: false });
  assert.equal("trigger-chance-percent" in withoutTrigger, false);

  const filled = normalizeDropCategory({
    "display-name": "Tier1",
    "trigger-chance-percent": 5.5,
    entries: [{ item: "tf_gacha_ticket_1", weight: 10, amount: 1 }, {}]
  }, { triggerChance: true });
  assert.equal(filled["display-name"], "Tier1");
  assert.equal(filled["trigger-chance-percent"], 5.5);
  assert.deepEqual(filled.entries, [
    { item: "tf_gacha_ticket_1", weight: 10, amount: 1 },
    { item: "", weight: 1, amount: 1 }
  ]);
});

test("resolveDropTableContainer: パスを辿って中間オブジェクトを生成する (working直接編集)", () => {
  const working = {};
  const container = resolveDropTableContainer(working, ["fishing", "groups", "treasure"]);
  assert.equal(working.fishing.groups.treasure, container);
  assert.deepEqual(container, {});
});

test("resolveDropTableContainer: 空パスは working 自身を返す", () => {
  const working = { foo: 1 };
  assert.equal(resolveDropTableContainer(working, []), working);
  assert.equal(resolveDropTableContainer(working, undefined), working);
});

test("resolveDropTableContainer: 既存の非オブジェクト値は上書きして辿る (working直接編集の既存挙動と一致)", () => {
  const working = { "drop-tables": "legacy-string" };
  const container = resolveDropTableContainer(working, ["drop-tables"]);
  assert.deepEqual(container, {});
  assert.deepEqual(working["drop-tables"], {});
});
