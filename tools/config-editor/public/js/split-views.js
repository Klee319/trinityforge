"use strict";

// サイドバー分割ビュー: 固定ハブタブなし。各ページは任意カテゴリタブ(_editor.categories)のみ。

(function () {
  const h = window.h;

  function rerenderForm(form) {
    if (!form) return;
    if (typeof form.rerender === "function") form.rerender();
    else if (typeof form.rerenderList === "function") form.rerenderList();
  }

  function withCategoryBar(host, tabKey, formEl, form, flowCategoryBar = false) {
    const root = h("div", { class: "split-view" });
    const nestBar = h("div", { class: `hub-subtabs${flowCategoryBar ? " hub-subtabs-flow" : ""}` });
    const body = h("div", { class: "hub-body" });
    function renderBar() {
      nestBar.innerHTML = "";
      if (typeof window.renderEditorCategoryBar === "function") {
        nestBar.appendChild(window.renderEditorCategoryBar(
          host,
          tabKey,
          () => rerenderForm(form),
          () => { renderBar(); rerenderForm(form); }
        ));
      }
    }
    renderBar();
    body.appendChild(formEl);
    root.appendChild(nestBar);
    root.appendChild(body);
    return root;
  }

  /**
   * @param {object} opts
   * @param {string} opts.configId primary save target
   * @param {object} opts.data loaded YAML
   * @param {string} [opts.categoryKey] _editor.categories key
   * @param {"catalog"|"materials"|"threads"|"item-stats"|"spellbooks"|"spellbooks-catalysts"|"spellbooks-books"|"thread-bundle"|"ritual-effects"} opts.type
   */
  window.buildSplitConfigView = function buildSplitConfigView(opts) {
    const o = opts || {};
    const data = o.data && typeof o.data === "object" ? o.data : {};
    const categoryKey = o.categoryKey || "default";
    let form;
    let getData;
    let extraGets = [];

    // ファイル跨ぎ移動 (catalog⇄materials): 相手ファイルのデータを受け取っていれば
    // フォームへ渡し、移動が発生した保存時だけ extraGets 経由で相手ファイルも書き込む。
    // counterpartDirty はコンフリクトマージ後の再構築でステージ済み移動を引き継ぐケース。
    const cross = o.counterpartData && o.counterpartId
      ? { id: o.counterpartId, data: o.counterpartData, dirty: !!o.counterpartDirty } : null;

    if (o.type === "catalog") {
      form = window.buildCatalogForm(data, {
        hubMode: true,
        initialCategory: o.itemCategory || "weapon",
        editorCategoryKey: categoryKey,
        crossFile: cross
      });
      if (cross) extraGets.push({ id: cross.id, getData: () => cross.data, when: () => cross.dirty });
      if (o.itemCategory && typeof form.setActiveCategory === "function") {
        form.setActiveCategory(o.itemCategory);
      }
      getData = () => {
        const d = form.getData();
        // form 側 working が _editor を新規作成/更新している場合はそれを優先。
        // 旧: if (data._editor) d._editor = data._editor だと、form が同じ参照でも
        // 浅いコピー後の取りこぼしや古いスナップショットで並びが消えることがあった。
        if (d._editor) {
          data._editor = d._editor;
        } else if (data._editor) {
          d._editor = data._editor;
        }
        return d;
      };
    } else if (o.type === "materials") {
      form = window.buildMaterialsForm(data, { editorCategoryKey: categoryKey, crossFile: cross });
      if (cross) extraGets.push({ id: cross.id, getData: () => cross.data, when: () => cross.dirty });
      getData = () => {
        const d = form.getData();
        // form 側 working が _editor を新規作成/更新している場合はそれを優先。
        // 旧: if (data._editor) d._editor = data._editor だと、form が同じ参照でも
        // 浅いコピー後の取りこぼしや古いスナップショットで並びが消えることがあった。
        if (d._editor) {
          data._editor = d._editor;
        } else if (data._editor) {
          d._editor = data._editor;
        }
        return d;
      };
    } else if (o.type === "threads") {
      form = window.buildThreadsForm(data);
      getData = () => {
        const d = form.getData();
        // form 側 working が _editor を新規作成/更新している場合はそれを優先。
        // 旧: if (data._editor) d._editor = data._editor だと、form が同じ参照でも
        // 浅いコピー後の取りこぼしや古いスナップショットで並びが消えることがあった。
        if (d._editor) {
          data._editor = d._editor;
        } else if (data._editor) {
          d._editor = data._editor;
        }
        return d;
      };
    } else if (o.type === "item-stats") {
      const skills = (window.ITEM_STATS_USE_SKILLS && window.ITEM_STATS_USE_SKILLS[o.itemCategory]) || null;
      form = window.buildItemStatsForm(data, {
        useSkillOptions: skills,
        hubMode: true,
        initialCategory: o.itemCategory || "weapon",
        editorCategoryKey: categoryKey,
        catalogCandidates: Array.isArray(o.catalogCandidates) ? o.catalogCandidates : []
      });
      if (o.itemCategory && typeof form.setActiveCategory === "function") {
        form.setActiveCategory(o.itemCategory);
      }
      if (skills && typeof form.setUseSkillOptions === "function") {
        form.setUseSkillOptions(skills);
      }
      getData = () => {
        const d = form.getData();
        // form 側 working が _editor を新規作成/更新している場合はそれを優先。
        // 旧: if (data._editor) d._editor = data._editor だと、form が同じ参照でも
        // 浅いコピー後の取りこぼしや古いスナップショットで並びが消えることがあった。
        if (d._editor) {
          data._editor = d._editor;
        } else if (data._editor) {
          d._editor = data._editor;
        }
        return d;
      };
    } else if (o.type === "spellbooks" || o.type === "spellbooks-catalysts" || o.type === "spellbooks-books") {
      const part = o.type === "spellbooks-catalysts" ? "catalysts"
        : o.type === "spellbooks-books" ? "books" : null;
      form = window.buildSpellbooksForm(data, { hideTabBar: !!part, initialPart: part || "books" });
      if (part && typeof form.setActivePart === "function") form.setActivePart(part);
      getData = () => {
        const d = form.getData();
        // form 側 working が _editor を新規作成/更新している場合はそれを優先。
        // 旧: if (data._editor) d._editor = data._editor だと、form が同じ参照でも
        // 浅いコピー後の取りこぼしや古いスナップショットで並びが消えることがあった。
        if (d._editor) {
          data._editor = d._editor;
        } else if (data._editor) {
          d._editor = data._editor;
        }
        return d;
      };
    } else if (o.type === "thread-bundle") {
      const threadsData = o.threadsData || {};
      const setsData = o.threadSetsData || {};
      const threadsForm = window.buildThreadsForm(threadsData);
      const setsForm = window.buildThreadSetsForm
        ? window.buildThreadSetsForm(setsData)
        : { element: h("div"), getData: () => setsData };
      const wrap = h("div", { class: "hub-ars" });
      wrap.appendChild(h("div", { class: "sub-title", text: "スレッド定義 (threads.yml)" }));
      wrap.appendChild(threadsForm.element);
      wrap.appendChild(h("div", { class: "sub-title", text: "セット効果 (thread-sets.yml)" }));
      wrap.appendChild(setsForm.element);
      form = { element: wrap };
        getData = () => {
          const d = threadsForm.getData();
          if (d._editor) {
            threadsData._editor = d._editor;
          } else if (threadsData._editor) {
            d._editor = threadsData._editor;
          }
          return d;
        };
      extraGets.push({ id: "thread-sets", getData: () => setsForm.getData() });
      const root = withCategoryBar(threadsData, categoryKey, wrap, threadsForm);
      const wrapGet = (fn) => () => {
        const d = fn();
        if (typeof window.pruneEditorUiState === "function") window.pruneEditorUiState(d);
        return d;
      };
      return {
        element: root,
        configId: "threads",
        getData: wrapGet(getData),
        getExtraSaves: () => extraGets.map((e) => ({ id: e.id, data: wrapGet(e.getData)() }))
      };
    } else if (o.type === "ritual-effects") {
      form = window.buildRecipesForm(data, { onlyEffects: true });
      if (typeof form.setActiveTab === "function") form.setActiveTab("ritual_effects");
      getData = () => form.getData();
    } else {
      form = { element: h("div", { text: "unknown split type" }), getData: () => data };
      getData = () => data;
    }

    const host = data;
    const flowCategoryBar = ["catalog", "item-stats"].includes(o.type);
    const root = withCategoryBar(host, categoryKey, form.element, form, flowCategoryBar);
    const wrapGet = (fn) => () => {
      const d = fn();
      if (typeof window.pruneEditorUiState === "function") window.pruneEditorUiState(d);
      // ensureEditor で _editorActive を host から削除済みでも、スプレッド残骸を落とす
      if (host && Object.prototype.hasOwnProperty.call(host, "_editorActive")) delete host._editorActive;
      return d;
    };
    return {
      element: root,
      configId: o.configId,
      getData: wrapGet(getData),
      // when() 付き extra (ファイル跨ぎ移動の相手ファイル) は、移動が起きたときだけ保存対象にする。
      getExtraSaves: () => extraGets
        .filter((e) => typeof e.when !== "function" || e.when())
        .map((e) => ({ id: e.id, data: wrapGet(e.getData)() })),
      getCmdCollisionData: o.type === "item-stats" ? () => wrapGet(getData)() : null
    };
  };
})();
