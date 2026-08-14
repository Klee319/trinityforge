"use strict";

// public/js/gate-effects.js (window.GATE_EFFECTS) の単体テスト。
// ブラウザ用 IIFE (window 前提) なので merge.test.js と同じ手法で global.window に生やして読み込む。

const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
require("../public/js/gate-effects.js");

const {
  parseGateEffectId, parseDropTarget, isLegacyGateEffectId,
  gateEffectTypeLabel, isUniqueGateEffectType, computeDuplicateGateEffectIds,
  gateEffectDuplicateKey, resolveFeatureValueEdit
} = global.window.GATE_EFFECTS;

test("プレフィックス付きIDを type/target に解析する", () => {
  assert.deepEqual(parseGateEffectId("glyph:blink"), { type: "glyph", target: "blink", raw: "glyph:blink" });
  assert.deepEqual(parseGateEffectId("brew:swiftness-jump"), { type: "brew", target: "swiftness-jump", raw: "brew:swiftness-jump" });
  assert.deepEqual(parseGateEffectId("trade:WEAPONSMITH"), { type: "trade", target: "WEAPONSMITH", raw: "trade:WEAPONSMITH" });
  assert.deepEqual(parseGateEffectId("recipe:tf_scrap"), { type: "recipe", target: "tf_scrap", raw: "recipe:tf_scrap" });
  assert.deepEqual(parseGateEffectId("ritual:tf_scrap"), { type: "ritual", target: "tf_scrap", raw: "ritual:tf_scrap" });
  assert.deepEqual(parseGateEffectId("drop:mining:tier1"), { type: "drop", target: "mining:tier1", raw: "drop:mining:tier1" });
  assert.deepEqual(parseGateEffectId("feature:vein-mining"), { type: "feature", target: "vein-mining", raw: "feature:vein-mining" });
  assert.deepEqual(parseGateEffectId("overenchant:over-enchant-1"), { type: "overenchant", target: "over-enchant-1", raw: "overenchant:over-enchant-1" });
  assert.deepEqual(parseGateEffectId("reward:dragon-slayer"), { type: "reward", target: "dragon-slayer", raw: "reward:dragon-slayer" });
});

test("ars-tier はコロン無しの特別扱い", () => {
  assert.deepEqual(parseGateEffectId("ars-tier"), { type: "ars-tier", target: "", raw: "ars-tier" });
});

test("旧形式(非プレフィックス)IDは null (未知形式として扱う)", () => {
  assert.equal(parseGateEffectId("blacksmith-unlock"), null);
  assert.equal(parseGateEffectId("gacha-ticket-1"), null);
  assert.equal(parseGateEffectId(""), null);
  assert.equal(parseGateEffectId(null), null);
  assert.equal(parseGateEffectId(undefined), null);
});

test("空target/未知プレフィックスは null", () => {
  assert.equal(parseGateEffectId("glyph:"), null);
  assert.equal(parseGateEffectId("unknown:foo"), null);
  assert.equal(parseGateEffectId(":foo"), null);
});

test("isLegacyGateEffectId は parseGateEffectId の逆", () => {
  assert.equal(isLegacyGateEffectId("glyph:blink"), false);
  assert.equal(isLegacyGateEffectId("ars-tier"), false);
  assert.equal(isLegacyGateEffectId("blacksmith-unlock"), true);
});

test("parseDropTarget: カテゴリ形式とアイテム形式を分解する", () => {
  assert.deepEqual(parseDropTarget("mining:tier1"), { profession: "mining", mode: "category", categoryId: "tier1" });
  assert.deepEqual(parseDropTarget("fishing:treasure:tier1"), { profession: "fishing", mode: "category", categoryId: "treasure:tier1" });
  assert.deepEqual(parseDropTarget("mining:item:tf_gacha_ticket_1"), { profession: "mining", mode: "item", itemId: "tf_gacha_ticket_1" });
});

test("gateEffectTypeLabel は既知typeの日本語ラベルを返す", () => {
  assert.equal(gateEffectTypeLabel("glyph"), "グリフ解放");
  assert.equal(gateEffectTypeLabel("ars-tier"), "ArsTier");
  assert.equal(gateEffectTypeLabel("unknown-type"), "unknown-type");
});

test("isUniqueGateEffectType: ars-tier のみ加算型として除外", () => {
  assert.equal(isUniqueGateEffectType("glyph"), true);
  assert.equal(isUniqueGateEffectType("reward"), true);
  assert.equal(isUniqueGateEffectType("ars-tier"), false);
});

test("computeDuplicateGateEffectIds: 同一unique idが複数ノードにあれば警告集合に入る", () => {
  const nodes = {
    "node-1": { "dedicated-effects": [{ id: "glyph:blink" }, { id: "ars-tier", value: 1 }] },
    "node-2": { "dedicated-effects": [{ id: "glyph:blink" }] },
    "node-3": { "dedicated-effects": [{ id: "feature:vein-mining" }] }
  };
  const dup = computeDuplicateGateEffectIds(nodes);
  assert.ok(dup.has("glyph:blink"));
  assert.equal(dup.size, 1);
});

test("computeDuplicateGateEffectIds: ars-tier は複数ノードにあっても警告対象外", () => {
  const nodes = {
    "node-1": { "dedicated-effects": [{ id: "ars-tier", value: 1 }] },
    "node-2": { "dedicated-effects": [{ id: "ars-tier", value: 2 }] }
  };
  const dup = computeDuplicateGateEffectIds(nodes);
  assert.equal(dup.size, 0);
});

test("computeDuplicateGateEffectIds: 旧形式IDは判定不能なので対象外", () => {
  const nodes = {
    "node-1": { "dedicated-effects": [{ id: "blacksmith-unlock" }] },
    "node-2": { "dedicated-effects": [{ id: "blacksmith-unlock" }] }
  };
  const dup = computeDuplicateGateEffectIds(nodes);
  assert.equal(dup.size, 0);
});

test("computeDuplicateGateEffectIds: ノード/dedicated-effects欠損でも落ちない", () => {
  assert.equal(computeDuplicateGateEffectIds(null).size, 0);
  assert.equal(computeDuplicateGateEffectIds({}).size, 0);
  assert.equal(computeDuplicateGateEffectIds({ "node-1": {} }).size, 0);
  assert.equal(computeDuplicateGateEffectIds({ "node-1": { "dedicated-effects": "not-array" } }).size, 0);
});

// ---- 引数(tier)違いを重複と誤判定していたバグ (2026-08-14 実利用報告) ----
// feature:<id> の value は「そのノードが解放する段階(tier)」であり、Java 側は
// DedicatedEffectGateIndex#valueMaxByPerks で保持ノードのうち最大の tier を採る。
// つまり同じ機能を tier 違いで複数ノードに置くのは設計どおりの正しい形で、
// 「⚠ 重複」を出してはいけない。id だけで数えていたので全部重複扱いになっていた。

test("computeDuplicateGateEffectIds: 同じfeatureでもtierが違えば重複ではない", () => {
  const nodes = {
    "node-1": { "dedicated-effects": [{ id: "feature:vein-mining", value: 1 }] },
    "node-2": { "dedicated-effects": [{ id: "feature:vein-mining", value: 2 }] },
    "node-3": { "dedicated-effects": [{ id: "feature:vein-mining", value: 3 }] }
  };
  assert.equal(computeDuplicateGateEffectIds(nodes).size, 0);
});

test("computeDuplicateGateEffectIds: 同じfeatureでtierも同じなら重複", () => {
  const nodes = {
    "node-1": { "dedicated-effects": [{ id: "feature:vein-mining", value: 2 }] },
    "node-2": { "dedicated-effects": [{ id: "feature:vein-mining", value: 2 }] }
  };
  const dup = computeDuplicateGateEffectIds(nodes);
  assert.equal(dup.size, 1);
  assert.ok(dup.has(gateEffectDuplicateKey({ id: "feature:vein-mining", value: 2 })));
});

test("computeDuplicateGateEffectIds: scaleの空欄(tier1相当)と明示value:1は同じ段階として重複", () => {
  // FeatureEffectParam#defaultsMissingValue: value キーが無い scale 配置は tier1 として読まれる。
  const nodes = {
    "node-1": { "dedicated-effects": [{ id: "feature:vein-mining" }] },
    "node-2": { "dedicated-effects": [{ id: "feature:vein-mining", value: 1 }] }
  };
  assert.equal(computeDuplicateGateEffectIds(nodes).size, 1);
});

test("computeDuplicateGateEffectIds: feature以外のunique種別はvalueが違っても重複", () => {
  // glyph/brew/trade/recipe/drop/overenchant/reward は純粋な on/off 解放で value に意味が無い。
  // 手書き yml に紛れ込んだ value で重複警告が消えてはいけない。
  const nodes = {
    "node-1": { "dedicated-effects": [{ id: "glyph:blink", value: 1 }] },
    "node-2": { "dedicated-effects": [{ id: "glyph:blink", value: 2 }] }
  };
  const dup = computeDuplicateGateEffectIds(nodes);
  assert.equal(dup.size, 1);
  assert.ok(dup.has("glyph:blink"));
});

test("gateEffectDuplicateKey: 判定対象外(ars-tier/旧形式/欠損)は null", () => {
  assert.equal(gateEffectDuplicateKey({ id: "ars-tier", value: 1 }), null);
  assert.equal(gateEffectDuplicateKey({ id: "blacksmith-unlock" }), null);
  assert.equal(gateEffectDuplicateKey(null), null);
  assert.equal(gateEffectDuplicateKey({}), null);
});

test("gateEffectDuplicateKey: featureはtierまで含み、他種別はidそのまま", () => {
  assert.equal(gateEffectDuplicateKey({ id: "glyph:blink" }), "glyph:blink");
  assert.notEqual(
    gateEffectDuplicateKey({ id: "feature:vein-mining", value: 1 }),
    gateEffectDuplicateKey({ id: "feature:vein-mining", value: 2 })
  );
  assert.equal(
    gateEffectDuplicateKey({ id: "feature:vein-mining" }),
    gateEffectDuplicateKey({ id: "feature:vein-mining", value: 1 })
  );
});

// resolveFeatureValueEdit: 2026-07-25 アクティブスキルtier常時1固定バグ修正のUI側ロジック。
// Java FeatureEffectParam#defaultsMissingValue/requiresValue の意味論と一致していることを固定する。
test("resolveFeatureValueEdit: scaleは空欄でvalueキー削除を指示する(0を書かない)", () => {
  assert.deepEqual(resolveFeatureValueEdit("scale", null), { remove: true });
});

test("resolveFeatureValueEdit: scaleは数値入力をそのまま通す", () => {
  assert.deepEqual(resolveFeatureValueEdit("scale", 3), { remove: false, value: 3 });
  assert.deepEqual(resolveFeatureValueEdit("scale", 5), { remove: false, value: 5 });
});

test("resolveFeatureValueEdit: levelは空欄を0にフォールバックする(既存挙動の固定)", () => {
  assert.deepEqual(resolveFeatureValueEdit("level", null), { remove: false, value: 0 });
});

test("resolveFeatureValueEdit: levelは数値入力をそのまま通す", () => {
  assert.deepEqual(resolveFeatureValueEdit("level", 25), { remove: false, value: 25 });
});
