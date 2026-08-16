"use strict";

// ---------------------------------------------------------------------------
// 2026-08-16: `_editor.categories[*].itemIds` / `_editor.itemTabs` / `_editor.orders` の
// 「宙ぶらりん」検出(実在しないアイテムidへの参照)。
//
// 背景: エディタ経由の削除は `window.removeEditorCategoryItem` (public/js/editor-categories.js)
// が itemIds を掃除するが、**エディタを通さない手編集・改名・別ツールでの削除**は誰も検出できず
// 残り続ける。実測で ArsPaper fork の materials.yml に 19 件見つかった(うち17件は
// `cat_dungeon_keys`=「ダンジョンの鍵」カテゴリが catalog.yml の id を指す**意図的な設計**で、
// 誤検知として除外する必要がある。残り2件 `hoglin_tusk`(旧id)/`source_gem_block_3x` は本物の
// 宙ぶらりんで、fixture作業時に本体が既に削除済み)。
//
// 設計:
// - 検出のみ・自動修復はしない(黙って消すと id のタイプミスも黙って消えて気づけなくなるため)。
// - `danglingEditorMetaIds(data, itemIdSet)` は「そのファイル自身のアイテム集合」だけと
//   突き合わせない。呼び出し側が itemIdSet を組み立てる(自ファイル ∪ 必要なら他ファイル)。
// - lib/(サーバ側)と public/js/(ブラウザ側)は同じ定義のミラー2本。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const {
  danglingEditorMetaIds,
  describeDanglingEditorMetaId,
  editorMetaItemIdSet,
  computeEditorMetaWarnings
} = require("../lib/editor-meta-integrity");
const { validate } = require("../lib/schema");

// ---------------------------------------------------------------------------
// 1. 宙ぶらりん id を含む fixture で検出されること(検出件数と中身の両方を確認)
// ---------------------------------------------------------------------------

test("danglingEditorMetaIds: itemTabs の宙ぶらりんidを検出する", () => {
  const data = {
    items: { real_sword: { material: "IRON_SWORD" } },
    _editor: { itemTabs: { real_sword: "weapon", ghost_axe: "weapon" } }
  };
  const result = danglingEditorMetaIds(data, new Set(["real_sword"]));
  assert.equal(result.length, 1, `検出件数がおかしい: ${JSON.stringify(result)}`);
  assert.equal(result[0].id, "ghost_axe");
  assert.equal(result[0].kind, "itemTabs");
  assert.equal(result[0].where, "_editor.itemTabs.ghost_axe");
});

test("danglingEditorMetaIds: categories[*].itemIds の宙ぶらりんidを検出する(実在idは巻き込まない)", () => {
  const data = {
    items: { real_sword: {} },
    _editor: {
      categories: {
        weapon: [
          { id: "cat_1", label: "剣", itemIds: ["real_sword", "ghost_axe", "hoglin_tusk"] }
        ]
      }
    }
  };
  const result = danglingEditorMetaIds(data, new Set(["real_sword"]));
  assert.equal(result.length, 2, `検出件数がおかしい: ${JSON.stringify(result)}`);
  const ids = result.map((r) => r.id).sort();
  assert.deepEqual(ids, ["ghost_axe", "hoglin_tusk"]);
  for (const r of result) {
    assert.equal(r.kind, "categories");
    assert.equal(r.where, "_editor.categories.weapon[剣].itemIds");
  }
});

test("danglingEditorMetaIds: orders[tabKey] の宙ぶらりんidを検出する", () => {
  const data = {
    items: { real_sword: {} },
    _editor: { orders: { weapon: ["real_sword", "ghost_axe"] } }
  };
  const result = danglingEditorMetaIds(data, new Set(["real_sword"]));
  assert.equal(result.length, 1, `検出件数がおかしい: ${JSON.stringify(result)}`);
  assert.equal(result[0].id, "ghost_axe");
  assert.equal(result[0].kind, "orders");
  assert.equal(result[0].where, "_editor.orders.weapon");
});

test("danglingEditorMetaIds: itemTabs/categories/orders 全部に宙ぶらりんがある混在fixtureで合計件数と中身が一致する", () => {
  const data = {
    items: { real_a: {}, real_b: {} },
    _editor: {
      itemTabs: { real_a: "weapon", ghost_1: "weapon" },
      categories: {
        weapon: [{ id: "cat_1", label: "剣", itemIds: ["real_b", "ghost_2"] }]
      },
      orders: { weapon: ["real_a", "ghost_3"] }
    }
  };
  const idSet = new Set(["real_a", "real_b"]);
  const result = danglingEditorMetaIds(data, idSet);
  assert.equal(result.length, 3, `検出件数がおかしい: ${JSON.stringify(result)}`);
  const byKind = { itemTabs: [], categories: [], orders: [] };
  for (const r of result) byKind[r.kind].push(r.id);
  assert.deepEqual(byKind, { itemTabs: ["ghost_1"], categories: ["ghost_2"], orders: ["ghost_3"] });
});

// ---------------------------------------------------------------------------
// 2. 全部実在する fixture で 0 件になること
// ---------------------------------------------------------------------------

test("danglingEditorMetaIds: 全idが実在する fixture は0件", () => {
  const data = {
    items: { a: {}, b: {} },
    _editor: {
      itemTabs: { a: "weapon", b: "armor" },
      categories: { weapon: [{ id: "cat_1", label: "剣", itemIds: ["a"] }] },
      orders: { weapon: ["a", "b"] }
    }
  };
  const result = danglingEditorMetaIds(data, new Set(["a", "b"]));
  assert.deepEqual(result, []);
});

test("danglingEditorMetaIds: _editor 自体が無い/空でも0件(後方互換、例外にもならない)", () => {
  assert.deepEqual(danglingEditorMetaIds({ items: {} }, new Set()), []);
  assert.deepEqual(danglingEditorMetaIds({ items: {}, _editor: {} }, new Set()), []);
});

// ---------------------------------------------------------------------------
// 3. itemIds が未定義/空配列/配列でない場合に落ちない
// ---------------------------------------------------------------------------

test("danglingEditorMetaIds: itemIds が未定義/空配列/配列でなくても例外にならない", () => {
  const cases = [
    { categories: { weapon: [{ id: "cat_1", label: "空" }] } }, // itemIds 未定義
    { categories: { weapon: [{ id: "cat_1", label: "空配列", itemIds: [] }] } },
    { categories: { weapon: [{ id: "cat_1", label: "非配列", itemIds: "not-an-array" }] } },
    { categories: { weapon: "not-an-array" } }, // カテゴリ配列自体が壊れている
    { categories: null },
    { itemTabs: null },
    { orders: { weapon: "not-an-array" } },
    { orders: null }
  ];
  for (const editorMeta of cases) {
    assert.doesNotThrow(() => {
      const result = danglingEditorMetaIds({ items: {}, _editor: editorMeta }, new Set());
      assert.ok(Array.isArray(result));
    }, `落ちてはいけない fixture: ${JSON.stringify(editorMeta)}`);
  }
});

test("danglingEditorMetaIds: data 自体が null/非オブジェクト/配列でも例外にならず空配列", () => {
  for (const bad of [null, undefined, "string", 42, []]) {
    assert.deepEqual(danglingEditorMetaIds(bad, new Set(["a"])), []);
  }
});

test("danglingEditorMetaIds: itemIdSet を渡さない/配列で渡しても動く(Setへの正規化)", () => {
  const data = { items: {}, _editor: { itemTabs: { a: "weapon" } } };
  assert.equal(danglingEditorMetaIds(data, undefined).length, 1, "itemIdSet省略時は空集合扱いで全部宙ぶらりん扱いになるはず");
  assert.deepEqual(danglingEditorMetaIds(data, ["a"]), [], "配列で渡しても Set 相当に扱われる");
});

// ---------------------------------------------------------------------------
// 4. 保存がブロックされないこと(挙動で固定): 同じ宙ぶらりんfixtureを schema.validate() に
//    通してもエラーにならない。computeEditorMetaWarnings も文字列の配列を返すだけで例外にしない
//    (server.js はこれを cmdWarnings と同じ「非ブロッキング」応答フィールドとして返す)。
// ---------------------------------------------------------------------------

test("保存はブロックされない: 宙ぶらりんを含む catalog データでも validate(\"catalog\", ...) は空配列(エラーなし)", () => {
  const data = {
    items: { real_sword: { material: "IRON_SWORD" } },
    _editor: { itemTabs: { real_sword: "weapon", ghost_axe: "weapon" } }
  };
  // 前提: このfixtureは実際に宙ぶらりんとして検出される(=検査対象として機能している)。
  const dangling = danglingEditorMetaIds(data, new Set(Object.keys(data.items)));
  assert.equal(dangling.length, 1, "前提が崩れている: このfixtureは宙ぶらりんを含むはず");

  const errors = validate("catalog", data);
  assert.deepEqual(errors, [], "宙ぶらりんメタ検査が schema.js の errors(保存ブロック)へ紛れ込んでいる");
});

test("computeEditorMetaWarnings: 宙ぶらりんがあっても例外を投げず、日本語の警告文字列配列を返す(保存を止める形にしない)", () => {
  const ctx = { readEntryById: () => null };
  const data = {
    items: { real_sword: {} },
    _editor: { itemTabs: { real_sword: "weapon", ghost_axe: "weapon" } }
  };
  let warnings;
  assert.doesNotThrow(() => { warnings = computeEditorMetaWarnings(ctx, "catalog", data); });
  assert.equal(warnings.length, 1);
  assert.equal(typeof warnings[0], "string");
  assert.ok(warnings[0].includes("ghost_axe"), "警告文にidが含まれていない");
  assert.ok(warnings[0].includes("実在しません") || warnings[0].includes("存在しません"),
    "「なぜ問題か」の説明が警告文に無い");
});

test("computeEditorMetaWarnings: 未対応のconfigId(threads等)は空配列を返し何もしない", () => {
  const ctx = { readEntryById: () => null };
  const data = { effects: {}, _editor: { itemTabs: { ghost: "x" } } };
  assert.deepEqual(computeEditorMetaWarnings(ctx, "threads", data), []);
});

// ---------------------------------------------------------------------------
// 5. lib/ と public/js/ のミラーが同じ結果を返すこと
// ---------------------------------------------------------------------------

test("ミラー一致: public/js/editor-meta-integrity.js の window.danglingEditorMetaIds は lib版と同じ結果を返す", () => {
  global.window = global.window || {};
  delete require.cache[require.resolve("../public/js/editor-meta-integrity.js")];
  require("../public/js/editor-meta-integrity.js");
  const browserFn = global.window.danglingEditorMetaIds;
  assert.equal(typeof browserFn, "function", "window.danglingEditorMetaIds が公開されていない");

  const fixtures = [
    { items: { a: {} }, _editor: { itemTabs: { a: "weapon", ghost: "weapon" } } },
    {
      items: { a: {} },
      _editor: { categories: { weapon: [{ id: "c1", label: "剣", itemIds: ["a", "ghost1", "ghost2"] }] } }
    },
    { items: {}, _editor: { orders: { weapon: ["ghost"] } } },
    { items: { a: {} }, _editor: {} },
    {}
  ];
  const idSets = [new Set(["a"]), new Set(["a"]), new Set(), new Set(["a"]), new Set()];

  for (let i = 0; i < fixtures.length; i++) {
    const libResult = danglingEditorMetaIds(fixtures[i], idSets[i]);
    const browserResult = browserFn(fixtures[i], idSets[i]);
    assert.deepEqual(browserResult, libResult, `fixture[${i}] で lib/public 結果が食い違う`);
  }
});

test("ミラー一致: describeDanglingEditorMetaId も lib/public で同じ文字列を返す", () => {
  global.window = global.window || {};
  delete require.cache[require.resolve("../public/js/editor-meta-integrity.js")];
  require("../public/js/editor-meta-integrity.js");
  const browserDescribe = global.window.describeDanglingEditorMetaId;
  assert.equal(typeof browserDescribe, "function");

  const entries = [
    { where: "_editor.itemTabs.ghost", id: "ghost", kind: "itemTabs" },
    { where: "_editor.categories.weapon[剣].itemIds", id: "ghost2", kind: "categories" },
    { where: "_editor.orders.weapon", id: "ghost3", kind: "orders" }
  ];
  for (const entry of entries) {
    assert.equal(browserDescribe(entry), describeDanglingEditorMetaId(entry));
  }
});

// ---------------------------------------------------------------------------
// 6. cat_dungeon_keys のような「他ファイルを意図的に参照するカテゴリ」を誤検知しないこと
// ---------------------------------------------------------------------------

test("editorMetaItemIdSet: materials は 自ファイルの materials ∪ catalog.yml の items を有効集合にする", () => {
  const ctx = {
    readEntryById: (id) => (id === "catalog" ? { items: { key_mines: {}, key_binder: {} } } : null)
  };
  const materialsData = { materials: { source_gem: {} } };
  const idSet = editorMetaItemIdSet(ctx, "materials", materialsData);
  assert.ok(idSet instanceof Set);
  assert.ok(idSet.has("source_gem"), "自ファイルのmaterialsキーが集合に含まれていない");
  assert.ok(idSet.has("key_mines"), "catalog.ymlの鍵idが集合に含まれていない(cat_dungeon_keysの誤検知を招く)");
  assert.ok(idSet.has("key_binder"));
});

test("誤検知しないこと: cat_dungeon_keys が catalog.yml の鍵idを指していても宙ぶらりんとして検出しない"
  + "(一方、どちらにも無い本物の宙ぶらりんidは検出する)", () => {
  const ctx = {
    readEntryById: (id) => (id === "catalog" ? { items: { key_mines: {}, key_binder: {} } } : null)
  };
  const materialsData = {
    materials: { source_gem: {} },
    _editor: {
      categories: {
        material: [
          { id: "cat_dungeon_keys", label: "ダンジョンの鍵", itemIds: ["key_mines", "key_binder"] },
          // 本物の宙ぶらりん(旧id。materials にもcatalogにも存在しない)。
          { id: "cat_drop", label: "ドロップ素材", itemIds: ["source_gem", "hoglin_tusk"] }
        ]
      }
    }
  };
  const idSet = editorMetaItemIdSet(ctx, "materials", materialsData);
  const dangling = danglingEditorMetaIds(materialsData, idSet);
  assert.equal(dangling.length, 1, `cat_dungeon_keysが誤検知されている、または本物の宙ぶらりんを見逃している: ${JSON.stringify(dangling)}`);
  assert.equal(dangling[0].id, "hoglin_tusk");
});

test("editorMetaItemIdSet: catalog.yml が読めない(counterpart未取得)場合は自ファイルのmaterialsキーだけを有効集合にする"
  + "(誤検知を増やす方向にはしない = 鍵カテゴリは検出されうるが、無いものを検出しない側には倒さない)", () => {
  const ctx = { readEntryById: () => null };
  const idSet = editorMetaItemIdSet(ctx, "materials", { materials: { source_gem: {} } });
  assert.deepEqual([...idSet], ["source_gem"]);
});

test("editorMetaItemIdSet: catalog/item-stats は自ファイルの items キーのみ(他ファイル参照なし)", () => {
  const ctx = { readEntryById: () => { throw new Error("呼ばれてはいけない"); } };
  assert.deepEqual([...editorMetaItemIdSet(ctx, "catalog", { items: { a: {}, b: {} } })].sort(), ["a", "b"]);
  assert.deepEqual([...editorMetaItemIdSet(ctx, "item-stats", { items: { "IRON_SWORD#1": {} } })], ["IRON_SWORD#1"]);
});

test("editorMetaItemIdSet: 未対応のconfigIdはnullを返す(呼び出し側はそのconfigの検査をスキップする)", () => {
  const ctx = { readEntryById: () => null };
  assert.equal(editorMetaItemIdSet(ctx, "threads", { effects: {} }), null);
  assert.equal(editorMetaItemIdSet(ctx, "spellbooks", { "spell-books": [] }), null);
});

// ---------------------------------------------------------------------------
// 7. 実データ: 出荷 catalog.yml / (あれば)ArsPaper fork の materials.yml で実測する。
//    materials.yml は .gitignore 除外でクリーンクローンには存在しないため、無い環境では
//    テストを失敗させずスキップする。
// ---------------------------------------------------------------------------

const ROOT = path.resolve(__dirname, "..");
const CATALOG_PATH = path.resolve(ROOT, "../../TrinityForge/src/main/resources/items/catalog.yml");
const MATERIALS_PATH = path.resolve(ROOT, "../../fork-handoff/arspaper/fork/src/main/resources/materials.yml");

function loadYaml(p) {
  return YAML.parse(fs.readFileSync(p, "utf8"));
}

// 実測は `_editor.categories[*].itemIds` だけを厳密比較する(コーディネータが事前に
// 実測・裏取り済みの値: catalog.yml=0件, materials.yml=17件(全てcat_dungeon_keys、
// union後は0件)と直接対応する範囲に絞る)。`itemTabs`/`orders` は今回のタスクで新たに
// 検査対象へ加えた範囲で、出荷データ全体に対する実測値が無いため、ここで断定的に0件を
// 主張すると「実装のバグ」と「未検証の既存データの積年の孤児」の区別がつかなくなる
// (このテストは新機能の回帰テストであって出荷データの全数監査ではない)。
// `itemTabs`/`orders` の検出ロジック自体は上の fixture テストで個別に固定済み。
function onlyCategories(dangling) {
  return dangling.filter((d) => d.kind === "categories");
}

test("実データ: 出荷 catalog.yml 自身は categories[*].itemIds に宙ぶらりんが無い", (t) => {
  if (!fs.existsSync(CATALOG_PATH)) {
    t.skip("catalog.yml が見つからない環境ではスキップ");
    return;
  }
  const data = loadYaml(CATALOG_PATH);
  const idSet = new Set(Object.keys(data.items || {}));
  const dangling = onlyCategories(danglingEditorMetaIds(data, idSet));
  assert.deepEqual(dangling, [], `catalog.yml 自身に宙ぶらりんの categories 参照がある: ${JSON.stringify(dangling)}`);
});

test("実データ: ArsPaper fork の materials.yml は catalog.yml との union で categories[*].itemIds に宙ぶらりんが無い"
  + "(cat_dungeon_keys の17件は誤検知しないこと)", (t) => {
  if (!fs.existsSync(MATERIALS_PATH) || !fs.existsSync(CATALOG_PATH)) {
    t.skip("フォーク未取得(.gitignore除外)の環境ではスキップ");
    return;
  }
  const materialsData = loadYaml(MATERIALS_PATH);
  const catalogData = loadYaml(CATALOG_PATH);
  const ctx = { readEntryById: (id) => (id === "catalog" ? catalogData : null) };
  const idSet = editorMetaItemIdSet(ctx, "materials", materialsData);
  const dangling = onlyCategories(danglingEditorMetaIds(materialsData, idSet));
  assert.deepEqual(dangling, [], `materials.yml に宙ぶらりんが残っている: ${JSON.stringify(dangling)}`);
});
