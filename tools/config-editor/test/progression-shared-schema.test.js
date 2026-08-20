"use strict";

// skills/base/*_progression.yml (16ファイル) の共通スキーマを実データから検証する。
// config-editor の skill-exp 画面 (buildSkillExpForm, tf-forms.js) は experience.max_level と
// experience.exp_level_curve を専用欄にし、それ以外のEXPレート/行動テーブルは再帰描画する。
// producer追加時に action table 名をUIの許可リストへ追記する運用へ戻ると、設定は存在するのに
// editorから変更できない状態が再発するため、汎用描画であることを回帰的に確認する。

const { test } = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const fs = require("node:fs");
const { readConfig } = require("../lib/yamlio.js");

const BASE_DIR = path.join(__dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources", "skills", "base");

global.window = global.window || {};
require("../public/js/labels.js");
const LABELS = global.window.LABELS;

const SKILL_IDS = [
  "alchemy", "archery", "digging", "enchanting", "farming", "fishing",
  "heavy_armor", "heavy_weapons", "light_armor", "light_weapons",
  "mining", "power", "smithing", "woodcutting", "ars_magic", "ars_smithing"
];

const TF_FORMS_SRC = fs.readFileSync(
  path.join(__dirname, "..", "public", "js", "tf-forms.js"),
  "utf8"
);

test("16個の progression ファイルが全て実在する", (t) => {
  if (!fs.existsSync(BASE_DIR)) { t.skip("skills/base ディレクトリが無い環境ではスキップ"); return; }
  for (const id of SKILL_IDS) {
    const file = path.join(BASE_DIR, `${id}_progression.yml`);
    assert.ok(fs.existsSync(file), `${id}_progression.yml が存在しない`);
  }
});

test("全16スキル共通: experience.max_level と experience.exp_level_curve は必須", (t) => {
  if (!fs.existsSync(BASE_DIR)) { t.skip("skills/base ディレクトリが無い環境ではスキップ"); return; }
  for (const id of SKILL_IDS) {
    const file = path.join(BASE_DIR, `${id}_progression.yml`);
    const cfg = readConfig(file);
    assert.ok(cfg.exists, `${id}: ファイルが読めない`);
    const exp = cfg.data && cfg.data.experience;
    assert.ok(exp && typeof exp === "object", `${id}: experience セクションが無い`);
    assert.strictEqual(typeof exp.max_level, "number", `${id}: experience.max_level が数値でない`);
    assert.strictEqual(typeof exp.exp_level_curve, "string", `${id}: experience.exp_level_curve が文字列でない`);
  }
});

function numericTableLeaves(value, prefix, out) {
  if (typeof value === "number") {
    out.push(prefix);
    return;
  }
  if (!value || typeof value !== "object" || Array.isArray(value)) return;
  for (const [key, child] of Object.entries(value)) {
    numericTableLeaves(child, prefix ? `${prefix}.${key}` : key, out);
  }
  return out;
}

test("progressionの行動EXP表は実データ上すべて数値leafとして列挙できる", (t) => {
  if (!fs.existsSync(BASE_DIR)) { t.skip("skills/base ディレクトリが無い環境ではスキップ"); return; }
  const leaves = [];
  for (const id of SKILL_IDS) {
    const cfg = readConfig(path.join(BASE_DIR, `${id}_progression.yml`));
    for (const [key, value] of Object.entries(cfg.data.experience || {})) {
      if (!value || typeof value !== "object" || Array.isArray(value)) continue;
      numericTableLeaves(value, `${id}.${key}`, leaves);
    }
  }
  assert.ok(leaves.length > 100, `行動EXP表の数値leafが少なすぎる: ${leaves.length}`);
  assert.ok(leaves.some((p) => p.startsWith("farming.entity_breed.")), "farming.entity_breed が見つからない");
  assert.ok(leaves.some((p) => p.startsWith("enchanting.exp_gain.enchantment_base.")),
    "enchanting.exp_gain.enchantment_base が見つからない");
});

test("防具progressionの追加EXPキーは日本語ラベルへ解決される", (t) => {
  if (!fs.existsSync(BASE_DIR)) { t.skip("skills/base ディレクトリが無い環境ではスキップ"); return; }
  for (const id of ["light_armor", "heavy_armor"]) {
    const cfg = readConfig(path.join(BASE_DIR, `${id}_progression.yml`));
    assert.ok(Object.prototype.hasOwnProperty.call(cfg.data.experience, "pvp_multiplier_exponent"),
      `${id}: experience.pvp_multiplier_exponent が無い`);
    assert.ok(Object.prototype.hasOwnProperty.call(cfg.data.experience, "entity_exp_multipliers"),
      `${id}: experience.entity_exp_multipliers が無い`);
  }
  for (const key of ["pvp_multiplier_exponent", "entity_exp_multipliers"]) {
    assert.notEqual(LABELS.fieldLabel(key), key, `${key} が生IDのまま表示される`);
  }
});

test("buildSkillExpFormはEXPキーの許可リストを持たず、experienceを再帰描画する", () => {
  assert.doesNotMatch(TF_FORMS_SRC, /\bRATE_KEYS\b/,
    "新しいEXPキーがeditorから消える許可リスト方式へ戻っている");
  assert.match(TF_FORMS_SRC, /scalarSectionBody\(\s*exp,/,
    "progression.experience が汎用の再帰フォームへ渡されていない");
  assert.match(TF_FORMS_SRC, /PROGRESSION_DEDICATED_KEYS,\s*\{\s*forceFloat:\s*true\s*\}/,
    "progressionのEXP表で小数入力が許可されていない");
});
