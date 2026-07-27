"use strict";

// 要求された武器/ツール系カタログが、編集用の分類情報も含めて欠けないことを守る回帰テスト。
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const root = path.resolve(__dirname, "..", "..", "..");
const catalog = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge", "src", "main", "resources", "items", "catalog.yml"), "utf8"));
const crafting = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge", "src", "main", "resources", "progression", "crafting-features.yml"), "utf8"));

function itemIds(category, label) {
  const categoryRows = catalog._editor.categories[category] || [];
  const row = categoryRows.find((entry) => entry.label === label);
  return row ? row.itemIds : [];
}

test("カタログで表示タブを持つアイテムは同じタブのカテゴリにも所属する", () => {
  for (const [id, tab] of Object.entries(catalog._editor.itemTabs || {})) {
    if (!["weapon", "catalyst", "tool", "armor", "other"].includes(tab)) continue;
    const assigned = (catalog._editor.categories[tab] || [])
      .some((entry) => (entry.itemIds || []).includes(id));
    assert.ok(assigned, `${id} が ${tab} のカテゴリ未割当`);
  }
});

test("ソースジェムの遠隔武器4種は武器カタログに定義・分類される", () => {
  const expected = ["source_gem_bow", "source_gem_trident", "source_gem_mace", "source_gem_crossbow"];
  for (const id of expected) assert.ok(catalog.items[id], `${id} が未定義`);
  assert.ok(itemIds("weapon", "弓").includes("source_gem_bow"));
  assert.ok(itemIds("weapon", "トライデント").includes("source_gem_trident"));
  assert.ok(itemIds("weapon", "メイス").includes("source_gem_mace"));
  assert.ok(itemIds("weapon", "クロスボウ").includes("source_gem_crossbow"));
});

test("エンダードラゴン製ツールは定義・分類されない", () => {
  for (const type of ["pickaxe", "shovel", "axe_tool", "hoe"]) {
    const id = `dragon_${type}`;
    assert.equal(catalog.items[id], undefined, `${id} が残っている`);
    assert.equal(catalog._editor.itemTabs[id], undefined, `${id} の分類が残っている`);
  }
});

test("木～ネザライトのツール斧は戦斧と異なるソースジェム式レシピを持つ", () => {
  const tiers = [
    ["wooden", "list:planks"],
    ["stone", "list:cobblestone"],
    ["copper", "COPPER_INGOT"],
    ["iron", "IRON_INGOT"],
    ["golden", "GOLD_INGOT"],
    ["diamond", "DIAMOND"],
    ["netherite", "NETHERITE_INGOT"]
  ];
  const listed = itemIds("tool", "斧");
  for (const [tier, ingredient] of tiers) {
    const id = `${tier}_axe_tool`;
    const item = catalog.items[id];
    assert.ok(item, `${id} が未定義`);
    assert.equal(catalog._editor.itemTabs[id], "tool", `${id} の分類`);
    assert.ok(listed.includes(id), `${id} がツール/斧カテゴリにない`);
    assert.deepEqual(item.recipe.shape, [" si", " si", " s "], `${id} の素材配置`);
    assert.equal(item.recipe.ingredients.i, ingredient, `${id} の主素材`);
    assert.equal(item.recipe.ingredients.s, "STICK", `${id} の柄素材`);
  }
});

test("インフィニティツールの未確定レシピを再追加しない", () => {
  for (const type of ["pickaxe", "shovel", "axe_tool", "hoe"]) {
    assert.equal(catalog.items[`infinity_${type}`]?.recipe, undefined, `infinity_${type} のレシピ`);
  }
});

test("木～ネザライトの斧は戦斧として武器へ移し、バニラレシピを無効化する", () => {
  const battleAxes = ["wooden_axe_tf", "stone_axe_tf", "copper_axe_tf", "iron_axe_tf", "golden_axe_tf", "diamond_axe_tf", "netherite_axe_tf"];
  const listed = itemIds("weapon", "戦斧");
  for (const id of battleAxes) {
    assert.match(catalog.items[id]["display-name"], /戦斧$/);
    assert.equal(catalog._editor.itemTabs[id], "weapon", `${id} の分類`);
    assert.ok(listed.includes(id), `${id} が戦斧カテゴリに無い`);
    assert.ok(!itemIds("tool", "斧").includes(id), `${id} がツールの斧に残っている`);
  }
  for (const id of battleAxes.filter((id) => id !== "netherite_axe_tf")) {
    assert.deepEqual(catalog.items[id].recipe.shape, ["ii ", "is ", " s "], `${id} の素材配置`);
  }
  assert.deepEqual(crafting["removed-vanilla-recipes"], [
    "minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:copper_axe", "minecraft:iron_axe",
    "minecraft:golden_axe", "minecraft:diamond_axe", "minecraft:netherite_axe_smithing"
  ]);
});

test("ソースジェム装備はソースジェムそのもので作り、防具4部位もカタログ分類される", () => {
  const sourceGear = Object.entries(catalog.items).filter(([id]) => id.startsWith("source_gem_"));
  for (const [id, item] of sourceGear) {
    const recipes = item.recipes || (item.recipe ? [item.recipe] : []);
    for (const recipe of recipes) {
      assert.doesNotMatch(JSON.stringify(recipe), /custom:source_gem_block/, `${id} がソースジェムブロックを要求している`);
    }
  }
  const armor = ["source_gem_helmet", "source_gem_chestplate", "source_gem_leggings", "source_gem_boots"];
  const sourceCategory = itemIds("armor", "ソースジェム");
  for (const id of armor) {
    assert.ok(catalog.items[id], `${id} が未定義`);
    assert.ok(sourceCategory.includes(id), `${id} がソースジェム防具カテゴリに無い`);
    assert.equal(catalog._editor.itemTabs[id], "armor", `${id} のタブ`);
    assert.equal(catalog.items[id].recipe.ingredients.i, "custom:source_gem", `${id} の素材`);
  }

  const registry = JSON.parse(fs.readFileSync(path.join(root, "resourcepack", "cmd-registry.json"), "utf8"));
  for (const id of armor) {
    const item = catalog.items[id];
    assert.ok(registry.allocations.some((entry) => entry.material === item.material
      && entry.cmd === item["custom-model-data"] && entry.id === id), `${id} のCMD台帳登録`);
  }
});
