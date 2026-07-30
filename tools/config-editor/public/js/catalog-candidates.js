"use strict";

// catalog.yml / materials.yml → item-stats・カタログ参照UI 共通の候補リスト。
//
// 2026-07-25: 「素材」タブ (ArsPaper materials.yml) が候補に出てこない不具合を修正。
//   従来は catalog.yml の items: しか見ておらず、materials.yml (別プラグイン・別ファイル・
//   snake_case キー) が構造的に候補へ入らなかった。
//   - materials.yml のデータ構造は ars-forms.js の parseMaterialEntry と同一に扱う
//     (base_material / custom_model_data / display_name)。
//   - snake_case → 候補オブジェクトの正規化はこの関数の内部に閉じ込め、呼び出し側へは
//     ばらまかない。
//   - materials 由来の候補には tab: "material" を付け、util.js の catalogItemSuggest が
//     受け取る filterCandidate オプションで絞り込めるようにする。
//   - catalog.yml と materials.yml は同じIDを定義できてしまうため、衝突時は catalog.yml を
//     先勝ちさせる (util.js:setCustomItemCandidates の seen セット方式と同じ「先に登録した
//     方が勝つ」流儀に合わせる)。
//
// この関数自体はブラウザ非依存 (window 参照は分岐でガード) にして、Node テストの往復検証
// から直接 require できるようにする。
(function (root, isBrowser) {
  function normalizeMaterial(raw) {
    return raw == null ? "" : String(raw).trim().toUpperCase();
  }

  function normalizeCmd(raw) {
    if (raw == null || raw === "") return null;
    const n = Number(raw);
    return Number.isInteger(n) && n >= 0 ? n : null;
  }

  // 2026-07-30: 「ソースベリーがセレクトメニューから参照できない」への対処。
  // 候補源が catalog.yml + materials.yml の2本しかなく、ArsPaper の特殊アイテム
  // (functional-items.yml: ソースベリー/ウェイストーン/儀式の核 …)、ソースジャー
  // (sourcejars.yml)、触媒 (spellbooks.yml catalysts:) が構造的に候補へ入らなかった。
  // どれも catalog.yml のレシピから custom:<id> 素材として参照されうるので、
  // 「id → material/CMD/表示名」が yml から取れるものは全部ここへ足す。
  //
  // スレッド (threads.yml) と魔導書 (spellbooks.yml spell-books:) は yml に base material が
  // 無く、素材が Java 側 (ThreadType / SpellBook) にしか無いため、ここでは扱えない。
  // 素材を推測で埋めると候補UIのアイコンが黙って化けるので、あえて除外している。
  const EXTRA_SOURCES = Object.freeze([
    { key: "functionalItems", root: "items", tab: "other" },
    { key: "sourcejars", root: "jars", tab: "other" },
    { key: "catalysts", root: "catalysts", tab: "other" }
  ]);

  /**
   * @param catalogData   items/catalog.yml
   * @param materialsData ArsPaper materials.yml
   * @param extraData     {functionalItems, sourcejars, catalysts} — 省略可。
   *                      catalysts は spellbooks.yml をそのまま渡してよい(catalysts: 節だけ見る)。
   */
  function buildCatalogCandidates(catalogData, materialsData, extraData) {
    const host = catalogData && typeof catalogData === "object" ? catalogData : {};
    const items = host.items && typeof host.items === "object" ? host.items : {};
    const out = [];
    const seen = new Set();

    for (const [id, entry] of Object.entries(items)) {
      if (!entry || typeof entry !== "object") continue;
      const material = normalizeMaterial(entry.material);
      if (!material) continue;
      const cmd = normalizeCmd(entry["custom-model-data"]);
      let tab = null;
      if (isBrowser && typeof root.getItemDisplayTab === "function") {
        tab = root.getItemDisplayTab(host, id, material);
      } else if (isBrowser && typeof root.inferItemCategory === "function") {
        tab = root.inferItemCategory(material);
      }
      out.push({
        id,
        displayName: entry["display-name"] == null ? "" : String(entry["display-name"]),
        material,
        cmd,
        tab: tab || "other"
      });
      seen.add(id);
    }

    // materials.yml (ArsPaper 中間素材)。ars-forms.js の parseMaterialEntry が扱う実キー
    // (base_material / custom_model_data / display_name) に合わせて正規化する。
    const materialsRoot = materialsData && typeof materialsData === "object" ? materialsData : {};
    const materials = materialsRoot.materials && typeof materialsRoot.materials === "object"
      ? materialsRoot.materials : {};
    for (const [id, entry] of Object.entries(materials)) {
      if (seen.has(id)) continue; // catalog.yml が先勝ち
      if (!entry || typeof entry !== "object") continue;
      const material = normalizeMaterial(entry.base_material);
      if (!material) continue;
      const cmd = normalizeCmd(entry.custom_model_data);
      out.push({
        id,
        displayName: entry.display_name == null ? "" : String(entry.display_name),
        material,
        cmd,
        tab: "material"
      });
      seen.add(id);
    }

    // ArsPaper の特殊アイテム/ソースジャー/触媒。キー綴りは catalog.yml と同じ kebab-case
    // (display-name / custom-model-data)。materials.yml だけが snake_case なので、
    // 上のループとは別扱いにしてある。
    const extras = extraData && typeof extraData === "object" ? extraData : {};
    for (const source of EXTRA_SOURCES) {
      const host = extras[source.key];
      if (!host || typeof host !== "object") continue;
      const entries = host[source.root];
      if (!entries || typeof entries !== "object" || Array.isArray(entries)) continue;
      for (const [id, entry] of Object.entries(entries)) {
        if (seen.has(id)) continue; // catalog.yml / materials.yml が先勝ち
        if (!entry || typeof entry !== "object") continue;
        const material = normalizeMaterial(entry.material);
        if (!material) continue;
        out.push({
          id,
          displayName: entry["display-name"] == null ? "" : String(entry["display-name"]),
          material,
          cmd: normalizeCmd(entry["custom-model-data"]),
          tab: source.tab
        });
        seen.add(id);
      }
    }

    return out;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { buildCatalogCandidates };
  }
  if (!isBrowser) return;
  root.buildCatalogCandidates = buildCatalogCandidates;
})(typeof window !== "undefined" ? window : globalThis, typeof window !== "undefined" && typeof document !== "undefined");
