"use strict";

// 軽量 DOM ヘルパー。フレームワーク不使用。
window.h = function h(tag, attrs, children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) {
      if (value === null || value === undefined) continue;
      if (key === "class") el.className = value;
      else if (key === "text") el.textContent = value;
      else if (key.startsWith("on") && typeof value === "function") {
        el.addEventListener(key.slice(2).toLowerCase(), value);
      }       else if (key === "value") {
        el.value = value;
      } else if (key === "checked") {
        el.checked = Boolean(value);
      } else if (key === "disabled" || key === "readonly" || key === "required" || key === "draggable") {
        // HTML boolean attrs: presence alone enables them. disabled="false" still disables.
        if (value) el.setAttribute(key, key === "draggable" ? "true" : "");
        else el.removeAttribute(key);
      } else if (typeof value === "boolean") {
        if (value) el.setAttribute(key, "");
        else el.removeAttribute(key);
      } else {
        el.setAttribute(key, value);
      }
    }
  }
  if (children !== undefined && children !== null) {
    const arr = Array.isArray(children) ? children : [children];
    for (const c of arr) {
      if (c === null || c === undefined || c === false) continue;
      el.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
    }
  }
  return el;
};

// Material / アイテム参照の共通セレクト。
//
// 2026-07-29: それまでは「テキスト入力 + 独自サジェスト」で、editor 内の他のセレクト
// (アイテムカタログの表示タブ等 = listSelect) と見た目も操作も揃っていなかった。
// 呼び出し側 30 箇所超を個別に書き換える代わりに、この関数の中身だけを listSelect へ
// 差し替えて「アイテム/Material を引数に取る欄」の DOM を 1 つに統一する。
//   - 主表示 = 日本語アイテム名 / 副表示 = 薄字の ID (listSelect の既定描画)
//   - ドロップダウン先頭の絞り込み欄で ID・日本語表示名のどちらの部分一致でも引ける
//   - opts.allowCustom: true でカタログのカスタムアイテム(custom:<id>)を候補の先頭に含める
//     (false の箇所 = ゲーム仕様上バニラ Material しか置けない欄)
//   - 候補に無い ID も「＋ 直接入力…」から入れられる (未知 Material / 手書きトークンの救済)
//
// 引数と戻り値の契約は据え置き: 戻り値は .value の get/set と change リスナを持つ span。
// listId は後方互換のため受け取るが未使用。
window.materialInput = function materialInput(value, listId, onInput, opts) {
  const options = opts && typeof opts === "object" ? opts : {};
  const allowCustom = !!options.allowCustom;
  const FREE_INPUT = "__material_free__";
  const changeListeners = [];
  let current = value == null ? "" : String(value);

  function isCustomKey(key) {
    return /^custom:/i.test(String(key || ""));
  }

  function customLabelOf(key) {
    const labels = window.CUSTOM_ITEM_LABELS && typeof window.CUSTOM_ITEM_LABELS === "object"
      ? window.CUSTOM_ITEM_LABELS : {};
    return labels[key] || labels[String(key).toLowerCase()] || "";
  }

  function customCatalog() {
    let raw = [];
    if (typeof options.getCustomCandidates === "function") {
      raw = options.getCustomCandidates() || [];
    } else if (Array.isArray(options.customCandidates)) {
      raw = options.customCandidates;
    } else if (Array.isArray(window.CUSTOM_ITEM_CANDIDATES)) {
      raw = window.CUSTOM_ITEM_CANDIDATES;
    }
    const out = [];
    const seen = new Set();
    for (const x of raw) {
      if (x == null || x === "") continue;
      const s = String(x);
      const key = isCustomKey(s) ? ("custom:" + s.slice(s.indexOf(":") + 1)) : ("custom:" + s);
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(key);
    }
    return out;
  }

  function materialOption(key) {
    const ja = (window.LABELS && typeof window.LABELS.materialLabel === "function")
      ? window.LABELS.materialLabel(key)
      : ((window.MATERIAL_LABELS && window.MATERIAL_LABELS[key]) || "");
    return { value: key, primary: ja || key, secondary: key };
  }

  function customOption(key) {
    const id = String(key).slice(String(key).indexOf(":") + 1);
    const label = customLabelOf(key);
    return {
      value: key,
      primary: label && label !== id ? label : `カスタム: ${id}`,
      secondary: key
    };
  }

  // 候補一覧に載らない現在値の見せ方。素材互換リスト(list:<id>)は解体設定などで実際に
  // 保存されている正規のトークンなので、「候補外」ではなく中身の分かる表示にする。
  function currentFallbackOption(key) {
    if (isCustomKey(key)) return customOption(key);
    const m = String(key).match(/^list:(.*)$/i);
    if (m) return { value: key, primary: `互換リスト: ${m[1]}`, secondary: key };
    return { value: key, primary: key, secondary: "候補外" };
  }

  function resolveOptions() {
    const out = [];
    // 「＋ 直接入力…」は先頭に置く。候補は 1700 件近くあり listSelect の描画上限(200件)で
    // 打ち切られるため、末尾に置くと絞り込まない限り永久に見えない。
    out.push({ value: FREE_INPUT, primary: "＋ 直接入力…", title: "候補に無い ID を直接入力します" });
    if (allowCustom) {
      for (const k of customCatalog()) out.push(customOption(k));
    }
    for (const k of (Array.isArray(window.MATERIALS) ? window.MATERIALS : [])) {
      out.push(materialOption(k));
    }
    // 候補に無い現在値(未知 Material・手書きトークン)でも表示が消えないように差し込む。
    if (current && !out.some((o) => o.value === current)) {
      out.splice(1, 0, currentFallbackOption(current));
    }
    return out;
  }

  // 自由入力の正規化。custom: は allowCustom の欄でだけ特別扱いし、list: (素材互換リスト)は
  // どの欄でも保持する — 大文字化で潰すと "list:cobblestone" が黙って別トークンに化けるため。
  function normalizeCommitValue(nv) {
    const raw = nv == null ? "" : String(nv).trim();
    if (!raw) return "";
    if (allowCustom && /^custom:/i.test(raw)) {
      const id = raw.slice(raw.indexOf(":") + 1).trim();
      return id ? ("custom:" + id) : "custom:";
    }
    if (/^list:/i.test(raw)) {
      const id = raw.slice(raw.indexOf(":") + 1).trim();
      return id ? ("list:" + id) : "list:";
    }
    return raw.toUpperCase().replace(/[^A-Z0-9_]/g, "");
  }

  const el = window.listSelect({
    value: current,
    options: resolveOptions,
    allowCustom: true,
    customValue: FREE_INPUT,
    customPlaceholder: allowCustom
      ? "Material名 / custom:<カタログID>"
      : "Material名 (例: IRON_INGOT)",
    placeholder: options.placeholder || "アイテムを選択…",
    filterPlaceholder: "絞り込み (アイテム名 / ID)",
    // listSelect の基底クラスに material-suggest が既に入っているので、ここでは足さない。
    className: options.className,
    onCommit: (nv) => {
      const next = normalizeCommitValue(nv);
      current = next;
      if (typeof onInput === "function") onInput(next);
      const ev = { type: "change", target: { value: next }, currentTarget: el };
      for (const fn of changeListeners) {
        try { fn(ev); } catch (_) { /* listener error は他リスナを止めない */ }
      }
      // 正規化後の値を listSelect 側の確定値としても採用させる。
      return next;
    }
  });

  Object.defineProperty(el, "value", {
    get() { return current; },
    set(v) {
      current = v == null ? "" : String(v);
      el.setValue(current);
    },
    configurable: true
  });

  const nativeAdd = el.addEventListener.bind(el);
  el.addEventListener = function (type, listener, opts2) {
    if (type === "change" && typeof listener === "function") {
      changeListeners.push(listener);
      return;
    }
    return nativeAdd(type, listener, opts2);
  };

  // /api/material-labels の到着後に日本語表示へ差し替えるためのフック (app.js から呼ばれる)。
  el._rebuildSuggestIndex = function () { el.refresh(); };
  el._rebuildMaterialOptions = el._rebuildSuggestIndex;

  return el;
};

// catalog / recipes / materials などから custom: 候補を共有登録する。
// entries: string | { id|key, label|displayName } の配列。
// opts.replace=true で全置換（既定はマージ）。
window.setCustomItemCandidates = function setCustomItemCandidates(entries, opts) {
  const options = opts && typeof opts === "object" ? opts : {};
  const replace = !!options.replace;
  const out = replace ? [] : (Array.isArray(window.CUSTOM_ITEM_CANDIDATES) ? window.CUSTOM_ITEM_CANDIDATES.slice() : []);
  const labels = replace
    ? {}
    : Object.assign({}, (window.CUSTOM_ITEM_LABELS && typeof window.CUSTOM_ITEM_LABELS === "object")
      ? window.CUSTOM_ITEM_LABELS : {});
  const seen = new Set(out);

  function pushOne(raw, label) {
    if (raw == null || raw === "") return;
    const s = String(raw);
    const key = /^custom:/i.test(s) ? ("custom:" + s.slice(s.indexOf(":") + 1)) : ("custom:" + s);
    if (!seen.has(key)) {
      seen.add(key);
      out.push(key);
    }
    const plain = label == null ? "" : String(label).trim();
    if (plain) {
      const stripped = typeof window.stripDisplayNamePlain === "function"
        ? window.stripDisplayNamePlain(plain) : plain;
      if (stripped) labels[key] = stripped;
    }
  }

  for (const x of entries || []) {
    if (x == null || x === "") continue;
    if (typeof x === "object") {
      const id = x.id != null ? x.id : (x.key != null ? x.key : null);
      const label = x.label != null ? x.label : (x.displayName != null ? x.displayName : "");
      pushOne(id, label);
    } else {
      pushOne(x, "");
    }
  }
  window.CUSTOM_ITEM_CANDIDATES = out;
  window.CUSTOM_ITEM_LABELS = labels;
};

// 数値入力。整数フラグで step を切り替える。空欄は null を返す。
window.numberInput = function numberInput(value, onInput, opts) {
  const options = opts || {};
  return window.h("input", {
    class: "field-input num",
    type: "number",
    step: options.int ? "1" : "any",
    value: value == null ? "" : String(value),
    oninput: (e) => {
      const raw = e.target.value;
      if (raw === "") return onInput(null);
      const n = Number(raw);
      onInput(Number.isNaN(n) ? raw : n);
    }
  });
};

window.textInput = function textInput(value, onInput, placeholder) {
  return window.h("input", {
    class: "field-input",
    value: value == null ? "" : String(value),
    placeholder: placeholder || "",
    oninput: (e) => onInput(e.target.value),
    spellcheck: "false"
  });
};

/**
 * Material サジェストと同型の見た目で、候補は全件から選ぶセレクト（フィルタなし）。
 * ネイティブ &lt;select size&gt; 展開は使わず fixed オーバーレイにするため、周囲レイアウトがずれない。
 *
 * @param {object} cfg
 * @param {string} [cfg.value]
 * @param {Array<{value:string,primary:string,secondary?:string,title?:string}>|function():Array} cfg.options
 * @param {function(string): (boolean|void)} [cfg.onCommit] false で選択を却下
 * @param {function(string): void} [cfg.onChange] onCommit が無いときの簡易コールバック
 * @param {boolean} [cfg.allowCustom]
 * @param {string} [cfg.customPlaceholder]
 * @param {string} [cfg.placeholder]
 * @param {boolean} [cfg.disabled]
 * @param {string} [cfg.className]
 * @param {string} [cfg.customValue] allowCustom 時の特殊 value（既定 __custom__）
 */
window.listSelect = function listSelect(cfg) {
  const h = window.h;
  const CUSTOM = cfg.customValue != null ? String(cfg.customValue) : "__custom__";
  let current = cfg.value == null ? "" : String(cfg.value);
  let open = false;
  let activeIndex = -1;
  const allowCustom = !!cfg.allowCustom;
  const disabled = !!cfg.disabled;

  const wrap = h("span", {
    class: "list-select material-suggest" + (cfg.className ? " " + cfg.className : "")
  });
  const trigger = h("button", {
    type: "button",
    class: "field-input material-suggest-input list-select-trigger"
  });
  if (disabled) trigger.disabled = true;
  const list = h("ul", {
    class: "material-suggest-list",
    role: "listbox",
    style: "display:none;"
  });
  const custom = h("input", {
    class: "field-input list-select-custom",
    placeholder: cfg.customPlaceholder || "自由入力",
    spellcheck: "false",
    autocomplete: "off"
  });
  custom.style.display = "none";

  // 2026-07-28: 候補が数百件あるセレクト(素材/敵種類/エンチャント等)で目的の項目まで
  // スクロールするしかなかったため、ドロップダウン先頭に絞り込み入力を常設する。
  // 日本語名・ID のどちらでも引ける (材質サジェスト materialInput と同じ流儀)。
  const MAX_RENDERED = 200;
  let query = "";
  let renderedOptions = [];
  const filterInput = h("input", {
    class: "field-input list-select-filter",
    type: "text",
    spellcheck: "false",
    autocomplete: "off",
    placeholder: cfg.filterPlaceholder || "絞り込み (日本語名 / ID)"
  });
  const filterRow = h("li", { class: "list-select-filter-row" }, [filterInput]);

  function resolveOptions() {
    const raw = typeof cfg.options === "function" ? cfg.options() : (cfg.options || []);
    return Array.isArray(raw) ? raw.slice() : [];
  }

  function normalizeQuery(raw) {
    return String(raw == null ? "" : raw).trim().toLowerCase().replace(/\s+/g, "");
  }

  // 前方一致 > 部分一致 の順に並べ替える。同スコア内は元の並び順(=呼び出し側が意図した順)を保つ。
  function filterOptions(options, rawQuery) {
    const q = normalizeQuery(rawQuery);
    if (!q) return options;
    const scored = [];
    options.forEach((opt, idx) => {
      const value = String(opt.value == null ? "" : opt.value).toLowerCase();
      const primary = normalizeQuery(opt.primary);
      const secondary = String(opt.secondary == null ? "" : opt.secondary).toLowerCase();
      const compact = value.replace(/_/g, "");
      let score = 0;
      if (value === q || primary === q || compact === q) score = 3;
      else if (value.startsWith(q) || primary.startsWith(q) || compact.startsWith(q)
        || secondary.startsWith(q)) score = 2;
      else if (value.includes(q) || primary.includes(q) || compact.includes(q)
        || secondary.includes(q)) score = 1;
      if (score > 0) scored.push({ opt, score, idx });
    });
    scored.sort((a, b) => (b.score - a.score) || (a.idx - b.idx));
    return scored.map((s) => s.opt);
  }

  function findOption(value, options) {
    const v = String(value == null ? "" : value);
    return options.find((o) => String(o.value) === v) || null;
  }

  function optionLabel(opt) {
    if (!opt) return "";
    if (opt.secondary) return `${opt.primary} (${opt.secondary})`;
    return opt.primary || String(opt.value);
  }

  // 2026-07-29: 確定後のトリガー表示は主表示(日本語名)だけにする。
  // ID は「編集者が既に知っている情報」で、選び終わったあとの欄では冗長なため
  // (候補一覧では薄字の secondary 行として出るので、引きたいときはそちらで確認できる)。
  // 情報自体は title 属性に残すので hover では読める。
  function triggerLabel(opt) {
    if (!opt) return "";
    return opt.primary || String(opt.value);
  }

  function syncTrigger() {
    const options = resolveOptions();
    const opt = findOption(current, options);
    if (opt) {
      trigger.textContent = triggerLabel(opt);
      trigger.title = opt.title || optionLabel(opt);
      trigger.classList.remove("is-placeholder");
    } else if (current) {
      trigger.textContent = current;
      trigger.title = current;
      trigger.classList.remove("is-placeholder");
    } else {
      trigger.textContent = cfg.placeholder || "選択…";
      trigger.title = "";
      trigger.classList.add("is-placeholder");
    }
  }

  function positionList() {
    const rect = trigger.getBoundingClientRect();
    const gutter = 8;
    const preferredW = Math.max(rect.width, 260);
    const maxW = Math.max(180, window.innerWidth - gutter * 2);
    const width = Math.min(preferredW, maxW);
    const left = Math.min(Math.max(gutter, rect.left), window.innerWidth - width - gutter);
    const spaceBelow = window.innerHeight - rect.bottom - gutter;
    const spaceAbove = rect.top - gutter;
    const preferBelow = spaceBelow >= 140 || spaceBelow >= spaceAbove;
    const maxH = Math.min(320, Math.max(120, preferBelow ? spaceBelow : spaceAbove));

    list.style.position = "fixed";
    list.style.left = left + "px";
    list.style.width = width + "px";
    list.style.maxWidth = maxW + "px";
    list.style.maxHeight = maxH + "px";
    list.style.zIndex = "10000";
    list.style.overflowX = "hidden";
    list.style.overflowY = "auto";
    list.style.right = "auto";
    if (preferBelow) {
      list.style.top = (rect.bottom + 4) + "px";
      list.style.bottom = "auto";
    } else {
      list.style.top = "auto";
      list.style.bottom = (window.innerHeight - rect.top + 4) + "px";
    }
  }

  function closeList() {
    open = false;
    activeIndex = -1;
    list.style.display = "none";
    list.innerHTML = "";
    if (list.parentNode) list.parentNode.removeChild(list);
    document.removeEventListener("mousedown", onDocDown, true);
    window.removeEventListener("resize", onScrollOrResize);
    window.removeEventListener("scroll", onScrollOrResize, true);
  }

  function onDocDown(e) {
    if (wrap.contains(e.target) || list.contains(e.target)) return;
    closeList();
  }

  function onScrollOrResize() {
    if (open) positionList();
  }

  function tryCommit(nv) {
    let next = nv == null ? "" : String(nv);
    if (typeof cfg.onCommit === "function") {
      const result = cfg.onCommit(next);
      if (result === false) {
        syncTrigger();
        return false;
      }
      // 文字列を返したら「正規化後の確定値」として採用する。自由入力を呼び出し側が
      // 整形する欄(materialInput の大文字化など)で、トリガー表示と保存値がずれないようにする。
      if (typeof result === "string") next = result;
    } else if (typeof cfg.onChange === "function") {
      cfg.onChange(next);
    }
    current = next;
    syncTrigger();
    return true;
  }

  function pick(value) {
    // per-option disabled (グレーアウト): 選択不可
    const opt = findOption(value, resolveOptions());
    if (opt && opt.disabled) return;
    if (allowCustom && String(value) === CUSTOM) {
      closeList();
      custom.style.display = "";
      custom.value = "";
      custom.focus();
      return;
    }
    custom.style.display = "none";
    if (tryCommit(value)) closeList();
  }

  function renderList() {
    const all = resolveOptions();
    const matched = filterOptions(all, query);
    const options = matched.slice(0, MAX_RENDERED);
    renderedOptions = options;
    if (activeIndex >= options.length) activeIndex = options.length ? options.length - 1 : -1;
    // 絞り込み欄だけは作り直さない: innerHTML を空にすると入力中のフォーカスとカーソル位置が
    // 飛んでしまい、1文字打つごとに入力が中断される。
    for (const child of [...list.children]) {
      if (child !== filterRow) list.removeChild(child);
    }
    if (!list.parentNode) document.body.appendChild(list);
    // 絞り込み欄は候補が少ないときには邪魔なので、一定件数を超えるときだけ出す。
    if (all.length > 8) {
      if (filterRow.parentNode !== list) list.appendChild(filterRow);
    } else if (filterRow.parentNode === list) {
      list.removeChild(filterRow);
    }
    if (!options.length) {
      list.appendChild(h("li", {
        class: "material-suggest-empty",
        text: all.length ? "一致する候補がありません" : "選択肢がありません"
      }));
    } else {
      options.forEach((opt, idx) => {
        const children = [
          h("span", { class: "material-suggest-primary", text: opt.primary || String(opt.value) })
        ];
        if (opt.secondary) {
          children.push(h("span", { class: "material-suggest-secondary", text: opt.secondary }));
        }
        const li = h("li", {
          class: "material-suggest-item"
            + (String(opt.value) === String(current) ? " is-current" : "")
            + (idx === activeIndex ? " is-active" : "")
            + (opt.disabled ? " is-disabled" : ""),
          role: "option",
          "data-value": String(opt.value),
          title: opt.title || optionLabel(opt)
        }, children);
        li.addEventListener("mousedown", (e) => {
          e.preventDefault();
          pick(opt.value);
        });
        list.appendChild(li);
      });
      if (matched.length > options.length) {
        list.appendChild(h("li", {
          class: "material-suggest-empty",
          text: `他 ${matched.length - options.length} 件。絞り込んでください。`
        }));
      }
    }
    positionList();
    list.style.display = "";
    open = true;
    document.addEventListener("mousedown", onDocDown, true);
    window.addEventListener("resize", onScrollOrResize);
    window.addEventListener("scroll", onScrollOrResize, true);
    const curLi = list.querySelector(".material-suggest-item.is-current");
    if (curLi) curLi.scrollIntoView({ block: "nearest" });
  }

  function openList() {
    if (disabled) return;
    custom.style.display = "none";
    query = "";
    filterInput.value = "";
    activeIndex = -1;
    const options = resolveOptions();
    const idx = options.findIndex((o) => String(o.value) === String(current));
    activeIndex = idx >= 0 ? idx : 0;
    renderList();
    // 絞り込み欄があるときはそこへフォーカスする(開いた直後から打てる)。
    if (filterRow.parentNode) filterInput.focus();
    else trigger.focus();
  }

  /** ↑↓/Enter/Escape の共通処理。trigger と絞り込み入力の両方から呼ぶ。 */
  function handleNavKey(e) {
    if (disabled) return false;
    const items = [...list.querySelectorAll(".material-suggest-item")];
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) { openList(); return true; }
      activeIndex = Math.min(items.length - 1, activeIndex + 1);
      items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
      if (items[activeIndex]) items[activeIndex].scrollIntoView({ block: "nearest" });
      return true;
    }
    if (e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) return true;
      activeIndex = Math.max(0, activeIndex - 1);
      items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
      if (items[activeIndex]) items[activeIndex].scrollIntoView({ block: "nearest" });
      return true;
    }
    if (e.key === "Enter") {
      e.preventDefault();
      if (!open) { openList(); return true; }
      if (activeIndex >= 0 && items[activeIndex]) {
        pick(items[activeIndex].getAttribute("data-value"));
      }
      return true;
    }
    if (e.key === "Escape") {
      if (open) {
        e.preventDefault();
        closeList();
        trigger.focus();
      }
      return true;
    }
    return false;
  }

  trigger.addEventListener("click", (e) => {
    e.preventDefault();
    if (open) closeList();
    else openList();
  });

  trigger.addEventListener("keydown", (e) => {
    if (disabled) return;
    if (e.key === " ") {
      e.preventDefault();
      if (!open) openList();
      return;
    }
    handleNavKey(e);
  });

  filterInput.addEventListener("keydown", (e) => {
    // スペースは絞り込み文字として入力させる(trigger 側の「開く」ショートカットと衝突させない)。
    handleNavKey(e);
  });
  filterInput.addEventListener("input", () => {
    query = filterInput.value;
    activeIndex = 0;
    renderList();
  });
  // ドロップダウン内のクリックでトリガーへフォーカスが戻らないようにする。
  filterRow.addEventListener("mousedown", (e) => { e.stopPropagation(); });

  custom.addEventListener("change", () => {
    const nv = custom.value.trim();
    if (!nv) {
      custom.style.display = "none";
      syncTrigger();
      return;
    }
    if (tryCommit(nv)) custom.style.display = "none";
  });
  custom.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      custom.style.display = "none";
      syncTrigger();
      trigger.focus();
    }
  });

  wrap.appendChild(trigger);
  wrap.appendChild(custom);
  syncTrigger();

  wrap.getValue = () => current;
  wrap.setValue = (v) => {
    current = v == null ? "" : String(v);
    syncTrigger();
  };
  wrap.refresh = syncTrigger;
  return wrap;
};

// アイテム参照 (カタログID / バニラMaterial) の共通セレクト。
// アチーブメント等の付与アイテム (tf-rewards-forms.js itemIdInput) と、ダンジョンゲートの
// 必要鍵アイテム (tf-dungeon-forms.js buildDungeonGatesForm) の両方から使う共通ヘルパー。
// 保存される値は常に ID 文字列そのもの (catalogID 生値 / バニラMaterial名)。表示だけを
// listSelect の日本語主表示に差し替える。
// cfg.value: 現在値
// cfg.onChange(next): 確定時コールバック (next は素のID文字列)
// cfg.catalogCandidates: [{ id, label, ... }] (省略時はバニラMaterialのみの候補になる)
// cfg.placeholder / cfg.customPlaceholder: 任意
window.itemRefSelect = function itemRefSelect(cfg) {
  const options = [];
  // 2026-07-29: 主表示を c.label だけから引いていたが、候補を作る唯一の生成器
  // (catalog-candidates.js buildCatalogCandidates) が返すキーは displayName で、label は
  // 誰も入れていなかった。結果 || c.id へ落ちて、報酬アイテム・ダンジョンの必要鍵アイテムの
  // セレクトが全部カタログIDの羅列になっていた。両方のキーを見る。
  // display-name は MiniMessage/&色コード入りなので素の文字へ落として並べる。
  const plain = (raw) => (typeof window.stripDisplayNamePlain === "function"
    ? window.stripDisplayNamePlain(raw) : String(raw == null ? "" : raw));
  for (const c of (Array.isArray(cfg.catalogCandidates) ? cfg.catalogCandidates : [])) {
    if (!c || !c.id) continue;
    const name = plain(c.label != null && c.label !== "" ? c.label : c.displayName);
    options.push({ value: c.id, primary: name || c.id, secondary: c.id });
  }
  // バニラ Material も英字IDのままだったので、素材辞書(日本語名)を主表示にする。
  const materialLabel = (m) => (window.LABELS && typeof window.LABELS.materialLabelWithFallback === "function"
    ? window.LABELS.materialLabelWithFallback(m) : m);
  for (const m of (Array.isArray(window.MATERIALS) ? window.MATERIALS : [])) {
    options.push({ value: m, primary: materialLabel(m) || m, secondary: m });
  }
  const cur = cfg.value == null ? "" : String(cfg.value);
  // 候補に無い値 (手書きID・未知のカタログID等) でも消えないよう、値そのものを先頭候補として差し込む
  // (tf-rewards-forms.js statKeySelect と同じ流儀)。
  //
  // 2026-07-31: 出荷 achievements.yml の rewards.items[].id は全て `custom:<id>` 形式なのに、
  // 候補の value は素のカタログID(上のループ)なので照合が必ず外れ、生トークンが主表示に
  // なっていた(「custom:tf_gacha_ticket_5」がそのまま欄に出る)。接頭辞を剥がした形で
  // 候補を引き直し、**表示だけ**候補のラベルへ寄せる。
  // 保存値は `custom:` 付きのまま (verbatim)。剥がして書き戻すと「開いて保存しただけ」で
  // yml に無関係な差分が出る。Java 側 (CrossPluginItemResolver#stripCustomPrefix) は
  // どちらの形でも解けるので、保存値を触る理由が無い。
  if (cur && !options.some((o) => o.value === cur)) {
    const bare = /^custom:/i.test(cur) ? cur.slice(cur.indexOf(":") + 1).trim() : "";
    const hit = bare ? options.find((o) => o.value === bare) : null;
    options.unshift(hit
      ? { value: cur, primary: hit.primary, secondary: cur }
      : { value: cur, primary: cur, secondary: "" });
  }
  return window.listSelect({
    value: cur,
    options,
    onChange: cfg.onChange,
    allowCustom: true,
    customPlaceholder: cfg.customPlaceholder || "catalogID / バニラMaterial を直接入力",
    placeholder: cfg.placeholder || "アイテムを選択…",
    className: cfg.className
  });
};

/**
 * EntityType セレクト (2026-07-29)。
 *
 * モブ指定欄は各所で「datalist 付きの素の text 入力」だったため、候補は英字 EntityType の
 * 羅列で日本語では引けず、タイポも保存できてしまっていた。日本語名を主表示にした
 * listSelect へ統一する。保存値は従来どおり EntityType 名そのもの。
 *
 * @param {string} value 現在値
 * @param {function(string): void} onChange 確定時 (opts.onCommit があればそちらが優先)
 * @param {object} [opts] { allowCustom, customPlaceholder, placeholder, unknownNote,
 *                          onCommit: false を返すと選択を却下 (キー重複チェック等) }
 */
window.mobTypeSelect = function mobTypeSelect(value, onChange, opts) {
  const o = opts && typeof opts === "object" ? opts : {};
  const ids = Array.isArray(window.VANILLA_MOBS) ? window.VANILLA_MOBS : [];
  const ja = window.MOB_LABELS_JA || {};
  const options = ids.map((id) => ({ value: id, primary: ja[id] || id, secondary: id }));
  const cur = value == null ? "" : String(value);
  if (cur && !ids.includes(cur)) {
    options.unshift({ value: cur, primary: ja[cur] || cur, secondary: o.unknownNote || "候補外" });
  }
  const allowCustom = o.allowCustom !== false;
  if (allowCustom) options.push({ value: "__custom__", primary: "＋ 自由入力…" });
  const cfg = {
    value: cur,
    options,
    allowCustom,
    customPlaceholder: o.customPlaceholder || "EntityType名 (例: ZOMBIE)",
    placeholder: o.placeholder || "モブを選択…",
    className: o.className
  };
  if (typeof o.onCommit === "function") cfg.onCommit = o.onCommit;
  else cfg.onChange = onChange;
  return window.listSelect(cfg);
};

// 2026-07-29: 「値の配列をそのまま並べる」window.selectInput は削除した。
// primary に生の値を入れる作りなので、使うだけでセレクトが英字ID表示になり、
// 実際に特殊報酬・親ノード・パーティクル形状・型選択が全てID表示になっていた。
// 代わりに、日本語主表示を明示する window.listSelect({options:[{value,primary,secondary}]}) か、
// 語彙グループを持つ列挙なら window.selectLabeledInput を使うこと。

// 列挙値セレクト。表示は日本語ラベル、value は元の英字キーのまま (保存値は不変)。
// enumGroup は window.LABELS.ENUM_LABELS のグループ名 ("bind-type" 等)。
window.selectLabeledInput = function selectLabeledInput(value, options, enumGroup, onInput) {
  const label = window.LABELS ? window.LABELS.enumLabel : (g, v) => v;
  return window.listSelect({
    value: value == null ? "" : String(value),
    options: (options || []).map((opt) => {
      const ja = label(enumGroup, opt);
      return {
        value: opt,
        primary: ja && ja !== opt ? ja : opt,
        secondary: ja && ja !== opt ? opt : "",
        title: opt
      };
    }),
    onChange: onInput
  });
};

// 「?」ヘルプアイコン用の独自ツールチップ。
// ブラウザ標準の title 属性だと折返し位置・書式(キー行と説明の階層)を一切制御できず、
// labels.js には200文字超の説明も多いため長文が読めない塊になっていた(2026-07-27)。
// CSSで見た目を制御できる自前のポップオーバーに置き換える。1つだけ開く/外側click・Escで閉じる方式は
// colors.js の色ピッカーポップオーバーと同じ流儀。
let helpTooltipEl = null;
let helpTooltipAnchor = null;
let helpTooltipPinned = false;

function closeHelpTooltip() {
  if (helpTooltipAnchor) helpTooltipAnchor.setAttribute("aria-expanded", "false");
  if (helpTooltipEl && helpTooltipEl.parentNode) helpTooltipEl.parentNode.removeChild(helpTooltipEl);
  helpTooltipEl = null;
  helpTooltipAnchor = null;
  helpTooltipPinned = false;
  document.removeEventListener("mousedown", onHelpTooltipDocDown, true);
  document.removeEventListener("keydown", onHelpTooltipDocKey, true);
}
function onHelpTooltipDocDown(e) {
  if (helpTooltipEl && !helpTooltipEl.contains(e.target) && !(helpTooltipAnchor && helpTooltipAnchor.contains(e.target))) {
    closeHelpTooltip();
  }
}
function onHelpTooltipDocKey(e) {
  if (e.key === "Escape") closeHelpTooltip();
}

// pinned=true はクリック/フォーカスで開いた場合(hoverが外れても閉じない、明示操作でのみ閉じる)。
function openHelpTooltip(anchorEl, keyLabel, desc, pinned) {
  if (helpTooltipAnchor === anchorEl) {
    if (pinned) helpTooltipPinned = true;
    return;
  }
  closeHelpTooltip();
  const bodyChildren = [];
  if (keyLabel) bodyChildren.push(window.h("div", { class: "help-tooltip-key", text: keyLabel }));
  // desc 内の改行(\n)を行として反映する。テキストノードのみで組み立てる(innerHTML不使用)。
  String(desc).split("\n").forEach((line) => {
    bodyChildren.push(window.h("div", { class: "help-tooltip-line", text: line }));
  });
  const tip = window.h("div", { class: "help-tooltip", role: "tooltip" }, bodyChildren);
  document.body.appendChild(tip);

  if (!anchorEl.id) anchorEl.id = "help-icon-" + Math.random().toString(36).slice(2);
  tip.id = anchorEl.id + "-tip";
  anchorEl.setAttribute("aria-describedby", tip.id);
  anchorEl.setAttribute("aria-expanded", "true");

  // 画面端で切れないよう、右/下にはみ出す場合は左/上へ出方を反転する。
  const r = anchorEl.getBoundingClientRect();
  let left = r.left;
  const maxLeft = window.innerWidth - tip.offsetWidth - 8;
  if (left > maxLeft) left = Math.max(8, r.right - tip.offsetWidth);
  tip.style.left = Math.round(left) + "px";
  let top = r.bottom + 6;
  if (top + tip.offsetHeight > window.innerHeight - 8) top = Math.max(8, r.top - tip.offsetHeight - 6);
  tip.style.top = Math.round(top) + "px";

  helpTooltipEl = tip;
  helpTooltipAnchor = anchorEl;
  helpTooltipPinned = !!pinned;
  // 直後の同一 mousedown で即閉じないよう、次tickから外側clickの購読を始める。
  setTimeout(() => {
    if (helpTooltipEl === tip) {
      document.addEventListener("mousedown", onHelpTooltipDocDown, true);
      document.addEventListener("keydown", onHelpTooltipDocKey, true);
    }
  }, 0);
}

// 「?」ヘルプアイコン。説明文を独自ツールチップで表示する。desc が空なら null。
// ホバーで開く。クリック/フォーカスでも開閉できる(長文をゆっくり読める・キーボードでも到達できる)。
// opts.keyLabel を渡すと「キー: xxx」をツールチップ内の別行(バッジ的な先頭行)として表示する
// (fieldLabelEl から使用。呼び出し元インターフェース自体は helpIcon(desc) のまま変えない)。
window.helpIcon = function helpIcon(desc, opts) {
  if (!desc) return null;
  const keyLabel = opts && opts.keyLabel ? opts.keyLabel : "";
  const icon = window.h("span", {
    class: "help-icon", text: "?",
    tabindex: "0", role: "button",
    "aria-haspopup": "true", "aria-expanded": "false",
    // 【2026-08-01 実サーバ報告「?をホバーするとレガシーのHTMLの説明が出てくる」の修正】
    // title 属性のツールチップは**祖先へ遡って**表示される (自分に title が無い要素をホバーすると
    // 最も近い祖先の title がブラウザ標準の黄色いツールチップとして出る)。
    // editor には `sub-title` / `lore-stat-key` のように「説明文をまるごと title へ入れた」
    // 旧方式の親要素がまだ多数あり、その中に置いた「?」をホバーすると
    //   独自ツールチップ(整形済み) + ブラウザ標準ツールチップ(未整形の生テキスト)
    // が二重に出ていた。生テキストには MiniMessage の `<gray><icon><name>` 等が含まれるので
    // 「HTML の断片が出てくる」ように見える。
    // 空文字の title は「この要素には注釈が無い」の明示宣言で、**祖先の title を打ち消す**
    // (HTML 仕様。空でない title を入れる = 標準ツールチップを使う、ではない点に注意)。
    title: ""
  });
  icon.addEventListener("mouseenter", () => {
    if (helpTooltipAnchor !== icon) openHelpTooltip(icon, keyLabel, desc, false);
  });
  icon.addEventListener("mouseleave", () => {
    if (helpTooltipAnchor === icon && !helpTooltipPinned) closeHelpTooltip();
  });
  icon.addEventListener("focus", () => {
    if (helpTooltipAnchor !== icon) openHelpTooltip(icon, keyLabel, desc, false);
  });
  icon.addEventListener("blur", () => {
    if (helpTooltipAnchor === icon && !helpTooltipPinned) closeHelpTooltip();
  });
  icon.addEventListener("click", (e) => {
    e.stopPropagation();
    if (helpTooltipAnchor === icon && helpTooltipPinned) closeHelpTooltip();
    else openHelpTooltip(icon, keyLabel, desc, true);
  });
  icon.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      icon.click();
    }
  });
  return icon;
};

// フィールドの日本語ラベル要素。英字キーは小さく併記し、説明は「?」の独自ツールチップへ回す。
// FIELD_LABELS に説明があればラベル横に「?」も付ける。
// opts.label / opts.desc を渡すとグローバル辞書より優先して使う(呼び出し元の文脈固有ラベル)。
window.fieldLabelEl = function fieldLabelEl(key, opts) {
  const options = opts || {};
  const L = window.LABELS;
  const ja = options.label != null ? options.label : (L ? L.fieldLabel(key) : key);
  const desc = options.desc != null ? options.desc : (L ? L.fieldDesc(key) : "");
  const keyLabel = `キー: ${key}`;
  const children = [window.h("span", { class: "form-label-ja", text: ja + (options.required ? " *" : ""), title: keyLabel })];
  const help = window.helpIcon(desc, { keyLabel: options.hideKey ? "" : keyLabel });
  if (help) children.push(help);
  // 英字キーを小さく併記 (ユーザがYAMLキーを見失わないように)
  if (!options.hideKey && ja !== key) children.push(window.h("span", { class: "form-label-key", text: key, title: "YAMLキー" }));
  return window.h("span", { class: "form-label with-ja" }, children);
};

// セクション見出し (`.sub-title`)。説明文は「?」の独自ツールチップへ回す。
//
// 2026-08-01: それまでは `h("div", { class: "sub-title", text, title: desc })` と書いて
// **説明文をブラウザ標準の title ツールチップ**で出していた (2026-07-27 に helpIcon から
// 追い出したはずの旧方式が、見出し側にだけ残っていた)。折返しも書式も制御できないうえ、
// 見出しの中に「?」がある画面では標準ツールチップと独自ツールチップが二重に出る。
// 見出しの説明は必ずこのヘルパー経由にして、`.sub-title` に title を書かないこと。
//
// @param {string} text 見出し文字列
// @param {string} [desc] 説明文 (あれば「?」を付ける。空なら見出しだけ)
// @param {Array} [extraChildren] 見出し行に並べる追加要素
window.subTitleEl = function subTitleEl(text, desc, extraChildren) {
  const children = [window.h("span", { class: "sub-title-text", text: text == null ? "" : String(text) })];
  const help = window.helpIcon(desc);
  if (help) children.push(help);
  if (Array.isArray(extraChildren)) {
    for (const c of extraChildren) if (c) children.push(c);
  }
  return window.h("div", { class: "sub-title" }, children);
};

// ステータスキー入力の横に出す日本語ヒント。入力変更に追従して更新する。
window.statHintEl = function statHintEl(initialKey) {
  const L = window.LABELS;
  const span = window.h("span", { class: "stat-hint" });
  span.update = (key) => {
    const ja = L ? L.statLabel(key) : key;
    span.textContent = ja && ja !== key ? ja : "";
    span.title = ja && ja !== key ? `${ja} (${key})` : "";
  };
  span.update(initialKey);
  return span;
};

// Material入力の横に出す日本語ヒント。入力変更に追従して更新する。
window.materialHintEl = function materialHintEl(initialMat) {
  const L = window.LABELS;
  const span = window.h("span", { class: "mat-hint" });
  span.update = (mat) => {
    const m = mat == null ? "" : String(mat);
    if (/^custom:/i.test(m)) {
      const labels = window.CUSTOM_ITEM_LABELS || {};
      const ja = labels[m] || labels[m.toLowerCase()] || "";
      const id = m.slice(m.indexOf(":") + 1);
      span.textContent = ja || (id ? `カスタム:${id}` : "");
      span.title = m;
      return;
    }
    const ja = L ? L.materialLabel(m) : "";
    span.textContent = ja || "";
    span.title = ja ? `${ja} (${m})` : "";
  };
  span.update(initialMat);
  return span;
};

// datalist を id で共有生成する (document body 直下に1回だけ作る)。EntityType入力等、
// ネイティブ<input list>のサジェストを使う軽量フィールドで使う共通ヘルパー。
window.ensureDatalist = function ensureDatalist(id, values) {
  const h = window.h;
  let dl = document.getElementById(id);
  if (!dl) { dl = h("datalist", { id }); document.body.appendChild(dl); }
  if (dl._filled) return id;
  dl._filled = true;
  for (const v of values) dl.appendChild(h("option", { value: v }));
  return id;
};

window.checkboxInput = function checkboxInput(value, onInput) {
  return window.h("input", {
    type: "checkbox",
    checked: Boolean(value),
    onchange: (e) => onInput(e.target.checked)
  });
};

// 数値文字列を数値へ寄せる。整数判定用。
window.isIntStr = function isIntStr(v) {
  return typeof v === "number" && Number.isInteger(v);
};

// エントリ単位の自由記述「タグ」を localStorage に保持するだけの表示補助ストア。
// catalog.yml / item-stats.yml のスキーマには一切書き込まない (保存対象外・ブラウザローカルのみ)。
// storageKey ごとに { entryId: "tag1, tag2" } の形で保持する。
window.createTagStore = function createTagStore(storageKey) {
  let map = {};
  try { map = JSON.parse(localStorage.getItem(storageKey) || "{}") || {}; } catch (_) { map = {}; }
  function persist() {
    try { localStorage.setItem(storageKey, JSON.stringify(map)); } catch (_) { /* localStorage不可でも表示は続行 */ }
  }
  return {
    get: (id) => map[id] || "",
    set: (id, value) => { if (value) map[id] = value; else delete map[id]; persist(); },
    rename: (oldId, newId) => { if (oldId === newId) return; if (map[oldId] !== undefined) { map[newId] = map[oldId]; delete map[oldId]; persist(); } },
    remove: (id) => { if (map[id] !== undefined) { delete map[id]; persist(); } },
    matches: (id, query) => {
      const q = String(query || "").trim().toLowerCase();
      if (!q) return true;
      return (map[id] || "").toLowerCase().includes(q);
    }
  };
};

// 折りたたみ可能なエントリカード。head は常時表示、body は ▶/▼ で開閉する。
// item定義系フォーム(item-stats / catalog / materials / threads / spellbooks / 儀式 等)で
// skilltree と同じ折りたたみUXを共有するためのヘルパー。
//   headChildren : ヘッダ行の子要素(配列/単体)。先頭にトグルボタンを自動で差し込む。
//   bodyChildren : 本体の子要素。
//   opts.expanded: 初期の開閉状態 (既定 false=折りたたみ)。
//   opts.onToggle(open): 開閉時コールバック (呼び出し側が Set 等で状態を保持する用)。
// 再描画をまたいで開閉状態を保つには、呼び出し側で id 集合を持ち、
//   expanded: set.has(id), onToggle: (o) => o ? set.add(id) : set.delete(id)
// のように渡す。
window.collapsibleCard = function collapsibleCard(headChildren, bodyChildren, opts) {
  const options = opts || {};
  let open = !!options.expanded;
  let suppressHeaderClick = false;
  const body = window.h("div", { class: "entry-body" }, bodyChildren);
  body.style.display = open ? "" : "none";
  const toggle = window.h("button", {
    class: "btn-small collapse-toggle", type: "button",
    text: open ? "▼" : "▶", title: "詳細の折りたたみ切替"
  });
  const card = window.h("div", { class: "entry-card" }, []);
  function syncCollapsedClass() {
    card.classList.toggle("is-collapsed", !open);
    card.classList.toggle("is-expanded", open);
  }
  function syncDraggable() {
    const canDrag = !open && options.dragId != null && options.dragId !== "";
    card.draggable = canDrag;
    card.classList.toggle("drag-reorderable", canDrag);
  }
  function setOpen(nextOpen) {
    open = !!nextOpen;
    body.style.display = open ? "" : "none";
    toggle.textContent = open ? "▼" : "▶";
    syncCollapsedClass();
    syncDraggable();
    if (typeof options.onToggle === "function") options.onToggle(open);
  }
  toggle.addEventListener("click", () => {
    setOpen(!open);
  });
  const heads = Array.isArray(headChildren) ? headChildren : [headChildren];
  const head = window.h("div", { class: "entry-head" }, [toggle].concat(heads));
  head.addEventListener("click", (event) => {
    // 折りたたみ中のみ、ヘッダの空き領域・サマリーをクリックして展開する。
    // 入力部品や各操作ボタンの本来の動作は奪わない。
    if (open || suppressHeaderClick) return;
    const interactive = event.target.closest(
      "button, input, select, textarea, a, label, [role='button'], [contenteditable='true']"
    );
    if (interactive) return;
    setOpen(true);
  });
  card.addEventListener("dragstart", () => {
    suppressHeaderClick = true;
  });
  card.addEventListener("dragend", () => {
    // dragend 直後に発火する click でカードが誤展開しないよう、次のタスクまで抑止する。
    setTimeout(() => { suppressHeaderClick = false; }, 0);
  });
  card.appendChild(head);
  card.appendChild(body);
  if (options.dragId != null && options.dragId !== "") {
    card.dataset.dragId = String(options.dragId);
  }
  syncCollapsedClass();
  syncDraggable();
  return card;
};

/** MiniMessage / legacy 色コードを除いたプレーン表示名。 */
window.stripDisplayNamePlain = function stripDisplayNamePlain(raw) {
  if (raw == null || raw === "") return "";
  let s = String(raw);
  s = s.replace(/<[^>]+>/g, "");
  s = s.replace(/§[0-9a-fk-or]/gi, "");
  s = s.replace(/&[0-9a-fk-or]/gi, "");
  return s.replace(/\s+/g, " ").trim();
};

/**
 * カタログ候補セレクト。candidates: [{ id, displayName, material, cmd, tab }]
 *
 * 2026-07-29: materialInput と同じく listSelect ベースへ移行し、「アイテムを引数に取る欄」の
 * DOM を統一する(以前はここだけ独自のテキスト入力+サジェストだった)。
 * 主表示 = カタログの表示名(日本語)、副表示 = 薄字の catalogID。確定後は主表示のみ。
 *
 * @param {string} valueId 現在の catalogID
 * @param {Array} candidates 候補
 * @param {function(object|null): void} onPick 確定時。候補オブジェクト(未選択なら null)を渡す
 * @param {object} [opts] { placeholder, className, disabled, filterCandidate }
 */
window.catalogItemSuggest = function catalogItemSuggest(valueId, candidates, onPick, opts) {
  const options = opts && typeof opts === "object" ? opts : {};
  const all = Array.isArray(candidates) ? candidates.slice() : [];
  let currentId = valueId == null ? "" : String(valueId);

  function findById(id) {
    return all.find((c) => c && c.id === id) || null;
  }

  function optionOf(c) {
    const dn = window.stripDisplayNamePlain(c.displayName || "") || c.id;
    // 表示名を持たない候補は、せめて材質(+CMD)を副表示にして見分けられるようにする。
    let secondary = c.id;
    if (dn === c.id) {
      secondary = c.material
        ? (c.cmd != null && c.cmd !== "" ? `${c.material}#${c.cmd}` : String(c.material))
        : "";
    }
    return { value: c.id, primary: dn, secondary };
  }

  function resolveOptions() {
    // テキスト入力だった頃は「空にして Enter」で解除できたので、その経路を選択肢として残す。
    const out = [{ value: "", primary: "(未選択)", title: "選択を解除します" }];
    for (const c of all) {
      if (!c || !c.id) continue;
      if (typeof options.filterCandidate === "function" && !options.filterCandidate(c)) continue;
      out.push(optionOf(c));
    }
    // 候補に無い ID(絞り込みで除外された・カタログから消えた)でも表示が消えないようにする。
    if (currentId && !out.some((o) => o.value === currentId)) {
      const known = findById(currentId);
      out.splice(1, 0, known ? optionOf(known) : { value: currentId, primary: currentId, secondary: "候補外" });
    }
    return out;
  }

  const el = window.listSelect({
    value: currentId,
    options: resolveOptions,
    disabled: !!options.disabled,
    placeholder: options.placeholder || "カタログアイテムを選択…",
    filterPlaceholder: "絞り込み (表示名 / カタログID)",
    className: "catalog-id-suggest" + (options.className ? " " + options.className : ""),
    onChange: (nv) => {
      currentId = nv == null ? "" : String(nv);
      if (typeof onPick === "function") onPick(findById(currentId));
    }
  });

  Object.defineProperty(el, "value", {
    get() { return currentId; },
    set(v) {
      currentId = v == null ? "" : String(v);
      el.setValue(currentId);
    },
    configurable: true
  });

  return el;
};

/**
 * HTML5 drag-and-drop reorder for collapsed entry cards in a list container.
 * Cards must be draggable only when collapsed (collapsibleCard sets this when dragId is passed).
 */
window.bindCollapsedCardReorder = function bindCollapsedCardReorder(listBox, opts) {
  const options = opts || {};
  const cardSelector = options.cardSelector || ":scope > .entry-card";
  const getId = options.getId || ((el) => el.dataset.dragId || el.dataset.entryRawKey || "");
  let dragId = null;
  let dropTarget = null;
  let dropBefore = true;

  function clearDropIndicators() {
    listBox.querySelectorAll(".entry-card").forEach((c) => {
      c.classList.remove("drag-over-before", "drag-over-after", "dragging");
    });
  }

  function visibleCards() {
    return [...listBox.querySelectorAll(cardSelector)].filter((c) => c.style.display !== "none");
  }

  function orderedIdsFromDom() {
    return visibleCards().map(getId).filter(Boolean);
  }

  listBox.addEventListener("dragstart", (e) => {
    if (e.target.closest("input, select, textarea, button, label")) {
      e.preventDefault();
      return;
    }
    const card = e.target.closest(".entry-card");
    if (!card || !listBox.contains(card) || !card.draggable) return;
    const body = card.querySelector(".entry-body");
    if (body && body.style.display !== "none") {
      e.preventDefault();
      return;
    }
    dragId = getId(card);
    if (!dragId) {
      e.preventDefault();
      return;
    }
    card.classList.add("dragging");
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", dragId);
  });

  listBox.addEventListener("dragend", () => {
    dragId = null;
    dropTarget = null;
    clearDropIndicators();
  });

  listBox.addEventListener("dragover", (e) => {
    if (!dragId) return;
    e.preventDefault();
    listBox.querySelectorAll(".entry-card").forEach((c) => {
      c.classList.remove("drag-over-before", "drag-over-after");
    });
    const cards = visibleCards();
    let nearest = null;
    let nearestDist = Infinity;
    for (const card of cards) {
      const id = getId(card);
      if (!id || id === dragId) continue;
      const rect = card.getBoundingClientRect();
      const midY = rect.top + rect.height / 2;
      const dist = Math.abs(e.clientY - midY);
      if (dist < nearestDist) {
        nearestDist = dist;
        nearest = card;
      }
    }
    if (!nearest) {
      dropTarget = null;
      return;
    }
    const rect = nearest.getBoundingClientRect();
    dropBefore = e.clientY < rect.top + rect.height / 2;
    dropTarget = nearest;
    nearest.classList.add(dropBefore ? "drag-over-before" : "drag-over-after");
    e.dataTransfer.dropEffect = "move";
  });

  listBox.addEventListener("drop", (e) => {
    e.preventDefault();
    if (!dragId || !dropTarget) return;
    const targetId = getId(dropTarget);
    const ids = orderedIdsFromDom().filter((id) => id !== dragId);
    let idx = ids.indexOf(targetId);
    if (idx < 0) idx = ids.length;
    else if (!dropBefore) idx += 1;
    ids.splice(idx, 0, dragId);
    if (typeof options.onReorder === "function") options.onReorder(ids);
    dragId = null;
    dropTarget = null;
    clearDropIndicators();
  });
};
