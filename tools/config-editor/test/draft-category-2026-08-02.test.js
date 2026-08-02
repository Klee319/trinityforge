"use strict";

// 「準備中」カテゴリ (2026-08-02)。
//
// 全タブに既定で1つだけ出る予約カテゴリで、ここへ入れた品は catalog.yml に `draft: true` が付く。
// サーバ側 (ItemCatalogConfig#load) は draft を template(id) / all() から落とすので、
// レシピ登録もドロップもガチャも実績報酬も一切走らない = ゲーム内に存在しない状態になる。
// エディタからは通常どおり編集・参照できるので「実際には出ないがドロップ表には先に書いておく」
// という早期仕込みができ、解禁はこのカテゴリから出すだけで済む。
//
// ここで固定するのは、その二重化の一方向性:
//   カテゴリ所属 (_editor) = 入力 / items[].draft = 出力
// `_editor` はゲームが読まないメタなので、サーバ側は items の draft しか見られない。
// 逆にカテゴリ側が無いとエディタで「準備中だけ一覧する」ができない。だから両方持つが、
// **移動のたびに一方向で同期する**と決めてある。逆流させると、どちらが正か分からなくなる。

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");

const ROOT = path.resolve(__dirname, "..");
const JS = (f) => fs.readFileSync(path.join(ROOT, "public", "js", f), "utf8");
const editorCategories = JS("editor-categories.js");

// ---- renderEditorCategoryBar が触る範囲だけを満たす最小フェイク要素 ----
// (catalog-category-host.test.js と同じ手法。selector は `.class` と `.class[attr]` の2形のみ)
function makeCatEl(tag, attrs) {
  const props = attrs || {};
  const classes = new Set(String(props.class || "").split(/\s+/).filter(Boolean));
  const attributes = {};
  const el = {
    tag,
    props,
    children: [],
    dataset: {},
    disabled: false,
    classList: {
      add: (c) => classes.add(c),
      remove: (...cs) => cs.forEach((c) => classes.delete(c)),
      contains: (c) => classes.has(c),
      toggle: (c, on) => (on === undefined ? (classes.has(c) ? classes.delete(c) : classes.add(c))
        : (on ? classes.add(c) : classes.delete(c)))
    },
    setAttribute: (k, v) => { attributes[k] = String(v); },
    getAttribute: (k) => (Object.prototype.hasOwnProperty.call(attributes, k) ? attributes[k] : null),
    removeAttribute: (k) => { delete attributes[k]; },
    addEventListener: () => {},
    appendChild(c) { if (c != null && c !== false) el.children.push(c); return c; },
    contains: (other) => descendants(el).includes(other),
    matches(selector) {
      const attrMatch = /\[([^\]=]+)\]$/.exec(selector);
      const attrName = attrMatch ? attrMatch[1] : null;
      const classPart = attrMatch ? selector.slice(0, attrMatch.index) : selector;
      for (const cls of classPart.split(".").filter(Boolean)) {
        if (!classes.has(cls)) return false;
      }
      return attrName ? attributes[attrName] != null : true;
    },
    querySelectorAll: (selector) => descendants(el).filter((d) => d.matches && d.matches(selector)),
    querySelector: (selector) => descendants(el).find((d) => d.matches && d.matches(selector)) || null
  };
  return el;
}

function descendants(el) {
  const out = [];
  for (const child of el.children || []) {
    out.push(child);
    out.push(...descendants(child));
  }
  return out;
}

function loadEditorCategories(opts) {
  const o = opts || {};
  const warnings = [];
  const prompts = o.prompts ? o.prompts.slice() : [];
  const alerts = [];
  const win = {
    h: (tag, attrs, children) => {
      const el = makeCatEl(tag, attrs);
      if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
      else if (children != null) el.appendChild(children);
      return el;
    },
    prompt: () => (prompts.length ? prompts.shift() : null),
    alert: (msg) => alerts.push(String(msg)),
    confirm: () => true
  };
  const consoleStub = { warn: (m) => warnings.push(String(m)) };
  new Function("window", "console", "prompt", "alert", "confirm", editorCategories)(
    win, consoleStub,
    (...a) => win.prompt(...a),
    (...a) => win.alert(...a),
    (...a) => win.confirm(...a)
  );
  return { win, warnings, alerts };
}

function tabIds(bar) {
  return bar.querySelectorAll(".recipe-tab").map((b) => b.getAttribute("data-cat-id"));
}

function hostWithOneCategory() {
  return {
    items: { sword_a: {}, sword_b: {} },
    _editor: {
      categories: {
        weapon: [{ id: "cat_swords", label: "剣", itemIds: ["sword_a"] }]
      }
    }
  };
}

// ============================================================
// (2) 「未設定」で絞り込み中の追加
// ============================================================


function hostWith(items) {
  return {
    items,
    _editor: { categories: { weapon: [{ id: "cat_swords", label: "剣", itemIds: Object.keys(items) }] } }
  };
}

test("「準備中」へ移すと items[].draft が立つ", () => {
  const { win } = loadEditorCategories();
  const host = hostWith({ abyss_sword: { material: "NETHERITE_SWORD" } });

  win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  win.moveItemEditorCategory(host, "weapon", "abyss_sword", "cat_auto_draft");

  assert.equal(host.items.abyss_sword.draft, true,
    "準備中に入れたのに draft が立っていない (サーバ側は _editor を読まないので、"
    + "これが無いと配線されたまま = ゲーム内で入手できてしまう)");
});

test("「準備中」から出すと draft キーごと消える (false を残さない)", () => {
  const { win } = loadEditorCategories();
  const host = hostWith({ abyss_sword: { material: "NETHERITE_SWORD", draft: true } });
  host._editor.categories.weapon.push({ id: "cat_auto_draft", label: "準備中", itemIds: ["abyss_sword"] });
  host._editor.categories.weapon[0].itemIds = [];

  win.moveItemEditorCategory(host, "weapon", "abyss_sword", "cat_swords");

  assert.ok(!("draft" in host.items.abyss_sword),
    "解禁したのに draft キーが残っている (draft: false を書き残すと yml が無駄に汚れ、"
    + "「準備中を外した」のか「最初から出荷済み」なのか読めなくなる)");
});

test("準備中以外への移動は他アイテムの draft を巻き込まない", () => {
  const { win } = loadEditorCategories();
  const host = hostWith({
    abyss_sword: { material: "NETHERITE_SWORD", draft: true },
    hero_sword: { material: "DIAMOND_SWORD" }
  });
  host._editor.categories.weapon.push({ id: "cat_auto_draft", label: "準備中", itemIds: ["abyss_sword"] });

  win.moveItemEditorCategory(host, "weapon", "hero_sword", "cat_swords");

  assert.equal(host.items.abyss_sword.draft, true, "無関係なアイテムの draft が落ちている");
  assert.ok(!("draft" in host.items.hero_sword), "準備中でないのに draft が付いている");
});

test("「準備中」はバー描画時に全タブへ既定で用意される (yml に無くても出る)", () => {
  const { win } = loadEditorCategories();
  for (const tab of ["weapon", "armor", "tool", "other", "catalyst", "spellbook", "thread"]) {
    const host = { items: {}, _editor: { categories: {} } };
    win.renderEditorCategoryBar(host, tab, () => {}, () => {});
    const ids = win.listEditorCategories(host, tab).map((c) => c.id);
    assert.ok(ids.includes("cat_auto_draft"), `${tab} タブに準備中が出ていない`);
  }
});

test("読んだだけではカテゴリが増えない (触っていない yml が保存で変わらない)", () => {
  const { win } = loadEditorCategories();
  const host = hostWith({ hero_sword: { material: "DIAMOND_SWORD" } });

  // listEditorCategories はデータ層の純粋な参照。ここで行を生やすと、開いて保存しただけで
  // yml にカテゴリが1つ増える (normalize の既定値ドリフトと同型の事故)。
  const before = win.listEditorCategories(host, "weapon").length;
  win.listEditorCategories(host, "weapon");
  assert.equal(win.listEditorCategories(host, "weapon").length, before,
    "参照しただけでカテゴリが増えている");
  assert.ok(!win.listEditorCategories(host, "weapon").some((c) => c.id === "cat_auto_draft"),
    "バーを描画していないのに準備中が生えている");
});
