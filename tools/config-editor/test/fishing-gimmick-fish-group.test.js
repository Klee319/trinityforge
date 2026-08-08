"use strict";

// public/js/tf-lifestyle-forms.js の window.buildFishingGimmickForm を対象にした、
// 2026-07-25 fishing-gimmick.yml 仕様変更2件の固定テスト。
//   1) fishing.groups.fish (T1新設): 未設定のままフォームを開いて保存しても fish キーが
//      書き込まれないこと(後方互換=バニラ釣果維持)。ユーザーが明示的に「設定する」を押した
//      場合のみ treasure/junk と同じ { categories: {...} } 構造で保存されること。
//   2) fish-sell.prices (T2): キーがMaterial限定でなくなり、ドロップテーブル entries[].item と
//      同じ materialInput({ allowCustom: true }) を使うこと。既存のMaterialキー4件、および
//      カスタムアイテムIDキーの両方が壊れず編集・保存できること。
//
// ブラウザ用 IIFE (window.h 前提) なので drop-table-logic.test.js / mob-forms-logic.test.js と
// 同じ手法で window 一式を最小スタブして読み込む。実際のCSS/レイアウトはテストしない。
// スタブの h() は本物のDOMではなく { tag, attrs, children, appendChild, ... } の簡易ツリーを
// 作る。attrs.onclick / attrs.onchange はイベント発火用に _onclick / _onchange として保持する。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeFakeEl(tag, attrs) {
  const el = {
    tag,
    attrs: attrs || {},
    children: [],
    _onclick: attrs && typeof attrs.onclick === "function" ? attrs.onclick : null,
    _onchange: attrs && typeof attrs.onchange === "function" ? attrs.onchange : null,
    value: attrs && attrs.value != null ? attrs.value : "",
    textContent: attrs && attrs.text != null ? attrs.text : "",
    appendChild(child) {
      if (child !== null && child !== undefined && child !== false) el.children.push(child);
      return child;
    },
    querySelector() { return null; },
    addEventListener() {}
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

global.window = global.window || {};
global.window.h = function h(tag, attrs, children) {
  const el = makeFakeEl(tag, attrs);
  if (children !== undefined && children !== null) {
    const arr = Array.isArray(children) ? children : [children];
    arr.forEach((c) => el.appendChild(c));
  }
  return el;
};
global.window.fieldLabelEl = () => makeFakeEl("div");
global.window.checkboxInput = () => makeFakeEl("input");
global.window.collapsibleCard = (head, body) => {
  const el = makeFakeEl("div");
  (Array.isArray(head) ? head : [head]).forEach((c) => el.appendChild(c));
  (Array.isArray(body) ? body : [body]).forEach((c) => el.appendChild(c));
  return el;
};

// numberInput/textInput/materialInput は onInput を呼べるよう trigger を露出する。
const materialInputCalls = [];
global.window.numberInput = (value, onInput) => {
  const el = makeFakeEl("input", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
global.window.textInput = (value, onInput) => {
  const el = makeFakeEl("input", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
global.window.textInputOnCommit = global.window.textInput;
global.window.materialInput = (value, listId, onInput, opts) => {
  materialInputCalls.push({ value, opts: opts || {} });
  const el = makeFakeEl("span", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
// E-1 (2026-07-25): fishing.ocean-biomes が textInput から listSelect ベースの biomeSelect へ
// 変更されたため、他のスタブと同じ「trigger で onChange を呼べる」最小スタブを追加する。
global.window.listSelect = (cfg) => {
  const el = makeFakeEl("span", { value: cfg.value });
  el.trigger = (v) => { if (typeof cfg.onChange === "function") cfg.onChange(v); };
  return el;
};

require("../public/js/tf-lifestyle-forms.js");

/** wrap 以下を再帰的に辿り、attrs.text が一致する最初の要素を返す。 */
function findByText(root, text) {
  if (!root || typeof root !== "object") return null;
  if (root.attrs && root.attrs.text === text) return root;
  for (const c of root.children || []) {
    const found = findByText(c, text);
    if (found) return found;
  }
  return null;
}

function baselineFishingData() {
  // fishing-gimmick.yml の実データ相当(出荷ymlどおり groups.fish は未設定)。
  return {
    "junk-materials": ["LEATHER"],
    "treasure-materials": ["NAME_TAG"],
    "fish-sell": {
      prices: {
        COD: 2.0,
        SALMON: 3.0,
        TROPICAL_FISH: 6.0,
        PUFFERFISH: 4.0
      },
      "max-sells-per-minute": 20
    },
    "xp-bottle-store": { "store-amount": 100, "return-rate": 1.0 },
    fishing: {
      "luck-per-level": 0.005,
      "bonus-per-level": 0.02,
      "ocean-biomes": ["ocean"],
      "group-ratio": { "treasure-percent": 5.0, "junk-percent": 10.0 },
      groups: {
        treasure: { categories: { treasure_vanilla: { "display-name": "宝", entries: [{ item: "NAME_TAG", weight: 1, amount: 1 }] } } },
        junk: { categories: { junk_vanilla: { "display-name": "ゴミ", entries: [{ item: "LEATHER", weight: 1, amount: 1 }] } } }
      }
    }
  };
}

test("fishing.groups.fish: 未設定のままフォームを開いて保存しても fish キーが書き込まれない(後方互換)", () => {
  const data = baselineFishingData();
  const form = window.buildFishingGimmickForm(data);
  const saved = form.getData();
  assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing.groups, "fish"), false);
  // treasure/junk は既存構造のまま維持される(壊されない)。
  assert.deepEqual(saved.fishing.groups.treasure.categories.treasure_vanilla.entries, [{ item: "NAME_TAG", weight: 1, amount: 1 }]);
});

test("fishing.groups.fish: groups.fish が最初から未定義(groupsキー自体が無い)場合も開くだけでは作られない", () => {
  const data = baselineFishingData();
  delete data.fishing.groups; // groups 自体が無い最小構成
  data.fishing.groups = { treasure: { categories: {} }, junk: { categories: {} } };
  const form = window.buildFishingGimmickForm(data);
  const saved = form.getData();
  assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing.groups, "fish"), false);
});

test("fishing.groups.fish: 「+ 魚グループを設定する」を押すと treasure/junk と同じ { categories: {} } 構造で作られる", () => {
  const data = baselineFishingData();
  const form = window.buildFishingGimmickForm(data);
  const enableBtn = findByText(form.element, "+ 魚グループを設定する");
  assert.ok(enableBtn, "「+ 魚グループを設定する」ボタンが見つかること");
  enableBtn._onclick();
  const saved = form.getData();
  assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing.groups, "fish"), true);
  assert.deepEqual(saved.fishing.groups.fish, { categories: {} });
});

test("fishing.groups.fish: 設定後にカテゴリを追加すると treasure/junk と同じ形状 (display-name/entries[].item,weight,amount) で保存される", () => {
  const data = baselineFishingData();
  data.fishing.groups.fish = { categories: {} };
  const form = window.buildFishingGimmickForm(data);
  const addCategoryBtns = [];
  (function collect(node) {
    if (!node || typeof node !== "object") return;
    if (node.attrs && node.attrs.text === "+ カテゴリ追加") addCategoryBtns.push(node);
    for (const c of node.children || []) collect(c);
  })(form.element);
  // treasure/junk/fish の3つ分の「+ カテゴリ追加」ボタンがあるはず。fish 用(最後)を使う。
  assert.equal(addCategoryBtns.length, 3);
  addCategoryBtns[addCategoryBtns.length - 1]._onclick();
  const saved = form.getData();
  const catIds = Object.keys(saved.fishing.groups.fish.categories);
  assert.equal(catIds.length, 1);
  const cat = saved.fishing.groups.fish.categories[catIds[0]];
  assert.deepEqual(cat, { "display-name": catIds[0], entries: [] });
});

test("fishing.groups.fish: 解除ボタンで fish キーが削除される(バニラ釣果へ戻る)", () => {
  const data = baselineFishingData();
  data.fishing.groups.fish = { categories: { x: { "display-name": "x", entries: [] } } };
  const form = window.buildFishingGimmickForm(data);
  const disableBtn = findByText(form.element, "設定を解除する(バニラ釣果に戻す)");
  assert.ok(disableBtn, "解除ボタンが見つかること");
  disableBtn._onclick();
  const saved = form.getData();
  assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing.groups, "fish"), false);
});

test("fish-sell.prices: 既存のMaterialキー4件がそのまま編集・保存できる", () => {
  const data = baselineFishingData();
  materialInputCalls.length = 0;
  const form = window.buildFishingGimmickForm(data);
  const saved = form.getData();
  assert.deepEqual(saved["fish-sell"].prices, {
    COD: 2.0, SALMON: 3.0, TROPICAL_FISH: 6.0, PUFFERFISH: 4.0
  });
  // 4件それぞれが materialInput({ allowCustom: true }) で描画されていること
  // (ドロップテーブル entries[].item と同一の語彙入力部品であることの根拠)。
  const keys = ["COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH"];
  for (const k of keys) {
    const call = materialInputCalls.find((c) => c.value === k);
    assert.ok(call, `materialInput が ${k} を描画すること`);
    assert.equal(call.opts.allowCustom, true, `${k} は allowCustom:true で描画されること`);
  }
});

test("fish-sell.prices: カスタムアイテムIDのキーを追加して保存できる(Material限定ではない)", () => {
  const data = baselineFishingData();
  const form = window.buildFishingGimmickForm(data);
  const saved = form.getData();
  // ドロップテーブルの item: と同じ語彙 (custom:<id> 形式) を直接キーとして設定できることを確認。
  saved["fish-sell"].prices["custom:tf_gacha_fish"] = 15;
  assert.equal(saved["fish-sell"].prices["custom:tf_gacha_fish"], 15);

  // 再度フォームを構築(再ロード相当)しても、カスタムキーは壊れず維持される。
  const form2 = window.buildFishingGimmickForm(saved);
  const saved2 = form2.getData();
  assert.equal(saved2["fish-sell"].prices["custom:tf_gacha_fish"], 15);
});

test("fish-sell.prices: 「+ 追加」ボタンでキーが増える(空プロファイルからでも壊れない)", () => {
  const data = baselineFishingData();
  data["fish-sell"].prices = {};
  const form = window.buildFishingGimmickForm(data);
  const addBtn = findByText(form.element, "+ 追加");
  assert.ok(addBtn, "「+ 追加」ボタンが見つかること");
  addBtn._onclick();
  const saved = form.getData();
  assert.deepEqual(Object.keys(saved["fish-sell"].prices), ["COD"]);
});
