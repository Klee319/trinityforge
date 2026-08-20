"use strict";

// TrinityForge lore.yml 専用フォーム。
//   layout(ステータス表示テンプレート/色ルール) + multiplier-layers(乗算レイヤ) + stats.<key>
// ステはカテゴリタブ(攻撃/防御/クラフト/採集/補助/魔法/その他)ごとに編集。
// 2026-07 仕様変更:
//   - ステのセレクト/追加ボタンは廃止 (ステは実装依存のため増やせない)
//   - order 数値入力は廃止 → 行のドラッグで並び替え (順序は order へ自動採番)
//   - format セレクトは廃止 → 「単位」(デフォルト表示+カスタムチェックで自由入力)
//   - decimals → 小数点の桁数 / 0で隠す → 0を表示(既定OFF) / 符号 → 符号を非表示(符号は既定ON)
//   - プラス/マイナス色は固定値ステ・ロールステ別 + 高度なオプション(付与確率あり時の色)

(function () {
  const h = window.h;

  const LORE_CATEGORIES = [
    ["attack", "攻撃"],
    ["defense", "防御"],
    ["craft", "クラフト"],
    ["gathering", "採集"],
    ["utility", "補助"],
    ["ars", "魔法"],
    ["other", "その他"]
  ];

  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }

  // 職業EXP増加: 全スキル一律(skill-exp-bonus) + スキル別15キー(2026-08-05 に3→15へ拡張)。
  // POWER は EXP がプレイヤー行動から直接付与されないので存在しない(stats/lore.yml のコメント参照)。
  const SKILL_EXP_BONUS_KEYS = [
    "skill-exp-bonus",
    "woodcutting-exp-bonus", "farming-exp-bonus", "digging-exp-bonus",
    "mining-exp-bonus", "fishing-exp-bonus", "alchemy-exp-bonus", "enchanting-exp-bonus",
    "smithing-exp-bonus", "ars-smithing-exp-bonus", "ars-magic-exp-bonus", "archery-exp-bonus",
    "light-weapons-exp-bonus", "heavy-weapons-exp-bonus",
    "light-armor-exp-bonus", "heavy-armor-exp-bonus"
  ];

  function inferLoreCategory(stat) {
    const s = String(stat || "").toLowerCase();
    // 2026-07-23 7分類再編 (attack/defense/craft/gathering/utility/ars/other、旧 support は utility へ改名)。
    // entry.category が優先されるため、これは新規statや category 未設定時のフォールバックに過ぎない。
    // CT(item-cooldown) と 効率強化増幅(tool-enchant-*) は「その他」。
    if (s.includes("item-cooldown") || s.startsWith("tool-enchant")) return "other";
    // 職業EXP増加(スキル別)は attack/defense の部分一致より先に判定する。
    // ここを下(utility の配列)に置くと light-armor-exp-bonus / heavy-armor-exp-bonus が
    // "armor" の部分一致で防御へ落ちる(Java 側 StatCategoryInference も同じ理由で
    // 部分一致より前に置いている)。
    if (SKILL_EXP_BONUS_KEYS.includes(s)) return "utility";
    if (s.startsWith("craft-") || s.startsWith("workbench-") || s.startsWith("ritual-")
      // 2026-08-14: lapis-cost-reduction を廃止(ArsPaper の消費リスナーごと削除)。
      || ["material-refund-chance", "ingredient-save-chance"].includes(s)) return "craft";
    if (["mining-fortune", "fishing-luck", "fishing-bonus", "suspicious-respawn-chance", "hive-harvest-fortune"].includes(s)) return "gathering";
    if (["mana", "spell", "glyph", "thread", "slot", "arcane", "source-cost-reduction"].some((k) => s.includes(k))) return "ars";
    if ([
      "attack", "aoe", "crit", "penetration", "bleed", "bonus-damage", "damage-modifier", "fixed-damage",
      "bow-", "ammo-save", "arrow-", "distance-damage", "melee-knockback", "stun-chance", "power-attack", "cooldown-reduction"
    ].some((k) => s.includes(k))) return "attack";
    if (["defense", "resistance", "armor", "max-health", "knockback", "dodge", "reduction", "health-regen"].some((k) => s.includes(k))) return "defense";
    if ([
      "move-speed", "hunger-save-chance", "mob-drop-bonus", "loot-luck", "mob-drop-quality", "gacha-rate-bonus", "food-save-chance",
      // 2026-07-24 新規: バニラEXP/追加ドロップ/満腹度/繁殖・成長
      "kill-vanilla-exp-bonus", "break-vanilla-exp-bonus", "vanilla-exp-bonus", "breeding-vanilla-exp-bonus",
      // 2026-08-15: 破壊時バニラEXPの採取スキル別キー。共通キーと同じ utility に置く
      // (ここに無いと mining- を含む名前が上の gathering/attack の部分一致より後で other へ落ちる)。
      "mining-break-vanilla-exp-bonus", "woodcutting-break-vanilla-exp-bonus",
      "digging-break-vanilla-exp-bonus", "farming-break-vanilla-exp-bonus",
      "woodcutting-extra-drop-chance", "harvest-extra-drop-chance", "food-restore-bonus", "hidden-saturation-bonus",
      "breeding-extra-child-chance", "bred-animal-growth-bonus", "planted-crop-growth-bonus"
    ].includes(s)) return "utility";
    return "other";
  }

  // stat のデフォルト単位: PERCENT フォーマットは "%"、それ以外は既知辞書 (無ければ単位なし)。
  function defaultUnitFor(key, entry) {
    const fmt = String((entry && entry.format) || (window.FALLBACK_STAT_FORMATS && window.FALLBACK_STAT_FORMATS[key]) || "FLAT").toUpperCase();
    if (fmt === "PERCENT") return "%";
    return (window.FALLBACK_STAT_UNITS && window.FALLBACK_STAT_UNITS[key]) || "";
  }

  function categorySelect(value, onChange) {
    const cur = value || "other";
    return window.listSelect({
      value: cur,
      options: LORE_CATEGORIES.map(([id, label]) => ({
        value: id,
        primary: label,
        secondary: id
      })),
      onChange
    });
  }

  window.buildLoreForm = function buildLoreForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const root = h("div", { class: "dedicated-form lore-form" });
    let activeCat = "attack";

    // ---------- レイアウト (layout) ----------
    if (!working.layout || typeof working.layout !== "object") working.layout = {};
    root.appendChild(renderLayoutCard(working.layout));

    // ---------- 所有者・使用制限行 (bind) ----------
    if (!working.bind || typeof working.bind !== "object") working.bind = {};
    root.appendChild(renderBindCard(working.bind));

    // ---------- ステ表示定義 (stats) ----------
    if (!working.stats || typeof working.stats !== "object") working.stats = {};
    const stats = working.stats;

    // Ensure every entry has category (infer once for UI; saved on edit).
    for (const [key, entry] of Object.entries(stats)) {
      if (!entry || typeof entry !== "object") continue;
      if (!entry.category) entry.category = inferLoreCategory(key);
      if (Object.prototype.hasOwnProperty.call(entry, "default")) delete entry.default;
    }

    const statsCard = h("div", { class: "entry-card" });
    // 乗算レイヤカードはステ表示定義の直下に置く (2026-07 仕様変更)。
    // 生成関数はこの下で定義され、statsCard append 後に root へ追加する。
    statsCard.appendChild(h("div", { class: "entry-head" }, [
      h("span", { class: "entry-key-label", text: "ステ表示定義 (stats)" }),
      h("span", {
        class: "mini-label",
        text: "カテゴリごとに ==== 区切りで表示 (====の長さはアイテム情報ポップアップの横幅=最長行に自動追従)。行をドラッグして表示順を入れ替えられます。"
      })
    ]));
    const tabBar = h("div", { class: "recipe-tabs" });
    const statsBody = h("div", { class: "entry-body" });
    statsCard.appendChild(tabBar);
    statsCard.appendChild(statsBody);
    root.appendChild(statsCard);

    // ---------- 乗算レイヤ (multiplier-layers) — ステ表示定義の下 ----------
    root.appendChild(renderMultiplierLayersCard(working));

    function keysInCategory(cat) {
      return Object.keys(stats).filter((k) => {
        const e = stats[k];
        const c = (e && e.category) || inferLoreCategory(k);
        return c === cat;
      }).sort((a, b) => {
        const oa = Number(stats[a] && stats[a].order != null ? stats[a].order : 100);
        const ob = Number(stats[b] && stats[b].order != null ? stats[b].order : 100);
        if (oa !== ob) return oa - ob;
        return a.localeCompare(b);
      });
    }

    function renderTabs() {
      tabBar.innerHTML = "";
      for (const [cat, label] of LORE_CATEGORIES) {
        const count = keysInCategory(cat).length;
        tabBar.appendChild(h("button", {
          class: `recipe-tab ${activeCat === cat ? "active" : ""}`, type: "button",
          onclick: () => { activeCat = cat; render(); }
        }, [h("span", { text: label }), h("span", { class: "recipe-tab-count", text: String(count) })]));
      }
    }

    // ドラッグ後の並びを order へ自動採番 (カテゴリ内 1..N)。
    function applyOrderFromList(cat, orderedKeys) {
      orderedKeys.forEach((k, i) => {
        if (stats[k] && typeof stats[k] === "object") stats[k].order = i + 1;
      });
    }

    let dragKey = null;

    function render() {
      renderTabs();
      statsBody.innerHTML = "";
      const keys = keysInCategory(activeCat);
      if (keys.length === 0) {
        statsBody.appendChild(emptyGuide(
          `${LORE_CATEGORIES.find(([id]) => id === activeCat)?.[1] || activeCat} カテゴリにステがありません。`,
          "他カテゴリのステ行で「カテゴリ」を変更するとここへ移動します。(ステ自体は実装依存のため追加できません)"
        ));
        return;
      }
      const table = h("div", { class: "lore-stat-table" });
      table.appendChild(h("div", { class: "lore-stat-head" }, [
        cell("ステ"), cell("name"), cell("icon"), cell("単位"),
        cell("小数点の桁数"), cell("カテゴリ"),
        cell("符号を非表示"), cell("0を表示")
      ]));
      for (const key of keys) table.appendChild(renderStatRow(key, keys));
      statsBody.appendChild(table);
    }

    function renderStatRow(key, keysInCat) {
      const e = stats[key] && typeof stats[key] === "object" ? stats[key] : (stats[key] = {});
      if (!e.category) e.category = inferLoreCategory(key);
      // 移行(2026-07): 旧 `stats.<key>.default` はアイテム側 fallback `lore-default` へ移動済み。
      // Java側もこのキーを読まないため、フォームを開いた時点で残骸を掃除する(意図的なクリーンアップ)。
      if (Object.prototype.hasOwnProperty.call(e, "default")) delete e.default;
      const decimalsDesc = "小数点以下の表示桁数";
      const iconDesc = window.LABELS && typeof window.LABELS.fieldDesc === "function"
        ? window.LABELS.fieldDesc("icon") : "";

      // ステ列: セレクト廃止 → 日本語ラベル + 英字キー併記の固定表示 (ステは実装依存)。
      const label = (window.LABELS && window.LABELS.statLabel) ? window.LABELS.statLabel(key) : key;
      const statDesc = (window.LABELS && typeof window.LABELS.statDescription === "function")
        ? window.LABELS.statDescription(key) : "このステータスの実装上の説明は未登録です。";
      // 2026-07-31: 親 span の title 属性を撤去した。HTML の title は子孫にも効くため、
      // helpIcon の独自ポップオーバーとブラウザ標準ツールチップが同時に出ていた
      // (説明文の MiniMessage プレースホルダがそのまま見える症状)。キー名は helpIcon の
      // keyLabel(ツールチップ先頭行)と隣の mini-label で見せる。
      const keyLabel = h("span", { class: "lore-stat-key" }, [
        h("span", { class: "drag-handle", text: "⠿", title: "ドラッグで表示順を入れ替え" }),
        h("span", { text: label && label !== key ? label : key }),
        window.helpIcon(statDesc, { keyLabel: "キー: " + key }),
        label && label !== key ? h("span", { class: "mini-label", text: " " + key }) : null
      ]);

      // 単位列: デフォルト単位をグレーアウト表示、「カスタム」チェックONで自由入力。
      const defUnit = defaultUnitFor(key, e);
      const isCustom = typeof e.unit === "string" && e.unit !== defUnit;
      const unitCell = h("div", { class: "lore-cell lore-unit-cell" });
      function renderUnitCell(custom) {
        unitCell.innerHTML = "";
        const input = h("input", {
          class: "field-input lore-unit-input", type: "text", spellcheck: "false",
          value: custom ? (typeof e.unit === "string" ? e.unit : defUnit) : (defUnit || "(単位なし)")
        });
        if (!custom) {
          input.disabled = true;
          input.classList.add("locked");
          input.title = "デフォルト単位 (実装依存)。カスタムをONにすると自由入力できます。";
        } else {
          input.oninput = () => { e.unit = input.value; };
        }
        const cb = window.checkboxInput(custom, (v) => {
          if (v) {
            if (typeof e.unit === "string" && e.unit) {
              // 既存のカスタム値を維持
            } else if (defUnit) {
              e.unit = defUnit;
            } else {
              // デフォルト単位なし: 入力されるまで未保存にする (unit:"" のゴミキーを作らない)。
              delete e.unit;
            }
          } else {
            // OFF → デフォルトへ自動フォールバック。既定単位を明示保存するのは意図的:
            // Java側(lore.yml読取)はエディタのデフォルト単位辞書を持たないため、キーを消すと
            // ゲーム内表示から単位が消える。エディタUI上は「カスタムOFF」として扱われる。
            if (defUnit) e.unit = defUnit;
            else delete e.unit;
          }
          renderUnitCell(v);
        });
        unitCell.appendChild(input);
        unitCell.appendChild(h("label", { class: "inline-check", title: "ONにすると単位を自由入力 (OFFでデフォルトに戻る)" }, [
          cb, h("span", { class: "mini-label", text: "カスタム" })
        ]));
      }
      renderUnitCell(isCustom);

      const row = h("div", { class: "lore-stat-row", draggable: true }, [
        h("div", { class: "lore-cell lore-cell-stat" }, [keyLabel]),
        h("div", { class: "lore-cell" }, [window.textInput(e.name, (v) => {
          e.name = v;
          if (!window.STAT_META) window.STAT_META = {};
          if (!window.STAT_META[key] || typeof window.STAT_META[key] !== "object") {
            window.STAT_META[key] = { order: Number(e.order) || 100, category: e.category || "other" };
          }
          window.STAT_META[key].name = v;
        })]),
        h("div", { class: "lore-cell" }, [
          window.textInput(e.icon, (v) => { e.icon = v; }, "アイコン"),
          window.helpIcon(iconDesc)
        ]),
        unitCell,
        h("div", { class: "lore-cell num" }, [
          window.numberInput(e.decimals, (v) => { e.decimals = v == null ? 0 : v; }, { int: true }),
          window.helpIcon(decimalsDesc)
        ]),
        h("div", { class: "lore-cell" }, [categorySelect(e.category, (v) => {
          e.category = v;
          if (v !== activeCat) activeCat = v;
          render();
        })]),
        // 符号は既定ON。「符号を非表示」= !show-sign
        h("div", { class: "lore-cell check", title: "符号(+/-)を表示しない" }, [
          window.checkboxInput(e["show-sign"] === false, (v) => { e["show-sign"] = !v; })
        ]),
        // 「0を表示」= !hide-when-zero (既定OFF = 0は隠す)
        h("div", { class: "lore-cell check", title: "値が0でも行を表示する (既定OFF)" }, [
          window.checkboxInput(e["hide-when-zero"] === false, (v) => { e["hide-when-zero"] = !v; })
        ])
      ]);
      row.dataset.statKey = key;
      // 入力欄の上で始まったドラッグは行の並べ替えにしない(理由は guardRowDragFromInputs)。
      window.guardRowDragFromInputs(row);

      row.addEventListener("dragstart", (ev) => {
        dragKey = key;
        row.classList.add("dragging");
        ev.dataTransfer.effectAllowed = "move";
        ev.dataTransfer.setData("text/plain", key);
      });
      row.addEventListener("dragend", () => {
        dragKey = null;
        row.classList.remove("dragging");
        statsBody.querySelectorAll(".lore-stat-row").forEach((r) => {
          r.classList.remove("drag-over-before", "drag-over-after");
        });
      });
      row.addEventListener("dragover", (ev) => {
        if (!dragKey || dragKey === key) return;
        ev.preventDefault();
        statsBody.querySelectorAll(".lore-stat-row").forEach((r) => {
          r.classList.remove("drag-over-before", "drag-over-after");
        });
        const rect = row.getBoundingClientRect();
        const before = ev.clientY < rect.top + rect.height / 2;
        row.classList.add(before ? "drag-over-before" : "drag-over-after");
        row.dataset.dropBefore = before ? "1" : "0";
        ev.dataTransfer.dropEffect = "move";
      });
      row.addEventListener("drop", (ev) => {
        if (!dragKey || dragKey === key) return;
        ev.preventDefault();
        const ordered = keysInCategory(activeCat);
        const fromIdx = ordered.indexOf(dragKey);
        let toIdx = ordered.indexOf(key);
        if (fromIdx < 0 || toIdx < 0) return;
        const before = row.dataset.dropBefore !== "0";
        ordered.splice(fromIdx, 1);
        if (fromIdx < toIdx) toIdx--;
        if (!before) toIdx++;
        ordered.splice(Math.max(0, toIdx), 0, dragKey);
        applyOrderFromList(activeCat, ordered);
        dragKey = null;
        render();
      });
      return row;
    }

    render();
    return {
      element: root,
      getData: () => {
        // unit:"" の残骸掃除: デフォルト単位も空のステでは「空カスタム単位」に意味がなく、
        // リロード時にカスタムOFF扱いへ戻ってUIから見えない/消せないゴミキーになるため落とす。
        // (デフォルト単位が非空のステの unit:"" は「単位を消すカスタム」として有効なので保持する。)
        const st = working && typeof working.stats === "object" ? working.stats : {};
        for (const [key, e] of Object.entries(st)) {
          if (e && typeof e === "object" && e.unit === "" && !defaultUnitFor(key, e)) {
            delete e.unit;
          }
        }
        return working;
      }
    };

    function cell(text) { return h("div", { class: "lore-cell head", text }); }

    // ---------- bind カード (所有者行 / 使用制限行) ----------
    function renderBindCard(B) {
      const body = h("div", { class: "entry-inputs" });

      const ownerDesc = window.LABELS && typeof window.LABELS.fieldDesc === "function"
        ? window.LABELS.fieldDesc("owner-line") : "MiniMessage文字列。<owner>=所有者名。";
      body.appendChild(h("div", { class: "lore-layout-section" }, [
        h("label", { class: "inline-check" }, [
          window.checkboxInput(B["show-owner"] !== false, (v) => { B["show-owner"] = v; }),
          h("span", { text: "所有者行を表示 (show-owner)" })
        ]),
        window.subTitleEl("所有者行テンプレート (owner-line)", ownerDesc),
        window.richTextInput(B["owner-line"] || "", "minimessage", (v) => { B["owner-line"] = v; },
          { placeholders: ["owner"] })
      ]));

      const reqDesc = window.LABELS && typeof window.LABELS.fieldDesc === "function"
        ? window.LABELS.fieldDesc("use-requirement-line") : "MiniMessage文字列。<level>=使用可能レベル / <skill>=使用スキル。";
      body.appendChild(h("div", { class: "lore-layout-section" }, [
        h("label", { class: "inline-check" }, [
          window.checkboxInput(B["show-use-requirement"] !== false, (v) => { B["show-use-requirement"] = v; }),
          h("span", { text: "使用制限行を表示 (show-use-requirement)" })
        ]),
        window.subTitleEl("使用制限行テンプレート (use-requirement-line)", reqDesc),
        window.richTextInput(B["use-requirement-line"] || "", "minimessage",
          (v) => { B["use-requirement-line"] = v; },
          { placeholders: ["level", "skill"] })
      ]));

      return card([h("span", { class: "entry-key-label", text: "所有者・使用制限行 (bind)" })], [body]);
    }

    // ---------- layout カード ----------
    function renderLayoutCard(L) {
      const body = h("div", { class: "entry-inputs" });

      // ステータス表示テンプレート (旧: 行テンプレート)。GUI/簡易編集 (MiniMessage richText)。
      // ラベル+入力を縦積みにして、他セクションと同じ「見出し → 内容」の体裁に揃える。
      const templateDesc = "1ステ行のMiniMessageテンプレート。使えるプレースホルダー: "
        + "<icon>=アイコン文字列 / <name>=ステ表示名 / <value>=符号・単位・色つきの値。"
        + "例: <gray><icon><name>：<value></gray>";
      body.appendChild(h("div", { class: "lore-layout-section" }, [
        window.subTitleEl("ステータス表示テンプレート (line-template)", templateDesc),
        // 差し込みタグを宣言しないと、出荷値のように <icon> を含む値で GUI モードが常に無効になる。
        window.richTextInput(L["line-template"] || "", "minimessage", (v) => { L["line-template"] = v; },
          { placeholders: ["icon", "name", "value"] })
      ]));

      // スコア表示テンプレート (品質スコア行)。line-template と同じ GUI/簡易編集 UIUX。
      // 未設定時は Java 側デフォルトをそのまま表示する (編集した時だけ保存される)。
      const scoreTemplateDefault = "<tier><gray>:</gray><white>Score→<score></white>";
      const scoreTemplateDesc = "品質スコア行のMiniMessageテンプレート。使えるプレースホルダー: "
        + "<tier>=ティア色付きの【ティア名】 / <tier-name>=色なしのティア名 / <score>=品質スコア値。"
        + "例: " + scoreTemplateDefault;
      body.appendChild(h("div", { class: "lore-layout-section" }, [
        window.subTitleEl("スコア表示テンプレート (score-line-template)", scoreTemplateDesc),
        window.richTextInput(L["score-line-template"] || scoreTemplateDefault, "minimessage",
          (v) => { L["score-line-template"] = v; },
          { placeholders: ["tier", "tier-name", "score"] })
      ]));

      // 色ルール (colors.fixed / colors.roll)。旧 positive-color/negative-color は互換のため
      // YAMLに残っていても触らない (Java側は colors を優先)。
      if (!L.colors || typeof L.colors !== "object") L.colors = {};
      // 未設定時に適用される Java 側デフォルト (LoreConfig.parseColorRules と同じ導出):
      //   fixed.positive=white / roll.positive=green / negative は negative-color (既定 red)。
      const defNegative = typeof L["negative-color"] === "string" && L["negative-color"] ? L["negative-color"] : "red";
      body.appendChild(renderColorGroup(L.colors, "fixed", "固定値ステの色",
        "fixed / per-quality 由来のステ値の色 (既定: プラス=white / マイナス=negative-color)",
        { positive: "white", negative: defNegative }));
      body.appendChild(renderColorGroup(L.colors, "roll", "ロールステの色",
        "random ロール由来のステ値の色 (既定: プラス=green / マイナス=negative-color)",
        { positive: "green", negative: defNegative }));

      body.appendChild(h("div", { class: "sub-section" }, [
        h("div", { class: "sub-title", text: "ヘッダ行 (header)" }),
        lineList(L, "header")
      ]));
      body.appendChild(h("div", { class: "sub-section" }, [
        h("div", { class: "sub-title", text: "フッタ行 (footer)" }),
        lineList(L, "footer")
      ]));
      return card([h("span", { class: "entry-key-label", text: "レイアウト (layout)" })], [body]);
    }

    function renderColorGroup(colors, groupKey, title, desc, defaults) {
      if (!colors[groupKey] || typeof colors[groupKey] !== "object") colors[groupKey] = {};
      const g = colors[groupKey];
      const def = defaults || {};
      const boxBody = h("div", { class: "entry-inputs" });
      const row = h("div", { class: "field-grid" });
      row.appendChild(h("div", { class: "form-field" }, [
        h("span", { class: "form-label", text: "プラス値の色" }),
        colorCtl(g.positive, (v) => { if (v === "") delete g.positive; else g.positive = v; }, def.positive)
      ]));
      row.appendChild(h("div", { class: "form-field" }, [
        h("span", { class: "form-label", text: "マイナス値の色" }),
        colorCtl(g.negative, (v) => { if (v === "") delete g.negative; else g.negative = v; }, def.negative)
      ]));
      boxBody.appendChild(row);

      // 高度なオプション: 付与確率 (grant-chances) が設定されたステ専用の色。
      // (sub-section の入れ子は境界線が重なって段差に見えるためプレーンな div にする)
      const advBox = h("div", { class: "lore-layout-adv" });
      const hasAdv = g["chance-positive"] != null || g["chance-negative"] != null;
      function renderAdv(show) {
        advBox.innerHTML = "";
        if (!show) {
          advBox.appendChild(h("button", {
            class: "btn-small", type: "button", text: "高度なオプション (advanced)",
            title: "付与確率 (grant-chances) が設定されたステのプラス/マイナス値の色を別に設定します。",
            onclick: () => renderAdv(true)
          }));
          return;
        }
        advBox.appendChild(h("div", {
          class: "mini-label",
          text: "付与確率 (grant-chances) 付きステの色 (未設定なら通常色にフォールバック)"
        }));
        const advRow = h("div", { class: "field-grid" });
        advRow.appendChild(h("div", { class: "form-field" }, [
          h("span", { class: "form-label", text: "プラス値の色 (確率付与)" }),
          colorCtl(g["chance-positive"], (v) => { if (v === "") delete g["chance-positive"]; else g["chance-positive"] = v; })
        ]));
        advRow.appendChild(h("div", { class: "form-field" }, [
          h("span", { class: "form-label", text: "マイナス値の色 (確率付与)" }),
          colorCtl(g["chance-negative"], (v) => { if (v === "") delete g["chance-negative"]; else g["chance-negative"] = v; })
        ]));
        advBox.appendChild(advRow);
      }
      renderAdv(hasAdv);

      return h("div", { class: "sub-section" }, [
        window.subTitleEl(title, desc),
        boxBody,
        advBox
      ]);
    }

    // ---------- 乗算レイヤカード ----------
    // 2026-07 仕様変更: レイヤは「基準ステータスごと」に定義する。
    //   追加フロー: + 乗算レイヤを追加 → 基準ステータスをセレクトで選択 → レイヤ名を入力。
    //   アイテムステ側の乗算モードでは「そのステが基準のレイヤ」だけが選択候補に出る
    //   (未定義レイヤ/基準ステ不一致は保存時にエラー)。
    function renderMultiplierLayersCard(workingRoot) {
      const box = h("div", { class: "entry-card" });
      box.appendChild(h("div", { class: "entry-head" }, [
        h("span", { class: "entry-key-label", text: "乗算レイヤ (multiplier-layers)" }),
        h("span", {
          class: "mini-label",
          text: "アイテムステの乗算モードで選ぶレイヤ。基準ステータスごとに定義し、そのステの乗算モードでだけ選択できます。同一レイヤの倍率は足し合わせてから乗算。表示はレイヤ名のみ (符号=x・単位なし固定)。ドラッグで並び替え可 (セレクトの順番も変わる)。"
        })
      ]));
      const body = h("div", { class: "entry-body" });
      box.appendChild(body);

      function layers() {
        if (!Array.isArray(workingRoot["multiplier-layers"])) workingRoot["multiplier-layers"] = [];
        return workingRoot["multiplier-layers"];
      }
      function syncGlobal() {
        window.MULTIPLIER_LAYERS = layers()
          .filter((l) => l && typeof l === "object" && l.id)
          .map((l) => ({
            id: String(l.id),
            name: typeof l.name === "string" && l.name ? l.name : String(l.id),
            stat: typeof l.stat === "string" ? l.stat : ""
          }));
      }
      function newLayerId() {
        let n = layers().length + 1;
        let id = "layer_" + n;
        while (layers().some((l) => l && l.id === id)) id = "layer_" + (++n);
        return id;
      }

      let dragIdx = null;
      function renderLayers() {
        body.innerHTML = "";
        const arr = layers();
        if (arr.length === 0) {
          body.appendChild(h("div", { class: "empty-hint", text: "乗算レイヤはまだありません。「+ 乗算レイヤを追加」で作成し、基準ステータスを選んでレイヤ名を入力します。" }));
        }
        arr.forEach((layer, idx) => {
          if (!layer || typeof layer !== "object") return;
          const statSel = window.statSelect
            ? window.statSelect(layer.stat || "", (nv) => {
                if (!nv) return false;
                layer.stat = nv;
                syncGlobal();
                renderLayers();
                return true;
              })
            : window.textInput(layer.stat || "", (v) => { layer.stat = v; syncGlobal(); }, "基準ステータス");
          const row = h("div", { class: "stat-row lore-layer-row", draggable: true }, [
            h("span", { class: "drag-handle", text: "⠿", title: "ドラッグで並び替え" }),
            h("span", { class: "mini-label", text: "基準ステ" }),
            statSel,
            window.textInput(layer.name || "", (v) => { layer.name = v; syncGlobal(); }, "レイヤ名"),
            h("span", { class: "mini-label", text: layer.id || "" }),
            !layer.stat ? h("span", { class: "empty-hint", text: "(基準ステータス未選択: 保存できません)" }) : null,
            h("button", {
              class: "btn-small danger", type: "button", text: "×",
              title: "レイヤを削除 (このレイヤを使っているアイテムステ設定は未定義レイヤ扱いになり保存時にエラーになります)",
              onclick: () => {
                if (!confirm(`乗算レイヤ「${layer.name || layer.id}」を削除しますか？`)) return;
                arr.splice(idx, 1);
                syncGlobal();
                renderLayers();
              }
            })
          ]);
          // 入力欄の上で始まったドラッグは行の並べ替えにしない(理由は guardRowDragFromInputs)。
          window.guardRowDragFromInputs(row);
          row.addEventListener("dragstart", (ev) => {
            dragIdx = idx;
            row.classList.add("dragging");
            ev.dataTransfer.effectAllowed = "move";
          });
          row.addEventListener("dragend", () => {
            dragIdx = null;
            row.classList.remove("dragging");
            body.querySelectorAll(".lore-layer-row").forEach((r) => r.classList.remove("drag-over-before", "drag-over-after"));
          });
          row.addEventListener("dragover", (ev) => {
            if (dragIdx == null || dragIdx === idx) return;
            ev.preventDefault();
            body.querySelectorAll(".lore-layer-row").forEach((r) => r.classList.remove("drag-over-before", "drag-over-after"));
            const rect = row.getBoundingClientRect();
            const before = ev.clientY < rect.top + rect.height / 2;
            row.classList.add(before ? "drag-over-before" : "drag-over-after");
            row.dataset.dropBefore = before ? "1" : "0";
            ev.dataTransfer.dropEffect = "move";
          });
          row.addEventListener("drop", (ev) => {
            if (dragIdx == null || dragIdx === idx) return;
            ev.preventDefault();
            const before = row.dataset.dropBefore !== "0";
            const [moved] = arr.splice(dragIdx, 1);
            let to = idx;
            if (dragIdx < idx) to--;
            if (!before) to++;
            arr.splice(Math.max(0, to), 0, moved);
            dragIdx = null;
            syncGlobal();
            renderLayers();
          });
          body.appendChild(row);
        });
        // 追加ボタンは常に最下部に1つ (行ごとの追加ボタンは廃止)。
        // 追加 → 基準ステータスをセレクトで選択 → レイヤ名を入力、の順で設定する。
        body.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 乗算レイヤを追加",
          onclick: () => {
            arr.push({ id: newLayerId(), name: "", stat: "" });
            syncGlobal();
            renderLayers();
          }
        }));
      }
      renderLayers();
      syncGlobal();
      return box;
    }

    function colorCtl(value, onInput, defaultValue) {
      return window.colorPickerInput
        ? window.colorPickerInput(value, "mm-color", onInput, { defaultValue })
        : window.textInput(value, onInput);
    }
    function lineList(obj, arrKey) {
      const box = h("div", { class: "lore-rows" });
      const ensureArr = () => { if (!Array.isArray(obj[arrKey])) obj[arrKey] = []; return obj[arrKey]; };
      function renderLines() {
        box.innerHTML = "";
        const arr = Array.isArray(obj[arrKey]) ? obj[arrKey] : [];
        arr.forEach((line, idx) => {
          box.appendChild(h("div", { class: "stat-row lore-row" }, [
            window.richTextInput(line, "minimessage", (v) => { ensureArr()[idx] = v; }),
            h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { ensureArr().splice(idx, 1); renderLines(); } })
          ]));
        });
        box.appendChild(h("button", { class: "btn-small", type: "button", text: "+ 行追加", onclick: () => { ensureArr().push(""); renderLines(); } }));
      }
      renderLines();
      return box;
    }
  };

  // Editor select / item-stats から参照する lore メタ (order / category)。
  window.inferLoreCategory = inferLoreCategory;
  // カテゴリID→日本語ラベル対応。tf-skilltree.js のバフ追加UIでカテゴリ絞り込みに再利用する
  // (要望2026-07-26: スキル選択にステータスカテゴリの絞り込みを追加)。ラベルの重複定義を避けるため公開する。
  window.LORE_CATEGORIES = LORE_CATEGORIES;
})();
