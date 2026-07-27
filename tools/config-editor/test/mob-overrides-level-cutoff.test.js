"use strict";

// mob-forms.js buildLevelCutoffBlock (2026-07-27 「レベル差による足きり」config-editor欄) の
// DOM配線テスト。tf-dungeon-gates-key-item.test.js と同じ手法: util.js を実物のまま読み込み、
// window.h だけ document 不要の軽量スタブへ差し替える(window.collapsibleCard を通る他フォームの
// スタブが el.style/el.classList を要求するのと同じ理由で、ここでも同形のスタブを使う)。
//
// buildLevelCutoffBlock 自体は window.MOB_FORMS_LOGIC 経由で直接呼べる(関数宣言の巻き上げにより
// expRampValue と同じ形で公開されている)。実際に組み立てられる欄の順序は over-level の
// threshold/exp-rate/drop-rate、続いて under-level の item-threshold の4つ。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    style: {},
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener() {},
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

function setupDom() {
  global.window = global.window || {};
  global.document = global.document || {};

  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };

  delete require.cache[require.resolve("../public/js/mob-forms.js")];
  require("../public/js/mob-forms.js");
}

/** 木を深さ優先で潜り、type=number の <input> だけを構築順に集める。 */
function collectNumberInputs(el, out) {
  out = out || [];
  if (!el || !Array.isArray(el.children)) return out;
  if (el.tag === "input" && el.props && el.props.type === "number") out.push(el);
  el.children.forEach((c) => collectNumberInputs(c, out));
  return out;
}

test("buildLevelCutoffBlock: 空のhostは4つの数値欄(threshold/exp-rate/drop-rate/item-threshold)を空欄で描く", () => {
  setupDom();
  const host = {};
  const block = global.window.MOB_FORMS_LOGIC.buildLevelCutoffBlock(host);
  const inputs = collectNumberInputs(block);
  assert.equal(inputs.length, 4);
  inputs.forEach((input) => assert.equal(input.props.value, ""));
});

test("buildLevelCutoffBlock: threshold入力で level-cutoff.over-level.threshold が書かれる", () => {
  setupDom();
  const host = {};
  const block = global.window.MOB_FORMS_LOGIC.buildLevelCutoffBlock(host);
  const [thresholdInput] = collectNumberInputs(block);

  thresholdInput.props.oninput({ target: { value: "10" } });

  assert.equal(host["level-cutoff"]["over-level"].threshold, 10);
});

test("buildLevelCutoffBlock: exp-rateに-1を入れると『経験値0』の特別値として書かれる", () => {
  setupDom();
  const host = {};
  const block = global.window.MOB_FORMS_LOGIC.buildLevelCutoffBlock(host);
  const [, expRateInput] = collectNumberInputs(block);

  expRateInput.props.oninput({ target: { value: "-1" } });

  assert.equal(host["level-cutoff"]["over-level"]["exp-rate"], -1);
});

test("buildLevelCutoffBlock: drop-rateを入力してから空に戻すと over-level ブロックごと消える", () => {
  setupDom();
  const host = {};
  const block = global.window.MOB_FORMS_LOGIC.buildLevelCutoffBlock(host);
  const [, , dropRateInput] = collectNumberInputs(block);

  dropRateInput.props.oninput({ target: { value: "0.5" } });
  assert.equal(host["level-cutoff"]["over-level"]["drop-rate"], 0.5);

  dropRateInput.props.oninput({ target: { value: "" } });
  assert.equal("level-cutoff" in host, false,
    "over-levelの唯一の項目を空にしたら、over-levelブロックごとlevel-cutoffキー自体も消えるはず");
});

test("buildLevelCutoffBlock: item-threshold入力で level-cutoff.under-level.item-threshold が書かれる", () => {
  setupDom();
  const host = {};
  const block = global.window.MOB_FORMS_LOGIC.buildLevelCutoffBlock(host);
  const [, , , itemThresholdInput] = collectNumberInputs(block);

  itemThresholdInput.props.oninput({ target: { value: "20" } });

  assert.equal(host["level-cutoff"]["under-level"]["item-threshold"], 20);
});

test("buildLevelCutoffBlock: under-levelを空に戻してもover-levelが残っていればlevel-cutoff自体は残る", () => {
  setupDom();
  const host = {};
  const block = global.window.MOB_FORMS_LOGIC.buildLevelCutoffBlock(host);
  const [thresholdInput, , , itemThresholdInput] = collectNumberInputs(block);

  thresholdInput.props.oninput({ target: { value: "10" } });
  itemThresholdInput.props.oninput({ target: { value: "20" } });
  itemThresholdInput.props.oninput({ target: { value: "" } });

  assert.equal("under-level" in host["level-cutoff"], false);
  assert.equal(host["level-cutoff"]["over-level"].threshold, 10,
    "under-levelだけを消してもover-levelの既存値は消えないはず");
});

test("buildLevelCutoffBlock: 既存値がある host からは初期値としてそのまま表示される", () => {
  setupDom();
  const host = {
    "level-cutoff": {
      "over-level": { threshold: 5, "exp-rate": 0.25, "drop-rate": -1 },
      "under-level": { "item-threshold": 30 }
    }
  };
  const block = global.window.MOB_FORMS_LOGIC.buildLevelCutoffBlock(host);
  const [thresholdInput, expRateInput, dropRateInput, itemThresholdInput] = collectNumberInputs(block);

  assert.equal(thresholdInput.props.value, "5");
  assert.equal(expRateInput.props.value, "0.25");
  assert.equal(dropRateInput.props.value, "-1");
  assert.equal(itemThresholdInput.props.value, "30");
});
