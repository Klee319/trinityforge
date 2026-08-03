"use strict";

// items.<id>.yield-multiplier (fork: SourcelinkConfig#readYieldMultiplier / SourceGenerationScaling) に
// editor 側の入力欄と検証を付けた際の回帰テスト(2026-08-03)。
//
// 背景: 2026-08-02 の階梯(transfer-multiplier)は「転送レート」しか上げず、上位ソースリンクへ
// 燃料を1個焼べても得られるソースは無印と同じだった。生成量そのものを上げるのが yield-multiplier。
//
// 固定する不変条件:
//   A. lib/schema.js が Java と同じ範囲(0より大)を検証し、未設定は許す。
//   B. 出荷 sourcelinks.yml が editor の検証を通る(GUIで開いた瞬間に赤くならない)。
//   C. 出荷 sourcelinks.yml の階梯4段すべてに yield-multiplier が入っている
//      (= 未設定=1.0で「階梯なのに素材効率が無印と同じ」へ退行していない)。
//   D. 入力欄は yield-multiplier キーだけを実体化し、空へ戻すとキーごと消える
//      (transfer-multiplier と取り違えない)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..");
const { validate } = require("../lib/schema");
const SHIPPED = path.resolve(ROOT, "../../fork-handoff/arspaper/fork/src/main/resources/sourcelinks.yml");

// ============================================================
// A. schema 検証
// ============================================================

test("yield-multiplier: 未設定/正の数は通り、0以下と非数値は弾く", () => {
  const withValue = (v) => ({ items: { volcanic_sourcelink_ii: { material: "FURNACE", "yield-multiplier": v } } });

  assert.deepEqual(validate("ars-sourcelinks", { items: { x: { material: "FURNACE" } } }), [],
    "未設定は既定1.0(無印と同じ)なので正常");
  assert.deepEqual(validate("ars-sourcelinks", withValue(1.5)), []);
  assert.deepEqual(validate("ars-sourcelinks", withValue(4)), []);

  for (const bad of [0, -2, "2.0"]) {
    const errors = validate("ars-sourcelinks", withValue(bad));
    assert.equal(errors.length, 1, `${JSON.stringify(bad)} は弾かれるべき: ${JSON.stringify(errors)}`);
    assert.match(errors[0], /yield-multiplier/,
      "エラー文が transfer-multiplier と取り違えられていない");
  }
});

test("transfer-multiplier と yield-multiplier は独立に検証される", () => {
  const errors = validate("ars-sourcelinks", {
    items: { x: { material: "FURNACE", "transfer-multiplier": 0, "yield-multiplier": 0 } }
  });
  assert.equal(errors.length, 2, `両方のキーが個別に報告されるべき: ${JSON.stringify(errors)}`);
});

// ============================================================
// B/C. 出荷 yml
// ============================================================

test("出荷 sourcelinks.yml が editor の検証を通る", () => {
  const data = YAML.parse(fs.readFileSync(SHIPPED, "utf8"));
  assert.deepEqual(validate("ars-sourcelinks", data), []);
});

test("出荷 sourcelinks.yml の階梯4段すべてに生成量倍率が入っている", () => {
  const data = YAML.parse(fs.readFileSync(SHIPPED, "utf8"));
  const missing = [];
  for (const [id, entry] of Object.entries(data.items)) {
    if (!/_(ii|iii|iv|v)$/.test(id)) continue;
    if (entry["yield-multiplier"] === undefined) missing.push(id);
  }
  assert.deepEqual(missing, [],
    "階梯なのに生成量倍率が無い = 燃料1個から得られるソースが無印と同じ(要件の未達)");
  assert.equal(Object.keys(data.items).filter((id) => /_(ii|iii|iv|v)$/.test(id)).length, 20,
    "5種 x 4段 = 20件の階梯が揃っている必要がある");
});

// ============================================================
// D. 入力欄
// ============================================================

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    style: {},
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    _listeners: {},
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener(type, fn) { (el._listeners[type] = el._listeners[type] || []).push(fn); },
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  if (attrs && "value" in attrs) el.value = attrs.value;
  Object.defineProperty(el, "innerHTML", { get() { return ""; }, set() { el.children = []; } });
  return el;
}

/** ソースリンクカードを描き、numberInput 呼び出し(ラベル付き)を記録する。 */
function renderCard(entry) {
  const calls = [];
  global.window = {};
  global.document = { getElementById: () => null, body: { appendChild() {} } };
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children])
        .forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  // ⚠ fieldRow(key, input, opts) は「input を評価してから fieldLabelEl(key,…) を呼ぶ」ので、
  // numberInput の時点ではまだキーが分からない。直後に来る fieldLabelEl でFIFOに紐付ける
  // (キーを取り違えると「隣のフィールドを見て緑になる」テストになる)。
  global.window.fieldLabelEl = (key, o) => {
    const pending = calls.find((c) => c.key === null);
    if (pending) pending.key = key;
    return makeEl("label", { text: key, labelOpts: o || {} });
  };
  global.window.numberInput = (value, onInput, o) => {
    calls.push({ key: null, value, onInput, opts: o });
    return makeEl("input", { value });
  };
  global.window.checkboxInput = (value) => makeEl("input", { type: "checkbox", value });
  global.window.textInput = (value) => makeEl("input", { value });
  global.window.richTextInput = (value) => makeEl("input", { value });
  global.window.listSelect = (cfg) => makeEl("span", { listCfg: cfg });
  global.window.materialInput = (value) => makeEl("span", { class: "material-suggest", value });
  global.window.materialHintEl = () => { const el = makeEl("span"); el.update = () => {}; return el; };
  global.window.buildTooltipPreview = () => ({ element: makeEl("div"), update: () => {} });
  global.window.collapsibleCard = (headChildren, bodyChildren) => {
    const card = makeEl("div", { class: "entry-card" });
    card.appendChild(global.window.h("div", { class: "entry-head" }, headChildren));
    card.appendChild(global.window.h("div", { class: "entry-body" }, bodyChildren));
    return card;
  };
  delete require.cache[require.resolve("../public/js/ars-source-forms.js")];
  require("../public/js/ars-source-forms.js");
  const working = { items: { volcanic_sourcelink_ii: entry } };
  global.window.buildSourceLinksForm(working);
  return { calls, entry: working.items.volcanic_sourcelink_ii };
}

test("yield-multiplier の入力欄がカードにあり、そのキーだけを書き換える", () => {
  const { calls, entry } = renderCard({ material: "FURNACE", "display-name": "v", lore: [] });
  const call = calls.find((c) => c.key === "yield-multiplier");
  assert.ok(call, "yield-multiplier の入力欄が描かれていない(ラベルキー一覧: "
    + calls.map((c) => c.key).join(", ") + ")");
  assert.equal(call.value, undefined, "未設定なら空で表示する(既定値を勝手に埋めない)");

  call.onInput(2.5);
  assert.equal(entry["yield-multiplier"], 2.5);
  assert.equal("transfer-multiplier" in entry, false,
    "転送レート倍率まで実体化してはいけない(開いて保存しただけでymlの意味が変わる事故)");

  call.onInput(null);
  assert.equal("yield-multiplier" in entry, false, "空へ戻したらキーごと消える");
});

test("既存の yield-multiplier 値は入力欄へそのまま渡る", () => {
  const { calls } = renderCard({
    material: "FURNACE", "display-name": "v2", lore: [],
    "transfer-multiplier": 2.0, "yield-multiplier": 1.5
  });
  const yieldCall = calls.find((c) => c.key === "yield-multiplier");
  const transferCall = calls.find((c) => c.key === "transfer-multiplier");
  assert.equal(yieldCall.value, 1.5);
  assert.equal(transferCall.value, 2.0, "2つの倍率が取り違えられていない");
});
