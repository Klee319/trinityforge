"use strict";

// サイドバー分割ビュー: 固定ハブタブなし。各ページは任意カテゴリタブ(_editor.categories)のみ。

(function () {
  const h = window.h;

  /**
   * フォームの保存用出力 `out` と、画面が握っているデータ `host` の間で `_editor` を揃える。
   *
   * <p>方向は**一方通行**にする。`host._editor` が既にあるならそれが画面の正であり、
   * 保存用出力で上書きしてはいけない。out の `_editor` は pruneEditorUiState が
   * 「items に無いidのピン」を落としたクローンでありうるため、書き戻すと
   * **画面の表示タブピンが消える**(カタログ候補の空枠が Material 推論のタブへ落ちる。
   * 2026-08-06「補助の未設定にある内容が消せない」の再発経路)。
   *
   * <p>逆に host 側にまだ `_editor` が無い場合だけは、フォームが新規作成したものを採用する
   * (旧コードの「form 側が _editor を新規作成している場合はそれを優先」の意図はこれ)。
   */
  function adoptEditorMeta(host, out) {
    if (!host || typeof host !== "object" || !out || typeof out !== "object") return out;
    if (!host._editor && out._editor) host._editor = out._editor;
    else if (host._editor && !out._editor) out._editor = host._editor;
    return out;
  }

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
  // itemIdSet: 「_editor.categories/itemTabs/orders の宙ぶらりんid」検査に使う、このファイルで
  // 有効なidの集合。呼び出し側(buildSplitConfigView)が対応済みの type だけ渡す。null なら検査しない
  // (2026-08-16。docs/agent-context/config-editor.md 参照。検出のみ・保存はブロックしない)。
  function withCategoryBar(host, tabKey, formEl, form, flowCategoryBar = false, includeDraftCategory = false, itemIdSet = null) {
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
    // 宙ぶらりんが無ければ何も追加しない(既存の `root=[nestBar, body]` という固定構造に
    // 依存するテスト(例: thread-dedicated-ui-removed-2026-08-02.test.js の
    // `view.element.children[1]` インデックス参照)を壊さないため。警告があるときだけ
    // 先頭に差し込む(2026-08-16)。
    if (typeof window.buildDanglingEditorMetaWarning === "function") {
      const banner = window.buildDanglingEditorMetaWarning(host, itemIdSet);
      if (banner) root.appendChild(banner);
    }
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
   * @param {"catalog"|"materials"|"threads"|"item-stats"|"spellbooks"|"spellbooks-catalysts"|"spellbooks-books"|"ritual-effects"} opts.type
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
    // _editor.categories/itemTabs/orders の宙ぶらりんid検査(withCategoryBar)に渡す、この画面で
    // 有効なidの集合。対応済みの type だけ下の各分岐で設定する。null のままの type は検査しない。
    let itemIdSet = null;

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
      // 宙ぶらりん検査は隠したTF特殊アイテムも「有効」に数える(この画面からは見えないだけで
      // 実在するため、data.items(隠す前の原本)から集合を作る)。
      if (data.items && typeof data.items === "object") itemIdSet = new Set(Object.keys(data.items));
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
        adoptEditorMeta(data, d);
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
      // 宙ぶらりん検査: materials.yml 自身の materials キー ∪ catalog.yml の items キー
      // (「ダンジョンの鍵」カテゴリが意図的に catalog.yml の id を指す設計のため。
      // 詳細は docs/agent-context/config-editor.md)。cross.data が無い(=カタログ未取得)画面では
      // catalog側集合が作れないため検査自体をスキップする(誤検知を出さない、null のまま)。
      if (data.materials && typeof data.materials === "object" && cross && cross.data
          && cross.data.items && typeof cross.data.items === "object") {
        itemIdSet = new Set([...Object.keys(data.materials), ...Object.keys(cross.data.items)]);
      }
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
        adoptEditorMeta(data, d);
        return d;
      };
    } else if (o.type === "threads") {
      form = window.buildThreadsForm(data);
      getData = () => {
        const d = form.getData();
        adoptEditorMeta(data, d);
        return d;
      };
    } else if (o.type === "item-stats") {
      // 宙ぶらりんid検査は<この画面では行わない>(itemIdSet は null のまま)。
      //
      // 2026-08-16 まではここで `new Set(Object.keys(data.items))` を渡していたが、これは誤り。
      // item-stats.yml は lib/cmd-removal.js が明記するとおり<アイテム定義ではなく既存
      // (material,cmd) への参照専用ファイル>で、`items:` に載るのは「TFステータスを設定済みの
      // ものだけ」＝実在アイテム集合の部分集合にすぎない。まだステータスを付けていない実在
      // アイテム(catalog.yml の深罪の終幕・魔法書3種、ArsPaper spellbooks.yml の触媒3種など)が
      // 「もう存在しません」と誤検知され、本物の改名漏れが警告の山に埋もれていた。
      //
      // 正しい母集合は「定義ファイル全部」だが、それを組めるのはサーバ側だけ
      // (lib/editor-meta-integrity.js の editorMetaItemIdSet が CmdRegistry.scanUsage で組む)。
      // ブラウザ側は catalog/spellbooks/materials を持っていないので、ここで無理に集合を作ると
      // 必ず誤検知になる。検査は保存時のサーバ警告に一本化する。
      itemIdSet = null;
      const skills = (window.ITEM_STATS_USE_SKILLS && window.ITEM_STATS_USE_SKILLS[o.itemCategory]) || null;
      // 「スレッド」タブは threads.yml / thread-sets.yml も横から一緒に読み書きする(2026-08-09)。
      // アイテムステータス側で「Ars効果とそれ以外」「効果とセット効果」を画面分割しない、
      // というユーザー指示に合わせ、旧・独立ナビ「スレッド効果 (Ars)」(thread-bundle 分割ビュー、
      // 0b23802)と、その後に一度新設した thread-sets.yml 専用ナビ(__thread_sets__)を両方撤去し、
      // 代わりにこのカード(forms.js の renderThreadExtraFields)から threads.yml/thread-sets.yml
      // を直接編集する。o.threadsData/o.threadSetsData は呼び出し元(app.js)が別途 GET した
      // 各ファイルのルートオブジェクトで、forms.js 側がそのまま(浅いクローンを挟まず)
      // 破壊的に書き込む。
      const threadsData = o.threadsData && typeof o.threadsData === "object" ? o.threadsData : null;
      const threadSetsData = o.threadSetsData && typeof o.threadSetsData === "object" ? o.threadSetsData : null;
      const itemStatsForm = window.buildItemStatsForm(data, {
        useSkillOptions: skills,
        hubMode: true,
        initialCategory: o.itemCategory || "weapon",
        editorCategoryKey: categoryKey,
        catalogCandidates: Array.isArray(o.catalogCandidates) ? o.catalogCandidates : [],
        threadsData,
        threadSetsData
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
        adoptEditorMeta(data, d);
        return d;
      };
      // threadsData/threadSetsData が変更されていなければ isConfigDataChanged が base 比較で
      // スキップするので、ここで when() で絞り込む必要は無い(「素材」画面の cross と同じ流儀、
      // 97-98行のコメント参照)。
      if (threadsData) extraGets.push({ id: "threads", getData: () => threadsData });
      if (threadSetsData) extraGets.push({ id: "thread-sets", getData: () => threadSetsData });
    } else if (o.type === "spellbooks" || o.type === "spellbooks-catalysts" || o.type === "spellbooks-books") {
      const part = o.type === "spellbooks-catalysts" ? "catalysts"
        : o.type === "spellbooks-books" ? "books" : null;
      form = window.buildSpellbooksForm(data, { hideTabBar: !!part, initialPart: part || "books" });
      if (part && typeof form.setActivePart === "function") form.setActivePart(part);
      getData = () => {
        const d = form.getData();
        adoptEditorMeta(data, d);
        return d;
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
    const root = withCategoryBar(host, categoryKey, form.element, form, flowCategoryBar, o.type === "catalog", itemIdSet);
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
