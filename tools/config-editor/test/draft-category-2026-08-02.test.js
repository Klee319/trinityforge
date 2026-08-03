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
    // 【2026-08-02 指摘4】catalog.yml の画面であることを明示するオプトインが無いと
    // 「準備中」は用意されない(下の「他ファイルの画面には出ない」テストと対になる)。
    win.renderEditorCategoryBar(host, tab, () => {}, () => {}, { includeDraftCategory: true });
    const ids = win.listEditorCategories(host, tab).map((c) => c.id);
    assert.ok(ids.includes("cat_auto_draft"), `${tab} タブに準備中が出ていない`);
  }
});

// ============================================================
// 【2026-08-02 指摘4】draft: true は catalog.yml(ItemCatalogConfig#load)でしか実効を持たない。
// materials.yml / threads.yml / spellbooks.yml のような他ファイルの画面で「準備中」を出すと、
// そこへ入れても意味が無い(materials/threadsは無反応、item-stats.yml では誰も読まない
// draft:true ゴーストキーが実際に書かれる)。opts.includeDraftCategory を渡さない画面
// (= catalog.yml 以外の split view)では「準備中」を一切生やさないことを固定する。
// ============================================================

test("includeDraftCategory を渡さない画面には「準備中」が出ない (materials/item-stats 等)", () => {
  const { win } = loadEditorCategories();
  const host = { items: {}, _editor: { categories: {} } };
  win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  const ids = win.listEditorCategories(host, "weapon").map((c) => c.id);
  assert.ok(!ids.includes("cat_auto_draft"),
    "draft が実効を持たない画面にまで「準備中」カテゴリが生やされている"
    + "(item-stats.yml では draft: true のゴーストキーとして実際に書き込まれる)");
});

test("includeDraftCategory: false を明示しても「準備中」は出ない", () => {
  const { win } = loadEditorCategories();
  const host = { items: {}, _editor: { categories: {} } };
  win.renderEditorCategoryBar(host, "weapon", () => {}, () => {}, { includeDraftCategory: false });
  const ids = win.listEditorCategories(host, "weapon").map((c) => c.id);
  assert.ok(!ids.includes("cat_auto_draft"));
});

// ============================================================
// 【2026-08-02 指摘1 CRITICAL】表示タブの移動 (moveItemDisplayTab) は draft: true を変えてはならない。
//
// 失敗シナリオ: ダンジョンの鍵を「準備中」に入れる(draft: true, ガチャにも出ずクラフト不可) →
// 運用者がそれを「鍵」タブへピン留めする → 旧実装は moveItemDisplayTab 内部の
// removeEditorCategoryItem/ensureItemEditorCategory が無条件で syncDraftFlag(false) を呼び、
// draft が消える → 次の保存で鍵がゲームに出る(レシピ登録・ガチャ抽選対象)。
// ============================================================

test("【CRITICAL】表示タブを移しても draft: true は残る(準備中の品を他タブへピン留めしても解禁されない)", () => {
  const { win } = loadEditorCategories();
  const host = {
    items: { key_mines: { material: "TRIAL_KEY", draft: true } },
    _editor: {
      categories: {
        other: [{ id: "cat_auto_draft", label: "準備中", itemIds: ["key_mines"] }]
      },
      itemTabs: { key_mines: "other" }
    }
  };

  win.moveItemDisplayTab(host, "key_mines", "key", ["other", "key"]);

  assert.equal(host.items.key_mines.draft, true,
    "表示タブを移しただけで draft が外れている(次の保存でゲームに出てしまう)");
  assert.equal(win.getItemDisplayTab(host, "key_mines"), "key",
    "表示タブ自体は正しく移っている前提が崩れている");
});

test("表示タブの移動元でも draft でなかった品は、移動先でも draft にならない", () => {
  const { win } = loadEditorCategories();
  const host = {
    items: { hero_sword: { material: "DIAMOND_SWORD" } },
    _editor: {
      categories: { weapon: [{ id: "cat_swords", label: "剣", itemIds: ["hero_sword"] }] },
      itemTabs: { hero_sword: "weapon" }
    }
  };

  win.moveItemDisplayTab(host, "hero_sword", "other", ["weapon", "other"]);

  assert.ok(!("draft" in host.items.hero_sword),
    "移動しただけで draft: false が書かれている(無関係なキー汚染)");
});

// ============================================================
// 【2026-08-02 指摘2】予約カテゴリ(未分類/準備中)は削除・改名できてはいけない。
// ============================================================

test("「準備中」カテゴリは削除できない(削除すると draft メンバーが孤児化する)", () => {
  const { win, alerts } = loadEditorCategories();
  const host = hostWith({ key_mines: { material: "TRIAL_KEY", draft: true } });
  host._editor.categories.weapon.push({ id: "cat_auto_draft", label: "準備中", itemIds: ["key_mines"] });

  const bar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {}, { includeDraftCategory: true });
  const draftBtn = bar.querySelectorAll(".recipe-tab[data-cat-id]")
    .find((b) => b.getAttribute("data-cat-id") === "cat_auto_draft");
  assert.ok(draftBtn, "準備中タブが無い");
  draftBtn.props.onclick();

  const deleteBtn = bar.querySelectorAll(".btn-small").find((b) => b.props.text === "カテゴリ削除");
  assert.ok(deleteBtn);
  deleteBtn.props.onclick();

  assert.ok(win.listEditorCategories(host, "weapon").some((c) => c.id === "cat_auto_draft"),
    "予約カテゴリ「準備中」が削除できてしまった");
  assert.ok(alerts.length >= 1, "削除できない理由を伝えていない");
});

test("「準備中」カテゴリは改名できない(id は cat_auto_draft のままラベルだけ変わる事故を防ぐ)", () => {
  const { win, alerts } = loadEditorCategories({ prompts: ["強化予定"] });
  const host = hostWith({});
  host._editor.categories.weapon.push({ id: "cat_auto_draft", label: "準備中", itemIds: [] });

  const bar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {}, { includeDraftCategory: true });
  const draftBtn = bar.querySelectorAll(".recipe-tab[data-cat-id]")
    .find((b) => b.getAttribute("data-cat-id") === "cat_auto_draft");
  draftBtn.props.onclick();

  const renameBtn = bar.querySelectorAll(".btn-small").find((b) => b.props.text === "カテゴリ名変更");
  assert.ok(renameBtn);
  renameBtn.props.onclick();

  const draft = win.listEditorCategories(host, "weapon").find((c) => c.id === "cat_auto_draft");
  assert.equal(draft.label, "準備中",
    "予約カテゴリのラベルが書き換わっている(id は cat_auto_draft のまま「強化予定」等に化ける)");
  assert.ok(alerts.length >= 1, "改名できない理由を伝えていない");
});

test("「未分類」カテゴリも予約カテゴリとして削除・改名できない", () => {
  const { win } = loadEditorCategories();
  const host = hostWith({});
  host._editor.categories.weapon.push({ id: "cat_auto_unclassified", label: "未分類", itemIds: [] });

  const bar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  const unclassBtn = bar.querySelectorAll(".recipe-tab[data-cat-id]")
    .find((b) => b.getAttribute("data-cat-id") === "cat_auto_unclassified");
  unclassBtn.props.onclick();

  bar.querySelectorAll(".btn-small").find((b) => b.props.text === "カテゴリ削除").props.onclick();
  assert.ok(win.listEditorCategories(host, "weapon").some((c) => c.id === "cat_auto_unclassified"),
    "予約カテゴリ「未分類」が削除できてしまった");
});

// ============================================================
// 【2026-08-02 指摘3】手書きの「準備中」カテゴリを予約 id へ昇格させるとき、
// 既存メンバーにも draft: true を付ける (id だけ書き換えて中身は配線されたままにしない)。
// ============================================================

test("手書きの「準備中」カテゴリを昇格させると、既存メンバーにも draft: true が付く", () => {
  const { win } = loadEditorCategories();
  const host = {
    items: {
      old_stock_a: { material: "IRON_SWORD" },
      old_stock_b: { material: "GOLDEN_SWORD" }
    },
    _editor: {
      // id 無し・手書きの「準備中」。ensureDraftCategory がラベル一致で昇格させる対象。
      categories: { weapon: [{ label: "準備中", itemIds: ["old_stock_a", "old_stock_b"] }] }
    }
  };

  win.renderEditorCategoryBar(host, "weapon", () => {}, () => {}, { includeDraftCategory: true });

  assert.equal(host.items.old_stock_a.draft, true,
    "昇格前から準備中に入っていた品に draft: true が付いていない"
    + "(タブは「準備中」と表示されるのに中身は配線されたままになる)");
  assert.equal(host.items.old_stock_b.draft, true);
  const cat = win.listEditorCategories(host, "weapon").find((c) => c.label === "準備中");
  assert.equal(cat.id, "cat_auto_draft", "予約 id へ昇格していない");
});

test("手書きの「準備中」の昇格は、既に draft の付いていない別カテゴリのメンバーを巻き込まない", () => {
  const { win } = loadEditorCategories();
  const host = {
    items: {
      old_stock: { material: "IRON_SWORD" },
      other_item: { material: "DIAMOND" }
    },
    _editor: {
      categories: {
        weapon: [
          { label: "準備中", itemIds: ["old_stock"] },
          { id: "cat_other", label: "その他", itemIds: ["other_item"] }
        ]
      }
    }
  };

  win.renderEditorCategoryBar(host, "weapon", () => {}, () => {}, { includeDraftCategory: true });

  assert.equal(host.items.old_stock.draft, true);
  assert.ok(!("draft" in host.items.other_item), "無関係なカテゴリのメンバーまで draft が付いている");
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
