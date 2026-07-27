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
  // ブラウザ標準titleに頼らない: title属性は設定しない
  assert.equal(icon.getAttribute("title"), null, "title属性を使わないこと");
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
