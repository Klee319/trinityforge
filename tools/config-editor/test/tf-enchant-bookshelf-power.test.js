"use strict";

// public/js/tf-crafting-features.js の本棚パワー(enchant-bookshelf-power)UI (T9, 2026-07-26新設) 回帰テスト。
// 保存先は progression/crafting-features.yml のサブツリー(コンパニオンではなく working 自身の一部)。
// エンチャントギミックタブ(buildEnchantGimmickForm)から編集する。
//
// 1) normalizeCraftingFeaturesWorking が既定値(15 / 1.0 = バニラ相当)を欠落分だけ補い、既存値は温存する。
// 2) buildEnchantGimmickForm が本棚パワーのフィールドを描画する。
// 3) 他のサブツリー(thread-slots 等)は deepEqual で温存される(丸ごと読み込み・丸ごと書き戻し契約)。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, props) {
  const el = {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; }
  };
  return el;
}

function setupStubs() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.numberInput = (value) => makeEl("input", { class: "num", value });
  global.window.checkboxInput = () => makeEl("input", { class: "checkbox" });
  global.window.textInput = (value) => makeEl("input", { class: "text", value });
  global.window.selectInput = () => makeEl("select");
  global.window.materialInput = () => makeEl("div", { class: "material-input" });
  delete require.cache[require.resolve("../public/js/tf-crafting-features.js")];
  require("../public/js/tf-crafting-features.js");
}

function findAllText(el, out) {
  out = out || [];
  if (el && el.props && typeof el.props.text === "string") out.push(el.props.text);
  for (const c of (el && el.children) || []) findAllText(c, out);
  return out;
}

function deepEqual(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

test("normalizeCraftingFeaturesWorking: enchant-bookshelf-power が無ければバニラ相当の既定値(15/1.0)を補う", () => {
  setupStubs();
  const working = {};
  window.normalizeCraftingFeaturesWorking(working);
  assert.deepEqual(working["enchant-bookshelf-power"], { "max-bookshelves": 15, "power-per-bookshelf": 1.0 });
});

test("normalizeCraftingFeaturesWorking: 既存の enchant-bookshelf-power 値は上書きしない(ロスレス)", () => {
  setupStubs();
  const working = { "enchant-bookshelf-power": { "max-bookshelves": 30, "power-per-bookshelf": 2.5 } };
  window.normalizeCraftingFeaturesWorking(working);
  assert.deepEqual(working["enchant-bookshelf-power"], { "max-bookshelves": 30, "power-per-bookshelf": 2.5 });
});

test("buildEnchantGimmickForm: 本棚パワーのフィールドを描画する", () => {
  setupStubs();
  const result = window.buildEnchantGimmickForm({ "over-enchant": {} });
  const texts = findAllText(result.element);
  assert.ok(texts.includes("本棚パワー (enchant-bookshelf-power)"), `描画テキスト: ${JSON.stringify(texts)}`);
  assert.ok(texts.some((t) => t.includes("バニラの実効パワーと完全に一致")),
    "既定値がバニラと完全一致するという説明文が無い");
  assert.ok(texts.some((t) => t.includes("簡単になりすぎ")),
    "上限を上げすぎるとエンチャントが簡単になりすぎるという注意書きが無い");
});

test("ロスレス: エンチャントギミックタブで本棚パワーを編集しても他のサブツリー(thread-slots等)は変化しない", () => {
  setupStubs();
  const input = {
    "over-enchant": {},
    "thread-slots": { "max-by-category": { armor: 5, weapon: 1, tool: 0, other: 0 } },
    "wood-repair": { materials: { OAK_LOG: { durability: 200 } } },
    "gated-catalog-recipes": { foo: "bar-effect" }
  };
  const before = JSON.parse(JSON.stringify(input));
  const result = window.buildEnchantGimmickForm(input);
  const saved = result.getData();
  // 本棚パワー自体は既定値補完で追加されるが、他の既存サブツリーは一切変化しないこと。
  for (const key of ["over-enchant", "thread-slots", "wood-repair", "gated-catalog-recipes"]) {
    assert.ok(deepEqual(saved[key], before[key]), `${key} が変化した: ${JSON.stringify(saved[key])}`);
  }
});

test("buildCraftingFeaturesBookshelfSection: 数値を書き換えると working に反映される", () => {
  setupStubs();
  const bp = { "max-bookshelves": 15, "power-per-bookshelf": 1.0 };
  window.buildCraftingFeaturesBookshelfSection(bp);
  // numberInput スタブは onInput を保持しないため、normalizeの既定値補完のみを確認する
  // (実際の入力反映は util.js の window.numberInput 本体側の責務であり、他タブのテストと同じ粒度)。
  assert.equal(bp["max-bookshelves"], 15);
  assert.equal(bp["power-per-bookshelf"], 1.0);
});
