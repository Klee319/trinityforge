"use strict";

// combat/mob-types.yml (tf-mob-types) 専用フォーム。
//   defaults / mob-types.<ENTITY_TYPE>:
//     level, coordinate-coefficient, max-health, armor-strength,
//     physical{...}, magical{...}, level-coefficients{...}, drops[]
//
// 設計方針 (往復ロスレス最優先、tf-forms.js と同じ流儀):
//   - working = 受け取った data を「そのまま」直接編集する。未知キー・キー順は温存する。
//   - drops / max-health / level-coefficients は省略可。ユーザーが入力するまで無理に実体化しない。

(function () {
  const h = window.h;

  function card(headChildren, bodyChildren) {
    return h("div", { class: "entry-card" }, [
      h("div", { class: "entry-head" }, headChildren),
      h("div", { class: "entry-body" }, bodyChildren)
    ]);
  }
  function fieldRow(key, control, opts) {
    const labelEl = window.fieldLabelEl(key, opts);
    return h("div", { class: "form-field" }, [labelEl, control]);
  }
  function emptyGuide(title, hint) {
    return h("div", { class: "empty-guide" }, [
      h("div", { class: "empty-guide-title", text: title }),
      h("div", { class: "empty-guide-hint", text: hint })
    ]);
  }
  function gridRow(fields) { return h("div", { class: "field-grid" }, fields); }
  function subTitle(text) { return h("div", { class: "sub-title", text }); }

  function renameKey(map, oldKey, newKey) {
    const rebuilt = {};
    for (const k of Object.keys(map)) rebuilt[k === oldKey ? newKey : k] = map[k];
    for (const k of Object.keys(map)) delete map[k];
    Object.assign(map, rebuilt);
  }

  function ensureObject(parent, key) {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) parent[key] = {};
    return parent[key];
  }

  // E-2 (2026-07-25): 以前は mob-forms.js と tf-rewards-forms.js に37種の同一配列が別々に重複定義
  // されていた。vocab-1.21.11.js の VANILLA_MOBS(paper-api javap抽出、召喚可能な生物83種)へ集約する。
  // 集約前の37種は全て VANILLA_MOBS に含まれることを確認済み(候補は減らない、むしろ大幅に増える)。
  const ENTITY_TYPE_CANDIDATES = Array.isArray(window.VANILLA_MOBS) ? window.VANILLA_MOBS : [];

  // 2026-08-02: dimensions: のキーは World.Environment 名の4種で固定(MobTypesConfig#parseDimensions
  // が Enum.valueOf で解決するため、未知の値は警告付きでスキップされ無干渉になる)。EntityType 選択と
  // 同じ「自由入力+datalist」にすると、サーバ構成依存のワールド名を書いてしまい常に無干渉になる事故を
  // 誘発するため固定4択にする。
  const DIMENSION_ENVS = [
    { key: "NORMAL", label: "オーバーワールド (NORMAL)" },
    { key: "NETHER", label: "ネザー (NETHER)" },
    { key: "THE_END", label: "エンド (THE_END)" },
    { key: "CUSTOM", label: "カスタムワールド (CUSTOM)" }
  ];

  const PHYS_MAGIC_FIELDS = ["defense-rate", "resistance", "damage-reduction", "flat-defense"];
  const ATTACK_FIELDS = [
    "attack-power", "flat-bonus-damage", "percent-bonus-damage", "penetration",
    "crit-chance", "crit-damage", "damage-modifier", "fixed-damage"
  ];

  // 2026-08-13: モブ側の割合フィールドを % 入力にする(ユーザー指示「割合記法のものはすべて
  // %記法にしてほしい」)。**キーからは割合だと判定できない** ── これらは physical:/magical:/
  // attack: ブロック内の短縮キーで、`stats/lore.yml` のステ語彙(phys-resistance 等)に存在せず
  // `isPercentStat` は FLAT を返す。だから語彙ではなくこの表で明示する。
  // 単位の根拠は DefenseStats / AttackStats の javadoc(いずれも [0,1] の割合。
  // damage-modifier だけは中立値 1.0 の倍率だが単位は同じ割合で、%表示だと 100% = ×1.0 になる)。
  // **除外したものと理由**: flat-defense / flat-bonus-damage / fixed-damage / attack-power は
  // 割合ではなくダメージ量そのもの、max-health / level も実数。ここに入れると 100 倍で表示される。
  const RATE_FIELDS = new Set([
    "defense-rate", "resistance", "damage-reduction", "armor-strength",
    "percent-bonus-damage", "penetration", "crit-chance", "crit-damage", "damage-modifier"
  ]);

  /**
   * モブ系フォームの値入力。割合フィールドなら % 入力(表示×100 / 保存÷100)、それ以外は素の数値。
   * **空欄は 0 ではなく null で返す** ── モブ系は「空欄 = キーを書かず上位スコープを継承」なので、
   * 0 に潰すと継承が黙って壊れる(呼び出し側は元から null で delete している)。
   */
  function mobValueInput(key, value, onSet) {
    if (RATE_FIELDS.has(key) && typeof window.rateValueControl === "function") {
      return window.rateValueControl(value, onSet);
    }
    return window.numberInput(value, onSet, { int: false });
  }

  function buildDefenseBlock(title, obj) {
    const fields = PHYS_MAGIC_FIELDS.map((key) => {
      const input = mobValueInput(key, obj[key], (v) => {
        if (v === null || v === "") { delete obj[key]; return; }
        obj[key] = v;
      });
      return fieldRow(key, input);
    });
    return h("div", { class: "mob-defense-block" }, [subTitle(title), gridRow(fields)]);
  }

  function buildAttackBlock(title, obj) {
    if (!obj || typeof obj !== "object" || Array.isArray(obj)) obj = {};
    const fields = ATTACK_FIELDS.map((key) => {
      const input = mobValueInput(key, obj[key], (v) => {
        if (v === null || v === "") { delete obj[key]; return; }
        obj[key] = v;
      });
      return fieldRow(key, input);
    });
    return h("div", { class: "mob-attack-block" }, [subTitle(title), gridRow(fields)]);
  }

  // 既存の rampEditors (mob-import.yml 等) と同じ流儀: growth / growth-interval は空欄なら
  // キーを書き込まない(未入力=従来どおりの線形)。mob-types.yml は base+per-level+growth を
  // 1つのオブジェクトへまとめず {baseKey}/{baseKey}-growth/{baseKey}-growth-interval の3キーへ
  // 分散配置する既存レイアウトを踏襲するため、rampEditors そのものは再利用せず同じ入力UXだけ倣う。
  function growthFieldRows(touch, obj, baseKey, labelPrefix, opts) {
    const growthKey = `${baseKey}-growth`;
    const intervalKey = `${baseKey}-growth-interval`;
    const rows = [
      fieldRow(growthKey, window.numberInput(obj[growthKey] == null ? "" : obj[growthKey], (v) => {
        const target = touch();
        if (v === null || v === "") { delete target[growthKey]; return; }
        target[growthKey] = v;
      }, { int: false }), {
        label: `${labelPrefix}指数(growth)`,
        desc: "省略 or 1.0 = 従来どおりの線形(基準+Lv×増分)。growth>1.0で指数的に増加。"
      }),
      fieldRow(intervalKey, window.numberInput(obj[intervalKey] == null ? "" : obj[intervalKey], (v) => {
        const target = touch();
        if (v === null || v === "") { delete target[intervalKey]; return; }
        target[intervalKey] = v;
      }, { int: false }), {
        label: `${labelPrefix}指数間隔`,
        desc: "growth 1回分あたりのレベル幅(省略時1.0)。"
      })
    ];
    // 2026-08-03(45+難易度修正): {baseKey}-high-level-from/{baseKey}-high-level-per-level。
    // 現状は max-health だけが呼び出し側(buildLevelCoeffBlock)から opts.showHighLevel:true で
    // 有効化されている(Java側 MobLevelCoefficients が max-health にしかこの2キーを持たないため。
    // attack-power 側に同じUIを出すと「入力しても何も効かない」死んだフィールドになるので出さない)。
    if (opts && opts.showHighLevel) {
      const fromKey = `${baseKey}-high-level-from`;
      const perLevelKey = `${baseKey}-high-level-per-level`;
      rows.push(
        fieldRow(fromKey, window.numberInput(obj[fromKey] == null ? "" : obj[fromKey], (v) => {
          const target = touch();
          if (v === null || v === "") { delete target[fromKey]; return; }
          target[fromKey] = v;
        }, { int: false }), {
          label: `${labelPrefix}高レベル開始`,
          desc: "このレベル以上だけ追加加算する第2区間の開始レベル。省略=発動しない(従来どおり)。"
        }),
        fieldRow(perLevelKey, window.numberInput(obj[perLevelKey] == null ? "" : obj[perLevelKey], (v) => {
          const target = touch();
          if (v === null || v === "") { delete target[perLevelKey]; return; }
          target[perLevelKey] = v;
        }, { int: false }), {
          label: `${labelPrefix}高レベル加算/Lv`,
          desc: "開始レベル以上、1レベルごとに加算する量(乗算ではなく純粋な加算)。開始レベルちょうどでは0(連続)。"
        })
      );
    }
    return rows;
  }

  function buildLevelCoeffBlock(host) {
    // 未設定ならキーを作らず表示のみ。入力時に実体化する（未編集で YAML を汚さない）。
    const hasBlock = host["level-coefficients"] && typeof host["level-coefficients"] === "object"
      && !Array.isArray(host["level-coefficients"]);
    const coeffs = hasBlock ? host["level-coefficients"] : {};
    const phys = (coeffs.physical && typeof coeffs.physical === "object" && !Array.isArray(coeffs.physical))
      ? coeffs.physical : {};
    const mag = (coeffs.magical && typeof coeffs.magical === "object" && !Array.isArray(coeffs.magical))
      ? coeffs.magical : {};
    const atk = (coeffs.attack && typeof coeffs.attack === "object" && !Array.isArray(coeffs.attack))
      ? coeffs.attack : {};
    function touchCoeffs() {
      if (!host["level-coefficients"] || typeof host["level-coefficients"] !== "object") {
        host["level-coefficients"] = coeffs;
      }
      return host["level-coefficients"];
    }
    function touchPhys() {
      const c = touchCoeffs();
      if (!c.physical || typeof c.physical !== "object") c.physical = phys;
      return c.physical;
    }
    function touchMag() {
      const c = touchCoeffs();
      if (!c.magical || typeof c.magical !== "object") c.magical = mag;
      return c.magical;
    }
    function touchAtk() {
      const c = touchCoeffs();
      if (!c.attack || typeof c.attack !== "object") c.attack = atk;
      return c.attack;
    }
    const topFields = [
      fieldRow("max-health", window.numberInput(coeffs["max-health"], (v) => {
        const c = touchCoeffs();
        if (v === null || v === "") { delete c["max-health"]; return; }
        c["max-health"] = v;
      }, { int: false }), {
        label: "最大HP係数",
        desc: "レベル1あたりの最大HP加算係数。"
      }),
      ...growthFieldRows(touchCoeffs, coeffs, "max-health", "最大HP", { showHighLevel: true }),
      fieldRow("armor-strength", mobValueInput("armor-strength", coeffs["armor-strength"], (v) => {
        const c = touchCoeffs();
        if (v === null || v === "") { delete c["armor-strength"]; return; }
        c["armor-strength"] = v;
      }), {
        label: "防具強度係数",
        desc: "レベル1あたりの防具強度加算(割合なので % 表示。0.25% = Lv100 で +25%)。"
      })
    ];
    function buildLazyDefenseBlock(title, obj, touch) {
      const fields = PHYS_MAGIC_FIELDS.map((key) => {
        const input = mobValueInput(key, obj[key], (v) => {
          const target = touch();
          if (v === null || v === "") { delete target[key]; return; }
          target[key] = v;
        });
        return fieldRow(key, input);
      });
      return h("div", { class: "mob-defense-block" }, [subTitle(title), gridRow(fields)]);
    }
    return h("div", { class: "mob-level-coeff-block" }, [
      subTitle("レベル係数 (level-coefficients)"),
      h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: "各ステの実効値 = 基準 + 係数 × effectiveLevel。割合系は適用後に0〜1へクランプ。"
      }),
      gridRow(topFields),
      buildLazyDefenseBlock("物理係数 (physical)", phys, touchPhys),
      buildLazyDefenseBlock("魔法係数 (magical)", mag, touchMag),
      buildAttackCoeffBlock("攻撃係数 (attack)", atk, touchAtk)
    ]);
  }

  function buildAttackCoeffBlock(title, obj, touch) {
    const fields = ATTACK_FIELDS.flatMap((key) => {
      const input = mobValueInput(key, obj[key], (v) => {
        const target = touch();
        if (v === null || v === "") { delete target[key]; return; }
        target[key] = v;
      });
      const row = fieldRow(key, input);
      // attack-power のみ growth/growth-interval に対応 (mob-import.yml の attack-power と揃える)。
      // 構造上は他ステも同じ growthFieldRows で後から追加できる。
      return key === "attack-power" ? [row, ...growthFieldRows(touch, obj, "attack-power", "攻撃力")] : [row];
    });
    return h("div", { class: "mob-attack-block" }, [subTitle(title), gridRow(fields)]);
  }

  function buildDropRow(drop, onRemove) {
    // 2026-08-01 U13: material は Material名 または custom:<カタログID>。allowCustom を渡していな
    // かったため、この画面だけカスタムアイテムが候補に出ず、「＋ 直接入力…」で custom:foo と打っても
    // normalizeCommitValue が CUSTOMFOO へ潰していた(=入れる経路が1本も無い)。
    // 候補源は app.js の共通入口 ensureCustomItemCandidates() が積む window.CUSTOM_ITEM_CANDIDATES
    // をそのまま使う(この画面用に新しく取りに行かない)。
    // Java 側は MobTypesConfig#parseDrops / MobDropEntry / MobTypeDropListener が custom: を解釈する。
    // 2026-08-02: materialInput は 2026-07-29 の listSelect 移行で選択後の表示自体が既に日本語名
    // (primary) になっている。ここに materialHintEl を並べて足すと同じ日本語名が2回出て行が崩れる
    // (実サーバ報告: レベルテーブルの add-drops 行で「幸運のスレッド / 幸運のスレッド」と重複表示)。
    // ヒント欄は削除し、materialInput 自身の表示だけに一本化する。
    const matInput = window.materialInput(drop.material, "material-list", (v) => {
      drop.material = v;
    }, { allowCustom: true });
    const chanceInput = window.numberInput(drop.chance, (v) => { drop.chance = v == null ? 0 : v; }, { int: false });
    const minInput = window.numberInput(drop.min, (v) => { drop.min = v == null ? 0 : v; }, { int: true });
    const maxInput = window.numberInput(drop.max, (v) => { drop.max = v == null ? 0 : v; }, { int: true });
    const qualityInput = window.numberInput(drop.quality, (v) => {
      if (v === null) { delete drop.quality; return; }
      drop.quality = v;
    }, { int: true });

    return h("div", { class: "mob-drop-row" }, [
      h("div", { class: "field-grid" }, [
        fieldRow("material", matInput, {
          label: "素材(Material/custom:)",
          desc: "ドロップするアイテム。Material名 または custom:<カタログID>"
            + "(items/catalog.yml と ArsPaper materials.yml の両方から選べる)。"
        }),
        fieldRow("chance", chanceInput),
        fieldRow("min", minInput, { label: "個数(最小)", desc: "ドロップ個数の下限。0以上の整数。" }),
        fieldRow("max", maxInput, { label: "個数(最大)", desc: "ドロップ個数の上限。0以上の整数、min以上。" }),
        fieldRow("quality", qualityInput, {
          label: "固定品質(省略可)",
          desc: "省略時はモブレベル駆動で自動決定。custom: のアイテムには効かない(生成側が品質を決める)。"
        })
      ]),
      h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: onRemove })
    ]);
  }

  // 再描画をまたいで開閉状態を保つ(collapsibleCard の推奨パターン)。
  // 2026-07-26 に mob-types.yml が1.21.11の全生存EntityType 90体へ拡張されたため、
  // 全部開いたまま描くとスクロールが数万pxになって実用にならない。既定は全て折りたたみ。
  const openMobTypeCards = new Set();

  /**
   * 折りたたんだままでも「どういう設定のモブか」が分かる要約バッジ。
   * 90枚を上から眺めて調整対象を探す用途なので、効きが大きい HP と攻撃力だけを出す。
   * max-health 未設定(=バニラHPのまま)は、戦闘設計を載せていない友好・受動モブの目印になるので
   * 空欄ではなく明示的に「バニラ据え置き」と書く。
   */
  function mobTypeSummary(entry) {
    const parts = [];
    const hp = entry && entry["max-health"];
    parts.push(hp === undefined || hp === null || hp === "" ? "HP: バニラ据え置き" : `HP: ${hp}`);
    const atk = entry && entry.attack && entry.attack["attack-power"];
    if (atk !== undefined && atk !== null && atk !== "" && Number(atk) !== 0) {
      parts.push(`攻撃: ${atk}`);
    }
    const drops = entry && Array.isArray(entry.drops) ? entry.drops.length : 0;
    if (drops > 0) parts.push(`ドロップ ${drops}件`);
    return h("span", { class: "entry-key-sub", text: parts.join(" / ") });
  }

  window.buildMobTypesForm = function buildMobTypesForm(data) {
    const working = data && typeof data === "object" ? data : {};
    working.defaults = working.defaults || {};
    const defaults = working.defaults;
    ensureObject(defaults, "physical");
    ensureObject(defaults, "magical");
    // attack: 省略可（未設定ならバニラ攻撃のまま）
    if (!working["mob-types"] || typeof working["mob-types"] !== "object" || Array.isArray(working["mob-types"])) {
      working["mob-types"] = {};
    }
    const mobTypes = working["mob-types"];
    const root = h("div", { class: "dedicated-form" });

    function renderDefaultsCard() {
      const levelInput = window.numberInput(defaults.level, (v) => {
        if (v === null) { delete defaults.level; return; }
        defaults.level = Math.max(0, Math.floor(Number(v)) || 0);
      }, { int: true });
      const coordInput = window.numberInput(defaults["coordinate-coefficient"], (v) => {
        if (v === null) { delete defaults["coordinate-coefficient"]; return; }
        defaults["coordinate-coefficient"] = v;
      }, { int: false });
      const armorInput = mobValueInput("armor-strength", defaults["armor-strength"], (v) => {
        if (v === null) { delete defaults["armor-strength"]; return; }
        defaults["armor-strength"] = v;
      });
      const hpInput = window.numberInput(defaults["max-health"], (v) => {
        if (v === null || v === "") { delete defaults["max-health"]; return; }
        defaults["max-health"] = v;
      }, { int: false });
      const basicBody = gridRow([
        fieldRow("level", levelInput, {
          label: "基準戦闘レベル",
          desc: "未タグ付けモブの基準戦闘レベル。座標係数と合算して effectiveLevel になる。0以上の整数。"
        }),
        fieldRow("coordinate-coefficient", coordInput, {
          label: "座標係数",
          desc: "ワールドスポーンからの距離1ブロックあたりのレベル加算。effectiveLevel = level + floor(距離×係数)。"
        }),
        fieldRow("max-health", hpInput, {
          label: "最大HP (省略可)",
          desc: "未タグ付けモブの最大HP上書き。省略時はバニラHPのまま。レベル係数の max-health と合算。"
        }),
        fieldRow("armor-strength", armorInput)
      ]);
      return card(
        [h("span", { class: "entry-key-label", text: "未タグ付けモブの既定ステ" })],
        [
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
            text: "mob-types に無いバニラモブ向け。level/座標係数/防御/HP/係数を設定するとスポーン時に刻印・スケールされます（全て0かつHP未設定なら刻印せず従来パス）。"
          }),
          basicBody,
          buildDefenseBlock("物理 (physical)", defaults.physical),
          buildDefenseBlock("魔法 (magical)", defaults.magical),
          buildAttackBlock("攻撃 (attack)", defaults.attack || {}),
          buildLevelCoeffBlock(defaults)
        ]
      );
    }

    function renderMaxLevelCard() {
      // CMB-21: 距離由来レベルの上限(トップレベルmax-level、既定100)。defaults/mob-types どちらの
      // effectiveLevelにも一律で効く(タグ付き/未タグ付け問わず)。
      const maxLevelInput = window.numberInput(working["max-level"], (v) => {
        if (v === null || v === "") { delete working["max-level"]; return; }
        working["max-level"] = Math.max(1, Math.floor(Number(v)) || 100);
      }, { int: true });
      return card(
        [h("span", { class: "entry-key-label", text: "距離レベル上限" })],
        [gridRow([
          fieldRow("max-level", maxLevelInput, {
            label: "レベル上限 (max-level)",
            desc: "座標由来のeffectiveLevelの上限。省略時は既定100。上限が無いと遠距離モブが" +
                "極端な高レベルへ達し貫通が飽和して防御ステが無意味になるため必ず設定される。"
          })
        ])]
      );
    }

    // 2026-08-02: dimensions: (MobTypesConfig#parseDimensions) 専用カード。
    // キーは World.Environment 名の4種固定(EntityTypeのような自由入力にしない — ネザー/エンドの
    // ワールド名はサーバ構成依存で一致せず、EliteMobsのインスタンスワールドは毎回名前が変わるため、
    // ワールド名で引く設計は成立しない。combat.md 参照)。
    // 未編集でカードを開いただけでは working.dimensions に一切触れない(4環境ぶんの base-level:0 が
    // 勝手に埋まる事故を避ける)。値を入力した環境だけ実体化し、getData() 時に空エントリを刈る。
    function renderDimensionsCard() {
      function touchEnv(envKey) {
        const dims = ensureObject(working, "dimensions");
        if (!dims[envKey] || typeof dims[envKey] !== "object" || Array.isArray(dims[envKey])) {
          dims[envKey] = {};
        }
        return dims[envKey];
      }
      function readEnv(envKey) {
        const dims = working.dimensions;
        return (dims && typeof dims === "object" && dims[envKey] && typeof dims[envKey] === "object")
          ? dims[envKey] : {};
      }

      const rows = DIMENSION_ENVS.map(({ key: envKey, label }) => {
        const current = readEnv(envKey);
        const baseLevelInput = window.numberInput(current["base-level"], (v) => {
          if (v === null || v === "") {
            if (working.dimensions && working.dimensions[envKey]) delete working.dimensions[envKey]["base-level"];
            return;
          }
          touchEnv(envKey)["base-level"] = Math.max(0, Math.floor(Number(v)) || 0);
        }, { int: true });
        const coordInput = window.numberInput(current["coordinate-coefficient"], (v) => {
          if (v === null || v === "") {
            if (working.dimensions && working.dimensions[envKey]) delete working.dimensions[envKey]["coordinate-coefficient"];
            return;
          }
          touchEnv(envKey)["coordinate-coefficient"] = v;
        }, { int: false });

        return gridRow([
          fieldRow(`dimensions.${envKey}.base-level`, baseLevelInput, {
            label: `${label} — 基準レベル下駄`,
            desc: "このディメンションの全モブのeffectiveLevelに加算する整数下駄。省略時0(=従来どおり無干渉)。"
          }),
          fieldRow(`dimensions.${envKey}.coordinate-coefficient`, coordInput, {
            label: `${label} — 座標係数上書き (省略可)`,
            desc: "省略時はモブ側(defaults/mob-types)の座標係数をそのまま使用。指定時だけこの" +
                "ディメンション全体の座標係数を上書きする。"
          })
        ]);
      });

      return card(
        [h("span", { class: "entry-key-label", text: "ディメンション別の基準レベル (dimensions)" })],
        [
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
            text: "World.Environment(NORMAL/NETHER/THE_END/CUSTOM)ごとにレベル下駄・座標係数上書きを" +
                "設定します。ワールド名では引かないため、ネザー/エンドのワールド名を書いても効きません。" +
                "未設定のディメンションは従来どおり無干渉です。"
          }),
          ...rows
        ]
      );
    }

    function render() {
      root.innerHTML = "";
      root.appendChild(renderMaxLevelCard());
      root.appendChild(renderDimensionsCard());
      root.appendChild(renderDefaultsCard());
      const keys = Object.keys(mobTypes);
      if (keys.length === 0) {
        root.appendChild(emptyGuide(
          "モブ定義がまだありません。",
          "「+ モブ追加」で EntityType 単位のレベル/HP/防御/レベル係数/ドロップを定義します。"
        ));
      }
      for (const entityType of keys) root.appendChild(renderCard(entityType));
      root.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ モブ追加",
          onclick: () => {
            let n = "ZOMBIE", i = 1;
            let key = n;
            while (Object.prototype.hasOwnProperty.call(mobTypes, key)) key = `${n}_${i++}`;
            mobTypes[key] = { level: 0, "coordinate-coefficient": 0 };
            render();
          }
        })
      ]));
    }

    function renderCard(entityType) {
      const entry = mobTypes[entityType] && typeof mobTypes[entityType] === "object" && !Array.isArray(mobTypes[entityType])
        ? mobTypes[entityType]
        : (mobTypes[entityType] = {});

      // 2026-07-29: datalist 付きの素の text 入力を、日本語名で引けるセレクトへ置換。
      // キー重複は onCommit で却下する (従来の change ハンドラと同じ判定)。
      const typeInput = window.mobTypeSelect(entityType, null, {
        onCommit: (raw) => {
          const nv = String(raw || "").trim().toUpperCase().replace(/[^A-Z0-9_]/g, "");
          if (!nv || nv === entityType) return false;
          if (Object.prototype.hasOwnProperty.call(mobTypes, nv)) {
            alert("同じ EntityType が既に存在します");
            return false;
          }
          renameKey(mobTypes, entityType, nv);
          render();
          return true;
        }
      });

      // 2026-07-28: カードを閉じたときに EntityType の生ID しか見えず、どのモブの定義か
      // 分からなかったため、先頭に日本語名を出す(オーバーライド側カードと同じ流儀)。
      const jaName = (window.MOB_LABELS_JA && window.MOB_LABELS_JA[entityType]) || "";
      const head = [
        h("span", { class: "entry-key-label", text: jaName || entityType }),
        h("span", { class: "entry-key-label", text: "EntityType" }), typeInput,
        mobTypeSummary(entry),
        h("div", { class: "spacer" }),
        h("button", {
          class: "btn-small", type: "button", text: "複製",
          onclick: () => {
            let n = `${entityType}_COPY`, i = 1;
            let key = n;
            while (Object.prototype.hasOwnProperty.call(mobTypes, key)) key = `${n}_${i++}`;
            mobTypes[key] = JSON.parse(JSON.stringify(entry));
            render();
          }
        }),
        h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => { delete mobTypes[entityType]; render(); }
        })
      ];

      const levelInput = window.numberInput(entry.level, (v) => { entry.level = v == null ? 0 : v; }, { int: true });
      const coordInput = window.numberInput(entry["coordinate-coefficient"], (v) => {
        entry["coordinate-coefficient"] = v == null ? 0 : v;
      }, { int: false });
      const hpInput = window.numberInput(entry["max-health"], (v) => {
        if (v === null || v === "") { delete entry["max-health"]; return; }
        entry["max-health"] = v;
      }, { int: false });
      const armorInput = mobValueInput("armor-strength", entry["armor-strength"], (v) => {
        if (v === null) { delete entry["armor-strength"]; return; }
        entry["armor-strength"] = v;
      });

      const basicBody = gridRow([
        fieldRow("level", levelInput, { label: "基準戦闘レベル", desc: "このモブの基準となる戦闘レベル。0以上の整数。" }),
        fieldRow("coordinate-coefficient", coordInput),
        fieldRow("max-health", hpInput, {
          label: "最大HP (省略可)",
          desc: "スポーン時の最大HP基準。省略時はバニラHP。実効 = max-health + レベル係数×effectiveLevel。"
        }),
        fieldRow("armor-strength", armorInput)
      ]);

      ensureObject(entry, "physical");
      ensureObject(entry, "magical");

      const dropsBox = h("div", { class: "mob-drops-box" });
      function renderDrops() {
        dropsBox.innerHTML = "";
        const drops = Array.isArray(entry.drops) ? entry.drops : [];
        if (drops.length === 0) {
          dropsBox.appendChild(h("div", { class: "empty-guide-hint", text: "追加ドロップはまだありません(省略時は追加ドロップなし)。" }));
        }
        drops.forEach((d, idx) => {
          if (!d || typeof d !== "object") return;
          dropsBox.appendChild(buildDropRow(d, () => {
            entry.drops.splice(idx, 1);
            if (entry.drops.length === 0) delete entry.drops;
            renderDrops();
          }));
        });
        dropsBox.appendChild(h("div", { class: "form-actions" }, [
          h("button", {
            class: "btn-small", type: "button", text: "+ ドロップ追加",
            onclick: () => {
              if (!Array.isArray(entry.drops)) entry.drops = [];
              entry.drops.push({ material: "ROTTEN_FLESH", chance: 0.1, min: 1, max: 1 });
              renderDrops();
            }
          })
        ]));
      }
      renderDrops();

      const body = [
        basicBody,
        buildDefenseBlock("物理 (physical)", entry.physical),
        buildDefenseBlock("魔法 (magical)", entry.magical),
        buildAttackBlock("攻撃 (attack)", entry.attack || {}),
        buildLevelCoeffBlock(entry),
        h("div", { class: "mob-drops-section" }, [
          window.fieldLabelEl("drops"),
          dropsBox
        ])
      ];
      return window.collapsibleCard(head, body, {
        expanded: openMobTypeCards.has(entityType),
        onToggle: (open) => {
          if (open) openMobTypeCards.add(entityType);
          else openMobTypeCards.delete(entityType);
        }
      });
    }

    render();
    return {
      element: root,
      getData: () => {
        // 空の level-coefficients ブロックは落とす (無編集ロスレス)。
        // 旧 level-constants は削除済み仕様のため保存時に除去する。
        pruneEmptyLevelCoeffs(working.defaults);
        deleteLegacyLevelConstants(working.defaults);
        for (const entry of Object.values(working["mob-types"] || {})) {
          pruneEmptyLevelCoeffs(entry);
          deleteLegacyLevelConstants(entry);
        }
        pruneEmptyDimensions(working);
        return working;
      }
    };
  };

  function pruneEmptyLevelCoeffs(host) {
    pruneEmptyScalingBlock(host, "level-coefficients");
  }
  function deleteLegacyLevelConstants(host) {
    if (host && typeof host === "object") delete host["level-constants"];
  }
  function pruneEmptyScalingBlock(host, key) {
    if (!host || typeof host !== "object") return;
    const coeffs = host[key];
    if (!coeffs || typeof coeffs !== "object") return;
    const phys = coeffs.physical;
    const mag = coeffs.magical;
    const atk = coeffs.attack;
    const physEmpty = !phys || typeof phys !== "object" || Object.keys(phys).length === 0;
    const magEmpty = !mag || typeof mag !== "object" || Object.keys(mag).length === 0;
    const atkEmpty = !atk || typeof atk !== "object" || Object.keys(atk).length === 0;
    // 2026-08-03(45+難易度修正): max-health-high-level-from/per-level をここに足し忘れると、
    // このフィールドだけ入力して保存したときに「空扱いされてブロックごと消える」事故になる
    // (現に max-health-growth 系はこの理由で既にチェック対象、同じ罠を踏まないため追加)。
    const topEmpty = coeffs["max-health"] == null && coeffs["armor-strength"] == null
      && coeffs["max-health-growth"] == null && coeffs["max-health-growth-interval"] == null
      && coeffs["max-health-high-level-from"] == null && coeffs["max-health-high-level-per-level"] == null;
    if (physEmpty) delete coeffs.physical;
    if (magEmpty) delete coeffs.magical;
    if (atkEmpty) delete coeffs.attack;
    if (topEmpty && !coeffs.physical && !coeffs.magical && !coeffs.attack) delete host[key];
  }

  // 2026-08-02: dimensions.<ENV> が空オブジェクト({})になったエントリを刈る(値を入力→全部消した
  // ときの掃除)。dimensions 自体が未タッチ(working.dimensions が undefined)なら何もしない —
  // 「開いて保存しただけで dimensions: {} が4環境ぶんの base-level:0 で埋まる」事故を防ぐのはUI側の
  // touchEnv 遅延実体化が主だが、掃除側でも空エントリだけは必ず削る(どちらか片方が壊れても事故らない
  // 二重の安全策)。
  function pruneEmptyDimensions(host) {
    if (!host || typeof host !== "object") return;
    const dims = host.dimensions;
    if (!dims || typeof dims !== "object" || Array.isArray(dims)) return;
    for (const key of Object.keys(dims)) {
      const entry = dims[key];
      if (!entry || typeof entry !== "object" || Array.isArray(entry) || Object.keys(entry).length === 0) {
        delete dims[key];
      }
    }
  }

  // Node テスト向けに純関数を公開 (tf-lifestyle-forms.js の window.DROP_TABLE_LOGIC と同じ流儀)。
  // ブラウザ実行時の挙動には影響しない (window.buildMobTypesForm は従来どおり別途公開)。
  // expRampValue は下方(mob-overrides の vanilla-exp ブロック)で宣言されるが、関数宣言の巻き上げが
  // 効くのでここで公開できる(pruneEmptyMobSelections と同じ流儀)。
  // buildLevelCutoffBlock(レベル差による足きり)は 2026-08-09 に撤去した。設定が
  // combat/mob-overrides.yml から combat/damage.yml へ移り、共通変数タブのスカラー欄
  // (lib/constants.js の level-cutoff.*)になったため、専用フォームが不要になった。
  window.MOB_FORMS_LOGIC = {
    pruneEmptyScalingBlock, pruneEmptyMobSelections, expRampValue,
    pruneEmptyNoSkillExpMobs, pruneEmptyDimensions
  };

  // --------------------------------------------------------------------------------------------
  // combat/mob-level-table.yml (tf-mob-level-table) 専用フォーム。
  //   dungeon-only: bool
  //   tiers[]: { min-level, remove-drops[], add-drops[]{material,chance,min,max}, vanilla-exp }
  // mob-types.yml と同じ「往復ロスレス最優先」方針: working を直接編集し、未編集キーは温存する。
  // --------------------------------------------------------------------------------------------

  function buildAddDropRow(drop, onRemove) {
    // buildDropRow(mob-types.yml向け)は quality フィールドを持つが、レベルテーブルの add-drops は
    // material/chance/min/max(+2026-07-25で mobs)のみ読む(Javaパーサが quality を見ない)ため、
    // 混乱を避けて専用に組む。
    // 2026-07-25 §2-B: material は Material名 または custom:<items/catalog.ymlのID> を受け付ける
    // (LevelTierDropEntry/MobLevelTableConfig 側で custom: プレフィクスを解釈)。fish-sell.prices や
    // ドロップテーブル entries[].item と同じ window.materialInput({ allowCustom: true }) に統一する。
    // 2026-08-02: materialHintEl は削除 (materialInput 自身が既に日本語名を表示するため、
    // 隣に足すと同じ名前が2回出て行が潰れる。実サーバ報告の直接該当箇所)。
    const matInput = window.materialInput(drop.material, "material-list", (v) => {
      drop.material = v;
    }, { allowCustom: true });
    const chanceInput = window.numberInput(drop.chance, (v) => { drop.chance = v == null ? 0 : v; }, { int: false });
    const minInput = window.numberInput(drop.min, (v) => { drop.min = v == null ? 0 : v; }, { int: true });
    const maxInput = window.numberInput(drop.max, (v) => { drop.max = v == null ? 0 : v; }, { int: true });

    return h("div", { class: "mob-drop-row" }, [
      // .field-grid と対象モブブロックは縦に積む(横並びにすると両方が潰れて崩れる)。
      h("div", { class: "mob-drop-body" }, [
        h("div", { class: "field-grid" }, [
          fieldRow("material", matInput, {
            label: "素材(Material/custom:)",
            desc: "この帯だけで追加ドロップするアイテム。Material名 または custom:<カタログID>。"
          }),
          fieldRow("chance", chanceInput, { label: "確率(0〜1)", desc: "1死亡あたりのドロップ確率。" }),
          fieldRow("min", minInput, { label: "個数(最小)", desc: "ドロップ個数の下限。0以上の整数。" }),
          fieldRow("max", maxInput, { label: "個数(最大)", desc: "ドロップ個数の上限。0以上の整数、min以上。" })
        ]),
        buildTargetFilterSection(drop, "このドロップの対象モブ", "省略時はこの帯の全モブに適用。")
      ]),
      h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: onRemove })
    ]);
  }

  // 2026-07-26: 対象モブ絞り込み(mobs = EntityType / mob-ids = EliteMobsモブid)の共通UI。
  // 帯(tier)そのものと add-drops の各行で同じ意味を持つため1つにまとめる。
  // 【なぜ mob-ids が要るか】ダンジョンは1ダンジョンまるごと同じ EntityType(見た目替えのZOMBIE等)
  // であることが多く、mobs: だけでは個体を狙えなかった。
  function buildTargetFilterSection(host, title, scopeHint) {
    return h("div", { class: "mob-drops-section" }, [
      window.fieldLabelEl("mobs", {
        label: `${title} — 種類 (mobs、省略可)`,
        desc: `${scopeHint}指定時はここに列挙したEntityTypeのキルだけに絞り込む。`
      }),
      buildAddDropMobsBox(host),
      window.fieldLabelEl("mob-ids", {
        label: `${title} — モブid (mob-ids、省略可)`,
        desc: "EliteMobsのカスタムボスid(mob-overridesと同じid)で絞り込む。"
          + "mobs と両方指定した場合は両方を満たすモブだけが対象(AND)。"
      }),
      buildMobIdsBox(host)
    ]);
  }

  // mob-ids の編集ボックス。候補は既定ダンジョン台帳(EM_DUNGEONS)から全ダンジョン横断で出し、
  // 台帳に無いid(自作モブ)も手入力できるようにする。
  function buildMobIdsBox(host) {
    const box = h("div", { class: "mob-drops-box" });
    function render() {
      box.innerHTML = "";
      const list = Array.isArray(host["mob-ids"]) ? host["mob-ids"] : [];
      if (list.length === 0) {
        box.appendChild(h("div", { class: "empty-guide-hint", text: "未指定(モブidでは絞り込まない)。" }));
      }
      list.forEach((mobId, idx) => {
        const select = window.listSelect({
          value: mobId == null ? "" : String(mobId),
          placeholder: "モブidを選択…",
          allowCustom: true,
          customPlaceholder: "モブidを直接入力",
          options: () => (window.EM_DUNGEONS ? window.EM_DUNGEONS.mobOptions(null) : []),
          onChange: (v) => { list[idx] = String(v || "").trim(); }
        });
        box.appendChild(h("div", { class: "mob-drop-row" }, [
          h("div", { class: "input-with-hint" }, [select]),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { list.splice(idx, 1); if (list.length === 0) delete host["mob-ids"]; render(); }
          })
        ]));
      });
      box.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small", type: "button", text: "+ モブidを追加",
          onclick: () => {
            if (!Array.isArray(host["mob-ids"])) host["mob-ids"] = [];
            host["mob-ids"].push("");
            render();
          }
        })
      ]));
    }
    render();
    if (window.EM_DUNGEONS && !window.EM_DUNGEONS.isLoaded()) {
      window.EM_DUNGEONS.load().then(() => render());
    }
    return box;
  }

  // 2026-07-25 §2-A/§2-C: add-drops エントリごとの mobs: (EntityType一覧) フィルタ編集UI。
  // buildRemoveDropsBox と同じ「行の配列を add/remove するボックス」の流儀を踏襲し、
  // 候補は window.VANILLA_MOBS(entity-type-list datalist、mob-types.yml と共有)から出す。
  function buildAddDropMobsBox(drop) {
    const box = h("div", { class: "mob-drops-box" });
    function render() {
      box.innerHTML = "";
      const list = Array.isArray(drop.mobs) ? drop.mobs : [];
      if (list.length === 0) {
        box.appendChild(h("div", { class: "empty-guide-hint", text: "未指定(全モブに適用)。" }));
      }
      list.forEach((mob, idx) => {
        const mobInput = window.mobTypeSelect(mob, (v) => {
          list[idx] = String(v || "").trim().toUpperCase().replace(/[^A-Z0-9_]/g, "");
        });
        box.appendChild(h("div", { class: "mob-drop-row" }, [
          h("div", { class: "input-with-hint" }, [mobInput]),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { list.splice(idx, 1); if (list.length === 0) delete drop.mobs; render(); }
          })
        ]));
      });
      box.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small", type: "button", text: "+ 対象モブを追加",
          onclick: () => {
            if (!Array.isArray(drop.mobs)) drop.mobs = [];
            drop.mobs.push("ZOMBIE");
            render();
          }
        })
      ]));
    }
    render();
    return box;
  }

  // 2026-07-27 牧場対策: no-skill-exp-mobs (トップレベル、帯システムとは独立)。バニラEXPオーブは
  // 対象外(従来どおり落ちる) — 止めるのはTrinityForgeの戦闘スキルEXP(武器命中/防具被弾)のみ。
  // 魔法(ARS_MAGIC)は対象外: Ars側のEXPは「詠唱したこと」に対して付き、何を撃ったかを見ないため。
  // buildAddDropMobsBox と同じ「行の配列を add/remove するボックス」の流儀を working 直下に適用する。
  function buildNoSkillExpMobsBox(working) {
    const box = h("div", { class: "mob-drops-box" });
    function render() {
      box.innerHTML = "";
      const list = Array.isArray(working["no-skill-exp-mobs"]) ? working["no-skill-exp-mobs"] : [];
      if (list.length === 0) {
        box.appendChild(h("div", { class: "empty-guide-hint", text: "未指定(戦闘スキルEXP無効化の対象なし)。" }));
      }
      list.forEach((mob, idx) => {
        const mobInput = window.mobTypeSelect(mob, (v) => {
          list[idx] = String(v || "").trim().toUpperCase().replace(/[^A-Z0-9_]/g, "");
        });
        box.appendChild(h("div", { class: "mob-drop-row" }, [
          h("div", { class: "input-with-hint" }, [mobInput]),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { list.splice(idx, 1); if (list.length === 0) delete working["no-skill-exp-mobs"]; render(); }
          })
        ]));
      });
      box.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small", type: "button", text: "+ 対象モブを追加",
          onclick: () => {
            if (!Array.isArray(working["no-skill-exp-mobs"])) working["no-skill-exp-mobs"] = [];
            working["no-skill-exp-mobs"].push("BEE");
            render();
          }
        })
      ]));
    }
    render();
    return box;
  }

  function buildRemoveDropsBox(tier) {
    const box = h("div", { class: "mob-drops-box" });
    function render() {
      box.innerHTML = "";
      const list = Array.isArray(tier["remove-drops"]) ? tier["remove-drops"] : [];
      if (list.length === 0) {
        box.appendChild(h("div", { class: "empty-guide-hint", text: "削除対象はまだありません(省略時は何も削除しません)。" }));
      }
      list.forEach((mat, idx) => {
        // 2026-08-02: materialHintEl は削除 (materialInput 自身が listSelect の日本語表示名を
        // 既に出しているため、隣に足すと同じ名前が2回並んで行が崩れる)。
        const matInput = window.materialInput(mat, "material-list", (v) => {
          list[idx] = v;
        });
        box.appendChild(h("div", { class: "mob-drop-row" }, [
          h("div", { class: "input-with-hint" }, [matInput]),
          h("button", {
            class: "btn-small danger", type: "button", text: "削除",
            onclick: () => { list.splice(idx, 1); if (list.length === 0) delete tier["remove-drops"]; render(); }
          })
        ]));
      });
      box.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small", type: "button", text: "+ 削除対象を追加",
          onclick: () => {
            if (!Array.isArray(tier["remove-drops"])) tier["remove-drops"] = [];
            tier["remove-drops"].push("ROTTEN_FLESH");
            render();
          }
        })
      ]));
    }
    render();
    return box;
  }

  // 保存直前に「未選択のモブ指定」を取り除く純関数。対象は帯(tier)そのものと、その配下の
  // add-drops[] の両方の `mobs` / `mob-ids`。空文字の未選択行を捨て、結果が空になったキーごと消す。
  // mob-forms-logic.test.js の MOB_FORMS_LOGIC と同じ流儀でNodeテストから直接呼べるよう公開する
  // (テスト必須11: エディタでモブが選べる/未選択に戻せることの回帰確認)。
  //
  // 2026-07-26 改名: 旧名 pruneEmptyAddDropsMobs。2026-07-25 の初版は add-drops[].mobs だけが対象
  // だったが、その後 帯レベルの mobs/mob-ids と mob-ids 全般へ守備範囲が広がったため、名前が実態より
  // 狭いままになっていた(「add-drops の中しか刈らない」と読めてしまう)。
  /**
   * 配列の「空要素刈り取り + 正規化」を**同じ配列オブジェクトのまま**行う。
   *
   * ここを `arr = arr.map().filter()` で書くと**配列の同一性が壊れる**。行エディタ
   * (buildAddDropMobsBox / buildNoSkillExpMobsBox 等) は描画時に `working[...]` の配列を
   * ローカル変数へ掴んでから `list[idx] = v` で書き込むため、getData の中で配列を
   * 差し替えると掴んでいた方が**孤児**になり、以後その行の編集が working に届かない。
   * getData は画面を開いた直後 (app.js syncBaseFromEditor) と beforeunload のたびに
   * 呼ばれるので、「開いてから最初の1回の編集だけが黙って消える」という形で出る
   * (未保存判定 isEditorDirty も差分を見つけられないので警告すら出ない)。2026-08-05 修正。
   *
   * @returns {number} 刈り取り後の要素数
   */
  function normalizeStringArrayInPlace(arr) {
    for (let i = arr.length - 1; i >= 0; i--) {
      const v = String(arr[i] == null ? "" : arr[i]).trim();
      if (v === "") arr.splice(i, 1);
      else arr[i] = v;
    }
    return arr.length;
  }

  function pruneEmptyMobSelections(tiers) {
    function pruneHost(host) {
      if (!host || typeof host !== "object") return;
      if (Array.isArray(host.mobs)) {
        if (normalizeStringArrayInPlace(host.mobs) === 0) delete host.mobs;
      }
      if (Array.isArray(host["mob-ids"])) {
        if (normalizeStringArrayInPlace(host["mob-ids"]) === 0) delete host["mob-ids"];
      }
    }
    for (const tier of Array.isArray(tiers) ? tiers : []) {
      if (!tier || typeof tier !== "object") continue;
      pruneHost(tier);
      for (const drop of Array.isArray(tier["add-drops"]) ? tier["add-drops"] : []) pruneHost(drop);
    }
  }

  // 2026-07-27 牧場対策: no-skill-exp-mobs(トップレベル、tiers とは独立)の空文字/空配列刈り取り。
  // pruneEmptyMobSelections と同じ流儀(未選択行を捨て、結果が空ならキーごと消す)だが、こちらは
  // working 直下の単一キーが対象なのであえて共通化せず単独の純関数として置く。
  // 配列の差し替えではなく in-place で刈る理由は normalizeStringArrayInPlace のコメント参照
  // (差し替えると buildNoSkillExpMobsBox が掴んだ配列が孤児になり、開いた直後の1回目の
  //  編集が未保存警告も出さずに消える)。
  function pruneEmptyNoSkillExpMobs(working) {
    if (!working || typeof working !== "object") return;
    if (!Array.isArray(working["no-skill-exp-mobs"])) return;
    if (normalizeStringArrayInPlace(working["no-skill-exp-mobs"]) === 0) {
      delete working["no-skill-exp-mobs"];
    }
  }

  window.buildMobLevelTableForm = function buildMobLevelTableForm(data) {
    const working = data && typeof data === "object" ? data : {};
    if (typeof working["dungeon-only"] !== "boolean") working["dungeon-only"] = false;
    if (!Array.isArray(working.tiers)) working.tiers = [];
    const tiers = working.tiers;
    const root = h("div", { class: "dedicated-form" });

    // 2026-07-29: 帯が増えると縦に延々と積まれて全体像が掴めなかったため、全カードを
    // 折りたたみ式にする。再描画をまたいで開閉状態を保つ (collapsibleCard の推奨パターン)。
    // 帯カードのキーは配列 index ではなく min-level — 帯を削除/追加すると index がずれて
    // 別の帯の開閉状態を引き継いでしまうため。
    const expandedCards = new Set();
    function collapsible(cardKey, headChildren, bodyChildren, defaultOpen) {
      if (defaultOpen && !expandedCards.has(cardKey) && !expandedCards.has("!" + cardKey)) {
        expandedCards.add(cardKey);
      }
      return window.collapsibleCard(headChildren, bodyChildren, {
        expanded: expandedCards.has(cardKey),
        onToggle: (open) => {
          if (open) { expandedCards.add(cardKey); expandedCards.delete("!" + cardKey); }
          else { expandedCards.delete(cardKey); expandedCards.add("!" + cardKey); }
        }
      });
    }

    /** 折りたたみ中でも中身が想像できるよう、ヘッダに要約を出す。 */
    function tierSummary(tier) {
      const parts = [];
      const removes = Array.isArray(tier["remove-drops"]) ? tier["remove-drops"].length : 0;
      const adds = Array.isArray(tier["add-drops"]) ? tier["add-drops"].length : 0;
      if (tier["vanilla-exp"] != null) parts.push(`EXP ${tier["vanilla-exp"]}`);
      if (removes) parts.push(`削除 ${removes}`);
      if (adds) parts.push(`追加 ${adds}`);
      const mobs = Array.isArray(tier.mobs) ? tier.mobs.length : 0;
      if (mobs) parts.push(`対象 ${mobs} 種`);
      return parts.length ? parts.join(" / ") : "設定なし";
    }

    function renderDungeonOnlyCard() {
      const toggle = h("input", { type: "checkbox" });
      toggle.checked = !!working["dungeon-only"];
      toggle.addEventListener("change", () => { working["dungeon-only"] = toggle.checked; });
      return collapsible(
        "dungeon-only",
        [
          h("span", { class: "entry-key-label", text: "適用範囲" }),
          h("span", {
            class: "entry-summary",
            text: working["dungeon-only"] ? "ダンジョン内のみ" : "全ワールド"
          })
        ],
        [
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
            text: "モブのレベル帯ごとにドロップ削除/追加/討伐時バニラEXPを設定するテーブルです。"
              + " tiers が空なら何もしません(既存挙動を完全維持)。フィールドモブ(mob-types)・"
              + "ダンジョンモブ(EliteMobs輸入)の両方に、レベル値だけを見て横断的に適用します。"
          }),
          h("label", { class: "checkbox-row", style: "display:flex;align-items:center;gap:8px;" }, [
            toggle,
            h("span", { text: "ダンジョンインスタンスワールド内でのみ適用する (dungeon-only)" })
          ])
        ]
      );
    }

    function renderTierCard(tier, idx) {
      const minLevelInput = window.numberInput(tier["min-level"], (v) => {
        tier["min-level"] = v == null ? 0 : Math.max(0, Math.floor(Number(v)) || 0);
      }, { int: true });
      const expInput = window.numberInput(tier["vanilla-exp"], (v) => {
        if (v === null || v === "") { delete tier["vanilla-exp"]; return; }
        tier["vanilla-exp"] = Math.max(0, Math.floor(Number(v)) || 0);
      }, { int: true });

      const head = [
        h("span", { class: "entry-key-label", text: `帯 #${idx + 1} (Lv${tier["min-level"] == null ? 0 : tier["min-level"]}～)` }),
        h("span", { class: "entry-summary", text: tierSummary(tier) }),
        h("div", { class: "spacer" }),
        h("button", {
          class: "btn-small danger", type: "button", text: "削除",
          onclick: () => { tiers.splice(idx, 1); render(); }
        })
      ];

      const addDropsBox = h("div", { class: "mob-drops-box" });
      function renderAddDrops() {
        addDropsBox.innerHTML = "";
        const drops = Array.isArray(tier["add-drops"]) ? tier["add-drops"] : [];
        if (drops.length === 0) {
          addDropsBox.appendChild(h("div", { class: "empty-guide-hint", text: "この帯だけの追加ドロップはまだありません。" }));
        }
        drops.forEach((d, dIdx) => {
          if (!d || typeof d !== "object") return;
          addDropsBox.appendChild(buildAddDropRow(d, () => {
            tier["add-drops"].splice(dIdx, 1);
            if (tier["add-drops"].length === 0) delete tier["add-drops"];
            renderAddDrops();
          }));
        });
        addDropsBox.appendChild(h("div", { class: "form-actions" }, [
          h("button", {
            class: "btn-small", type: "button", text: "+ 追加ドロップ",
            onclick: () => {
              if (!Array.isArray(tier["add-drops"])) tier["add-drops"] = [];
              tier["add-drops"].push({ material: "BONE", chance: 0.1, min: 1, max: 1 });
              renderAddDrops();
            }
          })
        ]));
      }
      renderAddDrops();

      const body = [
        gridRow([
          fieldRow("min-level", minLevelInput, {
            label: "帯の下限レベル (min-level)",
            desc: "この値以上、次に定義された帯の手前までがこの帯の範囲(floor lookup)。0以上の整数、重複不可。"
          }),
          fieldRow("vanilla-exp", expInput, {
            label: "討伐時バニラEXP (省略可)",
            desc: "この帯の討伐で付与するバニラEXP(EXPオーブの量)。省略時はバニラ/既定量のまま。"
          })
        ]),
        h("div", { class: "mob-drops-section" }, [
          window.fieldLabelEl("remove-drops"),
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 4px;",
            text: "この帯の討伐で、最終ドロップ一覧(バニラ既定+mob-types追加ドロップ)からMaterial名で除去します。"
          }),
          buildRemoveDropsBox(tier)
        ]),
        buildTargetFilterSection(
          tier, "この帯を適用するモブ",
          "省略時はこのレベル帯の全モブに適用(ドロップ削除・追加・バニラEXPすべて)。"
        ),
        h("div", { class: "mob-drops-section" }, [
          window.fieldLabelEl("add-drops"),
          addDropsBox
        ])
      ];
      return collapsible(`tier:${tier["min-level"] == null ? idx : tier["min-level"]}`, head, body);
    }

    function renderNoSkillExpMobsCard() {
      const list = Array.isArray(working["no-skill-exp-mobs"]) ? working["no-skill-exp-mobs"] : [];
      return collapsible(
        "no-skill-exp-mobs",
        [
          h("span", { class: "entry-key-label", text: "戦闘スキルEXP無効化 (no-skill-exp-mobs)" }),
          h("span", { class: "entry-summary", text: list.length ? `${list.length} 種` : "未設定" })
        ],
        [
          h("div", {
            class: "field-desc",
            style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
            text: "このモブを相手にしても、TrinityForgeの戦闘スキルEXP(武器=命中/防具=被弾)は"
              + "一切加算されないモブの一覧です。バニラのEXPオーブ(討伐時に落ちる経験値)"
              + "には影響しません(エンチャント等の用途を潰さないよう、従来どおり落ちます)。"
              + "上のレベル帯(tiers)やdungeon-only設定とは独立に、常に効きます。空なら何もしません。"
              + "※魔法(ARS_MAGIC)は対象外です — Ars側のEXPは「詠唱したこと」に対して付き、"
              + "何を撃ったかを見ないため、モブ指定で止める手段が構造的にありません。"
          }),
          buildNoSkillExpMobsBox(working)
        ]
      );
    }

    function render() {
      root.innerHTML = "";
      root.appendChild(renderDungeonOnlyCard());
      root.appendChild(renderNoSkillExpMobsCard());
      if (tiers.length === 0) {
        root.appendChild(emptyGuide(
          "レベル帯がまだありません。",
          "「+ 帯を追加」でレベル帯ごとのドロップ削除/追加/討伐時バニラEXPを定義します(未定義なら現在の挙動を維持)。"
        ));
      }
      tiers.forEach((tier, idx) => {
        if (!tier || typeof tier !== "object") return;
        root.appendChild(renderTierCard(tier, idx));
      });
      root.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn", type: "button", text: "+ 帯を追加",
          onclick: () => {
            const usedLevels = tiers.map((t) => (t && typeof t === "object" ? t["min-level"] : null));
            let nextLevel = 0;
            while (usedLevels.includes(nextLevel)) nextLevel += 10;
            tiers.push({ "min-level": nextLevel });
            tiers.sort((a, b) => (a["min-level"] || 0) - (b["min-level"] || 0));
            render();
          }
        })
      ]));
    }

    render();
    // 2026-07-25 §2-C: material の custom:<id> サジェスト候補読み込み(tf-phase3-forms.js と同じ流儀)。
    if (window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
      window.RECIPES_UI.ensureCustomDatalist();
    }
    return {
      element: root,
      getData: () => {
        // 空行(min-level未入力のまま追加だけされた帯)は保存対象から除外しない — min-level は
        // ボタン押下時に既定値0が必ず入るため、未入力の孤児行は発生しない。
        // mobs: [] (全削除後の空配列)は back-compat の「未指定」と等価だが、working を直接編集する
        // 方針上ここで明示的に delete しておく(保存YAMLに空配列を残さない)。
        pruneEmptyMobSelections(tiers);
        pruneEmptyNoSkillExpMobs(working);
        return working;
      }
    };
  };

  // --------------------------------------------------------------------------------------------
  // combat/mob-overrides.yml (tf-mob-overrides) 専用フォーム。2026-07-26新設。
  //   overrides.<ワールド名|default>.mobs.<モブid>:
  //     stats: { level, max-health, armor-strength, physical{...}, magical{...}, attack{...} }
  //     drops: [{ item, chance, min, max }]
  // mob-types.yml/mob-level-table.ymlと同じ「往復ロスレス最優先」方針: working を直接編集し、
  // 未編集キーは温存する。physical/magical/attack のキー体系は combat/mob-profiles.yml と揃えるため
  // buildDefenseBlock/buildAttackBlock(PHYS_MAGIC_FIELDS/ATTACK_FIELDS)をそのまま再利用する。
  // --------------------------------------------------------------------------------------------

  // mob-overrides の drops で使うカタログ候補 ([{id, displayName, material, cmd, tab}])。
  // buildMobOverridesForm が受け取った値をここに置き、各ドロップ行から参照する
  // (行→ボックス→モブ→スコープ→フォームと5段引き回すより素直)。
  let dropCatalogCandidates = [];

  const CUSTOM_PREFIX = "custom:";

  /**
   * drops の item 欄。Material名 と custom:<カタログID> の2書式を、種別セレクトで
   * 切り替えて入力する (2026-07-26 「カタログのものもセレクトメニューUIで追加できるように」)。
   * カタログ側は既存の catalogItemSuggest (ID/表示名で検索できるセレクト) を再利用する。
   */
  function buildDropItemControl(drop, onModeChange) {
    const isCustom = typeof drop.item === "string" && drop.item.startsWith(CUSTOM_PREFIX);
    const kindSelect = window.listSelect({
      value: isCustom ? "custom" : "material",
      options: [
        { value: "material", primary: "Material(バニラ)", secondary: "例: BONE" },
        { value: "custom", primary: "カタログ / Ars素材", secondary: "custom:<ID>" }
      ],
      className: "drop-item-kind",
      onChange: (v) => {
        // 書式が変わるので値は持ち越さない(Material名をIDとして解釈すると解決不能になる)。
        drop.item = v === "custom" ? CUSTOM_PREFIX : "BONE";
        onModeChange();
      }
    });

    let valueControl;
    if (isCustom) {
      const currentId = drop.item.slice(CUSTOM_PREFIX.length);
      valueControl = (typeof window.catalogItemSuggest === "function" && dropCatalogCandidates.length)
        ? window.catalogItemSuggest(currentId, dropCatalogCandidates, (c) => {
          if (c) drop.item = CUSTOM_PREFIX + c.id;
        }, { placeholder: "カタログID / 表示名で検索" })
        // 候補が取れないときは自由入力に退避する(選べないより入力できるほうがまし)。
        : window.textInput(currentId, (v) => { drop.item = CUSTOM_PREFIX + String(v || "").trim(); });
    } else {
      // 2026-08-02: materialHintEl は削除 (materialInput 自身が listSelect の日本語表示名を
      // 既に出しているため、隣に足すと同じ名前が2回並んで行が崩れる)。
      const itemInput = window.materialInput(drop.item, "material-list", (v) => {
        drop.item = v;
      }, { allowCustom: true });
      valueControl = h("div", { class: "input-with-hint" }, [itemInput]);
    }
    return h("div", { class: "drop-item-control" }, [kindSelect, valueControl]);
  }

  function buildOverrideDropRow(drop, onRemove, onModeChange) {
    const chanceInput = window.numberInput(drop.chance, (v) => { drop.chance = v == null ? 0 : v; }, { int: false });
    const minInput = window.numberInput(drop.min, (v) => { drop.min = v == null ? 0 : v; }, { int: true });
    const maxInput = window.numberInput(drop.max, (v) => { drop.max = v == null ? 0 : v; }, { int: true });

    return h("div", { class: "mob-drop-row" }, [
      h("div", { class: "field-grid" }, [
        fieldRow("item", buildDropItemControl(drop, onModeChange), {
          label: "アイテム(Material/カタログ)",
          desc: "このモブを倒したときに追加で出るアイテム。種別で Material(バニラ) と"
            + " カタログ/Ars素材 を切り替えます。"
        }),
        fieldRow("chance", chanceInput, { label: "確率(0〜1)", desc: "1死亡あたりのドロップ確率。" }),
        fieldRow("min", minInput, { label: "個数(最小)", desc: "ドロップ個数の下限。0以上の整数。" }),
        fieldRow("max", maxInput, { label: "個数(最大)", desc: "ドロップ個数の上限。0以上の整数、min以上。" })
      ]),
      h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: onRemove })
    ]);
  }

  function buildOverrideDropsBox(mobEntry) {
    const box = h("div", { class: "mob-drops-box" });
    function render() {
      box.innerHTML = "";
      const list = Array.isArray(mobEntry.drops) ? mobEntry.drops : [];
      if (list.length === 0) {
        box.appendChild(h("div", {
          class: "empty-guide-hint",
          text: "ドロップ未設定(このスコープの独自ドロップなし。mob-level-table.yml等の他のドロップ源はそのまま有効)。"
        }));
      }
      list.forEach((d, idx) => {
        if (!d || typeof d !== "object") return;
        box.appendChild(buildOverrideDropRow(d, () => {
          mobEntry.drops.splice(idx, 1);
          if (mobEntry.drops.length === 0) delete mobEntry.drops;
          render();
        }, render));
      });
      box.appendChild(h("div", { class: "form-actions" }, [
        h("button", {
          class: "btn-small", type: "button", text: "+ ドロップを追加",
          onclick: () => {
            if (!Array.isArray(mobEntry.drops)) mobEntry.drops = [];
            mobEntry.drops.push({ item: "BONE", chance: 0.1, min: 1, max: 1 });
            render();
          }
        })
      ]));
    }
    render();
    return box;
  }

  function buildOverrideStatsBlock(mobEntry) {
    const hasStats = mobEntry.stats && typeof mobEntry.stats === "object" && !Array.isArray(mobEntry.stats);
    const stats = hasStats ? mobEntry.stats : {};
    function touchStats() {
      if (!mobEntry.stats || typeof mobEntry.stats !== "object") mobEntry.stats = stats;
      return mobEntry.stats;
    }
    const phys = (stats.physical && typeof stats.physical === "object" && !Array.isArray(stats.physical))
      ? stats.physical : {};
    const mag = (stats.magical && typeof stats.magical === "object" && !Array.isArray(stats.magical))
      ? stats.magical : {};
    const atk = (stats.attack && typeof stats.attack === "object" && !Array.isArray(stats.attack))
      ? stats.attack : {};
    function touchPhys() {
      const s = touchStats();
      if (!s.physical || typeof s.physical !== "object") s.physical = phys;
      return s.physical;
    }
    function touchMag() {
      const s = touchStats();
      if (!s.magical || typeof s.magical !== "object") s.magical = mag;
      return s.magical;
    }
    function touchAtk() {
      const s = touchStats();
      if (!s.attack || typeof s.attack !== "object") s.attack = atk;
      return s.attack;
    }

    const levelInput = window.numberInput(stats.level, (v) => {
      const s = touchStats();
      if (v === null || v === "") { delete s.level; return; }
      s.level = Math.max(0, Math.floor(Number(v)) || 0);
    }, { int: true });
    const maxHealthInput = window.numberInput(stats["max-health"], (v) => {
      const s = touchStats();
      if (v === null || v === "") { delete s["max-health"]; return; }
      s["max-health"] = v;
    }, { int: false });
    const armorStrengthInput = mobValueInput("armor-strength", stats["armor-strength"], (v) => {
      const s = touchStats();
      if (v === null || v === "") { delete s["armor-strength"]; return; }
      s["armor-strength"] = v;
    });

    function buildLazyDefenseBlock(title, obj, touch) {
      const fields = PHYS_MAGIC_FIELDS.map((key) => {
        const input = mobValueInput(key, obj[key], (v) => {
          const target = touch();
          if (v === null || v === "") { delete target[key]; return; }
          target[key] = v;
        });
        return fieldRow(key, input);
      });
      return h("div", { class: "mob-defense-block" }, [subTitle(title), gridRow(fields)]);
    }
    function buildLazyAttackBlock(title, obj, touch) {
      const fields = ATTACK_FIELDS.map((key) => {
        const input = mobValueInput(key, obj[key], (v) => {
          const target = touch();
          if (v === null || v === "") { delete target[key]; return; }
          target[key] = v;
        });
        return fieldRow(key, input);
      });
      return h("div", { class: "mob-attack-block" }, [subTitle(title), gridRow(fields)]);
    }

    return h("div", { class: "mob-level-coeff-block" }, [
      h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: "省略した項目は combat/mob-profiles.yml (または下位スコープ) の値のまま維持されます(項目単位マージ)。"
      }),
      gridRow([
        fieldRow("level", levelInput, { label: "戦闘レベル (省略可)" }),
        fieldRow("max-health", maxHealthInput, { label: "最大HP (省略可)" }),
        fieldRow("armor-strength", armorStrengthInput, {
          label: "防具強度 (省略可)", desc: "physical/magical両方に同じ値が適用されます。"
        })
      ]),
      buildLazyDefenseBlock("物理防御 (physical、省略可)", phys, touchPhys),
      buildLazyDefenseBlock("魔法防御 (magical、省略可)", mag, touchMag),
      buildLazyAttackBlock("攻撃 (attack、省略可)", atk, touchAtk)
    ]);
  }

  // vanilla-exp (モブごとのレベル依存EXP式、2026-07-26)。
  //   exp(level) = (base + per-level * level) * growth^(level / growth-interval)
  // yml上は「数値単体(レベル非依存の固定量)」も許されるので、往復ロスレス方針に従い
  // 「ユーザーが実際に編集するまでスカラーのまま温存する」(touchExp が初めて呼ばれた時点で
  // { base: <元の数値> } のマップに昇格させる)。
  const EXP_RAMP_FIELDS = [
    { key: "base", label: "base (基準値)", desc: "レベル0での値。" },
    { key: "per-level", label: "per-level (レベル毎)", desc: "1レベルあたりの線形加算。" },
    { key: "growth", label: "growth (指数の底)", desc: "1.0(既定)なら純粋な線形。" },
    { key: "growth-interval", label: "growth-interval (指数の間隔)", desc: "growth^(level / この値)。" }
  ];
  const EXP_PREVIEW_LEVELS = [1, 25, 50, 75, 100];

  function expRampValue(ramp, level) {
    const base = Number(ramp.base) || 0;
    const perLevel = Number(ramp["per-level"]) || 0;
    let growth = ramp.growth === undefined || ramp.growth === null ? 1 : Number(ramp.growth);
    let interval = ramp["growth-interval"] === undefined || ramp["growth-interval"] === null
      ? 1 : Number(ramp["growth-interval"]);
    // Java側 ConversionPolicy.Ramp のコンストラクタと同じ正規化(不正値は中立値に落とす)。
    if (!Number.isFinite(interval) || !(interval > 0)) interval = 1;
    if (!Number.isFinite(growth) || growth < 0) growth = 1;
    const lvl = Math.max(0, level);
    const linear = base + perLevel * lvl;
    const raw = growth === 1 ? linear : linear * Math.pow(growth, lvl / interval);
    if (!Number.isFinite(raw) || raw <= 0) return 0;
    return Math.round(raw);
  }

  function buildOverrideExpBlock(mobEntry) {
    const raw = mobEntry["vanilla-exp"];
    const isRampObj = raw && typeof raw === "object" && !Array.isArray(raw);
    // 表示用の値。スカラーは base として見せる(実体は編集されるまでスカラーのまま)。
    const view = isRampObj ? raw : (typeof raw === "number" ? { base: raw } : {});
    function touchExp() {
      const cur = mobEntry["vanilla-exp"];
      if (cur && typeof cur === "object" && !Array.isArray(cur)) return cur;
      mobEntry["vanilla-exp"] = typeof cur === "number" ? { base: cur } : {};
      return mobEntry["vanilla-exp"];
    }
    function dropIfEmpty() {
      const cur = mobEntry["vanilla-exp"];
      if (cur && typeof cur === "object" && !Array.isArray(cur) && Object.keys(cur).length === 0) {
        delete mobEntry["vanilla-exp"];
      }
    }

    const preview = h("div", {
      class: "field-desc",
      style: "font-size:11px;color:var(--muted,#6b7280);margin:6px 0 0;"
    });
    function refreshPreview() {
      const cur = mobEntry["vanilla-exp"];
      const ramp = (cur && typeof cur === "object" && !Array.isArray(cur))
        ? cur
        : (typeof cur === "number" ? { base: cur } : null);
      preview.textContent = ramp === null
        ? "EXP式 未設定 — このモブのEXPはバニラ/他の指定(mob-level-table.yml のレベル帯 vanilla-exp 等)のまま。"
        : "プレビュー: " + EXP_PREVIEW_LEVELS
          .map((lv) => `Lv${lv} → ${expRampValue(ramp, lv)} EXP`).join(" / ");
    }

    const fields = EXP_RAMP_FIELDS.map(({ key, label, desc }) => {
      const input = window.numberInput(view[key], (v) => {
        const target = touchExp();
        if (v === null || v === "") delete target[key];
        else target[key] = v;
        dropIfEmpty();
        refreshPreview();
      }, { int: false });
      return fieldRow(key, input, { label, desc });
    });

    const clearBtn = h("button", {
      class: "btn-small danger", type: "button", text: "EXP式を消す(未設定に戻す)",
      onclick: () => {
        delete mobEntry["vanilla-exp"];
        EXP_RAMP_FIELDS.forEach(({ key }) => { view[key] = undefined; });
        // 入力欄の表示も空に戻す
        fields.forEach((row) => {
          row.querySelectorAll("input").forEach((el) => { el.value = ""; });
        });
        refreshPreview();
      }
    });

    refreshPreview();
    return h("div", { class: "mob-level-coeff-block" }, [
      h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: "このモブを倒したときのバニラ経験値を、戦闘レベルの式で決めます。"
          + " exp(level) = (base + per-level × level) × growth^(level ÷ growth-interval)。"
          + " 全欄を空にすると「未設定」(EXPに一切干渉しない)。※絶対値ではなく式なのは、ダンジョンモブの"
          + "多くが level: dynamic(入場時に選んだレベルに追従)で、固定値だとLv1でもLv100でも同じEXPに"
          + "なってしまうためです。"
      }),
      gridRow(fields),
      preview,
      h("div", { class: "form-actions" }, [clearBtn])
    ]);
  }

  // abilities (特殊攻撃、2026-07-31)。値は combat/mob-abilities.yml のテンプレートID。
  // 候補は同ファイルから読み込む(window.MOB_ABILITY_IDS)。読めなかったときは自由入力に落として
  // 「候補が出ないから設定できない」状態を作らない。
  // window.listSelect は cfg オブジェクト1個を受ける(位置引数で呼ぶと候補が出ない)。
  // 候補に無いIDも通すのは、mob-abilities.yml をまだ保存していない状態でも書けるようにするため。
  function abilityIdSelect(current, ids, onChange) {
    const cur = current == null ? "" : String(current);
    const CUSTOM = "__custom_ability__";
    // window.MOB_ABILITY_LABELS_JA(app.js の fetchMobAbilityIds が同時に作る)があれば
    // 技名(display-name)を主表示にし、テンプレートIDは副表示へ回す。無ければ従来どおり生ID。
    const labels = window.MOB_ABILITY_LABELS_JA || {};
    const ja = (id) => labels[id] || id;
    const options = (ids || []).map((id) => ({ value: id, primary: ja(id), secondary: ja(id) === id ? "" : id, title: id }));
    if (cur && !(ids || []).includes(cur)) {
      options.unshift({ value: cur, primary: ja(cur), secondary: "(mob-abilities.yml に無い)", title: cur });
    }
    options.push({ value: CUSTOM, primary: "その他(自由入力)…", secondary: "" });
    return window.listSelect({
      value: cur,
      placeholder: "選択…",
      allowCustom: true,
      customPlaceholder: "テンプレートIDを直接入力",
      customValue: CUSTOM,
      options: options,
      onCommit: (v) => { onChange(v); return true; }
    });
  }

  function buildAbilitiesBlock(host) {
    const box = h("div", { class: "card-list-body" });
    function render() {
      box.innerHTML = "";
      box.appendChild(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 4px;",
        text: "このモブが撃つ特殊攻撃。技の中身(ダメージ倍率・クールダウン・演出)は"
          + "「敵の特殊攻撃 (mob-abilities)」画面のテンプレート側にあります。"
          + "drops と同じく置換で、ワールドスコープに1件でも書くと default 側は使われません。"
      }));
      const list = Array.isArray(host.abilities) ? host.abilities : [];
      if (!list.length) {
        box.appendChild(h("div", {
          class: "field-desc",
          style: "font-size:11px;color:var(--muted,#6b7280);",
          text: "未設定(このモブは特殊攻撃を撃ちません)。"
        }));
      }
      const candidates = Array.isArray(window.MOB_ABILITY_IDS) ? window.MOB_ABILITY_IDS : [];
      list.forEach((value, index) => {
        const row = h("div", { class: "stat-row" });
        row.appendChild(abilityIdSelect(String(value), candidates, (nv) => {
          host.abilities[index] = nv;
        }));
        row.appendChild(h("button", {
          class: "btn-small danger", type: "button", text: "\u00d7",
          onclick: () => {
            host.abilities.splice(index, 1);
            if (!host.abilities.length) delete host.abilities;
            render();
          }
        }));
        box.appendChild(row);
      });
      box.appendChild(h("button", {
        class: "btn-small", type: "button", text: "+ 特殊攻撃を追加",
        onclick: () => {
          if (!Array.isArray(host.abilities)) host.abilities = [];
          host.abilities.push(candidates.length ? candidates[0] : "");
          render();
        }
      }));
    }
    render();
    return box;
  }

  // 表示名(display-name)欄。ダンジョン(スコープ)とモブで同じ意味・同じ扱いなので共通化する。
  // 空欄で保存するとキーごと消す(未設定 = EliteMobs側の名前をそのまま使う)。
  function buildDisplayNameField(host, opts) {
    const input = h("input", {
      class: "field-input", value: host["display-name"] == null ? "" : String(host["display-name"]),
      spellcheck: "false", placeholder: opts.placeholder || ""
    });
    input.addEventListener("change", (ev) => {
      const nv = ev.target.value.trim();
      if (!nv) delete host["display-name"];
      else host["display-name"] = nv;
      if (typeof opts.onChange === "function") opts.onChange(nv);
    });
    return fieldRow("display-name", input, { label: opts.label, desc: opts.desc });
  }

  // 再描画をまたいで開閉状態を保つ(collapsibleCard の推奨パターン)。
  // 396体をすべて開いたまま描くと実用にならないため、既定は全て折りたたみ。
  const openOverrideScopes = new Set();
  const openOverrideMobs = new Set();
  // openOverrideMobs のキー区切り。scopeName / mobId のどちらにも絶対に現れない文字が必要。
  // 2026-07-26: ここは元々「生の NUL バイト」がソースに直接書かれていた。挙動は同じだが、
  // grep/diff/エディタがファイル全体を binary 扱いして走査できなくなる(実際に検索が空振りした)ため、
  // エスケープ表記の定数へ切り出した。
  const OVERRIDE_MOB_KEY_SEP = "\u0000";

  function overrideMobKey(scopeName, mobId) {
    return `${scopeName}${OVERRIDE_MOB_KEY_SEP}${mobId}`;
  }

  /**
   * スコープ(ワールド)をリネームしたとき、開閉状態のキーを新しい名前へ移し替える。
   * 2026-07-26 修正: これが無いと旧名のエントリが Set に残り続け、リネーム直後に
   * そのスコープと配下モブの折りたたみ状態が一度リセットされていた(再描画で旧キーが参照されないため)。
   */
  function renameScopeUiState(oldName, newName) {
    if (openOverrideScopes.delete(oldName)) openOverrideScopes.add(newName);
    const prefix = oldName + OVERRIDE_MOB_KEY_SEP;
    for (const key of Array.from(openOverrideMobs)) {
      if (!key.startsWith(prefix)) continue;
      openOverrideMobs.delete(key);
      openOverrideMobs.add(overrideMobKey(newName, key.slice(prefix.length)));
    }
  }

  /** スコープを削除したとき、そのスコープに紐づく開閉状態も捨てる(Set の無限成長を防ぐ)。 */
  function forgetScopeUiState(scopeName) {
    openOverrideScopes.delete(scopeName);
    const prefix = scopeName + OVERRIDE_MOB_KEY_SEP;
    for (const key of Array.from(openOverrideMobs)) {
      if (key.startsWith(prefix)) openOverrideMobs.delete(key);
    }
  }

  function buildOverrideMobCard(scope, scopeName, mobId, onRemove, onRename) {
    const mobEntry = scope.mobs[mobId];
    // 既定EliteMobsダンジョンのモブidは EliteMobs 側のファイル名と一致していなければ当たらない。
    // 台帳にあるidは編集不可にして、書き換えて無効化してしまう事故を防ぐ(削除は可能)。
    const isKnown = window.EM_DUNGEONS && window.EM_DUNGEONS.isKnownMob(scopeName, mobId);
    const jaName = isKnown ? window.EM_DUNGEONS.mobName(scopeName, mobId) : "";
    const nameLabel = h("span", {
      class: "entry-key-label",
      text: mobEntry && mobEntry["display-name"] ? String(mobEntry["display-name"]) : (jaName || mobId)
    });

    let idEl;
    if (isKnown) {
      idEl = h("code", { class: "entry-key-fixed", text: mobId, title: "EliteMobs同梱モブ — idは変更できません" });
    } else {
      idEl = h("input", { class: "field-input", value: mobId, spellcheck: "false" });
      idEl.addEventListener("change", (ev) => {
        const nv = ev.target.value.trim();
        if (!nv || nv === mobId || scope.mobs[nv]) { idEl.value = mobId; return; }
        onRename(mobId, nv);
      });
    }
    const head = [
      nameLabel,
      h("span", { class: "entry-key-label", text: "モブid:" }),
      idEl,
      h("div", { class: "spacer" }),
      h("button", { class: "btn-small danger", type: "button", text: "削除", onclick: onRemove })
    ];
    const body = [
      h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: isKnown
          ? "EliteMobs同梱のモブです。idは EliteMobs のカスタムボス設定ファイル名と一致している必要が"
            + "あるため変更できません(表示名だけを変えてください)。"
          : "EliteMobsのカスタムボス設定ファイル名(拡張子なし)。/trinityforge importmobs が"
            + " combat/mob-profiles.yml のキーに使うものと同じidを指定してください。"
      }),
      gridRow([
        buildDisplayNameField(mobEntry, {
          label: "モブの表示名 (display-name)",
          desc: "GUIやログでこのモブを指す名前。空欄ならEliteMobs側の名前のまま。",
          placeholder: jaName || mobId,
          onChange: (nv) => { nameLabel.textContent = nv || jaName || mobId; }
        })
      ]),
      subTitle("強さ (stats)"),
      buildOverrideStatsBlock(mobEntry),
      subTitle("経験値の式 (vanilla-exp)"),
      buildOverrideExpBlock(mobEntry),
      subTitle("特殊攻撃 (abilities)"),
      buildAbilitiesBlock(mobEntry),
      h("div", { class: "mob-drops-section" }, [
        window.fieldLabelEl("drops"),
        h("div", {
          class: "field-desc",
          style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 4px;",
          text: "このスコープ(ワールド、または default)の drops: を書くと、下位スコープの drops: は"
            + "マージされず丸ごと置き換わります(置換、マージではない)。"
        }),
        buildOverrideDropsBox(mobEntry)
      ])
    ];
    const key = overrideMobKey(scopeName, mobId);
    return window.collapsibleCard(head, body, {
      expanded: openOverrideMobs.has(key),
      onToggle: (open) => { if (open) openOverrideMobs.add(key); else openOverrideMobs.delete(key); }
    });
  }

  function buildOverrideScopeCard(overrides, scopeName, onRemoveScope, onRenameScope) {
    const scope = overrides[scopeName];
    if (!scope.mobs || typeof scope.mobs !== "object" || Array.isArray(scope.mobs)) scope.mobs = {};

    const isDefault = scopeName === "default";
    // 既定EliteMobsダンジョンのワールド名は「設計図ワールド名」そのもの。書き換えると
    // どのインスタンスにも当たらなくなるため、台帳にあるものは編集不可にする。
    const isKnown = !isDefault && window.EM_DUNGEONS && window.EM_DUNGEONS.isKnownWorld(scopeName);
    const jaName = isKnown ? window.EM_DUNGEONS.dungeonName(scopeName) : "";
    const nameInput = h("input", {
      class: "field-input", value: scopeName, spellcheck: "false", disabled: isDefault ? "disabled" : null
    });
    if (!isDefault) {
      nameInput.addEventListener("change", (ev) => {
        const nv = ev.target.value.trim();
        if (!nv || nv === scopeName || overrides[nv]) { nameInput.value = scopeName; return; }
        onRenameScope(scopeName, nv);
      });
    }

    const mobsBox = h("div", { class: "mob-drops-box" });
    function renderMobs() {
      mobsBox.innerHTML = "";
      const ids = Object.keys(scope.mobs);
      if (ids.length === 0) {
        mobsBox.appendChild(emptyGuide("モブがまだ登録されていません。", "「+ モブを追加」でこのスコープにモブidを追加します。"));
      }
      ids.forEach((mobId) => {
        // 手書きで「mob_id:」だけ書かれた(値なし)エントリは YAML 上 null になる。以降の
        // ブロック構築が軒並み null 参照で落ちてフォーム全体が描けなくなるため、ここで {} に均す。
        if (!scope.mobs[mobId] || typeof scope.mobs[mobId] !== "object" || Array.isArray(scope.mobs[mobId])) {
          scope.mobs[mobId] = {};
        }
        mobsBox.appendChild(buildOverrideMobCard(
          scope, scopeName, mobId,
          () => { delete scope.mobs[mobId]; renderMobs(); },
          (oldId, newId) => { renameKey(scope.mobs, oldId, newId); renderMobs(); }
        ));
      });
      const actions = [];
      if (isKnown) {
        // 既定ダンジョンでは未登録のモブを台帳から選ばせる(手打ちでidを間違えると無反応になるため)。
        const picker = window.listSelect({
          value: "",
          placeholder: "台帳から選んで追加…",
          options: () => window.EM_DUNGEONS.mobOptions(scopeName).filter((o) => !scope.mobs[o.value]),
          onChange: (v) => {
            if (!v || scope.mobs[v]) return;
            scope.mobs[v] = { "display-name": window.EM_DUNGEONS.mobName(scopeName, v) };
            renderMobs();
          }
        });
        actions.push(picker);
      }
      actions.push(h("button", {
        class: "btn-small", type: "button", text: "+ モブを追加",
        onclick: () => {
          let candidate = "new_mob";
          let i = 1;
          while (scope.mobs[candidate]) candidate = `new_mob_${i++}`;
          scope.mobs[candidate] = {};
          renderMobs();
        }
      }));
      mobsBox.appendChild(h("div", { class: "form-actions" }, actions));
    }
    renderMobs();

    const mobCount = Object.keys(scope.mobs).length;
    const titleLabel = h("span", {
      class: "entry-key-label",
      text: scope["display-name"] ? String(scope["display-name"]) : (jaName || scopeName)
    });
    const head = isDefault
      ? [h("span", { class: "entry-key-label", text: "default (全ダンジョン共通フォールバック)" }),
         h("span", { class: "entry-key-sub", text: `モブ ${mobCount}体` })]
      : [
          titleLabel,
          h("span", { class: "entry-key-label", text: "ワールド:" }),
          isKnown
            ? h("code", { class: "entry-key-fixed", text: scopeName, title: "EliteMobs同梱ダンジョン — ワールド名は変更できません" })
            : nameInput,
          h("span", { class: "entry-key-sub", text: `モブ ${mobCount}体` }),
          h("div", { class: "spacer" }),
          h("button", { class: "btn-small danger", type: "button", text: "スコープごと削除", onclick: onRemoveScope })
        ];
    const body = [];
    if (!isDefault) {
      body.push(h("div", {
        class: "field-desc",
        style: "font-size:11px;color:var(--muted,#6b7280);margin:0 0 8px;",
        text: isKnown
          ? "EliteMobs同梱のダンジョンです。ワールド名は設計図ワールド名(EliteMobsのダンジョン設定 worldName:)"
            + "と一致していないと全インスタンスに当たらないため変更できません。"
          : "自分で追加したスコープです。設計図ワールド名(EliteMobsのダンジョン設定 worldName: の値)を入れてください。"
      }));
      body.push(gridRow([
        buildDisplayNameField(scope, {
          label: "ダンジョンの表示名 (display-name)",
          desc: "GUIやログでこのダンジョンを指す名前。空欄ならワールド名をそのまま使います。",
          placeholder: jaName || scopeName,
          onChange: (nv) => { titleLabel.textContent = nv || jaName || scopeName; }
        })
      ]));
    }
    body.push(mobsBox);
    return window.collapsibleCard(head, body, {
      expanded: openOverrideScopes.has(scopeName),
      onToggle: (open) => { if (open) openOverrideScopes.add(scopeName); else openOverrideScopes.delete(scopeName); }
    });
  }

  window.buildMobOverridesForm = function buildMobOverridesForm(data, options) {
    const opts = options && typeof options === "object" ? options : {};
    dropCatalogCandidates = Array.isArray(opts.catalogCandidates) ? opts.catalogCandidates : [];
    const working = data && typeof data === "object" ? data : {};
    if (!working.overrides || typeof working.overrides !== "object" || Array.isArray(working.overrides)) {
      working.overrides = {};
    }
    const overrides = working.overrides;
    const root = h("div", { class: "dedicated-form" });

    function render() {
      root.innerHTML = "";
      root.appendChild(h("div", { class: "field-desc", style: "font-size:12px;color:var(--muted,#6b7280);margin:0 0 10px;" ,
        text: "EliteMobsダンジョンインスタンス内のカスタムモブごとに、個別の「強さ」「ドロップテーブル」"
          + "「経験値の式」を設定します。combat/mob-profiles.yml(自動生成)は直接編集せず、この層をその上に"
          + "重ねます。解決優先順位: <ワールド> > default > mob-profiles.yml"
          + "(強さは項目単位マージ、ドロップとEXP式は置換)。"
          + " ワールド名には「設計図ワールド名」(EliteMobsのダンジョン設定 worldName: の値)を入れてください。"
          + "インスタンスの実ワールド名は入場のたびに末尾の連番が増える(例: em_xxx_1, _2, ...)ため、"
          + "実名を書くと1インスタンスにしか当たりません。設計図名を書けば全インスタンスに当たります。"
      }));
      const scopeNames = Object.keys(overrides);
      if (!scopeNames.includes("default")) {
        // default スコープは常に存在させる(未使用でも空のカードとして表示し、追加の起点にする)。
        overrides.default = { mobs: {} };
        scopeNames.unshift("default");
      }
      scopeNames.forEach((scopeName) => {
        if (!overrides[scopeName] || typeof overrides[scopeName] !== "object") return;
        root.appendChild(buildOverrideScopeCard(
          overrides, scopeName,
          () => { delete overrides[scopeName]; forgetScopeUiState(scopeName); render(); },
          (oldName, newName) => {
            renameKey(overrides, oldName, newName);
            // 2026-07-26: 開閉状態のキーも一緒に移す。これが無いと旧名のまま Set に残り、
            // リネーム直後にそのスコープと配下モブの折りたたみ状態が一度リセットされていた。
            renameScopeUiState(oldName, newName);
            render();
          }
        ));
      });
      const addActions = [];
      if (window.EM_DUNGEONS && window.EM_DUNGEONS.isLoaded()) {
        addActions.push(window.listSelect({
          value: "",
          placeholder: "EliteMobs同梱ダンジョンから追加…",
          options: () => window.EM_DUNGEONS.dungeonOptions().filter((o) => !overrides[o.value]),
          onChange: (v) => {
            if (!v || overrides[v]) return;
            overrides[v] = { "display-name": window.EM_DUNGEONS.dungeonName(v), mobs: {} };
            openOverrideScopes.add(v);
            render();
          }
        }));
      }
      addActions.push(h("button", {
        class: "btn", type: "button", text: "+ ワールド(ダンジョン)を追加",
        onclick: () => {
          let candidate = "new_world";
          let i = 1;
          while (overrides[candidate]) candidate = `new_world_${i++}`;
          overrides[candidate] = { mobs: {} };
          openOverrideScopes.add(candidate);
          render();
        }
      }));
      root.appendChild(h("div", { class: "form-actions" }, addActions));
    }

    render();
    // 台帳(既定ダンジョン一覧)は非同期取得。届いたら id 固定表示・日本語名・選択候補を反映するため
    // 描き直す(working を直接編集する方式なので、再描画で入力内容は失われない)。
    if (window.EM_DUNGEONS && !window.EM_DUNGEONS.isLoaded()) {
      window.EM_DUNGEONS.load().then(() => render());
    }
    if (window.RECIPES_UI && typeof window.RECIPES_UI.ensureCustomDatalist === "function") {
      window.RECIPES_UI.ensureCustomDatalist();
    }
    return {
      element: root,
      getData: () => {
        // 空スコープ(mobsが空のまま)・空モブエントリ(stats/dropsとも未設定)は保存時に削らない —
        // working を直接編集する方針上、「default だけ存在して中身が空」は有効な状態(何もしない)
        // として素直に保存する(mob-level-table.ymlのtiers:[]と同じ思想)。
        return working;
      }
    };
  };
})();
