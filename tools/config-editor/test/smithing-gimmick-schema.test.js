"use strict";

// stats/smithing-gimmick.yml (tf-smithing-gimmick) のスキーマ検証。
// 唯一のキー auto-mode-multiplier は SmithingGimmickConfig#clamp01 と同じ [0,1] 範囲。

const { test } = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const { validate } = require("../lib/schema.js");
const { readConfig } = require("../lib/yamlio.js");

const SMITHING_GIMMICK_PATH = path.join(
  __dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources", "stats", "smithing-gimmick.yml"
);

test("tf-smithing-gimmick: 0〜1のauto-mode-multiplierはエラーなし", () => {
  assert.deepStrictEqual(validate("tf-smithing-gimmick", { "auto-mode-multiplier": 0.25 }), []);
  assert.deepStrictEqual(validate("tf-smithing-gimmick", { "auto-mode-multiplier": 0 }), []);
  assert.deepStrictEqual(validate("tf-smithing-gimmick", { "auto-mode-multiplier": 1 }), []);
});

test("tf-smithing-gimmick: 空ルート/キー無しは許容", () => {
  assert.deepStrictEqual(validate("tf-smithing-gimmick", null), []);
  assert.deepStrictEqual(validate("tf-smithing-gimmick", {}), []);
});

test("tf-smithing-gimmick: 範囲外/非数値のauto-mode-multiplierはエラー", () => {
  assert.strictEqual(validate("tf-smithing-gimmick", { "auto-mode-multiplier": 1.5 }).length, 1);
  assert.strictEqual(validate("tf-smithing-gimmick", { "auto-mode-multiplier": -0.1 }).length, 1);
  assert.strictEqual(validate("tf-smithing-gimmick", { "auto-mode-multiplier": "0.25" }).length, 1);
});

test("tf-smithing-gimmick: 実データはスキーマを通り、ロスレスにパースできる", (t) => {
  const cfg = readConfig(SMITHING_GIMMICK_PATH);
  if (!cfg.exists) {
    t.skip("smithing-gimmick.yml が見つからない環境ではスキップ");
    return;
  }
  assert.deepStrictEqual(validate("tf-smithing-gimmick", cfg.data), []);
  assert.strictEqual(typeof cfg.data["auto-mode-multiplier"], "number");
});
