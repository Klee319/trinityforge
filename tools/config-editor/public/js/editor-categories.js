"use strict";

// YAML `_editor` メタ: ゲームは無視。ConfigEditor の表示整理用。
// - categories: { <fixedTabKey>: [ { id, label, itemIds: [] } ] }  ネストタブ
// - orders: { <fixedTabKey>: [itemId, ...] }  並び順
// - itemTabs: { <itemId>: "weapon"|"armor"|"tool"|"other" }  固定タブ配置 (Material推論より優先)
// 注意: アクティブなネストタブは YAML に書かず、メモリ上 (WeakMap) のみで保持する。

(function () {
  const h = window.h;

  // host(YAMLルート) → { [tabKey]: activeCategoryId|"__all__" }
  // host に直接付けると getData のスプレッドで YAML に混入するため分離する。
  const activeByHost = new WeakMap();

  function activeMap(host) {
    if (!host || typeof host !== "object") return {};
    let m = activeByHost.get(host);
    if (!m) {
      m = {};
      activeByHost.set(host, m);
    }
    return m;
  }

  function ensureEditor(host) {
    if (!host || typeof host !== "object") return { categories: {} };
    if (!host._editor || typeof host._editor !== "object") host._editor = {};
    if (!host._editor.categories || typeof host._editor.categories !== "object") {
      host._editor.categories = {};
    }
    // 過去に誤って YAML へ保存された UI 状態を読み込み時に除去する。
    if (Object.prototype.hasOwnProperty.call(host, "_editorActive")) {
      const leaked = host._editorActive;
      if (leaked && typeof leaked === "object") {
        const m = activeMap(host);
        for (const [k, v] of Object.entries(leaked)) {
          if (v != null && m[k] == null) m[k] = v;
        }
      }
      delete host._editorActive;
    }
    return host._editor;
  }

  function listCategories(host, tabKey) {
    const ed = ensureEditor(host);
    if (!Array.isArray(ed.categories[tabKey])) ed.categories[tabKey] = [];
    return ed.categories[tabKey];
  }

  /**
   * Renders nested category tabs + add/rename/delete for one fixed tab.
   * @param {object} host YAML root object (mutated)
   * @param {string} tabKey fixed tab id (weapon, armor, ...)
   * @param {function} onFilterChange 表示フィルタ変更時 (タブ切替)。バーは再構築しない。
   * @param {function} [onStructureChange] カテゴリ追加/削除時。バー再構築が必要。
   * @returns {HTMLElement}
   */
  window.renderEditorCategoryBar = function renderEditorCategoryBar(host, tabKey, onFilterChange, onStructureChange) {
    ensureEditor(host);
    const cats = listCategories(host, tabKey);
    const bar = h("div", { class: "editor-cat-bar" });
    const am = activeMap(host);
    if (!am[tabKey]) am[tabKey] = "__all__";

    function isVirtualCatId(id) {
      return id === "__all__" || id === "__unset__";
    }

    function syncActiveUi() {
      const active = am[tabKey] || "__all__";
      bar.querySelectorAll(".recipe-tab[data-cat-id]").forEach((btn) => {
        btn.classList.toggle("active", btn.getAttribute("data-cat-id") === active);
      });
      const placeholder = isVirtualCatId(active);
      for (const sel of [".editor-cat-delete", ".editor-cat-rename"]) {
        const btn = bar.querySelector(sel);
        if (!btn) continue;
        btn.disabled = placeholder;
        btn.classList.toggle("is-placeholder", placeholder);
        if (placeholder) btn.setAttribute("aria-hidden", "true");
        else btn.removeAttribute("aria-hidden");
      }
    }

    function notifyFilter() {
      if (typeof onFilterChange === "function") onFilterChange();
    }
    function notifyStructure() {
      if (typeof onStructureChange === "function") onStructureChange();
      else notifyFilter();
    }

    const allBtn = h("button", {
      class: "recipe-tab", type: "button",
      onclick: () => { am[tabKey] = "__all__"; syncActiveUi(); notifyFilter(); }
    }, [h("span", { text: "すべて" })]);
    allBtn.setAttribute("data-cat-id", "__all__");
    bar.appendChild(allBtn);

    const unsetBtn = h("button", {
      class: "recipe-tab", type: "button",
      onclick: () => { am[tabKey] = "__unset__"; syncActiveUi(); notifyFilter(); }
    }, [h("span", { text: "未設定" })]);
    unsetBtn.setAttribute("data-cat-id", "__unset__");
    bar.appendChild(unsetBtn);

    let dragCatId = null;

    for (const cat of cats) {
      const btn = h("button", {
        class: "recipe-tab editor-cat-tab", type: "button", draggable: true,
        onclick: () => { am[tabKey] = cat.id; syncActiveUi(); notifyFilter(); }
      }, [h("span", { text: cat.label || cat.id })]);
      btn.setAttribute("data-cat-id", cat.id);
      btn.addEventListener("dragstart", (e) => {
        dragCatId = cat.id;
        btn.classList.add("dragging");
        e.dataTransfer.effectAllowed = "move";
        e.dataTransfer.setData("text/plain", cat.id);
      });
      btn.addEventListener("dragend", () => {
        dragCatId = null;
        btn.classList.remove("dragging");
        bar.querySelectorAll(".editor-cat-tab").forEach((b) => {
          b.classList.remove("drag-over-before", "drag-over-after");
        });
      });
      bar.appendChild(btn);
    }

    bar.addEventListener("dragover", (e) => {
      if (!dragCatId) return;
      const tab = e.target.closest(".editor-cat-tab");
      if (!tab || !bar.contains(tab)) return;
      const targetId = tab.getAttribute("data-cat-id");
      if (!targetId || targetId === dragCatId) return;
      e.preventDefault();
      bar.querySelectorAll(".editor-cat-tab").forEach((b) => {
        b.classList.remove("drag-over-before", "drag-over-after");
      });
      const rect = tab.getBoundingClientRect();
      const before = e.clientX < rect.left + rect.width / 2;
      tab.classList.add(before ? "drag-over-before" : "drag-over-after");
      e.dataTransfer.dropEffect = "move";
      tab.dataset.dropBefore = before ? "1" : "0";
    });

    bar.addEventListener("drop", (e) => {
      if (!dragCatId) return;
      const tab = e.target.closest(".editor-cat-tab");
      if (!tab || !bar.contains(tab)) return;
      e.preventDefault();
      const targetId = tab.getAttribute("data-cat-id");
      if (!targetId || targetId === dragCatId) return;
      const fromIdx = cats.findIndex((c) => c.id === dragCatId);
      let toIdx = cats.findIndex((c) => c.id === targetId);
      if (fromIdx < 0 || toIdx < 0) return;
      const before = tab.dataset.dropBefore !== "0";
      const [moved] = cats.splice(fromIdx, 1);
      if (fromIdx < toIdx) toIdx--;
      if (!before) toIdx++;
      cats.splice(Math.max(0, toIdx), 0, moved);
      dragCatId = null;
      notifyStructure();
    });

    bar.appendChild(h("button", {
      class: "btn-small", type: "button", text: "+ カテゴリ",
      onclick: () => {
        const label = prompt("新しいカテゴリ名");
        if (!label || !label.trim()) return;
        let id = label.trim().toLowerCase().replace(/[^a-z0-9_\-]+/g, "_").replace(/^_+|_+$/g, "");
        if (!id) id = "cat_" + Date.now();
        while (cats.some((c) => c.id === id)) id = id + "_" + Math.floor(Math.random() * 1000);
        cats.push({ id, label: label.trim(), itemIds: [] });
        am[tabKey] = "__all__";
        notifyStructure();
      }
    }));

    const renameBtn = h("button", {
      class: "btn-small editor-cat-rename", type: "button", text: "カテゴリ名変更",
      onclick: () => {
        const cur = am[tabKey] || "__all__";
        if (isVirtualCatId(cur)) return;
        const cat = cats.find((c) => c.id === cur);
        if (!cat) return;
        const label = prompt("新しいカテゴリ名", cat.label || cat.id);
        if (label == null || !label.trim()) return;
        cat.label = label.trim();
        notifyStructure();
      }
    });
    bar.appendChild(renameBtn);

    const delBtn = h("button", {
      class: "btn-small danger editor-cat-delete", type: "button", text: "カテゴリ削除",
      onclick: () => {
        if (isVirtualCatId(am[tabKey] || "__all__")) return;
        const cur = am[tabKey];
        const idx = cats.findIndex((c) => c.id === cur);
        if (idx < 0) return;
        if (!confirm(`カテゴリ「${cats[idx].label || cur}」を削除しますか？(アイテム自体は消えません)`)) return;
        cats.splice(idx, 1);
        am[tabKey] = "__all__";
        notifyStructure();
      }
    });
    bar.appendChild(delBtn);
    syncActiveUi();
    return bar;
  };

  /** Active nested category id for filtering, null for all, or "__unset__" for unassigned. */
  window.activeEditorCategory = function activeEditorCategory(host, tabKey) {
    if (!host) return null;
    ensureEditor(host);
    const a = activeMap(host)[tabKey];
    if (!a || a === "__all__") return null;
    if (a === "__unset__") return "__unset__";
    return a;
  };

  /**
   * Whether itemId belongs to active nested category (or all).
   * 空の itemIds は「未割当＝全件表示」にしない。所属は明示された id のみ通す。
   */
  window.itemInEditorCategory = function itemInEditorCategory(host, tabKey, itemId) {
    const active = window.activeEditorCategory(host, tabKey);
    if (!active) return true;
    if (active === "__unset__") {
      return !window.getItemEditorCategory(host, tabKey, itemId);
    }
    const cats = listCategories(host, tabKey);
    const cat = cats.find((c) => c.id === active);
    // 未知/削除済みタブ id が残っていても全件を通さない。
    if (!cat) return false;
    if (!Array.isArray(cat.itemIds) || cat.itemIds.length === 0) return false;
    return cat.itemIds.includes(itemId);
  };

  /** Assign item to currently active nested category (if any). */
  window.assignItemToActiveEditorCategory = function assignItemToActiveEditorCategory(host, tabKey, itemId) {
    const active = activeMap(host)[tabKey] || "__all__";
    if (active === "__all__" || active === "__unset__") return;
    window.moveItemEditorCategory(host, tabKey, itemId, active);
  };

  function listOrders(host) {
    const ed = ensureEditor(host);
    if (!ed.orders || typeof ed.orders !== "object") ed.orders = {};
    return ed.orders;
  }

  /** @returns {string[]} display order for tabKey (mutates host._editor.orders). */
  window.getEditorOrder = function getEditorOrder(host, tabKey) {
    const orders = listOrders(host);
    if (!Array.isArray(orders[tabKey])) orders[tabKey] = [];
    return orders[tabKey];
  };

  window.setEditorOrder = function setEditorOrder(host, tabKey, orderedIds) {
    listOrders(host)[tabKey] = Array.isArray(orderedIds) ? orderedIds.slice() : [];
  };

  /**
   * 画面に見えているカードだけ並べ替えた結果を、タブ全体の order にマージする。
   * 見える分だけで setEditorOrder すると他カテゴリの並びが消えて「リセット」に見えるため。
   */
  window.mergeVisibleEditorOrder = function mergeVisibleEditorOrder(host, tabKey, visibleOrderedIds) {
    const visible = Array.isArray(visibleOrderedIds) ? visibleOrderedIds.filter(Boolean) : [];
    const full = window.getEditorOrder(host, tabKey).slice();
    const vis = new Set(visible);
    const merged = [];
    let inserted = false;
    for (const id of full) {
      if (vis.has(id)) {
        if (!inserted) {
          merged.push(...visible);
          inserted = true;
        }
      } else {
        merged.push(id);
      }
    }
    if (!inserted) merged.push(...visible);
    for (const id of visible) {
      if (!merged.includes(id)) merged.push(id);
    }
    window.setEditorOrder(host, tabKey, merged);
    return merged;
  };

  /**
   * YAML/オブジェクトのキー順を display order に合わせて詰め直す (_editor.orders が欠けても並びに残る)。
   */
  window.reorderObjectKeys = function reorderObjectKeys(map, orderedIds) {
    if (!map || typeof map !== "object" || Array.isArray(map)) return;
    const order = Array.isArray(orderedIds) ? orderedIds : [];
    const rebuilt = {};
    for (const id of order) {
      if (Object.prototype.hasOwnProperty.call(map, id)) rebuilt[id] = map[id];
    }
    for (const id of Object.keys(map)) {
      if (!Object.prototype.hasOwnProperty.call(rebuilt, id)) rebuilt[id] = map[id];
    }
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  };

  window.appendEditorOrder = function appendEditorOrder(host, tabKey, itemId) {
    const order = window.getEditorOrder(host, tabKey);
    if (!order.includes(itemId)) order.push(itemId);
  };

  window.sortIdsByEditorOrder = function sortIdsByEditorOrder(host, tabKey, ids) {
    const order = window.getEditorOrder(host, tabKey);
    const rank = new Map();
    order.forEach((id, i) => rank.set(id, i));
    return ids.slice().sort((a, b) => {
      const ia = rank.has(a) ? rank.get(a) : Number.MAX_SAFE_INTEGER;
      const ib = rank.has(b) ? rank.get(b) : Number.MAX_SAFE_INTEGER;
      if (ia !== ib) return ia - ib;
      return String(a).localeCompare(String(b));
    });
  };

  window.getItemEditorCategory = function getItemEditorCategory(host, tabKey, itemId) {
    for (const cat of listCategories(host, tabKey)) {
      if (!Array.isArray(cat.itemIds) || cat.itemIds.length === 0) continue;
      if (cat.itemIds.includes(itemId)) return cat.id;
    }
    return "";
  };

  /** categoryId null/empty removes item from all nested categories. */
  window.moveItemEditorCategory = function moveItemEditorCategory(host, tabKey, itemId, categoryId) {
    const cats = listCategories(host, tabKey);
    for (const cat of cats) {
      if (!Array.isArray(cat.itemIds)) continue;
      cat.itemIds = cat.itemIds.filter((id) => id !== itemId);
    }
    if (categoryId) {
      const cat = cats.find((c) => c.id === categoryId);
      if (cat) {
        if (!Array.isArray(cat.itemIds)) cat.itemIds = [];
        if (!cat.itemIds.includes(itemId)) cat.itemIds.push(itemId);
      }
    }
  };

  window.renameEditorCategoryItem = function renameEditorCategoryItem(host, tabKey, oldId, newId) {
    for (const cat of listCategories(host, tabKey)) {
      if (!Array.isArray(cat.itemIds)) continue;
      const idx = cat.itemIds.indexOf(oldId);
      if (idx >= 0) cat.itemIds[idx] = newId;
    }
    const order = window.getEditorOrder(host, tabKey);
    const oi = order.indexOf(oldId);
    if (oi >= 0) order[oi] = newId;
  };

  window.removeEditorCategoryItem = function removeEditorCategoryItem(host, tabKey, itemId) {
    window.moveItemEditorCategory(host, tabKey, itemId, null);
    const order = window.getEditorOrder(host, tabKey);
    const idx = order.indexOf(itemId);
    if (idx >= 0) order.splice(idx, 1);
  };

  // ---- 固定タブ(武器/防具/ツール/…)配置: Material推論に頼らず明示ピン留め ----
  // _editor.itemTabs: { <itemId>: "weapon"|"armor"|"tool"|"other" }
  function itemTabsMap(host) {
    const ed = ensureEditor(host);
    if (!ed.itemTabs || typeof ed.itemTabs !== "object") ed.itemTabs = {};
    return ed.itemTabs;
  }

  /** 表示タブ。ピンがあればそれ、無ければ Material 推論。 */
  window.getItemDisplayTab = function getItemDisplayTab(host, itemId, material) {
    const map = itemTabsMap(host);
    if (map[itemId]) return map[itemId];
    if (typeof window.inferItemCategory === "function") return window.inferItemCategory(material);
    return "other";
  };

  window.setItemDisplayTab = function setItemDisplayTab(host, itemId, tab) {
    if (!itemId || !tab) return;
    itemTabsMap(host)[itemId] = tab;
  };

  window.renameItemDisplayTab = function renameItemDisplayTab(host, oldId, newId) {
    const map = itemTabsMap(host);
    if (!Object.prototype.hasOwnProperty.call(map, oldId)) return;
    map[newId] = map[oldId];
    delete map[oldId];
  };

  window.removeItemDisplayTab = function removeItemDisplayTab(host, itemId) {
    delete itemTabsMap(host)[itemId];
  };

  /**
   * 表示タブを移動。ネストカテゴリ/並び順も from→to へ移す。
   * @param {string[]} [tabKeys] 既知の固定タブ一覧 (掃除用)
   */
  window.moveItemDisplayTab = function moveItemDisplayTab(host, itemId, toTab, tabKeys) {
    if (!itemId || !toTab) return;
    const from = itemTabsMap(host)[itemId] || null;
    window.setItemDisplayTab(host, itemId, toTab);
    const keys = Array.isArray(tabKeys) ? tabKeys : [from, toTab].filter(Boolean);
    for (const k of keys) {
      if (!k || k === toTab) continue;
      window.removeEditorCategoryItem(host, k, itemId);
    }
    if (typeof window.appendEditorOrder === "function") {
      window.appendEditorOrder(host, toTab, itemId);
    }
  };

  /**
   * カード上の「表示タブ」セレクト。斧/クワなど武器・ツール両用を明示移動する。
   * @param {Array<[string,string]>} tabOptions e.g. [["weapon","武器"],...]
   * @param {Object<string,function>} [externalHandlers] タブid→ハンドラ。別ファイルへの移動
   *   (例: catalog⇄materials) は itemTabs のピン替えでは表現できないため、該当タブが選ばれたら
   *   moveItemDisplayTab せずハンドラへ委譲する。ハンドラ側で移動/中止を完結させること。
   */
  window.renderItemTabSelect = function renderItemTabSelect(host, itemId, material, tabOptions, onChange, externalHandlers) {
    const opts = Array.isArray(tabOptions) ? tabOptions : [];
    const external = externalHandlers && typeof externalHandlers === "object" ? externalHandlers : {};
    const current = window.getItemDisplayTab(host, itemId, material);
    const picker = window.listSelect({
      value: current,
      className: "editor-tab-select",
      options: opts.map(([id, label]) => ({
        value: id,
        primary: label,
        secondary: id
      })),
      onChange: (v) => {
        if (typeof external[v] === "function") {
          external[v](itemId);
          return;
        }
        const keys = opts.map((p) => p[0]);
        window.moveItemDisplayTab(host, itemId, v, keys);
        if (typeof onChange === "function") onChange();
      }
    });
    return h("span", { class: "editor-cat-field inline-field" }, [
      h("span", { class: "mini-label", text: "表示タブ" }),
      picker
    ]);
  };

  /**
   * Per-card nested category selector.
   * @param {function} [onChange] called after itemIds update
   */
  window.renderEditorCategorySelect = function renderEditorCategorySelect(host, tabKey, itemId, onChange) {
    const cats = listCategories(host, tabKey);
    const current = window.getItemEditorCategory(host, tabKey, itemId);
    const picker = window.listSelect({
      value: current || "",
      className: "editor-cat-select",
      placeholder: "(未設定)",
      options: [{ value: "", primary: "(未設定)", secondary: "" }].concat(
        cats.map((cat) => ({
          value: cat.id,
          primary: cat.label || cat.id,
          // 日本語カテゴリ名などslug化できず "cat_<timestamp>" で自動採番されたidは
          // ユーザーに意味が無いので副表記に出さない。
          secondary: cat.id !== (cat.label || "") && !/^cat_\d+(_\d+)?$/.test(cat.id) ? cat.id : ""
        }))
      ),
      onChange: (v) => {
        window.moveItemEditorCategory(host, tabKey, itemId, v || null);
        if (typeof onChange === "function") onChange();
      }
    });
    return h("span", { class: "editor-cat-field inline-field" }, [
      h("span", { class: "mini-label", text: "カテゴリ" }),
      picker
    ]);
  };

  /**
   * 保存前に UI 専用キーを落とす。
   *
   * <p>2026-07-26 追加: `_editor.itemTabs` の**孤児掃除**。アイテムを削除しても
   * `_editor.itemTabs` のピン留めが残り続けるため、`stats/item-stats.yml` に幽霊エントリが
   * 102件たまっていた(手作業で一度掃除済み)。削除経路ごとに `removeItemDisplayTab` を
   * 呼び忘れないようにするより、保存直前に「`items` に実在しないidのピン」を落とす方が
   * 抜け漏れが無いのでここで行う。
   *
   * <p>`items` を持たないconfig(=このメタを使わない画面)では何もしない。
   * `categories` / `orders` は**触らない** — こちらのキーはアイテムidではなくカテゴリ名なので、
   * 同じ判定で消すと正当なカテゴリ定義まで落ちる。
   */
  window.pruneEditorUiState = function pruneEditorUiState(data) {
    if (!data || typeof data !== "object") return data;
    if (Object.prototype.hasOwnProperty.call(data, "_editorActive")) delete data._editorActive;
    pruneOrphanItemTabs(data);
    return data;
  };

  function pruneOrphanItemTabs(data) {
    const ed = data._editor;
    if (!ed || typeof ed !== "object") return;
    const tabs = ed.itemTabs;
    if (!tabs || typeof tabs !== "object") return;
    const items = data.items;
    // items が無い/オブジェクトでない config では判定材料が無いので触らない(安全側)。
    if (!items || typeof items !== "object" || Array.isArray(items)) return;
    for (const itemId of Object.keys(tabs)) {
      if (!Object.prototype.hasOwnProperty.call(items, itemId)) delete tabs[itemId];
    }
    if (Object.keys(tabs).length === 0) delete ed.itemTabs;
  }

  window.ensureEditorMeta = ensureEditor;
  window.listEditorCategories = listCategories;
})();
