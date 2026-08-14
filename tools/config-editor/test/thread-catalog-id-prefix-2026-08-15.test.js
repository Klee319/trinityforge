"use strict";

// ---------------------------------------------------------------------------
// 2026-08-15: 実サーバ報告「スレッドの固有設定欄が項目名だけ残って表示されなくなった」の回帰テスト。
//
// 真因: items/catalog.yml に新規追加されたスレッド「流転のスレッド」の id が
// `thred_translate`(thread の綴り誤り)になっていた。ArsPaper の UnifiedRecipeLoader は
// カタログid を常に `recipeKey("thread_" + threads.ymlのid), ...)` で生成するため、
// `thread_` で始まらない id は threads.yml / thread-sets.yml のどのエントリにも対応せず、
// forms.js の resolveThreadId が null を返す。renderThreadExtraFields は見出しだけ無条件に
// 描画したあと、null な threadId のせいで renderThreadYmlEffects/renderThreadSetEffects が
// 両方とも即 return し、理由がどこにも表示されないまま「見出しだけの空欄」になっていた。
//
// このテストは
//   (a) forms.js の純関数 window.threadCatalogIdProblem の挙動そのもの(ソースgrepにしない)
//   (b) lib/schema.js の validateCatalog が「スレッド」タブに分類された id の命名規則違反を
//       保存時に検出すること
// の2本を、実際に関数/validate()を呼んで検証する。
//
// 「戻すと落ちる」証明: threadCatalogIdProblem の bad-prefix 判定条件
// (!catId.startsWith("thread_") || catId.length <= "thread_".length) を
// `false` 決め打ちに戻す、または validateCatalogThreadTabIds の呼び出しをコメントアウトすると、
// このファイルの該当テストが確実に落ちることを作業中に確認済み(下記レポート参照)。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema.js");

// ---------------------------------------------------------------------------
// 1. window.threadCatalogIdProblem (forms.js) の挙動
// ---------------------------------------------------------------------------

global.window = global.window || {};
require("../public/js/editor-categories.js");
require("../public/js/forms.js");
const threadCatalogIdProblem = global.window.threadCatalogIdProblem;

test("threadCatalogIdProblem: window に関数として公開されている", () => {
  assert.equal(typeof threadCatalogIdProblem, "function");
});

test("threadCatalogIdProblem: 正しい id (thread_translate) は null (問題なし)", () => {
  assert.equal(threadCatalogIdProblem("thread_translate"), null);
});

test("threadCatalogIdProblem: タイプミス (thred_translate) は bad-prefix で、実際の id を title に含む", () => {
  const problem = threadCatalogIdProblem("thred_translate");
  assert.ok(problem, "問題として検出されるべき");
  assert.equal(problem.reason, "bad-prefix");
  assert.ok(problem.title.includes("thred_translate"), `title に実際の id が含まれること: ${problem.title}`);
  assert.equal(typeof problem.hint, "string");
  assert.ok(problem.hint.length > 0);
});

test("threadCatalogIdProblem: 空文字/null/undefined は no-catalog", () => {
  for (const v of ["", null, undefined]) {
    const problem = threadCatalogIdProblem(v);
    assert.ok(problem, `${JSON.stringify(v)} は問題として検出されるべき`);
    assert.equal(problem.reason, "no-catalog");
  }
});

test("threadCatalogIdProblem: 'thread_' ちょうど(id部分が空)は bad-prefix", () => {
  const problem = threadCatalogIdProblem("thread_");
  assert.ok(problem);
  assert.equal(problem.reason, "bad-prefix");
});

// ---------------------------------------------------------------------------
// 2. lib/schema.js validateCatalog: _editor.itemTabs で thread に分類された id の命名規則検査
// ---------------------------------------------------------------------------

function catalogDoc(itemTabs) {
  return {
    items: {},
    _editor: { itemTabs }
  };
}

test("validateCatalog: thread 分類の正しい id (thread_foo) だけならエラーなし", () => {
  const errors = validate("catalog", catalogDoc({ thread_foo: "thread" }));
  assert.deepStrictEqual(errors, []);
});

test("validateCatalog: thread 分類に thred_foo(タイプミス)があるとエラーが1件、メッセージに id を含む", () => {
  const errors = validate("catalog", catalogDoc({ thred_foo: "thread" }));
  assert.equal(errors.length, 1, `エラーは1件のはず: ${JSON.stringify(errors)}`);
  assert.ok(errors[0].includes("thred_foo"), `エラーメッセージに id が含まれること: ${errors[0]}`);
});

test("validateCatalog: _editor 自体が無い catalog はエラーなし(後方互換)", () => {
  const errors = validate("catalog", { items: {} });
  assert.deepStrictEqual(errors, []);
});

test("validateCatalog: _editor.itemTabs が無い catalog はエラーなし(後方互換)", () => {
  const errors = validate("catalog", { items: {}, _editor: {} });
  assert.deepStrictEqual(errors, []);
});

test("validateCatalog: thread 以外のタブ(catalyst 等)の id は thread_ で始まらなくてもエラーにならない", () => {
  const errors = validate("catalog", catalogDoc({
    some_catalyst: "catalyst",
    thred_bad_but_not_thread: "other"
  }));
  assert.deepStrictEqual(errors, []);
});

test("validateCatalog: thread 分類が複数あり、一部だけタイプミスの場合は違反分だけエラーになる", () => {
  const errors = validate("catalog", catalogDoc({
    thread_ok_one: "thread",
    thread_ok_two: "thread",
    thred_bad: "thread"
  }));
  assert.equal(errors.length, 1);
  assert.ok(errors[0].includes("thred_bad"));
});
