"use strict";

// 2026-07-28 実サーバ報告「アイテムカタログで設定したアイテムカテゴリに切り替えてもソートされない」の回帰テスト。
//
// 真因: カテゴリの選択状態は `activeByHost` (WeakMap、キーはYAMLルートの**オブジェクト同一性**) に
// 持つ。ところが catalog 画面は TF 特殊アイテム2件を隠すために `{ ...data, items: clone }` の
// 浅いクローンをフォームへ渡しており、カテゴリバーには元の `data` を渡していた。
// → バーは data に選択を書き、フォームはクローンから読むので常に「すべて」になり、絞り込みが効かない。
// catalog.yml には skill_node_lock / skill_tree_reset が実在するので、この分岐は毎回通る。

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const ROOT = path.resolve(__dirname, "..");
const splitViews = fs.readFileSync(path.join(ROOT, "public", "js", "split-views.js"), "utf8");
const editorCategories = fs.readFileSync(path.join(ROOT, "public", "js", "editor-categories.js"), "utf8");

test("カテゴリバーにはフォームが実際に読むのと同一の host を渡す", () => {
  // クローンを作る分岐で categoryHost も必ず差し替えること。
  assert.match(
    splitViews,
    /catalogViewData = \{ \.\.\.data, items: itemsClone \};[\s\S]{0,300}?categoryHost = catalogViewData;/
  );
  assert.match(splitViews, /const host = categoryHost;/);
  // `const host = data;` に戻すと、クローン経路のある画面で選択状態が届かなくなる。
  assert.ok(!/const host = data;/.test(splitViews), "host を data 直渡しに戻してはいけない");
});

test("選択状態はオブジェクト同一性で引くので、クローンでは共有されない", () => {
  // この前提が崩れた(例: _editor 内に選択を保存する設計へ変えた)なら上のテストは不要になる。
  assert.match(editorCategories, /const activeByHost = new WeakMap\(\);/);
  assert.match(editorCategories, /activeByHost\.get\(host\)/);
});

test("フォーム側は絞り込みと並び替えの両方を active カテゴリで行う", () => {
  const forms = fs.readFileSync(path.join(ROOT, "public", "js", "forms.js"), "utf8");
  assert.match(forms, /function filterAndSortIds\(ids\)[\s\S]{0,400}?window\.itemInEditorCategory\(working, editorCategoryKey, id\)/);
  assert.match(forms, /window\.sortIdsByEditorOrder\(working, editorCategoryKey, result\)/);
});
