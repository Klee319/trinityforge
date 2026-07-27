"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema");

test("tf-mob-overrides: 空/未設定(既定値)は妥当", () => {
  assert.deepEqual(validate("tf-mob-overrides", {}), []);
  assert.deepEqual(validate("tf-mob-overrides", { overrides: {} }), []);
});

test("tf-mob-overrides: overrides が非マップはエラー", () => {
  const errs = validate("tf-mob-overrides", { overrides: [] });
  assert.ok(errs.some((e) => e.includes("overrides")));
});

test("tf-mob-overrides: 正常なワールド/defaultスコープ定義はエラーなし", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: {
        mobs: {
          goblin_chief: {
            stats: { level: 50, "max-health": 1200, "armor-strength": 0.3 },
            drops: [{ item: "custom:source_gem", chance: 0.25, min: 1, max: 3 }]
          }
        }
      },
      my_dungeon_world: {
        mobs: {
          goblin_chief: { stats: { "max-health": 2000 } }
        }
      }
    }
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-overrides: stats.level が非整数はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: { default: { mobs: { goblin_chief: { stats: { level: -1 } } } } }
  });
  assert.ok(errs.some((e) => e.includes("stats.level")));
});

test("tf-mob-overrides: stats.physical/magical の数値以外の値はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: {
        mobs: {
          goblin_chief: { stats: { physical: { "defense-rate": "high" } } }
        }
      }
    }
  });
  assert.ok(errs.some((e) => e.includes("stats.physical.defense-rate")));
});

test("tf-mob-overrides: stats.attack の各フィールド(attack-power含む)を数値検証する", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: {
        mobs: {
          goblin_chief: { stats: { attack: { "attack-power": "lots", "crit-chance": 0.5 } } }
        }
      }
    }
  });
  assert.ok(errs.some((e) => e.includes("stats.attack.attack-power")));
});

test("tf-mob-overrides: drops の必須フィールド欠落はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: { default: { mobs: { goblin_chief: { drops: [{ item: "BONE", chance: 0.3 }] } } } }
  });
  assert.ok(errs.some((e) => e.includes("drops[0].min")));
  assert.ok(errs.some((e) => e.includes("drops[0].max")));
});

test("tf-mob-overrides: drops の min>max はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: { mobs: { goblin_chief: { drops: [{ item: "BONE", chance: 0.3, min: 5, max: 1 }] } } }
    }
  });
  assert.ok(errs.some((e) => e.includes("min(5) <= max(1)")));
});

test("tf-mob-overrides: drops.item の custom:<id> はエラーなし", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: {
        mobs: { goblin_chief: { drops: [{ item: "custom:tf_core_meat", chance: 0.1, min: 1, max: 1 }] } }
      }
    }
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-overrides: drops.item が不正な形式はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: {
        mobs: { goblin_chief: { drops: [{ item: "not a valid token!", chance: 0.1, min: 1, max: 1 }] } }
      }
    }
  });
  assert.ok(errs.some((e) => e.includes("drops[0].item")));
});

test("tf-mob-overrides: scope.mobs が非マップはエラー", () => {
  const errs = validate("tf-mob-overrides", { overrides: { default: { mobs: [] } } });
  assert.ok(errs.some((e) => e.includes("overrides.default.mobs")));
});

test("tf-mob-overrides: mobエントリが非マップはエラー", () => {
  const errs = validate("tf-mob-overrides", { overrides: { default: { mobs: { goblin_chief: "oops" } } } });
  assert.ok(errs.some((e) => e.includes("overrides.default.mobs.goblin_chief")));
});

// --- vanilla-exp (モブごとのレベル依存EXP式、2026-07-26) ---

function expEntry(exp) {
  return { overrides: { default: { mobs: { goblin_chief: { "vanilla-exp": exp } } } } };
}

test("tf-mob-overrides: vanilla-exp は数値単体でも妥当", () => {
  assert.deepEqual(validate("tf-mob-overrides", expEntry(25)), []);
  assert.deepEqual(validate("tf-mob-overrides", expEntry(0)), []);
});

test("tf-mob-overrides: vanilla-exp はランプ(base/per-level/growth/growth-interval)でも妥当", () => {
  assert.deepEqual(validate("tf-mob-overrides", expEntry({
    base: 5, "per-level": 1.5, growth: 1.03, "growth-interval": 1.0
  })), []);
  assert.deepEqual(validate("tf-mob-overrides", expEntry({ base: 10 })), []);
  assert.deepEqual(validate("tf-mob-overrides", expEntry({})), []);
});

test("tf-mob-overrides: vanilla-exp の負のスカラーはエラー", () => {
  const errs = validate("tf-mob-overrides", expEntry(-1));
  assert.ok(errs.some((e) => e.includes("vanilla-exp")));
});

test("tf-mob-overrides: vanilla-exp が文字列/配列はエラー", () => {
  assert.ok(validate("tf-mob-overrides", expEntry("25")).some((e) => e.includes("vanilla-exp")));
  assert.ok(validate("tf-mob-overrides", expEntry([1, 2])).some((e) => e.includes("vanilla-exp")));
});

test("tf-mob-overrides: vanilla-exp の各項目が非数値はエラー", () => {
  const errs = validate("tf-mob-overrides", expEntry({ base: "x", "per-level": 1 }));
  assert.ok(errs.some((e) => e.includes("vanilla-exp.base")));
});

test("tf-mob-overrides: vanilla-exp の未知キーはエラー", () => {
  const errs = validate("tf-mob-overrides", expEntry({ base: 5, "per-lvl": 1 }));
  assert.ok(errs.some((e) => e.includes("vanilla-exp.per-lvl")));
});

test("tf-mob-overrides: growth-interval が0以下はエラー", () => {
  assert.ok(validate("tf-mob-overrides", expEntry({ base: 5, "growth-interval": 0 }))
    .some((e) => e.includes("growth-interval")));
  assert.ok(validate("tf-mob-overrides", expEntry({ base: 5, growth: -1 }))
    .some((e) => e.includes("growth")));
});

// --- 2026-07-26: display-name(ダンジョン名/モブ表示名) ---
// GUI・簡易モードで日本語名を編集できるようにしたキー。省略可、指定時は非空文字列。

test("tf-mob-overrides: スコープ/モブの display-name は妥当", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      em_id_the_mines: {
        "display-name": "鉱山",
        mobs: { the_mines_arachnidian_construct: { "display-name": "蜘蛛型構造体" } }
      }
    }
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-overrides: display-name が空文字/空白のみならエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: { em_id_the_mines: { "display-name": "   ", mobs: { a: { "display-name": "" } } } }
  });
  assert.equal(errs.filter((e) => e.includes("display-name")).length, 2);
});

test("tf-mob-overrides: display-name が文字列でなければエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: { em_id_the_mines: { "display-name": 123, mobs: {} } }
  });
  assert.ok(errs.some((e) => e.includes("display-name")));
});

// --- level-cutoff (レベル差による足きり、2026-07-27) ---

test("tf-mob-overrides: level-cutoff はスコープ単位・モブ単位のどちらでも妥当な形ならエラーなし", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: {
        "level-cutoff": { "over-level": { threshold: 10, "exp-rate": 0.25, "drop-rate": -1 } },
        mobs: {
          goblin_chief: {
            "level-cutoff": {
              "over-level": { threshold: 10, "exp-rate": -1, "drop-rate": 0.5 },
              "under-level": { "item-threshold": 20 }
            }
          }
        }
      }
    }
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-overrides: level-cutoff の負のthresholdは妥当(『無効』の正当な表現)", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: {
        mobs: {
          goblin_chief: {
            "level-cutoff": {
              "over-level": { threshold: -1 },
              "under-level": { "item-threshold": -1 }
            }
          }
        }
      }
    }
  });
  assert.deepEqual(errs, []);
});

test("tf-mob-overrides: level-cutoff.over-level.exp-rate が範囲外(-1でも[0,1]でもない)はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: { mobs: { goblin_chief: { "level-cutoff": { "over-level": { "exp-rate": 2.0 } } } } }
    }
  });
  assert.ok(errs.some((e) => e.includes("level-cutoff.over-level.exp-rate")));
});

test("tf-mob-overrides: level-cutoff.over-level.drop-rate が-0.5(範囲外)はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: { mobs: { goblin_chief: { "level-cutoff": { "over-level": { "drop-rate": -0.5 } } } } }
    }
  });
  assert.ok(errs.some((e) => e.includes("level-cutoff.over-level.drop-rate")));
});

test("tf-mob-overrides: level-cutoff.over-level.threshold が非整数はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: { mobs: { goblin_chief: { "level-cutoff": { "over-level": { threshold: 1.5 } } } } }
    }
  });
  assert.ok(errs.some((e) => e.includes("level-cutoff.over-level.threshold")));
});

test("tf-mob-overrides: level-cutoff.under-level.item-threshold が非整数はエラー", () => {
  const errs = validate("tf-mob-overrides", {
    overrides: {
      default: {
        mobs: { goblin_chief: { "level-cutoff": { "under-level": { "item-threshold": "high" } } } }
      }
    }
  });
  assert.ok(errs.some((e) => e.includes("level-cutoff.under-level.item-threshold")));
});

test("tf-mob-overrides: level-cutoff / over-level / under-level が非マップはエラー", () => {
  assert.ok(validate("tf-mob-overrides", {
    overrides: { default: { mobs: { goblin_chief: { "level-cutoff": [] } } } }
  }).some((e) => e.includes("level-cutoff")));
  assert.ok(validate("tf-mob-overrides", {
    overrides: { default: { mobs: { goblin_chief: { "level-cutoff": { "over-level": [] } } } } }
  }).some((e) => e.includes("level-cutoff.over-level")));
  assert.ok(validate("tf-mob-overrides", {
    overrides: { default: { mobs: { goblin_chief: { "level-cutoff": { "under-level": [] } } } } }
  }).some((e) => e.includes("level-cutoff.under-level")));
});
