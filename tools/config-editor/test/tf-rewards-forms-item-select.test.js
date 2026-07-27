"use strict";

// tf-rewards-forms.js 修正1 (2026-07-27) の回帰テスト。
//
// 背景: アチーブメント/図鑑の「付与アイテム (rewards.items)」欄は、内部IDそのままを
// datalist付きtextInputで表示していた。ユーザーからは日本語表示名が見えず、
// カタログID/バニラMaterial名を暗記していないと選べない状態だった。
//
// 修正: itemIdInput を util.js の window.itemRefSelect (listSelect ベースの共通ヘルパー)
// 経由に置き換えた。表示は「表示名 (ID)」、保存値は今までどおりID文字列そのもの。
// 候補に無い値(手書きID・未知のカタログID)は消さず、先頭候補として残す。
//
// listSelect 自体のDOM描画(開閉/位置計算/キーボード操作)は util.js の既存範囲であり、
// ここでは itemIdInput -> itemRefSelect が listSelect へ渡す cfg (options/value/allowCustom)
// の中身と、onChange で保存される値がID(ラベルではない)であることを検証する。

const test = require("node:test");
const assert = require("node:assert/strict");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
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

// util.js を実物のまま読み込み、window.h だけ document 不要の軽量版へ差し替え、
// window.listSelect は cfg をそのまま捕捉するスタブへ差し替える。
// (window.itemRefSelect / window.fieldLabelEl / window.numberInput / window.textInput は
//  util.js の実装をそのまま使う — いずれも document.* を直接叩かない純粋な h() ラッパーのため。)
function setupDom() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.alert = () => {};

  delete require.cache[require.resolve("../public/js/util.js")];
  require("../public/js/util.js");

  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };

  const captured = [];
  global.window.listSelect = (cfg) => {
    const el = makeEl("span", { value: cfg.value });
    el.cfg = cfg;
    captured.push(cfg);
    return el;
  };

  global.window.MATERIALS = ["DIAMOND", "IRON_INGOT"];

  delete require.cache[require.resolve("../public/js/tf-rewards-forms.js")];
  require("../public/js/tf-rewards-forms.js");

  return captured;
}

function makeAchievementData(itemId) {
  return {
    achievements: {
      ach1: {
        "display-name": "テスト称号",
        trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
        broadcast: false,
        rewards: {
          special: [], commands: [],
          items: [{ id: itemId, amount: 2 }],
          "job-exp": [], "permanent-buffs": {}
        }
      }
    }
  };
}

test("付与アイテムのセレクト: カタログ候補が表示名(primary)+ID(secondary)で候補に出る", () => {
  const captured = setupDom();
  const data = makeAchievementData("unknown_hand_item");
  const catalogCandidates = [{ id: "diamond_sword_plus", label: "ダイヤの剣+" }];

  global.window.buildAchievementsForm(data, { catalogCandidates });

  const itemCfgs = captured.filter((c) => c.className === "reward-item-id-input");
  assert.equal(itemCfgs.length, 1, "付与アイテム行のセレクトが1つ描画されているはず");
  const cfg = itemCfgs[0];

  const catalogOpt = cfg.options.find((o) => o.value === "diamond_sword_plus");
  assert.ok(catalogOpt, "カタログ候補が options に含まれていない(内部IDのまま出ている)");
  assert.equal(catalogOpt.primary, "ダイヤの剣+");
  assert.equal(catalogOpt.secondary, "diamond_sword_plus");

  assert.ok(cfg.options.some((o) => o.value === "DIAMOND"), "バニラMaterialが候補に出ていない");
});

test("付与アイテムのセレクト: 候補に無い手書きIDは消えず先頭候補として残る", () => {
  const captured = setupDom();
  const data = makeAchievementData("unknown_hand_item");

  global.window.buildAchievementsForm(data, { catalogCandidates: [] });

  const cfg = captured.filter((c) => c.className === "reward-item-id-input")[0];
  assert.equal(cfg.value, "unknown_hand_item");
  const unknownOpt = cfg.options.find((o) => o.value === "unknown_hand_item");
  assert.ok(unknownOpt, "候補に無い既存IDが options から消えている");
  assert.equal(unknownOpt.primary, "unknown_hand_item");
  assert.equal(cfg.options[0].value, "unknown_hand_item", "手書きIDは候補の先頭に差し込まれるはず");
  assert.equal(cfg.allowCustom, true, "allowCustomがtrueでないと手入力の余地が消える");
  assert.match(cfg.customPlaceholder, /直接入力/);
});

test("付与アイテムのセレクト: 選択後も保存値はID文字列のまま(表示名で上書きされない)", () => {
  const captured = setupDom();
  const data = makeAchievementData("");
  const catalogCandidates = [{ id: "diamond_sword_plus", label: "ダイヤの剣+" }];

  global.window.buildAchievementsForm(data, { catalogCandidates });
  const cfg = captured.filter((c) => c.className === "reward-item-id-input")[0];

  cfg.onChange("diamond_sword_plus");

  assert.equal(data.achievements.ach1.rewards.items[0].id, "diamond_sword_plus",
    "保存値がID(生値)のままになっていない");
});
