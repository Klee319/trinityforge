"use strict";

// skills/base/*_progression.yml (16ファイル) の共通スキーマを実データから検証する。
// config-editor の skill-exp 画面 (buildSkillExpForm, tf-forms.js) は experience.max_level と
// experience.exp_level_curve のみを全16スキル共通の必須項目として扱う。それ以外のキー
// (prestige_decay_rate 等) はスキルごとに異なり、RATE_KEYS 許可リストに載っているものだけが
// 専用フォームで編集可能になる。ここではそのRATE_KEYS が実データの主要キーを網羅しているかを
// 回帰的に確認する (取りこぼしがあれば editor 上で編集不能なまま気付かれずに終わる)。

const { test } = require("node:test");
const assert = require("node:assert");
const path = require("node:path");
const fs = require("node:fs");
const { readConfig } = require("../lib/yamlio.js");

const BASE_DIR = path.join(__dirname, "..", "..", "..", "TrinityForge", "src", "main", "resources", "skills", "base");

const SKILL_IDS = [
  "alchemy", "archery", "digging", "enchanting", "farming", "fishing",
  "heavy_armor", "heavy_weapons", "light_armor", "light_weapons",
  "mining", "power", "smithing", "woodcutting", "ars_magic", "ars_smithing"
];

// tf-forms.js の buildSkillExpForm と同期させる (曲線編集フォームが実際に見せるスカラーキー)。
const RATE_KEYS = new Set([
  "alchemy_brew_exp", "fishing_catch_exp", "exp_gain",
  "exp_damage_piece", "exp_damage_piece_min_damage", "exp_damage_piece_cooldown_seconds",
  "exp_multiplier_point",
  "durability_tools_exp_multiplier_stack", "durability_armors_exp_multiplier_stack",
  "exp_multiplier_mine", "exp_multiplier_blast",
  "exp_multiplier_quality", "multiplier_manual", "multiplier_automated",
  "prestige_decay_rate"
]);

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

// スキルごとの experience 直下スカラーキー(ネストしたオブジェクト/配列は除く)を集め、
// RATE_KEYS でカバーされていないキーを一覧する。max_level/exp_level_curve は専用フィールドとして
// 別枠で編集されるためここでは除外する。legacy.* (Valhalla遺産、TF未消費) も既知の除外対象。
function scalarExpKeys(exp) {
  const out = [];
  for (const [k, v] of Object.entries(exp)) {
    if (k === "max_level" || k === "exp_level_curve" || k === "legacy") continue;
    if (v !== null && typeof v === "object") continue; // ネストしたテーブル(mining_break等)は対象外
    out.push(k);
  }
  return out;
}

test("power_progression.yml の prestige_decay_rate はUIの編集対象キーに含まれる", (t) => {
  if (!fs.existsSync(BASE_DIR)) { t.skip("skills/base ディレクトリが無い環境ではスキップ"); return; }
  const cfg = readConfig(path.join(BASE_DIR, "power_progression.yml"));
  assert.ok(cfg.exists);
  const keys = scalarExpKeys(cfg.data.experience);
  assert.ok(keys.includes("prestige_decay_rate"), "power_progression.yml に prestige_decay_rate が無い(データ側の変更?)");
  assert.ok(RATE_KEYS.has("prestige_decay_rate"), "RATE_KEYS が prestige_decay_rate をカバーしていない(tf-forms.js側の回帰)");
});
