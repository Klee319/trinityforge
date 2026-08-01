"use strict";

// 2026-08-01 分離: craft-quality.yml の workbench/ritual 節 (作業台/儀式で別々の品質ばらつき補正)。
//
// この節の既定値は「分離しただけではバランスが動かない」ことの唯一の担保なので、
//   出荷yml / Java(CraftQualityConfig.SpreadTuning.IDENTITY) / lib/schema.js / public/js/tf-forms.js
// の4本が全て scale=1.0 / flat=0.0 で一致していなければならない。
// editor の既定値が Java とズレると「開いて保存しただけで yml の意味が変わる」事故になり、
// しかも条件が緩む方向なので実プレイでは気付けない (editor-normalize-default-drift)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..");
const REPO = path.resolve(ROOT, "..", "..");
const SHIPPED_YML = path.join(REPO, "TrinityForge", "src", "main", "resources", "stats", "craft-quality.yml");
const JAVA_CONFIG = path.join(REPO, "TrinityForge", "src", "main", "java", "com", "trinityforge",
  "config", "domains", "CraftQualityConfig.java");
const TF_FORMS = path.join(ROOT, "public", "js", "tf-forms.js");

const FIELDS = ["upswing-scale", "upswing-flat", "downswing-reduction-scale", "downswing-reduction-flat"];
const IDENTITY = { "upswing-scale": 1, "upswing-flat": 0, "downswing-reduction-scale": 1, "downswing-reduction-flat": 0 };

test("出荷 craft-quality.yml は workbench/ritual 節を持ち、既定値は恒等(=分離前と同じ挙動)", () => {
  const doc = YAML.parse(fs.readFileSync(SHIPPED_YML, "utf8"));
  for (const section of ["workbench", "ritual"]) {
    assert.ok(doc && doc[section] && typeof doc[section] === "object", `${section} 節が無い`);
    for (const key of FIELDS) {
      assert.equal(doc[section][key], IDENTITY[key], `${section}.${key} が恒等値でない`);
    }
  }
});

test("lib/schema.js の既定値は Java の IDENTITY と一致する", () => {
  const { TF_CRAFT_QUALITY_SPREAD_DEFAULTS } = require(path.join(ROOT, "lib", "schema.js"));
  for (const key of FIELDS) {
    assert.equal(TF_CRAFT_QUALITY_SPREAD_DEFAULTS[key], IDENTITY[key], `schema.js の ${key} がズレている`);
  }
});

test("public/js/tf-forms.js の既定値も同じ (lib/ と public/js/ の2本ミラー)", () => {
  const src = fs.readFileSync(TF_FORMS, "utf8");
  const block = src.match(/const CRAFT_QUALITY_SPREAD_DEFAULTS = \{([\s\S]*?)\};/);
  assert.ok(block, "tf-forms.js に CRAFT_QUALITY_SPREAD_DEFAULTS が無い");
  for (const key of FIELDS) {
    const hit = block[1].match(new RegExp(`"${key}":\\s*([-0-9.]+)`));
    assert.ok(hit, `tf-forms.js に ${key} が無い`);
    assert.equal(Number(hit[1]), IDENTITY[key], `tf-forms.js の ${key} がズレている`);
  }
});

test("Java 側 SpreadTuning.IDENTITY も (1.0, 0.0, 1.0, 0.0)", () => {
  const src = fs.readFileSync(JAVA_CONFIG, "utf8");
  assert.match(src, /IDENTITY\s*=\s*new SpreadTuning\(1\.0,\s*0\.0,\s*1\.0,\s*0\.0\)/);
});

test("schema バリデータが workbench/ritual の型と未知フィールドを弾く", () => {
  const { validate } = require(path.join(ROOT, "lib", "schema.js"));
  assert.equal(validate("tf-craft-quality", {
    workbench: { "upswing-scale": 1.0, "upswing-flat": 0.0 },
    ritual: { "downswing-reduction-scale": 0.5 }
  }).length, 0, "正常な節でエラーが出てはいけない");

  assert.ok(validate("tf-craft-quality", { ritual: { "upswing-scale": -1 } }).length > 0,
    "倍率の負値は弾く");
  assert.ok(validate("tf-craft-quality", { ritual: { "upswing-scale": "x" } }).length > 0,
    "数値でない倍率は弾く");
  assert.ok(validate("tf-craft-quality", { workbench: { "typo-field": 1 } }).length > 0,
    "未知フィールドは弾く(綴り間違いが無言で無視されない)");
  assert.equal(validate("tf-craft-quality", { mode: { "base-quality": 0 } }).length, 0,
    "節が無い旧ymlはそのまま通る(恒等へ落ちる)");
});
