"use strict";

// P5 専用フォーム: gacha.yml (ガチャ) / thread-sets.yml (スレッドセット効果)。
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
  function subTitle(text, title) { return h("div", { class: "sub-title", text, title: title || "" }); }
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

    // 券IDは items/catalog.yml のカタログIDそのもの (PDCタグで判定するのでバニラ Material は
    // 券になれない)。2026-07-29 まで素の文字入力で、存在しないIDを書いても無警告だった。
    // カタログ品だけを候補にしたセレクトへ置き換える (自由入力は残す)。
    function ticketIdSelect(value, onCommit) {
      const plain = (raw) => (typeof window.stripDisplayNamePlain === "function"
        ? window.stripDisplayNamePlain(raw) : String(raw == null ? "" : raw));
      const list = catalogCandidates.map((c) => ({
        value: c.id,
        primary: plain(c.label != null && c.label !== "" ? c.label : c.displayName) || c.id,
        secondary: c.id
      }));
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
        onclick: () => { working.tickets[uniqueKey(working.tickets, "tf_gacha_ticket")] = { pool: poolIds()[0] || "standard" }; render(); }
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
          // item = itemCatalog ID / custom:id / バニラMaterial。
          row.appendChild(window.materialInput(ent.item, "material-list", (v) => { ent.item = v; }, { allowCustom: true }));
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

  // ============================================================
  // thread-sets.yml (ars-thread-sets)
  //   thread-sets: { <threadType>: { thresholds: { <N>: { <stat>: value } } } }
  //   累積しきい値式: N以上の全しきい値のステを合算。
  // ============================================================
  const KNOWN_THREADS = [
    "mana_regen", "mana_boost", "speed", "jump_boost", "night_vision", "fire_resistance",
    "dolphins_grace", "conduit_power", "hero_of_the_village", "health_boost",
    "hit_mana_recovery", "damage_mana_recovery", "spell_cost_down", "flight", "backpack"
  ];

  window.buildThreadSetsForm = function buildThreadSetsForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (working["thread-sets"] == null || typeof working["thread-sets"] !== "object") working["thread-sets"] = {};
    const sets = working["thread-sets"];
    const root = h("div", { class: "dedicated-form" });

    function statValue(key, value, setter) {
      // forms.js の %入力(割合保存) を再利用。未ロード時は素の数値入力にフォールバック。
      if (window.statValueControl) return window.statValueControl(key, value, setter);
      return window.numberInput(value, (v) => setter(v == null ? 0 : v));
    }

    function render() {
      root.innerHTML = "";
      const setKeys = Object.keys(sets);
      if (!setKeys.length) root.appendChild(emptyHint("スレッド種がありません。「+ スレッド種追加」で追加します。"));

      for (const tname of setKeys) {
        const node = sets[tname] && typeof sets[tname] === "object" ? sets[tname] : (sets[tname] = {});
        if (node.thresholds == null || typeof node.thresholds !== "object") node.thresholds = {};
        const thresholds = node.thresholds;

        const head = h("div", { class: "entry-head-row" }, [
          h("span", { class: "range-label", text: "スレッド種" }),
          keyInput(sets, tname, render),
          h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: () => { delete sets[tname]; render(); } })
        ]);

        const body = h("div", { class: "stat-rows" });
        const thKeys = Object.keys(thresholds);
        if (!thKeys.length) body.appendChild(emptyHint("しきい値がありません(セット効果なし)。「+ しきい値追加」で追加します。"));
        for (const n of thKeys) {
          const statMap = thresholds[n] && typeof thresholds[n] === "object" ? thresholds[n] : (thresholds[n] = {});
          const thBox = h("div", { class: "threshold-box" });
          const thHead = h("div", { class: "stat-row" }, [
            h("span", { class: "range-label", text: "装備" }),
            (() => {
              // change(blur/Enter)で確定=入力中に再描画しない。1以上の整数へ丸め、重複は元へ戻す。
              const inp = h("input", { class: "field-input num", type: "number", step: "1", value: n });
              inp.addEventListener("change", () => {
                const nv = inp.value === "" ? "" : String(Math.max(1, Math.round(Number(inp.value))));
                if (!nv || nv === n) { inp.value = n; return; }
                if (Object.prototype.hasOwnProperty.call(thresholds, nv)) { alert("同じしきい値が既にあります"); inp.value = n; return; }
                renameKey(thresholds, n, nv); render();
              });
              return inp;
            })(),
            h("span", { class: "range-label", text: "個以上で発動" }),
            h("button", { class: "btn-small danger", type: "button", text: "しきい値削除", onclick: () => { delete thresholds[n]; render(); } })
          ]);
          thBox.appendChild(thHead);

          const statRows = h("div", { class: "stat-rows indented" });
          const statKeys = Object.keys(statMap);
          if (!statKeys.length) statRows.appendChild(emptyHint("ステがありません。「+ ステ追加」で追加します。"));
          for (const sk of statKeys) {
            const row = h("div", { class: "stat-row" });
            const s = window.statSelect(sk, (nv) => {
              if (!nv || nv === sk) return false;
              if (Object.prototype.hasOwnProperty.call(statMap, nv)) { alert("同じステータスが既にあります"); return false; }
              renameKey(statMap, sk, nv); render(); return true;
            });
            row.appendChild(s);
            row.appendChild(statValue(sk, statMap[sk], (v) => { statMap[sk] = v; }));
            if (window.statUnitSlot) row.appendChild(window.statUnitSlot(sk));
            row.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { delete statMap[sk]; render(); } }));
            statRows.appendChild(row);
          }
          statRows.appendChild(h("button", {
            class: "btn-small", type: "button", text: "+ ステ追加",
            onclick: () => { statMap[pickNewStat(statMap)] = 0; render(); }
          }));
          thBox.appendChild(statRows);
          body.appendChild(thBox);
        }
        body.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ しきい値追加",
          onclick: () => { thresholds[String(nextThreshold(thresholds))] = {}; render(); }
        }));

        root.appendChild(card([head], [subTitle("累積しきい値式: N個以上の全しきい値のステを合算")].concat([body])));
      }

      root.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ スレッド種追加",
        onclick: () => { sets[uniqueKey(sets, nextThreadName())] = { thresholds: {} }; render(); }
      }));
    }

    function nextThreshold(thresholds) {
      let n = 2;
      while (Object.prototype.hasOwnProperty.call(thresholds, String(n))) n++;
      return n;
    }
    function nextThreadName() {
      for (const t of KNOWN_THREADS) if (!Object.prototype.hasOwnProperty.call(sets, t)) return t;
      return "thread";
    }
    function pickNewStat(target) {
      const list = (window.STAT_LIST && window.STAT_LIST.length ? window.STAT_LIST : window.FALLBACK_STATS) || [];
      for (const c of list) if (!Object.prototype.hasOwnProperty.call(target, c)) return c;
      return "new-stat";
    }

    render();
    return { element: root, getData: () => working };
  };
})();
