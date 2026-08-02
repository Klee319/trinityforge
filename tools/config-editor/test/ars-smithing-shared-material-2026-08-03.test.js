"use strict";

// 2026-08-03 回帰テスト: skill-exp.yml (tf-skill-exp) の「Ars鍛冶」カードが
// exp-per-craft だけの旧表示のままで、鍛冶カードにある「素材ごとの獲得EXP」表が出ていなかった。
//
// 実装(ArsProgressionBridge#grantSmithingCraftExp, 2026-08-01以降)は儀式経路でも
// smithing.exp-per-material を読む(消費素材が全部表に載っているときだけ合計を使い、
// 1つでも表に無ければ ars-smithing.exp-per-craft の定額に戻る)。editor 側が古い表示の
// ままだと「実装済みなのにGUIから設定できない」状態になる。
//
// 修正方針: ars-smithing.exp-per-material という新しいキーは作らない
// (Java は smithing.exp-per-material しか読まないので、新キーは editor から保存できても
//  一切読まれない死に設定になる)。Ars鍛冶カードには smithing.exp-per-material への
// 直接参照を渡し、同じオブジェクトを鍛冶カードとAr鍛冶カードの両方で編集できるようにする。

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

function loadRealSkillExp() {
  const root = path.resolve(__dirname, "..", "..", "..");
  const p = path.join(root, "TrinityForge", "src", "main", "resources", "stats", "skill-exp.yml");
  return YAML.parse(fs.readFileSync(p, "utf8"));
}

function loadArsSmithingCurve() {
  const root = path.resolve(__dirname, "..", "..", "..");
  const p = path.join(
    root, "TrinityForge", "src", "main", "resources", "skills", "base", "ars_smithing_progression.yml");
  return { ars_smithing: YAML.parse(fs.readFileSync(p, "utf8")) };
}

test("前提: 実 skill-exp.yml は smithing.exp-per-material を持ち、ars-smithing はそれを持たない(テスト前提のドリフト検知)", () => {
  const data = loadRealSkillExp();
  assert.ok(data.smithing && typeof data.smithing["exp-per-material"] === "object",
    "smithing.exp-per-material が実ファイルに無い(テスト前提が崩れている)");
  assert.equal(data["ars-smithing"]["exp-per-material"], undefined,
    "ars-smithing.exp-per-material が既に存在する(想定と違うテスト前提)");
});

test("Ars鍛冶カードに素材ごとの獲得EXP表が出る(実ymlの内容で描画確認)", () => {
  setupDom();
  const data = loadRealSkillExp();
  const curves = loadArsSmithingCurve();
  const result = window.buildSkillExpForm(data, curves);

  const materialSelects = walk(result.element)
    .filter((el) => el.attrs && el.attrs.listConfig && el.attrs.listConfig.expMapKind === "material")
    .map((el) => el.attrs.listConfig.value);

  // smithing.exp-per-material の代表的な行(バニラ素材とcustom:素材の両方)が
  // Ars鍛冶カードの描画結果にも出ていること。
  assert.ok(materialSelects.includes("IRON_INGOT"), "IRON_INGOT の行がAr鍛冶カードに出ていない");
  assert.ok(materialSelects.includes("custom:source_gem"), "custom:source_gem の行がAr鍛冶カードに出ていない");
});

test("Ars鍛冶カードで素材表を編集すると smithing.exp-per-material が変わる(同一オブジェクト参照)", () => {
  setupDom();
  const data = {
    smithing: { "exp-per-craft": 15, "exp-per-material": { IRON_INGOT: 8, DIAMOND: 25 } },
    "ars-smithing": { "exp-per-craft": 100 }
  };
  const curves = { ars_smithing: { experience: { max_level: 50, exp_level_curve: "100 + %level%" } } };
  const result = window.buildSkillExpForm(data, curves);

  const ironRow = walk(result.element).find((el) =>
    el.attrs && el.attrs.listConfig && el.attrs.listConfig.expMapKind === "material"
      && el.attrs.listConfig.value === "IRON_INGOT");
  assert.ok(ironRow, "Ars鍛冶カードに IRON_INGOT の行が無い");

  // IRON_INGOT の行と同じ stat-row 内にある numberInput (EXP入力欄) を編集する。
  const rows = walk(result.element).filter((el) => el.attrs && el.attrs.class === "stat-row se-exp-map-row");
  const ironStatRow = rows.find((row) => row.children.some((child) =>
    child.attrs && child.attrs.listConfig && child.attrs.listConfig.value === "IRON_INGOT"));
  const ironAmountInput = ironStatRow.children.find((child) => child.tag === "input" && typeof child.trigger === "function");
  ironAmountInput.trigger(999);

  const gotData = result.getData();
  assert.equal(gotData.smithing["exp-per-material"].IRON_INGOT, 999,
    "Ars鍛冶カードでの編集が smithing.exp-per-material に反映されていない");
  // 新しいキー ars-smithing.exp-per-material が生えていないこと。
  assert.equal(gotData["ars-smithing"]["exp-per-material"], undefined,
    "ars-smithing.exp-per-material という新キーが生成されている");
});

test("Ars鍛冶カードから素材を新規追加しても、追加先は smithing.exp-per-material である(ars-smithing 側に新キーが生えない)", () => {
  setupDom();
  const data = {
    smithing: { "exp-per-craft": 15, "exp-per-material": { IRON_INGOT: 8 } },
    "ars-smithing": { "exp-per-craft": 100 }
  };
  const curves = { ars_smithing: { experience: { max_level: 50, exp_level_curve: "100 + %level%" } } };
  const result = window.buildSkillExpForm(data, curves);

  const addSelect = walk(result.element).find((el) =>
    el.attrs && el.attrs.listConfig && el.attrs.listConfig.expMapKind === "material"
      && el.attrs.listConfig.value === "" && el.attrs.listConfig.placeholder === "＋ 素材を追加…");
  assert.ok(addSelect, "Ars鍛冶カードに素材追加セレクトが無い");
  assert.equal(addSelect.attrs.listConfig.onCommit("DIAMOND"), true);

  const gotData = result.getData();
  assert.equal(gotData.smithing["exp-per-material"].DIAMOND, 0,
    "追加した素材が smithing.exp-per-material に入っていない");
  assert.equal(gotData["ars-smithing"]["exp-per-material"], undefined,
    "ars-smithing.exp-per-material という新キーが生成されている");
});

test("鍛冶カードで編集した内容がAr鍛冶カードの表示にも同時に反映される(同一参照)", () => {
  setupDom();
  const data = {
    smithing: { "exp-per-craft": 15, "exp-per-material": { IRON_INGOT: 8 } },
    "ars-smithing": { "exp-per-craft": 100 }
  };
  const curves = {
    smithing: { experience: { max_level: 50, exp_level_curve: "80 + %level%" } },
    ars_smithing: { experience: { max_level: 50, exp_level_curve: "100 + %level%" } }
  };
  const result = window.buildSkillExpForm(data, curves);

  // 鍛冶カード側の IRON_INGOT 行を編集する。
  const rows = walk(result.element).filter((el) => el.attrs && el.attrs.class === "stat-row se-exp-map-row");
  const ironStatRows = rows.filter((row) => row.children.some((child) =>
    child.attrs && child.attrs.listConfig && child.attrs.listConfig.value === "IRON_INGOT"));
  assert.equal(ironStatRows.length, 2, "IRON_INGOT の行は鍛冶カード/Ars鍛冶カードの2箇所に出ているはず");

  const firstAmountInput = ironStatRows[0].children.find((child) => child.tag === "input" && typeof child.trigger === "function");
  firstAmountInput.trigger(42);

  assert.equal(result.getData().smithing["exp-per-material"].IRON_INGOT, 42);
  // 同じオブジェクト参照なので、もう片方の行が保持する値(getData経由での再読込)も一致する。
  assert.equal(data.smithing["exp-per-material"].IRON_INGOT, 42);
});

test("Ars鍛冶カードの exp-per-craft ラベル/説明文は「素材表が全部載っているときだけ合計、1つでも欠けると定額」を説明する", () => {
  setupDom();
  const data = {
    smithing: { "exp-per-craft": 15, "exp-per-material": { IRON_INGOT: 8 } },
    "ars-smithing": { "exp-per-craft": 100 }
  };
  const curves = { ars_smithing: { experience: { max_level: 50, exp_level_curve: "100 + %level%" } } };
  window.buildSkillExpForm(data, curves);

  const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "tf-forms.js"), "utf8");
  const arsBlockMatch = src.match(/"ars-smithing":\s*\{[\s\S]*?"exp-per-craft":\s*\{[\s\S]*?\n\s*\}\s*\n\s*\}/);
  assert.ok(arsBlockMatch, "ars-smithing.exp-per-craft のフィールド定義が見つからない");
  const block = arsBlockMatch[0];
  assert.match(block, /全部/, "「全部載っている」旨の説明が無い");
  assert.match(block, /1つでも/, "「1つでも表に無ければ」旨の説明が無い");
  assert.doesNotMatch(block, /クラフト1回EXP/, "旧ラベル「クラフト1回EXP」がまだ残っている");
});

test("ロスレス: 実 skill-exp.yml をAr鍛冶カードの表示経路(progressionあり)に通しても無編集なら完全往復する", () => {
  setupDom();
  const data = loadRealSkillExp();
  const expected = JSON.parse(JSON.stringify(data));
  const curves = loadArsSmithingCurve();
  const result = window.buildSkillExpForm(data, curves);
  assert.deepEqual(result.getData(), expected);
});
