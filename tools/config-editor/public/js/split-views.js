"use strict";

// サイドバー分割ビュー: 固定ハブタブなし。各ページは任意カテゴリタブ(_editor.categories)のみ。

(function () {
  const h = window.h;

  function rerenderForm(form) {
    if (!form) return;
    if (typeof form.rerender === "function") form.rerender();
    else if (typeof form.rerenderList === "function") form.rerenderList();
  }

  // includeDraftCategory: 「準備中」既定カテゴリ(draft: true)は catalog.yml だけで実効を持つ
  // (ItemCatalogConfig#load が draft を見るのは items/catalog.yml のみ)。materials/threads/
  // item-stats/spellbooks など他ファイルの画面にまで無条件に出すと、そこへ入れても何も
  // 起きない(materials/threads)か、item-stats.yml に誰も読まない draft: true ゴーストキーが
  // 書かれる(2026-08-02 指摘4)。呼び出し側で o.type === "catalog" のときだけ true を渡す。
  function withCategoryBar(host, tabKey, formEl, form, flowCategoryBar = false, includeDraftCategory = false) {
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
          () => { renderBar(); rerenderForm(form); },
          { includeDraftCategory }
        ));
      }
    }
    renderBar();
    body.appendChild(formEl);
    root.appendChild(nestBar);
    root.appendChild(body);
    return root;
  }

  // 「素材」画面(materials.yml + catalog.yml の鍵)のセクション表示判定。カテゴリバー1本で
  // 2ファイル分の一覧を切り替えるための純関数 — DOM に触らないのでテストから直接呼べる。
  //   active: activeEditorCategory() の戻り (null=すべて / "__unset__"=未設定 / カテゴリid)
  //   keyCategoryId: 鍵カテゴリのid。鍵セクションが無い画面では null を渡す
  // 常に **どちらか片方だけ**を出す。「すべて」で2つ並べると検索欄が2つ・カードの列が2つに
  // なり、K の指摘「見にくい / 他のカタログのタブにUIをそろえてほしい」に戻る
  // (他のタブはどれも「カテゴリバー + 検索欄1つ + カード列1つ」)。
  window.MATERIALS_SPLIT_LOGIC = {
    visibleSections(active, keyCategoryId) {
      const keysOnly = !!keyCategoryId && active === keyCategoryId;
      return { materials: !keysOnly, keys: keysOnly };
    }
  };

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
    // カテゴリタブの状態(activeByHost の WeakMap キー)は、フォームが実際に読む host と
    // 同一オブジェクトでなければならない。catalog は TF 特殊アイテムを隠すために浅いクローンを
    // 渡すことがあり、そこで data と working が別オブジェクトになる(2026-07-28 のバグ:
    // カテゴリを切り替えても一覧が絞り込まれない)。
    let categoryHost = data;

    // ファイル跨ぎ移動 (catalog⇄materials): 相手ファイルのデータを受け取っていれば
    // フォームへ渡し、移動が発生した保存時だけ extraGets 経由で相手ファイルも書き込む。
    // counterpartDirty はコンフリクトマージ後の再構築でステージ済み移動を引き継ぐケース。
    const cross = o.counterpartData && o.counterpartId
      ? { id: o.counterpartId, data: o.counterpartData, dirty: !!o.counterpartDirty } : null;

    if (o.type === "catalog") {
      // 2026-07-27: TF の特殊アイテム2件(skill_node_lock/skill_tree_reset)は「特殊アイテム」画面
      // (functional-items.js)へ ID ロック付きで統合済みのため、カタログ画面(この buildCatalogForm)
      // からは隠す。二重導線になると、こちら側では ID を変更できてしまいロックの意味が無くなる。
      // buildCatalogForm は working=data を直接参照して破壊的に編集するため、渡す前に浅いクローンで
      // 2件だけ取り除き、保存時(getData)に取り除いた実体をそのまま無編集で戻す(ロスレス)。
      const tfHiddenIds = (window.FUNCTIONAL_ITEMS_CORE && window.FUNCTIONAL_ITEMS_CORE.TF_SPECIAL_ITEM_IDS) || [];
      let tfHiddenEntries = null;
      let catalogViewData = data;
      if (tfHiddenIds.length && data.items && typeof data.items === "object") {
        const present = tfHiddenIds.filter((id) => Object.prototype.hasOwnProperty.call(data.items, id));
        if (present.length) {
          tfHiddenEntries = {};
          const itemsClone = { ...data.items };
          for (const id of present) {
            tfHiddenEntries[id] = data.items[id];
            delete itemsClone[id];
          }
          catalogViewData = { ...data, items: itemsClone };
          // フォームが読む host はこのクローン。カテゴリバーにも同じ参照を渡すこと。
          categoryHost = catalogViewData;
        }
      }
      form = window.buildCatalogForm(catalogViewData, {
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
        // 隠した TF 特殊アイテムを欠落させずに戻す(この画面からは編集されていない=無加工のまま)。
        if (tfHiddenEntries) {
          d.items = { ...(d.items || {}), ...tfHiddenEntries };
        }
        return d;
      };
    } else if (o.type === "materials") {
      // 「素材」画面は2つのファイルを1画面で扱う: materials.yml の素材と、catalog.yml に実体を
      // 残したまま表示だけこちらへ寄せている鍵(表示タブ "key" = window.CATALOG_KEY_TAB)。
      //
      // 2026-08-04 改修: 以前は鍵を「カタログ内の素材 (items/catalog.yml、表示タブ「素材(カタログ内)」)」
      // という独立セクションとして下にぶら下げていた。見出しが内部事情の羅列で、検索欄が2つ並び、
      // カテゴリバーが無いので他のカタログタブと操作が揃わず、ユーザーからは「謎の要素の中に
      // 未分類のものが入っている」と見えていた。今は**カテゴリバー1本**
      // (materials.yml の _editor.categories.material)で素材と鍵を切り替える:
      //   ・鍵カテゴリ(MATERIALS_KEY_CATEGORY_ID)を選択 → 鍵の一覧だけ
      //   ・それ以外(すべて/未設定/素材カテゴリ) → 素材だけ
      // 鍵の実体を materials.yml へ移さないのは、gates.yml の key-item 判定・レシピ・CMD台帳が
      // catalog.yml 前提で、移すとダンジョン入場が壊れるため(2026-08-04 ユーザー確認)。
      const keyTab = window.CATALOG_KEY_TAB;
      const keyCategoryId = window.MATERIALS_KEY_CATEGORY_ID;
      const hasKeySection = !!(cross && cross.data && keyTab);
      function activeCategoryId() {
        return typeof window.activeEditorCategory === "function"
          ? window.activeEditorCategory(data, categoryKey) : null;
      }
      function sections() {
        return window.MATERIALS_SPLIT_LOGIC.visibleSections(activeCategoryId(), hasKeySection ? keyCategoryId : null);
      }
      const materialsForm = window.buildMaterialsForm(data, {
        editorCategoryKey: categoryKey,
        crossFile: cross,
        suppressed: () => !sections().materials
      });
      const wrap = h("div", { class: "hub-materials-composite" });
      wrap.appendChild(materialsForm.element);
      let catalogKeyForm = null;
      let keyBox = null;
      if (hasKeySection) {
        catalogKeyForm = window.buildCatalogForm(cross.data, {
          hubMode: true,
          initialCategory: keyTab[0],
          // 【2026-08-02 指摘5】editorCategoryKey を渡さないと useEditorMeta が false になり、
          // ここで新規追加/複製した品が catalog.yml の _editor.categories / orders に一切
          // 記録されない (次に開くと「未設定」に落ちる)。catalog.yml 側の他タブ
          // (weapon/armor/...) とは名前空間が別なので tabKey に "key" を使っても衝突しない。
          editorCategoryKey: keyTab[0]
        });
        keyBox = h("div", { class: "hub-materials-keys" });
        keyBox.appendChild(catalogKeyForm.element);
        wrap.appendChild(keyBox);
      }
      function applySectionVisibility() {
        if (!keyBox) return;
        keyBox.style.display = sections().keys ? "" : "none";
      }
      applySectionVisibility();
      form = {
        element: wrap,
        // カテゴリバーのタブ切替から呼ばれる。素材側と鍵側の両方を描き直してから表示を切る
        // (鍵側を描き直さないと、追加/削除した鍵がタブを戻すまで反映されない)。
        rerender: () => {
          rerenderForm(materialsForm);
          if (catalogKeyForm) rerenderForm(catalogKeyForm);
          applySectionVisibility();
        }
      };
      if (cross) {
        // このセクションが存在する限り catalog.yml を常に保存候補に含める(直接編集を拾うため)。
        // 「移動」機能専用だった旧 when:()=>cross.dirty は外す — 実際に変更が無ければ
        // app.js 側の isConfigDataChanged が base 比較でスキップするので、ここで絞る必要は無い。
        extraGets.push({
          id: cross.id,
          getData: () => (catalogKeyForm ? catalogKeyForm.getData() : cross.data)
        });
      }
      getData = () => {
        const d = materialsForm.getData();
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
      const itemStatsForm = window.buildItemStatsForm(data, {
        useSkillOptions: skills,
        hubMode: true,
        initialCategory: o.itemCategory || "weapon",
        editorCategoryKey: categoryKey,
        catalogCandidates: Array.isArray(o.catalogCandidates) ? o.catalogCandidates : []
      });
      form = itemStatsForm;
      if (o.itemCategory && typeof form.setActiveCategory === "function") {
        form.setActiveCategory(o.itemCategory);
      }
      if (skills && typeof form.setUseSkillOptions === "function") {
        form.setUseSkillOptions(skills);
      }
      // 「スレッド」タブに専用の抽選プールセクションを重ねる合成表示は 2026-08-02 に撤去した。
      // スレッドは武器/防具と同じ item-stats.yml のフォーム(fixed/per-quality/random/advanced)で
      // 通常のアイテムエントリとして編集する(専用GUIは作らない、というユーザー指示に合わせる)。
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

    const host = categoryHost;
    const flowCategoryBar = ["catalog", "item-stats"].includes(o.type);
    const root = withCategoryBar(host, categoryKey, form.element, form, flowCategoryBar, o.type === "catalog");
    const wrapGet = (fn) => () => {
      const d = fn();
      if (typeof window.pruneEditorUiState === "function") window.pruneEditorUiState(d);
      // ensureEditor で _editorActive を host から削除済みでも、スプレッド残骸を落とす
      if (host && Object.prototype.hasOwnProperty.call(host, "_editorActive")) delete host._editorActive;
      if (data !== host && Object.prototype.hasOwnProperty.call(data, "_editorActive")) {
        delete data._editorActive;
      }
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
