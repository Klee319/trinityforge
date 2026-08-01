"use strict";

// public/js/util.js の window.helpIcon / window.fieldLabelEl 回帰テスト。
//
// 背景 (2026-07-27): 以前は「?」ヘルプアイコンがブラウザ標準の title 属性でツールチップを
// 出していた。説明文(labels.js には200文字超のものが多数)は折返し位置も書式(キー行と説明の階層)も
// 一切制御できず、長文が読めない塊になっていた。CSSで見た目を制御できる独自ツールチップ
// (util.js の help-tooltip 要素、ホバー/クリック/フォーカスで開閉)へ置き換えた回帰を検証する。
//
// このリポジトリのDOMテストは jsdom を使わず、window.h が要求する最小限のDOM API
// (setAttribute/appendChild/addEventListener 等)を持つ素朴なフェイク要素で代用する
// (ars-config-form-tdz.test.js / mob-forms-logic.test.js と同じ手法)。

const test = require("node:test");
const assert = require("node:assert/strict");

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
    dispatch(type, evt) { (listeners[type] || []).forEach((fn) => fn(evt || { stopPropagation() {}, preventDefault() {} })); },
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
    addEventListener(type, fn, _cap) { (docListeners[type] = docListeners[type] || []).push(fn); },
    removeEventListener(type, fn) {
      if (!docListeners[type]) return;
      docListeners[type] = docListeners[type].filter((f) => f !== fn);
    }
  };
  global.document = doc;
  global.window = global.window || {};
  global.window.innerWidth = 1024;
  global.window.innerHeight = 768;
  return doc;
}

setupFakeDom();
delete require.cache[require.resolve("../public/js/util.js")];
require("../public/js/util.js");

test("helpIcon: desc が空/未指定なら null を返す (既存挙動を維持)", () => {
  assert.equal(window.helpIcon(""), null);
  assert.equal(window.helpIcon(null), null);
  assert.equal(window.helpIcon(undefined), null);
});

test("helpIcon: desc があれば独自ツールチップ用のアイコン要素を返す (title属性頼みでない)", () => {
  const icon = window.helpIcon("これは説明文です");
  assert.ok(icon, "要素が返されること");
  assert.equal(icon.className, "help-icon");
  assert.equal(icon.textContent, "?");
  // ブラウザ標準titleに説明を載せない。ただし title="" は「注釈なし」の明示宣言で、
  // 祖先要素(sub-title/lore-stat-key 等)の title が「?」ホバー時に一緒に出るのを打ち消す
  // (2026-08-01 実サーバ報告「?をホバーするとレガシーのHTMLの説明が出てくる」の対策)。
  assert.equal(icon.getAttribute("title"), "", "祖先の title を打ち消す空 title を持つこと");
  // キーボード操作で到達できること
  assert.equal(icon.getAttribute("tabindex"), "0");
  assert.equal(icon.getAttribute("role"), "button");
  assert.equal(icon.getAttribute("aria-haspopup"), "true");
  assert.equal(icon.getAttribute("aria-expanded"), "false");
});

test("helpIcon: クリックで開くと改行(\\n)が行として反映されたツールチップがbodyへ挿入される", () => {
  const doc = setupFakeDom();
  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  const icon = window.helpIcon("1行目\n2行目");
  icon.dispatch("click");

  assert.equal(doc.body.children.length, 1, "ツールチップがbodyへ1つ挿入されること");
  const tip = doc.body.children[0];
  assert.equal(tip.className, "help-tooltip");
  const lineTexts = tip.children.filter((c) => c.className === "help-tooltip-line").map((c) => c.textContent);
  assert.deepEqual(lineTexts, ["1行目", "2行目"], "descの改行が個別の行として反映されること");
  assert.equal(icon.getAttribute("aria-expanded"), "true");

  // クリックで開いた(pinned)ので、再クリックで閉じる
  icon.dispatch("click");
  assert.equal(doc.body.children.length, 0, "再クリックで閉じること");
  assert.equal(icon.getAttribute("aria-expanded"), "false");
});

test("helpIcon: opts.keyLabel を渡すとツールチップ内に「キー: xxx」の別行が先頭に入る", () => {
  const doc = setupFakeDom();
  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  const icon = window.helpIcon("説明本文", { keyLabel: "キー: item-stats.weapon-damage" });
  icon.dispatch("click");

  const tip = doc.body.children[0];
  const keyEl = tip.children.find((c) => c.className === "help-tooltip-key");
  assert.ok(keyEl, "help-tooltip-key 要素が先頭にあること");
  assert.equal(keyEl.textContent, "キー: item-stats.weapon-damage");
});

test("fieldLabelEl: ラベルのtitleは短いキー表示のみになり、説明はhelpIcon側へ渡る", () => {
  setupFakeDom();
  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");
  global.window.LABELS = {
    fieldLabel: (key) => "テストラベル",
    fieldDesc: (key) => "テスト説明文"
  };

  const el = window.fieldLabelEl("test.key");
  assert.equal(el.className, "form-label with-ja");
  const jaEl = el.children.find((c) => c.className === "form-label-ja");
  assert.ok(jaEl);
  assert.equal(jaEl.getAttribute("title"), "キー: test.key");
  const helpEl = el.children.find((c) => c.className === "help-icon");
  assert.ok(helpEl, "説明文があるので help-icon が付くこと");
});

// ============================================================
// 2026-08-01 実サーバ報告「『?』ホバー説明を表示するとレガシーの HTML の説明が出てくる」の回帰テスト。
//
// 真因: `title` 属性のツールチップは**祖先へ遡って**表示される。自分に title が無い要素を
// ホバーすると、最も近い祖先の title がブラウザ標準ツールチップとして出る。
// editor には「説明文を丸ごと `sub-title` の title へ入れる」旧方式が残っており、その中に
// 置いた「?」をホバーすると 独自ツールチップ(整形済み) + 標準ツールチップ(生テキスト) が
// 二重に出ていた。生テキストには MiniMessage の `<gray><icon><name>` 等が含まれるので
// 「HTML の断片が出てくる」ように見える。
//
// 対策は2段構え。どちらも戻ると症状が復活するのでソースで固定する:
//   1. `helpIcon` に空 title を付けて祖先の title を打ち消す
//   2. 見出しの説明は `window.subTitleEl` (= helpIcon) へ回し、`.sub-title` に title を書かない
// ============================================================

const fs = require("node:fs");
const path = require("node:path");

const PUBLIC_JS = path.join(__dirname, "..", "public", "js");
const jsFiles = fs.readdirSync(PUBLIC_JS).filter((f) => f.endsWith(".js"));
const readJs = (f) => fs.readFileSync(path.join(PUBLIC_JS, f), "utf8");

test("subTitleEl: 説明があれば「?」が付き、.sub-title に title 属性は付かない", () => {
  setupFakeDom();
  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  const withDesc = window.subTitleEl("見出し", "説明文");
  assert.equal(withDesc.className, "sub-title");
  assert.equal(withDesc.getAttribute("title"), null, ".sub-title に title を付けてはいけない");
  const textEl = withDesc.children.find((c) => c.className === "sub-title-text");
  assert.ok(textEl);
  assert.equal(textEl.textContent, "見出し");
  assert.ok(withDesc.children.some((c) => c.className === "help-icon"), "説明があるので「?」が付くこと");

  // 説明が無ければ「?」は出さない (空ツールチップを開けるアイコンを増やさない)。
  const noDesc = window.subTitleEl("見出しのみ");
  assert.ok(!noDesc.children.some((c) => c.className === "help-icon"));
});

test("`.sub-title` へ説明文を title 属性で載せる旧方式が復活していない", () => {
  const offenders = [];
  for (const f of jsFiles) {
    if (f === "util.js") continue; // 契約を説明するコメントだけを持つ
    const src = readJs(f);
    // h(..., { class: "sub-title", ... title: ... }) の 1 呼び出し内に title があるものを拾う。
    const re = /class:\s*"sub-title"[\s\S]{0,400}?\}\)/g;
    let m;
    while ((m = re.exec(src)) !== null) {
      const call = m[0];
      // 属性としての title:(= class/text と同じ階層) だけを見る。sub-title-text 等は無関係。
      if (/(^|[,{\s])title:\s*/.test(call)) {
        offenders.push(`${f}: ${call.slice(0, 90).replace(/\s+/g, " ")}`);
      }
    }
  }
  assert.deepEqual(offenders, [],
    "見出しの説明は window.subTitleEl (=「?」の独自ツールチップ) へ回すこと。"
    + " title 属性に書くとブラウザ標準ツールチップが「?」ホバー時に二重に出る");
});

test("helpIcon はブラウザ標準ツールチップへ説明文を渡さない", () => {
  const util = readJs("util.js");
  // 空文字ちょうどであること (説明文を title へ入れる形に戻っていない)。
  assert.match(util, /class: "help-icon", text: "\?"/);
  assert.match(util, /\n\s*title: ""\n\s*\}\);/,
    "helpIcon の title が空文字でない (祖先打ち消しにならない / 生テキストが標準ツールチップへ漏れる)");
});
