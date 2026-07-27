"use strict";

// タスク6 (2026-07-26) の回帰テスト: グリフ画面でカテゴリを追加できないバグ。
//
// 原因: glyphs.<id>.category は「意味を持たない表示用の分類」で、カテゴリ一覧
// (renderCatBar のチップ)は各グリフの category 自由入力欄から動的収集するだけだった。
// 他タブ(editor-categories.js の renderEditorCategoryBar)にはある「+ カテゴリ」ボタンが
// このグリフ画面には無く、まだどのグリフにも使われていない空のカテゴリを先に名付ける
// 手段が無かった。
//
// 修正: public/js/ars-p4.js に pendingCategories (ページ内メモリのみ、yml非保存) と
// 「+ カテゴリ」ボタンを追加し、collectCategories() が既存カテゴリと合流するようにした。
// その合流ロジックを window.GLYPH_CATEGORY_LOGIC.mergeCategoryNames として純関数に
// 切り出してあるので、DOM を介さずここでテストする。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

global.window = global.window || {};
if (!global.window.h) global.window.h = function h() { return {}; };

const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "ars-p4.js"), "utf8");
// eslint-disable-next-line no-new-func
new Function("window", src)(global.window);

const { mergeCategoryNames } = global.window.GLYPH_CATEGORY_LOGIC;

test("mergeCategoryNames: 既存カテゴリのみならソートしてそのまま返す", () => {
  assert.deepEqual(mergeCategoryNames(["攻撃", "移動"], []), ["攻撃", "移動"].sort());
});

test("mergeCategoryNames: pending(未使用の仮登録)カテゴリも一覧に合流する", () => {
  const result = mergeCategoryNames(["攻撃"], ["詠唱補助"]);
  assert.ok(result.includes("攻撃"));
  assert.ok(result.includes("詠唱補助"), "「+ カテゴリ」で追加した未使用カテゴリが一覧に出る必要がある");
});

test("mergeCategoryNames: 既存とpendingが重複していても1件にまとまる", () => {
  const result = mergeCategoryNames(["攻撃"], ["攻撃", "移動"]);
  assert.deepEqual(result, ["攻撃", "移動"].sort());
});

test("mergeCategoryNames: 空文字/空白のみのpendingは無視する", () => {
  const result = mergeCategoryNames(["攻撃"], ["", "   ", "移動"]);
  assert.deepEqual(result, ["攻撃", "移動"].sort());
});

test("mergeCategoryNames: 既存・pendingとも空なら空配列", () => {
  assert.deepEqual(mergeCategoryNames([], []), []);
  assert.deepEqual(mergeCategoryNames(undefined, undefined), []);
});
