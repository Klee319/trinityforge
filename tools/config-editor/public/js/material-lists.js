"use strict";

// 素材互換リスト (items/material-lists.yml) の共有UI。
// レシピ素材欄の「互換リスト」ボタン (forms.js ingredientMaterialControl) から呼ばれる。
//
//   window.MaterialListsUI.describe(listId) -> Promise<{id,label,count,materials}|null>
//     チップ表示用のリスト概要 (キャッシュあり)。
//   window.MaterialListsUI.openManager({selectedListId, onSelect})
//     リスト選択/新規作成/編集モーダル。選択で onSelect(listId)、解除で onSelect(null)。
//
// 保存は PUT /api/config/material-lists (expectedRevision 楽観ロック)。レシピ側には
// "list:<id>" トークンが入るだけなので、リスト編集はレシピ保存とは独立に即保存する。

(function () {
  const h = window.h;

  function notify(message, kind) {
    if (typeof window.toast === "function") { window.toast(message, kind); return; }
    if (kind === "error") window.alert(message);
  }

  async function apiCall(method, url, body) {
    const opts = { method, headers: { "Content-Type": "application/json" } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const res = await fetch(url, opts);
    const json = await res.json().catch(() => ({}));
    if (!res.ok) {
      // スキーマ検証エラー等は details に行単位の理由が入るため、そのまま見せる。
      const detail = Array.isArray(json.details) && json.details.length
        ? `: ${json.details.join(" / ")}` : "";
      const err = new Error((json.error || res.statusText) + detail);
      err.payload = json;
      throw err;
    }
    return json;
  }

  // { lists: {id: {label?, materials[]}}, revision } のキャッシュ。モーダルを開くたびに更新する。
  let cache = null;

  async function fetchLists(force) {
    if (cache && !force) return cache;
    const r = await apiCall("GET", "/api/config/material-lists");
    const data = r.data && typeof r.data === "object" ? r.data : {};
    const lists = data.lists && typeof data.lists === "object" && !Array.isArray(data.lists) ? data.lists : {};
    cache = { lists, revision: r.revision };
    return cache;
  }

  // 戻り値: {ok, conflict}。conflict=true のとき呼び出し側は必ず一覧を再読込すること。
  // cache が無い(=直前にconflictした/未fetch)状態では expectedRevision 無し保存 =
  // 楽観ロックのバイパスになるため、保存自体を拒否する。
  async function saveLists(lists) {
    if (!cache) {
      notify("互換リストが最新ではありません。一覧を読み直してから、もう一度操作してください。", "error");
      return { ok: false, conflict: true };
    }
    const body = { data: { lists }, expectedRevision: cache.revision };
    try {
      const r = await apiCall("PUT", "/api/config/material-lists", body);
      cache = { lists, revision: r.revision };
      return { ok: true, conflict: false };
    } catch (err) {
      if (err.payload && err.payload.conflict) {
        // 他編集者が先に保存: キャッシュを破棄し、呼び出し側に一覧再読込させる。
        cache = null;
        notify("他の編集者が互換リストを先に保存しました。最新の一覧を読み直します。もう一度操作してください。", "error");
        return { ok: false, conflict: true };
      }
      notify(`互換リストの保存に失敗しました: ${err.message}`, "error");
      return { ok: false, conflict: false };
    }
  }

  function describeFrom(lists, listId) {
    const entry = lists[listId];
    if (!entry || typeof entry !== "object") return null;
    const materials = Array.isArray(entry.materials) ? entry.materials.filter((m) => typeof m === "string" && m) : [];
    return { id: listId, label: entry.label || listId, count: materials.length, materials };
  }

  // チップ表示用のリスト概要。通信失敗と「リストが本当に存在しない」を区別して返す:
  //   {status:"ok", id, label, count, materials} | {status:"missing"} | {status:"error"}
  async function describe(listId) {
    if (!listId) return { status: "missing" };
    try {
      const { lists } = await fetchLists(false);
      const d = describeFrom(lists, listId);
      return d ? Object.assign({ status: "ok" }, d) : { status: "missing" };
    } catch (_) {
      return { status: "error" };
    }
  }

  // catalog.yml 内で list:<id> を参照しているレシピ数を数える (削除confirm用)。
  // 取得失敗時は null (呼び出し側は件数表示を省略する)。
  function countListRefsInRecipe(recipe, token) {
    if (!recipe || typeof recipe !== "object") return 0;
    let count = 0;
    const ing = recipe.ingredients;
    if (Array.isArray(ing)) {
      if (ing.some((v) => typeof v === "string" && v.trim().toLowerCase() === token)) count = 1;
    } else if (ing && typeof ing === "object") {
      if (Object.values(ing).some((v) => typeof v === "string" && v.trim().toLowerCase() === token)) count = 1;
    }
    return count;
  }

  async function countCatalogRefs(listId) {
    try {
      const r = await apiCall("GET", "/api/config/catalog");
      const items = r.data && r.data.items && typeof r.data.items === "object" ? r.data.items : {};
      const token = ("list:" + listId).toLowerCase();
      let count = 0;
      for (const entry of Object.values(items)) {
        if (!entry || typeof entry !== "object") continue;
        count += countListRefsInRecipe(entry.recipe, token);
        if (Array.isArray(entry.recipes)) {
          for (const rec of entry.recipes) count += countListRefsInRecipe(rec, token);
        }
      }
      return count;
    } catch (_) {
      return null;
    }
  }

  const LIST_ID_RE = /^[a-z0-9_]+$/;

  // ---- リスト編集フォーム (新規/既存共用) ----
  // onSaved(listId) は保存成功時に呼ぶ。onCancel() で一覧へ戻る。
  // onConflict() は他編集者との競合時に呼ぶ (最新一覧の再読込)。
  function buildEditForm(lists, editId, onSaved, onCancel, onConflict) {
    const isNew = editId == null;
    const entry = isNew ? { label: "", materials: [] } : (lists[editId] || { materials: [] });
    const working = {
      id: isNew ? "" : editId,
      label: typeof entry.label === "string" ? entry.label : "",
      materials: Array.isArray(entry.materials) ? entry.materials.slice() : []
    };
    if (working.materials.length === 0) working.materials.push("");

    const form = h("div", { class: "material-list-edit" });
    form.appendChild(h("div", { class: "mini-label", text: isNew ? "新規リスト作成" : `リスト編集: ${editId}` }));

    const idInput = h("input", {
      class: "field-input", type: "text", spellcheck: "false", value: working.id,
      placeholder: "リストID (例: planks)"
    });
    idInput.disabled = !isNew; // IDはレシピの list:<id> 参照キーのため既存は変更不可
    idInput.addEventListener("input", () => { working.id = idInput.value.trim(); });
    const labelInput = h("input", {
      class: "field-input", type: "text", value: working.label,
      placeholder: "表示名 (例: 板材)"
    });
    labelInput.addEventListener("input", () => { working.label = labelInput.value; });
    form.appendChild(h("div", { class: "stat-row" }, [h("span", { class: "mini-label", text: "ID" }), idInput]));
    form.appendChild(h("div", { class: "stat-row" }, [h("span", { class: "mini-label", text: "表示名" }), labelInput]));
    if (!isNew) {
      form.appendChild(h("div", { class: "field-desc", text: "IDはレシピから list:" + editId + " として参照されるため変更できません。" }));
    }

    const rowsBox = h("div", { class: "pedestal-rows" });
    function renderRows() {
      rowsBox.textContent = "";
      working.materials.forEach((mat, idx) => {
        const row = h("div", { class: "stat-row" });
        row.appendChild(window.materialInput(mat, "material-list", (v) => {
          const raw = v == null ? "" : String(v).trim();
          working.materials[idx] = /^custom:/i.test(raw) ? raw.toLowerCase() : raw.toUpperCase();
        }, { allowCustom: true }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { working.materials.splice(idx, 1); renderRows(); }
        }));
        rowsBox.appendChild(row);
      });
    }
    renderRows();
    form.appendChild(h("div", { class: "mini-label", text: "対象アイテム (Material / custom:カタログID・外部ID。互いに互換とみなす)" }));
    form.appendChild(rowsBox);
    form.appendChild(h("button", {
      class: "btn-small", type: "button", text: "+ アイテム追加",
      onclick: () => { working.materials.push(""); renderRows(); }
    }));

    const saveBtn = h("button", { class: "btn-small primary", type: "button", text: "保存" });
    saveBtn.addEventListener("click", async () => {
      const id = working.id.trim();
      if (!LIST_ID_RE.test(id)) {
        notify("リストIDは半角英小文字/数字/アンダースコアで入力してください (例: planks)", "error");
        return;
      }
      if (isNew && lists[id]) {
        notify(`リストID "${id}" は既に存在します`, "error");
        return;
      }
      const materials = [];
      for (const m of working.materials) {
        const name = String(m || "").trim().toUpperCase();
        if (name && !materials.includes(name)) materials.push(name);
      }
      if (materials.length === 0) {
        notify("アイテムを1個以上指定してください", "error");
        return;
      }
      saveBtn.disabled = true;
      try {
        const next = Object.assign({}, lists);
        const nextEntry = { materials };
        if (working.label.trim()) nextEntry.label = working.label.trim();
        next[id] = nextEntry;
        const r = await saveLists(next);
        if (r.ok) {
          notify(`互換リスト「${nextEntry.label || id}」を保存しました (${materials.length}種)`, "ok");
          onSaved(id);
        } else if (r.conflict && typeof onConflict === "function") {
          onConflict();
        }
      } finally {
        saveBtn.disabled = false;
      }
    });
    const actions = h("div", { class: "modal-actions" }, [
      saveBtn,
      h("button", { class: "btn-small", type: "button", text: "戻る", onclick: onCancel })
    ]);
    form.appendChild(actions);
    return form;
  }

  // ---- 選択/管理モーダル ----
  function openManager(opts) {
    const options = opts || {};
    const overlay = h("div", { class: "modal-overlay" });
    // onClose はモーダルを閉じた時に必ず呼ぶ (選択せず編集だけして閉じた場合でも、
    // 開いていた素材チップの種数/ツールチップを最新化させるため)。
    const cleanup = () => {
      if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
      if (typeof options.onClose === "function") options.onClose();
    };
    const box = h("div", { class: "modal-box modal-box-wide" });
    box.appendChild(h("div", { class: "modal-title", text: "素材互換リスト" }));
    box.appendChild(h("div", { class: "modal-text", text: "この素材欄に割り当てる互換リストを選択するか、新しいリストを作成します。リストのどのMaterialでも素材として認められます。" }));
    const body = h("div", { class: "material-list-manager" });
    box.appendChild(body);
    overlay.appendChild(box);
    overlay.addEventListener("click", (e) => { if (e.target === overlay) cleanup(); });
    document.body.appendChild(overlay);

    function materialNames(materials) {
      const L = window.LABELS;
      return materials.map((m) => {
        const ja = L && typeof L.materialLabel === "function" ? L.materialLabel(m) : "";
        return ja ? `${ja} (${m})` : m;
      });
    }

    function renderPicker(lists) {
      body.textContent = "";
      const ids = Object.keys(lists);
      if (ids.length === 0) {
        body.appendChild(h("div", { class: "empty-hint", text: "互換リストはまだありません。下のボタンから作成してください。" }));
      }
      for (const id of ids) {
        const d = describeFrom(lists, id);
        if (!d) continue;
        const row = h("div", { class: "stat-row material-list-row" });
        const isSelected = options.selectedListId === id;
        row.appendChild(h("span", {
          class: "material-list-name" + (isSelected ? " primary" : ""),
          text: `${d.label} (${id}) — ${d.count}種`,
          title: materialNames(d.materials).join("\n")
        }));
        if (typeof options.onSelect === "function") {
          row.appendChild(h("button", {
            class: "btn-small" + (isSelected ? " primary" : ""), type: "button",
            text: isSelected ? "選択中" : "選択",
            onclick: () => { options.onSelect(id); cleanup(); }
          }));
        }
        row.appendChild(h("button", {
          class: "btn-small", type: "button", text: "編集",
          onclick: () => {
            body.textContent = "";
            body.appendChild(buildEditForm(lists, id, () => reload(), () => renderPicker(lists), () => reload()));
          }
        }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: async () => {
            const refCount = await countCatalogRefs(id);
            const refLine = refCount == null
              ? "参照レシピ数の取得に失敗しました。参照が残っている可能性があります。"
              : (refCount > 0
                ? `現在カタログの ${refCount} 件のレシピが list:${id} を参照しています。削除すると起動/リロード時に警告つきで無効化されます。`
                : "このリストを参照しているカタログレシピはありません。");
            if (!window.confirm(`互換リスト「${d.label}」を削除します。\n${refLine}\nよろしいですか？`)) return;
            const next = Object.assign({}, lists);
            delete next[id];
            const r = await saveLists(next);
            if (r.ok) {
              notify(`互換リスト「${d.label}」を削除しました`, "ok");
              reload();
            } else if (r.conflict) {
              reload();
            }
          }
        }));
        body.appendChild(row);
      }
      const actions = h("div", { class: "modal-actions" });
      actions.appendChild(h("button", {
        class: "btn-small", type: "button", text: "＋新規リスト作成",
        onclick: () => {
          body.textContent = "";
          // 作成後は一覧へ戻る (そのままこの素材欄へ割り当て可能。自動選択はしない)。
          body.appendChild(buildEditForm(lists, null, () => reload(), () => renderPicker(lists), () => reload()));
        }
      }));
      if (options.selectedListId && typeof options.onSelect === "function") {
        actions.appendChild(h("button", {
          class: "btn-small", type: "button", text: "割当を解除",
          title: "この素材欄をリスト参照から通常のMaterial指定へ戻します",
          onclick: () => { options.onSelect(null); cleanup(); }
        }));
      }
      actions.appendChild(h("button", { class: "btn-small", type: "button", text: "閉じる", onclick: cleanup }));
      body.appendChild(actions);
    }

    async function reload() {
      body.textContent = "";
      body.appendChild(h("div", { class: "empty-hint", text: "読み込み中..." }));
      try {
        const { lists } = await fetchLists(true);
        renderPicker(lists);
      } catch (err) {
        body.textContent = "";
        body.appendChild(h("div", { class: "empty-hint", text: `互換リストの読み込みに失敗しました: ${err.message}` }));
      }
    }

    reload();
  }

  window.MaterialListsUI = {
    describe,
    openManager,
    invalidate() { cache = null; }
  };
})();
