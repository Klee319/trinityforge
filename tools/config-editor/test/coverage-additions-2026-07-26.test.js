"use strict";

// 2026-07-26 カバレッジ拡張の回帰テスト。
//
// 背景: stats/skill-exp.yml の exp-display:/level-up:、stats/mining-gimmick.yml の
// suspicious-block-respawn:、combat/mob-import.yml の unknown-mobs: の4キー群は、実際の
// 出荷ymlに存在するのに editor のどのフォーム/バリデータからも参照されていなかった
// (grep で public/js/ と lib/schema.js に一致なしを確認済み)。本テストは:
//   1) 各実ymlをフォームに通しても無編集なら完全に往復ロスレスであること
//   2) 新キーの編集が他の兄弟トップレベルキーを一切破壊しないこと
//   3) 新設した3バリデータ(tf-skill-exp の追加分・tf-mining-gimmick・tf-mob-import)が
//      型チェックを行うこと
// を確認する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

// ============================================================
// DOM スタブ (test/tab-restructure-2026-07-26.test.js と同じ軽量 makeEl 方式)
// ============================================================
function makeEl(tag, attrs) {
  const el = {
    tag,
    attrs: attrs || {},
    children: [],
    value: attrs && attrs.value != null ? attrs.value : "",
    textContent: attrs && attrs.text != null ? attrs.text : "",
    appendChild(child) {
      if (child !== null && child !== undefined && child !== false) el.children.push(child);
      return child;
    },
    querySelector() { return null; },
    addEventListener(type, fn) {
      if (type === "change") el._onchange = fn;
      if (type === "click") el._onclick = fn;
      if (type === "input") el._oninput = fn;
    }
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

function setupDom() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.document.createElementNS = (_namespace, tag) => {
    const el = makeEl(tag);
    el.setAttribute = (key, value) => { el.attrs[key] = value; };
    return el;
  };
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children !== undefined && children !== null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => el.appendChild(c));
    }
    return el;
  };
  global.window.fieldLabelEl = (key, options) => makeEl("div", {
    fieldKey: key,
    fieldOptions: options || {}
  });
  global.window.helpIcon = () => null;
  global.window.LABELS = {
    fieldLabel: (k) => k,
    fieldDesc: () => "",
    materialLabelWithFallback: (k) => ({
      DIAMOND_ORE: "ダイヤモンド鉱石",
      DEEPSLATE_DIAMOND_ORE: "深層ダイヤモンド鉱石"
    })[k] || k
  };
  global.window.VANILLA_MOBS = ["ZOMBIE", "SKELETON", "CREEPER"];
  global.window.MOB_LABELS_JA = {
    ZOMBIE: "ゾンビ",
    SKELETON: "スケルトン",
    CREEPER: "クリーパー"
  };
  global.window.MATERIALS = ["DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"];
  global.window.BLOCK_MATERIALS = new Set(global.window.MATERIALS);
  global.window.__skillExpListSelectConfigs = [];
  global.window.checkboxInput = (value, onChange) => {
    const el = makeEl("input", { value });
    el.trigger = (v) => onChange(v);
    return el;
  };
  global.window.collapsibleCard = (head, body) => {
    const el = makeEl("div");
    (Array.isArray(head) ? head : [head]).forEach((c) => el.appendChild(c));
    (Array.isArray(body) ? body : [body]).forEach((c) => el.appendChild(c));
    return el;
  };
  global.window.numberInput = (value, onInput, options) => {
    const el = makeEl("input", { value, numberOptions: options || {} });
    el.trigger = (v) => onInput(v);
    return el;
  };
  global.window.textInput = (value, onInput) => {
    const el = makeEl("input", { value });
    el.trigger = (v) => onInput(v);
    return el;
  };
  global.window.materialInput = (value, listId, onInput) => {
    const el = makeEl("span", { value });
    el.trigger = (v) => onInput(v);
    return el;
  };
  global.window.selectInput = (value, options, onChange) => {
    const el = makeEl("select", { value });
    el.trigger = (v) => onChange(v);
    return el;
  };
  global.window.listSelect = (cfg) => {
    global.window.__skillExpListSelectConfigs.push(cfg);
    const el = makeEl("span", { value: cfg.value, listConfig: cfg });
    el.trigger = (v) => {
      if (typeof cfg.onCommit === "function") return cfg.onCommit(v);
      if (typeof cfg.onChange === "function") return cfg.onChange(v);
      return undefined;
    };
    return el;
  };
  global.window.catalogItemSuggest = (value, candidates, onChange) => {
    const el = makeEl("span", { value });
    el.trigger = (v) => onChange({ id: v });
    return el;
  };
  global.alert = () => {};

  for (const mod of ["tf-forms.js", "tf-lifestyle-forms.js", "tf-dungeon-forms.js"]) {
    delete require.cache[require.resolve(`../public/js/${mod}`)];
    require(`../public/js/${mod}`);
  }
}

function loadYaml(relParts) {
  const root = path.resolve(__dirname, "..", "..", "..");
  const p = path.join(root, "TrinityForge", "src", "main", "resources", ...relParts);
  return YAML.parse(fs.readFileSync(p, "utf8"));
}

// ============================================================
// 1) 往復ロスレス (無編集)
// ============================================================

test("buildSkillExpForm: 実ymlを無編集で往復してもロスレス (exp-display/level-up含む)", () => {
  setupDom();
  const parsed = loadYaml(["stats", "skill-exp.yml"]);
  const expected = JSON.parse(JSON.stringify(parsed));
  const result = window.buildSkillExpForm(parsed, {});
  assert.deepEqual(result.getData(), expected);
});

test("buildMiningGimmickForm: 実ymlを無編集で往復してもロスレス (suspicious-block-respawn含む)", () => {
  setupDom();
  const parsed = loadYaml(["stats", "mining-gimmick.yml"]);
  const expected = JSON.parse(JSON.stringify(parsed));
  const result = window.buildMiningGimmickForm(parsed);
  assert.deepEqual(result.getData(), expected);
});

test("buildMobImportForm: 実ymlを無編集で往復してもロスレス (unknown-mobs含む)", () => {
  setupDom();
  const parsed = loadYaml(["combat", "mob-import.yml"]);
  const expected = JSON.parse(JSON.stringify(parsed));
  const result = window.buildMobImportForm(parsed);
  assert.deepEqual(result.getData(), expected);
});

// ============================================================
// 2) 1キー編集が兄弟トップレベルキーを破壊しないこと
// ============================================================

test("skill-exp.yml: exp-display.mode を編集しても他のトップレベルキーは無傷", () => {
  setupDom();
  const parsed = loadYaml(["stats", "skill-exp.yml"]);
  const expected = JSON.parse(JSON.stringify(parsed));
  window.buildSkillExpForm(parsed, {});
  parsed["exp-display"].mode = "actionbar";
  for (const key of Object.keys(expected).filter((k) => k !== "exp-display")) {
    assert.deepEqual(parsed[key], expected[key], `キー ${key} が変化した`);
  }
});

test("mining-gimmick.yml: suspicious-block-respawn.loot-tables を編集しても他のトップレベルキーは無傷", () => {
  setupDom();
  const parsed = loadYaml(["stats", "mining-gimmick.yml"]);
  const expected = JSON.parse(JSON.stringify(parsed));
  window.buildMiningGimmickForm(parsed);
  parsed["suspicious-block-respawn"]["loot-tables"]["suspicious-sand"] = "CHANGED";
  for (const key of Object.keys(expected).filter((k) => k !== "suspicious-block-respawn")) {
    assert.deepEqual(parsed[key], expected[key], `キー ${key} が変化した`);
  }
});

test("mob-import.yml: unknown-mobs.synthesize を編集しても他のトップレベルキーは無傷", () => {
  setupDom();
  const parsed = loadYaml(["combat", "mob-import.yml"]);
  const expected = JSON.parse(JSON.stringify(parsed));
  window.buildMobImportForm(parsed);
  parsed["unknown-mobs"].synthesize = false;
  for (const key of Object.keys(expected).filter((k) => k !== "unknown-mobs")) {
    assert.deepEqual(parsed[key], expected[key], `キー ${key} が変化した`);
  }
});

// ============================================================
// 3) バリデータ
// ============================================================
const { validate } = require("../lib/schema.js");

test("tf-skill-exp validate: exp-display の正常値はエラー無し", () => {
  const errors = validate("tf-skill-exp", {
    "exp-display": { mode: "actionbar", "bossbar-seconds": 2, "max-concurrent-bossbars": 2 }
  });
  assert.deepEqual(errors.filter((e) => e.includes("exp-display")), []);
});

test("tf-skill-exp validate: exp-display.mode が数値だとエラー", () => {
  const errors = validate("tf-skill-exp", { "exp-display": { mode: 5 } });
  assert.ok(errors.some((e) => e.includes("exp-display.mode")), `errors=${JSON.stringify(errors)}`);
});

test("tf-skill-exp validate: level-up の正常値はエラー無し", () => {
  const errors = validate("tf-skill-exp", {
    "level-up": { chat: true, "sound-enabled": true, sound: "ENTITY_PLAYER_LEVELUP", "title-every-levels": 10 }
  });
  assert.deepEqual(errors.filter((e) => e.includes("level-up")), []);
});

test("tf-skill-exp validate: level-up.chat が文字列だとエラー", () => {
  const errors = validate("tf-skill-exp", { "level-up": { chat: "yes" } });
  assert.ok(errors.some((e) => e.includes("level-up.chat")), `errors=${JSON.stringify(errors)}`);
});

test("tf-skill-exp validate: 採取EXP算出方式は3つの既知値だけを許可する", () => {
  for (const mode of ["drop_sum", "block_value", "max"]) {
    assert.deepEqual(validate("tf-skill-exp", { gathering: { "exp-mode": mode } }), []);
  }
  const errors = validate("tf-skill-exp", { gathering: { "exp-mode": "typo" } });
  assert.ok(errors.some((e) => e.includes("gathering.exp-mode")), `errors=${JSON.stringify(errors)}`);
});

test("tf-skill-exp validate: 出荷中の実 skill-exp.yml はトップレベルスカラーを含めて保存可能", () => {
  const parsed = loadYaml(["stats", "skill-exp.yml"]);
  assert.deepEqual(validate("tf-skill-exp", parsed), []);
});

test("tf-skill-exp validate: 既知のトップレベルスカラーは型と範囲を検証する", () => {
  const errors = validate("tf-skill-exp", {
    "dungeon-only-exp": "false",
    "outside-dungeon-exp-rate": -0.25
  });
  assert.ok(errors.some((e) => e.includes("dungeon-only-exp")), `errors=${JSON.stringify(errors)}`);
  assert.ok(errors.some((e) => e.includes("outside-dungeon-exp-rate")), `errors=${JSON.stringify(errors)}`);
});

test("tf-skill-exp validate: Ars魔法/戦闘の討伐・破壊EXP設定の正常値を許容する", () => {
  const errors = validate("tf-skill-exp", {
    "ars-magic": {
      "kill-exp": {
        enabled: true,
        base: 20,
        "per-mob-level": 1.5,
        "per-max-health": 0.25,
        "entity-type-multipliers": { ZOMBIE: 1, WARDEN: 7 }
      },
      "block-break-exp": { enabled: true, "source-multiplier": 1 }
    },
    combat: {
      "kill-exp": {
        base: { HEAVY_WEAPONS: 30, LIGHT_WEAPONS: 25 },
        "per-mob-level": 2,
        "per-max-health": 0.25,
        "entity-type-multipliers": { ZOMBIE: 1, WARDEN: 7 }
      }
    }
  });
  assert.deepEqual(errors, []);
});

test("tf-skill-exp validate: Ars魔法/戦闘の討伐・破壊EXP設定は型違いと負数を拒否する", () => {
  const errors = validate("tf-skill-exp", {
    "ars-magic": {
      "kill-exp": {
        enabled: "yes",
        base: -1,
        "per-mob-level": "fast",
        "per-max-health": -0.1,
        "entity-type-multipliers": { ZOMBIE: -1, WARDEN: "seven" }
      },
      "block-break-exp": { enabled: 1, "source-multiplier": -0.5 }
    },
    combat: {
      "kill-exp": {
        base: { HEAVY_WEAPONS: -30, LIGHT_WEAPONS: "many" },
        "per-mob-level": -2,
        "per-max-health": "quarter",
        "entity-type-multipliers": ["ZOMBIE"]
      }
    }
  });
  const expectedPaths = [
    "ars-magic.kill-exp.enabled",
    "ars-magic.kill-exp.base",
    "ars-magic.kill-exp.per-mob-level",
    "ars-magic.kill-exp.per-max-health",
    "ars-magic.kill-exp.entity-type-multipliers.ZOMBIE",
    "ars-magic.kill-exp.entity-type-multipliers.WARDEN",
    "ars-magic.block-break-exp.enabled",
    "ars-magic.block-break-exp.source-multiplier",
    "combat.kill-exp.base.HEAVY_WEAPONS",
    "combat.kill-exp.base.LIGHT_WEAPONS",
    "combat.kill-exp.per-mob-level",
    "combat.kill-exp.per-max-health",
    "combat.kill-exp.entity-type-multipliers"
  ];
  for (const pathName of expectedPaths) {
    assert.ok(errors.some((e) => e.includes(pathName)),
      `${pathName} の不正値が拒否されていない: ${JSON.stringify(errors)}`);
  }
});

test("tf-skill-exp validate: 廃止した戦闘・Ars魔法EXPキーを拒否する", () => {
  const errors = validate("tf-skill-exp", {
    "ars-magic": { "exp-per-cast": 1, "exp-per-mana": 0.25 },
    combat: {
      "exp-per-hit": 1,
      mode: "flat",
      "damage-scale": 0.1,
      "mob-level-scale": 0.02,
      "same-target-cooldown-seconds": 10,
      "by-skill": { archery: 7 }
    }
  });
  assert.ok(errors.some((e) => e.includes("ars-magic.exp-per-cast")), `errors=${JSON.stringify(errors)}`);
  assert.ok(errors.some((e) => e.includes("ars-magic.exp-per-mana")), `errors=${JSON.stringify(errors)}`);
  for (const key of [
    "exp-per-hit", "mode", "damage-scale", "mob-level-scale",
    "same-target-cooldown-seconds", "by-skill"
  ]) {
    assert.ok(errors.some((e) => e.includes(`combat.${key}`)),
      `combat.${key} が廃止キーとして拒否されていない: ${JSON.stringify(errors)}`);
  }
});

test("buildSkillExpForm: use-level-scaling.per-level の追加スキルも許可リスト無しで編集できる", () => {
  setupDom();
  const data = loadYaml(["stats", "skill-exp.yml"]);
  assert.equal(typeof data["use-level-scaling"]["per-level"]["ars-smithing"], "number",
    "実 skill-exp.yml に per-level.ars-smithing が存在しない");
  const result = window.buildSkillExpForm(data, {});

  function walk(el) {
    if (!el || typeof el !== "object") return [];
    return [el, ...(Array.isArray(el.children) ? el.children.flatMap(walk) : [])];
  }
  const arsSmithingRow = walk(result.element).find((el) =>
    el.attrs && el.attrs.class === "form-field"
      && el.children.some((child) => child.attrs && child.attrs.fieldKey === "ars-smithing"));
  const arsSmithingInput = arsSmithingRow && arsSmithingRow.children.find((el) =>
    el.tag === "input" && typeof el.trigger === "function");
  assert.ok(arsSmithingInput, "実ファイルの per-level.ars-smithing が入力欄に表示されていない");
  arsSmithingInput.trigger(0.2);
  assert.equal(result.getData()["use-level-scaling"]["per-level"]["ars-smithing"], 0.2);
  assert.equal(result.getData()["use-level-scaling"]["per-level"].smithing, 0.01);
});

test("buildSkillExpForm: progression.experience の未知の行動EXP表を再帰的に編集できる", () => {
  setupDom();
  const progression = {
    farming: {
      experience: {
        max_level: 100,
        exp_level_curve: "100 + %level%",
        legacy: null,
        future_action_table: {
          COW: 1234,
          nested_variant: { ELITE_COW: 2345.75 }
        }
      }
    }
  };
  const result = window.buildSkillExpForm({}, progression);

  function walk(el) {
    if (!el || typeof el !== "object") return [];
    return [el, ...(Array.isArray(el.children) ? el.children.flatMap(walk) : [])];
  }
  const controls = walk(result.element);
  const cowInput = controls.find((el) =>
    el.tag === "input" && el.value === 1234 && typeof el.trigger === "function");
  const eliteInput = controls.find((el) =>
    el.tag === "input" && el.value === 2345.75 && typeof el.trigger === "function");
  assert.ok(cowInput, "未知の行動EXP表 future_action_table.COW が数値入力になっていない");
  assert.ok(eliteInput, "2段以上ネストした数値表も再帰表示されていない");
  assert.equal(cowInput.attrs.numberOptions.int, false,
    "現在値が整数でもEXP表は小数へ調整できる入力である必要がある");

  cowInput.trigger(1500.25);
  eliteInput.trigger(2750.5);
  const saved = result.getProgressionData();
  assert.equal(saved.farming.experience.future_action_table.COW, 1500.25);
  assert.equal(saved.farming.experience.future_action_table.nested_variant.ELITE_COW, 2750.5);
  assert.equal(saved.farming.experience.exp_level_curve, "100 + %level%");
  assert.equal(Object.hasOwn(saved.farming.experience, "legacy"), false);
});

test("buildSkillExpForm: 全 progression 実ファイルは行動EXP表を含め無編集ならロスレス", () => {
  setupDom();
  const progression = {};
  const baseDir = path.join(
    path.resolve(__dirname, "..", "..", ".."),
    "TrinityForge", "src", "main", "resources", "skills", "base"
  );
  for (const file of fs.readdirSync(baseDir).filter((name) => name.endsWith("_progression.yml"))) {
    const skillId = file.slice(0, -"_progression.yml".length);
    progression[skillId] = YAML.parse(fs.readFileSync(path.join(baseDir, file), "utf8"));
  }
  const expected = JSON.parse(JSON.stringify(progression));
  const result = window.buildSkillExpForm({}, progression);
  assert.deepEqual(result.getProgressionData(), expected);
});

test("buildSkillExpForm: 敵倍率と採掘EXP素材は日本語セレクトで編集できる", () => {
  setupDom();
  const data = {
    combat: {
      "kill-exp": {
        base: { HEAVY_WEAPONS: 30, LIGHT_WEAPONS: 25 },
        "entity-type-multipliers": { ZOMBIE: 1 }
      }
    }
  };
  const progression = {
    mining: {
      experience: {
        max_level: 100,
        exp_level_curve: "100 + %level%",
        mining_break: { DIAMOND_ORE: 400 }
      }
    }
  };
  const result = window.buildSkillExpForm(data, progression);
  const configs = window.__skillExpListSelectConfigs;
  const mob = configs.find((cfg) => cfg.expMapKind === "mob" && cfg.value === "ZOMBIE");
  const material = configs.find((cfg) => cfg.expMapKind === "material" && cfg.value === "DIAMOND_ORE");

  assert.ok(mob, "敵倍率のキーがセレクトメニューになっていない");
  assert.ok(mob.options.some((o) =>
    o.value === "ZOMBIE" && o.primary === "ゾンビ" && o.secondary === undefined));
  assert.ok(material, "採掘EXPの素材キーがセレクトメニューになっていない");
  assert.ok(material.options.some((o) =>
    o.value === "DIAMOND_ORE" && o.primary === "ダイヤモンド鉱石"
      && o.secondary === undefined));

  assert.equal(mob.onCommit("SKELETON"), true);
  assert.equal(result.getData().combat["kill-exp"]["entity-type-multipliers"].ZOMBIE, undefined);
  assert.equal(result.getData().combat["kill-exp"]["entity-type-multipliers"].SKELETON, 1);
  assert.equal(material.onCommit("DEEPSLATE_DIAMOND_ORE"), true);
  assert.equal(result.getProgressionData().mining.experience.mining_break.DIAMOND_ORE, undefined);
  assert.equal(result.getProgressionData().mining.experience.mining_break.DEEPSLATE_DIAMOND_ORE, 400);
});

test("buildSkillExpForm: 旧EXPキーを読み込んでも保存データへ残さない", () => {
  setupDom();
  const data = {
    "ars-magic": {
      "exp-per-cast": 2,
      "exp-per-mana": 0.1,
      "kill-exp": { enabled: true }
    },
    combat: {
      "exp-per-hit": 1,
      mode: "flat",
      "damage-scale": 0.1,
      "mob-level-scale": 0.02,
      "same-target-cooldown-seconds": 10,
      "by-skill": { HEAVY_WEAPONS: 2 },
      "kill-exp": { base: { HEAVY_WEAPONS: 30, LIGHT_WEAPONS: 25 } }
    }
  };
  const progression = {
    heavy_weapons: {
      experience: {
        max_level: 100,
        exp_level_curve: "100 + %level%",
        exp_per_damage: 10,
        exp_enemies_nerfed: [],
        daily_limit: 1000,
        daily_limit_decay_percent: 5,
        legacy: null
      }
    }
  };
  const result = window.buildSkillExpForm(data, progression);
  assert.deepEqual(Object.keys(result.getData()["ars-magic"]).sort(), ["kill-exp"]);
  assert.deepEqual(Object.keys(result.getData().combat).sort(), ["kill-exp"]);
  assert.deepEqual(Object.keys(result.getProgressionData().heavy_weapons.experience).sort(),
    ["exp_level_curve", "max_level"]);
});

test("tf-mining-gimmick validate: suspicious-block-respawn の正常値はエラー無し", () => {
  const errors = validate("tf-mining-gimmick", {
    "suspicious-block-respawn": { "loot-tables": { "suspicious-sand": "DESERT_PYRAMID_ARCHAEOLOGY" } }
  });
  assert.deepEqual(errors, []);
});

test("tf-mining-gimmick validate: loot-tables の値が数値だとエラー", () => {
  const errors = validate("tf-mining-gimmick", {
    "suspicious-block-respawn": { "loot-tables": { "suspicious-sand": 5 } }
  });
  assert.ok(errors.some((e) => e.includes("suspicious-block-respawn.loot-tables.suspicious-sand")),
    `errors=${JSON.stringify(errors)}`);
});

test("tf-mob-import validate: unknown-mobs の正常値はエラー無し", () => {
  const errors = validate("tf-mob-import", { "unknown-mobs": { synthesize: true } });
  assert.deepEqual(errors, []);
});

test("tf-mob-import validate: unknown-mobs.synthesize が文字列だとエラー", () => {
  const errors = validate("tf-mob-import", { "unknown-mobs": { synthesize: "yes" } });
  assert.ok(errors.some((e) => e.includes("unknown-mobs.synthesize")), `errors=${JSON.stringify(errors)}`);
});
