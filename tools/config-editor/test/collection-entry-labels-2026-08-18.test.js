"use strict";

// 図鑑(progression/collection.yml)の2件のユーザー報告の再発防止。
//
// 【1】「エディタの図鑑でアイテム名がID表記になってしまっている」
//   itemRefSelect (util.js) は「候補集合に無い値」を primary=生ID で描く。つまり
//   collection.yml のエントリが候補源のどれからも作られていないと、日本語名を持っていても
//   生IDで出る。実際に items.sourcelink(25件) と items.functional(15件・material を持たない品)
//   がこれで生ID表示になっていた(候補源リストに sourcelinks.yml が無く、functional-items.yml は
//   materialless 未対応だった)。**候補源リストの取りこぼしは警告が一切出ない**ので、
//   「出荷 collection.yml の全エントリが名前付きで解決する」ことを機械的に固定する。
//
// 【2】「バニラの武器やモブの一部が登録されていない」
//   一括追加の走査集合が catalogCandidates(カスタムIDのみ)だったため、1件ずつのセレクトでは
//   選べるバニラ Material が一括追加からは構造的に addressable でなかった。
//
// 許可リスト方式(特定IDの列挙)は使わない。母集合は常に「実 yml のキー」から決める。

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const {
  buildCatalogCandidates, bulkAddCandidateIds, EXTRA_SOURCE_KEYS
} = require("../public/js/catalog-candidates.js");

const ROOT = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(ROOT, "../..");
const TF_RES = path.join(REPO_ROOT, "TrinityForge/src/main/resources");
const ARS_RES = path.join(REPO_ROOT, "fork-handoff/arspaper/fork/src/main/resources");

const readYaml = (abs) => {
  try {
    return YAML.parse(fs.readFileSync(abs, "utf8")) || {};
  } catch (_) {
    return null; // ArsPaper フォークは .gitignore 除外なのでワークツリーに無いことがある。
  }
};

const collection = readYaml(path.join(TF_RES, "progression/collection.yml"));
const materialLabels = JSON.parse(
  fs.readFileSync(path.join(ROOT, "lib/material-labels-ja-1.21.11.json"), "utf8")
).labels;
const vanillaMaterials = JSON.parse(
  fs.readFileSync(path.join(ROOT, "lib/materials-1.21.11.json"), "utf8")
).materials;

/** vocab-1.21.11.js の window.VANILLA_MOBS を Node で得る(ブラウザ用IIFEの最小スタブ)。 */
function loadVanillaMobs() {
  const win = {};
  global.window = win;
  const p = require.resolve(path.join(ROOT, "public/js/vocab-1.21.11.js"));
  delete require.cache[p];
  require(p);
  return win.VANILLA_MOBS || [];
}

function entriesOf(kind) {
  const cats = collection && collection.categories && collection.categories[kind];
  const out = [];
  for (const cat of Object.values(cats || {})) {
    for (const e of (cat && Array.isArray(cat.entries) ? cat.entries : [])) out.push(String(e).trim());
  }
  return [...new Set(out)].filter(Boolean);
}

/** app.js が実際に GET する候補源と同じ構成でカタログ候補を組む。 */
function shippedCandidates() {
  const catalog = readYaml(path.join(TF_RES, "items/catalog.yml"));
  const materials = readYaml(path.join(ARS_RES, "materials.yml"));
  const extra = {
    functionalItems: readYaml(path.join(ARS_RES, "functional-items.yml")),
    sourcejars: readYaml(path.join(ARS_RES, "sourcejars.yml")),
    sourcelinks: readYaml(path.join(ARS_RES, "sourcelinks.yml")),
    catalysts: readYaml(path.join(ARS_RES, "spellbooks.yml"))
  };
  const arsPresent = !!materials && Object.values(extra).every(Boolean);
  return { list: buildCatalogCandidates(catalog || {}, materials || {}, extra), arsPresent };
}

test("候補源リストは catalog-candidates.js と app.js で対になっている", () => {
  const app = fs.readFileSync(path.join(ROOT, "public/js/app.js"), "utf8");
  const block = app.slice(app.indexOf("const EXTRA_CONFIGS = ["));
  const listSrc = block.slice(0, block.indexOf("];"));
  const keysInApp = [...listSrc.matchAll(/\[\s*"([A-Za-z]+)"\s*,\s*"([a-z-]+)"\s*\]/g)].map((m) => m[1]);
  assert.ok(keysInApp.length > 0, "app.js の EXTRA_CONFIGS を読めていない(このテストを直すこと)");
  assert.deepEqual([...keysInApp].sort(), [...EXTRA_SOURCE_KEYS].sort(),
    "候補源の片方だけを増やすと、その yml のアイテムは editor 上で黙って生ID表示になる");
});

test("出荷 collection.yml の items エントリは全件が名前付きで解決する(生ID表示ゼロ)", () => {
  assert.ok(collection, "collection.yml を読めていない");
  const { list, arsPresent } = shippedCandidates();
  const byId = new Map(list.map((c) => [c.id, c]));
  const vanilla = new Set(vanillaMaterials);
  const unresolved = [];
  const unlabeled = [];
  for (const id of entriesOf("items")) {
    const hit = byId.get(id);
    if (hit) {
      // itemRefSelect の primary は displayName(無ければID)。空だと生IDに落ちる。
      if (!String(hit.displayName || "").trim()) unlabeled.push(id);
      continue;
    }
    if (vanilla.has(id)) {
      // materialLabelWithFallback は和名が無いと生IDを返す。
      if (!materialLabels[id]) unlabeled.push(id);
      continue;
    }
    // 小文字IDが解決しないのは ArsPaper フォーク不在(=.gitignore 除外)のときだけ許す。
    if (!arsPresent && !/^[A-Z0-9_]+$/.test(id)) continue;
    unresolved.push(id);
  }
  assert.deepEqual(unresolved, [],
    "候補源のどれにも無いエントリ。editor では primary=生ID で表示される");
  assert.deepEqual(unlabeled, [], "表示名が空なので生IDにフォールバックするエントリ");
});

test("出荷 collection.yml の mobs エントリは全件が EntityType 候補か mob-types.yml のID", () => {
  const mobs = new Set(loadVanillaMobs());
  const mobTypes = readYaml(path.join(TF_RES, "combat/mob-types.yml"));
  const custom = new Set(Object.keys((mobTypes && mobTypes["mob-types"]) || {}));
  const bad = entriesOf("mobs").filter((id) => !mobs.has(id) && !custom.has(id));
  assert.deepEqual(bad, [], "候補に無いモブは editor で生ID表示になり、和名で検索もできない");
});

test("バニラの武器・道具が図鑑に登録されている(1件も無い状態に戻さない)", () => {
  const registered = new Set(entriesOf("items"));
  const gear = vanillaMaterials.filter((m) => /_(SWORD|AXE|PICKAXE|SHOVEL|HOE)$/.test(m));
  const missing = gear.filter((m) => !registered.has(m));
  assert.deepEqual(missing, [],
    "バニラの剣/斧/ツルハシ/シャベル/クワは全ティア図鑑に載っていること");
  for (const id of ["BOW", "CROSSBOW", "SHIELD", "FISHING_ROD", "SHEARS", "FLINT_AND_STEEL"]) {
    assert.ok(registered.has(id), id + " が図鑑に無い");
  }
});

test("圧縮素材(*_<n>x)は material_compressed 以外のカテゴリに置かれていない", () => {
  // 2026-08-18: material_ars(現 material_misc)に *_4x / stone_5x の11件が混ざっていた。
  // 圧縮シリーズは ID の形で機械的に判定できるので、散り始めたら落ちるようにしておく。
  const cats = (collection && collection.categories && collection.categories.items) || {};
  const strays = [];
  for (const [id, cat] of Object.entries(cats)) {
    if (id === "material_compressed") continue;
    for (const e of (Array.isArray(cat.entries) ? cat.entries : [])) {
      if (/_\d+x$/.test(String(e))) strays.push(id + "/" + e);
    }
  }
  assert.deepEqual(strays, [], "圧縮素材は material_compressed にまとめること");
});

test("一括追加の走査集合は items ならバニラ Material も含む(*_SWORD が引ける)", () => {
  const catalogCandidates = [{ id: "infinity_sword" }, { id: "thread_luck" }];
  const ids = bulkAddCandidateIds("items", catalogCandidates, vanillaMaterials, ["ZOMBIE"]);
  assert.ok(ids.includes("infinity_sword"), "カスタムIDが落ちている");
  assert.ok(ids.includes("DIAMOND_SWORD"), "バニラ Material が候補に入っていない");
  const matched = ids.filter((id) => /^[A-Z0-9_]*_SWORD$/.test(id));
  assert.ok(matched.length >= 7, "`*_SWORD` で全ティアが引けない: " + matched.length + "件");
});

test("一括追加の走査集合は mobs なら EntityType のみ(アイテムIDが混ざらない)", () => {
  const ids = bulkAddCandidateIds("mobs", [{ id: "infinity_sword" }], vanillaMaterials, ["ZOMBIE", "HUSK"]);
  assert.deepEqual(ids, ["ZOMBIE", "HUSK"]);
});
