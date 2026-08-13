"use strict";

// YAML `_editor` メタ: ゲームは無視。ConfigEditor の表示整理用。
// - categories: { <fixedTabKey>: [ { id, label, itemIds: [] } ] }  ネストタブ
// - orders: { <fixedTabKey>: [itemId, ...] }  並び順
// - itemTabs: { <itemId>: "weapon"|"armor"|"tool"|"other" }  固定タブ配置 (Material推論より優先)
// 注意: アクティブなネストタブは YAML に書かず、メモリ上 (WeakMap) のみで保持する。

(function () {
  const h = window.h;

  // ---- 「未分類」受け皿カテゴリ (2026-08-13 廃止) ----
  // かつては行き先の決まらない品を必ず入れる実体カテゴリを自動生成していたが、
  // **仮想タブ「未設定」が同じ品をそのまま一覧できる**ため、タブが二重になるだけだった
  // (ユーザー指摘: 「未設定カテゴリがあるのに未分類カテゴリが自動生成されている」)。
  // 現在は新規に作らず、既存 yml に残っているものを**タブバー描画時に取り除く**。
  // 取り除かれた品は無所属に戻る = 仮想タブ「未設定」に並ぶので、消えたようには見えない。
  //
  // id は**予約語のまま**残す (下の RESERVED_CATEGORY_IDS)。ユーザーの手書きカテゴリや救済採番が
  // この id を取ると、掃除の対象になって中身ごと消える。
  const UNCLASSIFIED_CATEGORY_ID = "cat_auto_unclassified";

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

  // 【2026-08-13】かつてここに `refreshEditorCategoryBar`(host+tabKey からタブバーを外部再構築
  // する仕組み)があった。フォーム側の追加ハンドラが ensureItemEditorCategory 経由で「未分類」を
  // **生やす**唯一の経路のために用意したもので、その受け皿の廃止で発火元が消えたため削除した。
  // 今カテゴリの構造が変わるのは (a) バー上の「+ カテゴリ」/削除/改名 → onStructureChange で
  // split-views が renderBar をやり直す (b) 描画時の dropLegacyUnclassifiedCategory /
  // ensureDraftCategory → バーを組み立てる前に走るので最初から正しい、の2つだけ。
  // フォーム側からカテゴリを増減させる経路を足すなら、この仕組みも一緒に戻すこと。

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
    if (handmade) {
      handmade.id = DRAFT_CATEGORY_ID;
      // 【2026-08-02 指摘3】ラベル一致だけで既存カテゴリを昇格させる場合、既にそこへ
      // 入っていたメンバーにも draft: true を付ける。付けないと「タブは準備中と表示されるのに
      // 中身は普通に配線されたまま(ゲームに出続ける)」という食い違いが生まれる。
      if (Array.isArray(handmade.itemIds)) {
        for (const itemId of handmade.itemIds) syncDraftFlag(host, itemId, true);
      }
    } else {
      cats.push({ id: DRAFT_CATEGORY_ID, label: DRAFT_CATEGORY_LABEL, itemIds: [] });
    }
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
   * 旧「未分類」受け皿カテゴリ({@code cat_auto_unclassified})を取り除く。**タブバー描画時にだけ**呼ぶ
   * (ensureDraftCategory と同じ理由: データ層の純粋な参照で行を消すと「読んだだけで yml が変わる」)。
   *
   * <p>中身は無所属へ戻すだけで消さない — 無所属の品は仮想タブ「未設定」に並ぶ。
   * 対象は予約 id 完全一致のみ。ラベルが「未分類」なだけのユーザー製カテゴリは触らない
   * (自動生成の受け皿とユーザーの分類を取り違えて中身ごと消さないため)。
   * @returns {boolean} 実際に取り除いたら true (タブバーの再構築が要る)
   */
  function dropLegacyUnclassifiedCategory(host, tabKey) {
    const cats = listCategories(host, tabKey);
    const index = cats.findIndex((c) => c && c.id === UNCLASSIFIED_CATEGORY_ID);
    if (index < 0) return false;
    cats.splice(index, 1);
    const am = activeMap(host);
    // 消したカテゴリで絞り込み中だった場合、選択が宙に浮くので「すべて」へ戻す。
    if (am[tabKey] === UNCLASSIFIED_CATEGORY_ID) am[tabKey] = "__all__";
    return true;
  }

  /**
   * Renders nested category tabs + add/rename/delete for one fixed tab.
   * @param {object} host YAML root object (mutated)
   * @param {string} tabKey fixed tab id (weapon, armor, ...)
   * @param {function} onFilterChange 表示フィルタ変更時 (タブ切替)。バーは再構築しない。
   * @param {function} [onStructureChange] カテゴリ追加/削除時。バー再構築が必要。
   * @param {object} [opts]
   * @param {boolean} [opts.includeDraftCategory] true のときだけ「準備中」既定カテゴリを用意する。
   *   【2026-08-02 指摘4】draft: true は catalog.yml でしか実効を持たない(ItemCatalogConfig#load
   *   だけが読む)。material/threads/item-stats/spellbooks など他ファイルの画面でも無条件に
   *   ensureDraftCategory していたため、そこへ入れても意味の無い draft: true が
   *   (items を持つ item-stats.yml では実際に)書き込まれていた。呼び出し側(split-views.js)で
   *   catalog.yml の画面のときだけ true を渡すこと。
   * @returns {HTMLElement}
   */
  window.renderEditorCategoryBar = function renderEditorCategoryBar(host, tabKey, onFilterChange, onStructureChange, opts) {
    ensureEditor(host);
    const includeDraft = !!(opts && opts.includeDraftCategory);
    // 2026-08-13: 旧「未分類」受け皿は廃止。既存 yml に残っていればここで取り除く。
    dropLegacyUnclassifiedCategory(host, tabKey);
    let cats = includeDraft ? ensureDraftCategory(host, tabKey) : listCategories(host, tabKey);
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
      // 【2026-08-02 指摘2】予約カテゴリ(未分類/準備中)は id が固定の内部受け皿なので、
      // 改名・削除の対象から外す(仮想タブと同じ扱いでボタンを無効化する)。
      const placeholder = isVirtualCatId(active) || RESERVED_CATEGORY_IDS.has(active);
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
          // 【2026-08-02 指摘2】予約カテゴリを改名できると id は cat_auto_draft のまま
          // ラベルだけ「強化予定」等に変わり、以後そのタブが黙って draft: true を刻み続ける。
          if (RESERVED_CATEGORY_IDS.has(cur)) {
            alert("このカテゴリは編集内部で自動管理されているため名前を変更できません。");
            return;
          }
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
          // 【2026-08-02 指摘2】準備中カテゴリを splice で消すと、メンバーの draft: true を
          // 外さないまま受け皿だけ無くなり「なぜゲームに出ないか」の手掛かりが UI から消える。
          // 未分類も同じく id が固定の内部受け皿なので削除させない。
          if (RESERVED_CATEGORY_IDS.has(cur)) {
            alert("このカテゴリは編集内部で自動管理されているため削除できません。");
            return;
          }
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
  // 割り当てる作りだった。既定の表示は「すべて」なので、普通に追加した品はどのカテゴリにも
  // 入らない。そこで受け皿カテゴリ「未分類」を自動生成してそこへ入れるようにした。
  //
  // 【2026-08-13 実サーバ報告「未設定カテゴリがあるのに未分類カテゴリが自動生成されている」】
  // その受け皿は廃止した。**無所属の品は仮想タブ「未設定」がそのまま一覧する**ので、
  // 「未分類」は同じ集合を指すタブをもう1つ並べていただけで、しかも yml を汚していた
  // (無所属のまま返しても「すべて」と「未設定」の両方に出るので、品が画面から消えることはない)。
  // 既存 yml に残っている受け皿は renderEditorCategoryBar の dropLegacyUnclassifiedCategory が
  // 描画時に取り除く。id (UNCLASSIFIED_CATEGORY_ID) はその同定にだけ使い、**予約語のまま**
  // 据え置く (ユーザー採番がこれを取ると掃除がユーザーのカテゴリを消してしまう)。

  /**
   * itemId が tabKey のどれかのカテゴリに属している状態を保証する。
   * 既にどこかへ属していれば何もしない (勝手に移動させない)。
   * @param {object} [opts] moveItemEditorCategory へそのまま渡す (skipDraftSync 等)。
   * @returns {string} 所属カテゴリ id (割り当てられなかった場合は "")
   */
  window.ensureItemEditorCategory = function ensureItemEditorCategory(host, tabKey, itemId, opts) {
    if (!host || typeof host !== "object" || !tabKey || !itemId) return "";
    const existing = window.getItemEditorCategory(host, tabKey, itemId);
    if (existing) return existing;

    const cats = listCategories(host, tabKey);
    const active = activeMap(host)[tabKey] || "__all__";
    if (active !== "__all__" && active !== "__unset__" && cats.some((c) => c.id === active)) {
      window.moveItemEditorCategory(host, tabKey, itemId, active, opts);
      return active;
    }
    // 【2026-08-13】絞り込んでいないときは**どのカテゴリにも入れない**。
    // 以前は「未分類」カテゴリを自動生成してそこへ入れていたが、無所属の品は仮想タブ
    // 「未設定」がそのまま一覧するので、同じ集合を指すタブが 2 つ並ぶだけだった。
    // 無所属で返しても品が画面から消えることはない (「すべて」と「未設定」の両方に出る)。
    //
    // 【2026-08-01】仮想タブ「未設定」で絞り込み中も同じ理由で割り当てない。
    // ここで実体カテゴリへ入れると追加した品が絞り込み条件から外れ、
    // **追加した瞬間に画面から消える**。
    return "";
  };

  /**
   * 複製時の割当。**元アイテムと同じカテゴリ**へ入れる。
   * 元が無所属のときだけ通常の追加と同じ扱い (絞り込み中ならそのカテゴリ、無ければ無所属のまま)。
   *
   * 【2026-08-01】item-stats の複製だけがこれを通らず assignItemToActiveEditorCategory を
   * 呼んでいたため、絞り込みしていない状態で複製すると元のカテゴリを捨てて受け皿へ落ちていた。
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

  // 廃止済み受け皿の id。既存 yml からの掃除対象を同定するためだけに残す(新規生成はしない)。
  window.UNCLASSIFIED_EDITOR_CATEGORY_ID = UNCLASSIFIED_CATEGORY_ID;

  /**
   * 新規追加/複製時の割当。名前は呼び出し側との互換のため据え置き。
   * 絞り込み中のカテゴリがあればそこへ、無ければ無所属のまま (ensureItemEditorCategory)。
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

  /**
   * categoryId null/empty removes item from all nested categories.
   * @param {object} [opts]
   * @param {boolean} [opts.skipDraftSync] true のとき draft: true の付け外しを一切行わない。
   *   【2026-08-02 指摘1】表示タブの移動 (moveItemDisplayTab) は、移動先タブ内で
   *   ネストカテゴリの付け替え(旧タブの所属を外す/新タブの「未分類」等へ内部的に入れる)を
   *   伴うが、これは利用者が「準備中に入れた/出した」という意思表示ではない。
   *   ここで無条件に syncDraftFlag すると、準備中(draft:true)の品を他タブへピン留めし直した
   *   瞬間に draft が外れ、次の保存でゲームに出てしまう(レシピ登録・ガチャ抽選対象化)。
   *   syncDraftFlag を呼ぶのは「利用者がカテゴリセレクトを直接操作した」経路
   *   (renderEditorCategorySelect の onChange・複製・新規追加の絞り込み割当)だけに限定する。
   */
  window.moveItemEditorCategory = function moveItemEditorCategory(host, tabKey, itemId, categoryId, opts) {
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
    if (!(opts && opts.skipDraftSync)) {
      syncDraftFlag(host, itemId, categoryId === DRAFT_CATEGORY_ID);
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

  window.removeEditorCategoryItem = function removeEditorCategoryItem(host, tabKey, itemId, opts) {
    window.moveItemEditorCategory(host, tabKey, itemId, null, opts);
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
    // 【2026-08-02 指摘1 CRITICAL】表示タブの移動はネストカテゴリの内部的な付け替えを伴うが、
    // 利用者が「準備中へ入れた/出した」わけではない。skipDraftSync で draft: true の付け外しを
    // 一切発生させない(旧タブの準備中カテゴリから外れても draft は残る/元々無ければ付かない)。
    const internalOpts = { skipDraftSync: true };
    for (const k of keys) {
      if (!k || k === toTab) continue;
      window.removeEditorCategoryItem(host, k, itemId, internalOpts);
    }
    if (typeof window.appendEditorOrder === "function") {
      window.appendEditorOrder(host, toTab, itemId);
    }
    // 移動元タブのネストカテゴリからは外れたので、移動先タブでも必ずどこかへ入れる
    // (でないと「表示タブを変えたら未設定へ落ちる」= 2026-08-01 報告と同じ形になる)。
    // ここも internalOpts を渡し、移動先の「未分類」等へ内部的に入っただけで draft が
    // 変化しないようにする。
    window.ensureItemEditorCategory(host, toTab, itemId, internalOpts);
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
   * カテゴリセレクトの薄字(id)表示を決める純関数。
   *
   * <p>2026-08-04 報告「AIが作成したカテゴリだけ内部idが薄字で見える」の修正。旧実装は
   * 「idの見た目」(`"cat_" + Date.now()` 形式かどうか)で判定していたため、GUIの「+ カテゴリ」
   * ボタンが払い出す純数値idは隠れる一方、セッション(AI)がyml へ直接書く
   * `cat_20260724_source_gem` のような説明的な命名は常に薄字で残っていた。
   *
   * <p>ここでは id の命名規則を見ず、**ラベルの有用性**で判定する:
   * ラベルが空なら(選択肢が空欄になるので)id を出す。ラベルが同じセレクト内で他と
   * 重複していれば区別のため id を出す。それ以外(ラベルが非空かつ一意)は id を隠す。
   *
   * @param {Array<{id:string,label?:string}>} cats 同じセレクトに並ぶカテゴリ一覧
   * @returns {string[]} cats と同じ順序の副表記文字列 (不要なら "")
   */
  window.computeCategorySecondaries = function computeCategorySecondaries(cats) {
    const list = Array.isArray(cats) ? cats : [];
    const labelCounts = new Map();
    for (const cat of list) {
      const label = (cat && cat.label) || "";
      labelCounts.set(label, (labelCounts.get(label) || 0) + 1);
    }
    return list.map((cat) => {
      if (!cat) return "";
      const label = cat.label || "";
      if (!label) return cat.id; // ラベルが空 → 選択肢が空欄にならないよう id を出す
      return labelCounts.get(label) > 1 ? cat.id : ""; // 重複ラベルのみ id で区別
    });
  };

  /**
   * Per-card nested category selector.
   * @param {function} [onChange] called after itemIds update
   */
  window.renderEditorCategorySelect = function renderEditorCategorySelect(host, tabKey, itemId, onChange) {
    const cats = listCategories(host, tabKey);
    const current = window.getItemEditorCategory(host, tabKey, itemId);
    const secondaries = window.computeCategorySecondaries(cats);
    const picker = window.listSelect({
      value: current || "",
      className: "editor-cat-select",
      placeholder: "(未設定)",
      options: [{ value: "", primary: "(未設定)", secondary: "" }].concat(
        cats.map((cat, i) => ({
          value: cat.id,
          primary: cat.label || cat.id,
          secondary: secondaries[i]
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

  /**
   * 【2026-08-06 CRITICAL】ここで `_editor.itemTabs` を**その場で**書き換えてはいけない。
   *
   * <p>getData() の出力 `out` は `{ ...working }` の浅いコピーなので `out._editor` は
   * **画面が握っている `working._editor` と同一オブジェクト**になる。その場で delete すると
   * 保存用の整形のつもりで画面の表示タブピンまで消える。しかも getData() は画面を開いた
   * 直後 (app.js の syncBaseFromEditor) にも呼ばれるので、開いた瞬間に消える。
   *
   * <p>実害: buildItemStatsForm はカタログ候補ぶんの「値なしの空枠」を作り、候補の正しい
   * タブ (触媒/魔導書など) をピン留めする。空枠は dropEmptyItemProfiles で出力の items から
   * 落ちるため、そのピンが「孤児」と誤判定されて消え、表示タブが Material 推論へ退化した。
   * 結果 BLAZE_ROD (触媒) と BOOK (魔導書) が「補助」タブの未設定に湧き、
   * 消しても再描画のたびに候補同期で復活する ── これが 2026-08-06 の
   * 「補助の未設定にある内容が消せない。他のカテゴリにあるから要らないのに」の正体。
   *
   * <p>そこで**出力オブジェクトの `_editor` だけを差し替える**。`categories` / `orders` は
   * 同じ参照のまま持ち回るので、行エディタが掴んでいる配列は孤児にならない
   * (「刈り取りは working 配下のコンテナを差し替えない」という既存の不変条件と同じ理由)。
   */
  function pruneOrphanItemTabs(data) {
    const ed = data._editor;
    if (!ed || typeof ed !== "object") return;
    const tabs = ed.itemTabs;
    if (!tabs || typeof tabs !== "object") return;
    const items = data.items;
    // items が無い/オブジェクトでない config では判定材料が無いので触らない(安全側)。
    if (!items || typeof items !== "object" || Array.isArray(items)) return;
    const kept = {};
    let dropped = false;
    for (const [itemId, tab] of Object.entries(tabs)) {
      if (Object.prototype.hasOwnProperty.call(items, itemId)) kept[itemId] = tab;
      else dropped = true;
    }
    if (!dropped) return; // 落とすものが無いなら参照もそのまま保つ
    const cleaned = { ...ed };
    if (Object.keys(kept).length === 0) delete cleaned.itemTabs;
    else cleaned.itemTabs = kept;
    data._editor = cleaned;
  }

  window.ensureEditorMeta = ensureEditor;
  window.listEditorCategories = listCategories;
})();
