"use strict";

// public/js/labels.js (window.LABELS) の T5 命名規則テスト:
// バフ選択menuラベルは全て "<効果範囲プレフィックス>:<説明><方向記号?>" の形で、
// MAX_STAT_LABEL_LENGTH を超えないこと。casing違い(snake_case buffs キー)でも
// statLabel が英字キーへフォールバックしないことも合わせて検証する。

const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
require("../public/js/labels.js");

const { STAT_LABELS, MAX_STAT_LABEL_LENGTH, statLabel, normalizeStatKey } = global.window.LABELS;

test("MAX_STAT_LABEL_LENGTH が定義されている", () => {
  assert.equal(typeof MAX_STAT_LABEL_LENGTH, "number");
  assert.ok(MAX_STAT_LABEL_LENGTH > 0);
});

test("全STAT_LABELSエントリがMAX_STAT_LABEL_LENGTHを超えない(selectメニューで見切れない)", () => {
  const overflow = Object.entries(STAT_LABELS).filter(([, label]) => label.length > MAX_STAT_LABEL_LENGTH);
  assert.deepEqual(overflow, [], `over-length label(s): ${overflow.map(([k, v]) => `${k}="${v}"(${v.length})`).join(", ")}`);
});

test("全STAT_LABELSエントリが '<プレフィックス>:<説明>' 形式(コロン区切り)を持つ", () => {
  const missingColon = Object.entries(STAT_LABELS).filter(([, label]) => !label.includes(":"));
  assert.deepEqual(missingColon, [], `label(s) without a domain-scope prefix: ${missingColon.map(([k]) => k).join(", ")}`);
});

test("今回追加した経済連携3キーが登録済みで、ドメインプレフィックス+方向記号を持つ", () => {
  assert.equal(STAT_LABELS["fish-sell-price-bonus"], "釣り:売却額↑");
  assert.equal(STAT_LABELS["disassembly-return-bonus"], "解体:戻り量↑");
  assert.equal(STAT_LABELS["ocean-fishing-bonus"], "釣り:海釣り加算↑");
});

test("原価/消費が減る系のキーは↓、増加系のキーは↑を使う(命名規則の一貫性サンプル)", () => {
  assert.ok(STAT_LABELS["mana-cost-reduction-flat"].endsWith("↓"));
  assert.ok(STAT_LABELS["lapis-cost-reduction"].endsWith("↓"));
  assert.ok(STAT_LABELS["damage-reduction"].endsWith("↓"));
  assert.ok(STAT_LABELS["attack-power"].endsWith("↑"));
  assert.ok(STAT_LABELS["fishing-luck"].endsWith("↑"));
});

test("normalizeStatKey は snake_case を kebab-case へ揃える", () => {
  assert.equal(normalizeStatKey("loot_luck"), "loot-luck");
  assert.equal(normalizeStatKey("loot-luck"), "loot-luck");
});

test("statLabel はスキルツリーbuffsキーのsnake_case表記でも英字フォールバックしない", () => {
  // fishing.yml / power.yml 等は "loot_luck" のように書く (StatKeys.canonical と同じ表記ゆれ)。
  assert.equal(statLabel("loot_luck"), STAT_LABELS["loot-luck"]);
  assert.equal(statLabel("fish_sell_price_bonus"), STAT_LABELS["fish-sell-price-bonus"]);
  assert.equal(statLabel("disassembly_return_bonus"), STAT_LABELS["disassembly-return-bonus"]);
  assert.equal(statLabel("ocean_fishing_bonus"), STAT_LABELS["ocean-fishing-bonus"]);
});

test("statLabel は未登録キーをそのまま返す(壊れたフォールバックにしない)", () => {
  assert.equal(statLabel("not_a_real_stat"), "not_a_real_stat");
});
