"use strict";

// progression/crafting-features.yml 専用フォーム。
// スキルツリー専用効果(flag)のランタイム本体チューニングを、機能単位のタブで編集する。
// 往復ロスレス: working を直接編集。未知キー・キー順は温存。

(function (isBrowser) {
  // ============================================================
  // 純関数 (ブラウザ非依存・Node テストから直接 require 可能)
  // ============================================================

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

  // オーバーエンチャ「任意ID方式」: プロファイルIDはユーザー自由入力。
  // 規則: 半角英数字・ハイフン・アンダースコアのみ、空文字不可。
  const OVER_ENCHANT_ID_RE = /^[a-zA-Z0-9_-]+$/;
  function isValidOverEnchantId(id) {
    return typeof id === "string" && OVER_ENCHANT_ID_RE.test(id.trim());
  }
  // 変更後IDの妥当性 + (自分自身以外との)一意性を検査する。
  // 戻り値: null = OK。それ以外はユーザー向けエラーメッセージ文字列。
  function checkOverEnchantIdAvailable(map, currentId, nextIdRaw) {
    const next = typeof nextIdRaw === "string" ? nextIdRaw.trim() : "";
    if (!next) return "IDを入力してください";
    if (!isValidOverEnchantId(next)) return "IDは半角英数字・ハイフン・アンダースコアのみ使用できます";
    if (next !== currentId && Object.prototype.hasOwnProperty.call(map, next)) return "同じIDが既にあります";
    return null;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = { uniqueKey, renameKey, isValidOverEnchantId, checkOverEnchantIdAvailable };
  }
  if (!isBrowser) return;

  // ============================================================
  // DOM 部品 (ブラウザ専用)
  // ============================================================
  const h = window.h;

  // T6 (2026-07-26): 木材修繕(wood-repair)は伐採ギミックタブへ、解体(disassembly)は鍛冶ギミックタブへ、
  // ポーション統合(potion-merge)/醸造解放(brew-unlocks)は醸造ギミックタブへ、オーバーエンチャ
  // (over-enchant)はエンチャントギミックタブへそれぞれ表示移設。working データ・保存先ファイル
  // (progression/crafting-features.yml)は不変。この画面は丸ごと読み込み・丸ごと書き戻すため、
  // 他タブが担当するサブツリーもここで温存される(normalizeCraftingFeaturesWorking 参照)。
  const SECTIONS = [
    { id: "overview", label: "概要", hint: "各チューニングとスキルツリー解放の対応" },
    { id: "thread-slots", label: "スレッド枠", hint: "item-stats カテゴリ別の枠上限" },
    { id: "coating", label: "コーティング", hint: "武器コーティング素材" },
    { id: "vanilla-remove", label: "バニラレシピ削除", hint: "バニラ/データパックレシピの無効化" },
    { id: "recipe-add", label: "レシピ追加", hint: "バニラアイテムを結果にするクラフトレシピの追加登録" },
    // T4 (2026-07-25): ArsPaper config.yml の mana.source-auto-consume.items をこのタブへ移設。
    // 保存先は ars-config (getExtraSaves)。
    { id: "source-auto-consume", label: "ソース自動消費", hint: "アイテム→マナ自動消費 (ArsPaper config.yml)" }
    // T7 (2026-07-26): ポーション品質換算(alchemy-quality.yml)は「醸造ギミック」タブ、
    // エンチャント運(enchant-luck.yml)は「エンチャントギミック」タブへそれぞれ表示移設した
    // (T5 で一度この画面へ集約したが、ユーザー指示によりエンチャント/醸造関連の切り出し先を
    // 各専用タブへ揃える形へ変更)。buildCraftingFeaturesPotionQualitySection /
    // buildCraftingFeaturesEnchantLuckSection を参照。
  ];

  // item-stats タブと同一キー (EquipmentSlotResolver が認識するカテゴリ)
  const THREAD_CATEGORIES = [
    { key: "armor", label: "防具", blurb: "item-stats「防具」タブ。ヘルメット〜ブーツ。", recommended: 5 },
    { key: "weapon", label: "武器", blurb: "item-stats「武器」タブ。剣・斧・弓など。", recommended: 0 },
    { key: "tool", label: "ツール", blurb: "item-stats「ツール」タブ。ツルハシ等。", recommended: 0 },
    { key: "other", label: "補助", blurb: "item-stats「補助」タブ。上記以外。", recommended: 0 }
  ];

  const POTION_TYPES = [
    "SPEED", "SLOWNESS", "HASTE", "MINING_FATIGUE", "STRENGTH", "INSTANT_HEALTH",
    "INSTANT_DAMAGE", "JUMP", "NAUSEA", "REGENERATION", "RESISTANCE", "FIRE_RESISTANCE",
    "WATER_BREATHING", "INVISIBILITY", "BLINDNESS", "NIGHT_VISION", "HUNGER", "WEAKNESS",
    "POISON", "WITHER", "HEALTH_BOOST", "ABSORPTION", "SATURATION", "GLOWING",
    "LEVITATION", "LUCK", "UNLUCK", "SLOW_FALLING", "CONDUIT_POWER", "DOLPHINS_GRACE",
    "BAD_OMEN", "HERO_OF_THE_VILLAGE", "DARKNESS", "TRIAL_OMEN", "RAID_OMEN",
    "WIND_CHARGED", "WEAVING", "OOZING", "INFESTED", "FAST_DIGGING", "HASTE"
  ];

  const BREW_BASES = ["AWKWARD", "MUNDANE", "THICK", "WATER", "NIGHT_VISION", "INVISIBILITY",
    "LEAPING", "FIRE_RESISTANCE", "SWIFTNESS", "SLOWNESS", "WATER_BREATHING", "HEALING",
    "HARMING", "POISON", "REGENERATION", "STRENGTH", "WEAKNESS", "LUCK", "TURTLE_MASTER",
    "SLOW_FALLING"];

  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function subTitle(text, title) {
    return h("div", { class: "sub-title", text, title: title || "" });
  }
  function emptyHint(text) {
    return h("div", { class: "empty-hint", text });
  }
  function formHint(text) {
    return h("p", { class: "form-hint", text });
  }
  function field(label, control, hint) {
    const kids = [h("span", { class: "form-label", text: label }), control];
    if (hint) kids.push(h("div", { class: "field-hint", text: hint }));
    return h("div", { class: "form-field" }, kids);
  }
  function ensureObj(parent, key, fallback) {
    if (parent[key] == null || typeof parent[key] !== "object" || Array.isArray(parent[key])) {
      parent[key] = fallback != null ? fallback : {};
    }
    return parent[key];
  }
  function migrateWorking(working) {
    // coating: gem-catalog-id → materials
    const coating = ensureObj(working, "coating", {});
    ensureObj(coating, "materials", {});
    if (Object.keys(coating.materials).length === 0 && coating["gem-catalog-id"]) {
      const id = coating["gem-catalog-id"];
      coating.materials[id] = {
        "bonus-damage": coating["bonus-damage-per-stack"] == null ? 2.0 : Number(coating["bonus-damage-per-stack"]),
        "max-stacks": coating["base-max-stacks"] == null ? 3 : Math.max(1, Math.floor(Number(coating["base-max-stacks"])))
      };
    }
    delete coating["gem-catalog-id"];
    delete coating["bonus-damage-per-stack"];
    if (coating["base-max-stacks"] == null) coating["base-max-stacks"] = 3;

    // wood-repair: single → materials
    const wood = ensureObj(working, "wood-repair", {});
    ensureObj(wood, "materials", {});
    if (Object.keys(wood.materials).length === 0 && wood["compressed-wood-catalog-id"]) {
      wood.materials[wood["compressed-wood-catalog-id"]] = {
        durability: wood["durability-per-compressed-wood"] == null
          ? 200
          : Math.max(1, Math.floor(Number(wood["durability-per-compressed-wood"])))
      };
    }
    delete wood["compressed-wood-catalog-id"];
    delete wood["durability-per-compressed-wood"];

    // disassembly: target wildcard → recipe-material conversion rules + percent-per-level
    const dis = ensureObj(working, "disassembly", {});
    if (dis["percent-per-level"] == null) dis["percent-per-level"] = 25;
    ensureObj(dis, "items", {});
    delete dis["scrap-fallback"];
    if (Object.keys(dis.items).length === 0 && dis.returns && typeof dis.returns === "object") {
      for (const [tier, entry] of Object.entries(dis.returns)) {
        if (!entry || typeof entry !== "object") continue;
        const mat = entry.material;
        const amount = Math.max(1, Math.floor(Number(entry.amount) || 1));
        if (!mat) continue;
        const prefix = String(tier).toUpperCase() === "GOLD" ? "GOLDEN" : String(tier).toUpperCase();
        for (const piece of ["HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"]) {
          const item = prefix + "_" + piece;
          if (!dis.items[item]) dis.items[item] = { [mat]: amount };
        }
      }
    }
    delete dis.returns;

    // over-enchant: tiers+enchants → per-id profiles
    const oe = ensureObj(working, "over-enchant", {});
    const hasProfile = Object.keys(oe).some((k) =>
      k !== "tiers" && k !== "fortune-cap-bonus" && k !== "enchants" && k !== "profiles"
      && oe[k] && typeof oe[k] === "object" && oe[k].enchants);
    if (!hasProfile && oe.tiers && typeof oe.tiers === "object") {
      const list = Array.isArray(oe.enchants) ? oe.enchants : [];
      const fortuneBonus = Math.max(0, Math.floor(Number(oe["fortune-cap-bonus"]) || 1));
      for (const [tid, bonusRaw] of Object.entries(oe.tiers)) {
        const bonus = Math.max(0, Math.floor(Number(bonusRaw) || 0));
        const enchants = {};
        for (const name of list) {
          const key = String(name || "").toUpperCase();
          if (!key) continue;
          // Approximate absolute caps (vanilla max unknown in editor): 5+bonus, fortune 3+fortuneBonus
          if (key === "FORTUNE") enchants[key] = 3 + fortuneBonus;
          else enchants[key] = 5 + bonus;
        }
        oe[tid] = { enchants };
      }
    }
    delete oe.tiers;
    delete oe["fortune-cap-bonus"];
    delete oe.enchants;
    delete oe.profiles;
  }

  // T6 (2026-07-26): crafting-features.yml のサブツリー既定値補完 + 旧形式マイグレーションを1箇所へ集約。
  // この画面(その他ギミック)に加え、同じ物理ファイルを丸ごと読み込み・書き戻す「エンチャントギミック」
  // 「醸造ギミック」タブ、およびコンパニオンとして disassembly/wood-repair だけを表示する
  // 「鍛冶ギミック」「伐採ギミック」タブからも呼ばれる(冪等: 既に正規化済みなら何もしない)。
  // window に公開し、別ファイル(tf-forms.js の鍛冶ギミック / tf-lifestyle-forms.js の伐採ギミック)からも
  // 呼べるようにする。
  function normalizeCraftingFeaturesWorking(working) {
    ensureObj(working, "gated-catalog-recipes", {});
    ensureObj(working, "coating", {});
    ensureObj(working, "wood-repair", {});
    ensureObj(working, "disassembly", {});
    ensureObj(working, "potion-merge", { "max-effects": 5, "max-duration-seconds": 960 });
    ensureObj(working, "brew-unlocks", {});
    ensureObj(working, "over-enchant", {});
    // T9 (2026-07-26新設): エンチャントテーブルの本棚パワー(max-bookshelves/power-per-bookshelf)。
    // 既定値(15 / 1.0)はバニラ実効パワーと完全一致するため、未設定でも挙動は変わらない。
    ensureObj(working, "enchant-bookshelf-power", { "max-bookshelves": 15, "power-per-bookshelf": 1.0 });
    if (working["enchant-bookshelf-power"]["max-bookshelves"] == null
        || !Number.isFinite(Number(working["enchant-bookshelf-power"]["max-bookshelves"]))) {
      working["enchant-bookshelf-power"]["max-bookshelves"] = 15;
    }
    if (working["enchant-bookshelf-power"]["power-per-bookshelf"] == null
        || !Number.isFinite(Number(working["enchant-bookshelf-power"]["power-per-bookshelf"]))) {
      working["enchant-bookshelf-power"]["power-per-bookshelf"] = 1.0;
    }
    ensureObj(working, "thread-slots", { "max-by-category": { armor: 5, weapon: 0, tool: 0, other: 0 } });
    ensureObj(working["thread-slots"], "max-by-category", { armor: 5, weapon: 0, tool: 0, other: 0 });
    if (!Array.isArray(working["removed-vanilla-recipes"])) working["removed-vanilla-recipes"] = [];
    if (!Array.isArray(working["removed-vanilla-items"])) working["removed-vanilla-items"] = [];
    if (working["added-recipes"] != null && !Array.isArray(working["added-recipes"])) delete working["added-recipes"];
    migrateWorking(working);

    const caps = working["thread-slots"]["max-by-category"];
    for (const cat of THREAD_CATEGORIES) {
      if (caps[cat.key] == null || !Number.isFinite(Number(caps[cat.key]))) {
        caps[cat.key] = cat.recommended;
      }
    }
    return working;
  }
  window.normalizeCraftingFeaturesWorking = normalizeCraftingFeaturesWorking;

  // ============================================================
  window.buildCraftingFeaturesForm = function buildCraftingFeaturesForm(data, options) {
    const opts = options && typeof options === "object" ? options : {};
    const catalogCandidates = Array.isArray(opts.catalogCandidates) ? opts.catalogCandidates : [];
    const working = data && typeof data === "object" ? data : {};
    // T4 (2026-07-25): ArsPaper config.yml をコンパニオンとして保持し、mana.source-auto-consume.items
    // (アイテム→マナ自動消費)をこの画面の「ソース自動消費」タブから編集する。保存は getExtraSaves 経由。
    const working2 = opts.arsConfigData && typeof opts.arsConfigData === "object" ? opts.arsConfigData : {};
    const sourceAutoConsume = ensureObj(ensureObj(working2, "mana", {}), "source-auto-consume", {});
    const sourceAutoConsumeItems = ensureObj(sourceAutoConsume, "items", {});
    normalizeCraftingFeaturesWorking(working);

    function catalogIdControl(value, onChange) {
      if (typeof window.catalogItemSuggest === "function" && catalogCandidates.length) {
        return window.catalogItemSuggest(value || "", catalogCandidates, (c) => {
          onChange(c && c.id ? c.id : "");
        }, { placeholder: "カタログID / 表示名で検索" });
      }
      return window.textInput(value || "", onChange);
    }

    let active = "overview";
    const root = h("div", { class: "dedicated-form cf-form" });
    const tabsEl = h("div", { class: "recipe-tabs cf-tabs", role: "tablist" });
    const bodyEl = h("div", { class: "cf-body" });
    root.appendChild(tabsEl);
    root.appendChild(bodyEl);

    function setActive(id) {
      active = id;
      renderTabs();
      renderBody();
    }

    function renderTabs() {
      tabsEl.innerHTML = "";
      for (const sec of SECTIONS) {
        tabsEl.appendChild(h("button", {
          class: "recipe-tab" + (active === sec.id ? " active" : ""),
          type: "button",
          role: "tab",
          "aria-selected": active === sec.id ? "true" : "false",
          title: sec.hint,
          onclick: () => setActive(sec.id)
        }, [h("span", { text: sec.label })]));
      }
    }

    function renderOverview() {
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "このファイルの役割" })],
        [
          formHint("スキルツリーの「専用効果」を取ったときのクラフト・修繕・スレッド枠などの実挙動を調整します。解放はスキルツリー側、数値・上限・対応表はこちらです。"),
          h("div", { class: "cf-flow" }, [
            flowStep("1. スキルツリー", "ノードで専用効果を選択"),
            flowArrow(),
            flowStep("2. プレイヤー解放", "perk 所持 = 効果 ON"),
            flowArrow(),
            flowStep("3. この設定", "上限・係数・対応表"),
            flowArrow(),
            flowStep("4. ゲーム内挙動", "GUI / クラフト / ステ")
          ])
        ]
      ));
      const map = h("div", { class: "cf-map" });
      const rows = [
        ["スレッド枠", "thread-slots", "item-stats カテゴリ上限でクランプ。"],
        ["コーティング", "coating", "素材ごとのダメージとスタック上限。"]
      ];
      for (const [title, key, desc] of rows) {
        map.appendChild(h("button", {
          class: "cf-map-card", type: "button",
          onclick: () => setActive(key)
        }, [
          h("div", { class: "cf-map-title", text: title }),
          h("div", { class: "cf-map-desc", text: desc })
        ]));
      }
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "機能マップ" })],
        [subTitle("カードをクリックすると該当タブへ"), map]
      ));
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "他タブへ移設した項目" })],
        [formHint(
          "木材修繕は「伐採ギミック」、解体は「鍛冶ギミック」、ポーション統合と醸造解放は「醸造ギミック」、"
          + "オーバーエンチャは「エンチャントギミック」タブへ表示移設しました。"
          + "保存先ファイル(progression/crafting-features.yml)は変わらず、この画面から保存しても他タブの内容は保持されます。"
        )]
      ));
    }

    function flowStep(title, text) {
      return h("div", { class: "cf-flow-step" }, [
        h("div", { class: "cf-flow-title", text: title }),
        h("div", { class: "cf-flow-text", text })
      ]);
    }
    function flowArrow() {
      return h("div", { class: "cf-flow-arrow", text: "→" });
    }

    function renderThreadSlots() {
      const capsMap = working["thread-slots"]["max-by-category"];
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "スレッド枠の決まり方" })],
        [
          formHint("カテゴリキーは item-stats の武器/防具/ツール/補助と同じです。基本枠 + 鍛冶ボーナスをこの上限で切り、装着者の thread-slot-expansion を GUI で上乗せします。"),
          h("div", { class: "cf-pipeline" }, [
            pipeChip("item-stats\nthread-slots", "基本枠"),
            h("span", { class: "cf-pipe-op", text: "+" }),
            pipeChip("鍛冶 C-beta", "クラフト焼込み"),
            h("span", { class: "cf-pipe-op", text: "≤" }),
            pipeChip("カテゴリ上限", "この画面"),
            h("span", { class: "cf-pipe-op", text: "+" }),
            pipeChip("expansion", "装着者のみ")
          ])
        ]
      ));
      const grid = h("div", { class: "cf-cat-grid" });
      for (const cat of THREAD_CATEGORIES) {
        const enabled = Number(capsMap[cat.key]) > 0;
        const cardEl = h("div", { class: "cf-cat-card" + (enabled ? " is-on" : " is-off") });
        cardEl.appendChild(h("div", { class: "cf-cat-head" }, [
          h("div", { class: "cf-cat-name", text: cat.label }),
          h("label", { class: "inline-check cf-cat-toggle" }, [
            window.checkboxInput(enabled, (v) => {
              capsMap[cat.key] = v ? Math.max(1, Number(capsMap[cat.key]) || cat.recommended || 1) : 0;
              renderBody();
            }),
            h("span", { text: enabled ? "対象" : "対象外" })
          ])
        ]));
        cardEl.appendChild(h("div", { class: "cf-cat-blurb", text: cat.blurb }));
        if (enabled) {
          const val = Math.max(0, Math.floor(Number(capsMap[cat.key]) || 0));
          const row = h("div", { class: "cf-cat-controls" });
          const slider = h("input", {
            type: "range", min: "1", max: "12", step: "1", value: String(val), class: "cf-range"
          });
          const num = window.numberInput(val, (v) => {
            if (v == null || v === "") return;
            capsMap[cat.key] = Math.max(0, Math.floor(v));
            renderBody();
          }, { int: true });
          slider.addEventListener("input", () => {
            capsMap[cat.key] = Number(slider.value);
            renderBody();
          });
          row.appendChild(h("span", { class: "range-label", text: "上限" }));
          row.appendChild(slider);
          row.appendChild(num);
          cardEl.appendChild(row);
        } else {
          cardEl.appendChild(h("div", { class: "cf-cat-off-note", text: "上限 0 = このカテゴリではスレッド枠なし。" }));
        }
        grid.appendChild(cardEl);
      }
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "カテゴリ別上限 (max-by-category)" })],
        [grid]
      ));
    }

    function pipeChip(text, caption) {
      return h("div", { class: "cf-pipe-chip" }, [
        h("div", { class: "cf-pipe-chip-cap", text: caption }),
        h("div", { class: "cf-pipe-chip-text", text })
      ]);
    }

    // 解放レシピ (gated-catalog-recipes) の専用UIは削除済み (2026-07-23 動的ゲート移行)。
    // レシピ解放はスキルツリーの recipe:<itemId> ゲートに移行。working データは往復ロスレスのため温存する
    // (ensureObj("gated-catalog-recipes", {}) は buildCraftingFeaturesForm 冒頭のまま)。

    function renderCoating() {
      const c = working.coating;
      const mats = ensureObj(c, "materials", {});
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "共通" })],
        [
          formHint("解放: weapon-coating-unlock。スタック増は coating-stack-increase（value 加算）。オフハンドに素材、メインに武器で右クリック。"),
          field("基本最大スタック (フォールバック)", window.numberInput(c["base-max-stacks"], (v) => {
            if (v == null) return;
            c["base-max-stacks"] = Math.max(1, Math.floor(v));
          }, { int: true }), "素材の max-stacks 未設定時の既定")
        ]
      ));
      const list = h("div", { class: "cf-mat-list" });
      const ids = Object.keys(mats);
      if (!ids.length) list.appendChild(emptyHint("コーティング素材がありません。"));
      for (const id of ids) {
        const entry = mats[id] && typeof mats[id] === "object" ? mats[id] : (mats[id] = {});
        if (entry["bonus-damage"] == null) entry["bonus-damage"] = 2.0;
        if (entry["max-stacks"] == null) entry["max-stacks"] = c["base-max-stacks"] || 3;
        const matCard = h("div", { class: "cf-mat-card" });
        matCard.appendChild(h("div", { class: "cf-mat-card-head" }, [
          h("span", { class: "entry-key-label", text: "コーティング素材" }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete mats[id]; renderBody(); }
          })
        ]));
        matCard.appendChild(field("カタログ ID", catalogIdControl(id, (v) => {
          const next = (v || "").trim();
          if (!next || next === id) return;
          if (Object.prototype.hasOwnProperty.call(mats, next)) {
            alert("同じカタログ ID が既にあります");
            renderBody();
            return;
          }
          renameKey(mats, id, next);
          renderBody();
        })));
        matCard.appendChild(field("1スタックあたり追加ダメージ", window.numberInput(entry["bonus-damage"], (v) => {
          if (v == null) return;
          entry["bonus-damage"] = v;
        }), "flat-bonus-damage に加算（武器 PDC に蓄積）"));
        matCard.appendChild(field("この素材のスタック上限", window.numberInput(entry["max-stacks"], (v) => {
          if (v == null) return;
          entry["max-stacks"] = Math.max(1, Math.floor(v));
        }, { int: true }), "実効上限 = min(基本最大スタック, この値) + coating-stack-increase"));
        list.appendChild(matCard);
      }
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 素材を追加",
        onclick: () => {
          mats[uniqueKey(mats, "source_gem")] = {
            "bonus-damage": 2.0,
            "max-stacks": c["base-max-stacks"] || 3
          };
          renderBody();
        }
      }));
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "素材一覧" })],
        [list]
      ));
    }

    function renderSourceAutoConsume() {
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "ソース自動消費 (mana.source-auto-consume.items)" })],
        [
          formHint("指定アイテムを自動でソース消費してマナに変換する対応表。保存先は ArsPaper config.yml "
            + "(このタブの内容は保存時に ars-config へ一緒に反映されます)。"),
          typeof window.buildSourceAutoConsumeItemsEditor === "function"
            ? window.buildSourceAutoConsumeItemsEditor(sourceAutoConsumeItems)
            : emptyHint("エディタ部品(tf-phase3-forms.js)が読み込まれていません。")
        ]
      ));
    }

    function renderBody() {
      bodyEl.innerHTML = "";
      switch (active) {
        case "overview": renderOverview(); break;
        case "thread-slots": renderThreadSlots(); break;
        case "coating": renderCoating(); break;
        case "vanilla-remove": renderVanillaRemove(); break;
        case "recipe-add": renderRecipeAdd(); break;
        case "source-auto-consume": renderSourceAutoConsume(); break;
        default: renderOverview();
      }
    }

    // バニラアイテムを結果にするクラフトレシピの追加登録 (added-recipes)。
    // レシピ編集UIはアイテムカタログのレシピ追加(renderCatalogRecipeCard)を流用し、
    // method は workbench/inventory のみに制限する。結果は必ずバニラ Material。反映は再起動 or /trinityforge reload。
    function renderRecipeAdd() {
      const box = h("div", {});
      const list = Array.isArray(working["added-recipes"]) ? working["added-recipes"] : [];
      if (!list.length) box.appendChild(emptyHint("追加レシピがありません。「+ レシピ追加」で作成します。"));
      list.forEach((entry, i) => {
        if (!entry || typeof entry !== "object" || Array.isArray(entry)) return;
        const entryCard = h("div", { class: "cf-mat-card recipe-card" });
        const head = h("div", { class: "stat-row" });
        head.appendChild(h("div", { class: "sub-title", text: `追加レシピ ${i + 1}` }));
        head.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "このレシピを削除",
          onclick: () => {
            list.splice(i, 1);
            if (!list.length) delete working["added-recipes"];
            renderBody();
          }
        }));
        entryCard.appendChild(head);
        // 結果アイテム: バニラ Material のみ (custom:/list: は不可。保存時に除去する)。
        entryCard.appendChild(field("結果アイテム (result)",
          window.materialInput(entry.result || "", "material-list", (v) => {
            entry.result = String(v == null ? "" : v).trim().replace(/^(custom:|list:)/i, "");
          }),
          "このレシピの完成品。バニラアイテム(Material)のみ。個数は下の amount で指定します。"));
        // レシピ本体(method/type/配置/素材/amount)はカタログのレシピ編集DOMを流用。
        entryCard.appendChild(window.renderCatalogRecipeCard(entry, () => renderBody(), {}, entry.result || "",
          { methods: ["workbench", "inventory"], allowMirror: true }));
        box.appendChild(entryCard);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ レシピ追加",
        onclick: () => {
          if (!Array.isArray(working["added-recipes"])) working["added-recipes"] = [];
          const fresh = { result: "", amount: 1, method: "workbench", type: "shaped" };
          if (typeof window.ensureShapedRecipe === "function") window.ensureShapedRecipe(fresh);
          working["added-recipes"].push(fresh);
          renderBody();
        }
      }));
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "レシピ追加 (added-recipes)" })],
        [
          formHint("バニラアイテムを結果にするクラフトレシピを追加登録します。レシピの編集UIはアイテムカタログと同じです。"
            + "素材はバニラMaterial / custom:<カタログID> / list:<互換ID> を指定できます。"),
          formHint("反映はサーバ再起動 または /trinityforge reload。※結果はバニラアイテムのみ(カタログ品はカタログ側でレシピ定義してください)。"),
          box
        ]
      ));
    }

    // バニラ/データパックレシピの無効化リスト (removed-vanilla-recipes)。
    // キーは基本「成果物のアイテムID」(例: minecraft:iron_sword)。TF反映は再起動 or /trinityforge reload。
    function renderVanillaRemove() {
      const list = working["removed-vanilla-recipes"];
      // 補完: Material名の小文字がほぼそのままバニラレシピキーになる。
      // customID/material と同じサジェスト形式(listSelect)。データパックの任意キーは自由入力(allowCustom)。
      const recipeKeyOptions = Array.isArray(window.MATERIALS)
        ? window.MATERIALS.map((m) => { const v = "minecraft:" + String(m).toLowerCase(); return { value: v, primary: v }; })
        : [];
      const box = h("div", { class: "cf-mat-list" });
      if (!list.length) box.appendChild(emptyHint("削除対象がありません。「+ 追加」でバニラレシピのキーを登録します。"));
      list.forEach((value, i) => {
        const rowCard = h("div", { class: "cf-mat-card" });
        const input = window.listSelect({
          value: value || "",
          options: recipeKeyOptions,
          allowCustom: true,
          placeholder: "minecraft:iron_sword",
          customPlaceholder: "minecraft:iron_sword / データパックのキー",
          onChange: (v) => { list[i] = String(v).trim(); }
        });
        rowCard.appendChild(field("レシピキー", input,
          "基本は成果物のアイテムID。minecraft: は省略可。データパックのキーも指定可。"));
        rowCard.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => { list.splice(i, 1); renderBody(); }
        }));
        box.appendChild(rowCard);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 追加",
        onclick: () => { list.push(""); renderBody(); }
      }));
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "バニラレシピ削除" })],
        [
          formHint("ここに登録したバニラ(またはデータパック)のクラフトレシピをサーバから無効化します。"
            + "リストから外して保存すると復元されます。反映はサーバ再起動 または /trinityforge reload。"),
          formHint("※ trinityforge: のレシピ(カタログ品)は対象外です。カタログ側でレシピ定義を削除してください。"
            + " なお同型のカタログレシピを登録した場合はバニラより優先されるため、置き換え目的なら削除は不要です。"),
          box
        ]
      ));

      renderVanillaRemoveItems();
    }

    // アイテム自体の削除 (removed-vanilla-items)。指定アイテムの入手経路(戦利品/ドロップ/釣り/村人取引/拾得)を遮断し、
    // 既存所持もログイン時等に掃除する。TFカスタムアイテム(trinityforge:)は対象外。
    function renderVanillaRemoveItems() {
      const items = working["removed-vanilla-items"];
      // 補完: Material名そのもの (minecraft: プレフィックスなし)。Material:enchant_id も手入力可。
      // customID/material と同じサジェスト形式(listSelect)。Material:enchant_id 等は自由入力(allowCustom)。
      const itemMaterialOptions = Array.isArray(window.MATERIALS)
        ? window.MATERIALS.map((m) => { const v = String(m); return { value: v, primary: v }; })
        : [];
      const box = h("div", { class: "cf-mat-list" });
      if (!items.length) box.appendChild(emptyHint("削除対象がありません。「+ 追加」でアイテムを登録します。"));
      items.forEach((value, i) => {
        const rowCard = h("div", { class: "cf-mat-card" });
        const input = window.listSelect({
          value: value || "",
          options: itemMaterialOptions,
          allowCustom: true,
          placeholder: "IRON_PICKAXE",
          customPlaceholder: "IRON_PICKAXE / enchanted_book:mending",
          onChange: (v) => { items[i] = String(v).trim(); }
        });
        rowCard.appendChild(field("Material または Material:enchant_id", input,
          "エンチャント指定は Material:enchant_id の形式(例: enchanted_book:mending = 修繕の本だけ対象)。"));
        rowCard.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => { items.splice(i, 1); renderBody(); }
        }));
        box.appendChild(rowCard);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 追加",
        onclick: () => { items.push(""); renderBody(); }
      }));
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "アイテム自体を削除" })],
        [
          formHint("指定アイテムの入手を遮断し、既存の所持も掃除します。例: IRON_PICKAXE / enchanted_book:mending"
            + "(エンチャント指定は Material:enchant_id)。"),
          formHint("戦利品/ドロップ/釣り/村人取引/拾得からブロックされ、既存所持はログイン時等に掃除されます。"
            + " TFカスタムアイテム(trinityforge:)は対象外です。"),
          box
        ]
      ));
    }

    renderTabs();
    renderBody();
    return { element: root, getData: () => {
      // 空行は保存対象から除外 (追加直後の未入力行)
      working["removed-vanilla-recipes"] =
        (working["removed-vanilla-recipes"] || []).filter((v) => typeof v === "string" && v.trim());
      working["removed-vanilla-items"] =
        (working["removed-vanilla-items"] || []).filter((v) => typeof v === "string" && v.trim());
      return working;
    },
    getExtraSaves: () => [
      { id: "ars-config", data: working2 }
    ] };
  };

  // ============================================================
  // T6 (2026-07-26): crafting-features.yml のサブツリー単位で再利用する section builder。
  // それぞれ自己完結(内部で render() を持ち、変更のたびに自分の要素だけ innerHTML を再構築する)。
  // 呼び出し元(このファイル内の buildEnchantGimmickForm / buildBrewGimmickForm、
  // および別ファイル tf-forms.js の鍛冶ギミック / tf-lifestyle-forms.js の伐採ギミック)は、
  // それぞれの working サブツリーを渡して返り値の要素を自分のカード/root へ appendChild するだけでよい。
  // ============================================================

  // 木材修繕 (wood-repair)。素材ごとの耐久回復量を編集する。伐採ギミックタブが呼ぶ。
  window.buildCraftingFeaturesWoodRepairSection = function buildWoodRepairSection(wood, catalogCandidatesArg) {
    const candidates = Array.isArray(catalogCandidatesArg) ? catalogCandidatesArg : [];
    function catalogIdControl(value, onChange) {
      if (typeof window.catalogItemSuggest === "function" && candidates.length) {
        return window.catalogItemSuggest(value || "", candidates, (c) => {
          onChange(c && c.id ? c.id : "");
        }, { placeholder: "カタログID / 表示名で検索" });
      }
      return window.textInput(value || "", onChange);
    }
    const root = h("div", {});
    function render() {
      root.innerHTML = "";
      const mats = ensureObj(wood, "materials", {});
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "圧縮木材修繕" })],
        [formHint("解放: wood-repair-unlock。金床の第2スロットに素材を置くと耐久回復します。")]
      ));
      const list = h("div", { class: "cf-mat-list" });
      const ids = Object.keys(mats);
      if (!ids.length) list.appendChild(emptyHint("修繕素材がありません。"));
      for (const id of ids) {
        const entry = mats[id] && typeof mats[id] === "object" ? mats[id] : (mats[id] = { durability: 200 });
        if (entry.durability == null) entry.durability = 200;
        const matCard = h("div", { class: "cf-mat-card" });
        matCard.appendChild(h("div", { class: "cf-mat-card-head" }, [
          h("span", { class: "entry-key-label", text: "修繕素材" }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete mats[id]; render(); }
          })
        ]));
        matCard.appendChild(field("カタログ ID", catalogIdControl(id, (v) => {
          const next = (v || "").trim();
          if (!next || next === id) return;
          if (Object.prototype.hasOwnProperty.call(mats, next)) {
            alert("同じカタログ ID が既にあります");
            render();
            return;
          }
          renameKey(mats, id, next);
          render();
        })));
        matCard.appendChild(field("1個あたり耐久回復", window.numberInput(entry.durability, (v) => {
          if (v == null) return;
          entry.durability = Math.max(1, Math.floor(v));
        }, { int: true })));
        matCard.appendChild(field("クイック修繕", window.checkboxInput(!!entry["quick-repair"], (v) => {
          if (v) entry["quick-repair"] = true;
          else delete entry["quick-repair"];
        })));
        list.appendChild(matCard);
      }
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 素材を追加",
        onclick: () => {
          mats[uniqueKey(mats, "compressed_wood_1x")] = { durability: 200 };
          render();
        }
      }));
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "素材一覧" })],
        [list]
      ));
    }
    render();
    return root;
  };

  // 解体 (disassembly)。返却%と対象シリーズ↔返却ルールを編集する。鍛冶ギミックタブが呼ぶ。
  window.buildCraftingFeaturesDisassemblySection = function buildDisassemblySection(dis) {
    const root = h("div", {});
    function render() {
      root.innerHTML = "";
      const items = ensureObj(dis, "items", {});
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "返却の仕組み" })],
        [
          formHint("解放したプレイヤーが置いた金床を対象アイテムの上に落とすと、下にある複数アイテムを同時に解体します。返却数 = floor(floor(レシピ素材数 × 解体Lv × %/100) × 返却倍率)。対象・素材が未設定、または返却数が0の場合は消費されません。"),
          field("レベルあたり返却%", window.numberInput(dis["percent-per-level"], (v) => {
            if (v == null) return;
            dis["percent-per-level"] = Math.max(0, Math.floor(v));
          }, { int: true }), "例: Lv1 × 25% → 鉄チェスト(8) なら floor(8×0.25)=2 個")
        ]
      ));

      const list = h("div", { class: "cf-mat-list" });
      const itemKeys = Object.keys(items);
      if (!itemKeys.length) list.appendChild(emptyHint("対象シリーズの返却設定がありません。"));
      for (const itemMat of itemKeys) {
        const rules = Array.isArray(items[itemMat]) ? items[itemMat] : (items[itemMat] = []);
        const itemCard = h("div", { class: "cf-mat-card" });
        itemCard.appendChild(h("div", { class: "cf-mat-card-head" }, [
          h("span", { class: "entry-key-label", text: "解体対象シリーズ" }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete items[itemMat]; render(); }
          })
        ]));
        itemCard.appendChild(field("対象 ID（末尾 * でシリーズ指定）", window.textInput(itemMat, (v) => {
          const next = (v || "").trim();
          if (!next || next === itemMat) return;
          if (Object.prototype.hasOwnProperty.call(items, next)) {
            alert("同じ対象 ID が既にあります");
            render();
            return;
          }
          renameKey(items, itemMat, next);
          render();
        }, { allowCustom: false })));
        const ingBox = h("div", { class: "stat-rows" });
        rules.forEach((rule, index) => {
          const row = h("div", { class: "stat-row" });
          row.appendChild(h("span", { class: "range-label", text: "クラフト素材" }));
          row.appendChild(window.materialInput(rule.input || "", "material-list", (v) => {
            rule.input = (v || "").trim();
          }, { allowCustom: true }));
          row.appendChild(h("span", { class: "range-label", text: "返却先" }));
          row.appendChild(window.materialInput(rule.output || "", "material-list", (v) => {
            rule.output = (v || "").trim();
          }, { allowCustom: true }));
          row.appendChild(h("span", { class: "range-label", text: "返却倍率" }));
          row.appendChild(window.numberInput(rule.multiplier == null ? 1 : rule.multiplier, (v) => {
            if (v != null) rule.multiplier = Math.max(0, v);
          }));
          row.appendChild(h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { rules.splice(index, 1); render(); }
          }));
          ingBox.appendChild(row);
        });
        ingBox.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 返却項目",
          onclick: () => {
            rules.push({ input: "IRON_INGOT", output: "IRON_INGOT", multiplier: 1 });
            render();
          }
        }));
        itemCard.appendChild(h("div", { class: "form-field" }, [
          h("span", { class: "form-label", text: "クラフト素材 → 返却先" }),
          ingBox
        ]));
        list.appendChild(itemCard);
      }
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 対象シリーズ追加",
        onclick: () => {
          let number = 1;
          let key = "series_1_*";
          while (Object.prototype.hasOwnProperty.call(items, key)) key = `series_${++number}_*`;
          items[key] = [{ input: "IRON_INGOT", output: "IRON_INGOT", multiplier: 1 }];
          render();
        }
      }));
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "対象シリーズ ↔ 返却ルール" })],
        [list]
      ));
    }
    render();
    return root;
  };

  // ポーション統合 (potion-merge)。醸造ギミックタブが呼ぶ。
  window.buildCraftingFeaturesPotionMergeSection = function buildPotionMergeSection(p) {
    const root = h("div", {});
    const body = h("div", { class: "const-body" });
    body.appendChild(field("最大効果数(グローバル既定値)", window.numberInput(p["max-effects"], (v) => {
      if (v == null) return;
      p["max-effects"] = Math.max(1, Math.floor(v));
    }, { int: true }), "下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"));
    body.appendChild(field("最大持続秒(グローバル既定値)", window.numberInput(p["max-duration-seconds"], (v) => {
      if (v == null) return;
      p["max-duration-seconds"] = Math.max(1, Math.floor(v));
    }, { int: true }), "下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"));
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "ポーション統合" })],
      [
        formHint("解放: potion-merge(2026-07-26 tier-expand: SCALE化。tierはノードvalueから決まる)。"),
        body,
        h("div", { class: "sub-title", text: "tier別設定 (tiers) — 該当tier行があればグローバル既定値より優先される" }),
        typeof window.tierTableEditor === "function"
          ? window.tierTableEditor(p, [
              { key: "max-effects", label: "最大効果数", int: true },
              { key: "max-duration-seconds", label: "最大持続秒", int: true }
            ])
          : h("div", { class: "empty-hint", text: "tier表エディタ(tf-lifestyle-forms.js)が読み込まれていません。" })
      ]
    ));
    return root;
  };

  // 醸造解放 (brew-unlocks)。醸造ギミックタブが呼ぶ。
  window.buildCraftingFeaturesBrewSection = function buildBrewSection(brew) {
    const root = h("div", {});
    function render() {
      root.innerHTML = "";
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "醸造解放" })],
        [formHint("材料はバニラ Material 名、または custom:<カタログID>。該当材料の醸造は専用効果所持者のみ（未所持はBrewEventキャンセル）。custom: は醸造タイマーを強制開始します。")]
      ));
      const keys = Object.keys(brew);
      if (!keys.length) {
        root.appendChild(emptyHint("エントリがありません。「+ グループ追加」で作成します。"));
      }
      for (const gid of keys) {
        const group = brew[gid] && typeof brew[gid] === "object" ? brew[gid] : (brew[gid] = {});
        if (!Array.isArray(group.potions)) group.potions = [];
        const head = h("div", { class: "entry-head-row" }, [
          h("span", { class: "range-label", text: "グループID" }),
          (() => {
            const inp = h("input", { class: "field-input", value: gid, spellcheck: "false" });
            inp.addEventListener("change", () => {
              const v = (inp.value || "").trim();
              if (!v || v === gid) { inp.value = gid; return; }
              if (Object.prototype.hasOwnProperty.call(brew, v)) {
                alert("同じグループ ID が既にあります");
                inp.value = gid;
                return;
              }
              renameKey(brew, gid, v);
              render();
            });
            return inp;
          })(),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete brew[gid]; render(); }
          })
        ]);
        const body = h("div", { class: "stat-rows" });
        // group.effect の select は削除 (2026-07-23 動的ゲート移行)。effect キー自体は往復ロスレスのため
        // working データに残るが、実際の解放判定はスキルツリーのノード効果 brew:<グループID> 参照のみ。
        body.appendChild(formHint(
          `解放はスキルツリーのノード効果「醸造解放」(brew:${gid}) から参照。未参照のグループは解放不可。`
        ));
        group.potions.forEach((pot, idx) => {
          if (!pot || typeof pot !== "object") group.potions[idx] = pot = {};
          if (!pot.result || typeof pot.result !== "object") {
            pot.result = { type: "SPEED", duration: 3600, amplifier: 0 };
          }
          const row = h("div", { class: "cf-mat-card cf-brew-card" });
          row.appendChild(field("ベース", window.selectInput(pot.base || "AWKWARD", BREW_BASES, (v) => {
            pot.base = v;
          })));
          // タスク8 (2026-07-26): 材料IDが Material の生ID(例: NETHER_WART)のままで日本語表示が
          // 無かったため、他タブと同じ materialHintEl 相当の日本語ヒントを追加する。保存値は
          // pot.ingredient の生ID文字列のまま変えない(表示だけ日本語化。ロスレス性は維持)。
          const ingredientHint = h("span", { class: "mat-hint" });
          const updateIngredientHint = (v) => {
            const label = window.LABELS && typeof window.LABELS.materialLabelWithFallback === "function"
              ? window.LABELS.materialLabelWithFallback(v) : (v || "");
            const raw = v == null ? "" : String(v);
            ingredientHint.textContent = raw ? label : "";
            ingredientHint.title = raw && label !== raw ? `${label} (${raw})` : "";
          };
          updateIngredientHint(pot.ingredient);
          row.appendChild(field("材料 (Material / custom:id)", h("span", { class: "input-with-hint" }, [
            window.materialInput(pot.ingredient || "", "material-list", (v) => {
              pot.ingredient = v;
              updateIngredientHint(v);
            }, { allowCustom: true }),
            ingredientHint
          ])));
          const types = POTION_TYPES.slice();
          if (pot.result.type && !types.includes(pot.result.type)) types.unshift(pot.result.type);
          row.appendChild(field("効果", window.selectInput(pot.result.type || "SPEED", types, (v) => {
            pot.result.type = v;
          })));
          const nums = h("div", { class: "stat-row" });
          nums.appendChild(h("span", { class: "range-label", text: "tick" }));
          nums.appendChild(window.numberInput(pot.result.duration, (v) => {
            if (v == null) return;
            pot.result.duration = Math.max(1, Math.floor(v));
          }, { int: true }));
          nums.appendChild(h("span", { class: "range-label", text: "Lv(amplifier)" }));
          nums.appendChild(window.numberInput(pot.result.amplifier, (v) => {
            if (v == null) return;
            pot.result.amplifier = Math.max(0, Math.floor(v));
          }, { int: true }));
          nums.appendChild(h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { group.potions.splice(idx, 1); render(); }
          }));
          row.appendChild(nums);
          body.appendChild(row);
        });
        body.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 醸造レシピ",
          onclick: () => {
            group.potions.push({
              base: "AWKWARD",
              ingredient: "SUGAR",
              result: { type: "SPEED", duration: 3600, amplifier: 0 }
            });
            render();
          }
        }));
        root.appendChild(card([head], [body]));
      }
      root.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ グループ追加",
        onclick: () => {
          brew[uniqueKey(brew, "brew_group")] = { effect: "", potions: [] };
          render();
        }
      }));
    }
    render();
    return root;
  };

  // オーバーエンチャ (over-enchant)。エンチャントギミックタブが呼ぶ。
  window.buildCraftingFeaturesOverEnchantSection = function buildOverEnchantSection(oe) {
    const root = h("div", {});
    function render() {
      root.innerHTML = "";
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "オーバーエンチャ" })],
        [formHint("プロファイルIDは任意入力です。スキルツリーの『オーバーエンチャ』効果 (overenchant:<ID>) から参照されたIDのみ、対象エンチャントと絶対レベル上限が適用されます。複数所持時はエンチャントごとに最大の上限を採用します。")]
      ));
      const profileIds = Object.keys(oe).filter((k) =>
        oe[k] && typeof oe[k] === "object" && !Array.isArray(oe[k]));
      const enchList = Array.isArray(window.ENCHANT_KEYS)
        ? window.ENCHANT_KEYS.map((k) => String(k).toUpperCase())
        : [];

      if (!profileIds.length) {
        root.appendChild(emptyHint("プロファイルがありません。「+ 効果ID追加」で作成します。"));
      }
      for (const pid of profileIds) {
        const profile = ensureObj(oe, pid, {});
        const enchants = ensureObj(profile, "enchants", {});
        // 任意ID方式: プロファイルIDはユーザー自由入力 (uniqueKey/checkOverEnchantIdAvailable で一意性検証)。
        const idInput = h("input", { class: "field-input", value: pid, spellcheck: "false" });
        idInput.addEventListener("change", () => {
          const raw = idInput.value;
          const err = checkOverEnchantIdAvailable(oe, pid, raw);
          if (err) {
            alert(err);
            idInput.value = pid;
            return;
          }
          renameKey(oe, pid, raw.trim());
          render();
        });
        const head = h("div", { class: "entry-head-row" }, [
          h("span", { class: "range-label", text: "プロファイル ID" }),
          idInput,
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete oe[pid]; render(); }
          })
        ]);
        const box = h("div", { class: "stat-rows" });
        for (const name of Object.keys(enchants)) {
          const row = h("div", { class: "stat-row" });
          const opts = enchList.slice();
          if (name && !opts.includes(name)) opts.unshift(name);
          row.appendChild(window.selectInput(name, opts.length ? opts : [name || "SHARPNESS"], (v) => {
            const next = String(v || "").toUpperCase();
            if (!next || next === name) return;
            const cap = enchants[name];
            delete enchants[name];
            enchants[next] = cap;
            render();
          }));
          row.appendChild(h("span", { class: "range-label", text: "上限Lv" }));
          row.appendChild(window.numberInput(enchants[name], (v) => {
            if (v == null) return;
            enchants[name] = Math.max(1, Math.floor(v));
          }, { int: true }));
          row.appendChild(h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { delete enchants[name]; render(); }
          }));
          box.appendChild(row);
        }
        box.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ エンチャント",
          onclick: () => {
            enchants[uniqueKey(enchants, enchList[0] || "SHARPNESS")] = 6;
            render();
          }
        }));
        root.appendChild(card([head], [
          formHint("overenchant:" + pid + " — エンチャントごとの絶対上限"),
          box
        ]));
      }
      root.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 効果ID追加",
        onclick: () => {
          oe[uniqueKey(oe, "over-enchant-1")] = { enchants: { SHARPNESS: 6 } };
          render();
        }
      }));
    }
    render();
    return root;
  };

  // ポーション品質換算 (alchemy-quality.yml)。醸造ギミックタブが呼ぶ。
  // over-enchant 等と異なり保存先が crafting-features.yml とは別ファイルのため、working は
  // 呼び出し元(buildBrewGimmickForm)が用意したコンパニオンデータをそのまま渡す。
  window.buildCraftingFeaturesPotionQualitySection = function buildPotionQualitySection(pq) {
    if (pq["duration-ticks-per-quality"] == null) pq["duration-ticks-per-quality"] = 20.0;
    if (pq["amplifier-per-quality"] == null) pq["amplifier-per-quality"] = 0.5;
    if (pq["lingering-splash-duration-ticks-per-quality"] == null) pq["lingering-splash-duration-ticks-per-quality"] = 10.0;
    const body = h("div", { class: "const-body" });
    body.appendChild(field("効果時間 加算(tick/品質1pt)", window.numberInput(pq["duration-ticks-per-quality"], (v) => {
      if (v == null) return;
      pq["duration-ticks-per-quality"] = v;
    }), "20tick=1秒。"));
    body.appendChild(field("強度(amplifier) 加算(/品質1pt)", window.numberInput(pq["amplifier-per-quality"], (v) => {
      if (v == null) return;
      pq["amplifier-per-quality"] = v;
    }), "切り捨て(Math.floor)で整数キャストしてから適用。"));
    body.appendChild(field("スプラッシュ/残留 追加時間(tick/品質1pt)", window.numberInput(pq["lingering-splash-duration-ticks-per-quality"], (v) => {
      if (v == null) return;
      pq["lingering-splash-duration-ticks-per-quality"] = v;
    }), "通常の効果時間加算に上乗せする追加分。"));
    return card(
      [h("span", { class: "entry-key-label", text: "ポーション品質換算 (stat: potion_quality_bonus)" })],
      [formHint("スキルツリー(alchemy.yml)の「品質+N」ノードで蓄積するstatを、醸造ポーションの効果時間・強度へ換算する係数。保存先: stats/alchemy-quality.yml。"), body]
    );
  };

  // エンチャント運 (enchant-luck.yml)。エンチャントギミックタブが呼ぶ。
  // ポーション品質換算と同様、保存先が crafting-features.yml とは別ファイルのため working は
  // 呼び出し元(buildEnchantGimmickForm)が用意したコンパニオンデータをそのまま渡す。
  window.buildCraftingFeaturesEnchantLuckSection = function buildEnchantLuckSection(el) {
    if (el["level-boost-chance-per-luck"] == null) el["level-boost-chance-per-luck"] = 0.01;
    if (el["level-boost-max-steps"] == null) el["level-boost-max-steps"] = 2;
    if (el["overenchant-bonus-chance-per-luck"] == null) el["overenchant-bonus-chance-per-luck"] = 0.02;
    if (el["extra-enchant-chance-per-luck"] == null) el["extra-enchant-chance-per-luck"] = 0.005;
    const body = h("div", { class: "const-body" });
    body.appendChild(field("格上げ確率(/luck1.0)", window.numberInput(el["level-boost-chance-per-luck"], (v) => {
      if (v == null) return;
      el["level-boost-chance-per-luck"] = v;
    }), "適用確率 = min(1.0, この値 × enchant_luck)。バニラ上限まで。"));
    body.appendChild(field("格上げ最大試行回数", window.numberInput(el["level-boost-max-steps"], (v) => {
      if (v == null) return;
      el["level-boost-max-steps"] = Math.max(0, Math.floor(v));
    }, { int: true }), "1回の抽選で格上げを試行できる最大回数。"));
    body.appendChild(field("オーバーエンチャ格上げ確率(/luck1.0)", window.numberInput(el["overenchant-bonus-chance-per-luck"], (v) => {
      if (v == null) return;
      el["overenchant-bonus-chance-per-luck"] = v;
    }), "overenchant:<id> 解放者のみ、バニラ上限からさらに格上げを試行する確率。"));
    body.appendChild(field("追加エンチャ付与確率(/luck1.0)", window.numberInput(el["extra-enchant-chance-per-luck"], (v) => {
      if (v == null) return;
      el["extra-enchant-chance-per-luck"] = v;
    }), "抽選結果に無い別のエンチャントをレベル1で追加付与する確率(競合するものは付与しない)。"));
    return card(
      [h("span", { class: "entry-key-label", text: "エンチャント運 (stat: enchant_luck)" })],
      [formHint("スキルツリー(enchanting.yml)のbuffsで蓄積するstatを、エンチャントテーブルの格上げ抽選へ反映する重み付け。保存先: stats/enchant-luck.yml。"), body]
    );
  };

  // 本棚パワー (enchant-bookshelf-power)。エンチャントギミックタブが呼ぶ。
  // 保存先は working (crafting-features.yml) 自身のサブツリーなので、他のコンパニオン(enchant-luck等)と
  // 異なり getExtraSaves を経由しない(over-enchant と同じ扱い)。
  window.buildCraftingFeaturesBookshelfSection = function buildBookshelfSection(bp) {
    if (bp["max-bookshelves"] == null) bp["max-bookshelves"] = 15;
    if (bp["power-per-bookshelf"] == null) bp["power-per-bookshelf"] = 1.0;
    const body = h("div", { class: "const-body" });
    body.appendChild(field("考慮する本棚の最大数 (max-bookshelves)", window.numberInput(bp["max-bookshelves"], (v) => {
      if (v == null) return;
      bp["max-bookshelves"] = Math.max(0, Math.floor(v));
    }, { int: true }), "バニラ既定は15(これを超えて置いても本来は無効)。"));
    body.appendChild(field("本棚1個あたりのパワー係数 (power-per-bookshelf)", window.numberInput(bp["power-per-bookshelf"], (v) => {
      if (v == null) return;
      bp["power-per-bookshelf"] = v;
    }), "バニラは常に1.0固定(本棚1個=パワー1)。"));
    return card(
      [h("span", { class: "entry-key-label", text: "本棚パワー (enchant-bookshelf-power)" })],
      [
        formHint(
          "エンチャントテーブル周囲の本棚が生み出すエンチャントパワーの上限と係数です。"
          + "既定値(max-bookshelves: 15, power-per-bookshelf: 1.0)ではバニラの実効パワーと完全に一致し、"
          + "挙動は一切変化しません。"
        ),
        body,
        formHint(
          "注意: 上限を極端に上げる/係数を大きくすると、少ない本棚数でも高コスト・高レベルの提示が"
          + "出やすくなり、エンチャントが簡単になりすぎます。バランス調整は慎重に行ってください。"
        )
      ]
    );
  };

  // ============================================================
  // progression/crafting-features.yml (エンチャントギミックタブ, over-enchant / enchant-bookshelf-power を担当)
  // ============================================================
  window.buildEnchantGimmickForm = function buildEnchantGimmickForm(data, opts) {
    const working = data && typeof data === "object" ? data : {};
    normalizeCraftingFeaturesWorking(working);

    // T7 (2026-07-26): エンチャント運(enchant-luck.yml)をこのタブへコンパニオン表示する。
    // 保存先ファイルが crafting-features.yml とは別のため、鍛冶/伐採ギミックの
    // craftingFeaturesData コンパニオンと同じ形(未指定なら getExtraSaves は空配列)で扱う。
    const enchantLuckData = opts && opts.enchantLuckData && typeof opts.enchantLuckData === "object"
      ? opts.enchantLuckData : undefined;
    const hasEnchantLuck = enchantLuckData !== undefined;
    const enchantLuckWorking = hasEnchantLuck ? enchantLuckData : {};

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(formHint(
      "スキルツリーの『オーバーエンチャ』専用効果 (overenchant:<ID>) が参照する、効果IDごとのエンチャント絶対上限です。"
      + "保存先は「その他ギミック」「醸造ギミック」等と同じ progression/crafting-features.yml ですが、"
      + "このタブは over-enchant / enchant-bookshelf-power サブツリーのみを編集します"
      + "(他のサブツリーは保存時もそのまま温存されます)。"
    ));
    root.appendChild(window.buildCraftingFeaturesOverEnchantSection(working["over-enchant"]));
    // T9 (2026-07-26新設): 本棚によるエンチャントパワー (enchant-bookshelf-power)。保存先はこのタブと
    // 同じ crafting-features.yml のため、コンパニオン扱いではなく working 内のサブツリーとして直接編集する。
    root.appendChild(window.buildCraftingFeaturesBookshelfSection(working["enchant-bookshelf-power"]));
    if (hasEnchantLuck) {
      root.appendChild(window.buildCraftingFeaturesEnchantLuckSection(enchantLuckWorking));
    }

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => hasEnchantLuck ? [{ id: "enchant-luck", data: enchantLuckWorking }] : []
    };
  };

  // ============================================================
  // progression/crafting-features.yml (醸造ギミックタブ, potion-merge / brew-unlocks を担当)
  // ============================================================
  window.buildBrewGimmickForm = function buildBrewGimmickForm(data, opts) {
    const working = data && typeof data === "object" ? data : {};
    normalizeCraftingFeaturesWorking(working);

    // T7 (2026-07-26): ポーション品質換算(alchemy-quality.yml)をこのタブへコンパニオン表示する。
    // 保存先ファイルが crafting-features.yml とは別のため、鍛冶/伐採ギミックの
    // craftingFeaturesData コンパニオンと同じ形(未指定なら getExtraSaves は空配列)で扱う。
    const alchemyQualityData = opts && opts.alchemyQualityData && typeof opts.alchemyQualityData === "object"
      ? opts.alchemyQualityData : undefined;
    const hasAlchemyQuality = alchemyQualityData !== undefined;
    const alchemyQualityWorking = hasAlchemyQuality ? alchemyQualityData : {};

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(formHint(
      "醸造(ブリューイングスタンド)まわりの専用効果チューニングです。"
      + "保存先は「その他ギミック」「エンチャントギミック」等と同じ progression/crafting-features.yml ですが、"
      + "このタブは potion-merge / brew-unlocks サブツリーのみを編集します(他のサブツリーは保存時もそのまま温存されます)。"
    ));
    root.appendChild(window.buildCraftingFeaturesPotionMergeSection(working["potion-merge"]));
    root.appendChild(window.buildCraftingFeaturesBrewSection(working["brew-unlocks"]));
    if (hasAlchemyQuality) {
      root.appendChild(window.buildCraftingFeaturesPotionQualitySection(alchemyQualityWorking));
    }

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => hasAlchemyQuality ? [{ id: "alchemy-quality", data: alchemyQualityWorking }] : []
    };
  };

  // ============================================================
  // progression/use-requirements.yml
  // ============================================================
  window.buildUseRequirementsForm = function buildUseRequirementsForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (typeof working.enforce !== "boolean") working.enforce = !!working.enforce;

    const root = h("div", { class: "dedicated-form" });
    const on = !!working.enforce;

    const status = h("div", { class: "ur-status" + (on ? " is-on" : " is-off") }, [
      h("div", { class: "ur-status-badge", text: on ? "制限ON" : "制限OFF" }),
      h("div", { class: "ur-status-text", text: on
        ? "use-level / use-skill が設定された装備は、スキル不足だと使用できません。"
        : "use-level / use-skill はアイテムに刻印されますが、ランタイムではブロックしません。" })
    ]);

    const body = h("div", { class: "const-body" });
    function refreshStatus(v) {
      const onNow = !!v;
      status.className = "ur-status" + (onNow ? " is-on" : " is-off");
      status.querySelector(".ur-status-badge").textContent = onNow ? "制限ON" : "制限OFF";
      status.querySelector(".ur-status-text").textContent = onNow
        ? "use-level / use-skill が設定された装備は、スキル不足だと使用できません。"
        : "use-level / use-skill はアイテムに刻印されますが、ランタイムではブロックしません。";
      const gateChip = status.parentElement && status.parentElement.querySelector(".ur-gate-chip .cf-pipe-chip-text");
      if (gateChip) gateChip.textContent = onNow ? "ゲート有効" : "ゲート無効";
    }

    body.appendChild(h("div", { class: "form-field" }, [
      h("span", { class: "form-label", text: "使用制限を有効化 (enforce)" }),
      window.checkboxInput(on, (v) => {
        working.enforce = !!v;
        refreshStatus(v);
      }),
      h("div", { class: "field-hint", text: "アイテムステータス側の use-level-requirement / use-skill と連動します。" })
    ]));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "使用レベル / 使用スキル制限" })],
      [
        formHint("アイテムカタログ／ステータスで設定した「使用可能レベル」を、実際に戦闘・ツール使用で強制するかどうかを切り替えます。"),
        status,
        body,
        h("div", { class: "cf-pipeline ur-pipeline" }, [
          pipeChip("item-stats\nuse-level / skill", "設定"),
          h("span", { class: "cf-pipe-op", text: "→" }),
          pipeChip("PDC 刻印", "スタンプ"),
          h("span", { class: "cf-pipe-op", text: "→" }),
          h("div", { class: "cf-pipe-chip ur-gate-chip" }, [
            h("div", { class: "cf-pipe-chip-cap", text: "この画面" }),
            h("div", { class: "cf-pipe-chip-text", text: on ? "ゲート有効" : "ゲート無効" })
          ])
        ])
      ]
    ));

    function pipeChip(text, caption) {
      return h("div", { class: "cf-pipe-chip" }, [
        h("div", { class: "cf-pipe-chip-cap", text: caption }),
        h("div", { class: "cf-pipe-chip-text", text })
      ]);
    }
    function formHint(text) {
      return h("p", { class: "form-hint", text });
    }
    function card(headChildren, bodyChildren) {
      return h("div", { class: "entry-card" }, [
        h("div", { class: "entry-head" }, headChildren),
        h("div", { class: "entry-body" }, bodyChildren)
      ]);
    }

    return { element: root, getData: () => working };
  };
})(typeof window !== "undefined" && typeof document !== "undefined");
