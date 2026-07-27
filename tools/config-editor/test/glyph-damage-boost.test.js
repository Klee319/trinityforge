"use strict";

// stats/glyph-damage-boost.yml (T1, 2026-07-25) の登録・バリデーション・往復ロスレスのテスト。
// レジストリ登録 (id: glyph-damage-boost) / schema "tf-glyph-damage-boost" / グリフ(glyphs)画面の
// コンパニオンとして HIDDEN_CONFIG_IDS 経由でサイドバー非表示になっている前提。

const path = require("path");
const { test } = require("node:test");
const assert = require("node:assert");
const { validate } = require("../lib/schema.js");
const { findById } = require("../lib/registry.js");
const { readConfig, serializeConfig } = require("../lib/yamlio.js");

test("registry: glyph-damage-boost が登録されている (base=trinityforge)", () => {
  const entry = findById("glyph-damage-boost");
  assert.ok(entry, "registry entry が見つかりません");
  assert.strictEqual(entry.base, "trinityforge");
  assert.strictEqual(entry.rel, "stats/glyph-damage-boost.yml");
  assert.strictEqual(entry.schema, "tf-glyph-damage-boost");
});

test("validate: サンプルファイルの形状(boosted-glyphs: [harm])はエラーなし", () => {
  const errors = validate("tf-glyph-damage-boost", { "boosted-glyphs": ["harm"] });
  assert.deepStrictEqual(errors, []);
});

test("validate: 空配列もエラーなし(ボーナス対象なし)", () => {
  assert.deepStrictEqual(validate("tf-glyph-damage-boost", { "boosted-glyphs": [] }), []);
});

test("validate: 未設定(キーなし)もエラーなし", () => {
  assert.deepStrictEqual(validate("tf-glyph-damage-boost", {}), []);
});

test("validate: boosted-glyphs が配列でなければエラー", () => {
  const errors = validate("tf-glyph-damage-boost", { "boosted-glyphs": "harm" });
  assert.ok(errors.some((e) => /boosted-glyphs は配列である必要があります/.test(e)), JSON.stringify(errors));
});

test("validate: 空文字列/非文字列の要素はエラー", () => {
  const errors = validate("tf-glyph-damage-boost", { "boosted-glyphs": ["harm", "", 5, null] });
  assert.ok(errors.some((e) => /boosted-glyphs\[1\]/.test(e)), JSON.stringify(errors));
  assert.ok(errors.some((e) => /boosted-glyphs\[2\]/.test(e)), JSON.stringify(errors));
  assert.ok(errors.some((e) => /boosted-glyphs\[3\]/.test(e)), JSON.stringify(errors));
});

test("validate: ルートがマップでなければエラー", () => {
  const errors = validate("tf-glyph-damage-boost", []);
  assert.ok(errors.some((e) => /ルートはマップである必要があります/.test(e)), JSON.stringify(errors));
});

test("round-trip: 実ファイルを読み込み、値を変更してシリアライズしても読み戻せる (ヘッダコメント温存)", () => {
  const entry = findById("glyph-damage-boost");
  const toolConfig = require(path.join(__dirname, "..", "tool-config.json"));
  const baseDir = path.resolve(__dirname, "..", toolConfig.basePaths[entry.base]);
  const absPath = path.join(baseDir, ...entry.rel.split("/"));

  const original = readConfig(absPath);
  assert.strictEqual(original.exists, true, `ファイルが存在しません: ${absPath}`);
  assert.deepStrictEqual(original.data["boosted-glyphs"], ["harm"]);
  assert.ok(/^# TrinityForge/.test(original.raw), "先頭ヘッダコメントを前提にしたテストです");

  // フロント編集を模して要素を追加する (往復ロスレス方針: 既存 data を直接編集)。
  const edited = original.data;
  edited["boosted-glyphs"] = ["harm", "curse"];
  const errors = validate("tf-glyph-damage-boost", edited);
  assert.deepStrictEqual(errors, []);

  const serialized = serializeConfig(edited, original.raw);
  // ヘッダコメント(ファイル先頭の# 行)が保存後も残ることを確認する。
  assert.ok(serialized.startsWith(original.raw.split(/\r?\n/)[0]), "ヘッダの先頭行が保持されていません");
  assert.ok(/boosted-glyphs:/.test(serialized));
  assert.ok(/- harm/.test(serialized));
  assert.ok(/- curse/.test(serialized));

  // 再パースして元の構造(配列の内容)が失われていないことを確認する。
  const YAML = require("yaml");
  const reparsed = YAML.parse(serialized);
  assert.deepStrictEqual(reparsed["boosted-glyphs"], ["harm", "curse"]);
});
