"use strict";

// 「リソースパック管理」ツールビュー。CMD台帳の一覧・予約値リスト・パックビルド・
// 全アイテム横断のCMD一括採番＆保存を行う。
// 保存対象を持たない読み取り専用ビューだったが、CMD一括採番のみ catalog.yml/materials.yml へ
// 直接保存する (各カタログ/素材ページに散在していた「CMD一括割当」ボタンをここへ集約)。
//
// 純関数コア (collectCmdTargets/applyCmdResults) はブラウザ非依存で Node の単体テストから
// 検証できるよう分離する (recipes.js / ars-forms.js と同じ dual-export IIFE パターン)。

(function (root, isBrowser) {
  // ============================================================
  // 純関数コア (ブラウザ非依存)
  // ============================================================

  // catalog.yml(items.<id>.material / custom-model-data) と materials.yml
  // (materials.<id>.base_material / custom_model_data) を横断し、
  // 「material(base_material)はあるがCMD未設定」なアイテムを集める。
  //   - catalog: custom-model-data が整数でなければ未設定 (0 も「設定済み」扱い。forms.js既存踏襲)
  //   - materials: custom_model_data が「正の整数」でなければ未設定 (新規作成時の既定値0は未設定扱い。
  //     ars-forms.js既存踏襲)
  // 戻り値: [{ file: "catalog"|"materials", id, material }]
  function collectCmdTargets(catalogData, materialsData) {
    const targets = [];
    const items = (catalogData && catalogData.items && typeof catalogData.items === "object" && !Array.isArray(catalogData.items))
      ? catalogData.items : {};
    for (const [id, entry] of Object.entries(items)) {
      if (!entry || typeof entry !== "object") continue;
      if (entry.material && !Number.isInteger(entry["custom-model-data"])) {
        targets.push({ file: "catalog", id, material: entry.material });
      }
    }
    const materials = (materialsData && materialsData.materials && typeof materialsData.materials === "object" && !Array.isArray(materialsData.materials))
      ? materialsData.materials : {};
    for (const [id, entry] of Object.entries(materials)) {
      if (!entry || typeof entry !== "object") continue;
      const cmd = entry.custom_model_data;
      if (entry.base_material && !(Number.isInteger(cmd) && cmd > 0)) {
        targets.push({ file: "materials", id, material: entry.base_material });
      }
    }
    return targets;
  }

  // POST /api/cmd/allocate-bulk の結果 (targets と同順の配列) を、対応する catalog/materials
  // データへ書き戻す (in-place。呼び出し側が事前に複製済みのデータを渡す想定)。
  function applyCmdResults(catalogData, materialsData, targets, results) {
    targets.forEach((t, i) => {
      const r = results[i];
      if (!r || typeof r.cmd !== "number") return;
      if (t.file === "catalog") {
        if (!catalogData.items || typeof catalogData.items !== "object") catalogData.items = {};
        const e = catalogData.items[t.id];
        if (e && typeof e === "object") e["custom-model-data"] = r.cmd;
      } else if (t.file === "materials") {
        if (!materialsData.materials || typeof materialsData.materials !== "object") materialsData.materials = {};
        const e = materialsData.materials[t.id];
        if (e && typeof e === "object") e.custom_model_data = r.cmd;
      }
    });
  }

  const CORE = { collectCmdTargets, applyCmdResults };
  root.RESPACK_VIEW_CORE = CORE;
  if (typeof module !== "undefined" && module.exports) module.exports = CORE;

  // ブラウザ以外 (Node の単体テスト) では純関数コアのみを公開してここで終える。
  if (!isBrowser) return;

  // ============================================================
  // DOM構築 (ブラウザ専用)
  // ============================================================

  const h = window.h;

  // app.js の api() と同じ契約 (status/payload を Error に付与し、409コンフリクト判定に使う)。
  async function apiCall(method, url, body) {
    const opts = { method, headers: { "Content-Type": "application/json" } };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const res = await fetch(url, opts);
    const json = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw Object.assign(new Error(json.error || res.statusText), { status: res.status, payload: json });
    }
    return json;
  }

  function notify(message, kind) {
    if (typeof window.toast === "function") window.toast(message, kind);
  }

  // 個別登録解除: (material,cmd) を全configファイルから削除して台帳・リソパ定義を同期する。
  async function unregisterAllocation(material, cmd, refresh) {
    if (!window.confirm(`${material}#${cmd} を登録解除します。\n`
        + "この (material, CMD) を参照している全ての設定ファイル(カタログ/ステータス定義/素材/魔導書等)から\n"
        + "該当エントリを削除します。カタログ等に実体が残っている場合はそのアイテム定義ごと削除されます。\n"
        + "どの設定ファイルにも無いアイテム(由来が functional-items 等、フォークのJava側が\n"
        + "材質とCMDを固定しているもの)は、台帳とリソパ定義からの配線だけを外します。\n"
        + "反映後はパックの再ビルド/再公開が必要です。よろしいですか？")) {
      return;
    }
    try {
      const r = await apiCall("POST", "/api/respack/unregister", { material, cmd });
      const files = (r.removed || []).map((x) => `${x.file}:${x.id}`).join(", ");
      notify(`${material}#${cmd} を登録解除しました (${(r.removed || []).length}件: ${files})。`
        + "パックを再ビルド/再公開してください。", r.warning ? "warn" : "ok");
      if (r.warning) notify(r.warning, "warn");
      if (typeof refresh === "function") await refresh();
    } catch (err) {
      notify(`登録解除に失敗しました: ${err.message}`, "error");
    }
  }

  function buildAllocationsTable(status, refresh) {
    const rows = Array.isArray(status.allocations) ? status.allocations : [];
    if (!rows.length) {
      return h("div", { class: "empty-hint", text: "CMD台帳はまだ空です。各configで「CMD自動割当」を使うと自動的に記録されます。" });
    }
    const table = h("table", { class: "respack-table" });
    table.appendChild(h("thead", {}, [
      h("tr", {}, ["プレビュー", "material", "CMD", "id", "由来", "配線種別", "テクスチャ状態", "操作"].map((t) => h("th", { text: t })))
    ]));
    const tbody = h("tbody");
    const sorted = rows.slice().sort((a, b) => (a.material === b.material ? a.cmd - b.cmd : (a.material < b.material ? -1 : 1)));
    const previewHelpers = window.cmdPreviewHelpers;
    for (const row of sorted) {
      const texState = row.hasTexture
        ? h("span", { class: "cmd-tex-chip cmd-tex-chip-ok", text: "配線済み" })
        : h("span", { class: "cmd-tex-chip cmd-tex-chip-warn", text: "テクスチャ未登録" });
      // B: カスタムモデルJSON経路で登録された行は台帳に customModel:true が付く。
      const kindChip = row.customModel
        ? h("span", { class: "cmd-tex-chip cmd-tex-chip-info", text: "カスタムモデル" })
        : h("span", { class: "cmd-tex-chip cmd-tex-chip-info", text: "自動生成" });
      // 配線済み行のみプレビュー画像を遅延取得 (cmd-tools.js の共有キャッシュを利用)。
      const previewCell = h("td", { class: "respack-preview-cell" });
      if (row.hasTexture && previewHelpers) {
        previewHelpers.loadTexturePreview(row.material, row.cmd).then((preview) => {
          previewCell.appendChild(previewHelpers.buildPreviewThumbs(preview, "lg"));
        });
      }
      const unregBtn = h("button", {
        class: "btn-small danger", type: "button", text: "登録解除",
        title: "この(material, CMD)を全設定ファイルから削除し、台帳・リソパ定義から外します"
      });
      unregBtn.addEventListener("click", () => unregisterAllocation(row.material, row.cmd, refresh));
      tbody.appendChild(h("tr", {}, [
        previewCell,
        h("td", { text: row.material }),
        h("td", { text: String(row.cmd) }),
        h("td", { text: row.id || "" }),
        h("td", { text: row.source || "" }),
        h("td", {}, [kindChip]),
        h("td", {}, [texState]),
        h("td", {}, [unregBtn])
      ]));
    }
    table.appendChild(tbody);
    /* R (2026-08-04): 8列あるので狭い画面では必ず親幅を超える。親(.main)は overflow-x:hidden
       なので、ここで自前のスクロール枠に入れないと右側の列(操作ボタン等)が切り落とされて
       到達できなくなる。table 自身に overflow を当てても効かない(display:block にすると
       width:100% が効かなくなる)ため、ラッパで包む。 */
    return h("div", { class: "table-scroll" }, [table]);
  }

  function buildReservedSection(status) {
    const details = h("details", { class: "respack-reserved" });
    details.appendChild(h("summary", { text: `予約済みCMD一覧 (${(status.reserved || []).length}件、Java側ハードコード)` }));
    const list = h("div", { class: "respack-reserved-list" });
    const sorted = (status.reserved || []).slice().sort((a, b) => a - b);
    list.appendChild(h("div", { text: sorted.join(", ") }));
    details.appendChild(list);
    return details;
  }

  function buildWarningsSection(status) {
    const warnings = [];
    for (const row of status.allocations || []) {
      if (!row.hasTexture) {
        warnings.push(`${row.material}#${row.cmd} (${row.id || "?"}) はテクスチャ未登録です`);
      }
    }
    if (!warnings.length) return null;
    const box = h("div", { class: "form-banner" });
    box.appendChild(h("div", { text: `警告 ${warnings.length}件` }));
    const list = h("ul");
    for (const w of warnings.slice(0, 50)) list.appendChild(h("li", { text: w }));
    box.appendChild(list);
    return box;
  }

  // revision付き楽観ロックPUT。呼び出し元は必ず直前のGETで取得した revision を渡す想定
  // (409+payload.conflict をコンフリクトとして判定)。
  // マージUIは持たない (このビューはワンショットのバッチ処理のため、コンフリクト時は中断して
  // ユーザーに再読込・再実行を促すだけに留める)。
  // ⚠ 2026-08-25: 以前は `expectedRevision != null` の時だけ送っていたため、GETした時点で
  // ファイルが未作成(revision:null)だったケースで expectedRevision を省略してしまい、
  // その間に外部プロセスがファイルを新規作成しても楽観ロックが一切効かなかった
  // (server.js 側は expectedRevision キー自体の有無で判定するため、null も明示的に送る必要がある。
  // app.js の putConfig と契約を揃えた)。
  async function putConfigRevision(configId, data, expectedRevision) {
    const body = { data, expectedRevision };
    try {
      const r = await apiCall("PUT", `/api/config/${configId}`, body);
      return { ok: true, revision: r.revision };
    } catch (err) {
      if (err.status === 409 && err.payload && err.payload.conflict) {
        return { ok: false, conflict: true };
      }
      throw err;
    }
  }

  // 「全アイテムCMD一括採番＆保存」ボタン: catalog.yml + materials.yml を横断して
  // material はあるがCMD未設定のアイテムをまとめて採番し、両ファイルへ即保存する。
  // (各カタログ/素材ページに散在していた「CMD一括割当」ボタンの集約先。)
  function buildBulkAssignSection(refresh) {
    const box = h("div", { class: "respack-bulk-assign-box" });
    box.appendChild(h("div", {
      class: "field-desc",
      text: "全ファイル(カタログ7カテゴリ + 素材)を横断して、CMD未設定のアイテムへ一括でCMDを採番し、"
        + "そのままcatalog.yml/materials.ymlへ保存します。各編集ページを個別に開いて保存する必要はありません。"
    }));
    const btn = h("button", {
      class: "btn primary", type: "button", text: "全アイテムCMD一括採番＆保存"
    });
    btn.addEventListener("click", async () => {
      btn.disabled = true;
      try {
        // 1. revision込みで両ファイルを取得。
        const [catalogRes, materialsRes] = await Promise.all([
          apiCall("GET", "/api/config/catalog"),
          apiCall("GET", "/api/config/materials")
        ]);
        const catalogData = (catalogRes.data && typeof catalogRes.data === "object") ? catalogRes.data : {};
        const materialsData = (materialsRes.data && typeof materialsRes.data === "object") ? materialsRes.data : {};

        // 2. CMD未設定分を収集。
        const targets = window.RESPACK_VIEW_CORE.collectCmdTargets(catalogData, materialsData);
        if (targets.length === 0) {
          notify("CMD未設定のアイテムがありません (materialが空のものは対象外)", "error");
          return;
        }
        const preview = targets.slice(0, 20).map((t) => `  [${t.file === "catalog" ? "カタログ" : "素材"}] ${t.id} (${t.material})`).join("\n");
        const more = targets.length > 20 ? `\n  …ほか ${targets.length - 20} 件` : "";
        if (!window.confirm(`以下の ${targets.length} 件へCMDを一括採番し、catalog.yml/materials.ymlへ保存します。\n`
            + "一度払い出した番号は再利用されません。よろしいですか？\n\n" + preview + more)) {
          return;
        }

        // 3. 一括採番 (由来は元ファイルに合わせる。台帳「由来」列にそのまま表示される)。
        const allocRes = await apiCall("POST", "/api/cmd/allocate-bulk", {
          items: targets.map((t) => ({ material: t.material, id: t.id, source: t.file }))
        });
        const results = Array.isArray(allocRes.results) ? allocRes.results : [];

        // 4. 取得したデータへ書き戻し (in-place)。
        window.RESPACK_VIEW_CORE.applyCmdResults(catalogData, materialsData, targets, results);

        // 5. revision付きで保存。catalogを先に保存し、失敗したらmaterialsには触れない
        //    (二重コンフリクトで状況を複雑にしないため。採番自体は台帳に残るが、既存の
        //    「採番だけして保存を忘れる」ケースと同じ許容範囲の限界であり新規の後退ではない)。
        const catalogPut = await putConfigRevision("catalog", catalogData, catalogRes.revision);
        if (!catalogPut.ok) {
          notify("他で編集中のため保存できませんでした。画面を再読込して再実行してください。(catalog.yml)", "error");
          return;
        }
        const materialsPut = await putConfigRevision("materials", materialsData, materialsRes.revision);
        if (!materialsPut.ok) {
          notify("catalog.ymlは保存できましたが、他で編集中のためmaterials.ymlは保存できませんでした。"
            + "画面を再読込して再実行してください。(materials.yml)", "error");
          await refresh();
          return;
        }

        notify(`${results.length} 件へCMDを一括採番し保存しました。`
          + "他に開いているカタログ/素材の編集ページがあればリロードしてください (古いrevisionのままだと保存時に競合します)。", "ok");
        await refresh();
      } catch (err) {
        notify(`CMD一括採番に失敗しました: ${err.message}`, "error");
      } finally {
        btn.disabled = false;
      }
    });
    box.appendChild(btn);
    return box;
  }

  // 「カタログ削除を一括反映」ボタン: authoritativeな定義ファイルに実体が無く item-stats だけが
  // 参照している孤児(=カタログ等から削除済みなのにstat参照だけ残り、登録アイテム一覧に残り続ける行)
  // を一括で掃除する。カタログの実体を消す危険な操作ではなく、既に消えたアイテムの残骸だけを対象にする。
  function buildOrphanPruneSection(refresh) {
    const box = h("div", { class: "respack-bulk-assign-box" });
    box.appendChild(h("div", {
      class: "field-desc",
      text: "カタログ等からアイテムを削除しても、ステータス定義(item-stats)の参照が残ると"
        + "この一覧に残り続けます。実体がどの定義ファイルにも無く参照だけ残った項目(孤児)を一括で掃除します。"
        + "実体が残っているアイテムには影響しません。"
    }));
    const btn = h("button", {
      class: "btn", type: "button", text: "カタログ削除を一括反映(孤児を掃除)"
    });
    btn.addEventListener("click", async () => {
      if (!window.confirm("カタログ等から既に削除されたアイテムのうち、ステータス定義の参照だけが残った項目(孤児)を"
          + "一括で削除します。実体が残っているアイテムには影響しません。\n反映後はパックの再ビルド/再公開が必要です。"
          + "よろしいですか？")) {
        return;
      }
      btn.disabled = true;
      try {
        const r = await apiCall("POST", "/api/respack/prune-orphans");
        if (!r.count) {
          notify("掃除対象の孤児はありませんでした。", "ok");
        } else {
          notify(`孤児 ${r.count} 件を掃除しました。パックを再ビルド/再公開してください。`, r.warning ? "warn" : "ok");
          if (r.warning) notify(r.warning, "warn");
        }
        if (typeof refresh === "function") await refresh();
      } catch (err) {
        notify(`カタログ削除の一括反映に失敗しました: ${err.message}`, "error");
      } finally {
        btn.disabled = false;
      }
    });
    box.appendChild(btn);
    return box;
  }

  function buildBuildSection(status, refresh) {
    const box = h("div", { class: "respack-build-box" });
    box.appendChild(h("div", { class: "sub-title", text: "パックビルド" }));

    const resultBox = h("div", { class: "respack-build-result" });
    function renderBuildInfo(build) {
      resultBox.innerHTML = "";
      if (!build) {
        resultBox.appendChild(h("div", { class: "empty-hint", text: "まだビルドされていません。" }));
        return;
      }
      resultBox.appendChild(h("div", { text: `SHA-1: ${build.sha1}` }));
      resultBox.appendChild(h("div", { text: `サイズ: ${build.size} バイト` }));
      resultBox.appendChild(h("div", { text: `出力先: ${build.path}` }));
      resultBox.appendChild(h("div", { text: `最終更新: ${build.mtime}` }));
      // ビルドしただけの sha1 を server.properties へ貼らせてはいけない。配布URLは release を
      // 作って初めて決まるため、sha1 だけ先に更新すると URL が旧releaseのまま取り残され、
      // クライアントはハッシュ不一致で「ダウンロードに失敗しました」になる (2026-07-25 実障害)。
      resultBox.appendChild(h("div", {
        class: "warn-banner",
        text: "この SHA-1 はローカルzipのものです。server.properties にはまだ貼らないでください。"
          + "配布するには下の「GitHub releaseへ公開」を実行し、そこで出る URL と SHA-1 を"
          + "必ず両方セットで差し替えてください。"
      }));
    }
    renderBuildInfo(status.build);

    const buildBtn = h("button", {
      class: "btn primary", type: "button", text: "パックをビルド",
      onclick: async () => {
        buildBtn.disabled = true;
        try {
          const r = await apiCall("POST", "/api/respack/build");
          notify(`パックをビルドしました (${r.fileCount}件, SHA-1: ${r.sha1.slice(0, 12)}…)`, "ok");
          renderBuildInfo(r);
          if (typeof refresh === "function") await refresh();
        } catch (err) {
          notify(`パックのビルドに失敗しました: ${err.message}`, "error");
        } finally {
          buildBtn.disabled = false;
        }
      }
    });

    // GitHub release へ公開 (再ビルド→release作成→配布URL/sha1表示)。
    const publishBox = h("div", { class: "respack-publish-result" });
    const publishBtn = h("button", {
      class: "btn", type: "button", text: "GitHub releaseへ公開",
      title: "packRepo (tool-config.json) のGitHubリポジトリへ release を作成し、配布URLを発行します",
      onclick: async () => {
        if (!window.confirm("リソースパックを再ビルドして GitHub release へ公開します。\n"
            + "公開リポジトリのため、パック内容は誰でもダウンロード可能になります。よろしいですか？")) {
          return;
        }
        publishBtn.disabled = true;
        try {
          const r = await apiCall("POST", "/api/respack/publish");
          notify(`GitHub release を作成しました (${r.tag})`, "ok");
          publishBox.innerHTML = "";
          publishBox.appendChild(h("div", { text: `配布URL: ${r.url}` }));
          publishBox.appendChild(h("div", { text: `SHA-1: ${r.sha1}` }));
          const props = `resource-pack=${r.url}\nresource-pack-sha1=${r.sha1}`;
          publishBox.appendChild(h("div", {
            class: "warn-banner",
            text: "server.properties へ以下の2行を「両方まとめて」差し替えてください。"
              + "片方だけ更新すると URL と SHA-1 が別releaseを指し、全員がダウンロード失敗になります。"
          }));
          publishBox.appendChild(h("pre", { text: props }));
          publishBox.appendChild(h("button", {
            class: "btn-small", type: "button", text: "設定行をコピー",
            onclick: () => navigator.clipboard && navigator.clipboard.writeText(props)
          }));
          if (typeof refresh === "function") await refresh();
        } catch (err) {
          notify(`GitHub releaseへの公開に失敗しました: ${err.message}`, "error");
        } finally {
          publishBtn.disabled = false;
        }
      }
    });

    box.appendChild(buildBtn);
    box.appendChild(publishBtn);
    box.appendChild(resultBox);
    box.appendChild(publishBox);
    return box;
  }

  window.buildRespackView = function buildRespackView() {
    const viewRoot = h("div", { class: "dedicated-form respack-view" });
    viewRoot.appendChild(h("div", {
      class: "field-desc",
      text: "CMD (CustomModelData) 台帳の一覧確認と、テクスチャを含むリソースパックのビルドを行います。"
        + "各configの編集画面にある「CMD自動割当」「テクスチャ登録」で割り当てたCMDがここに集約されます。"
    }));

    const body = h("div", { class: "respack-body" }, [h("div", { class: "empty-hint", text: "読み込み中…" })]);
    viewRoot.appendChild(body);

    async function refresh() {
      body.innerHTML = "";
      let status;
      try {
        status = await apiCall("GET", "/api/respack/status");
      } catch (err) {
        body.appendChild(h("div", { class: "empty", text: `読み込み失敗: ${err.message}` }));
        return;
      }
      // H-2: 台帳ファイル自体が破損している場合は最優先で目立たせる。
      if (status.registryCorrupt) {
        body.appendChild(h("div", { class: "form-banner respack-corrupt-banner", text:
          "CMD台帳ファイル(cmd-registry.json)が破損しています。自動割当・テクスチャ登録は失敗するようになっています。"
          + " 同ディレクトリの cmd-registry.json.bak から手動で復旧してください。" }));
      }
      const warnBox = buildWarningsSection(status);
      if (warnBox) body.appendChild(warnBox);
      body.appendChild(buildBuildSection(status, refresh));
      body.appendChild(h("div", { class: "sub-title", text: "CMD台帳" }));
      body.appendChild(buildBulkAssignSection(refresh));
      body.appendChild(buildOrphanPruneSection(refresh));
      body.appendChild(buildAllocationsTable(status, refresh));
      body.appendChild(buildReservedSection(status));
    }

    refresh();

    // app.js の呼び出し側 (main.appendChild(window.buildRespackView())) は
    // buildHomeView/buildManualView と同じ「DOMノードを直接返す」契約を前提にしている。
    // { element: root } でラップして返すと appendChild が Node 以外を渡されて例外になり、
    // タブ本体が空のまま(例外で以降の描画が止まる)になっていた。これが空表示バグの原因。
    return viewRoot;
  };
})(typeof window !== "undefined" ? window : (typeof module !== "undefined" ? module.exports : this), typeof window !== "undefined" && typeof document !== "undefined");
