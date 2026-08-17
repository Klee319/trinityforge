"use strict";

// 2026-08-17 (ユーザー指示「level到達アナウンスの設定を editor のその他のカテゴリに移動し、
// 専用のGUIにせよ」) の回帰。
//
// 移動前は section: "skill-gimmicks" / schema: "generic" で、キー名が英字のまま並ぶ
// 汎用JSONツリーエディタしか無かった。3箇所(registry / サイドバーの並び / フォーム分岐)が
// 揃っていないと画面が出ない、あるいは汎用エディタへ戻るので、まとめて固定する。

const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const { findById } = require("../lib/registry.js");
const { validate } = require("../lib/schema.js");

function readPublic(rel) {
  return fs.readFileSync(path.join(ROOT, "public", rel), "utf8");
}

test("registry: レベル到達アナウンスは「その他」カテゴリの専用スキーマになっている", () => {
  const entry = findById("level-broadcast");
  assert.ok(entry, "level-broadcast が registry から消えている");
  assert.strictEqual(entry.section, "other");
  assert.strictEqual(entry.schema, "tf-level-broadcast");
  assert.strictEqual(entry.rel, "progression/level-broadcast.yml");
});

test("サイドバー: 「その他」に入り、「スキルギミック」からは外れている", () => {
  const app = readPublic("js/app.js");
  const other = app.match(/key:\s*"other"[\s\S]*?order:\s*\[([^\]]*)\]/);
  assert.ok(other, "その他カテゴリの order が読めない");
  assert.match(other[1], /"level-broadcast"/);

  const gimmicks = app.match(/key:\s*"skill-gimmicks"[\s\S]*?order:\s*\[([^\]]*)\]/);
  assert.ok(gimmicks, "スキルギミックカテゴリの order が読めない");
  assert.ok(!/"level-broadcast"/.test(gimmicks[1]),
    "スキルギミック側に残っていると同じ画面が2箇所に出る");
});

test("フォーム分岐と読み込みが配線されている(どちらか欠けると汎用エディタへ落ちる)", () => {
  assert.match(readPublic("js/app.js"),
    /case "tf-level-broadcast":\s*return window\.buildLevelBroadcastForm\(data\);/);
  assert.match(readPublic("js/tf-level-broadcast-form.js"),
    /window\.buildLevelBroadcastForm\s*=\s*function/);
  // index.html への読み込み行が無いと window.buildLevelBroadcastForm が undefined になる。
  assert.match(readPublic("index.html"), /js\/tf-level-broadcast-form\.js/);
});

test("保存時の検証: 出荷 yml 相当は通り、差し込みの書き忘れは落ちる", () => {
  const ok = {
    enabled: true,
    "multiple-of": 10,
    message: "<gold>%player%</gold> が %skill% で Lv%level% に到達しました!",
    "include-power": false,
    "max-announcements-per-batch": 3,
    sound: { enabled: true, key: "UI_TOAST_CHALLENGE_COMPLETE", volume: 1.0, pitch: 1.0 },
    "excluded-skills": [],
    "excluded-levels": []
  };
  assert.deepStrictEqual(validate("tf-level-broadcast", ok), []);

  // %player% / %level% が無いと「誰が何レベルになったのか分からない行」が流れる。
  const errors = validate("tf-level-broadcast", { message: "レベルアップ!" });
  assert.ok(errors.some((e) => e.includes("%player%")));
  assert.ok(errors.some((e) => e.includes("%level%")));

  assert.ok(validate("tf-level-broadcast", { "multiple-of": 0 })
    .some((e) => e.startsWith("multiple-of")), "0 は 0除算/全レベル通知のどちらにも倒せない");
  assert.ok(validate("tf-level-broadcast", { "excluded-levels": ["ten"] })
    .some((e) => e.startsWith("excluded-levels")));
});
