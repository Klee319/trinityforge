"use strict";

/**
 * One-off: remove ecipe from catalog items where recipe.register === false.
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { parseDocument } from "../config-editor/node_modules/yaml/dist/index.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "../..");
const CATALOG_PATH = path.join(
  REPO_ROOT,
  "TrinityForge/src/main/resources/items/catalog.yml",
);

const raw = fs.readFileSync(CATALOG_PATH, "utf8");
const doc = parseDocument(raw);
const items = doc.get("items");
if (!items || typeof items.get !== "function") {
  console.error("catalog.yml: items map not found");
  process.exit(1);
}

let removed = 0;
for (const item of items.items || []) {
  const id = item.key?.value ?? item.key;
  const node = item.value;
  if (id == null || !node || typeof node.get !== "function") continue;
  const recipe = node.get("recipe");
  if (!recipe || typeof recipe.get !== "function") continue;
  if (recipe.get("register") !== false) continue;
  node.delete("recipe");
  removed++;
}

fs.writeFileSync(CATALOG_PATH, String(doc), "utf8");
console.log(JSON.stringify({ recipeBlocksRemoved: removed }, null, 2));
