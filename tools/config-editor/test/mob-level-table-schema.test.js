"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema");

test("tf-mob-level-table: 空/未設定(既定値)は妥当", () => {
  assert.deepEqual(validate("tf-mob-level-table", {}), []);
  assert.deepEqual(validate("tf-mob-level-table", { "dungeon-only": false, tiers: [] }), []);
});

test("tf-mob-level-table: dungeon-only が真偽値でなければエラー", () => {
  const errs = validate("tf-mob-level-table", { "dungeon-only": "yes" });
  assert.ok(errs.some((e) => e.includes("dungeon-only")));
});

test("tf-mob-level-table: 正常な帯定義はエラーなし", () => {
  const errs = validate("tf-mob-level-table", {
    "dungeon-only": true,
    tiers: [
      {
        "min-level": 0,
        "remove-drops": ["ROTTEN_FLESH"],
        "add-drops": [{ material: "BONE", chance: 0.3, min: 1, max: 2 }],
        "vanilla-exp": 5
      },
      { "min-level": 20, "vanilla-exp": 12 }
    ]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: min-level 重複はエラー", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0 }, { "min-level": 0 }]
  });
  assert.ok(errs.some((e) => e.includes("重複")));
});

test("tf-mob-level-table: min-level 負値/非整数はエラー", () => {
  const errs = validate("tf-mob-level-table", { tiers: [{ "min-level": -1 }] });
  assert.ok(errs.some((e) => e.includes("min-level")));
});

test("tf-mob-level-table: add-drops の必須フィールド欠落はエラー", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.3 }] }]
  });
  assert.ok(errs.some((e) => e.includes("add-drops[0].min")));
  assert.ok(errs.some((e) => e.includes("add-drops[0].max")));
});

test("tf-mob-level-table: add-drops の min>max はエラー", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.3, min: 5, max: 1 }] }]
  });
  assert.ok(errs.some((e) => e.includes("min(5) <= max(1)")));
});

test("tf-mob-level-table: remove-drops が非配列/非文字列要素はエラー", () => {
  const errs1 = validate("tf-mob-level-table", { tiers: [{ "min-level": 0, "remove-drops": "BONE" }] });
  assert.ok(errs1.some((e) => e.includes("remove-drops")));
  const errs2 = validate("tf-mob-level-table", { tiers: [{ "min-level": 0, "remove-drops": [123] }] });
  assert.ok(errs2.some((e) => e.includes("remove-drops[0]")));
});

test("tf-mob-level-table: vanilla-exp が負/非整数はエラー", () => {
  const errs = validate("tf-mob-level-table", { tiers: [{ "min-level": 0, "vanilla-exp": -1 }] });
  assert.ok(errs.some((e) => e.includes("vanilla-exp")));
});

test("tf-mob-level-table: tiers が非配列はエラー", () => {
  const errs = validate("tf-mob-level-table", { tiers: {} });
  assert.ok(errs.some((e) => e.includes("tiers")));
});

// --- 2026-07-25 モブ別ドロップ指定拡張 (§2-A mobs / §2-B custom:、テスト必須11の schema 側) ---

test("tf-mob-level-table: add-drops.material の custom:<id> はエラーなし", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "custom:tf_core_meat", chance: 0.1, min: 1, max: 1 }] }]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: add-drops.material が不正な形式はエラー", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "not a valid token!", chance: 0.1, min: 1, max: 1 }] }]
  });
  assert.ok(errs.some((e) => e.includes("add-drops[0].material")));
});

test("tf-mob-level-table: add-drops.mobs 指定ありはエラーなし", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{
      "min-level": 0,
      "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1, mobs: ["ZOMBIE", "SKELETON"] }]
    }]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: add-drops.mobs 未指定(省略)はエラーなし(後方互換)", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1 }] }]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: add-drops.mobs が非配列/不正要素はエラー", () => {
  const errs1 = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1, mobs: "ZOMBIE" }] }]
  });
  assert.ok(errs1.some((e) => e.includes("add-drops[0].mobs")));

  const errs2 = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "add-drops": [{ material: "BONE", chance: 0.1, min: 1, max: 1, mobs: ["zombie!"] }] }]
  });
  assert.ok(errs2.some((e) => e.includes("add-drops[0].mobs[0]")));
});

// --- 2026-07-26: 対象モブ絞り込み(mobs = EntityType / mob-ids = EliteMobsモブid) ---
// 「レベルテーブルを付けるモブを指定できない」への対応。1ダンジョンは同じEntityTypeの
// 見た目替えであることが多く、mobs: だけでは個体を狙えないため mob-ids を新設した。

test("tf-mob-level-table: 帯そのものの mobs / mob-ids は妥当", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, mobs: ["ZOMBIE"], "mob-ids": ["the_mines_boss_phase_1"] }]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: add-drops の mob-ids は妥当", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{
      "min-level": 10,
      "add-drops": [{ material: "BONE", chance: 0.5, min: 1, max: 1, "mob-ids": ["guild_boss.yml"] }]
    }]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: mob-ids が配列でなければエラー", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "mob-ids": "the_mines_boss" }]
  });
  assert.ok(errs.some((e) => e.includes("mob-ids")));
});

test("tf-mob-level-table: mob-ids の空文字/不正文字はエラー", () => {
  const errs = validate("tf-mob-level-table", {
    tiers: [{ "min-level": 0, "mob-ids": ["", "bad id!"] }]
  });
  assert.equal(errs.filter((e) => e.includes("mob-ids")).length, 2);
});

// --- 2026-07-27 牧場対策: no-skill-exp-mobs (トップレベル、tiers とは独立) ---
// バニラEXPオーブは対象外(従来どおり落ちる)。TrinityForgeの戦闘スキルEXP(武器命中/防具被弾/
// 魔法詠唱)だけを止める。

test("tf-mob-level-table: no-skill-exp-mobs 未指定/空配列はエラーなし(後方互換)", () => {
  assert.deepEqual(validate("tf-mob-level-table", {}), []);
  assert.deepEqual(validate("tf-mob-level-table", { "no-skill-exp-mobs": [] }), []);
});

test("tf-mob-level-table: no-skill-exp-mobs の正常な EntityType 一覧はエラーなし", () => {
  const errs = validate("tf-mob-level-table", {
    "no-skill-exp-mobs": ["BEE", "GOAT", "LLAMA", "TRADER_LLAMA", "PANDA", "WOLF", "IRON_GOLEM"]
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-level-table: no-skill-exp-mobs が非配列はエラー", () => {
  const errs = validate("tf-mob-level-table", { "no-skill-exp-mobs": "BEE" });
  assert.ok(errs.some((e) => e.includes("no-skill-exp-mobs")));
});

test("tf-mob-level-table: no-skill-exp-mobs の不正な要素はエラー", () => {
  const errs = validate("tf-mob-level-table", { "no-skill-exp-mobs": ["bee!", 123] });
  assert.equal(errs.filter((e) => e.includes("no-skill-exp-mobs[")).length, 2);
});
