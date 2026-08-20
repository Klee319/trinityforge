"use strict";

// armor-set-buffs 全面移行 §1: lib/schema.js validateSkillBuffOwner の set-buffs 検証
// (マップであること／段キーは3・4のみ／値はstat→数値)。

const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema");

test("tf-skilltree: valid set-buffs on a node and on prestige is accepted", () => {
  const errs = validate("tf-skilltree", {
    skill: "LIGHT_ARMOR",
    prestige: {
      "set-buffs": { 3: { "knockback-resistance": 0.1 }, 4: { "knockback-resistance": 0.2 } }
    },
    nodes: {
      C: { name: "c", level: 50, role: "main", "set-buffs": { 3: { "dodge-chance": 0.1 } } }
    }
  });
  assert.deepEqual(errs, []);
});

test("tf-skilltree: set-buffs tier keys other than 3/4 are rejected", () => {
  const errs = validate("tf-skilltree", {
    skill: "LIGHT_ARMOR",
    nodes: {
      C: { name: "c", level: 50, role: "main", "set-buffs": { 2: { "dodge-chance": 0.1 }, 5: { "dodge-chance": 0.1 } } }
    }
  });
  assert.ok(errs.some((e) => e.includes("nodes.C.set-buffs.2")), errs.join("; "));
  assert.ok(errs.some((e) => e.includes("nodes.C.set-buffs.5")), errs.join("; "));
});

test("tf-skilltree: set-buffs must be a map of maps of numbers", () => {
  const errs1 = validate("tf-skilltree", {
    skill: "LIGHT_ARMOR",
    nodes: { C: { name: "c", level: 50, role: "main", "set-buffs": "not-a-map" } }
  });
  assert.ok(errs1.some((e) => e.includes("nodes.C.set-buffs")));

  const errs2 = validate("tf-skilltree", {
    skill: "LIGHT_ARMOR",
    nodes: { C: { name: "c", level: 50, role: "main", "set-buffs": { 3: "not-a-map" } } }
  });
  assert.ok(errs2.some((e) => e.includes("nodes.C.set-buffs.3")));

  const errs3 = validate("tf-skilltree", {
    skill: "LIGHT_ARMOR",
    nodes: { C: { name: "c", level: 50, role: "main", "set-buffs": { 3: { "dodge-chance": "x" } } } }
  });
  assert.ok(errs3.some((e) => e.includes("nodes.C.set-buffs.3.dodge-chance")));
});

test("tf-skilltree: empty/absent set-buffs is valid", () => {
  assert.deepEqual(validate("tf-skilltree", {
    skill: "LIGHT_ARMOR",
    nodes: { C: { name: "c", level: 50, role: "main" } }
  }), []);
});
