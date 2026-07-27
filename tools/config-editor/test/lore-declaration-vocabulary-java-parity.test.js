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
const {
  TRIGGER_WHEN,
  TRIGGER_WHEN_LABELS,
  SOURCE_SCOPE,
  SOURCE_SCOPE_LABELS,
  APPLIES_TO,
  APPLIES_TO_LABELS,
  STACKING,
  STACKING_LABELS,
} = require("../lib/lore-declaration-vocabulary");

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

// label() の switch 式から "case X -> "日本語ラベル";" のペアを抽出する(段階3)。
// enum定数の並び抽出とは別に、ファイル全体を対象に正規表現で拾う(label()はコンストール宣言の
// 後にあるので、上の parseJavaEnumConstants の走査範囲(最初の';'まで)には含まれない)。
function parseJavaLabels(javaSource) {
  const labels = {};
  const pattern = /case\s+([A-Z][A-Z0-9_]*)\s*->\s*"((?:[^"\\]|\\.)*)"/g;
  let m;
  while ((m = pattern.exec(javaSource)) !== null) {
    labels[m[1]] = m[2];
  }
  return labels;
}

function javaSourceOf(fileName) {
  return fs.readFileSync(path.join(JAVA_STATS_DIR, fileName), "utf8");
}

function javaEnumConstants(fileName) {
  return parseJavaEnumConstants(javaSourceOf(fileName));
}

const CASES = [
  { file: "StatTriggerWhen.java", js: TRIGGER_WHEN, jsLabels: TRIGGER_WHEN_LABELS, label: "TRIGGER_WHEN / StatTriggerWhen" },
  { file: "StatSourceScope.java", js: SOURCE_SCOPE, jsLabels: SOURCE_SCOPE_LABELS, label: "SOURCE_SCOPE / StatSourceScope" },
  { file: "StatAppliesTo.java", js: APPLIES_TO, jsLabels: APPLIES_TO_LABELS, label: "APPLIES_TO / StatAppliesTo" },
  { file: "StatStacking.java", js: STACKING, jsLabels: STACKING_LABELS, label: "STACKING / StatStacking" },
];

for (const { file, js, jsLabels, label } of CASES) {
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

  test(`Java ${label} の label() 文言が完全一致する(段階3: /tf stats detail)`, () => {
    const javaLabels = parseJavaLabels(javaSourceOf(file));
    const javaLabelKeys = Object.keys(javaLabels);
    assert.ok(javaLabelKeys.length > 0, `regex extracted zero labels from ${file}'s label(); pattern likely stale`);

    const javaConstants = new Set(javaEnumConstants(file));
    const javaLabelSet = new Set(javaLabelKeys);
    const jsLabelSet = new Set(Object.keys(jsLabels || {}));

    const constantsMissingLabel = [...javaConstants].filter((c) => !javaLabelSet.has(c));
    assert.deepEqual(constantsMissingLabel, [], `${label}: enum constant(s) without a label() case: ${constantsMissingLabel.join(", ")}`);

    const missingFromJs = [...javaLabelSet].filter((c) => !jsLabelSet.has(c));
    const missingFromJava = [...jsLabelSet].filter((c) => !javaLabelSet.has(c));
    assert.deepEqual(missingFromJs, [], `${label}: label present in Java but missing from JS *_LABELS: ${missingFromJs.join(", ")}`);
    assert.deepEqual(missingFromJava, [], `${label}: label present in JS *_LABELS but missing from Java: ${missingFromJava.join(", ")}`);

    for (const constant of javaLabelSet) {
      assert.equal(
        jsLabels[constant],
        javaLabels[constant],
        `${label}: label text mismatch for ${constant} (Java='${javaLabels[constant]}' JS='${jsLabels[constant]}')`
      );
    }
  });
}
