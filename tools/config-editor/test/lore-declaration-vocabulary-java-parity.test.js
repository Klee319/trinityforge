"use strict";

// lib/lore-declaration-vocabulary.js の4配列(TRIGGER_WHEN/SOURCE_SCOPE/APPLIES_TO/STACKING)が、
// Java側の正典 4 enum とズレていないことを検証する。
// (gate-vocabulary-java-parity.test.js / spell-form-vocabulary-java-parity.test.js と同じ手法:
//  Java/JSは別言語でクロスコンパイル呼び出しできないため、Javaソースをテキストとして読み取り
//  正規表現でenum定数名を抽出し、JS配列と突き合わせる。)

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { TRIGGER_WHEN, SOURCE_SCOPE, APPLIES_TO, STACKING } = require("../lib/lore-declaration-vocabulary");

const JAVA_STATS_DIR = path.join(__dirname, "..", "..", "..", "TrinityForge", "src", "main", "java", "com", "trinityforge", "stats");

// enum定数の並びを抽出する: クラス冒頭の "public enum X {" から最初の ";" までの間にある
// 大文字スネークケース識別子を列挙値として拾う(Javadocコメント行は除外)。
function parseJavaEnumConstants(javaSource) {
  const bodyMatch = javaSource.match(/public enum \w+\s*\{([\s\S]*?);/);
  assert.ok(bodyMatch, "could not locate 'public enum X { ... ; ' body");
  const body = bodyMatch[1];
  const withoutComments = body.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  const matches = withoutComments.match(/\b[A-Z][A-Z0-9_]*\b/g) || [];
  return matches;
}

function javaEnumConstants(fileName) {
  const javaSource = fs.readFileSync(path.join(JAVA_STATS_DIR, fileName), "utf8");
  return parseJavaEnumConstants(javaSource);
}

const CASES = [
  { file: "StatTriggerWhen.java", js: TRIGGER_WHEN, label: "TRIGGER_WHEN / StatTriggerWhen" },
  { file: "StatSourceScope.java", js: SOURCE_SCOPE, label: "SOURCE_SCOPE / StatSourceScope" },
  { file: "StatAppliesTo.java", js: APPLIES_TO, label: "APPLIES_TO / StatAppliesTo" },
  { file: "StatStacking.java", js: STACKING, label: "STACKING / StatStacking" },
];

for (const { file, js, label } of CASES) {
  test(`${file} が読める(パスが壊れていないこと)`, () => {
    assert.ok(fs.existsSync(path.join(JAVA_STATS_DIR, file)), `not found: ${file}`);
  });

  test(`Java ${label} の定数集合が完全一致する`, () => {
    const javaConstants = javaEnumConstants(file);
    assert.ok(javaConstants.length > 0, `regex extracted zero constants from ${file}; pattern likely stale`);

    const javaSet = new Set(javaConstants);
    const jsSet = new Set(js);

    const missingFromJs = [...javaSet].filter((c) => !jsSet.has(c));
    const missingFromJava = [...jsSet].filter((c) => !javaSet.has(c));

    assert.deepEqual(missingFromJs, [], `${label}: present in Java but missing from JS: ${missingFromJs.join(", ")}`);
    assert.deepEqual(missingFromJava, [], `${label}: present in JS but missing from Java: ${missingFromJava.join(", ")}`);
  });
}
