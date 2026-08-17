"use strict";

// 2026-08-17 回帰テスト: 「editor で鍛冶の経験値素材のリストが Ars と通常の鍛冶で同期されて
// しまっている」というユーザー報告への修正を固定する。
//
// 経緯: 2026-08-03 に Ars鍛冶カードへ素材表を出したとき、Java 側が
// smithing.exp-per-material しか読まなかったため、Ars鍛冶カードには
// 【鍛冶の表そのものへの参照】を渡していた(＝どちらのカードで編集しても同じ実体が動く)。
// 2026-08-17 に Java を分離した(ars-smithing.exp-per-material を新設)ので、
// editor も別表を編集するように直した。
//
// 同時に ars-smithing.exp-per-craft(素材表が引けないときの定額EXP)を機能ごと廃止した。
// 定額があると「1つでも表に無い素材があれば合計を捨てて定額へ戻す」全か無かの分岐が必要で、
// 素材を1つ足すとEXPが100分の1に落ちる向きの不整合が出ていた(binder_spear が 100 → 1)。
//
// ここが赤に戻る＝どちらかのカードの編集がもう片方に漏れている、という意味。

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
  window.textInputOnCommit = window.textInput;
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

/** 素材マップの行(listSelect)の中で、指定キーの行が入っている stat-row を全部返す。 */
function statRowsFor(element, materialKey) {
  return walk(element)
    .filter((el) => el.attrs && el.attrs.class === "stat-row se-exp-map-row")
    .filter((row) => row.children.some((child) =>
      child.attrs && child.attrs.listConfig && child.attrs.listConfig.value === materialKey));
}

test("前提: 実 skill-exp.yml は Ars鍛冶専用の素材表を持ち、定額 exp-per-craft は持たない", () => {
  const data = loadRealSkillExp();
  assert.ok(data["ars-smithing"] && typeof data["ars-smithing"]["exp-per-material"] === "object",
    "ars-smithing.exp-per-material が実ファイルに無い(2026-08-17 の分離が入っていない)");
  assert.ok(data.smithing && typeof data.smithing["exp-per-material"] === "object",
    "smithing.exp-per-material が実ファイルに無い(テスト前提が崩れている)");
  assert.equal(data["ars-smithing"]["exp-per-craft"], undefined,
    "ars-smithing.exp-per-craft が残っている(2026-08-17 に機能ごと廃止した)");
});

test("Ars鍛冶カードに専用の素材表が出る(実ymlの内容で描画確認)", () => {
  setupDom();
  const data = loadRealSkillExp();
  const curves = loadArsSmithingCurve();
  const result = window.buildSkillExpForm(data, curves);

  const materialSelects = walk(result.element)
    .filter((el) => el.attrs && el.attrs.listConfig && el.attrs.listConfig.expMapKind === "material")
    .map((el) => el.attrs.listConfig.value);

  assert.ok(materialSelects.includes("IRON_INGOT"), "IRON_INGOT の行が出ていない");
  assert.ok(materialSelects.includes("custom:source_gem"), "custom:source_gem の行が出ていない");
});

test("Ars鍛冶カードで素材表を編集しても smithing.exp-per-material は動かない(表が分離している)", () => {
  setupDom();
  const data = {
    smithing: { "exp-per-craft": 15, "exp-per-material": { IRON_INGOT: 8 } },
    "ars-smithing": { "exp-per-source": 0.01, "exp-per-material": { IRON_INGOT: 8 } }
  };
  // 曲線を ars_smithing だけ渡すと鍛冶カードは「曲線なし」側に描かれる。
  // どちらの経路でも表が2つ出ることを下の別テストで見る。
  const curves = { ars_smithing: { experience: { max_level: 50, exp_level_curve: "100 + %level%" } } };
  const result = window.buildSkillExpForm(data, curves);

  const ironRows = statRowsFor(result.element, "IRON_INGOT");
  assert.equal(ironRows.length, 2,
    "IRON_INGOT の行は鍛冶カードとAr鍛冶カードで2つ出るはず(実際: " + ironRows.length + ")");

  // 2つの行のうち、片方を編集してもう片方が動かないことを見る。
  for (const row of ironRows) {
    const input = row.children.find((child) => child.tag === "input" && typeof child.trigger === "function");
    input.trigger(999);
    const got = result.getData();
    const changed = [got.smithing["exp-per-material"].IRON_INGOT,
      got["ars-smithing"]["exp-per-material"].IRON_INGOT].filter((v) => v === 999);
    assert.equal(changed.length, 1,
      "片方のカードの編集が両方の表に反映されている(表が共用に戻っている)。"
      + "実際の値: smithing=" + got.smithing["exp-per-material"].IRON_INGOT
      + " / ars-smithing=" + got["ars-smithing"]["exp-per-material"].IRON_INGOT);
    input.trigger(8); // 次のループのために戻す
  }
});

test("Ars鍛冶カードから素材を新規追加しても、追加先は ars-smithing 側だけ", () => {
  setupDom();
  const data = {
    smithing: { "exp-per-craft": 15, "exp-per-material": { IRON_INGOT: 8 } },
    "ars-smithing": { "exp-per-source": 0.01, "exp-per-material": { IRON_INGOT: 8 } }
  };
  const curves = { smithing: { experience: { max_level: 50, exp_level_curve: "80 + %level%" } } };
  const result = window.buildSkillExpForm(data, curves);

  // 素材追加セレクトは2つ(鍛冶カード / Ars鍛冶カード)出る。両方試して、
  // それぞれ自分の表にしか行が増えないことを確認する。
  const addSelects = walk(result.element).filter((el) =>
    el.attrs && el.attrs.listConfig && el.attrs.listConfig.expMapKind === "material"
      && el.attrs.listConfig.value === "" && el.attrs.listConfig.placeholder === "＋ 素材を追加…");
  assert.equal(addSelects.length, 2,
    "素材追加セレクトが2つ出ていない(実際: " + addSelects.length + ")");

  assert.equal(addSelects[0].attrs.listConfig.onCommit("DIAMOND"), true);
  const afterFirst = result.getData();
  const addedTo = [
    afterFirst.smithing["exp-per-material"].DIAMOND !== undefined ? "smithing" : null,
    afterFirst["ars-smithing"]["exp-per-material"].DIAMOND !== undefined ? "ars-smithing" : null
  ].filter(Boolean);
  assert.equal(addedTo.length, 1,
    "1つのカードで追加した素材が両方の表に入っている(表が共用に戻っている): " + addedTo.join(", "));
});

test("Ars鍛冶カードの説明文は「別の表」であることと定額廃止を明示する", () => {
  const src = fs.readFileSync(path.join(__dirname, "..", "public", "js", "tf-forms.js"), "utf8");
  const arsBlock = src.match(/"ars-smithing":\s*\{[\s\S]*?\n\s{6}\}\n\s{4}\};/);
  assert.ok(arsBlock, "ars-smithing のフィールド上書き定義が見つからない");
  const block = arsBlock[0];
  assert.match(block, /別の表/, "「別の表」である旨の説明が無い(共用と誤解される)");
  assert.match(block, /廃止/, "定額EXPが廃止された旨の説明が無い");
  assert.doesNotMatch(block, /素材表が引けないときの定額EXP/,
    "旧ラベル「素材表が引けないときの定額EXP」が残っている");
});

test("ロスレス: 実 skill-exp.yml を表示経路に通しても無編集なら完全往復する", () => {
  setupDom();
  const data = loadRealSkillExp();
  const expected = JSON.parse(JSON.stringify(data));
  const curves = loadArsSmithingCurve();
  const result = window.buildSkillExpForm(data, curves);
  assert.deepEqual(result.getData(), expected);
});

test("tf-skill-exp validate: ars-smithing.exp-per-material も 0以上の数値マップとして検証される", () => {
  const { validate } = require("../lib/schema.js");
  assert.deepEqual(
    validate("tf-skill-exp", { "ars-smithing": { "exp-per-material": { IRON_INGOT: 8, "custom:source_gem": 0 } } }),
    []);
  const errors = validate("tf-skill-exp", { "ars-smithing": { "exp-per-material": { IRON_INGOT: -1 } } });
  assert.ok(errors.some((e) => e.includes("ars-smithing.exp-per-material.IRON_INGOT")),
    "負の値が検出されていない: " + JSON.stringify(errors));
});
