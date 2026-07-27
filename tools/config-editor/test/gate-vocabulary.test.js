"use strict";

// lib/gate-vocabulary.js のテスト。GET /api/gate-vocabulary が返す語彙の抽出ロジック。

const test = require("node:test");
const assert = require("node:assert/strict");
const { buildGateVocabulary, FEATURES } = require("../lib/gate-vocabulary");

test("全ソース欠損/空でも例外を投げず空配列を返す", () => {
  const vocab = buildGateVocabulary({});
  assert.deepEqual(vocab.glyphs, []);
  assert.deepEqual(vocab.brews, []);
  assert.deepEqual(vocab.trades, []);
  assert.deepEqual(vocab.overenchants, []);
  assert.deepEqual(vocab.drops, []);
  assert.deepEqual(vocab.specialRewards, []);
  assert.deepEqual(vocab.recipes, []);
  assert.deepEqual(vocab.rituals, []);
  // features はプログラム定義の固定語彙なので常に返る
  assert.ok(vocab.features.length === FEATURES.length);
});

test("null/undefined ソースでも落ちない", () => {
  const vocab = buildGateVocabulary({
    glyphs: null,
    craftingFeatures: undefined,
    villagerTrades: { professions: null },
    gimmicks: null,
    specialRewards: undefined
  });
  assert.deepEqual(vocab.glyphs, []);
  assert.deepEqual(vocab.trades, []);
  assert.deepEqual(vocab.drops, []);
});

test("glyphs.yml からカテゴリ/表示名を抽出する", () => {
  const vocab = buildGateVocabulary({
    glyphs: {
      glyphs: {
        projectile: { "display-name": "投射", tier: 1 },
        blink: { "display-name": "瞬間移動", category: "movement" },
        no_display: {}
      }
    }
  });
  assert.deepEqual(vocab.glyphs, [
    { key: "projectile", displayName: "投射", category: "" },
    { key: "blink", displayName: "瞬間移動", category: "movement" },
    { key: "no_display", displayName: "no_display", category: "" }
  ]);
});

test("crafting-features.yml から brew-unlocks / over-enchant のキーを抽出する", () => {
  const vocab = buildGateVocabulary({
    craftingFeatures: {
      "brew-unlocks": { "swiftness-jump": {}, "healthboost-haste": {} },
      "over-enchant": { "over-enchant-1": {}, "over-enchant-2": {} }
    }
  });
  assert.deepEqual(vocab.brews.sort(), ["healthboost-haste", "swiftness-jump"]);
  assert.deepEqual(vocab.overenchants.sort(), ["over-enchant-1", "over-enchant-2"]);
});

test("villager-trades.yml から職業キーを抽出する", () => {
  const vocab = buildGateVocabulary({
    villagerTrades: { professions: { WEAPONSMITH: {}, LIBRARIAN: {} } }
  });
  assert.deepEqual(vocab.trades.sort(), ["LIBRARIAN", "WEAPONSMITH"]);
});

test("ブロック破壊型ギミックの drop-tables.categories を抽出する", () => {
  const vocab = buildGateVocabulary({
    gimmicks: {
      mining: {
        "drop-tables": {
          categories: {
            tier1: { "display-name": "Tier1", entries: [] },
            tier2: {}
          }
        }
      }
    }
  });
  assert.deepEqual(vocab.drops, [
    { profession: "mining", categoryId: "tier1", displayName: "Tier1" },
    { profession: "mining", categoryId: "tier2", displayName: "tier2" }
  ]);
});

test("釣りは fishing.groups.<group>.categories 形式から抽出する", () => {
  const vocab = buildGateVocabulary({
    gimmicks: {
      fishing: {
        fishing: {
          groups: {
            treasure: { categories: { tier1: { "display-name": "宝Tier1" } } },
            junk: { categories: { tier1: {} } }
          }
        }
      }
    }
  });
  assert.deepEqual(vocab.drops, [
    { profession: "fishing", categoryId: "treasure:tier1", displayName: "宝Tier1" },
    { profession: "fishing", categoryId: "junk:tier1", displayName: "tier1" }
  ]);
});

test("special-rewards.yml の titles/particles キーを和集合で返す", () => {
  const vocab = buildGateVocabulary({
    specialRewards: {
      titles: { "dragon-slayer": {}, shared: {} },
      particles: { "crit-aura": {}, shared: {} }
    }
  });
  assert.deepEqual(vocab.specialRewards, ["crit-aura", "dragon-slayer", "shared"]);
});

test("special-rewards.yml の particle-seeds キーも和集合に含める", () => {
  const vocab = buildGateVocabulary({
    specialRewards: {
      titles: { "dragon-slayer": {} },
      particles: { "crit-aura": {} },
      "particle-seeds": { "crit-seed": {}, "dragon-slayer": {} }
    }
  });
  assert.deepEqual(vocab.specialRewards, ["crit-aura", "crit-seed", "dragon-slayer"]);
});

test("catalog.yml の作業台レシピと ArsPaper の儀式エフェクトを別々に抽出する", () => {
  const vocab = buildGateVocabulary({
    catalog: {
      items: {
        default_workbench: { recipe: { ingredients: ["STICK"] } },
        explicit_workbench: { recipe: { method: "workbench" } },
        ritual_staff: { recipe: { method: "ritual" } },
        combine_only: { recipe: { method: "combine" } },
        no_recipe: { material: "STONE" },
        malformed: { recipe: "not-an-object" }
      }
    },
    items: { ritual_effects: { weather_clear: {}, flight: {} } }
  });
  assert.deepEqual(vocab.recipes, ["default_workbench", "explicit_workbench"]);
  assert.deepEqual(vocab.rituals, ["flight", "weather_clear"]);
});

test("features は param=level を含むプログラム定義の固定語彙", () => {
  const vocab = buildGateVocabulary({});
  const dismantle = vocab.features.find((f) => f.id === "dismantle-unlock");
  assert.ok(dismantle);
  assert.equal(dismantle.param, "level");
  // 呼び出し側が返り値を変更しても内部の FEATURES 定数に影響しない
  dismantle.label = "改変済み";
  const vocab2 = buildGateVocabulary({});
  assert.notEqual(vocab2.features.find((f) => f.id === "dismantle-unlock").label, "改変済み");
});
