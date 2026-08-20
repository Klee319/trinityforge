"use strict";

// ---------------------------------------------------------------------------
// CMD 未割当のカタログ候補は item-stats のキーを持たない (2026-08-04)
//
// item-stats.yml のキーは `MATERIAL#CMD`。CMD が無いとキーが素の `MATERIAL` に退化し、
// **バニラの同素材アイテムすべて**にステータスが効いてしまう ── 狙ったカタログ品 1 件を
// 指す手段が無い。にもかかわらず buildItemStatsForm の候補同期ループは CMD 未割当の候補にも
// 素 Material キーの空枠を作っていた。
//
// 既存 item-stats.yml に同じ素キーが在るときは hasOwnProperty で skip されるため気づけず、
// **素キーがまだ無い材質のときだけ**「バニラ全部に効く枠」が静かに生える、という
// 材質依存の潜在バグになっていた(skill_node_lock / skill_tree_reset はこの害を個別に
// 名前で除外していたが、根本原因は CMD の有無なので新規追加品では毎回再発する)。
//
// 修正: CMD を持たない候補は item-stats のキー自体を持たない(statsKeyFromCandidate が "" を返す)。
// 枠は作らず、素キーのカードがカタログ品に解決されることも無くなる。CMD の採番は
// ステータス設定でその候補を選んだ時点で cmdEnsureCatalogItemCmd が行う
// (test/item-stats-new-catalog-item-cmd-2026-08-04.test.js)。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");

global.window = global.window || {};
require("../public/js/editor-categories.js");
require("../public/js/catalog-candidates.js");
const buildCatalogCandidates = global.window.buildCatalogCandidates
  || require("../public/js/catalog-candidates.js").buildCatalogCandidates;
require("../public/js/forms.js");
const buildItemStatsForm = global.window.buildItemStatsForm;

// item-stats-material-skip.test.js と同じ手口: DOM 未定義環境では root = h(...) 以降で
// 例外が出るが、候補走査と working.items への書き込みはそれより前に完了している。
function runBuildItemStatsForm(data, catalogCandidates) {
  try {
    buildItemStatsForm(data, { catalogCandidates });
  } catch (err) {
    // no-op: DOM 未定義による後段の例外は検証対象外。
  }
  return data;
}

test("CMD未割当のカタログ品は素Materialの空枠を作らない(衝突しない材質でも)", () => {
  // BOOK は出荷 item-stats.yml に素キーが無いため、旧実装では衝突検知に引っかからず
  // 素キー "BOOK" の枠が黙って生えていた(= バニラの本すべてにステが効く枠)。
  const catalogCandidates = buildCatalogCandidates({
    items: { new_spellbook: { material: "BOOK", "display-name": "追加したばかりの魔導書" } }
  }, null);
  const candidate = catalogCandidates.find((c) => c.id === "new_spellbook");
  assert.ok(candidate, "候補リストからは消さない(他画面のアイテム選択で使う)");
  assert.ok(candidate.cmd == null || candidate.cmd === "", "前提: CMD 未割当");

  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  assert.equal(
    Object.prototype.hasOwnProperty.call(data.items, "BOOK"),
    false,
    "素Materialキーの枠を作ってはいけない(バニラの同素材すべてに効いてしまう)"
  );
  const itemTabs = (data._editor && data._editor.itemTabs) || {};
  assert.equal(Object.prototype.hasOwnProperty.call(itemTabs, "BOOK"), false);
});

test("CMD割当済みのカタログ品は従来どおり枠を作る(巻き添えにしていない)", () => {
  const catalogCandidates = buildCatalogCandidates({
    items: { fine_sword: { material: "IRON_SWORD", "display-name": "良い剣", "custom-model-data": 4321 } }
  }, null);

  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  assert.ok(
    Object.prototype.hasOwnProperty.call(data.items, "IRON_SWORD#4321"),
    "CMD がある候補は MATERIAL#CMD の枠を作る"
  );
  assert.deepEqual(data.items["IRON_SWORD#4321"], {});
  assert.equal(
    Object.prototype.hasOwnProperty.call(data.items, "IRON_SWORD"),
    false,
    "素キー側は作らない"
  );
});

test("cmd が空文字の候補も `MATERIAL#` の壊れたキーを作らない", () => {
  // 旧実装の枠生成は `candidate.cmd == null` しか見ていないため、空文字だと
  // `DIAMOND_SWORD#` という Java 側が解決できないキーが生えていた。
  const catalogCandidates = [
    { id: "empty_cmd", displayName: "CMDが空文字", material: "DIAMOND_SWORD", cmd: "", tab: "weapon" }
  ];
  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  assert.deepEqual(Object.keys(data.items), [], "枠を1つも作らない");
});

test("既に素Materialキーが在る材質でも、CMD未割当の候補で枠が増えない", () => {
  // 衝突する側(旧実装では hasOwnProperty で skip されていた経路)。挙動は変わらないが、
  // 「素キーは常にバニラのもの」という不変条件をここで固定する。
  const catalogCandidates = buildCatalogCandidates({
    items: { new_sword: { material: "DIAMOND_SWORD", "display-name": "追加したばかりの剣" } }
  }, null);
  const data = { items: { DIAMOND_SWORD: { fixed: { attack: 10 } } } };
  runBuildItemStatsForm(data, catalogCandidates);

  assert.deepEqual(Object.keys(data.items), ["DIAMOND_SWORD"], "キーは増えない");
  assert.deepEqual(
    data.items.DIAMOND_SWORD.fixed, { attack: 10 },
    "既存のバニラ用エントリの中身を書き換えない"
  );
});
