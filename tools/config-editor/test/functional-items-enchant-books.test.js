"use strict";

// 2026-08-14 実サーバ報告「回生とマナ関連のエンチャント本の儀式レシピが消えている」。
//
// 正体は items.yml 用の画面(schema: ars-recipes)が app.js で常に onlyEffects:true で構築され、
// items: セクションを1件も描画しないこと。yml にもゲーム内にも最初から在ったが、editor からは
// 見ることも編集することもできなかった。エンチャント本8件を functional-items.yml へ移し、
// 「特殊アイテム」画面に専用セクションを足して編集できるようにした件を固定する。
//
// ここで一番危険なのは二重定義: 儀式の登録キーはエントリIDそのものなので、items.yml と
// functional-items.yml の両方に同じIDが在ると後に読まれた方が勝ち、editor で直した側が
// 無言で効かなくなる。出荷ymlの実データで両方向を検査する。

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const YAML = require("yaml");

const CORE = require("../public/js/functional-items.js");

const ROOT = path.resolve(__dirname, "../../..");
const ARS_RESOURCES = path.join(ROOT, "fork-handoff/arspaper/fork/src/main/resources");
const FUNCTIONAL_ITEMS_SRC = path.join(__dirname, "../public/js/functional-items.js");

const EXPECTED_IDS = [
  "enchant_book_mana_regen_1", "enchant_book_mana_regen_2", "enchant_book_mana_regen_3",
  "enchant_book_mana_boost_1", "enchant_book_mana_boost_2", "enchant_book_mana_boost_3",
  "enchant_book_share", "enchant_book_soulbound"
];

function readArsYml(name) {
  const file = path.join(ARS_RESOURCES, name);
  if (!fs.existsSync(file)) return null;
  return YAML.parse(fs.readFileSync(file, "utf8"));
}

// fork のソースは .gitignore 除外なので、クリーンなクローンには存在しない。
// 「無いから空」で通すと検査ごと消えるため、理由を出して明示的にスキップする。
function skipIfForkAbsent(data) {
  if (data) return false;
  console.log("skip: fork-handoff/arspaper のソースがこのワークツリーに無い");
  return true;
}

test("ARS_ENCHANT_BOOK_IDS: 8件ちょうどで、全件に日本語ラベルがある", () => {
  assert.deepEqual(CORE.ARS_ENCHANT_BOOK_IDS.slice().sort(), EXPECTED_IDS.slice().sort());
  for (const id of CORE.ARS_ENCHANT_BOOK_IDS) {
    const label = CORE.ARS_ENCHANT_BOOK_LABELS[id];
    assert.ok(typeof label === "string" && label.length > 0, `${id} のラベルが無い`);
  }
});

test("エンチャント本のIDは機能アイテム7件・TF特殊アイテムと重複しない", () => {
  for (const id of CORE.ARS_ENCHANT_BOOK_IDS) {
    assert.ok(!CORE.FUNCTIONAL_ITEM_IDS.includes(id), `${id} が機能アイテム7件と重複している`);
    assert.ok(!CORE.TF_SPECIAL_ITEM_IDS.includes(id), `${id} が TF 特殊アイテムと重複している`);
  }
});

test("firstRitualRecipe: recipe: 単数・recipes: 配列の両方から儀式レシピを拾う", () => {
  assert.equal(CORE.firstRitualRecipe({ recipe: { method: "ritual", name: "x" } }).name, "x");
  assert.equal(CORE.firstRitualRecipe({ recipes: [{ method: "workbench" }, { method: "ritual", name: "y" }] }).name, "y");
  // method 大文字も儀式として拾う(yml は手編集され得る)。
  assert.equal(CORE.firstRitualRecipe({ recipe: { method: "RITUAL", name: "z" } }).name, "z");
});

test("firstRitualRecipe: 儀式レシピが無ければ null (作業台だけ/レシピ無し/壊れた形)", () => {
  assert.equal(CORE.firstRitualRecipe({ recipe: { method: "workbench" } }), null);
  // method 省略は workbench 既定なので儀式ではない。
  assert.equal(CORE.firstRitualRecipe({ recipe: {} }), null);
  assert.equal(CORE.firstRitualRecipe({}), null);
  assert.equal(CORE.firstRitualRecipe(null), null);
  assert.equal(CORE.firstRitualRecipe({ recipes: "not-a-list" }), null);
});

test("normalize は既存のエンチャント本エントリを落とさない(未知IDの温存)", () => {
  const out = CORE.normalizeFunctionalItemsData({
    items: { enchant_book_soulbound: { "display-name": "回生", recipe: { method: "ritual" } } }
  });
  assert.equal(out.items.enchant_book_soulbound["display-name"], "回生");
  // 7件の正典は空オブジェクトで補完される(従来どおり)。
  for (const id of CORE.FUNCTIONAL_ITEM_IDS) {
    assert.ok(out.items[id], `${id} が補完されていない`);
  }
});

test("normalize はエンチャント本を勝手に生成しない(空エントリを yml へ書き戻さない)", () => {
  const out = CORE.normalizeFunctionalItemsData({ items: {} });
  for (const id of CORE.ARS_ENCHANT_BOOK_IDS) {
    assert.ok(!Object.prototype.hasOwnProperty.call(out.items, id),
      `${id} の空エントリが作られている(レシピの無い抜け殻を出荷ymlへ書き戻してしまう)`);
  }
});

test("特殊アイテム画面がエンチャント本セクションを描画している", () => {
  const src = fs.readFileSync(FUNCTIONAL_ITEMS_SRC, "utf8");
  assert.ok(src.includes("buildEnchantBookSection(working)"),
    "buildFunctionalItemsForm がエンチャント本セクションを組み立てていない");
  assert.ok(/function buildEnchantBookSection/.test(src), "buildEnchantBookSection が未定義");
});

test("実データ: functional-items.yml にエンチャント本8件が儀式レシピとして揃っている", () => {
  const data = readArsYml("functional-items.yml");
  if (skipIfForkAbsent(data)) return;
  const items = data.items || {};

  for (const id of EXPECTED_IDS) {
    const entry = items[id];
    assert.ok(entry && typeof entry === "object", `${id} が functional-items.yml に無い`);
    const ritual = CORE.firstRitualRecipe(entry);
    assert.ok(ritual, `${id} に儀式レシピが無い`);
    // effect-type/effect-params を落とすと「儀式は成立するのに何も出ない」形で無言死する。
    assert.equal(ritual["effect-type"], "enchant_book", `${id} の effect-type が enchant_book でない`);
    assert.ok(ritual["effect-params"] && ritual["effect-params"].enchantment,
      `${id} の effect-params.enchantment が無い`);
    assert.ok(ritual["effect-params"].level != null, `${id} の effect-params.level が無い`);
    // editor で表示を編集する足場として display-name / lore を宣言しておく。
    assert.ok(typeof entry["display-name"] === "string" && entry["display-name"].trim() !== "",
      `${id} に display-name が無い`);
    assert.ok(Array.isArray(entry.lore) && entry.lore.length > 0, `${id} に lore が無い`);
  }
});

test("実データ: items.yml 側にエンチャント本は残っていない(二重定義は後勝ちで無言に化ける)", () => {
  const data = readArsYml("items.yml");
  if (skipIfForkAbsent(data)) return;
  for (const id of Object.keys(data.items || {})) {
    assert.ok(!id.startsWith("enchant_book_"),
      `items.yml に ${id} が残っている(functional-items.yml と同じ登録キー)`);
  }
});
