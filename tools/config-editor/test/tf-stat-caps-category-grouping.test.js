"use strict";

// public/js/tf-base-stats.js の「上限」タブ再編 (2026-07-27) 回帰テスト。
//
// 手書きの STAT_CAPS_SECTIONS (クランプ機構の出典別) によるグルーピングを、「基礎」タブと同じ
// lore カテゴリ (categoryOf/CATEGORY_ORDER/CATEGORY_LABEL) 別へ変更した。表示するキー集合
// (= statCapsAllKeys()。実際にクランプが効くキーの許可リスト) は変えていないはずなので、
// STAT_CAPS_SECTIONS に列挙された全キーが、カテゴリ別グルーピングのどこかに必ず現れることを
// 固定する(取りこぼし = 「設定できるのに上限が効かない」というUIから消えるバグの検知)。

const test = require("node:test");
const assert = require("node:assert/strict");

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

function setupStubs() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
    else if (children != null) el.appendChild(children);
    return el;
  };
  global.window.numberInput = (value, onInput) => {
    const el = makeEl("input", { class: "num", value });
    el.__onInput = onInput;
    return el;
  };
  global.window.checkboxInput = (value, onInput) => {
    const el = makeEl("input", { class: "checkbox", checked: Boolean(value) });
    el.__onInput = onInput;
    return el;
  };
  global.window.STAT_LIST = [];
  global.window.FALLBACK_STATS = [];
  global.window.HIDDEN_STATS = [];
  global.window.STAT_META = {};
  global.window.STAT_FORMATS = {};
  global.window.LABELS = { statLabel: (k) => k };
  global.window.isPercentStat = () => false;
  delete require.cache[require.resolve("../public/js/tf-base-stats.js")];
  require("../public/js/tf-base-stats.js");
}

function collectRows(root) {
  const rows = [];
  (function walk(el) {
    if (!el || !el.children) return;
    if (el.props && el.props.class === "stat-row") rows.push(el);
    el.children.forEach(walk);
  })(root);
  return rows;
}

function collectSubTitles(root) {
  const titles = [];
  (function walk(el) {
    if (!el || !el.children) return;
    if (el.props && el.props.class === "sub-title" && typeof el.props.text === "string") {
      titles.push(el.props.text);
    }
    el.children.forEach(walk);
  })(root);
  return titles;
}

function renderCapsTabBody() {
  setupStubs();
  const statCapsData = { "stat-caps": {} };
  const result = global.window.buildBaseStatsForm({}, { statCapsData });
  const buttons = [];
  (function walk(el) {
    if (!el || !el.children) return;
    if (el.tag === "button") buttons.push(el);
    el.children.forEach(walk);
  })(result.element);
  const capsTabBtn = buttons.find((b) => b.children.some((c) => c.props && c.props.text === "上限"));
  capsTabBtn.props.onclick();
  return result.element;
}

test("上限タブ: STAT_CAPS_SECTIONS の全キーがカテゴリ別グルーピングのどこかに現れる(取りこぼし無し)", () => {
  delete require.cache[require.resolve("../public/js/tf-base-stats.js")];
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props) => makeEl(tag, props);
  const { STAT_CAPS_SECTIONS, statCapsAllKeys } = require("../public/js/tf-base-stats.js");
  const expectedKeys = new Set();
  for (const sec of STAT_CAPS_SECTIONS) for (const k of sec.keys) expectedKeys.add(k);

  const root = renderCapsTabBody();
  const rows = collectRows(root);
  // 各行の1番目の子(ラベル span)の title から生キーを復元する ("<key> — クランプ機構: ..." 形式)。
  // 「上限」タブには STAT_CAPS_SECTIONS 由来のカテゴリ別行に加え、
  // gathering-efficiency-max-enchant-level (bookshelfSection、statCapsAllKeys() の対象外・
  // combat/stat-caps.yml ルート直下の別キー) の行が1つ余分に存在するので、それだけ除いて比較する。
  const EXTRA_NON_CAP_KEY = "gathering-efficiency-max-enchant-level";
  const renderedKeys = new Set();
  for (const row of rows) {
    const labelSpan = row.children[0];
    if (!labelSpan || !labelSpan.props || typeof labelSpan.props.title !== "string") continue;
    const key = labelSpan.props.title.split(" — ")[0];
    if (key === EXTRA_NON_CAP_KEY) continue;
    renderedKeys.add(key);
  }

  for (const k of expectedKeys) {
    assert.ok(renderedKeys.has(k), `STAT_CAPS_SECTIONS のキー "${k}" が上限タブの描画結果から欠けている`);
  }
  // 逆方向(描画結果 → statCapsAllKeys)も一致すること = キー集合そのものは変わっていない。
  const allKeys = new Set(statCapsAllKeys());
  assert.equal(renderedKeys.size, allKeys.size, "描画されたキー数が statCapsAllKeys() と一致しない");
  for (const k of renderedKeys) assert.ok(allKeys.has(k), `描画されたキー "${k}" が statCapsAllKeys() に無い`);
});

test("上限タブ: 各行の title にクランプ機構の出典(STAT_CAPS_SECTIONS の title)が含まれる", () => {
  const root = renderCapsTabBody();
  const rows = collectRows(root);
  const critRow = rows.find((r) => {
    const label = r.children[0];
    return label && label.props && typeof label.props.title === "string"
      && label.props.title.startsWith("crit-chance");
  });
  assert.ok(critRow, "crit-chance の行が見つからない");
  assert.match(critRow.children[0].props.title, /CombatListenerが直接クランプ/,
    "クランプ機構の出典が title から失われている");
});

test("上限タブ: 見出し(sub-title)は基礎タブと同じ lore カテゴリ名になっている(クランプ出典名ではない)", () => {
  const root = renderCapsTabBody();
  const titles = collectSubTitles(root);
  // カテゴリ名(基礎タブの CATEGORY_LABEL)のどれかが出ているはず。
  const categoryLabels = ["攻撃", "防御", "魔法 (Ars)", "採集", "クラフト", "汎用", "その他"];
  assert.ok(categoryLabels.some((c) => titles.includes(c)),
    `カテゴリ見出しが1つも見つからない: ${JSON.stringify(titles)}`);
  // 旧・出典別の見出し文言(手書き STAT_CAPS_SECTIONS の title)はもう sub-title としては出ない。
  assert.ok(!titles.includes("攻撃 (通常攻撃/弓 - totalOf経由)"),
    "旧・出典別見出しがまだ sub-title として残っている");
});
