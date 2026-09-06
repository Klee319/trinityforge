"use strict";

// TrinityForge 小型 config 専用フォーム群 (P4)。
//   quality / craft-quality / quality-tiers / attribute-map / tool-enchants / item-categories
//
// 設計方針 (往復ロスレス最優先):
//   - working = 受け取った data を「そのまま」直接編集する (forms.js と同じ流儀)。
//     既知キーだけを専用UI(セレクト/色ピッカー/チェック)で編集し、未知キー・キー順は温存する。
//   - 無編集なら data は一切変化しないため getData() は原文と deep-equal になる。
//   - i18n は表示のみ。保存されるキー/値は英字のまま。

(function () {
  const h = window.h;

  // ---- 共通DOM部品 ----
  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function fieldRow(key, control, labelText) {
    const labelEl = labelText ? h("span", { class: "form-label", text: labelText }) : window.fieldLabelEl(key);
    return h("div", { class: "form-field" }, [labelEl, control]);
  }
  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }
  function gridRow(fields) { return h("div", { class: "field-grid" }, fields); }

  // recipes.js の enchant_book (effect-params.enchantment) 候補 datalist や
  // オーバーエンチャ設定から参照されるエンチャント名の一覧。
  // 一覧の実体は vocab-1.21.11.js (VANILLA_ENCHANTS) 側に一本化した。ここで独自に列挙していた頃は
  // トライデント/クロスボウ/メイス系 (loyalty, riptide, multishot, density, breach, wind_burst 等) が
  // まるごと抜けており、新しいエンチャントが追加されるたび2箇所を直す必要があった。
  const ENCHANT_KEYS = Array.isArray(window.VANILLA_ENCHANTS) && window.VANILLA_ENCHANTS.length
    ? window.VANILLA_ENCHANTS.slice()
    : [
      // vocab-1.21.11.js が読み込まれていない場合のフォールバック (順序も同じ)。
      "protection", "fire_protection", "feather_falling", "blast_protection",
      "projectile_protection", "respiration", "aqua_affinity", "thorns", "depth_strider",
      "frost_walker", "binding_curse", "sharpness", "smite", "bane_of_arthropods",
      "knockback", "fire_aspect", "looting", "sweeping_edge", "efficiency", "silk_touch",
      "unbreaking", "fortune", "power", "punch", "flame", "infinity", "luck_of_the_sea",
      "lure", "loyalty", "impaling", "riptide", "channeling", "multishot", "quick_charge",
      "piercing", "density", "breach", "wind_burst", "mending", "vanishing_curse",
      "soul_speed", "swift_sneak", "lunge",
      // ArsPaper 追加エンチャント
      "mana_regen", "mana_boost", "soulbound", "share"
    ];
  window.ENCHANT_KEYS = ENCHANT_KEYS;

  function ensureObj(parent, key) {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) parent[key] = {};
    return parent[key];
  }

  const MOB_EXP_MAP_KEYS = new Set([
    "entity-type-multipliers", "entity_exp_multipliers",
    "entity_breed", "entity_kill", "entity_shear"
  ]);
  const MATERIAL_EXP_MAP_KEYS = new Set([
    "brew_ingredient", "mining_break", "digging_break", "archaeology_brush",
    "woodcutting_break", "woodcutting_strip", "block_interact", "block_drops",
    // 2026-07-30 追加: skill-exp.yml の smithing.exp-per-material
    // (クラフト盤面に置いた素材1個あたりの鍛冶EXP)。custom:<カタログID> も選べる。
    "entity_drops", "fishing_catch", "exp-per-material"
  ]);

  function expMapKind(key) {
    if (MOB_EXP_MAP_KEYS.has(key)) return "mob";
    if (MATERIAL_EXP_MAP_KEYS.has(key)) return "material";
    return null;
  }

  // ------------------------------------------------------------------
  // 2026-07-28: エンチャントEXP設定 (skills/base/enchanting_progression.yml の
  // experience.exp_gain) の行見出しが生ID のままだった。
  //   enchantment_base            : エンチャントID (sharpness ...)
  //   enchantment_type_multiplier : 装備の素材/種別 (WOOD, IRON, BOW ...)
  //   enchantment_item_multiplier : 装備の部位 (SWORD, HELMET ...)
  //   enchantment_level_multiplier: エンチャントのレベル (1..10)
  // どれも Material でも EntityType でもないので既存の辞書では引けない。
  // ここでは行の「見出し」だけを日本語にする(キー文字列自体は保存値のまま)。
  // ------------------------------------------------------------------
  const ENCHANT_TYPE_LABELS = {
    BOW: "弓", CROSSBOW: "クロスボウ", WOOD: "木", LEATHER: "革", STONE: "石",
    CHAINMAIL: "チェーン", IRON: "鉄", GOLD: "金", DIAMOND: "ダイヤモンド",
    NETHERITE: "ネザライト", PRISMARINE: "プリズマリン(カメの甲羅)", MEMBRANE: "ファントムの皮膜(エリトラ)",
    MACE: "メイス", TRIDENT: "トライデント", SHEARS: "ハサミ"
  };
  const ENCHANT_ITEM_LABELS = {
    SWORD: "剣", PICKAXE: "ツルハシ", AXE: "斧", SHOVEL: "シャベル", HOE: "クワ",
    BOOTS: "ブーツ", LEGGINGS: "レギンス", CHESTPLATE: "チェストプレート", HELMET: "ヘルメット",
    SHEARS: "ハサミ", TRIDENT: "トライデント", CROSSBOW: "クロスボウ", BOW: "弓",
    FISHING_ROD: "釣竿", MACE: "メイス"
  };

  /** 表(オブジェクト)のキーから行見出しを作る関数を返す。対象外の表なら null。 */
  function keyedTableLabeler(sectionKey) {
    if (sectionKey === "enchantment_base") {
      return (key) => {
        const ja = window.LABELS && typeof window.LABELS.enchantLabel === "function"
          ? window.LABELS.enchantLabel(key) : "";
        return ja ? `${ja} (${key})` : key;
      };
    }
    if (sectionKey === "enchantment_type_multiplier") {
      return (key) => (ENCHANT_TYPE_LABELS[key] ? `${ENCHANT_TYPE_LABELS[key]} (${key})` : key);
    }
    if (sectionKey === "enchantment_item_multiplier") {
      return (key) => (ENCHANT_ITEM_LABELS[key] ? `${ENCHANT_ITEM_LABELS[key]} (${key})` : key);
    }
    if (sectionKey === "brew_result") {
      // 2026-07-28: 醸造結果EXP表の行見出しが PotionType の生ID(AWKWARD 等)のままだった。
      return (key) => {
        const ja = window.LABELS && typeof window.LABELS.potionTypeLabel === "function"
          ? window.LABELS.potionTypeLabel(key) : "";
        return ja ? `${ja} (${key})` : key;
      };
    }
    if (sectionKey === "enchantment_level_multiplier") {
      return (key) => `エンチャントLv ${key}`;
    }
    return null;
  }

  function renameMapKey(map, oldKey, newKey) {
    const entries = Object.entries(map);
    for (const key of Object.keys(map)) delete map[key];
    for (const [key, value] of entries) map[key === oldKey ? newKey : key] = value;
  }

  // 2026-07-28: カスタムアイテム(custom:<id>)も素材として設定できる。EXPテーブルは Java 側
  // (ItemExpLookup)が custom行 → バニラMaterial行 の順で引くため、両方を候補に出す。
  // custom: キーは大文字化してはいけない(IDが別物になる)ので add() では触らない。
  function isCustomExpKey(key) {
    return /^custom:/i.test(String(key == null ? "" : key));
  }

  function customExpCandidates() {
    const raw = Array.isArray(window.CUSTOM_ITEM_CANDIDATES) ? window.CUSTOM_ITEM_CANDIDATES : [];
    const out = [];
    const seen = new Set();
    for (const entry of raw) {
      if (entry == null || entry === "") continue;
      const s = String(entry);
      const key = "custom:" + (isCustomExpKey(s) ? s.slice(s.indexOf(":") + 1) : s);
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(key);
    }
    return out;
  }

  function customExpLabel(key) {
    const labels = window.CUSTOM_ITEM_LABELS && typeof window.CUSTOM_ITEM_LABELS === "object"
      ? window.CUSTOM_ITEM_LABELS : {};
    const id = String(key).slice(String(key).indexOf(":") + 1);
    const label = labels[key] || labels[String(key).toLowerCase()] || labels[id] || "";
    return label && label !== id ? label : `カスタム: ${id}`;
  }

  function expMapOptions(kind, map) {
    const ids = [];
    const seen = new Set();
    function add(raw) {
      const src = String(raw == null ? "" : raw).trim();
      // custom:<id> は大小を保つ。バニラ Material / EntityType 名だけ大文字へ寄せる。
      const id = isCustomExpKey(src) ? src : src.toUpperCase();
      if (!id || seen.has(id)) return;
      seen.add(id);
      ids.push(id);
    }
    if (kind === "mob") {
      for (const id of (Array.isArray(window.VANILLA_MOBS) ? window.VANILLA_MOBS : [])) add(id);
    } else {
      for (const id of customExpCandidates()) add(id);
      for (const id of (Array.isArray(window.MATERIALS) ? window.MATERIALS : [])) add(id);
      const blocks = window.BLOCK_MATERIALS;
      if (blocks && typeof blocks[Symbol.iterator] === "function") {
        for (const id of blocks) add(id);
      }
    }
    for (const id of Object.keys(map)) add(id);
    return ids.map((id) => {
      if (isCustomExpKey(id)) {
        return { value: id, primary: customExpLabel(id), secondary: id };
      }
      // 既知のモブ/素材は英字IDを併記しない(この画面の既存方針。絞り込みはIDでも効く)。
      const primary = kind === "mob"
        ? ((window.MOB_LABELS_JA && window.MOB_LABELS_JA[id]) || id)
        : (window.LABELS && typeof window.LABELS.materialLabelWithFallback === "function"
          ? window.LABELS.materialLabelWithFallback(id) : id);
      return { value: id, primary };
    });
  }

  /** セレクトで確定した値の正規化。custom:<id> は大小を保ち、それ以外は大文字へ寄せる。 */
  function normalizeExpMapKey(raw) {
    const s = String(raw == null ? "" : raw).trim();
    return isCustomExpKey(s) ? "custom:" + s.slice(s.indexOf(":") + 1) : s.toUpperCase();
  }

  function expMapEditor(map, kind, renderOptions) {
    const body = h("div", { class: "stat-rows se-exp-map-rows" });
    function render() {
      body.innerHTML = "";
      const keys = Object.keys(map);
      if (!keys.length) {
        body.appendChild(h("div", { class: "empty-hint", text:
          kind === "mob" ? "敵種類別のEXP倍率は未設定です。" : "素材別の獲得EXPは未設定です。" }));
      }
      for (const key of keys) {
        const selector = window.listSelect({
          value: key,
          options: expMapOptions(kind, map),
          expMapKind: kind,
          onCommit: (raw) => {
            const next = normalizeExpMapKey(raw);
            if (!next) return false;
            if (next === key) return true;
            if (Object.prototype.hasOwnProperty.call(map, next)) {
              if (typeof alert === "function") alert(`「${next}」は既に設定されています`);
              return false;
            }
            renameMapKey(map, key, next);
            render();
            return true;
          }
        });
        const amount = window.numberInput(map[key], (value) => {
          if (value === null || value === "") return;
          map[key] = value;
        }, { int: false });
        body.appendChild(h("div", { class: "stat-row se-exp-map-row" }, [
          selector,
          h("span", { class: "mini-label", text: kind === "mob" ? "倍率" : "EXP" }),
          amount,
          h("button", {
            class: "btn-small danger",
            type: "button",
            text: "×",
            title: "この設定を削除",
            onclick: () => { delete map[key]; render(); }
          })
        ]));
      }
      const available = expMapOptions(kind, map)
        .filter((option) => !Object.prototype.hasOwnProperty.call(map, option.value));
      if (available.length) {
        body.appendChild(window.listSelect({
          value: "",
          options: available,
          placeholder: kind === "mob" ? "＋ 敵種類を追加…" : "＋ 素材を追加…",
          expMapKind: kind,
          onCommit: (raw) => {
            const next = normalizeExpMapKey(raw);
            if (!next || Object.prototype.hasOwnProperty.call(map, next)) return false;
            map[next] = 0;
            render();
            return true;
          }
        }));
      }
    }
    render();
    return body;
  }

  // ---- ネストしたスカラー群 (mode/drop/ars-smithing 等) を1セクション=1カードで描画する共通部品 ----
  // obj は {key: number|boolean|string} 想定。int判定は値ごとに Number.isInteger で行い小数も温存。
  // exp-per-craft / spread は小数を取り得るので常に小数入力にする。
  // fieldOverrides: { key: { label, desc } } — グローバル辞書(FIELD_LABELS)のキーが他画面と
  // 意味衝突する場合(例: "mode" は items.yml では天候モード)に、この画面文脈だけラベルを上書きする。
  function scalarSectionBody(obj, labelOpts, fieldOverrides, excludedKeys, renderOptions) {
    const body = h("div", { class: "const-body" });
    for (const key of Object.keys(obj)) {
      if (excludedKeys && excludedKeys.has(key)) continue;
      const override = fieldOverrides && fieldOverrides[key];
      const opts = override ? Object.assign({}, labelOpts, override) : labelOpts;
      // 行見出し: 親が「エンチャントID → 値」等の表なら専用のラベル関数を使う。
      const keyLabeler = renderOptions && typeof renderOptions.keyLabeler === "function"
        ? renderOptions.keyLabeler : null;
      const labelEl = () => (keyLabeler
        ? h("span", { class: "form-label", text: keyLabeler(key), title: key })
        : window.fieldLabelEl(key, opts));
      if (obj[key] && typeof obj[key] === "object" && !Array.isArray(obj[key])) {
        const mapKind = expMapKind(key);
        const nestedLabeler = keyedTableLabeler(key);
        const nestedOptions = nestedLabeler
          ? Object.assign({}, renderOptions, { keyLabeler: nestedLabeler })
          // 表を抜けたら継承しない(孫の見出しまで親の辞書で引かれるのを防ぐ)。
          : Object.assign({}, renderOptions, { keyLabeler: null });
        body.appendChild(h("details", { class: "se-nested-section" }, [
          h("summary", { class: "entry-key-label", text: window.LABELS.fieldLabel(key) }),
          mapKind
            ? expMapEditor(obj[key], mapKind, renderOptions)
            : scalarSectionBody(obj[key], labelOpts, null, null, nestedOptions)
        ]));
      } else if (typeof obj[key] === "boolean") {
        body.appendChild(h("div", { class: "form-field" }, [labelEl(), window.checkboxInput(obj[key], (v) => { obj[key] = v; })]));
      } else if (typeof obj[key] === "number") {
        const intInput = !(renderOptions && renderOptions.forceFloat)
          && Number.isInteger(obj[key]) && key !== "exp-per-craft" && key !== "spread";
        body.appendChild(h("div", { class: "form-field" }, [labelEl(), window.numberInput(obj[key], (v) => { if (v === null || v === "") return; obj[key] = v; }, { int: intInput })]));
      } else if (typeof obj[key] === "string") {
        const control = key === "exp-mode"
          ? window.listSelect({
              value: obj[key],
              options: [
                { value: "drop_sum", primary: "ドロップ合計" },
                { value: "block_value", primary: "破壊ブロック基準" },
                { value: "max", primary: "大きい方を採用" }
              ],
              onChange: (v) => { obj[key] = v; }
            })
          : window.textInput(obj[key], (v) => { obj[key] = v; });
        body.appendChild(h("div", { class: "form-field" }, [labelEl(), control]));
      }
    }
    return body;
  }
  // ---- craft-quality.yml: 作業台/儀式で別々の品質ばらつき補正 (2026-08-01 分離) ----
  // 既定値は Java 側 CraftQualityConfig.SpreadTuning.IDENTITY と厳密に一致させること
  // (scale=1.0 / flat=0.0)。ここがズレると「editor で開いて保存しただけ」で yml の意味が変わり、
  // しかも条件が緩む方向なので誰も気付けない (lib/schema.js の同名定数と2本ミラー)。
  const CRAFT_QUALITY_SPREAD_DEFAULTS = {
    "upswing-scale": 1.0,
    "upswing-flat": 0.0,
    "downswing-reduction-scale": 1.0,
    "downswing-reduction-flat": 0.0
  };
  const CRAFT_QUALITY_SPREAD_LABELS = {
    workbench: "作業台クラフトのばらつき (workbench)",
    ritual: "儀式クラフトのばらつき (ritual)"
  };

  // 節が無い yml (分離前の出荷物) でもフォームを出すため、既定値で埋めてから描画する。
  // 既定値は恒等なので、埋めて保存しても挙動は分離前と変わらない。
  function appendCraftQualitySpreadSections(root, craftWorking) {
    root.appendChild(h("div", { class: "qd-note", text:
      "以下は品質抽選の「ばらつき」を作業台クラフトと儀式クラフトで別々に調整する設定です "
      + "(craft-quality.yml)。上振れ増加(craft-upswing-bonus)と下振れ抑制(craft-downswing-reduction)は"
      + "プレイヤー側の共通ステですが、経路ごとに「何倍で効かせるか(scale)」と「無条件に足すσ(flat)」を"
      + "別々に決められます。既定値(scale=1.0 / flat=0.0)は分離前とまったく同じ挙動です。" }));
    for (const section of ["workbench", "ritual"]) {
      let obj = craftWorking[section];
      if (!obj || typeof obj !== "object" || Array.isArray(obj)) {
        obj = {};
        craftWorking[section] = obj;
      }
      for (const [k, v] of Object.entries(CRAFT_QUALITY_SPREAD_DEFAULTS)) {
        if (typeof obj[k] !== "number") obj[k] = v;
      }
      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: CRAFT_QUALITY_SPREAD_LABELS[section] })],
        // scale/flat は小数を取るので必ず小数入力にする (1.0 は JS では整数扱いになるため)。
        [scalarSectionBody(obj, undefined, null, null, { forceFloat: true })]));
    }
  }

  // working の指定セクション(ネストしたオブジェクト)を順にカード化して root に追加する。
  function appendScalarSections(root, working, sectionKeys, labels, labelOpts) {
    for (const section of sectionKeys) {
      const obj = working[section];
      if (!obj || typeof obj !== "object" || Array.isArray(obj)) continue;
      root.appendChild(card([h("span", { class: "entry-key-label", text: (labels && labels[section]) || section })], [scalarSectionBody(obj, labelOpts)]));
    }
  }

  // ============================================================
  // quality.yml (tf-quality)  スカラー小フォーム
  //   craftData (craft-quality.yml の mode/drop) を受け取ると同じ画面下部に統合表示し、
  //   getCraftData() 経由で app.js が craft-quality.yml も一緒に保存する (品質定義タブへ統合)。
  //   ars-smithing EXP は skill-exp.yml (tf-skill-exp) へ分離済みでここには出さない。
  // ============================================================
  window.buildQualityForm = function buildQualityForm(data, craftData) {
    const working = data && typeof data === "object" ? data : {};
    const craftWorking = craftData && typeof craftData === "object" ? craftData : {};
    const root = h("div", { class: "dedicated-form" });
    const FIELDS = [
      { key: "max-quality", int: true },
      { key: "spread-up", int: false },
      { key: "spread-down", int: false },
      { key: "roll-spread-up", int: false },
      { key: "roll-spread-down", int: false },
      { key: "roll-center-inset", int: false },
      { key: "loot-base-quality", int: true },
      { key: "fishing-base-quality", int: true },
      { key: "give-default-quality", int: true },
      { key: "luck-potion-quality-per-level", int: false }
    ];
    // 分布プレビュー(下に埋め込み)は上振れσ/下振れσ/最大品質の現在値を読むため、フィールド編集で即再描画する。
    let distPanel = null;
    const body = h("div", { class: "const-body" });
    for (const f of FIELDS) {
      const control = window.numberInput(working[f.key], (v) => {
        if (v === null || v === "") return;
        working[f.key] = v;
        if (distPanel) distPanel.redraw();
      }, { int: f.int });
      body.appendChild(h("div", { class: "form-field" }, [window.fieldLabelEl(f.key), control]));
    }
    // 品質ティア(quality-tiers.yml)が定義されていれば、プラグインはティア数で max-quality を上書きする
    // (Nティア -> 品質 0..N-1)。プレビューの mode 段階もこの実効値に追従することを明示する。
    const tierMax = window.TF_QUALITY_TIER_MAX;
    const effectiveMax = () => (Number.isFinite(tierMax) ? tierMax : Number(working["max-quality"]));
    if (Number.isFinite(tierMax)) {
      body.appendChild(h("div", { class: "qd-note", text:
        "※ 品質ティアが" + (tierMax + 1) + "段定義されているため、実効最大品質は " + tierMax
        + " (品質 0.." + tierMax + ") です。上の max-quality はティア未定義時のフォールバックで、プレビューの mode 段階はティア数に追従します。" }));
    }
    root.appendChild(card([h("span", { class: "entry-key-label", text: "品質パラメータ" })], [body]));

    // 品質分布プレビュー: 品質値(mode)入力 → 品質ティア抽選(上下非対称な正規分布)を可視化。
    if (window.buildQualityDistPanel) {
      distPanel = window.buildQualityDistPanel(() => ({
        maxQuality: effectiveMax(),
        spreadUp: Number(working["spread-up"]),
        spreadDown: Number(working["spread-down"]),
        rollSpreadUp: Number(working["roll-spread-up"]),
        rollSpreadDown: Number(working["roll-spread-down"]),
        rollCenterInset: Number(working["roll-center-inset"])
      }));
      root.appendChild(card([h("span", { class: "entry-key-label", text: "品質分布プレビュー" })], [distPanel.element]));
    }

    // ---- craft-quality.yml の mode/drop を品質定義タブ下部に統合 ----
    // (ars-smithing EXP は skill-exp.yml へ分離。ここには mode/drop のみ)
    if (craftData !== undefined) {
      root.appendChild(h("div", { class: "qd-note", text:
        "以下はクラフト品質(mode)・敵ドロップ品質(drop)の設定です (craft-quality.yml)。保存すると品質パラメータと一緒に反映されます。" }));
      appendScalarSections(root, craftWorking, ["mode", "drop"], {
        mode: "クラフト品質 (mode)", drop: "敵ドロップ品質 (drop)"
      });
      appendCraftQualitySpreadSections(root, craftWorking);
    }

    return { element: root, getData: () => working, getCraftData: () => craftWorking };
  };

  // ============================================================
  // craft-quality.yml (tf-craft-quality)  単独フォールバック
  //   mode/drop のみ (ars-smithing は skill-exp.yml へ、統合表示は buildQualityForm 側で行う)。
  //   通常は品質定義タブに統合表示されるが、直接開いた場合のフォールバックとして残す。
  // ============================================================
  window.buildCraftQualityForm = function buildCraftQualityForm(data) {
    const working = data && typeof data === "object" ? data : {};
    const root = h("div", { class: "dedicated-form" });
    appendScalarSections(root, working, ["mode", "drop"], {
      mode: "クラフト品質 (mode)", drop: "敵ドロップ品質 (drop)"
    });
    appendCraftQualitySpreadSections(root, working);
    return { element: root, getData: () => working };
  };

  // ============================================================
  // skill-exp.yml (tf-skill-exp)  スキルEXP獲得設定
  //   ネストしたオブジェクト=1セクションをカード化。曲線は skills/base/*_progression.yml。
  // ============================================================
  window.buildSkillExpForm = function buildSkillExpForm(data, progressionData) {
    const working = data && typeof data === "object" ? data : {};
    const curves = progressionData && typeof progressionData === "object" ? progressionData : {};
    const root = h("div", { class: "dedicated-form" });

    // 旧producer由来の設定は現行実装から参照されない。表示から隠すだけでは再保存時に
    // 復活するため、フォーム構築時に移行対象を明示的に取り除く。
    const legacySkillExpKeys = {
      "ars-magic": ["exp-per-cast", "exp-per-mana"],
      combat: [
        "exp-per-hit", "mode", "damage-scale", "mob-level-scale",
        "same-target-cooldown-seconds", "by-skill"
      ]
    };
    for (const [section, keys] of Object.entries(legacySkillExpKeys)) {
      const target = working[section];
      if (!target || typeof target !== "object" || Array.isArray(target)) continue;
      for (const key of keys) delete target[key];
    }
    const legacyProgressionKeys = {
      // N5(2026-07-31): 弓術EXPを討伐時ベース(skill-exp.yml の combat.kill-exp)へ統一したので、
      // per-hit 式専用だった係数はすべて legacy。表示から隠すだけでは再保存で復活するため列挙して落とす。
      archery: [
        "daily_limit", "is_chunk_nerfed",
        "bow_exp_base", "crossbow_exp_base", "damage_exp_bonus",
        "distance_exp_multiplier_base", "distance_exp_multiplier", "distance_limit",
        "infinity_multiplier", "spawner_spawned_multiplier", "max_health_limitation",
        "pvp_multiplier", "entity_exp_multipliers"
      ],
      heavy_armor: ["exp_second_piece", "daily_limit"],
      light_armor: ["exp_second_piece", "daily_limit"],
      heavy_weapons: ["exp_per_damage", "exp_enemies_nerfed"],
      light_weapons: ["exp_per_damage", "exp_enemies_nerfed"],
      mining: ["exp_per_break"],
      smithing: [
        "durability_tools_exp_multiplier_stack",
        "durability_tools_exp_multiplier_maximum",
        "durability_armors_exp_multiplier_stack",
        "durability_armors_exp_multiplier_maximum"
      ]
    };
    for (const [skillId, progression] of Object.entries(curves)) {
      const exp = progression && progression.experience;
      if (!exp || typeof exp !== "object" || Array.isArray(exp)) continue;
      delete exp.legacy;
      delete exp.daily_limit;
      delete exp.daily_limit_decay_percent;
      for (const key of legacyProgressionKeys[skillId] || []) delete exp[key];
    }

    const SKILL_LABELS = {
      "ars-smithing": "Ars鍛冶",
      "ars-magic": "Ars魔法",
      combat: "戦闘（TrinityForge付与分）",
      alchemy: "錬金術",
      archery: "弓術",
      digging: "掘削",
      enchanting: "エンチャント",
      farming: "農業",
      fishing: "釣り",
      heavy_armor: "重装防具",
      heavy_weapons: "重武器",
      light_armor: "軽装防具",
      light_weapons: "軽武器",
      mining: "採掘",
      power: "総合",
      smithing: "鍛冶",
      woodcutting: "伐採",
      // 2026-07-29: 「〜の曲線」を外した。曲線カードは獲得EXPカードへ統合され、
      // 同じスキルが 2 つの別名で 2 箇所に出る状態が解消されたため。
      ars_magic: "Ars魔法",
      ars_smithing: "Ars鍛冶",
      "spot-diminishing": "同一地点での連続獲得逓減",
      "gathering": "採取EXP算出",
      "level-diminishing": "スキルレベルによるEXP逓減"
    };
    function skillLabel(id) {
      return SKILL_LABELS[id] || id;
    }

    root.appendChild(h("div", { class: "sub-title", text: "行動あたりの獲得EXP" }));
    root.appendChild(h("div", { class: "empty-hint", text:
      "ここはTrinityForgeとArsPaperが付与するレートです。"
      + "各職業のEXPとレベル曲線は下の「レベル曲線・獲得レート」で編集します。" }));

    // dungeon-only-exp: 戦闘6スキル(重武器/軽武器/弓術/重装甲/軽装甲/ARS_MAGIC)のEXPをダンジョン限定にするか。
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "ダンジョン限定EXP" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("dungeon-only-exp", {
            label: "戦闘6スキルのEXPをダンジョン限定にする",
            desc: "重武器・軽武器・弓術・重装防具・軽装防具・Ars魔法のEXPを、EliteMobsダンジョン内だけに限定します。"
              + "通常ワールド等では加算されません。無効にするとダンジョン外では「ダンジョン外EXP倍率」が適用されます。"
              + "Ars鍛冶・採取・釣りなどの非戦闘EXPは対象外です。",
            hideKey: true
          }),
          window.checkboxInput(working["dungeon-only-exp"] !== false, (v) => { working["dungeon-only-exp"] = v; })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("outside-dungeon-exp-rate", { hideKey: true }),
          window.numberInput(working["outside-dungeon-exp-rate"], (v) => {
            if (v === null || v === "") return;
            working["outside-dungeon-exp-rate"] = v;
          }, { int: false })
        ]),
        // 2026-08-21 新設: ダンジョン内側の倍率。外側とセットで「ダンジョンは外の何倍か」が決まる。
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("dungeon-exp-rate", { hideKey: true }),
          window.numberInput(working["dungeon-exp-rate"], (v) => {
            if (v === null || v === "") return;
            working["dungeon-exp-rate"] = v;
          }, { int: false })
        ])
      ]
    ));

    // break-vanilla-exp.base-exp: 破壊時バニラEXP(スキルツリーの解放ノード)で落ちるバニラEXPオーブの量。
    // 2026-08-18 ユーザー要望で 1.0 -> 0.25 へ下げつつ config 化した。小数を書いてよい(端数は持ち越し)。
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "破壊時バニラEXP" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("base-exp", {
            label: "1回の破壊あたりのベース量",
            desc: "スキルツリーで「破壊時バニラEXP」を解放したプレイヤーが採取扱いのブロックを壊したときに"
              + "落ちる【バニラのEXPオーブ】の量です(TFのスキルEXPとは別物)。"
              + "実際の付与量はこの値 ×(1 + 倍率ステの合計)。0.25(既定)は旧実装1.0の1/4。"
              + "0にすると解放しても増えません。小数を書いてよく、端数はプレイヤーごとに持ち越して"
              + "1貯まったぶんだけ渡します(0.25なら4回壊して1EXP)。",
            hideKey: true
          }),
          window.numberInput(
            (working["break-vanilla-exp"] || {})["base-exp"],
            (v) => {
              if (v === null || v === "") return;
              if (!working["break-vanilla-exp"]) working["break-vanilla-exp"] = {};
              working["break-vanilla-exp"]["base-exp"] = v;
            },
            { int: false }
          )
        ])
      ]
    ));

    // power.levels-per-skill-point: 総合(POWER)を何レベル進めるごとにスキルポイントを1点与えるか。
    // ページ上部の専用カードだけに出す。汎用セクションループ側でも working.power は
    // (progression-power の曲線を持つため)「レベル曲線・獲得レート」の総合カードへ合流するので、
    // このキーだけは SECTION_EXCLUDED_KEYS で汎用ループから除外し二重描画を防ぐ。
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "総合(POWER)とスキルポイント" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("levels-per-skill-point", {
            label: "1スキルポイントあたりの総合レベル",
            desc: "総合(POWER)レベルがこの値だけ上がるごとに、スキルツリーのポイントを1点付与します。"
              + "1 = 1レベルごとに1点(従来の挙動)。2 なら2レベルで1点。"
              + "ポイント総数 = 初期3点 + 総合レベル ÷ この値(切り捨て)。"
              + "⚠️ この値を大きくすると既存プレイヤーの獲得済みポイントが減ります。既に使った分が"
              + "新しい上限を超える場合は、ログイン時の救済処理が「使った分はそのまま・残りを0」に"
              + "整えます(パークは剥がしません)。",
            hideKey: true
          }),
          window.numberInput(
            (working.power && typeof working.power === "object" && !Array.isArray(working.power)
              && typeof working.power["levels-per-skill-point"] === "number")
              ? working.power["levels-per-skill-point"] : 1,
            (v) => {
              if (v === null || v === "") return;
              const n = Math.max(1, Math.floor(Number(v)));
              if (!Number.isFinite(n)) return;
              ensureObj(working, "power")["levels-per-skill-point"] = n;
            },
            { int: true }
          )
        ])
      ]
    ));

    // 曲線式のプレースホルダ / 演算子ヘルプ
    root.appendChild(h("div", { class: "entry-card se-help-card" }, [
      h("div", { class: "entry-head" }, [
        h("span", { class: "entry-key-label", text: "曲線式の書き方" })
      ]),
      h("div", { class: "entry-body" }, [
        h("p", { class: "form-hint", text: "TF進行数式です。レベル到達に必要なEXPを %level% で参照します。" }),
        h("ul", { class: "se-help-list" }, [
          h("li", {}, [h("code", { text: "%level%" }), h("span", { text: " — 現在レベル（1始まり）。必須プレースホルダ。" })]),
          h("li", {}, [h("code", { text: "+ - * /" }), h("span", { text: " — 四則演算" })]),
          h("li", {}, [h("code", { text: "^" }), h("span", { text: " — 累乗（内部では ** に変換）" })]),
          h("li", {}, [h("code", { text: "()" }), h("span", { text: " — 括弧で優先順位" })]),
          h("li", {}, [h("span", { text: "例: " }), h("code", { text: "50 + (%level% ^ 2) * 10" })])
        ])
      ])
    ]));

    // exp-display / level-up は下の専用カードで描画するため、汎用セクションループでは
    // 二重描画しないよう除外する。
    const DEDICATED_SECTION_KEYS = new Set(["exp-display", "level-up", "use-level-scaling"]);
    const expDisplay = ensureObj(working, "exp-display");
    const levelUp = ensureObj(working, "level-up");
    const useLevelScaling = ensureObj(working, "use-level-scaling");

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "EXP獲得表示" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("mode", {
            label: "表示モード",
            desc: "ボスバー表示では、レベル・現在EXP・次レベルまでの必要EXP・獲得量を表示します。"
              + "アクションバー表示では、獲得量だけを表示します。",
            hideKey: true
          }),
          window.listSelect({
            value: expDisplay.mode || "bossbar",
            options: [
              { value: "bossbar", primary: "ボスバー表示" },
              { value: "actionbar", primary: "アクションバー表示" }
            ],
            onChange: (v) => { expDisplay.mode = v; }
          })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("bossbar-seconds", {
            label: "ボスバー表示秒数",
            desc: "ボスバーを表示してから自動で隠すまでの秒数です。",
            hideKey: true
          }),
          window.numberInput(expDisplay["bossbar-seconds"], (v) => {
            if (v === null || v === "") return;
            expDisplay["bossbar-seconds"] = v;
          }, { int: false })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("max-concurrent-bossbars", {
            label: "同時表示ボスバー数上限",
            desc: "複数スキルのEXPを同時に獲得しても、この数を超えてボスバーを重ねません。",
            hideKey: true
          }),
          window.numberInput(expDisplay["max-concurrent-bossbars"], (v) => {
            if (v === null || v === "") return;
            expDisplay["max-concurrent-bossbars"] = v;
          }, { int: true })
        ])
      ]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "レベルアップ通知" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("chat", {
            label: "チャット通知",
            desc: "レベルアップ時にチャットへ通知します。",
            hideKey: true
          }),
          window.checkboxInput(levelUp.chat !== false, (v) => { levelUp.chat = v; })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("sound-enabled", {
            label: "効果音を鳴らす",
            desc: "レベルアップ時に効果音を再生します。",
            hideKey: true
          }),
          window.checkboxInput(levelUp["sound-enabled"] !== false, (v) => { levelUp["sound-enabled"] = v; })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("sound", {
            label: "効果音 (Bukkit Sound)",
            desc: "Bukkit の Sound 列挙値の名前 (例: ENTITY_PLAYER_LEVELUP)。"
              + "専用の音選択UIはこのエディタに存在しないためテキスト入力。",
            hideKey: true
          }),
          window.textInput(levelUp.sound, (v) => { levelUp.sound = v; })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("title-every-levels", {
            label: "タイトル表示間隔(レベル数)",
            desc: "このレベル数の倍数に到達したとき、通常のチャット・効果音に加えて画面タイトルでも通知します。",
            hideKey: true
          }),
          window.numberInput(levelUp["title-every-levels"], (v) => {
            if (v === null || v === "") return;
            levelUp["title-every-levels"] = v;
          }, { int: true })
        ])
      ]
    ));

    // 使用可能レベル連動EXP: per-level に定義されたスキルはすべて表示する。
    // producer追加のたびにUI側の許可リスト更新を要求しないことで、設定だけ編集不能になるのを防ぐ。
    (function () {
      const perLevel = ensureObj(useLevelScaling, "per-level");
      const PER_LEVEL_CONTEXT = {
        smithing: "鍛冶 (作成したツール/装備の使用可能レベル)",
        "ars-smithing": "Ars鍛冶 (作成したArs装備の使用可能レベル)",
        woodcutting: "伐採 (破壊に使ったツールの使用可能レベル)",
        mining: "採掘 (破壊に使ったツールの使用可能レベル)",
        digging: "切削 (破壊に使ったツールの使用可能レベル)"
      };
      const perLevelFields = Object.keys(perLevel).map((key) =>
        h("div", { class: "form-field" }, [
          window.fieldLabelEl(key, {
            label: PER_LEVEL_CONTEXT[key] || `${skillLabel(key)} (使用可能レベル)`,
            hideKey: true
          }),
          window.numberInput(perLevel[key], (v) => {
            if (v === null || v === "") return;
            perLevel[key] = v;
          }, { int: false })
        ]));

      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "使用可能レベル連動EXP" })],
        [
          h("div", { class: "form-field" }, [
            window.fieldLabelEl("enabled", {
              label: "機能を有効にする",
              desc: "使用したツールの使用可能レベル(鍛冶は作成したツール/装備の使用可能レベル)が高いほど、"
                + "獲得EXPが増える。使用可能レベル0(素手・バニラツール・item-statsにプロファイル無し)は"
                + "常に倍率1.0=現状維持。倍率=1+使用可能レベル×per-level。対象はper-levelに設定されたスキルのみ、"
                + "農業は対象外。爆破採掘(TNT等)にも掛からない。",
              hideKey: true
            }),
            window.checkboxInput(useLevelScaling.enabled !== false, (v) => { useLevelScaling.enabled = v; })
          ]),
          h("div", { class: "form-field" }, [
            window.fieldLabelEl("max-multiplier", {
              label: "倍率上限",
              desc: "レベルごとの加算量を大きくしたときの安全上限です。倍率はこの値を超えません。既定は3.0です。",
              hideKey: true
            }),
            window.numberInput(useLevelScaling["max-multiplier"], (v) => {
              if (v === null || v === "") return;
              useLevelScaling["max-multiplier"] = v;
            }, { int: false })
          ]),
          ...perLevelFields
        ]
      ));
    })();

    // レベル曲線 + experience 配下のEXP生産レート/行動テーブルを編集する。
    // producer追加時にUI側の許可リスト更新を要求すると設定だけ編集不能になるため、
    // max_level / exp_level_curve 以外を再帰描画する。配列とnullは値を温存するが、
    // 数値表ではないため入力欄にはしない。
    // (buildCurveBody がスキルカードの描画中にこれを読むので、宣言はループより前に置く)
    const PROGRESSION_DEDICATED_KEYS = new Set(["max_level", "exp_level_curve"]);

    const sections = Object.keys(working).filter((k) =>
      working[k] && typeof working[k] === "object" && !Array.isArray(working[k]) && !DEDICATED_SECTION_KEYS.has(k));

    // 2026-07-29: 同じスキルの設定が「行動あたりの獲得EXP」カードと
    // 「レベル曲線」カードに分かれていた (Ars 魔法 / Ars 鍛冶 / 鍛冶の 3 スキルが該当)。
    // skill-exp.yml はハイフン id (ars-magic)、progression/*.yml はアンダースコア id
    // (ars_magic) というファイル都合で別キーになっていただけで、編集者から見れば同じスキル。
    // 1 スキル 1 カードにまとめ、中を小見出しで 2 ブロックに分ける。
    const normalizeSkillId = (id) => String(id).replace(/-/g, "_");
    const curveBySkill = new Map();
    for (const k of Object.keys(curves)) {
      if (curves[k] && curves[k].experience) curveBySkill.set(normalizeSkillId(k), k);
    }

    /** カード内の小見出し (1 カードに 2 ブロック入るときだけ付ける)。 */
    function subHeading(text) {
      return h("div", { class: "se-card-subhead", text });
    }

    // クラフト系の exp-per-craft は skill-exp.yml 上ではキー名が同じでも意味が違う。
    // 鍛冶 = 作業台での通常クラフト / Ars鍛冶 = Ars装備(儀式クラフト含む)。
    // 説明文を1本にすると必ずどちらかが嘘になるのでスキルごとに差し替える。
    const SECTION_FIELD_OVERRIDES = {
      smithing: {
        "exp-per-craft": {
          label: "クラフト1回EXP",
          desc: "作業台での通常クラフト1回につき付与する 鍛冶(SMITHING) 経験値。"
            + "対象は武器/防具/道具カテゴリのアイテムで、Ars装備は含まない(そちらはArs鍛冶に入る)。"
            + "シフトクリックの一括クラフトでも1回分だけ付く。"
        }
      },
      "ars-smithing": {
        // 2026-08-17: 定額EXP(exp-per-craft)は機能ごと廃止した。古い yml に行が残っている
        // 環境ではまだこの欄が出るので、「読まれない」ことを明示して消せるようにしておく。
        "exp-per-craft": {
          label: "【廃止】定額EXP (もう読まれません)",
          desc: "2026-08-17 に機能ごと廃止しました。ここに値を入れてもプラグインは読みません。"
            + "Ars鍛冶EXPは「下の素材別EXPの合計 ＋ 消費ソース1あたりの追加EXP」だけで決まります。"
            + "この行は削除して構いません(残っていても無害です)。"
        },
        "exp-per-source": {
          label: "消費ソース1あたりの追加EXP",
          desc: "儀式で実際に消費したソース量に比例して Ars鍛冶(ARS_SMITHING) EXP を追加します。"
            + "下の素材別EXPの合計に「消費ソース量 × この値」を足します(置き換えではありません)。"
            + "0 = ソースを加味しない(既定)。"
            + "⚠️ ソース要求量は階梯とともに桁で増える(ソースの欠片100 → 無限のソース核45,000,000)ため、"
            + "1.0 のような値を入れると上位儀式1回で最大レベルに届きます。0.001 程度から様子を見てください。"
            + "ソース消費軽減(source_cost_reduction)が効いた後の実消費量で計算されます。"
        },
        "max-source-exp-per-craft": {
          label: "消費ソース由来EXPの上限(儀式1回)",
          desc: "上の「消費ソース1あたりの追加EXP」を掛けた結果にだけ効く上限です"
            + "(素材別EXPには掛かりません)。0 = 上限なし、既定は 100000。"
            + "⚠️ ソース要求量は階梯とともに桁で増える(ソースの欠片100 → 無限のソース核45,000,000)ため、"
            + "係数が小さくても上位儀式1回で最大レベルぶんのEXPが出てしまうのを防ぐ天井です。"
        },
        "exp-per-material": {
          label: "素材別EXP (Ars鍛冶専用の表)",
          desc: "儀式・Ars作業台で消費した素材1個あたりの Ars鍛冶(ARS_SMITHING) EXP。"
            + "⚠️ この表は鍛冶(SMITHING)カードの素材別EXPとは【別の表】です(2026-08-17 に分離)。"
            + "ここを編集しても通常鍛冶のEXPは変わりません。初期値は分離時点の通常鍛冶の表の複製です。"
            + "ここに無い素材は 0 として合計されます(その素材ぶんが乗らないだけで、他の素材ぶんは残ります)。"
        }
      }
    };

    // 汎用セクションループから除外するキー。power.levels-per-skill-point は
    // ページ上部の専用カードだけに出す(このセクション自体は curveBySkill 経由で
    // 「レベル曲線・獲得レート」の総合カードへ合流するため、キー単位で除外する)。
    const SECTION_EXCLUDED_KEYS = {
      power: new Set(["levels-per-skill-point"])
    };

    // 2026-07-29: 曲線を持つスキルのカードは「レベル曲線・獲得レート」の中へ入れる。
    // 曲線を持たない設定 (spot-diminishing / gathering / combat など) だけが上に残る。
    const sectionsWithoutCurve = sections.filter((s) => !curveBySkill.has(normalizeSkillId(s)));
    const sectionBySkill = new Map();
    for (const s of sections) {
      const curveKey = curveBySkill.get(normalizeSkillId(s));
      if (curveKey) sectionBySkill.set(curveKey, s);
    }

    if (!sections.length && !curveBySkill.size) {
      root.appendChild(emptyGuide("スキルEXP設定がまだありません。",
        "skill-exp.yml に ars-smithing.exp-per-craft などを定義すると、ここで編集できます。"));
    } else {
      for (const section of sectionsWithoutCurve) {
        const cardEl = h("details", { class: "entry-card se-skill-card", open: true });
        cardEl.appendChild(h("summary", { class: "entry-head se-skill-summary" }, [
          h("span", { class: "entry-key-label", text: skillLabel(section) })
        ]));
        // 2026-08-17: Ars鍛冶の素材表は ars-smithing.exp-per-material としてこのセクション自身に
        // 入ったので、scalarSectionBody が素材マップとして描く(鍛冶の表を借りてこない)。
        const bodyChildren = [scalarSectionBody(working[section], { hideKey: true }, SECTION_FIELD_OVERRIDES[section], SECTION_EXCLUDED_KEYS[section])];
        cardEl.appendChild(h("div", { class: "entry-body" }, bodyChildren));
        root.appendChild(cardEl);
      }
    }

    const curveKeys = Object.keys(curves).filter((k) => curves[k] && curves[k].experience);
    if (curveKeys.length) {
      root.appendChild(h("div", { class: "sub-title", text: "レベル曲線・獲得レート" }));
      root.appendChild(h("div", { class: "empty-hint", text:
        "戦闘武器の行動EXPは上の戦闘設定が基準です。ここではレベル曲線と、採取・防具・鍛冶・錬金などを編集します。" }));
      for (const skillId of curveKeys) {
        const section = sectionBySkill.get(skillId);
        const cardEl = h("details", { class: "entry-card se-skill-card", open: false });
        cardEl.appendChild(h("summary", { class: "entry-head se-skill-summary" }, [
          h("span", { class: "entry-key-label", text: skillLabel(section || skillId) })
        ]));
        if (section) {
          // 行動あたりの獲得EXP (skill-exp.yml) と曲線 (progression/*.yml) は別ファイルだが
          // 編集者から見れば同じスキルなので 1 カードに小見出しで 2 ブロック入れる。
          const body = h("div", { class: "entry-body" });
          body.appendChild(subHeading("行動あたりの獲得EXP"));
          body.appendChild(scalarSectionBody(working[section], { hideKey: true },
            SECTION_FIELD_OVERRIDES[section], SECTION_EXCLUDED_KEYS[section]));
          body.appendChild(subHeading("レベル曲線・獲得レート"));
          body.appendChild(buildCurveBody(skillId));
          cardEl.appendChild(body);
        } else {
          cardEl.appendChild(buildCurveBody(skillId));
        }
        root.appendChild(cardEl);
      }
    }

    /**
     * 1 スキル分のレベル曲線ブロック。単独カードと、獲得EXPカードへの合流の
     * 両方から呼ぶ (分かれていた 2 カードを 1 つにするための共通化)。
     */
    function buildCurveBody(skillId) {
      if (!curves[skillId].experience) curves[skillId].experience = {};
      const exp = curves[skillId].experience;
      const body = h("div", { class: "entry-body se-curve-body" });
      const chart = h("div", { class: "qd-chart" });
      function redrawChart() {
        chart.innerHTML = "";
        const svg = renderExpCurveSvg(exp.exp_level_curve || "", exp.max_level || 100);
        if (svg) chart.appendChild(svg);
        else chart.appendChild(h("div", { class: "empty-hint", text: "曲線式を評価できません" }));
      }
      const curveInput = window.textInput(exp.exp_level_curve || "", (v) => {
        exp.exp_level_curve = v;
        redrawChart();
      });
      const maxInput = window.numberInput(exp.max_level, (v) => {
        exp.max_level = v == null ? 100 : v;
        redrawChart();
      }, { int: true });
      body.appendChild(fieldRow("exp_level_curve", curveInput));
      body.appendChild(fieldRow("max_level", maxInput));
      body.appendChild(scalarSectionBody(
        exp,
        { hideKey: true },
        null,
        PROGRESSION_DEDICATED_KEYS,
        { forceFloat: true }
      ));
      body.appendChild(chart);
      redrawChart();
      return body;
    }

    function fieldRow(key, control) {
      return h("div", { class: "form-field" }, [
        window.fieldLabelEl(key, { hideKey: true }),
        control
      ]);
    }

    return {
      element: root,
      getData: () => working,
      getProgressionData: () => curves
    };
  };

  /** Evaluate the TF level expression (%level% + ...) with ^ as power; return SVG polyline with axes. */
  function renderExpCurveSvg(formula, maxLevel) {
    const maxLv = Math.max(1, Math.min(256, Number(maxLevel) || 100));
    const points = [];
    let peak = 1;
    for (let lv = 1; lv <= maxLv; lv++) {
      const y = evalLevelFormula(formula, lv);
      if (y == null || !Number.isFinite(y)) return null;
      points.push([lv, y]);
      if (y > peak) peak = y;
    }
    const w = 480, h = 180;
    const padL = 52, padR = 12, padT = 12, padB = 28;
    const plotW = w - padL - padR;
    const plotH = h - padT - padB;
    function xOf(lv) { return padL + (lv - 1) / Math.max(1, maxLv - 1) * plotW; }
    function yOf(v) { return padT + plotH - (v / peak) * plotH; }
    const pts = points.map(([x, y]) => `${xOf(x).toFixed(1)},${yOf(y).toFixed(1)}`).join(" ");
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
    svg.setAttribute("width", "100%");
    svg.setAttribute("height", String(h));
    const axisStroke = "currentColor";
    const axis = document.createElementNS("http://www.w3.org/2000/svg", "path");
    axis.setAttribute("d", `M${padL} ${padT} V${padT + plotH} H${padL + plotW}`);
    axis.setAttribute("fill", "none");
    axis.setAttribute("stroke", axisStroke);
    axis.setAttribute("stroke-width", "1");
    axis.setAttribute("opacity", "0.5");
    svg.appendChild(axis);

    const xTicks = [1, Math.round(maxLv / 2), maxLv].filter((v, i, a) => a.indexOf(v) === i);
    for (const lv of xTicks) {
      const x = xOf(lv);
      const tick = document.createElementNS("http://www.w3.org/2000/svg", "line");
      tick.setAttribute("x1", x); tick.setAttribute("x2", x);
      tick.setAttribute("y1", padT + plotH); tick.setAttribute("y2", padT + plotH + 4);
      tick.setAttribute("stroke", axisStroke); tick.setAttribute("stroke-width", "1"); tick.setAttribute("opacity", "0.6");
      svg.appendChild(tick);
      const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
      label.setAttribute("x", x); label.setAttribute("y", h - 6);
      label.setAttribute("text-anchor", "middle"); label.setAttribute("font-size", "10");
      label.setAttribute("fill", "currentColor"); label.textContent = String(lv);
      svg.appendChild(label);
    }
    const yTicks = [0, peak / 2, peak];
    for (const v of yTicks) {
      const y = yOf(v);
      const tick = document.createElementNS("http://www.w3.org/2000/svg", "line");
      tick.setAttribute("x1", padL - 4); tick.setAttribute("x2", padL);
      tick.setAttribute("y1", y); tick.setAttribute("y2", y);
      tick.setAttribute("stroke", axisStroke); tick.setAttribute("stroke-width", "1"); tick.setAttribute("opacity", "0.6");
      svg.appendChild(tick);
      const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
      label.setAttribute("x", padL - 6); label.setAttribute("y", y + 3);
      label.setAttribute("text-anchor", "end"); label.setAttribute("font-size", "10");
      label.setAttribute("fill", "currentColor");
      label.textContent = v >= 1000 ? (v / 1000).toFixed(1) + "k" : String(Math.round(v));
      svg.appendChild(label);
    }
    const xTitle = document.createElementNS("http://www.w3.org/2000/svg", "text");
    xTitle.setAttribute("x", padL + plotW / 2); xTitle.setAttribute("y", h - 2);
    xTitle.setAttribute("text-anchor", "middle"); xTitle.setAttribute("font-size", "9");
    xTitle.setAttribute("fill", "currentColor"); xTitle.setAttribute("opacity", "0.7");
    xTitle.textContent = "level";
    // keep above tick labels — skip duplicate
    const yTitle = document.createElementNS("http://www.w3.org/2000/svg", "text");
    yTitle.setAttribute("x", 10); yTitle.setAttribute("y", padT + 8);
    yTitle.setAttribute("font-size", "9"); yTitle.setAttribute("fill", "currentColor"); yTitle.setAttribute("opacity", "0.7");
    yTitle.textContent = "exp";
    svg.appendChild(yTitle);

    const poly = document.createElementNS("http://www.w3.org/2000/svg", "polyline");
    poly.setAttribute("fill", "none");
    poly.setAttribute("stroke", "currentColor");
    poly.setAttribute("stroke-width", "2");
    poly.setAttribute("points", pts);
    svg.appendChild(poly);
    return svg;
  }

  function evalLevelFormula(formula, level) {
    if (!formula || typeof formula !== "string") return null;
    let expr = formula.replace(/%level%/gi, String(level));
    // Legacy-compatible level expression: ^ is power.
    expr = expr.replace(/\^/g, "**");
    if (!/^[\d\s+\-*/().,**]+$/.test(expr.replace(/\*\*/g, ""))) {
      // allow digits ops only after substitution
    }
    try {
      // eslint-disable-next-line no-new-func
      return Function(`"use strict"; return (${expr});`)();
    } catch (_) {
      return null;
    }
  }

  // ============================================================
  // quality-tiers.yml (tf-quality-tiers)  tiers: [{name, color}]
  //   color は colorPickerInput mm-color (gradient 等は raw フォールバックで温存)。
  // ============================================================
  window.buildQualityTiersForm = function buildQualityTiersForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (!Array.isArray(working.tiers)) working.tiers = [];
    const tiers = working.tiers;
    const root = h("div", { class: "dedicated-form" });

    function render() {
      root.innerHTML = "";
      if (tiers.length === 0) root.appendChild(emptyGuide("品質ティアがまだありません。", "「+ ティア追加」で名前と色を持つティアを下位から順に登録します。"));
      tiers.forEach((t, idx) => root.appendChild(renderRow(t, idx)));
      root.appendChild(h("div", { class: "form-actions" }, [
        h("button", { class: "btn", type: "button", text: "+ ティア追加", onclick: () => { tiers.push({ name: "", color: "white" }); render(); } })
      ]));
    }

    function renderRow(t, idx) {
      if (!t || typeof t !== "object") return h("div");
      // 色付きプレビュー名。
      const nameSpan = h("span", { class: "tier-name-preview" });
      // "gradient:#a:#b:#c" を両端色 {from,to} に近似する (プレビュー用)。gradientでなければ null。
      function gradientEnds(color) {
        const m = String(color == null ? "" : color).trim().match(/^gradient:(.+)$/i);
        if (!m) return null;
        const hexes = m[1].split(":").map((s) => s.trim())
          .filter((s) => /^#?[0-9a-fA-F]{6}$/.test(s)).map((s) => (s[0] === "#" ? s : "#" + s));
        return hexes.length >= 2 ? { from: hexes[0], to: hexes[hexes.length - 1] } : null;
      }
      function paintName() {
        const nm = t.name == null || t.name === "" ? "(無名)" : String(t.name);
        const grad = window.COLORS ? gradientEnds(t.color) : null;
        if (grad) {
          // gradient は単一hexが無いので、文字ごとに補間着色してプレビューする (両端色で近似)。
          nameSpan.textContent = "";
          nameSpan.style.color = "inherit";
          for (const sp of window.COLORS.gradientSpans(nm, grad.from, grad.to)) {
            nameSpan.appendChild(h("span", { text: sp.char, style: `color:${sp.hex}` }));
          }
          return;
        }
        const tok = window.COLORS ? window.COLORS.normalizeColorToken(t.color) : null;
        nameSpan.textContent = nm;
        nameSpan.style.color = (tok && tok.hex) ? tok.hex : "inherit";
      }
      const nameInput = window.textInput(t.name, (v) => { t.name = v; paintName(); });
      // M-1: 「クリア」(空文字)は color キーの削除に統一する。
      const colorPicker = window.colorPickerInput
        ? window.colorPickerInput(t.color, "mm-color", (v) => { if (v === "") delete t.color; else t.color = v; paintName(); })
        : window.textInput(t.color, (v) => { if (v === "") delete t.color; else t.color = v; paintName(); });
      paintName();

      const head = [
        h("span", { class: "entry-key-label", text: `品質 ${idx}` }),
        nameSpan,
        h("div", { class: "spacer" }),
        h("button", { class: "btn-small", type: "button", text: "↑", title: "上へ", onclick: () => { if (idx > 0) { const tmp = tiers[idx - 1]; tiers[idx - 1] = tiers[idx]; tiers[idx] = tmp; render(); } } }),
        h("button", { class: "btn-small", type: "button", text: "↓", title: "下へ", onclick: () => { if (idx < tiers.length - 1) { const tmp = tiers[idx + 1]; tiers[idx + 1] = tiers[idx]; tiers[idx] = tmp; render(); } } }),
        h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: () => { tiers.splice(idx, 1); render(); } })
      ];
      const body = gridRow([
        fieldRow(null, nameInput, "ティア名 (name)"),
        fieldRow(null, colorPicker, "色 (color)")
      ]);
      return card(head, [body]);
    }

    render();
    return { element: root, getData: () => working };
  };

  // ============================================================
  // smithing-gimmick.yml (tf-smithing-gimmick)
  //   auto-mode-multiplier: ホッパー自動投入時、手動投入と比べた精錬速度/精錬ボーナスの減衰係数。
  //   0.0=自動投入では効果無効、1.0=手動と同じ効果。Java側 SmithingGimmickConfig#clamp01
  //   と同じ [0,1] 範囲でクランプする (範囲外は保存時にサーバがエラーを返す)。
  //   2026-07-28(数値のギミックyml集約): furnace-smelt.speed/bonus のtierテーブルもここに追加。
  //   スキルツリー側(feature:furnace-smelt-speed/bonus)はtier番号だけを持ち、実際の%はここで解決する
  //   (SmithingGimmickConfig.java のJavaDoc参照)。
  // ============================================================
  window.buildSmithingGimmickForm = function buildSmithingGimmickForm(data, opts) {
    const working = data && typeof data === "object" ? data : {};
    // T6 (2026-07-26): crafting-features.yml の disassembly(解体)サブツリーをこのタブへコンパニオン
    // 表示する(表示名を「精錬ギミック」→「鍛冶ギミック」へリネームしたのに合わせて解体もここへ集約)。
    // 保存は getExtraSaves 経由で crafting-features へ(丸ごと読み込み・丸ごと書き戻し、他のサブツリーは
    // normalizeCraftingFeaturesWorking がそのまま温存する)。
    const craftingFeaturesData = opts && opts.craftingFeaturesData && typeof opts.craftingFeaturesData === "object"
      ? opts.craftingFeaturesData : undefined;
    const hasCraftingFeatures = craftingFeaturesData !== undefined;
    const craftingFeaturesWorking = hasCraftingFeatures ? craftingFeaturesData : {};
    if (hasCraftingFeatures && typeof window.normalizeCraftingFeaturesWorking === "function") {
      window.normalizeCraftingFeaturesWorking(craftingFeaturesWorking);
    } else if (hasCraftingFeatures && (craftingFeaturesWorking.disassembly == null || typeof craftingFeaturesWorking.disassembly !== "object")) {
      craftingFeaturesWorking.disassembly = {};
    }
    const DEFAULT = 0.25;
    if (working["auto-mode-multiplier"] === undefined || working["auto-mode-multiplier"] === null) {
      working["auto-mode-multiplier"] = DEFAULT;
    }
    const root = h("div", { class: "dedicated-form" });

    const body = h("div", { class: "const-body" });
    const valueLabel = h("span", { class: "cf-muted" });
    const slider = h("input", {
      type: "range", min: "0", max: "1", step: "0.01",
      value: String(working["auto-mode-multiplier"])
    });
    const numInput = window.numberInput(working["auto-mode-multiplier"], (v) => {
      let n = v == null ? DEFAULT : Number(v);
      if (!Number.isFinite(n)) n = DEFAULT;
      n = Math.min(1, Math.max(0, n));
      working["auto-mode-multiplier"] = n;
      slider.value = String(n);
      updateLabel();
    }, { int: false });
    slider.addEventListener("input", () => {
      const n = Number(slider.value);
      working["auto-mode-multiplier"] = n;
      numInput.value = String(n);
      updateLabel();
    });
    function updateLabel() {
      const pct = Math.round(Number(working["auto-mode-multiplier"]) * 100);
      valueLabel.textContent = `手動投入比 ${pct}%`;
    }
    updateLabel();

    body.appendChild(h("div", { class: "form-field" }, [
      window.fieldLabelEl("auto-mode-multiplier", {
        label: "自動投入(ホッパー)の減衰係数",
        desc: "ホッパーで自動投入した精錬物には、手動投入時と比べてこの係数を掛けた精錬速度/精錬ボーナスしか適用されません。"
          + "0.0=自動投入では完全無効、1.0=手動投入と同じ効果。既定 0.25。範囲は0〜1。"
          + "精錬速度/精錬ボーナス自体の%はスキルツリー側(furnace-smelt-speed / furnace-smelt-bonus)で設定し、このファイルはその減衰率のみを持ちます。"
      }),
      h("div", { class: "stat-row smithing-gimmick-row" }, [slider, numInput, valueLabel])
    ]));
    root.appendChild(card([h("span", { class: "entry-key-label", text: "鍛冶ギミック (smithing-gimmick.yml)" })], [body]));

    // 2026-07-28(数値のギミックyml集約): furnace-smelt-speed/bonus は SCALE化(tier番号)され、
    // 実際の%はここ(furnace-smelt.speed/bonus)のtierテーブルへ移った。
    ensureObj(working, "furnace-smelt");
    const speedSection = ensureObj(working["furnace-smelt"], "speed");
    const bonusSection = ensureObj(working["furnace-smelt"], "bonus");
    if (speedSection.percent == null) speedSection.percent = 10;
    if (bonusSection.percent == null) bonusSection.percent = 10;

    // .const-body は 2 列グリッドなので、そのまま入れると「%入力の右隣に小見出し」
    // 「tier表の右隣に追加ボタン」という並びになり、右半分が空いたまま改行が崩れる。
    // このカードは 1 列で積むレイアウト (.fs-tier-body) を使う。
    function furnaceSmeltSection(section, title, hint) {
      const sBody = h("div", { class: "const-body fs-tier-body" });
      sBody.appendChild(h("p", { class: "form-hint", text: hint }));
      sBody.appendChild(h("div", { class: "form-field" }, [
        window.fieldLabelEl("percent", {
          label: "既定%",
          desc: "tier表に該当する行が無いときのフォールバック値(%)。",
          hideKey: true
        }),
        window.numberInput(section.percent, (v) => {
          if (v == null) return;
          section.percent = Math.max(0, v);
        })
      ]));
      sBody.appendChild(h("div", { class: "sub-title", text: "tier別%(該当tier行があればこちらを優先)" }));
      sBody.appendChild(
        typeof window.tierTableEditor === "function"
          ? window.tierTableEditor(section, [{ key: "percent", label: "%" }])
          : h("div", { class: "empty-hint", text: "tier表エディタ(tf-lifestyle-forms.js)が読み込まれていません。" })
      );
      return card([h("span", { class: "entry-key-label", text: title })], [sBody]);
    }
    root.appendChild(furnaceSmeltSection(speedSection, "精錬速度 (furnace-smelt.speed)",
      "skilltree/smithing.yml A-1/A-2/A-3 の feature:furnace-smelt-speed value(tier番号)で引く。"
      + "%は「精錬速度が何%増えるか」で、所要時間は 基準 ÷ (1 + %/100)。100なら2倍速、170なら2.7倍速で、"
      + "100を超える値も指定できます(かまどのバニラ基準は200tick=10.0秒/個)。"));
    root.appendChild(furnaceSmeltSection(bonusSection, "精錬ボーナス (furnace-smelt.bonus)",
      "skilltree/smithing.yml B-1/B-2/B-3 の feature:furnace-smelt-bonus value(tier番号)で引く。"));

    if (hasCraftingFeatures) {
      root.appendChild(h("p", { class: "form-hint", text:
        "以下の「解体」は progression/crafting-features.yml のサブツリーです(このファイルとは別ファイル)。"
        + "保存時は両方まとめて保存されます。"
      }));
      if (typeof window.buildCraftingFeaturesDisassemblySection === "function") {
        root.appendChild(window.buildCraftingFeaturesDisassemblySection(craftingFeaturesWorking.disassembly));
      } else {
        root.appendChild(h("div", { class: "empty-hint", text: "解体エディタ(tf-crafting-features.js)が読み込まれていません。" }));
      }
      // 2026-08-08: スクラップ変換(scrap-conversion)は解体の逆方向(集めたスクラップ->種別スクラップ)
      // なので同じ鍛冶ギミックタブに、解体セクションの直後へ並べる。
      if (typeof window.buildCraftingFeaturesScrapConversionSection === "function") {
        ensureObj(craftingFeaturesWorking, "scrap-conversion");
        // 見出しが無いと直前の「解体」セクションの続きに見えてしまう(実際にそう見えた)ので、
        // 解体と同じ体裁で境目を出す。
        root.appendChild(h("div", { class: "sub-title", text: "スクラップ変換 (scrap-conversion)" }));
        root.appendChild(h("p", { class: "form-hint", text:
          "上の「解体」の逆方向です。ここに登録したアイテムを持って右クリックすると、"
          + "基準素材数のぶんだけ消費して返却先から1件だけ抽選します(クラフト台のレシピではありません)。"
        }));
        root.appendChild(window.buildCraftingFeaturesScrapConversionSection(craftingFeaturesWorking["scrap-conversion"]));
      } else {
        root.appendChild(h("div", { class: "empty-hint", text: "スクラップ変換エディタ(tf-crafting-features.js)が読み込まれていません。" }));
      }
    }

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => hasCraftingFeatures ? [{ id: "crafting-features", data: craftingFeaturesWorking }] : []
    };
  };

})();
