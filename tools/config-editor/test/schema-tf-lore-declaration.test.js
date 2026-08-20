"use strict";

// stats/lore.yml (tf-lore) の stats.<key>.trigger / limits スキーマ検証(段階1)。
// lib/schema.js の validateLoreTrigger / validateLoreLimits (validate("tf-lore", ...) 経由) を通す。

const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema");

function baseStat(extra) {
  return {
    stats: {
      "dodge-chance": Object.assign(
        { name: "回避率", format: "PERCENT", decimals: 0, order: 66, category: "defense" },
        extra
      ),
    },
  };
}

test("trigger/limits を省略しても検証を通る(任意宣言)", () => {
  const errors = validate("tf-lore", baseStat({}));
  assert.deepEqual(errors, []);
});

test("正しいtrigger/limitsの宣言は検証を通る", () => {
  const errors = validate(
    "tf-lore",
    baseStat({
      trigger: { when: "ON_DAMAGE_TAKEN", sources: "ALL", "applies-to": ["PLAYER", "MOB"] },
      limits: { cap: 0.9, "cap-ref": "combat/damage.yml#defense.max-dodge-chance" },
    })
  );
  assert.deepEqual(errors, []);
});

test("trigger.when が閉じた語彙外だとエラーになる", () => {
  const errors = validate(
    "tf-lore",
    baseStat({ trigger: { when: "ON_BOGUS", sources: "ALL", "applies-to": ["PLAYER"] } })
  );
  assert.ok(errors.some((e) => e.includes("trigger.when")));
});

test("trigger.sources が閉じた語彙外だとエラーになる", () => {
  const errors = validate(
    "tf-lore",
    baseStat({ trigger: { when: "ON_ANY_HIT", sources: "EVERYWHERE", "applies-to": ["PLAYER"] } })
  );
  assert.ok(errors.some((e) => e.includes("trigger.sources")));
});

test("trigger.applies-to が空配列だとエラーになる", () => {
  const errors = validate(
    "tf-lore",
    baseStat({ trigger: { when: "ON_ANY_HIT", sources: "ALL", "applies-to": [] } })
  );
  assert.ok(errors.some((e) => e.includes("applies-to")));
});

test("trigger.applies-to に不正な値が混じるとエラーになる", () => {
  const errors = validate(
    "tf-lore",
    baseStat({ trigger: { when: "ON_ANY_HIT", sources: "ALL", "applies-to": ["PLAYER", "NPC"] } })
  );
  assert.ok(errors.some((e) => e.includes("applies-to")));
});

test("limits.cap-ref が '#' を含まない書式だとエラーになる", () => {
  const errors = validate("tf-lore", baseStat({ limits: { cap: 1, "cap-ref": "combat/damage.yml" } }));
  assert.ok(errors.some((e) => e.includes("cap-ref")));
});

test("limits.cap-ref の java: 形式は '#' があれば通る", () => {
  const errors = validate(
    "tf-lore",
    baseStat({
      limits: {
        cap: 64,
        "cap-ref": "java:com.trinityforge.listeners.CombatListener#MAX_DISTANCE_DAMAGE_BLOCKS",
      },
    })
  );
  assert.deepEqual(errors, []);
});

test("limits.stacking が閉じた語彙外だとエラーになる", () => {
  const errors = validate("tf-lore", baseStat({ limits: { stacking: "WEIRD" } }));
  assert.ok(errors.some((e) => e.includes("stacking")));
});

test("limits.min-pieces が負の整数だとエラーになる", () => {
  const errors = validate("tf-lore", baseStat({ limits: { "min-pieces": -1 } }));
  assert.ok(errors.some((e) => e.includes("min-pieces")));
});

test("limits.max-duration-ticks / max-duration-ticks-ref の正しい宣言は検証を通る", () => {
  const errors = validate(
    "tf-lore",
    baseStat({
      limits: {
        "max-duration-ticks": 100,
        "max-duration-ticks-ref":
          "java:com.trinityforge.skilltree.runtime.NativeCombatPerkListener#MAX_STUN_DURATION_TICKS",
      },
    })
  );
  assert.deepEqual(errors, []);
});

test("limits.max-distance / max-distance-ref の正しい宣言は検証を通る", () => {
  const errors = validate(
    "tf-lore",
    baseStat({
      limits: {
        "max-distance": 64,
        "max-distance-ref": "java:com.trinityforge.listeners.CombatListener#MAX_DISTANCE_DAMAGE_BLOCKS",
      },
    })
  );
  assert.deepEqual(errors, []);
});

test("limits.<X>-ref だけがあり limits.<X> が無いとエラーになる(cap-ref単独)", () => {
  const errors = validate("tf-lore", baseStat({ limits: { "cap-ref": "combat/damage.yml#defense.x" } }));
  assert.ok(errors.some((e) => e.includes("cap-ref")));
});

test("limits.<X>-ref だけがあり limits.<X> が無いとエラーになる(max-duration-ticks-ref単独)", () => {
  const errors = validate(
    "tf-lore",
    baseStat({
      limits: {
        "max-duration-ticks-ref":
          "java:com.trinityforge.skilltree.runtime.NativeCombatPerkListener#MAX_STUN_DURATION_TICKS",
      },
    })
  );
  assert.ok(errors.some((e) => e.includes("max-duration-ticks-ref")));
});

test("limits に未知のフィールドがあるとエラーになる", () => {
  const errors = validate("tf-lore", baseStat({ limits: { "max-duration": 64 } }));
  assert.ok(errors.some((e) => e.includes("max-duration") && e.includes("不明なフィールド")));
});
