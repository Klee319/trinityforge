"use strict";

// 追加依頼A (2026-08-02): 「素材」タブが catalog.yml のアイテム(ダンジョンの鍵など)を
// **実体は catalog.yml に残したまま**一覧・編集できるようにする複合ビューの回帰テスト。
//
// 実装は3箇所の連動:
//   1. split-views.js の「素材」分岐を、buildMaterialsForm + 2本目の buildCatalogForm を
//      合成する複合ビューへ変更 (thread-bundle パターン流用)。
//   2. forms.js の表示タブセレクト (tabOpts) に新しいタブ値 "material-ref" を追加。
//   3. forms.js:446 の item-stats.yml ゴーストエントリ防止ガードにも同じ値を追加。
//
// このタスク自身が明示的に指摘した2つの罠を、ここで実際の挙動として固定する:
//   罠A: 新タブ値 "material-ref" が既存の "material"(= materials.yml へ実データ移行する
//        ボタンの値、buildCatalogForm の moveEntryToMaterials)と文字列衝突すると、
//        鍵アイテムが catalog.yml から消えて materials.yml へ移動してしまう。
//   罠B: item-stats.yml のゴーストエントリガードに "material-ref" を足し忘れると、
//        material-ref 品が item-stats.yml に「タブの無い幽霊エントリ」として量産される。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");

// ============================================================
// 0. 定義そのもの: window.CATALOG_MATERIAL_REF_TAB が "material" と異なる文字列であること
// ============================================================

function loadFormsForConstant() {
  global.window = {};
  global.document = {};
  global.window.h = () => ({});
  delete require.cache[require.resolve("../public/js/editor-categories.js")];
  delete require.cache[require.resolve("../public/js/catalog-candidates.js")];
  delete require.cache[require.resolve("../public/js/forms.js")];
  require("../public/js/editor-categories.js");
  require("../public/js/catalog-candidates.js");
  require("../public/js/forms.js");
  return global.window;
}

test("window.CATALOG_MATERIAL_REF_TAB は id/label の2要素配列で、id は既存の実データ移行値 \"material\" と異なる", () => {
  const win = loadFormsForConstant();
  assert.ok(Array.isArray(win.CATALOG_MATERIAL_REF_TAB), "CATALOG_MATERIAL_REF_TAB が定義されていない");
  const [id, label] = win.CATALOG_MATERIAL_REF_TAB;
  assert.equal(typeof id, "string");
  assert.ok(id, "id が空");
  assert.notEqual(id, "material",
    "既存の \"material\"(materials.ymlへ実データ移行するボタンの値)と衝突している。"
    + "衝突すると catalog.yml のアイテムが materials.yml へ誤って移行される");
  assert.ok(label, "label が空");
});

// ============================================================
// 1. 罠A(文字列衝突)の実挙動テスト: editor-categories.js の実物を使う。
//    renderItemTabSelect の onChange が "material-ref" では external.material を
//    誤爆しない/ "material" では正しく誤爆(=意図した移行)することを両方向で固定する。
// ============================================================

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; }
  };
  Object.defineProperty(el, "innerHTML", { get() { return ""; }, set() { el.children = []; } });
  return el;
}

function loadEditorCategoriesReal() {
  global.window = {};
  global.document = {};
  global.window.h = (tag, attrs, children) => {
    const el = makeEl(tag, attrs);
    if (Array.isArray(children)) children.forEach((c) => c != null && c !== false && el.appendChild(c));
    else if (children != null && children !== false) el.appendChild(children);
    return el;
  };
  delete require.cache[require.resolve("../public/js/editor-categories.js")];
  require("../public/js/editor-categories.js");
  return global.window;
}

test("罠A: 表示タブに「素材(カタログ内)」を選んでも materials.yml への実データ移行ハンドラは呼ばれない", () => {
  const win = loadEditorCategoriesReal();
  let capturedCfg = null;
  win.listSelect = (cfg) => { capturedCfg = cfg; return makeEl("span"); };

  const host = { items: { key_mines: { material: "TRIAL_KEY" } } };
  const tabOpts = [["other", "補助"], ["material-ref", "素材(カタログ内)"], ["material", "素材(materials.ymlへ移動)"]];
  let migratedId = null;
  const external = { material: (id) => { migratedId = id; } };

  win.renderItemTabSelect(host, "key_mines", "TRIAL_KEY", tabOpts, () => {}, external);
  assert.ok(capturedCfg && typeof capturedCfg.onChange === "function", "listSelect が呼ばれていない");

  // 「素材(カタログ内)」を選ぶ: ローカルピンの変更だけで済み、実データ移行は起きない。
  capturedCfg.onChange("material-ref");
  assert.equal(migratedId, null,
    "material-ref を選んだだけで実データ移行ハンドラが呼ばれた(文字列衝突の再発)");
  assert.equal(win.getItemDisplayTab(host, "key_mines"), "material-ref",
    "material-ref のローカルピンが反映されていない");
  assert.ok(Object.prototype.hasOwnProperty.call(host.items, "key_mines"),
    "material-ref を選んだだけで catalog.yml からアイテムが消えてはいけない");

  // 対照実験: 本物の「素材(移動)」を選んだときは、これまでどおり移行ハンドラへ委譲されること。
  capturedCfg.onChange("material");
  assert.equal(migratedId, "key_mines", "実データ移行ハンドラ(material)が正しく呼ばれていない");
});

// ============================================================
// 2. 罠B(item-stats ゴーストエントリ)の実挙動テスト: forms.js の実物 buildItemStatsForm。
// ============================================================

function runBuildItemStatsForm(data, catalogCandidates) {
  global.window = global.window || {};
  global.document = global.document || {};
  delete require.cache[require.resolve("../public/js/editor-categories.js")];
  delete require.cache[require.resolve("../public/js/catalog-candidates.js")];
  delete require.cache[require.resolve("../public/js/forms.js")];
  require("../public/js/editor-categories.js");
  require("../public/js/catalog-candidates.js");
  require("../public/js/forms.js");
  const buildItemStatsForm = global.window.buildItemStatsForm;
  try {
    buildItemStatsForm(data, { catalogCandidates });
  } catch (err) {
    // no-op: DOM 未定義による後段の例外は検証対象外 (item-stats-material-skip.test.js と同じ手法)。
  }
  return data;
}

test("罠B: tab: \"material-ref\" の候補は item-stats.yml に working.items の枠を作らない", () => {
  global.window = {};
  const win = loadFormsForConstant();
  const refTabId = win.CATALOG_MATERIAL_REF_TAB[0];

  const catalogCandidates = [
    { id: "key_mines", displayName: "鉱山の鍵", material: "TRIAL_KEY", cmd: 5501, tab: refTabId }
  ];
  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  const key = "TRIAL_KEY#5501";
  assert.equal(
    Object.prototype.hasOwnProperty.call(data.items, key),
    false,
    "material-ref 品が working.items に幽霊エントリとして追加された"
    + "(ITEM_STATS_CATEGORIES に対応タブが無いので画面に出ないまま残り続ける)"
  );
  const itemTabs = (data._editor && data._editor.itemTabs) || {};
  assert.equal(
    Object.prototype.hasOwnProperty.call(itemTabs, key),
    false,
    "material-ref 品の itemTabs エントリが item-stats.yml 側に生成された"
  );
});

test("罠Bの対照実験: 通常タブ(other等)の候補は従来どおり枠を作る(巻き添え確認)", () => {
  const catalogCandidates = [
    { id: "sword_id", displayName: "剣", material: "IRON_SWORD", cmd: null, tab: "other" }
  ];
  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);
  assert.ok(Object.prototype.hasOwnProperty.call(data.items, "IRON_SWORD"),
    "material-ref 対応でガードを広げすぎて他タブまで巻き込んでいる");
});

// ============================================================
// 3. forms.js ソースの静的確認: tabOpts が material-ref と material の両方を
//    別々の配列要素として持つこと(同じ文字列に潰されていないこと)。
// ============================================================

test("forms.js: 表示タブセレクトの tabOpts は material-ref と material を別選択肢として持つ", () => {
  const src = JS("forms.js");
  const block = /const tabOpts = \(crossFile[\s\S]{0,400}?\);/.exec(src);
  assert.ok(block, "tabOpts の組み立て箇所が見つからない(forms.js の構造が変わった)");
  assert.match(block[0], /window\.CATALOG_MATERIAL_REF_TAB/, "material-ref の選択肢が消えている");
  assert.match(block[0], /\["material",/, "materials.ymlへの実データ移行オプションが消えている");
});

// ============================================================
// 5. 「+ アイテム追加」の既定 Material (2026-08-02 指摘5)。
//    defaultMaterial マップに "material-ref" が無いとフォールバックの "DIAMOND_SWORD" が
//    使われ、「素材」タブの参照セクションから品を足すと必ずダイヤの剣になっていた。
// ============================================================

test("forms.js: 「+ アイテム追加」の defaultMaterial は material-ref にも専用の既定値を持つ", () => {
  const src = JS("forms.js");
  // forms.js には defaultMaterial の組み立てが2箇所ある(buildItemStatsForm 側は無関係)ため、
  // "material-ref" を含む方をピンポイントで拾う(そうでないと最初にヒットした無関係な
  // ブロックを見て「見つからない」誤検知になる)。
  const block = /const defaultMaterial = \{[\s\S]{0,500}?"material-ref":[\s\S]{0,80}?\}\[activeCat\] \|\| "DIAMOND_SWORD";/
    .exec(src);
  assert.ok(block, "defaultMaterial マップの組み立て箇所(material-ref入り)が見つからない(forms.js の構造が変わった)");
  assert.match(block[0], /"material-ref":\s*"[A-Z_]+"/,
    "material-ref 用の既定 Material が定義されていない"
    + "(素材タブの参照セクションから追加すると必ずダイヤの剣になる)");
  assert.doesNotMatch(block[0].match(/"material-ref":\s*"([A-Z_]+)"/)[1], /^DIAMOND_SWORD$/,
    "material-ref の既定値がフォールバックと同じ DIAMOND_SWORD のまま");
});

test("forms.js:446 のゴーストエントリガードが material-ref も skip 対象にしている", () => {
  const src = JS("forms.js");
  const guard = /if \(candidate && \(candidate\.tab === "material" \|\| candidate\.tab === MATERIAL_REF_TAB_ID\)\) continue;/;
  assert.match(src, guard,
    "item-stats のゴーストエントリガードに material-ref が含まれていない"
    + "(このガードを漏らすと material-ref 品が item-stats.yml に幽霊エントリとして量産される)");
});

// ============================================================
// 4. split-views.js の複合ビュー配線 (buildMaterialsForm / buildCatalogForm をスタブして検証)。
//    functional-items-catalog-integration.test.js の setupSplitViewStubs と同じ流儀。
// ============================================================

function setupSplitViewStubs({ materialsFormFactory, catalogFormFactory }) {
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && c !== false && el.appendChild(c));
    else if (children != null && children !== false) el.appendChild(children);
    return el;
  };
  global.window.CATALOG_MATERIAL_REF_TAB = ["material-ref", "素材(カタログ内)"];
  global.window.buildMaterialsForm = materialsFormFactory;
  global.window.buildCatalogForm = catalogFormFactory;
  global.window.renderEditorCategoryBar = () => makeEl("div");
  global.window.pruneEditorUiState = (d) => d;
  delete require.cache[require.resolve("../public/js/split-views.js")];
  require("../public/js/split-views.js");
}

test("複合ビュー: catalog.yml(counterpart)があれば buildCatalogForm を material-ref 固定で呼ぶ", () => {
  let catalogFormOpts = null;
  setupSplitViewStubs({
    materialsFormFactory: (data) => ({ element: makeEl("div"), getData: () => data, rerender: () => {} }),
    catalogFormFactory: (data, opts) => {
      catalogFormOpts = opts;
      return { element: makeEl("div"), getData: () => data };
    }
  });
  const materialsData = { materials: {} };
  const catalogData = { items: { key_mines: { material: "TRIAL_KEY" } } };
  window.buildSplitConfigView({
    type: "materials", configId: "materials", categoryKey: "material",
    data: materialsData, counterpartId: "catalog", counterpartData: catalogData
  });
  assert.ok(catalogFormOpts, "counterpart(catalog.yml)があるのに buildCatalogForm が呼ばれていない");
  assert.equal(catalogFormOpts.hubMode, true, "7タブ切替バーを隠す hubMode が指定されていない");
  assert.equal(catalogFormOpts.initialCategory, "material-ref",
    "material-ref 固定で開かれていない(既存の7タブが混ざって出る恐れ)");
  // 2026-08-02 指摘5: editorCategoryKey が無いと useEditorMeta が false になり、
  // この画面で新規追加/複製した品が catalog.yml の _editor.categories / orders に
  // 一切記録されない (次にカタログ画面を開くと「未設定」に落ちる)。
  assert.equal(catalogFormOpts.editorCategoryKey, "material-ref",
    "editorCategoryKey が渡っていない(この画面での追加/複製が _editor に記録されない)");
});

test("複合ビュー: catalog.yml が読めない(counterpart無し)場合でも例外にならず materials.yml 単独で動く", () => {
  let catalogFormCalled = false;
  setupSplitViewStubs({
    materialsFormFactory: (data) => ({ element: makeEl("div"), getData: () => data, rerender: () => {} }),
    catalogFormFactory: () => { catalogFormCalled = true; return { element: makeEl("div"), getData: () => ({}) }; }
  });
  const materialsData = { materials: {} };
  assert.doesNotThrow(() => {
    window.buildSplitConfigView({
      type: "materials", configId: "materials", categoryKey: "material", data: materialsData
    });
  });
  assert.equal(catalogFormCalled, false, "counterpart が無いのに buildCatalogForm が呼ばれた");
});

test("複合ビュー: getExtraSaves は catalog サブフォームの getData() を返す(直接編集も保存対象に入る)", () => {
  const editedCatalogData = { items: { key_mines: { material: "TRIAL_KEY", "display-name": "編集後" } } };
  setupSplitViewStubs({
    materialsFormFactory: (data) => ({ element: makeEl("div"), getData: () => data, rerender: () => {} }),
    catalogFormFactory: () => ({ element: makeEl("div"), getData: () => editedCatalogData })
  });
  const materialsData = { materials: {} };
  const originalCatalogData = { items: { key_mines: { material: "TRIAL_KEY" } } };
  const view = window.buildSplitConfigView({
    type: "materials", configId: "materials", categoryKey: "material",
    data: materialsData, counterpartId: "catalog", counterpartData: originalCatalogData
  });
  const extras = view.getExtraSaves();
  const catalogExtra = extras.find((e) => e.id === "catalog");
  assert.ok(catalogExtra, "catalog.yml が保存対象 (getExtraSaves) に含まれていない"
    + "(旧 when:()=>cross.dirty のままだと、直接編集しただけでは保存されない)");
  assert.deepEqual(catalogExtra.data, editedCatalogData,
    "catalog サブフォームの getData() の結果が保存対象へ反映されていない");
});
