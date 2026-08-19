"use strict";

// Phase2 専用フォーム: gathering / *-gimmick / villager-trades / role-buffs
// 往復ロスレス: working を直接編集。

(function () {
  const h = window.h;

  function card(head, body) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, Array.isArray(head) ? head : [head]),
      h("div", { class: "entry-body" }, body)
    ]);
  }
  function field(key, control, opts) {
    const o = opts || {};
    return h("div", { class: "form-field" }, [
      window.fieldLabelEl(o.key || key, {
        label: o.label || key,
        desc: o.desc || "",
        hideKey: o.hideKey !== false
      }),
      control
    ]);
  }
  function grid(fields) { return h("div", { class: "field-grid" }, fields); }
  function sub(text) { return h("div", { class: "sub-title", text }); }
  function banner(text) {
    return h("div", { class: "form-banner", text });
  }
  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }
  function ensureObj(parent, key) {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) parent[key] = {};
    return parent[key];
  }
  function ensureArr(parent, key) {
    if (!Array.isArray(parent[key])) parent[key] = [];
    return parent[key];
  }
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }
  function numField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.numberInput(obj[key], (v) => {
      if (v == null || v === "") {
        if (o.clearable) delete obj[key];
        else obj[key] = o.fallback != null ? o.fallback : 0;
        return;
      }
      obj[key] = o.int ? Math.trunc(v) : v;
    }, o.int ? { int: true } : undefined), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  function textField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.textInput(obj[key] == null ? "" : String(obj[key]), (v) => {
      if (!v && o.clearable) delete obj[key];
      else obj[key] = v;
    }, o.placeholder || ""), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }
  function boolField(obj, key, opts) {
    const o = opts || {};
    return field(key, window.checkboxInput(!!obj[key], (v) => { obj[key] = v; }), {
      label: o.label || key,
      desc: o.desc || "",
      key
    });
  }

  // E-1 (2026-07-25): fishing.ocean-biomes は以前 textInput の自由入力のみだったため、タイポで
  // 無効なバイオームキーを設定できてしまっていた。tf-phase3-forms.js の form-cooldowns (T1) や
  // tf-rewards-forms.js のパーティクル/統計セレクトと同じ「listSelect(allowCustom:true)」の作法
  // (候補から選べる + 一覧に無い値も自由入力できる) に合わせ、候補は vocab-1.21.11.js の
  // VANILLA_BIOMES/BIOME_LABELS_JA から供給する。
  // ⚠️ fishing-gimmick.yml のコメントが明記する通り、バニラのバイオーム追加(将来のアップデート)に
  // 追随できるよう完全なセレクトにはしない — 一覧に無い値(将来の新バイオーム)も入力できる状態を
  // 維持するのが目的なので、選択肢はあくまで「よく使うものの補助」に留める。
  const BIOME_CUSTOM_VALUE = "__custom_biome__";
  function biomeSelect(value, onChange) {
    const cur = value == null ? "" : String(value);
    const catalog = Array.isArray(window.VANILLA_BIOMES) ? window.VANILLA_BIOMES : [];
    const opts = catalog.map((id) => ({
      value: id,
      primary: (window.BIOME_LABELS_JA && window.BIOME_LABELS_JA[id]) || id,
      secondary: id
    }));
    if (cur && !opts.some((o) => o.value === cur)) {
      opts.unshift({ value: cur, primary: cur, secondary: "(一覧外)" });
    }
    opts.push({ value: BIOME_CUSTOM_VALUE, primary: "＋ 自由入力…" });
    return window.listSelect({
      value: cur,
      placeholder: "バイオームを選択…",
      options: opts,
      allowCustom: true,
      customValue: BIOME_CUSTOM_VALUE,
      customPlaceholder: "namespace無し・小文字 (例: ocean)",
      onChange: (v) => { onChange(v); }
    });
  }

  /**
   * 自由キー文字列マップ編集 (suspicious-block-respawn.loot-tables 等)。
   * キーは任意識別子 (renameKey で書き換え可)、値は自由文字列。
   */
  function stringMapEditor(map, opts) {
    const o = opts || {};
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      const keys = Object.keys(map);
      if (!keys.length) {
        box.appendChild(h("div", { class: "empty-hint", text: o.empty || "まだありません。" }));
      }
      for (const key of keys) {
        const row = h("div", { class: "stat-row" });
        row.appendChild(h("input", {
          class: "field-input entry-key-input",
          value: key,
          spellcheck: "false",
          onchange: (e) => {
            const nv = e.target.value.trim();
            if (!nv || nv === key) { e.target.value = key; return; }
            if (Object.prototype.hasOwnProperty.call(map, nv)) { alert("同じキーがあります"); e.target.value = key; return; }
            renameKey(map, key, nv);
            render();
          }
        }));
        row.appendChild(window.textInput(map[key] == null ? "" : String(map[key]), (v) => { map[key] = v; }, o.valuePlaceholder || ""));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { delete map[key]; render(); }
        }));
        box.appendChild(row);
      }
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: o.addLabel || "+ 追加",
        onclick: () => {
          let n = "new-block", i = 1;
          while (Object.prototype.hasOwnProperty.call(map, n)) n = `new-block-${i++}`;
          map[n] = "";
          render();
        }
      }));
    }
    render();
    return box;
  }

  /** Material / 文字列リスト編集 */
  function stringListEditor(arr, opts) {
    const o = opts || {};
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      if (!arr.length) {
        box.appendChild(h("div", { class: "empty-hint", text: o.empty || "まだありません。" }));
      }
      arr.forEach((val, idx) => {
        const row = h("div", { class: "stat-row" });
        if (o.material) {
          row.appendChild(window.materialInput(val || "", "material-list", (v) => { arr[idx] = v; }, { allowCustom: false }));
        } else if (o.biome) {
          row.appendChild(biomeSelect(val, (v) => { arr[idx] = v; }));
        } else {
          row.appendChild(window.textInput(val || "", (v) => { arr[idx] = v; }, o.placeholder || ""));
        }
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { arr.splice(idx, 1); render(); }
        }));
        box.appendChild(row);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: o.addLabel || "+ 追加",
        onclick: () => { arr.push(o.defaultValue != null ? o.defaultValue : ""); render(); }
      }));
    }
    render();
    return box;
  }

  // ============================================================
  // ドロップテーブル 共通UI部品 (mining/woodcutting/digging-gimmick の drop-tables、
  // fishing-gimmick の groups.treasure/groups.junk)。
  // 設計書 2026-07-23-stat-gate-overhaul.md §4 準拠。
  // データ整形の純関数は window.DROP_TABLE_LOGIC としてテストからも参照する。
  // ============================================================
  function clampMinInt(v, min) {
    const n = Math.trunc(Number(v));
    if (!Number.isFinite(n) || n < min) return min;
    return n;
  }
  function normalizeDropEntry(entry) {
    const e = entry && typeof entry === "object" ? entry : {};
    return {
      item: e.item != null ? String(e.item) : "",
      weight: clampMinInt(e.weight != null ? e.weight : 1, 1),
      amount: clampMinInt(e.amount != null ? e.amount : 1, 1)
    };
  }
  function normalizeDropCategory(cat, opts) {
    const o = opts || {};
    const c = cat && typeof cat === "object" ? cat : {};
    const out = {
      "display-name": c["display-name"] != null ? String(c["display-name"]) : "",
      entries: Array.isArray(c.entries) ? c.entries.map(normalizeDropEntry) : []
    };
    if (o.triggerChance) {
      out["trigger-chance-percent"] = typeof c["trigger-chance-percent"] === "number" ? c["trigger-chance-percent"] : 0;
    }
    return out;
  }
  // 指定パス直下に categories オブジェクトを持つコンテナへ working を辿る (無ければ生成)。
  function resolveDropTableContainer(working, path) {
    let node = working && typeof working === "object" ? working : {};
    for (const key of path || []) node = ensureObj(node, key);
    return node;
  }

  window.DROP_TABLE_LOGIC = {
    clampMinInt,
    normalizeDropEntry,
    normalizeDropCategory,
    resolveDropTableContainer
  };

  /**
   * ドロップテーブル編集UI。working[...path].categories を直接編集する。
   * @param {object} working フォームのルートデータ (working直接編集・往復ロスレス方針)
   * @param {string[]} path categories を持つコンテナまでのキー列 (例: ["drop-tables"] / ["groups","treasure"])
   * @param {object} [opts]
   * @param {boolean} [opts.triggerChance] true でカテゴリごとの発動率%欄を表示する (釣りは false)
   */
  function dropTableEditor(working, path, opts) {
    const o = opts || {};
    const triggerChance = !!o.triggerChance;
    const container = resolveDropTableContainer(working, path);
    const categories = ensureObj(container, "categories");

    const root = h("div", { class: "drop-table-editor card-list" });
    const list = h("div", { class: "card-list-body" });
    root.appendChild(list);

    function entryRows(entries) {
      const box = h("div", { class: "stat-rows" });
      function renderRows() {
        box.innerHTML = "";
        if (!entries.length) box.appendChild(h("div", { class: "empty-hint", text: "ドロップがありません。" }));
        entries.forEach((entryRaw, idx) => {
          const entry = entryRaw && typeof entryRaw === "object" ? entryRaw : (entries[idx] = normalizeDropEntry(entryRaw));
          const row = h("div", { class: "stat-row drop-entry-row" });
          row.appendChild(window.materialInput(entry.item || "", "material-list", (v) => { entry.item = v; }, { allowCustom: true }));
          row.appendChild(h("span", { class: "mini-label", text: "重み" }));
          row.appendChild(window.numberInput(entry.weight == null ? 1 : entry.weight, (v) => { entry.weight = clampMinInt(v, 1); }, { int: true }));
          row.appendChild(h("span", { class: "mini-label", text: "個数" }));
          row.appendChild(window.numberInput(entry.amount == null ? 1 : entry.amount, (v) => { entry.amount = clampMinInt(v, 1); }, { int: true }));
          row.appendChild(h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { entries.splice(idx, 1); renderRows(); }
          }));
          box.appendChild(row);
        });
        box.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ ドロップ追加",
          onclick: () => { entries.push({ item: "", weight: 10, amount: 1 }); renderRows(); }
        }));
      }
      renderRows();
      return box;
    }

    function render() {
      list.innerHTML = "";
      const ids = Object.keys(categories);
      if (!ids.length) {
        list.appendChild(emptyGuide("カテゴリがありません", "「+ カテゴリ追加」で作成します。"));
      }
      for (const id of ids) {
        let cat = categories[id];
        if (!cat || typeof cat !== "object") cat = categories[id] = {};
        if (!Array.isArray(cat.entries)) cat.entries = [];
        const head = [
          h("input", {
            class: "field-input entry-key-input", value: id, spellcheck: "false",
            onchange: (e) => {
              const nv = e.target.value.trim();
              if (!nv || nv === id) { e.target.value = id; return; }
              if (Object.prototype.hasOwnProperty.call(categories, nv)) { alert("同じIDがあります"); e.target.value = id; return; }
              renameKey(categories, id, nv);
              render();
            }
          }),
          h("span", { class: "spacer" }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete categories[id]; render(); }
          })
        ];
        const fields = [textField(cat, "display-name", { label: "表示名" })];
        if (triggerChance) fields.push(numField(cat, "trigger-chance-percent", { label: "発動率%", desc: "対象ブロック破壊ごとの発動率", fallback: 0 }));
        // 釣りゴミグループ用: junk-to-scrap パークのスクラップ差し替えから除外するカテゴリ (例: 海洋の糸)
        if (o.scrapExempt) fields.push(field("scrap-exempt", window.checkboxInput(!!cat["scrap-exempt"], (v) => {
          if (v) cat["scrap-exempt"] = true; else delete cat["scrap-exempt"];
        }), { label: "スクラップ差替除外", key: "scrap-exempt", desc: "ONでjunk-to-scrapパークの対象外(糸などの特殊ドロップ用)" }));
        const body = [grid(fields), sub("ドロップ内容 (entries)"), entryRows(cat.entries)];
        list.appendChild(window.collapsibleCard(head, body, { expanded: ids.length <= 2 }));
      }
      list.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ カテゴリ追加",
          onclick: () => {
            let n = "tier1", i = 1;
            while (Object.prototype.hasOwnProperty.call(categories, n)) n = `tier${++i}`;
            const fresh = { "display-name": n, entries: [] };
            if (triggerChance) fresh["trigger-chance-percent"] = 5.0;
            categories[n] = fresh;
            render();
          }
        })
      ]));
    }
    render();
    return root;
  }

  // ============================================================
  // tier テーブル編集UI (2026-07-26 新設)。
  // 対象は section.tiers: { "<tier番号>": { <column.key>: number, ... } } という形の
  // オブジェクト(採取系「専用効果」がスキルツリーノードの value を集計した tier で参照する)。
  // Java 側 (MiningGimmickConfig / WoodcuttingGimmickConfig / FarmingGimmickConfig の
  // parse*Tiers 系) は「tiers が未定義、または該当tier未満の行しか無い場合は同セクション直下の
  // フラット値へフォールバックする」という規則で読む。そのため tiers が空になったら
  // 「tiers: {}」を残さずキーごと削除する(空オブジェクトを残すとフォールバック規則が
  // 曖昧に見えるため)。
  // dropTableEditor(195行〜、この直前)と同じ作法に合わせる: working(=section)を直接編集し、
  // 行の追加/削除ボタン・空状態はemptyGuide・キーの改名はentry-key-input型の
  // onchange(commit-on-blur)で行い、都度 render() し直す。
  // ============================================================
  function isPositiveIntegerTierKey(v) {
    if (v == null || v === "") return false;
    const s = String(v).trim();
    if (!/^[0-9]+$/.test(s)) return false;
    const n = Number(s);
    return Number.isInteger(n) && n > 0;
  }
  function normalizeTierRow(row, columns) {
    const r = row && typeof row === "object" ? row : {};
    const out = {};
    for (const col of columns) {
      const raw = r[col.key];
      const n = typeof raw === "number" && Number.isFinite(raw) ? raw : 0;
      out[col.key] = col.int ? Math.trunc(n) : n;
    }
    return out;
  }
  function pruneEmptyTiers(section) {
    if (section.tiers && typeof section.tiers === "object" && !Array.isArray(section.tiers)
        && Object.keys(section.tiers).length === 0) {
      delete section.tiers;
    }
  }
  window.TIER_TABLE_LOGIC = { isPositiveIntegerTierKey, normalizeTierRow, pruneEmptyTiers };

  /**
   * tier別パラメータ表の編集UI。section.tiers を直接編集する(working直接編集・往復ロスレス方針)。
   * @param {object} section tiers を持つセクション (例: haste = working["haste-active-mining"])
   * @param {Array<{key:string,label?:string,int?:boolean}>} columns tier行の列定義
   * @param {object} [opts]
   * @param {string} [opts.emptyTitle] 空状態の見出し
   * @param {string} [opts.emptyHint] 空状態の補足文
   */
  function tierTableEditor(section, columns, opts) {
    const o = opts || {};
    const root = h("div", { class: "tier-table-editor card-list" });
    const list = h("div", { class: "card-list-body" });
    root.appendChild(list);

    function tiersMap() {
      return (section.tiers && typeof section.tiers === "object" && !Array.isArray(section.tiers))
        ? section.tiers : null;
    }

    function render() {
      list.innerHTML = "";
      const map = tiersMap();
      const ids = map ? Object.keys(map).sort((a, b) => Number(a) - Number(b)) : [];
      if (!ids.length) {
        list.appendChild(emptyGuide(
          o.emptyTitle || "tier未設定(グローバル既定値のみ使用)",
          o.emptyHint || "「+ tier追加」で段階ごとの値を設定できます。tiersが1件も無い間は、"
            + "上のグローバル既定値がそのまま全員に使われます。"
        ));
      }
      for (const id of ids) {
        if (!map[id] || typeof map[id] !== "object") map[id] = normalizeTierRow(map[id], columns);
        const row = map[id];
        const line = h("div", { class: "stat-row tier-row" });
        line.appendChild(h("span", { class: "mini-label", text: "tier" }));
        line.appendChild(h("input", {
          class: "field-input entry-key-input tier-key-input",
          type: "number",
          step: "1",
          value: id,
          spellcheck: "false",
          onchange: (e) => {
            const nv = e.target.value == null ? "" : String(e.target.value).trim();
            if (nv === id) return;
            if (!isPositiveIntegerTierKey(nv)) {
              alert("tier番号は1以上の整数で入力してください。");
              e.target.value = id;
              return;
            }
            const canonical = String(Math.trunc(Number(nv)));
            if (canonical !== id && Object.prototype.hasOwnProperty.call(map, canonical)) {
              alert(`tier ${canonical} は既に存在します(重複不可)。`);
              e.target.value = id;
              return;
            }
            renameKey(map, id, canonical);
            render();
          }
        }));
        for (const col of columns) {
          line.appendChild(h("span", { class: "mini-label", text: col.label || col.key }));
          line.appendChild(window.numberInput(row[col.key] == null ? 0 : row[col.key], (v) => {
            if (v == null || v === "") { row[col.key] = 0; return; }
            row[col.key] = col.int ? Math.trunc(v) : v;
          }, col.int ? { int: true } : undefined));
        }
        line.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => {
            delete map[id];
            pruneEmptyTiers(section);
            render();
          }
        }));
        list.appendChild(line);
      }
      list.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small", type: "button", text: "+ tier追加",
          onclick: () => {
            const target = ensureObj(section, "tiers");
            const existingTiers = Object.keys(target).map((k) => Number(k)).filter((n) => Number.isFinite(n));
            const next = String((existingTiers.length ? Math.max(...existingTiers) : 0) + 1);
            target[next] = normalizeTierRow({}, columns);
            render();
          }
        })
      ]));
    }
    render();
    return root;
  }
  // 2026-07-26 tier-expand: potion-merge(tf-crafting-features.js)/xp-bottle-store(このファイル内
  // fishing-gimmick.yml フォーム)からも同じUIを再利用するため window に公開する(独自UI禁止の方針)。
  window.tierTableEditor = tierTableEditor;

  // ============================================================
  // gathering.yml は廃止 (2026-07-23)。
  // 採掘欄(fortune-*)は mining-gimmick タブの fortune: セクションへ、
  // 釣り欄(skill-id/luck-per-level/bonus-per-level)は fishing-gimmick タブの
  // fishing: セクションへ、それぞれ統合済み。stats/gathering.yml 自体の削除・値移行は別ウェーブ。
  // ============================================================

  // ============================================================
  // mining-gimmick.yml
  // ============================================================
  window.buildMiningGimmickForm = function buildMiningGimmickForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const vein = ensureObj(working, "vein-mining");
    const haste = ensureObj(working, "haste-active-mining");
    const fortune = ensureObj(working, "fortune");
    ensureArr(vein, "ore-blocks");
    ensureArr(fortune, "fortune-blocks");

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(banner("採掘ツリー専用効果の数値。vein-mining / haste / 幸運連携 / 追加ドロップ(drop-tables)。"));

    // 2026-07-28: 「怪しいブロックの再生成」は考古学(ブラシ=DIGGING)の設定なので掘削ギミックタブへ移設。
    // 保存先ファイルは stats/mining-gimmick.yml のままで、掘削タブがコンパニオンとして編集する。

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "一括採掘 (vein-mining)" })],
      [
        numField(vein, "max-extra-blocks", {
          label: "追加破壊上限(グローバル既定値)", int: true,
          desc: "トリガー1個は含まない。下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"
        }),
        sub("tier別設定 (tiers) — 該当tier行があればグローバル既定値より優先される"),
        tierTableEditor(vein, [{ key: "max-extra-blocks", label: "追加破壊上限", int: true }]),
        sub("対象鉱石"),
        stringListEditor(vein["ore-blocks"], { material: true, addLabel: "+ 鉱石追加" })
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "採掘加速 (haste-active-mining)" })],
      [
        grid([
          numField(haste, "amplifier", {
            label: "Haste段階(グローバル既定値)", int: true,
            desc: "0=I, 1=II。下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"
          }),
          numField(haste, "duration-ticks", {
            label: "持続tick(グローバル既定値)", int: true,
            desc: "20=1秒。下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"
          }),
          numField(haste, "cooldown-ticks", {
            label: "CT(tick)", int: true,
            desc: "tierに関わらず常にこの値(CT短縮は<id>-cooldown-reduction stat専用。tier表には含めない)。"
          })
        ]),
        sub("tier別設定 (tiers) — 該当tier行があればグローバル既定値より優先される (CTは含まない)"),
        tierTableEditor(haste, [
          { key: "amplifier", label: "Haste段階", int: true },
          { key: "duration-ticks", label: "持続(tick)", int: true }
        ])
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "採掘幸運 (fortune)" })],
      [
        grid([
          numField(fortune, "fortune-per-level", { label: "Lvあたり幸運期待値", desc: "採掘スキルLv × この値(参照スキルはプラグイン側で MINING 固定)" })
        ]),
        sub("幸運対象ブロック (fortune-blocks)"),
        stringListEditor(fortune["fortune-blocks"], { material: true, addLabel: "+ ブロック追加", empty: "対象ブロックがありません。" })
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "追加ドロップ (drop-tables)" })],
      [dropTableEditor(working, ["drop-tables"], { triggerChance: true })]
    ));
    return { element: root, getData: () => working };
  };

  // ============================================================
  // woodcutting-gimmick.yml
  // ============================================================
  window.buildWoodcuttingGimmickForm = function buildWoodcuttingGimmickForm(data, opts) {
    const working = data && typeof data === "object" ? data : {};
    // T6 (2026-07-26): crafting-features.yml の wood-repair(圧縮木材修繕)サブツリーをこのタブへ
    // コンパニオン表示する。保存は getExtraSaves 経由で crafting-features へ(丸ごと読み込み・丸ごと
    // 書き戻し、他のサブツリーは normalizeCraftingFeaturesWorking がそのまま温存する)。
    const craftingFeaturesData = opts && opts.craftingFeaturesData && typeof opts.craftingFeaturesData === "object"
      ? opts.craftingFeaturesData : undefined;
    const hasCraftingFeatures = craftingFeaturesData !== undefined;
    const craftingFeaturesWorking = hasCraftingFeatures ? craftingFeaturesData : {};
    if (hasCraftingFeatures && typeof window.normalizeCraftingFeaturesWorking === "function") {
      window.normalizeCraftingFeaturesWorking(craftingFeaturesWorking);
    } else if (hasCraftingFeatures && (craftingFeaturesWorking["wood-repair"] == null || typeof craftingFeaturesWorking["wood-repair"] !== "object")) {
      craftingFeaturesWorking["wood-repair"] = {};
    }
    const catalogCandidates = Array.isArray(opts && opts.catalogCandidates) ? opts.catalogCandidates : [];
    const fell = ensureObj(working, "tree-fell");
    // タスク3 (2026-07-26): small-max-extra-logs / large-max-extra-logs は 2026-07-25 の
    // gather-rework-active-framework §6 Q1 で tree-fell.max-extra-logs 1本 + tiers へ統合済みで、
    // Java (WoodcuttingGimmickConfig) はこの2キーをもう一切読まない死んだキー。editorがこれを
    // 描画・書き込みし続けていたため、yml に紛れ込んだ場合そのまま残ってしまう。
    // lib/cmd-removal.js の「参照専用ファイルの孤児エントリを保存前に取り除く」前例に倣い、
    // フォーム構築時(working を直接編集するタイミング)にこのゴミキーを掃除する。
    delete fell["small-max-extra-logs"];
    delete fell["large-max-extra-logs"];

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(banner("伐採ツリー専用効果。一括伐採上限と追加ドロップ(drop-tables)。"));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "一括伐採 (tree-fell)" })],
      [
        grid([
          numField(fell, "max-extra-logs", {
            label: "追加原木上限(グローバル既定値)", int: true,
            desc: "下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"
          }),
          numField(fell, "cooldown-ticks", { label: "CT(tick)", int: true })
        ]),
        sub("tier別設定 (tiers) — 該当tier行があればグローバル既定値より優先される"),
        tierTableEditor(fell, [{ key: "max-extra-logs", label: "追加原木上限", int: true }])
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "追加ドロップ (drop-tables)" })],
      [dropTableEditor(working, ["drop-tables"], { triggerChance: true })]
    ));

    if (hasCraftingFeatures) {
      root.appendChild(banner("以下の「木材修繕」は progression/crafting-features.yml のサブツリーです"
        + "(このファイルとは別ファイル)。保存時は両方まとめて保存されます。"));
      if (typeof window.buildCraftingFeaturesWoodRepairSection === "function") {
        root.appendChild(window.buildCraftingFeaturesWoodRepairSection(craftingFeaturesWorking["wood-repair"], catalogCandidates));
      } else {
        root.appendChild(h("div", { class: "empty-hint", text: "木材修繕エディタ(tf-crafting-features.js)が読み込まれていません。" }));
      }
    }

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => hasCraftingFeatures ? [{ id: "crafting-features", data: craftingFeaturesWorking }] : []
    };
  };

  // ============================================================
  // digging-gimmick.yml (2026-07-23 新設)
  // ============================================================
  // 怪しい砂/砂利の再生成に使うバニラ考古学ルートテーブル(BrushableBlock が受け付けるもの)。
  // Java 側は org.bukkit.loot.LootTables の定数名として解決し、未知の名前は警告して既定値へ落とす。
  const ARCHAEOLOGY_LOOT_TABLES = [
    { value: "DESERT_PYRAMID_ARCHAEOLOGY", primary: "砂漠のピラミッド" },
    { value: "DESERT_WELL_ARCHAEOLOGY", primary: "砂漠の井戸" },
    { value: "TRAIL_RUINS_ARCHAEOLOGY_COMMON", primary: "遺跡歩道(通常)" },
    { value: "TRAIL_RUINS_ARCHAEOLOGY_RARE", primary: "遺跡歩道(レア)" },
    { value: "OCEAN_RUIN_COLD_ARCHAEOLOGY", primary: "海底遺跡(寒冷)" },
    { value: "OCEAN_RUIN_WARM_ARCHAEOLOGY", primary: "海底遺跡(温暖)" }
  ];

  // 2026-07-28: 以前は自由キー×自由文字列のマップエディタで、行の見出しも値も生ID
  // (suspicious-sand / DESERT_PYRAMID_ARCHAEOLOGY)のままだった。キーは Java 側が
  // suspicious-sand / suspicious-gravel の2つしか読まないので、固定2行 + 選択式にする。
  function suspiciousRespawnRows(lootTables) {
    const ROWS = [
      { key: "suspicious-sand", label: "怪しい砂", fallback: "DESERT_PYRAMID_ARCHAEOLOGY" },
      { key: "suspicious-gravel", label: "怪しい砂利", fallback: "TRAIL_RUINS_ARCHAEOLOGY_COMMON" }
    ];
    const box = h("div", { class: "stat-rows" });
    for (const row of ROWS) {
      const options = ARCHAEOLOGY_LOOT_TABLES.map((o) => ({ value: o.value, primary: o.primary, secondary: o.value }));
      const current = lootTables[row.key] == null ? "" : String(lootTables[row.key]);
      if (current && !options.some((o) => o.value === current)) {
        options.unshift({ value: current, primary: current, secondary: "" });
      }
      box.appendChild(h("div", { class: "form-field" }, [
        h("span", { class: "form-label", text: row.label }),
        window.listSelect({
          value: current,
          options,
          placeholder: `未設定 (既定: ${row.fallback})`,
          allowCustom: true,
          customPlaceholder: "LootTables 定数名を直接入力",
          onChange: (v) => {
            if (!v) delete lootTables[row.key];
            else lootTables[row.key] = String(v).trim().toUpperCase();
          }
        })
      ]));
    }
    return box;
  }

  window.buildDiggingGimmickForm = function buildDiggingGimmickForm(data, opts) {
    const working = data && typeof data === "object" ? data : {};
    const durabilityExp = ensureObj(working, "durability-exp");
    // 2026-07-28: 「怪しいブロックの再生成」は考古学(ブラシ=DIGGING)の設定なので採掘タブから
    // ここへ移設した。保存先ファイルは stats/mining-gimmick.yml のままなので、コンパニオンとして
    // 読み込み getExtraSaves で一緒に保存する(farming-gimmick の食事ギミックと同じ方式)。
    const miningGimmickData = opts && opts.miningGimmickData && typeof opts.miningGimmickData === "object"
      ? opts.miningGimmickData : undefined;
    const hasMiningGimmick = miningGimmickData !== undefined;

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(banner("掘削(シャベル適正ブロック破壊)ギミック。追加ドロップ(drop-tables) + 耐久消費EXP換算。"));
    if (hasMiningGimmick) {
      const suspiciousRespawn = ensureObj(miningGimmickData, "suspicious-block-respawn");
      const lootTables = ensureObj(suspiciousRespawn, "loot-tables");
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "怪しいブロックの再生成" })],
        [
          h("div", { class: "form-hint", text:
            "怪しい砂/怪しい砂利を壊したあと再生成させたとき、ブラシで掘り出せる中身をどの"
            + "バニラ考古学ルートテーブルから抽選するか。再生成そのものの発生率はステータス"
            + "「怪しいブロック再生成率」で決まります。" }),
          suspiciousRespawnRows(lootTables),
          h("div", { class: "field-hint", text: "保存先: stats/mining-gimmick.yml の suspicious-block-respawn" })
        ]
      ));
    }
    // 2026-08-19 (W-146): 採掘ギミックには「採掘加速」カードがあるのに、掘削側には
    // 1枚も無かった(= haste-active-digging の amplifier / duration / CT / tier表を editor から
    // 一切いじれない)。数値は mining 側と意図的にミラーしない独立の値なので、
    // 保存先も stats/digging-gimmick.yml 本体のまま(コンパニオン扱いにはしない)。
    const hasteDigging = ensureObj(working, "haste-active-digging");
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "掘削加速 (haste-active-digging)" })],
      [
        h("div", { class: "form-hint", text:
          "シャベル専用のアクティブスキル。CTのバケツは採掘加速と共有するので、ツールを持ち替えて"
          + "連発しても合計アップタイムは増えません(採掘側のCTが残っていればこちらも撃てません)。" }),
        grid([
          numField(hasteDigging, "amplifier", {
            label: "Haste段階(グローバル既定値)", int: true,
            desc: "0=I, 1=II。下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"
          }),
          numField(hasteDigging, "duration-ticks", {
            label: "持続tick(グローバル既定値)", int: true,
            desc: "20=1秒。下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"
          }),
          numField(hasteDigging, "cooldown-ticks", {
            label: "CT(tick)", int: true,
            desc: "tierに関わらず常にこの値(CT短縮は haste-active-digging-cooldown-reduction stat専用。tier表には含めない)。"
          })
        ]),
        sub("tier別設定 (tiers) — 該当tier行があればグローバル既定値より優先される (CTは含まない)"),
        tierTableEditor(hasteDigging, [
          { key: "amplifier", label: "Haste段階", int: true },
          { key: "duration-ticks", label: "持続(tick)", int: true }
        ])
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "追加ドロップ (drop-tables)" })],
      [dropTableEditor(working, ["drop-tables"], { triggerChance: true })]
    ));
    // 2026-07-28(数値のギミックyml集約): 旧「%そのものをtierとして流用する」単一tiers表は廃止。
    // feature別(vanilla-exp/job-exp)に独立したtiers表 + cap-percent へ置き換えた。tierは
    // digging-durability-vanilla-exp/digging-durability-job-exp(いずれもSCALE)のノードvalueで決まる。
    const vanillaExp = ensureObj(durabilityExp, "vanilla-exp");
    const jobExp = ensureObj(durabilityExp, "job-exp");
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "耐久消費EXP換算 (durability-exp)" })],
      [
        grid([
          numField(durabilityExp, "durability-per-percent", {
            label: "1%ボーナスに必要な累積耐久消費量(グローバル既定値)", int: true,
            desc: "例: 100なら、シャベルの耐久を100消費するごとに+1%(上限cap-percentまでクランプ)。"
              + "下の各tier行が durability-per-percent を個別に持つ場合はそちらが優先される。"
          })
        ]),
        sub("バニラEXP上限% (vanilla-exp.tiers) — digging-durability-vanilla-exp のtierで引く"),
        tierTableEditor(vanillaExp, [
          { key: "cap-percent", label: "上限%", int: true },
          { key: "durability-per-percent", label: "1%あたり必要耐久消費量(任意、省略でグローバル既定値)", int: true }
        ], {
          emptyTitle: "tier未設定(上限0%=無効)",
          emptyHint: "「+ tier追加」でtier1から順に上限%を設定してください(未設定のtierは無効扱い)。"
        }),
        sub("職業EXP上限% (job-exp.tiers) — digging-durability-job-exp のtierで引く"),
        tierTableEditor(jobExp, [
          { key: "cap-percent", label: "上限%", int: true },
          { key: "durability-per-percent", label: "1%あたり必要耐久消費量(任意、省略でグローバル既定値)", int: true }
        ], {
          emptyTitle: "tier未設定(上限0%=無効)",
          emptyHint: "「+ tier追加」でtier1から順に上限%を設定してください(未設定のtierは無効扱い)。"
        })
      ]
    ));
    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => hasMiningGimmick ? [{ id: "mining-gimmick", data: miningGimmickData }] : []
    };
  };

  // ============================================================
  // farming-gimmick.yml
  // ============================================================
  window.buildFarmingGimmickForm = function buildFarmingGimmickForm(data, opts) {
    const working = data && typeof data === "object" ? data : {};
    const area = ensureObj(working, "area-harvest");
    const animal = ensureObj(working, "animal-damage-4x");
    const bee = ensureObj(working, "bee-no-aggro");
    // T6 (2026-07-26): 「食事ギミック」単独タブは廃止し、このタブの中で編集する(統合表示)。
    // ファイル自体(stats/food-gimmick.yml)は分離したまま。保存は getExtraSaves 経由。
    const foodGimmickData = opts && opts.foodGimmickData && typeof opts.foodGimmickData === "object"
      ? opts.foodGimmickData : undefined;
    const hasFoodGimmick = foodGimmickData !== undefined;
    const foodSubform = hasFoodGimmick
      ? window.buildFoodGimmickForm(foodGimmickData, { catalogCandidates: opts && opts.catalogCandidates })
      : null;

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(banner("農業／畜産ギミック。範囲収穫・動物ダメ倍率・ハチ鎮静。"));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "範囲収穫 (area-harvest)" })],
      [
        grid([
          numField(area, "radius", {
            label: "範囲収穫半径(グローバル既定値)", int: true,
            desc: "1=周囲3×3。下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"
          })
        ]),
        sub("tier別設定 (tiers) — 該当tier行があればグローバル既定値より優先される"),
        tierTableEditor(area, [{ key: "radius", label: "半径", int: true }])
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "その他パラメータ" })],
      [grid([
        numField(animal, "multiplier", { label: "動物ダメ倍率" }),
        numField(bee, "calm-radius", { label: "ハチ鎮静半径" })
      ])]
    ));
    if (foodSubform) {
      // 2026-08-09: 「別ファイルだが一緒に保存する」旨の長い説明文は邪魔だという指摘を受けて撤去し、
      // 区切りの見出し1行だけ残す(見出しごと消すと農業の設定の続きに見えてしまう)。
      root.appendChild(sub("食事ギミック (stats/food-gimmick.yml)"));
      root.appendChild(foodSubform.element);
    }

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => foodSubform ? [{ id: "food-gimmick", data: foodSubform.getData() }] : []
    };
  };

  // ============================================================
  // food-gimmick.yml
  // ============================================================
  window.buildFoodGimmickForm = function buildFoodGimmickForm(data, opts) {
    const catalogCandidates = (opts && opts.catalogCandidates) || [];
    const working = data && typeof data === "object" ? data : {};
    ensureArr(working, "junk-food-materials");
    const immun = ensureObj(working, "junkfood-immunity");
    const inv = ensureObj(working, "junkfood-inversion");
    const sat = ensureObj(working, "satiety-buff");
    const customFoods = ensureObj(working, "custom-foods");
    ensureArr(immun, "cancelled-debuff-effects");
    // 2026-08-09新設: custom-foodsに満腹度設定の無いカスタムID付き食料(圧縮食料の81倍/729倍等)を
    // 食べられなくするギミック。判定基準は「custom-foodsへの登録の有無」そのもの。
    const ban = ensureObj(working, "unregistered-custom-food-ban");
    ensureArr(ban, "excluded-materials");

    const root = h("div", { class: "dedicated-form" });
    // 2026-08-09: 見出し代わりの説明バナーは、農業ギミックタブへ統合表示したときに
    // 「別ファイル」バナーと2行続いて邪魔になるため撤去した(区切りは呼び出し側の見出しが担う)。

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "ゴミ食 Material" })],
      [stringListEditor(working["junk-food-materials"], { material: true, addLabel: "+ 食材追加" })]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "免疫で打ち消すデバフ" })],
      [
        h("div", { class: "mini-label", text: "免疫時に自動で打ち消すデバフ効果。" }),
        potionEffectListEditor(immun["cancelled-debuff-effects"], { addLabel: "+ 効果追加", empty: "打ち消すデバフがありません。" })
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "数値" })],
      [grid([
        numField(inv, "junk-saturation-bonus", { label: "ゴミ食 満腹加算" }),
        numField(inv, "non-junk-saturation-penalty", { label: "通常食 満腹減算" }),
        numField(sat, "saturation-bonus", { label: "満腹バフ 加算" })
      ])]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "カスタム食料" })],
      [
        h("div", { class: "mini-label", text: "カタログアイテムに満腹度/隠し満腹度を割り当てる (custom-foods)。" }),
        customFoodsEditor(customFoods, catalogCandidates)
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "未登録カスタム食料の禁止" })],
      [
        h("div", {
          class: "mini-label",
          text: "カタログ/materials定義のカスタムIDを持つが上の「カスタム食料」に未登録の食料は、素材として扱い食べられなくなる。除外Materialに載っている土台のアイテムは常に食べられる。"
        }),
        boolField(ban, "enabled", { label: "有効" }),
        h("div", { class: "mini-label", text: "除外Material(このMaterialを土台にした品は未登録でも食べられる)" }),
        stringListEditor(ban["excluded-materials"], { material: true, addLabel: "+ 除外Material追加" }),
        textField(ban, "message", { label: "禁止時メッセージ" })
      ]
    ));
    return { element: root, getData: () => working };
  };

  /** custom-foods: { <itemId>: { "food-level": int(0-20), saturation: number(>=0) } } の編集UI */
  function clampFoodLevel(v) {
    const n = Math.trunc(Number(v));
    if (!Number.isFinite(n)) return 0;
    return Math.min(20, Math.max(0, n));
  }
  function clampSaturation(v) {
    const n = Number(v);
    if (!Number.isFinite(n) || n < 0) return 0;
    return n;
  }
  function customFoodsEditor(map, catalogCandidates) {
    const list = h("div", { class: "stat-rows" });
    function render() {
      list.innerHTML = "";
      const ids = Object.keys(map);
      if (!ids.length) {
        list.appendChild(h("div", { class: "empty-hint", text: "カスタム食料がありません。" }));
      }
      for (const id of ids) {
        const entry = map[id] && typeof map[id] === "object" ? map[id] : (map[id] = {});
        const row = h("div", { class: "stat-row drop-entry-row" });
        row.appendChild(window.catalogItemSuggest(id, catalogCandidates, (c) => {
          const nv = c && c.id ? c.id : "";
          if (!nv || nv === id) return;
          if (Object.prototype.hasOwnProperty.call(map, nv)) { alert("同じIDがあります"); return; }
          renameKey(map, id, nv);
          render();
        }, { placeholder: "カタログID / 表示名で検索" }));
        row.appendChild(h("span", { class: "mini-label", text: "満腹度" }));
        row.appendChild(window.numberInput(entry["food-level"] == null ? 0 : entry["food-level"], (v) => {
          entry["food-level"] = clampFoodLevel(v);
        }, { int: true }));
        row.appendChild(h("span", { class: "mini-label", text: "隠し満腹度" }));
        row.appendChild(window.numberInput(entry.saturation == null ? 0 : entry.saturation, (v) => {
          entry.saturation = clampSaturation(v);
        }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { delete map[id]; render(); }
        }));
        list.appendChild(row);
      }
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 食料追加",
        onclick: () => {
          let n = "custom_food", i = 1;
          while (Object.prototype.hasOwnProperty.call(map, n)) n = `custom_food_${i++}`;
          map[n] = { "food-level": 0, saturation: 0 };
          render();
        }
      }));
    }
    render();
    return list;
  }

  // ============================================================
  // fishing-gimmick.yml
  // ============================================================
  /**
   * fish-sell.prices: { <Material または custom:カタログID> : <price:number> } の編集UI。
   * T2(2026-07-25)でキーがMaterial限定でなくなり、ドロップテーブルの entries[].item と同じ
   * トークン語彙(Material名 または custom:id)を受け付けるようになった。そのため入力部品も
   * entries[].item と同一の window.materialInput({ allowCustom: true }) に統一する。
   */
  function fishSellPricesEditor(prices) {
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      const keys = Object.keys(prices);
      if (!keys.length) box.appendChild(h("div", { class: "empty-hint", text: "売却対象がありません(未登録トークン=0円=売却対象外)。" }));
      for (const key of keys) {
        const row = h("div", { class: "stat-row" });
        row.appendChild(window.materialInput(key, "material-list", (v) => {
          const nv = String(v || "").trim();
          if (!nv || nv === key) return;
          if (Object.prototype.hasOwnProperty.call(prices, nv)) { alert("同じキーが存在します"); return; }
          renameKey(prices, key, nv);
          render();
        }, { allowCustom: true }));
        row.appendChild(h("span", { class: "mini-label", text: "売却額" }));
        row.appendChild(window.numberInput(prices[key] == null ? 0 : prices[key], (v) => {
          prices[key] = v == null || v === "" ? 0 : Math.max(0, v);
        }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { delete prices[key]; render(); }
        }));
        box.appendChild(row);
      }
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 追加",
        onclick: () => {
          let n = "COD", i = 1, key = n;
          while (Object.prototype.hasOwnProperty.call(prices, key)) key = `${n}_${i++}`;
          prices[key] = 0;
          render();
        }
      }));
    }
    render();
    return box;
  }

  /**
   * fishing.groups.fish (T1 2026-07-25新設): 「通常の魚」枠の重み付きドロップテーブル。
   * treasure/junk と完全に同一構造だが、未設定(空)＝バニラ釣果をそのまま維持、という後方互換
   * 既定を持つ点だけが異なる。そのため treasure/junk と違い、フォームを開いただけでは
   * fishing.groups.fish を書き込まず(dropTableEditor を呼ばない)、ユーザーが明示的に
   * 「設定する」を押した時だけ groups.fish = { categories: {} } を生成して以降は
   * dropTableEditor に委譲する。
   */
  function fishGroupEditor(fishing) {
    const wrap = h("div", {});
    function hasFish() {
      return !!(fishing.groups && typeof fishing.groups === "object"
        && Object.prototype.hasOwnProperty.call(fishing.groups, "fish"));
    }
    function renderEnabled() {
      wrap.innerHTML = "";
      wrap.appendChild(h("div", {
        class: "mini-label",
        text: "設定すると「通常の魚」の抽選結果がバニラ釣果からTF管理の重み付きドロップテーブルへ置き換わります。"
      }));
      wrap.appendChild(dropTableEditor(fishing, ["groups", "fish"], { triggerChance: false }));
      wrap.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small danger", type: "button", text: "設定を解除する(バニラ釣果に戻す)",
          onclick: () => {
            if (fishing.groups && typeof fishing.groups === "object") delete fishing.groups.fish;
            renderDisabled();
          }
        })
      ]));
    }
    function renderDisabled() {
      wrap.innerHTML = "";
      wrap.appendChild(emptyGuide(
        "未設定(バニラ釣果を維持)",
        "「設定する」を押すまでは fishing.groups.fish は保存されず、通常の魚の釣果は従来どおりバニラのままです。"
      ));
      wrap.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 魚グループを設定する",
        onclick: () => {
          ensureObj(fishing, "groups").fish = { categories: {} };
          renderEnabled();
        }
      }));
    }
    if (hasFish()) renderEnabled(); else renderDisabled();
    return wrap;
  }

  /**
   * fishing.unlock-groups.<groupId> (機能解放追加用テーブル、任意キー) の遅延生成エディタ。
   * groups.<id> と完全に同一の Category スキーマだが、カテゴリごとに機能解放が必要な点だけが違う。
   * fishGroupEditor(groups.fish 専用)と同じ「設定するまで書き込まない」方式を treasure/junk/fish の
   * 3グループ全てに適用する ── ensureObj で丸ごと実体化すると、フォームを開いただけで
   * fishing.unlock-groups: {} が yml へ書き戻ってしまう(config-editor.md の lazy-touch 注意事項と同根)。
   * 解放は運用者がスキルツリー側の dedicated-effects に drop:fishing:<groupId>:<catId> を置くことで行う
   * (lib/gate-vocabulary.js の extractDropCategories が groups と同じ形式でこの categoryId を語彙へ出す)。
   * @param {object} fishing fishing直下のworking
   * @param {string} groupId "treasure" | "junk" | "fish"
   * @param {string} groupLabelJa カード内の説明用ラベル(例:"宝")
   * @param {object} [dropOpts] dropTableEditor へそのまま渡すopts
   */
  function unlockGroupEditor(fishing, groupId, groupLabelJa, dropOpts) {
    const wrap = h("div", {});
    function hasGroup() {
      return !!(fishing["unlock-groups"] && typeof fishing["unlock-groups"] === "object"
        && Object.prototype.hasOwnProperty.call(fishing["unlock-groups"], groupId));
    }
    function renderEnabled() {
      wrap.innerHTML = "";
      wrap.appendChild(h("div", {
        class: "mini-label",
        text: "解放済みのカテゴリだけがデフォルトテーブルと同じ抽選プールへ合流します。"
          + "解放するとその分だけ既存アイテムの排出率は下がります。"
      }));
      wrap.appendChild(h("div", {
        class: "mini-label",
        text: `解放はカテゴリ単位です。スキルツリー側のノードに drop:fishing:${groupId}:<カテゴリID> を書いてください。`
      }));
      wrap.appendChild(dropTableEditor(fishing, ["unlock-groups", groupId], dropOpts));
      wrap.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small danger", type: "button", text: `設定を解除する(${groupLabelJa}の追加テーブルなし)`,
          onclick: () => {
            if (fishing["unlock-groups"] && typeof fishing["unlock-groups"] === "object") {
              delete fishing["unlock-groups"][groupId];
              if (Object.keys(fishing["unlock-groups"]).length === 0) delete fishing["unlock-groups"];
            }
            renderDisabled();
          }
        })
      ]));
    }
    function renderDisabled() {
      wrap.innerHTML = "";
      wrap.appendChild(emptyGuide(
        "未設定(追加テーブルなし)",
        `「設定する」を押すまでは fishing.unlock-groups.${groupId} は保存されません。`
      ));
      wrap.appendChild(h("button", {
        class: "btn-small", type: "button", text: `+ ${groupLabelJa}の追加テーブルを設定する`,
        onclick: () => {
          ensureObj(fishing, "unlock-groups")[groupId] = { categories: {} };
          renderEnabled();
        }
      }));
    }
    if (hasGroup()) renderEnabled(); else renderDisabled();
    return wrap;
  }

  window.buildFishingGimmickForm = function buildFishingGimmickForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const fishing = ensureObj(working, "fishing");
    const groupRatio = ensureObj(fishing, "group-ratio");
    ensureArr(working, "junk-materials");
    ensureArr(working, "treasure-materials");
    ensureArr(fishing, "ocean-biomes");
    const fishSell = ensureObj(working, "fish-sell");
    ensureObj(fishSell, "prices");

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(banner("釣りギミック。宝/ゴミの重み付きドロップテーブル + 釣り運連携比率。"));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "釣りスキル連携 (fishing)" })],
      [grid([
        numField(fishing, "luck-per-level", { label: "Lvあたり釣運", desc: "装備品質mode用(参照スキルはプラグイン側で FISHING 固定)" }),
        numField(fishing, "bonus-per-level", { label: "Lvあたり追加loot", desc: "非装備釣果の追加期待値" })
      ])]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "宝/ゴミ/魚 比率 (group-ratio)" })],
      [grid([
        numField(groupRatio, "treasure-percent", { label: "宝グループ率%", desc: "釣り運statで乗算シフト(+10%=宝率×1.1)。増減分はゴミ/魚が比例で吸収" }),
        numField(groupRatio, "junk-percent", { label: "ゴミグループ率%", desc: "残り(100−宝−ゴミ)は通常の魚(バニラ釣果のまま)" })
      ])]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "海釣り判定バイオーム (fishing.ocean-biomes)" })],
      [
        h("div", { class: "mini-label", text: "釣り位置のバイオームがこの一覧に含まれる場合のみ ocean_fishing_bonus stat が加算されます。"
          + "namespace無しの小文字表記 (例: ocean, deep_ocean)。候補から選べますが、バニラのバイオーム追加に"
          + "追随できるよう一覧に無い値も自由入力できます(完全なセレクトにはしていません)。" }),
        stringListEditor(fishing["ocean-biomes"], {
          biome: true,
          addLabel: "+ バイオーム追加",
          empty: "対象バイオームがありません(海釣り判定なし)。"
        })
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "釣果自動売却 (fish-sell)" })],
      [
        h("div", { class: "mini-label", text: "Material -> 基準売却額(Vault通貨)。未登録のMaterialは0円=売却対象外。"
          + "fish_sell_price_bonus stat はこの基準額への倍率として乗算されます。" }),
        fishSellPricesEditor(fishSell.prices),
        grid([
          numField(fishSell, "max-sells-per-minute", {
            label: "1分あたり自動売却上限回数", int: true,
            desc: "exploit対策(AFK釣り機/自動釣りマクロでの無限換金防止)"
          })
        ])
      ]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "宝グループ (groups.treasure)" })],
      [dropTableEditor(fishing, ["groups", "treasure"], { triggerChance: false })]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "ゴミグループ (groups.junk)" })],
      [dropTableEditor(fishing, ["groups", "junk"], { triggerChance: false, scrapExempt: true })]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "魚グループ (groups.fish、既定は未設定=バニラ釣果維持)" })],
      [fishGroupEditor(fishing)]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "宝グループ 追加解放テーブル (unlock-groups.treasure、既定は未設定)" })],
      [unlockGroupEditor(fishing, "treasure", "宝", { triggerChance: false })]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "ゴミグループ 追加解放テーブル (unlock-groups.junk、既定は未設定)" })],
      [unlockGroupEditor(fishing, "junk", "ゴミ", { triggerChance: false, scrapExempt: true })]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "魚グループ 追加解放テーブル (unlock-groups.fish、既定は未設定)" })],
      [unlockGroupEditor(fishing, "fish", "魚", { triggerChance: false })]
    ));
    root.appendChild(window.collapsibleCard(
      [h("span", { class: "entry-key-label", text: "バニラ釣果フォールバック分類 (junk/treasure-materials)" })],
      [
        h("div", { class: "mini-label", text: "ドロップテーブル対象外(バニラ釣果)の宝/ゴミ大まかな分類。" }),
        sub("ゴミ枠 Material"),
        stringListEditor(working["junk-materials"], { material: true, addLabel: "+ 追加" }),
        sub("宝枠 Material"),
        stringListEditor(working["treasure-materials"], { material: true, addLabel: "+ 追加" })
      ],
      { expanded: false }
    ));
    return { element: root, getData: () => working };
  };

  // ============================================================
  // villager-trades.yml
  // ============================================================
  const VILLAGER_PROFESSIONS = [
    "WEAPONSMITH", "ARMORER", "TOOLSMITH", "CLERIC", "LIBRARIAN",
    "FARMER", "FISHERMAN", "SHEPHERD", "BUTCHER", "CARTOGRAPHER",
    "FLETCHER", "LEATHERWORKER", "MASON", "NITWIT", "NONE"
  ];
  // 内部キー(英語)は維持。表示のみ日本語化する。
  // 2026-07-29: 辞書は labels.js へ一本化した (スキルツリーの trade: ゲートからも同じ名前で
  // 引けるようにするため)。ここでは参照するだけで、自前の辞書は持たない。
  function professionLabel(id) {
    return window.LABELS && typeof window.LABELS.professionLabel === "function"
      ? window.LABELS.professionLabel(id)
      : (id || "");
  }

  window.buildVillagerTradesForm = function buildVillagerTradesForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (!working.professions || typeof working.professions !== "object") working.professions = {};
    const professions = working.professions;
    const root = h("div", { class: "dedicated-form card-list" });
    const list = h("div", { class: "card-list-body" });
    root.appendChild(banner("スキル解放に連動する村人追加取引。解放は skilltree のノード効果「取引解放」"
      + "(trade:<職業>) から参照する (このタブでは編集しない)。"));
    root.appendChild(list);

    // input/output は「Material または custom:カタログID」1本の統一入力に amount を添える形へ統一。
    // 保存形状は既存互換を維持: custom: なら {catalog: xxx}、通常Materialなら {material: NAME}。
    function itemAmountEditor(stack, label) {
      if (!stack || typeof stack !== "object") stack = {};
      const wrap = h("div", { class: "threshold-box" });
      wrap.appendChild(sub(label));
      const current = stack.catalog ? ("custom:" + stack.catalog) : (stack.material || "");
      wrap.appendChild(grid([
        field("item", window.materialInput(current, "material-list", (v) => {
          if (!v) { delete stack.material; delete stack.catalog; return; }
          if (/^custom:/i.test(v)) { stack.catalog = v.slice(v.indexOf(":") + 1); delete stack.material; }
          else { stack.material = v; delete stack.catalog; }
        }, { allowCustom: true }), { label: "アイテム", key: "item", desc: "Material名 または custom:カタログID" }),
        numField(stack, "amount", { label: "個数", int: true, fallback: 1 })
      ]));
      return { el: wrap, stack };
    }

    function render() {
      list.innerHTML = "";
      const ids = Object.keys(professions);
      if (!ids.length) {
        list.appendChild(emptyGuide("職業がありません", "「+ 職業追加」で WEAPONSMITH などを追加します。"));
      }
      for (const id of ids) {
        const prof = professions[id] && typeof professions[id] === "object" ? professions[id] : (professions[id] = {});
        ensureArr(prof, "trades");
        const head = [
          h("strong", { text: professionLabel(id) }),
          h("span", { class: "entry-key-label", text: id }),
          h("span", { class: "spacer" }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete professions[id]; render(); }
          })
        ];
        const tradeBox = h("div", { class: "stat-rows" });
        function renderTrades() {
          tradeBox.innerHTML = "";
          prof.trades.forEach((tr, idx) => {
            if (!tr.input || typeof tr.input !== "object") tr.input = { material: "EMERALD", amount: 1 };
            if (!tr.output || typeof tr.output !== "object") tr.output = { material: "DIRT", amount: 1 };
            const block = h("div", { class: "threshold-box" });
            block.appendChild(h("div", { class: "entry-head-row" }, [
              h("span", { class: "mini-label", text: `取引 #${idx + 1}` }),
              h("span", { class: "spacer" }),
              h("button", {
                class: "btn-small danger", type: "button", text: "×",
                onclick: () => { prof.trades.splice(idx, 1); renderTrades(); }
              })
            ]));
            block.appendChild(itemAmountEditor(tr.input, "支払い (input)").el);
            block.appendChild(itemAmountEditor(tr.output, "受取 (output)").el);
            block.appendChild(grid([
              numField(tr, "max-uses", { label: "最大使用回数", int: true }),
              numField(tr, "villager-xp", { label: "村人XP", int: true })
            ]));
            tradeBox.appendChild(block);
          });
          tradeBox.appendChild(h("button", {
            class: "btn-small", type: "button", text: "+ 取引追加",
            onclick: () => {
              prof.trades.push({
                input: { material: "EMERALD", amount: 1 },
                output: { material: "BOOK", amount: 1 },
                "max-uses": 8,
                "villager-xp": 5
              });
              renderTrades();
            }
          }));
        }
        renderTrades();
        const body = [
          h("div", {
            class: "mini-label",
            text: `解放はスキルツリーのノード効果「取引解放」(trade:${id}) から参照します。`
              + "どのノードからも参照されない取引は出現しません。"
          }),
          grid([
            field("block-vanilla-trades", window.checkboxInput(!!prof["block-vanilla-trades"], (v) => {
              prof["block-vanilla-trades"] = v;
            }), { label: "バニラ取引を遮断", key: "block-vanilla-trades" })
          ]),
          sub("取引一覧"),
          tradeBox
        ];
        list.appendChild(window.collapsibleCard(head, body, { expanded: ids.length <= 2 }));
      }
      list.appendChild(h("div", { class: "form-actions" }, [
        window.listSelect({
          value: "",
          placeholder: "職業を選んで追加…",
          options: VILLAGER_PROFESSIONS.filter((p) => !Object.prototype.hasOwnProperty.call(professions, p))
            .map((p) => ({ value: p, primary: professionLabel(p), secondary: p, title: p })),
          onChange: (v) => {
            if (!v || Object.prototype.hasOwnProperty.call(professions, v)) return;
            professions[v] = {
              "unlock-effect": "",
              "block-vanilla-trades": false,
              trades: []
            };
            render();
          }
        })
      ]));
    }
    render();
    return { element: root, getData: () => working };
  };

  // ============================================================
  // role-buffs.yml
  // ============================================================
  // 1.21現行の PotionEffectType キー。その他は自由入力(allowCustom)で許容する。
  const POTION_EFFECT_OPTIONS = [
    ["SPEED", "移動速度上昇"], ["SLOWNESS", "移動速度低下"], ["HASTE", "採掘速度上昇"],
    ["MINING_FATIGUE", "採掘速度低下"], ["STRENGTH", "攻撃力上昇"], ["INSTANT_HEALTH", "即時回復"],
    ["INSTANT_DAMAGE", "即時ダメージ"], ["JUMP_BOOST", "跳躍力上昇"], ["NAUSEA", "吐き気"],
    ["REGENERATION", "再生能力"], ["RESISTANCE", "耐性"], ["FIRE_RESISTANCE", "火炎耐性"],
    ["WATER_BREATHING", "水中呼吸"], ["INVISIBILITY", "透明化"], ["BLINDNESS", "盲目"],
    ["NIGHT_VISION", "暗視"], ["HUNGER", "空腹"], ["WEAKNESS", "弱化"], ["POISON", "毒"],
    ["WITHER", "ウィザー"], ["HEALTH_BOOST", "体力増強"], ["ABSORPTION", "衝撃吸収"],
    ["SATURATION", "満腹度回復"], ["GLOWING", "発光"], ["LEVITATION", "浮遊"],
    ["LUCK", "幸運"], ["UNLUCK", "不運"], ["SLOW_FALLING", "落下速度低下"],
    ["CONDUIT_POWER", "コンジットパワー"], ["DOLPHINS_GRACE", "イルカの好意"],
    ["BAD_OMEN", "不吉な予感"], ["HERO_OF_THE_VILLAGE", "村の英雄"]
  ];
  // mob-abilities-form.js の POTION_EFFECT_IDS(候補一覧のみ・JA無し)と同じ集合を指す。
  // 新しい辞書を増やさず、ここで作った日本語対応表を window 経由で共有する。
  window.POTION_EFFECT_LABELS_JA = Object.fromEntries(POTION_EFFECT_OPTIONS);
  function potionEffectSelect(value, onChange) {
    const cur = value == null ? "" : String(value);
    const known = POTION_EFFECT_OPTIONS.some(([id]) => id === cur);
    const CUSTOM_VALUE = "__custom_potion__";
    return window.listSelect({
      value: cur,
      placeholder: "選択…",
      allowCustom: true,
      customPlaceholder: "その他のPotionEffectType (英字キー)",
      customValue: CUSTOM_VALUE,
      options: POTION_EFFECT_OPTIONS.map(([id, ja]) => ({ value: id, primary: ja, secondary: id, title: id }))
        .concat(cur && !known ? [{ value: cur, primary: cur, secondary: "", title: cur }] : [])
        .concat([{ value: CUSTOM_VALUE, primary: "その他(自由入力)…", secondary: "" }]),
      onCommit: (v) => { onChange(v); return true; }
    });
  }
  /** PotionEffectType 文字列リスト編集(行ごとに日本語select) */
  function potionEffectListEditor(arr, opts) {
    const o = opts || {};
    const box = h("div", { class: "stat-rows" });
    function render() {
      box.innerHTML = "";
      if (!arr.length) {
        box.appendChild(h("div", { class: "empty-hint", text: o.empty || "まだありません。" }));
      }
      arr.forEach((val, idx) => {
        const row = h("div", { class: "stat-row" });
        row.appendChild(potionEffectSelect(val || "", (v) => { arr[idx] = v; }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { arr.splice(idx, 1); render(); }
        }));
        box.appendChild(row);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: o.addLabel || "+ 追加",
        onclick: () => { arr.push(""); render(); }
      }));
    }
    render();
    return box;
  }

  window.buildRoleBuffsForm = function buildRoleBuffsForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const combat = ensureObj(working, "combat-roles");
    const support = ensureObj(working, "support-roles");
    const change = ensureObj(working, "role-change");

    const root = h("div", { class: "dedicated-form card-list" });
    root.appendChild(banner("ロールバフ。戦闘職は攻撃／守備ステ、補助職はEXP倍率とポーション。"));
    root.appendChild(banner(
      "サーバ側最終クランプ(注記): flat系ステ ±400 / %系ステ ±1.0 / crit-damage・damage-modifier ±2.0 / "
      + "ヘイト倍率 0〜15 / EXP倍率 1〜10。ここで設定した値がこの範囲を超えても保存はできるが、"
      + "実際の効果は起動時にこの範囲へ丸められる(サーバ側が最終クランプする)。"
    ));

    function statMapEditor(map, title) {
      const box = h("div", { class: "mob-defense-block" });
      box.appendChild(sub(title));
      const rows = h("div", { class: "stat-rows" });
      function render() {
        rows.innerHTML = "";
        const keys = Object.keys(map);
        if (!keys.length) rows.appendChild(h("div", { class: "empty-hint", text: "ステなし" }));
        keys.forEach((k) => {
          rows.appendChild(h("div", { class: "stat-row" }, [
            window.statSelect(k, (nv) => {
              if (!nv || nv === k) return false;
              if (Object.prototype.hasOwnProperty.call(map, nv)) { alert("重複"); return false; }
              renameKey(map, k, nv);
              render();
              return true;
            }),
            window.statValueControl
              ? window.statValueControl(k, map[k], (v) => { map[k] = v; })
              : window.numberInput(map[k], (v) => { map[k] = v == null ? 0 : v; }),
            window.statUnitSlot ? window.statUnitSlot(k) : null,
            h("button", {
              class: "btn-small danger", type: "button", text: "×",
              onclick: () => { delete map[k]; render(); }
            })
          ]));
        });
        rows.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ ステ追加",
          onclick: () => {
            let n = "percent-bonus-damage", i = 1;
            while (Object.prototype.hasOwnProperty.call(map, n)) n = `stat-${i++}`;
            map[n] = 0;
            render();
          }
        }));
      }
      render();
      box.appendChild(rows);
      return box;
    }

    function roleCards(host, kind) {
      const list = h("div", { class: "card-list-body" });
      function render() {
        list.innerHTML = "";
        const ids = Object.keys(host);
        if (!ids.length) {
          list.appendChild(emptyGuide("ロールがありません", "下のボタンで追加します。"));
        }
        for (const id of ids) {
          const role = host[id] && typeof host[id] === "object" ? host[id] : (host[id] = {});
          const head = [
            h("input", {
              class: "field-input entry-key-input",
              value: id,
              spellcheck: "false",
              onchange: (e) => {
                const nv = e.target.value.trim();
                if (!nv || nv === id) { e.target.value = id; return; }
                if (Object.prototype.hasOwnProperty.call(host, nv)) {
                  alert("同じIDがあります"); e.target.value = id; return;
                }
                renameKey(host, id, nv);
                render();
              }
            }),
            h("span", { class: "spacer" }),
            h("button", {
              class: "btn-small danger", type: "button", text: "削除",
              onclick: () => { delete host[id]; render(); }
            })
          ];
          // label / icon / description は表示専用項目(ロール選択GUIと /tf role のチャット
          // 表示に使う)。効果には一切影響しない。icon が空/不正なら既定アイコンへ倒れる。
          // 2026-07-29: 入力UIをアイテムカタログへ揃えた。
          //   表示名 = リッチテキスト欄(MiniMessage) / 説明 = 複数行 lore / アイコン = Materialセレクト
          //   (以前は3つとも素のテキスト欄で、アイコンはタイポ(FIDHING_LOD)がそのまま保存できた)
          if (!Array.isArray(role.description)) {
            role.description = role.description == null || String(role.description) === ""
              ? []
              : [String(role.description)];
          }
          const body = [
            field("label", window.richTextInput(role.label == null ? "" : String(role.label),
              "minimessage", (v) => { role.label = v; }), {
              label: "表示名",
              desc: "GUIとチャットに出す職業名。MiniMessage記法で色を付けられます。"
            }),
            field("icon", window.materialInput(role.icon || "", "role-icon-list", (v) => {
              if (v) role.icon = v; else delete role.icon;
            }, { allowCustom: false }), {
              label: "GUIアイコン",
              desc: "ロール選択GUI(/tf status のロールアイコンから開く)で使うMaterial。"
                + "空なら既定アイコン(戦闘職=鉄の剣 / 補助職=本)。"
            }),
            h("div", { class: "form-field" }, [
              window.fieldLabelEl("description", {
                label: "説明Lore (description)",
                desc: "GUIと /tf role のチャット表示に添える説明。行ごとにMiniMessage記法が使えます。",
                hideKey: true
              }),
              window.renderLoreRows(role.description, "minimessage", () => {}, () => render())
            ])
          ];
          if (kind === "combat") {
            ensureObj(role, "attack-buffs");
            ensureObj(role, "defense-buffs");
            body.push(statMapEditor(role["attack-buffs"], "攻撃バフ (attack-buffs) — flat系±400/%系±1.0/crit-damage・damage-modifier±2.0でサーバ側最終クランプ"));
            body.push(statMapEditor(role["defense-buffs"], "守備バフ (defense-buffs) — flat系±400/%系±1.0でサーバ側最終クランプ"));
            body.push(numField(role, "hate-threat-multiplier", {
              label: "ヘイト倍率",
              desc: "タンク等。未設定可。サーバ側で 0〜15 に最終クランプされる。",
              clearable: true
            }));
          } else {
            body.push(grid([
              field("exp-skill", window.skillSelect(role["exp-skill"] || "", (v) => {
                if (v) role["exp-skill"] = v; else delete role["exp-skill"];
              }, { allowEmpty: true }), { label: "EXP対象スキル", key: "exp-skill" }),
              numField(role, "exp-multiplier", { label: "EXP倍率", desc: "サーバ側で 1〜10 に最終クランプされる。" })
            ]));
            const pot = ensureObj(role, "potion-buff");
            body.push(sub("ポーションバフ"));
            body.push(grid([
              field("type", potionEffectSelect(pot.type || "", (v) => { pot.type = v; }), { label: "種類", key: "type" }),
              numField(pot, "duration", { label: "持続tick", int: true }),
              numField(pot, "amplifier", { label: "段階", int: true })
            ]));
          }
          list.appendChild(window.collapsibleCard(head, body, { expanded: false }));
        }
        list.appendChild(h("div", { class: "form-actions" }, [
          h("button", {
            class: "btn", type: "button", text: kind === "combat" ? "+ 戦闘ロール" : "+ 補助ロール",
            onclick: () => {
              let n = kind === "combat" ? "new_combat" : "new_support", i = 1;
              while (Object.prototype.hasOwnProperty.call(host, n)) n = `${n}_${i++}`;
              host[n] = kind === "combat"
                ? { label: n, "attack-buffs": {}, "defense-buffs": {} }
                : { label: n, "exp-skill": "MINING", "exp-multiplier": 1.2, "potion-buff": { type: "SPEED", duration: 999999, amplifier: 0 } };
              render();
            }
          })
        ]));
      }
      render();
      return list;
    }

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "戦闘ロール (combat-roles)" })],
      [roleCards(combat, "combat")]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "補助ロール (support-roles)" })],
      [roleCards(support, "support")]
    ));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "ロール変更 (role-change)" })],
      [
        // 2026-08-05 (W-28): /tf role set 廃止にあわせて allow-command から改名。
        // false の意味も「一切変更不可」から「初回の無料就職以外はアイテム消費でのみ変更可」へ変えた。
        // 旧キーしか無い yml はサーバ側が allow-command を読むので、ここでは新キーだけを出す。
        field("allow-change", window.checkboxInput(
          change["allow-change"] === undefined
            ? (change["allow-command"] === undefined ? true : !!change["allow-command"])
            : !!change["allow-change"],
          (v) => {
            change["allow-change"] = v;
            // 旧キーが残っていると「どちらが効くのか」が読めなくなるので、保存時に畳む。
            delete change["allow-command"];
          }), {
          label: "職業変更を許可するか", key: "allow-change",
          desc: "ON = 下の「変更の待ち時間(分)」による通常のクールダウン制。"
            + "OFF = 初回の無料就職(下の「初回は待ち時間を刻まない」がONのとき)以外は、"
            + "「転職の証」(role_reselect_ticket)を手に持って右クリックしたときだけ変更できる。"
            + "OFF のときは解除(/tf role clear)も塞がる — 枠を空にできると初回の無料就職を"
            + "無限に再利用できてしまうため。"
            + "戦闘中の可否は下の「交戦中ガードの半径」で決まる(既定では戦闘中でも変更できる)。"
        }),
        // 2026-07-31: 待ち時間が無いと、採掘するときだけ鉱夫・釣るときだけ漁師へ切り替えれば
        // 全系統に最大倍率が乗り、補助職の選択そのものが意味を失う。
        field("cooldown-minutes", window.numberInput(change["cooldown-minutes"], (v) => {
          if (v == null || v === "") delete change["cooldown-minutes"];
          else change["cooldown-minutes"] = Math.max(0, Number(v));
        }, { int: false }), {
          label: "変更の待ち時間(分)", key: "cooldown-minutes",
          desc: "0 で待ち時間なし。戦闘職と補助職は別々に数える。"
            + "0 にすると「採掘するときだけ鉱夫・釣るときだけ漁師」で全系統に最大倍率が乗るため、"
            + "補助職の選択そのものが意味を失う。上限は7日。"
            + "解除(/tf role clear)でも刻む(刻まないと解除→即再選択が迂回路になる)。"
        }),
        field("first-choice-free", window.checkboxInput(
          change["first-choice-free"] === undefined ? true : !!change["first-choice-free"], (v) => {
            change["first-choice-free"] = v;
          }), {
          label: "初回は待ち時間を刻まない", key: "first-choice-free",
          desc: "空の枠を初めて埋めるときは刻まない。"
            + "「1つ選んで説明を読み、選び直す」までは無料になるので、始めたばかりの人が詰まらない。"
        }),
        // 2026-07-31: 以前は 16 のハードコード＋Bukkit の Monster 判定だったため、ネザーの
        // ゾンビピグリンや壁越しの洞窟モブで常時変更不可・逆にエンドラ戦では素通りしていた。
        field("nearby-enemy-radius", window.numberInput(change["nearby-enemy-radius"], (v) => {
          if (v == null || v === "") delete change["nearby-enemy-radius"];
          else change["nearby-enemy-radius"] = Math.max(0, Number(v));
        }, { int: false }), {
          label: "交戦中ガードの半径(ブロック)", key: "nearby-enemy-radius",
          desc: "0 でガードを無効(既定)。上限は64。"
            + "0 より大きくすると、その半径内に「自分を狙っている敵」が居る間だけ変更できなくなる。"
            + "GUIを開く操作と /tf role clear はこのガードを通さない(説明を読むだけ・外すだけなので)。"
            + "待ち時間とは別の機構で、乗せ替え悪用の抑止は待ち時間側が担う。"
            + "待ち時間と両方を 0 にすると、被弾直前に tank・与ダメ直前に mage へ無制限に往復できる。"
        })
      ]
    ));

    // 画面を開いただけで description: [] が生えるのを防ぐ(編集で配列化しているため)。
    function pruneEmptyDescriptions(host) {
      for (const role of Object.values(host || {})) {
        if (!role || typeof role !== "object") continue;
        if (Array.isArray(role.description)) {
          // **配列は差し替えず in-place で刈る。** 説明文の行エディタ(renderLoreRows)は
          // 描画時に role.description を掴んでから list[idx] = v で書き込むため、
          // ここで新しい配列へ差し替えると掴んでいた方が孤児になり、以後その行の編集が
          // working に届かない。getData は画面を開いた直後(app.js syncBaseFromEditor)と
          // beforeunload のたびに呼ばれるので、「開いてから最初の1回の編集だけが
          // 未保存警告も出さずに消える」形で出る (2026-08-05 修正。レベルテーブルの
          // pruneEmptyNoSkillExpMobs と同じ壊れ方)。
          const lines = role.description;
          for (let i = lines.length - 1; i >= 0; i--) {
            if (String(lines[i] == null ? "" : lines[i]) === "") lines.splice(i, 1);
          }
          if (!lines.length) delete role.description;
        }
      }
    }

    return {
      element: root,
      getData: () => {
        pruneEmptyDescriptions(combat);
        pruneEmptyDescriptions(support);
        return working;
      }
    };
  };
})();
