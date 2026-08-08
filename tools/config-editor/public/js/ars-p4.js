"use strict";

// ArsPaper glyphs.yml 専用フォーム (P4)。
//   glyphs.yml   : glyphs.<id>{tier, mana-cost, params, max-augments, unlock-cost{level, materials}}
//
// 往復ロスレス最優先: working は受け取った data を直接編集する。
// グリフID / params キー / max-augments 増強名はハードコード実装と一致必須のため読取専用。
// 値のみ編集可。未知キー・キー順を温存。

(function () {
  const h = window.h;

  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }
  const has = (o, k) => o && Object.prototype.hasOwnProperty.call(o, k);

  // タスク6 (2026-07-26): グリフのカテゴリは「意味を持たない表示用の分類」で、グリフごとの
  // category 自由入力欄からしか生まれない(=まだどのグリフにも使われていない空のカテゴリを
  // 先に名付けておく手段が無かった)。他タブ(editor-categories.js の renderEditorCategoryBar)には
  // 「+ カテゴリ」ボタンがあるのに、このグリフ画面には無かったのが「カテゴリを追加できない」の原因。
  // yml へは保存しない一覧(既存のカテゴリ運用方針を維持)なので、ページ内メモリだけの
  // "空カテゴリの仮登録" セットを用意し、グリフの category 欄からも選べるようにする。
  // 実際にどれかのグリフへ割り当てられた時点で collectCategories() の通常経路に合流する。
  function mergeCategoryNames(usedCategories, pendingCategories) {
    const set = new Set(usedCategories || []);
    for (const c of pendingCategories || []) {
      const v = String(c || "").trim();
      if (v) set.add(v);
    }
    return [...set].sort();
  }
  window.GLYPH_CATEGORY_LOGIC = { mergeCategoryNames };

  function maxSpellbookTier() {
    // spellbooks.yml の max-glyph-tier 最大値。未取得時は 3。
    const cached = window._SPELLBOOK_MAX_GLYPH_TIER;
    if (Number.isFinite(cached) && cached >= 1) return Math.floor(cached);
    return 3;
  }

  async function refreshSpellbookMaxTier() {
    try {
      const res = await fetch("/api/config/spellbooks");
      const json = await res.json();
      const data = json && json.data;
      let max = 3;
      const books = data && (data["spell-books"] || data.spellbooks || data.books);
      if (Array.isArray(books)) {
        for (const b of books) {
          const t = Number(b && b["max-glyph-tier"]);
          if (Number.isFinite(t) && t > max) max = t;
        }
      } else if (books && typeof books === "object") {
        for (const b of Object.values(books)) {
          if (!b || typeof b !== "object") continue;
          const t = Number(b["max-glyph-tier"]);
          if (Number.isFinite(t) && t > max) max = t;
        }
      }
      window._SPELLBOOK_MAX_GLYPH_TIER = max;
    } catch (_) { /* keep default */ }
  }

  window.buildGlyphsForm = function buildGlyphsForm(data, companionData) {
    const working = data && typeof data === "object" ? data : {};
    if (!working.glyphs || typeof working.glyphs !== "object") working.glyphs = {};
    const glyphs = working.glyphs;
    // T1 (2026-07-25): stats/glyph-damage-boost.yml (TrinityForge base) をこの画面のコンパニオンとして
    // 編集する (glyph_damage_multiplier_bonus の適用対象グリフ一覧)。保存は getExtraSaves 経由。
    const working2 = companionData && typeof companionData === "object" ? companionData : {};
    if (!Array.isArray(working2["boosted-glyphs"])) working2["boosted-glyphs"] = [];
    let filter = "";
    let categoryFilter = ""; // "" = すべて, "__unset__" = カテゴリ未設定, それ以外 = カテゴリ値
    const expanded = new Set();
    // タスク6: 「+ カテゴリ」で先に名付けた、まだどのグリフにも割り当てていない空カテゴリ名。
    // yml には保存しない(ページ内メモリのみ、リロードで消える)。
    const pendingCategories = new Set();

    if (window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
      window.RECIPES_UI.ensureCustomDatalist().catch(() => {});
    }
    refreshSpellbookMaxTier().then(() => renderList());

    const root = h("div", { class: "dedicated-form glyphs-form card-list" });
    const searchInput = h("input", {
      class: "field-input recipe-search", placeholder: "グリフid・表示名で絞り込み", spellcheck: "false",
      value: filter, oninput: (e) => { filter = e.target.value; renderList(); }
    });
    const countLabel = h("span", { class: "glyph-count" });
    const catBar = h("div", { class: "recipe-tabs glyph-cat-bar", role: "tablist" });
    const listBox = h("div", { class: "card-list-body glyphs-card-list" });
    root.appendChild(h("div", { class: "recipe-toolbar" }, [searchInput, countLabel]));
    root.appendChild(catBar);
    root.appendChild(h("div", {
      class: "field-desc",
      style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 10px;",
      text: "グリフID・params名・増強名は Java 実装と紐づくため編集不可。値・マナ・ティア・解放コスト・表示名・カテゴリのみ変更できます。"
        + "表示名/カテゴリは editor 専用の表示整理用メタデータで、実装(fork)からは無視されます。ティア上限は魔導書の max-glyph-tier 最大値に連動します。"
    }));
    root.appendChild(boostedGlyphsSection());
    root.appendChild(exchangeTiersSection());
    root.appendChild(crushMapSection());
    root.appendChild(entityExchangePairsSection());
    root.appendChild(listBox);

    // カテゴリ値一覧 (glyphs.<id>.category から動的収集。意味を持たない表示用分類なので固定リストは持たない)。
    // タスク6: 「+ カテゴリ」で仮登録した pendingCategories もここへ合流する(まだ0件のグリフしか
    // 使っていなくてもチップ・datalist候補として見える)。
    function collectCategories() {
      const used = new Set();
      for (const id of Object.keys(glyphs)) {
        const e = glyphs[id];
        const cat = e && typeof e === "object" ? String(e.category || "").trim() : "";
        if (cat) used.add(cat);
      }
      return window.GLYPH_CATEGORY_LOGIC.mergeCategoryNames(used, pendingCategories);
    }

    function renderCatBar() {
      catBar.innerHTML = "";
      const cats = collectCategories();
      const chips = [["", "すべて"], ["__unset__", "未設定"]].concat(cats.map((c) => [c, c]));
      for (const [value, label] of chips) {
        catBar.appendChild(h("button", {
          class: "recipe-tab" + (categoryFilter === value ? " active" : ""),
          type: "button", role: "tab",
          "aria-selected": categoryFilter === value ? "true" : "false",
          onclick: () => { categoryFilter = value; renderList(); }
        }, [h("span", { text: label })]));
      }
      // タスク6: 他タブ(editor-categories.js の renderEditorCategoryBar)と同じ操作性の
      // 「+ カテゴリ」ボタン。ここで名付けた時点ではどのグリフにも紐づいていない
      // (yml上は何も変わらない)ため、実際に使うにはいずれかのグリフの「カテゴリ」欄で
      // この名前を選ぶ/入力する必要がある(未使用のまま画面を離れると消える)。
      catBar.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ カテゴリ",
        onclick: () => {
          const nameRaw = prompt("新しいカテゴリ名(まだどのグリフにも割り当てません。後で各グリフの「カテゴリ」欄から選べます)");
          const name = (nameRaw || "").trim();
          if (!name) return;
          pendingCategories.add(name);
          categoryFilter = name;
          renderList();
        }
      }));
    }

    function matchesCategory(id) {
      if (!categoryFilter) return true;
      const e = glyphs[id];
      const cat = e && typeof e === "object" ? String(e.category || "").trim() : "";
      if (categoryFilter === "__unset__") return !cat;
      return cat === categoryFilter;
    }

    function matches(id) {
      if (!matchesCategory(id)) return false;
      const f = filter.trim().toLowerCase();
      if (!f) return true;
      const e = glyphs[id];
      const displayName = e && typeof e === "object" && e["display-name"] ? String(e["display-name"]) : "";
      return String(id).toLowerCase().includes(f) || displayName.toLowerCase().includes(f);
    }

    function glyphTierOptions() {
      const max = Math.max(3, maxSpellbookTier());
      const list = [];
      for (let i = 1; i <= max; i++) list.push(String(i));
      return list;
    }

    // stats/glyph-damage-boost.yml (companion): glyph_damage_multiplier_bonus stat の適用対象グリフ一覧。
    // 有効なグリフID(glyphs.<id>のキー)のみ選べる listSelect にして、実装が読まない無効IDの
    // 混入(サイレントno-op)を防ぐ。
    function boostedGlyphsSection() {
        const list = working2["boosted-glyphs"];
        const box = h("div", { class: "stat-rows" });
        function knownGlyphOptions(excludeSet) {
          return Object.keys(glyphs)
            .filter((id) => !excludeSet.has(id))
            .sort()
            .map((id) => {
              const e = glyphs[id];
              const dn = e && typeof e === "object" && e["display-name"] ? String(e["display-name"]) : "";
              return { value: id, primary: dn ? `${dn} (${id})` : id, secondary: dn ? id : "" };
            });
        }
        function render() {
          box.innerHTML = "";
          if (!list.length) {
            box.appendChild(h("div", { class: "empty-hint", text: "対象グリフがありません(ボーナス無効)。" }));
          }
          list.forEach((val, idx) => {
            const used = new Set(list.filter((_, i) => i !== idx));
            const opts = knownGlyphOptions(used);
            const cur = String(val || "");
            if (cur && !opts.some((o) => o.value === cur)) {
              opts.unshift({ value: cur, primary: `${cur} (未登録のグリフID)`, secondary: cur });
            }
            const row = h("div", { class: "stat-row" }, [
              window.listSelect({
                value: cur,
                placeholder: "グリフを選択…",
                options: opts,
                onChange: (v) => { list[idx] = v; render(); }
              }),
              h("button", {
                class: "btn-small danger", type: "button", text: "×",
                onclick: () => { list.splice(idx, 1); render(); }
              })
            ]);
            box.appendChild(row);
          });
          const used = new Set(list);
          const remaining = knownGlyphOptions(used);
          box.appendChild(h("button", {
            class: "btn-small", type: "button", text: "+ グリフ追加",
            disabled: !remaining.length,
            onclick: () => {
              if (!remaining.length) return;
              list.push(remaining[0].value);
              render();
            }
          }));
        }
        render();
        return window.collapsibleCard(
          [h("span", { class: "entry-key-label", text: "グリフダメージブースト対象 (glyph-damage-boost.yml)" })],
          [
            h("div", {
              class: "field-desc",
              style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
              text: "ars_magic.yml「害悪強化」(glyph_damage_multiplier_bonus stat)のダメージ倍率ボーナスが乗る対象グリフ一覧。"
                + "コンパニオンファイル stats/glyph-damage-boost.yml (TrinityForge base) を一緒に保存します。"
            }),
            box
          ],
          { expanded: false }
        );
    }

    // ---- 共通: Material / EntityType の単一値フィールド (未知の値はブロックせず赤枠で警告) ----
    function materialField(value, onChange) {
      const hint = window.materialHintEl(value);
      const wrap = window.materialInput(value, "glyphs-exchange", (v) => {
        hint.update(v);
        updateErr(v);
        onChange(v);
      }, { allowCustom: false });
      function updateErr(v) {
        const list = Array.isArray(window.MATERIALS) ? window.MATERIALS : [];
        const ok = !v || !list.length || list.includes(String(v).toUpperCase());
        wrap.classList.toggle("field-invalid", !ok);
        wrap.title = ok ? "" : `未知のMaterialです（1.21.11のMaterial一覧に見つかりません。保存は可能ですが誤字の可能性があります）: ${v}`;
      }
      updateErr(value);
      return h("span", { class: "input-with-hint" }, [wrap, hint]);
    }

    // 2026-07-29: datalist 付きの素の text 入力(候補は英字ID、和名は横の hint だけ)を、
    // 日本語名で引けるセレクトへ置換。候補外の値は「候補外」注記つきで残す(誤字検知は
    // 従来の field-invalid ではなく副表示で行う)。
    function entityField(value, onChange) {
      return window.mobTypeSelect(value, (v) => {
        onChange(String(v || "").trim().toUpperCase().replace(/[^A-Z0-9_]/g, ""));
      }, { unknownNote: "候補外(誤字の可能性)" });
    }

    // ---- 交換グリフ: ブロック変換ティア (exchange_tiers) ----
    // ティアはブロックのグループ配列。同一ティア内でサイクル変換し、Amplify増強で
    // 上位ティア(配列の後方)の先頭へ変換される。順序(ティア間・ティア内とも)に意味がある。
    function exchangeTiersSection() {
      if (!Array.isArray(working.exchange_tiers)) working.exchange_tiers = [];
      const tiers = working.exchange_tiers;
      const box = h("div", { class: "glyph-section" });
      function render() {
        box.innerHTML = "";
        if (!tiers.length) {
          box.appendChild(h("div", { class: "empty-hint", text: "交換グリフのブロック変換ティアがまだありません。「+ ティア追加」でグループを作成します。" }));
        }
        tiers.forEach((rawGroup, ti) => {
          const group = Array.isArray(rawGroup) ? rawGroup : (tiers[ti] = []);
          const rows = h("div", { class: "stat-rows" });
          function renderRows() {
            rows.innerHTML = "";
            group.forEach((mat, mi) => {
              const field = materialField(mat, (v) => { group[mi] = v; });
              rows.appendChild(h("div", { class: "stat-row" }, [
                field,
                h("button", { class: "btn-small", type: "button", text: "↑", title: "グループ内で前へ",
                  onclick: () => { if (mi > 0) { const t = group[mi - 1]; group[mi - 1] = group[mi]; group[mi] = t; renderRows(); } } }),
                h("button", { class: "btn-small", type: "button", text: "↓", title: "グループ内で後へ",
                  onclick: () => { if (mi < group.length - 1) { const t = group[mi + 1]; group[mi + 1] = group[mi]; group[mi] = t; renderRows(); } } }),
                h("button", { class: "btn-small danger", type: "button", text: "×",
                  onclick: () => { group.splice(mi, 1); renderRows(); } })
              ]));
            });
            rows.appendChild(h("button", { class: "btn-small", type: "button", text: "+ ブロック追加",
              onclick: () => { group.push("STONE"); renderRows(); } }));
          }
          renderRows();
          const head = [
            h("span", { class: "entry-key-label", text: `ティア ${ti + 1}` }),
            h("span", { class: "cf-muted", text: `${group.length}種` }),
            h("div", { class: "spacer" }),
            h("button", { class: "btn-small", type: "button", text: "↑", title: "ティアの順序を前へ",
              onclick: () => { if (ti > 0) { const t = tiers[ti - 1]; tiers[ti - 1] = tiers[ti]; tiers[ti] = t; render(); } } }),
            h("button", { class: "btn-small", type: "button", text: "↓", title: "ティアの順序を後へ(Amplifyで進む先)",
              onclick: () => { if (ti < tiers.length - 1) { const t = tiers[ti + 1]; tiers[ti + 1] = tiers[ti]; tiers[ti] = t; render(); } } }),
            h("button", { class: "btn-small danger", type: "button", text: "ティア削除",
              onclick: () => { tiers.splice(ti, 1); render(); } })
          ];
          box.appendChild(window.collapsibleCard(head, [rows], { expanded: false }));
        });
        box.appendChild(h("div", { class: "form-actions" }, [
          h("button", { class: "btn-small", type: "button", text: "+ ティア追加",
            onclick: () => { tiers.push(["STONE"]); render(); } })
        ]));
      }
      render();
      return window.collapsibleCard(
        [h("span", { class: "entry-key-label", text: "交換グリフ: ブロック変換ティア (exchange_tiers)" })],
        [
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 10px;",
            text: "各ティアはブロックのグループです。同一ティア内でサイクル変換します。Amplify増強でティアを上げると"
              + "上位グループ(下に並ぶティア)の先頭に変換されます。サーバー管理者が自由にグループやブロックを追加・変更できます。"
              + "ティア間・ティア内とも並び順に意味があるため、↑↓で並べ替えてください。"
          }),
          box
        ],
        { expanded: false }
      );
    }

    // ---- 粉砕グリフ: 変換マッピング (crush_map) ----
    // 変換前 → 変換後 のペア。変換後が非ブロック素材(染料等)の場合、ブロックを破壊してアイテムをドロップする。
    function crushMapSection() {
      if (!working.crush_map || typeof working.crush_map !== "object" || Array.isArray(working.crush_map)) working.crush_map = {};
      const map = working.crush_map;
      const box = h("div", { class: "stat-rows" });
      function isBlockMaterial(mat) {
        const set = window.BLOCK_MATERIALS;
        return !!(set && set.size && set.has(String(mat || "").toUpperCase()));
      }
      function render() {
        box.innerHTML = "";
        const keys = Object.keys(map);
        if (!keys.length) box.appendChild(h("div", { class: "empty-hint", text: "粉砕グリフの変換マッピングがまだありません。" }));
        keys.forEach((before) => {
          const beforeField = materialField(before, (v) => {
            const nv = String(v || "").trim().toUpperCase();
            if (!nv || nv === before) return;
            if (has(map, nv)) { alert(`変換前 "${nv}" は既に存在します`); render(); return; }
            renameKey(map, before, nv);
            render();
          });
          const warn = h("span", { class: "inline-warn" });
          function updateWarn() {
            const known = Array.isArray(window.MATERIALS) && window.MATERIALS.includes(String(map[before] || "").toUpperCase());
            const set = window.BLOCK_MATERIALS;
            if (map[before] && known && set && set.size && !isBlockMaterial(map[before])) {
              warn.textContent = "⚠ 変換後がブロックではありません。ブロックを破壊してアイテムをドロップする挙動になります。";
            } else {
              warn.textContent = "";
            }
          }
          const afterField = materialField(map[before], (v) => { map[before] = v; updateWarn(); });
          updateWarn();
          box.appendChild(h("div", { class: "stat-row crush-map-row" }, [
            beforeField, h("span", { class: "mini-label", text: "→" }), afterField,
            h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { delete map[before]; render(); } })
          ]));
          box.appendChild(warn);
        });
        box.appendChild(h("button", { class: "btn-small", type: "button", text: "+ 変換追加",
          onclick: () => {
            let n = "STONE", i = 1, key = n;
            while (has(map, key)) key = `${n}_${i++}`;
            map[key] = "COBBLESTONE";
            render();
          } }));
      }
      render();
      return window.collapsibleCard(
        [h("span", { class: "entry-key-label", text: "粉砕グリフ: 変換マッピング (crush_map)" })],
        [
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 10px;",
            text: "変換前→変換後のペアです。サーバー管理者が自由に追加・変更できます。"
              + "変換後が非ブロック素材(染料等)の場合、ブロックを破壊してアイテムをドロップする挙動になります(下に警告表示)。"
          }),
          box
        ],
        { expanded: false }
      );
    }

    // ---- 交換グリフ: エンティティペアマッピング (entity_exchange_pairs) ----
    // 各配列はサイクル巡回(A→B→C→...→A)。2要素=双方向ペア、3要素以上=循環。
    function entityExchangePairsSection() {
      if (!Array.isArray(working.entity_exchange_pairs)) working.entity_exchange_pairs = [];
      const cycles = working.entity_exchange_pairs;
      const box = h("div", { class: "glyph-section" });
      function render() {
        box.innerHTML = "";
        if (!cycles.length) {
          box.appendChild(h("div", { class: "empty-hint", text: "交換グリフのエンティティサイクルがまだありません。「+ サイクル追加」で作成します。" }));
        }
        cycles.forEach((rawCycle, ci) => {
          const cycle = Array.isArray(rawCycle) ? rawCycle : (cycles[ci] = []);
          const rows = h("div", { class: "stat-rows" });
          function renderRows() {
            rows.innerHTML = "";
            cycle.forEach((ent, ei) => {
              const field = entityField(ent, (v) => { cycle[ei] = v; });
              rows.appendChild(h("div", { class: "stat-row" }, [
                field,
                h("button", { class: "btn-small", type: "button", text: "↑", title: "サイクル内で前へ",
                  onclick: () => { if (ei > 0) { const t = cycle[ei - 1]; cycle[ei - 1] = cycle[ei]; cycle[ei] = t; renderRows(); } } }),
                h("button", { class: "btn-small", type: "button", text: "↓", title: "サイクル内で後へ",
                  onclick: () => { if (ei < cycle.length - 1) { const t = cycle[ei + 1]; cycle[ei + 1] = cycle[ei]; cycle[ei] = t; renderRows(); } } }),
                h("button", { class: "btn-small danger", type: "button", text: "×",
                  onclick: () => { cycle.splice(ei, 1); renderRows(); } })
              ]));
            });
            if (cycle.length < 2) {
              rows.appendChild(h("div", { class: "inline-warn", text: "⚠ サイクルは最低2種類のEntityTypeが必要です。" }));
            }
            rows.appendChild(h("button", { class: "btn-small", type: "button", text: "+ エンティティ追加",
              onclick: () => { cycle.push("ZOMBIE"); renderRows(); } }));
          }
          renderRows();
          const head = [
            h("span", { class: "entry-key-label", text: `サイクル ${ci + 1}` }),
            h("span", { class: "cf-muted", text: `${cycle.length}種` }),
            h("div", { class: "spacer" }),
            h("button", { class: "btn-small danger", type: "button", text: "サイクル削除",
              onclick: () => { cycles.splice(ci, 1); render(); } })
          ];
          box.appendChild(window.collapsibleCard(head, [rows], { expanded: false }));
        });
        box.appendChild(h("div", { class: "form-actions" }, [
          h("button", { class: "btn-small", type: "button", text: "+ サイクル追加",
            onclick: () => { cycles.push(["HOGLIN", "ZOGLIN"]); render(); } })
        ]));
      }
      render();
      return window.collapsibleCard(
        [h("span", { class: "entry-key-label", text: "交換グリフ: エンティティ変換 (entity_exchange_pairs)" })],
        [
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 10px;",
            text: "交換グリフでエンティティに使用した際、サイクル巡回で種類を入れ替えます(A→B→C→…→A)。"
              + "2要素は双方向ペア、3要素以上は循環になります。サーバー管理者が自由にサイクルを追加・変更できます。"
          }),
          box
        ],
        { expanded: false }
      );
    }

    function renderList() {
      renderCatBar();
      listBox.innerHTML = "";
      const keys = Object.keys(glyphs);
      const shown = keys.filter(matches);
      countLabel.textContent = `${shown.length} / ${keys.length} 件`;
      if (keys.length === 0) listBox.appendChild(emptyGuide("グリフがまだありません。", "「+ グリフ追加」で登録します（新規IDは実装追加後にのみ意味があります）。"));
      else if (shown.length === 0) listBox.appendChild(h("div", { class: "empty-hint", text: "一致するグリフがありません。" }));
      for (const id of shown) listBox.appendChild(renderCard(id));
      listBox.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ グリフ追加",
          onclick: () => {
            let n = "new_glyph", i = 1;
            while (has(glyphs, n)) n = `new_glyph_${i++}`;
            glyphs[n] = { tier: 1, "mana-cost": 10 };
            expanded.add(n);
            filter = "";
            searchInput.value = "";
            renderList();
          }
        })
      ]));
    }

    function renderCard(id) {
      const e = glyphs[id] && typeof glyphs[id] === "object" && !Array.isArray(glyphs[id]) ? glyphs[id] : (glyphs[id] = {});
      // 折りたたみ時のタイトルは display-name (未設定ならキー名)。
      const displayTitle = (e["display-name"] && String(e["display-name"]).trim()) || id;
      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: displayTitle }),
          displayTitle !== id ? h("span", { class: "entry-sum-id", text: id }) : null,
          h("span", { class: "entry-sum-meta", text: `T${e.tier != null ? e.tier : "?"} · mana ${e["mana-cost"] != null ? e["mana-cost"] : "—"}` })
        ]),
        h("div", { class: "entry-collapse-edit" }, [
          h("span", { class: "entry-key-label", text: "id" }),
          h("span", { class: "field-readonly", text: id, title: "グリフIDは Java SpellComponent のキーと一致必須のため変更不可" }),
          h("div", { class: "spacer" }),
          h("button", {
            class: "btn-small", type: "button", text: "複製",
            onclick: () => {
              let copy = id + "_copy", i = 1;
              while (has(glyphs, copy)) copy = `${id}_copy_${i++}`;
              glyphs[copy] = JSON.parse(JSON.stringify(e));
              expanded.add(copy);
              renderList();
            }
          }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete glyphs[id]; expanded.delete(id); renderList(); }
          })
        ])
      ];

      const body = h("div", { class: "entry-inputs" });
      const rerender = () => renderList();

      body.appendChild(displayMetaSection(e, rerender));
      body.appendChild(scalarSection(e, "tier", rerender, { kind: "tier" }));
      body.appendChild(scalarSection(e, "mana-cost", rerender, { kind: "int" }));
      body.appendChild(mapSection(e, "params", rerender, "パラメータ (params) — キー読取専用", (obj) => kvNumberRowsReadonlyKeys(obj)));
      body.appendChild(mapSection(e, "max-augments", rerender, "増強の最大数 (max-augments) — 増強名読取専用", (obj) => augmentRowsReadonly(obj)));
      body.appendChild(unlockSection(e, rerender));

      return window.collapsibleCard(head, [body], {
        expanded: expanded.has(id),
        onToggle: (open) => { if (open) expanded.add(id); else expanded.delete(id); }
      });
    }

    // 表示名 (display-name) + カテゴリ (category): editor専用の表示整理用メタデータ。
    // fork実装からは無視される (values-only, IDと違い変更・削除も自由)。
    const CATEGORY_DATALIST_ID = "glyph-category-list";
    function categoryDatalist() {
      let dl = document.getElementById(CATEGORY_DATALIST_ID);
      if (!dl) { dl = h("datalist", { id: CATEGORY_DATALIST_ID }); document.body.appendChild(dl); }
      dl.innerHTML = "";
      for (const cat of collectCategories()) dl.appendChild(h("option", { value: cat }));
      return dl;
    }

    function displayMetaSection(e, rerender) {
      categoryDatalist();
      const box = h("div", { class: "glyph-section" });
      const row = h("div", { class: "stat-rows" });
      row.appendChild(h("div", { class: "form-field" }, [
        window.fieldLabelEl("display-name", { label: "表示名", desc: "editor表示用の和名。fork実装は無視する (未設定ならキー名を表示)。" }),
        // 2026-08-08: 以前は textInput(oninput=1文字ごと)のコールバックで rerender() していたため、
        // 1文字打つたびに入力欄が作り直されてフォーカスが飛んでいた。すぐ下の category 欄と同じく
        // 「入力中は値を書くだけ / 確定時に再描画」へ揃える。
        h("input", {
          class: "field-input",
          value: e["display-name"] || "",
          spellcheck: "false",
          oninput: (ev) => {
            const nv = (ev.target.value || "").trim();
            if (nv) e["display-name"] = nv; else delete e["display-name"];
          },
          onchange: () => rerender()
        })
      ]));
      const catInput = h("input", {
        class: "field-input", value: e.category || "", list: CATEGORY_DATALIST_ID,
        placeholder: "例: 移動 / 攻撃 / 詠唱補助",
        oninput: (ev) => {
          const nv = (ev.target.value || "").trim();
          if (nv) e.category = nv; else delete e.category;
        },
        onchange: () => rerender()
      });
      row.appendChild(h("div", { class: "form-field" }, [
        window.fieldLabelEl("category", { label: "カテゴリ", desc: "意味を持たない表示用の分類 (自由入力・既存候補はdatalistでサジェスト)。fork実装は無視する。" }),
        catInput
      ]));
      box.appendChild(row);
      return box;
    }

    function scalarSection(e, key, rerender, opts) {
      if (!has(e, key)) {
        return h("div", { class: "opt-add" }, [
          h("button", {
            class: "btn-small", type: "button", text: `+ ${key}`,
            onclick: () => { e[key] = opts.kind === "tier" ? 1 : 0; rerender(); }
          })
        ]);
      }
      let control;
      if (opts.kind === "tier") {
        const cur = String(e[key]);
        const optsList = glyphTierOptions();
        if (!optsList.includes(cur)) optsList.push(cur);
        control = window.listSelect({
          value: cur,
          options: optsList.map((o) => ({ value: o, primary: `Tier ${o}` })),
          onChange: (v) => {
            const n = Number(v);
            e[key] = Number.isNaN(n) ? v : n;
          }
        });
      } else {
        control = window.numberInput(e[key], (v) => { e[key] = v == null ? 0 : v; }, { int: opts.kind === "int" });
      }
      // tier / mana-cost は実装必須キーのため削除ボタンは出さない (値のみ編集可)。
      return h("div", { class: "form-field" }, [
        window.fieldLabelEl(key), control
      ]);
    }

    function mapSection(e, key, rerender, title, renderInner) {
      const box = h("div", { class: "glyph-section" });
      if (!has(e, key)) {
        // キーが実装固定のセクションは、空マップを後から足しても意味がないため追加UIも出さない。
        box.style.display = "none";
        return box;
      }
      // セクション自体は実装と紐づくため削除不可 (値のみ編集可)。
      box.appendChild(h("div", { class: "sub-title" }, [
        h("span", { text: title })
      ]));
      const obj = e[key] && typeof e[key] === "object" ? e[key] : (e[key] = {});
      box.appendChild(renderInner(obj));
      return box;
    }

    // params キーの日本語ヒント (labels.js の辞書/規則ベース)。ホバーで説明を出す。
    function paramTitle(key) {
      const L = window.LABELS;
      const hint = L && typeof L.glyphParamHint === "function" ? L.glyphParamHint(key) : "";
      return (hint ? `${hint}\n` : "") + "キーは実装固定（値のみ編集可）";
    }

    function kvNumberRowsReadonlyKeys(obj) {
      const box = h("div", { class: "stat-rows" });
      const keys = Object.keys(obj);
      if (!keys.length) {
        box.appendChild(h("div", { class: "empty-hint", text: "パラメータなし（実装が読むキーのみ意味があります）" }));
        return box;
      }
      for (const key of keys) {
        box.appendChild(h("div", { class: "stat-row" }, [
          h("span", { class: "field-readonly", text: key, title: paramTitle(key) }),
          window.numberInput(obj[key], (v) => { obj[key] = v == null ? 0 : v; })
        ]));
      }
      return box;
    }

    // 増強は実装固定セットのため追加/削除UIは出さない (最大スタック数のみ編集可)。
    function augmentRowsReadonly(obj) {
      const box = h("div", { class: "stat-rows" });
      const L = window.LABELS;
      const keys = Object.keys(obj);
      if (!keys.length) {
        box.appendChild(h("div", { class: "empty-hint", text: "増強設定なし（実装が読む増強のみ意味があります）" }));
        return box;
      }
      for (const key of keys) {
        const ja = L && typeof L.augmentLabel === "function" ? L.augmentLabel(key) : "";
        box.appendChild(h("div", { class: "stat-row" }, [
          h("span", {
            class: "field-readonly", text: key,
            title: (ja ? `${ja}\n` : "") + "増強IDは実装固定（最大数のみ編集可）"
          }),
          h("span", { class: "mini-label", text: "最大" }),
          window.numberInput(obj[key], (v) => { obj[key] = v == null ? 0 : v; }, { int: true })
        ]));
      }
      return box;
    }

    function unlockSection(e, rerender) {
      const box = h("div", { class: "glyph-section" });
      if (!has(e, "unlock-cost")) {
        box.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ unlock-cost",
          onclick: () => { e["unlock-cost"] = { level: 0, materials: {} }; rerender(); }
        }));
        return box;
      }
      const uc = e["unlock-cost"] && typeof e["unlock-cost"] === "object" ? e["unlock-cost"] : (e["unlock-cost"] = {});
      // セクション削除は不可 (値のみ編集可)。
      box.appendChild(h("div", { class: "sub-title" }, [
        h("span", { text: "解放コスト (unlock-cost)" })
      ]));
      // Player.getLevel() の経験値レベル（戦闘レベルではない）
      if (has(uc, "level")) {
        box.appendChild(h("div", { class: "form-field" }, [
          window.fieldLabelEl("level", {
            label: "経験値レベル (XP)",
            desc: "バニラ経験値バーのレベル。ScribingTable で Player.getLevel() から消費。戦闘レベルではない。"
          }),
          window.numberInput(uc.level, (v) => { uc.level = v == null ? 0 : v; }, { int: true })
        ]));
      } else {
        box.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 経験値レベル",
          onclick: () => { uc.level = 0; rerender(); }
        }));
      }
      if (has(uc, "materials")) {
        const mats = uc.materials && typeof uc.materials === "object" ? uc.materials : (uc.materials = {});
        box.appendChild(h("div", { class: "mini-label", text: "必要素材 (materials) — Material名のほか custom:<id> でTFカタログ品/Arsアイテムを指定可 (入力欄でカタログ候補をサジェスト)" }));
        box.appendChild(materialRows(mats));
      } else {
        box.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ materials",
          onclick: () => { uc.materials = {}; rerender(); }
        }));
      }
      return box;
    }

    function materialRows(mats) {
      const box = h("div", { class: "stat-rows" });
      function render() {
        box.innerHTML = "";
        for (const key of Object.keys(mats)) {
          const hint = window.materialHintEl(key);
          const matInput = window.materialInput(key, "material-list", (v) => {
            hint.update(v);
            const nv = String(v || "").trim();
            if (!nv || nv === key) return;
            if (has(mats, nv)) { alert("同じ素材が存在します"); return; }
            renameKey(mats, key, nv);
            render();
          }, { allowCustom: true });
          box.appendChild(h("div", { class: "stat-row" }, [
            h("span", { class: "input-with-hint" }, [matInput, hint]),
            h("span", { class: "mini-label", text: "×" }),
            window.numberInput(mats[key], (v) => { mats[key] = v == null ? 0 : v; }, { int: true }),
            h("button", {
              class: "btn-small danger", type: "button", text: "×",
              onclick: () => { delete mats[key]; render(); }
            })
          ]));
        }
        box.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 素材",
          onclick: () => {
            let n = "LAPIS_LAZULI", i = 1, key = n;
            while (has(mats, key)) key = `${n}_${i++}`;
            mats[key] = 1;
            render();
          }
        }));
      }
      render();
      return box;
    }

    renderList();
    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => [{ id: "glyph-damage-boost", data: working2 }]
    };
  };
})();
