"use strict";

// 【2026-08-02 指摘6】materialInput (util.js) は 2026-07-29 に listSelect ベースへ移行済みで、
// 選択後のトリガー表示自体が既に日本語名(primary)になっている。それより前に作られた
// materialHintEl を隣に並べ続けている箇所は、同じ日本語名が2回連続で描画され行が潰れる
// (実サーバ報告: 「素材 / material / 幸運のスレッド / 幸運のスレッド / 確率(0〜1) / …」)。
//
// mob-forms.js の4箇所は既に修正済み (2026-08-02)。ここでは残っていた3箇所
// (forms.js のカタログカード / ars-spellbooks.js / functional-items.js) を固定する。
//
// materialHintEl 自体は raw な <input> 系の Material 欄 (listSelect を経由しない箇所、例:
// forms.js の ingredientMaterialControl) では今も意味があるので一律削除しない —
// このテストは「materialInput の直後に matHint を並べる」パターンに限定して検出する。

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const ROOT = path.resolve(__dirname, "..");
const JS = (f) => fs.readFileSync(path.join(ROOT, "public", "js", f), "utf8");

// `[matInput, matHint]` は「materialInput の結果を matHint と並べて1つの入力欄として描画する」
// この不具合パターンそのもの。ingredientMaterialControl は wrap.appendChild を個別に呼ぶ
// 別の書き方なので、この文字列パターンには当たらない (誤検知しない)。
const DUP_PATTERN = /\[matInput,\s*matHint\]/;

test("forms.js: カタログカードの material 欄から matHint (二重表示) が消えている", () => {
  const src = JS("forms.js");
  assert.ok(!DUP_PATTERN.test(src),
    "forms.js に [matInput, matHint] パターンが残っている(同じ日本語名が2回出て行が潰れる)");
  // materialInput 自体(表示自体は残す)は残っていること。
  assert.match(src, /window\.materialInput\(entry\.material, "material-list"/,
    "material 入力そのものまで削除してしまっている");
  // ingredientMaterialControl (raw input + 互換リストボタン) 側の matHint は削除対象外。
  assert.match(src, /function ingredientMaterialControl/,
    "対象外のはずの ingredientMaterialControl 自体が無くなっている(誤って広く削除した疑い)");
  const ingredientHintUsage = /const matHint = window\.materialHintEl\(state\.base\);/;
  assert.match(src, ingredientHintUsage,
    "ingredientMaterialControl の matHint まで削除されている(こちらは raw input なので対象外)");
});

test("ars-spellbooks.js: 触媒カードの material 欄から matHint (二重表示) が消えている", () => {
  const src = JS("ars-spellbooks.js");
  assert.ok(!DUP_PATTERN.test(src),
    "ars-spellbooks.js に [matInput, matHint] パターンが残っている");
  assert.match(src, /window\.materialInput\(entry\.material, "material-list"/,
    "material 入力そのものまで削除してしまっている");
});

test("functional-items.js: material 欄から matHint (二重表示) が消えている", () => {
  const src = JS("functional-items.js");
  assert.ok(!DUP_PATTERN.test(src),
    "functional-items.js に [matInput, matHint] パターンが残っている");
  assert.match(src, /window\.materialInput\(entry\.material, "material-list"/,
    "material 入力そのものまで削除してしまっている");
});

test("ars-forms / ars-source-forms / loot-tables-form: materialInput の隣に materialHintEl を並べない", () => {
  for (const f of ["ars-forms.js", "ars-source-forms.js", "loot-tables-form.js", "ars-p4.js"]) {
    const src = JS(f);
    assert.ok(!/materialInput\([\s\S]{0,400}?materialHintEl/.test(src)
      && !/materialHintEl\([\s\S]{0,200}?materialInput/.test(src)
      && !/input-with-hint[\s\S]{0,80}?matInput/.test(src),
      `${f} が materialInput の隣に materialHintEl を並べている(日本語名が二重表示される)`);
  }
});

test("mob-forms.js: 既存修正(2026-08-02)が退行していない(対照実験)", () => {
  const src = JS("mob-forms.js");
  assert.ok(!DUP_PATTERN.test(src),
    "mob-forms.js に [matInput, matHint] パターンが再発している");
});
