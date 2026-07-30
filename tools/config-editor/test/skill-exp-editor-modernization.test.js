"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

function makeEl(tag, attrs) {
  const el = {
    tag,
    attrs: attrs || {},
    children: [],
    value: attrs && attrs.value != null ? attrs.value : "",
    appendChild(child) {
      if (child !== null && child !== undefined && child !== false) el.children.push(child);
      return child;
    },
    querySelector() { return null; },
    addEventListener() {}
  };
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

function setupDom() {
  global.window = {};
  global.document = {
    createElementNS(_namespace, tag) {
      const el = makeEl(tag);
      el.setAttribute = (key, value) => { el.attrs[key] = value; };
      return el;
    }
  };
  window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    for (const child of (Array.isArray(children) ? children : [children])) el.appendChild(child);
    return el;
  };
  window.fieldLabelEl = (key, options) => makeEl("label", { key, options });
  window.LABELS = {
    fieldLabel: (key) => key,
    materialLabelWithFallback: (key) => ({
      DIAMOND_ORE: "ダイヤモンド鉱石",
      DEEPSLATE_DIAMOND_ORE: "深層ダイヤモンド鉱石"
    })[key] || key
  };
  window.VANILLA_MOBS = ["ZOMBIE", "SKELETON", "CREEPER"];
  window.MOB_LABELS_JA = {
    ZOMBIE: "ゾンビ", SKELETON: "スケルトン", CREEPER: "クリーパー"
  };
  window.MATERIALS = ["DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"];
  window.BLOCK_MATERIALS = new Set(window.MATERIALS);
  window.checkboxInput = (value, onChange) => {
    const el = makeEl("input", { value });
    el.trigger = onChange;
    return el;
  };
  window.numberInput = (value, onChange) => {
    const el = makeEl("input", { value });
    el.trigger = onChange;
    return el;
  };
  window.textInput = window.numberInput;
  window.collapsibleCard = (_head, body) => makeEl("div", { body });
  window.listSelect = (cfg) => makeEl("select", { listConfig: cfg, value: cfg.value });
  global.alert = () => {};
  delete require.cache[require.resolve("../public/js/tf-forms.js")];
  require("../public/js/tf-forms.js");
}

function walk(el) {
  if (!el || typeof el !== "object") return [];
  return [el, ...(Array.isArray(el.children) ? el.children.flatMap(walk) : [])];
}

test("敵倍率マップは追加・削除・重複拒否を行い、未知の既存キーは温存する", () => {
  setupDom();
  const data = {
    combat: {
      "kill-exp": {
        "entity-type-multipliers": { ZOMBIE: 1, SKELETON: 2, CUSTOM_BOSS: 4 }
      }
    }
  };
  const result = window.buildSkillExpForm(data, {});
  const controls = () => walk(result.element);
  const selector = (value) => controls().find((el) =>
    el.attrs && el.attrs.listConfig && el.attrs.listConfig.expMapKind === "mob"
      && el.attrs.listConfig.value === value);

  const zombie = selector("ZOMBIE");
  assert.ok(zombie);
  assert.equal(zombie.attrs.listConfig.onCommit("SKELETON"), false, "重複キーへの変更を拒否しない");
  assert.deepEqual(result.getData().combat["kill-exp"]["entity-type-multipliers"],
    { ZOMBIE: 1, SKELETON: 2, CUSTOM_BOSS: 4 });

  const add = controls().find((el) =>
    el.attrs && el.attrs.listConfig && el.attrs.listConfig.expMapKind === "mob"
      && el.attrs.listConfig.value === "");
  assert.ok(add);
  assert.equal(add.attrs.listConfig.onCommit("CREEPER"), true);
  assert.equal(result.getData().combat["kill-exp"]["entity-type-multipliers"].CREEPER, 0);

  const creeperRow = controls().find((el) =>
    el.attrs && el.attrs.class === "stat-row se-exp-map-row"
      && el.children.some((child) => child.attrs && child.attrs.listConfig
        && child.attrs.listConfig.value === "CREEPER"));
  const remove = creeperRow.children.find((child) => child.attrs && typeof child.attrs.onclick === "function");
  remove.attrs.onclick();
  assert.equal(result.getData().combat["kill-exp"]["entity-type-multipliers"].CREEPER, undefined);
  assert.equal(result.getData().combat["kill-exp"]["entity-type-multipliers"].CUSTOM_BOSS, 4);
});

test("採取EXP算出方式は日本語セレクトで、任意文字列を入力させない", () => {
  setupDom();
  const result = window.buildSkillExpForm({
    gathering: { "exp-mode": "drop_sum" }
  }, {});
  const controls = walk(result.element);
  const modeSelect = controls.find((el) =>
    el.attrs && el.attrs.listConfig && el.attrs.listConfig.value === "drop_sum");

  assert.ok(modeSelect, "gathering.exp-mode がセレクトメニューになっていない");
  assert.deepEqual(modeSelect.attrs.listConfig.options, [
    { value: "drop_sum", primary: "ドロップ合計" },
    { value: "block_value", primary: "破壊ブロック基準" },
    { value: "max", primary: "大きい方を採用" }
  ]);

  modeSelect.attrs.listConfig.onChange("max");
  assert.equal(result.getData().gathering["exp-mode"], "max");
});

test("EXP専用画面は日本語ラベルの横にYAML内部キーを常時表示しない", () => {
  setupDom();
  const result = window.buildSkillExpForm({
    "dungeon-only-exp": true,
    "exp-display": {
      mode: "bossbar",
      "bossbar-seconds": 4,
      "max-concurrent-bossbars": 4
    },
    "level-up": {
      chat: true,
      "sound-enabled": true,
      sound: "ENTITY_PLAYER_LEVELUP",
      "title-every-levels": 10
    },
    "use-level-scaling": {
      enabled: true,
      "max-multiplier": 3
    }
  }, {});
  const labels = walk(result.element).filter((el) => el.tag === "label");

  for (const key of [
    "dungeon-only-exp", "mode", "bossbar-seconds", "max-concurrent-bossbars",
    "chat", "sound-enabled", "sound", "title-every-levels", "enabled", "max-multiplier"
  ]) {
    const label = labels.find((el) => el.attrs.key === key);
    assert.ok(label, `${key} のラベルがない`);
    assert.equal(label.attrs.options && label.attrs.options.hideKey, true,
      `${key} の内部キーが常時表示される`);
  }
});

test("既知のモブと素材はセレクト上で英語IDを併記しない", () => {
  setupDom();
  const result = window.buildSkillExpForm({
    combat: {
      "kill-exp": {
        "entity-type-multipliers": { ZOMBIE: 1 }
      }
    }
  }, {
    mining: {
      experience: {
        max_level: 100,
        exp_level_curve: "100 + %level%",
        mining_break: { DIAMOND_ORE: 400 }
      }
    }
  });
  const selects = walk(result.element)
    .filter((el) => el.attrs && el.attrs.listConfig)
    .map((el) => el.attrs.listConfig);
  const mob = selects.find((cfg) => cfg.expMapKind === "mob" && cfg.value === "ZOMBIE");
  const material = selects.find((cfg) =>
    cfg.expMapKind === "material" && cfg.value === "DIAMOND_ORE");

  assert.ok(mob);
  assert.deepEqual(mob.options.find((option) => option.value === "ZOMBIE"),
    { value: "ZOMBIE", primary: "ゾンビ" });
  assert.ok(material);
  assert.deepEqual(material.options.find((option) => option.value === "DIAMOND_ORE"),
    { value: "DIAMOND_ORE", primary: "ダイヤモンド鉱石" });
});

test("実progressionのEXPフィールド名は日本語ラベルへ解決される", () => {
  global.window = {};
  delete require.cache[require.resolve("../public/js/labels.js")];
  require("../public/js/labels.js");
  const baseDir = path.join(
    path.resolve(__dirname, "..", "..", ".."),
    "TrinityForge", "src", "main", "resources", "skills", "base"
  );
  const unresolved = [];
  for (const file of fs.readdirSync(baseDir).filter((name) => name.endsWith("_progression.yml"))) {
    const exp = YAML.parse(fs.readFileSync(path.join(baseDir, file), "utf8")).experience || {};
    for (const key of Object.keys(exp)) {
      if (key === "max_level" || key === "exp_level_curve") continue;
      if (window.LABELS.fieldLabel(key) === key) unresolved.push(`${file}:${key}`);
    }
  }
  assert.deepEqual(unresolved, []);
});
