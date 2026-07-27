"use strict";

// public/js/mob-forms.js (window.MOB_FORMS_LOGIC) の純関数群の単体テスト。
// combat/mob-types.yml editor: level-coefficients の growth 対応 (max-health-growth /
// attack.attack-power-growth) を追加した際、既存の空ブロック刈り取り (pruneEmptyScalingBlock) が
// growth だけを設定したブロックを誤って消してしまわないことを検証する。
// ブラウザ用 IIFE (window.h 前提) なので、drop-table-logic.test.js と同じ手法で window.h を最小
// スタブして読み込む。実際のDOM構築(buildMobTypesForm)はこのファイルではテストしない。

const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
global.window.h = () => ({});
require("../public/js/mob-forms.js");

const { pruneEmptyScalingBlock, pruneEmptyMobSelections, pruneEmptyNoSkillExpMobs } = global.window.MOB_FORMS_LOGIC;

test("pruneEmptyScalingBlock: max-health/armor-strength/growth が全て未設定なら level-coefficients を削除する", () => {
  const host = { "level-coefficients": {} };
  pruneEmptyScalingBlock(host, "level-coefficients");
  assert.equal("level-coefficients" in host, false);
});

test("pruneEmptyScalingBlock: max-health-growth だけ設定されていても level-coefficients を残す", () => {
  const host = { "level-coefficients": { "max-health-growth": 1.055 } };
  pruneEmptyScalingBlock(host, "level-coefficients");
  assert.equal("level-coefficients" in host, true);
  assert.equal(host["level-coefficients"]["max-health-growth"], 1.055);
});

test("pruneEmptyScalingBlock: max-health-growth-interval だけ設定されていても残す", () => {
  const host = { "level-coefficients": { "max-health-growth-interval": 2.0 } };
  pruneEmptyScalingBlock(host, "level-coefficients");
  assert.equal("level-coefficients" in host, true);
});

test("pruneEmptyScalingBlock: attack.attack-power-growth だけ設定されていても attack ブロックと親を残す", () => {
  const host = {
    "level-coefficients": {
      attack: { "attack-power-growth": 1.03 }
    }
  };
  pruneEmptyScalingBlock(host, "level-coefficients");
  assert.equal("level-coefficients" in host, true);
  assert.deepEqual(host["level-coefficients"].attack, { "attack-power-growth": 1.03 });
});

test("pruneEmptyScalingBlock: 空の attack オブジェクトは削除されるが、他が空でなければ親は残る", () => {
  const host = {
    "level-coefficients": {
      "max-health": 55,
      attack: {}
    }
  };
  pruneEmptyScalingBlock(host, "level-coefficients");
  assert.equal("level-coefficients" in host, true);
  assert.equal("attack" in host["level-coefficients"], false);
  assert.equal(host["level-coefficients"]["max-health"], 55);
});

test("pruneEmptyScalingBlock: physical/magical が空オブジェクトなら削除するが max-health は残す (既存挙動の回帰確認)", () => {
  const host = {
    "level-coefficients": {
      "max-health": 55,
      physical: {},
      magical: {}
    }
  };
  pruneEmptyScalingBlock(host, "level-coefficients");
  assert.equal("physical" in host["level-coefficients"], false);
  assert.equal("magical" in host["level-coefficients"], false);
  assert.equal(host["level-coefficients"]["max-health"], 55);
});

// --- 2026-07-25 モブ別ドロップ指定拡張 (§2-A mobs / §2-C editor、テスト必須11) ---
// combat/mob-level-table.yml editor: add-drops[].mobs を UI で全部削除して空配列に戻した場合、
// 保存時にキー自体を削除する(空配列を保存YAMLに残さない = back-compatな「未指定」表現へ正規化)。

test("pruneEmptyMobSelections: 空配列の mobs キーを削除する", () => {
  const tiers = [{ "min-level": 0, "add-drops": [{ material: "BONE", mobs: [] }] }];
  pruneEmptyMobSelections(tiers);
  assert.equal("mobs" in tiers[0]["add-drops"][0], false);
});

test("pruneEmptyMobSelections: 中身のある mobs 配列はそのまま残す", () => {
  const tiers = [{ "min-level": 0, "add-drops": [{ material: "BONE", mobs: ["ZOMBIE", "SKELETON"] }] }];
  pruneEmptyMobSelections(tiers);
  assert.deepEqual(tiers[0]["add-drops"][0].mobs, ["ZOMBIE", "SKELETON"]);
});

test("pruneEmptyMobSelections: mobs 未指定のエントリ/tiers・add-drops欠落は例外にならない", () => {
  assert.doesNotThrow(() => pruneEmptyMobSelections(undefined));
  assert.doesNotThrow(() => pruneEmptyMobSelections([{ "min-level": 0 }]));
  assert.doesNotThrow(() => pruneEmptyMobSelections([{ "min-level": 0, "add-drops": [{ material: "BONE" }] }]));
});

// --- 2026-07-26: mob-ids(EliteMobsモブid絞り込み)と帯そのものへの絞り込み ---
// 「+ モブidを追加」は空文字の行を積むので、未選択のまま保存すると mob-ids: [""] が残り、
// Java側で「どのモブにも一致しないフィルタ」になって帯ごと無効化される。空文字は必ず削る。

test("pruneEmptyMobSelections: 未選択(空文字)の mob-ids 行を削り、全て空ならキーごと消す", () => {
  const tiers = [{ "min-level": 0, "add-drops": [{ material: "BONE", "mob-ids": ["", "  "] }] }];
  pruneEmptyMobSelections(tiers);
  assert.equal("mob-ids" in tiers[0]["add-drops"][0], false);
});

test("pruneEmptyMobSelections: 有効な mob-ids は残し、空文字だけを取り除く", () => {
  const tiers = [{ "min-level": 0, "add-drops": [{ material: "BONE", "mob-ids": ["the_mines_boss", ""] }] }];
  pruneEmptyMobSelections(tiers);
  assert.deepEqual(tiers[0]["add-drops"][0]["mob-ids"], ["the_mines_boss"]);
});

test("pruneEmptyMobSelections: 帯そのものの mobs / mob-ids も同じ規則で刈る", () => {
  const tiers = [{ "min-level": 0, mobs: [], "mob-ids": ["", "guild_boss"] }];
  pruneEmptyMobSelections(tiers);
  assert.equal("mobs" in tiers[0], false);
  assert.deepEqual(tiers[0]["mob-ids"], ["guild_boss"]);
});

// --- pruneEmptyNoSkillExpMobs: no-skill-exp-mobs (2026-07-27 牧場対策) の空行刈り取り ---
// バニラEXPオーブは対象外。TrinityForgeの戦闘スキルEXP無効化リストの空文字/空配列を刈る純関数。
// pruneEmptyMobSelections とは別に、working 直下のトップレベルキー1つだけを対象にする。

test("pruneEmptyNoSkillExpMobs: 空文字/空白だけの行を取り除く", () => {
  const working = { "no-skill-exp-mobs": ["BEE", "", "  ", "GOAT"] };
  pruneEmptyNoSkillExpMobs(working);
  assert.deepEqual(working["no-skill-exp-mobs"], ["BEE", "GOAT"]);
});

test("pruneEmptyNoSkillExpMobs: 全行が空なら working からキー自体を削除する", () => {
  const working = { "no-skill-exp-mobs": ["", "   "] };
  pruneEmptyNoSkillExpMobs(working);
  assert.equal("no-skill-exp-mobs" in working, false);
});

test("pruneEmptyNoSkillExpMobs: 中身のある配列はそのまま残す", () => {
  const working = { "no-skill-exp-mobs": ["BEE", "GOAT"] };
  pruneEmptyNoSkillExpMobs(working);
  assert.deepEqual(working["no-skill-exp-mobs"], ["BEE", "GOAT"]);
});

test("pruneEmptyNoSkillExpMobs: キー未指定/working が空でも例外にならない", () => {
  assert.doesNotThrow(() => pruneEmptyNoSkillExpMobs({}));
  assert.doesNotThrow(() => pruneEmptyNoSkillExpMobs(undefined));
  assert.doesNotThrow(() => pruneEmptyNoSkillExpMobs({ "no-skill-exp-mobs": "BEE" }));
});

// --- expRampValue: mob-overrides の vanilla-exp プレビュー計算 (2026-07-26) ---
// Java側 ConversionPolicy.Ramp#at + MobOverridesConfig#toExpAmount と同じ結果になることが要件
// (エディタのプレビューが実際の付与量と食い違うと調整そのものが成立しない)。

const { expRampValue } = global.window.MOB_FORMS_LOGIC;

test("expRampValue: growth 未指定は線形 (base + per-level * level)", () => {
  assert.equal(expRampValue({ base: 10, "per-level": 2 }, 10), 30);
  assert.equal(expRampValue({ base: 10, "per-level": 2 }, 100), 210);
});

test("expRampValue: growth 指定で指数になる", () => {
  const ramp = { base: 5, "per-level": 1.5, growth: 1.03, "growth-interval": 1.0 };
  assert.equal(expRampValue(ramp, 1), Math.round(6.5 * Math.pow(1.03, 1)));
  assert.equal(expRampValue(ramp, 100), Math.round(155 * Math.pow(1.03, 100)));
});

test("expRampValue: growth-interval が指数の間隔として効く", () => {
  const ramp = { base: 100, "per-level": 0, growth: 2, "growth-interval": 10 };
  assert.equal(expRampValue(ramp, 10), 200);
  assert.equal(expRampValue(ramp, 20), 400);
});

test("expRampValue: 負の結果は0に丸められる", () => {
  assert.equal(expRampValue({ base: -50, "per-level": 1 }, 10), 0);
  assert.equal(expRampValue({ base: -50, "per-level": 1 }, 100), 50);
});

test("expRampValue: 不正な growth / growth-interval は中立値(1.0)に落とす", () => {
  // Java側 Ramp のコンストラクタと同じ正規化
  assert.equal(expRampValue({ base: 10, growth: -1 }, 50), 10);
  assert.equal(expRampValue({ base: 10, "growth-interval": 0, growth: 2 }, 3), 80);
});

test("expRampValue: 空ランプは0", () => {
  assert.equal(expRampValue({}, 50), 0);
});
