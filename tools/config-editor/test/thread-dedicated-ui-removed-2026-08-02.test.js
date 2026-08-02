"use strict";

// 【2026-08-02 差し戻し】スレッド厳選の TF item-stats.yml 移設タスクで、ユーザーの明示指示
// (「専用のGUIと仕様を作るな。武器と同じアイテムステータス設定の仕様とやり方で、スレッドも
// 個別にステータス定義しろ」)に反し、editor 側に random-roll-pools 専用GUI
// (p5-forms.js の buildRandomRollPoolsForm/buildRandomRollPoolEditor、split-views.js の
// 「スレッド」タブ合成表示)を新設してしまっていた。本ファイルはその撤去を固定する回帰テスト。
//
// スレッドは今後、item-stats.yml の他アイテム(武器/防具/ツール等)とまったく同じ
// fixed / per-quality / random / advanced フォーム(forms.js の buildItemStatsForm、
// __stats_thread__ タブ)で編集する。専用の入力欄を新設しないこと。

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const root = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(root, "public", "js", name), "utf8");

// ---------------------------------------------------------------------------
// 1. 静的ソースチェック: 専用GUIのエントリポイントが定義・参照されていないこと。
// ---------------------------------------------------------------------------

test("p5-forms.js は buildRandomRollPoolsForm/buildRandomRollPoolEditor をもう定義しない", () => {
  const src = JS("p5-forms.js");
  // コメント(撤去の経緯説明)には関数名が残ってよいので、実体定義(function/window.代入)だけを見る。
  assert.ok(!/window\.buildRandomRollPoolsForm\s*=/.test(src),
    "random-roll-pools 専用フォームが復活している(専用GUIは作らない、というユーザー指示に反する)");
  assert.ok(!/function\s+buildRandomRollPoolEditor\s*\(/.test(src));
  assert.ok(!/const RARITY_COLORS\s*=/.test(src), "レア度カラー専用の定数が残っている");
});

test("split-views.js はスレッドタブへ random-roll-pools 専用セクションを合成しない", () => {
  const src = JS("split-views.js");
  assert.ok(!/buildRandomRollPoolsForm/.test(src));
  assert.ok(!/hub-thread-composite/.test(src));
  assert.ok(!/itemCategory === "thread"/.test(src),
    "「スレッド」タブだけ特別扱いする分岐が復活している(他カテゴリと同じ扱いのはず)");
});

test("labels.js はスレッド厳選専用のレア度カラー辞書を持たない", () => {
  const src = JS("labels.js");
  // コメント上の言及(撤去記録)は許容し、実際のキー定義だけを禁止する。
  assert.ok(!/"rarity-color"\s*:\s*\{/.test(src));
});

// ---------------------------------------------------------------------------
// 2. buildSplitConfigView 経由の動作確認: 「スレッド」タブは他カテゴリと全く同じ経路
//    (itemStatsForm をそのまま使う)で、専用ラッパー要素も作られないこと。
//    random-roll-pools キーは item-stats.yml のルートに残っていても(TF 側の削除待ちでも)、
//    editor が触らず素通しすること(ロスレス確認)。
// ---------------------------------------------------------------------------

function makeEl(tag, props) {
  const el = {
    tag,
    props: props || {},
    children: [],
    appendChild(c) { if (c != null && c !== false) this.children.push(c); return c; },
    set innerHTML(_v) { this.children = []; },
    get innerHTML() { return ""; }
  };
  return el;
}

function setupSplitViewStubs() {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && c !== false && el.appendChild(c));
    else if (children != null && children !== false) el.appendChild(children);
    return el;
  };
  global.window.FUNCTIONAL_ITEMS_CORE = { TF_SPECIAL_ITEM_IDS: [] };
  global.window.renderEditorCategoryBar = () => makeEl("div");
  global.window.pruneEditorUiState = (d) => d;

  let lastItemStatsCall = null;
  global.window.buildItemStatsForm = (data, opts) => {
    lastItemStatsCall = { data, opts };
    return { element: makeEl("div", { class: "item-stats-stub" }), getData: () => data, setActiveCategory: () => {} };
  };
  // p5-forms.js を読み込んでも buildRandomRollPoolsForm が生えないことを、この場で確認できるように
  // 実ファイルを読み込む(存在すればここで window に生える)。
  delete require.cache[require.resolve("../public/js/p5-forms.js")];
  require("../public/js/p5-forms.js");

  delete require.cache[require.resolve("../public/js/split-views.js")];
  require("../public/js/split-views.js");
  return { getLastItemStatsCall: () => lastItemStatsCall };
}

test("スレッドタブ: buildRandomRollPoolsForm は window に存在しない(p5-forms.js を読み込んでも生えない)", () => {
  setupSplitViewStubs();
  assert.equal(typeof window.buildRandomRollPoolsForm, "undefined");
});

test("スレッドタブ: item-stats.yml の random-roll-pools はそのまま(未変更)で getData() から返る", () => {
  setupSplitViewStubs();
  const data = {
    items: { WHITE_WOOL: { fixed: { "atk-power": 1 } } },
    "random-roll-pools": { thread: { rarities: { common: { weight: 10 } } } }
  };
  const view = window.buildSplitConfigView({
    type: "item-stats", configId: "item-stats", categoryKey: "thread", itemCategory: "thread",
    data
  });
  assert.ok(view && view.element, "buildSplitConfigView が要素を返さない");
  // withCategoryBar の構造は root=[nestBar, body]、body=[formEl]。
  // 専用ラッパー(hub-thread-composite)が無い = formEl は itemStatsForm のスタブ要素そのもの。
  const body = view.element.children[1];
  assert.equal(body.props.class, "hub-body");
  const formEl = body.children[0];
  assert.equal(formEl.props.class, "item-stats-stub",
    "スレッドタブだけ専用の合成ラッパー(hub-thread-composite)へ差し替えられている(撤去できていない)");
  const out = view.getData();
  assert.deepEqual(out["random-roll-pools"], { thread: { rarities: { common: { weight: 10 } } } },
    "editor が random-roll-pools を書き換えている(素通しでなければならない)");
});
