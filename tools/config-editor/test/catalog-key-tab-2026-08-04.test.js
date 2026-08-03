"use strict";

// 「素材」画面が catalog.yml のアイテム(ダンジョンの鍵)を**実体は catalog.yml に残したまま**
// 一覧・編集できるようにする複合ビューの回帰テスト。
//
// 2026-08-02 に「素材(カタログ内)」(tab id: material-ref)という独立セクションとして導入し、
// 2026-08-04 に K の指摘「(items/catalog.yml、表示タブ「素材(カタログ内)」)という謎の要素の中に
// 未分類のものが入っている / 見にくいので消してほしい / 他のカタログのタブにUIをそろえてほしい」で
// **カテゴリバー1本(materials.yml の _editor.categories.material)に統合**した。
// タブ id は "key" / ラベル「鍵」へ改称し、独立セクションの見出しと2つ目の検索欄は撤去した。
//
// 実装は4箇所の連動:
//   1. forms.js: 表示タブ値 window.CATALOG_KEY_TAB = ["key", "鍵"] と、素材画面の鍵カテゴリ id
//      window.MATERIALS_KEY_CATEGORY_ID。
//   2. forms.js: item-stats.yml ゴーストエントリ防止ガードにも "key" を追加。
//   3. split-views.js: 「素材」分岐で buildMaterialsForm + buildCatalogForm(鍵タブ固定) を合成し、
//      MATERIALS_SPLIT_LOGIC.visibleSections でどちらを見せるか決める。
//   4. ars-forms.js: buildMaterialsForm の suppressed オプション(素材側を丸ごと畳む)。
//
// このタスクが明示的に指摘した2つの罠を、実際の挙動として固定する:
//   罠A: 新タブ値が既存の "material"(= materials.yml へ実データ移行するボタンの値、
//        buildCatalogForm の moveEntryToMaterials)と文字列衝突すると、鍵アイテムが
//        catalog.yml から消えて materials.yml へ移動してしまう。
//   罠B: item-stats.yml のゴーストエントリガードに新タブ値を足し忘れると、鍵が
//        item-stats.yml に「タブの無い幽霊エントリ」として量産される。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..");
const JS = (name) => fs.readFileSync(path.join(ROOT, "public", "js", name), "utf8");
const CATALOG = path.resolve(ROOT, "../../TrinityForge/src/main/resources/items/catalog.yml");
const MATERIALS = path.resolve(ROOT, "../../fork-handoff/arspaper/fork/src/main/resources/materials.yml");

function makeEl(tag, attrs) {
  const el = {
    tag,
    props: attrs || {},
    children: [],
    style: {},
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; }
  };
  Object.defineProperty(el, "innerHTML", { get() { return ""; }, set() { el.children = []; } });
  return el;
}

// ============================================================
// 0. 定義そのもの: window.CATALOG_KEY_TAB が "material" と異なる文字列であること
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

test("window.CATALOG_KEY_TAB は id/label の2要素配列で、id は既存の実データ移行値 \"material\" と異なる", () => {
  const win = loadFormsForConstant();
  assert.ok(Array.isArray(win.CATALOG_KEY_TAB), "CATALOG_KEY_TAB が定義されていない");
  const [id, label] = win.CATALOG_KEY_TAB;
  assert.equal(typeof id, "string");
  assert.ok(id, "id が空");
  assert.notEqual(id, "material",
    "既存の \"material\"(materials.ymlへ実データ移行するボタンの値)と衝突している。"
    + "衝突すると catalog.yml のアイテムが materials.yml へ誤って移行される");
  assert.ok(label, "label が空");
  assert.equal(typeof win.MATERIALS_KEY_CATEGORY_ID, "string");
  assert.ok(win.MATERIALS_KEY_CATEGORY_ID,
    "MATERIALS_KEY_CATEGORY_ID(素材画面のカテゴリバーに出す鍵カテゴリの id)が無い");
});

test("鍵タブはカタログのタブバー(CATALOG_CATEGORIES)には入れない", () => {
  const win = loadFormsForConstant();
  const ids = (win.CATALOG_CATEGORIES || []).map(([id]) => id);
  assert.ok(ids.length > 0, "CATALOG_CATEGORIES が取れていない");
  assert.equal(ids.includes(win.CATALOG_KEY_TAB[0]), false,
    "鍵タブがカタログのタブバーにも増えている(鍵の置き場は「素材」画面1本。"
    + "両方に出すと導線が二重になり、K の指摘「見にくい」に戻る)");
});

// ============================================================
// 1. 罠A(文字列衝突)の実挙動テスト: editor-categories.js の実物を使う。
//    renderItemTabSelect の onChange が "key" では external.material を
//    誤爆しない/ "material" では正しく誤爆(=意図した移行)することを両方向で固定する。
// ============================================================

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

test("罠A: 表示タブに「鍵」を選んでも materials.yml への実データ移行ハンドラは呼ばれない", () => {
  const win = loadEditorCategoriesReal();
  let capturedCfg = null;
  win.listSelect = (cfg) => { capturedCfg = cfg; return makeEl("span"); };

  const host = { items: { key_mines: { material: "TRIAL_KEY" } } };
  const tabOpts = [["other", "補助"], ["key", "鍵"], ["material", "素材(materials.ymlへ移動)"]];
  let migratedId = null;
  const external = { material: (id) => { migratedId = id; } };

  win.renderItemTabSelect(host, "key_mines", "TRIAL_KEY", tabOpts, () => {}, external);
  assert.ok(capturedCfg && typeof capturedCfg.onChange === "function", "listSelect が呼ばれていない");

  // 「鍵」を選ぶ: ローカルピンの変更だけで済み、実データ移行は起きない。
  capturedCfg.onChange("key");
  assert.equal(migratedId, null,
    "key を選んだだけで実データ移行ハンドラが呼ばれた(文字列衝突の再発)");
  assert.equal(win.getItemDisplayTab(host, "key_mines"), "key",
    "鍵タブのローカルピンが反映されていない");
  assert.ok(Object.prototype.hasOwnProperty.call(host.items, "key_mines"),
    "key を選んだだけで catalog.yml からアイテムが消えてはいけない");

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

test("罠B: tab: \"key\" の候補は item-stats.yml に working.items の枠を作らない", () => {
  global.window = {};
  const win = loadFormsForConstant();
  const keyTabId = win.CATALOG_KEY_TAB[0];

  const catalogCandidates = [
    { id: "key_mines", displayName: "鉱山の鍵", material: "TRIAL_KEY", cmd: 5501, tab: keyTabId }
  ];
  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  const key = "TRIAL_KEY#5501";
  assert.equal(
    Object.prototype.hasOwnProperty.call(data.items, key),
    false,
    "鍵が working.items に幽霊エントリとして追加された"
    + "(ITEM_STATS_CATEGORIES に対応タブが無いので画面に出ないまま残り続ける)"
  );
  const itemTabs = (data._editor && data._editor.itemTabs) || {};
  assert.equal(
    Object.prototype.hasOwnProperty.call(itemTabs, key),
    false,
    "鍵の itemTabs エントリが item-stats.yml 側に生成された"
  );
});

test("罠Bの対照実験: 通常タブ(other等)の候補は従来どおり枠を作る(巻き添え確認)", () => {
  const catalogCandidates = [
    { id: "sword_id", displayName: "剣", material: "IRON_SWORD", cmd: null, tab: "other" }
  ];
  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);
  assert.ok(Object.prototype.hasOwnProperty.call(data.items, "IRON_SWORD"),
    "鍵対応でガードを広げすぎて他タブまで巻き込んでいる");
});

// ============================================================
// 3. forms.js ソースの静的確認
// ============================================================

test("forms.js: 表示タブセレクトの tabOpts は鍵タブと material を別選択肢として持つ", () => {
  const src = JS("forms.js");
  const block = /const tabOpts = \(crossFile[\s\S]{0,400}?\);/.exec(src);
  assert.ok(block, "tabOpts の組み立て箇所が見つからない(forms.js の構造が変わった)");
  assert.match(block[0], /window\.CATALOG_KEY_TAB/, "鍵タブの選択肢が消えている");
  assert.match(block[0], /\["material",/, "materials.ymlへの実データ移行オプションが消えている");
});

test("forms.js: 「+ アイテム追加」の defaultMaterial は鍵タブにも専用の既定値を持つ", () => {
  const src = JS("forms.js");
  // forms.js には defaultMaterial の組み立てが2箇所ある(buildItemStatsForm 側は無関係)ため、
  // 鍵タブを含む方をピンポイントで拾う(そうでないと最初にヒットした無関係な
  // ブロックを見て「見つからない」誤検知になる)。
  const block = /const defaultMaterial = \{[\s\S]{0,600}?key: "[A-Z_]+"[\s\S]{0,120}?\}\[activeCat\] \|\| "DIAMOND_SWORD";/
    .exec(src);
  assert.ok(block, "defaultMaterial マップの組み立て箇所(鍵タブ入り)が見つからない(forms.js の構造が変わった)");
  const material = /\bkey: "([A-Z_]+)"/.exec(block[0])[1];
  assert.notEqual(material, "DIAMOND_SWORD",
    "鍵タブの既定値がフォールバックと同じ DIAMOND_SWORD のまま"
    + "(素材画面の鍵カテゴリから追加すると必ずダイヤの剣になる)");
});

test("forms.js のゴーストエントリガードが鍵タブも skip 対象にしている", () => {
  const src = JS("forms.js");
  const guard = /if \(candidate && \(candidate\.tab === "material" \|\| candidate\.tab === KEY_TAB_ID\)\) continue;/;
  assert.match(src, guard,
    "item-stats のゴーストエントリガードに鍵タブが含まれていない"
    + "(このガードを漏らすと鍵が item-stats.yml に幽霊エントリとして量産される)");
});

test("split-views.js: 旧「カタログ内の素材 (…)」見出しを再び生やしていない", () => {
  const src = JS("split-views.js");
  // コメント行(改修の経緯説明)は許す。h(...) で実際に描画していないことだけを見る。
  const rendering = /h\(\s*"div"\s*,\s*\{[^}]*sub-title[^}]*\}\s*,?[\s\S]{0,120}?カタログ内の素材/;
  assert.doesNotMatch(src, rendering,
    "「カタログ内の素材 (items/catalog.yml、表示タブ…)」見出しが復活している"
    + "(K の指摘: 内部事情の羅列で見にくい。カテゴリバー1本に統合したのでこの見出しは不要)");
});

// ============================================================
// 4. split-views.js の複合ビュー配線 (buildMaterialsForm / buildCatalogForm をスタブして検証)。
//    functional-items-catalog-integration.test.js の setupSplitViewStubs と同じ流儀。
// ============================================================

/** @returns {{barArgs: object}} カテゴリバーへ渡されたコールバック(タブ切替の再現に使う)。 */
function setupSplitViewStubs({ materialsFormFactory, catalogFormFactory }) {
  const captured = { barArgs: null };
  global.window = global.window || {};
  global.document = global.document || {};
  global.window.h = (tag, props, children) => {
    const el = makeEl(tag, props);
    if (Array.isArray(children)) children.forEach((c) => c != null && c !== false && el.appendChild(c));
    else if (children != null && children !== false) el.appendChild(children);
    return el;
  };
  global.window.CATALOG_KEY_TAB = ["key", "鍵"];
  global.window.MATERIALS_KEY_CATEGORY_ID = "cat_dungeon_keys";
  global.window.buildMaterialsForm = materialsFormFactory;
  global.window.buildCatalogForm = catalogFormFactory;
  global.window.activeEditorCategory = () => null;
  global.window.renderEditorCategoryBar = (host, tabKey, onFilterChange, onStructureChange) => {
    captured.barArgs = { host, tabKey, onFilterChange, onStructureChange };
    return makeEl("div");
  };
  global.window.pruneEditorUiState = (d) => d;
  delete require.cache[require.resolve("../public/js/split-views.js")];
  require("../public/js/split-views.js");
  return captured;
}

const stubForm = (data) => ({ element: makeEl("div"), getData: () => data, rerender: () => {} });

// ---- セクション表示判定 (純関数) ----

test("visibleSections: 鍵カテゴリを選んだときだけ鍵、それ以外は素材(常に片方だけ)", () => {
  setupSplitViewStubs({ materialsFormFactory: stubForm, catalogFormFactory: stubForm });
  const logic = global.window.MATERIALS_SPLIT_LOGIC;
  assert.deepEqual(logic.visibleSections("cat_dungeon_keys", "cat_dungeon_keys"),
    { materials: false, keys: true },
    "鍵カテゴリなのに素材リストが残る(「該当する素材がありません」と検索欄が鍵の上に出る)");
  // 「すべて」でも2つ並べない。並べると検索欄2つ・カード列2つになり、他のカタログタブと
  // 操作が揃わない(K の指摘「見にくい / UIをそろえてほしい」の再発)。
  assert.deepEqual(logic.visibleSections(null, "cat_dungeon_keys"),
    { materials: true, keys: false },
    "「すべて」で鍵の一覧までぶら下がっている(改修前の「謎の要素」そのもの)");
  assert.deepEqual(logic.visibleSections("cat_1784733348418", "cat_dungeon_keys"),
    { materials: true, keys: false },
    "素材カテゴリで絞り込んだのに鍵の一覧が残っている");
  assert.deepEqual(logic.visibleSections("__unset__", "cat_dungeon_keys"),
    { materials: true, keys: false },
    "「未設定」は materials.yml 側の未分類を見る画面なので鍵は出さない");
});

test("visibleSections: 鍵セクションが無い(catalog.yml が読めない)場合は素材だけ", () => {
  setupSplitViewStubs({ materialsFormFactory: stubForm, catalogFormFactory: stubForm });
  const logic = global.window.MATERIALS_SPLIT_LOGIC;
  assert.deepEqual(logic.visibleSections(null, null), { materials: true, keys: false });
  assert.deepEqual(logic.visibleSections("cat_dungeon_keys", null), { materials: true, keys: false },
    "鍵セクションが無いのに素材まで隠すと画面が真っ白になる");
});

// ---- 配線 ----

test("複合ビュー: catalog.yml(counterpart)があれば buildCatalogForm を鍵タブ固定で呼ぶ", () => {
  let catalogFormOpts = null;
  setupSplitViewStubs({
    materialsFormFactory: stubForm,
    catalogFormFactory: (data, opts) => {
      catalogFormOpts = opts;
      return stubForm(data);
    }
  });
  window.buildSplitConfigView({
    type: "materials", configId: "materials", categoryKey: "material",
    data: { materials: {} }, counterpartId: "catalog",
    counterpartData: { items: { key_mines: { material: "TRIAL_KEY" } } }
  });
  assert.ok(catalogFormOpts, "counterpart(catalog.yml)があるのに buildCatalogForm が呼ばれていない");
  assert.equal(catalogFormOpts.hubMode, true, "7タブ切替バーを隠す hubMode が指定されていない");
  assert.equal(catalogFormOpts.initialCategory, "key",
    "鍵タブ固定で開かれていない(既存の7タブが混ざって出る恐れ)");
  // 2026-08-02 指摘5: editorCategoryKey が無いと useEditorMeta が false になり、
  // この画面で新規追加/複製した品が catalog.yml の _editor.categories / orders に
  // 一切記録されない (次にカタログ画面を開くと「未設定」に落ちる)。
  assert.equal(catalogFormOpts.editorCategoryKey, "key",
    "editorCategoryKey が渡っていない(この画面での追加/複製が _editor に記録されない)");
});

test("複合ビュー: 素材フォームには suppressed が渡り、鍵カテゴリ選択時に true を返す", () => {
  let materialsOpts = null;
  setupSplitViewStubs({
    materialsFormFactory: (data, opts) => { materialsOpts = opts; return stubForm(data); },
    catalogFormFactory: stubForm
  });
  let active = null;
  global.window.activeEditorCategory = () => active;
  window.buildSplitConfigView({
    type: "materials", configId: "materials", categoryKey: "material",
    data: { materials: {} }, counterpartId: "catalog",
    counterpartData: { items: { key_mines: { material: "TRIAL_KEY" } } }
  });
  assert.ok(materialsOpts && typeof materialsOpts.suppressed === "function",
    "suppressed が渡っていない(鍵カテゴリでも素材側の検索欄と「+ 素材追加」が残る)");
  assert.equal(materialsOpts.suppressed(), false, "「すべて」では素材側を畳んではいけない");
  active = "cat_dungeon_keys";
  assert.equal(materialsOpts.suppressed(), true, "鍵カテゴリ選択中に素材側が畳まれない");
});

test("複合ビュー: カテゴリタブを素材カテゴリへ切り替えると鍵の一覧が display:none になる", () => {
  let keyRerenders = 0;
  const captured = setupSplitViewStubs({
    materialsFormFactory: stubForm,
    catalogFormFactory: (data) => ({
      element: makeEl("div"), getData: () => data, rerender: () => { keyRerenders++; }
    })
  });
  let active = null;
  global.window.activeEditorCategory = () => active;
  const view = window.buildSplitConfigView({
    type: "materials", configId: "materials", categoryKey: "material",
    data: { materials: {} }, counterpartId: "catalog",
    counterpartData: { items: { key_mines: { material: "TRIAL_KEY" } } }
  });
  const composite = view.element.children
    .flatMap((c) => (c && Array.isArray(c.children) ? c.children : []))
    .find((c) => c && c.props && c.props.class === "hub-materials-composite");
  assert.ok(composite, "複合ビューの外枠 .hub-materials-composite が無い");
  const box = composite.children.find((c) => c && c.props && c.props.class === "hub-materials-keys");
  assert.ok(box, "鍵の一覧を包む .hub-materials-keys が無い(表示切替の対象が特定できない)");
  assert.equal(box.style.display, "none",
    "初期表示(「すべて」)で鍵の一覧がぶら下がっている(改修前の状態)");

  // カテゴリバーのタブ切替(onFilterChange)は form.rerender へ配線されている(withCategoryBar)。
  assert.ok(captured.barArgs && typeof captured.barArgs.onFilterChange === "function",
    "カテゴリバーが描かれていない");
  active = "cat_dungeon_keys";
  captured.barArgs.onFilterChange();
  assert.equal(box.style.display, "", "鍵カテゴリを選んでも鍵の一覧が出てこない");
  assert.ok(keyRerenders > 0,
    "鍵側の rerender が呼ばれていない(この画面で追加/削除した鍵がタブを戻すまで反映されない)");

  active = "cat_1784733348418";
  captured.barArgs.onFilterChange();
  assert.equal(box.style.display, "none",
    "素材カテゴリへ切り替えても鍵の一覧が残る(絞り込んだのに関係ない品が出る)");
});

test("複合ビュー: catalog.yml が読めない(counterpart無し)場合でも例外にならず materials.yml 単独で動く", () => {
  let catalogFormCalled = false;
  setupSplitViewStubs({
    materialsFormFactory: stubForm,
    catalogFormFactory: () => { catalogFormCalled = true; return stubForm({}); }
  });
  assert.doesNotThrow(() => {
    window.buildSplitConfigView({
      type: "materials", configId: "materials", categoryKey: "material", data: { materials: {} }
    });
  });
  assert.equal(catalogFormCalled, false, "counterpart が無いのに buildCatalogForm が呼ばれた");
});

test("複合ビュー: getExtraSaves は catalog サブフォームの getData() を返す(直接編集も保存対象に入る)", () => {
  const editedCatalogData = { items: { key_mines: { material: "TRIAL_KEY", "display-name": "編集後" } } };
  setupSplitViewStubs({
    materialsFormFactory: stubForm,
    catalogFormFactory: () => ({ element: makeEl("div"), getData: () => editedCatalogData })
  });
  const view = window.buildSplitConfigView({
    type: "materials", configId: "materials", categoryKey: "material",
    data: { materials: {} }, counterpartId: "catalog",
    counterpartData: { items: { key_mines: { material: "TRIAL_KEY" } } }
  });
  const catalogExtra = view.getExtraSaves().find((e) => e.id === "catalog");
  assert.ok(catalogExtra, "catalog.yml が保存対象 (getExtraSaves) に含まれていない"
    + "(旧 when:()=>cross.dirty のままだと、直接編集しただけでは保存されない)");
  assert.deepEqual(catalogExtra.data, editedCatalogData,
    "catalog サブフォームの getData() の結果が保存対象へ反映されていない");
});

// ============================================================
// 5. buildMaterialsForm の suppressed を実物で確認する(受け取っても無視していないこと)。
//    split-views 側で渡すだけでは足りない — 無視すると鍵カテゴリで「該当する素材がありません」
//    と検索欄・「+ 素材追加」が鍵一覧の上に残り、K の言う「謎の要素」の状態に戻る。
// ============================================================

function buildRealMaterialsForm(options) {
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
  // ars-forms.js は window と document が両方あると「ブラウザ」と判断し、純関数コアを
  // require ではなく window.RECIPES から取る(末尾の IIFE 引数)。先に載せておく。
  global.window.RECIPES = require("../public/js/recipes.js");
  delete require.cache[require.resolve("../public/js/ars-forms.js")];
  require("../public/js/ars-forms.js");
  // materials を空にすると、描画は「検索欄 + 空ガイド + アクション行」だけになり
  // カード側の window.* 依存(materialInput 等)を要さない。
  return global.window.buildMaterialsForm({ materials: {} }, options || {});
}

test("buildMaterialsForm: suppressed() が true の間は何も描かない(検索欄も「+ 素材追加」も出さない)", () => {
  const shown = buildRealMaterialsForm({});
  assert.ok(shown.element.children.length > 0,
    "前提が崩れている: 通常は検索欄と空ガイドが描かれるはず");

  const hidden = buildRealMaterialsForm({ suppressed: () => true });
  assert.equal(hidden.element.children.length, 0,
    "suppressed() が true でも素材側が描かれている"
    + "(鍵カテゴリを選んでいる間、鍵の一覧の上に素材の検索欄と「+ 素材追加」が残る)");
});

// ============================================================
// 6. 出荷データ: 17件の鍵が鍵タブへピンされ、「素材」画面のカテゴリバーに鍵カテゴリがある。
// ============================================================

test("出荷 catalog.yml: key_* は全件が鍵タブにピンされ、鍵タブ内で未分類が残っていない", () => {
  const data = YAML.parse(fs.readFileSync(CATALOG, "utf8"));
  const keyIds = Object.keys(data.items).filter((id) => id.startsWith("key_"));
  assert.ok(keyIds.length >= 17, `key_* が少なすぎる: ${keyIds.length}`);
  const tabs = (data._editor && data._editor.itemTabs) || {};
  const wrong = keyIds.filter((id) => tabs[id] !== "key");
  assert.deepEqual(wrong, [], "鍵タブ以外にピンされている鍵がある(補助タブ等に混ざって出る)");

  const cats = ((data._editor && data._editor.categories) || {}).key || [];
  const classified = new Set(cats.flatMap((c) => c.itemIds || []));
  assert.deepEqual(keyIds.filter((id) => !classified.has(id)), [],
    "鍵タブ内で未分類の鍵が残っている(K の指摘「未分類のものが入っている」の再発)");
});

test("出荷 materials.yml: 「素材」画面のカテゴリバーに鍵カテゴリがあり、鍵を全件持つ", () => {
  const win = loadFormsForConstant();
  const data = YAML.parse(fs.readFileSync(MATERIALS, "utf8"));
  const cats = ((data._editor && data._editor.categories) || {}).material || [];
  const keyCat = cats.find((c) => c.id === win.MATERIALS_KEY_CATEGORY_ID);
  assert.ok(keyCat, `materials.yml の _editor.categories.material に ${win.MATERIALS_KEY_CATEGORY_ID} が無い`
    + "(素材画面のカテゴリバーに「鍵」タブが出ないので、鍵に永久に到達できない)");

  const catalog = YAML.parse(fs.readFileSync(CATALOG, "utf8"));
  const keyIds = Object.keys(catalog.items).filter((id) => id.startsWith("key_"));
  assert.deepEqual(keyIds.filter((id) => !(keyCat.itemIds || []).includes(id)), [],
    "catalog.yml にある鍵が素材画面の鍵カテゴリに載っていない");

  // 鍵の実体を materials.yml へ移していないこと(移すと gates.yml の key-item 判定が切れる)。
  const materials = data.materials || {};
  assert.deepEqual(keyIds.filter((id) => Object.prototype.hasOwnProperty.call(materials, id)), [],
    "鍵が materials.yml へ実データ移行されている"
    + "(dungeon/gates.yml の key-item・カタログのレシピ・CMD台帳が全部切れる)");
});
