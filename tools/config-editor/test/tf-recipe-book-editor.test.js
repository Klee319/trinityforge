"use strict";

// D7 (2026-07-31): レシピ本へのTF/Arsレシピ開示トグル (progression/crafting-features.yml の
// recipe-book サブツリー) の editor ミラー。
//
// このテストが守っているもの:
// 1) normalizeCraftingFeaturesWorking の既定値が Java 側 CraftingFeaturesConfig#loadRecipeBook と
//    完全に一致する(どちらも true/true)。ここがずれると「editor で開いて保存しただけ」で
//    サーバ挙動が変わる = 気付けない設定ドリフトになる(既知の落とし穴)。
// 2) 明示的に false と書かれた値を normalize が true に戻さない(ロスレス)。
// 3) 「レシピ本」タブが存在し、custom: 素材のクリック配置が効かないという既知の限界を必ず表示する
//    (D7 の受け入れ条件「限界を javadoc かプレイヤー向け表示のどちらかで必ず伝える」の editor 側)。
// 4) lib/schema.js が boolean 以外/未知キーを弾き、出荷 yml がそのスキーマを通る。

const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const { validate } = require("../lib/schema.js");
const { readConfig } = require("../lib/yamlio.js");

const CRAFTING_FEATURES_PATH = path.join(
  __dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources",
  "progression", "crafting-features.yml"
);

function makeEl(tag, props) {
  return {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; }
  };
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
  global.window.checkboxInput = (value, onChange) => makeEl("input", { class: "checkbox", value, onChange });
  global.window.textInput = (value) => makeEl("input", { class: "text", value });
  global.window.selectInput = () => makeEl("select");
  global.window.listSelect = () => makeEl("div", { class: "list-select" });
  global.window.materialInput = () => makeEl("div", { class: "material-input" });
  global.window.MATERIALS = ["IRON_SWORD"];
  delete require.cache[require.resolve("../public/js/tf-crafting-features.js")];
  require("../public/js/tf-crafting-features.js");
}

function findAllText(el, out) {
  out = out || [];
  if (el && el.props && typeof el.props.text === "string") out.push(el.props.text);
  for (const c of (el && el.children) || []) findAllText(c, out);
  return out;
}

/** チェックボックス相当のスタブ要素を描画順(深さ優先・先行順)で集める。 */
function collectCheckboxes(el, out) {
  out = out || [];
  if (el && el.props && el.props.class === "checkbox" && typeof el.props.onChange === "function") {
    out.push(el);
  }
  for (const c of (el && el.children) || []) collectCheckboxes(c, out);
  return out;
}

/** 指定ラベルのタブボタンを押して、その本文が描画された状態にする。 */
function clickTab(el, label) {
  const stack = [el];
  while (stack.length) {
    const node = stack.pop();
    if (!node) continue;
    if (node.tag === "button" && typeof node.props.onclick === "function"
        && findAllText(node).includes(label)) {
      node.props.onclick();
      return true;
    }
    for (const c of node.children || []) stack.push(c);
  }
  return false;
}

// ---- 既定値ミラー ----

test("normalizeCraftingFeaturesWorking: recipe-book が無ければ Java と同じ既定値(true/true)を補う", () => {
  setupStubs();
  const working = {};
  window.normalizeCraftingFeaturesWorking(working);
  assert.deepEqual(working["recipe-book"], {
    "reveal-plugin-recipes": true,
    "hide-locked-recipes": true
  });
});

test("normalizeCraftingFeaturesWorking: 明示的な false を true に戻さない(ロスレス)", () => {
  setupStubs();
  const working = { "recipe-book": { "reveal-plugin-recipes": false, "hide-locked-recipes": false } };
  window.normalizeCraftingFeaturesWorking(working);
  assert.deepEqual(working["recipe-book"], {
    "reveal-plugin-recipes": false,
    "hide-locked-recipes": false
  });
});

test("normalizeCraftingFeaturesWorking: boolean 以外の値は既定値(true)へ矯正する", () => {
  setupStubs();
  const working = { "recipe-book": { "reveal-plugin-recipes": "yes", "hide-locked-recipes": null } };
  window.normalizeCraftingFeaturesWorking(working);
  assert.deepEqual(working["recipe-book"], {
    "reveal-plugin-recipes": true,
    "hide-locked-recipes": true
  });
});

test("ロスレス: recipe-book を補完しても他のサブツリーは変化しない", () => {
  setupStubs();
  const input = {
    "thread-slots": { "max-by-category": { armor: 5, weapon: 1, tool: 0, other: 0 } },
    "gated-catalog-recipes": { foo: "recipe:foo" }
  };
  const before = JSON.parse(JSON.stringify(input));
  window.normalizeCraftingFeaturesWorking(input);
  assert.deepEqual(input["thread-slots"], before["thread-slots"]);
  assert.deepEqual(input["gated-catalog-recipes"], before["gated-catalog-recipes"]);
});

// ---- UI ----

test("buildCraftingFeaturesForm: 「レシピ本」タブが存在する", () => {
  setupStubs();
  const result = window.buildCraftingFeaturesForm({});
  const texts = findAllText(result.element);
  assert.ok(texts.includes("レシピ本"), `タブ一覧: ${JSON.stringify(texts)}`);
});

test("レシピ本タブ: 2つのトグルと「索引として使う」限界の注意書きを表示する", () => {
  setupStubs();
  const result = window.buildCraftingFeaturesForm({});
  assert.ok(clickTab(result.element, "レシピ本"), "レシピ本タブのボタンが見つからない");
  const texts = findAllText(result.element);
  assert.ok(texts.includes("TF/Arsレシピをレシピ本に載せる"),
    `トグル1が無い: ${JSON.stringify(texts)}`);
  assert.ok(texts.includes("未解放レシピは隠す"), `トグル2が無い: ${JSON.stringify(texts)}`);
  assert.ok(texts.some((t) => t.includes("索引")),
    "レシピ本は索引用途であるという既知の限界の説明が無い");
  assert.ok(texts.some((t) => t.includes("custom:")),
    "custom: 素材はクリック配置でクラフトできないという説明が無い");
});

test("レシピ本タブ: チェックボックスの onChange が working へ boolean を書く", () => {
  setupStubs();
  const working = {};
  const result = window.buildCraftingFeaturesForm(working);
  assert.ok(clickTab(result.element, "レシピ本"));
  const boxes = collectCheckboxes(result.element);
  assert.equal(boxes.length, 2, `チェックボックスが2つでない: ${boxes.length}`);
  // 描画順は reveal → hide。
  boxes[1].props.onChange(false);
  assert.equal(working["recipe-book"]["hide-locked-recipes"], false);
});

test("醸造解放セクション: 強制開始ではなく「投入を弾く」挙動とホッパー詰まりを説明する", () => {
  setupStubs();
  const el = window.buildCraftingFeaturesBrewSection({});
  const texts = findAllText(el);
  assert.ok(!texts.some((t) => t.includes("強制開始")),
    "D10 で醸造タイマーの強制開始は削除された(PotionMix 登録方式へ移行)ので、説明文が stale");
  assert.ok(texts.some((t) => t.includes("投入")), "投入自体が弾かれるという説明が無い");
  assert.ok(texts.some((t) => t.includes("ホッパー")), "ホッパーで詰まるという注意書きが無い");
  // D10 レビュー指摘#1(b) (2026-07-31): 判定は「近くのプレイヤー」ではなくスタンドの所有者。
  // ホッパー自動化の成立条件が変わったので、注意書きも新しい条件を書いていること。
  assert.ok(texts.some((t) => t.includes("所有者")),
    "ホッパー経由の判定が「醸造台に記録された所有者」であるという説明が無い");
  assert.ok(texts.some((t) => t.includes("同じ (ベース, 材料)")),
    "同じ組を2グループに書くと片方が永久に作れないという注意書きが無い");
});

// ---- スキーマ ----

test("tf-crafting-features: recipe-book の boolean はエラーなし", () => {
  assert.deepEqual(validate("tf-crafting-features", {
    "recipe-book": { "reveal-plugin-recipes": true, "hide-locked-recipes": false }
  }), []);
  assert.deepEqual(validate("tf-crafting-features", { "recipe-book": {} }), []);
});

test("tf-crafting-features: recipe-book の非boolean/未知キー/非マップはエラー", () => {
  assert.equal(validate("tf-crafting-features", {
    "recipe-book": { "reveal-plugin-recipes": "true" }
  }).length, 1);
  assert.equal(validate("tf-crafting-features", {
    "recipe-book": { "reveal-plugin-recipe": true }
  }).length, 1);
  assert.equal(validate("tf-crafting-features", { "recipe-book": [] }).length, 1);
});

// ---- brew-unlocks の重複した (ベース, 材料) — D10 レビュー指摘#2 (2026-07-31) ----
// 重複を書くと Paper の customMixes も Java 側リスナーも「先に一致した1件」で確定するので、
// もう一方のグループのポーションは永久に作れない(実害: Lv80 の上位段が Lv60 に食われていた)。
// Java 側は起動時に要求レベルの高い方を残して WARNING を出すが、起動ログを見ないと気づけない。

test("tf-crafting-features: 同じ (ベース, 材料) を2グループに書くとエラー", () => {
  const errors = validate("tf-crafting-features", {
    "brew-unlocks": {
      "healthboost-haste": { potions: [{ base: "THICK", ingredient: "GOLDEN_CARROT", result: {} }] },
      "healthboost-haste-2": { potions: [{ base: "THICK", ingredient: "GOLDEN_CARROT", result: {} }] }
    }
  });
  assert.equal(errors.length, 1, `重複が検出されていない: ${JSON.stringify(errors)}`);
  assert.ok(errors[0].includes("healthboost-haste"), errors[0]);
});

test("tf-crafting-features: ベースが違えば同じ材料でもエラーにならない(段の正しい分け方)", () => {
  assert.deepEqual(validate("tf-crafting-features", {
    "brew-unlocks": {
      lower: { potions: [{ base: "THICK", ingredient: "GOLDEN_CARROT", result: {} }] },
      upper: { potions: [{ base: "MUNDANE", ingredient: "GOLDEN_CARROT", result: {} }] }
    }
  }), []);
});

test("tf-crafting-features: 重複判定は綴りの揺れ(大小/minecraft:/custom:)を吸収する", () => {
  const errors = validate("tf-crafting-features", {
    "brew-unlocks": {
      a: { potions: [{ base: "THICK", ingredient: "SUGAR", result: {} }] },
      b: { potions: [{ base: " thick ", ingredient: "minecraft:sugar", result: {} }] },
      c: { potions: [{ base: "THICK", ingredient: "custom:Hoglin_Tusk", result: {} }] },
      d: { potions: [{ base: "THICK", ingredient: "custom:hoglin_tusk", result: {} }] }
    }
  });
  assert.equal(errors.length, 2, `綴り違いの重複を見逃している: ${JSON.stringify(errors)}`);
});

test("出荷 crafting-features.yml: recipe-book を宣言し、スキーマ検証を通る", (t) => {
  const cfg = readConfig(CRAFTING_FEATURES_PATH);
  if (!cfg.exists) {
    t.skip("crafting-features.yml が見つからない環境ではスキップ");
    return;
  }
  assert.deepEqual(validate("tf-crafting-features", cfg.data), []);
  assert.deepEqual(cfg.data["recipe-book"], {
    "reveal-plugin-recipes": true,
    "hide-locked-recipes": true
  }, "出荷値は Java の既定値と一致させる(ずれると reload で挙動が変わる)");
});
