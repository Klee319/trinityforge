"use strict";

// ArsPaper loot-tables.yml (ars-loot-tables) 専用フォーム。2026-07-31 新設。
//
// 構造物ルートチェストへの追加抽選を編集する画面。対象ルートテーブルは
// 「パスの最後の要素」「namespace:path の完全一致」「namespace:*」の3通りで書ける。
// データパック(Dungeons and Taverns 等)は namespace ワイルドカードで丸ごと拾う。
//
// 往復ロスレス方針: working を直接編集し、未知キー・キー順は温存する。

(function () {
  const h = window.h;

  // 以下のレイアウト部品は mob-forms.js / mob-abilities-form.js と同じ実装
  // (IIFE ローカルで window に出ていないため、ここでも同じものを持つ)。
  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function fieldRow(key, control, opts) {
    return h("div", { class: "form-field" }, [window.fieldLabelEl(key, opts), control]);
  }
  // 見出しの説明はブラウザ標準の title ではなく「?」の独自ツールチップへ回す (2026-08-01)。
  // 第2引数の名前は呼び出し側との互換のため据え置き (中身は説明文)。
  // util.js を読まない最小 window (単体テスト) では見出しだけを出す。
  // フォールバックでも title 属性は使わない — 標準ツールチップが二重に出る旧方式そのものなので。
  function subTitle(text, title) {
    if (typeof window.subTitleEl === "function") return window.subTitleEl(text, title);
    return h("div", { class: "sub-title", text });
  }
  // entry.item(バニラMaterial または custom:<ID>)から表示名を解決する。
  // 未登録なら生IDへフォールバックする(空欄化しない)。
  function resolveItemDisplayLabel(item) {
    const m = item == null ? "" : String(item);
    if (!m) return "(未設定)";
    if (/^custom:/i.test(m)) {
      const labels = window.CUSTOM_ITEM_LABELS || {};
      const ja = labels[m] || labels[m.toLowerCase()] || "";
      return ja || m;
    }
    const L = window.LABELS;
    const ja = L ? L.materialLabel(m) : "";
    return ja || m;
  }
  function uniqueKey(map, base) {
    if (!Object.prototype.hasOwnProperty.call(map, base)) return base;
    let i = 1;
    let k = base + "_" + i;
    while (Object.prototype.hasOwnProperty.call(map, k)) k = base + "_" + (++i);
    return k;
  }
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }

  // バニラの「探索の目的地になる」チェストのルートテーブル。
  // 村・トライアルチャンバー・難破船のような無限/大量にあるチェストは意図的に載せない
  // (無限湧きの構造物を対象にすると経済が壊れるため)。必要なら自由入力で足せる。
  const VANILLA_TABLES = [
    ["abandoned_mineshaft", "廃坑"],
    ["desert_pyramid", "砂漠の寺院"],
    ["jungle_temple", "ジャングルの寺院"],
    ["jungle_temple_dispenser", "ジャングルの寺院(ディスペンサー)"],
    ["simple_dungeon", "ダンジョン(モンスタースポナー部屋)"],
    ["stronghold_corridor", "要塞(通路)"],
    ["stronghold_crossing", "要塞(交差点)"],
    ["stronghold_library", "要塞(図書館)"],
    ["woodland_mansion", "森の洋館"],
    ["end_city_treasure", "エンドシティ"],
    ["bastion_treasure", "砦の遺跡(宝物庫)"],
    ["bastion_other", "砦の遺跡(その他)"],
    ["bastion_hoglin_stable", "砦の遺跡(ホグリン厩舎)"],
    ["bastion_bridge", "砦の遺跡(橋)"],
    ["nether_bridge", "ネザー要塞"],
    ["ancient_city", "古代都市"],
    ["ancient_city_ice_box", "古代都市(氷の箱)"],
    ["buried_treasure", "埋もれた宝"],
    ["ruined_portal", "荒廃したポータル"],
    ["igloo_chest", "イグルー"],
    ["pillager_outpost", "略奪者の前哨基地"],
    ["underwater_ruin_big", "海底遺跡(大)"],
    ["underwater_ruin_small", "海底遺跡(小)"]
  ];

  // データパック用の雛形。namespace が分かっていなくても選べるように候補へ出す。
  const DATAPACK_TABLE_HINTS = [
    ["dungeons_and_taverns:*", "Dungeons and Taverns 全体"],
    ["dungeons_and_taverns_stronghold_overhaul:*", "同 要塞改修 全体"]
  ];

  const ENTRY_TYPES = ["item", "enchant-book"];
  const ENTRY_TYPE_LABELS = {
    "item": "アイテム (item)",
    "enchant-book": "カスタムエンチャント本 (enchant-book)"
  };

  /**
   * 対象ルートテーブルのセレクト。window.listSelect は cfg オブジェクト1個を受ける
   * (位置引数で呼ぶと候補が一切出ない)。候補外も自由入力で通すのは、データパックの
   * namespace が事前に分からないため。
   */
  function tableSelect(current, onChange) {
    const cur = current == null ? "" : String(current);
    const CUSTOM = "__custom_table__";
    const known = new Set();
    const options = [];
    for (const [value, label] of VANILLA_TABLES.concat(DATAPACK_TABLE_HINTS)) {
      if (known.has(value)) continue;
      known.add(value);
      options.push({ value, primary: label + " (" + value + ")", secondary: value, title: value });
    }
    if (cur && !known.has(cur)) {
      options.unshift({ value: cur, primary: cur, secondary: "(一覧外)", title: cur });
    }
    options.push({ value: CUSTOM, primary: "その他(自由入力)…", secondary: "" });
    return window.listSelect({
      value: cur,
      placeholder: "対象を選択…",
      allowCustom: true,
      customPlaceholder: "simple_dungeon / minecraft:chests/... / namespace:*",
      customValue: CUSTOM,
      options: options,
      onCommit: (v) => { onChange(String(v || "").trim()); return true; }
    });
  }

  function tablesBlock(pool, onStructureChange) {
    const box = h("div", { class: "card-list-body" });
    const list = Array.isArray(pool.tables) ? pool.tables : (pool.tables = []);
    if (!list.length) {
      box.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--danger,#b91c1c);",
        text: "対象が空です。このままだとこのプールは永久に発動しません。"
      }));
    }
    list.forEach((value, index) => {
      box.appendChild(h("div", { class: "stat-row" }, [
        tableSelect(value, (nv) => { list[index] = nv; }),
        h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { list.splice(index, 1); onStructureChange(); }
        })
      ]));
    });
    box.appendChild(h("button", {
      class: "btn-small", type: "button", text: "+ 対象を追加",
      onclick: () => { list.push("simple_dungeon"); onStructureChange(); }
    }));
    return box;
  }

  function numberCell(labelText, value, fallback, bounds, onChange) {
    const input = h("input", {
      class: "field-input num", type: "number",
      min: String(bounds[0]), max: String(bounds[1]), step: String(bounds[2]),
      value: value == null ? "" : String(value)
    });
    input.addEventListener("change", () => {
      if (input.value === "") { onChange(null); return; }
      let v = Number(input.value);
      if (!Number.isFinite(v)) { input.value = value == null ? "" : String(value); return; }
      if (bounds[2] === 1) v = Math.round(v);
      v = Math.max(bounds[0], Math.min(bounds[1], v));
      input.value = String(v);
      onChange(v);
    });
    if (value == null && fallback != null) input.placeholder = String(fallback);
    return h("span", { class: "range-cell" }, [
      h("span", { class: "range-label", text: labelText }), input
    ]);
  }

  function entriesBlock(pool, onStructureChange) {
    const box = h("div", { class: "card-list-body" });
    const list = Array.isArray(pool.entries) ? pool.entries : (pool.entries = []);
    if (!list.length) {
      box.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--danger,#b91c1c);",
        text: "候補が空です。このプールは何も追加しません。"
      }));
    }
    list.forEach((entry, index) => {
      if (!entry || typeof entry !== "object") { list[index] = entry = {}; }
      const type = ENTRY_TYPES.includes(entry.type) ? entry.type : "item";
      const rowBody = h("div", { class: "card-list-body" });

      const typeSelect = h("select", { class: "field-input" });
      for (const t of ENTRY_TYPES) {
        typeSelect.appendChild(h("option", {
          value: t, text: ENTRY_TYPE_LABELS[t], selected: type === t
        }));
      }
      typeSelect.addEventListener("change", () => {
        if (typeSelect.value === "item") delete entry.type;
        else entry.type = typeSelect.value;
        onStructureChange();
      });
      rowBody.appendChild(fieldRow("種別 (type)", typeSelect, {
        desc: "enchant-book はマナ回復速度・マナ最大値・共有のどれかを"
          + "ランダムなレベルで持つカスタムエンチャント本を手続き生成します(item は不要)。"
      }));

      if (type === "item") {
        const itemInput = window.materialInput(entry.item, "material-list", (v) => {
          entry.item = v;
        }, { allowCustom: true });
        rowBody.appendChild(fieldRow("アイテム (item)", itemInput, {
            desc: "バニラ Material か custom:<ID>。custom: は ArsPaper の中間素材/スレッドと"
              + " TrinityForge のカタログを両方引きます。custom:thread_* を指定すると"
              + "スレッドの厳選(個体差)がそのまま乗ります。"
          }));
      }

      const numbers = h("div", { class: "range-row" }, [
        numberCell("確率", entry.chance, 0.05, [0, 1, 0.01], (v) => {
          if (v == null) delete entry.chance; else entry.chance = v;
        }),
        numberCell("最小", entry.min, 1, [1, 64, 1], (v) => {
          if (v == null) delete entry.min; else entry.min = v;
        }),
        numberCell("最大", entry.max, 1, [1, 64, 1], (v) => {
          if (v == null) delete entry.max; else entry.max = v;
        })
      ]);
      rowBody.appendChild(fieldRow("確率と個数", numbers, {
        desc: "確率は「判定1回あたり」。判定回数は下の rolls です。個数は最小〜最大の一様乱数(省略時1個)。"
      }));

      rowBody.appendChild(h("button", {
        class: "btn-small danger", type: "button", text: "この候補を削除",
        onclick: () => { list.splice(index, 1); onStructureChange(); }
      }));
      box.appendChild(card([
        h("span", { class: "card-title", text: type === "enchant-book" ? "カスタムエンチャント本" : resolveItemDisplayLabel(entry.item) }),
        h("span", {
          class: "card-subtitle",
          text: (entry.chance == null ? 5 : Math.round(entry.chance * 1000) / 10) + "%"
        })
      ], [rowBody]));
    });
    box.appendChild(h("button", {
      class: "btn-small", type: "button", text: "+ 候補を追加",
      onclick: () => { list.push({ item: "DIAMOND", chance: 0.05 }); onStructureChange(); }
    }));
    return box;
  }

  window.buildLootTablesForm = function buildLootTablesForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (typeof working.enabled !== "boolean") working.enabled = true;
    if (!working.pools || typeof working.pools !== "object" || Array.isArray(working.pools)) {
      working.pools = {};
    }
    const root = h("div", { class: "dedicated-form" });

    function headerCard() {
      return card([subTitle("全体設定")], [
        fieldRow("有効化 (enabled)",
          window.checkboxInput(working.enabled, (v) => { working.enabled = v; }), {
            desc: "false にするとこのファイルの抽選を全部止めます"
              + "(ウォーデンの残響の欠片は ArsPaper 全体設定側なので影響しません)。"
          }),
        h("div", {
          class: "field-desc",
          style: "font-size:11px;color:var(--muted,#6b7280);margin:6px 0 0;",
          text: "構造物は「厳選スレッドの入手経路」として設計しています。"
            + "ステータス上限を上げる縦強化はここに置かず、増えるのは横の選択肢だけにしてください。"
            + "村・トライアルチャンバーのような無限/大量にあるチェストを対象にすると経済が壊れます。"
        })
      ]);
    }

    function poolCard(id) {
      const pool = working.pools[id] && typeof working.pools[id] === "object"
        ? working.pools[id] : (working.pools[id] = {});
      const tableCount = Array.isArray(pool.tables) ? pool.tables.length : 0;
      const entryCount = Array.isArray(pool.entries) ? pool.entries.length : 0;
      const head = [
        h("span", { class: "card-title", text: id }),
        h("span", { class: "card-subtitle", text: "対象 " + tableCount + " 件 / 候補 " + entryCount + " 件" })
      ];

      const body = h("div", { class: "card-list-body" });
      const idInput = h("input", { class: "field-input", value: id });
      idInput.addEventListener("change", () => {
        const next = idInput.value.trim().toLowerCase();
        if (!next || next === id) { idInput.value = id; return; }
        if (!/^[a-z0-9_]+$/.test(next)) {
          alert("IDは半角英小文字・数字・アンダースコアのみ使用できます");
          idInput.value = id;
          return;
        }
        if (Object.prototype.hasOwnProperty.call(working.pools, next)) {
          alert("同じIDが既にあります");
          idInput.value = id;
          return;
        }
        renameKey(working.pools, id, next);
        render();
      });
      body.appendChild(fieldRow("プールID", idInput,
        { desc: "表示用のラベルです。Java 側からIDで参照されることはありません。" }));

      body.appendChild(fieldRow("抽選回数 (rolls)",
        numberCell("回", pool.rolls, 1, [1, 16, 1], (v) => {
          if (v == null) delete pool.rolls; else pool.rolls = v;
        }), {
          desc: "各候補について rolls 回だけ独立に確率判定します。1 なら「候補ごとに1回ずつ判定」。"
            + "1回の生成で追加できるのは合計12個までです(Java 側の保険)。"
        }));

      body.appendChild(subTitle("対象ルートテーブル (tables)",
        "パスの最後の要素 / namespace:path の完全一致 / namespace:* の3通りが書けます"));
      body.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 4px;",
        text: "データパックの構造物は namespace:* でまとめて拾えます。導入前は該当テーブルが"
          + "生成されないだけで、警告も副作用も出ません。"
      }));
      body.appendChild(tablesBlock(pool, render));

      body.appendChild(subTitle("候補 (entries)"));
      body.appendChild(entriesBlock(pool, render));

      body.appendChild(h("button", {
        class: "btn-small danger", type: "button", text: "このプールを削除",
        onclick: () => {
          if (!confirm(id + " を削除しますか？")) return;
          delete working.pools[id];
          render();
        }
      }));
      return window.collapsibleCard(head, [body], { expanded: false });
    }

    function render() {
      root.innerHTML = "";
      root.appendChild(headerCard());
      const list = h("div", { class: "card-list" });
      const ids = Object.keys(working.pools);
      if (!ids.length) {
        list.appendChild(h("div", {
          class: "field-desc",
          text: "プールがありません。「+ プールを追加」で作成します。"
        }));
      }
      for (const id of ids) list.appendChild(poolCard(id));
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ プールを追加",
        onclick: () => {
          const id = uniqueKey(working.pools, "new_pool");
          working.pools[id] = {
            tables: ["simple_dungeon"],
            rolls: 1,
            entries: [{ item: "DIAMOND", chance: 0.05 }]
          };
          render();
        }
      }));
      root.appendChild(card([subTitle("ルートプール (pools)")], [list]));
    }

    render();
    return { element: root, getData: () => working };
  };
})();
