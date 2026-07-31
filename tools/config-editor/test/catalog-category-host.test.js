"use strict";

// 2026-07-28 実サーバ報告「アイテムカタログで設定したアイテムカテゴリに切り替えてもソートされない」の回帰テスト。
//
// 真因: カテゴリの選択状態は `activeByHost` (WeakMap、キーはYAMLルートの**オブジェクト同一性**) に
// 持つ。ところが catalog 画面は TF 特殊アイテム2件を隠すために `{ ...data, items: clone }` の
// 浅いクローンをフォームへ渡しており、カテゴリバーには元の `data` を渡していた。
// → バーは data に選択を書き、フォームはクローンから読むので常に「すべて」になり、絞り込みが効かない。
// catalog.yml には skill_node_lock / skill_tree_reset が実在するので、この分岐は毎回通る。

const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const assert = require("node:assert/strict");
const YAML = require("yaml");

const ROOT = path.resolve(__dirname, "..");
const REPO = path.resolve(ROOT, "..", "..");
const splitViews = fs.readFileSync(path.join(ROOT, "public", "js", "split-views.js"), "utf8");
const editorCategories = fs.readFileSync(path.join(ROOT, "public", "js", "editor-categories.js"), "utf8");

test("カテゴリバーにはフォームが実際に読むのと同一の host を渡す", () => {
  // クローンを作る分岐で categoryHost も必ず差し替えること。
  assert.match(
    splitViews,
    /catalogViewData = \{ \.\.\.data, items: itemsClone \};[\s\S]{0,300}?categoryHost = catalogViewData;/
  );
  assert.match(splitViews, /const host = categoryHost;/);
  // `const host = data;` に戻すと、クローン経路のある画面で選択状態が届かなくなる。
  assert.ok(!/const host = data;/.test(splitViews), "host を data 直渡しに戻してはいけない");
});

test("選択状態はオブジェクト同一性で引くので、クローンでは共有されない", () => {
  // この前提が崩れた(例: _editor 内に選択を保存する設計へ変えた)なら上のテストは不要になる。
  assert.match(editorCategories, /const activeByHost = new WeakMap\(\);/);
  assert.match(editorCategories, /activeByHost\.get\(host\)/);
});

test("フォーム側は絞り込みと並び替えの両方を active カテゴリで行う", () => {
  const forms = fs.readFileSync(path.join(ROOT, "public", "js", "forms.js"), "utf8");
  assert.match(forms, /function filterAndSortIds\(ids\)[\s\S]{0,400}?window\.itemInEditorCategory\(working, editorCategoryKey, id\)/);
  assert.match(forms, /window\.sortIdsByEditorOrder\(working, editorCategoryKey, result\)/);
});

// ============================================================
// 2026-07-31 報告「追加した素材のカテゴリ分けができていない」の回帰テスト。
//
// 真因: 手書きで materials.yml へ足した3カテゴリに `id:` が無かった。id はカテゴリの
// 唯一の同定子で、label は表示専用。id が欠けると (a) タブを押しても絞り込まれない、
// (b) カードのカテゴリ欄が (未設定) になる、(c) 所属済みの品が「未設定」タブに並ぶ、という
// 3つの症状が**エラーを出さずに**同時に出る。読み口 (listCategories) で id を補い、
// 併せて警告を出す = 無言で落とすのをやめる。
// ============================================================

// renderEditorCategoryBar は setAttribute / classList / querySelectorAll を使うので、
// h() のスタブもそこまでは満たす必要がある (selector は実際に使われる `.class` と
// `.class[attr]` の2形だけ解釈できればよい)。
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

/** editor-categories.js を最小 window へ読み込む (document は触らないので不要)。 */
function loadEditorCategories() {
  const captured = { warnings: [] };
  const win = {
    h: (tag, attrs, children) => {
      const el = makeCatEl(tag, attrs);
      if (Array.isArray(children)) children.forEach((c) => c != null && el.appendChild(c));
      else if (children != null) el.appendChild(children);
      return el;
    }
  };
  const consoleStub = { warn: (msg) => captured.warnings.push(String(msg)) };
  new Function("window", "console", editorCategories)(win, consoleStub);
  return { win, captured };
}

function hostWithIdlessCategory() {
  return {
    materials: { dungeon_seal_mines: {}, dragon_scale: {} },
    _editor: {
      categories: {
        material: [
          { id: "cat_20260723_drop", label: "ドロップ素材", itemIds: ["dragon_scale"] },
          // 手書き追加: id が無い (2026-07-31 に実際に起きた形)
          { label: "ダンジョンの印", itemIds: ["dungeon_seal_mines"] }
        ]
      }
    }
  };
}

test("id を持たないカテゴリは無言で落とさず、label 由来の安定 id を補う", () => {
  const { win, captured } = loadEditorCategories();
  const host = hostWithIdlessCategory();
  const cats = win.listEditorCategories(host, "material");

  const seal = cats.find((c) => c.label === "ダンジョンの印");
  assert.ok(seal.id, "id が補われていない (undefined のままだと絞り込みが全て無効になる)");
  assert.match(seal.id, /^cat_auto_/, "補った id は cat_auto_ 接頭辞で見分けられるようにする");
  assert.equal(cats.find((c) => c.label === "ドロップ素材").id, "cat_20260723_drop",
    "既存の id を書き換えてはいけない");

  // 無言で落とさないこと = 補ったら必ず警告が出ること。
  assert.equal(captured.warnings.length, 1, "id 欠落の警告が出ていない");
  assert.match(captured.warnings[0], /ダンジョンの印/);
  assert.match(captured.warnings[0], /id:/);
});

test("id を補ったカテゴリで絞り込み・カテゴリ表示・未設定判定が正しく動く", () => {
  const { win } = loadEditorCategories();
  const host = hostWithIdlessCategory();
  const sealId = win.listEditorCategories(host, "material").find((c) => c.label === "ダンジョンの印").id;

  // (b) カードの「カテゴリ」欄
  assert.equal(win.getItemEditorCategory(host, "material", "dungeon_seal_mines"), sealId);
  // (c) 所属済みなので「未設定」タブには入らない
  assert.equal(win.itemInEditorCategory(host, "material", "dungeon_seal_mines"), true,
    "「すべて」では当然通る");

  // (a) タブを押した状態を再現して絞り込みが効くこと。renderEditorCategoryBar は
  //     DOM 依存が重いので、バーが書くのと同じ経路 (assignItemToActiveEditorCategory が
  //     読む activeMap) を通す代わりに、公開 API だけで確認する。
  const bar = win.renderEditorCategoryBar(host, "material", () => {}, () => {});
  const sealBtn = bar.querySelectorAll(".editor-cat-tab")
    .find((c) => c.children[0] && c.children[0].props.text === "ダンジョンの印");
  assert.ok(sealBtn, "カテゴリタブが描画されていない");
  assert.equal(sealBtn.getAttribute("data-cat-id"), sealId,
    "タブの data-cat-id が undefined のまま (ハイライトが「すべて」へ戻る)");
  sealBtn.props.onclick();
  assert.equal(win.activeEditorCategory(host, "material"), sealId,
    "タブを押しても active が null(=すべて)に化けている");
  assert.equal(win.itemInEditorCategory(host, "material", "dungeon_seal_mines"), true);
  assert.equal(win.itemInEditorCategory(host, "material", "dragon_scale"), false,
    "絞り込みが効いていない (別カテゴリの品が通っている)");
});

test("id を無条件に信頼する実装へ戻していない (バックフィルが読み口に残っている)", () => {
  assert.match(editorCategories, /function backfillCategoryIds\(cats, tabKey\)/);
  assert.match(editorCategories, /function listCategories\(host, tabKey\)[\s\S]{0,200}?backfillCategoryIds\(/,
    "listCategories が backfill を通らなくなっている (ここが唯一の読み口)");
  // 自動採番 id はユーザーに意味が無いのでカードのセレクトの副表記に出さない。
  assert.match(editorCategories, /\^cat_\(\\d\+\(_\\d\+\)\?\|auto_\.\*\)\$/);
});

test("3-way マージの識別キーが id に決まる (カテゴリ配列が丸ごと衝突しない)", () => {
  // merge.js は cloneData / deepEqual / threeWayMerge を自分で window へ載せるので素の窓で足りる。
  const merge = fs.readFileSync(path.join(ROOT, "public", "js", "merge.js"), "utf8");
  const win = {};
  new Function("window", merge)(win);

  const { win: catWin } = loadEditorCategories();
  const build = () => hostWithIdlessCategory();
  // base / local / remote すべて読み口を通ってから保存される（editor の実経路）。
  const base = build(); catWin.listEditorCategories(base, "material");
  const local = build(); catWin.listEditorCategories(local, "material");
  const remote = build(); catWin.listEditorCategories(remote, "material");
  // 別要素をそれぞれ編集する: local はラベル変更、remote は itemIds 追加。
  local._editor.categories.material[0].label = "ドロップ素材A";
  remote._editor.categories.material[1].itemIds.push("dungeon_seal_forest");

  const merged = win.threeWayMerge(base, local, remote);
  assert.deepEqual(merged.conflicts, [], "別要素の同時編集が衝突になっている");
  assert.equal(merged.data._editor.categories.material[0].label, "ドロップ素材A");
  assert.ok(merged.data._editor.categories.material[1].itemIds.includes("dungeon_seal_forest"),
    "相手のカテゴリ編集が丸ごと消えている");
});

test("出荷 yml の _editor.categories は全要素が id を持つ", (t) => {
  const targets = [
    ["catalog.yml", path.join(REPO, "TrinityForge", "src", "main", "resources", "items", "catalog.yml")],
    ["item-stats.yml", path.join(REPO, "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml")],
    // fork は .gitignore 除外なので clone/worktree に存在しないことがある。
    ["materials.yml", path.join(REPO, "fork-handoff", "arspaper", "fork", "src", "main", "resources", "materials.yml")]
  ];
  let checked = 0;
  for (const [name, file] of targets) {
    if (!fs.existsSync(file)) continue;
    const data = YAML.parse(fs.readFileSync(file, "utf8"));
    const categories = data && data._editor && data._editor.categories;
    if (!categories || typeof categories !== "object") continue;
    for (const [tabKey, list] of Object.entries(categories)) {
      if (!Array.isArray(list)) continue;
      list.forEach((cat, i) => {
        checked += 1;
        assert.ok(cat && typeof cat === "object" && cat.id,
          `${name} の _editor.categories.${tabKey}[${i}] (label=${cat && cat.label}) に id: が無い`);
      });
    }
  }
  if (!checked) t.skip("検証対象の yml が見つからないためスキップ");
});
