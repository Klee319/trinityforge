"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");

// ---------------------------------------------------------------------------
// item-stats フォームの自動枠生成: 素材 (materials.yml) はスキップ / タブ未決定は
// 「補助」へ落とさずスキップ (2026-07-27)
//
// 不具合: buildItemStatsForm が catalogCandidates (catalog.yml + materials.yml 全353件)
// を回して working.items に空ステータス枠を自動生成していたが、materials.yml 由来の候補
// (tab: "material") には item-stats.yml 側に対応するタブが存在しない
// (ITEM_STATS_CATEGORIES = weapon/armor/tool/other/catalyst/spellbook/thread のみ)。
// そのため素材87件が「画面に出ないまま working.items だけ膨らむ幽霊エントリ」になっていた。
// さらに `candidate.tab || "other"` の暗黙フォールバックにより、タブが決まらない候補が
// 黙って「補助」タブに落ちていた(素材はタブが無いので、落ちた先からも消せない)。
//
// buildItemStatsForm 自体は DOM (window.h → document.createElement) に依存するブラウザ専用
// 関数だが、catalogCandidates の走査・working.items / _editor.itemTabs への書き込みは
// 関数冒頭 (DOM 生成 `const root = h(...)` より前) で完結する。working は data 自身への
// 参照 (data が object のとき) なので、DOM 未定義で後段が例外を投げても、走査結果は
// 呼び出し前に渡した data オブジェクトへ実際に反映済みになる。
// これを利用して、jsdom 無しでも「実際に buildItemStatsForm を呼んだ結果」を検証する
// (item-stats-empty-profile.test.js のような正規表現検証より実挙動に忠実)。
// ---------------------------------------------------------------------------
global.window = global.window || {};
require("../public/js/editor-categories.js");
require("../public/js/catalog-candidates.js");
const buildCatalogCandidates = global.window.buildCatalogCandidates
  || require("../public/js/catalog-candidates.js").buildCatalogCandidates;
require("../public/js/forms.js");
const buildItemStatsForm = global.window.buildItemStatsForm;

function runBuildItemStatsForm(data, catalogCandidates) {
  // DOM 非対応環境では途中 (root = h(...) 以降) で必ず例外が出るが、それより前に
  // working.items / _editor.itemTabs への書き込みは完了しているので無視してよい。
  try {
    buildItemStatsForm(data, { catalogCandidates });
  } catch (err) {
    // no-op: DOM 未定義による後段の例外は検証対象外。
  }
  return data;
}

test("buildItemStatsForm: 素材 (tab: material) は working.items にも itemTabs にも枠を作らない", () => {
  assert.equal(typeof buildItemStatsForm, "function");

  const catalogData = {
    items: {
      sword_id: { material: "IRON_SWORD", "display-name": "剣" }
    }
  };
  const materialsData = {
    materials: {
      source_gem: { base_material: "AMETHYST_SHARD", display_name: "源素の欠片" }
    }
  };
  const catalogCandidates = buildCatalogCandidates(catalogData, materialsData);
  const materialCandidate = catalogCandidates.find((c) => c.id === "source_gem");
  assert.ok(materialCandidate, "materials.yml 由来の候補が生成されていること");
  assert.equal(materialCandidate.tab, "material");

  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  assert.equal(
    Object.prototype.hasOwnProperty.call(data.items, "AMETHYST_SHARD"),
    false,
    "素材のキーが working.items に追加されてはいけない"
  );
  const itemTabs = (data._editor && data._editor.itemTabs) || {};
  assert.equal(
    Object.prototype.hasOwnProperty.call(itemTabs, "AMETHYST_SHARD"),
    false,
    "素材のキーが _editor.itemTabs にも追加されてはいけない"
  );
});

test("buildItemStatsForm: スレッド (tab: thread) は従来どおり枠を作りタブが thread になる", () => {
  const catalogCandidates = [
    { id: "thread_id", displayName: "スレッド例", material: "STRING", cmd: 5001, tab: "thread" }
  ];
  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  const key = "STRING#5001";
  assert.ok(
    Object.prototype.hasOwnProperty.call(data.items, key),
    "スレッド候補は working.items に空枠を作る"
  );
  assert.deepEqual(data.items[key], {});
  const itemTabs = (data._editor && data._editor.itemTabs) || {};
  assert.equal(itemTabs[key], "thread", "スレッド候補のタブは thread のまま");
});

// ---------------------------------------------------------------------------
// TF 特殊アイテム (skill_node_lock / skill_tree_reset、2026-08-04に3券を追加) も枠を作らない
// (2026-07-27)。この5件は「特殊アイテム」画面(機能アイテムカテゴリ)へ集約済み。加えて
// catalog.yml で custom-model-data を持たないため、ステータスキーが素の Material になり、
// 設定するとバニラの同名アイテムすべてに効いてしまう(狙ったアイテムだけを指せない)。
//
// forms.js は index.html 上で functional-items.js より先に読まれるため、TF_SPECIAL_ITEM_IDS の
// 参照は「呼び出し時」に解決される必要がある。ここで forms.js の後に functional-items.js を
// require しているのは、その遅延解決が実際に効いていることを同時に検証するため
// (const で受けていた実装では、この順序だと必ず空配列になりテストが落ちる)。
// ---------------------------------------------------------------------------
require("../public/js/functional-items.js");

test("buildItemStatsForm: TF特殊アイテム8件(既存2件+券3件+2026-08-24追加の良薬3件)は枠を作らない"
    + " (読み込み順に依存せず効く)", () => {
  const core = global.window.FUNCTIONAL_ITEMS_CORE;
  assert.ok(core && Array.isArray(core.TF_SPECIAL_ITEM_IDS), "TF_SPECIAL_ITEM_IDS が公開されていること");
  assert.deepEqual(core.TF_SPECIAL_ITEM_IDS.slice().sort(), [
    "exp_cleanse_tonic_greater", "exp_cleanse_tonic_lesser", "exp_cleanse_tonic_supreme",
    "quality_upgrade_ticket", "role_reselect_ticket", "skill_node_lock",
    "skill_tree_reset", "stat_reroll_ticket"
  ]);

  const catalogData = {
    items: {
      skill_node_lock: { material: "AMETHYST_SHARD", "display-name": "スキルノードの楔" },
      skill_tree_reset: { material: "ECHO_SHARD", "display-name": "スキル再構築の書" },
      role_reselect_ticket: { material: "NAME_TAG", "display-name": "職業付け替えの証" },
      stat_reroll_ticket: { material: "RABBIT_FOOT", "display-name": "厳選やり直しの護符" },
      quality_upgrade_ticket: { material: "HEART_OF_THE_SEA", "display-name": "品質昇華の結晶" },
      // 2026-08-04: 巻き添え確認用の対照は **CMD 付き** にする。CMD 未割当の候補は
      // 素 Material キーに退化してバニラ全部に効いてしまうため枠を作らなくなった
      // (test/item-stats-cmdless-candidate-no-bare-key-2026-08-04.test.js)。
      sword_id: { material: "IRON_SWORD", "display-name": "剣", "custom-model-data": 9001 }
    }
  };
  const catalogCandidates = buildCatalogCandidates(catalogData, null);
  // 前提の確認: 候補自体は生成されている(候補リストは他画面のアイテム選択でも使うので消さない)。
  assert.ok(catalogCandidates.some((c) => c.id === "skill_node_lock"));

  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  const itemTabs = (data._editor && data._editor.itemTabs) || {};
  for (const key of ["AMETHYST_SHARD", "ECHO_SHARD", "NAME_TAG", "RABBIT_FOOT", "HEART_OF_THE_SEA"]) {
    assert.equal(
      Object.prototype.hasOwnProperty.call(data.items, key),
      false,
      `${key} (TF特殊アイテム) の枠が working.items に作られてはいけない`
    );
    assert.equal(Object.prototype.hasOwnProperty.call(itemTabs, key), false);
  }
  // 巻き添えにしていないこと: 通常のカタログ品は従来どおり枠が作られる。
  assert.ok(Object.prototype.hasOwnProperty.call(data.items, "IRON_SWORD#9001"));
});

test("buildItemStatsForm: タブ未決定の候補は「補助」へ落とさず枠を作らない", () => {
  const catalogCandidates = [
    { id: "unknown_id", displayName: "不明", material: "UNKNOWN_MATERIAL", cmd: null, tab: null }
  ];
  const data = { items: {} };
  runBuildItemStatsForm(data, catalogCandidates);

  assert.equal(
    Object.prototype.hasOwnProperty.call(data.items, "UNKNOWN_MATERIAL"),
    false,
    "タブが決まらない候補は working.items に追加されてはいけない (旧: || \"other\" で補助に落ちていた)"
  );
  const itemTabs = (data._editor && data._editor.itemTabs) || {};
  assert.equal(
    Object.prototype.hasOwnProperty.call(itemTabs, "UNKNOWN_MATERIAL"),
    false
  );
});
