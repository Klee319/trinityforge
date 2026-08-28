"use strict";

// P5 専用フォーム: gacha.yml (ガチャ)。thread-sets.yml 専用フォームは撤去済み。
// 往復ロスレス方針は他フォームと同じ: working を直接編集し、構造変更時のみ render() で再描画。
// 未知キー・キー順は温存する。i18n は表示のみ。

(function () {
  const h = window.h;

  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  // 見出しの説明はブラウザ標準の title ではなく「?」の独自ツールチップへ回す (2026-08-01)。
  // 第2引数の名前は呼び出し側との互換のため据え置き (中身は説明文)。
  // util.js を読まない最小 window (単体テスト) では見出しだけを出す。
  // フォールバックでも title 属性は使わない — 標準ツールチップが二重に出る旧方式そのものなので。
  function subTitle(text, title) {
    if (typeof window.subTitleEl === "function") return window.subTitleEl(text, title);
    return h("div", { class: "sub-title", text });
  }
  function emptyHint(text) { return h("div", { class: "empty-hint", text }); }

  // 挿入順を保ったままマップのキーをリネームする。
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }
  function uniqueKey(map, base) {
    if (!Object.prototype.hasOwnProperty.call(map, base)) return base;
    let i = 1, k = `${base}_${i}`;
    while (Object.prototype.hasOwnProperty.call(map, k)) k = `${base}_${++i}`;
    return k;
  }

  // 編集可能なマップキー入力。change(blur/Enter)で確定するので入力中に再描画されない(フォーカス維持)。
  // 重複や空は元の値へ戻す。
  function keyInput(map, key, onRenamed) {
    const inp = h("input", { class: "field-input", value: key, spellcheck: "false" });
    inp.addEventListener("change", () => {
      const v = (inp.value || "").trim();
      if (!v || v === key) { inp.value = key; return; }
      if (Object.prototype.hasOwnProperty.call(map, v)) { alert("同じキーが既にあります"); inp.value = key; return; }
      renameKey(map, key, v); onRenamed();
    });
    return inp;
  }

  // ============================================================
  // gacha.yml (tf-gacha)
  //   tickets: { <ticketCatalogId>: { pool: <poolId> } }
  //   pools:   { <poolId>: { entries: [ { item, weight, amount, quality-random } ] } }
  // ============================================================
  window.buildGachaForm = function buildGachaForm(data, options) {
    const opts = options && typeof options === "object" ? options : {};
    const catalogCandidates = Array.isArray(opts.catalogCandidates) ? opts.catalogCandidates : [];
    const working = data && typeof data === "object" ? data : {};
    if (working.tickets == null || typeof working.tickets !== "object") working.tickets = {};
    if (working.pools == null || typeof working.pools !== "object") working.pools = {};
    const root = h("div", { class: "dedicated-form" });

    // カタログ候補を listSelect 用の {value, primary, secondary} 配列へ変換する共通処理。
    // 券ID(catalogCandidates限定)/景品item(catalogCandidates+バニラMaterial自由入力)の
    // どちらのセレクトからも使う (2026-08-02: 新規に候補生成ロジックを増やさず1本化)。
    function catalogCandidateOptions() {
      const plain = (raw) => (typeof window.stripDisplayNamePlain === "function"
        ? window.stripDisplayNamePlain(raw) : String(raw == null ? "" : raw));
      return catalogCandidates.map((c) => ({
        value: c.id,
        primary: plain(c.label != null && c.label !== "" ? c.label : c.displayName) || c.id,
        secondary: c.id
      }));
    }

    // 券IDは items/catalog.yml のカタログIDそのもの (PDCタグで判定するのでバニラ Material は
    // 券になれない)。2026-07-29 まで素の文字入力で、存在しないIDを書いても無警告だった。
    // カタログ品だけを候補にしたセレクトへ置き換える (自由入力は残す)。
    function ticketIdSelect(value, onCommit) {
      const list = catalogCandidateOptions();
      const cur = value == null ? "" : String(value);
      if (cur && !list.some((o) => o.value === cur)) {
        list.unshift({ value: cur, primary: cur, secondary: "カタログ未登録" });
      }
      list.push({ value: "__custom__", primary: "＋ 自由入力…" });
      return window.listSelect({
        value: cur, options: list, allowCustom: true,
        customPlaceholder: "カタログID (items/catalog.yml)",
        placeholder: "券アイテムを選択…",
        onCommit
      });
    }

    // window.MATERIALS (バニラ Material 全件) を listSelect の候補形式へ変換する。
    // materialInput (util.js) の materialOption と同じ変換だが、あちらは custom: 接頭辞前提の
    // 候補(CUSTOM_ITEM_CANDIDATES)と一体化しているため、bare なカタログID用のこの画面では
    // 個別に持つ (candidate 生成ロジックを増やすな、という方針への抵触は catalogCandidateOptions
    // との1本化で吸収し、こちらは"バニラMaterial一覧"という別の情報源そのものなので分ける)。
    function vanillaMaterialOptions() {
      const list = Array.isArray(window.MATERIALS) ? window.MATERIALS : [];
      const L = window.LABELS;
      return list.map((k) => {
        const ja = L && typeof L.materialLabel === "function" ? L.materialLabel(k) : "";
        return { value: k, primary: ja || k, secondary: k };
      });
    }

    // 景品(pool.entries[].item)は GachaEntry#itemId 仕様どおり「カタログID」または
    // 「バニラMaterial名」のどちらも取れる(custom: 接頭辞は付けない — 実際の gacha.yml も
    // "tf_scrap"/"iron_dagger" のような素の id と "COAL"/"DIAMOND" のような素の Material が
    // 同じ item: に混在している)。2026-08-02 実サーバ報告「景品セレクトがID表記のまま」の修正:
    // 従来はここだけ window.materialInput({allowCustom:true}) を使っており、custom: 接頭辞を
    // 前提にした候補としか一致しないため、接頭辞なしのカタログIDは常に「候補外」表示になっていた
    // (materialInput 自体はカタログ候補ではなく window.CUSTOM_ITEM_CANDIDATES を見るため)。
    // 2026-08-02 指摘7: その修正で catalogCandidateOptions() だけに切り替えた結果、今度は
    // バニラ Material の候補が丸ごと失われ、"COAL"/"DIAMOND" のような正確な enum 名を
    // 自由入力するしかなくなっていた (打ち間違えても UI もバリデータも検知しない)。
    // カタログ候補とバニラ Material 候補を同じ1本のセレクトへ両方積む
    // (GachaEntry#itemId はどちらも bare な文字列で受けるため値の形式は変えない)。
    function prizeItemSelect(value, onCommit) {
      const seen = new Set();
      const list = [];
      for (const opt of catalogCandidateOptions().concat(vanillaMaterialOptions())) {
        if (seen.has(opt.value)) continue;
        seen.add(opt.value);
        list.push(opt);
      }
      const cur = value == null ? "" : String(value);
      if (cur && !list.some((o) => o.value === cur)) {
        const L = window.LABELS;
        const ja = L && typeof L.materialLabel === "function" ? L.materialLabel(cur) : "";
        list.unshift(ja
          ? { value: cur, primary: ja, secondary: cur }
          : { value: cur, primary: cur, secondary: "カタログ未登録 / バニラMaterial" });
      }
      list.push({ value: "__custom__", primary: "＋ 自由入力…" });
      return window.listSelect({
        value: cur, options: list, allowCustom: true,
        customPlaceholder: "カタログID または バニラMaterial名 (例: DIAMOND)",
        placeholder: "景品アイテムを選択…",
        onCommit
      });
    }

    function render() {
      root.innerHTML = "";
      const poolIds = () => Object.keys(working.pools);

      // ---- 券 (tickets) ----
      const ticketBody = h("div", { class: "stat-rows" });
      const ticketKeys = Object.keys(working.tickets);
      if (!ticketKeys.length) ticketBody.appendChild(emptyHint("券がありません。「+ 券追加」で追加します。"));
      for (const tid of ticketKeys) {
        const entry = working.tickets[tid] && typeof working.tickets[tid] === "object" ? working.tickets[tid] : (working.tickets[tid] = {});
        const row = h("div", { class: "stat-row" });
        row.appendChild(h("span", { class: "range-label", text: "券ID" }));
        row.appendChild(ticketIdSelect(tid, (v) => {
          const next = String(v || "").trim();
          if (!next || next === tid) return false;
          if (Object.prototype.hasOwnProperty.call(working.tickets, next)) {
            alert("同じ券IDが既にあります");
            return false;
          }
          renameKey(working.tickets, tid, next);
          render();
          return true;
        }));
        row.appendChild(h("span", { class: "range-label", text: "→ プール" }));
        // プールは既存プールから選ぶ。未定義プールを指していてもロスレス表示のため候補に補う。
        // プールIDは運用側の任意名なので和訳できない。代わりに景品件数を副表示に出す。
        const poolOpts = poolIds().map((id) => {
          const p = working.pools[id];
          const n = p && Array.isArray(p.entries) ? p.entries.length : 0;
          return { value: id, primary: id, secondary: `景品${n}件` };
        });
        if (entry.pool && !poolOpts.some((o) => o.value === entry.pool)) {
          poolOpts.push({ value: entry.pool, primary: entry.pool, secondary: "未定義プール" });
        }
        row.appendChild(window.listSelect({
          value: entry.pool == null ? "" : entry.pool,
          options: poolOpts,
          placeholder: "プールを選択…",
          onChange: (v) => { entry.pool = v; }
        }));
        row.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { delete working.tickets[tid]; render(); } }));
        ticketBody.appendChild(row);
      }
      ticketBody.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 券追加",
        onclick: () => { working.tickets[uniqueKey(working.tickets, "gacha_ticket")] = { pool: poolIds()[0] || "standard" }; render(); }
      }));
      root.appendChild(card([h("span", { class: "entry-key-label", text: "券 (tickets)" })], [
        subTitle("券アイテムのitemCatalog IDと、参照する景品プールID"),
        ticketBody
      ]));

      // ---- 景品プール (pools) ----
      const poolKeys = poolIds();
      if (!poolKeys.length) root.appendChild(card([h("span", { class: "entry-key-label", text: "景品プール (pools)" })], [emptyHint("プールがありません。「+ プール追加」で追加します。")]));
      for (const pid of poolKeys) {
        const pool = working.pools[pid] && typeof working.pools[pid] === "object" ? working.pools[pid] : (working.pools[pid] = {});
        if (!Array.isArray(pool.entries)) pool.entries = [];

        const head = h("div", { class: "entry-head-row" }, [
          h("span", { class: "range-label", text: "プールID" }),
          keyInput(working.pools, pid, render),
          h("button", { class: "btn-small danger", type: "button", text: "プール削除", onclick: () => { delete working.pools[pid]; render(); } })
        ]);

        // 天井(pity): N回連続で最高レア枠(entries中でweight最小、タイは全て対象)を外すと次回確定。
        // 0または未設定=無効。
        const pityRow = h("div", { class: "stat-row" }, [
          h("span", { class: "range-label", text: "天井(pity)回数", title: "この回数だけ連続で最高レア枠(weight最小)を外すと、次回抽選は最高レア枠が確定する。0または空欄=無効。" }),
          window.numberInput(pool.pity && typeof pool.pity === "object" ? pool.pity.threshold : undefined, (v) => {
            if (v === null || v === "") {
              if (pool.pity && typeof pool.pity === "object") delete pool.pity.threshold;
              if (pool.pity && !Object.keys(pool.pity).length) delete pool.pity;
              return;
            }
            if (!pool.pity || typeof pool.pity !== "object") pool.pity = {};
            pool.pity.threshold = v;
          }, { int: true })
        ]);
        const entriesBox = h("div", { class: "stat-rows" });
        if (!pool.entries.length) entriesBox.appendChild(emptyHint("景品がありません。「+ 景品追加」で追加します。"));
        pool.entries.forEach((ent, idx) => {
          if (ent == null || typeof ent !== "object") { pool.entries[idx] = ent = {}; }
          const row = h("div", { class: "stat-row" });
          row.appendChild(h("span", { class: "range-label", text: "景品" }));
          // item = itemCatalog ID または バニラMaterial (custom: 接頭辞は付けない)。
          row.appendChild(prizeItemSelect(ent.item, (v) => {
            const next = String(v || "").trim();
            if (!next) return false;
            ent.item = next;
            return true;
          }));
          row.appendChild(h("span", { class: "range-label", text: "weight" }));
          row.appendChild(window.numberInput(ent.weight, (v) => { ent.weight = v == null ? 1 : v; }, { int: true }));
          row.appendChild(h("span", { class: "range-label", text: "個数" }));
          row.appendChild(window.numberInput(ent.amount, (v) => { ent.amount = v == null ? 1 : v; }, { int: true }));
          const chk = window.checkboxInput(ent["quality-random"], (v) => { if (v) ent["quality-random"] = true; else delete ent["quality-random"]; });
          row.appendChild(h("label", { class: "inline-check", title: "TFカタログ品のみ0〜最大品質をランダム付与" }, [chk, h("span", { text: "品質ランダム" })]));
          row.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { pool.entries.splice(idx, 1); render(); } }));
          entriesBox.appendChild(row);
        });
        entriesBox.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 景品追加",
          onclick: () => { pool.entries.push({ item: "", weight: 1, amount: 1 }); render(); }
        }));

        root.appendChild(card([head], [pityRow, subTitle("重み付き抽選テーブル (weightの比率で当選)"), entriesBox]));
      }
      root.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ プール追加",
        onclick: () => { working.pools[uniqueKey(working.pools, "pool")] = { entries: [] }; render(); }
      }));
    }

    render();
    return { element: root, getData: () => working };
  };

  // thread-sets.yml 専用フォーム(buildThreadSetsForm)は撤去済み。ナビからも到達不能で、
  // セット効果の編集はアイテムステータス「スレッド」タブ(forms.js の renderThreadExtraFields)
  // が thread-sets.yml を横から読み書きする。schema の ars-thread-sets 検証は残す。
  // 新しい専用ナビ/専用フォームをここに足さないこと。

  // 2026-08-02: スレッド厳選専用の random-roll-pools エディタ(旧 buildRandomRollPoolsForm /
  // buildRandomRollPoolEditor)は撤去した。ユーザー指示は「専用GUI/専用仕様を作るな。武器と同じ
  // アイテムステータス設定の仕様で、スレッドも個別にステータス定義しろ」であり、スレッドは
  // item-stats.yml 内の他アイテムと同じ fixed/per-quality/random/advanced フォーム
  // (forms.js の buildItemStatsForm、split-views.js の __stats_thread__ タブ)で編集する。
  // 新しい専用抽選UIをここに足さないこと。
})();
