"use strict";

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const root = path.resolve(__dirname, "..", "..", "..");
const crafting = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge", "src", "main", "resources", "progression", "crafting-features.yml"), "utf8"));
const materials = YAML.parse(fs.readFileSync(path.join(root, "fork-handoff", "arspaper", "fork", "src", "main", "resources", "materials.yml"), "utf8"));
const cmdRegistry = JSON.parse(fs.readFileSync(path.join(root, "resourcepack", "cmd-registry.json"), "utf8"));

const SCRAPS = [
  "plank_scrap", "copper_ingot_scrap", "iron_ingot_scrap", "gold_ingot_scrap", "diamond_scrap",
  "netherite_ingot_scrap", "leather_scrap", "turtle_scute_scrap"
];

test("バニラ装備の解体は素材別スクラップへ返し、チェーンは対象外", () => {
  const expected = {
    "wooden_*": { input: "list:planks", scrap: "plank_scrap" },
    "copper_*": { input: "COPPER_INGOT", scrap: "copper_ingot_scrap" },
    "iron_*": { input: "IRON_INGOT", scrap: "iron_ingot_scrap" },
    "golden_*": { input: "GOLD_INGOT", scrap: "gold_ingot_scrap" },
    "diamond_*": { input: "DIAMOND", scrap: "diamond_scrap" },
    "netherite_*": { input: "NETHERITE_INGOT", scrap: "netherite_ingot_scrap" },
    "leather_*": { input: "LEATHER", scrap: "leather_scrap" },
    "turtle_*": { input: "TURTLE_SCUTE", scrap: "turtle_scute_scrap" }
  };
  const rules = crafting.disassembly.items;
  for (const [series, { input, scrap }] of Object.entries(expected)) {
    const entries = rules[series];
    assert.ok(entries?.some((rule) => rule.input === input && rule.output === `custom:${scrap}` && rule.multiplier === 2), `${series} の返却先`);
  }
  assert.equal(rules["chainmail_*"], undefined);
  assert.equal(rules["stone_*"], undefined);
});

// 2026-07-31: base_material(見た目のベースアイテム)と recipe.result(4個で戻る素材)は別物。
// スクラップは「インゴットより小さい欠片」に見せるため base は NUGGET 系で、戻るのはインゴット。
// 以前は1つの表で両方を検証していたため、見た目を NUGGET へ変えた時点でこのテストが落ちていた。
// 2列に分けて、どちらの方向のドリフトも落ちるようにする。
const SCRAP_RETURNS = {
  plank_scrap: "OAK_PLANKS", copper_ingot_scrap: "COPPER_INGOT",
  iron_ingot_scrap: "IRON_INGOT", gold_ingot_scrap: "GOLD_INGOT", diamond_scrap: "DIAMOND",
  netherite_ingot_scrap: "NETHERITE_INGOT", leather_scrap: "LEATHER", turtle_scute_scrap: "TURTLE_SCUTE"
};
const SCRAP_BASE_LOOK = {
  plank_scrap: "OAK_PLANKS", copper_ingot_scrap: "COPPER_NUGGET",
  iron_ingot_scrap: "IRON_NUGGET", gold_ingot_scrap: "GOLD_NUGGET", diamond_scrap: "COPPER_NUGGET",
  netherite_ingot_scrap: "NETHERITE_SCRAP", leather_scrap: "LEATHER", turtle_scute_scrap: "TURTLE_SCUTE"
};

// 2026-08-13: 復号レシピの持ち主を TF 側(crafting-features.yml の added-recipes、
// エディタの「その他のギミック > レシピ追加」)へ移設した。ArsPaper の materials.yml 側に
// recipe: を残すと同じ入力パターンのレシピが二重登録になるので、両方向を固定する:
//   (1) TF 側に7件そろっている  (2) Ars 側にはもう1件も無い
// plank_scrap(木材スクラップ)は素材定義ごと存在しないので、この移設の対象外
// (wooden_* の解体をどうするかは未決。下の SCRAPS を使う2本のテストがそれを見張っている)。
const MOVED_SCRAPS = Object.fromEntries(
  Object.entries(SCRAP_RETURNS).filter(([id]) => id !== "plank_scrap"));

test("各素材スクラップ4個はプレイヤーの2×2クラフトで元の素材1個に戻せる", () => {
  const added = crafting["added-recipes"] || [];
  for (const [id, material] of Object.entries(MOVED_SCRAPS)) {
    const entry = materials.materials[id];
    assert.ok(entry, `${id} が素材カタログに未定義`);
    assert.equal(entry.base_material, SCRAP_BASE_LOOK[id], `${id} の見た目ベース`);

    const recipe = added.find((r) => r.ingredients && r.ingredients.i === `custom:${id}`);
    assert.ok(recipe, `${id} の復号レシピが crafting-features.yml の added-recipes に無い`);
    assert.deepEqual(recipe.shape, ["ii", "ii"], `${id} の2×2形状`);
    assert.equal(recipe.method, "inventory", `${id} はインベントリクラフト`);
    assert.equal(recipe.amount, 1, `${id} の返却数`);
    // result は必須。TF 側 loadAddedRecipes は result 無しのエントリを警告して丸ごと捨てるので、
    // 書き忘れると「レシピが1件も登録されない」という無言の欠落になる。
    assert.equal(recipe.result, material,
      `${id} の result が元素材(${material})でない`);
  }
  assert.equal(added.length, Object.keys(MOVED_SCRAPS).length,
    "added-recipes にスクラップ以外のエントリが増えている(増やすならこのテストも更新すること)");
});

test("移設後: ArsPaper の materials.yml にスクラップの復号レシピが残っていない", () => {
  const leftovers = Object.keys(materials.materials)
    .filter((id) => /scrap/i.test(id))
    .filter((id) => materials.materials[id] && materials.materials[id].recipe)
    .map((id) => `${id} に recipe: が残っている`);
  assert.deepEqual(leftovers, [],
    "TF 側 added-recipes と同じ入力パターンのレシピが Ars 側にも残ると二重登録になる"
    + "(同形レシピは後勝ちで片方が黙って消える)");
});

test("素材カタログとCMD台帳の両方にスクラップが登録される", () => {
  const materialCategory = materials._editor.categories.material.find((category) => category.label === "中間素材");
  for (const id of SCRAPS) {
    assert.ok(materialCategory.itemIds.includes(id), `${id} が素材カタログに未分類`);
    const entry = cmdRegistry.allocations.find((entry) => entry.id === id);
    assert.equal(entry?.source, "materials", `${id} のCMD由来`);
  }
});
