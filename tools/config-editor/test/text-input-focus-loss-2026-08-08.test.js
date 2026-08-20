"use strict";

// ---------------------------------------------------------------------------
// 入力欄が「1文字打つたびに入力状態が途切れる」問題 (2026-08-08)
//
// `window.textInput` は oninput = **1文字ごと**に発火する。そのコールバックの中で
// 再描画すると入力欄そのものが作り直され、フォーカスが失われる。
// 実際に踏んだのは鍛冶ギミック画面の「対象 ID」(crafting-features.yml disassembly の
// マップキー)。キー名の変更は renameKey → 再描画が必須なので、oninput のままだと
// 1文字ごとに rename が走り、途中状態のキー("c" → "co" → "cop" …)まで作っていた。
//
// 対策は `window.textInputOnCommit`(onchange = 確定時のみ)。
// ここでは 2 つを固定する:
//   1. 2 つのヘルパが実際にどのイベントへ配線されるか (挙動)
//   2. 再描画するコールバックを持つ textInput が新たに増えていないこと (構造ガード)
//
// 2 はソース走査だが、固定しているのは文字列ではなく「コールバック本文に再描画呼び出しを
// 含む textInput が存在するか」という構造。意図的に許すものは
// `// oninput-rerender-ok:` の理由コメントを直前に置くことで明示的に免除する
// (許可リストを別ファイルに持つとリスト自体が腐って検査ごと無効化されるため、
//  免除は必ず現場のコードの隣に置く)。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const JS_DIR = path.join(__dirname, "..", "public", "js");

// util.js は window.h に依存するだけなので、属性を記録するだけの h を差し込めば
// ブラウザ無しでも「どのイベントへ配線したか」を実際に呼んで確かめられる。
function loadInputHelpers() {
  const created = [];
  global.window = {
    h(tag, attrs) {
      const el = { tag, attrs: { ...attrs } };
      created.push(el);
      return el;
    }
  };
  const src = fs.readFileSync(path.join(JS_DIR, "util.js"), "utf8");
  // util.js 全体は document 等に触れる箇所があるので、必要な 2 関数の定義だけを取り出して評価する。
  const start = src.indexOf("window.textInput = function textInput");
  const end = src.indexOf("\n};", src.indexOf("window.textInputOnCommit")) + 3;
  assert.ok(start >= 0 && end > start, "util.js から textInput / textInputOnCommit を切り出せない");
  // eslint-disable-next-line no-new-func
  new Function("window", src.slice(start, end))(global.window);
  return { window: global.window, created };
}

test("textInput は 1 文字ごと(oninput)に発火する — 再描画するコールバックには使えない", () => {
  const { window } = loadInputHelpers();
  const seen = [];
  const el = window.textInput("abc", (v) => seen.push(v), "placeholder");
  assert.equal(typeof el.attrs.oninput, "function");
  assert.equal(el.attrs.onchange, undefined);
  el.attrs.oninput({ target: { value: "abcd" } });
  assert.deepEqual(seen, ["abcd"], "oninput で即座にコールバックが呼ばれる");
});

test("textInputOnCommit は確定時(onchange)だけ発火する — 1 文字ごとには呼ばれない", () => {
  const { window } = loadInputHelpers();
  const seen = [];
  const el = window.textInputOnCommit("copper_*", (v) => seen.push(v), "例: wooden_*");
  assert.equal(el.attrs.oninput, undefined, "oninput に配線するとフォーカス飛びが再発する");
  assert.equal(typeof el.attrs.onchange, "function");
  el.attrs.onchange({ target: { value: "iron_*" } });
  assert.deepEqual(seen, ["iron_*"]);
  assert.equal(el.attrs.value, "copper_*", "初期値がそのまま value に入る");
});

// --- 構造ガード: 再描画するコールバックを持つ textInput を増やさない ---

const RERENDER = /\b(render|rerender|renderEntry|renderAll|refresh)\s*\(\s*\)/;
const EXEMPT_MARKER = "oninput-rerender-ok:";

function findRerenderingTextInputs() {
  const hits = [];
  for (const file of fs.readdirSync(JS_DIR).filter((f) => f.endsWith(".js"))) {
    const src = fs.readFileSync(path.join(JS_DIR, file), "utf8");
    const re = /window\.textInput\s*\(/g;
    let m;
    while ((m = re.exec(src))) {
      // 呼び出しの閉じ括弧まで括弧の対応を数えて本文を切り出す
      let depth = 0;
      let i = m.index + m[0].length - 1;
      for (; i < src.length; i++) {
        if (src[i] === "(") depth++;
        else if (src[i] === ")") { depth--; if (depth === 0) break; }
      }
      const body = src.slice(m.index, i + 1);
      if (!RERENDER.test(body)) continue;
      const before = src.slice(0, m.index);
      const line = before.split("\n").length;
      // 直前 3 行以内に免除マーカーがあれば許す
      const near = before.split("\n").slice(-4).join("\n");
      hits.push({ file, line, exempt: near.includes(EXEMPT_MARKER) });
    }
  }
  return hits;
}

test("再描画するコールバックを持つ textInput は、理由コメント付きの免除以外に存在しない", () => {
  const offenders = findRerenderingTextInputs()
    .filter((hit) => !hit.exempt)
    .map((hit) => `${hit.file}:${hit.line}`);
  assert.deepEqual(offenders, [],
    "コールバックで再描画する入力欄は window.textInputOnCommit を使うこと"
    + "(textInput は 1 文字ごとに発火するのでフォーカスが飛ぶ)。"
    + `入力欄自体が作り直されないことが確かなら、直前に「// ${EXEMPT_MARKER} 理由」を書いて免除する。`);
});

test("構造ガード自体が動いている(免除マーカーの実物が1件以上ある)", () => {
  // 検査対象が 0 件だと「常に緑」で壊れていても気づけない。
  const all = findRerenderingTextInputs();
  assert.ok(all.length > 0, "textInput + 再描画の組み合わせを 1 件も見つけられていない = 走査が壊れている");
  assert.ok(all.some((h) => h.exempt), "免除マーカーが 1 件も無い = マーカーの読み取りが壊れている可能性");
});

test("鍛冶ギミックの「対象 ID」欄は確定時コミットになっている", () => {
  const src = fs.readFileSync(path.join(JS_DIR, "tf-crafting-features.js"), "utf8");
  const idx = src.indexOf("disassembly-target-id");
  assert.ok(idx > 0, "対象 ID 欄が見つからない(画面構成が変わったらこのテストも直すこと)");
  // ラベル定義の直後に来る入力欄が確定時コミットであること
  const after = src.slice(idx, idx + 900);
  assert.ok(after.includes("window.textInputOnCommit("),
    "対象 ID はマップのキー名なので rename → 再描画が必須。textInput のままだと 1 文字ごとに"
    + "入力欄が作り直されてフォーカスが飛ぶ");
});
