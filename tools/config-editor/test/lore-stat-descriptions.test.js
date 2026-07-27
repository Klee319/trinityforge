"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const YAML = require("yaml");

test("every Lore-displayed stat has an implementation-backed help description", () => {
  const root = path.resolve(__dirname, "../../..");
  const source = fs.readFileSync(path.join(root, "tools/config-editor/public/js/labels.js"), "utf8");
  const context = { window: {} };
  vm.runInNewContext(source, context, { filename: "labels.js" });
  const lore = YAML.parse(fs.readFileSync(path.join(root, "TrinityForge/src/main/resources/stats/lore.yml"), "utf8"));

  for (const key of Object.keys(lore.stats || {})) {
    const description = context.window.LABELS.statDescription(key);
    assert.ok(description && !description.includes("未登録"), `${key} needs a help description`);
  }
});
