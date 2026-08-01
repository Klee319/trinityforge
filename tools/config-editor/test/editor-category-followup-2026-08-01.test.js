"use strict";

// 2026-08-01 実サーバ報告の追跡 (前回 a42e55d の「追加した品が未分類へ入る」対応の**後始末**)。
//
// 前回、追加/複製/タブ移動を ensureItemEditorCategory 一本にまとめ「絞り込み中ならそのカテゴリ、
// 無ければ『未分類』」へ必ず入れる形にした。そのぶん次の4つが表面化した。
//
//  (2) 仮想タブ「未設定」で絞り込み中に追加すると、追加した品が**その場で画面から消える**。
//      「未設定」は『どのカテゴリにも属さない品』のビューなので、追加直後に「未分類」へ
//      入れられると絞り込み条件から外れる。追加した本人には「消えた」としか見えない。
//  (3) 「未分類」カテゴリが自動生成されるのは**フォーム側の追加ハンドラの中**。バーは
//      split-views の renderBar でしか作り直されないので、バーだけ古いまま
//      (タブに「未分類」が無いのにカードは「未分類」所属) になる。
//  (4) item-stats の「複製」だけが元アイテムのカテゴリを継承せず、
//      assignItemToActiveEditorCategory 経由で「未分類」へ落ちていた。
//  (6) 「未分類」の予約 id は `cat_auto_unclassified`。ところが id 無しカテゴリの救済
//      (derivedCategoryId) は label を slug 化して `cat_auto_<slug>` を作るので、
//      label が "unclassified" のカテゴリを手書きすると**予約 id をそのまま奪える**。
//      表示名でも、ユーザーが手で「未分類」を作ると同名タブが2つ並ぶ。

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

test("(2) 「未設定」で絞り込み中に追加した品は、そのまま「未設定」に見えたまま残る", () => {
  const { win } = loadEditorCategories();
  const host = hostWithOneCategory();
  const bar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  const unsetBtn = bar.querySelectorAll(".recipe-tab[data-cat-id]")
    .find((b) => b.getAttribute("data-cat-id") === "__unset__");
  assert.ok(unsetBtn, "「未設定」タブが無い");
  unsetBtn.props.onclick();
  assert.equal(win.activeEditorCategory(host, "weapon"), "__unset__");

  host.items.sword_new = {};
  const assigned = win.ensureItemEditorCategory(host, "weapon", "sword_new");

  assert.equal(assigned, "",
    "「未設定」で絞り込み中なのにカテゴリを割り当てている (割り当てた瞬間に絞り込みから外れて消える)");
  assert.equal(win.itemInEditorCategory(host, "weapon", "sword_new"), true,
    "追加直後に画面から消えている");
  assert.equal(win.getItemEditorCategory(host, "weapon", "sword_new"), "");
});

test("(2) 「すべて」表示のときは従来どおり「未分類」へ入る (前回の修正を戻さない)", () => {
  const { win } = loadEditorCategories();
  const host = hostWithOneCategory();
  host.items.sword_new = {};
  assert.equal(win.ensureItemEditorCategory(host, "weapon", "sword_new"), "cat_auto_unclassified");
});

test("(2) カテゴリで絞り込み中ならそのカテゴリへ入る (前回の修正を戻さない)", () => {
  const { win } = loadEditorCategories();
  const host = hostWithOneCategory();
  const bar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  bar.querySelectorAll(".editor-cat-tab")[0].props.onclick();
  host.items.sword_new = {};
  assert.equal(win.ensureItemEditorCategory(host, "weapon", "sword_new"), "cat_swords");
});

// ============================================================
// (3) 「未分類」自動生成後のタブバー追随
// ============================================================

test("(3) 「未分類」が自動生成されたらタブバーも作り直される (バーとカード一覧が食い違わない)", () => {
  const { win } = loadEditorCategories();
  const host = hostWithOneCategory();
  const bar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  assert.deepEqual(tabIds(bar), ["__all__", "__unset__", "cat_swords"]);

  // フォーム側の「追加」ハンドラが呼ぶ経路。バーの再構築はフォーム側からは呼ばれない。
  host.items.sword_new = {};
  win.ensureItemEditorCategory(host, "weapon", "sword_new");

  assert.deepEqual(tabIds(bar), ["__all__", "__unset__", "cat_swords", "cat_auto_unclassified"],
    "自動生成した「未分類」がタブバーに出ていない (バーだけ古いまま = 一覧と食い違う)");
});

test("(3) 同じ (host, tabKey) でバーを作り直したら、追随するのは最新のバーだけ", () => {
  const { win } = loadEditorCategories();
  const host = hostWithOneCategory();
  const oldBar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  const newBar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  host.items.sword_new = {};
  win.ensureItemEditorCategory(host, "weapon", "sword_new");
  assert.ok(tabIds(newBar).includes("cat_auto_unclassified"), "最新のバーが追随していない");
  assert.ok(!tabIds(oldBar).includes("cat_auto_unclassified"),
    "破棄済みのバーまで書き換えている (差し替え前の DOM を触るのは無駄で紛らわしい)");
});

// ============================================================
// (4) 複製はカテゴリを継承する
// ============================================================

test("(4) 複製は元アイテムのカテゴリを継承する", () => {
  const { win } = loadEditorCategories();
  const host = hostWithOneCategory();
  assert.equal(win.duplicateItemEditorCategory(host, "weapon", "sword_a", "sword_a_copy"), "cat_swords");
  assert.equal(win.getItemEditorCategory(host, "weapon", "sword_a_copy"), "cat_swords");
});

test("(4) 元アイテムが無所属なら、通常の追加と同じ扱い (「未分類」へ)", () => {
  const { win } = loadEditorCategories();
  const host = hostWithOneCategory();
  assert.equal(win.duplicateItemEditorCategory(host, "weapon", "sword_b", "sword_b_copy"),
    "cat_auto_unclassified");
});

test("(4) カテゴリを持つ画面の「複製」は全部 duplicateItemEditorCategory を通る", () => {
  // item-stats(forms.js) は assignItemToActiveEditorCategory を呼んでおり、
  // 絞り込みしていない状態では元カテゴリを捨てて「未分類」へ落ちていた。
  const offenders = [];
  for (const file of ["forms.js", "ars-forms.js"]) {
    const src = JS(file);
    const re = /text: "複製",\s*\n\s*onclick: \(\) => \{([\s\S]{0,1400}?)\n\s{8,10}\}\n/g;
    let m;
    while ((m = re.exec(src)) !== null) {
      const body = m[1];
      if (!/editorCategoryKey/.test(body)) continue; // カテゴリを持たない画面は対象外
      if (!/duplicateItemEditorCategory/.test(body)) {
        offenders.push(`${file}:${src.slice(0, m.index).split("\n").length}`);
      }
    }
  }
  assert.deepEqual(offenders, [],
    "複製ハンドラが duplicateItemEditorCategory を通っていない (継承漏れが再発する)");
});

// ============================================================
// (6) 「未分類」の予約 id / 同名カテゴリの衝突
// ============================================================

test("(6) label から導出した id が「未分類」の予約 id を奪わない", () => {
  const { win, warnings } = loadEditorCategories();
  const host = {
    items: { a: {} },
    _editor: {
      categories: {
        // 手書き: id 無し・label が "unclassified" → 旧実装は cat_auto_unclassified を生成した
        weapon: [{ label: "unclassified", itemIds: ["a"] }]
      }
    }
  };
  const cats = win.listEditorCategories(host, "weapon");
  assert.notEqual(cats[0].id, "cat_auto_unclassified",
    "ユーザーのカテゴリが「未分類」の予約 id を奪っている"
    + " (以後 ensureItemEditorCategory の受け皿がこのカテゴリに化ける)");
  assert.match(cats[0].id, /^cat_auto_/);
  assert.equal(warnings.length, 1);

  // 受け皿は別カテゴリとして作られ、ユーザーのカテゴリは汚染されない。
  host.items.b = {};
  assert.equal(win.ensureItemEditorCategory(host, "weapon", "b"), "cat_auto_unclassified");
  assert.deepEqual(cats[0].itemIds, ["a"]);
});

test("(6) 「未分類」という名前のカテゴリが既にあれば、それを使い同名タブを2つ作らない", () => {
  const { win } = loadEditorCategories();
  const host = {
    items: { a: {} },
    _editor: {
      categories: { weapon: [{ id: "cat_mine", label: "未分類", itemIds: [] }] }
    }
  };
  const assigned = win.ensureItemEditorCategory(host, "weapon", "a");
  assert.equal(assigned, "cat_mine", "同じ表示名のカテゴリを別に作っている (タブに「未分類」が2つ並ぶ)");
  assert.equal(win.listEditorCategories(host, "weapon").length, 1);
});

test("(6) 「+ カテゴリ」で既存カテゴリと同名は作れない (見分けが付かないタブを増やさない)", () => {
  const { win, alerts } = loadEditorCategories({ prompts: ["剣"] });
  const host = hostWithOneCategory();
  let structureChanges = 0;
  const bar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => { structureChanges++; });
  const addBtn = bar.querySelectorAll(".btn-small").find((b) => b.props.text === "+ カテゴリ");
  assert.ok(addBtn);
  addBtn.props.onclick();
  assert.equal(win.listEditorCategories(host, "weapon").length, 1, "同名カテゴリが増えている");
  assert.equal(structureChanges, 0);
  assert.equal(alerts.length, 1, "同名で作れないことを伝えていない (無言で握り潰さない)");
});

test("(6) 「+ カテゴリ」は予約 id をユーザーカテゴリに割り当てない", () => {
  const { win } = loadEditorCategories({ prompts: ["cat_auto_unclassified"] });
  const host = hostWithOneCategory();
  const bar = win.renderEditorCategoryBar(host, "weapon", () => {}, () => {});
  bar.querySelectorAll(".btn-small").find((b) => b.props.text === "+ カテゴリ").props.onclick();
  const created = win.listEditorCategories(host, "weapon").find((c) => c.label === "cat_auto_unclassified");
  assert.ok(created);
  assert.notEqual(created.id, "cat_auto_unclassified", "予約 id をそのまま割り当てている");
});

// ============================================================
// (5) mob-abilities-form.js の読み込み順依存
// ============================================================

test("(5) mob-abilities-form.js は読み込み時点で window.LABELS を読まない", () => {
  const src = JS("mob-abilities-form.js");
  let readsAtLoad = 0;
  const LABELS = {
    ENUM_LABELS: { "mob-ability-type": { charge: "突進 (charge)" } },
    enumLabel: (g, v) => (LABELS.ENUM_LABELS[g] || {})[v] || v
  };
  const win = {
    h: (tag, attrs) => makeCatEl(tag, attrs),
    get LABELS() { readsAtLoad++; return LABELS; }
  };
  new Function("window", src)(win);
  assert.equal(readsAtLoad, 0,
    "モジュール読み込み時に辞書を捕まえている。labels.js より先に読まれる並びになった瞬間、"
    + "警告もエラーも出ないまま生ID表示へ戻る");
});

test("(5) 型ラベルは描画のたびに引き直す (モジュールスコープの束縛を作らない)", () => {
  const raw = JS("mob-abilities-form.js");
  // 「こう書いてはいけない」と説明したコメント自体に当たらないよう、行コメントを外す。
  const src = raw.split("\n").filter((l) => !/^\s*(\/\/|\*|\/\*)/.test(l)).join("\n");
  assert.ok(!/const TYPE_LABELS\s*=/.test(src),
    "TYPE_LABELS をモジュールスコープの const で捕まえている (読み込み順に依存する)");
  assert.match(src, /function typeLabel\(/, "遅延解決する typeLabel() が無い");
  assert.ok(!/TYPE_LABELS\[/.test(src), "TYPE_LABELS[...] の参照が残っている");
});
