"use strict";

// ---------------------------------------------------------------------------
// 2026-08-13 実サーバ報告の回帰テスト:
//   「アイテムカタログやアイテムステータス内の検索窓で文字を1つ入れるたびに入力状態が途切れる」
//
// 真因は2つ重なっていた。
//   (1) 検索欄の oninput が同期で一覧を作り直していた。カタログ「武器」タブで実測すると
//       1打鍵あたり **570ms** メインスレッドを止める(188カード再生成)。日本語入力では
//       この停止が変換中に挟まるので「入力状態が途切れる」形で現れる。
//   (2) 「素材」タブ(ars-forms.js)の oninput は render() を呼ぶが、render() は
//       root.innerHTML = "" で**検索欄自身を作り直す**。入力欄が DOM から外れるので
//       1文字ごとに確実にフォーカスが飛ぶ。
//
// 対策: 共通部品 window.filterInput に集約する。
//   - IME 変換中(compositionstart〜compositionend)は一切絞り込まない(変換を壊さない)
//   - それ以外は入力から delay ms のデバウンス(打鍵ごとの全再描画をやめる)
//   - 再描画で入力欄ごと作り直される画面のために、フォーカスとカーソル位置を新しい欄へ戻す
// ---------------------------------------------------------------------------

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");

function makeEl(tag) {
  const el = {
    tag, attrs: {}, children: [], value: "", className: "", textContent: "",
    isConnected: true, selectionStart: null,
    _listeners: {},
    setAttribute(k, v) { el.attrs[k] = v; },
    removeAttribute(k) { delete el.attrs[k]; },
    appendChild(c) { el.children.push(c); return c; },
    addEventListener(ev, fn) { (el._listeners[ev] = el._listeners[ev] || []).push(fn); },
    focus() { el._focused = true; },
    setSelectionRange(a) { el._caret = a; },
    fire(ev) { for (const fn of el._listeners[ev] || []) fn({ target: el }); }
  };
  return el;
}

function setupDom() {
  global.window = {};
  global.document = {
    createElement: (tag) => makeEl(tag),
    createTextNode: (t) => ({ text: t })
  };
  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

test("filterInput: 打鍵のたびには絞り込まず、入力が止まってから1回だけ走る(デバウンス)", async () => {
  setupDom();
  const calls = [];
  const el = window.filterInput("test", "", (v) => calls.push(v), { delay: 30 });

  for (const v of ["s", "sw", "swo", "swor", "sword"]) {
    el.value = v;
    el.fire("input");
    await sleep(5);
  }
  assert.deepEqual(calls, [], "打鍵の途中で絞り込みが走っている(1打鍵ごとの全再描画が残っている)");
  await sleep(80);
  assert.deepEqual(calls, ["sword"], "入力が止まったあとに1回だけ走るはず");
});

test("filterInput: IME 変換中は一切絞り込まず、確定時に1回だけ走る", async () => {
  setupDom();
  const calls = [];
  const el = window.filterInput("test", "", (v) => calls.push(v), { delay: 10 });

  el.fire("compositionstart");
  for (const v of ["k", "ke", "けん"]) {
    el.value = v;
    el.fire("input");
    await sleep(20);
  }
  assert.deepEqual(calls, [], "変換中に絞り込みが走っている(IME の変換状態が壊れる)");

  el.value = "剣";
  el.fire("compositionend");
  await sleep(40);
  assert.deepEqual(calls, ["剣"], "変換確定後に1回だけ走るはず");
});

test("filterInput: 再描画で作り直された検索欄へフォーカスとカーソル位置を戻す", async () => {
  setupDom();
  const first = window.filterInput("catalog", "", () => {}, { delay: 5 });
  first.value = "sw";
  first.selectionStart = 2;
  first.fire("input");
  await sleep(20);

  // 再描画で新しい入力欄が作られた、という状況を作る。
  const second = window.filterInput("catalog", "sw", () => {}, { delay: 5 });
  await sleep(20);
  assert.equal(second._focused, true, "作り直された検索欄にフォーカスが戻っていない");
  assert.equal(second._caret, 2, "カーソル位置が復元されていない");
});

test("filterInput: 別画面の検索欄(key違い)へはフォーカスを奪わない", async () => {
  setupDom();
  const a = window.filterInput("catalog", "", () => {}, { delay: 5 });
  a.value = "s";
  a.fire("input");
  await sleep(20);
  const b = window.filterInput("materials", "", () => {}, { delay: 5 });
  await sleep(20);
  assert.notEqual(b._focused, true, "無関係な画面の検索欄がフォーカスを奪っている");
});

// ---------------------------------------------------------------------------
// 配線: 3つの検索欄はすべて共通部品を使い、oninput から直接再描画しない
// ---------------------------------------------------------------------------

const FILTER_SITES = [
  ["forms.js", "アイテムステータス / アイテムカタログ"],
  ["ars-forms.js", "アイテムカタログの「素材」タブ"]
];

for (const [file, what] of FILTER_SITES) {
  test(`${file}: ${what} の検索欄は window.filterInput を使う(oninput 直結の再描画を残さない)`, () => {
    const src = JS(file);
    assert.match(src, /window\.filterInput\(/, `${file} が共通部品を使っていない`);
    assert.ok(!/oninput: \(e\) => \{ filterText = e\.target\.value;/.test(src),
      `${file} に oninput 直結の絞り込みが残っている(1打鍵ごとに全再描画される)`);
  });
}
