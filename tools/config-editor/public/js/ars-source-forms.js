"use strict";

// ArsPaper sourcejars.yml / sourcelinks.yml 専用フォーム。
// アイテムカタログと同型: material(TileState必須) / display-name / CMD / lore + プレビュー + recipe。
// ソースリンクは投入マテリアルをカード内で編集（volcanic / mycelial / alchemical）。

(function () {
  const h = window.h;

  // Paper 1.21.11 TileState 実装ブロック（tilestate-materials-1.21.11.json と同期）。
  // CustomBlock は TileState PDC 必須のため、ここ以外は選べない。
  const FALLBACK_TILESTATE = [
    "FURNACE", "BLAST_FURNACE", "SMOKER", "DECORATED_POT", "BARREL", "BEEHIVE", "BEE_NEST",
    "CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "HOPPER", "DROPPER", "DISPENSER",
    "BREWING_STAND", "LECTERN", "JUKEBOX", "ENCHANTING_TABLE", "CRAFTER",
    "CHISELED_BOOKSHELF", "CAMPFIRE", "SOUL_CAMPFIRE", "SPAWNER", "TRIAL_SPAWNER", "VAULT",
    "SCULK_CATALYST", "BEACON", "SHULKER_BOX"
  ];

  function tileStateMaterials() {
    if (Array.isArray(window.TILESTATE_MATERIALS) && window.TILESTATE_MATERIALS.length) {
      return window.TILESTATE_MATERIALS.slice();
    }
    return FALLBACK_TILESTATE.slice();
  }

  // display-name / lore の記法判定。fork の com.arspaper.util.DisplayText#parse と同じ優先順で、
  // レガシー(&コード)が1つでもあればレガシー、無ければ MiniMessage として扱う。
  // ⚠ ここを minimessage 固定にすると、出荷 sourcelinks.yml / sourcejars.yml の "&c焔喰いの炉" が
  //   プレビューでもリッチ入力でも「&c」の生文字列として見える(2026-08-04 報告の実バグ)。
  //   ゲーム内は DisplayText が両方を解釈するので、editor 側だけが食い違っていた。
  function markupMode(value) {
    return /[&§][0-9a-fk-orA-FK-OR]/.test(String(value == null ? "" : value))
      ? "legacy" : "minimessage";
  }

  /** 複数行(lore)は1行でもレガシーが混ざっていればレガシーとして描く(プレビューは1モードのみ)。 */
  function markupModeOfLines(lines) {
    return markupMode(Array.isArray(lines) ? lines.join(String.fromCharCode(10)) : "");
  }

  function fieldRow(key, control, opts) {
    return h("div", { class: "form-field" }, [window.fieldLabelEl(key, opts), control]);
  }

  // 2026-08-02: sourcelinks.yml transfer: 節 (SourceTransferConfig#parse) 用の汎用パスヘルパー。
  // dimensions(mob-types.yml)と同じ「未編集ならキーを作らない」方針 — Java側の既定値は
  // ConfigurationSection#isSet で判定するため、キー省略と既定値の明示書き込みは意味的に同値だが、
  // 将来 Java 側の既定値が変わったときに editor が古い既定値を全ファイルへ焼き付けてしまう事故を
  // 避けるため、あえて省略のままにする(「開いて保存しただけで yml の意味が変わる」既知の事故クラス)。
  function ensurePath(root, path) {
    let node = root;
    for (const key of path) {
      if (!node[key] || typeof node[key] !== "object" || Array.isArray(node[key])) node[key] = {};
      node = node[key];
    }
    return node;
  }
  function readPath(root, path) {
    let node = root;
    for (const key of path) {
      if (!node || typeof node !== "object") return undefined;
      node = node[key];
    }
    return node;
  }
  // 末端のキーを消したあと、空になった中間オブジェクトを根本方向へ辿って刈る
  // (例: transfer.sourcelink.detection-radius.vitalic を消して空になったら detection-radius も消す)。
  function pruneEmptyPath(root, path) {
    const nodes = [root];
    let node = root;
    for (const key of path) {
      if (!node || typeof node !== "object") { node = undefined; nodes.push(undefined); continue; }
      node = node[key];
      nodes.push(node);
    }
    for (let i = path.length; i >= 1; i -= 1) {
      const parent = nodes[i - 1];
      const key = path[i - 1];
      const cur = nodes[i];
      if (parent && typeof parent === "object" && cur && typeof cur === "object"
          && !Array.isArray(cur) && Object.keys(cur).length === 0) {
        delete parent[key];
      } else {
        break;
      }
    }
  }
  // getData() 側の最終防衛線 (dimensions の pruneEmptyDimensions と同じ二重安全策)。
  // ライブ編集中の delete+pruneEmptyPath だけに頼らず、保存直前にも空の transfer.* 中間object を刈る。
  function pruneEmptyTransfer(host) {
    if (!host || typeof host !== "object") return;
    const transfer = host.transfer;
    if (!transfer || typeof transfer !== "object" || Array.isArray(transfer)) return;
    const link = transfer.sourcelink;
    if (link && typeof link === "object") {
      const detect = link["detection-radius"];
      if (detect && typeof detect === "object" && Object.keys(detect).length === 0) delete link["detection-radius"];
      if (Object.keys(link).length === 0) delete transfer.sourcelink;
    }
    const net = transfer.network;
    if (net && typeof net === "object") {
      const fx = net["path-particles"];
      if (fx && typeof fx === "object" && Object.keys(fx).length === 0) delete net["path-particles"];
      if (Object.keys(net).length === 0) delete transfer.network;
    }
    const core = transfer["infinity-core"];
    if (core && typeof core === "object" && Object.keys(core).length === 0) delete transfer["infinity-core"];
    if (Object.keys(transfer).length === 0) delete host.transfer;
  }

  function setOrDelete(obj, key, value) {
    if (value === null || value === undefined || value === "") delete obj[key];
    else obj[key] = value;
  }

  function pruneEmptyLore(map) {
    const out = {};
    for (const [id, entry] of Object.entries(map || {})) {
      if (!entry || typeof entry !== "object") { out[id] = entry; continue; }
      const copy = { ...entry };
      if (Array.isArray(copy.lore) && copy.lore.length === 0) delete copy.lore;
      out[id] = copy;
    }
    return out;
  }

  function tileStateMaterialInput(value, onChange) {
    const allowed = new Set(tileStateMaterials());
    const cur = value == null ? "" : String(value);
    const opts = tileStateMaterials().map((m) => {
      const ja = (window.LABELS && window.LABELS.materialLabel) ? window.LABELS.materialLabel(m) : "";
      return { value: m, primary: ja && ja !== m ? `${ja} (${m})` : m, secondary: m };
    });
    if (cur && !allowed.has(cur)) {
      opts.unshift({ value: cur, primary: cur + " (TileState外・要変更)", secondary: cur });
    }
    return window.listSelect({
      value: cur,
      placeholder: "TileState ブロックを選択…",
      options: opts,
      onChange: (v) => { if (typeof onChange === "function") onChange(v || ""); }
    });
  }

  function renderLoreRows(loreArray, onEdit, onStructureChange) {
    const box = h("div", { class: "lore-rows" });
    const list = Array.isArray(loreArray) ? loreArray : [];
    list.forEach((line, idx) => {
      box.appendChild(h("div", { class: "lore-row" }, [
        window.richTextInput(line == null ? "" : String(line), markupMode(line), (v) => {
          list[idx] = v;
          if (typeof onEdit === "function") onEdit();
        }),
        h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => {
            list.splice(idx, 1);
            if (typeof onStructureChange === "function") onStructureChange();
          }
        })
      ]));
    });
    box.appendChild(h("button", {
      class: "btn-small", type: "button", text: "+ lore行",
      onclick: () => {
        list.push("");
        if (typeof onStructureChange === "function") onStructureChange();
      }
    }));
    return box;
  }

  function buildMaterialValueMap(host, title, hint, onRerender) {
    if (!host.materials || typeof host.materials !== "object" || Array.isArray(host.materials)) {
      host.materials = {};
    }
    const mats = host.materials;
    const box = h("div", { class: "sub-section" });
    box.appendChild(h("div", { class: "sub-title", text: title }));
    if (hint) {
      box.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: hint
      }));
    }
    const rows = h("div", { class: "pedestal-rows" });
    const keys = Object.keys(mats);
    if (keys.length === 0) {
      rows.appendChild(h("div", { class: "empty-hint", text: "投入マテリアルがありません。「+ 追加」で登録します。" }));
    }
    keys.forEach((mat) => {
      const hintEl = window.materialHintEl(mat);
      const matInput = window.materialInput(mat, "material-list", (v) => {
        const nv = String(v || "").trim();
        if (!nv || nv === mat) return;
        if (Object.prototype.hasOwnProperty.call(mats, nv)) {
          alert("同じキーが既にあります");
          return;
        }
        mats[nv] = mats[mat];
        delete mats[mat];
        if (typeof onRerender === "function") onRerender();
      }, { allowCustom: true });
      rows.appendChild(h("div", { class: "stat-row" }, [
        h("span", { class: "input-with-hint" }, [matInput, hintEl]),
        window.numberInput(mats[mat], (v) => {
          mats[mat] = v == null ? 0 : v;
        }, { int: true }),
        h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => {
            delete mats[mat];
            if (typeof onRerender === "function") onRerender();
          }
        })
      ]));
    });
    rows.appendChild(h("div", { class: "form-actions" }, [
      h("button", {
        class: "btn-small", type: "button", text: "+ Material追加",
        onclick: () => {
          let n = "NEW_MATERIAL", i = 1;
          let key = n;
          while (Object.prototype.hasOwnProperty.call(mats, key)) key = `${n}_${i++}`;
          mats[key] = 1;
          if (typeof onRerender === "function") onRerender();
        }
      })
    ]));
    box.appendChild(rows);
    return box;
  }

  // Java 実装 (Sourcelink サブクラス) が既定で登録する固定 id。
  // これ以外の items.<id> は「カスタムソースリンク」として type: で挙動を指定する。
  const SOURCELINK_IDS = [
    "volcanic_sourcelink", "mycelial_sourcelink", "alchemical_sourcelink",
    "vitalic_sourcelink", "botanical_sourcelink"
  ];
  const SOURCELINK_JA = {
    volcanic_sourcelink: "ヴォルカニック（燃料投入）",
    mycelial_sourcelink: "マイセリアル（食料投入）",
    alchemical_sourcelink: "アルケミカル（醸造素材投入）",
    vitalic_sourcelink: "ヴィタリック（繁殖/成長イベント）",
    botanical_sourcelink: "ボタニカル（作物成長イベント）"
  };

  // カスタムソースリンクの type: に指定できる挙動タイプ (プラグイン実装と同期)。
  const SOURCELINK_TYPES = ["volcanic", "mycelial", "alchemical", "vitalic", "botanical"];
  const TYPE_JA = {
    volcanic: "ヴォルカニック（燃料投入）",
    mycelial: "マイセリアル（食料投入）",
    alchemical: "アルケミカル（醸造素材投入）",
    vitalic: "ヴィタリック（mob撃破イベント）",
    botanical: "ボタニカル（作物成長イベント）"
  };

  /**
   * エントリの挙動タイプを解決する。明示 type: を最優先し、無ければ id に含まれる
   * 種別名から推定 (固定5種はこれで解決)。解決できなければ "" (プラグインに読まれない)。
   */
  function typeForEntry(id, entry) {
    const explicit = entry && typeof entry.type === "string" ? entry.type.trim().toLowerCase() : "";
    if (SOURCELINK_TYPES.includes(explicit)) return explicit;
    const s = String(id || "").toLowerCase();
    for (const t of SOURCELINK_TYPES) {
      if (s.includes(t)) return t;
    }
    return "";
  }

  /** type → 投入マテリアル表を持つセクション名 (volcanic|mycelial|alchemical) か null */
  function feedSectionForType(type) {
    return ["volcanic", "mycelial", "alchemical"].includes(type) ? type : null;
  }

  function buildCatalogLikeCard(id, entry, opts) {
    const options = opts || {};
    const expanded = options.expanded;
    const onToggle = options.onToggle;
    const onDelete = options.onDelete;
    const onRerender = options.onRerender;
    const extraFields = options.extraFields || (() => []);
    const itemsMap = options.itemsMap || {};
    const allowDelete = options.allowDelete !== false;
    const cmdSource = options.cmdSource || "sourcejars";

    if (!entry || typeof entry !== "object") return h("div");
    if (!Array.isArray(entry.lore)) entry.lore = [];

    const plainDisplay = (window.stripDisplayNamePlain
      ? window.stripDisplayNamePlain(entry["display-name"])
      : entry["display-name"]) || id;

    const headActions = [h("div", { class: "spacer" })];
    if (allowDelete && typeof onDelete === "function") {
      headActions.push(h("button", {
        class: "btn-small danger", type: "button", text: "削除",
        onclick: () => onDelete(id)
      }));
    }

    const head = [
      h("div", { class: "entry-collapse-summary" }, [
        h("span", { class: "entry-sum-name", text: plainDisplay }),
        h("span", { class: "entry-sum-id", text: id }),
        h("span", { class: "entry-sum-meta", text: entry.material || "" })
      ]),
      h("div", { class: "entry-collapse-edit" }, [
        h("span", { class: "entry-key-label", text: "id" }),
        h("span", { class: "field-readonly", text: id, title: "id は固定です（リネーム不可）" }),
        ...headActions
      ])
    ];

    const matHint = window.materialHintEl(entry.material);
    const matInput = tileStateMaterialInput(entry.material, (v) => {
      entry.material = v;
      matHint.update(v);
      if (typeof onRerender === "function") onRerender();
    });

    const preview = window.buildTooltipPreview();
    function refreshPreview() {
      const nm = entry["display-name"];
      preview.update({
        name: (nm != null && nm !== "") ? nm : id,
        nameMode: markupMode(nm),
        loreLines: Array.isArray(entry.lore) ? entry.lore : [],
        loreMode: markupModeOfLines(entry.lore)
      });
    }

    const inputChildren = [
      fieldRow("material", h("span", { class: "input-with-hint" }, [matInput, matHint]), {
        required: true,
        label: "material (TileState)",
        desc: "Paper 1.21.11 の TileState 対応ブロックのみ。かまど・飾り壺など。"
      }),
      fieldRow("display-name", window.richTextInput(entry["display-name"] || "", markupMode(entry["display-name"]), (v) => {
        setOrDelete(entry, "display-name", v);
        refreshPreview();
      })),
      fieldRow("custom-model-data", (() => {
        const wrap = h("span", { class: "cmd-field-row" });
        wrap.appendChild(window.numberInput(entry["custom-model-data"], (v) => {
          setOrDelete(entry, "custom-model-data", v);
        }, { int: true }));
        if (typeof window.cmdAutoAssignButton === "function") {
          wrap.appendChild(window.cmdAutoAssignButton({
            getMaterial: () => entry.material,
            getId: () => id,
            source: cmdSource,
            onAssigned: (cmd) => { entry["custom-model-data"] = cmd; if (typeof onRerender === "function") onRerender(); }
          }));
        }
        return wrap;
      })()),
      ...extraFields(entry, { refreshPreview, onRerender }),
      h("div", { class: "sub-title", text: "フレーバー説明文 (lore)" }),
      renderLoreRows(entry.lore, refreshPreview, () => {
        if (typeof onRerender === "function") onRerender();
      })
    ];

    const inputs = h("div", { class: "entry-inputs" }, inputChildren);
    const previewCol = h("div", { class: "entry-preview" }, [
      h("div", { class: "preview-label", text: "表示プレビュー" }),
      preview.element,
      h("div", {
        class: "preview-note",
        text: "品質ティア行・自動ステ行はここには表示されません。lore はフレーバー説明文です。"
      })
    ]);
    refreshPreview();

    const recipeSection = typeof window.renderCatalogRecipeSection === "function"
      // sourcejars.yml / sourcelinks.yml は UnifiedRecipeLoader が recipe:(単数)しか読まないので1件まで。
      ? window.renderCatalogRecipeSection(entry, () => {
          if (typeof onRerender === "function") onRerender();
        }, itemsMap, id, { maxRecipes: 1 })
      : h("div", { class: "field-desc", text: "レシピUI未読込（forms.js を確認）" });

    return window.collapsibleCard(
      head,
      [h("div", { class: "entry-2col" }, [inputs, previewCol]), recipeSection],
      { expanded: !!expanded, onToggle, dragId: undefined }
    );
  }

  // ---- sourcejars.yml ----
  window.buildSourceJarsForm = function buildSourceJarsForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (!working.jars || typeof working.jars !== "object" || Array.isArray(working.jars)) {
      working.jars = {};
    }
    const jars = working.jars;
    const root = h("div", { class: "dedicated-form card-list" });
    const expanded = new Set();

    function render() {
      root.innerHTML = "";
      root.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 12px;",
        text: "ソースジャーの見た目・容量・クラフト。material は TileState 必須（既定: DECORATED_POT）。capacity は -1 で無限。"
      }));
      const ids = Object.keys(jars);
      if (ids.length === 0) {
        root.appendChild(h("div", { class: "empty-guide" }, [
          h("div", { class: "empty-guide-title", text: "ジャー定義がまだありません。" }),
          h("div", { class: "empty-guide-hint", text: "「+ ジャー追加」で source_jar などを登録します。" })
        ]));
      }
      for (const id of ids) {
        const entry = jars[id] && typeof jars[id] === "object" ? jars[id] : (jars[id] = {});
        root.appendChild(buildCatalogLikeCard(id, entry, {
          expanded: expanded.has(id),
          onToggle: (open) => { if (open) expanded.add(id); else expanded.delete(id); },
          onRerender: render,
          onDelete: (delId) => { delete jars[delId]; render(); },
          itemsMap: jars,
          cmdSource: "sourcejars",
          extraFields: (ent) => [
            fieldRow("capacity", window.numberInput(ent.capacity, (v) => {
              if (v === null || v === "") delete ent.capacity;
              else ent.capacity = v;
            }, { int: true }), {
              label: "容量 (capacity)",
              desc: "蓄積可能なソース量。-1 で無限。"
            })
          ]
        }));
      }
      root.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ ジャー追加",
          onclick: () => {
            let name = "source_jar", i = 1;
            let key = name;
            while (Object.prototype.hasOwnProperty.call(jars, key)) key = `${name}_${i++}`;
            jars[key] = {
              material: "DECORATED_POT",
              "display-name": key,
              "custom-model-data": 200002,
              capacity: 10000,
              lore: []
            };
            expanded.add(key);
            render();
          }
        })
      ]));
    }

    render();
    return {
      element: root,
      getData: () => ({ ...working, jars: pruneEmptyLore(jars) })
    };
  };

  // ---- sourcelinks.yml ----
  window.buildSourceLinksForm = function buildSourceLinksForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (!working.items || typeof working.items !== "object" || Array.isArray(working.items)) {
      working.items = {};
    }
    for (const key of ["volcanic", "mycelial", "alchemical"]) {
      if (!working[key] || typeof working[key] !== "object" || Array.isArray(working[key])) {
        working[key] = { materials: {} };
      }
      if (!working[key].materials || typeof working[key].materials !== "object") {
        working[key].materials = {};
      }
    }
    const items = working.items;
    const root = h("div", { class: "dedicated-form card-list" });
    const expanded = new Set();
    if (window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
      window.RECIPES_UI.ensureCustomDatalist().catch(() => {});
    }

    const FEED_HINT = {
      volcanic: "燃料を消費してソースを生成（右クリック投入）",
      mycelial: "食料を消費してソースを生成",
      alchemical: "醸造素材を消費してソースを生成"
    };

    // 2026-08-02: transfer: 節 (SourceTransferConfig#parse) の数値/真偽フィールド1個分のUI。
    // 未編集ならキーを一切書かない(dimensionsと同じ流儀)。ラベルには Java 側の既定値を明記する。
    function transferNumberField(path, key, opts) {
      const o = opts || {};
      const section = readPath(working, path);
      const current = section && typeof section === "object" ? section[key] : undefined;
      const input = window.numberInput(current, (v) => {
        if (v === null || v === "") {
          const sec = readPath(working, path);
          if (sec && typeof sec === "object") delete sec[key];
          pruneEmptyPath(working, path);
          return;
        }
        const sec = ensurePath(working, path);
        sec[key] = o.int ? Math.trunc(Number(v)) : v;
      }, { int: !!o.int });
      return fieldRow(`transfer.${path.slice(1).join(".")}.${key}`, input, { label: o.label, desc: o.desc });
    }

    // path-particles.enabled のみ真偽値 (Java既定=true)。「!== false」表示は本コードベースの
    // 既存の「省略時true」フィールドと同じ流儀 (例: rb["reveal-plugin-recipes"] !== false)。
    function transferBoolField(path, key, defaultValue, opts) {
      const o = opts || {};
      const section = readPath(working, path);
      const raw = section && typeof section === "object" ? section[key] : undefined;
      const checked = raw === undefined ? defaultValue : !!raw;
      const input = window.checkboxInput(checked, (v) => {
        if (v === defaultValue) {
          const sec = readPath(working, path);
          if (sec && typeof sec === "object") delete sec[key];
          pruneEmptyPath(working, path);
          return;
        }
        const sec = ensurePath(working, path);
        sec[key] = v;
      });
      return fieldRow(`transfer.${path.slice(1).join(".")}.${key}`, input, { label: o.label, desc: o.desc });
    }

    // 2026-08-02: transfer: 節専用カード。SourceTransferConfig#parse の受理範囲をそのままラベルの
    // desc へ書く (K-16 でconfig化されたレバー。前は定数だったため editor に UI が無かった)。
    function renderTransferCard() {
      return h("div", { class: "entry-card" }, [
        h("div", { class: "entry-head" }, [
          h("span", { class: "entry-key-label", text: "ソース転送チューニング (transfer)" })
        ]),
        h("div", { class: "entry-body" }, [
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
            text: "SourceTransferConfig が読む調整値。未編集の項目は既定値のまま(キーを書きません)。"
                + "変更は /ars reload で反映されます。"
          }),
          h("div", { class: "sub-title", text: "ソースリンク → 隣接ソースジャー" }),
          h("div", { class: "field-grid" }, [
            transferNumberField(["transfer", "sourcelink"], "interval-ticks", {
              int: true, label: "供給周期 (tick)",
              desc: "1〜72000。省略時は既定100。実効レート=max-per-transfer÷interval-ticks。"
            }),
            transferNumberField(["transfer", "sourcelink"], "max-per-transfer", {
              int: true, label: "基準転送量 (1周期あたり)",
              desc: "1〜2147483647。省略時は既定50。個々の items.<id>.transfer-multiplier がこの基準値に掛かる。"
            }),
            transferNumberField(["transfer", "sourcelink"], "buffer-cap", {
              int: true, label: "内部バッファ上限",
              desc: "1〜2147483647。省略時は既定2147483647(int上限=実質無制限)。"
            })
          ]),
          h("div", { class: "field-grid" }, [
            transferNumberField(["transfer", "sourcelink", "detection-radius"], "vitalic", {
              int: true, label: "バイタリック討伐検知半径",
              desc: "0〜256。省略時は既定10。0でこの種別のイベント蓄積を止める。"
            }),
            transferNumberField(["transfer", "sourcelink", "detection-radius"], "botanical", {
              int: true, label: "ボタニカル成長検知半径",
              desc: "0〜256。省略時は既定10。0でこの種別のイベント蓄積を止める。"
            })
          ]),
          h("div", { class: "sub-title", text: "ソースネットワーク (ドミニオンワンドのリレー網)" }),
          h("div", { class: "field-grid" }, [
            transferNumberField(["transfer", "network"], "interval-ticks", {
              int: true, label: "転送周期 (tick)",
              desc: "1〜72000。省略時は既定40。"
            }),
            transferNumberField(["transfer", "network"], "max-per-transfer", {
              int: true, label: "転送量 (1周期あたり)",
              desc: "1〜2147483647。省略時は既定100。"
            }),
            transferNumberField(["transfer", "network"], "max-link-range", {
              int: true, label: "最大接続距離 (ブロック)",
              desc: "1〜256。省略時は既定30。既存の長いリンクは縮めても切れない(新規接続だけ弾かれる)。"
            })
          ]),
          h("div", { class: "sub-title", text: "経路パーティクル表示" }),
          h("div", { class: "field-grid" }, [
            transferBoolField(["transfer", "network", "path-particles"], "enabled", true, {
              label: "表示する", desc: "省略時は既定true。falseで完全に無効化。"
            }),
            transferNumberField(["transfer", "network", "path-particles"], "interval-ticks", {
              int: true, label: "描画周期 (tick)",
              desc: "1〜1200。省略時は既定20。短くすると滑らかになるが負荷が比例して上がる。"
            }),
            transferNumberField(["transfer", "network", "path-particles"], "spacing", {
              int: false, label: "粒子間隔 (ブロック)",
              desc: "0.1〜16.0。省略時は既定1.0。小さいほど線が濃くなる。"
            }),
            transferNumberField(["transfer", "network", "path-particles"], "view-distance", {
              int: true, label: "表示距離",
              desc: "1〜256。省略時は既定48。この距離内に端点がある経路だけ描く。"
            }),
            transferNumberField(["transfer", "network", "path-particles"], "max-paths", {
              int: true, label: "同時表示数",
              desc: "1〜4096。省略時は既定16。1プレイヤーあたりの描画経路数上限。"
            })
          ]),
          h("div", { class: "sub-title", text: "infinity_source_core (1億ソース到達の恒久設備)" }),
          h("div", { class: "field-grid" }, [
            transferNumberField(["transfer", "infinity-core"], "radius", {
              int: true, label: "効果半径 (ブロック)",
              desc: "0〜256。省略時は既定5。0でこの補正自体を無効化できる。"
            }),
            transferNumberField(["transfer", "infinity-core"], "transfer-multiplier", {
              int: false, label: "転送倍率",
              desc: "0〜1000。省略時は既定2.0。半径内ソースリンクのmax-per-transferに掛かる。"
            }),
            transferNumberField(["transfer", "infinity-core"], "buffer-multiplier", {
              int: false, label: "バッファ倍率",
              desc: "0〜1000。省略時は既定2.0。半径内ソースリンクのbuffer-capに掛かる。"
            })
          ])
        ])
      ]);
    }

    function render() {
      root.innerHTML = "";
      root.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 12px;",
        text: "ソースリンク本体。既定5種に加えて、任意 id + 挙動タイプ (type) のカスタムソースリンクを追加できます。material は TileState ブロックのみ。投入マテリアル表は volcanic/mycelial/alchemical タイプで共有（vitalic/botanical はイベント生成のため投入表なし）。追加は /ars reload、削除の反映はサーバー再起動が必要です。"
      }));
      root.appendChild(renderTransferCard());

      const ids = Object.keys(items);
      if (ids.length === 0) {
        root.appendChild(h("div", { class: "empty-guide" }, [
          h("div", { class: "empty-guide-title", text: "ソースリンク本体がまだありません。" }),
          h("div", { class: "empty-guide-hint", text: "下の追加ボタンで5種のソースリンクを登録します。" })
        ]));
      }
      for (const id of ids) {
        const entry = items[id] && typeof items[id] === "object" ? items[id] : (items[id] = {});
        const fixed = SOURCELINK_IDS.includes(id);
        const type = typeForEntry(id, entry);
        const feed = feedSectionForType(type);
        root.appendChild(buildCatalogLikeCard(id, entry, {
          expanded: expanded.has(id),
          onToggle: (open) => { if (open) expanded.add(id); else expanded.delete(id); },
          onRerender: render,
          onDelete: (delId) => { delete items[delId]; render(); },
          itemsMap: items,
          cmdSource: "sourcelinks",
          extraFields: () => {
            const extras = [];
            // 2026-08-02 (K-16): items.<id>.transfer-multiplier (SourcelinkConfig#readTransferMultiplier)。
            // 基準レート(transfer.sourcelink.max-per-transfer)に掛かる倍率。未入力=1.0(無印と同じ)。
            // 固定5種・カスタム・II/III階梯のいずれにも適用できるので fixed 判定の外(常時表示)。
            extras.push(fieldRow("transfer-multiplier", window.numberInput(entry["transfer-multiplier"], (v) => {
              if (v === null || v === "") { delete entry["transfer-multiplier"]; return; }
              entry["transfer-multiplier"] = v;
            }, { int: false }), {
              label: "転送レート倍率",
              desc: "基準レートに掛かる倍率。省略で1.0(無印と同じ)。上位ソースリンク(_ii〜_v)向け。"
            }));
            // 2026-08-03: items.<id>.yield-multiplier (SourcelinkConfig#readYieldMultiplier)。
            // 転送レート倍率とは効き方が違う ── あちらは「バッファから1周期に出せる量」で、上げても
            // 素材1個あたりの取得ソースは変わらない。こちらは生成そのもの(燃料投入/受動生成/
            // 成長・撃破ボーナス)に掛かるので素材効率が上がる。返却経路には掛からない。
            extras.push(fieldRow("yield-multiplier", window.numberInput(entry["yield-multiplier"], (v) => {
              if (v === null || v === "") { delete entry["yield-multiplier"]; return; }
              entry["yield-multiplier"] = v;
            }, { int: false }), {
              label: "生成量倍率",
              desc: "燃料投入・受動生成・成長/撃破ボーナスの生成量に掛かる倍率。省略で1.0(無印と同じ)。"
            }));
            if (!fixed) {
              // カスタム id は挙動タイプを明示指定する (プラグインはこの type で実体を作る)。
              extras.push(fieldRow("type", window.listSelect({
                value: SOURCELINK_TYPES.includes(entry.type) ? entry.type : type,
                placeholder: "— タイプを選択 —",
                options: SOURCELINK_TYPES.map((t) => ({
                  value: t, primary: `${TYPE_JA[t] || t}`, secondary: t
                })),
                onChange: (v) => {
                  setOrDelete(entry, "type", v || "");
                  render();
                }
              }), {
                required: true,
                label: "挙動タイプ (type)",
                desc: "このソースリンクの動作。投入マテリアル表もタイプに従います。"
              }));
              if (!type) {
                extras.push(h("div", {
                  class: "form-banner",
                  text: "type が未設定です。タイプを選択しないとプラグインに読み込まれません。"
                }));
              }
            }
            if (feed && working[feed]) {
              extras.push(buildMaterialValueMap(
                working[feed],
                `投入マテリアル (${feed}.materials)` + (fixed ? "" : " — 同タイプで共有"),
                FEED_HINT[feed] || "",
                render
              ));
            } else if (type) {
              extras.push(h("div", {
                class: "field-desc",
                style: "font-size:11px;color:var(--muted,#6b7280);",
                text: "この種別は投入マテリアル表を持ちません（成長／撃破イベントでソース生成）。"
              }));
            }
            return extras;
          }
        }));
      }
      // 追加: 未定義の固定5種 + 任意idのカスタムソースリンク。
      const missing = SOURCELINK_IDS.filter((sid) => !Object.prototype.hasOwnProperty.call(items, sid));
      const addButtons = missing.map((sid) => h("button", {
        class: "btn", type: "button", text: `+ ${SOURCELINK_JA[sid] || sid}`,
        title: sid,
        onclick: () => {
          items[sid] = {
            material: "FURNACE",
            "display-name": sid,
            "custom-model-data": 200003,
            lore: []
          };
          expanded.add(sid);
          render();
        }
      }));
      addButtons.push(h("button", {
        class: "btn", type: "button", text: "+ カスタムソースリンク追加",
        title: "任意 id + type で新しいソースリンクを定義します",
        onclick: () => {
          let key = "custom_sourcelink", i = 1;
          while (Object.prototype.hasOwnProperty.call(items, key)) key = `custom_sourcelink_${i++}`;
          items[key] = {
            type: "volcanic",
            material: "FURNACE",
            "display-name": key,
            "custom-model-data": 200003,
            lore: []
          };
          expanded.add(key);
          render();
        }
      }));
      root.appendChild(h("div", { class: "form-actions" }, addButtons));
    }

    render();
    return {
      element: root,
      getData: () => {
        pruneEmptyTransfer(working);
        const out = { ...working, items: pruneEmptyLore(items) };
        for (const key of ["volcanic", "mycelial", "alchemical"]) {
          if (out[key] && typeof out[key] === "object") {
            out[key] = { materials: { ...(out[key].materials || {}) } };
          }
        }
        return out;
      }
    };
  };

  // Node テスト向けに純関数を公開 (mob-forms.js の window.MOB_FORMS_LOGIC と同じ流儀)。
  // ブラウザ実行時の挙動には影響しない。
  window.ARS_SOURCE_FORMS_LOGIC = { ensurePath, readPath, pruneEmptyPath, pruneEmptyTransfer, markupMode, markupModeOfLines };
})();
