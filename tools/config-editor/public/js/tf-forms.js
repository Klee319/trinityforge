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

  // recipes.js の enchant_book (effect-params.enchantment) 候補 datalist から参照される
  // バニラエンチャント名の一覧 (tool-enchants フォーム廃止後もこの一覧は外部で再利用される)。
  const ENCHANT_KEYS = [
    "efficiency", "unbreaking", "fortune", "luck_of_the_sea", "lure", "silk_touch",
    "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect", "looting",
    "sweeping_edge", "power", "punch", "flame", "infinity", "protection",
    "fire_protection", "blast_protection", "projectile_protection", "thorns",
    "respiration", "aqua_affinity", "depth_strider", "frost_walker", "feather_falling",
    "mending", "vanishing_curse", "binding_curse",
    // ArsPaper 追加エンチャント
    "mana_regen", "mana_boost", "soulbound", "share"
  ];
  window.ENCHANT_KEYS = ENCHANT_KEYS;

  function ensureObj(parent, key) {
    if (!parent[key] || typeof parent[key] !== "object" || Array.isArray(parent[key])) parent[key] = {};
    return parent[key];
  }

  // ---- ネストしたスカラー群 (mode/drop/ars-smithing 等) を1セクション=1カードで描画する共通部品 ----
  // obj は {key: number|boolean|string} 想定。int判定は値ごとに Number.isInteger で行い小数も温存。
  // exp-per-craft / spread は小数を取り得るので常に小数入力にする。
  // fieldOverrides: { key: { label, desc } } — グローバル辞書(FIELD_LABELS)のキーが他画面と
  // 意味衝突する場合(例: "mode" は items.yml では天候モード)に、この画面文脈だけラベルを上書きする。
  function scalarSectionBody(obj, labelOpts, fieldOverrides) {
    const body = h("div", { class: "const-body" });
    for (const key of Object.keys(obj)) {
      const override = fieldOverrides && fieldOverrides[key];
      const opts = override ? Object.assign({}, labelOpts, override) : labelOpts;
      if (typeof obj[key] === "boolean") {
        body.appendChild(h("div", { class: "form-field" }, [window.fieldLabelEl(key, opts), window.checkboxInput(obj[key], (v) => { obj[key] = v; })]));
      } else if (typeof obj[key] === "number") {
        body.appendChild(h("div", { class: "form-field" }, [window.fieldLabelEl(key, opts), window.numberInput(obj[key], (v) => { if (v === null || v === "") return; obj[key] = v; }, { int: Number.isInteger(obj[key]) && key !== "exp-per-craft" && key !== "spread" })]));
      } else {
        body.appendChild(h("div", { class: "form-field" }, [window.fieldLabelEl(key, opts), window.textInput(obj[key], (v) => { obj[key] = v; })]));
      }
    }
    return body;
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
      { key: "give-default-quality", int: true }
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

    const SKILL_LABELS = {
      "ars-smithing": "Ars鍛冶",
      "ars-magic": "Ars魔法",
      combat: "戦闘 (TF付与分)",
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
      power: "総合 (Power)",
      smithing: "鍛冶",
      woodcutting: "伐採",
      ars_magic: "Ars魔法 (曲線)",
      ars_smithing: "Ars鍛冶 (曲線)"
    };
    function skillLabel(id) {
      return SKILL_LABELS[id] || id;
    }

    root.appendChild(h("div", { class: "sub-title", text: "行動あたりの獲得EXP (skill-exp.yml)" }));
    root.appendChild(h("div", { class: "empty-hint", text:
      "ここは TF/Ars が付与するレートです。各職業の曲線は下の職業カード（skills/base/*_progression.yml）で編集します。" }));

    // dungeon-only-exp: 戦闘6スキル(重武器/軽武器/弓術/重装甲/軽装甲/ARS_MAGIC)のEXPをダンジョン限定にするか。
    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "ダンジョン限定EXP (dungeon-only-exp)" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("dungeon-only-exp", {
            label: "戦闘6スキルのEXPをダンジョン限定にする",
            desc: "6スキル(重武器/軽武器/弓術/重装甲/軽装甲/ARS_MAGIC)のEXPを EliteMobs ダンジョンのインスタンスワールド内に限定します。"
              + "オーバーワールド等では加算されません(トリガ自体は従来どおり: 武器=命中/防具=被弾/魔法=詠唱。範囲だけをゲートします)。"
              + "既定は true。false にすると従来どおりどこでも加算します。ars-smithing(クラフト)/採取/釣り等の非戦闘EXPは対象外です。"
          }),
          window.checkboxInput(working["dungeon-only-exp"] !== false, (v) => { working["dungeon-only-exp"] = v; })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("outside-dungeon-exp-rate", { hideKey: true }),
          window.numberInput(working["outside-dungeon-exp-rate"], (v) => {
            if (v === null || v === "") return;
            working["outside-dungeon-exp-rate"] = v;
          }, { int: false })
        ])
      ]
    ));

    // 曲線式のプレースホルダ / 演算子ヘルプ
    root.appendChild(h("div", { class: "entry-card se-help-card" }, [
      h("div", { class: "entry-head" }, [
        h("span", { class: "entry-key-label", text: "曲線式の書き方 (exp_level_curve)" })
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
      [h("span", { class: "entry-key-label", text: "EXP獲得表示 (exp-display)" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("mode", {
            label: "表示モード",
            desc: "bossbar(既定)＝Lv・現EXP/次Lv必要EXP・獲得量をボスバーに表示。"
              + "actionbar＝獲得量のみをアクションバーに表示。"
          }),
          window.listSelect({
            value: expDisplay.mode || "bossbar",
            options: [
              { value: "bossbar", primary: "ボスバー表示", secondary: "bossbar" },
              { value: "actionbar", primary: "アクションバー表示", secondary: "actionbar" }
            ],
            onChange: (v) => { expDisplay.mode = v; }
          })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("bossbar-seconds", {
            label: "ボスバー表示秒数",
            desc: "bossbar時、この秒数後に自動で隠す。"
          }),
          window.numberInput(expDisplay["bossbar-seconds"], (v) => {
            if (v === null || v === "") return;
            expDisplay["bossbar-seconds"] = v;
          }, { int: false })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("max-concurrent-bossbars", {
            label: "同時表示ボスバー数上限",
            desc: "複数スキルのEXPが同tickで入っても、この数を超えて重ねてボスバーを表示しない。"
          }),
          window.numberInput(expDisplay["max-concurrent-bossbars"], (v) => {
            if (v === null || v === "") return;
            expDisplay["max-concurrent-bossbars"] = v;
          }, { int: true })
        ])
      ]
    ));

    root.appendChild(card(
      [h("span", { class: "entry-key-label", text: "レベルアップ通知 (level-up)" })],
      [
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("chat", {
            label: "チャット通知",
            desc: "レベルアップ時にチャットへ通知するか。"
          }),
          window.checkboxInput(levelUp.chat !== false, (v) => { levelUp.chat = v; })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("sound-enabled", {
            label: "効果音を鳴らす",
            desc: "レベルアップ時に効果音を再生するか。"
          }),
          window.checkboxInput(levelUp["sound-enabled"] !== false, (v) => { levelUp["sound-enabled"] = v; })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("sound", {
            label: "効果音 (Bukkit Sound)",
            desc: "Bukkit の Sound 列挙値の名前 (例: ENTITY_PLAYER_LEVELUP)。"
              + "専用の音選択UIはこのエディタに存在しないためテキスト入力。"
          }),
          window.textInput(levelUp.sound, (v) => { levelUp.sound = v; })
        ]),
        h("div", { class: "form-field" }, [
          window.fieldLabelEl("title-every-levels", {
            label: "タイトル表示間隔(レベル数)",
            desc: "このレベル数の倍数に到達したとき、通常のチャット/効果音に加えて画面タイトルでも通知する。"
          }),
          window.numberInput(levelUp["title-every-levels"], (v) => {
            if (v === null || v === "") return;
            levelUp["title-every-levels"] = v;
          }, { int: true })
        ])
      ]
    ));

    // 使用可能レベル連動EXP (2026-07-28): 鍛冶(作成したツール)・伐採/採掘/切削(使用したツール)の
    // 使用可能レベルが高いほどEXP付与量を増やす。農業は対象外(per-levelに行を作らない)。
    (function () {
      const perLevel = ensureObj(useLevelScaling, "per-level");
      const PER_LEVEL_SKILLS = [
        { key: "smithing", label: "鍛冶 (作成したツール/装備の使用可能レベル)" },
        { key: "woodcutting", label: "伐採 (破壊に使ったツールの使用可能レベル)" },
        { key: "mining", label: "採掘 (破壊に使ったツールの使用可能レベル)" },
        { key: "digging", label: "切削 (破壊に使ったツールの使用可能レベル)" }
      ];
      const perLevelFields = PER_LEVEL_SKILLS.map(({ key, label }) =>
        h("div", { class: "form-field" }, [
          window.fieldLabelEl(key, { label, hideKey: true }),
          window.numberInput(perLevel[key], (v) => {
            if (v === null || v === "") return;
            perLevel[key] = v;
          }, { int: false })
        ]));

      root.appendChild(card(
        [h("span", { class: "entry-key-label", text: "使用可能レベル連動EXP (use-level-scaling)" })],
        [
          h("div", { class: "form-field" }, [
            window.fieldLabelEl("enabled", {
              label: "機能を有効にする",
              desc: "使用したツールの使用可能レベル(鍛冶は作成したツール/装備の使用可能レベル)が高いほど、"
                + "獲得EXPが増える。使用可能レベル0(素手・バニラツール・item-statsにプロファイル無し)は"
                + "常に倍率1.0=現状維持。倍率=1+使用可能レベル×per-level。対象は鍛冶/伐採/採掘/切削の4スキルのみ、"
                + "農業は対象外。爆破採掘(TNT等)にも掛からない。"
            }),
            window.checkboxInput(useLevelScaling.enabled !== false, (v) => { useLevelScaling.enabled = v; })
          ]),
          h("div", { class: "form-field" }, [
            window.fieldLabelEl("max-multiplier", {
              label: "倍率上限",
              desc: "per-levelを大きくしたときの暴走止め(安全弁)。倍率がこの値を超えることはない。既定3.0。"
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

    // "mode" 等はグローバル辞書(FIELD_LABELS)では他画面(例: items.yml の天候)向けの意味を
    // 持つため、この画面のセクションだけ文脈固有のラベルへ上書きする(2026-07-27 タスク1)。
    const SECTION_FIELD_OVERRIDES = {
      combat: {
        mode: {
          label: "命中EXP計算方式",
          desc: "flat(既定)=exp-per-hit/by-skillの固定値。damage_scaled=与ダメージ×damage-scaleに(1+モブレベル×mob-level-scale)を掛けた値。"
        }
      }
    };

    const sections = Object.keys(working).filter((k) =>
      working[k] && typeof working[k] === "object" && !Array.isArray(working[k]) && !DEDICATED_SECTION_KEYS.has(k));
    if (!sections.length) {
      root.appendChild(emptyGuide("スキルEXP設定がまだありません。",
        "skill-exp.yml に ars-smithing.exp-per-craft などを定義すると、ここで編集できます。"));
    } else {
      for (const section of sections) {
        const obj = working[section];
        const cardEl = h("details", { class: "entry-card se-skill-card", open: true });
        cardEl.appendChild(h("summary", { class: "entry-head se-skill-summary" }, [
          h("span", { class: "entry-key-label", text: skillLabel(section) }),
          h("span", { class: "cf-muted", text: section })
        ]));
        cardEl.appendChild(h("div", { class: "entry-body" },
          [scalarSectionBody(obj, { hideKey: true }, SECTION_FIELD_OVERRIDES[section])]));
        root.appendChild(cardEl);
      }
    }

    // レベル曲線 + TFが実際に消費するスカラーレートのみ編集可。
    // daily_limit / is_chunk_nerfed / 戦闘行動表などは Valhalla 遺産で TF 未配線のため出さない。
    const RATE_KEYS = [
      "alchemy_brew_exp", "fishing_catch_exp", "exp_gain",
      "exp_damage_piece", "exp_damage_piece_min_damage", "exp_damage_piece_cooldown_seconds",
      "exp_multiplier_point",
      "durability_tools_exp_multiplier_stack", "durability_armors_exp_multiplier_stack",
      "exp_multiplier_mine", "exp_multiplier_blast",
      "exp_multiplier_quality", "multiplier_manual", "multiplier_automated",
      "prestige_decay_rate"
    ];
    const curveKeys = Object.keys(curves).filter((k) => curves[k] && curves[k].experience);
    if (curveKeys.length) {
      root.appendChild(h("div", { class: "sub-title", text: "レベル曲線・獲得レート (skills/base/*_progression.yml)" }));
      root.appendChild(h("div", { class: "empty-hint", text:
        "戦闘武器の行動EXPは上の skill-exp.yml（combat.*）が権威です。ここは曲線・採取/防具/鍛冶/錬金等。" }));
      for (const skillId of curveKeys) {
        if (!curves[skillId].experience) curves[skillId].experience = {};
        const exp = curves[skillId].experience;
        const cardEl = h("details", { class: "entry-card se-skill-card", open: false });
        cardEl.appendChild(h("summary", { class: "entry-head se-skill-summary" }, [
          h("span", { class: "entry-key-label", text: skillLabel(skillId) }),
          h("span", { class: "cf-muted", text: skillId })
        ]));
        const body = h("div", { class: "entry-body" });
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
        for (const rk of RATE_KEYS) {
          if (exp[rk] === undefined || exp[rk] === null) continue;
          if (typeof exp[rk] === "object") continue;
          body.appendChild(fieldRow(rk, window.numberInput(exp[rk], (v) => { exp[rk] = v; })));
        }
        // Enchanting nested conversion rate
        if (exp.exp_gain && typeof exp.exp_gain === "object"
            && exp.exp_gain.experience_spent_conversion != null) {
          body.appendChild(fieldRow("exp_gain.experience_spent_conversion",
            window.numberInput(exp.exp_gain.experience_spent_conversion, (v) => {
              exp.exp_gain.experience_spent_conversion = v;
            })));
        }
        const chart = h("div", { class: "qd-chart" });
        body.appendChild(chart);
        function redrawChart() {
          chart.innerHTML = "";
          const svg = renderExpCurveSvg(exp.exp_level_curve || "", exp.max_level || 100);
          if (svg) chart.appendChild(svg);
          else chart.appendChild(h("div", { class: "empty-hint", text: "曲線式を評価できません" }));
        }
        redrawChart();
        cardEl.appendChild(body);
        root.appendChild(cardEl);
      }
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

    function furnaceSmeltSection(section, title, hint) {
      const sBody = h("div", { class: "const-body" });
      sBody.appendChild(h("div", { class: "form-field" }, [
        window.fieldLabelEl("percent", { label: "グローバル既定%(tier未該当時のフォールバック)", desc: hint }),
        window.numberInput(section.percent, (v) => {
          if (v == null) return;
          section.percent = Math.max(0, v);
        })
      ]));
      sBody.appendChild(h("div", { class: "sub-title", text: "tier別% (tiers) — 該当tier行があればこちらが優先" }));
      sBody.appendChild(
        typeof window.tierTableEditor === "function"
          ? window.tierTableEditor(section, [{ key: "percent", label: "%" }])
          : h("div", { class: "empty-hint", text: "tier表エディタ(tf-lifestyle-forms.js)が読み込まれていません。" })
      );
      return card([h("span", { class: "entry-key-label", text: title })], [sBody]);
    }
    root.appendChild(furnaceSmeltSection(speedSection, "精錬速度 (furnace-smelt.speed)",
      "skilltree/smithing.yml A-1/A-2/A-3 の feature:furnace-smelt-speed value(tier番号)で引く。"));
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
    }

    return {
      element: root,
      getData: () => working,
      getExtraSaves: () => hasCraftingFeatures ? [{ id: "crafting-features", data: craftingFeaturesWorking }] : []
    };
  };

})();
