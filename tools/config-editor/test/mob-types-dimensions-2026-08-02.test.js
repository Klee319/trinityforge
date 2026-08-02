"use strict";

// combat/mob-types.yml の dimensions: (MobTypesConfig#parseDimensions, 2026-08-02導入) に
// editor UI を付けた際の回帰テスト。機構自体は Java 側で実装・commit 済み(4348e8c)だったが、
// editor から編集できず「実質使えない」状態だった。ここで固定する不変条件:
//
//   A. 未編集(カードを開いただけ)で保存しても working.dimensions は一切変化しない
//      (4環境ぶんの base-level:0 が勝手に埋まる = 「開いて保存しただけで yml の意味が変わる」
//      既知の事故クラス。docs/agent-context/config-editor.md 参照)。
//   B. 値を入力した環境だけが実体化され、他の環境・他フィールドには波及しない。
//   C. 入力後に値を消すと、その環境エントリごと消える(空オブジェクトを残さない)。
//   D. キーは World.Environment 名の4種固定 (NORMAL/NETHER/THE_END/CUSTOM)。schema.js もこの4種
//      以外・base-level の型・coordinate-coefficient の型を検証する。
//   E. 出荷 yml (dimensions: {}) を素通しで読み込み、往復してもコメント本文以外の意味が変わらない。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");

// ============================================================
// レンダリングハーネス (mob-types-custom-drops-2026-08-01.test.js と同じ流儀)
// ============================================================

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    style: {},
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    _listeners: {},
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    addEventListener(type, fn) { (el._listeners[type] = el._listeners[type] || []).push(fn); },
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
  if (attrs && "value" in attrs) el.value = attrs.value;
  Object.defineProperty(el, "innerHTML", {
    get() { return ""; },
    set() { el.children = []; }
  });
  return el;
}

/**
 * buildMobTypesForm を描き、numberInput の呼び出しを発生順に記録して返す。
 * render() は renderMaxLevelCard() → renderDimensionsCard() → renderDefaultsCard() → (モブカード…)
 * の順に同期実行されるため、mob-types が空のデータでは numberInput の呼び出し順は
 *   [0] max-level
 *   [1] dimensions.NORMAL.base-level   [2] dimensions.NORMAL.coordinate-coefficient
 *   [3] dimensions.NETHER.base-level   [4] dimensions.NETHER.coordinate-coefficient
 *   [5] dimensions.THE_END.base-level  [6] dimensions.THE_END.coordinate-coefficient
 *   [7] dimensions.CUSTOM.base-level   [8] dimensions.CUSTOM.coordinate-coefficient
 * で決定的に固定される(renderDefaultsCard 以降の呼び出しはこれより後に来る)。
 */
function renderMobTypesForm(data) {
  const numberInputCalls = [];
  global.window = {};
  global.document = { getElementById: () => null, body: { appendChild() {} } };
  global.window.VANILLA_MOBS = ["ZOMBIE", "SKELETON"];
  global.window.MOB_LABELS_JA = { ZOMBIE: "ゾンビ", SKELETON: "スケルトン" };
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children])
        .forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  global.window.fieldLabelEl = (key, opts) => makeEl("label", { text: key, labelOpts: opts || {} });
  global.window.numberInput = (value, onInput, opts) => {
    numberInputCalls.push({ value, onInput, opts });
    return makeEl("input", { value, numOnInput: onInput, numOpts: opts });
  };
  global.window.textInput = (value, onInput) => makeEl("input", { value, textOnInput: onInput });
  global.window.mobTypeSelect = (value, onChange) => makeEl("span", { value, mobOnChange: onChange });
  global.window.listSelect = (opts) => makeEl("span", { listOpts: opts });
  global.window.materialHintEl = () => {
    const el = makeEl("span", { class: "mat-hint" });
    el.update = () => {};
    return el;
  };
  global.window.materialInput = (value, listId, onInput, opts) =>
    makeEl("span", { class: "material-suggest", matValue: value, matOnInput: onInput, matOpts: opts || {} });
  global.window.collapsibleCard = (headChildren, bodyChildren) => {
    const card = makeEl("div", { class: "entry-card" });
    card.appendChild(global.window.h("div", { class: "entry-head" }, headChildren));
    card.appendChild(global.window.h("div", { class: "entry-body" }, bodyChildren));
    return card;
  };

  delete require.cache[require.resolve("../public/js/mob-forms.js")];
  require("../public/js/mob-forms.js");
  const built = global.window.buildMobTypesForm(data);
  return { built, numberInputCalls };
}

// index 定数 (ハーネスのdocコメントに書いた固定順)
const IDX = {
  MAX_LEVEL: 0,
  NORMAL_BASE: 1, NORMAL_COORD: 2,
  NETHER_BASE: 3, NETHER_COORD: 4,
  THE_END_BASE: 5, THE_END_COORD: 6,
  CUSTOM_BASE: 7, CUSTOM_COORD: 8
};

// ============================================================
// A. 未編集なら working.dimensions は一切変化しない
// ============================================================

test("dimensions: {} を開いただけで保存しても中身は空のまま (4環境が勝手に埋まらない)", () => {
  const { built } = renderMobTypesForm({ dimensions: {} });
  const data = built.getData();
  assert.deepEqual(data.dimensions, {}, "未編集で base-level:0 等が書き込まれてはいけない");
});

test("dimensions キー自体が無い yml を開いただけで保存してもキーは作られない", () => {
  const { built } = renderMobTypesForm({ "mob-types": { ZOMBIE: { level: 1 } } });
  const data = built.getData();
  assert.equal("dimensions" in data, false, "未編集で dimensions: {} すら新設してはいけない");
});

test("既存の NETHER 設定だけがある yml は、他フィールドを触らなければそのまま保存される", () => {
  const { built } = renderMobTypesForm({ dimensions: { NETHER: { "base-level": 20 } } });
  const data = built.getData();
  assert.deepEqual(data.dimensions, { NETHER: { "base-level": 20 } },
    "既存エントリの温存に加え、他の3環境が勝手に追加されてはいけない");
});

// ============================================================
// B. 値を入力した環境だけが実体化される
// ============================================================

test("NETHER の base-level だけ入力すると、その環境だけが実体化される", () => {
  const { built, numberInputCalls } = renderMobTypesForm({ dimensions: {} });
  numberInputCalls[IDX.NETHER_BASE].onInput(20);
  const data = built.getData();
  assert.deepEqual(data.dimensions, { NETHER: { "base-level": 20 } });
});

test("THE_END の coordinate-coefficient だけ入力しても base-level は書き込まれない", () => {
  const { built, numberInputCalls } = renderMobTypesForm({ dimensions: {} });
  numberInputCalls[IDX.THE_END_COORD].onInput(0.16);
  const data = built.getData();
  assert.deepEqual(data.dimensions, { THE_END: { "coordinate-coefficient": 0.16 } });
});

test("2環境に別々の値を入れても互いに独立する", () => {
  const { built, numberInputCalls } = renderMobTypesForm({ dimensions: {} });
  numberInputCalls[IDX.NETHER_BASE].onInput(20);
  numberInputCalls[IDX.CUSTOM_BASE].onInput(5);
  const data = built.getData();
  assert.deepEqual(data.dimensions, { NETHER: { "base-level": 20 }, CUSTOM: { "base-level": 5 } });
});

// ============================================================
// C. 値を消すとエントリごと消える (空オブジェクトを残さない)
// ============================================================

test("入力した base-level を空へ戻すと NETHER エントリごと消える", () => {
  const { built, numberInputCalls } = renderMobTypesForm({ dimensions: {} });
  numberInputCalls[IDX.NETHER_BASE].onInput(20);
  numberInputCalls[IDX.NETHER_BASE].onInput(null);
  const data = built.getData();
  assert.deepEqual(data.dimensions, {}, "空になったエントリは getData() 時に刈られる");
});

// ============================================================
// D. 純関数 pruneEmptyDimensions (window.MOB_FORMS_LOGIC)
// ============================================================

test("MOB_FORMS_LOGIC.pruneEmptyDimensions: 空オブジェクトのエントリだけを刈る", () => {
  global.window = {};
  global.window.h = () => ({});
  delete require.cache[require.resolve("../public/js/mob-forms.js")];
  require("../public/js/mob-forms.js");
  const { pruneEmptyDimensions } = global.window.MOB_FORMS_LOGIC;

  const host = { dimensions: { NETHER: {}, THE_END: { "base-level": 45 } } };
  pruneEmptyDimensions(host);
  assert.deepEqual(host.dimensions, { THE_END: { "base-level": 45 } });
});

test("MOB_FORMS_LOGIC.pruneEmptyDimensions: dimensions未設定/非オブジェクトでも例外にならない", () => {
  global.window = {};
  global.window.h = () => ({});
  delete require.cache[require.resolve("../public/js/mob-forms.js")];
  require("../public/js/mob-forms.js");
  const { pruneEmptyDimensions } = global.window.MOB_FORMS_LOGIC;

  assert.doesNotThrow(() => pruneEmptyDimensions(undefined));
  assert.doesNotThrow(() => pruneEmptyDimensions({}));
  assert.doesNotThrow(() => pruneEmptyDimensions({ dimensions: "oops" }));
});

// ============================================================
// E. lib/schema.js の tf-mob-types 検証 (Java 側 MobTypesConfig#parseDimensions と突き合わせ)
// ============================================================

const { validate } = require("../lib/schema.js");

test("schema: dimensions: {} (出荷yml既定値) はエラーにならない", () => {
  assert.deepEqual(validate("tf-mob-types", { dimensions: {} }), []);
});

test("schema: World.Environment の4種は受け付ける", () => {
  const data = {
    dimensions: {
      NORMAL: { "base-level": 0 },
      NETHER: { "base-level": 20, "coordinate-coefficient": 0.16 },
      THE_END: { "base-level": 45 },
      CUSTOM: { "base-level": 10 }
    }
  };
  assert.deepEqual(validate("tf-mob-types", data), []);
});

test("schema: 未知のディメンションキーはエラーになる (ワールド名で引く設計を拒否する)", () => {
  const errors = validate("tf-mob-types", { dimensions: { world_nether: { "base-level": 20 } } });
  assert.ok(errors.some((e) => e.includes("dimensions.world_nether")),
    "ワールド名のような自由文字列キーは弾かれる必要がある");
});

test("schema: base-level は0以上の整数以外を拒否する", () => {
  const errors = validate("tf-mob-types", { dimensions: { NETHER: { "base-level": -1 } } });
  assert.ok(errors.some((e) => e.includes("dimensions.NETHER.base-level")));
});

test("schema: coordinate-coefficient は数値以外を拒否する", () => {
  const errors = validate("tf-mob-types", { dimensions: { NETHER: { "coordinate-coefficient": "fast" } } });
  assert.ok(errors.some((e) => e.includes("dimensions.NETHER.coordinate-coefficient")));
});

test("schema: dimensions 自体がマップでない場合はエラーになる", () => {
  const errors = validate("tf-mob-types", { dimensions: [1, 2, 3] });
  assert.ok(errors.some((e) => e === "dimensions: マップである必要があります"));
});

// ============================================================
// F. 往復ロスレス: 出荷済み combat/mob-types.yml を実際に読み込んで確認する
// ============================================================

const { readConfig, serializeConfig } = require("../lib/yamlio.js");

const SHIPPED_MOB_TYPES = path.resolve(ROOT, "..", "..", "TrinityForge", "src", "main", "resources",
  "combat", "mob-types.yml");

test("出荷済み mob-types.yml は dimensions: {} を持ち、editor で開いて保存しても中身が変わらない", (t) => {
  if (!fs.existsSync(SHIPPED_MOB_TYPES)) {
    t.skip("出荷 yml が見つからない環境ではスキップ");
    return;
  }
  const { data: original, raw } = readConfig(SHIPPED_MOB_TYPES);
  assert.deepEqual(original.dimensions, {}, "出荷 yml の既定値が dimensions: {} であることが前提");

  const { built } = renderMobTypesForm(JSON.parse(JSON.stringify(original)));
  const saved = built.getData();
  assert.deepEqual(saved.dimensions, {}, "未編集で保存しても dimensions は空のまま");

  // 実際に serialize → 再parse しても意味論的に同じであることを確認する (yamlio 経由の往復)。
  const reserialized = serializeConfig(saved, raw);
  const reparsed = require("yaml").parse(reserialized);
  assert.deepEqual(reparsed.dimensions, {});
});
