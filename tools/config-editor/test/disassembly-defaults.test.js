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

test("各素材スクラップ4個はプレイヤーの2×2クラフトで元の素材1個に戻せる", () => {
  const expected = {
    plank_scrap: "OAK_PLANKS", copper_ingot_scrap: "COPPER_INGOT",
    iron_ingot_scrap: "IRON_INGOT", gold_ingot_scrap: "GOLD_INGOT", diamond_scrap: "DIAMOND",
    netherite_ingot_scrap: "NETHERITE_INGOT", leather_scrap: "LEATHER", turtle_scute_scrap: "TURTLE_SCUTE"
  };
  for (const [id, material] of Object.entries(expected)) {
    const entry = materials.materials[id];
    assert.ok(entry, `${id} が素材カタログに未定義`);
    assert.equal(entry.base_material, material, `${id} の返却素材`);
    assert.deepEqual(entry.recipe?.shape, ["ii", "ii"], `${id} の2×2形状`);
    assert.equal(entry.recipe?.method, "inventory", `${id} はインベントリクラフト`);
    assert.equal(entry.recipe?.ingredients?.i, `custom:${id}`, `${id} の素材`);
    assert.equal(entry.recipe?.amount, 1, `${id} の返却数`);
    // 2026-07-26: result の明示が必須。UnifiedRecipeLoader.loadWorkbenchFromSection は result 未指定時に
    // "custom:<自分自身>" を既定にするため、書き忘れると「スクラップ4個 → スクラップ1個」という
    // 純粋な破壊レシピとして登録され、解体で得たスクラップの用途が完全に消える(実際にそうなっていた)。
    assert.equal(entry.recipe?.result, material,
      `${id} の recipe.result が元素材(${material})でない。未指定だと自分自身に戻る破壊レシピになる`);
  }
});

test("素材カタログとCMD台帳の両方にスクラップが登録される", () => {
  const materialCategory = materials._editor.categories.material.find((category) => category.label === "中間素材");
  for (const id of SCRAPS) {
    assert.ok(materialCategory.itemIds.includes(id), `${id} が素材カタログに未分類`);
    const entry = cmdRegistry.allocations.find((entry) => entry.id === id);
    assert.equal(entry?.source, "materials", `${id} のCMD由来`);
  }
});
