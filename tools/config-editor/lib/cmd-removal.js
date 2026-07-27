"use strict";

// CMD台帳の「個別登録解除」「カタログ削除の一括反映(孤児掃除)」用の純関数群。
// scanner (cmd-registry.js) の逆操作: パース済みconfigデータから (material,cmd) を参照する
// エントリを取り除いた新しいデータを返す (不変。元オブジェクトは破壊しない)。
//
// item-stats は「アイテム定義」ではなく既存(material,cmd)への参照専用ファイル(cmd-routes H-1)。
// authoritativeな定義ファイル(catalog/materials/spellbooks/external-items/sourcejars/sourcelinks)の
// どれにも無く item-stats だけが参照している (material,cmd) は「カタログ等から削除済みなのに
// stat参照だけ残った孤児」であり、これが「登録アイテム一覧に消したアイテムが残る」バグの実体。
// = カタログ削除の一括反映ボタンの対象。

// 参照専用ファイルの base タグ集合 (source.file は "spellbooks:catalysts" のように ":" を含む場合がある)。
const REFERENCE_ONLY_BASES = new Set(["item-stats"]);

function sourceBase(file) {
  return String(file || "").split(":")[0];
}

// usage (scanUsage の戻り値) から、参照専用ファイル(item-stats)だけが参照している孤児を返す。
// 戻り値: [{material, cmd, sources:[{file,id}]}]
function findOrphanAllocations(usage) {
  const list = Array.isArray(usage) ? usage : [];
  return list.filter((u) => {
    const sources = Array.isArray(u.sources) ? u.sources : [];
    if (sources.length === 0) return false;
    return sources.every((s) => REFERENCE_ONLY_BASES.has(sourceBase(s.file)));
  });
}

// material/cmd が一致する map(オブジェクト)エントリを除去する汎用ヘルパ。不変。
function removeFromEntryMap(data, mapKey, matField, cmdField, fileTag, mat, cmd) {
  const map = data[mapKey];
  const removed = [];
  if (!map || typeof map !== "object" || Array.isArray(map)) return { data, removed };
  const nextMap = {};
  for (const [id, entry] of Object.entries(map)) {
    if (entry && typeof entry === "object"
        && String(entry[matField] || "").toUpperCase() === mat
        && entry[cmdField] === cmd) {
      removed.push({ file: fileTag, id });
      continue; // drop
    }
    nextMap[id] = entry;
  }
  if (removed.length === 0) return { data, removed };
  return { data: { ...data, [mapKey]: nextMap }, removed };
}

// item-stats のキーは "MATERIAL#CMD"。該当キーだけを除去する。
function removeFromItemStats(data, mat, cmd) {
  const items = data.items;
  const removed = [];
  if (!items || typeof items !== "object" || Array.isArray(items)) return { data, removed };
  const key = `${mat}#${cmd}`;
  if (!(key in items)) return { data, removed };
  const nextItems = {};
  for (const [k, v] of Object.entries(items)) {
    if (k === key) { removed.push({ file: "item-stats", id: k }); continue; }
    nextItems[k] = v;
  }
  return { data: { ...data, items: nextItems }, removed };
}

// spellbooks.yml: spell-books[] (material は BOOK 固定) + catalysts{} (material フィールドあり)。
function removeFromSpellbooks(data, mat, cmd) {
  const removed = [];
  let next = data;
  const books = Array.isArray(data["spell-books"]) ? data["spell-books"] : null;
  if (books && mat === "BOOK") {
    const kept = [];
    let changed = false;
    for (const b of books) {
      if (b && typeof b === "object" && b["custom-model-data"] === cmd) {
        removed.push({ file: "spellbooks", id: b.id || "(no-id)" });
        changed = true;
        continue;
      }
      kept.push(b);
    }
    if (changed) next = { ...next, "spell-books": kept };
  }
  const catalysts = data.catalysts && typeof data.catalysts === "object" && !Array.isArray(data.catalysts)
    ? data.catalysts : null;
  if (catalysts) {
    const nextCat = {};
    let catChanged = false;
    for (const [id, entry] of Object.entries(catalysts)) {
      if (entry && typeof entry === "object"
          && String(entry.material || "").toUpperCase() === mat
          && entry["custom-model-data"] === cmd) {
        removed.push({ file: "spellbooks:catalysts", id });
        catChanged = true;
        continue;
      }
      nextCat[id] = entry;
    }
    if (catChanged) next = { ...next, catalysts: nextCat };
  }
  return { data: next, removed };
}

// configId のファイルデータから (material,cmd) を参照するエントリを取り除く。
// 戻り値: { data: 新データ(変更なしなら元と同一参照), removed: [{file,id}] }
function removeAllocationFromData(configId, data, material, cmd) {
  const mat = String(material || "").toUpperCase();
  if (!data || typeof data !== "object") return { data, removed: [] };
  switch (configId) {
    case "catalog":
      return removeFromEntryMap(data, "items", "material", "custom-model-data", "catalog", mat, cmd);
    case "item-stats":
      return removeFromItemStats(data, mat, cmd);
    case "materials":
      return removeFromEntryMap(data, "materials", "base_material", "custom_model_data", "materials", mat, cmd);
    case "external-items":
      return removeFromEntryMap(data, "items", "material", "custom-model-data", "external-items", mat, cmd);
    case "sourcejars":
      return removeFromEntryMap(data, "jars", "material", "custom-model-data", "sourcejars", mat, cmd);
    case "sourcelinks":
      return removeFromEntryMap(data, "items", "material", "custom-model-data", "sourcelinks", mat, cmd);
    case "spellbooks":
      return removeFromSpellbooks(data, mat, cmd);
    default:
      return { data, removed: [] };
  }
}

module.exports = {
  REFERENCE_ONLY_BASES,
  findOrphanAllocations,
  removeAllocationFromData
};
