"use strict";

// public/js/functional-items.js の MATERIAL_EDITABLE_IDS が、Java側の正典
// com.arspaper.item.FunctionalItemConfig#MATERIAL_OVERRIDE_ALLOWED とズレていないことを検証する。
// editor側でこのIDリストを二重管理せず(要件: 「ハードコードで二重管理しないこと」)、
// Java側のソースをテキストとして読み取り正規表現で Set.of(...) の文字列リテラルを抽出し、
// JS側の定数と突き合わせる(lib/gate-vocabulary.js の FEATURES と同じ手法。
// test/gate-vocabulary-java-parity.test.js 参照)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { MATERIAL_EDITABLE_IDS } = require("../public/js/functional-items.js");

const JAVA_CONFIG_PATH = path.join(
  __dirname,
  "..",
  "..",
  "..",
  "fork-handoff",
  "arspaper",
  "fork",
  "src",
  "main",
  "java",
  "com",
  "arspaper",
  "item",
  "FunctionalItemConfig.java"
);

// MATERIAL_OVERRIDE_ALLOWED = Set.of(\n "id1", "id2", ...\n); ブロックを抽出する。
const ALLOWED_BLOCK_PATTERN = /MATERIAL_OVERRIDE_ALLOWED\s*=\s*Set\.of\(([\s\S]*?)\);/;
const STRING_LITERAL_PATTERN = /"([^"]+)"/g;

function parseJavaAllowedIds(javaSource) {
  const blockMatch = ALLOWED_BLOCK_PATTERN.exec(javaSource);
  if (!blockMatch) return null;
  const ids = [];
  let m;
  while ((m = STRING_LITERAL_PATTERN.exec(blockMatch[1])) !== null) {
    ids.push(m[1]);
  }
  return ids;
}

test("FunctionalItemConfig.java が読める(パスが壊れていないこと)", () => {
  assert.ok(fs.existsSync(JAVA_CONFIG_PATH), `not found: ${JAVA_CONFIG_PATH}`);
});

test("Java MATERIAL_OVERRIDE_ALLOWED と JS MATERIAL_EDITABLE_IDS が完全一致する", () => {
  const javaSource = fs.readFileSync(JAVA_CONFIG_PATH, "utf8");
  const javaIds = parseJavaAllowedIds(javaSource);
  assert.ok(javaIds !== null, "MATERIAL_OVERRIDE_ALLOWED = Set.of(...) が見つからない; 正規表現がJavaソースの形と食い違っている可能性");
  assert.ok(javaIds.length > 0, "regex extracted zero ids; pattern likely stale vs. Java source shape");

  const javaSet = new Set(javaIds);
  const jsSet = new Set(MATERIAL_EDITABLE_IDS);

  const missingFromJs = [...javaSet].filter((id) => !jsSet.has(id));
  const missingFromJava = [...jsSet].filter((id) => !javaSet.has(id));

  assert.deepEqual(missingFromJs, [], `id(s) present in Java allow-list but missing from JS MATERIAL_EDITABLE_IDS: ${missingFromJs.join(", ")}`);
  assert.deepEqual(missingFromJava, [], `id(s) present in JS MATERIAL_EDITABLE_IDS but missing from Java allow-list: ${missingFromJava.join(", ")}`);
});
