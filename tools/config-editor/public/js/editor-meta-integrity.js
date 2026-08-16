"use strict";

// lib/editor-meta-integrity.js のミラー(ブラウザ側)。2ファイルは同じ定義を保つこと
// (docs/agent-context/config-editor.md「lib/ と public/js/ は同じ定義のミラー2本」)。
// 片方だけ直すと画面表示と保存後サーバ警告が無言で食い違う。
// 変更するときは lib 側もそのまま合わせて直す(ロジックは意図的に完全一致させてある)。

(function () {
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

  window.danglingEditorMetaIds = danglingEditorMetaIds;
  window.describeDanglingEditorMetaId = describeDanglingEditorMetaId;
})();
