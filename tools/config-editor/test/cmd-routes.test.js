"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");

const { computeCmdWarnings } = require("../lib/cmd-routes");

// computeCmdWarnings は「保存後の全ファイル状態」を ctx.readEntryById 経由で読むため、
// テストでは configId に対応する fixture をあらかじめ「保存済み」の内容に更新してから呼ぶ
// (server.js の実際のフロー: ファイル書込 → computeCmdWarnings の順と同じ)。
function makeCtx(fixtures) {
  return { readEntryById: (id) => (Object.prototype.hasOwnProperty.call(fixtures, id) ? fixtures[id] : null) };
}

test("H-1: item-stats を保存しても常に警告なし (参照専用ファイルのため対象外)", () => {
  const fixtures = {
    catalog: { items: { foo: { material: "IRON_INGOT", "custom-model-data": 100001 } } }, // 予約値と衝突する内容
    "item-stats": { items: { "IRON_INGOT#100001": { fixed: { "attack-power": 1 } } } }
  };
  const ctx = makeCtx(fixtures);
  const warnings = computeCmdWarnings(ctx, "item-stats", {}, fixtures["item-stats"]);
  assert.deepEqual(warnings, []);
});

test("H-1: catalogがitem-statsと同じ(material,cmd)を持っていても警告しない", () => {
  const fixtures = {
    catalog: { items: { foo: { material: "IRON_INGOT", "custom-model-data": 5 } } },
    "item-stats": { items: { "IRON_INGOT#5": { fixed: { "attack-power": 1 } } } }
  };
  const ctx = makeCtx(fixtures);
  const warnings = computeCmdWarnings(ctx, "catalog", {}, fixtures.catalog);
  assert.deepEqual(warnings, [], "item-statsとの重複はクロスファイル警告の対象外");
});

test("H-1: catalog↔materials の意図的ミラーペアは警告しない", () => {
  const fixtures = {
    catalog: { items: { foo: { material: "STRING", "custom-model-data": 42 } } },
    materials: { materials: { foo: { base_material: "STRING", custom_model_data: 42 } } }
  };
  const ctx = makeCtx(fixtures);
  const warnings = computeCmdWarnings(ctx, "catalog", {}, fixtures.catalog);
  assert.deepEqual(warnings, [], "catalog⇔materials変換機能による正規の重複");
});

test("H-1: catalog↔spellbooks(books) の意図的ミラーペアは警告しない", () => {
  // 予約値(100001-3)ではなく通常のCMD値で「ミラーペア除外」だけを単独検証する。
  const fixtures = {
    catalog: { items: { spell_book_novice: { material: "BOOK", "custom-model-data": 500001 } } },
    spellbooks: { "spell-books": [{ id: "spell_book_novice", "custom-model-data": 500001 }] }
  };
  const ctx = makeCtx(fixtures);
  const warnings = computeCmdWarnings(ctx, "catalog", {}, fixtures.catalog);
  assert.deepEqual(warnings, [], "spell_book_* の catalog/spellbooks ミラーは正規重複");
});

test("H-1: catalog↔spellbooks:catalysts (触媒) も正規ミラーなので警告しない", () => {
  // 実データの ember_wand は catalog.yml と spellbooks.yml catalysts の両方に同一(material,cmd)で
  // 存在するのが正規形。ミラー判定は base(":"より前=spellbooks)で行うため catalysts も除外される (N-1)。
  const fixtures = {
    catalog: { items: { foo: { material: "BLAZE_ROD", "custom-model-data": 7 } } },
    spellbooks: { catalysts: { ember_wand: { material: "BLAZE_ROD", "custom-model-data": 7 } } }
  };
  const ctx = makeCtx(fixtures);
  const warnings = computeCmdWarnings(ctx, "catalog", {}, fixtures.catalog);
  assert.deepEqual(warnings, [], "触媒の catalog/spellbooks ミラーは正規重複");
});

test("予約値との衝突は新規発生時のみ警告される", () => {
  const fixtures = {
    catalog: { items: { foo: { material: "IRON_INGOT", "custom-model-data": 300001 } } }
  };
  const ctx = makeCtx(fixtures);
  const warnings = computeCmdWarnings(ctx, "catalog", {}, fixtures.catalog);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /予約済みCMD/);
});

test("既に存在していた予約値の重複は再保存しても警告しない", () => {
  const before = { items: { foo: { material: "IRON_INGOT", "custom-model-data": 300001 } } };
  const after = { items: { foo: { material: "IRON_INGOT", "custom-model-data": 300001, "display-name": "changed" } } };
  const fixtures = { catalog: after };
  const ctx = makeCtx(fixtures);
  const warnings = computeCmdWarnings(ctx, "catalog", before, after);
  assert.deepEqual(warnings, [], "既存の重複は再警告しない");
});

test("catalog↔sourcejars のような非ミラーファイル間の新規重複は警告する", () => {
  const fixtures = {
    catalog: { items: { foo: { material: "DECORATED_POT", "custom-model-data": 55 } } },
    sourcejars: { jars: { source_jar: { material: "DECORATED_POT", "custom-model-data": 55 } } }
  };
  const ctx = makeCtx(fixtures);
  const warnings = computeCmdWarnings(ctx, "catalog", {}, fixtures.catalog);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /他ファイルと重複/);
});
