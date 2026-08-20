"use strict";

// 2026-08-04 実サーバ報告「あなた(AI)が作成したカテゴリをセレクトメニューで選ぶと
// グレー字でカテゴリ内部IDが表記され見づらい」の回帰テスト。
//
// 旧実装 (`renderEditorCategorySelect` の `secondary:` 判定) は id の**見た目**
// (`"cat_" + Date.now()` の純数値、または予約 `cat_auto_*`) でしか薄字表示を抑止して
// いなかった。そのため GUI の「+ カテゴリ」ボタンが払い出す id は隠れる一方、
// セッション(AI)が yml へ直接書く `cat_20260724_source_gem` のような説明的な id は
// 常に薄字で表示され続けていた。
//
// 修正方針は「id の命名規則」ではなく「ラベルの有用性」で判定すること:
//   - ラベルが空 → id を出す(出さないと選択肢が空欄になる)
//   - ラベルが同じセレクト内で他と重複 → 区別のため id を出す
//   - それ以外(ラベルが非空かつ一意) → id を隠す(命名規則を問わない)
// 判定は `window.computeCategorySecondaries` という純関数に切り出し、直接テストする。

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const ROOT = path.resolve(__dirname, "..");
const editorCategoriesSrc = fs.readFileSync(path.join(ROOT, "public", "js", "editor-categories.js"), "utf8");

/** editor-categories.js を最小 window へ読み込む (document は触らないので不要)。 */
function loadEditorCategories() {
  const win = {
    h: (tag, attrs, children) => {
      const el = { tag, attrs, children: [] };
      if (Array.isArray(children)) children.forEach((c) => c != null && el.children.push(c));
      else if (children != null) el.children.push(children);
      return el;
    },
    // renderEditorCategorySelect の統合テスト用: listSelect には cfg をそのまま返させ、
    // 渡された options.secondary を直接検証できるようにする。
    listSelect: (cfg) => cfg
  };
  new Function("window", "console", editorCategoriesSrc)(win, console);
  return win;
}

test("computeCategorySecondaries: ラベルが同じセレクト内で一意なら薄字(id)は出さない", () => {
  const win = loadEditorCategories();
  const cats = [
    { id: "cat_20260724_source_gem", label: "ソースジェム" },
    { id: "cat_abyss_binder_armor", label: "束縛者の装束" }
  ];
  assert.deepEqual(win.computeCategorySecondaries(cats), ["", ""]);
});

test("computeCategorySecondaries: ラベルが空なら id を出す(選択肢が空欄化するのを防ぐ)", () => {
  const win = loadEditorCategories();
  const cats = [
    { id: "cat_no_label" },
    { id: "cat_empty_label", label: "" }
  ];
  assert.deepEqual(win.computeCategorySecondaries(cats), ["cat_no_label", "cat_empty_label"]);
});

test("computeCategorySecondaries: ラベルが重複するときは両方に id を出して区別する", () => {
  const win = loadEditorCategories();
  const cats = [
    { id: "cat_alpha", label: "武器" },
    { id: "cat_beta", label: "武器" },
    { id: "cat_gamma", label: "防具" }
  ];
  assert.deepEqual(win.computeCategorySecondaries(cats), ["cat_alpha", "cat_beta", ""]);
});

test("computeCategorySecondaries: GUI採番(cat_<Date.now()>)でも命名規則に関わらずラベルだけで判定する", () => {
  const win = loadEditorCategories();
  // GUIの「+ カテゴリ」ボタンが払い出す純数値idと、AIがymlへ直接書く説明的なidが混在していても、
  // ラベルさえ一意なら等しく隠れることを固定する(命名規則で場合分けしない)。
  const cats = [
    { id: "cat_1784963631488", label: "手作りカテゴリ" },
    { id: "cat_20260724_source_gem", label: "ソースジェム" }
  ];
  assert.deepEqual(win.computeCategorySecondaries(cats), ["", ""]);
});

test("computeCategorySecondaries: 予約カテゴリ(cat_auto_*)もラベルが一意なら薄字なし", () => {
  const win = loadEditorCategories();
  const cats = [
    { id: "cat_auto_unclassified", label: "未分類" },
    { id: "cat_auto_draft", label: "準備中" }
  ];
  assert.deepEqual(win.computeCategorySecondaries(cats), ["", ""]);
});

test("renderEditorCategorySelect: 実データ相当(source_gem/abyss_binder_armor)で副表記が出ない", () => {
  const win = loadEditorCategories();
  const host = {
    items: { source_gem_1: {}, binder_helmet: {} },
    _editor: {
      categories: {
        other: [
          { id: "cat_20260724_source_gem", label: "ソースジェム", itemIds: ["source_gem_1"] },
          { id: "cat_abyss_binder_armor", label: "束縛者の装束", itemIds: ["binder_helmet"] }
        ]
      }
    }
  };
  const field = win.renderEditorCategorySelect(host, "other", "source_gem_1", () => {});
  // field = h("span", ..., [h("span", mini-label), picker]) で picker は listSelect スタブの戻り値(cfg)。
  const picker = field.children[1];
  const opt1 = picker.options.find((o) => o.value === "cat_20260724_source_gem");
  const opt2 = picker.options.find((o) => o.value === "cat_abyss_binder_armor");
  assert.ok(opt1, "ソースジェムの選択肢が見つからない");
  assert.ok(opt2, "束縛者の装束の選択肢が見つからない");
  assert.equal(opt1.secondary, "", "ラベルが一意なのに id が薄字表示されている(報告の再現)");
  assert.equal(opt2.secondary, "", "ラベルが一意なのに id が薄字表示されている(報告の再現)");
});

test("renderEditorCategorySelect: ラベルが重複するカテゴリでは id が薄字表示される", () => {
  const win = loadEditorCategories();
  const host = {
    items: { item_a: {}, item_b: {} },
    _editor: {
      categories: {
        other: [
          { id: "cat_dup_1", label: "重複ラベル", itemIds: ["item_a"] },
          { id: "cat_dup_2", label: "重複ラベル", itemIds: ["item_b"] }
        ]
      }
    }
  };
  const field = win.renderEditorCategorySelect(host, "other", "item_a", () => {});
  const picker = field.children[1];
  const opt1 = picker.options.find((o) => o.value === "cat_dup_1");
  const opt2 = picker.options.find((o) => o.value === "cat_dup_2");
  assert.equal(opt1.secondary, "cat_dup_1", "重複ラベルなのに id で区別できていない");
  assert.equal(opt2.secondary, "cat_dup_2", "重複ラベルなのに id で区別できていない");
});
