"use strict";

// lib/gate-vocabulary.js の FEATURES 固定語彙が、Java側の正典
// com.trinityforge.skilltree.effects.FeatureEffectRegistry と id/param でズレていないことを検証する。
//
// 2026-07-24: feature:break-vanilla-exp がJava FeatureEffectRegistryにはあるのに、この
// gate-vocabulary.js FEATURES配列から漏れ、editorから配置不能になる事故が起きた
// (session-2026-07-24-3bugs-6specs-review参照)。Java/JSは別言語でクロスコンパイル呼び出しできないため、
// Javaソースファイルをテキストとして読み取り正規表現で add(map, "id", "label", FeatureEffectParam.X) 行を
// 抽出し、FEATURESと突き合わせる(フル言語間実行より脆いが、このリポジトリで唯一実行可能な自動ドリフト検知)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { FEATURES } = require("../lib/gate-vocabulary");

const JAVA_REGISTRY_PATH = path.join(
  __dirname,
  "..",
  "..",
  "..",
  "TrinityForge",
  "src",
  "main",
  "java",
  "com",
  "trinityforge",
  "skilltree",
  "effects",
  "FeatureEffectRegistry.java"
);

// add(map, "id", "label", FeatureEffectParam.PARAM); の行を抽出する。
const ADD_LINE_PATTERN = /add\(map,\s*"([^"]+)",\s*"([^"]*)",\s*FeatureEffectParam\.(\w+)\);/g;

function parseJavaRegistry(javaSource) {
  const entries = [];
  let match;
  while ((match = ADD_LINE_PATTERN.exec(javaSource)) !== null) {
    const [, id, label, param] = match;
    entries.push({ id, label, param: param.toLowerCase() });
  }
  return entries;
}

test("FeatureEffectRegistry.java が読める(パスが壊れていないこと)", () => {
  assert.ok(fs.existsSync(JAVA_REGISTRY_PATH), `not found: ${JAVA_REGISTRY_PATH}`);
});

test("Java FeatureEffectRegistry と JS FEATURES の id 集合が完全一致する", () => {
  const javaSource = fs.readFileSync(JAVA_REGISTRY_PATH, "utf8");
  const javaEntries = parseJavaRegistry(javaSource);
  assert.ok(javaEntries.length > 0, "regex extracted zero entries; pattern likely stale vs. Java source shape");

  const javaIds = new Set(javaEntries.map((e) => e.id));
  const jsIds = new Set(FEATURES.map((f) => f.id));

  const missingFromJs = [...javaIds].filter((id) => !jsIds.has(id));
  const missingFromJava = [...jsIds].filter((id) => !javaIds.has(id));

  assert.deepEqual(missingFromJs, [], `feature id(s) present in Java but missing from gate-vocabulary.js FEATURES: ${missingFromJs.join(", ")}`);
  assert.deepEqual(missingFromJava, [], `feature id(s) present in gate-vocabulary.js FEATURES but missing from Java: ${missingFromJava.join(", ")}`);
});

test("Java FeatureEffectRegistry と JS FEATURES の param が id ごとに一致する", () => {
  const javaSource = fs.readFileSync(JAVA_REGISTRY_PATH, "utf8");
  const javaById = new Map(parseJavaRegistry(javaSource).map((e) => [e.id, e.param]));
  const jsById = new Map(FEATURES.map((f) => [f.id, f.param]));

  const mismatches = [];
  for (const [id, javaParam] of javaById) {
    const jsParam = jsById.get(id);
    if (jsParam !== undefined && jsParam !== javaParam) {
      mismatches.push(`${id}: java=${javaParam} js=${jsParam}`);
    }
  }
  assert.deepEqual(mismatches, [], `param mismatch(es): ${mismatches.join("; ")}`);
});

test("正規表現が現行Javaの並びからサンプル抽出できる(vein-mining=scale, dismantle-unlock=level)", () => {
  const javaSource = fs.readFileSync(JAVA_REGISTRY_PATH, "utf8");
  const entries = parseJavaRegistry(javaSource);
  const byId = new Map(entries.map((e) => [e.id, e.param]));
  assert.equal(byId.get("vein-mining"), "scale");
  assert.equal(byId.get("tree-fell"), "scale");
  assert.equal(byId.get("area-harvest"), "scale");
  assert.equal(byId.get("haste-active-mining"), "scale");
  assert.equal(byId.get("dismantle-unlock"), "level");
  assert.equal(byId.get("auto-replant"), "none");
});
