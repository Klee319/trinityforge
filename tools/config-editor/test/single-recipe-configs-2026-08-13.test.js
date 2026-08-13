"use strict";

// ---------------------------------------------------------------------------
// 2026-08-13 実サーバ報告の回帰テスト:
//   「アイテムカタログで2つ目のレシピを登録しようとすると1つ目のレシピが消える」
//
// 真因: レシピ編集UI (forms.js の renderCatalogRecipeSection) は
// 「0件=キーなし / 1件=recipe: / 2件以上=recipes:」という catalog.yml (TF本体) の正規形へ
// 書き戻す。TF本体の ItemCatalogConfig は recipes: を読むのでこれで正しいが、
// **ArsPaper 側の yml は例外なく recipe:(単数)しか読まない**
// (UnifiedRecipeLoader が functional-items.yml / items.yml / materials.yml / threads.yml /
//  sourcejars.yml / spellbooks.yml のいずれでも getConfigurationSection("recipe") のみ)。
//
// それでも同じUIを流用していたため、Ars 系の画面で「+ レシピを追加」を押すと:
//   - materials.yml (「アイテムカタログ」グループの『素材』タブ): 書き戻し先の entryLike が
//     recipe: を失って recipes: になり、呼び出し元は entryLike.recipe しか見ないので
//     hasRecipe=false になる → **1件目もろとも消える**(報告そのもの)。
//   - spellbooks/sourcejars/functional-items: recipes: が書かれるが Java は読まないので
//     元のレシピが無言で効かなくなる。
//
// 対策: 1件しか持てない config では opts.maxRecipes = 1 を渡し、「+ レシピを追加」自体を
// 出さない(データを壊せる操作を UI から消す)。catalog.yml だけが従来どおり複数可。
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
// 1. UI 部品側: maxRecipes を超えたら「+ レシピを追加」を出さない
// ---------------------------------------------------------------------------

test("forms.js: renderCatalogRecipeSection は opts.maxRecipes を受け取り、上限に達したら追加ボタンを出さない", () => {
  const src = JS("forms.js");
  const start = src.indexOf("function renderCatalogRecipeSection(");
  assert.ok(start > 0, "renderCatalogRecipeSection が見つからない");
  const body = src.slice(start, src.indexOf("\n  // 1レシピ分の編集フォーム", start));
  assert.match(body, /options\.maxRecipes|opts && opts\.maxRecipes/,
    "maxRecipes を読んでいない");
  assert.match(body, /list\.length < maxRecipes/,
    "追加ボタンの描画が上限で門番されていない");
});

// ---------------------------------------------------------------------------
// 2. 配線: ArsPaper 側の yml を編集する画面はすべて maxRecipes: 1 を渡す
// ---------------------------------------------------------------------------

const SINGLE_RECIPE_CALLERS = [
  ["ars-forms.js", "materials.yml(「素材」タブ)"],
  ["ars-spellbooks.js", "spellbooks.yml(魔導書)"],
  ["ars-source-forms.js", "sourcejars.yml/sourcelinks.yml(ソース)"],
  ["functional-items.js", "functional-items.yml(機能アイテム)"]
];

for (const [file, what] of SINGLE_RECIPE_CALLERS) {
  test(`${file}: ${what} のレシピUIは maxRecipes: 1 で呼ぶ(2件目を作らせない)`, () => {
    const src = JS(file);
    // ars-forms.js は window.renderCatalogRecipeSection を一度ローカル変数へ受けてから呼ぶ。
    assert.match(src, /renderCatalogRecipeSection/,
      `${file} が renderCatalogRecipeSection を参照していない(前提のドリフト)`);
    const withLimit = src.match(/maxRecipes:\s*1/g) || [];
    assert.ok(withLimit.length > 0, `${file} が maxRecipes: 1 を渡していない`);
  });
}

test("functional-items.js: catalog.yml 側(TF特殊アイテム)は上限を付けない(複数レシピ可のまま)", () => {
  const src = JS("functional-items.js");
  // buildTfSpecialItemsSection は catalog.yml の items を編集するので複数レシピを許す。
  const start = src.indexOf("function buildTfSpecialItemsSection(");
  assert.ok(start > 0, "buildTfSpecialItemsSection が見つからない");
  const body = src.slice(start);
  const callIdx = body.indexOf("renderCatalogRecipeSection(");
  assert.ok(callIdx > 0, "buildTfSpecialItemsSection がレシピUIを呼んでいない");
  const callLine = body.slice(callIdx, body.indexOf("\n", callIdx));
  assert.ok(!/maxRecipes/.test(callLine),
    "catalog.yml 側にまで1件上限が付いている(TF本体は recipes: を読めるので制限は不要)");
});

test("forms.js: catalog.yml のカタログ画面は上限を付けない(複数レシピ可)", () => {
  const src = JS("forms.js");
  assert.match(src, /renderCatalogRecipeSection\(entry, \(\) => renderList\(\), working\.items, id, \{ allowMirror: true \}\)/,
    "カタログ画面の呼び出しに想定外の変更がある");
});

// ---------------------------------------------------------------------------
// 3. 前提: ArsPaper のローダーは recipe:(単数)しか読まない
//    (fork は .gitignore 除外のため、存在するときだけ検査する)
// ---------------------------------------------------------------------------

test("前提: ArsPaper の UnifiedRecipeLoader は recipes:(複数形)を一切読まない", () => {
  if (!fs.existsSync(FORK_JAVA)) {
    console.log("fork-handoff が無いため ArsPaper 側の前提確認をスキップ");
    return;
  }
  const java = fs.readFileSync(FORK_JAVA, "utf8");
  assert.ok(/getConfigurationSection\("recipe"\)/.test(java),
    "recipe: を読む行が見当たらない(前提のドリフト)");
  assert.ok(!/getList\("recipes"|getConfigurationSection\("recipes"\)/.test(java),
    "ArsPaper が recipes:(複数形)を読むようになった。maxRecipes: 1 の制限を見直すこと");
});

// ---------------------------------------------------------------------------
// 4. materials.yml の書き戻しは recipes: が来ても1件目を失わない(多重防御)
// ---------------------------------------------------------------------------

test("ars-forms.js: 素材のレシピ書き戻しは entryLike.recipes[0] も拾う(1件目を捨てない)", () => {
  const src = JS("ars-forms.js");
  const start = src.indexOf("function renderMaterialRecipeSection(");
  assert.ok(start > 0, "renderMaterialRecipeSection が見つからない");
  const body = src.slice(start, start + 1500);
  assert.match(body, /entryLike\.recipes/,
    "recipes: が来た場合に1件目を拾う多重防御が無い(UI制限が外れた瞬間にデータが消える)");
});
