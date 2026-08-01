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

// ⚠️ このテストは元々「3ファイル合計で1件も見なかったときだけ skip」だったため、
// catalog.yml(40要素) と item-stats.yml(47要素) が合計 87 件を提供する分で常に「pass」になり、
// **本命の materials.yml が1行も検査されていなくても緑**だった(fork は .gitignore 除外なので
// clone/worktree には存在しない = 実際にほぼ毎回無検査)。このリポジトリで繰り返し踏んでいる
// 「SKIPPED 素通り」と同型。検査できたか否かは**ファイル単位で見える形**にすること。
/**
 * 各 target の `_editor.categories` 全要素に id があることを検査する。
 * 「検査できなかった」ファイルは件数で埋め合わせず、**ファイル単位で** diagnostic に出し、
 * required なものが1件でも検査できなかったら失敗させる。
 *
 * @param targets `{ name, file, required }` の配列
 * @param diagnostic 1行ずつ受け取るシンク (node:test の `t.diagnostic`)
 */
function assertEditorCategoryIds(targets, diagnostic) {
  const uninspected = [];
  for (const { name, file } of targets) {
    if (!fs.existsSync(file)) {
      diagnostic(`${name}: 未検査 — ファイルが存在しない (${file})`);
      uninspected.push(name);
      continue;
    }
    const data = YAML.parse(fs.readFileSync(file, "utf8"));
    const categories = data && data._editor && data._editor.categories;
    if (!categories || typeof categories !== "object") {
      diagnostic(`${name}: 未検査 — _editor.categories が無い`);
      uninspected.push(name);
      continue;
    }
    let checked = 0;
    for (const [tabKey, list] of Object.entries(categories)) {
      if (!Array.isArray(list)) continue;
      list.forEach((cat, i) => {
        checked += 1;
        assert.ok(cat && typeof cat === "object" && cat.id,
          `${name} の _editor.categories.${tabKey}[${i}] (label=${cat && cat.label}) に id: が無い`);
      });
    }
    if (!checked) {
      diagnostic(`${name}: 未検査 — _editor.categories に配列要素が無い`);
      uninspected.push(name);
      continue;
    }
    diagnostic(`${name}: ${checked} 要素を検査`);
  }
  // 他ファイルの件数で埋め合わせて「pass」に化けないよう、required の未検査は失敗にする。
  const missingRequired = uninspected.filter(
    (name) => targets.find((tg) => tg.name === name).required);
  assert.deepEqual(missingRequired, [],
    `検査できなかった必須ファイルがある: ${missingRequired.join(", ")}`);
  if (uninspected.length) {
    diagnostic(`未検査のファイル: ${uninspected.join(", ")} (fork 未取得なら正常)`);
  }
}

const EDITOR_CATEGORY_TARGETS = [
  // required: リポジトリに必ずある = 検査0件なら「見落とし」なのでテスト失敗にする。
  { name: "catalog.yml", required: true,
    file: path.join(REPO, "TrinityForge", "src", "main", "resources", "items", "catalog.yml") },
  { name: "item-stats.yml", required: true,
    file: path.join(REPO, "TrinityForge", "src", "main", "resources", "stats", "item-stats.yml") },
  // fork は .gitignore 除外なので clone/worktree に存在しないことがある。存在しないこと自体は
  // 正常だが、「検査できなかった」ことは必ず出力に残す。
  { name: "materials.yml", required: false,
    file: path.join(REPO, "fork-handoff", "arspaper", "fork", "src", "main", "resources", "materials.yml") }
];

test("出荷 yml の _editor.categories は全要素が id を持つ", (t) => {
  assertEditorCategoryIds(EDITOR_CATEGORY_TARGETS, (line) => t.diagnostic(line));
});

test("検査できなかったファイルは他ファイルの件数で埋め合わせない", () => {
  // ⚠️ 元の実装は「3ファイル合計で1件も見なかったときだけ skip」だったため、
  // catalog.yml(40要素) と item-stats.yml(47要素) の合計 87 件で常に「pass」になり、
  // **本命の materials.yml が1行も検査されていなくても緑**だった(fork は .gitignore 除外なので
  // clone/worktree には存在しない = 実際にほぼ毎回無検査)。このリポジトリで繰り返し踏んでいる
  // 「SKIPPED 素通り」と同型なので、その形へ戻らないことをここで固定する。
  const lines = [];
  const present = EDITOR_CATEGORY_TARGETS.find((tg) => tg.name === "catalog.yml");
  const missing = { name: "__absent__.yml", required: true, file: path.join(REPO, "__no_such_file__.yml") };

  assert.throws(() => assertEditorCategoryIds([present, missing], (l) => lines.push(l)),
    /検査できなかった必須ファイルがある: __absent__\.yml/,
    "検査できたファイルの件数で必須ファイルの未検査が隠れている");
  // 何が検査され、何が検査できなかったかがファイル単位で出ていること。
  assert.ok(lines.some((l) => /^catalog\.yml: \d+ 要素を検査$/.test(l)), lines.join(" / "));
  assert.ok(lines.some((l) => l.startsWith("__absent__.yml: 未検査")), lines.join(" / "));
});

// ============================================================
// 2026-08-01 実サーバ報告「追加した素材が editor でカテゴリ分けできていない」の回帰テスト。
//
// 真因: 割当は `assignItemToActiveEditorCategory` の1本しかなく、これは
// 「カテゴリタブで絞り込み中のときだけ」割り当てる。既定の表示は「すべて」なので、
// 普通に「+ 素材追加」した品はどのカテゴリにも入らない。エラーも警告も出ないので、
// カテゴリタブを押さずに追加し続ける限り全部が「未設定」に溜まり続ける。
// ファイル跨ぎ移動 (カタログ⇄素材) と表示タブ移動も同じ形で無所属になっていた。
//
// 対策: `ensureItemEditorCategory` = 「絞り込み中ならそのカテゴリ、無ければ『未分類』」。
// 未分類は仮想タブの「未設定」と違い実体のあるカテゴリなので yml に残る。
// ============================================================

function hostWithTwoCategories() {
  return {
    materials: {},
    _editor: {
      categories: {
        material: [
          { id: "cat_drop", label: "ドロップ素材", itemIds: [] },
          { id: "cat_craft", label: "クラフト素材", itemIds: [] }
        ]
      }
    }
  };
}

test("「すべて」表示のまま追加しても未分類カテゴリへ必ず入る", () => {
  const { win } = loadEditorCategories();
  const host = hostWithTwoCategories();

  // 絞り込みなし (= 既定の「すべて」)。ここが旧実装では no-op だった。
  const catId = win.ensureItemEditorCategory(host, "material", "dragon_scale");
  assert.equal(catId, win.UNCLASSIFIED_EDITOR_CATEGORY_ID);
  assert.equal(win.getItemEditorCategory(host, "material", "dragon_scale"), catId,
    "追加した品がどのカテゴリにも属していない (未設定タブにしか出ない)");

  const cats = win.listEditorCategories(host, "material");
  const unclassified = cats.find((c) => c.id === catId);
  assert.ok(unclassified, "未分類カテゴリが作られていない");
  assert.equal(unclassified.label, "未分類");
  assert.deepEqual(unclassified.itemIds, ["dragon_scale"]);

  // 2件目は同じ未分類カテゴリを使い回す (追加のたびに増やさない)。
  win.ensureItemEditorCategory(host, "material", "dungeon_seal_mines");
  const after = win.listEditorCategories(host, "material");
  assert.equal(after.filter((c) => c.id === catId).length, 1);
  assert.deepEqual(after.find((c) => c.id === catId).itemIds,
    ["dragon_scale", "dungeon_seal_mines"]);
});

test("カテゴリタブで絞り込み中なら、そのカテゴリへ入る (従来挙動を維持)", () => {
  const { win } = loadEditorCategories();
  const host = hostWithTwoCategories();
  const bar = win.renderEditorCategoryBar(host, "material", () => {}, () => {});
  const dropBtn = bar.querySelectorAll(".editor-cat-tab")
    .find((c) => c.getAttribute("data-cat-id") === "cat_drop");
  dropBtn.props.onclick();

  assert.equal(win.ensureItemEditorCategory(host, "material", "dragon_scale"), "cat_drop");
  // 絞り込み中に追加したものは未分類を作らない。
  assert.ok(!win.listEditorCategories(host, "material")
    .some((c) => c.id === win.UNCLASSIFIED_EDITOR_CATEGORY_ID));
});

test("既にカテゴリを持つ品は勝手に動かさない", () => {
  const { win } = loadEditorCategories();
  const host = hostWithTwoCategories();
  win.moveItemEditorCategory(host, "material", "dragon_scale", "cat_craft");
  assert.equal(win.ensureItemEditorCategory(host, "material", "dragon_scale"), "cat_craft");
  assert.ok(!win.listEditorCategories(host, "material")
    .some((c) => c.id === win.UNCLASSIFIED_EDITOR_CATEGORY_ID));
});

test("表示タブを移すと移動先タブでもカテゴリへ入る (未設定へ落ちない)", () => {
  const { win } = loadEditorCategories();
  const host = {
    items: { battle_axe: {} },
    _editor: {
      itemTabs: { battle_axe: "tool" },
      categories: {
        tool: [{ id: "cat_axe", label: "斧", itemIds: ["battle_axe"] }],
        weapon: [{ id: "cat_sword", label: "剣", itemIds: [] }]
      }
    }
  };
  win.moveItemDisplayTab(host, "battle_axe", "weapon", ["weapon", "tool"]);

  assert.equal(win.getItemDisplayTab(host, "battle_axe"), "weapon");
  assert.equal(win.getItemEditorCategory(host, "tool", "battle_axe"), "",
    "移動元タブのカテゴリからは外れること");
  assert.equal(win.getItemEditorCategory(host, "weapon", "battle_axe"),
    win.UNCLASSIFIED_EDITOR_CATEGORY_ID,
    "移動先タブで無所属になっている (表示タブを変えると未設定へ落ちる)");
});

test("assignItemToActiveEditorCategory は ensure へ委譲している (旧 no-op へ戻していない)", () => {
  // 呼び出し側(forms.js / ars-forms.js)は名前を変えずに全部この関数を通るので、
  // ここが no-op に戻ると「追加した品が未設定に溜まる」症状がそのまま復活する。
  assert.match(editorCategories,
    /window\.assignItemToActiveEditorCategory = function[\s\S]{0,200}?window\.ensureItemEditorCategory\(/);
  assert.match(editorCategories, /function ensureItemEditorCategory\(host, tabKey, itemId\)/);
  // 追加経路が ensure を通っていること (素材の「+ 素材追加」/ ファイル跨ぎ移動)。
  const arsForms = fs.readFileSync(path.join(ROOT, "public", "js", "ars-forms.js"), "utf8");
  const forms = fs.readFileSync(path.join(ROOT, "public", "js", "forms.js"), "utf8");
  assert.match(arsForms, /window\.ensureItemEditorCategory\(cat, tab, entry\.id\)/,
    "素材→カタログ移動でカテゴリ割当をしていない");
  assert.match(forms, /window\.ensureItemEditorCategory\(mats, "material", id\)/,
    "カタログ→素材移動でカテゴリ割当をしていない");
});
