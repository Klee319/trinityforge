"use strict";

// 儀式レシピの台座合計台数バリデーション (物理台座リング=48台、Y±1の3段×16マス) のテスト。
// "NAME xN" はゲーム内で台座N台分に展開されるため、行数ではなく合計台数で判定する。
// 2026-08-02: 上限を16→48に訂正(フォークの RitualManager#findNearbyPedestals は
// 外周16マスをY±1の3段ぶん走査するため、1段だけの16は誤った上限だった)。

const { test } = require("node:test");
const assert = require("node:assert");
const { validate } = require("../lib/schema.js");

function ritualDoc(pedestalItems) {
  return {
    items: {
      sample: {
        recipe: {
          method: "ritual",
          "core-item": "DIAMOND",
          "pedestal-items": pedestalItems,
          source: 100
        }
      }
    }
  };
}

test("pedestal-items: 合計48台ちょうどはエラーなし", () => {
  const errors = validate("ars-recipes", ritualDoc(["AMETHYST_SHARD x8", "GOLD_INGOT x39", "custom:thread_empty"]));
  assert.deepStrictEqual(errors, []);
});

test("pedestal-items: 1行のxNでも合計48台まで許容", () => {
  assert.deepStrictEqual(validate("ars-recipes", ritualDoc(["DIAMOND x48"])), []);
});

test("pedestal-items: 合計49台は上限超過エラー", () => {
  const errors = validate("ars-recipes", ritualDoc(["DIAMOND x48", "IRON_INGOT"]));
  assert.ok(errors.some((e) => /pedestal-items: 合計49台は上限48台/.test(e)), JSON.stringify(errors));
});

test("pedestal-items: xN展開で超過するケースもエラー", () => {
  const errors = validate("ars-recipes", ritualDoc(["AMETHYST_SHARD x30", "GOLD_INGOT x30"]));
  assert.ok(errors.some((e) => /上限48台/.test(e)), JSON.stringify(errors));
});

test("pedestal-items: ritual_effects 側でも同じ上限が効く", () => {
  const errors = validate("ars-recipes", {
    ritual_effects: {
      storm: {
        name: "嵐呼び",
        "effect-type": "weather",
        "pedestal-items": ["COPPER_INGOT x49"],
        source: 50
      }
    }
  });
  assert.ok(errors.some((e) => /ritual_effects\.storm\.pedestal-items: 合計49台は上限48台/.test(e)), JSON.stringify(errors));
});

test("pedestal-items: 形式不正行は台数計算から除外しつつ形式エラーを出す", () => {
  const errors = validate("ars-recipes", ritualDoc(["DIAMOND x48", "!!bad!!"]));
  assert.ok(errors.some((e) => /pedestal-items\[1\]/.test(e)), JSON.stringify(errors));
  // 不正行は台数に数えない → 上限超過エラーは出ない
  assert.ok(!errors.some((e) => /上限48台/.test(e)), JSON.stringify(errors));
});

test("pedestal-items: x0 は形式エラー (台座0台は不許可)", () => {
  const errors = validate("ars-recipes", ritualDoc(["DIAMOND x0"]));
  assert.ok(errors.some((e) => /pedestal-items\[0\]/.test(e)), JSON.stringify(errors));
});

test("pedestal-items: catalog スキーマ経由でも ritual method で台座上限が効く", () => {
  const errors = validate("catalog", {
    items: {
      ritual_staff: {
        material: "STICK",
        recipe: {
          method: "ritual",
          "pedestal-items": ["AMETHYST_SHARD x49"]
        }
      }
    }
  });
  assert.ok(errors.some((e) => /items\.ritual_staff\.recipe\.pedestal-items: 合計49台は上限48台/.test(e)), JSON.stringify(errors));
});
