"use strict";

// sourcelinks.yml の transfer: 節 (fork の SourceTransferConfig#parse, K-16対応) と
// items.<id>.transfer-multiplier (SourcelinkConfig#readTransferMultiplier) に editor UI を
// 付けた際の回帰テスト。カタログ的な項目(material/display-name/CMD/lore/recipe等)は
// public/js/ars-source-forms.js に既存実装があり(docs/agent-context/config-editor.md 参照)、
// 欠けていたのはこの2点だけだった。
//
// 固定する不変条件:
//   A. transfer: が既に無い/空でも、カードを開いただけで保存すると全フィールドが既定値で
//      埋まったりしない(dimensionsと同じ「開いて保存しただけでyml意味が変わる」事故クラス)。
//   B. 個別フィールドを入力すると、そのキーだけが実体化される(他フィールド・他ブロックへ波及しない)。
//   C. 入力を空へ戻すと、空になった中間object(sourcelink/network/detection-radius等)ごと消える。
//   D. items.<id>.transfer-multiplier は既存カード(buildCatalogLikeCard)の extraFields に
//      正しいキー名で追加され、未入力なら書き込まれない。
//   E. lib/schema.js が Java(SourceTransferConfig#parse)と同じ範囲を検証する。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");

// ============================================================
// レンダリングハーネス
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
 * buildSourceLinksForm を描き、numberInput/checkboxInput の呼び出しを発生順に記録する。
 * items:{} (空)で呼べば、transfer: カードの呼び出しだけが記録される(カタログカードの
 * 大量の依存スタブを避けられる)。数値/真偽入力の発生順は render() の同期実行なので決定的:
 *   numberInputCalls:
 *     [0] sourcelink.interval-ticks   [1] sourcelink.max-per-transfer  [2] sourcelink.buffer-cap
 *     [3] detection-radius.vitalic    [4] detection-radius.botanical
 *     [5] network.interval-ticks      [6] network.max-per-transfer     [7] network.max-link-range
 *     [8] path-particles.interval-ticks [9] path-particles.spacing
 *     [10] path-particles.view-distance [11] path-particles.max-paths
 *     [12] infinity-core.radius       [13] infinity-core.transfer-multiplier
 *     [14] infinity-core.buffer-multiplier
 *   checkboxInputCalls:
 *     [0] path-particles.enabled
 */
function renderSourceLinksForm(data, opts) {
  const numberInputCalls = [];
  const checkboxInputCalls = [];
  global.window = {};
  global.document = { getElementById: () => null, body: { appendChild() {} } };
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children])
        .forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  global.window.fieldLabelEl = (key, o) => makeEl("label", { text: key, labelOpts: o || {} });
  global.window.numberInput = (value, onInput, o) => {
    numberInputCalls.push({ value, onInput, opts: o });
    return makeEl("input", { value });
  };
  global.window.checkboxInput = (value, onInput) => {
    checkboxInputCalls.push({ value, onInput });
    return makeEl("input", { type: "checkbox", value });
  };
  global.window.textInput = (value, onInput) => makeEl("input", { value, textOnInput: onInput });
  global.window.richTextInput = (value) => makeEl("input", { value });
  global.window.listSelect = (cfg) => makeEl("span", { listCfg: cfg });
  global.window.materialInput = (value) => makeEl("span", { class: "material-suggest", value });
  global.window.materialHintEl = () => {
    const el = makeEl("span", { class: "mat-hint" });
    el.update = () => {};
    return el;
  };
  global.window.buildTooltipPreview = () => ({ element: makeEl("div"), update: () => {} });
  global.window.collapsibleCard = (headChildren, bodyChildren) => {
    const card = makeEl("div", { class: "entry-card" });
    card.appendChild(global.window.h("div", { class: "entry-head" }, headChildren));
    card.appendChild(global.window.h("div", { class: "entry-body" }, bodyChildren));
    return card;
  };

  delete require.cache[require.resolve("../public/js/ars-source-forms.js")];
  require("../public/js/ars-source-forms.js");
  const built = global.window.buildSourceLinksForm(data);
  return { built, numberInputCalls, checkboxInputCalls };
}

const IDX = {
  LINK_INTERVAL: 0, LINK_MAX_PER_TRANSFER: 1, LINK_BUFFER_CAP: 2,
  DETECT_VITALIC: 3, DETECT_BOTANICAL: 4,
  NET_INTERVAL: 5, NET_MAX_PER_TRANSFER: 6, NET_MAX_LINK_RANGE: 7,
  FX_INTERVAL: 8, FX_SPACING: 9, FX_VIEW_DISTANCE: 10, FX_MAX_PATHS: 11,
  CORE_RADIUS: 12, CORE_TRANSFER_MULT: 13, CORE_BUFFER_MULT: 14
};

// ============================================================
// A. 未編集なら working.transfer は一切変化しない
// ============================================================

test("transfer: 節が無い sourcelinks.yml を開いただけで保存しても transfer キーは作られない", () => {
  const { built } = renderSourceLinksForm({ items: {} });
  const data = built.getData();
  assert.equal("transfer" in data, false, "未編集で transfer: を新設してはいけない");
});

test("既存の transfer.sourcelink.interval-ticks だけがある場合、他フィールドを触らなければそのまま保存される", () => {
  const { built } = renderSourceLinksForm({
    items: {},
    transfer: { sourcelink: { "interval-ticks": 100 } }
  });
  const data = built.getData();
  assert.deepEqual(data.transfer, { sourcelink: { "interval-ticks": 100 } });
});

// ============================================================
// B. 値を入力したフィールドだけが実体化される
// ============================================================

test("sourcelink.max-per-transfer だけ入力すると、そのキーだけが実体化される", () => {
  const { built, numberInputCalls } = renderSourceLinksForm({ items: {} });
  numberInputCalls[IDX.LINK_MAX_PER_TRANSFER].onInput(80);
  const data = built.getData();
  assert.deepEqual(data.transfer, { sourcelink: { "max-per-transfer": 80 } });
});

test("detection-radius.botanical だけ入力しても vitalic や他ブロックには波及しない", () => {
  const { built, numberInputCalls } = renderSourceLinksForm({ items: {} });
  numberInputCalls[IDX.DETECT_BOTANICAL].onInput(20);
  const data = built.getData();
  assert.deepEqual(data.transfer, { sourcelink: { "detection-radius": { botanical: 20 } } });
});

test("network.max-link-range と infinity-core.radius を両方入力すると互いに独立する", () => {
  const { built, numberInputCalls } = renderSourceLinksForm({ items: {} });
  numberInputCalls[IDX.NET_MAX_LINK_RANGE].onInput(50);
  numberInputCalls[IDX.CORE_RADIUS].onInput(8);
  const data = built.getData();
  assert.deepEqual(data.transfer, {
    network: { "max-link-range": 50 },
    "infinity-core": { radius: 8 }
  });
});

test("path-particles.enabled を false にすると明示的に書き込まれ、trueへ戻すとキーが消える", () => {
  const { built, checkboxInputCalls } = renderSourceLinksForm({ items: {} });
  assert.equal(checkboxInputCalls[0].value, true, "省略時は既定true(チェック済み)で表示する");
  checkboxInputCalls[0].onInput(false);
  let data = built.getData();
  assert.deepEqual(data.transfer, { network: { "path-particles": { enabled: false } } });

  checkboxInputCalls[0].onInput(true);
  data = built.getData();
  assert.equal("transfer" in data, false, "既定値(true)へ戻したらキーごと消える");
});

// ============================================================
// C. 値を消すと空の中間objectごと消える
// ============================================================

test("入力した path-particles.spacing を空へ戻すと path-particles ごと消える", () => {
  const { built, numberInputCalls } = renderSourceLinksForm({ items: {} });
  numberInputCalls[IDX.FX_SPACING].onInput(2.0);
  numberInputCalls[IDX.FX_SPACING].onInput(null);
  const data = built.getData();
  assert.equal("transfer" in data, false, "空になった枝は根本まで刈られる");
});

// ============================================================
// D. items.<id>.transfer-multiplier (K-16)
// ============================================================

test("items.<id>.transfer-multiplier: 未入力なら書き込まれない", () => {
  const { built } = renderSourceLinksForm({
    items: {
      volcanic_sourcelink: { material: "FURNACE", "display-name": "v", lore: [] }
    }
  });
  const data = built.getData();
  assert.equal("transfer-multiplier" in data.items.volcanic_sourcelink, false);
});

test("items.<id>.transfer-multiplier: 既存値(II/III階梯相当)はそのまま保持される", () => {
  const { built } = renderSourceLinksForm({
    items: {
      volcanic_sourcelink_ii: {
        material: "FURNACE", "display-name": "v2", lore: [], "transfer-multiplier": 2.0
      }
    }
  });
  const data = built.getData();
  assert.equal(data.items.volcanic_sourcelink_ii["transfer-multiplier"], 2.0);
});

test("items.<id>.transfer-multiplier: numberInput へ現在値がそのまま渡っている", () => {
  // buildCatalogLikeCard の extraFields から numberInput が呼ばれる際、
  // 現在値がそのまま渡っていること(値の取り違えがないこと)を固定する。
  const numberInputCalls = [];
  global.window = {};
  global.document = { getElementById: () => null, body: { appendChild() {} } };
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (children != null) {
      (Array.isArray(children) ? children : [children])
        .forEach((c) => c != null && c !== false && el.appendChild(c));
    }
    return el;
  };
  global.window.fieldLabelEl = (key, o) => makeEl("label", { text: key, labelOpts: o || {} });
  global.window.numberInput = (value, onInput, o) => {
    numberInputCalls.push({ value, onInput, opts: o });
    return makeEl("input", { value });
  };
  global.window.checkboxInput = (value, onInput) => makeEl("input", { type: "checkbox", value });
  global.window.textInput = (value, onInput) => makeEl("input", { value });
  global.window.richTextInput = (value) => makeEl("input", { value });
  global.window.listSelect = (cfg) => makeEl("span", { listCfg: cfg });
  global.window.materialInput = (value) => makeEl("span", { class: "material-suggest", value });
  global.window.materialHintEl = () => {
    const el = makeEl("span", { class: "mat-hint" });
    el.update = () => {};
    return el;
  };
  global.window.buildTooltipPreview = () => ({ element: makeEl("div"), update: () => {} });
  global.window.collapsibleCard = (headChildren, bodyChildren) => {
    const card = makeEl("div", { class: "entry-card" });
    card.appendChild(global.window.h("div", { class: "entry-head" }, headChildren));
    card.appendChild(global.window.h("div", { class: "entry-body" }, bodyChildren));
    return card;
  };
  delete require.cache[require.resolve("../public/js/ars-source-forms.js")];
  require("../public/js/ars-source-forms.js");
  global.window.buildSourceLinksForm({
    items: { volcanic_sourcelink: { material: "FURNACE", "display-name": "v", lore: [], "transfer-multiplier": 3.5 } }
  });
  const call = numberInputCalls.find((c) => c.value === 3.5);
  assert.ok(call, "transfer-multiplier の現在値(3.5)を渡す numberInput 呼び出しが見当たらない");
});

// ============================================================
// E. 純関数 (window.ARS_SOURCE_FORMS_LOGIC)
// ============================================================

test("ARS_SOURCE_FORMS_LOGIC.pruneEmptyTransfer: 空の中間objectだけを刈る", () => {
  global.window = {};
  global.window.h = () => ({});
  delete require.cache[require.resolve("../public/js/ars-source-forms.js")];
  require("../public/js/ars-source-forms.js");
  const { pruneEmptyTransfer } = global.window.ARS_SOURCE_FORMS_LOGIC;

  const host = {
    transfer: {
      sourcelink: { "detection-radius": {} },
      network: { "max-per-transfer": 100, "path-particles": {} },
      "infinity-core": {}
    }
  };
  pruneEmptyTransfer(host);
  assert.deepEqual(host.transfer, { network: { "max-per-transfer": 100 } });
});

test("ARS_SOURCE_FORMS_LOGIC.pruneEmptyTransfer: 全て空なら transfer キー自体を消す", () => {
  global.window = {};
  global.window.h = () => ({});
  delete require.cache[require.resolve("../public/js/ars-source-forms.js")];
  require("../public/js/ars-source-forms.js");
  const { pruneEmptyTransfer } = global.window.ARS_SOURCE_FORMS_LOGIC;

  const host = { transfer: { sourcelink: { "detection-radius": {} }, network: {}, "infinity-core": {} } };
  pruneEmptyTransfer(host);
  assert.equal("transfer" in host, false);
});

test("ARS_SOURCE_FORMS_LOGIC.pruneEmptyTransfer: transfer未設定/非オブジェクトでも例外にならない", () => {
  global.window = {};
  global.window.h = () => ({});
  delete require.cache[require.resolve("../public/js/ars-source-forms.js")];
  require("../public/js/ars-source-forms.js");
  const { pruneEmptyTransfer } = global.window.ARS_SOURCE_FORMS_LOGIC;

  assert.doesNotThrow(() => pruneEmptyTransfer(undefined));
  assert.doesNotThrow(() => pruneEmptyTransfer({}));
  assert.doesNotThrow(() => pruneEmptyTransfer({ transfer: "oops" }));
});

// ============================================================
// F. lib/schema.js の ars-sourcelinks 検証 (SourceTransferConfig#parse と同じ範囲)
// ============================================================

const { validate } = require("../lib/schema.js");

test("schema: transfer: 節が無い/空でもエラーにならない", () => {
  assert.deepEqual(validate("ars-sourcelinks", {}), []);
  assert.deepEqual(validate("ars-sourcelinks", { transfer: {} }), []);
});

test("schema: transfer.sourcelink.interval-ticks は範囲外を拒否する (1〜72000)", () => {
  const tooLow = validate("ars-sourcelinks", { transfer: { sourcelink: { "interval-ticks": 0 } } });
  assert.ok(tooLow.some((e) => e.includes("transfer.sourcelink.interval-ticks")));
  const tooHigh = validate("ars-sourcelinks", { transfer: { sourcelink: { "interval-ticks": 999999 } } });
  assert.ok(tooHigh.some((e) => e.includes("transfer.sourcelink.interval-ticks")));
  assert.deepEqual(validate("ars-sourcelinks", { transfer: { sourcelink: { "interval-ticks": 100 } } }), []);
});

test("schema: transfer.network.path-particles.spacing は0.1〜16.0の数値", () => {
  const bad = validate("ars-sourcelinks", { transfer: { network: { "path-particles": { spacing: 0.01 } } } });
  assert.ok(bad.some((e) => e.includes("transfer.network.path-particles.spacing")));
  assert.deepEqual(
    validate("ars-sourcelinks", { transfer: { network: { "path-particles": { spacing: 1.0 } } } }), []);
});

test("schema: transfer.network.path-particles.enabled は真偽値以外を拒否する", () => {
  const errors = validate("ars-sourcelinks", { transfer: { network: { "path-particles": { enabled: "yes" } } } });
  assert.ok(errors.some((e) => e.includes("transfer.network.path-particles.enabled")));
});

test("schema: transfer.infinity-core.transfer-multiplier は0〜1000", () => {
  assert.deepEqual(
    validate("ars-sourcelinks", { transfer: { "infinity-core": { "transfer-multiplier": 2.0 } } }), []);
  const bad = validate("ars-sourcelinks", { transfer: { "infinity-core": { "transfer-multiplier": 1001 } } });
  assert.ok(bad.some((e) => e.includes("transfer.infinity-core.transfer-multiplier")));
});

test("schema: items.<id>.transfer-multiplier は0より大きい数値を要求する", () => {
  const zero = validate("ars-sourcelinks", { items: { x: { "transfer-multiplier": 0 } } });
  assert.ok(zero.some((e) => e.includes("items.x.transfer-multiplier")));
  const negative = validate("ars-sourcelinks", { items: { x: { "transfer-multiplier": -1 } } });
  assert.ok(negative.some((e) => e.includes("items.x.transfer-multiplier")));
  assert.deepEqual(validate("ars-sourcelinks", { items: { x: { "transfer-multiplier": 2.0 } } }), []);
});

test("schema: items.<id>.transfer-multiplier 省略はエラーにならない (既定1.0)", () => {
  assert.deepEqual(validate("ars-sourcelinks", { items: { volcanic_sourcelink: { material: "FURNACE" } } }), []);
});

// ============================================================
// G. 出荷済み(フォーク側) sourcelinks.yml を読み込んで transfer: の実測値が範囲内であることを確認する
//    (フォークは別リポジトリ・別レーンの未コミット変更があるため書き込みは絶対に行わない — 読むだけ)
// ============================================================

const FORK_SOURCELINKS = path.resolve(ROOT, "..", "..", "fork-handoff", "arspaper", "fork",
  "src", "main", "resources", "sourcelinks.yml");

test("フォーク側 sourcelinks.yml の transfer: は schema 上エラーにならない (読み取り専用)", (t) => {
  if (!fs.existsSync(FORK_SOURCELINKS)) {
    t.skip("フォーク未取得の環境ではスキップ");
    return;
  }
  const YAML = require("yaml");
  const raw = fs.readFileSync(FORK_SOURCELINKS, "utf8");
  const data = YAML.parse(raw);
  assert.ok(data.transfer, "transfer: セクションが無い(フォーク側の前提が崩れている)");
  const errors = validate("ars-sourcelinks", data).filter((e) => e.startsWith("transfer."));
  assert.deepEqual(errors, []);
});
