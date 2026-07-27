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

// Material サジェスト入力 (部分一致・日本語/英字)。
// 候補は window.MATERIALS (1.21.11 カタログ。起動時に /api/material-labels で差し替え)。
// opts.allowCustom: true のとき "custom:" 入力でカタログ等の追加アイテム候補を出す。
// listId は後方互換のため受け取るが未使用。
// 戻り値は span.material-suggest。互換のため .value と change リスナをサポートする。
window.materialInput = function materialInput(value, listId, onInput, opts) {
  const h = window.h;
  const options = opts && typeof opts === "object" ? opts : {};
  const allowCustom = !!options.allowCustom;
  const MAX_SUGGEST = 60;
  let current = value == null ? "" : String(value);
  let activeIndex = -1;
  let open = false;

  const wrap = h("span", { class: "material-suggest" + (options.className ? " " + options.className : "") });
  const input = h("input", {
    class: "field-input material-suggest-input",
    type: "text",
    spellcheck: "false",
    autocomplete: "off",
    placeholder: allowCustom
      ? "例: 表示名 / custom:id / IRON_INGOT"
      : "例: 銅の剣 / copper / SPEAR / オウムガイ"
  });
  // body 直下に出して entry-card の overflow にクリップされないようにする。
  const list = h("ul", {
    class: "material-suggest-list",
    role: "listbox",
    style: "display:none;"
  });

  const changeListeners = [];

  function isCustomKey(key) {
    return /^custom:/i.test(String(key || ""));
  }

  function customLabelOf(key) {
    const labels = window.CUSTOM_ITEM_LABELS && typeof window.CUSTOM_ITEM_LABELS === "object"
      ? window.CUSTOM_ITEM_LABELS : {};
    return labels[key] || labels[String(key).toLowerCase()] || "";
  }

  function optionParts(key) {
    if (!key) return { primary: "", secondary: "" };
    if (isCustomKey(key)) {
      const id = String(key).slice(String(key).indexOf(":") + 1);
      const label = customLabelOf(key);
      if (label && label !== id) {
        return { primary: label, secondary: key };
      }
      return { primary: `カスタム: ${id}`, secondary: key };
    }
    const ja = (window.LABELS && typeof window.LABELS.materialLabel === "function")
      ? window.LABELS.materialLabel(key) : (window.MATERIAL_LABELS && window.MATERIAL_LABELS[key]) || "";
    return {
      primary: ja || key,
      secondary: ja ? key : ""
    };
  }

  function optionText(key) {
    const parts = optionParts(key);
    if (!parts.primary) return "";
    return parts.secondary ? `${parts.primary} (${parts.secondary})` : parts.primary;
  }

  function normalizeQuery(q) {
    return String(q || "").trim().toLowerCase().replace(/\s+/g, "");
  }

  function materialCatalog() {
    return Array.isArray(window.MATERIALS) ? window.MATERIALS : [];
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

  function scoreMatch(key, q, jaText) {
    if (!q) return 0;
    const id = key.toLowerCase();
    const ja = String(jaText || "").toLowerCase().replace(/\s+/g, "");
    const idCompact = id.replace(/_/g, "").replace(/^custom:/, "");
    if (id === q || ja === q || idCompact === q) return 100;
    if (id.startsWith(q) || idCompact.startsWith(q) || ja.startsWith(q)) return 80;
    if (id.includes(q) || idCompact.includes(q) || ja.includes(q)) return 50;
    const tokens = q.split(/[_\s:/]+/).filter(Boolean);
    if (tokens.length > 1 && tokens.every((t) => id.includes(t) || ja.includes(t) || idCompact.includes(t))) return 40;
    return 0;
  }

  function wantsCustomSuggest(rawQuery) {
    if (!allowCustom) return false;
    const lower = String(rawQuery || "").trim().toLowerCase();
    return lower === "c" || lower === "cu" || lower === "cus" || lower === "cust"
      || lower === "custo" || lower === "custom" || lower.startsWith("custom:");
  }

  function filterCandidates(rawQuery) {
    const raw = String(rawQuery || "");
    if (wantsCustomSuggest(raw)) {
      const after = raw.toLowerCase().startsWith("custom:") ? raw.slice(raw.indexOf(":") + 1) : "";
      const q = normalizeQuery(after);
      const customs = customCatalog();
      const scored = [];
      for (const k of customs) {
        const id = k.slice("custom:".length);
        const label = customLabelOf(k);
        const s = q ? Math.max(scoreMatch(k, q, id), scoreMatch(k, q, label)) : 10;
        if (!q || s > 0) scored.push({ k, s: s || 10 });
      }
      scored.sort((a, b) => b.s - a.s || a.k.localeCompare(b.k));
      return scored.slice(0, MAX_SUGGEST).map((x) => x.k);
    }

    const q = normalizeQuery(raw);
    const keys = materialCatalog();
    const customs = allowCustom ? customCatalog() : [];
    if (!q) {
      const seed = [];
      if (current) {
        if (isCustomKey(current) && allowCustom) seed.push(current);
        else if (keys.includes(current)) seed.push(current);
      }
      // カタログ/素材タブの custom: を空クエリでも先頭付近に出す（儀式・レシピで探しやすくする）
      for (const k of customs) {
        if (seed.length >= 24) break;
        if (!seed.includes(k)) seed.push(k);
      }
      for (const k of keys) {
        if (seed.length >= 28) break;
        if (seed.includes(k)) continue;
        if (/_(SWORD|AXE|PICKAXE|SHOVEL|HOE|SPEAR|HELMET|CHESTPLATE|LEGGINGS|BOOTS|NAUTILUS_ARMOR)$/.test(k)
            || k === "BOW" || k === "CROSSBOW" || k === "TRIDENT" || k === "MACE"
            || k === "IRON_INGOT" || k === "GOLD_INGOT" || k === "DIAMOND" || k === "EMERALD"
            || k === "STICK" || k === "STRING" || k === "LEATHER" || k === "PAPER") {
          seed.push(k);
        }
      }
      return seed;
    }
    const scored = [];
    for (const k of keys) {
      const ja = (window.MATERIAL_LABELS && window.MATERIAL_LABELS[k]) || "";
      const s = scoreMatch(k, q, ja);
      if (s > 0) scored.push({ k, s });
    }
    // allowCustom: 表示名・id でカタログ素材を通常検索に混ぜる（custom: 前置不要）
    if (allowCustom) {
      for (const k of customs) {
        const id = k.slice("custom:".length);
        const label = customLabelOf(k);
        const s = Math.max(scoreMatch(k, q, id), scoreMatch(k, q, label));
        if (s > 0) scored.push({ k, s: Math.max(s, 55) });
      }
    }
    scored.sort((a, b) => b.s - a.s || a.k.localeCompare(b.k));
    const seen = new Set();
    const out = [];
    for (const x of scored) {
      if (seen.has(x.k)) continue;
      seen.add(x.k);
      out.push(x.k);
      if (out.length >= MAX_SUGGEST) break;
    }
    return out;
  }

  function positionList() {
    const rect = input.getBoundingClientRect();
    const gutter = 8;
    const preferredW = Math.max(rect.width, 280);
    const maxW = Math.max(180, window.innerWidth - gutter * 2);
    const width = Math.min(preferredW, maxW);
    let left = Math.min(Math.max(gutter, rect.left), window.innerWidth - width - gutter);
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
  }

  function renderList(candidates) {
    list.innerHTML = "";
    if (!list.parentNode) document.body.appendChild(list);
    if (!candidates.length) {
      list.appendChild(h("li", {
        class: "material-suggest-empty",
        text: allowCustom
          ? "一致なし — Enter で確定 (custom:id 可)"
          : "一致なし — Enter で入力値を確定"
      }));
    } else {
      candidates.forEach((key, idx) => {
        const parts = optionParts(key);
        const children = [
          h("span", { class: "material-suggest-primary", text: parts.primary })
        ];
        if (parts.secondary) {
          children.push(h("span", { class: "material-suggest-secondary", text: parts.secondary }));
        }
        const li = h("li", {
          class: "material-suggest-item" + (key === current ? " is-current" : ""),
          role: "option",
          "data-key": key,
          title: optionText(key)
        }, children);
        li.addEventListener("mousedown", (e) => {
          e.preventDefault();
          pick(key);
        });
        if (idx === activeIndex) li.classList.add("is-active");
        list.appendChild(li);
      });
    }
    positionList();
    list.style.display = "";
    open = true;
  }

  function openSuggest(query) {
    renderList(filterCandidates(query));
    // 候補ロード未完了なら完了後に再描画（glyphs 等で初回 custom: 入力が空になるのを防ぐ）
    if (allowCustom && !window._customItemCandidatesLoaded
        && window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
      window.RECIPES_UI.ensureCustomDatalist().then(() => {
        if (document.activeElement === input && open) {
          renderList(filterCandidates(input.value));
        }
      }).catch(() => {});
    }
  }

  function syncInputDisplay() {
    input.value = current ? optionText(current) : "";
    input.dataset.materialId = current || "";
  }

  function normalizeCommitValue(nv) {
    const raw = nv == null ? "" : String(nv).trim();
    if (!raw) return "";
    if (allowCustom && /^custom:/i.test(raw)) {
      const id = raw.slice(raw.indexOf(":") + 1).trim();
      return id ? ("custom:" + id) : "custom:";
    }
    return raw.toUpperCase().replace(/[^A-Z0-9_]/g, "");
  }

  function commit(nv) {
    const next = normalizeCommitValue(nv);
    current = next;
    syncInputDisplay();
    if (typeof onInput === "function") onInput(next);
    const ev = { type: "change", target: { value: next }, currentTarget: wrap };
    for (const fn of changeListeners) {
      try { fn(ev); } catch (_) { /* listener error は他リスナを止めない */ }
    }
  }

  function pick(key) {
    commit(key);
    closeList();
    input.blur();
  }

  function commitTyped() {
    const raw = input.value.trim();
    // 空入力はクリア確定（seed 候補の先頭を誤ピックしない）
    if (!raw) {
      commit("");
      closeList();
      return;
    }
    const parenCustom = raw.match(/\((custom:[^)]+)\)\s*$/i);
    if (parenCustom) {
      pick(parenCustom[1]);
      return;
    }
    const paren = raw.match(/\(([A-Z][A-Z0-9_]*)\)\s*$/);
    if (paren) {
      pick(paren[1]);
      return;
    }
    if (allowCustom && /^custom:/i.test(raw)) {
      pick(raw);
      return;
    }
    const asId = raw.toUpperCase().replace(/[^A-Z0-9_]/g, "");
    if (!asId) {
      // 日本語のみ等: 一致がちょうど1件のときだけ確定。複数は一覧を開いたままにする。
      const hits = filterCandidates(raw);
      if (hits.length === 1) {
        pick(hits[0]);
        return;
      }
      if (hits.length > 1) {
        openSuggest(raw);
        activeIndex = 0;
        const items = list.querySelectorAll(".material-suggest-item");
        items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
        return;
      }
      syncInputDisplay();
      closeList();
      return;
    }
    const exact = materialCatalog().find((k) => k === asId);
    if (exact) {
      pick(exact);
      return;
    }
    const hits = filterCandidates(raw);
    if (hits.length === 1) {
      pick(hits[0]);
      return;
    }
    commit(asId);
    closeList();
  }

  function onScrollOrResize() {
    if (open) positionList();
  }

  input.addEventListener("focus", () => {
    input.value = current || "";
    input.select();
    openSuggest(current || "");
  });

  input.addEventListener("input", () => {
    openSuggest(input.value);
    activeIndex = 0;
    const items = list.querySelectorAll(".material-suggest-item");
    items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
  });

  input.addEventListener("keydown", (e) => {
    const items = [...list.querySelectorAll(".material-suggest-item")];
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) openSuggest(input.value);
      else {
        activeIndex = Math.min(items.length - 1, activeIndex + 1);
        items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
        if (items[activeIndex]) items[activeIndex].scrollIntoView({ block: "nearest" });
      }
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      activeIndex = Math.max(0, activeIndex - 1);
      items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
      if (items[activeIndex]) items[activeIndex].scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (open && activeIndex >= 0 && items[activeIndex]) {
        pick(items[activeIndex].getAttribute("data-key"));
      } else {
        commitTyped();
      }
    } else if (e.key === "Escape") {
      e.preventDefault();
      syncInputDisplay();
      closeList();
      input.blur();
    }
  });

  input.addEventListener("blur", () => {
    setTimeout(() => {
      if (!wrap.contains(document.activeElement) && !list.contains(document.activeElement)) {
        if (open) commitTyped();
        else syncInputDisplay();
        closeList();
      }
    }, 120);
  });

  window.addEventListener("scroll", onScrollOrResize, true);
  window.addEventListener("resize", onScrollOrResize);

  Object.defineProperty(wrap, "value", {
    get() { return current; },
    set(v) {
      current = v == null ? "" : String(v);
      syncInputDisplay();
    },
    configurable: true
  });

  const nativeAdd = wrap.addEventListener.bind(wrap);
  wrap.addEventListener = function (type, listener, options) {
    if (type === "change" && typeof listener === "function") {
      changeListeners.push(listener);
      return;
    }
    return nativeAdd(type, listener, options);
  };

  wrap._rebuildSuggestIndex = function () {
    syncInputDisplay();
  };
  wrap._rebuildMaterialOptions = wrap._rebuildSuggestIndex;

  wrap.appendChild(input);
  syncInputDisplay();
  return wrap;
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

  function resolveOptions() {
    const raw = typeof cfg.options === "function" ? cfg.options() : (cfg.options || []);
    return Array.isArray(raw) ? raw.slice() : [];
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

  function syncTrigger() {
    const options = resolveOptions();
    const opt = findOption(current, options);
    if (opt) {
      trigger.textContent = optionLabel(opt);
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
    const next = nv == null ? "" : String(nv);
    if (typeof cfg.onCommit === "function") {
      if (cfg.onCommit(next) === false) {
        syncTrigger();
        return false;
      }
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
    const options = resolveOptions();
    list.innerHTML = "";
    if (!list.parentNode) document.body.appendChild(list);
    if (!options.length) {
      list.appendChild(h("li", {
        class: "material-suggest-empty",
        text: "選択肢がありません"
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
    activeIndex = -1;
    const options = resolveOptions();
    const idx = options.findIndex((o) => String(o.value) === String(current));
    activeIndex = idx >= 0 ? idx : 0;
    renderList();
    trigger.focus();
  }

  trigger.addEventListener("click", (e) => {
    e.preventDefault();
    if (open) closeList();
    else openList();
  });

  trigger.addEventListener("keydown", (e) => {
    if (disabled) return;
    const items = [...list.querySelectorAll(".material-suggest-item")];
    if (e.key === "ArrowDown" || e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      if (!open) {
        openList();
        return;
      }
      if (e.key === "Enter" || e.key === " ") {
        if (activeIndex >= 0 && items[activeIndex]) {
          pick(items[activeIndex].getAttribute("data-value"));
        }
        return;
      }
      activeIndex = Math.min(items.length - 1, activeIndex + 1);
      items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
      if (items[activeIndex]) items[activeIndex].scrollIntoView({ block: "nearest" });
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) return;
      activeIndex = Math.max(0, activeIndex - 1);
      items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
      if (items[activeIndex]) items[activeIndex].scrollIntoView({ block: "nearest" });
    } else if (e.key === "Escape") {
      if (open) {
        e.preventDefault();
        closeList();
      }
    }
  });

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

window.selectInput = function selectInput(value, options, onInput) {
  return window.listSelect({
    value: value == null ? "" : String(value),
    options: (options || []).map((opt) => ({
      value: String(opt),
      primary: String(opt)
    })),
    onChange: onInput
  });
};

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

// 「?」ヘルプアイコン。説明文をツールチップ(title)で表示する。desc が空なら null。
window.helpIcon = function helpIcon(desc) {
  if (!desc) return null;
  return window.h("span", { class: "help-icon", title: desc, text: "?" });
};

// フィールドの日本語ラベル要素。英字キーと説明をツールチップに併記する。
// FIELD_LABELS に説明があればラベル横に「?」も付ける。
// opts.label / opts.desc を渡すとグローバル辞書より優先して使う(呼び出し元の文脈固有ラベル)。
window.fieldLabelEl = function fieldLabelEl(key, opts) {
  const options = opts || {};
  const L = window.LABELS;
  const ja = options.label != null ? options.label : (L ? L.fieldLabel(key) : key);
  const desc = options.desc != null ? options.desc : (L ? L.fieldDesc(key) : "");
  const tip = options.hideKey
    ? (desc || "")
    : (desc ? `キー: ${key}\n${desc}` : `キー: ${key}`);
  const children = [window.h("span", { class: "form-label-ja", text: ja + (options.required ? " *" : ""), title: tip })];
  const help = window.helpIcon(desc);
  if (help) children.push(help);
  // 英字キーを小さく併記 (ユーザがYAMLキーを見失わないように)
  if (!options.hideKey && ja !== key) children.push(window.h("span", { class: "form-label-key", text: key, title: "YAMLキー" }));
  return window.h("span", { class: "form-label with-ja" }, children);
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
 * カタログ候補サジェスト。candidates: [{ id, displayName, material, cmd, tab }]
 */
window.catalogItemSuggest = function catalogItemSuggest(valueId, candidates, onPick, opts) {
  const h = window.h;
  const options = opts && typeof opts === "object" ? opts : {};
  const MAX = 40;
  let currentId = valueId == null ? "" : String(valueId);
  let activeIndex = -1;
  let open = false;
  const list = Array.isArray(candidates) ? candidates.slice() : [];

  const wrap = h("span", { class: "material-suggest catalog-id-suggest" + (options.className ? " " + options.className : "") });
  const input = h("input", {
    class: "field-input material-suggest-input",
    type: "text",
    spellcheck: "false",
    autocomplete: "off",
    placeholder: options.placeholder || "カタログID / 表示名で検索",
    disabled: !!options.disabled
  });
  const drop = h("ul", { class: "material-suggest-list", role: "listbox", style: "display:none;" });

  function findById(id) {
    return list.find((c) => c && c.id === id) || null;
  }

  function labelOf(c) {
    if (!c) return "";
    const dn = window.stripDisplayNamePlain(c.displayName || "") || c.id;
    return dn === c.id ? c.id : `${dn} (${c.id})`;
  }

  function syncDisplay() {
    const c = findById(currentId);
    input.value = c ? labelOf(c) : (currentId || "");
  }

  function filter(qRaw) {
    const q = String(qRaw || "").trim().toLowerCase().replace(/\s+/g, "");
    const scored = [];
    for (const c of list) {
      if (!c || !c.id) continue;
      if (typeof options.filterCandidate === "function" && !options.filterCandidate(c)) continue;
      const id = String(c.id).toLowerCase();
      const dn = window.stripDisplayNamePlain(c.displayName || "").toLowerCase().replace(/\s+/g, "");
      if (!q) {
        scored.push({ c, s: 1 });
        continue;
      }
      let s = 0;
      if (id === q || dn === q) s = 100;
      else if (id.startsWith(q) || dn.startsWith(q)) s = 80;
      else if (id.includes(q) || dn.includes(q)) s = 50;
      if (s > 0) scored.push({ c, s });
    }
    scored.sort((a, b) => b.s - a.s || a.c.id.localeCompare(b.c.id));
    return scored.slice(0, MAX).map((x) => x.c);
  }

  function positionList() {
    const rect = input.getBoundingClientRect();
    const gutter = 8;
    const preferredW = Math.max(rect.width, 280);
    const maxW = Math.max(180, window.innerWidth - gutter * 2);
    const width = Math.min(preferredW, maxW);
    const left = Math.min(Math.max(gutter, rect.left), window.innerWidth - width - gutter);
    const spaceBelow = window.innerHeight - rect.bottom - gutter;
    const spaceAbove = rect.top - gutter;
    const preferBelow = spaceBelow >= 140 || spaceBelow >= spaceAbove;
    const maxH = Math.min(320, Math.max(120, preferBelow ? spaceBelow : spaceAbove));
    drop.style.position = "fixed";
    drop.style.left = left + "px";
    drop.style.width = width + "px";
    drop.style.maxWidth = maxW + "px";
    drop.style.maxHeight = maxH + "px";
    drop.style.zIndex = "10000";
    drop.style.overflowX = "hidden";
    drop.style.overflowY = "auto";
    if (preferBelow) {
      drop.style.top = (rect.bottom + 4) + "px";
      drop.style.bottom = "auto";
    } else {
      drop.style.top = "auto";
      drop.style.bottom = (window.innerHeight - rect.top + 4) + "px";
    }
  }

  function closeList() {
    open = false;
    activeIndex = -1;
    drop.style.display = "none";
    drop.innerHTML = "";
    if (drop.parentNode) drop.parentNode.removeChild(drop);
  }

  function pick(c) {
    currentId = c ? c.id : "";
    syncDisplay();
    closeList();
    if (typeof onPick === "function") onPick(c);
    input.blur();
  }

  function renderList(cands) {
    drop.innerHTML = "";
    if (!drop.parentNode) document.body.appendChild(drop);
    if (!cands.length) {
      drop.appendChild(h("li", { class: "material-suggest-empty", text: "一致するカタログアイテムがありません" }));
    } else {
      cands.forEach((c, idx) => {
        const dn = window.stripDisplayNamePlain(c.displayName || "") || c.id;
        const children = [h("span", { class: "material-suggest-primary", text: dn })];
        if (dn !== c.id) {
          children.push(h("span", { class: "material-suggest-secondary", text: c.id }));
        } else if (c.material) {
          const keyHint = c.cmd != null && c.cmd !== "" ? `${c.material}#${c.cmd}` : c.material;
          children.push(h("span", { class: "material-suggest-secondary", text: keyHint }));
        }
        const li = h("li", {
          class: "material-suggest-item" + (c.id === currentId ? " is-current" : ""),
          role: "option",
          "data-id": c.id
        }, children);
        li.addEventListener("mousedown", (e) => { e.preventDefault(); pick(c); });
        if (idx === activeIndex) li.classList.add("is-active");
        drop.appendChild(li);
      });
    }
    positionList();
    drop.style.display = "";
    open = true;
  }

  input.addEventListener("focus", () => {
    if (options.disabled) return;
    input.value = currentId || "";
    input.select();
    renderList(filter(currentId || ""));
  });
  input.addEventListener("input", () => {
    if (options.disabled) return;
    renderList(filter(input.value));
    activeIndex = 0;
  });
  input.addEventListener("keydown", (e) => {
    const items = [...drop.querySelectorAll(".material-suggest-item")];
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) renderList(filter(input.value));
      else {
        activeIndex = Math.min(items.length - 1, activeIndex + 1);
        items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
      }
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      activeIndex = Math.max(0, activeIndex - 1);
      items.forEach((el, i) => el.classList.toggle("is-active", i === activeIndex));
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (open && activeIndex >= 0 && items[activeIndex]) {
        pick(findById(items[activeIndex].getAttribute("data-id")));
      } else {
        const hits = filter(input.value);
        if (hits.length === 1) pick(hits[0]);
        else if (!input.value.trim()) pick(null);
        else renderList(hits);
      }
    } else if (e.key === "Escape") {
      e.preventDefault();
      syncDisplay();
      closeList();
      input.blur();
    }
  });
  input.addEventListener("blur", () => {
    setTimeout(() => {
      if (!wrap.contains(document.activeElement) && !drop.contains(document.activeElement)) {
        syncDisplay();
        closeList();
      }
    }, 120);
  });
  window.addEventListener("scroll", () => { if (open) positionList(); }, true);
  window.addEventListener("resize", () => { if (open) positionList(); });

  Object.defineProperty(wrap, "value", {
    get() { return currentId; },
    set(v) { currentId = v == null ? "" : String(v); syncDisplay(); },
    configurable: true
  });

  wrap.appendChild(input);
  syncDisplay();
  return wrap;
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
