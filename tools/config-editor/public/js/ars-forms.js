"use strict";

// ArsPaper materials.yml / threads.yml 専用フォーム。
//
//   materials.<id>: base_material / custom_model_data / display_name / lore / recipe(workbench|ritual)
//     (name_color は廃止済み。色は display_name 内の&コードで表現する。既存ファイルの残存値のみロスレス温存)
//   threads.<id> : display_name / 効果パラメータ(regen-bonus 等) / stackable / max / recipe(ritual)
//
// 設計方針 (recipes.js と同一):
//   - parse/serialize の中核はブラウザ非依存の純関数として分離し、Node の往復テストで検証する。
//   - 往復ロスレスが最重要。エントリ/レシピのキー順・任意キーの有無・未知キーを完全に保つ。
//   - materials の recipe は catalog と同型 (method workbench|ritual)。plain object として往復ロスレス保持。
//   - threads の儀式レシピ DOM/parse は recipes.js を再利用 (結果無しの儀式)。
//   - lore は materials の欠落補完の本体 (プラグインは読むのに専用UIが無かった)。threads には lore を追加しない
//     (プラグイン非対応キーを書き込まないため)。

(function (root, isBrowser) {
  // Node/ブラウザ双方で recipes.js の純関数コアへアクセスする。
  const RC = isBrowser ? root.RECIPES : require("./recipes.js");
  const clone = RC.clone;

  // ============================================================
  // 純関数コア (ブラウザ非依存)
  // ============================================================

  // ---- materials エントリ ----
  const MATERIAL_KNOWN = new Set(["base_material", "custom_model_data", "display_name", "name_color", "lore", "recipe", "enchant_glow"]);

  function parseMaterialEntry(id, entry) {
    const e = entry && typeof entry === "object" && !Array.isArray(entry) ? entry : {};
    const has = (k) => Object.prototype.hasOwnProperty.call(e, k);
    const model = {
      id,
      _order: Object.keys(e), // エントリのキー順を保持
      _extra: {}, // 未知キー verbatim
      hasBaseMaterial: has("base_material"), baseMaterial: e.base_material,
      hasCmd: has("custom_model_data"), customModelData: e.custom_model_data,
      hasDisplayName: has("display_name"), displayName: e.display_name,
      hasNameColor: has("name_color"), nameColor: e.name_color,
      hasLore: has("lore"),
      lore: Array.isArray(e.lore) ? e.lore.slice() : (e.lore !== undefined ? e.lore : []),
      hasRecipe: has("recipe"),
      recipe: has("recipe") && e.recipe && typeof e.recipe === "object" && !Array.isArray(e.recipe)
        ? clone(e.recipe) : null,
      hasEnchantGlow: has("enchant_glow"), enchantGlow: e.enchant_glow
    };
    for (const k of Object.keys(e)) if (!MATERIAL_KNOWN.has(k)) model._extra[k] = clone(e[k]);
    return model;
  }

  function serializeMaterialEntry(model) {
    const out = {};
    const emitted = new Set();
    const emit = (k) => {
      emitted.add(k);
      switch (k) {
        case "base_material": if (model.hasBaseMaterial) out.base_material = model.baseMaterial; break;
        case "custom_model_data": if (model.hasCmd) out.custom_model_data = model.customModelData; break;
        case "display_name": if (model.hasDisplayName) out.display_name = model.displayName; break;
        case "name_color": if (model.hasNameColor) out.name_color = model.nameColor; break;
        case "lore": if (model.hasLore) out.lore = model.lore.slice(); break;
        case "recipe": if (model.hasRecipe && model.recipe) out.recipe = clone(model.recipe); break;
        case "enchant_glow": if (model.hasEnchantGlow) out.enchant_glow = model.enchantGlow; break;
        default: if (Object.prototype.hasOwnProperty.call(model._extra, k)) out[k] = clone(model._extra[k]);
      }
    };
    for (const k of model._order) emit(k);
    // UI で新規に有効化した任意キーが元順に無ければ末尾へ追加。
    if (model.hasBaseMaterial && !emitted.has("base_material")) out.base_material = model.baseMaterial;
    if (model.hasCmd && !emitted.has("custom_model_data")) out.custom_model_data = model.customModelData;
    if (model.hasDisplayName && !emitted.has("display_name")) out.display_name = model.displayName;
    if (model.hasNameColor && !emitted.has("name_color")) out.name_color = model.nameColor;
    if (model.hasLore && !emitted.has("lore")) out.lore = model.lore.slice();
    if (model.hasRecipe && model.recipe && !emitted.has("recipe")) out.recipe = clone(model.recipe);
    if (model.hasEnchantGlow && !emitted.has("enchant_glow")) out.enchant_glow = model.enchantGlow;
    return out;
  }

  // ---- threads エントリ ----
  const THREAD_EFFECT_KEYS = ["regen-bonus", "mana-bonus", "recovery", "cost-reduction", "slots"];
  const THREAD_EFFECT_SET = new Set(THREAD_EFFECT_KEYS);
  const THREAD_KNOWN = new Set(["display_name", "stackable", "max", "recipe"].concat(THREAD_EFFECT_KEYS));

  function parseThreadEntry(id, entry) {
    const e = entry && typeof entry === "object" && !Array.isArray(entry) ? entry : {};
    const has = (k) => Object.prototype.hasOwnProperty.call(e, k);
    const model = {
      id,
      _order: Object.keys(e),
      _extra: {},
      hasDisplayName: has("display_name"), displayName: e.display_name,
      effects: {}, // 効果パラメータ (存在するものだけ、順序は元キー順に従う)
      hasStackable: has("stackable"), stackable: e.stackable,
      hasMax: has("max"), max: e.max,
      hasRecipe: has("recipe"),
      recipe: RC.parseRitualRecipe(e.recipe)
    };
    for (const k of Object.keys(e)) {
      if (THREAD_EFFECT_SET.has(k)) model.effects[k] = e[k];
      else if (!THREAD_KNOWN.has(k)) model._extra[k] = clone(e[k]);
    }
    return model;
  }

  function serializeThreadEntry(model) {
    const out = {};
    const emitted = new Set();
    const emit = (k) => {
      emitted.add(k);
      if (k === "display_name") { if (model.hasDisplayName) out.display_name = model.displayName; }
      else if (k === "stackable") { if (model.hasStackable) out.stackable = model.stackable; }
      else if (k === "max") { if (model.hasMax) out.max = model.max; }
      else if (k === "recipe") { if (model.hasRecipe) out.recipe = RC.serializeRitualRecipe(model.recipe); }
      else if (THREAD_EFFECT_SET.has(k)) { if (Object.prototype.hasOwnProperty.call(model.effects, k)) out[k] = model.effects[k]; }
      else if (Object.prototype.hasOwnProperty.call(model._extra, k)) out[k] = clone(model._extra[k]);
    };
    for (const k of model._order) emit(k);
    // 新規追加された効果/キーを末尾へ (元順に無いもの)。
    for (const k of THREAD_EFFECT_KEYS) if (Object.prototype.hasOwnProperty.call(model.effects, k) && !emitted.has(k)) out[k] = model.effects[k];
    if (model.hasStackable && !emitted.has("stackable")) out.stackable = model.stackable;
    if (model.hasMax && !emitted.has("max")) out.max = model.max;
    if (model.hasRecipe && !emitted.has("recipe")) out.recipe = RC.serializeRitualRecipe(model.recipe);
    return out;
  }

  const CORE = {
    parseMaterialEntry, serializeMaterialEntry,
    parseThreadEntry, serializeThreadEntry,
    THREAD_EFFECT_KEYS
  };
  root.ARS_FORMS = CORE;
  if (typeof module !== "undefined" && module.exports) module.exports = CORE;

  // ============================================================
  // DOM 部品 (ブラウザ専用)
  // ============================================================
  if (!isBrowser) return;

  const h = window.h;
  const UI = window.RECIPES_UI; // 儀式 DOM 部品 (recipes.js 由来)

  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }
  function fieldRow(labelKey, control, labelText) {
    const labelEl = labelText ? h("span", { class: "form-label", text: labelText }) : window.fieldLabelEl(labelKey);
    return h("div", { class: "form-field" }, [labelEl, control]);
  }
  function uniqueId(base, models) {
    let name = base, i = 1;
    while (models.some((x) => x.id === name)) name = `${base}_${i++}`;
    return name;
  }
  // 新規レシピ (core-item/pedestal-items/source を持つ空の儀式)。
  function newRitualRecipe() {
    return window.RECIPES.parseRitualRecipe({ "core-item": "", "pedestal-items": [], source: 0 });
  }

  // ============================================================
  // materials.yml
  // ============================================================
  window.buildMaterialsForm = function buildMaterialsForm(data, opts) {
    const options = opts && typeof opts === "object" ? opts : {};
    // アイテムカタログ分割ビューのネストカテゴリ (_editor.categories) 連携キー。
    // 未指定 (旧呼び出し) はカテゴリUIなしの従来表示。
    const editorCategoryKey = options.editorCategoryKey || null;
    // ファイル跨ぎ移動 (カタログタブ=catalog.yml へ素材を移す)。
    // { id:"catalog", data:<catalog.ymlのデータ>, dirty:false } を split-views 経由で受け取り、
    // 移動が発生したら dirty=true にして保存時に両ファイルへ書き込ませる。
    const crossFile = options.crossFile && options.crossFile.data ? options.crossFile : null;
    const src = data && typeof data === "object" ? data : {};
    const topKeys = Object.keys(src);
    if (!topKeys.includes("materials")) topKeys.push("materials");
    const extraTop = {};
    for (const k of Object.keys(src)) if (k !== "materials") extraTop[k] = src[k];

    const matSrc = src.materials && typeof src.materials === "object" ? src.materials : {};
    const models = Object.keys(matSrc).map((id) => ({ id, model: CORE.parseMaterialEntry(id, matSrc[id]) }));

    const root = h("div", { class: "dedicated-form materials-form card-list" });
    if (UI && UI.ensureCustomDatalist) UI.ensureCustomDatalist();
    // 折りたたみ状態(開いているidの集合)。既定は全て折りたたみ (skilltreeと同じUX)。再描画をまたいで保持する。
    const expanded = new Set();
    // ID/表示名での絞り込み (カタログ他タブの filterRow と同じ挙動)。
    let filterText = "";

    function visibleModels() {
      if (!editorCategoryKey || typeof window.itemInEditorCategory !== "function") return models;
      return models.filter((e) => window.itemInEditorCategory(src, editorCategoryKey, e.id));
    }

    // 検索テキストによる絞り込み (ID または表示名の部分一致・大小無視)。
    function filterModelsByText(list) {
      const q = filterText.trim().toLowerCase();
      if (!q) return list;
      return list.filter((entry) => {
        const plain = (window.stripDisplayNamePlain
          ? window.stripDisplayNamePlain(entry.model.displayName)
          : entry.model.displayName) || entry.id;
        return entry.id.toLowerCase().includes(q) || String(plain).toLowerCase().includes(q);
      });
    }

    // 折りたたみカードのドラッグ並べ替え (カタログと同じUX)。models 配列の順=保存されるYAMLキー順。
    function bindReorder() {
      if (root._reorderBound || typeof window.bindCollapsedCardReorder !== "function") return;
      root._reorderBound = true;
      window.bindCollapsedCardReorder(root, {
        getId: (el) => el.dataset.dragId || "",
        onReorder: (ordered) => {
          let full = ordered;
          if (editorCategoryKey && typeof window.mergeVisibleEditorOrder === "function") {
            // orders が空のままだと非表示カテゴリの並びが失われるため、現在の全体順で初期化してからマージ。
            if (typeof window.getEditorOrder === "function"
                && window.getEditorOrder(src, editorCategoryKey).length === 0) {
              window.setEditorOrder(src, editorCategoryKey, models.map((e) => e.id));
            }
            full = window.mergeVisibleEditorOrder(src, editorCategoryKey, ordered);
          }
          const rank = new Map(full.map((id, i) => [id, i]));
          const rankOf = (e) => (rank.has(e.id) ? rank.get(e.id) : Number.MAX_SAFE_INTEGER);
          models.sort((a, b) => rankOf(a) - rankOf(b));
          render();
        }
      });
    }

    /**
     * カタログタブ (catalog.yml) へのファイル跨ぎ移動。表示名(レガシーname_color残存分も連結)/loreは
     * &コード→MiniMessageへ変換し、変換できないコード(&r等)はプレーンテキストへフォールバック。
     * 実際のファイル書き込みは「保存」時 (materials.yml + catalog.yml の両方)。
     */
    function moveMaterialToCatalog(entry, tab, tabLabel) {
      if (!crossFile) return;
      const cat = crossFile.data;
      if (!cat.items || typeof cat.items !== "object") cat.items = {};
      if (Object.prototype.hasOwnProperty.call(cat.items, entry.id)) {
        alert(`catalog.yml に同じ id (${entry.id}) が既に存在するため移動できません。`);
        render();
        return;
      }
      if (!confirm(`「${entry.id}」をカタログの「${tabLabel}」タブ (catalog.yml) へ移動します。\n`
          + "表示名/loreは &コード → MiniMessage へ変換されます (変換できないコードはプレーン化)。"
          + "\n\n実ファイルへの反映は「保存」時に materials.yml / catalog.yml の両方へ書き込まれます。")) {
        render();
        return;
      }
      const m = entry.model;
      const toMM = (s) => {
        if (s == null || s === "") return "";
        const conv = window.COLORS && typeof window.COLORS.legacyToMiniMessage === "function"
          ? window.COLORS.legacyToMiniMessage(s) : null;
        return conv != null ? conv : (window.stripDisplayNamePlain ? window.stripDisplayNamePlain(s) : String(s));
      };
      const e = { material: m.baseMaterial || "PAPER" };
      if (m.hasCmd && m.customModelData != null) e["custom-model-data"] = m.customModelData;
      const legacyName = (typeof m.nameColor === "string" ? m.nameColor : "") + (m.displayName || "");
      const mmName = toMM(legacyName);
      if (mmName) e["display-name"] = mmName;
      // materials側の enchant_glow 既定は true (キー無し=光る)。catalog側は既定 false のため明示。
      if (m.hasEnchantGlow ? !!m.enchantGlow : true) e["enchant-glow"] = true;
      if (Array.isArray(m.lore) && m.lore.length) e.lore = m.lore.map(toMM);
      if (m.hasRecipe && m.recipe) e.recipe = clone(m.recipe);
      for (const k of Object.keys(m._extra || {})) e[k] = clone(m._extra[k]);
      cat.items[entry.id] = e;
      if (typeof window.setItemDisplayTab === "function") window.setItemDisplayTab(cat, entry.id, tab);
      if (typeof window.appendEditorOrder === "function") window.appendEditorOrder(cat, tab, entry.id);
      // 移動先(カタログの表示タブ)でも必ずどこかのカテゴリへ入れる。
      if (typeof window.ensureItemEditorCategory === "function") {
        window.ensureItemEditorCategory(cat, tab, entry.id);
      }
      // 素材側から除去 (ネストカテゴリ/並び順も掃除)。
      models.splice(models.indexOf(entry), 1);
      if (editorCategoryKey && typeof window.removeEditorCategoryItem === "function") {
        window.removeEditorCategoryItem(src, editorCategoryKey, entry.id);
      }
      crossFile.dirty = true;
      render();
    }

    function render() {
      root.innerHTML = "";
      const visible = filterModelsByText(visibleModels());
      // 検索欄 (ID/表示名で絞り込み。カタログ他タブの filterRow と同じ挙動)。
      // CMD一括割当ボタンはここには置かない。全ファイル横断で「リソースパック管理」画面
      // (respack-view.js の「全アイテムCMD一括採番＆保存」) に集約済み。
      const filterRow = h("div", { class: "item-stats-filter" }, [
        h("span", { class: "mini-label", text: "検索" }),
        h("input", {
          class: "field-input", type: "text", spellcheck: "false",
          placeholder: "ID/表示名で絞り込み",
          value: filterText,
          oninput: (e) => { filterText = e.target.value; render(); }
        })
      ]);
      root.appendChild(filterRow);
      if (models.length === 0) {
        root.appendChild(emptyGuide("中間素材がまだありません。", "「+ 素材追加」で、儀式で作成する中間素材を登録します。"));
      } else if (visible.length === 0) {
        root.appendChild(emptyGuide("該当する素材がありません。", "検索条件・カテゴリを確認してください。"));
      }
      for (const entry of visible) root.appendChild(renderCard(entry));
      bindReorder();
      root.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ 素材追加",
          onclick: () => {
            const id = uniqueId("new_material", models);
            const model = CORE.parseMaterialEntry(id, { base_material: "PAPER", custom_model_data: 0, display_name: "", lore: [] });
            models.push({ id, model });
            // アクティブなネストカテゴリで絞り込み中なら、そのカテゴリへ割り当てて見える位置に出す。
            if (editorCategoryKey && typeof window.assignItemToActiveEditorCategory === "function") {
              window.assignItemToActiveEditorCategory(src, editorCategoryKey, id);
            }
            expanded.add(id); // 新規追加は開いた状態で編集させる
            render();
          }
        })
      ]));
    }

    function renderCard(entry) {
      const model = entry.model;
      const idInput = h("input", { class: "field-input entry-id", value: entry.id, spellcheck: "false" });
      idInput.addEventListener("change", (ev) => {
        const nv = ev.target.value.trim();
        if (!nv || nv === entry.id) { ev.target.value = entry.id; return; }
        if (models.some((x) => x.id === nv)) { alert("同じidが存在します"); ev.target.value = entry.id; return; }
        const oldId = entry.id;
        entry.id = nv; model.id = nv;
        if (editorCategoryKey && typeof window.renameEditorCategoryItem === "function") {
          window.renameEditorCategoryItem(src, editorCategoryKey, oldId, nv);
        }
        if (expanded.has(oldId)) { expanded.delete(oldId); expanded.add(nv); }
        render();
      });
      const plainDisplay = (window.stripDisplayNamePlain
        ? window.stripDisplayNamePlain(model.displayName)
        : model.displayName) || entry.id;

      const editChildren = [
        h("span", { class: "entry-key-label", text: "id" }), idInput
      ];
      // 表示タブ: catalog.yml が読めていればカタログ側のタブへファイル跨ぎで移動できる。
      // 読めていない場合 (旧呼び出し等) は従来どおり素材固定の無効セレクト。
      const catalogTabs = Array.isArray(window.CATALOG_CATEGORIES) ? window.CATALOG_CATEGORIES : [];
      if (crossFile && catalogTabs.length) {
        editChildren.push(h("span", {
          class: "editor-cat-field inline-field",
          title: "カタログ側のタブを選ぶと catalog.yml へ移動します (表示名/loreは自動変換)。"
        }, [
          h("span", { class: "mini-label", text: "表示タブ" }),
          window.listSelect({
            value: "material",
            className: "editor-tab-select",
            options: [{ value: "material", primary: "素材", secondary: "material" }].concat(
              catalogTabs.map(([tid, tlabel]) => ({ value: tid, primary: tlabel, secondary: tid }))
            ),
            onChange: (v) => {
              if (v === "material") return;
              const found = catalogTabs.find(([tid]) => tid === v);
              moveMaterialToCatalog(entry, v, found ? found[1] : v);
            }
          })
        ]));
      } else {
        editChildren.push(h("span", {
          class: "editor-cat-field inline-field",
          title: "素材は materials.yml 専用のため、他の表示タブへは移動できません。"
        }, [
          h("span", { class: "mini-label", text: "表示タブ" }),
          window.listSelect({
            value: "material",
            disabled: true,
            className: "editor-tab-select",
            options: [{ value: "material", primary: "素材", secondary: "material" }]
          })
        ]));
      }
      if (editorCategoryKey && typeof window.renderEditorCategorySelect === "function") {
        editChildren.push(window.renderEditorCategorySelect(src, editorCategoryKey, entry.id, () => render()));
      }
      editChildren.push(
        h("div", { class: "spacer" }),
        h("button", {
          class: "btn-small", type: "button", text: "複製",
          onclick: () => {
            const copyId = uniqueId(entry.id + "_copy", models);
            const dup = CORE.parseMaterialEntry(copyId, CORE.serializeMaterialEntry(model));
            dup.id = copyId;
            models.splice(models.indexOf(entry) + 1, 0, { id: copyId, model: dup });
            // 複製元と同じネストカテゴリへ割り当てる (絞り込み中でも見失わない)。
            if (editorCategoryKey && typeof window.getItemEditorCategory === "function"
                && typeof window.moveItemEditorCategory === "function") {
              const cat = window.getItemEditorCategory(src, editorCategoryKey, entry.id);
              if (cat) window.moveItemEditorCategory(src, editorCategoryKey, copyId, cat);
            }
            render();
          }
        }),
        h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => {
            models.splice(models.indexOf(entry), 1);
            if (editorCategoryKey && typeof window.removeEditorCategoryItem === "function") {
              window.removeEditorCategoryItem(src, editorCategoryKey, entry.id);
            }
            render();
          }
        })
      );

      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: plainDisplay }),
          h("span", { class: "entry-sum-id", text: entry.id })
        ]),
        h("div", { class: "entry-collapse-edit" }, editChildren)
      ];

      // ---- 左カラム: 入力 ----
      const preview = window.buildTooltipPreview();
      function refreshPreview() {
        // M-2: display_name を name_color で着色してプレビュー。
        // Java側 (ConfigurableMaterial) は nameColor + displayName を legacy(&コード)として
        // 解釈するため、プレビューも同じ連結 + legacy モードで描画する
        // (display_name 内の & コード着色もそのまま反映される)。
        const baseName = (model.displayName != null && model.displayName !== "") ? model.displayName : entry.id;
        const colorPrefix = typeof model.nameColor === "string" ? model.nameColor : "";
        preview.update({
          name: colorPrefix + baseName,
          nameMode: "legacy",
          loreLines: Array.isArray(model.lore) ? model.lore : [],
          loreMode: "legacy"
        });
      }

      const matHint = window.materialHintEl(model.baseMaterial);
      const matInput = window.materialInput(model.baseMaterial, "material-list", (v) => { model.baseMaterial = v; model.hasBaseMaterial = true; matHint.update(v); });

      const inputs = h("div", { class: "entry-inputs" }, [
        fieldRow("base_material", h("span", { class: "input-with-hint" }, [matInput, matHint])),
        fieldRow("custom_model_data", (() => {
          const wrap = h("span", { class: "cmd-field-row" });
          const cmdNumInput = window.numberInput(model.customModelData, (v) => { model.customModelData = v == null ? 0 : v; model.hasCmd = true; }, { int: true });
          wrap.appendChild(cmdNumInput);
          // M-4: entryだけでなく数値入力欄の表示値にも直接反映する (フル再描画はしない)。
          function syncAssignedCmd(cmd) {
            model.customModelData = cmd;
            model.hasCmd = true;
            cmdNumInput.value = String(cmd);
          }
          // 個別の「CMD自動割当」ボタンは廃止 (リスト先頭の「CMD一括割当」がカテゴリ単位で担う。
          // テクスチャ登録時はcmdTextureControlがCMD未設定なら自動採番する)。
          if (typeof window.cmdTextureControl === "function") {
            wrap.appendChild(window.cmdTextureControl({
              getMaterial: () => model.baseMaterial,
              getCmd: () => (model.hasCmd ? model.customModelData : null),
              getId: () => model.id,
              source: "materials",
              onAssigned: syncAssignedCmd
            }));
          }
          return wrap;
        })()),
        // 表示名も Lore説明と同じ GUI/簡易切替のリッチ入力 (legacy &コード) にする。
        // 名前の色は display_name 内の &コードで表現する (name_color パラメータは廃止。
        // 既存ファイルに残っている場合のみ parse/serialize がロスレスに温存する)。
        fieldRow("display_name", window.richTextInput(model.displayName, "legacy", (v) => { model.displayName = v; model.hasDisplayName = true; refreshPreview(); })),
        fieldRow("enchant_glow", window.checkboxInput(model.hasEnchantGlow ? model.enchantGlow : true, (v) => { model.enchantGlow = v; model.hasEnchantGlow = true; })),
        h("div", { class: "sub-title", text: "説明文 (lore)" }),
        renderLore(model, refreshPreview),
        renderMaterialRecipeSection(model)
      ]);

      const previewCol = h("div", { class: "entry-preview" }, [
        h("div", { class: "preview-label", text: "表示プレビュー" }),
        preview.element
      ]);

      refreshPreview();
      return window.collapsibleCard(head, [h("div", { class: "entry-2col" }, [inputs, previewCol])], {
        expanded: expanded.has(entry.id),
        onToggle: (open) => { if (open) expanded.add(entry.id); else expanded.delete(entry.id); },
        dragId: entry.id
      });

      function renderLore(m, refresh) {
        const box = h("div", { class: "lore-rows" });
        // 描画は読み取り専用。lore を持たないエントリに hasLore=true / [] を書き込まない。
        const lines = Array.isArray(m.lore) ? m.lore : [];
        lines.forEach((line, idx) => {
          const row = h("div", { class: "stat-row lore-row" });
          row.appendChild(window.richTextInput(line, "legacy", (v) => { m.lore[idx] = v; m.hasLore = true; refresh(); }));
          // M-7: 行の上下移動 (行配列の入替のみ、ロスレス維持)。
          row.appendChild(h("button", { class: "btn-small", type: "button", text: "↑", title: "上へ", onclick: () => { if (idx > 0) { const a = m.lore; const t = a[idx - 1]; a[idx - 1] = a[idx]; a[idx] = t; m.hasLore = true; render(); } } }));
          row.appendChild(h("button", { class: "btn-small", type: "button", text: "↓", title: "下へ", onclick: () => { const a = m.lore; if (idx < a.length - 1) { const t = a[idx + 1]; a[idx + 1] = a[idx]; a[idx] = t; m.hasLore = true; render(); } } }));
          row.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { m.lore.splice(idx, 1); m.hasLore = true; render(); } }));
          box.appendChild(row);
        });
        box.appendChild(h("button", { class: "btn-small", type: "button", text: "+ 行追加", onclick: () => { m.hasLore = true; if (!Array.isArray(m.lore)) m.lore = []; m.lore.push(""); render(); } }));
        return box;
      }

      function renderMaterialRecipeSection(m) {
        const entryLike = { recipe: m.hasRecipe ? m.recipe : undefined };
        const renderRecipe = window.renderCatalogRecipeSection;
        if (typeof renderRecipe !== "function") {
          return h("div", { class: "empty-hint", text: "レシピ UI を読み込めません (forms.js)" });
        }
        return renderRecipe(entryLike, () => {
          if (entryLike.recipe !== undefined && entryLike.recipe !== null) {
            m.hasRecipe = true;
            m.recipe = entryLike.recipe;
          } else {
            m.hasRecipe = false;
            m.recipe = null;
          }
          render();
        });
      }
    }

    render();
    function getData() {
      const out = {};
      const buildMaterials = () => { const m = {}; for (const e of models) m[e.id] = CORE.serializeMaterialEntry(e.model); return m; };
      for (const k of topKeys) {
        if (k === "materials") out.materials = buildMaterials();
        else out[k] = extraTop[k];
      }
      return out;
    }
    // rerender: ネストカテゴリバーのタブ切替 (split-views の rerenderForm) から呼ばれる。
    return { element: root, getData, rerender: render };
  };

  // ============================================================
  // threads.yml
  // ============================================================
  window.buildThreadsForm = function buildThreadsForm(data) {
    const src = data && typeof data === "object" ? data : {};
    const topKeys = Object.keys(src);
    if (!topKeys.includes("threads")) topKeys.push("threads");
    const extraTop = {};
    for (const k of Object.keys(src)) if (k !== "threads") extraTop[k] = src[k];

    const thSrc = src.threads && typeof src.threads === "object" ? src.threads : {};
    const models = Object.keys(thSrc).map((id) => ({ id, model: CORE.parseThreadEntry(id, thSrc[id]) }));

    const root = h("div", { class: "dedicated-form threads-form card-list" });
    if (UI && UI.ensureCustomDatalist) UI.ensureCustomDatalist();
    // 折りたたみ状態(開いているidの集合)。既定は全て折りたたみ (skilltreeと同じUX)。再描画をまたいで保持する。
    const expanded = new Set();

    // 効果パラメータの日本語ラベル (threads.yml ヘッダ準拠)。
    const EFFECT_LABELS = {
      "regen-bonus": "マナ回復速度ボーナス",
      "mana-bonus": "最大マナボーナス",
      "recovery": "マナ回復量(被弾/攻撃時)",
      "cost-reduction": "スペルコスト軽減率(%)",
      "slots": "バックパックスロット数"
    };

    function render() {
      root.innerHTML = "";
      if (models.length === 0) {
        root.appendChild(emptyGuide("スレッドがまだありません。", "「+ スレッド追加」で、効果と儀式レシピを持つスレッドを登録します。"));
      }
      for (const entry of models) root.appendChild(renderCard(entry));
      root.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ スレッド追加",
          onclick: () => {
            const id = uniqueId("new_thread", models);
            const model = CORE.parseThreadEntry(id, { display_name: "", recipe: { "core-item": "custom:thread_empty", "pedestal-items": [], source: 0 } });
            models.push({ id, model });
            expanded.add(id); // 新規追加は開いた状態で編集させる
            render();
          }
        })
      ]));
    }

    function renderCard(entry) {
      const model = entry.model;
      const idInput = h("input", { class: "field-input entry-id", value: entry.id, spellcheck: "false" });
      idInput.addEventListener("change", (ev) => {
        const nv = ev.target.value.trim();
        if (!nv || nv === entry.id) { ev.target.value = entry.id; return; }
        if (models.some((x) => x.id === nv)) { alert("同じidが存在します"); ev.target.value = entry.id; return; }
        entry.id = nv; model.id = nv;
      });
      const plainDisplay = (window.stripDisplayNamePlain
        ? window.stripDisplayNamePlain(model.displayName)
        : model.displayName) || entry.id;
      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: plainDisplay }),
          h("span", { class: "entry-sum-id", text: entry.id })
        ]),
        h("div", { class: "entry-collapse-edit" }, [
          h("span", { class: "entry-key-label", text: "id" }), idInput,
          h("div", { class: "spacer" }),
          h("button", {
            class: "btn-small", type: "button", text: "複製",
            onclick: () => {
              const copyId = uniqueId(entry.id + "_copy", models);
              const dup = CORE.parseThreadEntry(copyId, CORE.serializeThreadEntry(model));
              dup.id = copyId;
              models.splice(models.indexOf(entry) + 1, 0, { id: copyId, model: dup });
              render();
            }
          }),
          h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: () => { models.splice(models.indexOf(entry), 1); render(); } })
        ])
      ];

      const body = [];
      body.push(fieldRow("display_name", window.textInput(model.displayName, (v) => { model.displayName = v; model.hasDisplayName = true; }), "表示名 (display_name)"));

      // ---- 効果パラメータ ----
      body.push(h("div", { class: "sub-title", text: "効果パラメータ" }));
      body.push(renderEffects(model));

      // ---- stackable / max ----
      body.push(h("div", { class: "sub-title", text: "重複設定" }));
      body.push(renderStackable(model));

      // ---- 儀式レシピ ----
      body.push(h("div", { class: "sub-title", text: "儀式レシピ (recipe)" }));
      const ritualToggle = h("div", {});
      const rOn = h("input", { type: "checkbox", checked: model.hasRecipe, onchange: (e) => { model.hasRecipe = e.target.checked; if (e.target.checked && (!model.recipe || !model.recipe._order.length)) model.recipe = newRitualRecipe(); render(); } });
      ritualToggle.appendChild(h("label", { class: "result-default" }, [rOn, h("span", { text: "儀式レシピを設定する" })]));
      if (model.hasRecipe) {
        const ritual = h("div", { class: "recipe-body ritual-layout" });
        ritual.appendChild(UI.buildCoreSection(model.recipe, render, { coreLabel: "コアアイテム (中央)" }));
        ritual.appendChild(UI.buildPedestalSection(model.recipe, render));
        ritual.appendChild(UI.buildSourceRow(model.recipe, {}));
        ritualToggle.appendChild(ritual);
      }
      body.push(ritualToggle);

      return window.collapsibleCard(head, body, {
        expanded: expanded.has(entry.id),
        onToggle: (open) => { if (open) expanded.add(entry.id); else expanded.delete(entry.id); }
      });

      function renderEffects(m) {
        const box = h("div", { class: "effect-params-box" });
        const present = CORE.THREAD_EFFECT_KEYS.filter((k) => Object.prototype.hasOwnProperty.call(m.effects, k));
        if (present.length === 0) box.appendChild(h("div", { class: "empty-hint", text: "効果パラメータはありません。「+ 効果追加」で数値効果を足せます。" }));
        for (const k of present) {
          const row = h("div", { class: "stat-row" });
          row.appendChild(h("span", { class: "form-label", text: `${EFFECT_LABELS[k] || k} (${k})`, title: k }));
          row.appendChild(window.numberInput(m.effects[k], (v) => { m.effects[k] = v == null ? 0 : v; }));
          row.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { delete m.effects[k]; render(); } }));
          box.appendChild(row);
        }
        // 追加セレクト (未追加の効果キーのみ)。
        const remaining = CORE.THREAD_EFFECT_KEYS.filter((k) => !Object.prototype.hasOwnProperty.call(m.effects, k));
        if (remaining.length) {
          const sel = h("select", { class: "field-input" });
          sel.appendChild(h("option", { value: "", text: "+ 効果追加" }));
          for (const k of remaining) sel.appendChild(h("option", { value: k, text: `${EFFECT_LABELS[k] || k} (${k})` }));
          sel.addEventListener("change", (e) => { const k = e.target.value; if (!k) return; m.effects[k] = 0; render(); });
          box.appendChild(h("div", { class: "effect-add-row" }, [sel]));
        }
        return box;
      }

      function renderStackable(m) {
        const box = h("div", { class: "result-slot" });
        const stackOn = h("input", { type: "checkbox", checked: !!m.stackable, onchange: (e) => { m.stackable = e.target.checked; m.hasStackable = true; render(); } });
        box.appendChild(h("label", { class: "result-default" }, [stackOn, h("span", { text: "同じ防具に複数セット可 (stackable)" })]));
        const maxInput = window.numberInput(m.max, (v) => { m.max = v == null ? 1 : v; m.hasMax = true; }, { int: true });
        if (!m.stackable) { maxInput.disabled = true; maxInput.title = "stackable が有効なときのみ設定できます"; }
        box.appendChild(fieldRow("max", maxInput, "最大セット数 (max)"));
        return box;
      }
    }

    render();
    function getData() {
      const out = {};
      const buildThreads = () => { const m = {}; for (const e of models) m[e.id] = CORE.serializeThreadEntry(e.model); return m; };
      for (const k of topKeys) {
        if (k === "threads") out.threads = buildThreads();
        else out[k] = extraTop[k];
      }
      return out;
    }
    return { element: root, getData };
  };
})(typeof window !== "undefined" ? window : (typeof module !== "undefined" ? module.exports : this), typeof window !== "undefined" && typeof document !== "undefined");
