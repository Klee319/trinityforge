"use strict";

// 2026-08-04 回帰テスト: skill-exp.yml に新設した2キーを config-editor の「スキルEXP」画面から
// 編集できるようにした対応のテスト。
//
//   power.levels-per-skill-point : 総合(POWER)を何レベルごとに1スキルポイント与えるか(整数、既定1)。
//     ページ上部の専用カードに描画する。working.power は progression-power の曲線を持つため
//     汎用セクションループでも「レベル曲線・獲得レート」の総合カードへ合流する
//     (curveBySkill.has("power") === true, skills/base/power_progression.yml が experience: を持つため)。
//     このキーを SECTION_EXCLUDED_KEYS で汎用ループから除外しないと、上部カードと総合カードの
//     2箇所に同じ入力欄が出る。
//
//   ars-smithing.exp-per-source : 儀式で消費したソース量に比例する追加EXP(0以上の数値、既定0.0)。
//
// 両キーとも Java 側 (SkillExpConfig) の既定値と editor 側の表示既定値が一致することを確認する
// (config-editor.md「Java側との既定値の食い違い」の教訓: normalize系の既定値がJavaとズレると
//  無編集保存だけで意味が変わる)。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");
const { validate } = require("../lib/schema.js");

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
    materialLabelWithFallback: (key) => key
  };
  window.VANILLA_MOBS = ["ZOMBIE", "SKELETON", "CREEPER"];
  window.MOB_LABELS_JA = { ZOMBIE: "ゾンビ", SKELETON: "スケルトン", CREEPER: "クリーパー" };
  window.MATERIALS = ["IRON_INGOT", "DIAMOND", "STICK"];
  window.BLOCK_MATERIALS = new Set(window.MATERIALS);
  window.CUSTOM_ITEM_CANDIDATES = [];
  window.checkboxInput = (value, onChange) => {
    const el = makeEl("input", { value });
    el.trigger = onChange;
    return el;
  };
  window.numberInput = (value, onChange, opts) => {
    const el = makeEl("input", { value, numberInputOpts: opts });
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

function reposRoot() {
  return path.resolve(__dirname, "..", "..", "..");
}

function loadRealSkillExp() {
  const p = path.join(reposRoot(), "TrinityForge", "src", "main", "resources", "stats", "skill-exp.yml");
  return YAML.parse(fs.readFileSync(p, "utf8"));
}

function loadRealPowerProgression() {
  const p = path.join(
    reposRoot(), "TrinityForge", "src", "main", "resources", "skills", "base", "power_progression.yml");
  return YAML.parse(fs.readFileSync(p, "utf8"));
}

// ============================================================
// 前提: 実 skill-exp.yml に2キーが存在し、Java既定値と editor 側の表示既定値が一致する
// ============================================================

test("前提: 実 skill-exp.yml は power.levels-per-skill-point と ars-smithing.exp-per-source を持つ", () => {
  const data = loadRealSkillExp();
  assert.equal(data.power && data.power["levels-per-skill-point"], 1,
    "power.levels-per-skill-point が実ファイルに無い、または既定値1と食い違う(テスト前提のドリフト)");
  assert.equal(data["ars-smithing"] && data["ars-smithing"]["exp-per-source"], 0.0,
    "ars-smithing.exp-per-source が実ファイルに無い、または既定値0.0と食い違う(テスト前提のドリフト)");
});

test("前提: 実 power_progression.yml は experience: を持つ(power セクションが曲線カードへ合流する前提)", () => {
  const progression = loadRealPowerProgression();
  assert.ok(progression.experience && typeof progression.experience === "object",
    "power_progression.yml が experience: を持たない(power が曲線カードへ合流しない=除外不要になる可能性)");
});

// ============================================================
// 二重描画の回帰防止: levels-per-skill-point の入力欄は1箇所だけ
// ============================================================

test("levels-per-skill-point の入力欄は上部の専用カードにだけ出る(総合カードとの二重描画防止)", () => {
  setupDom();
  const data = loadRealSkillExp();
  const progression = { power: loadRealPowerProgression() };
  const result = window.buildSkillExpForm(data, progression);

  const labels = walk(result.element).filter((el) =>
    el.tag === "label" && el.attrs && el.attrs.key === "levels-per-skill-point");
  assert.equal(labels.length, 1,
    `levels-per-skill-point のラベルが${labels.length}箇所に出ている(1箇所だけであるべき)`);
});

test("exp-per-source の入力欄はAr鍛冶カードに出る(素材表が引けないときの定額EXPと並んで表示)", () => {
  setupDom();
  const data = loadRealSkillExp();
  const result = window.buildSkillExpForm(data, {});

  const labels = walk(result.element).filter((el) =>
    el.tag === "label" && el.attrs && el.attrs.key === "exp-per-source");
  assert.equal(labels.length, 1, `exp-per-source のラベルが${labels.length}箇所に出ている`);
  assert.match(labels[0].attrs.options.label, /消費ソース/);
});

// ============================================================
// 値の編集
// ============================================================

test("上部カードの levels-per-skill-point を編集すると working.power に反映される", () => {
  setupDom();
  const data = { power: { "levels-per-skill-point": 1 } };
  const result = window.buildSkillExpForm(data, {});

  const label = walk(result.element).find((el) =>
    el.tag === "label" && el.attrs && el.attrs.key === "levels-per-skill-point");
  // ラベルの直後の兄弟が入力欄 (form-field の children = [label, control])。
  const formField = walk(result.element).find((el) =>
    el.attrs && el.attrs.class === "form-field" && el.children.includes(label));
  const input = formField.children.find((c) => c !== label);
  input.trigger(5);

  assert.equal(result.getData().power["levels-per-skill-point"], 5);
});

test("上部カードで0以下や小数を入力しても1未満には落ちない(下限クランプ)", () => {
  setupDom();
  const data = { power: { "levels-per-skill-point": 1 } };
  const result = window.buildSkillExpForm(data, {});
  const label = walk(result.element).find((el) =>
    el.tag === "label" && el.attrs && el.attrs.key === "levels-per-skill-point");
  const formField = walk(result.element).find((el) =>
    el.attrs && el.attrs.class === "form-field" && el.children.includes(label));
  const input = formField.children.find((c) => c !== label);

  input.trigger(0);
  assert.equal(result.getData().power["levels-per-skill-point"], 1, "0入力が1へクランプされていない");

  input.trigger(-3);
  assert.equal(result.getData().power["levels-per-skill-point"], 1, "負数入力が1へクランプされていない");

  input.trigger(2.7);
  assert.equal(result.getData().power["levels-per-skill-point"], 2, "小数入力が切り捨てられていない");
});

// ============================================================
// ロスレス往復: 無操作保存で power / ars-smithing が1文字も変わらない
// ============================================================

test("ロスレス: 実 skill-exp.yml を読み込んで何も操作せず保存しても power と ars-smithing は変形しない", () => {
  setupDom();
  const data = loadRealSkillExp();
  const expectedPower = JSON.parse(JSON.stringify(data.power));
  const expectedArsSmithing = JSON.parse(JSON.stringify(data["ars-smithing"]));
  const progression = { power: loadRealPowerProgression() };

  const result = window.buildSkillExpForm(data, progression);
  const got = result.getData();

  assert.deepEqual(got.power, expectedPower, "power セクションが無編集保存で変化した");
  assert.deepEqual(got["ars-smithing"], expectedArsSmithing, "ars-smithing セクションが無編集保存で変化した");
});

test("ロスレス: 実 skill-exp.yml 全体を無操作保存しても完全に往復する(progression 無しの経路)", () => {
  setupDom();
  const data = loadRealSkillExp();
  const expected = JSON.parse(JSON.stringify(data));
  const result = window.buildSkillExpForm(data, {});
  assert.deepEqual(result.getData(), expected);
});

// ============================================================
// スキーマ検証
// ============================================================

test("tf-skill-exp validate: power.levels-per-skill-point は1以上の整数のみ許可する", () => {
  assert.deepEqual(validate("tf-skill-exp", { power: { "levels-per-skill-point": 1 } }), []);
  assert.deepEqual(validate("tf-skill-exp", { power: { "levels-per-skill-point": 5 } }), []);

  for (const bad of [0, -1, 1.5, "1", null]) {
    if (bad === null) continue; // null/undefined は「未設定」として許容(既存方針)
    const errors = validate("tf-skill-exp", { power: { "levels-per-skill-point": bad } });
    assert.ok(errors.some((e) => e.includes("power.levels-per-skill-point")),
      `power.levels-per-skill-point=${JSON.stringify(bad)} がエラーにならない: ${JSON.stringify(errors)}`);
  }
});

test("tf-skill-exp validate: ars-smithing.exp-per-source は0以上の数値のみ許可する", () => {
  assert.deepEqual(validate("tf-skill-exp", { "ars-smithing": { "exp-per-source": 0 } }), []);
  assert.deepEqual(validate("tf-skill-exp", { "ars-smithing": { "exp-per-source": 0.001 } }), []);

  const errors = validate("tf-skill-exp", { "ars-smithing": { "exp-per-source": -0.5 } });
  assert.ok(errors.some((e) => e.includes("ars-smithing.exp-per-source")),
    `負数がエラーにならない: ${JSON.stringify(errors)}`);
});

test("tf-skill-exp validate: 出荷中の実 skill-exp.yml は power/ars-smithing を含めて検証を通る", () => {
  const data = loadRealSkillExp();
  const errors = validate("tf-skill-exp", data);
  assert.deepEqual(errors, []);
});
