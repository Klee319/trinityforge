"use strict";

// タスク8 (2026-07-26) の回帰テスト: 醸造ギミックタブ(potion-merge/brew-unlocks)の材料欄が
// Material の生ID(例: NETHER_WART)のままで日本語表示が無かった問題。
//
// 修正: public/js/labels.js に window.LABELS.materialLabelWithFallback を追加した。
// 既存の MATERIAL_LABELS 辞書 (/api/material-labels で埋まる) をそのまま参照するだけの
// 薄いラッパーで、新しい辞書は作っていない。辞書ヒット時は日本語、ミス時は生IDそのものを返す
// (既存の materialLabel() は未登録キーに空文字を返す仕様なので、そこだけ挙動を変えている)。

const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
require("../public/js/labels.js");

const { materialLabelWithFallback } = global.window.LABELS;

test("辞書ヒット時は日本語ラベルを返す", () => {
  global.window.MATERIAL_LABELS = { NETHER_WART: "ネザーウォート" };
  assert.equal(materialLabelWithFallback("NETHER_WART"), "ネザーウォート");
});

test("辞書ミス時は生IDのままフォールバックする(空文字にしない)", () => {
  global.window.MATERIAL_LABELS = { NETHER_WART: "ネザーウォート" };
  assert.equal(materialLabelWithFallback("SOME_UNKNOWN_MATERIAL"), "SOME_UNKNOWN_MATERIAL");
});

test("辞書が未ロード(空オブジェクト)でも落ちずに生IDへフォールバックする", () => {
  global.window.MATERIAL_LABELS = {};
  assert.equal(materialLabelWithFallback("GOLDEN_CARROT"), "GOLDEN_CARROT");
});

test("空/未指定はそのまま空文字を返す", () => {
  global.window.MATERIAL_LABELS = { NETHER_WART: "ネザーウォート" };
  assert.equal(materialLabelWithFallback(""), "");
  assert.equal(materialLabelWithFallback(null), "");
  assert.equal(materialLabelWithFallback(undefined), "");
});
