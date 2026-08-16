"use strict";

// ---------------------------------------------------------------------------
// 宙ぶらりん `_editor` 検査の「有効id集合」が item-stats で痩せていた (2026-08-16)
//
// 症状: エディタのアイテムステータス画面を開くと
//   「カテゴリ/表示タブ/並び順の設定に、もう実在しないアイテムidへの参照が15件あります」
// と出るが、15件のうち10件は**今も実在するアイテム**を指していた。
//
// 真因: 有効id集合を item-stats.yml 自身の `items:` キーだけで作っていた。
//   item-stats.yml は lib/cmd-removal.js が明記するとおり<アイテム定義ではなく既存
//   (material,cmd) への参照専用ファイル>で、`items:` に載るのは「TFステータスを
//   設定済みのものだけ」＝実在アイテム集合の**部分集合**にすぎない。
//   まだステータスを付けていない実在アイテム(カタログ候補)が軒並み誤検知されていた。
//
// 誤検知は「検査が煩い」だけでは済まない: 本物の取り残し(下の4件)が警告の山に埋もれる。
// 実際この10件のせいで、b969faa が items エントリだけ消して categories/orders に
// 取り残した4件が見過ごされていた。
//
// 不変条件:
//   1. item-stats の有効id集合は「定義ファイル全部(CMD台帳スキャン)」から作る。
//      カタログにしか無いid・ArsPaper spellbooks.yml にしか無いidも有効に数える。
//   2. 出荷 item-stats.yml の `_editor` に宙ぶらりん参照は1件も無い。
//   3. 検査は空回りしていない(実在しないidを混ぜれば必ず落ちる)。
// ---------------------------------------------------------------------------

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const YAML = require("yaml");

const { danglingEditorMetaIds, editorMetaItemIdSet } = require("../lib/editor-meta-integrity.js");

const ROOT = path.resolve(__dirname, "..");
const TF_RES = path.resolve(ROOT, "../../TrinityForge/src/main/resources");
const ARS_RES = path.resolve(ROOT, "../../fork-handoff/arspaper/fork/src/main/resources");

// server.js の readEntryById 相当。フォークのソースは .gitignore 除外なので、
// クリーンなクローンや新しい worktree には存在しない → 読めないファイルは null を返す
// (集合が痩せる方向＝誤検知側へ倒れるので、下の非空回り検査で気づけるようにしてある)。
const FILES = {
  catalog: [TF_RES, "items/catalog.yml"],
  "item-stats": [TF_RES, "stats/item-stats.yml"],
  materials: [ARS_RES, "materials.yml"],
  spellbooks: [ARS_RES, "spellbooks.yml"],
  "external-items": [TF_RES, "items/external-items.yml"],
  sourcejars: [ARS_RES, "sourcejars.yml"],
  sourcelinks: [ARS_RES, "sourcelinks.yml"]
};

function readEntryById(id) {
  const spec = FILES[id];
  if (!spec) return null;
  const file = path.join(spec[0], spec[1]);
  if (!fs.existsSync(file)) return null;
  return YAML.parse(fs.readFileSync(file, "utf8"));
}

const ctx = { readEntryById };
const itemStats = readEntryById("item-stats");
const forkPresent = fs.existsSync(path.join(ARS_RES, "spellbooks.yml"));

test("item-stats の有効id集合はカタログ側のidも含む(自ファイルの items だけではない)", () => {
  const ids = editorMetaItemIdSet(ctx, "item-stats", itemStats);

  // 深罪の終幕: catalog.yml に IRON_SWORD / custom-model-data 68 で実在するが、
  // item-stats.yml の items: には無い(＝ステータス未設定)。旧実装ではこれが
  // 「もう存在しません」と誤検知されていた。
  assert.ok(ids.has("IRON_SWORD#68"),
    "カタログにしか無いid(IRON_SWORD#68 = 深罪の終幕)が有効集合から漏れている");
  assert.ok(!Object.prototype.hasOwnProperty.call(itemStats.items, "IRON_SWORD#68"),
    "前提が崩れた: IRON_SWORD#68 に item-stats エントリが付いたなら、この検査は別のidで書き直すこと");
});

test("item-stats の有効id集合は ArsPaper spellbooks.yml のidも含む", { skip: !forkPresent && "フォーク未取得" }, () => {
  const ids = editorMetaItemIdSet(ctx, "item-stats", itemStats);
  // 無限の触媒: catalog.yml にも materials.yml にも無く spellbooks.yml にだけ居る。
  // 「カタログ + materials の2本だけ見る」実装だと必ず取りこぼす位置にある。
  assert.ok(ids.has("BLAZE_ROD#400024"),
    "spellbooks.yml にしか無いid(BLAZE_ROD#400024 = 無限の触媒)が有効集合から漏れている");
});

test("出荷 item-stats.yml の _editor に宙ぶらりん参照は無い", () => {
  const ids = editorMetaItemIdSet(ctx, "item-stats", itemStats);
  const dangling = danglingEditorMetaIds(itemStats, ids);
  assert.deepStrictEqual(dangling.map((d) => `${d.where} -> ${d.id}`), [],
    "_editor.categories/itemTabs/orders に実在しないidが残っている。"
      + "改名なら新しいidへ付け替え、削除済みならその行を消すこと");
});

test("この検査は空回りしていない(実在しないidを混ぜれば落ちる)", () => {
  const ids = editorMetaItemIdSet(ctx, "item-stats", itemStats);
  const poisoned = {
    ...itemStats,
    _editor: {
      ...(itemStats._editor || {}),
      orders: { ...((itemStats._editor || {}).orders || {}), weapon: ["NO_SUCH_MATERIAL#999999"] }
    }
  };
  const dangling = danglingEditorMetaIds(poisoned, ids);
  assert.equal(dangling.length, 1, "存在しないidを混ぜても検出されない = 検査が死んでいる");
  assert.equal(dangling[0].id, "NO_SUCH_MATERIAL#999999");
});
