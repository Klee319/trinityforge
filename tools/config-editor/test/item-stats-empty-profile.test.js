"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

// ---------------------------------------------------------------------------
// `_editor.itemTabs` 孤児掃除 (2026-07-26)
//
// 不具合: アイテムを削除しても `_editor.itemTabs` のタブピン留めが残り続けるため、
// stats/item-stats.yml に幽霊エントリが102件たまっていた(手作業で一度掃除した)。
// 削除経路ごとに removeItemDisplayTab を呼び忘れないようにするより、保存直前に
// 「items に実在しないidのピン」を落とす方が抜け漏れが無い、という設計をここで固定する。
//
// editor-categories.js は window へ生やすIIFEなので、require前に window シムを用意する。
// ---------------------------------------------------------------------------
global.window = global.window || {};
require("../public/js/editor-categories.js");
const pruneEditorUiState = global.window.pruneEditorUiState;

test("_editor.itemTabs: items に存在しないピンが保存前に落ちる (孤児掃除)", () => {
  assert.equal(typeof pruneEditorUiState, "function");

  const data = { items: { REAL: {} }, _editor: { itemTabs: { REAL: "weapon", ghost: "tool" } } };
  pruneEditorUiState(data);
  assert.equal("ghost" in data._editor.itemTabs, false, "削除済みアイテムのピンは残ってはいけない");
  assert.equal(data._editor.itemTabs.REAL, "weapon", "実在アイテムのピンは維持する");

  const allOrphans = { items: {}, _editor: { itemTabs: { g1: "weapon", g2: "armor" } } };
  pruneEditorUiState(allOrphans);
  assert.equal("itemTabs" in allOrphans._editor, false, "全部孤児なら itemTabs キーごと落とす");
});

test("_editor.itemTabs 掃除は categories と非itemsコンフィグを巻き添えにしない", () => {
  // categories のキーはアイテムidではなくカテゴリ名。同じ判定で消すと正当な定義まで落ちる。
  const withCategories = {
    items: {},
    _editor: { itemTabs: { ghost: "weapon" }, categories: { weapon: { nested: {} } } }
  };
  pruneEditorUiState(withCategories);
  assert.deepEqual(withCategories._editor.categories, { weapon: { nested: {} } });

  // items を持たない/想定外の形の config は判定材料が無いので触らない(安全側)。
  const noItems = { _editor: { itemTabs: { anything: "weapon" } } };
  pruneEditorUiState(noItems);
  assert.equal(noItems._editor.itemTabs.anything, "weapon");

  const arrayItems = { items: ["a"], _editor: { itemTabs: { anything: "weapon" } } };
  pruneEditorUiState(arrayItems);
  assert.equal(arrayItems._editor.itemTabs.anything, "weapon");

  // 既存挙動の回帰防止。
  const active = { _editorActive: "x", items: {}, _editor: {} };
  pruneEditorUiState(active);
  assert.equal("_editorActive" in active, false);

  assert.doesNotThrow(() => pruneEditorUiState(null));
  assert.doesNotThrow(() => pruneEditorUiState({ items: {} }));
});

test("item-stats save drops catalog display placeholders instead of persisting empty CMD overrides", () => {
  const forms = fs.readFileSync(path.join(__dirname, "..", "public", "js", "forms.js"), "utf8");
  assert.match(forms,
    /const items = dropEmptyItemProfiles\(pruneEntries\(working\.items,[\s\S]*?\)\);/);
  assert.match(forms,
    /function dropEmptyItemProfiles\(items\)[\s\S]*?Object\.keys\(entry\)\.length === 0[\s\S]*?delete items\[key\]/);
});
