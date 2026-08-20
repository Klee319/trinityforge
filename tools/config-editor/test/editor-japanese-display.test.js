"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const APP = fs.readFileSync(path.join(ROOT, "public", "js", "app.js"), "utf8");
const FORMS = fs.readFileSync(path.join(ROOT, "public", "js", "tf-forms.js"), "utf8");
const DUNGEON = fs.readFileSync(path.join(ROOT, "public", "js", "tf-dungeon-forms.js"), "utf8");
const REGISTRY = fs.readFileSync(path.join(ROOT, "lib", "registry.js"), "utf8");
const STYLE = fs.readFileSync(path.join(ROOT, "public", "style.css"), "utf8");

test("Editor見出しにschema内部名を常時表示しない", () => {
  assert.doesNotMatch(APP, /textContent\s*=\s*`\$\{[^`]*schema:\s*\$\{r\.schema\}/);
});

test("EXP画面の見出しは日本語のみで表示する", () => {
  assert.match(REGISTRY, /id:\s*"skill-exp",\s*label:\s*"スキルEXP獲得"/);
  assert.doesNotMatch(REGISTRY, /id:\s*"skill-exp",\s*label:\s*"[^"]*\(skill-exp\)"/);
  for (const key of ["spot-diminishing", "gathering", "level-diminishing"]) {
    assert.match(FORMS, new RegExp(`"${key}"\\s*:\\s*"[^"]*[ぁ-んァ-ヶ一-龠]`),
      `${key} の日本語セクション名がない`);
  }
  for (const rawHeading of [
    "ダンジョン限定EXP (dungeon-only-exp)",
    "曲線式の書き方 (exp_level_curve)",
    "EXP獲得表示 (exp-display)",
    "レベルアップ通知 (level-up)",
    "使用可能レベル連動EXP (use-level-scaling)"
  ]) {
    assert.equal(FORMS.includes(rawHeading), false, `${rawHeading} が表示文言に残っている`);
  }
  assert.doesNotMatch(FORMS, /skill-exp\.yml（combat\.\*）/);
  assert.doesNotMatch(STYLE, /\.entry-key-label\s*\{[^}]*text-transform:\s*uppercase/s);
});

test("ダンジョンゲート未設定は一般ユーザー拒否と案内する", () => {
  assert.doesNotMatch(DUNGEON, /fail-open/);
  assert.match(DUNGEON, /一般ユーザー.*入場できません/);
});
