"use strict";

// ---------------------------------------------------------------------------
// 2026-08-13 実サーバ報告の回帰テスト:
//   「アイテムカタログで2つ目のレシピを登録しようとすると1つ目のレシピが消える」
//
// 真因: レシピ編集UI (forms.js の renderCatalogRecipeSection) は
// 「0件=キーなし / 1件=recipe: / 2件以上=recipes:」という catalog.yml (TF本体) の正規形へ
// 書き戻す。TF本体の ItemCatalogConfig は recipes: を読むのでこれで正しかったが、
// **ArsPaper 側の UnifiedRecipeLoader は recipe:(単数)しか読まなかった**ため、
// 同じUIを流用している Ars 系の画面(素材/魔導書/ソース/機能アイテム)で2件目を足すと、
// 正規形が recipes: へ切り替わった瞬間にレシピが丸ごと Java から見えなくなっていた。
// 素材タブに至っては書き戻し先が entryLike.recipe しか読み戻さず、**画面上でも1件目が消えた**。
//
// 対策(ユーザー指示「素材以外にも儀式を使うアイテムはあるのだから区別する理由が無い。統合すべき」):
// UI を制限するのではなく **読み取り側を統合**した。UnifiedRecipeLoader が recipe: と recipes: の
// 両方を読み、2件目以降は登録キーだけ一意化する(結果アイテムIDは元のまま)。
// これでレシピ編集UIは全画面で完全に同じ挙動になる。
// ---------------------------------------------------------------------------

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");
const FORK_JAVA = path.resolve(
  ROOT, "..", "..", "fork-handoff", "arspaper", "fork", "src", "main", "java",
  "com", "arspaper", "recipe", "UnifiedRecipeLoader.java");

// ---------------------------------------------------------------------------
// 1. UI 部品側: レシピ数の上限機構そのものを持たない(全画面が同じ挙動)
// ---------------------------------------------------------------------------

test("forms.js: renderCatalogRecipeSection はレシピ数の上限を持たない(「+ レシピを追加」を常に出す)", () => {
  const src = JS("forms.js");
  const start = src.indexOf("function renderCatalogRecipeSection(");
  assert.ok(start > 0, "renderCatalogRecipeSection が見つからない");
  const body = src.slice(start, src.indexOf("\n  // 1レシピ分の編集フォーム", start));
  assert.ok(!/maxRecipes/.test(body),
    "レシピ数の上限が復活している(ArsPaper 側も recipes: を読むので画面ごとに挙動を変えない)");
  assert.match(body, /\+ レシピを追加/, "追加ボタンが無い");
});

// ---------------------------------------------------------------------------
// 2. 配線: レシピUIを使う全画面が上限なしで呼ぶ(素材だけ特別扱いしない)
// ---------------------------------------------------------------------------

const RECIPE_UI_CALLERS = [
  ["forms.js", "catalog.yml(アイテムカタログ)"],
  ["ars-forms.js", "materials.yml(「素材」タブ)"],
  ["ars-spellbooks.js", "spellbooks.yml(魔導書)"],
  ["ars-source-forms.js", "sourcejars.yml/sourcelinks.yml(ソース)"],
  ["functional-items.js", "functional-items.yml(機能アイテム)"]
];

for (const [file, what] of RECIPE_UI_CALLERS) {
  test(`${file}: ${what} のレシピUIは上限オプション無しで呼ぶ(全画面で同じ挙動)`, () => {
    const src = JS(file);
    // ars-forms.js は window.renderCatalogRecipeSection を一度ローカル変数へ受けてから呼ぶ。
    assert.match(src, /renderCatalogRecipeSection/,
      `${file} が renderCatalogRecipeSection を参照していない(前提のドリフト)`);
    assert.ok(!/maxRecipes/.test(src),
      `${file} がレシピ数を制限している(統合済みなので制限してはいけない)`);
  });
}

test("forms.js: catalog.yml のカタログ画面の呼び出しは従来どおり", () => {
  const src = JS("forms.js");
  assert.match(src, /renderCatalogRecipeSection\(entry, \(\) => renderList\(\), working\.items, id, \{ allowMirror: true \}\)/,
    "カタログ画面の呼び出しに想定外の変更がある");
});

// ---------------------------------------------------------------------------
// 3. ArsPaper のローダーが recipe: と recipes: の両方を読む
//    (fork は .gitignore 除外のため、存在するときだけ検査する)
// ---------------------------------------------------------------------------

test("ArsPaper の UnifiedRecipeLoader は recipe: と recipes: の両方を読む", () => {
  if (!fs.existsSync(FORK_JAVA)) {
    console.log("fork-handoff が無いため ArsPaper 側の確認をスキップ");
    return;
  }
  const java = fs.readFileSync(FORK_JAVA, "utf8");
  assert.ok(/getConfigurationSection\("recipe"\)/.test(java),
    "recipe:(単数)を読む行が無い(後方互換が壊れている)");
  assert.ok(/isList\("recipes"\)/.test(java),
    "recipes:(配列)を読んでいない。エディタで2件目を足すと Java から見えなくなる");
  // 各ローダーが1件ずつではなく「全件」回していること。
  assert.ok(/for \(ConfigurationSection recipeSection : recipeSections\(/.test(java),
    "recipeSections() を回すループになっていない(1件しか読んでいない可能性)");
});

test("ArsPaper: 2件目以降のレシピは登録キーを一意化する(同じキーだと後勝ちで片方消える)", () => {
  if (!fs.existsSync(FORK_JAVA)) {
    console.log("fork-handoff が無いため ArsPaper 側の確認をスキップ");
    return;
  }
  const java = fs.readFileSync(FORK_JAVA, "utf8");
  assert.match(java, /static String recipeKey\(String itemId, int index\)/,
    "登録キーの一意化ヘルパ recipeKey が無い");
  // 作業台レシピの登録キーは WorkbenchRecipeData.id、儀式は RitualRecipe の id。
  // どちらも結果アイテムIDではなく recipeKey を渡していること。
  assert.match(java, /new WorkbenchRecipeData\(recipeKey,/,
    "作業台レシピの登録キーが recipeKey になっていない");
});

// ---------------------------------------------------------------------------
// 4. materials.yml の書き戻しが recipes: を往復できる(素材モデルは中間表現を挟むため)
// ---------------------------------------------------------------------------

test("ars-forms.js: 素材のレシピ書き戻しは recipes: も往復する(1件目を捨てない)", () => {
  const src = JS("ars-forms.js");
  const start = src.indexOf("function renderMaterialRecipeSection(");
  assert.ok(start > 0, "renderMaterialRecipeSection が見つからない");
  const body = src.slice(start, start + 1500);
  assert.match(body, /entryLike\.recipes/,
    "recipes: を共通UIへ渡していない(2件目を足した瞬間に1件目が消える)");
  assert.match(body, /m\.hasRecipes\s*=/,
    "共通UIが書いた recipes: をモデルへ読み戻していない");
});

test("ars-forms.js: 素材モデルは recipes: を既知キーとして保存する(未知キーとして落とさない)", () => {
  const src = JS("ars-forms.js");
  assert.match(src, /MATERIAL_KNOWN[\s\S]{0,400}?"recipes"/,
    "MATERIAL_KNOWN に recipes が無い");
  assert.match(src, /case "recipes"/,
    "serializeMaterialEntry が recipes: を書き出していない");
});

// ---------------------------------------------------------------------------
// 5. 保存時の検証も両形に掛かる(recipes: を素通しにしない)
// ---------------------------------------------------------------------------

test("schema.js: recipe: と recipes: を同じ検証器へ流す共通ヘルパがある", () => {
  const src = fs.readFileSync(path.join(ROOT, "lib", "schema.js"), "utf8");
  assert.match(src, /function validateRecipeForms\(entry, prefix, errors, validateOne\)/,
    "validateRecipeForms が無い");
  for (const caller of [
    "validateRecipeForms(entry, `items.${id}`, errors, validateCatalogRecipe)",
    "validateRecipeForms(entry, prefix, errors, validateRitualRecipe)",
    "validateRecipeForms(b, `spell-books[${i}]`, errors, validateCatalogRecipe)"
  ]) {
    assert.ok(src.includes(caller), `検証の配線が欠けている: ${caller}`);
  }
});

test("schema.js: Ars 側の config でも recipes: が配列でなければエラーになる", () => {
  const { validate } = require(path.join(ROOT, "lib", "schema.js"));
  const bad = validate("ars-materials", { materials: { foo: { recipes: { a: 1 } } } });
  assert.ok(bad.some((e) => /recipes/.test(e)),
    `recipes: の型エラーが検出されない: ${JSON.stringify(bad)}`);

  const good = validate("ars-materials", {
    materials: {
      foo: {
        recipes: [
          { "core-item": "DIAMOND", source: 100 },
          { "core-item": "EMERALD", source: 200 }
        ]
      }
    }
  });
  assert.deepEqual(good, [], `正しい recipes: が弾かれている: ${JSON.stringify(good)}`);
});
