"use strict";

/**
 * カタログから「バニラ」エントリを削除する。
 * 判定: custom-model-data が無く、かつ recipe.register === false（Valhalla取り込みのバニラクラフト）。
 * CMD付きカスタム武器や TF 独自例示アイテムは残す。
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

const toDelete = [];
for (const item of items.items || []) {
  const id = item.key?.value ?? item.key;
  const node = item.value;
  if (id == null || !node || typeof node.get !== "function") continue;
  const hasCmd = node.has("custom-model-data") && node.get("custom-model-data") != null;
  const recipe = node.get("recipe");
  const registerFalse =
    recipe && typeof recipe.get === "function" && recipe.get("register") === false;
  if (!hasCmd && registerFalse) toDelete.push(String(id));
}

for (const id of toDelete) {
  items.delete(id);
}

// _editor.itemTabs からも掃除
const editor = doc.get("_editor");
if (editor && typeof editor.get === "function") {
  const tabs = editor.get("itemTabs");
  if (tabs && typeof tabs.delete === "function") {
    for (const id of toDelete) tabs.delete(id);
  }
  const orders = editor.get("orders");
  if (orders && typeof orders.items === "object") {
    for (const orderItem of orders.items || []) {
      const arr = orderItem.value;
      if (!arr || !Array.isArray(arr.items)) continue;
      arr.items = arr.items.filter((n) => {
        const v = n?.value ?? n;
        return !toDelete.includes(String(v));
      });
    }
  }
  const cats = editor.get("categories");
  if (cats && typeof cats.items === "object") {
    for (const catGroup of cats.items || []) {
      const list = catGroup.value;
      if (!list || !Array.isArray(list.items)) continue;
      for (const catNode of list.items) {
        const cat = catNode.value;
        if (!cat || typeof cat.get !== "function") continue;
        const itemIds = cat.get("itemIds");
        if (!itemIds || !Array.isArray(itemIds.items)) continue;
        itemIds.items = itemIds.items.filter((n) => {
          const v = n?.value ?? n;
          return !toDelete.includes(String(v));
        });
      }
    }
  }
}

fs.writeFileSync(CATALOG_PATH, String(doc), "utf8");
console.log(JSON.stringify({
  deleted: toDelete.length,
  remaining: (items.items || []).length,
  sampleDeleted: toDelete.slice(0, 15),
}, null, 2));
