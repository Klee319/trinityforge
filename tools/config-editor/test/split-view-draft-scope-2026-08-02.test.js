"use strict";

// 【2026-08-02 指摘4】split-views.js の配線: 「準備中」既定カテゴリ (draft: true) は
// catalog.yml (ItemCatalogConfig#load) でしか実効を持たない。materials.yml / threads.yml /
// item-stats.yml / spellbooks.yml の画面にまで無条件に出すと、そこへ入れても意味が無い
// (materials/threads は無反応、item-stats.yml には誰も読まない draft: true ゴーストキーが
// 実際に書かれる)。
//
// withCategoryBar は renderEditorCategoryBar の 5番目の引数 opts.includeDraftCategory へ
// 「o.type === "catalog"」のときだけ true を渡す設計にした。ここでは
// buildSplitConfigView の各 type からその配線が実際に効いていることを固定する
// (renderEditorCategoryBar 自体は editor-categories.js の実物ではなくスタブで呼び出し
// 引数だけを捕捉する — draft-category-2026-08-02.test.js が editor-categories.js 側の
// includeDraftCategory の実効(cat_auto_draft の有無)を固定しているので、ここでは
// 「split-views がどの type でどの値を渡すか」の配線だけを見る)。

const test = require("node:test");
const assert = require("node:assert/strict");

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

  const calls = [];
  global.window.renderEditorCategoryBar = (host, tabKey, onFilter, onStructure, opts) => {
    calls.push({ host, tabKey, opts });
    return makeEl("div");
  };
  global.window.pruneEditorUiState = (d) => d;

  global.window.buildCatalogForm = (data) => ({ element: makeEl("div"), getData: () => data });
  global.window.buildMaterialsForm = (data) => ({ element: makeEl("div"), getData: () => data, rerender: () => {} });
  global.window.buildThreadsForm = (data) => ({ element: makeEl("div"), getData: () => data });
  global.window.buildItemStatsForm = (data) => ({ element: makeEl("div"), getData: () => data });
  global.window.buildSpellbooksForm = (data) => ({ element: makeEl("div"), getData: () => data });

  delete require.cache[require.resolve("../public/js/split-views.js")];
  require("../public/js/split-views.js");
  return calls;
}

test("catalog: renderEditorCategoryBar へ includeDraftCategory: true が渡る", () => {
  const calls = setupSplitViewStubs();
  window.buildSplitConfigView({
    type: "catalog", configId: "catalog", categoryKey: "weapon",
    data: { items: {} }
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.includeDraftCategory, true,
    "catalog.yml の画面なのに準備中カテゴリが opt-in されていない");
});

test("materials: renderEditorCategoryBar へ includeDraftCategory は渡らない (false)", () => {
  const calls = setupSplitViewStubs();
  window.buildSplitConfigView({
    type: "materials", configId: "materials", categoryKey: "material",
    data: { materials: {} }
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.includeDraftCategory, false,
    "materials.yml では draft: true が無効なのに準備中カテゴリが opt-in されている");
});

test("item-stats: renderEditorCategoryBar へ includeDraftCategory は渡らない (false)", () => {
  const calls = setupSplitViewStubs();
  window.buildSplitConfigView({
    type: "item-stats", configId: "item-stats", categoryKey: "weapon",
    data: { items: {} }
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.includeDraftCategory, false,
    "item-stats.yml は items を持つため draft: true が実際に書かれてしまうゴーストキー経路"
    + "(2026-08-02 指摘4 実測: {\"NETHERITE_SWORD#300010\":{\"fixed\":{...},\"draft\":true}})");
});

test("threads: renderEditorCategoryBar へ includeDraftCategory は渡らない (false)", () => {
  const calls = setupSplitViewStubs();
  window.buildSplitConfigView({
    type: "threads", configId: "threads", categoryKey: "thread",
    data: { threads: {} }
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.includeDraftCategory, false);
});

test("spellbooks: renderEditorCategoryBar へ includeDraftCategory は渡らない (false)", () => {
  const calls = setupSplitViewStubs();
  window.buildSplitConfigView({
    type: "spellbooks", configId: "spellbooks", categoryKey: "default",
    data: { books: {} }
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.includeDraftCategory, false);
});

test("thread-sets(未知の split type、2026-08-09に単独画面自体を撤去): 汎用フォールバックへ落ち、includeDraftCategory は渡らない", () => {
  // 2026-08-09: thread-bundle(threads.yml + thread-sets.yml を1画面に束ねる旧実装)を撤去した際、
  // 一度は thread-sets.yml 専用の単独 split type を新設したが、「スレッドを画面で分けるな」という
  // 指示の趣旨に反する(撤去した thread-bundle と同じ形の分割を作り直しただけ)として再度撤去された。
  // thread-sets.yml の編集は item-stats.yml の「スレッド」タブへ完全統合済み(下のテスト参照)。
  // "thread-sets" という type 値自体は split-views.js に対応する分岐を持たず、汎用の
  // 「unknown split type」フォールバックに落ちる。ここではそのフォールバック経路でも
  // includeDraftCategory が漏れないことだけを確認する(不変条件の回帰防止)。
  const calls = setupSplitViewStubs();
  window.buildSplitConfigView({
    type: "thread-sets", configId: "thread-sets", categoryKey: "thread-sets",
    data: { "thread-sets": {} }
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.includeDraftCategory, false);
});

test("item-stats(スレッド): threadsData/threadSetsData を渡しても includeDraftCategory は false のまま", () => {
  // 2026-08-09: item-stats.yml の「スレッド」タブが threads.yml/thread-sets.yml を横から
  // 読み書きするようになった(3ファイル1画面)。includeDraftCategory は catalog.yml 専用のフラグ
  // なので、threadsData/threadSetsData の有無に関わらず不変。
  const calls = setupSplitViewStubs();
  window.buildSplitConfigView({
    type: "item-stats", configId: "item-stats", categoryKey: "thread", itemCategory: "thread",
    data: { items: {} }, threadsData: { threads: {} }, threadSetsData: { "thread-sets": {} }
  });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].opts.includeDraftCategory, false);
});
