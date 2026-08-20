"use strict";

// progression/special-rewards.yml / achievements.yml / collection.yml (2026-07-23 §6.7) スキーマ検証のテスト。

const { test } = require("node:test");
const assert = require("node:assert");
const { validate } = require("../lib/schema.js");

// ---- tf-special-rewards ----

test("tf-special-rewards: 正常な3セクションはエラーなし", () => {
  const errors = validate("tf-special-rewards", {
    titles: { "dragon-slayer": { display: "<gradient:#ff0000:#ffff00>竜殺し</gradient>" } },
    particles: { "crit-aura": { particle: "CRIT", count: 8, radius: 0.6, "interval-ticks": 10, shape: "circle" } },
    "particle-seeds": { "crit-seed": { "seed-item": "custom:tf_crystal_apple", particle: "CRIT", count: 4 } }
  });
  assert.deepStrictEqual(errors, []);
});

test("tf-special-rewards: 空ルート/各セクション無しは許容", () => {
  assert.deepStrictEqual(validate("tf-special-rewards", null), []);
  assert.deepStrictEqual(validate("tf-special-rewards", {}), []);
});

test("tf-special-rewards: shape不正・型不正はエラー", () => {
  const errors = validate("tf-special-rewards", {
    particles: { bad: { particle: "FLAME", count: -1, shape: "square" } }
  });
  assert.ok(errors.some((e) => /particles\.bad\.count/.test(e)));
  assert.ok(errors.some((e) => /particles\.bad\.shape/.test(e)));
});

test("tf-special-rewards: titles.display は文字列必須", () => {
  const errors = validate("tf-special-rewards", { titles: { x: { display: 123 } } });
  assert.ok(errors.some((e) => /titles\.x\.display/.test(e)));
});

test("tf-special-rewards: prune-orphaned-grants の正常系(true/false)はエラーなし", () => {
  assert.deepStrictEqual(validate("tf-special-rewards", { "prune-orphaned-grants": true }), []);
  assert.deepStrictEqual(validate("tf-special-rewards", { "prune-orphaned-grants": false }), []);
});

test("tf-special-rewards: prune-orphaned-grants の型不正はエラー", () => {
  const errors = validate("tf-special-rewards", { "prune-orphaned-grants": "yes" });
  assert.ok(errors.some((e) => /prune-orphaned-grants/.test(e)));
});

// ---- tf-achievements ----

test("tf-achievements: statistic トリガーの正常系はエラーなし", () => {
  const errors = validate("tf-achievements", {
    achievements: {
      jump_king: {
        "display-name": "ジャンプ王",
        trigger: { type: "statistic", statistic: "JUMP", threshold: 10000 },
        broadcast: true,
        rewards: { special: ["dragon-slayer"], commands: ["give %player% diamond 1"] }
      }
    }
  });
  assert.deepStrictEqual(errors, []);
});

test("tf-achievements: advancement トリガーの正常系はエラーなし", () => {
  const errors = validate("tf-achievements", {
    achievements: {
      diamond_miner: {
        "display-name": "ダイヤ掘り",
        trigger: { type: "advancement", advancement: "minecraft:story/mine_diamond" }
      }
    }
  });
  assert.deepStrictEqual(errors, []);
});

test("tf-achievements: static（図鑑登録）トリガーの正常系はエラーなし", () => {
  assert.deepStrictEqual(validate("tf-achievements", { achievements: { collector: {
    trigger: { type: "static", collection: { scope: "category", target: "weapons", threshold: 75, percent: true } }
  } } }), []);
});

// 2026-07-31: Java 側は 2026-07-27 に collection.targets(複数)へ拡張済みだったのに、
// このスキーマは単数 target 必須のままだった。そのため「Java では正しく動く定義」が
// エディタでは常に検証エラーになり、複数対象アチーブメントを GUI で作れなかった。
test("tf-achievements: collection.targets(複数)だけでも通り、threshold は省略できる", () => {
  assert.deepStrictEqual(validate("tf-achievements", { achievements: { relics: {
    trigger: { type: "static", collection: {
      scope: "item", targets: ["binder_fragment", "reality_thread_core", "abyssal_ingot"]
    } }
  } } }), []);
});

test("tf-achievements: threshold 省略が許されるのは scope=item/mob かつ percent でないときだけ", () => {
  const category = validate("tf-achievements", { achievements: { a: {
    trigger: { type: "static", collection: { scope: "category", targets: ["dungeon"] } }
  } } });
  assert.ok(category.some((e) => /collection\.threshold/.test(e)), "category は候補数と列挙数が一致しないので必須");

  const percent = validate("tf-achievements", { achievements: { b: {
    trigger: { type: "static", collection: { scope: "item", targets: ["x", "y"], percent: true } }
  } } });
  assert.ok(percent.some((e) => /collection\.threshold/.test(e)), "percent は百分率なので件数を既定にできない");
});

test("tf-achievements: target も targets も無ければエラー(scope!=all)", () => {
  const errors = validate("tf-achievements", { achievements: { a: {
    trigger: { type: "static", collection: { scope: "item", threshold: 1 } }
  } } });
  assert.ok(errors.some((e) => /collection\.target \/ targets/.test(e)));
});

test("tf-achievements: collection.targets の型不正はエラー", () => {
  const errors = validate("tf-achievements", { achievements: { a: {
    trigger: { type: "static", collection: { scope: "item", targets: ["ok", 3, " "] } }
  } } });
  assert.ok(errors.some((e) => /collection\.targets/.test(e)));
});

test("tf-achievements: trigger欠落・type不正はエラー", () => {
  const errors = validate("tf-achievements", {
    achievements: { a: {}, b: { trigger: { type: "bogus" } } }
  });
  assert.ok(errors.some((e) => /achievements\.a\.trigger/.test(e)));
  assert.ok(errors.some((e) => /achievements\.b\.trigger\.type/.test(e)));
});

test("tf-achievements: statistic必須フィールド欠落はエラー", () => {
  const errors = validate("tf-achievements", {
    achievements: { a: { trigger: { type: "statistic" } } }
  });
  assert.ok(errors.some((e) => /trigger\.statistic/.test(e)));
  assert.ok(errors.some((e) => /trigger\.threshold/.test(e)));
});

test("tf-achievements: rewards.special/commands の型不正はエラー", () => {
  const errors = validate("tf-achievements", {
    achievements: {
      a: {
        trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
        rewards: { special: "not-array", commands: [123] }
      }
    }
  });
  assert.ok(errors.some((e) => /rewards\.special/.test(e)));
  assert.ok(errors.some((e) => /rewards\.commands\[0\]/.test(e)));
});

test("tf-achievements: rewards.items/vanilla-exp/job-exp/permanent-buffs の正常系はエラーなし", () => {
  const errors = validate("tf-achievements", {
    achievements: {
      a: {
        trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
        rewards: {
          items: [{ id: "diamond", amount: 3 }, { id: "tf_gacha_ticket_1" }],
          "vanilla-exp": 100,
          "job-exp": [{ skill: "MINING", amount: 500.5 }],
          "permanent-buffs": { "attack-power": 5, "move-speed": 0.02 }
        }
      }
    }
  });
  assert.deepStrictEqual(errors, []);
});

test("tf-achievements: rewards.items の型不正はエラー", () => {
  const errors = validate("tf-achievements", {
    achievements: {
      a: {
        trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
        rewards: { items: [{ id: "" }, { id: "diamond", amount: 0 }, { id: "diamond", amount: -1 }, "not-object"] }
      }
    }
  });
  assert.ok(errors.some((e) => /rewards\.items\[0\]\.id/.test(e)));
  assert.ok(errors.some((e) => /rewards\.items\[1\]\.amount/.test(e)));
  assert.ok(errors.some((e) => /rewards\.items\[2\]\.amount/.test(e)));
  assert.ok(errors.some((e) => /rewards\.items\[3\]:/.test(e)));
});

test("tf-achievements: rewards.vanilla-exp の型不正はエラー", () => {
  const errors = validate("tf-achievements", {
    achievements: {
      a: {
        trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
        rewards: { "vanilla-exp": -1 }
      }
    }
  });
  assert.ok(errors.some((e) => /rewards\.vanilla-exp/.test(e)));
});

test("tf-achievements: rewards.job-exp の skill不正/amount型不正はエラー", () => {
  const errors = validate("tf-achievements", {
    achievements: {
      a: {
        trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
        rewards: { "job-exp": [{ skill: "NOT_A_SKILL", amount: "many" }] }
      }
    }
  });
  assert.ok(errors.some((e) => /rewards\.job-exp\[0\]\.skill/.test(e)));
  assert.ok(errors.some((e) => /rewards\.job-exp\[0\]\.amount/.test(e)));
});

test("tf-achievements: rewards.permanent-buffs の値型不正はエラー", () => {
  const errors = validate("tf-achievements", {
    achievements: {
      a: {
        trigger: { type: "statistic", statistic: "JUMP", threshold: 1 },
        rewards: { "permanent-buffs": { "attack-power": "five" } }
      }
    }
  });
  assert.ok(errors.some((e) => /rewards\.permanent-buffs\.attack-power/.test(e)));
});

test("tf-achievements: vanilla-advancements の正常系はエラーなし", () => {
  const errors = validate("tf-achievements", {
    "vanilla-advancements": { disabled: true, "keep-recipe-advancements": true, keep: ["minecraft:story/"] },
    achievements: {}
  });
  assert.deepStrictEqual(errors, []);
});

test("tf-achievements: vanilla-advancements の型不正はエラー", () => {
  const errors = validate("tf-achievements", {
    "vanilla-advancements": { disabled: "yes", "keep-recipe-advancements": 1, keep: [1, "ok"] }
  });
  assert.ok(errors.some((e) => /vanilla-advancements\.disabled/.test(e)));
  assert.ok(errors.some((e) => /vanilla-advancements\.keep-recipe-advancements/.test(e)));
  assert.ok(errors.some((e) => /vanilla-advancements\.keep\[0\]/.test(e)));
});

test("tf-achievements: vanilla-advancements自体が非マップならエラー", () => {
  const errors = validate("tf-achievements", { "vanilla-advancements": "nope" });
  assert.ok(errors.some((e) => /vanilla-advancements:/.test(e)));
});

// ---- tf-collection ----

test("tf-collection: 既存reward-tiers構造 + categories追加はエラーなし", () => {
  const errors = validate("tf-collection", {
    enabled: true,
    sources: { "catalog-items": true, "mob-kills": true },
    categories: {
      items: { weapons: { "display-name": "武器", order: 1, entries: ["tf_gacha_ticket_1"] } },
      mobs: { bosses: { "display-name": "ボス", order: 1, entries: ["ENDER_DRAGON"] } }
    },
    "reward-tiers": {
      bronze: { threshold: 10, title: "駆け出し収集家", broadcast: false, commands: [], special: ["dragon-slayer"] }
    }
  });
  assert.deepStrictEqual(errors, []);
});

test("tf-collection: 空ルート/reward-tiers空は許容 (既存ファイル互換)", () => {
  assert.deepStrictEqual(validate("tf-collection", { enabled: true, "reward-tiers": {} }), []);
});

test("tf-collection: categories配下の型不正はエラー", () => {
  const errors = validate("tf-collection", {
    categories: { items: { weapons: { order: "one", entries: "not-array" } } }
  });
  assert.ok(errors.some((e) => /categories\.items\.weapons\.order/.test(e)));
  assert.ok(errors.some((e) => /categories\.items\.weapons\.entries/.test(e)));
});

test("tf-collection: reward-tiers.special の型不正はエラー", () => {
  const errors = validate("tf-collection", {
    "reward-tiers": { bronze: { special: [1, "ok"] } }
  });
  assert.ok(errors.some((e) => /reward-tiers\.bronze\.special\[0\]/.test(e)));
});

test("tf-collection: reward-tiers.items/vanilla-exp/job-exp/permanent-buffs の正常系はエラーなし", () => {
  const errors = validate("tf-collection", {
    "reward-tiers": {
      bronze: {
        threshold: 10,
        items: [{ id: "diamond", amount: 2 }],
        "vanilla-exp": 50,
        "job-exp": [{ skill: "SMITHING", amount: 100 }],
        "permanent-buffs": { "max-health": 4 }
      }
    }
  });
  assert.deepStrictEqual(errors, []);
});

test("tf-collection: reward-tiers.job-exp の skill不正はエラー", () => {
  const errors = validate("tf-collection", {
    "reward-tiers": { bronze: { "job-exp": [{ skill: "BOGUS", amount: 1 }] } }
  });
  assert.ok(errors.some((e) => /reward-tiers\.bronze\.job-exp\[0\]\.skill/.test(e)));
});
