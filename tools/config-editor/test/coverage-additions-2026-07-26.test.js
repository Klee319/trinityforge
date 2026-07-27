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
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children !== undefined && children !== null) {
      (Array.isArray(children) ? children : [children]).forEach((c) => el.appendChild(c));
    }
    return el;
  };
  global.window.fieldLabelEl = () => makeEl("div");
  global.window.helpIcon = () => null;
  global.window.LABELS = {
    fieldLabel: (k) => k,
    fieldDesc: () => ""
  };
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
  global.window.numberInput = (value, onInput) => {
    const el = makeEl("input", { value });
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
    const el = makeEl("span", { value: cfg.value });
    el.trigger = (v) => { if (typeof cfg.onChange === "function") cfg.onChange(v); };
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
