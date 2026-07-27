"use strict";

// public/js/tf-rewards-forms.js の純関数(Node/ブラウザ両対応)のテスト。
// 特殊報酬/アチーブメント/図鑑タブ共通のID重複チェック・正規化ロジックが対象。

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  uniqueKey,
  renameKey,
  isValidRewardId,
  checkRewardIdAvailable,
  normalizeAchievementTrigger,
  normalizeAchievementRewards,
  normalizeRewardExtras,
  filterNonEmptyStrings,
  filterRewardItems,
  filterJobExp,
  filterPermanentBuffs,
  filterRewardExtras
} = require("../public/js/tf-rewards-forms.js");

test("uniqueKey: 未使用のbaseはそのまま返す", () => {
  assert.equal(uniqueKey({}, "dragon-slayer"), "dragon-slayer");
});

test("uniqueKey: 衝突したら連番を振る", () => {
  const map = { new_title: {}, new_title_1: {} };
  assert.equal(uniqueKey(map, "new_title"), "new_title_2");
});

test("renameKey: キー順を保ったままキー名を差し替える", () => {
  const map = { a: 1, b: 2, c: 3 };
  renameKey(map, "b", "renamed");
  assert.deepEqual(Object.keys(map), ["a", "renamed", "c"]);
  assert.equal(map.renamed, 2);
});

test("isValidRewardId: 半角英数字・ハイフン・アンダースコアのみ許可", () => {
  assert.equal(isValidRewardId("dragon-slayer"), true);
  assert.equal(isValidRewardId("crit_aura_1"), true);
  assert.equal(isValidRewardId(""), false);
  assert.equal(isValidRewardId("   "), false);
  assert.equal(isValidRewardId("不正id"), false);
  assert.equal(isValidRewardId("has space"), false);
  assert.equal(isValidRewardId(null), false);
});

test("checkRewardIdAvailable: 空文字はエラー", () => {
  assert.equal(checkRewardIdAvailable({}, "x", "   "), "IDを入力してください");
});

test("checkRewardIdAvailable: 不正文字はエラー", () => {
  assert.match(checkRewardIdAvailable({}, "x", "不正 id"), /半角英数字/);
});

test("checkRewardIdAvailable: 他エントリと重複していればエラー", () => {
  const map = { a: {}, b: {} };
  assert.equal(checkRewardIdAvailable(map, "a", "b"), "同じIDが既にあります");
});

test("checkRewardIdAvailable: 自分自身と同じIDは許可", () => {
  const map = { a: {} };
  assert.equal(checkRewardIdAvailable(map, "a", "a"), null);
});

test("checkRewardIdAvailable: 妥当かつ未使用のIDはOK", () => {
  const map = { a: {} };
  assert.equal(checkRewardIdAvailable(map, "a", "new-id"), null);
});

test("normalizeAchievementTrigger: 未定義は statistic 既定 + 必須フィールド実体化", () => {
  const t = normalizeAchievementTrigger(undefined);
  assert.equal(t.type, "statistic");
  assert.equal(t.statistic, "");
  assert.equal(t.threshold, 1);
});

test("normalizeAchievementTrigger: advancement は advancement フィールドのみ実体化", () => {
  const t = normalizeAchievementTrigger({ type: "advancement" });
  assert.equal(t.type, "advancement");
  assert.equal(t.advancement, "");
  assert.equal(t.statistic, undefined);
});

test("normalizeAchievementTrigger: 既存値は保持する", () => {
  const t = normalizeAchievementTrigger({ type: "statistic", statistic: "JUMP", threshold: 500 });
  assert.equal(t.statistic, "JUMP");
  assert.equal(t.threshold, 500);
});

test("normalizeAchievementRewards: 未定義配列を実体化する", () => {
  const r = normalizeAchievementRewards({});
  assert.deepEqual(r.special, []);
  assert.deepEqual(r.commands, []);
});

test("normalizeAchievementRewards: 既存配列は保持する", () => {
  const r = normalizeAchievementRewards({ special: ["a"], commands: ["give %player% x 1"] });
  assert.deepEqual(r.special, ["a"]);
  assert.deepEqual(r.commands, ["give %player% x 1"]);
});

test("filterNonEmptyStrings: 空文字・非文字列を除去する", () => {
  assert.deepEqual(filterNonEmptyStrings(["a", "", "  ", "b", 1, null]), ["a", "b"]);
});

test("filterNonEmptyStrings: 非配列は空配列を返す", () => {
  assert.deepEqual(filterNonEmptyStrings(null), []);
  assert.deepEqual(filterNonEmptyStrings(undefined), []);
});

test("normalizeAchievementRewards: items/job-exp/permanent-buffsも実体化する", () => {
  const r = normalizeAchievementRewards({});
  assert.deepEqual(r.items, []);
  assert.deepEqual(r["job-exp"], []);
  assert.deepEqual(r["permanent-buffs"], {});
  assert.equal(r["vanilla-exp"], undefined);
});

test("normalizeRewardExtras: 既存値は保持する", () => {
  const c = normalizeRewardExtras({ items: [{ id: "diamond", amount: 2 }], "job-exp": [{ skill: "MINING", amount: 1 }], "permanent-buffs": { "attack-power": 5 } });
  assert.deepEqual(c.items, [{ id: "diamond", amount: 2 }]);
  assert.deepEqual(c["job-exp"], [{ skill: "MINING", amount: 1 }]);
  assert.deepEqual(c["permanent-buffs"], { "attack-power": 5 });
});

test("filterRewardItems: id空の行を除去しamountを1以上の整数に丸める", () => {
  assert.deepEqual(
    filterRewardItems([{ id: "diamond", amount: 3.7 }, { id: "" }, { id: "  " }, { id: "iron", amount: 0 }, { id: "gold", amount: -5 }]),
    [{ id: "diamond", amount: 3 }, { id: "iron", amount: 1 }, { id: "gold", amount: 1 }]
  );
});

test("filterRewardItems: 非配列は空配列を返す", () => {
  assert.deepEqual(filterRewardItems(null), []);
});

test("filterJobExp: skill空の行を除去する", () => {
  assert.deepEqual(
    filterJobExp([{ skill: "MINING", amount: 500 }, { skill: "" }, { skill: "SMITHING" }]),
    [{ skill: "MINING", amount: 500 }, { skill: "SMITHING", amount: 0 }]
  );
});

test("filterPermanentBuffs: 数値でない値を除去する", () => {
  assert.deepEqual(
    filterPermanentBuffs({ "attack-power": 5, "move-speed": "fast", "max-health": 4.5 }),
    { "attack-power": 5, "max-health": 4.5 }
  );
});

test("filterRewardExtras: vanilla-expが空/未設定ならキーを削除する", () => {
  const c = filterRewardExtras({ "vanilla-exp": null, items: [], "job-exp": [], "permanent-buffs": {} });
  assert.equal("vanilla-exp" in c, false);
  const c2 = filterRewardExtras({ "vanilla-exp": 0, items: [], "job-exp": [], "permanent-buffs": {} });
  assert.equal(c2["vanilla-exp"], 0);
});
