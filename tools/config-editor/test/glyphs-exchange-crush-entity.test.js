"use strict";

// glyphs.yml (ars-glyphs) の exchange_tiers / crush_map / entity_exchange_pairs スキーマ検証。
// これら3構造は編集専用スクリーンが無かった箇所 (config-editor screen 1)。
// ロスレス往復(パース→検証→再シリアライズで元データと一致)と、不正なMaterial/EntityType名の
// 拒否を確認する。

const { test } = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const YAML = require("yaml");
const { validate } = require("../lib/schema.js");
const { readConfig, serializeConfig } = require("../lib/yamlio.js");

const GLYPHS_PATH = path.join(
  __dirname, "..", "..", "..", "fork-handoff", "arspaper", "fork",
  "src", "main", "resources", "glyphs.yml"
);

test("ars-glyphs: exchange_tiers/crush_map/entity_exchange_pairs の正常データはエラーなし", () => {
  const errors = validate("ars-glyphs", {
    glyphs: {},
    exchange_tiers: [
      ["DIRT", "COARSE_DIRT", "MUD"],
      ["STONE", "COBBLESTONE"]
    ],
    crush_map: { STONE: "COBBLESTONE", DANDELION: "YELLOW_DYE" },
    entity_exchange_pairs: [
      ["HOGLIN", "ZOGLIN"],
      ["SKELETON", "STRAY", "BOGGED"]
    ]
  });
  assert.deepStrictEqual(errors, []);
});

test("ars-glyphs: exchange_tiers は配列の配列である必要がある", () => {
  let errors = validate("ars-glyphs", { exchange_tiers: { not: "array" } });
  assert.ok(errors.some((e) => /exchange_tiers/.test(e)));

  errors = validate("ars-glyphs", { exchange_tiers: [[]] });
  assert.ok(errors.some((e) => /exchange_tiers\[0\]/.test(e)));

  errors = validate("ars-glyphs", { exchange_tiers: [["stone"]] });
  assert.ok(errors.some((e) => /exchange_tiers\[0\]\[0\]/.test(e)), "小文字Materialは拒否される");
});

test("ars-glyphs: crush_map は Material -> Material のマップである必要がある", () => {
  let errors = validate("ars-glyphs", { crush_map: ["not", "a map"] });
  assert.ok(errors.some((e) => /crush_map/.test(e)));

  errors = validate("ars-glyphs", { crush_map: { STONE: 123 } });
  assert.ok(errors.some((e) => /crush_map\.STONE/.test(e)), "変換後が非文字列は拒否される");
});

test("ars-glyphs: entity_exchange_pairs は2要素以上のEntityType配列の配列である必要がある", () => {
  let errors = validate("ars-glyphs", { entity_exchange_pairs: [["ZOMBIE"]] });
  assert.ok(errors.some((e) => /entity_exchange_pairs\[0\]/.test(e)), "1要素のサイクルは拒否される");

  errors = validate("ars-glyphs", { entity_exchange_pairs: [["zombie", "husk"]] });
  assert.ok(errors.some((e) => /entity_exchange_pairs\[0\]\[0\]/.test(e)), "小文字EntityTypeは拒否される");
});

test("ars-glyphs: 実際の glyphs.yml のロード→再シリアライズはロスレス往復する", (t) => {
  const cfg = readConfig(GLYPHS_PATH);
  if (!cfg.exists) {
    t.skip("glyphs.yml が見つからない環境ではスキップ");
    return;
  }
  const data = cfg.data;
  assert.ok(Array.isArray(data.exchange_tiers) && data.exchange_tiers.length > 0, "exchange_tiers が読める");
  assert.ok(data.crush_map && typeof data.crush_map === "object", "crush_map が読める");
  assert.ok(Array.isArray(data.entity_exchange_pairs) && data.entity_exchange_pairs.length > 0, "entity_exchange_pairs が読める");

  // 現行データはスキーマ検証を通る (実データの健全性を兼ねて確認)。
  assert.deepStrictEqual(validate("ars-glyphs", data), []);

  // 未編集のまま serializeConfig -> readConfig 相当のパースを通しても構造データは変化しない
  // (本文コメントの消失は yamlio.js の既知の制約であり、このテストの対象外)。
  const roundTripped = YAML.parse(serializeConfig(data, cfg.raw));
  assert.deepStrictEqual(roundTripped.exchange_tiers, data.exchange_tiers);
  assert.deepStrictEqual(roundTripped.crush_map, data.crush_map);
  assert.deepStrictEqual(roundTripped.entity_exchange_pairs, data.entity_exchange_pairs);
});
