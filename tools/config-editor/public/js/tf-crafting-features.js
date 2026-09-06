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
    // D7 (2026-07-31): レシピ本にTF/Arsのレシピを出す(索引用途)。
    { id: "recipe-book", label: "レシピ本", hint: "TF/Arsレシピをレシピ本に載せるかどうか" },
    // T4 (2026-07-25): ArsPaper config.yml の mana.source-auto-consume.items をこのタブへ移設。
    // 保存先は ars-config (getExtraSaves)。
    { id: "source-auto-consume", label: "ソース自動消費", hint: "アイテム→マナ自動消費 (ArsPaper config.yml)" }
    // ポーション品質換算(alchemy-quality.yml)の GUI は 2026-08-29 に外した(yml 直編集)。
    // エンチャント運は「エンチャントギミック」タブ (buildCraftingFeaturesEnchantLuckSection)。
  ];

  // item-stats タブと同一キー (EquipmentSlotResolver が認識するカテゴリ)
  // 既定値は Java 側 CraftingFeaturesConfig#DEFAULT_THREAD_SLOT_CAP(=5、全カテゴリ)のミラー。
  // ⚠ ここを Java とずらすと「開いて保存しただけ」で武器・触媒のスレッド枠が消える:
  //   上限 0 のカテゴリは ThreadSlotPolicy#applyCategoryCap が thread-slots をキーごと削除するため、
  //   lore に「スレッド枠 N枠」と出るのに装着 GUI も効果も無い状態になる(2026-07-31 F2 の実体)。
  const THREAD_SLOT_CAP_DEFAULT = 5;
  const THREAD_CATEGORIES = [
    { key: "armor", label: "防具", blurb: "item-stats「防具」タブ。ヘルメット〜ブーツ。", recommended: THREAD_SLOT_CAP_DEFAULT },
    { key: "weapon", label: "武器", blurb: "item-stats「武器」タブ。剣・斧・弓など。", recommended: THREAD_SLOT_CAP_DEFAULT },
    { key: "tool", label: "ツール", blurb: "item-stats「ツール」タブ。ツルハシ等。", recommended: THREAD_SLOT_CAP_DEFAULT },
    { key: "other", label: "補助", blurb: "item-stats「補助」タブ。触媒(杖)など上記以外。", recommended: THREAD_SLOT_CAP_DEFAULT }
  ];

  /**
   * カテゴリ上限を 0 にしようとしたときの警告文(2026-07-31 F6 指摘6)。
   *
   * ⚠ editor は「0以上の整数」を受け付ける一方、TF 側は出荷 yml に 0 を書くことを
   * テストで禁止している(ShippedThreadSlotCapDriftTest#noShippedCategoryDisablesThreadSlots)。
   * 0 は「枠が小さくなる」ではなく ThreadSlotPolicy#applyCategoryCap が thread-slots キーを
   * 丸ごと削除する = lore の「スレッド枠 N枠」も装着 GUI も効果も全部消えるので、
   * 「0=対象外」というラベルだけでは何が起こるか分からない(F2 の実体そのもの)。
   */
  function threadCapDisableWarning(categoryLabel) {
    return `「${categoryLabel}」の上限を 0 にすると、このカテゴリのスレッド機構が丸ごと無効になります。\n`
      + "・lore の「スレッド枠 N枠」表示が消えます\n"
      + "・装着 GUI (/ars thread・防具のスニーク+右クリック) が開かなくなります\n"
      + "・既に装着済みのスレッドの効果も出なくなります\n"
      + "（枠を減らしたいだけなら 1 以上の数値にしてください。"
      + "TF の出荷 yml は 0 を禁止しているので、0 のまま出荷するとTF側のテストが落ちます。）\n\n"
      + "それでも 0 にしますか？";
  }

  /** THREAD_CATEGORIES と同じ既定値の max-by-category マップを作る(ensureObj の初期値用)。 */
  function defaultThreadSlotCaps() {
    const caps = {};
    for (const cat of THREAD_CATEGORIES) caps[cat.key] = cat.recommended;
    return caps;
  }

  const POTION_TYPES = [
    "SPEED", "SLOWNESS", "HASTE", "MINING_FATIGUE", "STRENGTH", "INSTANT_HEALTH",
    "INSTANT_DAMAGE", "JUMP_BOOST", "NAUSEA", "REGENERATION", "RESISTANCE", "FIRE_RESISTANCE",
    "WATER_BREATHING", "INVISIBILITY", "BLINDNESS", "NIGHT_VISION", "HUNGER", "WEAKNESS",
    "POISON", "WITHER", "HEALTH_BOOST", "ABSORPTION", "SATURATION", "GLOWING",
    "LEVITATION", "LUCK", "UNLUCK", "SLOW_FALLING", "CONDUIT_POWER", "DOLPHINS_GRACE",
    "BAD_OMEN", "HERO_OF_THE_VILLAGE", "DARKNESS", "TRIAL_OMEN", "RAID_OMEN",
    "WIND_CHARGED", "WEAVING", "OOZING", "INFESTED"
  ];
  // 2026-07-28: 候補は Registry.EFFECT の現行キー(TF側 PotionEffectTypes.resolve が第一に引く名前)
  // に揃える。旧 pre-1.20.5 名(JUMP / FAST_DIGGING)は resolve のエイリアスで今も動くので、
  // 既存 yml にそれらが書かれていれば labeledIdSelect が現在値として先頭に足す(下の LABELS に
  // レガシー名も残してあるので日本語で出る)。候補側に重複して並べる必要は無い。

  const BREW_BASES = ["AWKWARD", "MUNDANE", "THICK", "WATER", "NIGHT_VISION", "INVISIBILITY",
    "LEAPING", "FIRE_RESISTANCE", "SWIFTNESS", "SLOWNESS", "WATER_BREATHING", "HEALING",
    "HARMING", "POISON", "REGENERATION", "STRENGTH", "WEAKNESS", "LUCK", "TURTLE_MASTER",
    "SLOW_FALLING"];

  // 2026-07-28: 醸造ギミックの「ベース」「効果」が生ID(AWKWARD / SPEED)のプルダウンだったため、
  // 日本語名 + ID併記の絞り込みセレクトへ差し替える。保存値は英字IDのまま(表示だけ日本語化)。
  const POTION_TYPE_LABELS = {
    SPEED: "移動速度上昇", SLOWNESS: "移動速度低下", HASTE: "採掘速度上昇", FAST_DIGGING: "採掘速度上昇(旧名)",
    MINING_FATIGUE: "採掘速度低下", STRENGTH: "攻撃力上昇", INSTANT_HEALTH: "即時回復",
    INSTANT_DAMAGE: "即時ダメージ", JUMP_BOOST: "跳躍力上昇", JUMP: "跳躍力上昇(旧名)",
    NAUSEA: "吐き気", REGENERATION: "再生能力",
    RESISTANCE: "耐性", FIRE_RESISTANCE: "火炎耐性", WATER_BREATHING: "水中呼吸", INVISIBILITY: "透明化",
    BLINDNESS: "盲目", NIGHT_VISION: "暗視", HUNGER: "空腹", WEAKNESS: "弱化", POISON: "毒",
    WITHER: "ウィザー", HEALTH_BOOST: "体力増強", ABSORPTION: "衝撃吸収", SATURATION: "満腹度回復",
    GLOWING: "発光", LEVITATION: "浮遊", LUCK: "幸運", UNLUCK: "不運", SLOW_FALLING: "落下速度低下",
    CONDUIT_POWER: "コンジットパワー", DOLPHINS_GRACE: "イルカの好意", BAD_OMEN: "不吉な予感",
    HERO_OF_THE_VILLAGE: "村の英雄", DARKNESS: "暗闇", TRIAL_OMEN: "不吉な試練",
    RAID_OMEN: "襲撃の予感", WIND_CHARGED: "ウィンドチャージ", WEAVING: "細工", OOZING: "滲出",
    INFESTED: "蟲の巣",
    // pre-1.20.5 のレガシー名。TF側 PotionEffectTypes.resolve が今もエイリアス解決するため、
    // 既存 yml に残っていることがある。候補には出さないが、現在値として表示されたときに
    // 生IDのまま見えないようラベルだけ用意しておく。
    SLOW: "移動速度低下(旧名)", SLOW_DIGGING: "採掘速度低下(旧名)", CONFUSION: "吐き気(旧名)",
    DAMAGE_RESISTANCE: "耐性(旧名)", INCREASE_DAMAGE: "攻撃力上昇(旧名)",
    HEAL: "即時回復(旧名)", HARM: "即時ダメージ(旧名)"
  };
  // ベース(PotionType)の日本語辞書は labels.js が唯一の持ち主。スキルEXPの「醸造結果EXP表」も
  // 同じ語彙を引くので、ここでは参照するだけにする(labels.js は先に読み込まれる)。
  const BREW_BASE_LABELS = (window.LABELS && window.LABELS.POTION_TYPE_LABELS_JA) || {};

  /** 生IDの配列を「日本語名 (ID)」の絞り込みセレクトにする共通ヘルパー。 */
  function labeledIdSelect(value, ids, labelMap, onChange, placeholder) {
    const cur = value == null ? "" : String(value);
    const list = ids.slice();
    if (cur && !list.includes(cur)) list.unshift(cur);
    const seen = new Set();
    const options = [];
    for (const id of list) {
      if (seen.has(id)) continue;
      seen.add(id);
      options.push({ value: id, primary: labelMap[id] || id, secondary: id, title: id });
    }
    return window.listSelect({
      value: cur,
      options,
      placeholder: placeholder || "選択…",
      onChange
    });
  }

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
    // 経験値瓶格納 (xp-bottle-store-unlock): 2026-08-15 に stats/fishing-gimmick.yml から移設。
    // 既定値は Java 側 CraftingFeaturesConfig の DEFAULT_XP_BOTTLE_STORE_AMOUNT/DEFAULT_XP_BOTTLE_RETURN_RATE
    // と一致させること(ここがずれると「editor で開いて保存しただけ」で挙動が変わる)。
    ensureObj(working, "xp-bottle-store", { "store-amount": 100, "return-rate": 1.0 });
    ensureObj(working, "gated-catalog-recipes", {});
    ensureObj(working, "coating", {});
    ensureObj(working, "wood-repair", {});
    ensureObj(working, "disassembly", {});
    // 2026-08-08新設: scrap-conversion(スクラップ変換)。未設定なら空マップ(=機能オフ)のまま温存する。
    ensureObj(working, "scrap-conversion", {});
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
    ensureObj(working, "thread-slots", { "max-by-category": defaultThreadSlotCaps() });
    ensureObj(working["thread-slots"], "max-by-category", defaultThreadSlotCaps());
    // D7 (2026-07-31): レシピ本へのTF/Arsレシピ開示。既定値は Java 側
    // CraftingFeaturesConfig#loadRecipeBook と厳密に一致させること(ここがずれると
    // 「editor で開いて保存しただけ」で挙動が変わる)。
    ensureObj(working, "recipe-book", { "reveal-plugin-recipes": true, "hide-locked-recipes": true });
    for (const key of ["reveal-plugin-recipes", "hide-locked-recipes"]) {
      if (typeof working["recipe-book"][key] !== "boolean") working["recipe-book"][key] = true;
    }
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
        ["コーティング", "coating", "素材ごとのダメージとスタック上限。"],
        ["レシピ本", "recipe-book", "TF/Arsレシピをレシピ本に載せるか。"]
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
        [h("span", { class: "entry-key-label", text: "他タブにある項目" })],
        [formHint(
          "木材修繕は「伐採ギミック」、解体は「鍛冶ギミック」、ポーション統合と醸造解放は「醸造ギミック」、"
          + "オーバーエンチャは「エンチャントギミック」タブにあります。"
          + "保存先ファイル(progression/crafting-features.yml)は共通のため、この画面から保存しても他タブの内容は保持されます。"
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
              // 0 は「枠が減る」ではなく「機構が丸ごと消える」ので、消える内容を出して確認を取る。
              if (!v && typeof window.confirm === "function"
                  && !window.confirm(threadCapDisableWarning(cat.label))) {
                renderBody();
                return;
              }
              capsMap[cat.key] = v ? Math.max(1, Number(capsMap[cat.key]) || cat.recommended || 1) : 0;
              renderBody();
            }),
            h("span", { text: enabled ? "対象" : "対象外 (機構ごと無効)" })
          ])
        ]));
        cardEl.appendChild(h("div", { class: "cf-cat-blurb", text: cat.blurb }));
        if (enabled) {
          const val = Math.max(0, Math.floor(Number(capsMap[cat.key]) || 0));
          const row = h("div", { class: "cf-cat-controls" });
          const slider = h("input", {
            type: "range", min: "1", max: "12", step: "1", value: String(val), class: "cf-range"
          });
          // 「対象」のあいだは 1 未満へ落とせない。0 にする唯一の道は上のトグル(警告付き)。
          const num = window.numberInput(val, (v) => {
            if (v == null || v === "") return;
            capsMap[cat.key] = Math.max(1, Math.floor(v));
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
          cardEl.appendChild(h("div", { class: "cf-cat-off-note",
            text: "上限 0 = スレッド機構が丸ごと無効。lore の枠表示も装着 GUI も装着済みスレッドの効果も出ません"
              + "（枠を減らしたいだけなら「対象」に戻して 1 以上にしてください。TF の出荷 yml は 0 を禁止しています）。" }));
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
          formHint("解放: weapon-coating-unlock。スタック増は stat coating_charges_bonus（skilltreeの buffs:）。オフハンドに素材、メインに武器で右クリック。"),
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
        }, { int: true }), "実効上限 = min(基本最大スタック, この値) + coating_charges_bonus"));
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
          // 2026-08-14 追加: 自動消費のクールタイム(秒)。空欄はキーごと削除して ArsPaper の既定値10秒に
          // 委ねる(0を書き込むと「CT無し」という別の意味になるので、空欄を0へ丸めてはいけない)。
          field("自動消費のCT(秒・全体既定)", window.numberInput(sourceAutoConsume["cooldown-seconds"], (v) => {
            if (v == null || v === "") delete sourceAutoConsume["cooldown-seconds"];
            else sourceAutoConsume["cooldown-seconds"] = Math.max(0, Math.trunc(v));
          }, { int: true }),
            "下の一覧で「CT(秒)」を空欄にしたアイテムに使われる既定値。"
            + "変換が成立してから次に変換できるまでの秒数で、0でCT無し。この欄が空欄ならさらに既定の10秒。"
            + "CTはアイテムごとに独立して進むので、片方がCT中でも別アイテムは使えます。"),
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
        case "recipe-book": renderRecipeBook(); break;
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

    // レシピ本へのTF/Arsレシピ開示 (recipe-book)。D7 (2026-07-31)。
    // Bukkit.addRecipe は「登録」だけで「発見(discover)」はしないため、TF/Ars のレシピは
    // レシピ本に一切出ていなかった。ログイン時に discoverRecipes で開示する。
    function renderRecipeBook() {
      const rb = working["recipe-book"];
      const box = h("div", { class: "cf-mat-list" });
      const revealCard = h("div", { class: "cf-mat-card" });
      revealCard.appendChild(field("TF/Arsレシピをレシピ本に載せる",
        window.checkboxInput(rb["reveal-plugin-recipes"] !== false, (v) => {
          rb["reveal-plugin-recipes"] = v === true;
          renderBody();
        }),
        "ログイン時と /trinityforge reload 時に、trinityforge: と arspaper: のレシピをレシピ本へ開示します。"));
      box.appendChild(revealCard);
      const hideCard = h("div", { class: "cf-mat-card" });
      hideCard.appendChild(field("未解放レシピは隠す",
        window.checkboxInput(rb["hide-locked-recipes"] !== false, (v) => {
          rb["hide-locked-recipes"] = v === true;
        }),
        "gated-catalog-recipes でスキルツリー解放待ちになっているレシピを、解放前はレシピ本から隠します。"
        + " OFF にすると未解放レシピも一覧に出ます(クラフトは従来どおり弾かれます)。"));
      box.appendChild(hideCard);
      bodyEl.appendChild(card(
        [h("span", { class: "entry-key-label", text: "レシピ本 (recipe-book)" })],
        [
          formHint("レシピ本(バニラのレシピブック)にTF/ArsPaperのレシピを載せるかどうかの設定です。"
            + "バニラは非表示の進捗(minecraft:recipes/...)でレシピを解禁しているため、プラグインが登録した"
            + "レシピは明示的に開示しないと永久にレシピ本へ出ません。"),
          formHint("⚠️ レシピ本は「索引」として使ってください。custom:<ID> 素材のレシピはレシピ本のクリック配置で"
            + "素のバニラ素材が入るため、そのままではクラフトできません(TF側の素材チェックで弾かれます)。"
            + "実際に作るときはTFのレシピGUIを使ってください。"),
          formHint("反映はプレイヤーの再ログイン または /trinityforge reload。"
            + "※ OFF に戻しても、既に開示済みのレシピはプレイヤーデータに残るため自動では消えません。"),
          box
        ]
      ));
    }

    // バニラレシピキーの製法サフィックス。キーは「成果物 + 製法」で作られているので、
    // 剥がして成果物名を日本語で出し、製法は括弧で添える。
    const RECIPE_METHOD_SUFFIXES = [
      ["_smithing", "鍛冶台"],
      ["_from_smelting", "かまど"],
      ["_from_blasting", "溶鉱炉"],
      ["_from_smoking", "燻製器"],
      ["_from_campfire_cooking", "焚き火"],
      ["_stonecutting", "石切台"]
    ];

    /** バニラ/データパックのレシピキーの表示名。分からなければキーをそのまま返す。 */
    function vanillaRecipeKeyLabel(key) {
      const raw = String(key || "").trim();
      if (!raw) return "";
      const labelOf = (materialLike) => (window.LABELS && typeof window.LABELS.materialLabel === "function"
        ? window.LABELS.materialLabel(String(materialLike).toUpperCase()) : "");
      const id = raw.includes(":") ? raw.slice(raw.indexOf(":") + 1) : raw;
      const direct = labelOf(id);
      if (direct && direct.toUpperCase() !== id.toUpperCase()) return direct;
      for (const [suffix, method] of RECIPE_METHOD_SUFFIXES) {
        if (!id.endsWith(suffix)) continue;
        const base = id.slice(0, -suffix.length);
        const ja = labelOf(base);
        if (ja && ja.toUpperCase() !== base.toUpperCase()) return `${ja} (${method})`;
      }
      return raw;
    }

    // バニラ/データパックレシピの無効化リスト (removed-vanilla-recipes)。
    // キーは基本「成果物のアイテムID」(例: minecraft:iron_sword)。TF反映は再起動 or /trinityforge reload。
    function renderVanillaRemove() {
      const list = working["removed-vanilla-recipes"];
      // 補完: Material名の小文字がほぼそのままバニラレシピキーになる。
      // customID/material と同じサジェスト形式(listSelect)。データパックの任意キーは自由入力(allowCustom)。
      // 2026-07-28: 候補が minecraft:iron_sword の生キーだけで何のレシピか分からなかったため、
      // 主表示を日本語のアイテム名にしてキーは副表示へ回す(絞り込みは日本語/キーどちらでも効く)。
      const recipeKeyOptions = Array.isArray(window.MATERIALS)
        ? window.MATERIALS.map((m) => {
          const v = "minecraft:" + String(m).toLowerCase();
          const ja = window.LABELS && typeof window.LABELS.materialLabel === "function"
            ? window.LABELS.materialLabel(m) : "";
          return { value: v, primary: ja || v, secondary: v };
        })
        : [];
      const box = h("div", { class: "cf-mat-list" });
      if (!list.length) box.appendChild(emptyHint("削除対象がありません。「+ 追加」でバニラレシピのキーを登録します。"));
      list.forEach((value, i) => {
        const rowCard = h("div", { class: "cf-mat-card" });
        // 候補(=Material そのままのキー)に無い現在値は、そのままだと生キー表示になる。
        // バニラは <アイテム>_smithing / <アイテム>_from_blasting のように「成果物 + 製法」の
        // キーが多いので、製法サフィックスを剥がして成果物名で引き直したうえで先頭に足す。
        const cur = String(value || "").trim();
        const rowOptions = cur && !recipeKeyOptions.some((o) => o.value === cur)
          ? [{ value: cur, primary: vanillaRecipeKeyLabel(cur), secondary: cur, title: cur }].concat(recipeKeyOptions)
          : recipeKeyOptions;
        const input = window.listSelect({
          value: value || "",
          options: rowOptions,
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
        ? window.MATERIALS.map((m) => {
          const v = String(m);
          const ja = window.LABELS && typeof window.LABELS.materialLabel === "function"
            ? window.LABELS.materialLabel(v) : "";
          return { value: v, primary: ja || v, secondary: v };
        })
        : [];
      const box = h("div", { class: "cf-mat-list" });
      if (!items.length) box.appendChild(emptyHint("削除対象がありません。「+ 追加」でアイテムを登録します。"));
      // Material:enchant_id (例 ANY:MENDING / ENCHANTED_BOOK:mending) は候補に無いので
      // そのままだと生ID表示になる。両側をそれぞれ日本語に直して先頭に足す。
      const removedItemLabel = (raw) => {
        const s = String(raw || "").trim();
        if (!s) return "";
        const matLabel = (m) => {
          const key = String(m).toUpperCase();
          if (key === "ANY") return "全アイテム";
          const ja = window.LABELS && typeof window.LABELS.materialLabel === "function"
            ? window.LABELS.materialLabel(key) : "";
          return ja && ja.toUpperCase() !== key ? ja : key;
        };
        if (!s.includes(":")) return matLabel(s);
        const mat = s.slice(0, s.indexOf(":"));
        const ench = s.slice(s.indexOf(":") + 1);
        const enchJa = window.LABELS && typeof window.LABELS.enchantLabel === "function"
          ? window.LABELS.enchantLabel(ench) : "";
        return `${matLabel(mat)}:${enchJa || ench}`;
      };
      items.forEach((value, i) => {
        const rowCard = h("div", { class: "cf-mat-card" });
        const cur = String(value || "").trim();
        const rowOptions = cur && !itemMaterialOptions.some((o) => o.value === cur)
          ? [{ value: cur, primary: removedItemLabel(cur), secondary: cur, title: cur }].concat(itemMaterialOptions)
          : itemMaterialOptions;
        const input = window.listSelect({
          value: value || "",
          options: rowOptions,
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
    // 2026-07-27 タスク2: このセクションはクラス無しの div にカードを複数直接追加していたため
    // gap(余白)を作る CSS が一つも当たらずカード同士が密着していた。既存の card-list-body
    // (CSS側は .card-list と同じ間隔で統合済み)を使う。
    const root = h("div", { class: "card-list-body" });
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

  // 解体ルール1件のエディタ (2026-07-27 個別指定/重み抽選/レシピなしアイテム対応)。
  // 素材数の決め方は input(レシピから数える) と base-amount(固定数) の二択で、
  // base-amount 側はクラフトレシピを持たないアイテム(釣りのゴミ等)を解体対象にするための唯一の手段。
  // 返却先は output(1種類) と outputs(重み抽選・1件だけ当たる) の二択で、Java側の
  // CraftingFeaturesConfig.parseDisassemblyRule と同じ語彙をそのまま編集する。
  function disassemblyRuleEditor(rule, onRemove) {
    const box = h("div", { class: "cf-mat-card" });
    function render() {
      box.innerHTML = "";
      const fixedAmount = rule["base-amount"] != null;
      const weighted = Array.isArray(rule.outputs);

      const head = h("div", { class: "cf-mat-card-head" }, [
        h("span", { class: "entry-key-label", text: "返却ルール" }),
        h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: onRemove })
      ]);
      box.appendChild(head);

      box.appendChild(field("素材数の決め方", window.listSelect({
        value: fixedAmount ? "fixed" : "recipe",
        options: [
          { value: "recipe", primary: "レシピから数える", secondary: "input" },
          { value: "fixed", primary: "固定数を指定", secondary: "base-amount" }
        ],
        onChange: (v) => {
          if (v === "fixed") {
            delete rule.input;
            rule["base-amount"] = 1;
          } else {
            delete rule["base-amount"];
            rule.input = rule.input || "IRON_INGOT";
          }
          render();
        }
      }), "「レシピから数える」= 対象アイテムのレシピを引いて素材の使用個数を数える / 「固定数を指定」= レシピを引かず固定数を使う（★クラフトレシピの無いアイテム＝釣りのゴミ等はこちら）"));

      if (fixedAmount) {
        box.appendChild(field("基準素材数 (base-amount)", window.numberInput(rule["base-amount"], (v) => {
          if (v == null) return;
          rule["base-amount"] = Math.max(0, v);
        }), "レシピの代わりに使う素材数。釣りのゴミなら 1 程度。"));
      } else {
        box.appendChild(field("クラフト素材 (input)", window.materialInput(rule.input || "", "material-list", (v) => {
          rule.input = (v || "").trim();
        }, { allowCustom: true }), "対象アイテムのレシピ内でこの素材が何個使われているかを数える。"));
      }

      box.appendChild(field("返却先の決め方", window.listSelect({
        value: weighted ? "weighted" : "single",
        options: [
          { value: "single", primary: "1種類だけ返す", secondary: "output" },
          { value: "weighted", primary: "重み抽選(ランダム)", secondary: "outputs" }
        ],
        onChange: (v) => {
          if (v === "weighted") {
            const single = (rule.output || "").trim();
            delete rule.output;
            rule.outputs = [{ item: single || "IRON_INGOT", weight: 1 }];
          } else {
            const first = Array.isArray(rule.outputs) && rule.outputs.length ? rule.outputs[0] : null;
            delete rule.outputs;
            rule.output = (first && first.item) || "IRON_INGOT";
          }
          render();
        }
      }), "「1種類だけ返す」= 常に同じアイテムが返る / 「重み抽選」= 候補から重み抽選で★1件だけ★当たる"));

      if (weighted) {
        const rows = h("div", { class: "stat-rows" });
        const total = rule.outputs.reduce((sum, o) => sum + (Number(o && o.weight) > 0 ? Number(o.weight) : 0), 0);
        rule.outputs.forEach((out, i) => {
          const row = h("div", { class: "stat-row" });
          row.appendChild(h("span", { class: "range-label", text: "返却先" }));
          row.appendChild(window.materialInput(out.item || "", "material-list", (v) => {
            out.item = (v || "").trim();
          }, { allowCustom: true }));
          row.appendChild(h("span", { class: "range-label", text: "重み" }));
          row.appendChild(window.numberInput(out.weight == null ? 1 : out.weight, (v) => {
            if (v == null) return;
            out.weight = Math.max(0, v);
            render();
          }));
          const w = Number(out.weight) > 0 ? Number(out.weight) : 0;
          row.appendChild(h("span", {
            class: "range-label",
            text: total > 0 ? `${Math.round((w / total) * 1000) / 10}%` : "—"
          }));
          row.appendChild(h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => { rule.outputs.splice(i, 1); render(); }
          }));
          rows.appendChild(row);
        });
        rows.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 候補",
          onclick: () => { rule.outputs.push({ item: "IRON_INGOT", weight: 1 }); render(); }
        }));
        box.appendChild(h("div", { class: "form-field" }, [
          h("span", { class: "form-label", text: "返却先の候補 (outputs) — 重み抽選" }),
          rows,
          formHint("重みは合計に対する相対値。右の%は現在の当選確率。重み 0 以下の候補は抽選から外れる。")
        ]));
      } else {
        box.appendChild(field("返却先 (output)", window.materialInput(rule.output || "", "material-list", (v) => {
          rule.output = (v || "").trim();
        }, { allowCustom: true })));
      }

      box.appendChild(field("返却倍率 (multiplier)", window.numberInput(rule.multiplier == null ? 1 : rule.multiplier, (v) => {
        if (v != null) rule.multiplier = Math.max(0, v);
      })));
    }
    render();
    return box;
  }

  // 解体 (disassembly)。返却%と対象シリーズ↔返却ルールを編集する。鍛冶ギミックタブが呼ぶ。
  window.buildCraftingFeaturesDisassemblySection = function buildDisassemblySection(dis) {
    // 2026-07-27 タスク2: 同上(card-list-body で余白を確保する)。
    const root = h("div", { class: "card-list-body" });
    // 対象シリーズカードの開閉状態。render() で DOM を作り直すため外に持たないと
    // 1項目触るたびに全部畳まれてしまう。
    const openDisassemblyItems = new Set();
    function render() {
      root.innerHTML = "";
      const items = ensureObj(dis, "items", {});
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "返却の仕組み" })],
        [
          formHint("解放したプレイヤーが置いた金床を対象アイテムの上に落とすと、下にある複数アイテムを同時に解体します。返却数 = floor(floor(素材数 × 戻り総% / 100) × 返却倍率)。戻り総%は下の tier 表の完全一致（Lv3 なら 60。無いレベルは 解体Lv × percent-per-level）。対象・素材が未設定、または返却数が0の場合は消費されません。素材数は作業台の形のマス（キーの種類数ではない）とネザライト強化だけを数えます。防具装飾の鍛冶と、同じ素材の別アイテム（カタログの革チェスト等）は含みません。クラフトレシピを持たないアイテムは base-amount を使ってください。"),
          field("レベルあたり返却%", window.numberInput(dis["percent-per-level"], (v) => {
            if (v == null) return;
            dis["percent-per-level"] = Math.max(0, Math.floor(v));
          }, { int: true }), "例: Lv1 × 25% → 鉄チェスト(8) なら floor(8×0.25)=2 個")
        ]
      ));

      // 2026-07-28(数値のギミックyml集約): tiers はレベルの完全一致でのみ引く(digging/smithingと違い
      // 「以下で最大」フォールバックはしない — tier キー = 解体レベルそのもの。未設定ならグローバル
      // 既定値(上のレベルあたり返却%)×レベルの線形式がそのまま使われる。
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "レベル別 戻り総% (任意)" })],
        [
          formHint("解体レベルの完全一致でのみ使われる(以下最大へのフォールバックなし)。未設定のレベルは"
            + "「レベルあたり返却% × レベル」の線形計算がそのまま使われる。"),
          typeof window.tierTableEditor === "function"
            ? window.tierTableEditor(dis, [{ key: "percent", label: "戻り総%", int: true }], {
                emptyTitle: "レベル別設定未使用(線形計算のみ)",
                emptyHint: "「+ tier追加」で特定レベルの戻り総%を個別に上書きできます。"
              })
            : h("div", { class: "empty-hint", text: "tier表エディタ(tf-lifestyle-forms.js)が読み込まれていません。" })
        ]
      ));

      const list = h("div", { class: "cf-mat-list" });
      const itemKeys = Object.keys(items);
      if (!itemKeys.length) list.appendChild(emptyHint("対象シリーズの返却設定がありません。"));
      for (const itemMat of itemKeys) {
        const rules = Array.isArray(items[itemMat]) ? items[itemMat] : (items[itemMat] = []);
        // 2026-07-29: 対象シリーズが 15 件以上あり、全部展開したまま縦に積まれていたため
        // 「どこに何があるか」が見えなかった。details で折りたためる (既定は閉じる)。
        // 見出しには対象 ID と返却ルール件数を出し、閉じたまま一覧として読めるようにする。
        const itemCard = h("details", { class: "cf-mat-card cf-mat-card-collapsible" });
        if (openDisassemblyItems.has(itemMat)) itemCard.setAttribute("open", "");
        itemCard.addEventListener("toggle", () => {
          if (itemCard.open) openDisassemblyItems.add(itemMat);
          else openDisassemblyItems.delete(itemMat);
        });
        // itemMat はワイルドカード(*)対応の対象IDパターン。ワイルドカード無し(単一Material)の
        // ときだけ表示名を解決する(ワイルドカードは複数Materialへ一致するため単一の名前へ潰せない)。
        // 注意: labels.js のフォールバック付きヘルパー関数(このファイルでは custom: を解けない
        // 実装として使用禁止 — select-japanese-labels-2026-07-29.test.js 参照)は呼ばず、
        // 同じ辞書(MATERIAL_LABELS)を直接引くだけに留める(custom: とは無関係な単純Materialの解決)。
        const itemMatLabel = (!/[*?]/.test(itemMat) && window.MATERIAL_LABELS)
          ? (window.MATERIAL_LABELS[itemMat] || itemMat)
          : itemMat;
        itemCard.appendChild(h("summary", { class: "cf-mat-card-head" }, [
          h("span", { class: "entry-key-label", text: itemMatLabel }),
          h("span", { class: "entry-summary", text: `返却ルール ${rules.length} 件` }),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: (e) => { e.preventDefault(); delete items[itemMat]; render(); }
          })
        ]));
        // 2026-07-27 タスク3: 長い説明付きの見出しが1階層とコンポーネントを消費していたため、
        // 短いラベル+「?」ツールチップ(既存の window.fieldLabelEl/helpIcon の仕組み)へ移し、
        // 見出し行と値行を1つの form-field に統合してネストを1段減らす。説明文の情報は
        // ツールチップへそのまま残す(消さない)。
        itemCard.appendChild(h("div", { class: "form-field" }, [
          window.fieldLabelEl("disassembly-target-id", {
            label: "対象 ID",
            desc: "末尾 * でシリーズ指定 (例: wooden_*)、* なしはアイテム個別指定 (例: ROTTEN_FLESH)。"
              + "ワイルドカードを取るパターン欄なのでアイテムセレクトではなく自由入力。",
            hideKey: true
          }),
          // textInput の第3引数は placeholder。以前は { allowCustom: false } を渡していて
          // placeholder="[object Object]" になっていた。
          // 2026-08-08: textInput(oninput=1文字ごと)だと、下の renameKey→render() で入力欄が
          // 作り直され【1文字打つたびにフォーカスが飛ぶ】。確定時だけ発火する方へ変更。
          window.textInputOnCommit(itemMat, (v) => {
            const next = (v || "").trim();
            if (!next || next === itemMat) return;
            if (Object.prototype.hasOwnProperty.call(items, next)) {
              alert("同じ対象 ID が既にあります");
              render();
              return;
            }
            renameKey(items, itemMat, next);
            // 開閉状態は ID をキーにしているのでリネームに追随させる。
            if (openDisassemblyItems.delete(itemMat)) openDisassemblyItems.add(next);
            render();
          }, "例: wooden_* / ROTTEN_FLESH")
        ]));
        const ingBox = h("div", { class: "stat-rows" });
        rules.forEach((rule, index) => {
          ingBox.appendChild(disassemblyRuleEditor(rule, () => { rules.splice(index, 1); render(); }));
        });
        ingBox.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 返却項目",
          onclick: () => {
            rules.push({ input: "IRON_INGOT", output: "IRON_INGOT", multiplier: 1 });
            render();
          }
        }));
        itemCard.appendChild(h("div", { class: "form-field" }, [
          window.fieldLabelEl("disassembly-return-rules", {
            label: "返却ルール",
            desc: "複数書ける。それぞれ独立に適用される。",
            hideKey: true
          }),
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
          // 追加直後は編集したいので開いた状態で描く。
          openDisassemblyItems.add(key);
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

  // スクラップ変換 (scrap-conversion)。2026-08-08 追加。「ただのスクラップ」を右クリック消費すると
  // 重み付き抽選で種別スクラップ1個へ変わるギミック(ScrapConversionListener、右クリック即消費/即抽選)。
  // データ形は disassembly の1件分の返却ルールと同じ(base-amount + outputs)なので、
  // 1件ぶんの編集UIは disassemblyRuleEditor をそのまま再利用する(重複実装しない)。
  // 鍛冶ギミックタブが disassembly の直後に呼ぶ想定 (tf-forms.js)。
  window.buildCraftingFeaturesScrapConversionSection = function buildScrapConversionSection(conv) {
    const root = h("div", { class: "card-list-body" });
    function render() {
      root.innerHTML = "";
      const list = h("div", { class: "cf-mat-list" });
      const keys = Object.keys(conv);
      if (!keys.length) list.appendChild(emptyHint("スクラップ変換の設定がありません。"));
      for (const sourceId of keys) {
        const rule = (conv[sourceId] && typeof conv[sourceId] === "object") ? conv[sourceId] : (conv[sourceId] = {});
        const itemCard = h("div", { class: "cf-mat-card" });
        itemCard.appendChild(h("div", { class: "form-field" }, [
          window.fieldLabelEl("scrap-conversion-source-id", {
            label: "変換元 ID",
            desc: "custom: の item id (末尾の custom: は付けない)。例: tf_scrap",
            hideKey: true
          }),
          window.textInputOnCommit(sourceId, (v) => {
            const next = (v || "").trim();
            if (!next || next === sourceId) return;
            if (Object.prototype.hasOwnProperty.call(conv, next)) {
              alert("同じ変換元 ID が既にあります");
              render();
              return;
            }
            renameKey(conv, sourceId, next);
            render();
          }, "例: tf_scrap"),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { delete conv[sourceId]; render(); }
          })
        ]));
        itemCard.appendChild(disassemblyRuleEditor(rule, () => { delete conv[sourceId]; render(); }));
        list.appendChild(itemCard);
      }
      list.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 変換元追加",
        onclick: () => {
          let number = 1;
          let key = "scrap_source_1";
          while (Object.prototype.hasOwnProperty.call(conv, key)) key = `scrap_source_${++number}`;
          conv[key] = { "base-amount": 4, outputs: [{ item: "IRON_INGOT", weight: 1 }] };
          render();
        }
      }));
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "変換元 ↔ 変換ルール" })],
        [
          formHint("右クリックで即時消費・即時抽選(クラフトレシピではない)。素材数の決め方は"
            + "「固定数を指定(base-amount)」を使うのが基本(スクラップ自体はレシピを持たないため)。"),
          list
        ]
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
    // 2026-07-27 タスク2: 同上(card-list-body で余白を確保する)。
    const root = h("div", { class: "card-list-body" });
    function render() {
      root.innerHTML = "";
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "醸造解放" })],
        [
          formHint("材料はバニラ Material 名、または custom:<カタログID>。該当材料の醸造は専用効果所持者のみです"
            + "（未解放プレイヤーは投入自体が弾かれ、燃料も入りません）。"),
          // D10 (2026-07-31): ここに書かれた (ベース, 材料) の組は起動時に Paper の PotionMix として
          // サーバへ登録される。登録しないと醸造タイマーがそもそも進まない(THICK/MUNDANE ベースは
          // バニラに対応する醸造が無いため、次tickで brewTime が 0 に戻される)。
          formHint("⚠️ バニラと同じ (ベース, 材料) の組を書くと、そのバニラ醸造レシピをサーバ全体で"
            + "上書き(=消滅)させてしまうため、その組は登録をスキップします(起動ログに警告が出ます)。"
            + "TF独自の醸造には custom:<カタログID> 材料、または THICK / MUNDANE ベースを使ってください。"
            + "なおレッドストーン/グロウストーン/発酵した蜘蛛の目は延長・強化・反転なので、"
            + "THICK / MUNDANE ベースなら衝突しません(火薬とドラゴンブレスはどのベースでも衝突します)。"),
          formHint("⚠️ 同じ (ベース, 材料) の組を2つのグループに書かないでください。先に一致した1件しか"
            + "成立しないため、もう一方のグループのポーションは永久に作れません(上位段を作るときは"
            + "ベースを変えてください。例: 下位段=THICK / 上位段=MUNDANE)。"),
          formHint("⚠️ 投入の判定は「操作したプレイヤー」、ホッパー経由の判定は「醸造台に記録された所有者」"
            + "です(所有者=その組み合わせを最初に解放済みの状態で組み立てたプレイヤー)。ホッパーは"
            + "所有者が未記録・オフライン・未解放のいずれでも弾かれ、材料が手前で詰まります"
            + "(自動化する場合は解放済みのプレイヤーが一度手で組み立て、オンラインである必要があります)。")
        ]
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
          row.appendChild(field("ベース", labeledIdSelect(pot.base || "AWKWARD", BREW_BASES, BREW_BASE_LABELS, (v) => {
            pot.base = v;
          }, "ベースポーションを選択…")));
          // タスク8 (2026-07-26): 材料IDが Material の生ID(例: NETHER_WART)のままで日本語表示が
          // 無かったため、日本語ヒントを追加した。保存値は pot.ingredient の生ID文字列のまま
          // 変えない(表示だけ日本語化。ロスレス性は維持)。
          // 2026-07-31: 自前実装が LABELS の素材ラベル関数 = `MATERIAL_LABELS[key] || key`
          // を直呼びしていたため `custom:witch_elixir` が生返しになり、セレクト本体は日本語なのに
          // 横のヒントだけIDという状態だった。`custom:` を CUSTOM_ITEM_LABELS で解く実装は
          // 共通ヘルパー util.js の materialHintEl に既にあるので、そちらへ寄せる。
          const ingredientHint = window.materialHintEl(pot.ingredient);
          row.appendChild(field("材料 (Material / custom:id)", h("span", { class: "input-with-hint" }, [
            window.materialInput(pot.ingredient || "", "material-list", (v) => {
              pot.ingredient = v;
              ingredientHint.update(v);
            }, { allowCustom: true }),
            ingredientHint
          ])));
          row.appendChild(field("効果", labeledIdSelect(pot.result.type || "SPEED", POTION_TYPES,
            POTION_TYPE_LABELS, (v) => { pot.result.type = v; }, "効果を選択…")));
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
    // 2026-07-27 タスク2: 同上(card-list-body で余白を確保する)。
    const root = h("div", { class: "card-list-body" });
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
          // 2026-07-28: 生ID(SHARPNESS)のプルダウンだったので日本語名+ID併記の絞り込みセレクトへ。
          const ids = enchList.slice();
          if (name && !ids.includes(name)) ids.unshift(name);
          row.appendChild(window.listSelect({
            value: name,
            options: (ids.length ? ids : [name || "SHARPNESS"]).map((id) => ({
              value: id,
              primary: (window.LABELS && typeof window.LABELS.enchantLabelWithFallback === "function"
                ? window.LABELS.enchantLabelWithFallback(id) : id),
              secondary: id
            })),
            placeholder: "エンチャントを選択…",
            onCommit: (v) => {
              const next = String(v || "").trim().toUpperCase();
              if (!next || next === name) return true;
              if (Object.prototype.hasOwnProperty.call(enchants, next)) {
                alert("同じエンチャントが既にあります");
                return false;
              }
              const cap = enchants[name];
              delete enchants[name];
              enchants[next] = cap;
              render();
              return true;
            }
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

  // エンチャント運 (enchant-luck.yml)。エンチャントギミックタブが呼ぶ。
  // 保存先が crafting-features.yml とは別ファイルのため working は
  // 呼び出し元(buildEnchantGimmickForm)が用意したコンパニオンデータをそのまま渡す。
  window.buildCraftingFeaturesEnchantLuckSection = function buildEnchantLuckSection(el) {
    if (el["level-boost-chance-per-luck"] == null) el["level-boost-chance-per-luck"] = 0.01;
    if (el["level-boost-max-steps"] == null) el["level-boost-max-steps"] = 2;
    if (el["overenchant-bonus-chance-per-luck"] == null) el["overenchant-bonus-chance-per-luck"] = 0.02;
    if (el["extra-enchant-chance-per-luck"] == null) el["extra-enchant-chance-per-luck"] = 0.005;
    if (el["vanilla-parity-luck"] == null) el["vanilla-parity-luck"] = 10;
    if (el["level-nerf-chance-at-zero"] == null) el["level-nerf-chance-at-zero"] = 0.5;
    if (el["level-nerf-max-steps"] == null) el["level-nerf-max-steps"] = 2;
    const body = h("div", { class: "const-body" });
    body.appendChild(field("格上げ確率(/luck1.0)", window.numberInput(el["level-boost-chance-per-luck"], (v) => {
      if (v == null) return;
      el["level-boost-chance-per-luck"] = v;
    }), "適用確率 = min(1.0, この値 × enchant_luck)。バニラ上限まで。パリティ未満では使わない。"));
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
    body.appendChild(field("バニラ同等になる運 (vanilla-parity-luck)", window.numberInput(el["vanilla-parity-luck"], (v) => {
      if (v == null) return;
      el["vanilla-parity-luck"] = Math.max(0, v);
    }), "この値未満の運では格上げせず弱体化する。0 でナーフ無効(旧挙動)。"));
    body.appendChild(field("運0の弱体化確率", window.numberInput(el["level-nerf-chance-at-zero"], (v) => {
      if (v == null) return;
      el["level-nerf-chance-at-zero"] = v;
    }), "運0のときのレベル-1 試行確率。パリティ直前では 0 に近づく。"));
    body.appendChild(field("弱体化最大試行回数", window.numberInput(el["level-nerf-max-steps"], (v) => {
      if (v == null) return;
      el["level-nerf-max-steps"] = Math.max(0, Math.floor(v));
    }, { int: true }), "1回の抽選でレベルを-1できる最大回数。下限はレベル1。"));
    return card(
      [h("span", { class: "entry-key-label", text: "エンチャント運 (stat: enchant_luck)" })],
      [formHint("スキルツリー(enchanting.yml)のbuffsで蓄積するstatを、エンチャントテーブルの補正抽選へ反映する重み付け。保存先: stats/enchant-luck.yml。運がパリティ未満なら弱体化、以上なら格上げ。"), body]
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

  // 経験値瓶格納 (xp-bottle-store)。エンチャントギミックタブが呼ぶ。2026-08-15 に
  // stats/fishing-gimmick.yml から移設(釣りとは無関係な設定がそちらに置かれていたため)。
  // 保存先は working (crafting-features.yml) 自身のサブツリーなので、他のコンパニオン(enchant-luck等)と
  // 異なり getExtraSaves を経由しない(over-enchant / enchant-bookshelf-power と同じ扱い)。
  function buildXpBottleStoreSection(xp) {
    if (!xp || typeof xp !== "object") xp = {};
    const body = h("div", { class: "const-body" });
    body.appendChild(field("経験値瓶 格納量(グローバル既定値)", window.numberInput(xp["store-amount"], (v) => {
      if (v == null) return;
      xp["store-amount"] = Math.max(1, Math.floor(v));
    }, { int: true }), "下のtier表に該当tier行がある場合はそちらが優先され、この値は使われない。"));
    body.appendChild(field("還元率(0-1、グローバル既定値)", window.numberInput(xp["return-rate"], (v) => {
      if (v == null) return;
      xp["return-rate"] = v;
    }), "取り出し時に返る割合(0.0〜1.0)。下のtier表に該当tier行がある場合はそちらが優先される。"));
    return card(
      [h("span", { class: "entry-key-label", text: "経験値瓶格納 (xp-bottle-store)" })],
      [
        formHint("解放: xp-bottle-store-unlock(エンチャントツリー「EXPフリーザー」。2026-07-26 tier-expand: "
          + "SCALE化。tierはノードvalueから決まる)。"),
        body,
        h("div", { class: "sub-title", text: "tier別設定 (tiers) — 該当tier行があればグローバル既定値より優先される" }),
        typeof window.tierTableEditor === "function"
          ? window.tierTableEditor(xp, [
              { key: "store-amount", label: "格納量", int: true },
              { key: "return-rate", label: "還元率(0-1)" }
            ])
          : h("div", { class: "empty-hint", text: "tier表エディタ(tf-lifestyle-forms.js)が読み込まれていません。" })
      ]
    );
  }

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
    // 経験値瓶格納 (xp-bottle-store-unlock、2026-08-15 stats/fishing-gimmick.yml から移設): エンチャント
    // ツリー「EXPフリーザー」で解放される機能なので、このタブ(エンチャントギミック)が担当する。
    root.appendChild(buildXpBottleStoreSection(working["xp-bottle-store"]));
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
  window.buildBrewGimmickForm = function buildBrewGimmickForm(data) {
    const working = data && typeof data === "object" ? data : {};
    normalizeCraftingFeaturesWorking(working);

    const root = h("div", { class: "dedicated-form" });
    root.appendChild(formHint(
      "醸造(ブリューイングスタンド)まわりの専用効果チューニングです。"
      + "保存先は「その他ギミック」「エンチャントギミック」等と同じ progression/crafting-features.yml ですが、"
      + "このタブは potion-merge / brew-unlocks サブツリーのみを編集します(他のサブツリーは保存時もそのまま温存されます)。"
    ));
    root.appendChild(window.buildCraftingFeaturesPotionMergeSection(working["potion-merge"]));
    root.appendChild(window.buildCraftingFeaturesBrewSection(working["brew-unlocks"]));

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => []
    };
  };

  // ============================================================
  // progression/use-requirements.yml
  // ============================================================
  window.buildUseRequirementsForm = function buildUseRequirementsForm(data, opts) {
    const working = data && typeof data === "object" ? data : {};
    if (typeof working.enforce !== "boolean") working.enforce = !!working.enforce;
    // 2026-07-27: AFK(離席)判定(afk.yml)は独立タブを作らず、この画面内へコンパニオン表示する
    // (ユーザー指示)。保存先ファイルは use-requirements.yml とは別のため、鍛冶/伐採ギミックの
    // craftingFeaturesData コンパニオンと同じ形(未指定なら getExtraSaves は空配列)で扱う。
    const afkData = opts && opts.afkData && typeof opts.afkData === "object" ? opts.afkData : undefined;
    const hasAfk = afkData !== undefined;
    const afkSubform = hasAfk ? window.buildAfkSection(afkData) : null;

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

    if (afkSubform) {
      root.appendChild(afkSubform.element);
    }

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => hasAfk ? [{ id: "afk", data: afkSubform.getData() }] : []
    };
  };
})(typeof window !== "undefined" && typeof document !== "undefined");
