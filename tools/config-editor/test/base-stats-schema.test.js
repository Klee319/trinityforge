"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema");

test("tf-base-stats: 空/未設定は妥当", () => {
  assert.deepEqual(validate("tf-base-stats", {}), []);
  assert.deepEqual(validate("tf-base-stats", { "base-stats": {} }), []);
});

test("tf-base-stats: 数値マップは妥当", () => {
  assert.deepEqual(validate("tf-base-stats", {
    "base-stats": { "crit-chance": 0.05, "phys-flat-defense": 2, "max-health": 4 }
  }), []);
});

test("tf-base-stats: 非数値・非有限はエラー", () => {
  const errs = validate("tf-base-stats", {
    "base-stats": { "crit-chance": "x", "penetration": Infinity }
  });
  assert.equal(errs.length, 2);
  assert.ok(errs.some((e) => e.includes("crit-chance")));
  assert.ok(errs.some((e) => e.includes("penetration")));
});

test("tf-base-stats: base-stats が配列/非オブジェクトはエラー", () => {
  assert.ok(validate("tf-base-stats", { "base-stats": [] }).length >= 1);
  assert.ok(validate("tf-base-stats", "nope").length >= 1);
});
