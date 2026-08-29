"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

// 儀式の結果個数は Ars UnifiedRecipeLoader が result-amount を読む。
// 共通レシピUI (forms.js renderCatalogRecipeCard) が作業台用 amount だけを出すと、
// materials.yml の source_engine のように result-amount: 4 があっても欄が空(=1)に見える。

const formsSrc = fs.readFileSync(path.join(__dirname, "../public/js/forms.js"), "utf8");
const recipesSrc = fs.readFileSync(path.join(__dirname, "../public/js/recipes.js"), "utf8");

test("renderCatalogRecipeCard: 儀式は result-amount を編集し amount は書かない", () => {
  const start = formsSrc.indexOf("function renderCatalogRecipeCard(");
  assert.ok(start > 0, "renderCatalogRecipeCard が見つからない");
  const card = formsSrc.slice(start, formsSrc.indexOf("window.renderCatalogRecipeSection", start));
  assert.match(card, /recipe\.method === "ritual"/);
  assert.match(card, /fieldRow\("result-amount"/);
  assert.match(card, /setOrDelete\(recipe, "result-amount"/);
  assert.match(card, /delete recipe\.amount/);
  const ritualBlock = card.slice(card.indexOf("recipe.method === \"ritual\""));
  const workbenchIdx = ritualBlock.indexOf("recipe.method === \"workbench\"");
  const ritualOnly = workbenchIdx >= 0 ? ritualBlock.slice(0, workbenchIdx) : ritualBlock;
  assert.doesNotMatch(ritualOnly, /fieldRow\("amount"/,
    "儀式ブランチが作業台用 amount 欄を出してはいけない");
});

test("recipes.js items.yml 経路は従来どおり result-amount を往復する", () => {
  assert.match(recipesSrc, /model\.has\["result-amount"\] = recipe\["result-amount"\] !== undefined/);
  assert.match(recipesSrc, /if \(model\.has\["result-amount"\]\) r\["result-amount"\] = model\.resultAmount/);
});
