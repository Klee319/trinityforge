"use strict";

// CMD (CustomModelData) 自動割当 + テクスチャ登録の共有UI部品。
// forms.js / ars-forms.js / ars-spellbooks.js / ars-source-forms.js から呼ばれる。
//
//   window.cmdAutoAssignButton({getMaterial, getId, source, onAssigned})
//     「CMD自動割当」ボタン。押すと POST /api/cmd/allocate → onAssigned(cmd)。
//   window.cmdTextureControl({getMaterial, getCmd, getId, source})
//     PNGアップロード+状態チップ。CMD未設定なら先に自動割当してからアップロードする(1クリック)。

(function () {
  const h = window.h;

  // app.js の toast はモジュール内クロージャのため window.toast として公開してもらう想定。
  // 未公開環境 (単体プレビュー等) でも動くよう、フォールバックの簡易通知を用意する。
  function notify(message, kind) {
    if (typeof window.toast === "function") {
      window.toast(message, kind);
      return;
    }
    if (kind === "error") window.alert(message);
  }

  async function apiCall(method, url, body) {
    const opts = { method, headers: { "Content-Type": "application/json" } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const res = await fetch(url, opts);
    const json = await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error(json.error || res.statusText);
      // 変換エラーの明細をUI側で箇条書きにできるよう、Errorへ載せ替えて伝搬させる。
      if (Array.isArray(json.conversionErrors)) err.conversionErrors = json.conversionErrors;
      // revision 楽観ロックの 409 を呼び出し側で判別するため、状態とペイロードも載せる
      // (respack-view.js の apiCall と同じ契約に揃える)。
      err.status = res.status;
      err.payload = json;
      throw err;
    }
    return json;
  }

  // revision付き楽観ロックPUT (app.js の putConfig / respack-view.js の同名関数と同じ契約)。
  // ⚠ 2026-08-25: expectedRevision は (undefinedでない限り) null でもそのまま送る。
  // 「!= null の時だけ送る」と、GET時点でファイル未作成(revision:null)だったケースで
  // 楽観ロックが一切効かなくなる(外部プロセスの新規作成を無条件で上書きしてしまう)。
  // expectedRevision が呼び出し元から渡されない(undefined)場合は JSON.stringify が
  // キーごと省く自然な挙動に任せる。
  async function putConfigRevision(configId, data, expectedRevision) {
    const body = { data, expectedRevision };
    try {
      const r = await apiCall("PUT", `/api/config/${configId}`, body);
      return { ok: true, revision: r.revision };
    } catch (err) {
      if (err.status === 409 && err.payload && err.payload.conflict) return { ok: false, conflict: true };
      throw err;
    }
  }

  /**
   * カタログ品に CMD が未割当なら、その場で採番して catalog.yml へ即保存する。
   *
   * なぜ必要か: item-stats.yml のキーは `MATERIAL#CMD` で、CMD が空だと素の Material に退化する。
   * すると (a) バニラの同素材アイテム全部にステが効いてしまい狙ったカタログ品を指せない、
   * (b) 既存の素 Material エントリ(バニラ用の 77 件)と衝突して「同じキーが既に存在します」で
   * 追加そのものができない。新規追加したカタログ品は CMD 未割当なので必ず後者を踏む。
   * 単一アイテムを狙うには CMD が必須なので、ここで採番するのが唯一の正しい解決になる。
   *
   * catalog.yml へ即保存するのは、台帳と item-stats のキーだけ `#123` になって catalog.yml が
   * 未割当のまま残ると、**そのステータスが実物のアイテムに一生マッチしない**半端な状態になるため。
   *
   * @returns {Promise<number|null>} 割り当て済み/新規採番した CMD。中止・失敗時は null。
   */
  window.cmdEnsureCatalogItemCmd = async function cmdEnsureCatalogItemCmd(opts) {
    const options = opts || {};
    const id = String(options.id || "").trim();
    const material = String(options.material || "").trim().toUpperCase();
    if (!id || !material) {
      notify("カタログIDと material が確定していないためCMDを割り当てられません", "error");
      return null;
    }

    const readCatalog = async () => {
      const res = await apiCall("GET", "/api/config/catalog");
      const data = res && res.data && typeof res.data === "object" ? res.data : {};
      const items = data.items && typeof data.items === "object" ? data.items : {};
      return { data, revision: res ? res.revision : null, entry: items[id] || null };
    };

    try {
      const first = await readCatalog();
      if (!first.entry) {
        // functional-items / sourcejars / 触媒など catalog.yml 以外を出自とする候補。
        notify(`catalog.yml に id "${id}" が見つかりません。出自のファイルでCMDを割り当ててください`, "error");
        return null;
      }
      const already = first.entry["custom-model-data"];
      if (typeof already === "number") return already; // 既に割当済み: 何も聞かずそのまま使う

      if (!window.confirm(
        `「${id}」(${material}) にはまだCMD(CustomModelData)が割り当てられていません。\n\n`
        + `CMDが無いとステータスのキーが素の ${material} になり、バニラの ${material} すべてに\n`
        + `効いてしまうため、このアイテム単体を指定できません。\n\n`
        + "いま未使用のCMDを1つ採番して catalog.yml へ保存します。\n"
        + "一度払い出した番号は再利用されません。よろしいですか？"
      )) return null;

      // 確認ダイアログの表示中に他画面が catalog.yml を保存していると revision が古くなる。
      // 採番より前に revision を取り直す (先に採番すると 409 で台帳の番号だけ捨てることになる)。
      const fresh = await readCatalog();
      if (!fresh.entry) {
        notify(`catalog.yml から id "${id}" が消えています。画面を再読込してください`, "error");
        return null;
      }
      const raced = fresh.entry["custom-model-data"];
      if (typeof raced === "number") return raced; // 待っている間に他画面が割り当てた

      const alloc = await apiCall("POST", "/api/cmd/allocate", { material, id, source: "catalog" });
      const cmd = alloc && alloc.cmd;
      if (typeof cmd !== "number") {
        notify("CMDの採番結果が不正でした", "error");
        return null;
      }
      fresh.entry["custom-model-data"] = cmd;
      const put = await putConfigRevision("catalog", fresh.data, fresh.revision);
      if (!put.ok) {
        // 台帳には番号が残る (respack-view の一括採番と同じ既知の限界)。
        // 次の reconcile で config に無い行は落ち、欠番は再利用される。
        notify(`CMD ${cmd} は採番しましたが、他で編集中のため catalog.yml に保存できませんでした。`
          + "画面を再読込してやり直してください", "error");
        return null;
      }
      notify(`CMD ${cmd} を「${id}」へ割り当てて catalog.yml に保存しました`, "ok");
      return cmd;
    } catch (err) {
      notify(`CMDの割当に失敗しました: ${err.message}`, "error");
      return null;
    }
  };

  // カタログを開き直しても、登録済みテクスチャの状態を台帳から復元する。
  // 以前は各コントロールが常に「未登録」で初期化され、POST直後だけ表示が正しかった。
  // 一覧をコントロールごとに取得しないよう、画面内では1回だけ共有する。
  let textureStatusPromise = null;
  let textureStatusRows = [];

  function loadTextureStatus() {
    if (!textureStatusPromise) {
      textureStatusPromise = apiCall("GET", "/api/respack/status")
        .then((status) => {
          textureStatusRows = Array.isArray(status.allocations) ? status.allocations : [];
          return textureStatusRows;
        })
        .catch(() => {
          textureStatusRows = [];
          return textureStatusRows;
        });
    }
    return textureStatusPromise;
  }

  function updateTextureStatusCache(row) {
    const idx = textureStatusRows.findIndex((current) => current.material === row.material && current.cmd === row.cmd);
    if (idx >= 0) textureStatusRows[idx] = { ...textureStatusRows[idx], ...row };
    else textureStatusRows.push(row);
  }

  // 配線済みテクスチャのプレビュー (material#cmd → Promise) の共有キャッシュ。
  // force=true で再取得 (テクスチャ/モデル登録直後の表示更新用)。
  const texturePreviewCache = new Map();
  function loadTexturePreview(material, cmd, force) {
    const key = `${material}#${cmd}`;
    if (force) texturePreviewCache.delete(key);
    if (!texturePreviewCache.has(key)) {
      texturePreviewCache.set(
        key,
        apiCall("GET", `/api/respack/preview?material=${encodeURIComponent(material)}&cmd=${encodeURIComponent(cmd)}`)
          .catch(() => ({ hasTexture: false, textures: [] }))
      );
    }
    return texturePreviewCache.get(key);
  }

  // プレビューのサムネイル群を生成する。PNG実体があるもの最大3枚をピクセルアート拡大で表示。
  // minecraft: 参照のみのモデル (バニラ資産流用) は参照名テキストのみ表示する。
  function buildPreviewThumbs(preview, size) {
    const wrap = h("span", { class: "cmd-preview-thumbs" });
    if (!preview || !preview.hasTexture) return wrap;
    const withPng = (preview.textures || []).filter((t) => t && t.pngBase64);
    for (const t of withPng.slice(0, 3)) {
      wrap.appendChild(h("img", {
        class: "cmd-preview-img" + (size === "lg" ? " cmd-preview-img-lg" : ""),
        src: `data:image/png;base64,${t.pngBase64}`,
        title: t.ref,
        alt: t.name || ""
      }));
    }
    if (!withPng.length && (preview.textures || []).length) {
      wrap.appendChild(h("span", {
        class: "cmd-preview-refonly",
        title: "バニラ資産参照のためパック内にPNG実体がありません",
        text: preview.textures[0].ref
      }));
    }
    // 2026-07-25: 「登録済みなのに真っ白」を無言にしない。モデルは在るのに解決できる参照が
    // 1件も無い場合は、原因(Blockbenchプロジェクト誤投入 / 参照名の不一致)を明示する。
    if (preview.broken) {
      wrap.appendChild(h("span", {
        class: "cmd-preview-broken",
        title: preview.blockbenchProject
          ? "Blockbenchのプロジェクトファイルがそのまま登録されています。再アップロードすると自動変換されます"
          : "モデルが参照するテクスチャがパック内に見つかりません",
        text: preview.blockbenchProject ? "⚠ 未変換のBlockbenchプロジェクト" : "⚠ テクスチャ未解決"
      }));
    }
    return wrap;
  }

  // respack-view など他画面からの再利用用。
  window.cmdPreviewHelpers = { loadTexturePreview, buildPreviewThumbs };

  window.cmdAutoAssignButton = function cmdAutoAssignButton(opts) {
    const options = opts || {};
    const btn = h("button", {
      class: "btn-small", type: "button", text: "CMD自動割当",
      title: "未使用のCustomModelDataを自動で払い出します"
    });
    btn.addEventListener("click", async () => {
      const material = String((options.getMaterial && options.getMaterial()) || "").trim().toUpperCase();
      if (!material) {
        notify("先に material を設定してください", "error");
        return;
      }
      btn.disabled = true;
      try {
        const r = await apiCall("POST", "/api/cmd/allocate", {
          material,
          id: options.getId ? options.getId() : undefined,
          source: options.source
        });
        if (typeof options.onAssigned === "function") options.onAssigned(r.cmd);
        notify(`CMD ${r.cmd} を割り当てました (${material})`, "ok");
      } catch (err) {
        notify(`CMD自動割当に失敗しました: ${err.message}`, "error");
      } finally {
        btn.disabled = false;
      }
    });
    return btn;
  };

  // CMD一括割当ボタン。collectTargets() が返す [{id, material}] 全件へ
  // POST /api/cmd/allocate-bulk で連番を払い出し、onAssigned(results) で反映する。
  window.cmdBulkAssignButton = function cmdBulkAssignButton(opts) {
    const options = opts || {};
    const btn = h("button", {
      class: "btn-small", type: "button", text: "CMD一括割当",
      title: "CMD未設定のアイテム全件へ、未使用のCustomModelDataをまとめて払い出します"
    });
    btn.addEventListener("click", async () => {
      const targets = (options.collectTargets ? options.collectTargets() : [])
        .filter((t) => t && t.id && t.material);
      if (targets.length === 0) {
        notify("CMD未設定のアイテムがありません (materialが空のものは対象外)", "error");
        return;
      }
      const preview = targets.slice(0, 20).map((t) => `  ${t.id} (${t.material})`).join("\n");
      const more = targets.length > 20 ? `\n  …ほか ${targets.length - 20} 件` : "";
      if (!window.confirm(`以下の ${targets.length} 件へCMDを一括割当します。\n`
          + "一度払い出した番号は再利用されません。よろしいですか？\n\n" + preview + more)) {
        return;
      }
      btn.disabled = true;
      try {
        const r = await apiCall("POST", "/api/cmd/allocate-bulk", {
          items: targets.map((t) => ({ material: String(t.material).trim().toUpperCase(), id: t.id, source: options.source }))
        });
        const results = Array.isArray(r.results) ? r.results : [];
        if (typeof options.onAssigned === "function") options.onAssigned(results);
        notify(`${results.length} 件へCMDを一括割当しました`, "ok");
      } catch (err) {
        notify(`CMD一括割当に失敗しました: ${err.message}`, "error");
      } finally {
        btn.disabled = false;
      }
    });
    return btn;
  };

  function statusChip(text, kind) {
    return h("span", { class: `cmd-tex-chip cmd-tex-chip-${kind || "info"}`, text });
  }

  function setStatusChip(chip, text, kind) {
    chip.textContent = text;
    chip.className = `cmd-tex-chip cmd-tex-chip-${kind || "info"}`;
  }

  function readFileAsBase64(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const result = String(reader.result || "");
        const idx = result.indexOf(",");
        resolve(idx >= 0 ? result.slice(idx + 1) : result);
      };
      reader.onerror = () => reject(reader.error || new Error("ファイル読込に失敗しました"));
      reader.readAsDataURL(file);
    });
  }

  function readFileAsText(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result || ""));
      reader.onerror = () => reject(reader.error || new Error("ファイル読込に失敗しました"));
      reader.readAsText(file);
    });
  }

  // A: PNGの寸法を Image() でデコードして取得する(respack.js のIHDR自前パースと同じ判定に使う)。
  function readImageDimensions(dataUrl) {
    return new Promise((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve({ width: img.naturalWidth, height: img.naturalHeight });
      img.onerror = () => reject(new Error("画像のデコードに失敗しました"));
      img.src = dataUrl;
    });
  }

  function isAnimationCandidate(dim) {
    return !!dim && dim.width > 0 && dim.height % dim.width === 0 && dim.height / dim.width >= 2;
  }

  window.cmdTextureControl = function cmdTextureControl(opts) {
    const options = opts || {};
    const wrap = h("span", { class: "cmd-texture-control" });

    // 素のHTMLファイル入力は非表示にし、既存editorの .btn-small トーンに揃えたカスタムボタン
    // 「テクスチャを選択...」から間接的にクリックを委譲する。選択後はファイル名+サムネイルを表示する。
    const fileInput = h("input", { type: "file", accept: "image/png", class: "cmd-tex-file-hidden" });
    const selectBtn = h("button", { class: "btn-small", type: "button", text: "テクスチャを選択..." });
    const thumb = h("img", { class: "cmd-tex-thumb", alt: "" });
    const fileNameLabel = h("span", { class: "cmd-tex-filename", text: "未選択" });
    const uploadBtn = h("button", { class: "btn-small", type: "button", text: "テクスチャ登録" });
    const chip = statusChip("未登録", "warn");
    // 配線済みテクスチャのプレビュー表示枠 (チップの隣)。
    const wiredPreview = h("span", { class: "cmd-preview-slot" });

    function refreshWiredPreview(material, cmd, force) {
      loadTexturePreview(material, cmd, force).then((preview) => {
        wiredPreview.innerHTML = "";
        wiredPreview.appendChild(buildPreviewThumbs(preview));
      });
    }

    async function restorePersistedStatus() {
      const material = String((options.getMaterial && options.getMaterial()) || "").trim().toUpperCase();
      const cmd = Number(options.getCmd && options.getCmd());
      if (!material || !Number.isInteger(cmd) || cmd <= 0) return;
      setStatusChip(chip, "状態を確認中…", "info");
      const rows = await loadTextureStatus();
      // 非同期の状態読込より先に、この画面で登録が完了した場合は成功表示を上書きしない。
      if (chip.dataset.justRegistered === "true") return;
      const row = rows.find((candidate) => candidate.material === material && candidate.cmd === cmd);
      if (!row || !row.hasTexture) {
        setStatusChip(chip, "未登録", "warn");
        return;
      }
      setStatusChip(chip, row.customModel ? "カスタムモデル" : "配線済み", "ok");
      refreshWiredPreview(material, cmd, false);
    }

    // A: 縦長PNG選択時のみ表示するアニメーション設定行。
    const animRow = h("span", { class: "cmd-anim-row" });
    const animToggle = h("input", { type: "checkbox", class: "cmd-anim-toggle" });
    const animFrametime = h("input", { type: "number", class: "cmd-anim-frametime", value: "4", min: "1", max: "200" });
    const animInterpolate = h("input", { type: "checkbox", class: "cmd-anim-interpolate" });
    animRow.appendChild(h("label", {}, [animToggle, " アニメーションにする"]));
    animRow.appendChild(h("label", {}, [" コマ時間(tick): ", animFrametime]));
    animRow.appendChild(h("label", {}, [animInterpolate, " 補間"]));
    animRow.style.display = "none";

    selectBtn.addEventListener("click", () => fileInput.click());
    fileInput.addEventListener("change", async () => {
      const file = fileInput.files && fileInput.files[0];
      animRow.style.display = "none";
      animToggle.checked = false;
      if (!file) {
        fileNameLabel.textContent = "未選択";
        thumb.removeAttribute("src");
        return;
      }
      fileNameLabel.textContent = file.name;
      const reader = new FileReader();
      reader.onload = async () => {
        const dataUrl = String(reader.result || "");
        thumb.src = dataUrl;
        try {
          const dim = await readImageDimensions(dataUrl);
          if (isAnimationCandidate(dim)) animRow.style.display = "";
        } catch (_) {
          // 寸法検出失敗は致命的ではない(アニメーショントグルを出さないだけ)。
        }
      };
      reader.readAsDataURL(file);
    });

    wrap.appendChild(fileInput);
    wrap.appendChild(selectBtn);
    wrap.appendChild(thumb);
    wrap.appendChild(fileNameLabel);
    wrap.appendChild(animRow);
    wrap.appendChild(uploadBtn);
    wrap.appendChild(chip);
    wrap.appendChild(wiredPreview);

    uploadBtn.addEventListener("click", async () => {
      const file = fileInput.files && fileInput.files[0];
      if (!file) {
        notify("PNGファイルを選択してください", "error");
        return;
      }
      const material = String((options.getMaterial && options.getMaterial()) || "").trim().toUpperCase();
      if (!material) {
        notify("先に material を設定してください", "error");
        return;
      }
      uploadBtn.disabled = true;
      try {
        // CMD未設定なら先に自動割当する (1クリックフロー)。
        let cmd = options.getCmd ? options.getCmd() : null;
        if (cmd == null) {
          const r = await apiCall("POST", "/api/cmd/allocate", {
            material, id: options.getId ? options.getId() : undefined, source: options.source
          });
          cmd = r.cmd;
          if (typeof options.onAssigned === "function") options.onAssigned(cmd);
        }
        const pngBase64 = await readFileAsBase64(file);
        const animation = animToggle.checked
          ? { frametime: Number(animFrametime.value) || 4, interpolate: !!animInterpolate.checked }
          : undefined;
        const result = await apiCall("POST", "/api/respack/texture", {
          material, cmd, id: options.getId ? options.getId() : undefined, pngBase64, animation
        });
        updateTextureStatusCache({ material, cmd, hasTexture: true, customModel: false });
        chip.dataset.justRegistered = "true";
        setStatusChip(chip, "配線済み", "ok");
        refreshWiredPreview(material, cmd, true);
        notify(`テクスチャを登録しました (${material}#${cmd} → ${result.assetName})`, "ok");
      } catch (err) {
        notify(`テクスチャ登録に失敗しました: ${err.message}`, "error");
      } finally {
        uploadBtn.disabled = false;
      }
    });

    wrap.appendChild(buildAdvancedCustomModelSection(options, chip, refreshWiredPreview));

    // fire-and-forget: 台帳取得に失敗しても登録操作は可能で、従来どおり未登録表示のままにする。
    restorePersistedStatus();

    return wrap;
  };

  /**
   * 隠した <input type="file"> を包むドラッグ&ドロップ枠 (2026-07-27)。
   * クリック / Enter / Space でファイル選択ダイアログを開き、ファイルを落とすと input に流し込んで
   * change を発火させる — 呼び出し側は input の change ハンドラだけ書けばよく、
   * 「ボタン経由」と「ドロップ経由」で処理が分岐しない。
   */
  function fileDropZone({ input, icon, label, hint, status }) {
    const zone = h("div", { class: "cmd-dropzone", tabindex: "0", role: "button" }, [
      input,
      h("span", { class: "cmd-dropzone-icon", text: icon }),
      h("div", { class: "cmd-dropzone-text" }, [
        h("span", { class: "cmd-dropzone-label", text: label }),
        h("span", { class: "cmd-dropzone-hint", text: hint })
      ]),
      status
    ]);
    const open = () => input.click();
    zone.addEventListener("click", (e) => {
      // input 自身のクリックを拾って無限ループにしない
      if (e.target !== input) open();
    });
    zone.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        open();
      }
    });
    for (const type of ["dragenter", "dragover"]) {
      zone.addEventListener(type, (e) => {
        e.preventDefault();
        zone.classList.add("is-over");
      });
    }
    zone.addEventListener("dragleave", () => zone.classList.remove("is-over"));
    zone.addEventListener("drop", (e) => {
      e.preventDefault();
      zone.classList.remove("is-over");
      const dropped = e.dataTransfer && e.dataTransfer.files;
      if (!dropped || !dropped.length) return;
      input.files = dropped;
      input.dispatchEvent(new Event("change"));
    });
    return zone;
  }

  // B: 折りたたみ「上級: カスタムモデルJSON」欄。modelJsonファイル+テクスチャPNG複数選択+アップロード。
  function buildAdvancedCustomModelSection(options, chip, refreshWiredPreview) {
    const details = h("details", { class: "cmd-advanced-model" });
    details.appendChild(h("summary", { text: "上級: カスタムモデルJSON" }));

    // 素のファイル入力は隠し、ドロップゾーンへ委譲する
    // (2026-07-27: 「.btn-small + 未選択ラベル」が羅列されるだけで何を入れる欄か分かりづらかったため、
    //  ドラッグ&ドロップ対応の枠に置き換えた。クリック/Enter/Space でも従来どおりファイル選択が開く)。
    const modelFileInput = h("input", { type: "file", accept: "application/json,.json,.bbmodel", class: "cmd-tex-file-hidden" });
    const texFilesInput = h("input", { type: "file", accept: "image/png", multiple: true, class: "cmd-tex-file-hidden" });
    const modelNameLabel = h("span", { class: "cmd-dropzone-file", text: "未選択" });
    const texNameLabel = h("span", { class: "cmd-dropzone-file", text: "未選択" });
    const modelInfo = h("div", { class: "cmd-model-info" });
    const texThumbs = h("div", { class: "cmd-preview-thumbs" });
    const uploadBtn = h("button", { class: "btn primary cmd-upload-btn", type: "button", text: "カスタムモデルを登録" });

    const body = h("div", { class: "cmd-advanced-model-body" });
    body.appendChild(h("div", {
      class: "field-desc",
      text: "モデルJSONとその中で参照するテクスチャPNG(複数可)をアップロードします。"
        + "Blockbenchのプロジェクトファイル(.bbmodel形式)を選ぶと、Java版アイテムモデルへ自動変換し、"
        + "埋め込み画像もPNGとして取り出します。v1制約: この経路のテクスチャにアニメーション指定はできません。"
    }));
    body.appendChild(fileDropZone({
      input: modelFileInput,
      icon: "{ }",
      label: "モデルJSON / .bbmodel をドロップ",
      hint: "クリックでファイル選択",
      status: modelNameLabel
    }));
    body.appendChild(modelInfo);
    body.appendChild(fileDropZone({
      input: texFilesInput,
      icon: "🖼",
      label: "テクスチャPNG をドロップ（複数可）",
      hint: "クリックでファイル選択",
      status: texNameLabel
    }));
    body.appendChild(texThumbs);
    body.appendChild(uploadBtn);
    details.appendChild(body);

    // 選択したモデルJSONをその場で解析し、形式と規模を出す。登録前に「これはプロジェクト
    // ファイルだ」と分かるようにするのが目的(送信して初めて気づく状況をなくす)。
    modelFileInput.addEventListener("change", async () => {
      modelInfo.innerHTML = "";
      const file = modelFileInput.files && modelFileInput.files[0];
      modelNameLabel.textContent = file ? file.name : "未選択";
      modelNameLabel.classList.toggle("has-file", !!file);
      if (!file) return;
      let json;
      try {
        json = JSON.parse(await readFileAsText(file));
      } catch (err) {
        modelInfo.appendChild(h("span", { class: "cmd-tex-chip cmd-tex-chip-error", text: "JSONとして読めません" }));
        return;
      }
      const isProject = !!(json && (
        (json.meta && json.meta.format_version) || Array.isArray(json.textures) || Array.isArray(json.outliner)
      ));
      const elementCount = Array.isArray(json.elements) ? json.elements.length : 0;
      const textureCount = Array.isArray(json.textures)
        ? json.textures.length
        : Object.keys((json && json.textures) || {}).length;
      modelInfo.appendChild(h("span", {
        class: `cmd-tex-chip cmd-tex-chip-${isProject ? "warn" : "ok"}`,
        text: isProject ? "Blockbenchプロジェクト → 登録時に自動変換" : "Java版モデル形式"
      }));
      modelInfo.appendChild(h("span", {
        class: "cmd-model-meta",
        text: ` 立方体 ${elementCount} 個 / テクスチャ ${textureCount} 枚`
      }));
      if (isProject) {
        const embedded = (Array.isArray(json.textures) ? json.textures : [])
          .filter((t) => t && typeof t.source === "string" && t.source.startsWith("data:image/png"));
        for (const t of embedded.slice(0, 3)) {
          modelInfo.appendChild(h("img", { class: "cmd-preview-img", src: t.source, alt: t.name || "", title: t.name || "" }));
        }
      }
    });

    // 選択したPNGをその場でサムネイル表示する(上級欄にはプレビューが一切無かった)。
    texFilesInput.addEventListener("change", () => {
      texThumbs.innerHTML = "";
      const files = texFilesInput.files ? Array.from(texFilesInput.files) : [];
      texNameLabel.textContent = files.length ? `${files.length} 枚選択` : "未選択";
      texNameLabel.classList.toggle("has-file", files.length > 0);
      for (const file of files.slice(0, 6)) {
        const img = h("img", { class: "cmd-preview-img", alt: file.name, title: file.name });
        const reader = new FileReader();
        reader.onload = () => { img.src = String(reader.result || ""); };
        reader.readAsDataURL(file);
        texThumbs.appendChild(img);
      }
    });

    uploadBtn.addEventListener("click", async () => {
      const modelFile = modelFileInput.files && modelFileInput.files[0];
      if (!modelFile) {
        notify("モデルJSONファイルを選択してください", "error");
        return;
      }
      const material = String((options.getMaterial && options.getMaterial()) || "").trim().toUpperCase();
      if (!material) {
        notify("先に material を設定してください", "error");
        return;
      }
      uploadBtn.disabled = true;
      try {
        const modelText = await readFileAsText(modelFile);
        let modelJson;
        try {
          modelJson = JSON.parse(modelText);
        } catch (err) {
          throw new Error(`モデルJSONのパースに失敗しました: ${err.message}`);
        }
        let cmd = options.getCmd ? options.getCmd() : null;
        if (cmd == null) {
          const r = await apiCall("POST", "/api/cmd/allocate", {
            material, id: options.getId ? options.getId() : undefined, source: options.source
          });
          cmd = r.cmd;
          if (typeof options.onAssigned === "function") options.onAssigned(cmd);
        }
        const texFiles = texFilesInput.files ? Array.from(texFilesInput.files) : [];
        const textures = [];
        for (const f of texFiles) {
          const pngBase64 = await readFileAsBase64(f);
          textures.push({ name: f.name.replace(/\.png$/i, ""), pngBase64 });
        }
        const result = await apiCall("POST", "/api/respack/model", {
          material, cmd, id: options.getId ? options.getId() : undefined, modelJson, textures
        });
        updateTextureStatusCache({ material, cmd, hasTexture: true, customModel: true });
        chip.dataset.justRegistered = "true";
        setStatusChip(chip, "カスタムモデル", "ok");
        if (typeof refreshWiredPreview === "function") refreshWiredPreview(material, cmd, true);
        const suffix = result.converted ? " / Blockbenchプロジェクトから自動変換" : "";
        notify(`カスタムモデルを登録しました (${material}#${cmd} → ${result.assetName})${suffix}`, "ok");
        for (const warning of result.warnings || []) notify(warning, "error");
      } catch (err) {
        // 変換不能の理由は複数個あるので、1件ずつ出して直せるようにする。
        const details = err && Array.isArray(err.conversionErrors) ? err.conversionErrors : null;
        if (details && details.length) {
          notify(`Blockbenchプロジェクトを変換できませんでした (${details.length}件)`, "error");
          for (const line of details.slice(0, 8)) notify(line, "error");
          if (details.length > 8) notify(`…ほか ${details.length - 8} 件`, "error");
        } else {
          notify(`カスタムモデルの登録に失敗しました: ${err.message}`, "error");
        }
      } finally {
        uploadBtn.disabled = false;
      }
    });

    return details;
  }
})();
