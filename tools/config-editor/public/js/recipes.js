"use strict";

// ArsPaper items.yml 専用フォーム (schema: ars-recipes)。
//
// items.yml は2セクション:
//   items:          <id>: recipe: {method/type/shape/ingredients/... } (作業台 or 儀式)
//   ritual_effects: <id>: {name/effect-type/effect-params/core-item?/pedestal-items/source/...} (recipe ラッパー無し)
//
// 設計:
//   - parse/serialize の中核は「ブラウザ非依存の純関数」として分離 (Node の往復テストで検証)。
//   - 往復ロスレスが最重要。原文の shape/ingredients/pedestal-items の記法・キー順・任意キーの
//     有無をすべて保つ。編集された箇所のみ再生成する。
//   - shaped の shape⇔3×3グリッドはセル単位で origChar を保持して往復ロスレスにする。
//   - "NAME xN" (N=1 省略/明示混在)、custom: プレフィックスは raw 保持で完全一致を保証する。
//   - recipe ラッパーは UI に見せず保存時に復元する。未知キーは温存する。

(function (root, isBrowser) {
  // ============================================================
  // 純関数コア (ブラウザ非依存)
  // ============================================================

  function clone(v) {
    return v === undefined ? undefined : JSON.parse(JSON.stringify(v));
  }

  // キー順を無視した deep-equal (配列は順序を見る)。エントリ単位の verbatim ガードで使う:
  // 現モデルを再シリアライズした結果が「原文と値として等価」なら、ユーザー未編集とみなして
  // 原文をそのまま出力しキー順・記法を完全保存する。
  function deepEqualUnordered(a, b) {
    if (a === b) return true;
    if (typeof a !== typeof b) return false;
    if (Array.isArray(a) || Array.isArray(b)) {
      if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
      for (let i = 0; i < a.length; i++) if (!deepEqualUnordered(a[i], b[i])) return false;
      return true;
    }
    if (a && b && typeof a === "object") {
      const ka = Object.keys(a), kb = Object.keys(b);
      if (ka.length !== kb.length) return false;
      for (const k of ka) {
        if (!Object.prototype.hasOwnProperty.call(b, k)) return false;
        if (!deepEqualUnordered(a[k], b[k])) return false;
      }
      return true;
    }
    return a === b;
  }

  // キー順を含めた deep-equal (往復テスト/内部ガード用)。
  function deepEqualOrdered(a, b) {
    if (a === b) return true;
    if (typeof a !== typeof b) return false;
    if (Array.isArray(a) || Array.isArray(b)) {
      if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
      for (let i = 0; i < a.length; i++) if (!deepEqualOrdered(a[i], b[i])) return false;
      return true;
    }
    if (a && b && typeof a === "object") {
      const ka = Object.keys(a), kb = Object.keys(b);
      if (ka.length !== kb.length) return false;
      for (let i = 0; i < ka.length; i++) {
        if (ka[i] !== kb[i]) return false; // キー順も一致必須
        if (!deepEqualOrdered(a[ka[i]], b[kb[i]])) return false;
      }
      return true;
    }
    return a === b;
  }

  const CHAR_POOL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".split("");

  // ---- shaped: shape(3行文字列) + ingredients(char->item) ⇔ 3×3グリッド ----

  // shape/ingredients → 9セル配列 [{item, origChar}] (row-major, 0..8)。
  function shapeToGrid(shape, ingredients) {
    const ing = ingredients || {};
    const sh = Array.isArray(shape) ? shape : [];
    const cells = [];
    for (let r = 0; r < 3; r++) {
      const row = sh[r] !== undefined && sh[r] !== null ? String(sh[r]) : "";
      for (let c = 0; c < 3; c++) {
        const ch = row[c] !== undefined ? row[c] : " ";
        if (ch === " ") cells.push({ item: "", origChar: null });
        else cells.push({ item: ing[ch] !== undefined ? ing[ch] : "", origChar: ch });
      }
    }
    return cells;
  }

  // 9セル配列 → {shape:[3行], ingredients:{}}。origChar を優先的に再利用しロスレスにする。
  function gridToShapeIngredients(cells, origIngredients) {
    const orig = origIngredients || {};
    const used = new Set();
    const cellChar = new Array(9).fill(" ");
    // pass1: origChar が原文と同じアイテムを指すセルは origChar を維持
    for (let i = 0; i < 9; i++) {
      const cel = cells[i];
      if (!cel || !cel.item) continue;
      if (cel.origChar && orig[cel.origChar] === cel.item) {
        cellChar[i] = cel.origChar;
        used.add(cel.origChar);
      }
    }
    // pass2: 残りセルに文字を割当 (同一アイテム=同一文字)。空いていれば origChar 優先。
    const itemToChar = {};
    for (let i = 0; i < 9; i++) {
      const cel = cells[i];
      if (!cel || !cel.item || cellChar[i] !== " ") continue;
      if (itemToChar[cel.item]) { cellChar[i] = itemToChar[cel.item]; continue; }
      let ch = (cel.origChar && !used.has(cel.origChar)) ? cel.origChar : CHAR_POOL.find((p) => !used.has(p));
      used.add(ch);
      itemToChar[cel.item] = ch;
      cellChar[i] = ch;
    }
    // shape 3行を組む
    const shape = [];
    for (let r = 0; r < 3; r++) shape.push(cellChar.slice(r * 3, r * 3 + 3).join(""));
    // ingredients: 原文キー順で使用中のものを先に、その後に新規を row-major で追加
    const charToItem = {};
    for (let i = 0; i < 9; i++) if (cellChar[i] !== " ") charToItem[cellChar[i]] = cells[i].item;
    const ingredients = {};
    for (const ch of Object.keys(orig)) if (charToItem[ch] !== undefined) ingredients[ch] = charToItem[ch];
    for (let i = 0; i < 9; i++) {
      const ch = cellChar[i];
      if (ch !== " " && !(ch in ingredients)) ingredients[ch] = charToItem[ch];
    }
    return { shape, ingredients };
  }

  // ---- shapeless: ingredients(char->item) ⇔ 素材行リスト ----
  function ingMapToRows(ingredients) {
    const rows = [];
    for (const [ch, item] of Object.entries(ingredients || {})) rows.push({ item, origChar: ch });
    return rows;
  }

  function rowsToIngredients(rows, origIngredients) {
    const orig = origIngredients || {};
    const used = new Set();
    const chars = rows.map((row) => {
      if (row.origChar && orig[row.origChar] === row.item) { used.add(row.origChar); return row.origChar; }
      return null;
    });
    rows.forEach((row, i) => {
      if (chars[i] !== null) return;
      const ch = (row.origChar && !used.has(row.origChar)) ? row.origChar : CHAR_POOL.find((p) => !used.has(p));
      used.add(ch);
      chars[i] = ch;
    });
    const out = {};
    const usedSet = new Set(chars);
    for (const ch of Object.keys(orig)) {
      if (!usedSet.has(ch)) continue;
      const idx = chars.indexOf(ch);
      out[ch] = rows[idx].item;
    }
    rows.forEach((row, i) => { const ch = chars[i]; if (!(ch in out)) out[ch] = row.item; });
    return out;
  }

  // ---- pedestal-items: "NAME xN" ⇔ 行 {item, count, raw} ----
  function pedestalToRows(list) {
    const arr = Array.isArray(list) ? list : [];
    return arr.map((s) => {
      const str = String(s);
      const m = str.match(/^(.*) x(\d+)$/);
      if (m) return { item: m[1], count: parseInt(m[2], 10), raw: str };
      return { item: str, count: 1, raw: str };
    });
  }

  function pedestalRowToString(row) {
    if (row.raw != null) return row.raw; // 未編集は原文を保持 (明示 x1 等もロスレス)
    return row.count > 1 ? `${row.item} x${row.count}` : row.item;
  }

  function rowsToPedestal(rows) {
    return (rows || []).map(pedestalRowToString);
  }

  // ---- 儀式レシピ (core-item / pedestal-items / source) の共有パース ----
  // materials.yml / threads.yml の recipe は「結果無しの儀式」であり、items.yml の儀式部と同形。
  // method 等の未知キーと元のキー順を保持して往復ロスレスにする (result は扱わない)。
  const RITUAL_KNOWN = new Set(["core-item", "pedestal-items", "source"]);

  function parseRitualRecipe(recipe) {
    const r = recipe && typeof recipe === "object" && !Array.isArray(recipe) ? recipe : {};
    const model = {
      _order: Object.keys(r), // 元キー順を保持
      _extra: {}, // 未知キー (method 等) を verbatim 保持
      hasCoreItem: Object.prototype.hasOwnProperty.call(r, "core-item"),
      coreItem: r["core-item"] !== undefined ? r["core-item"] : "",
      hasPedestal: Object.prototype.hasOwnProperty.call(r, "pedestal-items"),
      pedestalRows: pedestalToRows(r["pedestal-items"]),
      hasSource: Object.prototype.hasOwnProperty.call(r, "source"),
      source: r.source !== undefined ? r.source : 0
    };
    for (const k of Object.keys(r)) if (!RITUAL_KNOWN.has(k)) model._extra[k] = clone(r[k]);
    return model;
  }

  function serializeRitualRecipe(model) {
    const out = {};
    const emitted = new Set();
    const emit = (k) => {
      emitted.add(k);
      if (k === "core-item") { if (model.hasCoreItem) out["core-item"] = model.coreItem; }
      else if (k === "pedestal-items") { if (model.hasPedestal) out["pedestal-items"] = rowsToPedestal(model.pedestalRows); }
      else if (k === "source") { if (model.hasSource) out.source = model.source; }
      else if (Object.prototype.hasOwnProperty.call(model._extra, k)) out[k] = clone(model._extra[k]);
    };
    for (const k of model._order) emit(k);
    // UI で新規に有効化されたキーが元順に無ければ末尾へ追加する。
    if (model.hasCoreItem && !emitted.has("core-item")) out["core-item"] = model.coreItem;
    if (model.hasPedestal && !emitted.has("pedestal-items")) out["pedestal-items"] = rowsToPedestal(model.pedestalRows);
    if (model.hasSource && !emitted.has("source")) out.source = model.source;
    return out;
  }

  // ---- エントリ (items セクション) ----
  // _extra 判定は「現 method ブランチが実際に出力するキー集合」を基準にする。
  // 例えば workbench レシピに紛れ込んだ name/source/core-item 等の非分岐既知キーは、
  // workbench serialize では出力されないため _extra に退避して verbatim 保持する
  // (旧実装は両ブランチ共有の Set で判定していたため、これらが黙って消えていた)。
  const WORKBENCH_CONSUMED = new Set(["method", "type", "shape", "ingredients", "result", "amount"]);
  const RITUAL_CONSUMED = new Set(["method", "name", "effect-type", "effect-params", "core-item", "pedestal-items", "source", "result", "result-amount"]);

  function parseItemEntry(id, entry) {
    const e = entry && typeof entry === "object" ? entry : {};
    const recipe = e.recipe && typeof e.recipe === "object" ? e.recipe : {};
    const method = recipe.method === "ritual" ? "ritual" : (recipe.method || "workbench");
    const model = { kind: "item", id, method, has: {}, _extra: {}, _entryExtra: {} };
    // 未編集判定用に原文エントリを保持 (getData の verbatim ガードで使用)。
    model._origEntry = clone(e);

    const consumed = method === "workbench" ? WORKBENCH_CONSUMED : RITUAL_CONSUMED;
    for (const k of Object.keys(recipe)) if (!consumed.has(k)) model._extra[k] = clone(recipe[k]);
    for (const k of Object.keys(e)) if (k !== "recipe") model._entryExtra[k] = clone(e[k]);

    if (method === "workbench") {
      model.type = recipe.type === "shapeless" ? "shapeless" : (recipe.type || "shaped");
      const ingredients = recipe.ingredients && typeof recipe.ingredients === "object" ? recipe.ingredients : {};
      model._origIngredients = clone(ingredients);
      if (model.type === "shaped") {
        model.cells = shapeToGrid(recipe.shape, ingredients);
        // ロスレスガード: グリッド往復が原文と不一致なら原文を verbatim 保持
        const rt = gridToShapeIngredients(model.cells, model._origIngredients);
        const lossless = deepEqualOrdered(rt.shape, recipe.shape || []) && deepEqualOrdered(rt.ingredients, ingredients);
        model._verbatim = lossless ? null : { shape: clone(recipe.shape), ingredients: clone(ingredients) };
      } else {
        model.shapelessRows = ingMapToRows(ingredients);
        model.hasShape = recipe.shape !== undefined;
        model._shape = clone(recipe.shape !== undefined ? recipe.shape : []);
      }
      model.has.result = recipe.result !== undefined;
      model.has.amount = recipe.amount !== undefined;
      model.result = recipe.result !== undefined ? recipe.result : ("custom:" + id);
      model.amount = recipe.amount !== undefined ? recipe.amount : 1;
    } else {
      model.hasCoreItem = recipe["core-item"] !== undefined;
      model.coreItem = recipe["core-item"] !== undefined ? recipe["core-item"] : "";
      model.hasPedestal = recipe["pedestal-items"] !== undefined;
      model.pedestalRows = pedestalToRows(recipe["pedestal-items"]);
      model.hasSource = recipe.source !== undefined;
      model.source = recipe.source !== undefined ? recipe.source : 0;
      model.has.name = recipe.name !== undefined;
      model.has["effect-type"] = recipe["effect-type"] !== undefined;
      model.has["effect-params"] = recipe["effect-params"] !== undefined;
      model.has.result = recipe.result !== undefined;
      model.has["result-amount"] = recipe["result-amount"] !== undefined;
      model.name = recipe.name !== undefined ? recipe.name : "";
      model.effectType = recipe["effect-type"] !== undefined ? recipe["effect-type"] : "craft";
      model.effectParams = clone(recipe["effect-params"] !== undefined ? recipe["effect-params"] : {});
      model.result = recipe.result !== undefined ? recipe.result : ("custom:" + id);
      model.resultAmount = recipe["result-amount"] !== undefined ? recipe["result-amount"] : 1;
    }
    return model;
  }

  function serializeItemEntry(model) {
    const r = {};
    if (model.method === "workbench") {
      r.method = "workbench";
      r.type = model.type;
      if (model.type === "shaped") {
        if (model._verbatim) {
          r.shape = clone(model._verbatim.shape);
          r.ingredients = clone(model._verbatim.ingredients);
        } else {
          const g = gridToShapeIngredients(model.cells, model._origIngredients);
          r.shape = g.shape;
          r.ingredients = g.ingredients;
        }
      } else {
        if (model.hasShape !== false) r.shape = clone(model._shape !== undefined ? model._shape : []);
        r.ingredients = rowsToIngredients(model.shapelessRows, model._origIngredients);
      }
      if (model.has.result) r.result = model.result;
      if (model.has.amount) r.amount = model.amount;
    } else {
      r.method = "ritual";
      if (model.has.name) r.name = model.name;
      if (model.has["effect-type"]) r["effect-type"] = model.effectType;
      if (model.has["effect-params"]) r["effect-params"] = clone(model.effectParams);
      if (model.hasCoreItem) r["core-item"] = model.coreItem;
      if (model.hasPedestal) r["pedestal-items"] = rowsToPedestal(model.pedestalRows);
      if (model.hasSource) r.source = model.source;
      if (model.has.result) r.result = model.result;
      if (model.has["result-amount"]) r["result-amount"] = model.resultAmount;
    }
    for (const k of Object.keys(model._extra)) r[k] = clone(model._extra[k]);
    const out = { recipe: r };
    for (const k of Object.keys(model._entryExtra)) out[k] = clone(model._entryExtra[k]);
    return out;
  }

  // method 切替 (workbench⇔ritual)。旧実装はモデルを空モデルで丸ごと置換していたため、
  // 3×3グリッド素材・台座・_extra/_entryExtra が消失し往復不能だった。
  // ここでは model を保持したまま method を変え、対象ブランチに欠けているフィールドだけ
  // 既定値で補う。非対象ブランチのフィールド・_extra・_entryExtra は温存されるため、
  // 切替→切替戻しで元の入力を失わない (undo 相当)。
  function ensureWorkbenchFields(model) {
    if (model.type === undefined) model.type = "shaped";
    if (model.type === "shaped") {
      if (!model.cells) { model.cells = shapeToGrid(["   ", "   ", "   "], {}); }
      if (model._origIngredients === undefined) model._origIngredients = {};
      if (model._verbatim === undefined) model._verbatim = null;
    } else {
      if (!model.shapelessRows) model.shapelessRows = [];
      if (model._shape === undefined) model._shape = [];
      if (model.hasShape === undefined) model.hasShape = true;
      if (model._origIngredients === undefined) model._origIngredients = {};
    }
    if (model.has.result === undefined) model.has.result = false;
    if (model.has.amount === undefined) model.has.amount = false;
    if (model.result === undefined) model.result = "custom:" + model.id;
    if (model.amount === undefined) model.amount = 1;
  }
  function ensureRitualFields(model) {
    if (model.hasCoreItem === undefined) model.hasCoreItem = false;
    if (model.coreItem === undefined) model.coreItem = "";
    if (model.hasPedestal === undefined) model.hasPedestal = false;
    if (!model.pedestalRows) model.pedestalRows = [];
    if (model.hasSource === undefined) model.hasSource = false;
    if (model.source === undefined) model.source = 0;
    if (model.has.name === undefined) model.has.name = false;
    if (model.has["effect-type"] === undefined) model.has["effect-type"] = false;
    if (model.has["effect-params"] === undefined) model.has["effect-params"] = false;
    if (model.has["result-amount"] === undefined) model.has["result-amount"] = false;
    if (model.name === undefined) model.name = "";
    if (model.effectType === undefined) model.effectType = "craft";
    if (model.effectParams === undefined) model.effectParams = {};
    if (model.resultAmount === undefined) model.resultAmount = 1;
  }
  function switchItemMethod(model, newMethod) {
    const m = newMethod === "ritual" ? "ritual" : "workbench";
    model.method = m;
    if (m === "workbench") ensureWorkbenchFields(model);
    else ensureRitualFields(model);
    return model;
  }

  // ---- エントリ (ritual_effects セクション / recipe ラッパー無し) ----
  const EFFECT_KNOWN = new Set([
    "name", "effect-type", "effect-params", "core-item", "pedestal-items", "source", "result"
  ]);

  function parseEffectEntry(id, entry) {
    const e = entry && typeof entry === "object" ? entry : {};
    const model = { kind: "effect", id, has: {}, _extra: {} };
    model._origEntry = clone(e); // 未編集判定用 (getData の verbatim ガード)
    for (const k of Object.keys(e)) if (!EFFECT_KNOWN.has(k)) model._extra[k] = clone(e[k]);
    model.has.name = e.name !== undefined;
    model.has["effect-type"] = e["effect-type"] !== undefined;
    model.has["effect-params"] = e["effect-params"] !== undefined;
    model.hasCoreItem = e["core-item"] !== undefined;
    model.hasPedestal = e["pedestal-items"] !== undefined;
    model.hasSource = e.source !== undefined;
    model.has.result = e.result !== undefined;
    model.name = e.name !== undefined ? e.name : id;
    model.effectType = e["effect-type"] !== undefined ? e["effect-type"] : "craft";
    model.effectParams = clone(e["effect-params"] !== undefined ? e["effect-params"] : {});
    model.coreItem = e["core-item"] !== undefined ? e["core-item"] : "";
    model.pedestalRows = pedestalToRows(e["pedestal-items"]);
    model.source = e.source !== undefined ? e.source : 0;
    model.result = e.result !== undefined ? e.result : "";
    return model;
  }

  function serializeEffectEntry(model) {
    const out = {};
    if (model.has.name) out.name = model.name;
    if (model.has["effect-type"]) out["effect-type"] = model.effectType;
    if (model.has["effect-params"]) out["effect-params"] = clone(model.effectParams);
    if (model.hasCoreItem) out["core-item"] = model.coreItem;
    if (model.hasPedestal) out["pedestal-items"] = rowsToPedestal(model.pedestalRows);
    if (model.hasSource) out.source = model.source;
    if (model.has.result) out.result = model.result;
    for (const k of Object.keys(model._extra)) out[k] = clone(model._extra[k]);
    return out;
  }

  const CORE = {
    clone, deepEqualOrdered, deepEqualUnordered,
    shapeToGrid, gridToShapeIngredients,
    ingMapToRows, rowsToIngredients,
    pedestalToRows, rowsToPedestal, pedestalRowToString,
    parseRitualRecipe, serializeRitualRecipe,
    parseItemEntry, serializeItemEntry, switchItemMethod,
    parseEffectEntry, serializeEffectEntry
  };

  root.RECIPES = CORE;
  root.RECIPES_CORE = CORE;
  if (typeof module !== "undefined" && module.exports) module.exports = CORE;

  // ============================================================
  // DOM 部品 (ブラウザ専用)
  // ============================================================
  if (!isBrowser) return;

  const h = window.h;
  const METHODS = ["workbench", "ritual"];
  const TYPES = ["shaped", "shapeless"];
  // ArsPaper.java の register 実装に一致 + 既定 craft。
  const EFFECT_TYPES = ["craft", "weather", "flight", "moonfall", "sunrise", "repair", "animal_summon", "mob_summon", "enchant_book", "thread_slot_expand", "thread_reroll"];
  const WEATHER_MODES = ["clear", "rain", "thunder"];
  const MOB_GROUPS = ["default", "raid", "nether", "variant"];
  const CUSTOM_DATALIST_ID = "recipe-item-list";
  const ENCHANT_DATALIST_ID = "recipe-enchant-list";

  // enchant_book の enchantment 候補 datalist (tf-forms の ENCHANT_KEYS を再利用、無ければ空)。
  function ensureEnchantDatalist() {
    let dl = document.getElementById(ENCHANT_DATALIST_ID);
    if (!dl) { dl = h("datalist", { id: ENCHANT_DATALIST_ID }); document.body.appendChild(dl); }
    if (dl._filled) return;
    const keys = Array.isArray(window.ENCHANT_KEYS) ? window.ENCHANT_KEYS : [];
    if (!keys.length) return; // まだ供給元が読めていなければ次回に再試行
    dl._filled = true;
    for (const k of keys) dl.appendChild(h("option", { value: k }));
  }

  function enumLabel(group, v) { return window.LABELS ? window.LABELS.enumLabel(group, v) : v; }

  function labeledSelect(value, options, group, onChange) {
    const cur = value == null ? "" : String(value);
    const opts = options.slice();
    if (cur && !opts.includes(cur)) opts.push(cur);
    return window.listSelect({
      value: cur,
      options: opts.map((opt) => {
        const ja = enumLabel(group, opt);
        return {
          value: opt,
          primary: ja && ja !== opt ? ja : opt,
          secondary: ja && ja !== opt ? opt : "",
          title: opt
        };
      }),
      onChange
    });
  }

  // アイテムピッカー (バニラ Material サジェスト + custom: カタログ候補)。
  function itemPicker(value, onChange, extraClass) {
    return window.materialInput(value, null, onChange, {
      allowCustom: true,
      className: extraClass || ""
    });
  }

  function numberStepper(value, onChange, opts) {
    return window.numberInput(value, (v) => onChange(v == null ? null : v), opts || { int: true });
  }

  function fieldRow(labelKey, control, labelText) {
    const labelEl = labelText
      ? h("span", { class: "form-label", text: labelText })
      : window.fieldLabelEl(labelKey);
    return h("div", { class: "form-field" }, [labelEl, control]);
  }

  // ---- 共有される儀式レイアウト部品 (items ritual / materials / threads で共用) ----
  // model は { hasCoreItem, coreItem, hasPedestal, pedestalRows, hasSource, source } を持つ。
  // rerender は構造変更時に呼ぶ再描画コールバック。
  function buildCoreSection(model, rerender, opts) {
    const o = opts || {};
    const coreWrap = h("div", { class: "ritual-core" });
    const coreOn = h("input", { type: "checkbox", checked: model.hasCoreItem, onchange: (e) => { model.hasCoreItem = e.target.checked; rerender(); } });
    coreWrap.appendChild(h("label", { class: "result-default" }, [coreOn, h("span", { text: "コアアイテムを使う" })]));
    if (model.hasCoreItem) coreWrap.appendChild(fieldRow("core-item", itemPicker(model.coreItem, (v) => { model.coreItem = v; }), o.coreLabel || "コアアイテム (中央)"));
    return coreWrap;
  }

  // 儀式コア周囲の台座リング(距離2)は物理16台。"ITEM xN" はN台分に展開されるため合計で数える。
  const MAX_PEDESTAL_TOTAL = 16;

  function pedestalTotalOf(rows) {
    return (rows || []).reduce((sum, r) => sum + (r && r.count > 0 ? Math.trunc(r.count) : 1), 0);
  }

  function buildPedestalSection(model, rerender) {
    const box = h("div", { class: "pedestal-box" });
    const head = h("div", { class: "mini-label", text: "台座アイテム (pedestal-items)" });
    const counter = h("span", { style: "margin-left:8px;" });
    head.appendChild(counter);
    box.appendChild(head);
    // 描画は読み取り専用。pedestal-items を持たないエントリに hasPedestal=true / [] を
    // 書き込まない (無編集保存でキーが増える副作用を防ぐ)。有効化はユーザー操作時のみ。
    const rows = Array.isArray(model.pedestalRows) ? model.pedestalRows : [];
    const rowsBox = h("div", { class: "pedestal-rows" });
    const addBtn = h("button", {
      class: "btn-small", type: "button", text: "+ 台座追加",
      onclick: () => { model.hasPedestal = true; if (!Array.isArray(model.pedestalRows)) model.pedestalRows = []; model.pedestalRows.push({ item: "", count: 1, raw: null }); rerender(); }
    });
    // 合計台数の表示と、上限到達時の追加ボタン無効化 (個数ステッパー変更時も追随)。
    function updateCounter() {
      const total = pedestalTotalOf(Array.isArray(model.pedestalRows) ? model.pedestalRows : rows);
      counter.textContent = "合計 " + total + "/" + MAX_PEDESTAL_TOTAL + "台";
      counter.style.color = total > MAX_PEDESTAL_TOTAL ? "#dc2626" : "";
      addBtn.disabled = total >= MAX_PEDESTAL_TOTAL;
      addBtn.title = total >= MAX_PEDESTAL_TOTAL ? "台座リングの上限(" + MAX_PEDESTAL_TOTAL + "台)に達しています" : "";
    }
    rows.forEach((row) => {
      const r = h("div", { class: "pedestal-row" });
      r.appendChild(itemPicker(row.item, (v) => { row.item = v; row.raw = null; model.hasPedestal = true; }));
      r.appendChild(h("span", { class: "mini-label", text: "×" }));
      r.appendChild(numberStepper(row.count, (v) => { row.count = v == null || v < 1 ? 1 : v; row.raw = null; model.hasPedestal = true; updateCounter(); }, { int: true }));
      r.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { model.pedestalRows.splice(model.pedestalRows.indexOf(row), 1); model.hasPedestal = true; rerender(); } }));
      rowsBox.appendChild(r);
    });
    rowsBox.appendChild(addBtn);
    box.appendChild(rowsBox);
    updateCounter();
    return box;
  }

  function buildSourceRow(model, opts) {
    const o = opts || {};
    return fieldRow("source", numberStepper(model.source, (v) => { model.source = v == null ? 0 : v; model.hasSource = true; }, { int: true }), o.sourceLabel || "必要ソース (source)");
  }

  // materials/threads から使う共有部品を公開する。
  window.RECIPES_UI = {
    itemPicker, numberStepper, fieldRow, labeledSelect, ensureCustomDatalist,
    buildCoreSection, buildPedestalSection, buildSourceRow,
    pedestalTotalOf, MAX_PEDESTAL_TOTAL
  };

  // custom: 候補を構築 (catalog / materials / items / threads から合成)。
  // 複数箇所から呼ばれるため Promise を共有し、完了後は即 return。
  async function ensureCustomDatalist() {
    if (window._customItemCandidatesPromise) return window._customItemCandidatesPromise;
    window._customItemCandidatesPromise = (async () => {
      async function grab(id) {
        try {
          const res = await fetch(`/api/config/${id}`);
          const json = await res.json();
          return json && json.data && typeof json.data === "object" ? json.data : {};
        } catch (_) { return {}; }
      }
      const [catalog, materials, items, threads, externalItems] = await Promise.all([
        grab("catalog"), grab("materials"), grab("items"), grab("threads"), grab("external-items")
      ]);
      const entries = [];
      for (const [k, ent] of Object.entries((catalog.items) || {})) {
        const rawName = ent && ent["display-name"];
        const label = rawName
          ? (typeof window.stripDisplayNamePlain === "function"
            ? window.stripDisplayNamePlain(rawName) : String(rawName))
          : k;
        entries.push({ id: k, label });
      }
      for (const [k, ent] of Object.entries(materials.materials || {})) {
        const rawName = ent && ent.display_name;
        const label = rawName
          ? (typeof window.stripDisplayNamePlain === "function"
            ? window.stripDisplayNamePlain(rawName) : String(rawName))
          : k;
        entries.push({ id: k, label });
      }
      for (const k of Object.keys(items.items || {})) entries.push({ id: k, label: k });
      entries.push({ id: "thread_empty", label: "空のスレッド" });
      for (const [k, ent] of Object.entries(threads.threads || {})) {
        const rawName = ent && ent.display_name;
        const label = rawName
          ? (typeof window.stripDisplayNamePlain === "function"
            ? window.stripDisplayNamePlain(rawName) : String(rawName))
          : k;
        entries.push({ id: "thread_" + k, label });
      }
      for (const [k, ent] of Object.entries(externalItems.items || {})) {
        const label = ent && ent["display-name"] ? String(ent["display-name"]) : `外部: ${k}`;
        entries.push({ id: k, label });
      }
      if (typeof window.setCustomItemCandidates === "function") {
        window.setCustomItemCandidates(entries, { replace: false });
      } else {
        window.CUSTOM_ITEM_CANDIDATES = entries.map((e) => "custom:" + e.id);
      }
      // 旧 datalist 互換 (残存参照向け)。
      let dl = document.getElementById(CUSTOM_DATALIST_ID);
      if (!dl) { dl = h("datalist", { id: CUSTOM_DATALIST_ID }); document.body.appendChild(dl); }
      dl.innerHTML = "";
      for (const v of (window.CUSTOM_ITEM_CANDIDATES || [])) dl.appendChild(h("option", { value: v }));
      for (const m of (window.MATERIALS || [])) dl.appendChild(h("option", { value: m }));
      window._customItemCandidatesLoaded = true;
    })().catch((err) => {
      window._customItemCandidatesPromise = null;
      throw err;
    });
    return window._customItemCandidatesPromise;
  }

  // ============================================================
  // メインフォーム
  // ============================================================
  window.buildRecipesForm = function buildRecipesForm(data, opts) {
    const src = data && typeof data === "object" ? data : {};
    // 元の top-level キー順を保持 (items / ritual_effects 以外の未知キーも温存)。
    const topKeys = Object.keys(src);
    if (!topKeys.includes("items")) topKeys.push("items");
    if (!topKeys.includes("ritual_effects")) topKeys.push("ritual_effects");
    const extraTop = {};
    for (const k of Object.keys(src)) if (k !== "items" && k !== "ritual_effects") extraTop[k] = src[k];

    const itemsSrc = src.items && typeof src.items === "object" ? src.items : {};
    const effectsSrc = src.ritual_effects && typeof src.ritual_effects === "object" ? src.ritual_effects : {};
    const itemModels = Object.keys(itemsSrc).map((id) => ({ id, model: CORE.parseItemEntry(id, itemsSrc[id]) }));
    const effectModels = Object.keys(effectsSrc).map((id) => ({ id, model: CORE.parseEffectEntry(id, effectsSrc[id]) }));
    // フォームを開いた時点(=保存済みスナップショット)に存在した儀式エフェクトID集合。
    // このセットに無いID(=このセッション中に新規追加され未保存)のみ、IDを編集可能にする。
    // 保存済みIDはコード側(儀式ゲート/効果解決)と直接紐づくため読み取り専用のまま。
    const originalEffectIds = new Set(Object.keys(effectsSrc));

    // items セクション(method=workbench/ritual)をサブタブとして分割表示する (表示のみ。
    // items 配列自体は分割せず、items.yml のスキーマ・保存構造は不変)。
    // onlyEffects: カタログへ作業台/儀式レシピを移したため、儀式エフェクトのみ表示。
    const onlyEffects = !!(opts && opts.onlyEffects);
    let activeArea = "ritual_effects";
    let filter = "";

    const root = h("div", { class: "dedicated-form recipes-form" });
    ensureCustomDatalist();

    // 折りたたみ状態(開いているidの集合)。既定は全て折りたたみ (skilltreeと同じUX)。
    // items(作業台/儀式共用)と ritual_effects で別々の集合を持つ。再描画をまたいで保持する。
    const expandedItems = new Set();
    const expandedEffects = new Set();

    // ---- タブ + 検索 ----
    const tabBar = h("div", { class: "recipe-tabs" });
    function tabButton(area, label, count) {
      return h("button", {
        class: `recipe-tab ${activeArea === area ? "active" : ""}`, type: "button",
        onclick: () => { activeArea = area; render(); }
      }, [h("span", { text: label }), h("span", { class: "recipe-tab-count", text: String(count) })]);
    }
    const searchInput = h("input", {
      class: "field-input recipe-search", placeholder: "id・名前で絞り込み", spellcheck: "false",
      value: filter, oninput: (e) => { filter = e.target.value; renderList(); }
    });

    const areaBox = h("div", { class: "recipe-area card-list" });

    function matchesFilter(id, name) {
      const f = filter.trim().toLowerCase();
      if (!f) return true;
      return String(id).toLowerCase().includes(f) || String(name || "").toLowerCase().includes(f);
    }

    function uniqueId(base, listModels) {
      let name = base, i = 1;
      const has = (n) => listModels.some((x) => x.id === n);
      while (has(name)) name = `${base}_${i++}`;
      return name;
    }

    function itemModelsByMethod(method) {
      return itemModels.filter((e) => e.model.method === method);
    }

    function render() {
      tabBar.innerHTML = "";
      if (!onlyEffects) {
        tabBar.appendChild(tabButton("workbench", "作業台", itemModelsByMethod("workbench").length));
        tabBar.appendChild(tabButton("ritual", "儀式", itemModelsByMethod("ritual").length));
      }
      tabBar.appendChild(tabButton("ritual_effects", "儀式エフェクト", effectModels.length));
      tabBar.style.display = onlyEffects ? "none" : "";
      renderList();
    }

    function renderList() {
      areaBox.innerHTML = "";
      if (activeArea === "workbench") renderItemsArea("workbench");
      else if (activeArea === "ritual") renderItemsArea("ritual");
      else renderEffectsArea();
    }

    // ---- items エリア (method で絞り込み表示) ----
    function renderItemsArea(method) {
      const visible = itemModelsByMethod(method);
      if (visible.length === 0) {
        const hint = method === "workbench"
          ? "「+ レシピ追加」で作業台レシピ(3×3クラフト)を作成します。"
          : "「+ レシピ追加」で儀式レシピ(祭壇/台座)を作成します。";
        areaBox.appendChild(emptyGuide(method === "workbench" ? "作業台レシピがまだありません。" : "儀式レシピがまだありません。", hint));
      }
      for (const entry of visible) {
        if (!matchesFilter(entry.id, entry.model.name)) continue;
        areaBox.appendChild(renderItemCard(entry));
      }
      areaBox.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ レシピ追加",
          onclick: () => {
            const id = uniqueId("new_recipe", itemModels);
            const model = method === "ritual" ? newRitualModel(id) : newWorkbenchModel(id);
            itemModels.push({ id, model });
            expandedItems.add(id); // 新規追加は開いた状態で編集させる
            filter = ""; searchInput.value = "";
            render();
          }
        })
      ]));
    }

    function newWorkbenchModel(id) {
      const m = CORE.parseItemEntry(id, { recipe: { method: "workbench", type: "shaped", shape: ["   ", "   ", "   "], ingredients: {} } });
      return m;
    }

    function newRitualModel(id) {
      const m = CORE.parseItemEntry(id, { recipe: { method: "ritual" } });
      return m;
    }

    function renderItemCard(entry) {
      const model = entry.model;
      const idInput = h("input", { class: "field-input entry-id", value: entry.id, spellcheck: "false" });
      idInput.addEventListener("change", (ev) => {
        const nv = ev.target.value.trim();
        if (!nv || nv === entry.id) { ev.target.value = entry.id; return; }
        if (itemModels.some((x) => x.id === nv)) { alert("同じidが存在します"); ev.target.value = entry.id; return; }
        const oldId = entry.id;
        entry.id = nv; model.id = nv;
        if (expandedItems.has(oldId)) { expandedItems.delete(oldId); expandedItems.add(nv); }
      });

      const methodSel = labeledSelect(model.method, METHODS, "method", (v) => {
        if (v === model.method) return;
        // モデルを保持したまま method を切替 (両ブランチの入力・未知キーを温存し往復ロスレス)。
        CORE.switchItemMethod(model, v);
        render();
      });

      const head = [
        h("span", { class: "entry-key-label", text: "id" }), idInput,
        h("span", { class: "mini-label", text: "方式" }), methodSel,
        h("div", { class: "spacer" }),
        h("button", {
          class: "btn-small", type: "button", text: "複製",
          onclick: () => {
            const copyId = uniqueId(entry.id + "_copy", itemModels);
            const dupModel = CORE.parseItemEntry(copyId, CORE.serializeItemEntry(model));
            dupModel.id = copyId;
            const idx = itemModels.indexOf(entry);
            itemModels.splice(idx + 1, 0, { id: copyId, model: dupModel });
            render();
          }
        }),
        h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: () => { itemModels.splice(itemModels.indexOf(entry), 1); render(); } })
      ];

      const body = model.method === "workbench" ? renderWorkbenchBody(model) : renderRitualBody(model, true);
      const el = window.collapsibleCard(head, body, {
        expanded: expandedItems.has(entry.id),
        onToggle: (open) => { if (open) expandedItems.add(entry.id); else expandedItems.delete(entry.id); }
      });
      el.classList.add("recipe-card"); // style.css の .recipe-card .entry-head レイアウトを維持する
      return el;
    }

    // ---- 作業台ボディ ----
    function renderWorkbenchBody(model) {
      const box = h("div", { class: "recipe-body" });
      const typeSel = labeledSelect(model.type, TYPES, "type", (v) => {
        if (v === model.type) return;
        model.type = v;
        if (v === "shaped" && !model.cells) { model.cells = CORE.shapeToGrid(["   ", "   ", "   "], {}); model._verbatim = null; model._origIngredients = {}; }
        if (v === "shapeless" && !model.shapelessRows) { model.shapelessRows = []; model._shape = []; model.hasShape = true; model._origIngredients = {}; }
        renderList();
      });
      box.appendChild(fieldRow("type", typeSel));

      if (model.type === "shaped") {
        box.appendChild(renderCraftGrid(model));
      } else {
        box.appendChild(renderShapelessList(model));
      }
      box.appendChild(renderResultSlot(model));
      return box;
    }

    // 3×3 クラフトグリッド。セル編集で _verbatim を解除しグリッド再生成に切替える。
    function renderCraftGrid(model) {
      const wrap = h("div", { class: "craft-layout" });
      const grid = h("div", { class: "craft-grid" });
      model.cells.forEach((cell, idx) => {
        const inp = itemPicker(cell.item, (v) => { cell.item = v; model._verbatim = null; }, "craft-cell-input");
        const gcell = h("div", { class: "craft-cell" }, [inp]);
        grid.appendChild(gcell);
        // eslint-disable-next-line no-unused-vars
        void idx;
      });
      wrap.appendChild(h("div", { class: "craft-grid-wrap" }, [
        h("div", { class: "mini-label", text: "クラフト配置 (3×3)" }), grid
      ]));
      wrap.appendChild(h("div", { class: "craft-arrow", text: "→" }));
      return wrap;
    }

    function renderShapelessList(model) {
      const box = h("div", { class: "shapeless-list" });
      box.appendChild(h("div", { class: "mini-label", text: "素材 (順不同)" }));
      const rowsBox = h("div", { class: "pedestal-rows" });
      model.shapelessRows.forEach((row) => {
        const r = h("div", { class: "pedestal-row" });
        r.appendChild(itemPicker(row.item, (v) => { row.item = v; row.origChar = row.origChar; }));
        r.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { model.shapelessRows.splice(model.shapelessRows.indexOf(row), 1); renderList(); } }));
        rowsBox.appendChild(r);
      });
      rowsBox.appendChild(h("button", { class: "btn-small", type: "button", text: "+ 素材追加", onclick: () => { model.shapelessRows.push({ item: "", origChar: null }); renderList(); } }));
      box.appendChild(rowsBox);
      return box;
    }

    // 結果スロット (既定 custom:<id> チェック + amount)。
    function renderResultSlot(model) {
      const box = h("div", { class: "result-slot" });
      const useDefault = h("input", { type: "checkbox", checked: !model.has.result, onchange: (e) => { model.has.result = !e.target.checked; renderList(); } });
      box.appendChild(h("label", { class: "result-default" }, [useDefault, h("span", { text: `結果を既定 (custom:${model.id}) にする` })]));
      if (model.has.result) {
        box.appendChild(fieldRow("result", itemPicker(model.result, (v) => { model.result = v; }), "結果アイテム"));
      }
      const amtOn = h("input", { type: "checkbox", checked: model.has.amount, onchange: (e) => { model.has.amount = e.target.checked; renderList(); } });
      const amtRow = h("label", { class: "result-default" }, [amtOn, h("span", { text: "個数を指定" })]);
      box.appendChild(amtRow);
      if (model.has.amount) {
        box.appendChild(fieldRow("amount", numberStepper(model.amount, (v) => { model.amount = v == null ? 1 : v; }), "個数"));
      }
      return box;
    }

    // ---- 儀式ボディ (items ritual / ritual_effects 共用) ----
    // isItem=true のとき result スロット/既定を出す。
    function renderRitualBody(model, isItem) {
      const box = h("div", { class: "recipe-body ritual-layout" });
      box.appendChild(buildCoreSection(model, renderList, { coreLabel: "コアアイテム (中央)" }));
      box.appendChild(buildPedestalSection(model, renderList));
      box.appendChild(buildSourceRow(model, {}));
      if (isItem) box.appendChild(renderResultSlotRitual(model));
      // 高度な設定 (name / effect-type / effect-params)。
      box.appendChild(renderAdvanced(model, isItem));
      return box;
    }

    function renderResultSlotRitual(model) {
      const box = h("div", { class: "result-slot" });
      const useDefault = h("input", { type: "checkbox", checked: !model.has.result, onchange: (e) => { model.has.result = !e.target.checked; renderList(); } });
      box.appendChild(h("label", { class: "result-default" }, [useDefault, h("span", { text: `結果を既定 (custom:${model.id}) にする` })]));
      if (model.has.result) box.appendChild(fieldRow("result", itemPicker(model.result, (v) => { model.result = v; }), "結果アイテム"));
      const raOn = h("input", { type: "checkbox", checked: model.has["result-amount"], onchange: (e) => { model.has["result-amount"] = e.target.checked; renderList(); } });
      box.appendChild(h("label", { class: "result-default" }, [raOn, h("span", { text: "結果個数を指定" })]));
      if (model.has["result-amount"]) box.appendChild(fieldRow("result-amount", numberStepper(model.resultAmount, (v) => { model.resultAmount = v == null ? 1 : v; }), "結果個数"));
      return box;
    }

    // 儀式クラフト(items)の設定。表示名(name)のみをデフォルト表示する。
    // エフェクト種別(effect-type)/effect-params は「儀式エフェクト専門」(ritual_effects セクション)の
    // 管轄へ集約したため、ここ(クラフトレシピ側)では編集UIを出さない。既存データは parse/serialize の
    // passthrough でロスレス温存される(has フラグを保つため保存時に消えない)。
    function renderAdvanced(model, isItem) {
      const box = h("div", { class: "advanced-box" });
      box.appendChild(fieldRow("name", window.textInput(model.name, (v) => {
        model.name = v;
        model.has.name = v !== undefined && v !== "";
      }), "表示名 (name)"));
      void isItem;
      return box;
    }

    // effect-type 別の専用 effect-params 欄。
    function renderEffectParams(model) {
      const box = h("div", { class: "effect-params-box" });
      box.appendChild(h("div", { class: "mini-label", text: "effect-params" }));
      const p = model.effectParams || (model.effectParams = {});
      const t = model.effectType;
      // effect-params への書込みは全てユーザー編集なので、そのとき has フラグを立てる
      // (描画だけで effect-params:{} を持たないエントリにキーを増やさない)。
      const mark = () => { model.has["effect-params"] = true; };
      function setParam(key, val, isNum) {
        mark();
        if (val === "" || val == null) { delete p[key]; return; }
        p[key] = isNum ? (typeof val === "number" ? val : Number(val)) : val;
      }
      if (t === "weather") {
        box.appendChild(fieldRow("mode", labeledSelect(p.mode || "clear", WEATHER_MODES, "weather-mode", (v) => { p.mode = v; mark(); }), "天候 (mode)"));
      } else if (t === "flight") {
        box.appendChild(fieldRow("duration", numberStepper(p.duration, (v) => setParam("duration", v, true)), "継続時間 (duration)"));
      // 2026-07-25: effect-type "thread"(スレッド付与の儀式)は廃止したため、この分岐も削除した。
      // 枠付与の "thread_slot_expand" は存続しているので混同しないこと(前方一致する別物)。
      } else if (t === "animal_summon") {
        box.appendChild(fieldRow("count", numberStepper(p.count, (v) => setParam("count", v, true)), "召喚数 (count)"));
        box.appendChild(entityListEditor(p, "entities", mark, {
          title: "出現モブ (entities)",
          hint: "未指定時は Java 既定の友好モブリスト。指定時はその中からランダム。"
        }));
      } else if (t === "mob_summon") {
        box.appendChild(fieldRow("count", numberStepper(p.count, (v) => setParam("count", v, true)), "召喚数 (count)"));
        box.appendChild(fieldRow("group", labeledSelect(p.group || "default", MOB_GROUPS, "mob-group", (v) => { p.group = v; mark(); }), "グループ (group)"));
        box.appendChild(entityListEditor(p, "entities", mark, {
          title: "出現モブ上書き (entities)",
          hint: "指定時は group より優先。EntityType 名 (例: ZOMBIE)。"
        }));
      } else if (t === "enchant_book") {
        const keys = Array.isArray(window.ENCHANT_KEYS) ? window.ENCHANT_KEYS.slice() : [];
        const cur = p.enchantment == null ? "" : String(p.enchantment);
        if (cur && !keys.includes(cur)) keys.unshift(cur);
        box.appendChild(fieldRow("enchantment", window.listSelect({
          value: cur,
          placeholder: "エンチャントを選択…",
          options: keys.map((k) => {
            const ja = window.ENCHANT_LABELS_JA && window.ENCHANT_LABELS_JA[k];
            return ja ? { value: k, primary: ja, secondary: k } : { value: k, primary: k };
          }),
          allowCustom: true,
          customPlaceholder: "カスタムID",
          onChange: (v) => {
            if (!v) delete p.enchantment;
            else p.enchantment = v;
            mark();
          }
        }), "エンチャント (enchantment)"));
        box.appendChild(fieldRow("level", numberStepper(p.level, (v) => setParam("level", v, true)), "レベル (level)"));
      } else if (t === "thread_slot_expand") {
        box.appendChild(fieldRow("max-slots", numberStepper(p["max-slots"] == null ? 1 : p["max-slots"], (v) => setParam("max-slots", Math.max(1, Number(v) || 1), true)), "累計付与上限 (max-slots)"));
        box.appendChild(h("div", {
          class: "field-desc",
          style: "font-size:11px;color:var(--muted,#6b7280);margin:4px 0 8px;",
          text: "コアに置いた装備のスレッド枠(thread-slots)を1回の儀式ごとに+1します。max-slotsはこの儀式で1つの装備に付与できる累計スレッド枠数の上限です。"
        }));
      } else if (t === "thread_reroll") {
        box.appendChild(h("div", {
          class: "field-desc",
          style: "font-size:11px;color:var(--muted,#6b7280);margin:4px 0 8px;",
          text: "コアに置いた効果付きスレッド1個の厳選(主ステ/サブステ)を振り直します。パラメータはありません。抽選内容は TrinityForge の item-stats.yml(アイテムステータス > スレッドタブの各スレッドの random 設定)側で決まります。コアのスレッドは消費されず、ペデスタルの素材とソースだけが振り直しの費用になります。"
        }));
      } else {
        // 未知 type: key/value 行
        box.appendChild(renderKvRows(p, mark));
      }
      return box;
    }

    function entityListEditor(p, key, mark, opts) {
      const o = opts || {};
      const onMark = typeof mark === "function" ? mark : () => {};
      // ロード時: 文字列 "A,B" / 配列 の両方を配列に正規化（保存は配列のまま YAML list）
      if (typeof p[key] === "string") {
        p[key] = p[key].split(/[,\s]+/).map((s) => s.trim()).filter(Boolean);
      }
      const box = h("div", { class: "sub-section" });
      box.appendChild(h("div", { class: "mini-label", text: o.title || key }));
      if (o.hint) {
        box.appendChild(h("div", {
          class: "field-desc",
          style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 6px;",
          text: o.hint
        }));
      }
      const list = Array.isArray(p[key]) ? p[key] : [];
      const rows = h("div", { class: "pedestal-rows" });
      function paint() {
        rows.innerHTML = "";
        if (!list.length) rows.appendChild(h("div", { class: "empty-hint", text: "未指定（Java 既定リスト）" }));
        const vanillaMobs = Array.isArray(window.VANILLA_MOBS) ? window.VANILLA_MOBS : [];
        list.forEach((ent, idx) => {
          const cur = ent == null ? "" : String(ent);
          const opts = vanillaMobs.map((id) => {
            const ja = window.MOB_LABELS_JA && window.MOB_LABELS_JA[id];
            return ja ? { value: id, primary: ja, secondary: id } : { value: id, primary: id };
          });
          if (cur && !vanillaMobs.includes(cur)) opts.unshift({ value: cur, primary: cur });
          rows.appendChild(h("div", { class: "pedestal-row" }, [
            window.listSelect({
              value: cur,
              placeholder: "モブを選択…",
              options: opts,
              allowCustom: true,
              customPlaceholder: "EntityType名",
              onChange: (v) => {
                list[idx] = v;
                p[key] = list;
                onMark();
              }
            }),
            h("button", {
              class: "btn-small danger", type: "button", text: "×",
              onclick: () => { list.splice(idx, 1); p[key] = list.slice(); onMark(); paint(); }
            })
          ]));
        });
        rows.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ モブ追加",
          onclick: () => {
            if (!Array.isArray(p[key])) p[key] = list;
            list.push("ZOMBIE");
            p[key] = list;
            onMark();
            paint();
          }
        }));
      }
      paint();
      box.appendChild(rows);
      return box;
    }

    function renderKvRows(paramObj, mark) {
      const onEdit = typeof mark === "function" ? mark : () => {};
      const box = h("div", { class: "kv-rows" });
      for (const key of Object.keys(paramObj)) {
        const r = h("div", { class: "pedestal-row" });
        const keyInp = h("input", { class: "field-input", value: key, spellcheck: "false" });
        keyInp.addEventListener("change", (e) => {
          const nk = e.target.value; if (!nk || nk === key) { e.target.value = key; return; }
          const rebuilt = {}; for (const k of Object.keys(paramObj)) rebuilt[k === key ? nk : k] = paramObj[k];
          for (const k of Object.keys(paramObj)) delete paramObj[k]; Object.assign(paramObj, rebuilt); onEdit(); renderList();
        });
        r.appendChild(keyInp);
        r.appendChild(window.textInput(paramObj[key], (v) => { paramObj[key] = v; onEdit(); }));
        r.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { delete paramObj[key]; onEdit(); renderList(); } }));
        box.appendChild(r);
      }
      box.appendChild(h("button", { class: "btn-small", type: "button", text: "+ パラメータ追加", onclick: () => { let n = "key", i = 1; while (n in paramObj) n = `key_${i++}`; paramObj[n] = ""; onEdit(); renderList(); } }));
      return box;
    }

    // ---- ritual_effects エリア ----
    function renderEffectsArea() {
      if (effectModels.length === 0) {
        areaBox.appendChild(emptyGuide("儀式エフェクトがまだありません。", "「+ エフェクト追加」でワールド効果の儀式を作成します。"));
      }
      for (const entry of effectModels) {
        if (!matchesFilter(entry.id, entry.model.name)) continue;
        areaBox.appendChild(renderEffectCard(entry));
      }
      areaBox.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ エフェクト追加",
          onclick: () => {
            const id = uniqueId("new_effect", effectModels);
            const m = CORE.parseEffectEntry(id, { name: id, "effect-type": "weather", "effect-params": { mode: "clear" }, "pedestal-items": [], source: 0 });
            effectModels.push({ id, model: m });
            expandedEffects.add(id); // 新規追加は開いた状態で編集させる
            filter = ""; searchInput.value = "";
            render();
          }
        })
      ]));
    }

    function renderEffectCard(entry) {
      const model = entry.model;
      // 儀式エフェクトIDはコード側(儀式ゲート/効果解決)と直接紐づくため、保存済みエントリは改名不可
      // (読み取り専用表示)。ただし baseSnapshot(=フォームを開いた時点のデータ)に存在しない
      // 「このセッションで新規追加され未保存」のIDに限り、確定前の自動採番idを編集できる。
      const isNewUnsaved = !originalEffectIds.has(entry.id);
      const idInput = h("input", {
        class: `field-input entry-id${isNewUnsaved ? "" : " is-readonly"}`, value: entry.id, spellcheck: "false",
        readonly: !isNewUnsaved,
        title: isNewUnsaved ? "未保存の新規エントリ: 保存前ならID変更可" : "IDはプログラムと紐づくため変更不可"
      });
      if (isNewUnsaved) {
        idInput.addEventListener("change", (ev) => {
          const nv = ev.target.value.trim();
          if (!nv || nv === entry.id) { ev.target.value = entry.id; return; }
          if (effectModels.some((x) => x.id === nv)) { alert("同じidが存在します"); ev.target.value = entry.id; return; }
          const oldId = entry.id;
          entry.id = nv; model.id = nv;
          if (expandedEffects.has(oldId)) { expandedEffects.delete(oldId); expandedEffects.add(nv); }
        });
      }
      const plainName = (window.stripDisplayNamePlain
        ? window.stripDisplayNamePlain(model.name)
        : model.name) || entry.id;
      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: plainName }),
          h("span", { class: "entry-sum-id", text: entry.id }),
          h("span", { class: "entry-sum-meta", text: model.effectType || "craft" })
        ]),
        h("div", { class: "entry-collapse-edit" }, [
          h("span", { class: "entry-key-label", text: "id" }), idInput,
          h("div", { class: "spacer" }),
          h("button", {
            class: "btn-small", type: "button", text: "複製",
            onclick: () => {
              const copyId = uniqueId(entry.id + "_copy", effectModels);
              const dup = CORE.parseEffectEntry(copyId, CORE.serializeEffectEntry(model));
              dup.id = copyId;
              const idx = effectModels.indexOf(entry);
              effectModels.splice(idx + 1, 0, { id: copyId, model: dup });
              render();
            }
          }),
          h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: () => { effectModels.splice(effectModels.indexOf(entry), 1); render(); } })
        ])
      ];

      const body = h("div", { class: "recipe-body ritual-layout" });
      // name (儀式エフェクトは name をヘッダ直下に主表示)。描画は読み取り専用: 値を編集した
      // ときにのみ has.* を立て、name/effect-type を持たない合成エントリにキーを増やさない。
      body.appendChild(fieldRow("name", window.textInput(model.name, (v) => { model.name = v; model.has.name = true; }), "表示名 (name)"));
      // effect-type
      body.appendChild(fieldRow("effect-type", labeledSelect(model.effectType, EFFECT_TYPES, "effect-type", (v) => { model.effectType = v; model.has["effect-type"] = true; if (v !== "craft") { model.has["effect-params"] = true; if (!model.effectParams) model.effectParams = {}; } renderList(); }), "エフェクト種別"));
      if (model.has["effect-params"] || model.effectType !== "craft") body.appendChild(renderEffectParams(model));
      // コア/台座/source 共用部品
      body.appendChild(buildCoreSection(model, renderList, { coreLabel: "コアアイテム" }));
      body.appendChild(buildPedestalSection(model, renderList));
      body.appendChild(buildSourceRow(model, {}));
      // result 指定UIは廃止 (儀式エフェクトはワールド効果専門。アイテム生成は items の儀式レシピで行う)。
      // 既存データに result があってもここでは触らず温存する (ロスレス往復)。

      const el = window.collapsibleCard(head, [body], {
        expanded: expandedEffects.has(entry.id),
        onToggle: (open) => { if (open) expandedEffects.add(entry.id); else expandedEffects.delete(entry.id); }
      });
      el.classList.add("recipe-card"); // style.css の .recipe-card .entry-head レイアウトを維持する
      return el;
    }

    // ---- 共通部品 ----
    function emptyGuide(title, hint) {
      return h("div", { class: "empty-guide" }, [
        h("div", { class: "empty-guide-title", text: title }),
        h("div", { class: "empty-guide-hint", text: hint })
      ]);
    }

    root.appendChild(tabBar);
    root.appendChild(h("div", { class: "recipe-toolbar" }, [searchInput]));
    root.appendChild(areaBox);
    render();

    function getData() {
      const out = {};
      // エントリ単位の verbatim ガード: 再シリアライズ結果が原文と値等価なら原文をそのまま出力し、
      // キー順・キー構成・記法を完全保存する (未編集エントリのロスレス保証)。編集済みなら再生成値。
      const buildItems = () => {
        const m = {};
        for (const e of itemModels) {
          const fresh = CORE.serializeItemEntry(e.model);
          m[e.id] = (e.model._origEntry && CORE.deepEqualUnordered(fresh, e.model._origEntry))
            ? CORE.clone(e.model._origEntry) : fresh;
        }
        return m;
      };
      const buildEffects = () => {
        const m = {};
        for (const e of effectModels) {
          const fresh = CORE.serializeEffectEntry(e.model);
          m[e.id] = (e.model._origEntry && CORE.deepEqualUnordered(fresh, e.model._origEntry))
            ? CORE.clone(e.model._origEntry) : fresh;
        }
        return m;
      };
      for (const k of topKeys) {
        if (k === "items") out.items = buildItems();
        else if (k === "ritual_effects") out.ritual_effects = buildEffects();
        else out[k] = extraTop[k];
      }
      return out;
    }

    return {
      element: root,
      getData,
      setActiveTab: (area) => {
        if (!area) return;
        if (onlyEffects) activeArea = "ritual_effects";
        else activeArea = area;
        render();
      }
    };
  };
})(typeof window !== "undefined" ? window : (typeof module !== "undefined" ? module.exports : this), typeof window !== "undefined" && typeof document !== "undefined");
