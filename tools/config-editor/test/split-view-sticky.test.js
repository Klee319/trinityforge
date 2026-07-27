"use strict";

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const root = path.resolve(__dirname, "..");
const splitViews = fs.readFileSync(path.join(root, "public", "js", "split-views.js"), "utf8");
const style = fs.readFileSync(path.join(root, "public", "style.css"), "utf8");

test("カタログとアイテムステータスのカテゴリバーだけはスクロール追従しない", () => {
  assert.match(splitViews, /\["catalog",\s*"item-stats"\]\.includes\(o\.type\)/);
  assert.match(splitViews, /hub-subtabs-flow/);
  assert.match(style, /\.hub-subtabs\.hub-subtabs-flow\s*\{[^}]*position:\s*static/s);
});
