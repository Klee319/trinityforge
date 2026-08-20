"use strict";

// ---------------------------------------------------------------------------
// getData() の刈り取りが配列を差し替えると、開いた直後の1回目の編集が黙って消える
// (2026-08-05・レベルテーブル画面で実際に踏んだ)
//
// 症状: 「レベルテーブル」画面でモブ選択セレクトを別のモブへ変えても
//   ・表示は新しいモブに変わる
//   ・未保存警告は出ない / 保存ボタンは「自分の変更はありません」と言う
//       (= 変更が捨てられる)
//
// 真因: 行エディタ (buildNoSkillExpMobsBox / buildAddDropMobsBox) は描画時に
//   `const list = working["no-skill-exp-mobs"]` のように**配列オブジェクトを掴んでから**
//   `list[idx] = v` で書き込む。一方 getData() の刈り取りが
//   `working[key] = working[key].map().filter()` と**新しい配列へ差し替えて**いたため、
//   掴んでいた配列が孤児になり、以後その行の編集は working に届かなくなっていた。
//
//   getData() は画面を開いた直後 (app.js の syncBaseFromEditor) と beforeunload の
//   たびに呼ばれる。つまり「画面を開く → 最初の1回の編集」が必ずこの穴に落ちる。
//   未保存判定 isEditorDirty も working の差分を見るだけなので、警告すら出せない。
//
// 不変条件: **getData() の刈り取りは working 配下のコンテナを差し替えてはならない。**
//   このテストは「同一性が保たれること」＝差し替え実装へ戻したら落ちることを固定する。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
require("../public/js/mob-forms.js");
const { pruneEmptyMobSelections, pruneEmptyNoSkillExpMobs } = global.window.MOB_FORMS_LOGIC;

test("pruneEmptyNoSkillExpMobs: 配列オブジェクトの同一性を壊さない", () => {
  const working = { "no-skill-exp-mobs": ["BEE", "", "GOAT"] };
  const captured = working["no-skill-exp-mobs"]; // 行エディタが描画時に掴む参照

  pruneEmptyNoSkillExpMobs(working);

  assert.equal(working["no-skill-exp-mobs"], captured,
    "差し替えると行エディタが掴んだ配列が孤児になり、編集が working に届かなくなる");
  assert.deepEqual(captured, ["BEE", "GOAT"], "刈り取り自体は従来どおり効く");

  // 孤児化していれば、この書き込みは working に反映されない。
  captured[0] = "ALLAY";
  assert.deepEqual(working["no-skill-exp-mobs"], ["ALLAY", "GOAT"]);
});

test("pruneEmptyMobSelections: mobs / mob-ids の同一性を壊さない", () => {
  const tier = { "min-level": 0, mobs: ["ZOMBIE", ""], "mob-ids": ["the_mines_boss", "  "] };
  const drop = { material: "BONE", mobs: ["SKELETON", ""] };
  tier["add-drops"] = [drop];
  const capturedTierMobs = tier.mobs;
  const capturedMobIds = tier["mob-ids"];
  const capturedDropMobs = drop.mobs;

  pruneEmptyMobSelections([tier]);

  assert.equal(tier.mobs, capturedTierMobs, "帯の mobs を差し替えてはいけない");
  assert.equal(tier["mob-ids"], capturedMobIds, "帯の mob-ids を差し替えてはいけない");
  assert.equal(drop.mobs, capturedDropMobs, "add-drops の mobs を差し替えてはいけない");
  assert.deepEqual(tier.mobs, ["ZOMBIE"]);
  assert.deepEqual(tier["mob-ids"], ["the_mines_boss"]);
  assert.deepEqual(drop.mobs, ["SKELETON"]);
});

test("刈り取りを何度呼んでも同一性と内容が安定する(getDataは毎回呼ばれる)", () => {
  const working = { "no-skill-exp-mobs": ["BEE"] };
  const captured = working["no-skill-exp-mobs"];
  for (let i = 0; i < 5; i++) pruneEmptyNoSkillExpMobs(working);
  assert.equal(working["no-skill-exp-mobs"], captured);
  assert.deepEqual(captured, ["BEE"]);
});

test("全要素が空なら従来どおりキーごと消える(空配列を保存YAMLに残さない)", () => {
  const working = { "no-skill-exp-mobs": ["", "   "] };
  pruneEmptyNoSkillExpMobs(working);
  assert.equal(Object.prototype.hasOwnProperty.call(working, "no-skill-exp-mobs"), false);
});
