"use strict";

// YAML `_editor` メタ: ゲームは無視。ConfigEditor の表示整理用。
// - categories: { <fixedTabKey>: [ { id, label, itemIds: [] } ] }  ネストタブ
// - orders: { <fixedTabKey>: [itemId, ...] }  並び順
// - itemTabs: { <itemId>: "weapon"|"armor"|"tool"|"other" }  固定タブ配置 (Material推論より優先)
// 注意: アクティブなネストタブは YAML に書かず、メモリ上 (WeakMap) のみで保持する。

(function () {
  const h = window.h;

  // ---- 「未分類」受け皿カテゴリ ----
  // 新規追加/複製/タブ移動で行き先が決まらない品を必ず入れる、実体のあるカテゴリ。
  // 仮想タブ「未設定」と違い yml に残る = 次に開いても同じ場所に居る。
  // id は**予約語**として扱う (下の RESERVED_CATEGORY_IDS)。ユーザー側の採番・救済採番が
  // この id を取ると、以後この関数の受け皿がユーザーのカテゴリに化けて中身が混ざる。
  const UNCLASSIFIED_CATEGORY_ID = "cat_auto_unclassified";
  const UNCLASSIFIED_CATEGORY_LABEL = "未分類";

  // ---- 「準備中」カテゴリ (2026-08-02) ----
  // 全タブに既定で1つだけ存在する予約カテゴリ。ここへ入れた品は catalog.yml に
  // `draft: true` が付き、**ゲーム側へ一切配線されない**(レシピも登録されず、ドロップも
  // ガチャも実績報酬も出ない)。エディタからは通常どおり編集・参照できるので、
  // 「実際には出ないがドロップ表には先に書いておく」という早期仕込みができる。
  // 解禁はこのカテゴリから出すだけ (= draft: が外れる)。
  // 「未分類」と同じく id は**予約語**。ユーザー採番がこれを取ると受け皿が化けて中身が混ざる。
  const DRAFT_CATEGORY_ID = "cat_auto_draft";
  const DRAFT_CATEGORY_LABEL = "準備中";
  const RESERVED_CATEGORY_IDS = new Set([UNCLASSIFIED_CATEGORY_ID, DRAFT_CATEGORY_ID]);
  window.DRAFT_CATEGORY_ID = DRAFT_CATEGORY_ID;
  window.DRAFT_CATEGORY_LABEL = DRAFT_CATEGORY_LABEL;

  const normalizeLabel = (s) => String(s == null ? "" : s).trim();

  // host(YAMLルート) → { [tabKey]: activeCategoryId|"__all__" }
  // host に直接付けると getData のスプレッドで YAML に混入するため分離する。
  const activeByHost = new WeakMap();

  // host → Map<tabKey, バー再構築関数>。カテゴリの**構造**(カテゴリ自体の増減)は
  // フォーム側の追加ハンドラの中でも起きる (ensureItemEditorCategory が「未分類」を作る) が、
  // バーは split-views の renderBar からしか作り直されないため、バーだけ古いまま残っていた
  // (タブに「未分類」が無いのにカードは「未分類」所属 = 一覧と食い違う / 2026-08-01 報告)。
  // 同じ (host, tabKey) では**最後に作ったバーだけ**が生きているものとして扱う
  // (split-views の renderBar は毎回新しいバーへ差し替えるので、古い方は破棄済み)。
  const barRefreshByHost = new WeakMap();

  function registerBarRefresh(host, tabKey, fn) {
    if (!host || typeof host !== "object") return;
    let m = barRefreshByHost.get(host);
    if (!m) {
      m = new Map();
      barRefreshByHost.set(host, m);
    }
    m.set(tabKey, fn);
  }

  /** カテゴリ構造が変わったときにタブバーを描き直す。バーが無い画面では何もしない。 */
  window.refreshEditorCategoryBar = function refreshEditorCategoryBar(host, tabKey) {
    if (!host || typeof host !== "object") return;
    const m = barRefreshByHost.get(host);
    const fn = m && m.get(tabKey);
    if (typeof fn === "function") fn();
  };

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

  // ---- id 欠落カテゴリの救済 ----
  // `_editor.categories[].id` は**唯一の同定子**で、label は表示専用。手書きで yml に
  // カテゴリを足すときに id を省くと、editor は「黙って」以下の4つの形で壊れる:
  //   1. タブを押しても am[tabKey] に undefined が入り、activeEditorCategory が null(=すべて)
  //      に化けるので絞り込みが起きない
  //   2. getItemEditorCategory が undefined を返し、カードの「カテゴリ」欄が (未設定) になる
  //   3. itemInEditorCategory の __unset__ 分岐が真になり、所属済みの品が「未設定」タブに並ぶ
  //   4. カード上のカテゴリセレクトを操作すると所属が剥がれる / merge.js の identityKeyOf が
  //      配列全体を識別不能と判定して同時編集で相手のカテゴリ編集が丸ごと消える
  // どれもエラーを出さないので、id を補いつつ**警告も出す**（無言で落とさないのが要点）。
  const warnedMissingId = new Set();

  // 日本語ラベルは slug 化できず全部空になるので、ラベルから決定的なハッシュを作る。
  // 配列の並び順に依存させない（並べ替えただけで id が変わると itemIds の同定が揺れる）。
  function labelHash(label) {
    let hash = 0x811c9dc5; // FNV-1a 32bit の offset basis
    const s = String(label == null ? "" : label);
    for (let i = 0; i < s.length; i++) {
      hash ^= s.charCodeAt(i);
      // Math.imul でないと 32bit の積が 2^53 を超えて丸められる(倍精度の桁落ち)。
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    return hash.toString(36);
  }

  function derivedCategoryId(tabKey, label, index) {
    const slug = String(label == null ? "" : label).trim().toLowerCase()
      .replace(/[^a-z0-9_\-]+/g, "_").replace(/^_+|_+$/g, "");
    if (slug) return "cat_auto_" + slug;
    const raw = String(label == null ? "" : label).trim();
    if (raw) return "cat_auto_" + tabKey + "_" + labelHash(raw);
    return "cat_auto_" + tabKey + "_" + index;
  }

  /** id を持たないカテゴリへ、label 由来の安定 id を補う。補ったら警告する。 */
  function backfillCategoryIds(cats, tabKey) {
    const used = new Set();
    for (const cat of cats) {
      if (cat && typeof cat === "object" && cat.id) used.add(String(cat.id));
    }
    // 予約 id は救済採番の対象にしない。label が "unclassified" のカテゴリを手書きすると
    // derivedCategoryId が `cat_auto_unclassified` を作り、「未分類」の受け皿を丸ごと奪う
    // (以後そのカテゴリに新規追加品が混ざり込む / 2026-08-01)。
    // 既に明示的に `id: cat_auto_unclassified` を持つカテゴリは上の used 収集で守られる。
    for (const reserved of RESERVED_CATEGORY_IDS) used.add(reserved);
    cats.forEach((cat, index) => {
      if (!cat || typeof cat !== "object" || cat.id) return;
      let id = derivedCategoryId(tabKey, cat.label, index);
      let suffix = 2;
      while (used.has(id)) id = derivedCategoryId(tabKey, cat.label, index) + "_" + suffix++;
      cat.id = id;
      used.add(id);
      const warnKey = tabKey + "/" + id;
      if (!warnedMissingId.has(warnKey)) {
        warnedMissingId.add(warnKey);
        const label = cat.label == null ? "(ラベルなし)" : String(cat.label);
        // eslint-disable-next-line no-console
        console.warn(`[editor-categories] _editor.categories.${tabKey} のカテゴリ「${label}」に id: が`
          + ` 無いため ${id} を補いました。yml へ id: を書いてください(次の保存で書き戻されます)。`);
      }
    });
    return cats;
  }

  function listCategories(host, tabKey) {
    const ed = ensureEditor(host);
    if (!Array.isArray(ed.categories[tabKey])) ed.categories[tabKey] = [];
    return backfillCategoryIds(ed.categories[tabKey], tabKey);
  }

  /**
   * 「準備中」を全タブの既定カテゴリとして用意する。**UI(タブバー)の描画時にだけ**呼ぶ。
   *
   * listCategories 側でやらないのは、あれがデータ層の純粋な参照(移動・改名・削除・保存が全部通る)
   * だからで、そこで行を生やすと「読んだだけでカテゴリが1つ増える」= 触っていない yml が
   * 保存で変わる、という既知の事故クラス(normalize の既定値ドリフト)になる。
   * 必ず末尾に足すのは、既存の並びを崩さないため。
   * ラベル一致の手作りカテゴリがあれば予約 id へ昇格させ、同名タブを2つ並べない。
   */
  function ensureDraftCategory(host, tabKey) {
    const cats = listCategories(host, tabKey);
    if (cats.some((c) => c.id === DRAFT_CATEGORY_ID)) return cats;
    const handmade = cats.find((c) => normalizeLabel(c.label) === DRAFT_CATEGORY_LABEL);
    if (handmade) handmade.id = DRAFT_CATEGORY_ID;
    else cats.push({ id: DRAFT_CATEGORY_ID, label: DRAFT_CATEGORY_LABEL, itemIds: [] });
    return cats;
  }

  /**
   * 「準備中」カテゴリの所属を catalog.yml の `draft: true` へ反映する。
   *
   * カテゴリ所属(_editor)とアイテム本体(items)で状態を二重に持つことになるが、二重化は避けられない:
   * `_editor` はゲームが読まないメタなので、**サーバ側は items の draft しか見られない**。
   * 逆にカテゴリ側を持たないと、エディタで「準備中だけ一覧する」ができない。
   * そこで**カテゴリ所属を入力・draft を出力**と決め、移動のたびに一方向で同期する
   * (この関数以外から draft を書かない)。
   */
  function syncDraftFlag(host, itemId, inDraftCategory) {
    const entry = host && host.items && typeof host.items === "object" ? host.items[itemId] : null;
    if (!entry || typeof entry !== "object") return;
    if (inDraftCategory) entry.draft = true;
    else if ("draft" in entry) delete entry.draft;
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
    let cats = ensureDraftCategory(host, tabKey);
    const bar = h("div", { class: "editor-cat-bar" });
    const am = activeMap(host);
    if (!am[tabKey]) am[tabKey] = "__all__";

    function isVirtualCatId(id) {
      return id === "__all__" || id === "__unset__";
    }

    // バーの中身だけを作り直す。DOM API を全部は持たないテスト用フェイク要素でも動くよう、
    // replaceChildren → children 配列 → innerHTML の順にフォールバックする。
    function clearBar() {
      if (typeof bar.replaceChildren === "function") { bar.replaceChildren(); return; }
      if (Array.isArray(bar.children)) { bar.children.length = 0; return; }
      bar.innerHTML = "";
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

    let dragCatId = null;

    // タブ/操作ボタンをバーへ組み直す。カテゴリの増減 (「未分類」の自動生成を含む) の
    // たびに呼べるよう、DOM を作る側はすべてこの中に閉じる。
    function buildBar() {
      clearBar();
      cats = listCategories(host, tabKey);

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

      bar.appendChild(addBtn());
      bar.appendChild(renameBtn());
      bar.appendChild(deleteBtn());
      syncActiveUi();
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

    // 同じ表示名のカテゴリが2つ並ぶとタブでもカードのセレクトでも見分けが付かない。
    // とくに「未分類」は自動生成される受け皿と同名になりうる (2026-08-01 報告)。
    // 無言で作らせず、その場で断る。
    function labelTaken(label, exceptId) {
      const wanted = normalizeLabel(label);
      return cats.some((c) => c.id !== exceptId && normalizeLabel(c.label || c.id) === wanted);
    }

    function addBtn() {
      return h("button", {
        class: "btn-small", type: "button", text: "+ カテゴリ",
        onclick: () => {
          const raw = prompt("新しいカテゴリ名");
          if (!raw || !raw.trim()) return;
          const label = raw.trim();
          if (labelTaken(label, null)) {
            alert(`カテゴリ「${label}」は既にあります。別の名前を付けてください。`);
            return;
          }
          let id = label.toLowerCase().replace(/[^a-z0-9_\-]+/g, "_").replace(/^_+|_+$/g, "");
          if (!id) id = "cat_" + Date.now();
          // 予約 id (未分類の受け皿) はユーザーのカテゴリに渡さない。
          while (cats.some((c) => c.id === id) || RESERVED_CATEGORY_IDS.has(id)) {
            id = id + "_" + Math.floor(Math.random() * 1000);
          }
          cats.push({ id, label, itemIds: [] });
          am[tabKey] = "__all__";
          notifyStructure();
        }
      });
    }

    function renameBtn() {
      return h("button", {
        class: "btn-small editor-cat-rename", type: "button", text: "カテゴリ名変更",
        onclick: () => {
          const cur = am[tabKey] || "__all__";
          if (isVirtualCatId(cur)) return;
          const cat = cats.find((c) => c.id === cur);
          if (!cat) return;
          const raw = prompt("新しいカテゴリ名", cat.label || cat.id);
          if (raw == null || !raw.trim()) return;
          const label = raw.trim();
          if (labelTaken(label, cat.id)) {
            alert(`カテゴリ「${label}」は既にあります。別の名前を付けてください。`);
            return;
          }
          cat.label = label;
          notifyStructure();
        }
      });
    }

    function deleteBtn() {
      return h("button", {
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
    }

    buildBar();
    // カテゴリの増減がフォーム側で起きたときに、このバーだけを描き直せるようにする。
    registerBarRefresh(host, tabKey, buildBar);
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

  // ---- 新規追加品の自動カテゴリ割当 ----
  //
  // 【2026-08-01 実サーバ報告「追加した素材が editor でカテゴリ分けできていない」の修正】
  // それまでの `assignItemToActiveEditorCategory` は「カテゴリタブで絞り込み中のときだけ」
  // 割り当てる作りだった。既定の表示は「すべて」なので、**普通に追加した品はどのカテゴリにも
  // 入らない**。カテゴリタブを1つも押さずに追加し続ける限り、全部が「未設定」に溜まる
  // (エラーも警告も出ないので気づけない)。同じ理由で、カタログ⇄素材のファイル跨ぎ移動や
  // 表示タブの移動でも移動先タブでは無所属になっていた。
  //
  // 「絞り込み中ならそのカテゴリ、そうでなければ『未分類』カテゴリ」へ必ず入れる。
  // 未分類は実体のあるカテゴリなので、タブから一覧でき、カード上のセレクトで移動できる。
  // (仮想タブの「未設定」と違い、yml に残る = 次に開いたときも同じ場所に居る。)
  // 定数はファイル先頭で定義済み (UNCLASSIFIED_CATEGORY_ID / _LABEL / RESERVED_CATEGORY_IDS)。

  /**
   * itemId が tabKey のどれかのカテゴリに属している状態を保証する。
   * 既にどこかへ属していれば何もしない (勝手に移動させない)。
   * @returns {string} 所属カテゴリ id (割り当てられなかった場合は "")
   */
  window.ensureItemEditorCategory = function ensureItemEditorCategory(host, tabKey, itemId) {
    if (!host || typeof host !== "object" || !tabKey || !itemId) return "";
    const existing = window.getItemEditorCategory(host, tabKey, itemId);
    if (existing) return existing;

    const cats = listCategories(host, tabKey);
    const active = activeMap(host)[tabKey] || "__all__";
    if (active !== "__all__" && active !== "__unset__" && cats.some((c) => c.id === active)) {
      window.moveItemEditorCategory(host, tabKey, itemId, active);
      return active;
    }
    // 【2026-08-01】仮想タブ「未設定」で絞り込み中は**割り当てない**。
    // 「未設定」は『どのカテゴリにも属さない品』のビューなので、ここで「未分類」へ入れると
    // 追加した品が絞り込み条件から外れ、**追加した瞬間に画面から消える**。
    // 他のタブと同じで「絞り込み中の条件をそのまま満たす状態で追加する」が正しい振る舞い。
    if (active === "__unset__") return "";

    let fallback = cats.find((c) => c.id === UNCLASSIFIED_CATEGORY_ID);
    // ユーザーが手で作った「未分類」があればそれを使う (同名タブを2つ並べない)。
    if (!fallback) {
      fallback = cats.find((c) => normalizeLabel(c.label) === UNCLASSIFIED_CATEGORY_LABEL);
    }
    let created = false;
    if (!fallback) {
      fallback = { id: UNCLASSIFIED_CATEGORY_ID, label: UNCLASSIFIED_CATEGORY_LABEL, itemIds: [] };
      cats.push(fallback);
      created = true;
    }
    window.moveItemEditorCategory(host, tabKey, itemId, fallback.id);
    // カテゴリが1つ増えた = 構造が変わったのでタブバーにも反映する。
    // (フォーム側の追加ハンドラから呼ばれるため、バーは自力では作り直されない。)
    if (created) window.refreshEditorCategoryBar(host, tabKey);
    return fallback.id;
  };

  /**
   * 複製時の割当。**元アイテムと同じカテゴリ**へ入れる。
   * 元が無所属のときだけ通常の追加と同じ扱い (絞り込み中のカテゴリ or 「未分類」)。
   *
   * 【2026-08-01】item-stats の複製だけがこれを通らず assignItemToActiveEditorCategory を
   * 呼んでいたため、絞り込みしていない状態で複製すると元のカテゴリを捨てて「未分類」へ落ちていた。
   * @returns {string} 複製先の所属カテゴリ id ("" は無所属)
   */
  window.duplicateItemEditorCategory = function duplicateItemEditorCategory(host, tabKey, srcId, newId) {
    if (!host || typeof host !== "object" || !tabKey || !newId) return "";
    const srcCat = srcId ? window.getItemEditorCategory(host, tabKey, srcId) : "";
    if (srcCat) {
      window.moveItemEditorCategory(host, tabKey, newId, srcCat);
      return srcCat;
    }
    return window.ensureItemEditorCategory(host, tabKey, newId);
  };

  window.UNCLASSIFIED_EDITOR_CATEGORY_ID = UNCLASSIFIED_CATEGORY_ID;

  /**
   * 新規追加/複製時の割当。名前は呼び出し側との互換のため据え置き。
   * 絞り込み中のカテゴリがあればそこへ、無ければ「未分類」へ入れる (ensureItemEditorCategory)。
   */
  window.assignItemToActiveEditorCategory = function assignItemToActiveEditorCategory(host, tabKey, itemId) {
    window.ensureItemEditorCategory(host, tabKey, itemId);
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
    syncDraftFlag(host, itemId, categoryId === DRAFT_CATEGORY_ID);
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
    // 移動元タブのネストカテゴリからは外れたので、移動先タブでも必ずどこかへ入れる
    // (でないと「表示タブを変えたら未設定へ落ちる」= 2026-08-01 報告と同じ形になる)。
    window.ensureItemEditorCategory(host, toTab, itemId);
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
          // 日本語カテゴリ名などslug化できず "cat_<timestamp>" で自動採番されたidや、
          // id 欠落を救済した "cat_auto_*" はユーザーに意味が無いので副表記に出さない。
          secondary: cat.id !== (cat.label || "") && !/^cat_(\d+(_\d+)?|auto_.*)$/.test(cat.id) ? cat.id : ""
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
