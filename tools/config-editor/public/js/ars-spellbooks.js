"use strict";

// ArsPaper spellbooks.yml 専用フォーム。上部タブで「魔導書」/「触媒」を切り替える。
//   spell-books: 配列。並び順 = 魔導書のティア段階(PDCへ書き込まれる整数値)なので、
//   要素の並び順そのものに意味がある。往復ロスレス最優先で working["spell-books"] を直接
//   ミューテートし、既知フィールド(id/display-name/name-color/max-slots/max-glyph-tier/
//   custom-model-data/upgrade-from/cooldown)のみ専用UIで編集する。要素内の未知キーは温存する。
//   catalysts: map(id -> 触媒定義)。catalog.yml の items 相当(material/display-name/…)に
//   item-stat相当のステ(fixed/per-quality/random)を持たせたもの。未定義なら working.catalysts
//   キー自体を作らず、getData() でも空なら出力しない(既存の item-stats/catalog フォームの
//   pruneEntries と同様の方針)。
//
// forms.js の stat-group UI(statSelect/statValueControl/statHintEl)は window 公開済みのため
// そのまま再利用する。renderLoreRows・pruneEntries 等の非公開ヘルパーは forms.js を編集しない
// 方針のため、ここに同等ロジックを最小限で自前実装する(コメントで明記)。

(function () {
  const h = window.h;

  function fieldRow(key, control) {
    return h("div", { class: "form-field" }, [window.fieldLabelEl(key), control]);
  }
  // FIELD_LABELS 辞書に無いキー(cooldown 等)向け: labels.js は編集しない方針のため、
  // ラベル文字列とヒントをその場で指定できる簡易版フィールド行。
  // 説明はブラウザ標準の title ではなく「?」の独自ツールチップへ回す (2026-08-01)。
  function fieldRowCustom(labelText, desc, control) {
    const children = [h("span", { class: "form-label-ja", text: labelText })];
    const help = window.helpIcon(desc);
    if (help) children.push(help);
    const labelEl = h("span", { class: "form-label with-ja" }, children);
    return h("div", { class: "form-field" }, [labelEl, control]);
  }
  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }
  function gridRow(fields) { return h("div", { class: "field-grid" }, fields); }

  // 空文字/未指定は該当キーを削除する (任意フィールドを YAML に空出力しない)。
  // forms.js の同名ヘルパーと同一ロジック (forms.js 非公開のためここに複製)。
  function setOrDelete(obj, key, value) {
    if (value === "" || value === null || value === undefined) delete obj[key];
    else obj[key] = value;
  }

  // 挿入順を保ったままマップのキーをリネームする。forms.js の同名ヘルパーと同一ロジック
  // (forms.js は window 未公開・編集不可方針のためここに複製する)。
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }

  // item-stats フォーム同様、STAT_LIST(lore.yml由来) と FALLBACK_STATS の和集合をステ候補にする
  // (forms.js の statList() と同一ロジック。forms.js 非公開のためここに複製)。
  function statListLocal() {
    const primary = (window.STAT_LIST && window.STAT_LIST.length) ? window.STAT_LIST : [];
    const fallback = window.FALLBACK_STATS || [];
    const seen = new Set();
    const out = [];
    for (const k of primary.concat(fallback)) {
      if (!seen.has(k)) { seen.add(k); out.push(k); }
    }
    return out.length ? out : fallback;
  }

  // 簡易 lore 行エディタ (forms.js の renderLoreRows と同等・非公開のためここに複製)。
  function renderLoreRows(loreArray, mode, onEdit, onStructureChange) {
    const box = h("div", { class: "lore-rows" });
    loreArray.forEach((line, idx) => {
      const row = h("div", { class: "stat-row lore-row" });
      row.appendChild(window.richTextInput(line, mode, (v) => { loreArray[idx] = v; onEdit(); }));
      row.appendChild(h("button", { class: "btn-small", type: "button", text: "↑", title: "上へ", onclick: () => { if (idx > 0) { const t = loreArray[idx - 1]; loreArray[idx - 1] = loreArray[idx]; loreArray[idx] = t; onStructureChange(); } } }));
      row.appendChild(h("button", { class: "btn-small", type: "button", text: "↓", title: "下へ", onclick: () => { if (idx < loreArray.length - 1) { const t = loreArray[idx + 1]; loreArray[idx + 1] = loreArray[idx]; loreArray[idx] = t; onStructureChange(); } } }));
      row.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { loreArray.splice(idx, 1); onStructureChange(); } }));
      box.appendChild(row);
    });
    box.appendChild(h("button", { class: "btn-small", type: "button", text: "+ 行追加", onclick: () => { loreArray.push(""); onStructureChange(); } }));
    return box;
  }

  // bind-type セレクト。catalyst の bind-type は任意(未指定ならbind-type刻印を行わない)なので
  // window.selectLabeledInput(既定値強制)ではなく、空選択肢を持つ専用セレクトにする。
  function bindTypeSelect(value, onChange) {
    const cur = value == null ? "" : String(value);
    const label = window.LABELS ? window.LABELS.enumLabel : (g, v) => v;
    const options = [{ value: "", primary: "(未設定/刻印しない)", secondary: "" }];
    for (const opt of window.BIND_TYPES) {
      const ja = label("bind-type", opt);
      options.push({
        value: opt,
        primary: ja && ja !== opt ? ja : opt,
        secondary: ja && ja !== opt ? opt : "",
        title: opt
      });
    }
    return window.listSelect({
      value: cur,
      options,
      placeholder: "(未設定/刻印しない)",
      onChange
    });
  }

  window.buildSpellbooksForm = function buildSpellbooksForm(data, opts) {
    const options = opts && typeof opts === "object" ? opts : {};
    const hideTabBar = !!options.hideTabBar;
    const working = data && typeof data === "object" ? data : {};
    if (!Array.isArray(working["spell-books"])) working["spell-books"] = [];
    if (!working.catalysts || typeof working.catalysts !== "object") working.catalysts = {};
    const books = working["spell-books"];
    const root = h("div", { class: "dedicated-form" });

    // 旧 mana-cost-reduction.{flat,percent}(固定値のみ)を item-stat 化した
    //   stats.fixed.mana-cost-reduction-flat / mana-cost-reduction-percent へ移行する。
    // これにより固定ステ/品質別上昇値/ランダムロールが他ステと同じUIで設定できる。
    // 移行は冪等(旧キーを消すので再実行しても無害)。既にstats側に同キーがあれば旧値で上書きしない。
    function migrateCatalystManaReduction(entry) {
      if (!entry || typeof entry !== "object") return;
      const mcr = entry["mana-cost-reduction"];
      if (!mcr || typeof mcr !== "object") return;
      if (!entry.stats || typeof entry.stats !== "object") entry.stats = {};
      if (!entry.stats.fixed || typeof entry.stats.fixed !== "object") entry.stats.fixed = {};
      const fixed = entry.stats.fixed;
      if (mcr.flat != null && fixed["mana-cost-reduction-flat"] == null) fixed["mana-cost-reduction-flat"] = mcr.flat;
      if (mcr.percent != null && fixed["mana-cost-reduction-percent"] == null) fixed["mana-cost-reduction-percent"] = mcr.percent;
      delete entry["mana-cost-reduction"];
    }
    for (const centry of Object.values(working.catalysts)) migrateCatalystManaReduction(centry);

    let activeTab = options.initialPart === "catalysts" || options.initialPart === "catalyst"
      ? "catalysts" : "books"; // "books" | "catalysts"
    // 折りたたみ状態(開いているidの集合)。既定は全て折りたたみ (skilltreeと同じUX)。
    // idはidInputのchangeハンドラで重複チェックされ常にユニークなので、idをキーに使う。再描画をまたいで保持する。
    const expandedBooks = new Set();
    const expandedCatalysts = new Set();

    // upgrade-from の選択肢: 自分より前(=下位ティア)の id のみを候補にし、循環/前方参照を避ける。
    function idsBefore(idx) {
      return books.slice(0, idx).map((b) => b && b.id).filter((id) => typeof id === "string" && id !== "");
    }

    function render() {
      root.innerHTML = "";
      if (!hideTabBar) root.appendChild(renderTabBar());
      root.appendChild(activeTab === "books" ? renderBooksSection() : renderCatalystsSection());
    }

    // ============================================================
    // タブバー (魔導書 / 触媒)
    // ============================================================
    function renderTabBar() {
      const tabBar = h("div", { class: "recipe-tabs" });
      const catalystCount = working.catalysts && typeof working.catalysts === "object" ? Object.keys(working.catalysts).length : 0;
      const tabs = [["books", "魔導書", books.length], ["catalysts", "触媒", catalystCount]];
      for (const [key, label, count] of tabs) {
        tabBar.appendChild(h("button", {
          class: `recipe-tab ${activeTab === key ? "active" : ""}`, type: "button",
          onclick: () => { activeTab = key; render(); }
        }, [h("span", { text: label }), h("span", { class: "recipe-tab-count", text: String(count) })]));
      }
      return tabBar;
    }

    // ============================================================
    // 魔導書タブ (既存UI + cooldown)
    // ============================================================
    function renderBooksSection() {
      const box = h("div", { class: "card-list" });
      if (books.length === 0) {
        box.appendChild(emptyGuide("魔導書ティアがまだありません。", "「+ ティア追加」で、下位ティアから順に魔導書を登録します。"));
      }
      books.forEach((b, idx) => box.appendChild(renderBookCard(b, idx)));
      box.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ ティア追加",
          onclick: () => {
            let base = "spell_book_new", i = 1, id = base;
            while (books.some((b) => b && b.id === id)) id = `${base}_${i++}`;
            const prev = books.length ? books[books.length - 1] : null;
            const prevId = prev && typeof prev.id === "string" ? prev.id : null;
            books.push({
              id,
              "display-name": "",
              "name-color": "#FFFFFF",
              "max-slots": 1,
              "max-glyph-tier": 1,
              "custom-model-data": 0,
              "upgrade-from": prevId,
              "cooldown": 0
            });
            expandedBooks.add(id); // 新規追加は開いた状態で編集させる
            render();
          }
        })
      ]));
      return box;
    }

    function renderBookCard(b, idx) {
      if (!b || typeof b !== "object") return h("div");

      const curId = b.id == null ? "" : String(b.id);
      const idInput = h("input", { class: "field-input entry-id", value: curId, spellcheck: "false" });
      idInput.addEventListener("change", (ev) => {
        const nv = ev.target.value.trim();
        if (!nv) { ev.target.value = curId; return; }
        if (books.some((other, j) => j !== idx && other && other.id === nv)) {
          alert("同じidが存在します"); ev.target.value = curId; return;
        }
        const oldId = b.id;
        b.id = nv;
        // 他要素の upgrade-from が旧idを参照していれば追従させる (参照整合性の維持)。
        for (const other of books) {
          if (other && other !== b && other["upgrade-from"] === oldId) other["upgrade-from"] = nv;
        }
        if (expandedBooks.has(oldId)) { expandedBooks.delete(oldId); expandedBooks.add(nv); }
        render();
      });

      const head = [
        h("span", { class: "entry-key-label", text: `ティア ${idx + 1}` }), idInput,
        h("div", { class: "spacer" }),
        h("button", { class: "btn-small", type: "button", text: "↑", title: "上へ (ティア段階が変わります)", onclick: () => { if (idx > 0) { const t = books[idx - 1]; books[idx - 1] = books[idx]; books[idx] = t; render(); } } }),
        h("button", { class: "btn-small", type: "button", text: "↓", title: "下へ (ティア段階が変わります)", onclick: () => { if (idx < books.length - 1) { const t = books[idx + 1]; books[idx + 1] = books[idx]; books[idx] = t; render(); } } }),
        h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: () => { books.splice(idx, 1); render(); } })
      ];

      // upgrade-from セレクト: 自分より前の id + (なし/最下位)。現在値が候補外でもロスレス表示のため補う。
      const upgradeCandidates = idsBefore(idx);
      const curUpgrade = b["upgrade-from"] == null ? "" : String(b["upgrade-from"]);
      if (curUpgrade && !upgradeCandidates.includes(curUpgrade)) upgradeCandidates.push(curUpgrade);
      const upgradeSel = h("select", { class: "field-input" });
      upgradeSel.appendChild(h("option", { value: "", text: "(なし/最下位ティア)" }));
      for (const id of upgradeCandidates) {
        const o = h("option", { value: id, text: id });
        if (id === curUpgrade) o.selected = true;
        upgradeSel.appendChild(o);
      }
      upgradeSel.value = curUpgrade;
      upgradeSel.addEventListener("change", (ev) => {
        b["upgrade-from"] = ev.target.value === "" ? null : ev.target.value;
      });

      const nameColorCtl = window.colorPickerInput
        ? window.colorPickerInput(b["name-color"], "hex", (v) => { if (v === "") delete b["name-color"]; else b["name-color"] = v; })
        : window.textInput(b["name-color"], (v) => { b["name-color"] = v; });

      const body = [
        gridRow([
          fieldRow("display-name", window.textInput(b["display-name"], (v) => { b["display-name"] = v; })),
          fieldRow("name-color", nameColorCtl)
        ]),
        gridRow([
          fieldRow("max-slots", window.numberInput(b["max-slots"], (v) => { b["max-slots"] = v == null ? 0 : v; }, { int: true })),
          fieldRow("max-glyphs", window.numberInput(b["max-glyphs"] == null ? 9 : b["max-glyphs"], (v) => { b["max-glyphs"] = v == null ? 9 : v; }, { int: true })),
          fieldRow("max-glyph-tier", window.numberInput(b["max-glyph-tier"], (v) => { b["max-glyph-tier"] = v == null ? 0 : v; }, { int: true })),
          fieldRow("custom-model-data", (() => {
            const wrap = h("span", { class: "cmd-field-row" });
            wrap.appendChild(window.numberInput(b["custom-model-data"], (v) => { b["custom-model-data"] = v == null ? 0 : v; }, { int: true }));
            if (typeof window.cmdAutoAssignButton === "function") {
              wrap.appendChild(window.cmdAutoAssignButton({
                // 魔導書のベースmaterialは SpellBook.getBaseMaterial() で Material.BOOK 固定
                // (yml上にmaterialフィールドは存在しない)。
                getMaterial: () => "BOOK",
                getId: () => curId,
                source: "spellbooks",
                onAssigned: (cmd) => { b["custom-model-data"] = cmd; render(); }
              }));
            }
            return wrap;
          })())
        ]),
        fieldRow("upgrade-from", upgradeSel),
        fieldRowCustom(
          "発動CT(秒)",
          "この魔導書を介して詠唱するときの発動CT。0または未設定=従来の計算CT(SpellCaster側のform別/連射CT)にフォールバックします。バインド品からの詠唱でこの魔導書を触媒として使う場合も含みます。",
          window.numberInput(b.cooldown, (v) => { b.cooldown = v == null ? 0 : v; })
        )
      ];
      // クラフトレシピ（カタログと同UI）。魔導書 id を itemsMap 候補に載せる。
      const booksAsMap = {};
      for (const book of books) {
        if (book && typeof book.id === "string" && book.id) booksAsMap[book.id] = book;
      }
      if (typeof window.renderCatalogRecipeSection === "function") {
        body.push(window.renderCatalogRecipeSection(b, () => render(), booksAsMap, curId || `tier_${idx}`));
      }
      return window.collapsibleCard(head, body, {
        expanded: expandedBooks.has(curId),
        onToggle: (open) => { if (open) expandedBooks.add(curId); else expandedBooks.delete(curId); }
      });
    }

    // ============================================================
    // 触媒タブ (catalysts: map)
    // ============================================================
    function renderCatalystsSection() {
      const box = h("div", { class: "card-list" });
      const catalysts = working.catalysts && typeof working.catalysts === "object" ? working.catalysts : null;
      const ids = catalysts ? Object.keys(catalysts) : [];
      if (ids.length === 0) {
        box.appendChild(emptyGuide("触媒アイテムがまだありません。", "「+ 触媒追加」で、Material・バインド設定・ステを持つ触媒を登録します。"));
      }
      for (const id of ids) box.appendChild(renderCatalystCard(id));
      box.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ 触媒追加",
          onclick: () => {
            if (!working.catalysts || typeof working.catalysts !== "object") working.catalysts = {};
            let name = "new_catalyst", i = 1;
            while (Object.prototype.hasOwnProperty.call(working.catalysts, name)) name = `new_catalyst_${i++}`;
            working.catalysts[name] = { material: "BLAZE_ROD" };
            expandedCatalysts.add(name); // 新規追加は開いた状態で編集させる
            render();
          }
        })
      ]));
      return box;
    }

    function renderCatalystCard(id) {
      const entry = working.catalysts[id];
      // 既存YAMLで lore が非配列でも forEach 前提の描画が落ちないよう [] に正規化する
      // (catalog.js の renderEntry と同じ方針。空のまま保存すればgetDataで省かれる)。
      if (!Array.isArray(entry.lore)) entry.lore = [];

      const idInput = h("input", { class: "field-input entry-id", value: id, spellcheck: "false" });
      idInput.addEventListener("change", (ev) => {
        const nv = ev.target.value.trim();
        if (!nv || nv === id) { ev.target.value = id; return; }
        if (Object.prototype.hasOwnProperty.call(working.catalysts, nv)) { alert("同じidが存在します"); ev.target.value = id; return; }
        renameKey(working.catalysts, id, nv);
        if (expandedCatalysts.has(id)) { expandedCatalysts.delete(id); expandedCatalysts.add(nv); }
        render();
      });

      const head = [
        h("span", { class: "entry-key-label", text: "id" }), idInput,
        h("div", { class: "spacer" }),
        h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => { delete working.catalysts[id]; render(); }
        })
      ];

      const matHint = window.materialHintEl(entry.material);
      const matInput = window.materialInput(entry.material, "material-list", (v) => {
        entry.material = v;
        matHint.update(v);
        if (typeof window.isLeatherArmorMaterial === "function" && !window.isLeatherArmorMaterial(v)) {
          delete entry.color;
        }
        render();
      });

      const nameColorCtl = window.colorPickerInput
        ? window.colorPickerInput(entry["name-color"], "hex", (v) => { if (v === "") delete entry["name-color"]; else entry["name-color"] = v; })
        : window.textInput(entry["name-color"], (v) => { entry["name-color"] = v; });

      const isLeather = typeof window.isLeatherArmorMaterial === "function"
        ? window.isLeatherArmorMaterial(entry.material)
        : String(entry.material || "").toUpperCase().startsWith("LEATHER_");
      const leatherColorCtl = isLeather
        ? (window.colorPickerInput
          ? window.colorPickerInput(entry.color, "hex", (v) => { if (v === "") delete entry.color; else entry.color = v; })
          : window.textInput(entry.color, (v) => { entry.color = v; }))
        : null;

      const preview = window.buildTooltipPreview();
      function refreshPreview() {
        const nm = entry["display-name"];
        preview.update({
          name: (nm != null && nm !== "") ? nm : id,
          nameMode: "minimessage",
          loreLines: Array.isArray(entry.lore) ? entry.lore : [],
          loreMode: "minimessage"
        });
      }

      const bindSel = bindTypeSelect(entry["bind-type"], (v) => {
        if (v === "") delete entry["bind-type"]; else entry["bind-type"] = v;
      });

      const inputChildren = [
        fieldRow("material", h("span", { class: "input-with-hint" }, [matInput, matHint])),
        fieldRow("display-name", window.richTextInput(entry["display-name"], "minimessage", (v) => { setOrDelete(entry, "display-name", v); refreshPreview(); })),
        fieldRow("name-color", nameColorCtl),
        fieldRow("custom-model-data", (() => {
          const wrap = h("span", { class: "cmd-field-row" });
          wrap.appendChild(window.numberInput(entry["custom-model-data"], (v) => { setOrDelete(entry, "custom-model-data", v); }, { int: true }));
          if (typeof window.cmdAutoAssignButton === "function") {
            wrap.appendChild(window.cmdAutoAssignButton({
              getMaterial: () => entry.material,
              getId: () => id,
              source: "spellbooks:catalysts",
              onAssigned: (cmd) => { entry["custom-model-data"] = cmd; render(); }
            }));
          }
          return wrap;
        })())
      ];
      if (leatherColorCtl) {
        inputChildren.push(fieldRow("color", leatherColorCtl));
      }
      inputChildren.push(
        fieldRowCustom("バインド種別", "TrinityForge BindType名。未指定ならbind-type刻印は行いません。", bindSel),
        fieldRowCustom(
          "最大バインドグリフtier",
          "この触媒にバインド可能なスペルの最大グリフtier。超過するスペルのバインドは拒否されます(未設定時は3=事実上無制限)。",
          window.numberInput(entry["max-bind-tier"], (v) => {
            if (v == null) { delete entry["max-bind-tier"]; return; }
            entry["max-bind-tier"] = Math.trunc(v);
          }, { int: true })
        ),
        fieldRowCustom(
          "発動CT(秒)",
          "触媒由来の追加CTゲート。0または未設定=追加ゲートなし(form別/連射CTとは別キー空間で判定)。",
          window.numberInput(entry.cooldown, (v) => { if (v == null) delete entry.cooldown; else entry.cooldown = v; })
        ),
        // 旧 mana-cost-reduction UI は stats.fixed.mana-cost-reduction-* へ移行済み（下のステブロック）。
        h("div", { class: "sub-title", text: "フレーバー説明文 (lore)" }),
        renderLoreRows(entry.lore, "minimessage", refreshPreview, () => render())
      );
      const inputs = h("div", { class: "entry-inputs" }, inputChildren);

      const previewCol = h("div", { class: "entry-preview" }, [
        h("div", { class: "preview-label", text: "表示プレビュー" }),
        preview.element,
        h("div", { class: "preview-note", text: "品質ティア行・自動ステ行はここには表示されません。lore はその前に差し込まれるフレーバー説明文です。" })
      ]);

      refreshPreview();

      const statsSection = renderCatalystStatBlocks(entry);

      return window.collapsibleCard(head, [h("div", { class: "entry-2col" }, [inputs, previewCol]), statsSection], {
        expanded: expandedCatalysts.has(id),
        onToggle: (open) => { if (open) expandedCatalysts.add(id); else expandedCatalysts.delete(id); }
      });
    }

    // ---- 触媒の stats.fixed / stats.per-quality / stats.random (item-stat 相当) ----
    // forms.js の buildItemStatsForm と同じ操作感を、window 公開済みの statSelect/statValueControl/
    // statHintEl を使って自前実装する(forms.js 本体の fixedRow 等は非公開・編集不可のため)。
    function pickNewStat(target) {
      const candidates = statListLocal().filter((c) => !Object.prototype.hasOwnProperty.call(target, c));
      if (candidates.length) return candidates[0];
      let name = "new-stat", i = 1;
      while (Object.prototype.hasOwnProperty.call(target, name)) name = `new-stat-${i++}`;
      return name;
    }

    // グループが空になったら stats.<group> キー自体を削除し、stats が全グループ空になったら
    // entry.stats も削除する (ロスレス: 未使用グループ/キーを出力しない)。
    function pruneStatGroupIfEmpty(entry, group) {
      const stats = entry.stats;
      if (!stats) return;
      if (stats[group] && typeof stats[group] === "object" && Object.keys(stats[group]).length === 0) {
        delete stats[group];
      }
      if (Object.keys(stats).length === 0) delete entry.stats;
    }

    function ensureStatsGroup(entry, group) {
      if (!entry.stats || typeof entry.stats !== "object") entry.stats = {};
      if (!entry.stats[group] || typeof entry.stats[group] !== "object") entry.stats[group] = {};
      return entry.stats[group];
    }

    function statFixedRow(entry, group, stat, rerender) {
      const target = entry.stats[group];
      const row = h("div", { class: "stat-row" });
      const s = window.statSelect(stat, (nv) => {
        if (!nv || nv === stat) return false;
        if (Object.prototype.hasOwnProperty.call(target, nv)) { alert("同じステータスが既にあります"); return false; }
        renameKey(target, stat, nv); rerender(); return true;
      });
      row.appendChild(s);
      // per-quality は整数ステでも小数入力を許可 (閾値方式: 累積を切り捨てて実効値化)。
      row.appendChild(window.statValueControl(stat, target[stat], (v) => { target[stat] = v; }, { allowIntDecimal: group === "per-quality" }));
      if (window.statUnitSlot) row.appendChild(window.statUnitSlot(stat));
      row.appendChild(h("button", {
        class: "btn-small danger", type: "button", text: "×",
        onclick: () => { delete target[stat]; pruneStatGroupIfEmpty(entry, group); rerender(); }
      }));
      return row;
    }

    function statRandomRow(entry, stat, rerender) {
      const target = entry.stats.random;
      const range = target[stat] && typeof target[stat] === "object" ? target[stat] : {};
      if (range.min == null) range.min = 0;
      if (range.max == null) range.max = 0;
      target[stat] = range;

      const row = h("div", { class: "stat-row" });
      const s = window.statSelect(stat, (nv) => {
        if (!nv || nv === stat) return false;
        if (Object.prototype.hasOwnProperty.call(target, nv)) { alert("同じステータスが既にあります"); return false; }
        renameKey(target, stat, nv); rerender(); return true;
      });
      row.appendChild(s);
      row.appendChild(h("span", { class: "range-label", text: "min" }));
      row.appendChild(window.statValueControl(stat, range.min, (v) => { range.min = v; }));
      row.appendChild(h("span", { class: "range-label", text: "max" }));
      row.appendChild(window.statValueControl(stat, range.max, (v) => { range.max = v; }));
      if (window.statUnitSlot) row.appendChild(window.statUnitSlot(stat));
      row.appendChild(h("button", {
        class: "btn-small danger", type: "button", text: "×",
        onclick: () => { delete target[stat]; pruneStatGroupIfEmpty(entry, "random"); rerender(); }
      }));
      return row;
    }

    function renderCatalystStatBlocks(entry) {
      const rerender = () => render();
      const fixedGroup = (entry.stats && entry.stats.fixed) || {};
      const pqGroup = (entry.stats && entry.stats["per-quality"]) || {};
      const randGroup = (entry.stats && entry.stats.random) || {};

      const fixedRows = h("div", { class: "stat-rows" });
      for (const stat of Object.keys(fixedGroup)) fixedRows.appendChild(statFixedRow(entry, "fixed", stat, rerender));
      fixedRows.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 固定ステ追加",
        onclick: () => { const g = ensureStatsGroup(entry, "fixed"); g[pickNewStat(g)] = 0; render(); }
      }));

      const pqRows = h("div", { class: "stat-rows" });
      for (const stat of Object.keys(pqGroup)) pqRows.appendChild(statFixedRow(entry, "per-quality", stat, rerender));
      pqRows.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 品質別上昇値追加",
        onclick: () => { const g = ensureStatsGroup(entry, "per-quality"); g[pickNewStat(g)] = 0; render(); }
      }));

      const randRows = h("div", { class: "stat-rows" });
      for (const stat of Object.keys(randGroup)) randRows.appendChild(statRandomRow(entry, stat, rerender));
      randRows.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ ランダムロールステ追加",
        onclick: () => { const g = ensureStatsGroup(entry, "random"); g[pickNewStat(g)] = { min: 0, max: 0 }; render(); }
      }));

      return h("div", {}, [
        h("div", { class: "empty-hint", text: "マナ消費軽減(実数)/(%) もここで設定します(ステ一覧から選択)。整数ステのため品質別上昇値に小数を入れると累積し、整数化した分だけ実効値が上がります(切り捨て=閾値方式)。" }),
        h("div", { class: "sub-section" }, [
          window.subTitleEl("固定ステ (stats.fixed)",
            "常に適用される固定ステータス。int系ステ(thread-slots/マナ消費軽減等)は本体側で小数点以下を切り捨ててintとして扱われます。"),
          Object.keys(fixedGroup).length ? null : h("div", { class: "empty-hint", text: "まだ固定ステがありません。「+ 固定ステ追加」で追加します。" }),
          fixedRows
        ]),
        h("div", { class: "sub-section" }, [
          window.subTitleEl("品質別上昇値 (stats.per-quality)",
            "品質が1上がるごとにこのステへ加算される増分(fixedの上に加算)。int系ステは小数点以下を切り捨ててintとして扱われます。"),
          Object.keys(pqGroup).length ? null : h("div", { class: "empty-hint", text: "まだ品質別上昇値がありません。「+ 品質別上昇値追加」で追加します。" }),
          pqRows
        ]),
        h("div", { class: "sub-section" }, [
          window.subTitleEl("ランダムロールステ (stats.random)",
            "レンジ{min,max}をrollSeedに応じて個体ごとに解決するランダムステ。int系ステは小数点以下を切り捨ててintとして扱われます。"),
          Object.keys(randGroup).length ? null : h("div", { class: "empty-hint", text: "まだランダムロールステがありません。「+ ランダムロールステ追加」で追加します。" }),
          randRows
        ])
      ]);
    }

    // 保存前に、画面表示用に補完した空の任意項目(lore/mana-cost-reduction/stats.*)を省く。
    // working 自体は書き換えず浅いコピーを返す (forms.js の buildCatalogForm/buildItemStatsForm と
    // 同じ「表示用working + 保存時プルーン」方式)。
    function pruneCatalyst(entry) {
      const copy = { ...entry };
      if (Array.isArray(copy.lore) && copy.lore.length === 0) delete copy.lore;
      if (copy["mana-cost-reduction"] && typeof copy["mana-cost-reduction"] === "object") {
        if (Object.keys(copy["mana-cost-reduction"]).length === 0) delete copy["mana-cost-reduction"];
      }
      if (copy.stats && typeof copy.stats === "object") {
        const stats = { ...copy.stats };
        for (const g of ["fixed", "per-quality", "random"]) {
          if (stats[g] && typeof stats[g] === "object" && Object.keys(stats[g]).length === 0) delete stats[g];
        }
        if (Object.keys(stats).length === 0) delete copy.stats;
        else copy.stats = stats;
      }
      return copy;
    }

    render();
    return {
      element: root,
      /** Hub tabs: "books" | "catalysts" (aliases spellbook / catalyst). */
      setActivePart: (part) => {
        const key = String(part || "").toLowerCase();
        activeTab = (key === "catalysts" || key === "catalyst") ? "catalysts" : "books";
        render();
      },
      getData: () => {
        const out = { ...working };
        if (out.catalysts && typeof out.catalysts === "object") {
          const prunedMap = {};
          for (const [cid, centry] of Object.entries(out.catalysts)) prunedMap[cid] = pruneCatalyst(centry);
          if (Object.keys(prunedMap).length === 0) delete out.catalysts;
          else out.catalysts = prunedMap;
        }
        return out;
      }
    };
  };
})();
