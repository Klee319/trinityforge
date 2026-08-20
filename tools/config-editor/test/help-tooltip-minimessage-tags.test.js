"use strict";

// 2026-08-01 実サーバ報告の追跡:「『?』のホバー説明にレガシーの HTML が出てくる」。
//
// 前回(a42e55d)はブラウザ標準 title との**二重表示**を潰した。しかし報告は残っていた。
// 残っていた本体はこちら:
//
//   独自ツールチップ本体が、説明文に埋まった MiniMessage のプレースホルダー
//   (`<icon>` `<name>` `<value>` `<gray>` `</gray>` …) を**地の文と同じ書式の生テキスト**で
//   流し込んでいた。読み手には「説明の途中に HTML タグが漏れている」ようにしか見えない。
//
// 実際に出ている文字列 (public/js/tf-lore.js renderLayoutCard):
//   1ステ行のMiniMessageテンプレート。使えるプレースホルダー: <icon>=アイコン文字列 /
//   <name>=ステ表示名 / <value>=符号・単位・色つきの値。例: <gray><icon><name>：<value></gray>
//
// 【方針】タグは**消さずに、コード片として描き分ける**。
//   タグそのものが説明の中身(「`<icon>` と書くとアイコンになる」)なので、除去すると
//   「=アイコン文字列」だけが残って文が壊れる。editor には既に「機械が読む文字列は
//   等幅+チップ背景」という表示規約がある (.form-label-key / .help-tooltip-key /
//   .locked-id / .se-help-list code)。ツールチップ内のタグもその規約に合わせる。
//   → 地の文と見分けが付くので「HTML が漏れている」に見えなくなり、情報も失われない。
//
// 生成は従来どおり textContent のみ (innerHTML 不使用 = 設定値が HTML として解釈される経路を作らない)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

function makeFakeElement(tag) {
  const attrs = {};
  const listeners = {};
  const el = {
    tagName: tag,
    children: [],
    style: {},
    parentNode: null,
    offsetWidth: 40,
    offsetHeight: 20,
    get className() { return attrs["class"] || ""; },
    set className(v) { attrs["class"] = v; },
    get textContent() { return attrs.__text || ""; },
    set textContent(v) { attrs.__text = v; },
    setAttribute(k, v) { attrs[k] = String(v); },
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(attrs, k) ? attrs[k] : null; },
    removeAttribute(k) { delete attrs[k]; },
    hasAttribute(k) { return Object.prototype.hasOwnProperty.call(attrs, k); },
    addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
    removeEventListener(type, fn) {
      if (!listeners[type]) return;
      listeners[type] = listeners[type].filter((f) => f !== fn);
    },
    dispatch(type, evt) {
      (listeners[type] || []).forEach((fn) => fn(evt || { stopPropagation() {}, preventDefault() {} }));
    },
    appendChild(c) { this.children.push(c); c.parentNode = this; return c; },
    removeChild(c) {
      const i = this.children.indexOf(c);
      if (i >= 0) this.children.splice(i, 1);
      c.parentNode = null;
      return c;
    },
    contains(node) {
      if (node === this) return true;
      return this.children.some((c) => c === node || (c.contains && c.contains(node)));
    },
    getBoundingClientRect() { return { left: 10, right: 30, top: 10, bottom: 30, width: 20, height: 20 }; }
  };
  return el;
}

function setupFakeDom() {
  const docListeners = {};
  const body = makeFakeElement("body");
  const doc = {
    createElement: (tag) => makeFakeElement(tag),
    body,
    addEventListener(type, fn) { (docListeners[type] = docListeners[type] || []).push(fn); },
    removeEventListener(type, fn) {
      if (!docListeners[type]) return;
      docListeners[type] = docListeners[type].filter((f) => f !== fn);
    }
  };
  global.document = doc;
  global.window = global.window || {};
  global.window.innerWidth = 1024;
  global.window.innerHeight = 768;
  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");
  return doc;
}

/** ツールチップを開いて、行要素の配列を返す。 */
function openTooltip(desc) {
  const doc = setupFakeDom();
  const icon = window.helpIcon(desc);
  icon.dispatch("click");
  const tip = doc.body.children[0];
  return tip.children.filter((c) => c.className === "help-tooltip-line");
}

/** 行を構成する断片を [{ tag:boolean, text:string }] に落とす (素の text 行も1断片扱い)。 */
function segmentsOf(line) {
  if (line.children.length === 0) return [{ tag: false, text: line.textContent }];
  return line.children.map((c) => ({
    tag: c.className === "help-tooltip-tag",
    text: c.textContent
  }));
}

// 実際に画面へ出ている文言そのもの (tf-lore.js renderLayoutCard の templateDesc)。
const REAL_DESC = "1ステ行のMiniMessageテンプレート。使えるプレースホルダー: "
  + "<icon>=アイコン文字列 / <name>=ステ表示名 / <value>=符号・単位・色つきの値。"
  + "例: <gray><icon><name>：<value></gray>";

test("MiniMessage タグは地の文と同じ生テキストで流し込まない (HTML が漏れて見える真因)", () => {
  const lines = openTooltip(REAL_DESC);
  assert.equal(lines.length, 1);
  const segs = segmentsOf(lines[0]);
  const plainText = segs.filter((s) => !s.tag).map((s) => s.text).join("");
  assert.ok(!/<\/?[a-zA-Z][a-zA-Z0-9_:#-]*>/.test(plainText),
    "タグが地の文の断片に残っている = ツールチップ上で HTML の断片に見える。"
    + " 実際に残っていた文字列: " + JSON.stringify(plainText));
});

test("タグは削除せずコード片として描き分ける (説明の意味を壊さない)", () => {
  const lines = openTooltip(REAL_DESC);
  const segs = segmentsOf(lines[0]);
  // 1. 情報が落ちていない: 断片を連結すると元の説明文に戻る。
  assert.equal(segs.map((s) => s.text).join(""), REAL_DESC,
    "タグを削ると「=アイコン文字列」だけが残り説明が壊れる。連結して元文へ戻ること");
  // 2. タグは1つ残らずコード片側にある。
  const tagged = segs.filter((s) => s.tag).map((s) => s.text);
  assert.deepEqual(tagged,
    ["<icon>", "<name>", "<value>", "<gray>", "<icon>", "<name>", "<value>", "</gray>"],
    "開きタグ・閉じタグとも help-tooltip-tag へ切り出すこと");
});

test("タグを含まない説明は従来どおり単純な1行のまま (無用な断片化をしない)", () => {
  const lines = openTooltip("ふつうの説明文です。\n2行目もふつう。");
  assert.deepEqual(lines.map((l) => l.textContent), ["ふつうの説明文です。", "2行目もふつう。"]);
  assert.deepEqual(lines.map((l) => l.children.length), [0, 0]);
});

test("比較記号など タグでない `<` は切り出さない", () => {
  const lines = openTooltip("レベル<10 のとき適用 (a < b)");
  const segs = segmentsOf(lines[0]);
  assert.deepEqual(segs.filter((s) => s.tag), [], "`<10` や `< b` をタグ扱いしてはいけない");
});

test("ツールチップは innerHTML を使わない (設定値が HTML として解釈される経路を作らない)", () => {
  const util = fs.readFileSync(path.join(__dirname, "..", "public", "js", "util.js"), "utf8");
  const start = util.indexOf("function openHelpTooltip(");
  assert.ok(start > 0);
  const body = util.slice(start, util.indexOf("window.helpIcon = function", start));
  // 「innerHTML不使用」と書いたコメントに当たらないよう、実際の代入だけを見る。
  assert.ok(!/\.innerHTML\s*=/.test(body), "openHelpTooltip が innerHTML へ書き込んでいる");
});

test("CSS 側にコード片の見た目が定義されている (定義漏れだと地の文と同じに見える)", () => {
  const css = fs.readFileSync(path.join(__dirname, "..", "public", "style.css"), "utf8");
  assert.match(css, /\.help-tooltip-tag\s*\{[^}]*monospace/,
    ".help-tooltip-tag が等幅で定義されていない (editor の「機械が読む文字列」表示規約から外れる)");
});
