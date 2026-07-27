"use strict";

// 汎用JSONツリーエディタ。ネストしたマップ/配列/スカラーを追加・削除・型保持しながら編集する。
// 専用フォームが無い全configのフォールバック。working オブジェクトを直接編集し、
// 構造変更時のみサブツリーを再描画する。
//
// P5 の軽微改善(§3.10): 深いネストを畳めるよう gen-collection-head クリックで開閉する
// (既定は深さ2以降を閉じる)。常時表示だった型セレクトは「⋯」メニューへ退避し、
// 通常編集時の視覚ノイズを減らす。フォールバック用途のため過剰投資はしない。

(function () {
  // 深さ2以降のコレクションは初期状態で閉じる (トップとその直下だけ開く)。
  const COLLAPSE_FROM_DEPTH = 2;

  function valueType(v) {
    if (v === null) return "null";
    if (Array.isArray(v)) return "array";
    if (typeof v === "object") return "map";
    if (typeof v === "number") return "number";
    if (typeof v === "boolean") return "boolean";
    return "string";
  }

  function defaultForType(type) {
    switch (type) {
      case "map": return {};
      case "array": return [];
      case "number": return 0;
      case "boolean": return false;
      case "null": return null;
      default: return "";
    }
  }

  // 型変更セレクトを「⋯」メニューへ退避する共通部品。
  // 既定は非表示。ボタンで開閉し、型を選ぶと onPick(type) を呼ぶ。
  function typeMenu(currentType, onPick) {
    const menu = window.h("div", { class: "gen-type-menu" });
    const sel = window.selectInput(currentType, ["string", "number", "boolean", "null", "map", "array"], (t) => onPick(t));
    sel.classList.add("gen-type");
    const panel = window.h("div", { class: "gen-type-panel" }, [
      window.h("span", { class: "mini-label", text: "型" }), sel
    ]);
    const btn = window.h("button", {
      class: "gen-type-btn", type: "button", title: "値の型を変える", text: "⋯",
      onclick: (e) => {
        e.stopPropagation();
        panel.classList.toggle("open");
      }
    });
    menu.appendChild(btn);
    menu.appendChild(panel);
    return menu;
  }

  // container の中身を、node(value) に対応するUIで再描画する。
  // onReplace(newValue): このノードの値を親側で差し替える。
  function renderNode(container, value, onReplace, depth) {
    container.innerHTML = "";
    const type = valueType(value);

    if (type === "map" || type === "array") {
      container.appendChild(renderCollection(value, type, onReplace, depth || 0));
    } else {
      container.appendChild(renderScalar(value, type, onReplace));
    }
  }

  function renderScalar(value, type, onReplace) {
    const wrap = window.h("span", { class: "gen-scalar" });

    let input;
    if (type === "boolean") {
      input = window.checkboxInput(value, (v) => onReplace(v));
    } else if (type === "null") {
      input = window.h("span", { class: "gen-null", text: "null" });
    } else if (type === "number") {
      input = window.numberInput(value, (v) => onReplace(v === null ? 0 : v));
    } else {
      input = window.textInput(value, (v) => onReplace(v));
    }
    wrap.appendChild(input);
    wrap.appendChild(typeMenu(type, (t) => onReplace(defaultForType(t), true)));
    return wrap;
  }

  function renderCollection(value, type, onReplace, depth) {
    const box = window.h("div", { class: "gen-collection" });
    const head = window.h("div", { class: "gen-collection-head" });

    const caret = window.h("span", { class: "gen-caret", text: "▾" });
    const count = type === "map" ? Object.keys(value).length : value.length;
    head.appendChild(caret);
    head.appendChild(window.h("span", { class: "gen-badge", text: type === "map" ? "マップ" : "配列" }));
    head.appendChild(window.h("span", { class: "gen-count", text: `${count}件` }));
    head.appendChild(typeMenu(type, (t) => onReplace(defaultForType(t), true)));
    box.appendChild(head);

    const body = window.h("div", { class: "gen-body" });

    if (type === "map") {
      for (const key of Object.keys(value)) {
        body.appendChild(renderMapRow(value, key, onReplace, box, depth));
      }
      const addBtn = window.h("button", {
        class: "btn-small", type: "button", text: "+ キー追加",
        onclick: () => {
          let name = "new_key";
          let i = 1;
          while (Object.prototype.hasOwnProperty.call(value, name)) { name = `new_key_${i++}`; }
          value[name] = "";
          rerenderCollection(box, value, type, onReplace, depth);
        }
      });
      body.appendChild(addBtn);
    } else {
      value.forEach((item, idx) => {
        body.appendChild(renderArrayRow(value, idx, onReplace, box, depth));
      });
      const addBtn = window.h("button", {
        class: "btn-small", type: "button", text: "+ 要素追加",
        onclick: () => {
          value.push("");
          rerenderCollection(box, value, type, onReplace, depth);
        }
      });
      body.appendChild(addBtn);
    }

    box.appendChild(body);

    // ---- 開閉 (深さ2以降は初期状態で閉じる) ----
    const startCollapsed = depth >= COLLAPSE_FROM_DEPTH && count > 0;
    const setCollapsed = (collapsed) => {
      box.classList.toggle("collapsed", collapsed);
      body.style.display = collapsed ? "none" : "";
      caret.textContent = collapsed ? "▸" : "▾";
    };
    setCollapsed(startCollapsed);
    head.addEventListener("click", (e) => {
      // 型メニュー等のボタン操作ではトグルしない。
      if (e.target.closest && e.target.closest(".gen-type-menu")) return;
      setCollapsed(!box.classList.contains("collapsed"));
    });

    return box;
  }

  function rerenderCollection(box, value, type, onReplace, depth) {
    const fresh = renderCollection(value, type, onReplace, depth);
    box.replaceWith(fresh);
  }

  // 汎用エディタのキーに、辞書にある日本語名(ステ名/フィールド名)をヒント表示する。
  function knownKeyLabel(key) {
    const L = window.LABELS;
    if (!L || !key) return "";
    const stat = L.statLabel(key);
    if (stat && stat !== key) return stat;
    const field = L.fieldLabel(key);
    if (field && field !== key) return field;
    return "";
  }

  function renderMapRow(mapValue, key, onReplace, box, depth) {
    const row = window.h("div", { class: "gen-row" });
    const keyHint = window.h("span", { class: "stat-hint" });
    const updateHint = (k) => { const ja = knownKeyLabel(k); keyHint.textContent = ja; keyHint.title = ja ? `${ja} (${k})` : ""; };
    updateHint(key);
    const keyInput = window.h("input", {
      class: "field-input gen-key", value: key, spellcheck: "false",
      oninput: (e) => updateHint(e.target.value),
      onchange: (e) => {
        const newKey = e.target.value;
        if (newKey === key || newKey === "") { e.target.value = key; return; }
        if (Object.prototype.hasOwnProperty.call(mapValue, newKey)) { alert("同名キーが既に存在します"); e.target.value = key; return; }
        // 挿入順を保つため作り直す
        const rebuilt = {};
        for (const k of Object.keys(mapValue)) {
          rebuilt[k === key ? newKey : k] = mapValue[k];
        }
        for (const k of Object.keys(mapValue)) delete mapValue[k];
        Object.assign(mapValue, rebuilt);
        rerenderCollection(box, mapValue, "map", onReplace, depth);
      }
    });
    row.appendChild(keyInput);
    row.appendChild(keyHint);
    row.appendChild(window.h("span", { class: "gen-colon", text: ":" }));

    const valueHolder = window.h("div", { class: "gen-value-holder" });
    renderNode(valueHolder, mapValue[key], function selfReplace(newVal, structural) {
      mapValue[key] = newVal;
      if (structural) renderNode(valueHolder, mapValue[key], selfReplace, depth + 1);
    }, depth + 1);
    row.appendChild(valueHolder);

    row.appendChild(window.h("button", {
      class: "btn-small danger", type: "button", text: "削除",
      onclick: () => { delete mapValue[key]; rerenderCollection(box, mapValue, "map", onReplace, depth); }
    }));
    return row;
  }

  function renderArrayRow(arrValue, idx, onReplace, box, depth) {
    const row = window.h("div", { class: "gen-row" });
    row.appendChild(window.h("span", { class: "gen-index", text: `[${idx}]` }));

    const valueHolder = window.h("div", { class: "gen-value-holder" });
    renderNode(valueHolder, arrValue[idx], function selfReplace(newVal, structural) {
      arrValue[idx] = newVal;
      if (structural) renderNode(valueHolder, arrValue[idx], selfReplace, depth + 1);
    }, depth + 1);
    row.appendChild(valueHolder);

    row.appendChild(window.h("button", {
      class: "btn-small danger", type: "button", text: "削除",
      onclick: () => { arrValue.splice(idx, 1); rerenderCollection(box, arrValue, "array", onReplace, depth); }
    }));
    return row;
  }

  // 公開: 汎用エディタを構築。working を直接編集する。getData() は working を返す。
  window.buildGenericEditor = function buildGenericEditor(initialData) {
    // ルート値は可変コンテナ(rootValue)で保持し、型変更後も getData() が最新値を返すようにする。
    let rootValue = initialData && typeof initialData === "object" ? initialData : {};
    const root = window.h("div", { class: "gen-root" });
    const holder = window.h("div");
    renderNode(holder, rootValue, function rootReplace(newVal, structural) {
      rootValue = newVal;
      if (structural) renderNode(holder, rootValue, rootReplace, 0);
    }, 0);
    root.appendChild(holder);
    return { element: root, getData: () => rootValue };
  };
})();
