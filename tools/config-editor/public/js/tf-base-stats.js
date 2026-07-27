"use strict";

// 「プレイヤー基礎ステータス」(combat/base-stats.yml) 専用フォーム。
// lore.yml に定義された全ステータスを一覧し、各ステへ「全プレイヤー一律の初期値」を設定する。
// 空欄 = そのステはバニラ挙動(加算なし)。値の意味は item-stats と同一 (PERCENTステは% 表示・割合保存)。
//
// 値の権威ソースは working["base-stats"] の平坦マップ (キーは lore のステ名 = kebab-case)。
// 空欄にすると当該キーをマップから削除する (0 を書き込まない = "空欄はバニラ" を素直に表現)。

(function (isBrowser) {
  // ============================================================
  // 純関数 (ブラウザ非依存・Node テストから直接 require 可能)
  // ============================================================

  const roundTo = (v, d) => { const m = Math.pow(10, d); return Math.round(v * m) / m; };

  // lore category → 表示見出し。lore.yml の category(attack/defense/craft/gathering/utility/ars/other)。
  const CATEGORY_ORDER = ["attack", "defense", "ars", "gathering", "craft", "utility", "other"];
  const CATEGORY_LABEL = {
    attack: "攻撃", defense: "防御", ars: "魔法 (Ars)", gathering: "採集",
    craft: "クラフト", utility: "汎用", other: "その他"
  };

  // 2026-07-25 修正2: base-stats.yml に書いても効果が無い(no-op)キーを画面から除外する。
  // 根拠(Java側裏取り): base-stats.yml の値は PlayerStatAggregator の item マップ
  // (属性チャネルなら PerkAttributeApplier)にしか入らず、PerkBuffResolver.general() には一切入らない。
  // 以下の7キーは general() 経由でしか読まれないため、base-stats に書いても完全な no-op になる:
  //  - glyph-slot-bonus:
  //    TrinityForge/src/main/java/com/trinityforge/integration/ars/ArsNativeBridge.java:65
  //    (perkBuffResolver.buffsFor(playerId).general().getOrDefault(GLYPH_SLOT_BONUS, 0.0))
  //  - heavy-armor-move-speed-per-piece / heavy-armor-set-bonus-multiplier /
  //    heavy-armor-set-knockback-resistance / light-armor-move-speed-per-piece /
  //    light-armor-set-bonus-multiplier / light-armor-set-dodge-chance:
  //    TrinityForge/src/main/java/com/trinityforge/skilltree/runtime/NativeAttributeBridge.java:49
  //    (Map<String, Double> general = perkBuffs.buffsFor(id).general(); armorAttributesFor() 全体が
  //    この general マップからしか読まない)。加えて防具セット系6キーは装備2枚未満で0になる
  //    (装備枚数依存)ため、プレイヤー基礎ステとしての意味も持たない。
  // (2026-07-27: 以前ここに「charged-shot-unlocked だけは例外」と書いていたが、当のキーが
  //  挙動ゼロの同語反復フラグと判明したため語彙ごと撤去した。この画面にフラグ系のステは無い。)
  // 将来キーを追加する際は、Java側で `.general()` 経由でしか読まれないことを確認してからここに足すこと
  // (`agg.totalOf(...)` / PlayerStatAggregator 経由で読まれるキーはここに入れてはいけない)。
  const NO_OP_BASE_STATS_KEYS = new Set([
    "glyph-slot-bonus",
    "heavy-armor-move-speed-per-piece",
    "heavy-armor-set-bonus-multiplier",
    "heavy-armor-set-knockback-resistance",
    "light-armor-move-speed-per-piece",
    "light-armor-set-bonus-multiplier",
    "light-armor-set-dodge-chance"
  ]);

  function statLabel(key) {
    const meta = window.STAT_META && window.STAT_META[key];
    if (meta && meta.name) return meta.name;
    // 修正1: labelForStat という関数は存在しない(このファイルのみが誤って呼んでいた)。
    // 正しい公開APIは public/js/labels.js が公開する window.LABELS.statLabel(key)。
    // 未定義キーは LABELS.statLabel 自身がキーをそのまま返す(フォールバック挙動を維持)。
    if (window.LABELS && typeof window.LABELS.statLabel === "function") {
      return window.LABELS.statLabel(key);
    }
    return key;
  }

  function statFormat(key) {
    return String((window.STAT_FORMATS && window.STAT_FORMATS[key]) || "FLAT").toUpperCase();
  }

  // T1(2026-07-25): この5キーだけは base-stats.yml 内で「絶対値」を意味する(他のキー/他画面での
  // 同名キーは加算量のまま・変更なし)。attack-speed-bonus は同日の分離仕様で追加(割合ボーナスなので
  // バニラ既定=0.0、絶対値変換しても実質そのまま加算量になる)。
  // バニラ既定値は TrinityForge/…/stats/VanillaAttributeDefaults.java と同じ値(出典: Minecraft Wiki
  // "Attribute")。二重管理を避けたいが、Java側は起動しないと読めない静的な定数集合なのでここではミラー
  // せざるを得ない — 値を変える際は両方を揃えること(test/vanilla-attribute-defaults-java-parity.test.js
  // がドリフト検知)。
  //
  // 2026-07-26 (stat-scope 境界引き直し): attack-speed(絶対値)を削除。総合ステータス語彙から外れて
  // アイテム固有ステになったため、base-stats では設定できなくなった(Java側と同時に削除)。
  // 割合側の attack-speed-bonus は総合ステのまま残るので、こちらは維持する。
  const VANILLA_ATTRIBUTE_DEFAULTS = {
    "attack-speed-bonus": 0.0,
    "attack-reach": 3.0,
    "max-health": 20.0,
    "move-speed": 0.1,
    "knockback-resistance": 0.0
  };

  function vanillaAttributeDefault(key) {
    return Object.prototype.hasOwnProperty.call(VANILLA_ATTRIBUTE_DEFAULTS, key)
      ? VANILLA_ATTRIBUTE_DEFAULTS[key]
      : null;
  }

  // 2026-07-25 課題1: attack-speed-bonus はバニラ既定=0.0の「割合ボーナス加算」キーで、絶対値上書きの
  // 意味を持たない(T1コメント参照)ため、「※バニラの値は上書きされません」表記の対象から除外する。
  const NO_VANILLA_NOTE_KEYS = new Set(["attack-speed-bonus"]);
  function shouldShowVanillaNote(key) {
    return vanillaAttributeDefault(key) !== null && !NO_VANILLA_NOTE_KEYS.has(key);
  }

  function vanillaNoteText(key) {
    if (!shouldShowVanillaNote(key)) return null;
    return "※バニラの値" + vanillaAttributeDefault(key) + "は上書きされません";
  }

  function isPercentStat(key) {
    return typeof window.isPercentStat === "function"
      ? window.isPercentStat(key)
      : statFormat(key) === "PERCENT";
  }

  // lore 表示の全ステキー (STAT_LIST 優先, フォールバック補完, 非表示ステ除外)。forms.js statList と同趣旨。
  // 修正2: base-stats.yml では no-op な7キー (NO_OP_BASE_STATS_KEYS) もここで除外する。除外は表示のみで
  // working["base-stats"] マップ自体には触れない(既存値がある場合でも保存でロスレス温存される)。
  function allStatKeys() {
    const primary = (window.STAT_LIST && window.STAT_LIST.length) ? window.STAT_LIST : [];
    const fallback = window.FALLBACK_STATS || [];
    const hidden = new Set(window.HIDDEN_STATS || ["flat-defense"]);
    const seen = new Set();
    const out = [];
    for (const k of primary.concat(fallback)) {
      if (!k || hidden.has(k) || seen.has(k) || NO_OP_BASE_STATS_KEYS.has(k)) continue;
      seen.add(k);
      out.push(k);
    }
    return out;
  }

  function categoryOf(key) {
    const meta = window.STAT_META && window.STAT_META[key];
    const cat = meta && meta.category ? meta.category : "other";
    return CATEGORY_LABEL[cat] ? cat : "other";
  }

  function orderOf(key) {
    const meta = window.STAT_META && window.STAT_META[key];
    return meta && Number.isFinite(Number(meta.order)) ? Number(meta.order) : 100;
  }

  // ============================================================
  // T8 (2026-07-26新設): 総合ステータス上限 (combat/stat-caps.yml)。
  // 「プレイヤー基礎ステータス」画面の「上限」タブから編集する(保存先は base-stats とは別ファイル、
  // buildSmithingGimmickForm と同じ getExtraSaves コンパニオン方式)。
  //
  // ● 重要な規約(base-stats とは逆): 「未記載」と「明示的な0」は別物。
  //   working["stat-caps"] にキーが無ければ「上限なし」、キーがあれば(0を含め)その値が上限になる。
  //   base-stats.yml は「空欄=キー削除=バニラ」のみの1規約だが、stat-caps.yml は「未記載/明示0」の
  //   2状態を区別する必要があるため、UI側もチェックボックス(設定する/しない)+ 数値入力の組で表現する
  //   (チェックOFF = キー削除 = 上限なし。チェックON = 入力値をそのまま保存、0のままでも保存する)。
  //
  // ● 一覧の正典は TrinityForge/src/main/resources/combat/stat-caps.yml のヘッダコメント。
  //   実際にクランプが効くキーだけをここに列挙する(未対応の5キー move-speed / attack-speed-bonus /
  //   attack-reach / knockback-resistance / max-health はバニラAttributeチャネル直結でTF側の
  //   クランプが実効値に反映されないため、意図的にUIへ出さない。理由の詳細は同yml「まだ効かないキー」
  //   セクション参照)。
  const STAT_CAPS_SECTIONS = [
    {
      title: "攻撃 (通常攻撃/弓 - totalOf経由)",
      keys: [
        "power-attack-damage", "power-attack-radius", "aoe-radius", "aoe-max-targets",
        "aoe-damage-rate", "reflect-flat", "reflect-percent", "melee-knockback",
        "stun-chance", "stun-duration-bonus", "distance-damage-bonus", "arrow-knockback",
        "ammo-save-chance", "bow-accuracy", "arrow-piercing", "arrow-velocity"
      ]
    },
    {
      title: "攻撃 (近接/弓の本命ステ - CombatListenerが直接クランプ)",
      keys: [
        "attack-power", "crit-chance", "crit-damage", "flat-bonus-damage",
        "percent-bonus-damage", "penetration", "damage-modifier", "fixed-damage",
        "bleed-chance", "bleed-damage"
      ]
    },
    {
      title: "防御 (totalOf経由)",
      keys: ["health-regen-bonus"]
    },
    {
      title: "防御 (守備力/回避の暴走対策 - PlayerDefenseResolverが直接クランプ)",
      keys: [
        "phys-resistance", "magic-resistance", "damage-reduction", "armor-defense-rate",
        "dodge-chance", "armor-strength", "phys-flat-defense", "magic-flat-defense"
      ]
    },
    {
      title: "採集・経済・クラフト・Ars (totalOf経由)",
      keys: [
        "mining-fortune", "fishing-luck", "fishing-bonus", "gathering-efficiency",
        "fish-sell-price-bonus", "disassembly-return-bonus", "ocean-fishing-bonus",
        "hunger-save-chance", "mob-drop-bonus", "skill-exp-bonus", "loot-luck",
        "mob-drop-quality", "gacha-rate-bonus", "suspicious-respawn-chance",
        "hive-harvest-fortune", "food-save-chance", "workbench-quality-bonus",
        "ritual-quality-bonus", "craft-upswing-bonus", "craft-downswing-reduction",
        "craft-roll-up-bonus", "craft-roll-down-reduction", "craft-roll-inset",
        "vanilla-exp-bonus", "kill-vanilla-exp-bonus", "break-vanilla-exp-bonus",
        "breeding-vanilla-exp-bonus", "woodcutting-extra-drop-chance",
        "harvest-extra-drop-chance", "food-restore-bonus", "hidden-saturation-bonus",
        "breeding-extra-child-chance", "bred-animal-growth-bonus",
        "planted-crop-growth-bonus", "mana-bonus", "mana-regen", "ars-tier-bonus",
        "glyph-slot-bonus", "hit-mana-recovery", "damage-mana-recovery",
        "mana-cost-reduction-flat", "mana-cost-reduction-percent",
        "lapis-cost-reduction", "source-cost-reduction", "material-refund-chance",
        "ingredient-save-chance", "enchant-luck", "enchant-exp-gain-bonus",
        "potion-quality-bonus", "brew-speed-bonus", "enchant-cost-reduction",
        "glyph-damage-multiplier-bonus"
      ]
    },
    {
      // 2026-07-27: 長らく「上限が効かないのでUIに出さない5キー」として除外していたが、
      // PerkAttributeApplier の合算完了直後(バニラAttributeへの書き込み直前)にクランプ点を作り、
      // 設定できるようになった。ただし意味が他チャネルと違う ——「TFの寄与分」の上限であって
      // 「その属性の実効値」の上限ではない(Haste等の他ソース分は含まれない)。詳細は
      // docs/config-reference/combat/stat-caps.md の同名の節。
      title: "ATTRIBUTE チャネル (TFの寄与分のみ - PerkAttributeApplierが直接クランプ)",
      keys: [
        "move-speed", "attack-speed-bonus", "attack-reach", "knockback-resistance", "max-health"
      ]
    }
  ];

  function statCapsAllKeys() {
    const out = [];
    for (const sec of STAT_CAPS_SECTIONS) for (const k of sec.keys) out.push(k);
    return out;
  }

  // combat/stat-caps.yml の working を往復ロスレスな形へ正規化する(未知キー・既存値は一切消さない)。
  function normalizeStatCapsWorking(working) {
    const w = working && typeof working === "object" ? working : {};
    if (w["stat-caps"] == null || typeof w["stat-caps"] !== "object" || Array.isArray(w["stat-caps"])) {
      w["stat-caps"] = {};
    }
    return w;
  }

  if (typeof module !== "undefined" && module.exports) {
    module.exports = {
      NO_OP_BASE_STATS_KEYS: Array.from(NO_OP_BASE_STATS_KEYS),
      VANILLA_ATTRIBUTE_DEFAULTS,
      vanillaAttributeDefault,
      shouldShowVanillaNote,
      vanillaNoteText,
      statLabel,
      statFormat,
      isPercentStat,
      allStatKeys,
      categoryOf,
      orderOf,
      STAT_CAPS_SECTIONS,
      statCapsAllKeys,
      normalizeStatCapsWorking
    };
  }
  if (!isBrowser) return;

  // ============================================================
  // DOM 部品 (ブラウザ専用)
  // ============================================================
  const h = window.h;

  // 空欄 = キー削除、PERCENTステは% 表示・割合保存 (statValueControl と同一換算) の値入力。
  // 2026-07-27: 唯一のチェックボックス例外だった charged-shot-unlocked を撤去したため、
  // ここは全ステ共通の数値入力だけになった(フラグ系のステはもう存在しない)。
  function valueControl(key, map) {
    const cur = map[key];
    if (isPercentStat(key)) {
      const shown = cur == null ? null : roundTo(Number(cur) * 100, 4);
      const wrap = h("span", { class: "pct-input" });
      const input = window.numberInput(shown, (v) => {
        if (v == null || v === "") delete map[key];
        else map[key] = roundTo(Number(v) / 100, 6);
      });
      wrap.appendChild(input);
      wrap.appendChild(h("span", { class: "pct-suffix", text: "%" }));
      return wrap;
    }
    const isInt = statFormat(key) === "INTEGER";
    return window.numberInput(cur == null ? null : cur, (v) => {
      if (v == null || v === "") delete map[key];
      else map[key] = v;
    }, isInt ? { int: true } : undefined);
  }

  // ---- 上限タブ専用の値コントロール ----
  // base-stats の valueControl とは規約が逆(空欄=削除ではない): チェックボックスで
  // 「上限を設定する/しない」を明示的に切り替える。OFF=キー削除(上限なし)、ON=入力値をそのまま
  // 保存する(0のままONにすれば「上限0」として保存される)。
  function capValueControl(key, map) {
    const present = Object.prototype.hasOwnProperty.call(map, key);
    const pct = isPercentStat(key);
    const isInt = statFormat(key) === "INTEGER";
    const toShown = (raw) => (pct ? roundTo(Number(raw) * 100, 4) : raw);
    const toStored = (shown) => (pct ? roundTo(Number(shown) / 100, 6) : shown);

    const numInput = window.numberInput(present ? toShown(map[key]) : null, (v) => {
      if (v == null || v === "") return; // 消去はチェックボックス側の責務(空欄操作では削除しない)
      map[key] = toStored(v);
    }, isInt ? { int: true } : undefined);
    numInput.disabled = !present;

    const checkbox = window.checkboxInput(present, (checked) => {
      if (checked) {
        if (!Object.prototype.hasOwnProperty.call(map, key)) map[key] = 0;
        numInput.disabled = false;
        numInput.value = String(toShown(map[key]));
      } else {
        delete map[key];
        numInput.disabled = true;
        numInput.value = "";
      }
    });

    const wrap = h("span", { class: "stat-cap-control" });
    wrap.appendChild(checkbox);
    if (pct) {
      const pctWrap = h("span", { class: "pct-input" });
      pctWrap.appendChild(numInput);
      pctWrap.appendChild(h("span", { class: "pct-suffix", text: "%" }));
      wrap.appendChild(pctWrap);
    } else {
      wrap.appendChild(numInput);
    }
    return wrap;
  }

  // gathering-efficiency-max-enchant-level (stat-caps.yml ルート直下、stat-caps: マップの外側)。
  // 0以下 = 「無制限」を明示的に上書き。未設定 = stats/gathering-efficiency.yml (旧ファイル)の
  // max-enchant-level が引き続き使われる(後方互換、Java側がそちらへフォールバックする)。
  function bookshelfLikeIntControl(rootMap, key, opts) {
    const present = Object.prototype.hasOwnProperty.call(rootMap, key);
    const numInput = window.numberInput(present ? rootMap[key] : null, (v) => {
      if (v == null || v === "") return;
      rootMap[key] = Math.trunc(Number(v));
    }, { int: true });
    numInput.disabled = !present;
    const checkbox = window.checkboxInput(present, (checked) => {
      if (checked) {
        if (!Object.prototype.hasOwnProperty.call(rootMap, key)) {
          rootMap[key] = opts && opts.defaultValue != null ? opts.defaultValue : 0;
        }
        numInput.disabled = false;
        numInput.value = String(rootMap[key]);
      } else {
        delete rootMap[key];
        numInput.disabled = true;
        numInput.value = "";
      }
    });
    const wrap = h("span", { class: "stat-cap-control" });
    wrap.appendChild(checkbox);
    wrap.appendChild(numInput);
    return wrap;
  }

  function buildStatCapsTabBody(statCapsWorking) {
    normalizeStatCapsWorking(statCapsWorking);
    const capsMap = statCapsWorking["stat-caps"];

    const body = h("div", { class: "dedicated-form" });
    body.appendChild(h("div", { class: "form-banner", text:
      "プレイヤーの「最終合算値」(装備+スキルツリーパーク+永続バフ+base-stats+乗算レイヤを"
      + "すべて適用した後の値)に上側だけの上限をかけます。保存先は combat/stat-caps.yml"
      + "(base-stats.yml とは別ファイル)。反映は /trinityforge reload で即時。" }));
    body.appendChild(h("div", { class: "field-desc", text:
      "チェックボックスOFF = 「上限なし」(未記載、現在の挙動のまま)。"
      + "チェックボックスON = そのときの数値がそのまま上限として保存されます(0のままONにすれば「上限0」)。"
      + "base-stats タブ(空欄=バニラ)とは逆の規約なので、消したい場合は必ずチェックボックスをOFFにしてください。" }));
    body.appendChild(h("div", { class: "field-desc", text:
      "ここに出ているキーは、実際にクランプが効くと確認済みのものだけです"
      + "(バニラAttributeチャネル直結の move-speed / attack-speed-bonus / attack-reach / "
      + "knockback-resistance / max-health、アイテム個別ステ、*-cooldown-reduction系は"
      + "Java側の仕様上クランプが効かないため、意図的に出していません)。" }));

    for (const sec of STAT_CAPS_SECTIONS) {
      const section = h("div", { class: "mob-defense-block" });
      section.appendChild(h("div", { class: "sub-title", text: sec.title }));
      const rows = h("div", { class: "stat-rows" });
      for (const k of sec.keys) {
        rows.appendChild(h("div", { class: "stat-row" }, [
          h("span", { class: "stat-row-label", text: statLabel(k), title: k }),
          capValueControl(k, capsMap)
        ]));
      }
      section.appendChild(rows);
      body.appendChild(section);
    }

    // T2 (2026-07-26): 「最終効率の上限 (gathering-efficiency)」独立カテゴリを畳んでここへ統合。
    const bookshelfSection = h("div", { class: "mob-defense-block" });
    bookshelfSection.appendChild(h("div", { class: "sub-title", text: "最終効率 → 効率強化エンチャントの上限" }));
    const bsRows = h("div", { class: "stat-rows" });
    bsRows.appendChild(h("div", { class: "stat-row" }, [
      h("span", { class: "stat-row-label", text: "効率強化エンチャントの上限レベル", title: "gathering-efficiency-max-enchant-level" }),
      bookshelfLikeIntControl(statCapsWorking, "gathering-efficiency-max-enchant-level", { defaultValue: 0 })
    ]));
    bookshelfSection.appendChild(bsRows);
    bookshelfSection.appendChild(h("div", { class: "field-hint", text:
      "gathering-efficiency(最終効率)ステを、メインハンドの道具へ実行時に「効率強化」エンチャントの"
      + "レベルとして反映する際の上限です。旧設定 stats/gathering-efficiency.yml の max-enchant-level を"
      + "統合したもので、こちらにチェックを入れて値を保存すると旧ファイルより優先されます"
      + "(旧ファイルは後方互換のため残り続け、ここが未設定の間はそちらの値が使われます)。"
      + "0以下 = 無制限。内部ハード上限255は常に超えません。" }));
    body.appendChild(bookshelfSection);

    return body;
  }

  window.buildBaseStatsForm = function buildBaseStatsForm(data, opts) {
    const working = data && typeof data === "object" ? data : {};
    if (!working["base-stats"] || typeof working["base-stats"] !== "object"
        || Array.isArray(working["base-stats"])) {
      working["base-stats"] = {};
    }
    const map = working["base-stats"];

    // T8 (2026-07-26): combat/stat-caps.yml を「上限」タブへコンパニオン表示する
    // (buildSmithingGimmickForm と同じ getExtraSaves 方式。保存先は base-stats とは別ファイル)。
    const statCapsData = opts && opts.statCapsData && typeof opts.statCapsData === "object"
      ? opts.statCapsData : undefined;
    const hasStatCaps = statCapsData !== undefined;
    const statCapsWorking = hasStatCaps ? normalizeStatCapsWorking(statCapsData) : normalizeStatCapsWorking({});

    const root = h("div", { class: "dedicated-form" });

    let active = "base";
    const tabsEl = h("div", { class: "recipe-tabs bs-tabs", role: "tablist" });
    const bodyEl = h("div", { class: "cf-body" });
    root.appendChild(tabsEl);
    root.appendChild(bodyEl);

    function renderTabs() {
      tabsEl.innerHTML = "";
      const TABS = [
        { id: "base", label: "基礎ステータス" },
        { id: "caps", label: "上限" }
      ];
      for (const t of TABS) {
        tabsEl.appendChild(h("button", {
          class: "recipe-tab" + (active === t.id ? " active" : ""),
          type: "button", role: "tab",
          "aria-selected": active === t.id ? "true" : "false",
          onclick: () => { active = t.id; renderTabs(); renderBody(); }
        }, [h("span", { text: t.label })]));
      }
    }

    function renderBaseTabBody() {
      const section0 = h("div", { class: "dedicated-form" });
      section0.appendChild(h("div", { class: "form-banner", text:
        "全プレイヤーに一律で加算される「基礎ステータス」。装備・スキル・バフの合算にもう1レイヤとして"
        + "加わります。空欄のステはバニラ挙動(加算なし)です。反映は /trinityforge reload で即時。" }));
      section0.appendChild(h("div", { class: "field-desc", text:
        "値の意味は item-stats と同じです。PERCENTステは % 表示(内部では割合で保存)、FLAT/INTEGERはそのままの数値。"
        + " 例: 会心率に 5 と入れると全員の基礎会心率が +5%。" }));
      section0.appendChild(h("div", { class: "field-desc", text:
        "注意: 攻撃力(attack-power)は「武器の基本ダメージ」を置き換えるステのため、ここに基礎値を入れると"
        + "素手/バニラ武器の挙動に影響します。通常は空欄のままを推奨します。" }));

      const keys = allStatKeys();
      // カテゴリ→ステキー配列 (カテゴリ内は lore order 昇順)。
      const byCat = {};
      for (const k of keys) {
        const c = categoryOf(k);
        (byCat[c] = byCat[c] || []).push(k);
      }
      for (const c of Object.keys(byCat)) {
        byCat[c].sort((a, b) => orderOf(a) - orderOf(b) || (a < b ? -1 : 1));
      }

      const cats = CATEGORY_ORDER.filter((c) => byCat[c] && byCat[c].length);
      // CATEGORY_ORDER に無い未知カテゴリも末尾に拾う。
      for (const c of Object.keys(byCat)) if (!cats.includes(c)) cats.push(c);

      if (!cats.length) {
        section0.appendChild(h("div", { class: "empty-hint", text:
          "ステータス一覧を読み込めませんでした。ロア表示(lore)を一度開いてから再度お試しください。" }));
        return section0;
      }

      for (const c of cats) {
        const section = h("div", { class: "mob-defense-block" });
        section.appendChild(h("div", { class: "sub-title", text: CATEGORY_LABEL[c] || c }));
        const rows = h("div", { class: "stat-rows" });
        for (const k of byCat[c]) {
          const vanillaDefault = vanillaAttributeDefault(k);
          // T3(2026-07-25): この6キーだけは base-stats.yml 内で「絶対値」を意味する(他画面の同名キーは
          // 従来通り加算量)。
          // 2026-07-25 レビュー修正: 以前はこのヒントを .stat-row-label のテキストに直接連結していたが、
          // .stat-row-label は flex: 0 0 220px の固定幅かつ折り返し抑止(nowrap/ellipsis)が無いため、
          // 連結後の文字列が220pxを超え5行だけ2行折り返し・行高不揃いを起こしていた(コード内コメントは
          // 「別要素で挟む」と書いてあったが実装が伴っていなかった)。既存の .field-unit バッジ要素を
          // 転用し、ラベル本体とは別要素でヒントを出す(レイアウト崩れなし)。
          const labelText = statLabel(k);
          const labelTitle = vanillaDefault === null
            ? k
            : k + " — 絶対値方式: バニラ既定 " + vanillaDefault + "。空欄/0はバニラのまま。";
          // 2026-07-25 ユーザー要望: attack-speed-bonus はバニラ既定=0.0だが、絶対値上書きの意味を
          // 持たない「割合ボーナス加算」キー(T1コメント参照)。既定0で「上書きされません」と表示しても
          // 意味を成さないため、この1キーだけヒント表示を出さない(除外リストは増やさない = このキー
          // 固有の特別扱いとして明示)。
          const showVanillaNote = shouldShowVanillaNote(k);
          const rowChildren = [
            // 2026-07-25 T6: 生のID(kebab-case)を表示テキストへ直接出すのをやめ、日本語ラベルのみ表示。
            // ID は title 属性(ホバー表示)としてのみ残す。lore.yml の全ステータスに name(日本語)が
            // 定義済みなので statLabel(k) は常にラベルを返す(このためだけの labels.js 変更は不要 =
            // labels.js の14文字上限/<ドメイン>:<説明> 形式とは無関係な別系統のラベルなので衝突しない)。
            h("span", { class: "stat-row-label", text: labelText, title: labelTitle }),
            valueControl(k, map)
          ];
          if (showVanillaNote) {
            // 2026-07-25 ユーザー要望: 数値入力欄の右側に、四角枠なしのプレーンテキストで
            // 「※バニラの値Xは上書きされません」と表示する(以前は入力欄の左に四角バッジ表示だった)。
            // .field-unit は他画面(スキルツリー等)でも流用されている共有クラスなので、その見た目
            // (枠線・背景・padding)を直接変更すると他画面を巻き込む。そのため基礎ステ画面専用の
            // 新規クラス .base-stat-note を用意し、プレーンテキストで表示する(CSSは style.css 参照)。
            rowChildren.push(h("span", { class: "base-stat-note", text: vanillaNoteText(k), title: labelTitle }));
          }
          rows.appendChild(h("div", { class: "stat-row" }, rowChildren));
        }
        section.appendChild(rows);
        section0.appendChild(section);
      }
      return section0;
    }

    function renderBody() {
      bodyEl.innerHTML = "";
      if (active === "caps") {
        bodyEl.appendChild(buildStatCapsTabBody(statCapsWorking));
      } else {
        bodyEl.appendChild(renderBaseTabBody());
      }
    }

    renderTabs();
    renderBody();

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => hasStatCaps ? [{ id: "stat-caps", data: statCapsWorking }] : []
    };
  };
})(typeof window !== "undefined" && typeof document !== "undefined");
