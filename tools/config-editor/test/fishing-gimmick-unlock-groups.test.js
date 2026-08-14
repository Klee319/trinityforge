"use strict";

// public/js/tf-lifestyle-forms.js の window.buildFishingGimmickForm を対象にした、
// 2026-08-15 新設「機能解放追加用テーブル」(fishing.unlock-groups、任意キー) の固定テスト。
//   fishing.unlock-groups.<treasure|junk|fish> は groups.<id> と完全に同一の Category スキーマだが、
//   groups.fish (fishGroupEditor) と同じ「設定するまで書き込まない」lazy-touch 方式を3グループ全てに
//   適用する ── フォームを開いただけで fishing.unlock-groups:{} が yml へ書き戻ってはいけない。
//
// ブラウザ用 IIFE (window.h 前提) なので fishing-gimmick-fish-group.test.js と同じ手法で
// window 一式を最小スタブして読み込む。実際のCSS/レイアウトはテストしない。

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
  const el = makeFakeEl("span", { value });
  el.trigger = (v) => onInput(v);
  return el;
};
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

/** wrap 以下を再帰的に辿り、attrs.text が一致する全要素を返す。 */
function findAllByText(root, text) {
  const out = [];
  (function walk(node) {
    if (!node || typeof node !== "object") return;
    if (node.attrs && node.attrs.text === text) out.push(node);
    for (const c of node.children || []) walk(c);
  })(root);
  return out;
}

function baselineFishingData() {
  // fishing-gimmick.yml の実データ相当(出荷ymlどおり unlock-groups は未設定=キー自体が無い)。
  return {
    "junk-materials": ["LEATHER"],
    "treasure-materials": ["NAME_TAG"],
    "fish-sell": {
      prices: { COD: 2.0, SALMON: 3.0, TROPICAL_FISH: 6.0, PUFFERFISH: 4.0 },
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

test("unlock-groups: 未設定(キー自体が無い)状態でフォームを開いて保存しても unlock-groups キーが生えない(往復ロスレス)", () => {
  const data = baselineFishingData();
  assert.equal(Object.prototype.hasOwnProperty.call(data.fishing, "unlock-groups"), false);
  const form = window.buildFishingGimmickForm(data);
  const saved = form.getData();
  assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing, "unlock-groups"), false);
  // groups は既存構造のまま維持される(壊されない)。
  assert.deepEqual(saved.fishing.groups.treasure.categories.treasure_vanilla.entries, [{ item: "NAME_TAG", weight: 1, amount: 1 }]);
});

for (const [groupId, groupLabelJa] of [["treasure", "宝"], ["junk", "ゴミ"], ["fish", "魚"]]) {
  test(`unlock-groups.${groupId}: 「+ ${groupLabelJa}の追加テーブルを設定する」を押すと groups と同じ { categories: {} } 構造で作られる`, () => {
    const data = baselineFishingData();
    const form = window.buildFishingGimmickForm(data);
    const enableBtn = findByText(form.element, `+ ${groupLabelJa}の追加テーブルを設定する`);
    assert.ok(enableBtn, `「+ ${groupLabelJa}の追加テーブルを設定する」ボタンが見つかること`);
    enableBtn._onclick();
    const saved = form.getData();
    assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing["unlock-groups"], groupId), true);
    assert.deepEqual(saved.fishing["unlock-groups"][groupId], { categories: {} });
    // 他の2グループは未設定のまま(3枚が独立して lazy-touch されること)。
    const otherIds = ["treasure", "junk", "fish"].filter((id) => id !== groupId);
    for (const otherId of otherIds) {
      assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing["unlock-groups"], otherId), false);
    }
  });
}

test("unlock-groups.treasure: 設定後にカテゴリを追加すると groups と同じ形状 (display-name/entries[].item,weight,amount) で保存される", () => {
  const data = baselineFishingData();
  data.fishing["unlock-groups"] = { treasure: { categories: {} } };
  const form = window.buildFishingGimmickForm(data);
  // groups.treasure/groups.junk(常時有効) 2件 + groups.fish(未設定=ボタン無し) + unlock-groups.treasure
  // (このテストで有効化済み) 1件 = 計3件の「+ カテゴリ追加」ボタン。unlock-groups.junk/fish は
  // 未設定のままなので「+ カテゴリ追加」自体が描画されない(代わりに有効化ボタンが出る)。
  const addCategoryBtns = findAllByText(form.element, "+ カテゴリ追加");
  assert.equal(addCategoryBtns.length, 3);
  addCategoryBtns[addCategoryBtns.length - 1]._onclick();
  const saved = form.getData();
  const catIds = Object.keys(saved.fishing["unlock-groups"].treasure.categories);
  assert.equal(catIds.length, 1);
  const cat = saved.fishing["unlock-groups"].treasure.categories[catIds[0]];
  assert.deepEqual(cat, { "display-name": catIds[0], entries: [] });
});

test("unlock-groups.junk: 既存データを保持したままフォームを開いて保存しても内容が変わらない(往復ロスレス)", () => {
  const data = baselineFishingData();
  data.fishing["unlock-groups"] = {
    junk: { categories: { rare_junk: { "display-name": "レア屑", entries: [{ item: "STRING", weight: 2, amount: 1 }] } } }
  };
  const form = window.buildFishingGimmickForm(data);
  const saved = form.getData();
  assert.deepEqual(saved.fishing["unlock-groups"], {
    junk: { categories: { rare_junk: { "display-name": "レア屑", entries: [{ item: "STRING", weight: 2, amount: 1 }] } } }
  });
});

test("unlock-groups.fish: 解除ボタンで fish キーが削除され、unlock-groups が空になるとキーごと消える", () => {
  const data = baselineFishingData();
  data.fishing["unlock-groups"] = { fish: { categories: { x: { "display-name": "x", entries: [] } } } };
  const form = window.buildFishingGimmickForm(data);
  const disableBtn = findByText(form.element, "設定を解除する(魚の追加テーブルなし)");
  assert.ok(disableBtn, "解除ボタンが見つかること");
  disableBtn._onclick();
  const saved = form.getData();
  assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing, "unlock-groups"), false);
});

test("unlock-groups.junk: 他グループが有効なまま解除しても、解除した分だけ消えて unlock-groups キー自体は残る", () => {
  const data = baselineFishingData();
  data.fishing["unlock-groups"] = {
    treasure: { categories: {} },
    junk: { categories: { x: { "display-name": "x", entries: [] } } }
  };
  const form = window.buildFishingGimmickForm(data);
  const disableBtn = findByText(form.element, "設定を解除する(ゴミの追加テーブルなし)");
  assert.ok(disableBtn, "解除ボタンが見つかること");
  disableBtn._onclick();
  const saved = form.getData();
  assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing["unlock-groups"], "junk"), false);
  assert.equal(Object.prototype.hasOwnProperty.call(saved.fishing["unlock-groups"], "treasure"), true);
});
