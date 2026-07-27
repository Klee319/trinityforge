"use strict";

// T1 (2026-07-25 軽装レビュー): shaped recipe の非矩形検出。
// Bukkit の ShapedRecipe#shape は全行が同じ長さ(矩形)を要求する。以前は editor 側にこの検査が
// なく、7件の胴レシピ(bone_guard_chestplate 等)が非矩形のまま気づかれずに本番投入されていた。
// validateShape (lib/schema.js) に矩形検査を追加したので、editor バリデータでも確実に弾かれる
// こと、および本番 catalog.yml が全件矩形であることをここで固定する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");
const { validate } = require("../lib/schema");

test("catalog shaped recipe with unequal row lengths is rejected", () => {
  const data = {
    items: {
      bad_chestplate: {
        material: "LEATHER_CHESTPLATE",
        recipe: {
          method: "workbench",
          type: "shaped",
          shape: ["YY", "XXX", "XXX"],
          ingredients: { X: "BONE", Y: "LEATHER" }
        }
      }
    }
  };
  const errors = validate("catalog", data);
  assert.ok(
    errors.some((e) => e.includes("矩形") && e.includes("items.bad_chestplate.recipe.shape")),
    `expected a rectangularity error, got: ${JSON.stringify(errors)}`
  );
});

test("catalog shaped recipe with equal row lengths (blank slot padded) is accepted", () => {
  const data = {
    items: {
      good_chestplate: {
        material: "LEATHER_CHESTPLATE",
        recipe: {
          method: "workbench",
          type: "shaped",
          shape: ["Y Y", "XXX", "XXX"],
          ingredients: { X: "BONE", Y: "LEATHER" }
        }
      }
    }
  };
  const errors = validate("catalog", data);
  assert.deepEqual(errors, []);
});

test("production items/catalog.yml has no non-rectangular shaped recipes", () => {
  const catalogPath = path.join(__dirname, "..", "..", "..", "TrinityForge", "src", "main",
    "resources", "items", "catalog.yml");
  const data = YAML.parse(fs.readFileSync(catalogPath, "utf8"));
  const errors = validate("catalog", data);
  const rectErrors = errors.filter((e) => e.includes("矩形"));
  assert.deepEqual(rectErrors, [], `production catalog.yml has non-rectangular shaped recipe(s): ${JSON.stringify(rectErrors)}`);
});
