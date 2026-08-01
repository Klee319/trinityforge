"use strict";

// 「?」ヘルプのツールチップが二重に出る回帰の防止テスト (2026-07-31)。
//
// 背景: 2026-07-27 に helpIcon はブラウザ標準の title 属性ではなく独自ポップオーバー
// (util.js の help-tooltip) へ移行した (help-icon-tooltip.test.js が icon 自身に title を
// 付けないことを固定している)。ところが tf-lore.js は helpIcon を「同じ説明文を title 属性に
// 持つ親要素」の中へ入れていたため、HTML の title が子孫にも効く仕様で
// **独自ポップオーバーとブラウザ標準ツールチップが同時に出ていた**
// (MiniMessage のプレースホルダ <icon>/<name>/<value> がそのまま見えるので
//  「レガシーのHTMLが出てくる」という症状になる)。
//
// このテストは DOM を作らずソースを構文的に走査する。理由は
//  (a) 症状の原因が「同じ h() 呼び出しで title と helpIcon を併記する」というコード上の形そのもの、
//  (b) buildLoreForm の DOM テストは window.* スタブ不足でファイル丸ごと未実行になる事故があるため。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const JS_DIR = path.join(__dirname, "..", "public", "js");

// --- 最小の JS スキャナ (文字列/コメントを飛ばして対応括弧を取る) ---------------------------

function skipString(src, i) {
  const quote = src[i];
  i++;
  while (i < src.length) {
    if (src[i] === "\\") { i += 2; continue; }
    if (src[i] === quote) return i + 1;
    i++;
  }
  return i;
}

/** src[open] の括弧に対応する閉じ括弧の index。見つからなければ -1。 */
function matchDelims(src, open) {
  const pairs = { "(": ")", "{": "}", "[": "]" };
  const stack = [pairs[src[open]]];
  let i = open + 1;
  while (i < src.length && stack.length) {
    const c = src[i];
    if (c === '"' || c === "'" || c === "`") { i = skipString(src, i); continue; }
    if (c === "/" && src[i + 1] === "/") {
      while (i < src.length && src[i] !== "\n") i++;
      continue;
    }
    if (c === "/" && src[i + 1] === "*") {
      i = src.indexOf("*/", i + 2);
      if (i < 0) return -1;
      i += 2;
      continue;
    }
    if (c === "(" || c === "{" || c === "[") { stack.push(pairs[c]); i++; continue; }
    if (c === ")" || c === "}" || c === "]") {
      if (stack[stack.length - 1] !== c) return -1;
      stack.pop();
      if (!stack.length) return i;
      i++;
      continue;
    }
    i++;
  }
  return -1;
}

/** `h(` 呼び出し (window.h のエイリアス) を全部拾う。 */
function scanHCalls(src) {
  const out = [];
  for (let i = 0; i + 1 < src.length; i++) {
    if (src[i] !== "h" || src[i + 1] !== "(") continue;
    const prev = i > 0 ? src[i - 1] : " ";
    if (/[A-Za-z0-9_$.]/.test(prev)) continue; // window.h( / foo.h( / xxh( は対象外
    const end = matchDelims(src, i + 1);
    if (end < 0) continue;
    out.push({ start: i, text: src.slice(i, end + 1) });
  }
  return out;
}

/** h(...) の第2引数のオブジェクトリテラル本文 (無ければ null)。 */
function propsOf(callText) {
  const brace = callText.indexOf("{");
  if (brace < 0) return null;
  const end = matchDelims(callText, brace);
  if (end < 0) return null;
  return callText.slice(brace, end + 1);
}

function findDoubleTooltips(fileName, src) {
  const hits = [];
  for (const call of scanHCalls(src)) {
    const props = propsOf(call.text);
    if (!props || !/(^|[{,\s])title\s*:/.test(props)) continue;
    if (!call.text.includes("window.helpIcon(")) continue;
    const line = src.slice(0, call.start).split("\n").length;
    hits.push(`${fileName}:${line}`);
  }
  return hits;
}

// --- テスト --------------------------------------------------------------------------------

test("public/js: title属性とhelpIconを同じh()に併記した箇所が無い(ツールチップの二重表示)", () => {
  const files = fs.readdirSync(JS_DIR).filter((f) => f.endsWith(".js")).sort();
  assert.ok(files.length > 0, "public/js に走査対象の js が無い(パスが壊れている)");
  const hits = [];
  for (const f of files) {
    hits.push(...findDoubleTooltips(f, fs.readFileSync(path.join(JS_DIR, f), "utf8")));
  }
  assert.deepEqual(hits, [],
    "helpIcon と同じ要素/祖先に title 属性があるとブラウザ標準ツールチップも同時に出る。"
      + " 説明文は helpIcon 側だけに渡すこと: " + hits.join(", "));
});

test("スキャナ自体が二重表示パターンを検出できる(テストが常に緑になる事故の防止)", () => {
  const sample = 'const a = h("div", { class: "x", title: desc }, [window.helpIcon(desc)]);';
  assert.deepEqual(findDoubleTooltips("sample.js", sample), ["sample.js:1"]);
  const fixed = 'const a = h("div", { class: "x" }, [window.helpIcon(desc)]);';
  assert.deepEqual(findDoubleTooltips("sample.js", fixed), []);
});

test("tf-lore.js: ステ行のキーは title 属性ではなく helpIcon の keyLabel で見せる", () => {
  const src = fs.readFileSync(path.join(JS_DIR, "tf-lore.js"), "utf8");
  assert.ok(/window\.helpIcon\(statDesc,\s*\{\s*keyLabel:/.test(src),
    "ステ説明の helpIcon に keyLabel(キー名)を渡していない(title 削除でキー表示が失われる)");
  assert.ok(!/class:\s*"lore-stat-key",\s*title:/.test(src),
    "lore-stat-key に native title が残っている");
});

test("tf-lore.js: 色ルールの見出しも helpIcon 経由で説明を出す(他の見出しと不統一にしない)", () => {
  const src = fs.readFileSync(path.join(JS_DIR, "tf-lore.js"), "utf8");
  assert.ok(!/class:\s*"sub-title",\s*text:\s*title,\s*title:\s*desc/.test(src),
    "renderColorGroup の見出しが native title のみで説明を出している(helpIcon へ寄せること)");
});
