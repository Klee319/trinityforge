"use strict";

// 2026-09-04: 本体側で追加された3キーへの追随の回帰。
//   A. progression/level-broadcast.yml
//      - min-interval-seconds (数値, 既定30, 範囲0〜3600, 0で無効)
//      - message-prestige (文字列/MiniMessage, プレステージ1以上用の書式)
//   B. stats/skill-exp.yml
//      - ars-smithing.max-source-exp-per-craft (数値, 既定100000, 下限0=上限なし)
//
// A は既存の tf-level-broadcast-form.js に GUI がある前提(専用フォーム画面)。
// B はスキルEXP画面の generic scalarSectionBody 経由で描画される
// (tf-forms.js の SECTION_FIELD_OVERRIDES にラベル/説明を追加しただけで、新規画面は作っていない)。

const test = require("node:test");
const assert = require("node:assert/strict");
const { validate } = require("../lib/schema.js");

// ---- A. level-broadcast.yml: schema 検証 ----------------------------------

test("schema: min-interval-seconds は0〜3600の整数のみ許可する", () => {
  assert.deepEqual(validate("tf-level-broadcast", { "min-interval-seconds": 30 }), []);
  assert.deepEqual(validate("tf-level-broadcast", { "min-interval-seconds": 0 }), []);
  assert.deepEqual(validate("tf-level-broadcast", { "min-interval-seconds": 3600 }), []);

  assert.ok(validate("tf-level-broadcast", { "min-interval-seconds": 3601 })
    .some((e) => e.startsWith("min-interval-seconds")), "上限3600を超えた値が通ってしまう");
  assert.ok(validate("tf-level-broadcast", { "min-interval-seconds": -1 })
    .some((e) => e.startsWith("min-interval-seconds")), "負数が通ってしまう");
  assert.ok(validate("tf-level-broadcast", { "min-interval-seconds": 4000 })
    .some((e) => e.startsWith("min-interval-seconds")), "範囲外(4000)が通ってしまう");
});

test("schema: message-prestige は %player% / %level% が必須(message と同じ規約)", () => {
  assert.deepEqual(validate("tf-level-broadcast", {
    "message-prestige": "<gold>%player%</gold> が %skill%（%prestige%周目）で Lv%level% に到達しました!"
  }), []);

  const missingPlayer = validate("tf-level-broadcast", { "message-prestige": "Lv%level% 到達!" });
  assert.ok(missingPlayer.some((e) => e.includes("message-prestige") && e.includes("%player%")));

  const missingLevel = validate("tf-level-broadcast", { "message-prestige": "%player% が到達!" });
  assert.ok(missingLevel.some((e) => e.includes("message-prestige") && e.includes("%level%")));

  const notString = validate("tf-level-broadcast", { "message-prestige": 123 });
  assert.ok(notString.some((e) => e.startsWith("message-prestige")));
});

test("schema: 出荷 yml 相当(A+B) は丸ごと通る", () => {
  const ok = {
    enabled: true,
    "multiple-of": 10,
    message: "<gold>%player%</gold> が %skill% で Lv%level% に到達しました!",
    "message-prestige": "<gold>%player%</gold> が %skill%（%prestige%周目）で Lv%level% に到達しました!",
    "include-power": false,
    "max-announcements-per-batch": 3,
    "min-interval-seconds": 30,
    sound: { enabled: true, key: "UI_TOAST_CHALLENGE_COMPLETE", volume: 1.0, pitch: 1.0 },
    "excluded-skills": [],
    "excluded-levels": []
  };
  assert.deepEqual(validate("tf-level-broadcast", ok), []);
});

// ---- B. skill-exp.yml: ars-smithing.max-source-exp-per-craft ---------------

test("schema: ars-smithing.max-source-exp-per-craft は0以上の数値のみ許可する(0=上限なし)", () => {
  assert.deepEqual(validate("tf-skill-exp", {
    "ars-smithing": { "exp-per-source": 0.2, "max-source-exp-per-craft": 100000 }
  }), []);
  assert.deepEqual(validate("tf-skill-exp", {
    "ars-smithing": { "max-source-exp-per-craft": 0 }
  }), [], "0(上限なし)は正当な設定値なのでエラーにならない");

  const negative = validate("tf-skill-exp", { "ars-smithing": { "max-source-exp-per-craft": -1 } });
  assert.ok(negative.some((e) => e.startsWith("ars-smithing.max-source-exp-per-craft")));
});

// ---- 往復ロスレス: 未設定の yml を読んで保存しても A のキーが勝手に生えない -------

test("ロスレス: min-interval-seconds / message-prestige が未設定のデータは保存時にも生えない", () => {
  const legacy = {
    enabled: true,
    "multiple-of": 10,
    message: "<gold>%player%</gold> Lv%level%!",
    "include-power": false,
    "max-announcements-per-batch": 3,
    sound: { enabled: true, key: "UI_TOAST_CHALLENGE_COMPLETE", volume: 1.0, pitch: 1.0 },
    "excluded-skills": [],
    "excluded-levels": []
  };
  const original = JSON.parse(JSON.stringify(legacy));
  assert.deepEqual(validate("tf-level-broadcast", legacy), []);

  const working = buildLevelBroadcastWorking(legacy);
  const saved = working.getData();

  assert.deepEqual(saved, original, "無編集での往復で min-interval-seconds/message-prestige が生えている");
  assert.equal(Object.prototype.hasOwnProperty.call(saved, "min-interval-seconds"), false);
  assert.equal(Object.prototype.hasOwnProperty.call(saved, "message-prestige"), false);
});

test("フォーム: min-interval-seconds と message-prestige がフォームの入力欄として配線されている", () => {
  const data = {
    enabled: true,
    "multiple-of": 10,
    message: "<gold>%player%</gold> Lv%level%!",
    "min-interval-seconds": 45,
    "message-prestige": "<gold>%player%</gold> Lv%level%（%prestige%周目）!",
    sound: {}
  };
  const working = buildLevelBroadcastWorking(data);

  const numberInputs = findAllByProp(working.element, (el) => el.tag === "input" && el.props.class === "num");
  assert.ok(numberInputs.some((el) => el.props.value === 45),
    "min-interval-seconds(45) が数値入力として描画されていない");

  const richInputs = findAllByProp(working.element, (el) => el.tag === "input" && el.props.class === "rich");
  assert.ok(richInputs.some((el) => el.props.value === data["message-prestige"]),
    "message-prestige がリッチテキスト入力として描画されていない");

  // 編集して getData に反映されることも確認する(片方向バインドで固まっていないか)。
  const target = richInputs.find((el) => el.props.value === data["message-prestige"]);
  target.__onInput("<gold>変更後</gold> %player% Lv%level%（%prestige%）");
  assert.equal(working.getData()["message-prestige"], "<gold>変更後</gold> %player% Lv%level%（%prestige%）");
});

// ---- テストハーネス ---------------------------------------------------------

function makeEl(tag, props) {
  const el = {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { this.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; },
    set textContent(v) { this.props.text = v; },
    get textContent() { return this.props.text || ""; }
  };
  return el;
}

function findAllByProp(el, predicate, out) {
  out = out || [];
  if (!el) return out;
  if (predicate(el)) out.push(el);
  (el.children || []).forEach((c) => findAllByProp(c, predicate, out));
  return out;
}

function buildLevelBroadcastWorking(data) {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.fieldLabelEl = (key, opts) => makeEl("label", { text: (opts && opts.label) || key });
  global.window.checkboxInput = (value, onInput) => {
    const el = makeEl("input", { class: "checkbox", checked: Boolean(value) });
    el.__onInput = onInput;
    return el;
  };
  global.window.numberInput = (value, onInput) => {
    const el = makeEl("input", { class: "num", value });
    el.__onInput = onInput;
    return el;
  };
  global.window.textInput = (value, onInput) => {
    const el = makeEl("input", { class: "text", value });
    el.__onInput = onInput;
    return el;
  };
  global.window.richTextInput = (value, _mode, onInput) => {
    const el = makeEl("input", { class: "rich", value });
    el.__onInput = onInput;
    return el;
  };
  global.window.listSelect = (cfg) => makeEl("select", { listCfg: cfg });
  global.window.LABELS = { enumLabel: (_kind, id) => id };
  global.window.SKILLS = [];

  delete require.cache[require.resolve("../public/js/tf-level-broadcast-form.js")];
  require("../public/js/tf-level-broadcast-form.js");

  return global.window.buildLevelBroadcastForm(data);
}
