"use strict";

// タスク1 (2026-07-27) 回帰テスト: skill-exp.yml (tf-skill-exp) 画面で項目名が生のキーIDのまま
// 表示されるバグの修正確認。
//
// buildSkillExpForm (tf-forms.js) は入れ子オブジェクトのスカラーキー(gathering/ars-smithing/
// smithing/ars-magic/combat/spot-diminishing/level-diminishing)を汎用の scalarSectionBody 経由で
// 描画し、各フィールドのラベルは window.fieldLabelEl(key) -> window.LABELS.fieldLabel(key) が
// labels.js の FIELD_LABELS 辞書を引いて日本語化する。辞書に無いキーは生の英字キーがそのまま
// 表示されてしまう。
//
// このテストは「辞書に載っているか」を1件ずつハードコードして表明するのではなく、実ファイル
// skill-exp.yml を読み込んで全キーを動的に洗い出し、labels.js の解決結果と突き合わせる
// ドリフト検知にする(将来キーが増えたときに自動で落ちる)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

global.window = global.window || {};
require("../public/js/labels.js");
const LABELS = global.window.LABELS;

const repoRoot = path.resolve(__dirname, "..", "..", "..");
const skillExpPath = path.join(repoRoot, "TrinityForge", "src", "main", "resources", "stats", "skill-exp.yml");
const doc = YAML.parse(fs.readFileSync(skillExpPath, "utf8"));

const TF_FORMS_SRC = fs.readFileSync(path.join(__dirname, "..", "public", "js", "tf-forms.js"), "utf8");

// buildSkillExpForm が汎用描画(scalarSectionBody, ラベル辞書経由)に通す入れ子セクション。
// exp-display / level-up は window.fieldLabelEl(key, {label, desc}) の明示指定で描画するため対象外
// (DEDICATED_SECTION_KEYS)。トップレベルスカラー(dungeon-only-exp / outside-dungeon-exp-rate)は
// 専用カードで描画するため、これも別テストで直接確認する。
const GENERIC_SECTION_KEYS = [
  "gathering", "ars-smithing", "smithing", "ars-magic", "combat",
  "spot-diminishing", "level-diminishing"
];

test("前提: skill-exp.yml に汎用描画対象の全セクションが実在する(テスト前提のドリフト検知)", () => {
  for (const key of GENERIC_SECTION_KEYS) {
    assert.ok(doc[key] && typeof doc[key] === "object" && !Array.isArray(doc[key]),
      `skill-exp.yml に ${key} セクションが見つからない(スキーマが変わった場合はこのテストの前提を更新すること)`);
  }
});

test("skill-exp.yml の汎用描画対象キーは全て labels.js で日本語ラベルへ解決される(生IDのまま表示されない)", () => {
  const unresolved = [];
  for (const section of GENERIC_SECTION_KEYS) {
    for (const key of Object.keys(doc[section])) {
      const label = LABELS.fieldLabel(key);
      if (!label || label === key) unresolved.push(`${section}.${key}`);
    }
  }
  assert.deepEqual(unresolved, [],
    `labels.js の FIELD_LABELS に未登録で、生のキーIDのまま表示されるフィールド: ${unresolved.join(", ")}`);
});

test("combat.mode は items.yml 天候(weather)の 'mode' とグローバル辞書上で衝突するため、" +
  "tf-forms.js のセクション別上書き(SECTION_FIELD_OVERRIDES)で文脈固有ラベルへ差し替えられている", () => {
  // グローバル辞書自体は他画面の意味のままで良い(天候用の意味を壊さない)。
  assert.equal(LABELS.fieldLabel("mode"), "天候",
    "グローバル辞書の 'mode' は items.yml 天候用のまま(他画面を壊さないことを確認)");
  assert.match(TF_FORMS_SRC, /SECTION_FIELD_OVERRIDES\s*=\s*\{\s*combat:\s*\{\s*mode:\s*\{/,
    "tf-forms.js に combat.mode 用の SECTION_FIELD_OVERRIDES が見つからない");
  assert.match(TF_FORMS_SRC, /scalarSectionBody\(obj,\s*\{\s*hideKey:\s*true\s*\},\s*SECTION_FIELD_OVERRIDES\[section\]\)/,
    "scalarSectionBody へ SECTION_FIELD_OVERRIDES[section] が渡されていない");
});

test("トップレベルスカラー outside-dungeon-exp-rate が labels.js に登録され、実際に編集可能になっている", () => {
  assert.ok(Object.prototype.hasOwnProperty.call(doc, "outside-dungeon-exp-rate"),
    "前提: skill-exp.yml に outside-dungeon-exp-rate が無い(テスト前提が崩れている)");
  const label = LABELS.fieldLabel("outside-dungeon-exp-rate");
  assert.notEqual(label, "outside-dungeon-exp-rate", "outside-dungeon-exp-rate が labels.js に未登録");
  // 2026-07-27以前は dungeon-only-exp のチェックボックスしか描画されず、この値は編集UIが
  // 存在しなかった(生ID表示ではなく「そもそも出ない」バグ)。dungeon-only-exp カードに追加した。
  assert.match(TF_FORMS_SRC, /working\["outside-dungeon-exp-rate"\]/,
    "tf-forms.js の buildSkillExpForm で outside-dungeon-exp-rate が編集できるようになっていない");
});
