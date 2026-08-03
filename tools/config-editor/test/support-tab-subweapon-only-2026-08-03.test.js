"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

// ---------------------------------------------------------------------------
// 「補助」タブはサブウェポン専用 (2026-08-03 ユーザー指摘の再発防止)
//
// 指摘された状態:
//   ・アイテムステータス > 補助 に、ドミニオンワンド / テレポートコンパス / ソースベリー /
//     ソースジャー6種 / 触媒3種 / ダンジョンの鍵17種 が並んでいた。
//   ・カタログ > 補助 にも鍵17種が並んでいた。
//   本来ここは「新生の光輪」のようなサブウェポンだけの枠。
//
// 静かに壊れる形が3つあるので、3つとも机上で落とす:
//   罠1: catalog-candidates.js の EXTRA_SOURCES はどの源も tab を書けるが、既定を
//        "other" にしておくと**行き先の無い品が全部補助へ落ちる**。エラーも警告も出ない。
//   罠2: 「ステを持たない品」を tab で弾くと、tab: "other" を共有している
//        サブウェポン(新生の光輪)まで一緒に消える。statless(候補側の noItemStats)で
//        弾かないと必ずどちらかが犠牲になる。
//   罠3: 鍵の material(TRIAL_KEY)は inferItemCategory のどの分岐にも当たらないため
//        既定の "other" へ落ちる。**_editor.itemTabs のピンが唯一の防波堤**で、
//        ピンを1件消すとその鍵だけ黙って補助へ戻る。
// ---------------------------------------------------------------------------

global.window = global.window || {};
require("../public/js/editor-categories.js");
require("../public/js/materials.js"); // window.inferItemCategory (タブ推論の正本)
require("../public/js/catalog-candidates.js");
const buildCatalogCandidates = global.window.buildCatalogCandidates
  || require("../public/js/catalog-candidates.js").buildCatalogCandidates;
require("../public/js/forms.js");
require("../public/js/functional-items.js"); // window.FUNCTIONAL_ITEMS_CORE.TF_SPECIAL_ITEM_IDS
const buildItemStatsForm = global.window.buildItemStatsForm;

const CATALOG_PATH = path.join(__dirname, "..", "..", "..", "TrinityForge", "src", "main",
  "resources", "items", "catalog.yml");

/** 補助(other)タブに出てよいカタログID。サブウェポンだけ。増やすときはユーザー確認が要る。 */
const SUPPORT_TAB_ALLOWLIST = new Set(["novus_criculus_luminis"]);

/** DOM 無しで buildItemStatsForm の「候補走査」部分だけを実行する(item-stats-material-skip と同じ手法)。 */
function runBuildItemStatsForm(data, catalogCandidates) {
  try {
    buildItemStatsForm(data, { catalogCandidates });
  } catch (err) {
    // DOM 未定義による後段の例外は検証対象外。走査結果は既に data へ反映済み。
  }
  return data;
}

function extraDataFixture() {
  return {
    functionalItems: {
      items: {
        dominion_wand: { "display-name": "ドミニオンワンド", material: "BLAZE_ROD" },
        source_berry: { "display-name": "ソースベリー", material: "GLOW_BERRIES" }
      }
    },
    sourcejars: {
      jars: {
        source_jar: { "display-name": "ソースジャー", material: "DECORATED_POT", "custom-model-data": 200002 }
      }
    },
    catalysts: {
      catalysts: {
        infinity_catalyst: { "display-name": "インフィニティの触媒", material: "BLAZE_ROD", "custom-model-data": 400024 }
      }
    }
  };
}

// ---------------------------------------------------------------------------
// 罠1: 候補源ごとの tab / statless の割り当て
// ---------------------------------------------------------------------------

test("罠1: 触媒(spellbooks.yml catalysts)の候補は tab: catalyst で、ステを持つ扱いのまま", () => {
  const candidates = buildCatalogCandidates({ items: {} }, null, extraDataFixture());
  const catalyst = candidates.find((c) => c.id === "infinity_catalyst");
  assert.ok(catalyst, "触媒の候補が生成されていない");
  assert.equal(catalyst.tab, "catalyst",
    "触媒の候補が補助(other)へ落ちている。杖10本と同じ「触媒」タブに出さないと"
    + "アイテムステータスの補助タブが吹き溜まりに戻る");
  assert.notEqual(catalyst.noItemStats, true,
    "触媒は実際にステを持つ(BLAZE_ROD#400002-400014 / ENDER_EYE#85 が item-stats.yml に実在)ので"
    + "statless にしてはいけない");
});

test("罠1: 機能アイテムとソースジャーの候補は noItemStats が立つ(タブは other のまま)", () => {
  const candidates = buildCatalogCandidates({ items: {} }, null, extraDataFixture());
  for (const id of ["dominion_wand", "source_berry", "source_jar"]) {
    const c = candidates.find((x) => x.id === id);
    assert.ok(c, `${id} の候補が生成されていない(レシピ素材セレクトから参照するので消してはいけない)`);
    assert.equal(c.noItemStats, true,
      `${id} に noItemStats が立っていない。ステータス画面へ空枠が生えて補助タブに並ぶ`);
  }
});

// ---------------------------------------------------------------------------
// 罠2: noItemStats で弾く / tab では弾かない
// ---------------------------------------------------------------------------

test("罠2: noItemStats の候補は item-stats へ枠を作らないが、同じ tab のサブウェポンは残る", () => {
  const candidates = [
    // サブウェポン(新生の光輪 相当)。tab は機能アイテムと同じ "other"。
    { id: "novus_criculus_luminis", displayName: "新生の光輪", material: "GLOWSTONE", cmd: 84, tab: "other" },
    { id: "dominion_wand", displayName: "ドミニオンワンド", material: "BLAZE_ROD", cmd: 100002, tab: "other", noItemStats: true },
    { id: "source_jar", displayName: "ソースジャー", material: "DECORATED_POT", cmd: 200002, tab: "other", noItemStats: true }
  ];
  const data = { items: {} };
  runBuildItemStatsForm(data, candidates);

  assert.ok(Object.prototype.hasOwnProperty.call(data.items, "GLOWSTONE#84"),
    "サブウェポンの枠まで巻き添えで消えている(tab で弾く実装に戻っている)");
  for (const key of ["BLAZE_ROD#100002", "DECORATED_POT#200002"]) {
    assert.equal(Object.prototype.hasOwnProperty.call(data.items, key), false,
      `${key} の空枠が item-stats に作られている(noItemStats が無視されている)`);
  }
  const itemTabs = (data._editor && data._editor.itemTabs) || {};
  assert.equal(Object.prototype.hasOwnProperty.call(itemTabs, "BLAZE_ROD#100002"), false,
    "noItemStats の品が item-stats.yml 側 _editor.itemTabs に幽霊ピンを残している");
});

// ---------------------------------------------------------------------------
// 罠3: 出荷 catalog.yml の実データ
// ---------------------------------------------------------------------------

function shippedCatalog() {
  return YAML.parse(fs.readFileSync(CATALOG_PATH, "utf8"));
}

/** 出荷 catalog.yml の各アイテムの表示タブ(ピン優先 → material 推論)。 */
function shippedDisplayTabs(catalog) {
  const items = catalog.items || {};
  const out = new Map();
  for (const [id, entry] of Object.entries(items)) {
    if (!entry || typeof entry !== "object") continue;
    out.set(id, global.window.getItemDisplayTab(catalog, id, entry.material));
  }
  return out;
}

test("罠3: 出荷 catalog.yml の補助(other)タブはサブウェポンと特殊アイテムだけ", () => {
  const catalog = shippedCatalog();
  const tabs = shippedDisplayTabs(catalog);
  const specials = new Set((global.window.FUNCTIONAL_ITEMS_CORE || {}).TF_SPECIAL_ITEM_IDS || []);
  assert.ok(specials.size > 0, "TF_SPECIAL_ITEM_IDS が読めていない(テスト側の前提が崩れている)");

  const unexpected = [];
  for (const [id, tab] of tabs) {
    if (tab !== "other") continue;
    // 特殊アイテム2件は「特殊アイテム」画面へ集約済みで、カタログ/ステータスの補助タブには出ない。
    if (specials.has(id) || SUPPORT_TAB_ALLOWLIST.has(id)) continue;
    unexpected.push(id);
  }
  assert.deepEqual(unexpected, [],
    "補助タブにサブウェポン以外が入っている。表示タブのピン(_editor.itemTabs)を足すか、"
    + "サブウェポンなら SUPPORT_TAB_ALLOWLIST へ追加すること: " + unexpected.join(", "));
});

test("罠3: 出荷 catalog.yml の key_* は全件 material-ref にピンされている", () => {
  const catalog = shippedCatalog();
  const refTab = global.window.CATALOG_MATERIAL_REF_TAB[0];
  const tabs = shippedDisplayTabs(catalog);

  const keyIds = [...tabs.keys()].filter((id) => id.startsWith("key_"));
  assert.ok(keyIds.length >= 17, `catalog.yml から key_* を読めていない (${keyIds.length}件)`);

  const misplaced = keyIds.filter((id) => tabs.get(id) !== refTab);
  assert.deepEqual(misplaced, [],
    `鍵の表示タブが ${refTab} 以外になっている。TRIAL_KEY は inferItemCategory のどの分岐にも`
    + "当たらず既定の other へ落ちるため、ピンが唯一の防波堤: " + misplaced.join(", "));

  // 実体は catalog.yml に残っていること。materials.yml へ実移動すると鍵専用の PDC/レシピと
  // ShippedDungeonKeyReachabilityTest(入手経路の検査)が参照先を失う。
  for (const id of keyIds) {
    assert.ok(catalog.items[id] && catalog.items[id].material === "TRIAL_KEY",
      `${id} が catalog.yml の TRIAL_KEY エントリとして残っていない`);
  }
  assert.notEqual(refTab, "material",
    "material-ref が実データ移行値 material と同じ文字列になっている(鍵が materials.yml へ移送される)");
});
