"use strict";

// 機能アイテムタブ (魔法カテゴリ・schema: ars-functional-items)。
//
// 2026-07-25 に定義が functional-items.yml の1ファイルへ統合された(旧: catalog.yml 3件 +
// items.yml レシピに分裂)。編集可能キーは display-name / lore / enchant-glow / material(一部) /
// recipe の5つのみで、全て単一ファイル内で完結する(旧来の source:"catalog"|"items" 振り分けや
// recipeKey(waystone_craft 等)の特別扱いは、統合により不要になったため削除した)。
//
// 対象7アイテム: dominion_wand / teleport_compass / pedestal / ritual_core /
//                scribing_table / waystone / source_berry
// 内部ID(このファイルのitems.<id>キー名そのもの)は fork Java 実装が直接参照する固定値のため
// editorからは新規追加/削除/リネーム不可(読み取り専用のIDチップとしてのみ表示)。
//
// material の編集可否は3件(dominion_wand/teleport_compass/source_berry=保持アイテム)のみ許可。
// 残り4件(ブロック系)は fork の FunctionalItemConfig.java#MATERIAL_OVERRIDE_ALLOWED で拒否され
// warning ログのみで無視される(TileState対応判定・儀式の近傍探索がMaterialに密結合のため)。
// この許可リストは Java 側が唯一の正典。JS側の MATERIAL_EDITABLE_IDS はその複製であり、
// test/functional-items-java-parity.test.js が FunctionalItemConfig.java のソースを直接
// 正規表現で読み取って自動ドリフト検知する(lib/gate-vocabulary.js の FEATURES と同じ手法)。

(function (root, isBrowser) {
  // ============================================================
  // 純関数コア (ブラウザ非依存。Node テストから直接 require 可能)
  // ============================================================

  function clone(v) {
    return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
  }

  // functional-items.yml items.<id> の正典7件 (ファイル内の並び順)。
  const FUNCTIONAL_ITEM_IDS = Object.freeze([
    "dominion_wand", "teleport_compass", "pedestal",
    "ritual_core", "scribing_table", "waystone", "source_berry"
  ]);

  // material 上書きが許可されるID (保持アイテムのみ)。
  // ★唯一の正典は FunctionalItemConfig.java の MATERIAL_OVERRIDE_ALLOWED。変更したら
  //   test/functional-items-java-parity.test.js を必ず流して同期を確認すること。
  const MATERIAL_EDITABLE_IDS = Object.freeze(["dominion_wand", "teleport_compass", "source_berry"]);

  function isMaterialEditable(id) {
    return MATERIAL_EDITABLE_IDS.indexOf(id) !== -1;
  }

  // functional-items.yml の生データ(items.<id>)を、正典7件が(無ければ空オブジェクトで)
  // 必ず存在する形に正規化する。元データは変更しない。未知のキー(想定外のid)は温存する
  // (削除により手編集データを壊さないため)。
  function normalizeFunctionalItemsData(data) {
    const out = clone(data && typeof data === "object" ? data : {}) || {};
    if (!out.items || typeof out.items !== "object") out.items = {};
    for (const id of FUNCTIONAL_ITEM_IDS) {
      if (!Object.prototype.hasOwnProperty.call(out.items, id)
          || out.items[id] === null || typeof out.items[id] !== "object") {
        out.items[id] = {};
      }
    }
    return out;
  }

  // 保存直前の整形: 空の lore 配列はキーごと落とす(表示用に補完した空配列をYAMLへ出力しない)。
  // それ以外のフィールドは呼び出し側 (UI側のハンドラ) が setOrDelete 相当で既に整理済みの前提。
  function serializeFunctionalItemsData(data) {
    const out = clone(data && typeof data === "object" ? data : {}) || {};
    if (!out.items || typeof out.items !== "object") return out;
    for (const id of Object.keys(out.items)) {
      const entry = out.items[id];
      if (!entry || typeof entry !== "object") continue;
      if (Array.isArray(entry.lore) && entry.lore.length === 0) delete entry.lore;
    }
    return out;
  }

  const CORE_LOGIC = {
    FUNCTIONAL_ITEM_IDS,
    MATERIAL_EDITABLE_IDS,
    isMaterialEditable,
    normalizeFunctionalItemsData,
    serializeFunctionalItemsData
  };

  root.FUNCTIONAL_ITEMS_CORE = CORE_LOGIC;
  if (typeof module !== "undefined" && module.exports) module.exports = CORE_LOGIC;

  // ============================================================
  // DOM 部品 (ブラウザ専用)
  // ============================================================
  if (!isBrowser) return;

  const h = window.h;
  const fieldRow = window.RECIPES_UI.fieldRow;

  // 空文字/未指定は該当キーを削除する (catalog タブの setOrDelete と同じ挙動)。
  function setOrDelete(obj, key, value) {
    if (value === "" || value === null || value === undefined) delete obj[key];
    else obj[key] = value;
  }

  const ITEM_LABELS = {
    dominion_wand: "ドミニオンワンド",
    teleport_compass: "テレポートコンパス",
    pedestal: "台座",
    ritual_core: "儀式の核",
    scribing_table: "筆記台",
    waystone: "ウェイストーン",
    source_berry: "ソースベリー"
  };

  window.buildFunctionalItemsForm = function buildFunctionalItemsForm(data, opts) {
    const working = CORE_LOGIC.normalizeFunctionalItemsData(data);
    const root = h("div", { class: "dedicated-form functional-items-form" });
    const listBox = h("div", { class: "card-list" });
    const expandedCards = new Set();

    root.appendChild(h("div", { class: "form-hint", text:
      "内部ID (このアイテムを識別するキー) はプログラム制御のため変更できません。"
      + " 表示名・lore・エンチャント光・レシピは全アイテムで編集できます。"
      + " material は「保持アイテム」3種(ワンド/コンパス/ベリー)のみ編集可能です"
      + "(台座/儀式の核/筆記台/ウェイストーンはブロック実装がMaterialに密結合のため、フォーク側で変更が無視されます)。"
    }));
    root.appendChild(listBox);

    // レシピ入力の custom: 候補サジェストは、カタログ/素材/items/threads/外部アイテムを横断して
    // 集める共通ロード(recipes.js の RECIPES_UI.ensureCustomDatalist)をそのまま使う。
    if (window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
      window.RECIPES_UI.ensureCustomDatalist().catch(() => {});
    }

    function render() {
      listBox.innerHTML = "";
      for (const id of CORE_LOGIC.FUNCTIONAL_ITEM_IDS) listBox.appendChild(renderCard(id));
    }

    function renderCard(id) {
      const entry = working.items[id];
      if (!Array.isArray(entry.lore)) entry.lore = [];
      const materialEditable = CORE_LOGIC.isMaterialEditable(id);

      const plainDisplay = window.stripDisplayNamePlain(entry["display-name"]) || ITEM_LABELS[id] || id;
      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: plainDisplay }),
          h("span", { class: "entry-sum-id", text: id })
        ])
      ];

      const idChip = h("div", { class: "func-item-meta" }, [
        h("div", {
          class: "func-item-chip is-readonly",
          title: "プログラム制御のため変更不可 (fork Java ハードコード)"
        }, [
          h("span", { class: "mini-label", text: "item id" }),
          h("span", { class: "func-item-chip-value", text: id })
        ])
      ]);

      // tooltip ライブプレビュー (catalog タブと同じ部品)。
      const preview = window.buildTooltipPreview();
      function refreshPreview() {
        const nm = entry["display-name"];
        preview.update({
          name: (nm != null && nm !== "") ? nm : (ITEM_LABELS[id] || id),
          nameMode: "minimessage",
          loreLines: Array.isArray(entry.lore) ? entry.lore : [],
          loreMode: "minimessage"
        });
      }

      const inputChildren = [];

      if (materialEditable) {
        const matHint = window.materialHintEl(entry.material);
        const matInput = window.materialInput(entry.material, "material-list", (v) => {
          setOrDelete(entry, "material", v);
          matHint.update(v);
        });
        inputChildren.push(fieldRow("material", h("span", { class: "input-with-hint" }, [matInput, matHint])));
      } else {
        inputChildren.push(h("div", { class: "empty-guide" }, [
          h("div", { class: "empty-guide-title", text: "material 編集不可" }),
          h("div", { class: "empty-guide-hint", text:
            "このアイテムはブロック実装(TileState対応判定・儀式の近傍探索)がMaterialに密結合しているため、"
            + "functional-items.yml に material を書いてもフォーク側で無視されます(警告ログのみ)。" })
        ]));
      }

      inputChildren.push(
        fieldRow("display-name", window.richTextInput(entry["display-name"], "minimessage", (v) => {
          setOrDelete(entry, "display-name", v);
          refreshPreview();
        })),
        fieldRow("enchant-glow", (() => {
          const row = h("label", { class: "form-field inline-check" });
          row.appendChild(window.checkboxInput(!!entry["enchant-glow"], (v) => {
            if (v) entry["enchant-glow"] = true; else delete entry["enchant-glow"];
          }));
          row.appendChild(h("span", { class: "form-label", text: "enchant aura (オンでエンチャント光)" }));
          return row;
        })()),
        h("div", { class: "sub-title", text: "フレーバー説明文 (lore)" }),
        window.renderLoreRows(entry.lore, "minimessage", refreshPreview, () => render())
      );

      const inputs = h("div", { class: "entry-inputs" }, [idChip].concat(inputChildren));
      const previewCol = h("div", { class: "entry-preview" }, [
        h("div", { class: "preview-label", text: "表示プレビュー" }),
        preview.element,
        h("div", { class: "preview-note", text: "品質ティア行・自動ステ行はここには表示されません。lore はその前に差し込まれるフレーバー説明文です。" })
      ]);

      refreshPreview();

      // レシピ編集UIはアイテムカテゴリ(catalog)タブと完全共通化する。結果アイテムは常にこの
      // エントリ自身(custom:<id>)のため結果アイテム欄は無く、renderCatalogRecipeSection側の
      // 仕様どおりそのまま合致する。
      const recipeSection = window.renderCatalogRecipeSection(entry, () => render(), working.items, id, { allowMirror: true });

      const card = window.collapsibleCard(head, [h("div", { class: "entry-2col" }, [inputs, previewCol]), recipeSection], {
        expanded: expandedCards.has(id),
        onToggle: (open) => { if (open) expandedCards.add(id); else expandedCards.delete(id); }
      });
      card.classList.add("recipe-card");
      return card;
    }

    render();

    return {
      element: root,
      getData: () => CORE_LOGIC.serializeFunctionalItemsData(working)
    };
  };
})(typeof window !== "undefined" ? window : (typeof module !== "undefined" ? module.exports : this), typeof window !== "undefined" && typeof document !== "undefined");
