"use strict";

// ---------------------------------------------------------------------------
// getData() の「孤児ピン掃除」が画面の表示タブピンまで消していた (2026-08-06)
//
// 症状: アイテムステータス「補助」タブの**未設定**に、触媒(BLAZE_ROD#400024-400026)と
//   魔導書(BOOK#100001-100003)が並ぶ。どちらも触媒/魔導書タブに置いてあるので要らないのに、
//   カードを消しても再描画のたびに復活する。
//
// 真因: buildItemStatsForm はカタログ候補ぶんの「値なしの空枠」を items に作り、
//   候補の正しいタブ(catalyst / spellbook)を `_editor.itemTabs` へピン留めする。
//   一方 getData() は
//     ・空枠を dropEmptyItemProfiles で**出力の items から落とし**
//     ・そのあと pruneEditorUiState で「items に無いidのピン」を孤児として delete
//   していた。出力 `out` は `{ ...working }` の浅いコピーなので `out._editor` は
//   **画面が握っている working._editor と同一オブジェクト**であり、この delete が
//   画面のピンごと消していた。getData() は画面を開いた直後 (syncBaseFromEditor) にも
//   呼ばれるため、開いた瞬間にピンが消えて表示タブが Material 推論へ退化し、
//   BLAZE_ROD / BOOK が「補助」へ落ちていた。
//
// 不変条件:
//   1. pruneEditorUiState は**入力の `_editor.itemTabs` オブジェクトを書き換えない**
//      (出力オブジェクトの `_editor` だけを差し替える)。
//   2. それでも保存用の出力からは孤児ピンが消えている (yml に幽霊ピンを残さない)。
//   3. split-views の `_editor` 受け渡しは**画面側が既に持っている `_editor` を上書きしない**
//      (刈り取り済みクローンを書き戻すと 1 の対策が無効化される)。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

global.window = global.window || {};
require("../public/js/editor-categories.js");
const { pruneEditorUiState, setItemDisplayTab, getItemDisplayTab } = global.window;

// 画面が組み立てた状態: 空枠 + そのタブピン + 実データ1件
function makeWorking() {
  const working = {
    items: {
      "BLAZE_ROD#400001": { fixed: { "attack-damage": 5 } }, // 実データあり
      "BLAZE_ROD#400024": {},                                 // カタログ候補の空枠
      "BOOK#100001": {}                                       // カタログ候補の空枠
    },
    _editor: {}
  };
  setItemDisplayTab(working, "BLAZE_ROD#400001", "catalyst");
  setItemDisplayTab(working, "BLAZE_ROD#400024", "catalyst");
  setItemDisplayTab(working, "BOOK#100001", "spellbook");
  return working;
}

// getData() 相当: 空枠を出力から落として浅いコピーを返し、最後に刈り取る
function fakeGetData(working) {
  const items = {};
  for (const [k, v] of Object.entries(working.items)) {
    if (v && Object.keys(v).length) items[k] = v;
  }
  const out = { ...working, items };
  pruneEditorUiState(out);
  return out;
}

test("getData() 相当の刈り取りが画面のタブピンを消さない", () => {
  const working = makeWorking();
  const tabsBefore = working._editor.itemTabs;

  const out = fakeGetData(working);

  // 1. 画面側は無傷 — ここが壊れると空枠が Material 推論のタブ(補助)へ落ちる
  assert.equal(working._editor.itemTabs, tabsBefore, "itemTabs オブジェクトを差し替えてはいけない");
  assert.equal(working._editor.itemTabs["BLAZE_ROD#400024"], "catalyst");
  assert.equal(working._editor.itemTabs["BOOK#100001"], "spellbook");
  assert.equal(getItemDisplayTab(working, "BLAZE_ROD#400024", "BLAZE_ROD"), "catalyst");
  assert.equal(getItemDisplayTab(working, "BOOK#100001", "BOOK"), "spellbook");

  // 2. 保存用の出力からは孤児ピンが消えている (yml を幽霊ピンで膨らませない)
  assert.equal(out._editor.itemTabs["BLAZE_ROD#400001"], "catalyst");
  assert.equal("BLAZE_ROD#400024" in out._editor.itemTabs, false);
  assert.equal("BOOK#100001" in out._editor.itemTabs, false);

  // categories / orders は同じ参照で持ち回る (行エディタが掴んだ配列を孤児にしない)
  assert.equal(out._editor.categories, working._editor.categories);
});

test("何度 getData() を呼んでも画面のピンは残る (開いた直後にも呼ばれる)", () => {
  const working = makeWorking();
  for (let i = 0; i < 5; i++) fakeGetData(working);
  assert.equal(getItemDisplayTab(working, "BOOK#100001", "BOOK"), "spellbook");
});

test("削除済みアイテムのピンは従来どおり出力から落ちる (孤児掃除の本来の目的)", () => {
  const working = { items: { A: { fixed: { x: 1 } } }, _editor: {} };
  setItemDisplayTab(working, "A", "weapon");
  setItemDisplayTab(working, "DELETED#1", "armor"); // items に実体が無い
  const out = fakeGetData(working);
  assert.equal(out._editor.itemTabs.A, "weapon");
  assert.equal("DELETED#1" in out._editor.itemTabs, false);
});

test("落とすものが無いときは _editor の参照ごと保つ", () => {
  const working = { items: { A: { fixed: { x: 1 } } }, _editor: {} };
  setItemDisplayTab(working, "A", "weapon");
  const ed = working._editor;
  const out = fakeGetData(working);
  assert.equal(out._editor, ed);
});

test("split-views は画面側の _editor を保存用出力で上書きしない", () => {
  const src = fs.readFileSync(
    path.join(__dirname, "..", "public", "js", "split-views.js"), "utf8");
  // 刈り取り済みクローンを書き戻す旧パターンが残っていないこと
  assert.equal(/\bdata\._editor = d\._editor\b/.test(src), false,
    "保存用出力の _editor を画面側へ書き戻すとピン消失が再発する");
  assert.match(src, /function adoptEditorMeta\(/);

  // 挙動そのものを固定する (ソース文字列だけの検査にしない)
  const fn = new Function(
    src.slice(src.indexOf("function adoptEditorMeta("),
      src.indexOf("function rerenderForm(")) + "; return adoptEditorMeta;")();

  const liveEd = { itemTabs: { "BOOK#100001": "spellbook" } };
  const host = { _editor: liveEd };
  const out = { _editor: { itemTabs: {} } };
  fn(host, out);
  assert.equal(host._editor, liveEd, "画面側の _editor は据え置き");

  // 画面側にまだ無いときはフォームが作ったものを採用する
  const host2 = {};
  const made = { itemTabs: { A: "weapon" } };
  fn(host2, { _editor: made });
  assert.equal(host2._editor, made);

  // 出力側に無いときは画面側のものを載せる
  const out3 = {};
  fn({ _editor: liveEd }, out3);
  assert.equal(out3._editor, liveEd);
});
