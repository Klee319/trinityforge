"use strict";

// _editor.categories[*].itemIds / _editor.itemTabs / _editor.orders[tabKey] のうち、
// もう実在しないアイテムidを指している「宙ぶらりん」エントリの検出 (2026-08-16)。
//
// 検出のみ・自動修復はしない:
// - `lib/yaml-merge.js` とテストで固定した「開いて保存しただけで yml が変わらない」
//   不変条件を壊さないため。
// - 黙って消すと id のタイプミス(改名漏れ)も黙って消えて気づけなくなるため。
// - 保存経路 (`window.removeEditorCategoryItem` 等) を通さない手編集・改名・別ツールでの
//   削除はエディタ側から検出しようがないので、削除前提の自動掃除は成立しない。
// (docs/agent-context/config-editor.md 「宙ぶらりんの `_editor` メタ検出」節を参照)
//
// `itemIdSet` は呼び出し側が組み立てる「そのファイルで有効な id の集合」。単純に
// そのファイル自身の items/materials キーだけとは限らない ── 例えば ArsPaper の
// materials.yml は「ダンジョンの鍵」カテゴリ (`MATERIALS_KEY_CATEGORY_ID` = `cat_dungeon_keys`)
// だけ意図的に TrinityForge本体の catalog.yml (items/catalog.yml) の id を指す設計になっている。
// 鍵の実体を catalog.yml に置いたままにしているのは、`dungeon/gates.yml` の `key-item` が
// 焼き込まれた id を厳密比較し、鍵はカタログのレシピと CMD 台帳も持つため、materials.yml へ
// 実移動するとダンジョン入場が壊れるから。この場合 itemIdSet は「自ファイルのアイテム集合
// ∪ catalog.yml のアイテム集合」にすること(呼び出し側の責務。本関数は集合の中身を知らない)。

const CmdRegistry = require("./cmd-registry");

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function toIdSet(itemIdSet) {
  if (itemIdSet instanceof Set) return itemIdSet;
  if (Array.isArray(itemIdSet)) return new Set(itemIdSet);
  return new Set();
}

/**
 * @param {object} data - yml のルートオブジェクト(`_editor` を持つもの)
 * @param {Set<string>|string[]} itemIdSet - このファイルで有効な id の集合(呼び出し側が算出)
 * @returns {{where: string, id: string, kind: "itemTabs"|"categories"|"orders"}[]}
 */
function danglingEditorMetaIds(data, itemIdSet) {
  const results = [];
  if (!isPlainObject(data)) return results;
  const editorMeta = data._editor;
  if (!isPlainObject(editorMeta)) return results;
  const idSet = toIdSet(itemIdSet);

  const itemTabs = editorMeta.itemTabs;
  if (isPlainObject(itemTabs)) {
    for (const id of Object.keys(itemTabs)) {
      if (!idSet.has(id)) {
        results.push({ where: `_editor.itemTabs.${id}`, id, kind: "itemTabs" });
      }
    }
  }

  const categories = editorMeta.categories;
  if (isPlainObject(categories)) {
    for (const [tabKey, cats] of Object.entries(categories)) {
      if (!Array.isArray(cats)) continue;
      for (const cat of cats) {
        if (!isPlainObject(cat) || !Array.isArray(cat.itemIds)) continue;
        const label = typeof cat.label === "string" && cat.label ? cat.label : (cat.id || "?");
        for (const id of cat.itemIds) {
          if (typeof id !== "string") continue;
          if (!idSet.has(id)) {
            results.push({
              where: `_editor.categories.${tabKey}[${label}].itemIds`,
              id,
              kind: "categories"
            });
          }
        }
      }
    }
  }

  // `_editor.orders[tabKey]` は表示順(フラットな id 配列)。`pruneEditorUiState`
  // (editor-categories.js) は「`categories` / `orders` は触らない」と明記しており
  // itemTabs と違って孤児掃除の対象外なので、削除された id が永久に残り続けうる。
  const orders = editorMeta.orders;
  if (isPlainObject(orders)) {
    for (const [tabKey, order] of Object.entries(orders)) {
      if (!Array.isArray(order)) continue;
      for (const id of order) {
        if (typeof id !== "string") continue;
        if (!idSet.has(id)) {
          results.push({ where: `_editor.orders.${tabKey}`, id, kind: "orders" });
        }
      }
    }
  }

  return results;
}

/** 1件を利用者向けの日本語メッセージへ整形する(「なぜ問題か」まで書く)。 */
function describeDanglingEditorMetaId(entry) {
  if (!entry || typeof entry !== "object") return "";
  const kindLabel = entry.kind === "itemTabs" ? "表示タブの割り当て"
    : entry.kind === "orders" ? "表示順の並び"
    : "カテゴリの所属";
  return `${entry.where}: id "${entry.id}" のアイテムはもう存在しません。この${kindLabel}は表示にも絞り込みにも`
    + `永久に効きません。IDを改名した場合は新しいIDへ付け替えてください(タイプミスの可能性があるため自動では消しません)。`;
}

// 保存時に「そのconfigで有効なアイテムid集合」を組み立てる(サーバ側専用。ctx.readEntryById で
// 他ファイルを読む必要があるため、DOM を持たない public/js 側にはミラーしない。
// `computeCmdWarnings`(lib/cmd-routes.js)と同じ「configId で分岐するクロスファイル対応ヘルパ」)。
// 未対応のconfigは null を返し、呼び出し側はそのconfigの検査自体をスキップする。
function editorMetaItemIdSet(ctx, configId, data) {
  const ownIds = (obj, key) => {
    const m = obj && obj[key];
    return isPlainObject(m) ? Object.keys(m) : [];
  };
  if (configId === "catalog") return new Set(ownIds(data, "items"));
  if (configId === "item-stats") {
    // ⚠ item-stats.yml の `items:` は「有効なアイテムの一覧」ではない。
    // このファイルは cmd-removal.js の冒頭が明記しているとおり<アイテム定義ではなく
    // 既存 (material,cmd) への参照専用ファイル>で、`items:` に載るのは
    // 「TFステータスを設定済みのものだけ」＝実在アイテム集合の部分集合にすぎない。
    //
    // 2026-08-16 の実害: 自身の `items:` だけを有効集合にしていたため、
    // まだステータスを付けていない実在アイテム10件が「もう存在しません」と誤検知された
    // (深罪の終幕 IRON_SWORD#68 / 魔法書3種 BOOK#100001-100003 /
    //  novus_criculus_luminis GLOWSTONE_DUST#84 はいずれも catalog.yml に実在し、
    //  無限・竜・ウィザーの触媒 BLAZE_ROD#400024-400026 は ArsPaper spellbooks.yml に実在する)。
    // 誤検知は「本物の改名漏れ」を警告の山に埋めてしまうので、検査の価値そのものを殺す。
    //
    // 正しい母集合は CMD 台帳スキャンと同じ「定義ファイル全部」。CmdRegistry.scanUsage は
    // catalog / materials / spellbooks / external-items / sourcejars / sourcelinks / item-stats を
    // 走査して (material, cmd) を返すので、それを item-stats のキー形式 `MATERIAL#CMD` へ直す。
    // 保存中のファイル自身は ctx 経由だとディスク上の古い内容になるため、渡された data を使う。
    const ids = new Set(ownIds(data, "items")); // CMD 無しの素の `MATERIAL` キーもここで拾う
    const readEntry = (id) => (id === "item-stats"
      ? data
      : (ctx && typeof ctx.readEntryById === "function" ? ctx.readEntryById(id) : null));
    let usage;
    try {
      usage = CmdRegistry.scanUsage(readEntry);
    } catch (_) {
      // 他ファイルが読めない環境(単体テスト等)では自ファイル分だけで判定する。
      // 検査を落とすのではなく母集合が痩せるだけなので、誤検知側へ倒れる点に注意。
      usage = [];
    }
    for (const u of usage) ids.add(`${u.material}#${u.cmd}`);
    return ids;
  }
  if (configId === "materials") {
    // ArsPaper materials.yml: 自身の materials キーに加え、「ダンジョンの鍵」カテゴリが
    // 意図的に指す catalog.yml(TrinityForge本体) の items キーも有効集合に含める
    // (このファイル冒頭のコメント、および docs/agent-context/config-editor.md 参照)。
    const own = ownIds(data, "materials");
    const catalogData = ctx && typeof ctx.readEntryById === "function" ? ctx.readEntryById("catalog") : null;
    const catalogIds = ownIds(catalogData, "items");
    return new Set([...own, ...catalogIds]);
  }
  return null;
}

// PUT /api/config/:id の保存後に呼ぶ。cmdWarnings と同じ「保存をブロックしない警告」の作り。
// 対応外のconfigや問題なしの場合は空配列を返す。
function computeEditorMetaWarnings(ctx, configId, data) {
  const idSet = editorMetaItemIdSet(ctx, configId, data);
  if (!idSet) return [];
  return danglingEditorMetaIds(data, idSet).map(describeDanglingEditorMetaId);
}

module.exports = {
  danglingEditorMetaIds,
  describeDanglingEditorMetaId,
  editorMetaItemIdSet,
  computeEditorMetaWarnings
};
