"use strict";

// 専用リッチフォーム (item-stats / catalog)。
// いずれも working オブジェクトを直接編集し、構造変更時のみ render() で全体を再描画する。
// scalar編集は working を直接書き換える (再描画不要)。getData() は working を返す。

(function () {
  const h = window.h;

  // catalog.yml のアイテムを「素材」タブ(materials.yml編集画面)にも一覧・編集できるようにする
  // 表示タブピン (2026-08-02)。実体は catalog.yml に残したまま(移動しない)なので、既存の
  // "material"(= materials.yml へ実データ移行するボタンの値、buildCatalogForm の
  // moveEntryToMaterials)とは**絶対に文字列を一致させない**こと。一致させると、鍵アイテムの
  // ような catalog 専用の実装(PDCタグ/レシピ)を持つ品が誤って materials.yml へ移行されてしまう。
  // split-views.js / lib 側から参照する唯一の正典としてここで定義し、他ファイルはこの値を
  // (直接 window 経由で、または同じ文字列を書き写して)使う。
  const MATERIAL_REF_TAB_ID = "material-ref";
  const MATERIAL_REF_TAB_LABEL = "素材(カタログ内)";
  window.CATALOG_MATERIAL_REF_TAB = [MATERIAL_REF_TAB_ID, MATERIAL_REF_TAB_LABEL];

  function statList() {
    // lore.yml 由来の STAT_LIST を優先しつつ、FALLBACK_STATS にしか無いキー(採集・マナ等の
    // 本体が読むが lore に載っていない場合があるステ)も候補へ確実に含める (和集合・重複排除)。
    // 旧単純守備力(flat-defense)は候補から除外（phys/magic-flat-defense へ分離済み）。
    const primary = (window.STAT_LIST && window.STAT_LIST.length) ? window.STAT_LIST : [];
    const fallback = window.FALLBACK_STATS || [];
    const hidden = new Set(window.HIDDEN_STATS || ["flat-defense"]);
    const seen = new Set();
    const out = [];
    for (const k of primary.concat(fallback)) {
      if (!k || hidden.has(k) || seen.has(k)) continue;
      seen.add(k);
      out.push(k);
    }
    return out.length ? out : fallback.filter((k) => !hidden.has(k));
  }

  /** 耐久値など ALWAYS_SHOWN を候補の末尾へ固定し、それ以外は lore のカテゴリ→order 順。 */
  function orderStatCandidates(keys) {
    const always = window.ALWAYS_SHOWN_STATS || ["durability"];
    const catRank = { attack: 0, defense: 1, support: 2, ars: 3, other: 4 };
    const bottom = [];
    const rest = [];
    for (const k of keys) {
      if (always.includes(k)) bottom.push(k);
      else rest.push(k);
    }
    const metaOf = (k) => {
      const m = (window.STAT_META && window.STAT_META[k]) || {};
      const cat = m.category
        || (typeof window.inferStatCategory === "function" ? window.inferStatCategory(k) : "other");
      return { order: Number(m.order != null ? m.order : 1000), category: cat };
    };
    rest.sort((a, b) => {
      const ma = metaOf(a);
      const mb = metaOf(b);
      const ra = catRank[ma.category] != null ? catRank[ma.category] : 9;
      const rb = catRank[mb.category] != null ? catRank[mb.category] : 9;
      if (ra !== rb) return ra - rb;
      if (ma.order !== mb.order) return ma.order - mb.order;
      return String(a).localeCompare(String(b));
    });
    return rest.concat(bottom);
  }

  // stat の表示フォーマット (lore.yml 由来。欠ければフォールバック辞書、無ければ FLAT)。
  function statFormat(key) {
    const f = (window.STAT_FORMATS && window.STAT_FORMATS[key])
      || (window.FALLBACK_STAT_FORMATS && window.FALLBACK_STAT_FORMATS[key]);
    return (f || "FLAT").toUpperCase();
  }
  function isPercentStat(key) { return statFormat(key) === "PERCENT"; }
  window.isPercentStat = isPercentStat;

  // Rate-style PERCENT keys that must be stored as fractions (0.75 = 75%). Whole numbers 2..100
  // (or -2..-100) are treated as percent-point typos from older saves / mis-edits.
  // crit-damage is excluded (values like 10 = +1000% on crit are intentional).
  const RATE_PERCENT_KEYS = new Set([
    "damage-modifier", "percent-bonus-damage", "crit-chance", "penetration", "bleed-chance",
    "dodge-chance", "phys-resistance", "magic-resistance", "damage-reduction"
    , "armor-strength"
    // armor-defense-rate はバニラ防具ポイント(INTEGER)。÷100しない。
    // 2026-07-28: 採集の率系2キー。Java 側 PercentStatNormalize.RATE_KEYS と対になる
    // (mining-fortune はそちらでも登録漏れしていて、15 が 1500% として効いていた)。
    // fishing-bonus は追加ドロップの期待個数(生値)なので対象外。
    , "mining-fortune", "fishing-luck"
  ]);
  function coerceRatePercent(key, value) {
    if (!RATE_PERCENT_KEYS.has(key) || value == null || !Number.isFinite(Number(value))) return value;
    const n = Number(value);
    const abs = Math.abs(n);
    if (abs > 1 && abs <= 100 && abs === Math.floor(abs)) return n / 100;
    return n;
  }
  function normalizeRatePercentsInEntry(entry) {
    if (!entry || typeof entry !== "object") return;
    for (const section of ["fixed", "per-quality"]) {
      const map = entry[section];
      if (!map || typeof map !== "object") continue;
      for (const [k, v] of Object.entries(map)) {
        if (RATE_PERCENT_KEYS.has(k)) map[k] = coerceRatePercent(k, v);
      }
    }
    const random = entry.random;
    if (random && typeof random === "object") {
      for (const [k, range] of Object.entries(random)) {
        if (!RATE_PERCENT_KEYS.has(k) || !range || typeof range !== "object") continue;
        if (range.min != null) range.min = coerceRatePercent(k, range.min);
        if (range.max != null) range.max = coerceRatePercent(k, range.max);
      }
    }
  }

  const roundTo = (v, digits) => { const m = Math.pow(10, digits); return Math.round(v * m) / m; };

  // item-stats の値入力コントロール。PERCENT ステは「割合(0.0〜1.0)で保存・% で表示/入力」する
  // (表示=値×100、保存=入力÷100、浮動小数ノイズを丸めで抑制)。FLAT/INTEGER 等はそのまま数値入力。
  // INTEGER ステ(durability, thread-slots, aoe-max-targets 等)は「小数点以下は切り捨ててintとして
  // 扱われる」旨のヒントを値入力の横に添える(本体側の floor 処理と実際の保存値の乖離を防ぐ)。
  // setter(finalValue) には保存する実値(PERCENTなら割合)を渡す。
  function statValueControl(key, value, setter, opts) {
    const options = opts || {};
    if (!isPercentStat(key)) {
      const isIntegerStat = statFormat(key) === "INTEGER";
      // fixed は整数ステを整数入力に固定するが、per-quality / random は小数入力を許可する。
      // 閾値方式: 累積値(固定 + 品質別×品質 + ロール)を切り捨てた整数が実効値になるため、
      // 品質別上昇値に例えば 0.5 を入れると「品質2ごとに +1」のような離散上昇が表現できる。
      const forceInt = isIntegerStat && !options.allowIntDecimal;
      const input = window.numberInput(value, (v) => setter(v == null ? 0 : v), forceInt ? { int: true } : undefined);
      if (!isIntegerStat) return input;
      const wrap = h("span", { class: "int-input" });
      wrap.appendChild(input);
      wrap.appendChild(h("span", {
        class: "int-suffix",
        text: options.allowIntDecimal ? "(整数/閾値)" : "(整数)",
        title: options.allowIntDecimal
          ? "整数ステ。小数で入力でき、累積(固定 + 品質別×品質 + ロール)を切り捨てた整数が実効値になります(閾値方式)。"
          : "このステータスは整数値です。小数点以下は切り捨てて扱われます。"
      }));
      return wrap;
    }
    const wrap = h("span", { class: "pct-input" });
    const shown = value == null ? 0 : roundTo(Number(value) * 100, 4);
    const input = window.numberInput(shown, (v) => {
      setter(v == null || v === "" ? 0 : roundTo(Number(v) / 100, 6));
    });
    wrap.appendChild(input);
    wrap.appendChild(h("span", { class: "pct-suffix", text: "%" }));
    return wrap;
  }
  // thread-sets フォーム等でも同じ %入力(割合保存) を再利用する。
  window.statValueControl = statValueControl;

  // 挿入順を保ったままマップのキーをリネームする。
  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }

  // item-stats のステータスキー選択。既知ステータス(lore由来の STAT_LIST + 辞書)を
  // 日本語ラベル付きプルダウンで選ぶ。ID直接入力の誤りを防ぐ。
  // item-stats は任意ステ対応のため「その他(自由入力)」で任意IDも入力できる。
  // 現在値がリストに無ければロスレス表示のため選択肢に補う。
  // onCommit(newKey) は受理時 true を返す想定。false/未受理なら選択を現在値へ戻す。
  function statSelect(stat, onCommit, filterFn) {
    const CUSTOM = "__custom__";
    const cur = stat == null ? "" : String(stat);
    const label = (window.LABELS && window.LABELS.statLabel) ? window.LABELS.statLabel : (k) => k;

    function buildOptions() {
      let keys = statList().slice();
      if (typeof filterFn === "function") keys = keys.filter(filterFn);
      if (cur && !keys.includes(cur)) keys.push(cur);
      keys = orderStatCandidates(keys);
      const opts = keys.map((k) => {
        // 表示名は Lore表示設定の name + 単位 で統一 (PERCENT は自動で %)。
        const ja = label(k);
        const unit = typeof window.defaultStatUnit === "function" ? window.defaultStatUnit(k) : (isPercentStat(k) ? "%" : "");
        return {
          value: k,
          primary: (ja || k) + (unit ? `（${unit}）` : ""),
          secondary: ja && ja !== k ? k : "",
          title: isPercentStat(k)
            ? `${k}（割合ステ: %入力。内部では0.0〜1.0で保存）`
            : k
        };
      });
      opts.push({ value: CUSTOM, primary: "その他(自由入力)…", secondary: "" });
      return opts;
    }

    return window.listSelect({
      value: cur,
      options: buildOptions,
      allowCustom: true,
      customValue: CUSTOM,
      customPlaceholder: "任意のステータスID",
      className: "stat-select",
      onCommit
    });
  }
  // lore フォームからも同じステ選択プルダウンを再利用する。
  window.statSelect = statSelect;

  // 値入力の直後に置く単位スロット。単位が無いステでも2文字分の幅を常に確保し、
  // 後続の乗算チェック等の開始位置を行間で揃える (PERCENT は %入力側で表示済みのため空)。
  // stat に null を渡すと空スロット (乗算行などの位置揃え用)。
  function statUnitSlot(stat) {
    const unit = (stat && !isPercentStat(stat) && typeof window.defaultStatUnit === "function")
      ? window.defaultStatUnit(stat) : "";
    return h("span", {
      class: "unit-suffix unit-slot",
      text: unit || "",
      title: unit ? "Lore表示設定の単位" : ""
    });
  }
  // skilltree / spellbooks / thread-sets 等のステ行でも同じ単位スロットを使う。
  window.statUnitSlot = statUnitSlot;

  function card(titleChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, titleChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }

  // lore(説明文/フレーバー文)行リストの共通レンダラ。mode("legacy"|"minimessage")で記法を切替え、
  // catalog(minimessage)や他の legacy 記法フォームから共有される。
  //   loreArray  : 直接ミューテートする文字列配列 (working 側の実体)。
  //   mode       : richTextInput へ渡す記法 ("legacy" | "minimessage")。
  //   onEdit()   : 1行のテキスト編集後に呼ぶ (プレビュー更新など、再描画不要な軽い副作用用)。
  //   onStructureChange() : 追加/削除/上下移動後に呼ぶ (呼び出し側のフォーム全体を再描画する想定)。
  function renderLoreRows(loreArray, mode, onEdit, onStructureChange) {
    const box = h("div", { class: "lore-rows" });
    loreArray.forEach((line, idx) => {
      const row = h("div", { class: "stat-row lore-row" });
      row.appendChild(window.richTextInput(line, mode, (v) => { loreArray[idx] = v; onEdit(); }));
      // 行の上下移動 (行配列の入替のみ、ロスレス維持)。
      row.appendChild(h("button", { class: "btn-small", type: "button", text: "↑", title: "上へ", onclick: () => { if (idx > 0) { const t = loreArray[idx - 1]; loreArray[idx - 1] = loreArray[idx]; loreArray[idx] = t; onStructureChange(); } } }));
      row.appendChild(h("button", { class: "btn-small", type: "button", text: "↓", title: "下へ", onclick: () => { if (idx < loreArray.length - 1) { const t = loreArray[idx + 1]; loreArray[idx + 1] = loreArray[idx]; loreArray[idx] = t; onStructureChange(); } } }));
      row.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { loreArray.splice(idx, 1); onStructureChange(); } }));
      box.appendChild(row);
    });
    box.appendChild(h("button", { class: "btn-small", type: "button", text: "+ 行追加", onclick: () => { loreArray.push(""); onStructureChange(); } }));
    return box;
  }
  // skilltree の説明文など、legacy/minimessage の複数行 lore 編集を他フォームからも共有できるよう公開する。
  window.renderLoreRows = renderLoreRows;

  // 項目が無いリスト向けの空状態ガイド (見出し + 次アクションの案内)。
  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }

  // key は FIELD_LABELS のキー。日本語ラベル + ツールチップ + 英字キー併記を表示する。
  // opts.required で必須マーカー、opts.labelText でラベル文字列を明示上書き(辞書に無いキー用)。
  function fieldRow(key, control, opts) {
    const options = opts || {};
    const labelEl = options.labelText
      ? h("span", { class: "form-label", text: options.labelText })
      : window.fieldLabelEl(key, { required: options.required });
    return h("div", { class: "form-field" }, [labelEl, control]);
  }

  // /api/skills が読めない場合のフォールバックID (server.js の FALLBACK_SKILLS と同一の15スキル)。
  const SKILL_FALLBACK_IDS = [
    "ALCHEMY", "ARCHERY", "ARS_MAGIC", "ARS_SMITHING", "DIGGING", "ENCHANTING",
    "FARMING", "FISHING", "HEAVY_ARMOR", "HEAVY_WEAPONS", "LIGHT_ARMOR",
    "LIGHT_WEAPONS", "MINING", "SMITHING", "WOODCUTTING"
  ];
  function skillIds() {
    return (window.SKILLS && window.SKILLS.length) ? window.SKILLS.map((s) => s.id) : SKILL_FALLBACK_IDS.slice();
  }

  // use-skill セレクト。既定では先頭に「(制限なし)」空選択 (空= setOrDelete でキー削除)。
  // opts.allowEmpty:false のときは空選択を出さない (craft-quality の category-skill 用。
  // カテゴリは必ずスキルを持つため「未設定=削除」を提示しない)。
  // 現在値がリストに無ければロスレス表示のため選択肢に補う。
  // progression/role-buffs.yml のキーと一致させること。ここがずれると
  // 「エディタで選べるのに実行時は一致しない」という無言の不一致になる。
  const USE_ROLE_IDS = [
    "swordfighter", "mage", "tank",
    "farmer", "fisher", "miner", "digger", "woodcutter"
  ];

  function roleSelect(value, onChange) {
    const cur = value == null ? "" : String(value);
    const label = window.LABELS ? window.LABELS.enumLabel : (g, v) => v;
    const ids = USE_ROLE_IDS.slice();
    if (cur && !ids.includes(cur)) ids.push(cur);
    const optsList = [{ value: "", primary: "(職業を問わない)", secondary: "" }];
    for (const id of ids) {
      const ja = label("use-role", id);
      optsList.push({
        value: id,
        primary: ja && ja !== id ? ja : id,
        secondary: ja && ja !== id ? id : "",
        title: id
      });
    }
    return window.listSelect({
      value: cur,
      options: optsList,
      placeholder: "(職業を問わない)",
      onChange
    });
  }

  function skillSelect(value, onChange, opts) {
    const allowEmpty = !opts || opts.allowEmpty !== false;
    const cur = value == null ? "" : String(value);
    const label = window.LABELS ? window.LABELS.enumLabel : (g, v) => v;
    const optsList = [];
    if (allowEmpty) optsList.push({ value: "", primary: "(制限なし)", secondary: "" });
    const ids = skillIds();
    if (cur && !ids.includes(cur)) ids.push(cur);
    for (const id of ids) {
      const ja = label("use-skill", id);
      optsList.push({
        value: id,
        primary: ja && ja !== id ? ja : id,
        secondary: ja && ja !== id ? id : "",
        title: id
      });
    }
    return window.listSelect({
      value: cur,
      options: optsList,
      placeholder: allowEmpty ? "(制限なし)" : "選択…",
      onChange
    });
  }
  // craft-quality (P4) の category-skill も同じスキルセレクトを再利用する。
  window.skillSelect = skillSelect;

  // ============================================================
  // item-stats.yml
  // ============================================================
  // カテゴリ(武器/防具/ツール/補助/触媒/魔導書/スレッド)のサブタブへ表示上のみ分割する。
  // `_editor.itemTabs` の明示ピンを優先し、無ければキー先頭 material から推論する
  // (item-stats.yml のスキーマ・保存構造は不変。ピンは `_editor` メタのみ)。
  const ITEM_STATS_CATEGORIES = [
    ["weapon", "武器"], ["armor", "防具"], ["tool", "ツール"], ["other", "補助"],
    ["catalyst", "触媒"], ["spellbook", "魔導書"], ["thread", "スレッド"]
  ];
  const LEGACY_FALLBACK_KEYS = new Set([
    "fixed", "per-quality", "random", "durability", "offhand-stats-apply",
    "advanced", "use-skill", "use-level-requirement", "use-role", "lore-default"
  ]);
  // 「特殊アイテム」画面(機能アイテムカテゴリ)へ集約済みの TF 特殊アイテム。唯一の正典は
  // functional-items.js の TF_SPECIAL_ITEM_IDS で、ここでは二重管理せず参照するだけにする。
  // ※ index.html の読み込み順は forms.js(64行) → functional-items.js(67行) なので、
  //   モジュール読み込み時点の const で受けると必ず空配列で固定される。呼び出し時に解決すること。
  function tfSpecialItemIdsForStats() {
    const core = window.FUNCTIONAL_ITEMS_CORE;
    return (core && core.TF_SPECIAL_ITEM_IDS) || [];
  }
  // カテゴリ(攻撃/守備/補助/Ars)のチェックに関わらず常に「+追加」候補へ出すステ。
  // 耐久力は装備種を問わず設定しうるためカテゴリ非依存にする(要件: 補助カテゴリから独立)。
  window.ALWAYS_SHOWN_STATS = window.ALWAYS_SHOWN_STATS || ["durability"];
  // タブごとの既定ON (攻撃/守備/補助/Ars)。武器→攻撃、防具→守備、ツール/補助→補助、Ars系→Ars。
  const ITEM_STATS_CAT_DEFAULTS = {
    weapon: { attack: true, defense: false, support: false, ars: false, other: false },
    armor: { attack: false, defense: true, support: false, ars: false, other: false },
    tool: { attack: false, defense: false, support: true, ars: false, other: true },
    other: { attack: false, defense: false, support: true, ars: false, other: true },
    catalyst: { attack: false, defense: false, support: false, ars: true, other: false },
    spellbook: { attack: false, defense: false, support: false, ars: true, other: false },
    thread: { attack: false, defense: false, support: true, ars: true, other: false }
  };

  // スレッド特殊効果 (暗視・飛行など)。threads.yml のタイプ id に対応する既定候補。
  // ここに載せてよいのは「装備しているだけで常時効果が乗る」種別だけ。
  // フォークの ThreadType が PotionEffectType を持つ種別 (= ArmorManaListener が毎tick 付け直す) と
  // flight (ポーションではないが常時効果) が該当する。
  // ステ加算だけの種別 (miner / spoils など) をここに足すと、選べるのに何も起きない候補が増える。
  // スレッドの特殊効果ラベル。
  // ⚠ ラベルの正は【実機の lore】= ArsPaper フォークの ThreadType#getEffectLore /
  //   ThreadConfig の switch(この2箇所が同じ文言を持つ)。labels.js の POTION_TYPE_LABELS_JA は
  //   醸造の PotionType 用の辞書で、スレッドの文言とは別系統かつ dolphins_grace /
  //   conduit_power / health_boost / hero_of_the_village を持たない。そちらへ揃えると
  //   「エディタでは A、実機では B」という食い違いになるので、必ず実機側に合わせること。
  const THREAD_SPECIAL_EFFECTS = [
    { id: "night_vision", label: "暗視" },
    { id: "fire_resistance", label: "火炎耐性" },
    { id: "flight", label: "飛行" },
    { id: "speed", label: "移動速度上昇" },
    { id: "jump_boost", label: "跳躍力上昇" },
    { id: "dolphins_grace", label: "イルカの好意" },
    { id: "conduit_power", label: "コンジットパワー" },
    { id: "hero_of_the_village", label: "村の英雄" },
    // health_boost は既存16種の頃から PotionEffectType.HEALTH_BOOST を持ち、常時付与セットにも
    // 入っているのに、このリストにだけ元から入っていなかった(= エディタで選べず、既存値も
    // 生IDのまま表示されていた)。2026-08-02 に補完。
    { id: "health_boost", label: "体力増強" },
    // 2026-08-02 スレッド16→40種で追加。新規24種のうち PotionEffectType を持つのはこの2つだけ
    // (他22種は thread-sets.yml のステ加算のみ)。
    // slow_falling は「落下耐性」ではない —— それはバニラ Feather Falling(落下ダメージ軽減)の
    // 訳語で、実機 lore の「落下速度低下」とは別の効果。
    { id: "slow_falling", label: "落下速度低下" },
    { id: "luck", label: "幸運" }
  ];
  const STAT_FILTER_GROUPS = [
    ["attack", "攻撃"], ["defense", "守備"], ["support", "補助"], ["ars", "Ars"], ["other", "その他"]
  ];

  // ステキー -> {攻撃|守備|補助|Ars} の簡易推論 (item-categories.yml のような設定は無いため、
  // キー名のキーワードから推論するヒューリスティック。lore.yml 由来の未知ステは既定「補助」)。
  // durability(耐久値)はいずれのキーワードにも一致しないため既定の「補助」に落ちる(意図通り)。
  // thread-slots(スレッド枠)は mana 系と同じ Ars グループへ強制分類するため "thread"/"slot" を追加。
  function inferStatCategory(stat) {
    const s = String(stat == null ? "" : stat).toLowerCase();
    // 2026-07 仕様変更: CT(item-cooldown)と効率強化増幅(tool-enchant-*)は「その他」へ。
    if (s.includes("item-cooldown") || s.startsWith("tool-enchant")) return "other";
    if (["mana", "spell", "cast", "arcane", "glyph", "sunrise", "moonfall", "thread", "slot"].some((k) => s.includes(k))) return "ars";
    if (["attack", "aoe", "crit", "penetration", "bleed", "bonus-damage", "damage-modifier", "fixed-damage"].some((k) => s.includes(k))) return "attack";
    if (["defense", "resistance", "armor", "max-health", "knockback", "dodge", "reduction"].some((k) => s.includes(k))) return "defense";
    return "support";
  }
  window.inferStatCategory = inferStatCategory;

  // 「+追加」候補の絞り込みに使うステのカテゴリ。lore.yml (STAT_META.category) を最優先し、
  // 無ければキー名ヒューリスティック (仕様: 追加候補の表示選択肢は lore設定のカテゴリに依存)。
  function statFilterCategory(stat) {
    const meta = window.STAT_META && window.STAT_META[stat];
    const cat = meta && meta.category;
    if (cat === "attack" || cat === "defense" || cat === "support" || cat === "ars" || cat === "other") {
      return cat;
    }
    return inferStatCategory(stat);
  }

  const ITEM_STATS_LOCKED_TABS = new Set(["catalyst", "spellbook", "thread"]);

  window.buildItemStatsForm = function buildItemStatsForm(data, opts) {
    const options = opts && typeof opts === "object" ? opts : {};
    let useSkillOptions = options.useSkillOptions || null; // null = unrestricted skillSelect
    let hideCategoryTabs = !!options.hubMode;
    const editorCategoryKey = options.editorCategoryKey || null;
    const useEditorMeta = !!editorCategoryKey && (!!options.hubMode || !!(data && data._editor));
    const catalogCandidates = Array.isArray(options.catalogCandidates) ? options.catalogCandidates : [];
    const working = data && typeof data === "object" ? data : {};
    if (!working.items || typeof working.items !== "object") working.items = {};
    // カタログ品は必ず対応するステータスタブに「値なしの枠」を持つ。
    // 空エントリは既存の fixed/per-quality/fallback 解決を一切変えないため、同期だけで
    // ゲームバランスを変えず、設定対象の取りこぼしを防げる。
    for (const candidate of catalogCandidates) {
      // 素材 (ArsPaper materials.yml 由来、tab: "material") はここでは枠を作らない。
      // 素材はアイテムステータスを持たず materials.yml 側の別画面で管理するため、
      // item-stats へ空エントリを生やすと「画面に出ないまま working.items だけ膨らむ
      // 幽霊エントリ」になる (ITEM_STATS_CATEGORIES に "material" タブが存在しない)。
      // 2026-08-02: "material-ref"(catalog.yml のアイテムを「素材」タブに"移動せず"表示する
      // ピン。CATALOG_MATERIAL_REF_TAB 参照)も同じ理由でスキップする。ITEM_STATS_CATEGORIES に
      // 対応タブが無いため、ここを漏らすと material-ref 品が item-stats.yml に
      // 「タブの無い幽霊エントリ」として量産される。
      if (candidate && (candidate.tab === "material" || candidate.tab === MATERIAL_REF_TAB_ID)) continue;
      // 2026-08-03: ステータスを持たない品(機能アイテム = ワンド/コンパス/台座/儀式の核/
      // 筆記台/ウェイストーン/ソースベリー、およびブロックであるソースジャー6種)も枠を作らない。
      // 印は catalog-candidates.js の EXTRA_SOURCES.statless → candidate.noItemStats。
      // tab で弾かないのは、これらの tab が "other"(補助) = サブウェポンの正当な置き場と
      // 同じ値だから — tab で切ると新生の光輪まで一緒に消える。
      // 候補リスト自体からは消さない: catalog.yml のレシピ素材セレクトが custom:<id> で
      // 参照するため、候補から消すと「ソースベリーが選べない」(2026-07-30 報告)に戻る。
      if (candidate && candidate.noItemStats) continue;
      // TF の特殊アイテム(skill_node_lock / skill_tree_reset)も枠を作らない。
      // 2026-07-27 ユーザー指示: この2件は「特殊アイテム」画面(機能アイテムカテゴリ)へ集約し、
      // アイテムステータス側には出さない。
      // 加えて実害がある — この2件は catalog.yml で custom-model-data を持たないため、
      // ステータスキーが素の Material(AMETHYST_SHARD / ECHO_SHARD)になる。そこへステを設定すると
      // 「バニラのアメジストの欠片/残響の欠片すべて」に効いてしまい、特殊アイテム1件を狙えない。
      if (candidate && tfSpecialItemIdsForStats().includes(candidate.id)) continue;
      const key = candidate && candidate.material
        ? (candidate.cmd == null ? candidate.material : `${candidate.material}#${candidate.cmd}`) : "";
      if (!key || Object.prototype.hasOwnProperty.call(working.items, key)) continue;
      // タブが決まらない候補を暗黙で「補助」へ落とすと、ユーザーには「なぜここにいるのか
      // 分からないエントリ」として補助タブに出続けてしまう(かつ素材のようにタブ自体が
      // 存在しない候補は画面から消す手段がない)。タブ未決定の候補は枠自体を作らずスキップする。
      if (!candidate || !candidate.tab) continue;
      working.items[key] = {};
      if (typeof window.setItemDisplayTab === "function") {
        window.setItemDisplayTab(working, key, candidate.tab);
      }
      // カタログ由来で自動生成した枠も必ずどこかのカテゴリへ入れる。
      // 入れないと「カタログに足したのにステータス画面では未設定タブにしか出ない」
      // (2026-08-01 報告「追加した素材がカテゴリ分けできていない」と同じ形)。
      if (useEditorMeta && typeof window.ensureItemEditorCategory === "function") {
        window.ensureItemEditorCategory(working, candidate.tab, key);
      }
    }
    for (const entry of Object.values(working.items)) normalizeRatePercentsInEntry(entry);
    if (working.fallback && typeof working.fallback === "object") {
      // 旧形式 (fallback.fixed 直下) + 新形式 (fallback.<cat>.fixed)
      if (working.fallback.fixed || working.fallback["per-quality"] || working.fallback.random) {
        normalizeRatePercentsInEntry(working.fallback);
      }
      for (const [cat, sec] of Object.entries(working.fallback)) {
        if (sec && typeof sec === "object" && !LEGACY_FALLBACK_KEYS.has(cat)) {
          normalizeRatePercentsInEntry(sec);
        }
      }
    }
    const root = h("div", { class: "dedicated-form" });

    function statsKeyFromCandidate(c) {
      if (!c || !c.material) return "";
      const cmd = c.cmd;
      if (cmd != null && cmd !== "") return `${c.material}#${cmd}`;
      return c.material;
    }
    function pickPreferredCatalogCandidate(matches) {
      if (!matches || !matches.length) return null;
      if (matches.length === 1) return matches[0];
      const nonCopper = matches.filter((c) => !String(c.id || "").startsWith("copper_"));
      return nonCopper.length ? nonCopper[0] : matches[0];
    }
    function candidatesForStatsKey(key) {
      const hashIdx = key.indexOf("#");
      const material = hashIdx >= 0 ? key.slice(0, hashIdx) : key;
      const cmd = hashIdx >= 0 ? key.slice(hashIdx + 1) : "";
      return catalogCandidates.filter((c) =>
        c && c.material === material && String(c.cmd == null ? "" : c.cmd) === String(cmd)
      );
    }
    const statsKeyToCandidate = {};
    const statsKeyAliases = {};
    for (const c of catalogCandidates) {
      const sk = statsKeyFromCandidate(c);
      if (!sk) continue;
      if (!statsKeyToCandidate[sk]) {
        statsKeyToCandidate[sk] = c;
        continue;
      }
      const existing = statsKeyToCandidate[sk];
      const preferred = pickPreferredCatalogCandidate([existing, c]);
      if (preferred !== existing) statsKeyToCandidate[sk] = preferred;
      if (!statsKeyAliases[sk]) statsKeyAliases[sk] = [existing.id];
      if (!statsKeyAliases[sk].includes(c.id)) statsKeyAliases[sk].push(c.id);
      if (!statsKeyAliases[sk].includes(existing.id)) statsKeyAliases[sk].unshift(existing.id);
    }
    function findCatalogForStatsKey(key) {
      if (statsKeyToCandidate[key]) return statsKeyToCandidate[key];
      return pickPreferredCatalogCandidate(candidatesForStatsKey(key));
    }
    function candidateTab(c) {
      if (c && c.tab) return c.tab;
      return typeof window.inferItemCategory === "function" ? window.inferItemCategory(c && c.material) : "other";
    }
    function tabLabelFor(catId, categories) {
      const found = categories.find(([id]) => id === catId);
      return found ? found[1] : catId;
    }
    function nestLabelForItem(itemId) {
      if (!useEditorMeta || typeof window.getItemEditorCategory !== "function") return "—";
      const catId = window.getItemEditorCategory(working, editorCategoryKey, itemId);
      if (!catId) return "—";
      const cats = typeof window.listEditorCategories === "function"
        ? window.listEditorCategories(working, editorCategoryKey) : [];
      const cat = cats.find((c) => c.id === catId);
      return cat ? (cat.label || cat.id) : "—";
    }
    function renderLockedTabLabel(tab) {
      const label = tabLabelFor(tab, ITEM_STATS_CATEGORIES);
      const picker = window.listSelect({
        value: tab,
        disabled: true,
        className: "editor-tab-select",
        options: ITEM_STATS_CATEGORIES.map(([id, lbl]) => ({
          value: id,
          primary: lbl,
          secondary: id
        }))
      });
      return h("span", { class: "editor-cat-field inline-field", title: `表示タブ: ${label}` }, [
        h("span", { class: "mini-label", text: "表示タブ" }),
        picker
      ]);
    }

    let activeCat = options.initialCategory || "weapon";
    // 折りたたみ状態(開いているキーの集合)。既定は全て折りたたみ (skilltreeと同じUX)。再描画をまたいで保持する。
    const expanded = new Set();
    // セクションごとの行表示順 (加算行と乗算行を同じステでグルーピングするための表示専用オーダー)。
    // 乗算モード切替でステが 加算マップ → multipliers へ移動しても行位置が最下部へ飛ばないようにする。
    const sectionRowOrders = {};
    // カテゴリ別チェックボックス状態 (タブごとに独立。初回訪問時のみ既定値で埋める)。
    // タブ上部 = デフォルト。各カードは上書き可能 (cardFilterOverrides に載ったキーのみ独立)。
    const statFilters = {};
    for (const [cat] of ITEM_STATS_CATEGORIES) statFilters[cat] = { ...ITEM_STATS_CAT_DEFAULTS[cat] };
    const cardStatFilters = {};
    const cardFilterOverrides = new Set();

    function activeStatFilter() { return statFilters[activeCat]; }
    function filterForCard(entryKey) {
      if (entryKey && cardFilterOverrides.has(entryKey) && cardStatFilters[entryKey]) {
        return cardStatFilters[entryKey];
      }
      return activeStatFilter();
    }
    function ensureCardFilter(entryKey) {
      if (!cardStatFilters[entryKey]) {
        cardStatFilters[entryKey] = { ...activeStatFilter() };
      }
      return cardStatFilters[entryKey];
    }
    function statAllowed(stat, entryKey) {
      // 耐久力(durability)はカテゴリ非依存で常に候補に出す (どのステカテゴリを選んでいても選択可)。
      if (window.ALWAYS_SHOWN_STATS.includes(stat)) return true;
      // 表示選択肢は lore設定のカテゴリ (STAT_META) に依存する。
      return !!filterForCard(entryKey)[statFilterCategory(stat)];
    }
    function keyMaterial(key) {
      const hashIdx = key.indexOf("#");
      return hashIdx >= 0 ? key.slice(0, hashIdx) : key;
    }
    function idsInCategory(cat) {
      return Object.keys(working.items).filter((k) => {
        const mat = keyMaterial(k);
        if (typeof window.getItemDisplayTab === "function") {
          return window.getItemDisplayTab(working, k, mat) === cat;
        }
        return window.inferItemCategory(mat) === cat;
      });
    }

    function filterAndSortIds(ids) {
      let result = ids;
      if (useEditorMeta && typeof window.itemInEditorCategory === "function") {
        result = result.filter((id) => window.itemInEditorCategory(working, editorCategoryKey, id));
      }
      if (useEditorMeta && typeof window.sortIdsByEditorOrder === "function") {
        result = window.sortIdsByEditorOrder(working, editorCategoryKey, result);
      }
      return result;
    }

    function bindListReorder() {
      if (!useEditorMeta || !listBox || listBox._reorderBound) return;
      if (typeof window.bindCollapsedCardReorder !== "function") return;
      listBox._reorderBound = true;
      window.bindCollapsedCardReorder(listBox, {
        getId: (el) => el.dataset.dragId || el.dataset.entryRawKey || "",
        onReorder: (ordered) => {
          let full = ordered;
          if (typeof window.mergeVisibleEditorOrder === "function") {
            full = window.mergeVisibleEditorOrder(working, editorCategoryKey, ordered);
          } else if (typeof window.setEditorOrder === "function") {
            window.setEditorOrder(working, editorCategoryKey, ordered);
          }
          if (typeof window.reorderObjectKeys === "function") {
            window.reorderObjectKeys(working.items, full);
          }
          renderListOnly();
        }
      });
    }

    function refreshListPreserveScroll(fn) {
      const main = document.querySelector(".main");
      const top = main ? main.scrollTop : 0;
      fn();
      if (main) main.scrollTop = top;
    }

    function placeKeyAtEndOfVisibleList(key) {
      if (!useEditorMeta || typeof window.getEditorOrder !== "function") return;
      const order = window.getEditorOrder(working, editorCategoryKey);
      const existing = order.indexOf(key);
      if (existing >= 0) order.splice(existing, 1);

      // いま画面に出ている並びの末尾へ挿入（ネストカテゴリ絞り込み中もその一覧の一番下）。
      const visible = filterAndSortIds(idsInCategory(activeCat)).filter((id) => id !== key);
      for (const id of visible) {
        if (!order.includes(id)) order.push(id);
      }
      if (visible.length === 0) {
        order.push(key);
        return;
      }
      const last = visible[visible.length - 1];
      const at = order.indexOf(last);
      order.splice(at >= 0 ? at + 1 : order.length, 0, key);
    }

    function commitAddStatsKey(key, pinTab) {
      if (!key) return false;
      if (Object.prototype.hasOwnProperty.call(working.items, key)) {
        alert("同じキーが既に存在します");
        return false;
      }
      working.items[key] = { fixed: {} };
      const tab = pinTab || activeCat;
      if (typeof window.setItemDisplayTab === "function") {
        window.setItemDisplayTab(working, key, tab);
      }
      if (useEditorMeta && typeof window.assignItemToActiveEditorCategory === "function") {
        window.assignItemToActiveEditorCategory(working, editorCategoryKey, key);
      }
      placeKeyAtEndOfVisibleList(key);
      expanded.add(key);
      render();
      return true;
    }

    function addDefaultEmptyEntry() {
      const items = working.items;
      const defaultMaterial = {
        weapon: "DIAMOND_SWORD", armor: "DIAMOND_CHESTPLATE", tool: "DIAMOND_PICKAXE",
        other: "DIAMOND", catalyst: "BLAZE_ROD", spellbook: "BOOK", thread: "STRING"
      }[activeCat] || "DIAMOND_SWORD";
      let key = defaultMaterial;
      if (Object.prototype.hasOwnProperty.call(items, key)) {
        const unused = catalogCandidates.filter((c) => {
          const sk = statsKeyFromCandidate(c);
          return candidateTab(c) === activeCat && sk && !Object.prototype.hasOwnProperty.call(items, sk);
        });
        if (unused.length) {
          key = statsKeyFromCandidate(unused[0]);
        } else {
          let cmd = 1;
          key = `${defaultMaterial}#${cmd}`;
          while (Object.prototype.hasOwnProperty.call(items, key)) key = `${defaultMaterial}#${++cmd}`;
        }
      }
      commitAddStatsKey(key, activeCat);
    }

    function renderListOnly() {
      if (!listBox) return;
      listBox.innerHTML = "";
      const items = working.items;
      const idsHere = filterAndSortIds(idsInCategory(activeCat));
      if (Object.keys(items).length === 0) {
        listBox.appendChild(emptyGuide("アイテム個別ステはまだありません。", "「+ 追加」でエントリを作り、カード内でカタログID / 表示名 / Material を指定します。"));
      } else if (idsHere.length === 0) {
        listBox.appendChild(emptyGuide("このサブタブに該当するエントリがありません。", "他のサブタブを確認するか、「+ 追加」で新規エントリを作ります。"));
      }
      for (const key of idsHere) {
        const entryEl = renderEntry(key);
        entryEl.dataset.entryKey = key.toLowerCase();
        entryEl.dataset.entryRawKey = key;
        entryEl.dataset.dragId = key;
        listBox.appendChild(entryEl);
      }
      applyFilter();
    }

    // カタログID / 表示名 / statsキーの部分一致で表示中のエントリカードを絞り込む。
    let filterText = "";
    function applyFilter() {
      const q = filterText.trim().toLowerCase();
      const cards = listBox.querySelectorAll(":scope > .entry-card");
      cards.forEach((c) => {
        const key = (c.dataset.entryKey || "").toLowerCase();
        const rawKey = (c.dataset.entryRawKey || "").toLowerCase();
        const catalogId = (c.dataset.catalogId || "").toLowerCase();
        const displayPlain = (c.dataset.displayPlain || "").toLowerCase();
        const ok = !q || key.includes(q) || rawKey.includes(q) || catalogId.includes(q) || displayPlain.includes(q);
        c.style.display = ok ? "" : "none";
      });
    }

    let listBox = null;
    const tabBar = h("div", { class: "recipe-tabs" });
    const catCheckboxRow = h("div", { class: "applies-box" });

    function renderTabBar() {
      tabBar.innerHTML = "";
      for (const [cat, label] of ITEM_STATS_CATEGORIES) {
        const count = idsInCategory(cat).length;
        tabBar.appendChild(h("button", {
          class: `recipe-tab ${activeCat === cat ? "active" : ""}`, type: "button",
          onclick: () => { activeCat = cat; render(); }
        }, [h("span", { text: label }), h("span", { class: "recipe-tab-count", text: String(count) })]));
      }
    }

    function renderCatCheckboxes() {
      catCheckboxRow.innerHTML = "";
      const filter = activeStatFilter();
      for (const [key, label] of STAT_FILTER_GROUPS) {
        const cb = window.checkboxInput(filter[key], (v) => { filter[key] = v; render(); });
        catCheckboxRow.appendChild(h("label", { class: "form-field inline-check" }, [
          cb,
          h("span", { class: "form-label", text: label })
        ]));
      }
      catCheckboxRow.appendChild(h("span", {
        class: "mini-label",
        text: "（タブ既定。各カードでも変更可）",
        title: "上部はタブ全体の既定値。個別カード内のチェックで上書きできます。"
      }));
    }

    function renderCardCatCheckboxes(entryKey) {
      const filter = filterForCard(entryKey);
      const row = h("div", { class: "applies-box card-stat-filter" });
      row.appendChild(h("span", {
        class: "mini-label",
        text: "表示ステータス:",
        title: "「+追加」プルダウンに出すステカテゴリ。未変更ならタブ上部の既定を継承します。"
      }));
      for (const [key, label] of STAT_FILTER_GROUPS) {
        const cb = window.checkboxInput(!!filter[key], (v) => {
          const own = ensureCardFilter(entryKey);
          own[key] = v;
          cardFilterOverrides.add(entryKey);
          render();
        });
        row.appendChild(h("label", { class: "form-field inline-check" }, [
          cb,
          h("span", { class: "form-label", text: label })
        ]));
      }
      if (cardFilterOverrides.has(entryKey)) {
        row.appendChild(h("button", {
          class: "btn-small", type: "button", text: "タブ既定に戻す",
          onclick: () => {
            cardFilterOverrides.delete(entryKey);
            delete cardStatFilters[entryKey];
            render();
          }
        }));
      }
      return row;
    }

    function render() {
      root.innerHTML = "";
      const items = working.items;

      if (!hideCategoryTabs) {
        renderTabBar();
        root.appendChild(tabBar);
      }

      renderCatCheckboxes();
      root.appendChild(h("div", { class: "sub-section" }, [
        window.subTitleEl("表示ステータス (攻撃/守備/補助/Ars/その他)", "チェックしたカテゴリ(Lore表示設定のカテゴリ)のステだけを「+追加」プルダウンの候補に出します。登録済みのステは絞り込みに関係なく常に表示されます。"),
        catCheckboxRow
      ]));
      root.appendChild(h("div", {
        class: "field-desc",
        text: "値の基準: 固定値・品質値・カテゴリフォールバックはすべて加算です。攻撃力/防具値/防具強度/ノックバック耐性は 0 基準、攻撃速度・リーチ・最大体力・移動速度はバニラ値に加算されます。耐久値だけは未設定=無限、設定時=その最大値で上書きです。"
      }));

      if (activeCat === "other") {
        root.appendChild(h("div", {
          class: "field-desc",
          style: "font-size:12px;color:var(--muted,#6b7280);margin:0 0 10px;line-height:1.55;padding:8px 10px;border:1px solid var(--border);border-radius:6px;background:var(--panel-2);",
          text: "【補助カテゴリ・実装済みステ】durability（最大耐久力・floorしてint）、mining-fortune（採掘幸運）、fishing-luck / fishing-bonus（釣り幸運・ボーナス）。いずれも fixed / per-quality / random で設定。高度なオプションで付与種類のランダム化も可。"
        }));
      }

      // フォールバックはネスト「すべて」タブのみ（カスタム/未設定カテゴリでは出さない）
      if (shouldShowFallbackCard()) {
        root.appendChild(renderCategoryFallbackSection());
      }

      const filterRow = h("div", { class: "item-stats-filter" }, [
        h("span", { class: "mini-label", text: "検索" }),
        h("input", {
          class: "field-input", type: "text", spellcheck: "false",
          placeholder: "カタログID / 表示名 / キーで絞り込み",
          value: filterText,
          oninput: (e) => { filterText = e.target.value; applyFilter(); }
        })
      ]);
      root.appendChild(filterRow);

      listBox = h("div", { class: "item-stats-list" });
      root.appendChild(listBox);

      renderListOnly();
      bindListReorder();

      root.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ 追加",
          title: "新規エントリを追加（カード内でカタログID / 表示名 / Material を指定）",
          onclick: () => addDefaultEmptyEntry()
        })
      ]));

      applyFilter();
    }

    // ============================================================
    // 乗算モード (multipliers) ヘルパー
    //   entry.multipliers.<layerId>.{fixed,per-quality,random}.<stat> = 倍率 (1.2 = x1.2)
    //   未選択レイヤは UNSET_LAYER キーで保持 (保存前validationがエラーで保存をブロックする —
    //   レイヤ選択を確定するまで保存できない仕様。Java側は防御的に __ 接頭辞をスキップ)。
    // ============================================================
    const UNSET_LAYER = "__unset__";

    function multiplierLayers() {
      return Array.isArray(window.MULTIPLIER_LAYERS) ? window.MULTIPLIER_LAYERS : [];
    }
    /**
     * このステで使える乗算レイヤ定義 (2026-07: レイヤは基準ステータスごとの定義)。
     * stat 未指定の旧形式レイヤは全ステ許容として残す (後方互換)。
     */
    function layersForStatDef(stat) {
      const norm = (k) => String(k || "").trim().toLowerCase().replace(/_/g, "-");
      return multiplierLayers().filter((l) => !l.stat || norm(l.stat) === norm(stat));
    }
    function multipliersOf(entryRef) {
      if (!entryRef.multipliers || typeof entryRef.multipliers !== "object") entryRef.multipliers = {};
      return entryRef.multipliers;
    }
    function layerSection(entryRef, layerId, sectionKey) {
      const ml = multipliersOf(entryRef);
      if (!ml[layerId] || typeof ml[layerId] !== "object") ml[layerId] = {};
      if (!ml[layerId][sectionKey] || typeof ml[layerId][sectionKey] !== "object") ml[layerId][sectionKey] = {};
      return ml[layerId][sectionKey];
    }
    function cleanupLayer(entryRef, layerId) {
      const ml = multipliersOf(entryRef);
      const layer = ml[layerId];
      if (!layer || typeof layer !== "object") { delete ml[layerId]; return; }
      for (const sec of ["fixed", "per-quality", "random"]) {
        if (layer[sec] && typeof layer[sec] === "object" && Object.keys(layer[sec]).length === 0) delete layer[sec];
      }
      if (Object.keys(layer).length === 0) delete ml[layerId];
      if (Object.keys(ml).length === 0) delete entryRef.multipliers;
    }
    /** このステが同セクションで既に使っている乗算レイヤの集合。 */
    function usedLayersFor(entryRef, sectionKey, stat) {
      const used = new Set();
      const ml = entryRef.multipliers;
      if (!ml || typeof ml !== "object") return used;
      for (const [layerId, layer] of Object.entries(ml)) {
        const sec = layer && layer[sectionKey];
        if (sec && typeof sec === "object" && Object.prototype.hasOwnProperty.call(sec, stat)) used.add(layerId);
      }
      return used;
    }
    /** 乗算モードON時の割当先レイヤ: このステ用の未使用の定義済みレイヤ → 無ければ未選択。 */
    function pickFreeLayer(entryRef, sectionKey, stat) {
      const used = usedLayersFor(entryRef, sectionKey, stat);
      for (const l of layersForStatDef(stat)) {
        if (!used.has(l.id)) return l.id;
      }
      return used.has(UNSET_LAYER) ? null : UNSET_LAYER;
    }
    function layerNameOf(layerId) {
      if (layerId === UNSET_LAYER) return "(レイヤ未選択)";
      const found = multiplierLayers().find((l) => l.id === layerId);
      return found ? found.name : layerId;
    }
    // ============================================================
    // 行表示順ヘルパー (加算行の直後にそのステの乗算行を並べる)
    // ============================================================
    function rowOrderKey(entryKey, sectionKey) {
      return `${entryKey || "__fallback__"}\u0000${sectionKey}`;
    }
    /** セクション内に存在する全ステ (加算 + 乗算レイヤ) をデータ順で列挙。 */
    function statsInSection(entryRef, sectionKey) {
      const present = [];
      for (const k of Object.keys(entryRef[sectionKey] || {})) present.push(k);
      const ml = entryRef.multipliers;
      if (ml && typeof ml === "object") {
        for (const layer of Object.values(ml)) {
          const sec = layer && layer[sectionKey];
          if (!sec || typeof sec !== "object") continue;
          for (const k of Object.keys(sec)) if (!present.includes(k)) present.push(k);
        }
      }
      return present;
    }
    /** 表示順を保持しつつ現データと同期した行順を返す (新ステは末尾へ追加)。 */
    function sectionRowOrder(entryRef, sectionKey, entryKey) {
      const present = statsInSection(entryRef, sectionKey);
      const key = rowOrderKey(entryKey, sectionKey);
      const prev = sectionRowOrders[key] || [];
      const next = prev.filter((s) => present.includes(s));
      for (const s of present) if (!next.includes(s)) next.push(s);
      sectionRowOrders[key] = next;
      return next;
    }
    /**
     * ステ名変更を表示順へ反映する。旧ステがまだ残っている場合 (乗算行のみ改名等) は
     * 旧位置の直後へ新ステを差し込み、残っていなければその場で置換する。
     */
    function renameInRowOrder(entryRef, sectionKey, entryKey, oldStat, newStat) {
      const order = sectionRowOrders[rowOrderKey(entryKey, sectionKey)];
      if (!order) return;
      const i = order.indexOf(oldStat);
      if (i < 0) return;
      if (statsInSection(entryRef, sectionKey).includes(oldStat)) {
        if (!order.includes(newStat)) order.splice(i + 1, 0, newStat);
      } else if (order.includes(newStat)) {
        order.splice(i, 1);
      } else {
        order[i] = newStat;
      }
    }
    /** このステが使う乗算レイヤをレイヤ定義順 → 未定義レイヤ → 未選択の順で列挙。 */
    function layersForStat(entryRef, sectionKey, stat) {
      const ml = entryRef.multipliers;
      if (!ml || typeof ml !== "object") return [];
      const ordered = [];
      for (const l of multiplierLayers()) {
        if (ml[l.id]) ordered.push(l.id);
      }
      for (const id of Object.keys(ml)) {
        if (!ordered.includes(id) && id !== UNSET_LAYER) ordered.push(id);
      }
      if (ml[UNSET_LAYER]) ordered.push(UNSET_LAYER);
      return ordered.filter((layerId) => {
        const sec = ml[layerId] && ml[layerId][sectionKey];
        return sec && typeof sec === "object" && Object.prototype.hasOwnProperty.call(sec, stat);
      });
    }
    /**
     * 乗算レイヤ選択セレクト。候補はこのステが基準のレイヤのみ (2026-07 仕様変更)。
     * 同ステ・同セクションで使用済みのレイヤはグレーアウト。基準ステ不一致の現在レイヤは
     * エラー表記で残す (保存はサーバ側バリデーションが弾くため、ここで選び直せるようにする)。
     */
    function multLayerSelect(entryRef, sectionKey, stat, currentLayer) {
      const used = usedLayersFor(entryRef, sectionKey, stat);
      const opts = [{
        value: UNSET_LAYER, primary: "(レイヤ未選択)", secondary: "",
        disabled: used.has(UNSET_LAYER) && currentLayer !== UNSET_LAYER
      }];
      const forStat = layersForStatDef(stat);
      for (const l of forStat) {
        opts.push({
          value: l.id,
          primary: l.name,
          secondary: l.id !== l.name ? l.id : "",
          disabled: used.has(l.id) && l.id !== currentLayer,
          title: used.has(l.id) && l.id !== currentLayer ? "このレイヤには同じステが設定済みです" : l.name
        });
      }
      // 現在レイヤがこのステ用の定義に無い (未定義 or 基準ステ不一致) 場合もセレクトに出す。
      if (currentLayer !== UNSET_LAYER && !forStat.some((l) => l.id === currentLayer)) {
        const def = multiplierLayers().find((l) => l.id === currentLayer);
        opts.push({
          value: currentLayer,
          primary: (def ? def.name : currentLayer) + " (このステ用の定義ではありません)",
          secondary: currentLayer,
          title: def
            ? `レイヤ「${def.name}」の基準ステータスは ${def.stat || "(未設定)"} です。保存前にこのステ用のレイヤへ変更してください。`
            : "未定義のレイヤです。保存前にこのステ用のレイヤへ変更してください。"
        });
      }
      return window.listSelect({
        value: currentLayer,
        className: "mult-layer-select",
        options: opts,
        onCommit: (nv) => {
          if (!nv || nv === currentLayer) return false;
          const usedNow = usedLayersFor(entryRef, sectionKey, stat);
          if (usedNow.has(nv)) { alert("このレイヤには同じステータスが設定済みです"); return false; }
          const from = layerSection(entryRef, currentLayer, sectionKey);
          const value = from[stat];
          delete from[stat];
          cleanupLayer(entryRef, currentLayer);
          layerSection(entryRef, nv, sectionKey)[stat] = value;
          render();
          return true;
        }
      });
    }
    /** 乗算モードチェックボックス (加算行/乗算行 共通)。強制乗算(加算行が既にある)状態の拒否は change ハンドラ内の alert で行う。 */
    function multModeCheckbox(entryRef, sectionKey, stat, isMult) {
      const cb = window.checkboxInput(isMult, (v) => {
        if (v && !isMult) {
          // このステ用のレイヤ未定義なら乗算モード自体を許可しない (未選択レイヤは保存もできず行き止まりになる)。
          if (layersForStatDef(stat).length === 0) {
            alert("このステータス用の乗算レイヤが定義されていません。先に「ロア表示 (lore)」設定の『乗算レイヤ (multiplier-layers)』で、基準ステータスにこのステを選んだレイヤを追加してください。");
            render();
            return;
          }
          // 加算 → 乗算: 値は既定 x1.0 で移す (加算値と倍率は意味が異なるため引き継がない)。
          const layerId = pickFreeLayer(entryRef, sectionKey, stat);
          if (layerId == null) { alert("このステータスは全ての乗算レイヤに設定済みです"); render(); return; }
          delete entryRef[sectionKey][stat];
          layerSection(entryRef, layerId, sectionKey)[stat] =
            sectionKey === "random" ? { min: 1, max: 1 } : 1.0;
          render();
        } else if (!v && isMult) {
          // 乗算 → 加算: 同セクションに加算モードが既にあれば外せない (仕様)。
          if (Object.prototype.hasOwnProperty.call(entryRef[sectionKey] || {}, stat)) {
            alert("このステータスの合算(加算)モードは既に設定済みのため、乗算モードを外せません。先に加算側の行を削除してください。");
            render();
            return;
          }
          const layers = usedLayersFor(entryRef, sectionKey, stat);
          const from = [...layers].find((l) => layerSection(entryRef, l, sectionKey)[stat] !== undefined);
          if (from != null) {
            delete layerSection(entryRef, from, sectionKey)[stat];
            cleanupLayer(entryRef, from);
          }
          if (!entryRef[sectionKey] || typeof entryRef[sectionKey] !== "object") entryRef[sectionKey] = {};
          entryRef[sectionKey][stat] = sectionKey === "random" ? { min: 0, max: 0 } : 0;
          render();
        }
      });
      const label = h("label", {
        class: "inline-check",
        title: "ONにするとプレイヤーの合算済み総合ステータスへこの倍率を掛けます (同一レイヤの倍率は足し合わせてから乗算)。Lore表示は符号x・単位なし。"
      }, [cb, h("span", { class: "mini-label", text: "乗算" })]);
      return label;
    }

    // fixed/per-quality/random の1行レンダラ (item エントリとフォールバックの両方で共用)。
    // entryRef は { fixed, per-quality, random } の形を持つオブジェクト (item entry または working.fallback)。
    // UI: ステ選択 [数値入力] 単位スロット(2文字分固定幅) 乗算モードチェック (乗算時: レイヤ選択) ×ボタン
    function additiveRow(entryRef, sectionKey, stat, entryKey, valueOpts) {
      const row = h("div", { class: "stat-row" });
      const allowed = (s) => statAllowed(s, entryKey);
      const s = statSelect(stat, (nv) => {
        if (!nv || nv === stat) return false;
        if (Object.prototype.hasOwnProperty.call(entryRef[sectionKey], nv)) {
          // 同一ステ選択は許可 (仕様変更): 加算が既にあるステを選んだ場合は
          // 強制的に乗算モードの行として追加し直す。このステ用のレイヤ未定義なら追加不可。
          if (layersForStatDef(nv).length === 0) {
            alert("同じステータスの2行目は乗算モードになりますが、このステータス用の乗算レイヤが定義されていません。先に「ロア表示 (lore)」設定の『乗算レイヤ (multiplier-layers)』で、基準ステータスにこのステを選んだレイヤを追加してください。");
            return false;
          }
          const layerId = pickFreeLayer(entryRef, sectionKey, nv);
          if (layerId == null) { alert("このステータスは加算+全レイヤ乗算が設定済みで、これ以上追加できません"); return false; }
          delete entryRef[sectionKey][stat];
          layerSection(entryRef, layerId, sectionKey)[nv] =
            sectionKey === "random" ? { min: 1, max: 1 } : 1.0;
          renameInRowOrder(entryRef, sectionKey, entryKey, stat, nv);
          render();
          return true;
        }
        renameKey(entryRef[sectionKey], stat, nv);
        renameInRowOrder(entryRef, sectionKey, entryKey, stat, nv);
        render();
        return true;
      }, allowed);
      row.appendChild(s);
      if (sectionKey === "random") {
        const range = entryRef.random[stat] && typeof entryRef.random[stat] === "object" ? entryRef.random[stat] : {};
        if (range.min == null) range.min = 0;
        if (range.max == null) range.max = 0;
        entryRef.random[stat] = range;
        row.appendChild(h("span", { class: "range-label", text: "min" }));
        row.appendChild(statValueControl(stat, range.min, (v) => { range.min = v; }, { allowIntDecimal: true }));
        row.appendChild(h("span", { class: "range-label", text: "max" }));
        row.appendChild(statValueControl(stat, range.max, (v) => { range.max = v; }, { allowIntDecimal: true }));
      } else {
        row.appendChild(statValueControl(stat, entryRef[sectionKey][stat], (v) => { entryRef[sectionKey][stat] = v; }, valueOpts));
      }
      row.appendChild(window.statUnitSlot(stat));
      if (entryKey) row.appendChild(multModeCheckbox(entryRef, sectionKey, stat, false));
      row.appendChild(h("button", {
        class: "btn-small danger", type: "button", text: "×",
        onclick: () => { delete entryRef[sectionKey][stat]; render(); }
      }));
      return row;
    }

    // 乗算モード行: [x数値入力] (単位なし・空スロットで位置揃え) 乗算チェックON レイヤ選択 ×ボタン
    function multiplierRow(entryRef, sectionKey, layerId, stat, entryKey) {
      const sec = layerSection(entryRef, layerId, sectionKey);
      const row = h("div", { class: "stat-row mult-row" });
      const allowed = (s) => statAllowed(s, entryKey);
      const s = statSelect(stat, (nv) => {
        if (!nv || nv === stat) return false;
        const usedNow = usedLayersFor(entryRef, sectionKey, nv);
        if (usedNow.has(layerId)) { alert("このレイヤには同じステータスが設定済みです"); return false; }
        // レイヤは基準ステータスごとの定義: 変更先ステで現在レイヤが使えなければ拒否。
        if (layerId !== UNSET_LAYER && !layersForStatDef(nv).some((l) => l.id === layerId)) {
          alert("このレイヤの基準ステータスが変更先と一致しません。変更先ステ用の乗算レイヤを「ロア表示 (lore)」設定で定義するか、レイヤ選択を変更してください。");
          return false;
        }
        renameKey(sec, stat, nv);
        renameInRowOrder(entryRef, sectionKey, entryKey, stat, nv);
        render();
        return true;
      }, allowed);
      row.appendChild(s);
      if (sectionKey === "random") {
        const range = sec[stat] && typeof sec[stat] === "object" ? sec[stat] : {};
        if (range.min == null) range.min = 1;
        if (range.max == null) range.max = 1;
        sec[stat] = range;
        row.appendChild(h("span", { class: "range-label", text: "min" }));
        row.appendChild(h("span", { class: "mult-prefix", text: "x" }));
        row.appendChild(window.numberInput(range.min, (v) => { range.min = v == null ? 1 : v; }));
        row.appendChild(h("span", { class: "range-label", text: "max" }));
        row.appendChild(h("span", { class: "mult-prefix", text: "x" }));
        row.appendChild(window.numberInput(range.max, (v) => { range.max = v == null ? 1 : v; }));
      } else {
        row.appendChild(h("span", { class: "mult-prefix", text: "x", title: "倍率 (1.2 = 総合値を1.2倍)" }));
        row.appendChild(window.numberInput(sec[stat], (v) => { sec[stat] = v == null ? 1 : v; }));
      }
      row.appendChild(window.statUnitSlot(null));
      row.appendChild(multModeCheckbox(entryRef, sectionKey, stat, true));
      row.appendChild(multLayerSelect(entryRef, sectionKey, stat, layerId));
      row.appendChild(h("button", {
        class: "btn-small danger", type: "button", text: "×",
        onclick: () => {
          delete sec[stat];
          cleanupLayer(entryRef, layerId);
          render();
        }
      }));
      return row;
    }

    /**
     * 加算行 + 乗算行を「ステ単位でグルーピング」して DOM へ追加する。
     * 表示順は sectionRowOrders が保持する行順 (加算行の直後に同ステの乗算行が続く)。
     */
    function appendSectionRows(container, entryRef, sectionKey, entryKey, rowFn) {
      const order = sectionRowOrder(entryRef, sectionKey, entryKey);
      for (const stat of order) {
        if (Object.prototype.hasOwnProperty.call(entryRef[sectionKey] || {}, stat)) {
          container.appendChild(rowFn(entryRef, stat, entryKey));
        }
        if (entryKey) {
          for (const layerId of layersForStat(entryRef, sectionKey, stat)) {
            container.appendChild(multiplierRow(entryRef, sectionKey, layerId, stat, entryKey));
          }
        }
      }
      return order.length;
    }

    function fixedRow(entryRef, stat, entryKey) {
      return additiveRow(entryRef, "fixed", stat, entryKey);
    }

    function perQualityRow(entryRef, stat, entryKey) {
      return additiveRow(entryRef, "per-quality", stat, entryKey, { allowIntDecimal: true });
    }

    // ランダムロール行: ステ選択 + min/max 数値 (値は {min,max} オブジェクト)。
    function randomRow(entryRef, stat, entryKey) {
      return additiveRow(entryRef, "random", stat, entryKey);
    }

    // fixed/per-quality/random の3小節をまとめたブロックを構築する (item エントリとフォールバックの両方で共用)。
    // entryKey があるときカード単位のステカテゴリフィルタを使う。
    function renderStatBlocks(entryRef, entryKey) {
      if (entryRef.fixed == null) entryRef.fixed = {};
      if (entryRef["per-quality"] == null) entryRef["per-quality"] = {};
      if (entryRef.random == null) entryRef.random = {};

      const allowed = (s) => statAllowed(s, entryKey);

      // 登録済みステは常に全件表示する。カテゴリ・チェックボックスは「追加候補プルダウン」の
      // 絞り込み専用であり、既に登録済みのステを不可視にはしない (A4)。
      const fixedRows = h("div", { class: "stat-rows" });
      const fixedCount = appendSectionRows(fixedRows, entryRef, "fixed", entryKey, fixedRow);
      fixedRows.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 固定ステ追加",
        onclick: () => { addStat(entryRef.fixed, 0, allowed); render(); }
      }));

      const perQualityRows = h("div", { class: "stat-rows" });
      const pqCount = appendSectionRows(perQualityRows, entryRef, "per-quality", entryKey, perQualityRow);
      perQualityRows.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 品質別上昇値追加",
        onclick: () => { addStat(entryRef["per-quality"], 0, allowed); render(); }
      }));

      const randomRows = h("div", { class: "stat-rows" });
      const randCount = appendSectionRows(randomRows, entryRef, "random", entryKey, randomRow);
      randomRows.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ ランダムロールステ追加",
        onclick: () => { addRandomStat(entryRef.random, allowed); render(); }
      }));

      const assignedKeys = assignedStatKeys(entryRef);
      const hasAdvanced = entryRef.advanced && typeof entryRef.advanced === "object";
      const advSection = h("div", { class: "sub-section" });
      advSection.appendChild(window.subTitleEl("高度なオプション (advanced)",
        "割り当て済みステのうち、実際に付与される種類を確率でランダム化します。"));
      if (!hasAdvanced) {
        advSection.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ 高度なオプション追加",
          onclick: () => {
            entryRef.advanced = { "randomize-grants": true, "grant-chances": {} };
            syncGrantChances(entryRef);
            render();
          }
        }));
      } else {
        const adv = entryRef.advanced;
        // Presence of advanced = randomize-grants on; no separate toggle.
        adv["randomize-grants"] = true;
        if (adv["grant-chances"] == null || typeof adv["grant-chances"] !== "object") adv["grant-chances"] = {};
        syncGrantChances(entryRef);
        const chanceRows = h("div", { class: "stat-rows" });
        if (assignedKeys.length === 0) {
          chanceRows.appendChild(h("div", { class: "empty-hint", text: "先に fixed / per-quality / random へステを追加すると、種類ごとに付与確率行が自動追加されます。" }));
        }
        for (const sk of assignedKeys) {
          const pct = Math.round((Number(adv["grant-chances"][sk]) || 0) * 100);
          const row = h("div", { class: "stat-row" });
          const skJa = (window.LABELS && typeof window.LABELS.statLabel === "function") ? window.LABELS.statLabel(sk) : sk;
          row.appendChild(h("span", { class: "form-label", text: skJa || sk, title: sk }));
          row.appendChild(window.numberInput(pct, (v) => {
            const n = v == null ? 100 : Math.max(0, Math.min(100, v));
            adv["grant-chances"][sk] = n / 100;
          }, { int: true }));
          row.appendChild(h("span", { class: "pct-suffix", text: "%" }));
          chanceRows.appendChild(row);
        }
        advSection.appendChild(h("div", { class: "mini-label", text: "ステごとの付与確率 (grant-chances)" }));
        advSection.appendChild(chanceRows);
        advSection.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "× 高度なオプションを削除",
          onclick: () => { delete entryRef.advanced; render(); }
        }));
      }

      const blocks = [];
      // 表示ステータス絞り込み (カード個別) は固定ステの1つ上の行に独立して置く。
      if (entryKey) {
        blocks.push(h("div", { class: "sub-section" }, [renderCardCatCheckboxes(entryKey)]));
      }
      blocks.push(
        h("div", { class: "sub-section" }, [
          window.subTitleEl("固定ステ (fixed)", "常に適用される固定ステータス"),
          fixedCount ? null : h("div", { class: "empty-hint", text: "まだ固定ステがありません。「+ 固定ステ追加」で追加します。" }),
          fixedRows
        ]),
        h("div", { class: "sub-section" }, [
          window.subTitleEl("品質別上昇値 (per-quality)", "品質が1上がるごとにこのステへ加算される増分 (fixedの上に加算)"),
          pqCount ? null : h("div", { class: "empty-hint", text: "まだ品質別上昇値がありません。「+ 品質別上昇値追加」で追加します。" }),
          perQualityRows
        ]),
        h("div", { class: "sub-section" }, [
          window.subTitleEl("ランダムロールステ (random)", "レンジ{min,max}を「範囲=1」として品質に応じたロール分布で抽選し、fixed/per-qualityの上に加算(物理魔法共用)。分布パラメータは quality.yml。"),
          randCount ? null : h("div", { class: "empty-hint", text: "まだランダムロールステがありません。「+ ランダムロールステ追加」で追加します。" }),
          randomRows
        ]),
        advSection
      );
      return blocks;
    }

    function assignedStatKeys(entryRef) {
      const keys = new Set();
      for (const k of Object.keys(entryRef.fixed || {})) keys.add(k);
      for (const k of Object.keys(entryRef["per-quality"] || {})) keys.add(k);
      for (const k of Object.keys(entryRef.random || {})) keys.add(k);
      return [...keys].sort();
    }

    function syncGrantChances(entryRef) {
      if (!entryRef.advanced || typeof entryRef.advanced !== "object") return;
      if (!entryRef.advanced["grant-chances"] || typeof entryRef.advanced["grant-chances"] !== "object") {
        entryRef.advanced["grant-chances"] = {};
      }
      const chances = entryRef.advanced["grant-chances"];
      const assigned = new Set(assignedStatKeys(entryRef));
      for (const k of assigned) {
        if (!Object.prototype.hasOwnProperty.call(chances, k)) chances[k] = 1;
      }
      for (const k of Object.keys(chances)) {
        if (!assigned.has(k)) delete chances[k];
      }
    }

    // カテゴリ別フォールバック (fixed のみ + デフォルト表示)。品質別/ランダム/高度なオプションは無し。
    // 解決順: アイテム個別 → 任意カテゴリの fallback-overrides (所属時・全置換) → 装備種 fallback → バニラ材質。
    // 表示ルール (2026-07 仕様変更):
    //   「すべて」(__all__)      : フォールバックカードを出さない
    //   「未設定」(__unset__)   : 既定 (未設定) フォールバックカードを出す
    //   任意カテゴリ            : チェックボックスON時のみ上書きフォールバックカードを出す
    function fallbackCardMode() {
      if (!useEditorMeta || !editorCategoryKey) return "default";
      if (typeof window.activeEditorCategory !== "function") return "default";
      const active = window.activeEditorCategory(working, editorCategoryKey);
      if (active == null) return "none";           // すべて
      if (active === "__unset__") return "default"; // 未設定 → 既定フォールバック
      return "override:" + active;                  // 任意カテゴリ → 上書き
    }
    function shouldShowFallbackCard() {
      return fallbackCardMode() !== "none";
    }

    function renderCategoryFallbackSection() {
      const mode = fallbackCardMode();
      if (mode.startsWith("override:")) {
        return renderFallbackOverrideSection(mode.slice("override:".length));
      }
      const cat = activeCat;
      const catLabel = tabLabelFor(cat, ITEM_STATS_CATEGORIES);
      const sec = working.fallback && typeof working.fallback === "object" ? working.fallback[cat] : null;
      const hasFixed = sec && typeof sec === "object" && sec.fixed && typeof sec.fixed === "object"
        && Object.keys(sec.fixed).length > 0;

      const head = [h("span", {
        class: "entry-key-label",
        text: `フォールバックステ (${catLabel} / 未設定カテゴリ用)`
      })];
      const hint = h("div", {
        class: "empty-hint",
        text: "任意カテゴリ未所属 (未設定) のアイテムに適用される既定フォールバック。アイテム個別未設定の固定ステを埋める。品質別・ランダム・高度なオプションは不可。デフォルト表示ONなら値が0でも Lore に出す。"
      });

      if (!hasFixed) {
        return h("div", { class: "entry-card fallback-card" }, [
          h("div", { class: "entry-head" }, head),
          h("div", { class: "entry-body" }, [
            hint,
            h("div", { class: "form-actions" }, [
              h("button", {
                class: "btn-small", type: "button", text: "+ 固定ステ追加",
                onclick: () => {
                  const fb = ensureCategoryFallback(cat);
                  addStat(fb.fixed, 0, (s) => statAllowed(s));
                  render();
                }
              })
            ])
          ])
        ]);
      }

      const body = [hint].concat(renderFallbackFixedBlocks(sec, () => ensureCategoryFallback(cat)));
      return card(head, body);
    }

    // 任意カテゴリのフォールバック上書き: チェックON → fallback-overrides.<catId> カードを表示。
    // 上書きが設定されたカテゴリのアイテムは既定フォールバックの代わりにこれだけを適用する。
    function renderFallbackOverrideSection(catId) {
      const overridesRoot = () => {
        if (!working["fallback-overrides"] || typeof working["fallback-overrides"] !== "object") {
          working["fallback-overrides"] = {};
        }
        return working["fallback-overrides"];
      };
      const existing = working["fallback-overrides"] && typeof working["fallback-overrides"] === "object"
        ? working["fallback-overrides"][catId] : null;
      const enabled = !!(existing && typeof existing === "object");

      const catLabel = (typeof window.listEditorCategories === "function"
        ? (window.listEditorCategories(working, editorCategoryKey).find((c) => c.id === catId) || {})
        : {}).label || catId;

      const box = h("div", { class: "entry-card fallback-card" });
      const headRow = h("div", { class: "entry-head" }, [
        h("span", { class: "entry-key-label", text: `フォールバック上書き (${catLabel})` }),
        h("label", { class: "inline-check", title: "ONにするとこのカテゴリ専用のフォールバックを設定できます (既定フォールバックを完全に置き換え)。" }, [
          window.checkboxInput(enabled, (v) => {
            if (v) {
              const root = overridesRoot();
              if (!root[catId] || typeof root[catId] !== "object") root[catId] = { fixed: {} };
            } else {
              if (existing && Object.keys(existing.fixed || {}).length > 0
                  && !confirm("このカテゴリのフォールバック上書きを削除しますか？")) {
                render();
                return;
              }
              if (working["fallback-overrides"]) {
                delete working["fallback-overrides"][catId];
                if (Object.keys(working["fallback-overrides"]).length === 0) delete working["fallback-overrides"];
              }
            }
            render();
          }),
          h("span", { class: "mini-label", text: "フォールバックを上書き" })
        ])
      ]);
      box.appendChild(headRow);

      const body = h("div", { class: "entry-body" });
      if (!enabled) {
        body.appendChild(h("div", {
          class: "empty-hint",
          text: "上書きOFF: このカテゴリのアイテムには既定 (未設定) フォールバックが適用されます。"
        }));
      } else {
        body.appendChild(h("div", {
          class: "empty-hint",
          text: "上書きON: このカテゴリのアイテムは既定フォールバックの代わりにこの内容だけを適用します。"
        }));
        const ensureFn = () => {
          const root = overridesRoot();
          if (!root[catId] || typeof root[catId] !== "object") root[catId] = {};
          const sec = root[catId];
          if (sec.fixed == null || typeof sec.fixed !== "object") sec.fixed = {};
          if (sec["lore-default"] == null || typeof sec["lore-default"] !== "object") sec["lore-default"] = {};
          delete sec["per-quality"];
          delete sec.random;
          delete sec.advanced;
          return sec;
        };
        const sec = ensureFn();
        for (const el of renderFallbackFixedBlocks(sec, ensureFn)) body.appendChild(el);
      }
      box.appendChild(body);
      return box;
    }

    function ensureCategoryFallback(cat) {
      if (!working.fallback || typeof working.fallback !== "object") working.fallback = {};
      if (!working.fallback[cat] || typeof working.fallback[cat] !== "object") {
        working.fallback[cat] = {};
      }
      const sec = working.fallback[cat];
      if (sec.fixed == null || typeof sec.fixed !== "object") sec.fixed = {};
      if (sec["lore-default"] == null || typeof sec["lore-default"] !== "object") sec["lore-default"] = {};
      // カテゴリ別フォールバックでは品質別/ランダム/高度なオプションを持たない
      delete sec["per-quality"];
      delete sec.random;
      delete sec.advanced;
      return sec;
    }

    function fallbackFixedRow(catSec, stat) {
      if (!catSec["lore-default"] || typeof catSec["lore-default"] !== "object") {
        catSec["lore-default"] = {};
      }
      const loreDef = catSec["lore-default"];
      const row = h("div", { class: "stat-row" });
      const allowed = (s) => statAllowed(s);
      const s = statSelect(stat, (nv) => {
        if (!nv || nv === stat) return false;
        if (Object.prototype.hasOwnProperty.call(catSec.fixed, nv)) { alert("同じステータスが既にあります"); return false; }
        renameKey(catSec.fixed, stat, nv);
        if (Object.prototype.hasOwnProperty.call(loreDef, stat)) {
          loreDef[nv] = loreDef[stat];
          delete loreDef[stat];
        }
        render(); return true;
      }, allowed);
      row.appendChild(s);
      row.appendChild(statValueControl(stat, catSec.fixed[stat], (v) => { catSec.fixed[stat] = v; }));
      row.appendChild(window.statUnitSlot(stat));
      const loreDesc = window.LABELS && typeof window.LABELS.fieldDesc === "function"
        ? window.LABELS.fieldDesc("lore-default")
        : "ONなら値が0でも Lore に表示";
      row.appendChild(h("label", {
        class: "inline-check",
        title: loreDesc
      }, [
        window.checkboxInput(!!loreDef[stat], (v) => {
          if (v) loreDef[stat] = true;
          else delete loreDef[stat];
        }),
        h("span", { text: "デフォルト表示" })
      ]));
      row.appendChild(h("button", {
        class: "btn-small danger", type: "button", text: "×",
        onclick: () => {
          delete catSec.fixed[stat];
          delete loreDef[stat];
          render();
        }
      }));
      return row;
    }

    function renderFallbackFixedBlocks(catSec, ensureFn) {
      if (catSec.fixed == null) catSec.fixed = {};
      if (catSec["lore-default"] == null) catSec["lore-default"] = {};
      const fixedKeys = Object.keys(catSec.fixed);
      const fixedRows = h("div", { class: "stat-rows" });
      for (const stat of fixedKeys) fixedRows.appendChild(fallbackFixedRow(catSec, stat));
      fixedRows.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 固定ステ追加",
        onclick: () => {
          if (typeof ensureFn === "function") ensureFn();
          addStat(catSec.fixed, 0, (s) => statAllowed(s));
          render();
        }
      }));
      return [
        h("div", { class: "sub-section" }, [
          window.subTitleEl("固定ステ (fixed)", "この装備種で未設定のキーを埋める既定値"),
          fixedKeys.length ? null : h("div", { class: "empty-hint", text: "まだ固定ステがありません。" }),
          fixedRows
        ])
      ];
    }

    // アイテム個別のトップレベル設定 (fixed/random/per-quality と同じ item 直下)。
    //   offhand-stats-apply : オフハンドに持ったときステを合算するか (既定OFF)。ONのときのみキーを立てる。
    // 既定値のときはキーを作らない (ロスレス: 元に無かったキーを増やさない)。
    // 旧「耐久値(簡易上書き)」フィールドは廃止。耐久力は「補助」の durability ステ
    //   (固定/品質別上昇値/ランダムロール)で統一して設定する。
    function itemLevelFields(entry) {
      const grid = h("div", { class: "field-grid" });

      const offCb = window.checkboxInput(entry["offhand-stats-apply"] === true, (v) => {
        if (v) entry["offhand-stats-apply"] = true;
        else delete entry["offhand-stats-apply"];
      });
      grid.appendChild(h("label", { class: "form-field inline-check" }, [
        window.fieldLabelEl("offhand-stats-apply", { label: "オフハンド合算", desc: "このアイテムをオフハンドに持ったとき、そのステータスを戦闘集計に合算するか(既定OFF)。" }),
        offCb
      ]));

      grid.appendChild(fieldRow("use-level-requirement", window.numberInput(
        entry["use-level-requirement"] != null ? entry["use-level-requirement"] : 0,
        (v) => {
          // 未入力は 0 にフォールバック（キーも 0 で残す）。
          const n = v == null || Number.isNaN(v) ? 0 : Math.max(0, Math.trunc(v));
          entry["use-level-requirement"] = n;
        },
        { int: true }
      )));

      // 使用スキル: hub から渡されたカテゴリ限定リストがあればそれ、なければ全スキル。
      if (useSkillOptions && Array.isArray(useSkillOptions)) {
        const picker = window.listSelect({
          value: entry["use-skill"] || "",
          options: useSkillOptions.map(([id, label]) => ({
            value: id,
            primary: label || id || "(制限なし)",
            secondary: id || ""
          })),
          onChange: (v) => {
            if (!v) delete entry["use-skill"];
            else entry["use-skill"] = v;
          }
        });
        grid.appendChild(fieldRow("use-skill", picker));
      } else {
        grid.appendChild(fieldRow("use-skill", skillSelect(entry["use-skill"], (v) => {
          if (!v) delete entry["use-skill"];
          else entry["use-skill"] = v;
        })));
      }

      // 専用職業 (use-role): この職業に就いているときだけ装備/使用できる。
      // 使用スキルとは独立した条件で、両方書けば両方満たす必要がある(UseRequirementService)。
      grid.appendChild(fieldRow("use-role", roleSelect(entry["use-role"], (v) => {
        if (!v) delete entry["use-role"];
        else entry["use-role"] = v;
      })));

      // 品質基準値 (quality-mode-offset): クラフト品質modeのオフセット。負値可。
      // 空欄=キー削除(デフォルト=クラフトユーザの品質ポイント通り)。
      // 「使用スキル」の右隣に配置 (field-grid は auto-fill の複数列レイアウトのため、
      // 直後に置くことで同じ行に並ぶ)。
      const qualityModeField = fieldRow("quality-mode-offset", window.numberInput(
        entry["quality-mode-offset"] != null ? entry["quality-mode-offset"] : null,
        (v) => {
          if (v == null || Number.isNaN(v)) delete entry["quality-mode-offset"];
          else entry["quality-mode-offset"] = Math.trunc(v);
        },
        { int: true }
      ));
      qualityModeField.classList.add("quality-mode-field");
      grid.appendChild(qualityModeField);

      return h("div", { class: "sub-section" }, [
        window.subTitleEl("使用制限・オフハンド", "使用可能レベルと武器種/装備種スキル。カタログではなく item-stats で設定します。"),
        grid
      ]);
    }

    // 触媒/魔導書/スレッド固有フィールド (通常の fixed/per-quality/random に加えて)。
    function categoryExtraFields(entry) {
      if (activeCat === "spellbook") {
        const grid = h("div", { class: "field-grid" });
        grid.appendChild(fieldRow("max-glyphs", window.numberInput(entry["max-glyphs"], (v) => {
          if (v == null) delete entry["max-glyphs"]; else entry["max-glyphs"] = Math.trunc(v);
        }, { int: true })));
        grid.appendChild(fieldRow("max-slots", window.numberInput(entry["max-slots"], (v) => {
          if (v == null) delete entry["max-slots"]; else entry["max-slots"] = Math.trunc(v);
        }, { int: true })));
        grid.appendChild(fieldRow("max-glyph-tier", window.numberInput(entry["max-glyph-tier"], (v) => {
          if (v == null) delete entry["max-glyph-tier"]; else entry["max-glyph-tier"] = Math.trunc(v);
        }, { int: true })));
        return h("div", { class: "sub-section" }, [
          window.subTitleEl("魔導書固有", "グリフ設定可能数・魔法保存数・設定可能グリフの最大ティア"),
          grid
        ]);
      }
      if (activeCat === "catalyst") {
        const grid = h("div", { class: "field-grid" });
        grid.appendChild(fieldRow("max-bind-tier", window.numberInput(entry["max-bind-tier"], (v) => {
          if (v == null) delete entry["max-bind-tier"]; else entry["max-bind-tier"] = Math.trunc(v);
        }, { int: true })));
        return h("div", { class: "sub-section" }, [
          window.subTitleEl("触媒固有", "この触媒にバインド可能なスペルの最大グリフティア"),
          grid
        ]);
      }
      if (activeCat === "thread") {
        return renderThreadExtraFields(entry);
      }
      return null;
    }

    function renderThreadExtraFields(entry) {
      if (!entry["set-effects"] || typeof entry["set-effects"] !== "object") {
        // 未設定のままキーを増やさない。UI操作時に materialize。
      }
      const box = h("div", { class: "sub-section" });
      box.appendChild(window.subTitleEl("スレッド固有",
        "セット効果の閾値・ステータス、および暗視などの特殊効果"));

      // ---- セット効果 ----
      const setBox = h("div", { class: "sub-section" });
      setBox.appendChild(h("div", { class: "mini-label", text: "セット効果 (set-effects)" }));
      const se = entry["set-effects"] && typeof entry["set-effects"] === "object" ? entry["set-effects"] : null;
      const thresholds = se && se.thresholds && typeof se.thresholds === "object" ? se.thresholds : {};
      const thrKeys = Object.keys(thresholds).sort((a, b) => Number(a) - Number(b));
      const thrRows = h("div", { class: "pedestal-rows" });
      if (thrKeys.length === 0) {
        thrRows.appendChild(h("div", { class: "empty-hint", text: "セット効果なし。「+ 閾値追加」で N個装備時のボーナスを定義します。" }));
      }
      thrKeys.forEach((tk) => {
        const stats = thresholds[tk] && typeof thresholds[tk] === "object" ? thresholds[tk] : {};
        const card = h("div", { class: "stat-rows indented" });
        card.appendChild(h("div", { class: "stat-row" }, [
          h("span", { class: "mini-label", text: "閾値(個数)" }),
          window.numberInput(Number(tk), (v) => {
            const n = v == null || v < 1 ? 1 : Math.trunc(v);
            const next = String(n);
            if (next === tk) return;
            if (!entry["set-effects"]) entry["set-effects"] = { thresholds: {} };
            if (!entry["set-effects"].thresholds) entry["set-effects"].thresholds = {};
            if (Object.prototype.hasOwnProperty.call(entry["set-effects"].thresholds, next)) {
              alert("同じ閾値が既にあります"); render(); return;
            }
            entry["set-effects"].thresholds[next] = entry["set-effects"].thresholds[tk];
            delete entry["set-effects"].thresholds[tk];
            render();
          }, { int: true }),
          h("button", {
            class: "btn-small danger", type: "button", text: "× 閾値",
            onclick: () => {
              delete entry["set-effects"].thresholds[tk];
              if (Object.keys(entry["set-effects"].thresholds).length === 0) delete entry["set-effects"];
              render();
            }
          })
        ]));
        const statKeys = Object.keys(stats);
        statKeys.forEach((st) => {
          card.appendChild(h("div", { class: "stat-row" }, [
            window.statSelect(st, (nv) => {
              if (!nv || nv === st) return false;
              if (Object.prototype.hasOwnProperty.call(stats, nv)) { alert("同じステが既にあります"); return false; }
              renameKey(stats, st, nv);
              render();
              return true;
            }),
            window.numberInput(stats[st], (v) => { stats[st] = v == null ? 0 : v; }),
            window.statUnitSlot(st),
            h("button", {
              class: "btn-small danger", type: "button", text: "×",
              onclick: () => { delete stats[st]; render(); }
            })
          ]));
        });
        card.appendChild(h("button", {
          class: "btn-small", type: "button", text: "+ セットステ追加",
          onclick: () => {
            const name = pickNewStat(stats);
            stats[name] = 0;
            render();
          }
        }));
        thrRows.appendChild(card);
      });
      thrRows.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 閾値追加",
        onclick: () => {
          if (!entry["set-effects"]) entry["set-effects"] = { thresholds: {} };
          if (!entry["set-effects"].thresholds) entry["set-effects"].thresholds = {};
          let n = 2;
          while (Object.prototype.hasOwnProperty.call(entry["set-effects"].thresholds, String(n))) n++;
          entry["set-effects"].thresholds[String(n)] = {};
          render();
        }
      }));
      setBox.appendChild(thrRows);
      box.appendChild(setBox);

      // ---- 特殊効果 ----
      const fxBox = h("div", { class: "sub-section" });
      fxBox.appendChild(h("div", { class: "mini-label", text: "特殊効果 (special-effects)" }));
      const fxList = Array.isArray(entry["special-effects"]) ? entry["special-effects"] : [];
      const fxRows = h("div", { class: "pedestal-rows" });
      if (fxList.length === 0) {
        fxRows.appendChild(h("div", { class: "empty-hint", text: "特殊効果なし。暗視・飛行などをセレクトで追加できます。" }));
      }
      fxList.forEach((fxId, idx) => {
        const known = THREAD_SPECIAL_EFFECTS.find((e) => e.id === fxId);
        fxRows.appendChild(h("div", { class: "stat-row" }, [
          h("span", { class: "form-label", text: known ? `${known.label} (${fxId})` : fxId }),
          h("button", {
            class: "btn-small danger", type: "button", text: "×",
            onclick: () => {
              const arr = Array.isArray(entry["special-effects"]) ? entry["special-effects"] : [];
              arr.splice(idx, 1);
              if (arr.length === 0) delete entry["special-effects"];
              else entry["special-effects"] = arr;
              render();
            }
          })
        ]));
      });
      const addSel = h("select", { class: "field-input" });
      addSel.appendChild(h("option", { value: "", text: "特殊効果を選ぶ…" }));
      for (const e of THREAD_SPECIAL_EFFECTS) {
        if (fxList.includes(e.id)) continue;
        addSel.appendChild(h("option", { value: e.id, text: `${e.label} (${e.id})` }));
      }
      fxRows.appendChild(h("div", { class: "form-actions" }, [
        addSel,
        h("button", {
          class: "btn-small", type: "button", text: "+ 追加",
          onclick: () => {
            const id = addSel.value;
            if (!id) return;
            if (!Array.isArray(entry["special-effects"])) entry["special-effects"] = [];
            if (!entry["special-effects"].includes(id)) entry["special-effects"].push(id);
            render();
          }
        })
      ]));
      fxBox.appendChild(fxRows);
      box.appendChild(fxBox);
      return box;
    }

    function renderEntry(key) {
      const entry = working.items[key];
      if (entry.fixed == null) entry.fixed = {};
      if (entry["per-quality"] == null) entry["per-quality"] = {};
      if (entry.random == null) entry.random = {};

      const hashIdx = key.indexOf("#");
      const material = hashIdx >= 0 ? key.slice(0, hashIdx) : key;
      const cmd = hashIdx >= 0 ? key.slice(hashIdx + 1) : "";
      const catalogMatch = findCatalogForStatsKey(key);
      const matJa = (window.LABELS && typeof window.LABELS.materialLabel === "function")
        ? window.LABELS.materialLabel(material)
        : ((window.MATERIAL_LABELS && window.MATERIAL_LABELS[material]) || "");
      const plainDisplay = catalogMatch
        ? (window.stripDisplayNamePlain(catalogMatch.displayName) || catalogMatch.id)
        : (matJa || material || "—");
      const idLabel = catalogMatch ? catalogMatch.id : material;
      const pinnedTab = typeof window.getItemDisplayTab === "function"
        ? window.getItemDisplayTab(working, key, material)
        : activeCat;
      const tabLabel = tabLabelFor(pinnedTab, ITEM_STATS_CATEGORIES);
      const nestLabel = nestLabelForItem(key);
      const tabLocked = ITEM_STATS_LOCKED_TABS.has(activeCat) || ITEM_STATS_LOCKED_TABS.has(pinnedTab);
      const isVanillaKey = !catalogMatch;

      function commitKey(newMat, newCmd) {
        const normMat = String(newMat || "").trim().toUpperCase().replace(/[^A-Z0-9_]/g, "");
        if (normMat === "") {
          alert("Material（バニラアイテムID）を入力してください");
          render();
          return;
        }
        const cmdStr = String(newCmd == null ? "" : newCmd).trim();
        let normCmd = "";
        if (cmdStr !== "") {
          const n = Number(cmdStr);
          if (Number.isInteger(n) && n >= 0) normCmd = String(n);
        }
        const newKey = normCmd !== "" ? `${normMat}#${normCmd}` : normMat;
        if (newKey === key) { render(); return; }
        if (Object.prototype.hasOwnProperty.call(working.items, newKey)) {
          alert("同じキーが既に存在します（重複）");
          render();
          return;
        }
        renameKey(working.items, key, newKey);
        if (cardStatFilters[key]) {
          cardStatFilters[newKey] = cardStatFilters[key];
          delete cardStatFilters[key];
        }
        for (const secKey of ["fixed", "per-quality", "random"]) {
          const ok = rowOrderKey(key, secKey);
          if (sectionRowOrders[ok]) {
            sectionRowOrders[rowOrderKey(newKey, secKey)] = sectionRowOrders[ok];
            delete sectionRowOrders[ok];
          }
        }
        if (cardFilterOverrides.has(key)) {
          cardFilterOverrides.delete(key);
          cardFilterOverrides.add(newKey);
        }
        if (typeof window.renameItemDisplayTab === "function") {
          window.renameItemDisplayTab(working, key, newKey);
        }
        if (typeof window.setItemDisplayTab === "function") {
          window.setItemDisplayTab(working, newKey, activeCat);
        }
        if (useEditorMeta && typeof window.renameEditorCategoryItem === "function") {
          window.renameEditorCategoryItem(working, editorCategoryKey, key, newKey);
        }
        if (expanded.has(key)) { expanded.delete(key); expanded.add(newKey); }
        render();
      }

      function commitKeyFromCatalog(candidate) {
        if (!candidate) return;
        commitKey(
          candidate.material,
          candidate.cmd != null && candidate.cmd !== "" ? candidate.cmd : ""
        );
      }

      let matValue = material;
      const matInput = window.materialInput(material, "material-list", (v) => {
        matValue = v;
        commitKey(v, cmdInput.value);
      });
      const cmdInput = h("input", {
        class: "field-input num", type: "number", step: "1",
        value: cmd, placeholder: "CMD(任意・バニラは空)"
      });
      cmdInput.addEventListener("change", () => commitKey(matValue || matInput.value, cmdInput.value));

      const catalogSuggest = typeof window.catalogItemSuggest === "function"
        ? window.catalogItemSuggest(catalogMatch ? catalogMatch.id : "", catalogCandidates, (c) => {
          if (c) commitKeyFromCatalog(c);
        }, { placeholder: "カタログから選択（任意）" })
        : null;

      const displayReadonly = h("input", {
        class: "field-input is-readonly", type: "text", disabled: true,
        value: plainDisplay === "—" ? "" : plainDisplay,
        title: catalogMatch ? "カタログの表示名（参照）" : "バニラMaterialの日本語名（参照）"
      });

      const editChildren = [];
      if (catalogSuggest) {
        editChildren.push(
          h("span", { class: "entry-key-label", text: "アイテム", title: "カタログIDまたは表示名で検索・切替" }),
          catalogSuggest
        );
      }
      editChildren.push(
        h("span", { class: "mini-label", text: "表示名" }),
        displayReadonly,
        h("span", { class: "entry-key-label", text: "Material", title: "バニラのアイテムID (org.bukkit.Material)" }),
        matInput,
        h("span", { class: "entry-hash", text: "#" }),
        cmdInput,
        h("span", { class: "mini-label", text: "キー" }),
        h("span", { class: "entry-sum-id", text: key, title: "MATERIAL または MATERIAL#CMD" })
      );
      if (isVanillaKey) {
        editChildren.push(h("span", { class: "entry-sum-meta", text: "バニラ" }));
      }
      if (!tabLocked && typeof window.renderItemTabSelect === "function") {
        const tabOpts = ITEM_STATS_CATEGORIES.filter(([id]) => !ITEM_STATS_LOCKED_TABS.has(id));
        editChildren.push(window.renderItemTabSelect(working, key, material, tabOpts, () => {
          refreshListPreserveScroll(renderListOnly);
        }));
      } else if (tabLocked) {
        editChildren.push(renderLockedTabLabel(pinnedTab || activeCat));
      }
      if (useEditorMeta && typeof window.renderEditorCategorySelect === "function") {
        editChildren.push(window.renderEditorCategorySelect(working, editorCategoryKey, key, () => {
          refreshListPreserveScroll(renderListOnly);
        }));
      }
      editChildren.push(
        h("div", { class: "spacer" }),
        h("button", {
          class: "btn-small", type: "button", text: "複製",
          onclick: () => {
            const srcMat = keyMaterial(key);
            let n = 1, copy = `${srcMat}#${n}`;
            while (Object.prototype.hasOwnProperty.call(working.items, copy)) copy = `${srcMat}#${++n}`;
            working.items[copy] = JSON.parse(JSON.stringify(working.items[key]));
            if (typeof window.setItemDisplayTab === "function") {
              window.setItemDisplayTab(working, copy, activeCat);
            }
            // 複製元と同じネストカテゴリへ。元が無所属のときだけ通常の追加と同じ扱い。
            if (useEditorMeta && typeof window.duplicateItemEditorCategory === "function") {
              window.duplicateItemEditorCategory(working, editorCategoryKey, key, copy);
            }
            if (useEditorMeta && typeof window.appendEditorOrder === "function") {
              window.appendEditorOrder(working, editorCategoryKey, copy);
            }
            render();
          }
        }),
        h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => {
            delete working.items[key];
            if (typeof window.removeItemDisplayTab === "function") {
              window.removeItemDisplayTab(working, key);
            }
            if (useEditorMeta && typeof window.removeEditorCategoryItem === "function") {
              window.removeEditorCategoryItem(working, editorCategoryKey, key);
            }
            render();
          }
        })
      );

      const summaryChildren = [
        h("span", { class: "entry-sum-name", text: plainDisplay }),
        h("span", { class: "entry-sum-id", text: idLabel }),
        h("span", { class: "entry-sum-meta", text: tabLabel }),
        h("span", { class: "entry-sum-meta", text: nestLabel })
      ];
      if (isVanillaKey) {
        summaryChildren.push(h("span", { class: "entry-sum-meta", text: "バニラ" }));
      }
      const aliasNote = statsKeyAliases[key];
      if (aliasNote && aliasNote.length > 1) {
        summaryChildren.push(h("span", {
          class: "entry-sum-meta",
          text: `別名: ${aliasNote.filter((id) => id !== idLabel).join(", ") || aliasNote.join(", ")}`,
          title: "同じ MATERIAL#CMD に複数のカタログIDが存在します"
        }));
      }

      const head = [
        h("div", { class: "entry-collapse-summary" }, summaryChildren),
        h("div", { class: "entry-collapse-edit" }, editChildren)
      ];

      const bodyChildren = [itemLevelFields(entry)];
      const extras = categoryExtraFields(entry);
      if (extras) bodyChildren.push(extras);
      // 表示ステータス絞り込みは固定ステの1つ上の行へ（renderStatBlocks 内）。
      bodyChildren.push(...renderStatBlocks(entry, key));

      const card = window.collapsibleCard(head, bodyChildren, {
        expanded: expanded.has(key),
        onToggle: (open) => { if (open) expanded.add(key); else expanded.delete(key); },
        dragId: useEditorMeta ? key : undefined
      });
      card.dataset.entryKey = key.toLowerCase();
      card.dataset.entryRawKey = key;
      card.dataset.catalogId = catalogMatch ? catalogMatch.id : "";
      card.dataset.displayPlain = plainDisplay === "—" ? "" : plainDisplay.toLowerCase();
      return card;
    }

    function addStat(target, defVal, allowedFn) {
      const name = pickNewStat(target, allowedFn);
      target[name] = defVal;
    }
    function addRandomStat(target, allowedFn) {
      const name = pickNewStat(target, allowedFn);
      target[name] = { min: 0, max: 0 };
    }
    // チェックされたカテゴリのステを候補にする (A4)。該当が無ければ全候補にフォールバック。
    // 耐久値は常に末尾候補。
    function pickNewStat(target, allowedFn) {
      const filter = typeof allowedFn === "function" ? allowedFn : (s) => statAllowed(s);
      const candidates = orderStatCandidates(
        statList().filter((c) => !Object.prototype.hasOwnProperty.call(target, c))
      );
      const allowed = candidates.filter(filter);
      const ordered = allowed.length ? allowed : candidates;
      if (ordered.length) return ordered[0];
      let name = "new-stat", i = 1;
      while (Object.prototype.hasOwnProperty.call(target, name)) name = `new-stat-${i++}`;
      return name;
    }

    /**
     * カテゴリ別 fallback を保存形に整える。旧形式(fixed直下)は読み取り互換のため残す。
     * {@code isOverrides} = true (fallback-overrides 用) のときは全キーをカテゴリ節として扱う —
     * 任意カテゴリのIDはユーザー命名のスラッグなので "random"/"fixed" 等と衝突しても旧形式扱いしない。
     */
    function pruneFallbackForSave(fb, isOverrides) {
      const out = {};
      for (const [key, val] of Object.entries(fb || {})) {
        if (!isOverrides && LEGACY_FALLBACK_KEYS.has(key)) {
          // 旧形式キーは下の legacyProbe 経路で処理する(空なら落とす)
          continue;
        }
        if (!val || typeof val !== "object") continue;
        const fixed = val.fixed && typeof val.fixed === "object" ? { ...val.fixed } : {};
        const loreDefault = val["lore-default"] && typeof val["lore-default"] === "object"
          ? { ...val["lore-default"] } : {};
        // lore-default は fixed に無いキーを落とす / false を落とす
        for (const sk of Object.keys(loreDefault)) {
          if (!Object.prototype.hasOwnProperty.call(fixed, sk) || loreDefault[sk] !== true) {
            delete loreDefault[sk];
          }
        }
        // 手編集YAML保全: UIが扱わない未知キーはそのまま持ち越す(fixed/lore-default だけ整形)。
        const pruned = {};
        for (const [uk, uv] of Object.entries(val)) {
          if (uk === "fixed" || uk === "lore-default") continue;
          pruned[uk] = uv;
        }
        if (Object.keys(fixed).length) pruned.fixed = fixed;
        if (Object.keys(loreDefault).length) pruned["lore-default"] = loreDefault;
        if (Object.keys(pruned).length) out[key] = pruned;
      }
      // 旧形式 (fixed/per-quality/random 直下等) が残っていれば後方互換で保持。
      // probe対象は LEGACY_FALLBACK_KEYS から導出する(手書きリストだと use-skill 等の取り漏れで
      // 保存時にサイレント欠落する — レビュー指摘#1)。
      if (!isOverrides) {
        const legacyProbe = {};
        for (const k of LEGACY_FALLBACK_KEYS) {
          if (Object.prototype.hasOwnProperty.call(fb, k)) legacyProbe[k] = fb[k];
        }
        if (Object.keys(legacyProbe).length) {
          const prunedLegacy = pruneEntries({ fallback: legacyProbe }, ["fixed", "random", "per-quality"]).fallback;
          if (prunedLegacy && typeof prunedLegacy === "object") {
            Object.assign(out, prunedLegacy);
          }
        }
      }
      return Object.keys(out).length ? out : undefined;
    }

    render();
    // 保存時は表示用に空補完した fixed/random/per-quality を省いて出力する (元に無かったキーを増やさない)。
    // fallback も同じ形なので同じ pruneEntries ロジックを (単一エントリ用に) 再利用する。
    return {
      element: root,
      getData: () => {
        const items = dropEmptyItemProfiles(pruneEntries(working.items, [
          "fixed", "random", "per-quality", "special-effects", "multipliers"
        ]));
        for (const entry of Object.values(items)) {
          if (!entry || typeof entry !== "object") continue;
          // 乗算モード: 空レイヤ/空セクションを落とす (multipliers 自体が空なら pruneEntries が削除済み)。
          const ml = entry.multipliers;
          if (ml && typeof ml === "object") {
            for (const [layerId, layer] of Object.entries(ml)) {
              if (!layer || typeof layer !== "object") { delete ml[layerId]; continue; }
              for (const secKey of ["fixed", "per-quality", "random"]) {
                if (layer[secKey] && typeof layer[secKey] === "object" && isEmptyObject(layer[secKey])) {
                  delete layer[secKey];
                }
              }
              if (isEmptyObject(layer)) delete ml[layerId];
            }
            if (isEmptyObject(ml)) delete entry.multipliers;
          }
          const se = entry["set-effects"];
          if (se && typeof se === "object") {
            if (se.thresholds && typeof se.thresholds === "object") {
              for (const [tk, stats] of Object.entries(se.thresholds)) {
                if (isEmptyObject(stats)) delete se.thresholds[tk];
              }
              if (isEmptyObject(se.thresholds)) delete se.thresholds;
            }
            if (isEmptyObject(se)) delete entry["set-effects"];
          }
          const adv = entry.advanced;
          if (adv && typeof adv === "object") {
            // advanced ブロックがある＝付与ランダム化ON（UIトグルなし）
            if (adv["randomize-grants"] !== true) adv["randomize-grants"] = true;
            if (adv["grant-chances"] && typeof adv["grant-chances"] === "object" && isEmptyObject(adv["grant-chances"])) {
              delete adv["grant-chances"];
            }
          }
        }
        const out = { ...working, items };
        if (out.fallback && typeof out.fallback === "object") {
          const prunedFb = pruneFallbackForSave(out.fallback);
          if (prunedFb) out.fallback = prunedFb;
          else delete out.fallback;
        }
        // 任意カテゴリのフォールバック上書き: 空カードは落とす (チェックONのまま何も設定していない等)。
        // キーはユーザー命名のカテゴリIDなので旧形式キー処理はスキップ(isOverrides=true)。
        if (out["fallback-overrides"] && typeof out["fallback-overrides"] === "object") {
          const prunedOv = pruneFallbackForSave(out["fallback-overrides"], true);
          if (prunedOv) out["fallback-overrides"] = prunedOv;
          else delete out["fallback-overrides"];
        }
        if (typeof window.pruneEditorUiState === "function") window.pruneEditorUiState(out);
        return out;
      },
      setActiveCategory: (cat) => {
        if (!cat) return;
        activeCat = cat;
        if (window.ITEM_STATS_USE_SKILLS && window.ITEM_STATS_USE_SKILLS[cat]) {
          useSkillOptions = window.ITEM_STATS_USE_SKILLS[cat];
        }
        render();
      },
      setUseSkillOptions: (opts) => {
        useSkillOptions = opts;
        render();
      },
      setHubMode: (on) => {
        hideCategoryTabs = !!on;
        render();
      },
      // ネスト「すべて」切替でフォールバックカードの出し分けも更新するためフル描画
      rerender: () => render(),
      rerenderList: () => renderListOnly()
    };
  };

  // ============================================================
  // catalog.yml
  // ============================================================
  // カテゴリ(武器/防具/ツール/補助/触媒/魔導書/スレッド)のサブタブへ表示上のみ分割する。
  // `_editor.itemTabs` の明示ピンを優先し、無ければ material から推論する
  // (catalog.yml のスキーマ・保存構造は不変。ピンは `_editor` メタのみ)。
  // ※「素材」は materials.yml 側。ここでの other は矢・汎用など補助枠。
  const CATALOG_CATEGORIES = [
    ["weapon", "武器"], ["armor", "防具"], ["tool", "ツール"], ["other", "補助"],
    ["catalyst", "触媒"], ["spellbook", "魔導書"], ["thread", "スレッド"]
  ];
  // ars-forms.js (素材タブ) がカタログ行きの移動先セレクトを組むのに参照する。
  window.CATALOG_CATEGORIES = CATALOG_CATEGORIES;

  // ---- catalog.yml items.<id>.recipe / recipes (クラフトレシピ・任意) ----
  // TF本体の catalog.yml recipe スキーマに一致させる。1アイテムに複数レシピを設定可能。
  // 保存形は正規形: 0件=キーなし / 1件=recipe:(単発マップ) / 2件以上=recipes:(マップ配列)。
  const CATALOG_RECIPE_TYPES = ["shaped", "shapeless"];
  // inventory = インベントリ内2×2クラフト(手持ちで作れる小型レシピ)。workbench(作業台3×3専用)とは
  // グリッドサイズのみ異なる (type/shaped/shapeless は共通)。
  const CATALOG_RECIPE_METHODS = ["workbench", "ritual", "combine", "netherite", "inventory"];
  const CATALOG_RECIPE_METHOD_LABELS = {
    workbench: "作業台 (workbench)",
    ritual: "儀式 (ritual)",
    combine: "合成 (combine)",
    netherite: "ネザライト化 (netherite)",
    inventory: "インベントリ (2×2)"
  };

  // method から配置グリッドの一辺サイズを返す (workbench/ritual等は3×3、inventoryは2×2)。
  function catalogRecipeGridSize(method) {
    return method === "inventory" ? 2 : 3;
  }

  // shape の1行を size 文字ちょうどに揃える(空白パディング/切り詰め)。size省略時は3(workbench既定)。
  function padShapeRow(row, size) {
    const n = size || 3;
    const s = row == null ? "" : String(row);
    return (s + " ".repeat(n)).slice(0, n);
  }

  // recipe を shaped の既定形へ整形する (in-place。既存 shape/ingredients は保つ)。
  // size省略時は3×3(workbench)。inventory(2×2)へ切り替えた場合ははみ出た行/列を切り詰める。
  function ensureShapedRecipe(recipe, size) {
    const n = size || 3;
    const shape = Array.isArray(recipe.shape) ? recipe.shape.slice(0, n) : [];
    while (shape.length < n) shape.push(" ".repeat(n));
    recipe.shape = shape.map((row) => padShapeRow(row, n));
    if (!recipe.ingredients || typeof recipe.ingredients !== "object" || Array.isArray(recipe.ingredients)) {
      recipe.ingredients = {};
    }
  }

  // recipe を shapeless の既定形へ整形する (in-place。ingredients は Material名の配列)。
  function ensureShapelessRecipe(recipe) {
    if (!Array.isArray(recipe.ingredients)) recipe.ingredients = [];
  }

  // shape 文字列中で使われている記号を出現順(重複排除・空白除く)で返す。
  function shapeSymbols(shape) {
    const seen = [];
    for (const row of shape) {
      for (const ch of String(row)) {
        if (ch !== " " && !seen.includes(ch)) seen.push(ch);
      }
    }
    return seen;
  }

  // reversible (解凍を許可) の付与条件判定: shaped は shape上の全非空スロットの参照値、
  // shapeless は全ingredientが、それぞれ同一Material/list:/custom:参照であることを求める。
  // (schema.js側の catalogRecipeIngredientsAllSame と同じ判定基準。空/未設定は「同一」とみなさない。)
  function catalogRecipeIngredientsAllSame(recipe) {
    const type = recipe.type || "shaped";
    if (type === "shapeless") {
      const arr = Array.isArray(recipe.ingredients) ? recipe.ingredients : [];
      if (arr.length === 0) return false;
      return arr.every((v) => typeof v === "string" && v !== "" && v === arr[0]);
    }
    const ing = (recipe.ingredients && typeof recipe.ingredients === "object" && !Array.isArray(recipe.ingredients))
      ? recipe.ingredients : {};
    const symbols = shapeSymbols(Array.isArray(recipe.shape) ? recipe.shape : []);
    if (symbols.length === 0) return false;
    const values = symbols.map((s) => ing[s]);
    return values.every((v) => typeof v === "string" && v !== "" && v === values[0]);
  }

  // 旧 any:<MATERIAL> トークン検出時の周知トースト (ページ読み込みごとに1回だけ)。
  // ハードコード互換シリーズは廃止済みで、any: は素のMaterial指定として扱われる。
  let legacyAnyNotified = false;
  function notifyLegacyAnyOnce() {
    if (legacyAnyNotified) return;
    legacyAnyNotified = true;
    if (typeof window.toast === "function") {
      window.toast("旧 any:互換トークンを検出しました。現在は素のMaterial指定として扱われます"
        + " (保存すると any: は除去されます)。互換が必要な場合は「互換リスト」を割り当ててください。", "warn");
    }
  }

  // レシピ素材1件分の入力コントロール: Material入力 + 互換リストボタン + 日本語ヒント + 未設定警告。
  // 値は "OAK_LOG" 等の Material名 / "list:<id>" (素材互換リスト) / "custom:id" のトークン。
  // 「互換リスト」ボタン (Material入力の右隣) で material-lists.yml のリストを選択/作成でき、
  // 選択中はリストチップを表示して list:<id> で保存する。旧 any:<MATERIAL> トークンは
  // 読み込み時に素のMaterial指定へ降格する (ハードコード互換シリーズは廃止)。
  function ingredientMaterialControl(value, onChange, opts) {
    const options = opts && typeof opts === "object" ? opts : {};
    const raw = value == null ? "" : String(value);
    const state = { base: "", listId: null };
    if (/^list:/i.test(raw)) {
      state.listId = raw.slice(5).trim() || null;
    } else if (/^any:/i.test(raw)) {
      state.base = raw.slice(4).trim(); // legacy any: → 素のMaterialへ降格
      notifyLegacyAnyOnce();
    } else {
      state.base = raw;
    }

    const wrap = h("span", { class: "ingredient-material-control" });
    const matHint = window.materialHintEl(state.base);
    const warn = h("span", { class: "empty-hint", text: "(警告: この記号のMaterialが未設定です)" });
    const listChip = h("span", { class: "ingredient-list-chip" });
    const listBtn = h("button", {
      class: "btn-small", type: "button",
      title: "素材互換リストを割り当てます。リストのどのMaterialでも素材として認められます (リストの作成/編集も可能)"
    });

    function commit() {
      onChange(state.listId ? "list:" + state.listId : state.base);
    }

    function refresh() {
      const hasList = !!state.listId;
      matInput.style.display = hasList ? "none" : "";
      matHint.style.display = hasList ? "none" : "";
      listChip.style.display = hasList ? "" : "none";
      listBtn.textContent = hasList ? "互換リスト変更" : "互換リスト";
      listBtn.classList.toggle("primary", hasList);
      if (hasList) {
        const id = state.listId;
        listChip.textContent = `互換リスト: ${id}`;
        listChip.title = "";
        if (window.MaterialListsUI) {
          window.MaterialListsUI.describe(id).then((d) => {
            if (state.listId !== id) return; // 非同期解決中に変更されたら破棄
            if (d && d.status === "ok") {
              listChip.textContent = `互換リスト「${d.label}」(${d.count}種)`;
              const L = window.LABELS;
              listChip.title = d.materials.map((m) => {
                const ja = L && typeof L.materialLabel === "function" ? L.materialLabel(m) : "";
                return ja ? `${ja} (${m})` : m;
              }).join("\n");
            } else if (d && d.status === "error") {
              // 通信/認証エラー: リスト不存在と誤断定しない。
              listChip.textContent = `互換リスト: ${id} (読み込み失敗)`;
              listChip.title = "互換リストの読み込みに失敗しました。エディタサーバとの接続を確認してください。";
            } else {
              listChip.textContent = `互換リスト: ${id} (未定義!)`;
              listChip.title = "このIDのリストは material-lists.yml に存在しません。参照レシピは起動時に無効化されます。";
            }
          });
        }
      } else {
        matHint.update(state.base);
      }
      warn.style.display = (state.base || hasList) ? "none" : (options.warnWhenEmpty ? "" : "none");
    }

    listBtn.addEventListener("click", () => {
      if (!window.MaterialListsUI) return;
      window.MaterialListsUI.openManager({
        selectedListId: state.listId,
        onSelect: (id) => {
          state.listId = id || null;
          refresh();
          commit();
        },
        // 選択せずリスト編集だけして閉じた場合もチップの種数/ツールチップを最新化する。
        onClose: () => refresh()
      });
    });

    const matInput = window.materialInput(state.base, "material-list", (v) => {
      state.base = v == null ? "" : String(v);
      refresh();
      commit();
    }, { allowCustom: true, getCustomCandidates: options.getCustomCandidates });

    wrap.appendChild(matInput);
    wrap.appendChild(listChip);
    wrap.appendChild(listBtn);
    wrap.appendChild(matHint);
    if (options.warnWhenEmpty) wrap.appendChild(warn);
    refresh();
    return wrap;
  }

  // 配置グリッド(記号1文字/セル) + 記号→Material対応表。size=3(workbench等)/2(inventory)。
  function renderShapedRecipeGrid(recipe, rerenderEntry, size) {
    const n = size || 3;
    const wrap = h("div", { class: "recipe-shaped-wrap" });
    wrap.appendChild(h("div", { class: "mini-label", text: `配置 (${n}×${n}・各セルに記号1文字、空欄=空きマス)` }));
    // 入力欄の既定幅に引っ張られない正方形グリッド。2×2 は行数も2に固定する。
    const grid = h("div", {
      class: "craft-grid",
      style: `grid-template-columns: repeat(${n}, 48px); grid-template-rows: repeat(${n}, 48px);`
    });
    for (let r = 0; r < n; r++) {
      for (let c = 0; c < n; c++) {
        const rowStr = padShapeRow(recipe.shape[r], n);
        const ch = rowStr[c];
        const cellInput = h("input", {
          class: "field-input craft-cell-input", type: "text", maxlength: "1", spellcheck: "false",
          value: ch === " " ? "" : ch
        });
        cellInput.addEventListener("change", (e) => {
          const v = (e.target.value || " ").slice(0, 1);
          const chars = padShapeRow(recipe.shape[r], n).split("");
          chars[c] = v === "" ? " " : v;
          recipe.shape[r] = chars.join("");
          rerenderEntry();
        });
        grid.appendChild(h("div", { class: "craft-cell" }, [cellInput]));
      }
    }
    wrap.appendChild(grid);

    const symbols = shapeSymbols(recipe.shape);
    const table = h("div", { class: "recipe-ingredient-table" });
    table.appendChild(h("div", { class: "mini-label", text: "記号 → Material 対応表" }));
    if (symbols.length === 0) {
      table.appendChild(h("div", { class: "empty-hint", text: "配置に記号を入力すると、ここに対応するMaterial入力欄が現れます。" }));
    }
    for (const sym of symbols) {
      const row = h("div", { class: "stat-row" });
      row.appendChild(h("span", { class: "entry-hash", text: sym }));
      row.appendChild(ingredientMaterialControl(recipe.ingredients[sym], (v) => {
        recipe.ingredients[sym] = v;
      }, { warnWhenEmpty: true }));
      table.appendChild(row);
    }
    wrap.appendChild(table);
    return wrap;
  }

  // shapeless: Material名の可変リスト。max省略時は9個(workbench等)。inventoryは2×2=4個。
  function renderShapelessRecipeRows(recipe, rerenderEntry, max) {
    const maxCount = max || 9;
    const box = h("div", { class: "recipe-shapeless-wrap" });
    box.appendChild(h("div", { class: "mini-label", text: `素材 (順不同・最大${maxCount}個)` }));
    const rows = h("div", { class: "pedestal-rows" });
    recipe.ingredients.forEach((mat, idx) => {
      const row = h("div", { class: "stat-row" });
      row.appendChild(ingredientMaterialControl(mat, (v) => {
        recipe.ingredients[idx] = v;
      }));
      row.appendChild(h("button", { class: "btn-small danger", type: "button", text: "×", onclick: () => { recipe.ingredients.splice(idx, 1); rerenderEntry(); } }));
      rows.appendChild(row);
    });
    box.appendChild(rows);
    if (recipe.ingredients.length >= maxCount) {
      box.appendChild(h("div", { class: "empty-hint", text: `shapeless の素材は最大${maxCount}個までです。` }));
    } else {
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 素材追加",
        onclick: () => { recipe.ingredients.push(""); rerenderEntry(); }
      }));
    }
    return box;
  }

  // recipe セクション本体 (レシピカードのリスト + 追加/削除)。
  // rerenderEntry() は構造変更(追加/削除/type切替/行の追加削除/記号変更)時に呼ぶ再描画コールバック。
  // itemsMap は catalog の items（候補サジェスト用）。buildCatalogForm 外なので working クロージャは使わない。
  function recipeItemInput(value, onChange, itemsMap) {
    const catalogEntries = [];
    for (const [id, ent] of Object.entries(itemsMap || {})) {
      catalogEntries.push({
        id,
        label: (typeof window.stripDisplayNamePlain === "function"
          ? window.stripDisplayNamePlain(ent && ent["display-name"])
          : (ent && ent["display-name"])) || id
      });
    }
    if (typeof window.setCustomItemCandidates === "function") {
      window.setCustomItemCandidates(catalogEntries, { replace: false });
    }
    return window.materialInput(value, "material-list", onChange, {
      allowCustom: true,
      getCustomCandidates: () => window.CUSTOM_ITEM_CANDIDATES || []
    });
  }

  function renderCatalogRecipeSection(entry, rerenderEntry, itemsMap, entryId, opts) {
    const items = itemsMap && typeof itemsMap === "object" ? itemsMap : {};

    // recipe:(単発マップ) と recipes:(マップ配列) を1つの作業配列に正規化する。
    // 保存形は常に正規形へ書き戻す: 0件=両キーなし / 1件=recipe: のみ / 2件以上=recipes: のみ。
    const list = [];
    if (entry.recipe !== undefined && entry.recipe !== null
        && typeof entry.recipe === "object" && !Array.isArray(entry.recipe)) {
      list.push(entry.recipe);
    }
    if (Array.isArray(entry.recipes)) {
      for (const r of entry.recipes) {
        if (r !== null && typeof r === "object" && !Array.isArray(r)) list.push(r);
      }
    }
    function writeBack() {
      if (list.length === 0) {
        delete entry.recipe;
        delete entry.recipes;
      } else if (list.length === 1) {
        entry.recipe = list[0];
        delete entry.recipes;
      } else {
        delete entry.recipe;
        entry.recipes = list.slice();
      }
    }
    writeBack();

    const box = h("div", { class: "sub-section recipe-section" });
    box.appendChild(window.subTitleEl("クラフトレシピ (recipe / recipes・任意)",
      "作業台 / 儀式 / 合成 / ネザライト化。1アイテムに複数レシピを設定できます(例: 圧縮+分解、単発+まとめ生産の儀式)。"));

    list.forEach((recipe, idx) => {
      const card = h("div", { class: "sub-section recipe-card" });
      const head = h("div", { class: "stat-row" });
      head.appendChild(h("div", { class: "sub-title", text: `レシピ ${idx + 1}` }));
      head.appendChild(h("button", {
        class: "btn-small danger", type: "button", text: "このレシピを削除",
        onclick: () => { list.splice(idx, 1); writeBack(); rerenderEntry(); }
      }));
      card.appendChild(head);
      card.appendChild(renderCatalogRecipeCard(recipe, rerenderEntry, items, entryId, opts));
      box.appendChild(card);
    });
    if (list.length === 0) {
      box.appendChild(h("div", { class: "empty-hint", text: "レシピは未設定です。" }));
    }
    box.appendChild(h("button", {
      class: "btn-small", type: "button", text: "+ レシピを追加",
      onclick: () => {
        const fresh = { method: "workbench", type: "shaped" };
        ensureShapedRecipe(fresh);
        list.push(fresh);
        writeBack();
        rerenderEntry();
      }
    }));
    return box;
  }

  // 1レシピ分の編集フォーム (method + workbench時 type/配置 or ritual時 コア/台座 等)。
  // recipe オブジェクトをその場で書き換える。構造変更時は rerenderEntry() で再描画。
  function renderCatalogRecipeCard(recipe, rerenderEntry, items, entryId, opts) {
    const box = h("div", { class: "recipe-card-body" });
    if (!recipe.method) {
      if (recipe["core-item"] != null || recipe["pedestal-items"] != null) recipe.method = "ritual";
      else if (recipe["addition-item"] != null || recipe["combine-exp"] != null) recipe.method = "combine";
      else if (recipe["source-item"] != null) recipe.method = "netherite";
      else recipe.method = "workbench";
    }

    // opts.methods で選べる method を制限できる (例: バニラレシピ追加は workbench/inventory のみ)。
    const allowedMethods = (opts && Array.isArray(opts.methods) && opts.methods.length)
      ? CATALOG_RECIPE_METHODS.filter((m) => opts.methods.includes(m))
      : CATALOG_RECIPE_METHODS;
    if (!allowedMethods.includes(recipe.method)) recipe.method = allowedMethods[0];
    const methodSel = h("select", { class: "field-input" });
    for (const m of allowedMethods) {
      const o = h("option", { value: m, text: CATALOG_RECIPE_METHOD_LABELS[m] || m });
      if (recipe.method === m) o.selected = true;
      methodSel.appendChild(o);
    }
    methodSel.addEventListener("change", (e) => {
      recipe.method = e.target.value;
      if (recipe.method === "ritual") {
        recipe.type = "shapeless";
        ensureShapelessRecipe(recipe);
        delete recipe.shape;
        if (!Array.isArray(recipe["pedestal-items"])) recipe["pedestal-items"] = [];
        if (recipe.source == null) recipe.source = 0;
        delete recipe["source-item"];
        delete recipe["addition-item"];
        delete recipe["combine-exp"];
        delete recipe["inherit-source-quality"];
        delete recipe.reversible; // reversible は workbench/inventory 限定
      } else if (recipe.method === "combine") {
        delete recipe.type;
        delete recipe.shape;
        delete recipe.ingredients;
        delete recipe["core-item"];
        delete recipe["pedestal-items"];
        delete recipe.source;
        if (recipe["source-item"] == null) recipe["source-item"] = "";
        if (recipe["addition-item"] == null) recipe["addition-item"] = "";
        if (recipe["combine-exp"] == null) recipe["combine-exp"] = 0;
        if (recipe["inherit-source-quality"] == null) recipe["inherit-source-quality"] = false;
        delete recipe.reversible;
      } else if (recipe.method === "netherite") {
        delete recipe.type;
        delete recipe.shape;
        delete recipe.ingredients;
        delete recipe["core-item"];
        delete recipe["pedestal-items"];
        delete recipe.source;
        delete recipe["addition-item"];
        delete recipe["combine-exp"];
        delete recipe["inherit-source-quality"];
        if (recipe["source-item"] == null) recipe["source-item"] = "";
        delete recipe.reversible;
      } else {
        // workbench/inventory: 他方式からの復帰時も配置UIが必ず出るよう整形する
        // (グリッドサイズのみ異なる。2×2↔3×3切替ではみ出た行/列は ensureShapedRecipe が切り詰める)
        delete recipe["source-item"];
        delete recipe["addition-item"];
        delete recipe["combine-exp"];
        delete recipe["inherit-source-quality"];
        delete recipe["core-item"];
        delete recipe["pedestal-items"];
        delete recipe.source;
        if (recipe.type !== "shaped" && recipe.type !== "shapeless") recipe.type = "shaped";
        const gridSize = catalogRecipeGridSize(recipe.method);
        if (recipe.type === "shapeless") ensureShapelessRecipe(recipe);
        else ensureShapedRecipe(recipe, gridSize);
      }
      rerenderEntry();
    });
    box.appendChild(fieldRow("method", methodSel));

    if (recipe.method === "ritual") {
      recipe.type = "shapeless";
      ensureShapelessRecipe(recipe);
      delete recipe.shape;

      box.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: "儀式ではレシピ種別は不定形(shapeless)固定です。素材は台座アイテムで指定します。"
      }));

      box.appendChild(fieldRow("core-item", recipeItemInput(recipe["core-item"] || "", (v) => {
        setOrDelete(recipe, "core-item", v || null);
      }, items)));
      box.appendChild(fieldRow("source", window.numberInput(recipe.source, (v) => {
        if (v == null) delete recipe.source; else recipe.source = v;
      }, { int: true })));

      const ped = Array.isArray(recipe["pedestal-items"]) ? recipe["pedestal-items"] : [];
      const pedBox = h("div", { class: "sub-section" });
      const pedHead = h("div", { class: "sub-title", text: "台座アイテム (pedestal-items)" });
      const pedCounter = h("span", { class: "mini-label", style: "margin-left:8px;" });
      pedHead.appendChild(pedCounter);
      pedBox.appendChild(pedHead);
      // 儀式エフェクトと同じく "ITEM xN" 形式。個数はステッパーで編集。
      const pedRows = (window.RECIPES_CORE && typeof window.RECIPES_CORE.pedestalToRows === "function")
        ? window.RECIPES_CORE.pedestalToRows(ped)
        : ped.map((s) => {
          const str = String(s == null ? "" : s);
          const m = str.match(/^(.*) x(\d+)$/);
          return m ? { item: m[1], count: parseInt(m[2], 10), raw: str } : { item: str, count: 1, raw: str };
        });
      function writePedRows() {
        const toStr = (row) => {
          if (row.raw != null && !row._dirty) return row.raw;
          return row.count > 1 ? `${row.item} x${row.count}` : row.item;
        };
        if (!Array.isArray(recipe["pedestal-items"])) {
          recipe["pedestal-items"] = [];
        }
        recipe["pedestal-items"].length = 0;
        for (const row of pedRows) recipe["pedestal-items"].push(toStr(row));
      }
      const stepper = (window.RECIPES_UI && window.RECIPES_UI.numberStepper)
        ? window.RECIPES_UI.numberStepper
        : (val, onChange) => window.numberInput(val, onChange, { int: true });
      // 儀式コア周囲の台座リング(距離2)は物理16台。"ITEM xN" はN台分に展開されるため合計で数える。
      const MAX_PED = (window.RECIPES_UI && window.RECIPES_UI.MAX_PEDESTAL_TOTAL) || 16;
      const pedAddBtn = h("button", {
        class: "btn-small", type: "button", text: "+ 台座",
        onclick: () => {
          pedRows.push({ item: "", count: 1, raw: null, _dirty: true });
          writePedRows();
          rerenderEntry();
        }
      });
      function updatePedCounter() {
        const total = (window.RECIPES_UI && typeof window.RECIPES_UI.pedestalTotalOf === "function")
          ? window.RECIPES_UI.pedestalTotalOf(pedRows)
          : pedRows.reduce((s, r) => s + (r && r.count > 0 ? Math.trunc(r.count) : 1), 0);
        pedCounter.textContent = "合計 " + total + "/" + MAX_PED + "台";
        pedCounter.style.color = total > MAX_PED ? "#dc2626" : "";
        pedAddBtn.disabled = total >= MAX_PED;
        pedAddBtn.title = total >= MAX_PED ? "台座リングの上限(" + MAX_PED + "台)に達しています" : "";
      }
      pedRows.forEach((row, idx) => {
        const r = h("div", { class: "pedestal-row" });
        r.appendChild(recipeItemInput(row.item || "", (v) => {
          row.item = v;
          row.raw = null;
          row._dirty = true;
          writePedRows();
        }, items));
        r.appendChild(h("span", { class: "mini-label", text: "×" }));
        r.appendChild(stepper(row.count, (v) => {
          row.count = v == null || v < 1 ? 1 : Math.trunc(v);
          row.raw = null;
          row._dirty = true;
          writePedRows();
          updatePedCounter();
        }, { int: true }));
        r.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "×",
          onclick: () => { pedRows.splice(idx, 1); writePedRows(); rerenderEntry(); }
        }));
        pedBox.appendChild(r);
      });
      pedBox.appendChild(pedAddBtn);
      updatePedCounter();
      box.appendChild(pedBox);
    } else if (recipe.method === "combine") {
      const resultLabel = entryId ? `このアイテム (${entryId})` : "このアイテム";
      box.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: `金床で 合成元(左)＋合成対象(右)＝合成先。合成先は${resultLabel}です。品質引継ぎOFF時は合成元と合成対象の品質平均が出力になります。`
      }));
      box.appendChild(fieldRow("source-item", recipeItemInput(recipe["source-item"] || "", (v) => {
        setOrDelete(recipe, "source-item", v || null);
      }, items), { labelText: "合成元 (source-item)" }));
      box.appendChild(fieldRow("addition-item", recipeItemInput(recipe["addition-item"] || "", (v) => {
        setOrDelete(recipe, "addition-item", v || null);
      }, items), { labelText: "合成対象 (addition-item)" }));
      box.appendChild(h("div", {
        class: "form-field",
        style: "opacity:0.85;"
      }, [
        h("span", { class: "form-label", text: "合成先 (結果)", title: "編集中のカタログエントリ自身が合成結果になります。" }),
        h("div", { class: "field-input", style: "padding:6px 8px;background:var(--panel,#f3f4f6);border-radius:4px;", text: resultLabel })
      ]));
      box.appendChild(fieldRow("combine-exp", window.numberInput(recipe["combine-exp"], (v) => {
        if (v == null) delete recipe["combine-exp"]; else recipe["combine-exp"] = v;
      }, { int: true }), { labelText: "合成経験値 (combine-exp)" }));
      const inherit = window.checkboxInput(!!recipe["inherit-source-quality"], (v) => {
        recipe["inherit-source-quality"] = !!v;
      });
      box.appendChild(h("label", { class: "form-field inline-check" }, [
        inherit,
        h("span", { class: "form-label", text: "合成元品質を引き継ぐ (既定OFF=合成元と合成対象の平均)", title: "inherit-source-quality" })
      ]));
    } else if (recipe.method === "netherite") {
      box.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: "鍛冶台でネザライト化します。合成元のみ指定。バニラ同様、ネザライト強化の鍛冶型＋ネザライトインゴットが必要です。結果はこのアイテム。"
      }));
      box.appendChild(fieldRow("source-item", recipeItemInput(recipe["source-item"] || "", (v) => {
        setOrDelete(recipe, "source-item", v || null);
      }, items), { labelText: "合成元 (source-item)" }));
    } else {
      const typeSel = h("select", { class: "field-input" });
      for (const t of CATALOG_RECIPE_TYPES) {
        const o = h("option", { value: t, text: t === "shaped" ? "定形 (shaped)" : "不定形 (shapeless)" });
        if ((recipe.type || "shaped") === t) o.selected = true;
        typeSel.appendChild(o);
      }
      const gridSize = catalogRecipeGridSize(recipe.method);
      typeSel.addEventListener("change", (e) => {
        recipe.type = e.target.value;
        if (recipe.type === "shapeless") ensureShapelessRecipe(recipe);
        else ensureShapedRecipe(recipe, gridSize);
        rerenderEntry();
      });
      box.appendChild(fieldRow("type", typeSel));

      if ((recipe.type || "shaped") === "shaped") {
        ensureShapedRecipe(recipe, gridSize);
        box.appendChild(renderShapedRecipeGrid(recipe, rerenderEntry, gridSize));
        // 向き固定 (catalog.yml のみ。TF側リスナーが向きを強制するため素材等Ars側では無効)。
        // inventory(2×2)でも shaped であれば workbench 同様に意味を持つ。
        if (opts && opts.allowMirror) {
          const strictLabel = h("label", { class: "recipe-mirror-toggle", title:
            "OFF(デフォルト)ではバニラ同様、左右反転した置き方でもクラフトできます。"
            + "ONにすると登録した向きの配置でのみクラフトできます。左右対称な配置ではこの設定は影響しません。" }, [
            window.checkboxInput(recipe["strict-orientation"] === true, (v) => {
              if (v) recipe["strict-orientation"] = true; else delete recipe["strict-orientation"];
            }),
            " 登録した向き以外を拒否 (ミラー配置を無効化)"
          ]);
          box.appendChild(h("div", { class: "field-row" }, [strictLabel]));
          delete recipe.mirror; // 旧仕様キー (逆意味) の掃除
        } else {
          // Ars側レシピに紛れ込んだ場合は温存しない (意味を持たないため)。
          delete recipe.mirror;
          delete recipe["strict-orientation"];
        }
      } else {
        ensureShapelessRecipe(recipe);
        box.appendChild(renderShapelessRecipeRows(recipe, rerenderEntry, gridSize * gridSize));
        delete recipe.mirror;
        delete recipe["strict-orientation"];
      }

      // reversible「解凍を許可」: workbench/inventory かつ 素材が全て同一のレシピにのみ設定できる。
      // ONで recipe.reversible=true を保存 (TF側が逆レシピ(結果⇄素材)を自動生成する)。
      const allSame = catalogRecipeIngredientsAllSame(recipe);
      if (!allSame && recipe.reversible) delete recipe.reversible; // 対象外になったら自動でOFFへ戻す
      const reversibleHint = "ONにすると逆レシピ(完成品→素材)が自動登録され、バニラの鉄ブロックのように元に戻せます。"
        + "素材が全て同一のレシピのみ設定可能。";
      const reversibleCb = window.checkboxInput(allSame && recipe.reversible === true, (v) => {
        if (v) recipe.reversible = true; else delete recipe.reversible;
      });
      reversibleCb.disabled = !allSame;
      const reversibleLabel = h("label", {
        class: "form-field inline-check",
        title: allSame ? reversibleHint : reversibleHint + " (現在: 素材が同一でないため設定不可)"
      }, [
        reversibleCb,
        h("span", { class: "form-label", text: "解凍を許可 (reversible)" })
      ]);
      box.appendChild(reversibleLabel);
      if (!allSame) {
        box.appendChild(h("div", { class: "empty-hint", text: "素材が全て同一のレシピのみ「解凍を許可」を設定できます。" }));
      }
    }

    if (recipe.method === "workbench" || recipe.method === "ritual" || recipe.method === "inventory") {
      box.appendChild(fieldRow("amount", window.numberInput(recipe.amount, (v) => { setOrDelete(recipe, "amount", v); }, { int: true })));
    }

    return box;
  }

  window.renderCatalogRecipeSection = renderCatalogRecipeSection;
  // 1レシピ分の編集カード。crafting-features の「レシピ追加」タブ等から流用する
  // (opts.methods で workbench/inventory のみに制限して使う)。
  window.renderCatalogRecipeCard = renderCatalogRecipeCard;
  window.ensureShapedRecipe = ensureShapedRecipe;

  window.buildCatalogForm = function buildCatalogForm(data, opts) {
    const options = opts && typeof opts === "object" ? opts : {};
    let hideCategoryTabs = !!options.hubMode;
    const editorCategoryKey = options.editorCategoryKey || null;
    const useEditorMeta = !!editorCategoryKey && (!!options.hubMode || !!(data && data._editor));
    // ファイル跨ぎ移動 (素材タブ=materials.yml へアイテムを移す)。
    // { id:"materials", data:<materials.ymlのデータ>, dirty:false } を split-views 経由で受け取り、
    // 移動が発生したら dirty=true にして保存時に両ファイルへ書き込ませる。
    const crossFile = options.crossFile && options.crossFile.data ? options.crossFile : null;
    const working = data && typeof data === "object" ? data : {};
    if (!working.items || typeof working.items !== "object") working.items = {};
    const root = h("div", { class: "dedicated-form" });

    let activeCat = options.initialCategory || "weapon";
    let filterText = "";
    // 折りたたみ状態(開いているidの集合)。既定は全て折りたたみ (skilltreeと同じUX)。再描画をまたいで保持する。
    const expanded = new Set();

    function tabLabelForItem(itemId, material) {
      const tab = typeof window.getItemDisplayTab === "function"
        ? window.getItemDisplayTab(working, itemId, material)
        : "other";
      const found = CATALOG_CATEGORIES.find(([id]) => id === tab);
      return found ? found[1] : tab;
    }
    function nestLabelForItem(itemId) {
      if (!useEditorMeta || typeof window.getItemEditorCategory !== "function") return "—";
      const catId = window.getItemEditorCategory(working, editorCategoryKey, itemId);
      if (!catId) return "—";
      const cats = typeof window.listEditorCategories === "function"
        ? window.listEditorCategories(working, editorCategoryKey) : [];
      const cat = cats.find((c) => c.id === catId);
      return cat ? (cat.label || cat.id) : "—";
    }

    const tabBar = h("div", { class: "recipe-tabs" });
    const filterRow = h("div", { class: "item-stats-filter" }, [
      h("span", { class: "mini-label", text: "検索" }),
      h("input", {
        class: "field-input", type: "text", spellcheck: "false",
        placeholder: "ID/表示名で絞り込み",
        value: filterText,
        oninput: (e) => { filterText = e.target.value; renderList(); }
      })
    ]);
    // CMD一括割当ボタンはここには置かない。全ファイル横断で「リソースパック管理」画面
    // (respack-view.js の「全アイテムCMD一括採番＆保存」) に集約済み。
    const listBox = h("div", { class: "card-list" });

    function idsInCategory(cat) {
      return Object.keys(working.items).filter((id) => {
        const mat = working.items[id] && working.items[id].material;
        if (typeof window.getItemDisplayTab === "function") {
          return window.getItemDisplayTab(working, id, mat) === cat;
        }
        return window.inferItemCategory(mat) === cat;
      });
    }

    function filterAndSortIds(ids) {
      let result = ids;
      if (useEditorMeta && typeof window.itemInEditorCategory === "function") {
        result = result.filter((id) => window.itemInEditorCategory(working, editorCategoryKey, id));
      }
      if (useEditorMeta && typeof window.sortIdsByEditorOrder === "function") {
        result = window.sortIdsByEditorOrder(working, editorCategoryKey, result);
      }
      return result;
    }

    function bindListReorder() {
      if (!useEditorMeta || !listBox || listBox._reorderBound) return;
      if (typeof window.bindCollapsedCardReorder !== "function") return;
      listBox._reorderBound = true;
      window.bindCollapsedCardReorder(listBox, {
        getId: (el) => el.dataset.dragId || "",
        onReorder: (ordered) => {
          let full = ordered;
          if (typeof window.mergeVisibleEditorOrder === "function") {
            full = window.mergeVisibleEditorOrder(working, editorCategoryKey, ordered);
          } else if (typeof window.setEditorOrder === "function") {
            window.setEditorOrder(working, editorCategoryKey, ordered);
          }
          if (typeof window.reorderObjectKeys === "function") {
            window.reorderObjectKeys(working.items, full);
          }
          renderList();
        }
      });
    }

    function refreshListPreserveScroll(fn) {
      const main = document.querySelector(".main");
      const top = main ? main.scrollTop : 0;
      fn();
      if (main) main.scrollTop = top;
    }

    /**
     * 素材タブ (materials.yml) へのファイル跨ぎ移動。表示名/loreは MiniMessage→&コードへ変換し、
     * 変換できない装飾 (gradient / 16色外のhex等) はプレーンテキストへフォールバックする。
     * 実際のファイル書き込みは「保存」時 (catalog.yml + materials.yml の両方)。
     */
    function moveEntryToMaterials(id) {
      const entry = working.items[id];
      if (!entry || !crossFile) return;
      const mats = crossFile.data;
      if (!mats.materials || typeof mats.materials !== "object") mats.materials = {};
      if (Object.prototype.hasOwnProperty.call(mats.materials, id)) {
        alert(`materials.yml に同じ id (${id}) が既に存在するため移動できません。`);
        refreshListPreserveScroll(renderList);
        return;
      }
      const toLegacy = (s) => {
        if (s == null || s === "") return "";
        const conv = window.COLORS && typeof window.COLORS.miniMessageToLegacy === "function"
          ? window.COLORS.miniMessageToLegacy(s) : null;
        return conv != null ? conv : (window.stripDisplayNamePlain(s) || "");
      };
      const notes = [];
      if (entry["bind-type"] && entry["bind-type"] !== "TRADEABLE") notes.push(`bind-type(${entry["bind-type"]})`);
      if (entry.color) notes.push("color(革防具染色)");
      if (Array.isArray(entry.recipes) && entry.recipes.length > 1) notes.push("2件目以降のレシピ(recipesは1件のみ移行)");
      if (!confirm(`「${id}」を素材タブ (materials.yml) へ移動します。\n`
          + "表示名/loreは MiniMessage → &コードへ変換されます (変換できない装飾はプレーン化)。"
          + (notes.length ? `\n引き継げない項目: ${notes.join(", ")}` : "")
          + "\n\n実ファイルへの反映は「保存」時に catalog.yml / materials.yml の両方へ書き込まれます。")) {
        refreshListPreserveScroll(renderList);
        return;
      }
      const m = { base_material: entry.material || "PAPER" };
      m.custom_model_data = entry["custom-model-data"] != null ? entry["custom-model-data"] : 0;
      m.display_name = toLegacy(entry["display-name"]) || id;
      // materials側の enchant_glow 既定は true のため false も明示して書く。
      m.enchant_glow = !!entry["enchant-glow"];
      if (Array.isArray(entry.lore) && entry.lore.length) m.lore = entry.lore.map(toLegacy);
      if (entry.recipe && typeof entry.recipe === "object") {
        m.recipe = JSON.parse(JSON.stringify(entry.recipe));
      } else if (Array.isArray(entry.recipes) && entry.recipes.length) {
        m.recipe = JSON.parse(JSON.stringify(entry.recipes[0]));
      }
      mats.materials[id] = m;
      if (typeof window.appendEditorOrder === "function") window.appendEditorOrder(mats, "material", id);
      // 移動先(素材タブ)でも必ずどこかのカテゴリへ入れる。入れないと素材画面で「未設定」に落ちる。
      if (typeof window.ensureItemEditorCategory === "function") {
        window.ensureItemEditorCategory(mats, "material", id);
      }
      // カタログ側から除去 (タブピン/ネストカテゴリ/並び順も全タブから掃除)。
      delete working.items[id];
      if (typeof window.removeItemDisplayTab === "function") window.removeItemDisplayTab(working, id);
      if (typeof window.removeEditorCategoryItem === "function") {
        for (const [tabKey] of CATALOG_CATEGORIES) window.removeEditorCategoryItem(working, tabKey, id);
      }
      crossFile.dirty = true;
      refreshListPreserveScroll(render);
    }

    function render() {
      tabBar.innerHTML = "";
      for (const [cat, label] of CATALOG_CATEGORIES) {
        const count = idsInCategory(cat).length;
        tabBar.appendChild(h("button", {
          class: `recipe-tab ${activeCat === cat ? "active" : ""}`, type: "button",
          onclick: () => { activeCat = cat; render(); }
        }, [h("span", { text: label }), h("span", { class: "recipe-tab-count", text: String(count) })]));
      }
      renderList();
    }

    function renderList() {
      listBox.innerHTML = "";
      if (typeof window.setCustomItemCandidates === "function") {
        const entries = Object.entries(working.items || {}).map(([id, ent]) => ({
          id,
          label: (typeof window.stripDisplayNamePlain === "function"
            ? window.stripDisplayNamePlain(ent && ent["display-name"])
            : (ent && ent["display-name"])) || id
        }));
        window.setCustomItemCandidates(entries, { replace: false });
      }
      if (window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
        window.RECIPES_UI.ensureCustomDatalist().catch(() => {});
      }
      const q = filterText.trim().toLowerCase();
      const ids = filterAndSortIds(idsInCategory(activeCat).filter((id) => {
        if (!q) return true;
        const entry = working.items[id];
        const plain = (window.stripDisplayNamePlain(entry && entry["display-name"]) || id).toLowerCase();
        return id.toLowerCase().includes(q) || plain.includes(q);
      }));
      if (Object.keys(working.items).length === 0) {
        listBox.appendChild(emptyGuide("カタログにアイテムがまだありません。", "「+ アイテム追加」で、id と素材(Material)を持つアイテムを登録します。"));
      } else if (ids.length === 0) {
        listBox.appendChild(emptyGuide("該当するアイテムがありません。", "サブタブ・検索条件を確認してください。"));
      }
      for (const id of ids) {
        const el = renderEntry(id);
        el.dataset.dragId = id;
        listBox.appendChild(el);
      }
      bindListReorder();
      const actionButtons = [];
      actionButtons.push(
        h("button", {
          class: "btn", type: "button", text: "+ アイテム追加",
          onclick: () => {
            let name = "new_item", i = 1;
            while (Object.prototype.hasOwnProperty.call(working.items, name)) name = `new_item_${i++}`;
            const defaultMaterial = {
              weapon: "DIAMOND_SWORD", armor: "DIAMOND_CHESTPLATE", tool: "DIAMOND_PICKAXE",
              other: "DIAMOND", catalyst: "BLAZE_ROD", spellbook: "BOOK", thread: "STRING",
              // 2026-08-02 指摘5: "material-ref" が無いとフォールバックの DIAMOND_SWORD が使われ、
              // 「素材」タブの参照セクションから足すと必ずダイヤの剣になってしまっていた。
              "material-ref": "PAPER"
            }[activeCat] || "DIAMOND_SWORD";
            working.items[name] = { material: defaultMaterial };
            if (typeof window.setItemDisplayTab === "function") {
              window.setItemDisplayTab(working, name, activeCat);
            }
            if (useEditorMeta && typeof window.assignItemToActiveEditorCategory === "function") {
              window.assignItemToActiveEditorCategory(working, editorCategoryKey, name);
            }
            if (useEditorMeta && typeof window.appendEditorOrder === "function") {
              window.appendEditorOrder(working, editorCategoryKey, name);
            }
            render();
          }
        })
      );
      listBox.appendChild(h("div", { class: "form-actions" }, actionButtons));
    }

    function renderEntry(id) {
      const entry = working.items[id];
      // 既存YAMLで lore が非配列(文字列など)でも forEach 前提の描画が落ちないよう [] に正規化する。
      if (!Array.isArray(entry.lore)) entry.lore = [];

      const idInput = h("input", { class: "field-input", value: id, spellcheck: "false" });
      idInput.addEventListener("change", (e) => {
        const nv = e.target.value;
        if (!nv || nv === id) { e.target.value = id; return; }
        if (Object.prototype.hasOwnProperty.call(working.items, nv)) { alert("同じidが存在します"); e.target.value = id; return; }
        renameKey(working.items, id, nv);
        if (typeof window.renameItemDisplayTab === "function") {
          window.renameItemDisplayTab(working, id, nv);
        }
        if (useEditorMeta && typeof window.renameEditorCategoryItem === "function") {
          window.renameEditorCategoryItem(working, editorCategoryKey, id, nv);
        }
        if (expanded.has(id)) { expanded.delete(id); expanded.add(nv); }
        render();
      });

      const plainDisplay = window.stripDisplayNamePlain(entry["display-name"]) || id;
      const tabLabel = tabLabelForItem(id, entry.material);
      const nestLabel = nestLabelForItem(id);

      const editChildren = [
        h("span", { class: "entry-key-label", text: "id" }), idInput
      ];
      if (typeof window.renderItemTabSelect === "function") {
        // 「素材(カタログ内)」ピンは常時選べる (実データ移動を伴わないただの表示タブなので
        // crossFile の有無に関係ない)。materials.yml が読めていればさらに「素材」への実データ
        // 移動先も出す (ファイル跨ぎはハンドラで処理)。2つの選択肢名は絶対に文字列衝突させない
        // (MATERIAL_REF_TAB_ID = "material-ref" ≠ 実データ移行の "material")。
        const tabOpts = (crossFile
          ? CATALOG_CATEGORIES.concat([window.CATALOG_MATERIAL_REF_TAB, ["material", "素材(materials.ymlへ移動)"]])
          : CATALOG_CATEGORIES.concat([window.CATALOG_MATERIAL_REF_TAB]));
        const external = crossFile ? { material: (itemId) => moveEntryToMaterials(itemId) } : null;
        editChildren.push(window.renderItemTabSelect(working, id, entry.material, tabOpts, () => {
          refreshListPreserveScroll(renderList);
        }, external));
      }
      if (useEditorMeta && typeof window.renderEditorCategorySelect === "function") {
        editChildren.push(window.renderEditorCategorySelect(working, editorCategoryKey, id, () => {
          refreshListPreserveScroll(renderList);
        }));
      }
      editChildren.push(
        h("div", { class: "spacer" }),
        h("button", {
          class: "btn-small", type: "button", text: "複製",
          onclick: () => {
            let n = 2, copy = `${id}_${n}`;
            while (Object.prototype.hasOwnProperty.call(working.items, copy)) copy = `${id}_${++n}`;
            working.items[copy] = JSON.parse(JSON.stringify(working.items[id]));
            // CMDはアイテム固有 (台帳1行=1アイテム) のため複製には引き継がない。
            delete working.items[copy]["custom-model-data"];
            if (typeof window.setItemDisplayTab === "function") {
              window.setItemDisplayTab(working, copy, activeCat);
            }
            // 複製元と同じネストカテゴリへ割り当てる (絞り込み中でも見失わない)。
            if (useEditorMeta && typeof window.duplicateItemEditorCategory === "function") {
              window.duplicateItemEditorCategory(working, editorCategoryKey, id, copy);
            }
            if (useEditorMeta && typeof window.appendEditorOrder === "function") {
              window.appendEditorOrder(working, editorCategoryKey, copy);
            }
            render();
          }
        }),
        h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => {
            delete working.items[id];
            if (typeof window.removeItemDisplayTab === "function") {
              window.removeItemDisplayTab(working, id);
            }
            if (useEditorMeta && typeof window.removeEditorCategoryItem === "function") {
              window.removeEditorCategoryItem(working, editorCategoryKey, id);
            }
            render();
          }
        })
      );

      const head = [
        h("div", { class: "entry-collapse-summary" }, [
          h("span", { class: "entry-sum-name", text: plainDisplay }),
          h("span", { class: "entry-sum-id", text: id }),
          h("span", { class: "entry-sum-meta", text: tabLabel }),
          h("span", { class: "entry-sum-meta", text: nestLabel })
        ]),
        h("div", { class: "entry-collapse-edit" }, editChildren)
      ];

      // 2026-08-02: materialHintEl は削除 (materialInput 自身が 2026-07-29 の listSelect 移行で
      // 既に日本語表示名(primary)を出しているため、隣に並べると同じ名前が2回出て行が潰れる)。
      // material 変更時も現在タブへピン留めし、推論による強制タブ移動を防ぐ。
      const matInput = window.materialInput(entry.material, "material-list", (v) => {
        entry.material = v;
        // 革防具以外では color は無効 (Java側も無視)。切替時にキーを落として YAML をきれいに保つ。
        if (typeof window.isLeatherArmorMaterial === "function" && !window.isLeatherArmorMaterial(v)) {
          delete entry.color;
        }
        if (typeof window.setItemDisplayTab === "function") {
          window.setItemDisplayTab(working, id, activeCat);
        }
        renderList();
      });

      // tooltip ライブプレビュー (表示名 + フレーバーlore をライブ反映。品質/ステ行は自動生成のため含まない)。
      const preview = window.buildTooltipPreview();
      function refreshPreview() {
        const nm = entry["display-name"];
        preview.update({
          name: (nm != null && nm !== "") ? nm : id,
          nameMode: "minimessage",
          loreLines: Array.isArray(entry.lore) ? entry.lore : [],
          loreMode: "minimessage"
        });
      }

      const isLeather = typeof window.isLeatherArmorMaterial === "function"
        ? window.isLeatherArmorMaterial(entry.material)
        : String(entry.material || "").toUpperCase().startsWith("LEATHER_");
      const leatherColorCtl = isLeather
        ? (window.colorPickerInput
          ? window.colorPickerInput(entry.color, "hex", (v) => {
              if (v === "") delete entry.color; else entry.color = v;
            })
          : window.textInput(entry.color || "", (v) => { setOrDelete(entry, "color", v || null); }, { placeholder: "#RRGGBB" }))
        : null;

      const inputChildren = [
        fieldRow("material", h("span", { class: "input-with-hint" }, [matInput]), { required: true }),
        fieldRow("display-name", window.richTextInput(entry["display-name"], "minimessage", (v) => { setOrDelete(entry, "display-name", v); refreshPreview(); })),
        fieldRow("custom-model-data", (() => {
          const wrap = h("span", { class: "cmd-field-row" });
          const cmdNumInput = window.numberInput(entry["custom-model-data"], (v) => { setOrDelete(entry, "custom-model-data", v); }, { int: true });
          wrap.appendChild(cmdNumInput);
          // M-4: 自動割当/テクスチャ登録で払い出したCMDは entry だけでなく数値入力欄の表示値
          // にも直接反映する (フル再描画はしない: テクスチャ登録は非同期アップロード中にDOMが
          // 差し替わるとアップロード完了後のチップ表示が消えるため、値の直接同期で対応する)。
          function syncAssignedCmd(cmd) {
            entry["custom-model-data"] = cmd;
            cmdNumInput.value = String(cmd);
          }
          // 個別の「CMD自動割当」ボタンは廃止 (検索欄横の「CMD一括割当」がカテゴリ単位で担う。
          // テクスチャ登録時はcmdTextureControlがCMD未設定なら自動採番する)。
          if (typeof window.cmdTextureControl === "function") {
            wrap.appendChild(window.cmdTextureControl({
              getMaterial: () => entry.material,
              getCmd: () => (typeof entry["custom-model-data"] === "number" ? entry["custom-model-data"] : null),
              getId: () => id,
              source: "catalog",
              onAssigned: syncAssignedCmd
            }));
          }
          return wrap;
        })()),
        fieldRow("bind-type", window.selectLabeledInput(entry["bind-type"] || "TRADEABLE", window.BIND_TYPES, "bind-type", (v) => { entry["bind-type"] = v; }))
      ];
      if (leatherColorCtl) {
        inputChildren.push(fieldRow("color", leatherColorCtl));
      }
      inputChildren.push(
        fieldRow("enchant-glow", (() => {
          const row = h("label", { class: "form-field inline-check" });
          row.appendChild(window.checkboxInput(!!entry["enchant-glow"], (v) => {
            if (v) entry["enchant-glow"] = true; else delete entry["enchant-glow"];
          }));
          row.appendChild(h("span", { class: "form-label", text: "enchant aura (オンでエンチャント光)" }));
          return row;
        })()),
        h("div", { class: "sub-title", text: "フレーバー説明文 (lore)" }),
        renderLoreRows(entry.lore, "minimessage", refreshPreview, () => render())
      );
      const inputs = h("div", { class: "entry-inputs" }, inputChildren);

      const previewCol = h("div", { class: "entry-preview" }, [
        h("div", { class: "preview-label", text: "表示プレビュー" }),
        preview.element,
        h("div", { class: "preview-note", text: "品質ティア行・自動ステ行はここには表示されません(ロア表示configが担当)。lore はその前に差し込まれるフレーバー説明文です。" })
      ]);

      refreshPreview();
      const recipeSection = renderCatalogRecipeSection(entry, () => renderList(), working.items, id, { allowMirror: true });
      return window.collapsibleCard(head, [h("div", { class: "entry-2col" }, [inputs, previewCol]), recipeSection], {
        expanded: expanded.has(id),
        onToggle: (open) => { if (open) expanded.add(id); else expanded.delete(id); },
        dragId: useEditorMeta ? id : undefined
      });
    }

    render();
    if (!hideCategoryTabs) root.appendChild(tabBar);
    root.appendChild(filterRow);
    root.appendChild(listBox);
    // 保存時は表示用に空補完した lore を省いて出力する (元に無かったキーを増やさない)。
    return {
      element: root,
      getData: () => {
        const out = { ...working, items: pruneEntries(working.items, ["lore"]) };
        if (typeof window.pruneEditorUiState === "function") window.pruneEditorUiState(out);
        return out;
      },
      setActiveCategory: (cat) => {
        if (cat && cat !== activeCat) {
          activeCat = cat;
          render();
        } else if (cat) {
          activeCat = cat;
          render();
        }
      },
      setHubMode: (on) => {
        hideCategoryTabs = !!on;
        if (hideCategoryTabs && tabBar.parentNode) tabBar.parentNode.removeChild(tabBar);
        else if (!hideCategoryTabs && !tabBar.parentNode) root.insertBefore(tabBar, filterRow);
        render();
      },
      rerender: () => renderList(),
      rerenderList: () => renderList()
    };
  };

  // 空文字/未指定は該当キーを削除する (任意フィールドを YAML に空出力しない)。
  function setOrDelete(obj, key, value) {
    if (value === "" || value === null || value === undefined) delete obj[key];
    else obj[key] = value;
  }

  // 画面表示用に補完された空の任意項目を、保存出力から取り除くためのヘルパー群。
  // working (表示用) は直接変更せず、浅いコピーに対して省略する (表示と保存データの分離)。
  function isEmptyObject(v) {
    return v !== null && typeof v === "object" && !Array.isArray(v) && Object.keys(v).length === 0;
  }
  function isEmptyArray(v) {
    return Array.isArray(v) && v.length === 0;
  }

  // マップ配下の各エントリを浅コピーし、指定キーが空(空オブジェクト/空配列)なら省く。
  function pruneEntries(mapValue, emptyOptionalKeys) {
    const out = {};
    for (const [key, entry] of Object.entries(mapValue || {})) {
      if (entry === null || typeof entry !== "object" || Array.isArray(entry)) {
        out[key] = entry;
        continue;
      }
      const copy = { ...entry };
      for (const optKey of emptyOptionalKeys) {
        const val = copy[optKey];
        if (isEmptyObject(val) || isEmptyArray(val)) delete copy[optKey];
      }
      out[key] = copy;
    }
    return out;
  }

  function dropEmptyItemProfiles(items) {
    for (const [key, entry] of Object.entries(items || {})) {
      if (
        entry
        && typeof entry === "object"
        && !Array.isArray(entry)
        && Object.keys(entry).length === 0
      ) {
        delete items[key];
      }
    }
    return items;
  }
})();
